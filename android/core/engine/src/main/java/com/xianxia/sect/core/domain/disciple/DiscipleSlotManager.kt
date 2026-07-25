package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.CoroutineScopeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
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
    private val scopeProvider: CoroutineScopeProvider,
    private val discipleSlotCleanup: DiscipleSlotCleanup,
    private val discipleStatusService: DiscipleStatusService,
) {
    companion object {
        private const val TAG = "DiscipleSlotManager"
        private val explorationStatuses = setOf(
            ExplorationStatus.TRAVELING, ExplorationStatus.EXPLORING,
            ExplorationStatus.SCOUTING, ExplorationStatus.DANGER
        )
        private val caveExplorationStatuses = setOf(
            CaveExplorationStatus.TRAVELING, CaveExplorationStatus.EXPLORING
        )
    }

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
     */
    fun clearDiscipleFromAllSlots(discipleId: String) {
        stateStore.update { gameData = discipleSlotCleanup.clearAllSlots(gameData, discipleId, includeResidence = true) }

        val forgeSlots = productionSlotRepository.getSlotsByBuildingId(BUILDING_FORGE)
        for (slot in forgeSlots) {
            if (slot.assignedDiscipleId == discipleId && !slot.isWorking) {
                scopeProvider.scope.launch(Dispatchers.IO) {
                    productionSlotRepository.updateSlotByBuildingId(BUILDING_FORGE, slot.slotIndex) { s ->
                        s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                    }
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
            val ids = mutableSetOf<String>()
            for (id in discipleTables.ids) {
                val status = discipleTables.statuses[id]
                if (status == DiscipleStatus.REFLECTING || status == DiscipleStatus.REFINING) {
                    ids.add(id.toString())
                }
            }

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

            val updatedTeams = teams.map { team ->
                if (team.memberIds.any { it !in ids }) {
                    team.copy(
                        memberIds = emptyList(),
                        memberNames = emptyList(),
                        status = ExplorationStatus.COMPLETED
                    )
                } else team
            }

            gameData = gameData.copy(
                spiritMineSlots = clearedSpiritMineSlots,
                librarySlots = clearedLibrarySlots,
                elderSlots = clearedElderSlots,
                worldMapSects = clearedGarrisonSects,
                caveExplorationTeams = clearedCaveTeams,
                activeMissions = clearedActiveMissions
            )
            teams = updatedTeams

            for (id in discipleTables.ids) {
                val isAlive = discipleTables.isAlive[id] == 1
                val status = discipleTables.statuses[id]
                if (!isAlive) continue
                if (status == DiscipleStatus.REFLECTING) continue
                if (status == DiscipleStatus.REFINING) continue
                if (status == DiscipleStatus.IDLE) continue
                discipleTables.statusData[id] = emptyMap()
            }

            ids
        }

        discipleStatusService.syncAllDiscipleStatuses()

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
        var updated = slots

        if (updated.viceSectMaster.isNotEmpty() && updated.viceSectMaster !in protectedIds)
            updated = updated.copy(viceSectMaster = "")
        if (updated.herbGardenElder.isNotEmpty() && updated.herbGardenElder !in protectedIds)
            updated = updated.copy(herbGardenElder = "")
        if (updated.alchemyElder.isNotEmpty() && updated.alchemyElder !in protectedIds)
            updated = updated.copy(alchemyElder = "")
        if (updated.forgeElder.isNotEmpty() && updated.forgeElder !in protectedIds)
            updated = updated.copy(forgeElder = "")
        if (updated.outerElder.isNotEmpty() && updated.outerElder !in protectedIds)
            updated = updated.copy(outerElder = "")
        if (updated.preachingElder.isNotEmpty() && updated.preachingElder !in protectedIds)
            updated = updated.copy(preachingElder = "")
        if (updated.lawEnforcementElder.isNotEmpty() && updated.lawEnforcementElder !in protectedIds)
            updated = updated.copy(lawEnforcementElder = "")
        if (updated.innerElder.isNotEmpty() && updated.innerElder !in protectedIds)
            updated = updated.copy(innerElder = "")
        if (updated.qingyunPreachingElder.isNotEmpty() && updated.qingyunPreachingElder !in protectedIds)
            updated = updated.copy(qingyunPreachingElder = "")

        updated = updated.copy(
            preachingMasters = updated.preachingMasters.filter { it.discipleId in protectedIds },
            lawEnforcementDisciples = updated.lawEnforcementDisciples.filter { it.discipleId in protectedIds },
            qingyunPreachingMasters = updated.qingyunPreachingMasters.filter { it.discipleId in protectedIds },
            herbGardenDisciples = updated.herbGardenDisciples.filter { it.discipleId in protectedIds },
            alchemyDisciples = updated.alchemyDisciples.filter { it.discipleId in protectedIds },
            forgeDisciples = updated.forgeDisciples.filter { it.discipleId in protectedIds },
            spiritMineDeaconDisciples = updated.spiritMineDeaconDisciples.filter { it.discipleId in protectedIds }
        )

        return updated
    }
}
