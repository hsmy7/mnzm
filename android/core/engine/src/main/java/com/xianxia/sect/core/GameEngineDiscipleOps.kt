package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.engine.domain.disciple.DiscipleSlotCleanup

fun GameEngine.addDisciple(disciple: Disciple) = discipleFacade.addDisciple(disciple)
fun GameEngine.removeDisciple(discipleId: String): DomainResult<Unit> = discipleFacade.removeDisciple(discipleId)
fun GameEngine.getDiscipleById(discipleId: String): Disciple? = discipleFacade.getDiscipleById(discipleId)
fun GameEngine.updateDisciple(disciple: Disciple) = discipleFacade.updateDisciple(disciple)
fun GameEngine.getDiscipleStatus(discipleId: String): DiscipleStatus = discipleFacade.getDiscipleStatus(discipleId)
suspend fun GameEngine.syncAllDiscipleStatuses() = discipleFacade.syncAllDiscipleStatuses()
fun GameEngine.syncSingleDiscipleStatus(discipleId: String) = discipleFacade.syncSingleDiscipleStatus(discipleId)
suspend fun GameEngine.resetAllDisciplesStatus() = discipleFacade.resetAllDisciplesStatus()
fun GameEngine.recruitDisciple(): Disciple = discipleFacade.recruitDisciple()
suspend fun GameEngine.expelDisciple(discipleId: String): DomainResult<Unit> = discipleFacade.expelDisciple(discipleId)
suspend fun GameEngine.apprenticeToMaster(discipleId: String, masterId: String): DomainResult<Unit> = discipleFacade.apprenticeToMaster(discipleId, masterId)
suspend fun GameEngine.releaseReflectionDisciple(discipleId: String) = discipleFacade.releaseReflectionDisciple(discipleId)
fun GameEngine.clearPendingNotification() = discipleFacade.clearPendingNotification()
suspend fun GameEngine.equipEquipment(discipleId: String, equipmentId: String): DomainResult<Unit> = discipleFacade.equipEquipment(discipleId, equipmentId)
suspend fun GameEngine.unequipEquipment(discipleId: String, equipmentId: String): DomainResult<Unit> = discipleFacade.unequipEquipment(discipleId, equipmentId)
fun GameEngine.isDiscipleAssignedToSpiritMine(discipleId: String): Boolean = discipleFacade.isDiscipleAssignedToSpiritMine(discipleId)
suspend fun GameEngine.updateYearlySalaryEnabled(realm: Int, enabled: Boolean) = discipleFacade.updateYearlySalaryEnabled(realm, enabled)
fun GameEngine.getAliveDisciplesCount(): Int = discipleFacade.getAliveDisciplesCount()
fun GameEngine.getIdleDisciples(): List<Disciple> = discipleFacade.getIdleDisciples()
fun GameEngine.getDiscipleAggregate(discipleId: String): DiscipleAggregate? = discipleFacade.getDiscipleAggregate(discipleId)
fun GameEngine.getAllDiscipleAggregates(): List<DiscipleAggregate> = discipleFacade.getAllDiscipleAggregates()
suspend fun GameEngine.dismissDisciple(discipleId: String) = discipleFacade.dismissDisciple(discipleId)
fun GameEngine.giveItemToDisciple(discipleId: String, itemId: String, itemType: String) = discipleFacade.giveItemToDisciple(discipleId, itemId, itemType)
fun GameEngine.assignManual(discipleId: String, stackId: String) = discipleFacade.assignManual(discipleId, stackId)
fun GameEngine.removeManual(discipleId: String, instanceId: String) = discipleFacade.removeManual(discipleId, instanceId)
suspend fun GameEngine.recruitDiscipleFromList(discipleId: String): String = discipleFacade.recruitDiscipleFromList(discipleId)
suspend fun GameEngine.rewardItemsToDisciple(discipleId: String, items: List<RewardSelectedItem>) = discipleFacade.rewardItemsToDisciple(discipleId, items)
fun GameEngine.updateElderSlots(newElderSlots: ElderSlots) = discipleFacade.updateElderSlots(newElderSlots)
fun GameEngine.assignDirectDisciple(elderSlotType: String, slotIndex: Int, discipleId: String, discipleName: String, discipleRealm: String, discipleSpiritRootColor: String) = discipleFacade.assignDirectDisciple(elderSlotType, slotIndex, discipleId, discipleName, discipleRealm, discipleSpiritRootColor)
fun GameEngine.removeDirectDisciple(elderSlotType: String, slotIndex: Int) = discipleFacade.removeDirectDisciple(elderSlotType, slotIndex)
suspend fun GameEngine.updateDiscipleStatus(discipleId: String, status: DiscipleStatus) = discipleFacade.updateDiscipleStatus(discipleId, status)
fun GameEngine.assignDiscipleToLibrarySlot(slotIndex: Int, discipleId: String, discipleName: String) = discipleFacade.assignDiscipleToLibrarySlot(slotIndex, discipleId, discipleName)
fun GameEngine.removeDiscipleFromLibrarySlot(slotIndex: Int) = discipleFacade.removeDiscipleFromLibrarySlot(slotIndex)

/**
 * 原子操作：清除指定弟子在所有槽位中的引用，并将其状态重置为 IDLE。
 * 在同一 [stateStore.update] 事务中完成，保证清除 + 重置的一致性。
 * 用于"显示所有可用弟子"功能中选中非空闲弟子时的自动释放。
 *
 * 状态特殊处理：
 * - REFLECTING（思过中）：清除 reflection 字段，不加道德/忠诚（视为手动释放）
 * - REFINING（血炼中）：clearAllSlots 已移除 activeBloodRefinements，
 *   额外清理 statusData 中的 buildingId（视为血炼失败，不返还材料）
 */
suspend fun GameEngine.releaseDiscipleFromAllSlotsAtomic(discipleId: String) {
    engineContextDispatcher.withEngineContext {
        stateStore.update {
            val id = discipleId.toIntOrNull()
            if (id == null || id !in discipleTables.ids) return@update

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
                }
                else -> {
                    gameData = DiscipleSlotCleanup(assignmentGate).clearAllSlots(gameData, discipleId)
                }
            }
        }
        syncSingleDiscipleStatus(discipleId)
        // clearAllSlots 内部已调用 gate.release()，无需重复调用
    }
}
