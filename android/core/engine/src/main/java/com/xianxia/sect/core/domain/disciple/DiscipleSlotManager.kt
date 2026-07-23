package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.DomainLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 弟子槽位管理服务。
 *
 * ## 职责
 * 1. **状态同步** — 根据所有槽位的分配情况同步弟子状态（[syncAllDiscipleStatuses]）
 * 2. **状态重置** — 重置所有弟子的状态为 IDLE（[resetAllDisciplesStatus]）
 * 3. **槽位清理** — 从所有槽位中移除指定弟子（[clearDiscipleFromAllSlots]）
 * 4. **槽位查询** — 查询弟子是否分配了特定槽位（[isDiscipleAssignedToSpiritMine]）
 */
@Singleton
class DiscipleSlotManager @Inject constructor(
    private val stateStore: GameStateStore,
    private val productionSlotRepository: ProductionSlotRepository,
    private val scopeProvider: CoroutineScopeProvider,
    private val discipleSlotCleanup: DiscipleSlotCleanup,
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

    // ==================== 状态同步 ====================

    /**
     * Sync all disciples' status based on their assignments
     */
    fun syncAllDiscipleStatuses() {
        val tables = stateStore.discipleTables

        // 前置修复：清除无效的灵脉采矿槽位（包含自己的 update 事务，在锁外执行）
        fixInvalidMiningSlots(tables)

        stateStore.update {
            // 在锁内使用最新 gameData 构建 ID 集合，避免 TOCTOU
            val data = gameData
            val lawEnforcerIds = buildLawEnforcerIds(data.elderSlots)
            val preachingIds = buildPreachingIds(data.elderSlots)
            val deaconingIds = buildDeaconingIds(data.elderSlots)
            val managingIds = buildManagingIds(data.elderSlots)
            val studyingIds = buildStudyingIds(data)
            val miningIds = buildMiningIds(data, tables)
            val garrisonIds = buildGarrisonIds(data)
            val inTeamIds = buildInTeamIds(data, teams)
            val patrollingIds = buildPatrollingIds(data)

            // 生产槽位 ID 集合（用于推导 ALCHEMY / FORGE / SPIRIT_PLANTING 状态）
            val alchemyIds = data.productionSlots
                .filter { !it.assignedDiscipleId.isNullOrEmpty() && it.buildingId == "alchemy" }
                .map { it.assignedDiscipleId!! }.toSet()
            val forgeIds = data.productionSlots
                .filter { !it.assignedDiscipleId.isNullOrEmpty() && it.buildingId == "forge" }
                .map { it.assignedDiscipleId!! }.toSet()
            val plantIds = data.productionSlots
                .filter { !it.assignedDiscipleId.isNullOrEmpty() && it.buildingId == "herbGarden" }
                .map { it.assignedDiscipleId!! }.toSet()

            for (id in discipleTables.ids) {
                val isAlive = discipleTables.isAlive[id] == 1
                val status = discipleTables.statuses[id]
                if (!isAlive) continue
                if (status == DiscipleStatus.REFLECTING) continue
                if (status == DiscipleStatus.ON_MISSION) continue
                if (status == DiscipleStatus.REFINING) continue

                val discipleId = id.toString()
                val newStatus = when {
                    garrisonIds.contains(discipleId) -> DiscipleStatus.GARRISONING
                    inTeamIds.contains(discipleId) -> DiscipleStatus.IN_TEAM
                    lawEnforcerIds.contains(discipleId) -> DiscipleStatus.LAW_ENFORCING
                    preachingIds.contains(discipleId) -> DiscipleStatus.PREACHING
                    deaconingIds.contains(discipleId) -> DiscipleStatus.DEACONING
                    managingIds.contains(discipleId) -> DiscipleStatus.MANAGING
                    studyingIds.contains(discipleId) -> DiscipleStatus.STUDYING
                    miningIds.contains(discipleId) -> DiscipleStatus.MINING
                    patrollingIds.contains(discipleId) -> DiscipleStatus.PATROLLING
                    alchemyIds.contains(discipleId) -> DiscipleStatus.ALCHEMY
                    forgeIds.contains(discipleId) -> DiscipleStatus.FORGE
                    plantIds.contains(discipleId) -> DiscipleStatus.SPIRIT_PLANTING
                    else -> DiscipleStatus.IDLE
                }

                if (status != newStatus) {
                    discipleTables.statuses[id] = newStatus
                }
            }
        }
    }

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
                discipleTables.statuses[id] = DiscipleStatus.IDLE
                discipleTables.statusData[id] = emptyMap()
            }

            ids
        }

        val allSlots = productionSlotRepository.getSlots()
        for (slot in allSlots) {
            if (slot.assignedDiscipleId != null && slot.assignedDiscipleId !in protectedIds && !slot.isWorking) {
                productionSlotRepository.updateSlotByBuildingId(slot.buildingId, slot.slotIndex) { s ->
                    s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                }
            }
        }
    }

    // ── syncAllDiscipleStatuses 拆出的槽位收集函数 ──────────────────────

    private fun buildLawEnforcerIds(elderSlots: ElderSlots): Set<String> {
        val ids = mutableSetOf<String>()
        elderSlots.lawEnforcementElder?.let { ids.add(it) }
        elderSlots.lawEnforcementDisciples.mapNotNull { it.discipleId }.forEach { ids.add(it) }
        return ids
    }

    private fun buildPreachingIds(elderSlots: ElderSlots): Set<String> {
        val ids = mutableSetOf<String>()
        elderSlots.preachingElder?.let { ids.add(it) }
        elderSlots.qingyunPreachingElder?.let { ids.add(it) }
        elderSlots.preachingMasters.mapNotNull { it.discipleId }.forEach { ids.add(it) }
        elderSlots.qingyunPreachingMasters.mapNotNull { it.discipleId }.forEach { ids.add(it) }
        return ids
    }

    private fun buildDeaconingIds(elderSlots: ElderSlots): Set<String> =
        elderSlots.spiritMineDeaconDisciples.mapNotNull { it.discipleId }.toSet()

    private fun buildManagingIds(elderSlots: ElderSlots): Set<String> {
        val ids = mutableSetOf<String>()
        elderSlots.viceSectMaster?.let { ids.add(it) }
        elderSlots.outerElder?.let { ids.add(it) }
        elderSlots.innerElder?.let { ids.add(it) }
        elderSlots.forgeElder?.let { ids.add(it) }
        elderSlots.alchemyElder?.let { ids.add(it) }
        elderSlots.herbGardenElder?.let { ids.add(it) }
        elderSlots.herbGardenDisciples.forEach { if (it.discipleId.isNotEmpty()) ids.add(it.discipleId) }
        elderSlots.alchemyDisciples.forEach { if (it.discipleId.isNotEmpty()) ids.add(it.discipleId) }
        elderSlots.forgeDisciples.forEach { if (it.discipleId.isNotEmpty()) ids.add(it.discipleId) }
        return ids
    }

    private fun buildStudyingIds(data: GameData): Set<String> =
        data.librarySlots.mapNotNull { it.discipleId.takeIf { id -> id.isNotEmpty() } }.toSet()

    private fun buildMiningIds(data: GameData, tables: DiscipleTables): Set<String> =
        data.spiritMineSlots
            .mapNotNull { it.discipleId.takeIf { id -> id.isNotEmpty() } }
            .filter { id -> tables.ids.contains(id.toInt()) }
            .toSet()

    private fun fixInvalidMiningSlots(tables: DiscipleTables) {
        val data = stateStore.gameData.value
        val hasInvalid = data.spiritMineSlots.any { slot ->
            slot.discipleId.isNotEmpty() &&
                !tables.ids.contains(slot.discipleId.toInt())
        }
        if (hasInvalid) {
            val fixed = data.spiritMineSlots.map { slot ->
                if (slot.discipleId.isNotEmpty() &&
                    !tables.ids.contains(slot.discipleId.toInt())
                ) slot.copy(discipleId = "", discipleName = "") else slot
            }
            stateStore.update { gameData = gameData.copy(spiritMineSlots = fixed) }
        }
    }

    private fun buildGarrisonIds(data: GameData): Set<String> {
        val ids = mutableSetOf<String>()
        data.worldMapSects.find { it.isPlayerSect }?.garrisonSlots
            ?.filter { it.discipleId.isNotEmpty() }
            ?.forEach { ids.add(it.discipleId) }
        data.warehouseGarrisons.filter { it.discipleId.isNotEmpty() }.forEach { ids.add(it.discipleId) }
        return ids
    }

    private fun buildInTeamIds(data: GameData, teams: List<ExplorationTeam>): Set<String> {
        val ids = mutableSetOf<String>()
        data.battleTeams.flatMap { it.slots }
            .filter { it.discipleId.isNotEmpty() }
            .forEach { ids.add(it.discipleId) }
        // 探索/洞窟队伍成员
        ids.addAll(teams
            .filter { it.status in explorationStatuses }
            .flatMap { it.memberIds })
        ids.addAll(data.caveExplorationTeams
            .filter { it.status in caveExplorationStatuses }
            .flatMap { it.memberIds })
        return ids
    }

    private fun buildPatrollingIds(data: GameData): Set<String> =
        data.patrolSlots.filter { it.discipleId.isNotEmpty() }.map { it.discipleId }.toSet()

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
