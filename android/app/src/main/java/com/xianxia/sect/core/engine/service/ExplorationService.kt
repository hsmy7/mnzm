package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.engine.BattleSystem
import com.xianxia.sect.core.engine.CaveExplorationSystem
import com.xianxia.sect.core.engine.LevelGenerator
import com.xianxia.sect.core.event.DeathEvent
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.di.ApplicationScopeProvider
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.engine.system.ExplorationSystem
import com.xianxia.sect.core.engine.system.GameSystem
import com.xianxia.sect.core.engine.system.StateAccessorFactory
import com.xianxia.sect.core.engine.system.SystemPriority
import android.util.Log
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@SystemPriority(order = 240)
@Singleton
class ExplorationService @Inject constructor(
    private val stateStore: GameStateStore,
    private val eventBus: EventBusPort,
    private val applicationScopeProvider: ApplicationScopeProvider,
    private val battleSystem: BattleSystem,
    private val buildingConfigService: BuildingConfigService
) : GameSystem {
    override val systemName: String = "ExplorationService"
    private val scope get() = applicationScopeProvider.scope

    override fun initialize() {
        Log.d(TAG, "ExplorationService initialized as GameSystem")
    }

    override fun release() {
        Log.d(TAG, "ExplorationService released")
    }

    override suspend fun clearForSlot(slotId: Int) {}

    override suspend fun onMonthTick(state: MutableGameState) {
        val data = state.gameData
        val year = data.gameYear
        val month = data.gameMonth

        val remainingLevels = data.worldLevels.filter { !it.checkExpired(year, month) }
        val playerSect = data.worldMapSects.find { it.isPlayerSect } ?: return

        val edges = LevelGenerator.buildConnectionEdges(data.worldMapSects)
        val newLevels = LevelGenerator.generateWorldLevels(
            existingSects = data.worldMapSects,
            connectionEdges = edges,
            currentYear = year,
            currentMonth = month,
            existingLevels = remainingLevels
        )

        if (newLevels.isNotEmpty()) {
            state.gameData = data.copy(worldLevels = remainingLevels + newLevels)
        } else if (remainingLevels.size != data.worldLevels.size) {
            state.gameData = data.copy(worldLevels = remainingLevels)
        }

        // 巡视楼自动攻击
        processPatrolAttacks(state)
    }

    private fun processPatrolAttacks(state: MutableGameState) {
        var gd = state.gameData
        var disciples = state.disciples
        val allSlots = gd.patrolSlots
        val configs = gd.patrolConfigs
        if (allSlots.isEmpty()) return

        val numTowers = gd.placedBuildings.count { it.displayName == "巡视楼" }
        val equipmentMap = state.equipmentInstances.associateBy { it.id }
        val manualMap = state.manualInstances.associateBy { it.id }
        val allProficiencies = gd.manualProficiencies.mapValues { (_, list) ->
            list.associateBy { it.manualId }
        }
        val year = gd.gameYear; val month = gd.gameMonth
        val claimedBeasts = mutableSetOf<String>()

        val slotsPerTower = buildingConfigService.getSlotCountByDisplayName("巡视楼")
        for (towerIndex in 0 until numTowers) {
            val config = configs.getOrElse(towerIndex) { PatrolConfig() }
            val start = towerIndex * slotsPerTower
            val end = (start + slotsPerTower).coerceAtMost(allSlots.size)
            val towerSlots = allSlots.subList(start, end).filter { it.discipleId.isNotEmpty() }
            if (towerSlots.isEmpty()) continue

            val towerDiscipleIds = towerSlots.map { it.discipleId }.toSet()
            val towerDisciples = disciples.filter { it.id in towerDiscipleIds && it.isAlive }
            if (towerDisciples.isEmpty()) continue

            // 满状态检查
            if (config.requireFullStatus) {
                val anyNotFull = towerDisciples.any {
                    it.combat.currentHp < it.maxHp || it.combat.currentMp < it.maxMp
                }
                if (anyNotFull) continue
            }

            // 找匹配的妖兽（排除已被其他塔选中的）
            val target = gd.worldLevels.firstOrNull {
                it.type == LevelType.BEAST &&
                !it.defeated &&
                !it.checkExpired(year, month) &&
                it.realm in config.targetRealms &&
                it.count <= config.maxBeastCount &&
                it.id !in claimedBeasts
            } ?: continue

            claimedBeasts.add(target.id)

            val battle = battleSystem.createBattle(
                disciples = towerDisciples,
                equipmentMap = equipmentMap,
                manualMap = manualMap,
                beastLevel = target.realm,
                beastCount = target.count,
                beastType = target.beastName,
                manualProficiencies = allProficiencies
            )
            val result = battleSystem.executeBattle(battle)

            // 更新弟子状态
            val hpMap = result.battle.team.associate { it.id to (it.hp to it.mp) }
            val survivorIds = result.battle.team.filter { !it.isDead }.map { it.id }.toSet()
            disciples = disciples.map { d ->
                val (hp, mp) = hpMap[d.id] ?: return@map d
                if (d.id !in survivorIds) {
                    d.copy(isAlive = false, status = DiscipleStatus.DEAD)
                } else {
                    d.copy(combat = d.combat.copy(
                        currentHp = hp.coerceIn(0, d.maxHp),
                        currentMp = mp.coerceIn(0, d.maxMp)
                    ))
                }
            }

            // 标记妖兽已击败
            if (result.victory) {
                gd = gd.copy(
                    worldLevels = gd.worldLevels.map {
                        if (it.id == target.id) it.copy(defeated = true) else it
                    }
                )
                // 清理阵亡弟子槽位
                val deadIds = disciples.filter { !it.isAlive }.map { it.id }.toSet()
                if (deadIds.isNotEmpty()) {
                    gd = gd.copy(
                        patrolSlots = gd.patrolSlots.map { slot ->
                            if (slot.discipleId in deadIds) PatrolSlot(index = slot.index) else slot
                        }
                    )
                }
            }
        }

        state.gameData = gd
        state.disciples = disciples
    }
    private val state = StateAccessorFactory(stateStore, scope, null)

    private var currentGameData: GameData
        get() = state.gameData().current
        set(value) { state.gameData().current = value }

    private var currentDisciples: List<Disciple>
        get() = state.disciples().current
        set(value) { state.disciples().current = value }

    private var currentTeams: List<ExplorationTeam>
        get() = state.teams().current
        set(value) { state.teams().current = value }

    companion object {
        private const val TAG = "ExplorationService"
    }

    // ==================== StateFlow 暴露 ====================

    /**
     * Get exploration teams StateFlow
     */
    fun getTeams(): StateFlow<List<ExplorationTeam>> = stateStore.teams

    // ==================== 探索队伍管理 ====================

    /**
     * Create new exploration team for dungeon
     */
    fun createExplorationTeam(
        name: String,
        memberIds: List<String>,
        dungeonId: String,
        dungeonName: String,
        duration: Int,
        currentYear: Int,
        currentMonth: Int,
        currentDay: Int
    ): ExplorationTeam {
        val memberNames = memberIds.mapNotNull { id ->
            currentDisciples.find { it.id == id }?.name
        }

        val team = ExplorationTeam(
            id = UUID.randomUUID().toString(),
            name = name,
            memberIds = memberIds,
            memberNames = memberNames,
            dungeon = dungeonId,
            dungeonName = dungeonName,
            startYear = currentYear,
            startMonth = currentMonth,
            startDay = currentDay,
            duration = duration,
            status = ExplorationStatus.TRAVELING
        )

        currentTeams = currentTeams + team

        memberIds.forEach { memberId ->
            updateDiscipleStatus(memberId, DiscipleStatus.IN_TEAM)
        }

        return team
    }

    /**
     * Recall exploration team
     */
    fun recallTeam(teamId: String): Boolean {
        val teamIndex = currentTeams.indexOfFirst { it.id == teamId }
        if (teamIndex < 0) return false

        val team = currentTeams[teamIndex]

        currentTeams = currentTeams.filter { it.id != teamId }

        team.memberIds.forEach { memberId ->
            updateDiscipleStatus(memberId, DiscipleStatus.IDLE)
        }

        return true
    }

    fun recallDiscipleFromTeam(teamId: String, discipleId: String): Boolean {
        val teamIndex = currentTeams.indexOfFirst { it.id == teamId }
        if (teamIndex < 0) return false

        val team = currentTeams[teamIndex]
        if (!team.memberIds.contains(discipleId)) return false

        val discipleName = currentDisciples.find { it.id == discipleId }?.name ?: "弟子"

        val remainingMemberIds = team.memberIds.filter { it != discipleId }
        val remainingMemberNames = team.memberNames.toMutableList()
        val removedIndex = team.memberIds.indexOf(discipleId)
        if (removedIndex in remainingMemberNames.indices) {
            remainingMemberNames.removeAt(removedIndex)
        }

        if (remainingMemberIds.isEmpty()) {
            currentTeams = currentTeams.filter { it.id != teamId }
        } else {
            val updatedTeam = team.copy(
                memberIds = remainingMemberIds,
                memberNames = remainingMemberNames
            )
            currentTeams = currentTeams.toMutableList().also { it[teamIndex] = updatedTeam }
        }

        updateDiscipleStatus(discipleId, DiscipleStatus.IDLE)
        return true
    }

    /**
     * Complete exploration (success or failure)
     */
    fun completeExploration(teamId: String, success: Boolean, survivorIds: List<String>) {
        val teamIndex = currentTeams.indexOfFirst { it.id == teamId }
        if (teamIndex < 0) return

        val team = currentTeams[teamIndex]

        // Mark as completed
        val updatedTeam = team.copy(status = ExplorationStatus.COMPLETED)
        currentTeams = currentTeams.toMutableList().also { it[teamIndex] = updatedTeam }

        // Reset survivor statuses, mark dead disciples
        team.memberIds.forEach { memberId ->
            if (survivorIds.contains(memberId)) {
                updateDiscipleStatus(memberId, DiscipleStatus.IDLE)
            } else {
                markDiscipleDead(memberId)
            }
        }
    }

    /**
     * Mark disciple as dead
     */
    private fun markDiscipleDead(discipleId: String) {
        val disciple = currentDisciples.find { it.id == discipleId }
        currentDisciples = currentDisciples.map {
            if (it.id == discipleId) it.copy(isAlive = false, status = DiscipleStatus.DEAD) else it
        }
        disciple?.let { eventBus.emitSync(DeathEvent(it.id, it.name, "探索阵亡")) }
    }

    private fun updateDiscipleStatus(discipleId: String, status: DiscipleStatus) {
        currentDisciples = currentDisciples.map {
            if (it.id == discipleId) it.copy(status = status) else it
        }
    }

}
