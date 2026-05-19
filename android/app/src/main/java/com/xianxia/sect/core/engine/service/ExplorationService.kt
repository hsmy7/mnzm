package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.xianxia.sect.core.model.*
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
    private val applicationScopeProvider: ApplicationScopeProvider
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

    // ==================== 洞府探索管理 ====================

    /**
     * Start cave exploration
     */
    suspend fun startCaveExploration(
        cave: CultivatorCave,
        selectedDisciples: List<Disciple>
    ): Boolean {
        val data = currentGameData

        val underageDisciples = selectedDisciples.filter { it.age < 5 }
        if (underageDisciples.isNotEmpty()) {
            return false
        }

        val lowHpDisciples = selectedDisciples.filter { it.hpPercent < 30 }
        if (lowHpDisciples.isNotEmpty()) {
            return false
        }

        val busyDisciples = selectedDisciples.filter { it.status != DiscipleStatus.IDLE }
        if (busyDisciples.isNotEmpty()) {
            return false
        }

        val caveExploringIds = data.caveExplorationTeams
            .filter { it.status == CaveExplorationStatus.TRAVELING || it.status == CaveExplorationStatus.EXPLORING }
            .flatMap { it.memberIds }
            .toSet()
        val alreadyCaveExploring = selectedDisciples.filter { it.id in caveExploringIds }
        if (alreadyCaveExploring.isNotEmpty()) {
                return false
            }

            // Calculate travel time
            val playerSect = data.worldMapSects.find { it.isPlayerSect }
            val sectX = playerSect?.x ?: 2000f
            val sectY = playerSect?.y ?: 1750f

            val distance = kotlin.math.sqrt(
                (cave.x - sectX) * (cave.x - sectX) +
                (cave.y - sectY) * (cave.y - sectY)
            )
            val travelDuration = (distance / 200 * 30).toInt().coerceIn(1, 360)

            // Create team
            val team = CaveExplorationSystem.createCaveExplorationTeam(
                cave = cave,
                disciples = selectedDisciples,
                currentYear = data.gameYear,
                currentMonth = data.gameMonth,
                sectX = sectX,
                sectY = sectY,
                travelDuration = travelDuration
            )

            // Update cave status
            val updatedCave = cave.copy(status = CaveStatus.EXPLORING)
            val updatedCaves = data.cultivatorCaves.map {
                if (it.id == cave.id) updatedCave else it
            }

            selectedDisciples.forEach { disciple ->
                updateDiscipleStatus(disciple.id, DiscipleStatus.IN_TEAM)
            }

            // Save changes
            @Suppress("USELESS_CAST")
            currentGameData = data.copy(
                cultivatorCaves = updatedCaves,
                caveExplorationTeams = (data.caveExplorationTeams + team) as List<CaveExplorationTeam>
            )

            return true
    }

    /**
     * Recall cave exploration team
     */
    fun recallCaveExplorationTeam(teamId: String): Boolean {
        val data = currentGameData
        val team = data.caveExplorationTeams.find { it.id == teamId } ?: return false

        val updatedTeams = data.caveExplorationTeams.filter { it.id != teamId }

        // Reset disciple statuses
        team.memberIds.forEach { memberId ->
            updateDiscipleStatus(memberId, DiscipleStatus.IDLE)
        }

        // Reset cave status
        val updatedCaves = data.cultivatorCaves.map { cave ->
            if (cave.id == team.caveId && cave.status == CaveStatus.EXPLORING) {
                cave.copy(status = CaveStatus.AVAILABLE)
            } else {
                cave
            }
        }

        currentGameData = data.copy(
            caveExplorationTeams = updatedTeams,
            cultivatorCaves = updatedCaves
        )

        return true
    }

    // ==================== 辅助方法 ====================

    /**
     * Update disciple status
     */
    private fun updateDiscipleStatus(discipleId: String, status: DiscipleStatus) {
        currentDisciples = currentDisciples.map {
            if (it.id == discipleId) it.copy(status = status) else it
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

    /**
     * Get active exploration teams count
     */
    fun getActiveExplorationTeamsCount(): Int {
        return currentTeams.count {
            it.status == ExplorationStatus.TRAVELING ||
            it.status == ExplorationStatus.EXPLORING ||
            it.status == ExplorationStatus.SCOUTING
        }
    }

    /**
     * Get active cave exploration teams count
     */
    fun getActiveCaveExplorationTeamsCount(): Int {
        val data = currentGameData
        return data.caveExplorationTeams.count {
            it.status == CaveExplorationStatus.TRAVELING ||
            it.status == CaveExplorationStatus.EXPLORING
        }
    }
}
