package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.CaveExplorationStatus
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.GarrisonSlot
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.engine.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton



/**
 * 弟子槽位管理服务。
 *
 * ## 职责
 * 1. **状态重置** — 重置所有弟子的状态为 IDLE（[resetAllDisciplesStatus]）
 * 2. **槽位清理** — 从所有槽位中移除指定弟子（[clearDiscipleFromAllSlots]）
 * 3. **槽位查询** — 查询弟子是否分配了特定槽位（[isDiscipleAssignedToSpiritMine]）
 */
@Singleton
class DiscipleSlotManager @Inject constructor(
    private val stateStore: GameStateStore,
    private val productionSlotRepository: ProductionSlotRepository,
    private val discipleSlotCleanup: DiscipleSlotCleanup,
    /** 使用 Provider 打破 Hilt 循环依赖：DiscipleStatusService → DiscipleLifecycleManager →
     *  DiscipleSlotManager → DiscipleStatusService */
    private val discipleStatusServiceProvider: Provider<DiscipleStatusService>,
    private val ioDispatcher: IoDispatcher
) {
    // ==================== 槽位查询 ====================

    /**
     * Check if disciple is assigned to spirit mine
     */
    fun isDiscipleAssignedToSpiritMine(discipleId: String): Boolean {
        val data = stateStore.gameData.value
        val inMinerSlots = data.spiritMineSlots.any { it.discipleId == discipleId }
        val inDeaconSlots = data.elderSlots.spiritMineDeaconDisciples.any { it.discipleId == discipleId }
        return inMinerSlots || inDeaconSlots
    }

    // ==================== 槽位清理 ====================

    /**
     * Clear disciple from all slots and assignments
     *
     * 清理范围与 DiscipleLifecycleProcessor.clearDiscipleFromAllSlots 完整范式一致：
     * 全部槽位（含炼丹/灵田槽与进行中工作槽——弟子已被逐出，工作中断），
     * 且 Repository 清理为同步阻塞（防跨线程竞态）。
     * 事务内 state 级清理（Gate + GameData + teams）+ 同步阻塞清全部建筑
     * Repository 槽位。
     */
    fun clearDiscipleFromAllSlots(discipleId: String) {
        stateStore.update {
            discipleSlotCleanup.clearAllSlotsState(this, discipleId, includeResidence = true)
        }

        kotlinx.coroutines.runBlocking(ioDispatcher.dispatcher) {
            productionSlotRepository.getSlots()
                .filter { it.assignedDiscipleId == discipleId }
                .forEach { slot ->
                    productionSlotRepository.updateSlotByBuildingId(slot.buildingId, slot.slotIndex) { s ->
                        s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                    }
                }
        }
    }

    // ==================== 状态重置 ====================

    /**
     * Reset all disciples to IDLE status.
     * Used when resetting game state or disbanding all teams.
     */
    suspend fun resetAllDisciplesStatus() {
        val protectedIds = stateStore.updateAndReturn {
            val ids = collectProtectedIds()
            clearAllSlotsForReset(ids = ids)
            resetStatusDataForUnprotected()
            ids
        }

        discipleStatusServiceProvider.get().syncAllDiscipleStatuses()

        clearProductionSlotsForReset(protectedIds = protectedIds)
    }

    /** 收集需保护的弟子 id：反省/炼器中不重置 */
    private fun MutableGameState.collectProtectedIds(): Set<String> {
        val ids = mutableSetOf<String>()
        for (id in discipleTables.ids) {
            val status = discipleTables.statuses[id]
            if (status == DiscipleStatus.REFLECTING || status == DiscipleStatus.REFINING) {
                ids.add(id.toString())
            }
        }
        return ids
    }

    /** 清理各槽位：灵田/藏书/长老/驻地/探险队/任务 */
    private fun MutableGameState.clearAllSlotsForReset(ids: Set<String>) {
        val clearedSpiritMineSlots = gameData.spiritMineSlots.map {
            if (it.discipleId.isNotEmpty() && it.discipleId !in ids)
                it.copy(discipleId = "", discipleName = "") else it
        }

        val clearedLibrarySlots = gameData.librarySlots.map {
            if (it.discipleId.isNotEmpty() && it.discipleId !in ids)
                it.copy(discipleId = "", discipleName = "") else it
        }

        val clearedElderSlots = clearAllDisciplesFromElderSlots(gameData.elderSlots, ids)

        val clearedGarrisonSects = gameData.worldMapSects.map { sect ->
            if (sect.isPlayerSect) {
                sect.copy(
                    garrisonSlots = sect.garrisonSlots.map { slot ->
                        if (slot.discipleId.isNotEmpty() && slot.discipleId !in ids)
                            GarrisonSlot(index = slot.index)
                        else slot
                    }
                )
            } else sect
        }

        val clearedCaveTeams = gameData.caveExplorationTeams.map { team ->
            if (team.memberIds.any { it !in ids }) {
                team.copy(
                    memberIds = emptyList(),
                    memberNames = emptyList(),
                    status = CaveExplorationStatus.COMPLETED
                )
            } else team
        }

        val clearedActiveMissions = gameData.activeMissions.filter { mission ->
            mission.discipleIds.all { it in ids }
        }

        gameData = gameData.copy(
            spiritMineSlots = clearedSpiritMineSlots,
            librarySlots = clearedLibrarySlots,
            elderSlots = clearedElderSlots,
            worldMapSects = clearedGarrisonSects,
            caveExplorationTeams = clearedCaveTeams,
            activeMissions = clearedActiveMissions
        )
    }

    /** 清空非保护弟子的 statusData：状态重置由 syncAllDiscipleStatuses 兜底 */
    private fun MutableGameState.resetStatusDataForUnprotected() {
        for (id in discipleTables.ids) {
            val isAlive = discipleTables.isAlive[id] == 1
            val status = discipleTables.statuses[id]
            if (isProtectedFromStatusReset(isAlive, status)) continue
            discipleTables.statusData[id] = emptyMap()
        }
    }

    /** 清空生产仓库槽位：非保护弟子且非工作中 */
    private suspend fun clearProductionSlotsForReset(protectedIds: Set<String>) {
        val allSlots = productionSlotRepository.getSlots()
        for (slot in allSlots) {
            if (slot.assignedDiscipleId != null && slot.assignedDiscipleId !in protectedIds && !slot.isWorking) {
                productionSlotRepository.updateSlotByBuildingId(slot.buildingId, slot.slotIndex) { s ->
                    s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                }
            }
        }
    }

    // ── resetAllDisciplesStatus 拆出的辅助函数 ────────────────────────

    private fun clearAllDisciplesFromElderSlots(slots: ElderSlots, protectedIds: Set<String>): ElderSlots {
        // 各职务字段相互独立，逐一守卫清理（与原 if 链同序同条件）
        val clearedTitles = slots.copy(
            viceSectMaster = clearElderTitleIfUnprotected(slots.viceSectMaster, protectedIds),
            herbGardenElder = clearElderTitleIfUnprotected(slots.herbGardenElder, protectedIds),
            alchemyElder = clearElderTitleIfUnprotected(slots.alchemyElder, protectedIds),
            forgeElder = clearElderTitleIfUnprotected(slots.forgeElder, protectedIds),
            outerElder = clearElderTitleIfUnprotected(slots.outerElder, protectedIds),
            preachingElder = clearElderTitleIfUnprotected(slots.preachingElder, protectedIds),
            lawEnforcementElder = clearElderTitleIfUnprotected(slots.lawEnforcementElder, protectedIds),
            innerElder = clearElderTitleIfUnprotected(slots.innerElder, protectedIds),
            qingyunPreachingElder = clearElderTitleIfUnprotected(slots.qingyunPreachingElder, protectedIds)
        )

        return clearedTitles.copy(
            preachingMasters = clearedTitles.preachingMasters.filter { it.discipleId in protectedIds },
            lawEnforcementDisciples = clearedTitles.lawEnforcementDisciples.filter { it.discipleId in protectedIds },
            qingyunPreachingMasters = clearedTitles.qingyunPreachingMasters.filter { it.discipleId in protectedIds },
            herbGardenDisciples = clearedTitles.herbGardenDisciples.filter { it.discipleId in protectedIds },
            alchemyDisciples = clearedTitles.alchemyDisciples.filter { it.discipleId in protectedIds },
            forgeDisciples = clearedTitles.forgeDisciples.filter { it.discipleId in protectedIds },
            spiritMineDeaconDisciples = clearedTitles.spiritMineDeaconDisciples.filter { it.discipleId in protectedIds }
        )
    }

    /** 单个长老职务字段守卫清理：非空且不受保护时清空 */
    private fun clearElderTitleIfUnprotected(title: String, protectedIds: Set<String>): String =
        if (title.isNotEmpty() && title !in protectedIds) "" else title
}
