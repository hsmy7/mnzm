package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameStateStore
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 弟子状态同步服务。
 *
 * ## 职责
 * 1. **[syncAllDiscipleStatuses]** — 根据所有槽位分配推导并同步弟子状态
 * 2. **[resetAllDisciplesStatus]** — 重置所有弟子为 IDLE 状态（清除槽位）
 */
@Singleton
class DiscipleStatusService @Inject constructor(
    private val stateStore: GameStateStore,
    private val discipleLifecycleManager: DiscipleLifecycleManager
) {
    companion object {
        private val explorationStatuses = setOf(
            ExplorationStatus.TRAVELING, ExplorationStatus.EXPLORING,
            ExplorationStatus.SCOUTING, ExplorationStatus.DANGER
        )
        private val caveExplorationStatuses = setOf(
            CaveExplorationStatus.TRAVELING, CaveExplorationStatus.EXPLORING
        )
    }

    private val currentDiscipleTables: DiscipleTables
        get() = stateStore.discipleTables

    // ── 槽位收集函数 ──────────────────────────────────

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

    private fun fixInvalidMiningSlots(data: GameData, tables: DiscipleTables) {
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

    private fun buildInTeamIds(data: GameData): MutableSet<String> {
        val ids = mutableSetOf<String>()
        data.battleTeams.flatMap { it.slots }
            .filter { it.discipleId.isNotEmpty() }
            .forEach { ids.add(it.discipleId) }
        // 探索/洞窟队伍成员
        ids.addAll(stateStore.teams.value
            .filter { it.status in explorationStatuses }
            .flatMap { it.memberIds })
        ids.addAll(data.caveExplorationTeams
            .filter { it.status in caveExplorationStatuses }
            .flatMap { it.memberIds })
        return ids
    }

    private fun buildPatrollingIds(data: GameData): Set<String> =
        data.patrolSlots.filter { it.discipleId.isNotEmpty() }.map { it.discipleId }.toSet()

    // ── 公开 API ──────────────────────────────────────

    /**
     * 根据所有槽位分配同步所有存活弟子的状态。
     * 保留 REFLECTING / ON_MISSION / REFINING 不覆盖。
     */
    fun syncAllDiscipleStatuses() {
        val data = stateStore.gameData.value
        val tables = stateStore.discipleTables

        val lawEnforcerIds = buildLawEnforcerIds(data.elderSlots)
        val preachingIds = buildPreachingIds(data.elderSlots)
        val deaconingIds = buildDeaconingIds(data.elderSlots)
        val managingIds = buildManagingIds(data.elderSlots)
        val studyingIds = buildStudyingIds(data)
        val miningIds = buildMiningIds(data, tables)
        val garrisonIds = buildGarrisonIds(data)
        val inTeamIds = buildInTeamIds(data)
        val patrollingIds = buildPatrollingIds(data)

        val alchemyIds = data.productionSlots
            .filter { !it.assignedDiscipleId.isNullOrEmpty() && it.buildingId == "alchemy" }
            .map { it.assignedDiscipleId!! }.toSet()
        val forgeIds = data.productionSlots
            .filter { !it.assignedDiscipleId.isNullOrEmpty() && it.buildingId == "forge" }
            .map { it.assignedDiscipleId!! }.toSet()
        val plantIds = data.productionSlots
            .filter { !it.assignedDiscipleId.isNullOrEmpty() && it.buildingId == "herbGarden" }
            .map { it.assignedDiscipleId!! }.toSet()

        fixInvalidMiningSlots(data, tables)

        stateStore.update {
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
     * 重置所有弟子为 IDLE 状态。
     * 保留 REFLECTING / REFINING 不受影响。
     * 清除所有槽位分配（灵脉矿/藏经阁/长老/驻守/探索队伍/任务）。
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

        discipleLifecycleManager.clearProductionSlots(protectedIds)
    }

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
