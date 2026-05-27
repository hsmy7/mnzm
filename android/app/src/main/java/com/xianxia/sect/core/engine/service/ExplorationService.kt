package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.engine.BattleSystem
import com.xianxia.sect.core.engine.CaveExplorationSystem
import com.xianxia.sect.core.engine.LevelGenerator
import com.xianxia.sect.core.event.DeathEvent
import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.di.ApplicationScopeProvider
import com.xianxia.sect.core.state.MutableGameState
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
    private val eventBus: EventBus,
    private val applicationScopeProvider: ApplicationScopeProvider,
    private val battleSystem: BattleSystem
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
        if (allSlots.isEmpty() || allSlots.none { it.discipleId.isNotEmpty() }) return

        val numTowers = gd.placedBuildings.count { it.displayName == "巡视楼" }
        if (numTowers == 0) return

        val year = gd.gameYear; val month = gd.gameMonth
        val equipmentMap = state.equipmentInstances.associateBy { it.id }
        val manualMap = state.manualInstances.associateBy { it.id }
        val allProficiencies = gd.manualProficiencies.mapValues { (_, list) ->
            list.associateBy { it.manualId }
        }

        val attackedBeasts = mutableSetOf<String>()

        // 每塔独立进攻
        for (towerIdx in 0 until numTowers) {
            val rangeStart = towerIdx * 10
            val rangeEnd = rangeStart + 10
            val towerSlots = (rangeStart until rangeEnd).mapNotNull { allSlots.getOrNull(it) }
            val config = configs.getOrElse(towerIdx) { PatrolConfig() }

            val towerDiscipleIds = towerSlots.filter { it.discipleId.isNotEmpty() }.map { it.discipleId }.toSet()
            val towerDisciples = disciples.filter { it.id in towerDiscipleIds && it.isAlive }
            if (towerDisciples.isEmpty()) continue

            // 满状态检查
            if (config.requireFullStatus) {
                val anyNotFull = towerDisciples.any {
                    it.combat.currentHp < it.maxHp || it.combat.currentMp < it.maxMp
                }
                if (anyNotFull) continue
            }

            // 找匹配的妖兽（跳过已被其他塔攻击的）
            val targets = gd.worldLevels.filter {
                it.type == LevelType.BEAST &&
                !it.defeated &&
                !it.checkExpired(year, month) &&
                it.id !in attackedBeasts &&
                it.realm in config.targetRealms &&
                it.count <= config.maxBeastCount
            }.sortedWith(compareBy({ it.realm }, { it.count }))

            if (targets.isEmpty()) continue
            val beast = targets.first()

            val aliveIds = towerDiscipleIds.intersect(
                disciples.filter { it.isAlive }.map { it.id }.toSet()
            )
            if (aliveIds.isEmpty()) continue

            val attackers = disciples.filter { it.id in aliveIds && it.isAlive }
            val battle = battleSystem.createBattle(
                disciples = attackers,
                equipmentMap = equipmentMap,
                manualMap = manualMap,
                beastLevel = beast.realm,
                beastCount = beast.count,
                beastType = beast.beastName,
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
                attackedBeasts.add(beast.id)
                gd = gd.copy(
                    worldLevels = gd.worldLevels.map {
                        if (it.id == beast.id) it.copy(defeated = true) else it
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
