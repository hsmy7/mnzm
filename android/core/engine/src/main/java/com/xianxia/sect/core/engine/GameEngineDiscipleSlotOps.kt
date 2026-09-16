package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.SlotCategory
import com.xianxia.sect.core.model.SlotRef
import com.xianxia.sect.core.engine.domain.disciple.DiscipleSlotCleanup
import com.xianxia.sect.core.nativebridge.ActionIds
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import com.xianxia.sect.core.engine.domain.disciple.getAliveDisciplesCount
import com.xianxia.sect.core.engine.domain.disciple.getIdleDisciples
import com.xianxia.sect.core.engine.domain.disciple.updateYearlySalaryEnabled


fun GameEngine.clearPendingNotification() = discipleFacade.clearPendingNotification()
fun GameEngine.isDiscipleAssignedToSpiritMine(discipleId: String): Boolean = discipleFacade
    .isDiscipleAssignedToSpiritMine(discipleId)
suspend fun GameEngine.updateYearlySalaryEnabled(realm: Int,
    enabled: Boolean) = discipleFacade.updateYearlySalaryEnabled(realm, enabled)
fun GameEngine.getAliveDisciplesCount(): Int = discipleFacade.getAliveDisciplesCount()
fun GameEngine.getIdleDisciples(): List<Disciple> = discipleFacade.getIdleDisciples()
suspend fun GameEngine.dismissDisciple(discipleId: String) = discipleFacade.dismissDisciple(discipleId)

// ── 任命/卸任事务 native 转发（batch-08：亲传槽/藏经阁槽）─────────────
//
// C++ disciple_tx.h assign/unassignSlotTransaction AUTHORITATIVE 转发
//（InventoryNativeForward 同族机制：flag 门控 + 失败信封/降级 → 回退
// discipleFacade 原路径——双实现并行契约）。槽位数据写（clearAllSlots +
// 槽覆写）在 C++；Gate 注册表/状态同步/Room 回放为 Kotlin 运行态域，
// native 成功后照原序执行（[finishAssignRuntime] / release + sync）。

private fun GameEngine.trySlotTxNative(
    actionId: Int,
    paramsBuilder: JsonObjectBuilder.() -> Unit
): JsonElement? = InventoryNativeForward.tryForward(this, actionId, paramsBuilder)

/** 任命 native 成功后的运行态收尾（DiscipleFacadeImpl 原序等价）：
 *  clearAllSlots 内嵌 release（C++ 数据段不触 Gate）→ confirm →
 *  旧 occupant 释放+同步 → 新任者同步 → 生产槽 Room 回放清理。 */
private fun GameEngine.finishAssignRuntime(
    discipleId: String,
    oldOccupantId: String,
    slotRef: SlotRef
) {
    assignmentGate.release(discipleId)
    assignmentGate.confirmAssign(discipleId, slotRef)
    // 换人后释放并同步旧 occupant（不释放会使旧弟子 gate 注册与状态残留，
    // 从选择弹窗消失）
    if (oldOccupantId.isNotEmpty() && oldOccupantId != discipleId) {
        assignmentGate.release(oldOccupantId)
        syncSingleDiscipleStatus(oldOccupantId)
    }
    syncSingleDiscipleStatus(discipleId)
    // 双存储同步：事务内 clearAllSlots 清了镜像（含生产槽），
    // 必须同步清 Room 生产槽 Repository（双槽分叉根因）
    clearDiscipleFromProductionRepository(discipleId)
}

