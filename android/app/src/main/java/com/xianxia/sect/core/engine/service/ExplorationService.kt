package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.engine.CaveExplorationSystem
import com.xianxia.sect.core.event.DeathEvent
import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.di.ApplicationScopeProvider
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.engine.system.GameSystem
import com.xianxia.sect.core.engine.system.SystemPriority
import android.util.Log
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@SystemPriority(order = 240)
@Singleton
class ExplorationService @Inject constructor(
    private val stateStore: GameStateStore,
    private val eventService: EventService,
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

    override suspend fun clear() {}
    private var currentGameData: GameData
        get() = stateStore.currentTransactionMutableState()?.gameData ?: stateStore.gameData.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.gameData = value; return }
            scope.launch { stateStore.update { gameData = value } }
        }

    private var currentDisciples: List<Disciple>
        get() = stateStore.currentTransactionMutableState()?.disciples ?: stateStore.disciples.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.disciples = value; return }
            scope.launch { stateStore.update { disciples = value } }
        }

    private var currentTeams: List<ExplorationTeam>
        get() = stateStore.currentTransactionMutableState()?.teams ?: stateStore.teams.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.teams = value; return }
            scope.launch { stateStore.update { teams = value } }
        }

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

        eventService.addGameEvent("探索队伍【$name】出发前往 $dungeonName，预计 ${duration} 天后到达", EventType.INFO)

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

        eventService.addGameEvent("探索队伍【${team.name}】已召回", EventType.INFO)
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
            eventService.addGameEvent("探索队伍【${team.name}】已召回（无剩余成员）", EventType.INFO)
        } else {
            val updatedTeam = team.copy(
                memberIds = remainingMemberIds,
                memberNames = remainingMemberNames
            )
            currentTeams = currentTeams.toMutableList().also { it[teamIndex] = updatedTeam }
            eventService.addGameEvent("${discipleName}已从探索队伍【${team.name}】召回", EventType.INFO)
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
            val names = underageDisciples.joinToString(", ") { it.name }
            eventService.addGameEvent("以下弟子年龄太小，无法参加洞府探索：$names", EventType.WARNING)
            return false
        }

        val lowHpDisciples = selectedDisciples.filter { it.hpPercent < 30 }
        if (lowHpDisciples.isNotEmpty()) {
            val names = lowHpDisciples.joinToString(", ") { it.name }
            eventService.addGameEvent("以下弟子生命值过低，无法参加洞府探索：$names", EventType.WARNING)
            return false
        }

        val busyDisciples = selectedDisciples.filter { it.status != DiscipleStatus.IDLE }
        if (busyDisciples.isNotEmpty()) {
            val names = busyDisciples.joinToString(", ") { it.name }
            eventService.addGameEvent("以下弟子正在忙碌中，无法参加洞府探索：$names", EventType.WARNING)
            return false
        }

        val caveExploringIds = data.caveExplorationTeams
            .filter { it.status == CaveExplorationStatus.TRAVELING || it.status == CaveExplorationStatus.EXPLORING }
            .flatMap { it.memberIds }
            .toSet()
        val alreadyCaveExploring = selectedDisciples.filter { it.id in caveExploringIds }
        if (alreadyCaveExploring.isNotEmpty()) {
            val names = alreadyCaveExploring.joinToString(", ") { it.name }
            eventService.addGameEvent("以下弟子已在洞府探索中，无法重复派遣：$names", EventType.WARNING)
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

            eventService.addGameEvent("派遣队伍前往${cave.name}探索，预计${travelDuration}天后到达", EventType.INFO)
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

        eventService.addGameEvent("${team.caveName}的探索队伍已召回", EventType.INFO)
        return true
    }

    // ==================== 探查任务管理 ====================

    /**
     * Start scout mission to target sect
     */
    fun startScoutMission(
        memberIds: List<String>,
        targetSect: WorldSect,
        currentYear: Int,
        currentMonth: Int,
        currentDay: Int
    ) {
        val playerSect = currentGameData.worldMapSects.find { it.isPlayerSect }

        if (playerSect == null) {
            eventService.addGameEvent("无法找到玩家宗门，探查失败", EventType.WARNING)
            return
        }

        // Calculate distance and travel time
        val distance = kotlin.math.sqrt(
            (targetSect.x - playerSect.x) * (targetSect.x - playerSect.x) +
            (targetSect.y - playerSect.y) * (targetSect.y - playerSect.y)
        )
        val travelDays = (distance / 200 * 30).toInt().coerceIn(1, 360)

        var arrivalDay = currentDay + travelDays
        var arrivalMonth = currentMonth
        var arrivalYear = currentYear

        while (arrivalDay > 30) {
            arrivalDay -= 30
            arrivalMonth++
            if (arrivalMonth > 12) {
                arrivalMonth = 1
                arrivalYear++
            }
        }

        // Create scout team
        val team = ExplorationTeam(
            id = UUID.randomUUID().toString(),
            name = "探查队伍",
            memberIds = memberIds,
            memberNames = memberIds.mapNotNull { id -> currentDisciples.find { it.id == id }?.name },
            dungeon = "scout",
            dungeonName = "探查-${targetSect.name}",
            startYear = currentYear,
            startMonth = currentMonth,
            startDay = currentDay,
            duration = travelDays,
            status = ExplorationStatus.SCOUTING,
            scoutTargetSectId = targetSect.id,
            scoutTargetSectName = targetSect.name,
            currentX = playerSect.x,
            currentY = playerSect.y,
            targetX = targetSect.x,
            targetY = targetSect.y,
            moveProgress = 0f,
            arrivalYear = arrivalYear,
            arrivalMonth = arrivalMonth,
            arrivalDay = arrivalDay,
            route = listOf(playerSect.id, targetSect.id),
            currentRouteIndex = 0,
            currentSegmentProgress = 0f
        )

        currentTeams = currentTeams + team

        memberIds.forEach { memberId ->
            updateDiscipleStatus(memberId, DiscipleStatus.IN_TEAM)
        }

        eventService.addGameEvent("探查队伍出发前往${targetSect.name}进行探查，预计${travelDays}天后到达", EventType.INFO)
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
            if (it.id == discipleId) it.copy(isAlive = false, status = DiscipleStatus.IDLE) else it
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
