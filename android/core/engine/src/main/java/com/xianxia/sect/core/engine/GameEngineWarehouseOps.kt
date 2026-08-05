package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.domain.disciple.DiscipleSlotCleanup
import com.xianxia.sect.core.model.SlotCategory
import com.xianxia.sect.core.model.SlotRef
import com.xianxia.sect.core.model.WarehouseGarrisonSlot
import com.xianxia.sect.core.util.DomainLog
import kotlin.coroutines.cancellation.CancellationException

// GameEngineWarehouseOps.kt — 仓库驻守 GameEngine 扩展入口
// （对照 GameEngineAtomicAssign.kt 的原子事务 + gate 模式）

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
        var oldOccupantId = ""
        stateStore.update {
            val id = discipleId.toIntOrNull()
            require(id != null && id in discipleTables.ids) { "弟子不存在: $discipleId" }
            require(discipleTables.isAlive[id] != 0) { "弟子已死亡: $discipleId" }
            // 覆写前捕获旧 occupant（事务内读取，避免快照竞态）
            oldOccupantId = gameData.warehouseGarrisons
                .find { it.buildingInstanceId == buildingInstanceId }?.discipleId.orEmpty()
            // 清理新弟子全部槽位（回归：此前直写 GameData，勾选"显示所有弟子"
            // 可从巡逻/长老等岗位直接拉入仓库驻守，旧槽位残留）
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
        // 同步新弟子与旧 occupant 状态（回归：此前旧 occupant 从不 release/sync）
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
}
