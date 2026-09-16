package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.domain.disciple.DiscipleSlotCleanup
import com.xianxia.sect.core.model.SlotCategory
import com.xianxia.sect.core.model.SlotRef
import com.xianxia.sect.core.model.WarehouseGarrisonSlot
import com.xianxia.sect.core.util.DomainLog
import kotlin.coroutines.cancellation.CancellationException

// GameEngineWarehouseOps.kt — 仓库驻守 GameEngine 扩展入口
// （对照 GameEngineAtomicAssign.kt 的原子事务 + gate 模式）
//
// batch-15 native 臂：AUTHORITATIVE 稳态下 C++ 事务（appointment_tx.h
// warehouseGarrisonAssignTx——校验链 + 旧 occupant 捕获 + 全槽清理数据段 +
// 条目替换）先行；成功后 Kotlin 仅执行事务外残差（Gate 注册表 + Room 清理 +
// 状态同步，[applyWarehouseGarrisonResiduals] 与回退臂共用同一序列）。
// flag 关闭 / 桥未加载 / 失败信封 → 回退 Kotlin 原事务体（双实现并行契约）。

/**
 * 原子化分配弟子到仓库驻守槽位（按建筑实例 ID）。
 *
 * 事务内：释放目标槽旧 occupant、清理新弟子全部槽位（防同一弟子多槽位）、
 * 写入新槽位；事务成功后：释放新弟子与旧 occupant 的 gate 注册、登记新分配、
 * 同步双方状态。住所与工作共存是有意设计，不清理住所槽位。
 */
fun GameEngine.assignWarehouseGarrisonAtomic(
    buildingInstanceId: String,
    discipleId: String,
    discipleName: String,
    sectId: String
) {
    gameEngineCore.launchInScope {
        // native 臂（batch-15）：失败信封/降级 null → 回退 Kotlin 原事务体
        val receipt = tryAssignWarehouseGarrisonNative(
            buildingInstanceId, discipleId, discipleName, sectId
        )
        if (receipt != null) {
            applyWarehouseGarrisonResiduals(buildingInstanceId, discipleId, receipt.oldOccupantId)
            return@launchInScope
        }
        var oldOccupantId = ""
        stateStore.update {
            val id = discipleId.toIntOrNull()
            require(id != null && id in discipleTables.ids) { "弟子不存在: $discipleId" }
            require(discipleTables.isAlive[id] != 0) { "弟子已死亡: $discipleId" }
            // 覆写前捕获旧 occupant（事务内读取，避免快照竞态）
            oldOccupantId = gameData.warehouseGarrisons
                .find { it.buildingInstanceId == buildingInstanceId }?.discipleId.orEmpty()
            // 清理新弟子全部槽位（否则可从巡逻/长老等岗位直接拉入仓库驻守，旧槽位残留）
            gameData = DiscipleSlotCleanup(assignmentGate).clearAllSlotsDataOnly(gameData, discipleId)
            gameData = gameData.copy(
                warehouseGarrisons = gameData.warehouseGarrisons.filter {
                    it.buildingInstanceId != buildingInstanceId
                } + WarehouseGarrisonSlot(
                    buildingInstanceId = buildingInstanceId,
                    discipleId = discipleId,
                    discipleName = discipleName,
                    sectId = sectId
                )
            )
        }
        // 事务成功后才操作 gate（失败回滚时不触碰注册表）
        applyWarehouseGarrisonResiduals(buildingInstanceId, discipleId, oldOccupantId)
    }
}

/**
 * 驻守事务成功后的运行态残差（native 臂与 Kotlin 回退臂共用，batch-12
 * applyPatrolResiduals 同款提取）：gate 释放/登记 + 双存储同步 + 双方状态同步。
 */
private fun GameEngine.applyWarehouseGarrisonResiduals(
    buildingInstanceId: String,
    discipleId: String,
    oldOccupantId: String
) {
    assignmentGate.release(discipleId)
    if (oldOccupantId.isNotEmpty() && oldOccupantId != discipleId) {
        assignmentGate.release(oldOccupantId)
    }
    assignmentGate.confirmAssign(
        discipleId,
        SlotRef(SlotCategory.WAREHOUSE_GARRISON, buildingInstanceId, "warehouse_$buildingInstanceId")
    )
    // 双存储同步：清 Room 生产槽 Repository
    clearDiscipleFromProductionRepository(discipleId)
    // 同步新弟子与旧 occupant 状态（不 release/sync 会注册表/状态残留）
    @Suppress("TooGenericExceptionCaught")
    try {
        discipleFacade.syncSingleDiscipleStatus(discipleId)
        if (oldOccupantId.isNotEmpty() && oldOccupantId != discipleId) {
            discipleFacade.syncSingleDiscipleStatus(oldOccupantId)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainLog.w("GameEngine", "assignWarehouseGarrison: sync 失败", e)
    }
}

/**
 * 卸任仓库驻守（UI 入口 ProductionViewModel.removeWarehouseGarrison 迁入引擎层
 * ——w3-13 通道关闭配套 §2.79）：移除槽位条目 + gate/Repository 清理；warehouseGarrisons
 * §2.79 转关闭后，本写入为该字段唯一非 boot/回退臂稳态 Kotlin 写者，写后全量
 * 重建 native 基线回导 C++（低频用户动作，O(状态) 一次性成本可接受）。
 */
suspend fun GameEngine.removeWarehouseGarrison(buildingInstanceId: String) {
    engineContextDispatcher.withEngineContext {
        val currentDiscipleId = stateStore.gameDataSnapshot.warehouseGarrisons
            .find { it.buildingInstanceId == buildingInstanceId }?.discipleId.orEmpty()
        updateGameDataAndSync { data ->
            data.copy(
                warehouseGarrisons = data.warehouseGarrisons.filter {
                    it.buildingInstanceId != buildingInstanceId
                }
            )
        }
        if (currentDiscipleId.isNotEmpty()) {
            assignmentGate.release(currentDiscipleId)
        }
        rebaselineNativeMirror("仓库驻守卸任")
    }
}