fun GameEngine.updateElderSlots(newElderSlots: ElderSlots) = discipleFacade.updateElderSlots(newElderSlots)
fun GameEngine.assignDirectDisciple(elderSlotType: String, slotIndex: Int, discipleId: String, discipleName: String,
    discipleRealm: String, discipleSpiritRootColor: String) {
    val data = trySlotTxNative(ActionIds.DISCIPLE_TX_ASSIGN_SLOT) {
        put("family", "elderDirect")
        put("elderSlotType", elderSlotType)
        put("slotIndex", slotIndex)
        put("discipleId", discipleId)
        put("discipleName", discipleName)
        put("discipleRealm", discipleRealm)
        put("spiritRootColor", discipleSpiritRootColor)
    }
    if (data?.str("assigned") == "true") {
        finishAssignRuntime(
            discipleId = discipleId,
            oldOccupantId = data.str("oldOccupantId").orEmpty(),
            slotRef = SlotRef(
                category = SlotCategory.ELDER_POSITION,
                slotType = "$elderSlotType:$slotIndex",
                slotId = "elder_${elderSlotType}_$slotIndex"
            )
        )
        return
    }
    discipleFacade.assignDirectDisciple(elderSlotType, slotIndex, discipleId, discipleName,
        discipleRealm, discipleSpiritRootColor)
}
fun GameEngine.removeDirectDisciple(elderSlotType: String,
    slotIndex: Int) {
    val data = trySlotTxNative(ActionIds.DISCIPLE_TX_UNASSIGN_SLOT) {
        put("family", "elderDirect")
        put("elderSlotType", elderSlotType)
        put("slotIndex", slotIndex)
    }
    if (data?.str("unassigned") == "true") {
        val removedId = data.str("removedDiscipleId").orEmpty()
        if (removedId.isNotEmpty()) {
            assignmentGate.release(removedId)
        }
        syncSingleDiscipleStatus(removedId)
        return
    }
    discipleFacade.removeDirectDisciple(elderSlotType, slotIndex)
}
fun GameEngine.assignDiscipleToLibrarySlot(slotIndex: Int, discipleId: String,
    discipleName: String) {
    val data = trySlotTxNative(ActionIds.DISCIPLE_TX_ASSIGN_SLOT) {
        put("family", "library")
        put("slotIndex", slotIndex)
        put("discipleId", discipleId)
        put("discipleName", discipleName)
    }
    if (data?.str("assigned") == "true") {
        finishAssignRuntime(
            discipleId = discipleId,
            oldOccupantId = data.str("oldOccupantId").orEmpty(),
            slotRef = SlotRef(
                category = SlotCategory.LIBRARY_SLOT,
                slotType = "library:$slotIndex",
                slotId = "library_$slotIndex"
            )
        )
        return
    }
    discipleFacade.assignDiscipleToLibrarySlot(slotIndex, discipleId, discipleName)
}
fun GameEngine.removeDiscipleFromLibrarySlot(slotIndex: Int) {
    val data = trySlotTxNative(ActionIds.DISCIPLE_TX_UNASSIGN_SLOT) {
        put("family", "library")
        put("slotIndex", slotIndex)
    }
    if (data?.str("unassigned") == "true") {
        val removedId = data.str("removedDiscipleId").orEmpty()
        if (removedId.isNotEmpty()) {
            assignmentGate.release(removedId)
        }
        syncSingleDiscipleStatus(removedId)
        return
    }
    discipleFacade.removeDiscipleFromLibrarySlot(slotIndex)
}

/**
 * 原子操作：清除指定弟子在所有槽位中的引用，并将其状态重置为 IDLE。
 * 在同一 [stateStore.update] 事务中完成，保证清除 + 重置的一致性。
 * 用于"显示所有可用弟子"功能中选中非空闲弟子时的自动释放。
 *
 * 状态特殊处理：
 * - REFLECTING（思过中）：清除 reflection 字段，不加道德/忠诚（视为手动释放）
 * - REFINING（血炼中）：clearAllSlots 会清除 activeBloodRefinements，
 *   额外清理 statusData 中的 buildingId（视为血炼失败，不返还材料）
 */
suspend fun GameEngine.releaseDiscipleFromAllSlotsAtomic(discipleId: String) {
    engineContextDispatcher.withEngineContext {
        // 捕获豁免（updateMirror，§2.79）：本事务写弟子协议列（statuses/statusData，
        // 通道已关闭 §2.77）+ 槽位族字段（elderSlots/librarySlots 等 §2.79 关闭）——
        // 捕获侧 presence 检测会对合法释放误报，写入经尾部 rebaselineNativeMirror
        // 全量重建基线回导 C++（释放为低频用户动作，O(状态) 一次性成本可接受）
        stateStore.updateMirror {
            val id = discipleId.toIntOrNull()
            if (id == null || id !in discipleTables.ids) return@updateMirror

            when (discipleTables.statuses[id]) {
                DiscipleStatus.REFLECTING -> {
                    val existingData = discipleTables.statusData[id]
                    discipleTables.statusData[id] = existingData - setOf(
                        "reflectionStartYear", "reflectionEndYear"
                    )
                    // 清除受保护状态标记，后续 syncSingleDiscipleStatus 会重新推导正确状态
                    discipleTables.statuses[id] = DiscipleStatus.IDLE
                }
                DiscipleStatus.REFINING -> {
                    gameData = DiscipleSlotCleanup(assignmentGate).clearAllSlots(gameData, discipleId)
                    val current = discipleTables.statusData.getOrDefault(id, emptyMap())
                    discipleTables.statusData[id] = current - "buildingId"
                    // 血炼 REFINING 是受保护状态，须显式重置为 IDLE（与上方
                    // REFLECTING 分支一致），否则事务外 syncSingleDiscipleStatus
                    // 推导仍锁定 REFINING，弟子永远无法被释放/重新分配
                    discipleTables.statuses[id] = DiscipleStatus.IDLE
                }
                else -> {
                    gameData = DiscipleSlotCleanup(assignmentGate).clearAllSlots(gameData, discipleId)
                }
            }
        }
        rebaselineNativeMirror("弟子槽位释放")
        syncSingleDiscipleStatus(discipleId)
        // clearAllSlots 内部已调用 gate.release()，无需重复调用
        // 双存储同步：事务内只清了镜像，必须同步清 Room 生产槽 Repository，
        // 否则 repo 残留占用会经月度自动重启/读档自愈复活（回归：双槽分叉审查发现）
        clearDiscipleFromProductionRepository(discipleId)
    }
}
