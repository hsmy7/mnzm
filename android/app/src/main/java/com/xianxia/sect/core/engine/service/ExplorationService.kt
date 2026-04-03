package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.engine.CaveExplorationSystem
import java.util.UUID

/**
 * 探索服务 - 负责探索队伍和洞穴探索管理
 *
 * 职责域：
 * - 秘境探索队伍管理
 * - 洞府探索逻辑
 * - teams StateFlow
 * - 探查任务管理
 */
class ExplorationService constructor(
    private val _gameData: MutableStateFlow<GameData>,
    private val _disciples: MutableStateFlow<List<Disciple>>,
    private val _teams: MutableStateFlow<List<ExplorationTeam>>,
    private val addEvent: (String, EventType) -> Unit,
    private val transactionMutex: Any
) {
    companion object {
        private const val TAG = "ExplorationService"
    }

    // ==================== StateFlow 暴露 ====================

    /**
     * Get exploration teams StateFlow
     */
    fun getTeams(): StateFlow<List<ExplorationTeam>> = _teams

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
            _disciples.value.find { it.id == id }?.name
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

        _teams.value = _teams.value + team

        // Update disciple statuses to EXPLORING
        memberIds.forEach { memberId ->
            updateDiscipleStatus(memberId, DiscipleStatus.EXPLORING)
        }

        addEvent("探索队伍【$name】出发前往 $dungeonName，预计 ${duration} 天后到达", EventType.INFO)

        return team
    }

    /**
     * Recall exploration team
     */
    fun recallTeam(teamId: String): Boolean {
        val teamIndex = _teams.value.indexOfFirst { it.id == teamId }
        if (teamIndex < 0) return false

        val team = _teams.value[teamIndex]

        // Remove team
        _teams.value = _teams.value.filter { it.id != teamId }

        // Reset disciple statuses
        team.memberIds.forEach { memberId ->
            updateDiscipleStatus(memberId, DiscipleStatus.IDLE)
        }

        addEvent("探索队伍【${team.name}】已召回", EventType.INFO)
        return true
    }

    /**
     * Complete exploration (success or failure)
     */
    fun completeExploration(teamId: String, success: Boolean, survivorIds: List<String>) {
        val teamIndex = _teams.value.indexOfFirst { it.id == teamId }
        if (teamIndex < 0) return

        val team = _teams.value[teamIndex]

        // Mark as completed
        val updatedTeam = team.copy(status = ExplorationStatus.COMPLETED)
        _teams.value = _teams.value.toMutableList().also { it[teamIndex] = updatedTeam }

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
    fun startCaveExploration(
        cave: CultivatorCave,
        selectedDisciples: List<Disciple>
    ): Boolean {
        synchronized(transactionMutex) {
            val data = _gameData.value

            // Validate disciples
            val underageDisciples = selectedDisciples.filter { it.age < 5 }
            if (underageDisciples.isNotEmpty()) {
                val names = underageDisciples.joinToString(", ") { it.name }
                addEvent("以下弟子年龄太小，无法参加洞府探索：$names", EventType.WARNING)
                return false
            }

            val lowHpDisciples = selectedDisciples.filter { it.hpPercent < 30 }
            if (lowHpDisciples.isNotEmpty()) {
                val names = lowHpDisciples.joinToString(", ") { it.name }
                addEvent("以下弟子生命值过低，无法参加洞府探索：$names", EventType.WARNING)
                return false
            }

            val busyDisciples = selectedDisciples.filter { it.status != DiscipleStatus.IDLE }
            if (busyDisciples.isNotEmpty()) {
                val names = busyDisciples.joinToString(", ") { it.name }
                addEvent("以下弟子正在忙碌中，无法参加洞府探索：$names", EventType.WARNING)
                return false
            }

            // Check if already exploring
            val caveExploringIds = data.caveExplorationTeams
                .filter { it.status == CaveExplorationStatus.TRAVELING || it.status == CaveExplorationStatus.EXPLORING }
                .flatMap { it.memberIds }
                .toSet()
            val alreadyCaveExploring = selectedDisciples.filter { it.id in caveExploringIds }
            if (alreadyCaveExploring.isNotEmpty()) {
                val names = alreadyCaveExploring.joinToString(", ") { it.name }
                addEvent("以下弟子已在洞府探索中，无法重复派遣：$names", EventType.WARNING)
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

            // Update disciple statuses
            selectedDisciples.forEach { disciple ->
                updateDiscipleStatus(disciple.id, DiscipleStatus.EXPLORING)
            }

            // Save changes
            @Suppress("USELESS_CAST")
            _gameData.value = data.copy(
                cultivatorCaves = updatedCaves,
                caveExplorationTeams = (data.caveExplorationTeams + team) as List<CaveExplorationTeam>
            )

            addEvent("派遣队伍前往${cave.name}探索，预计${travelDuration}天后到达", EventType.INFO)
            return true
        }
    }

    /**
     * Recall cave exploration team
     */
    fun recallCaveExplorationTeam(teamId: String): Boolean {
        val data = _gameData.value
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

        _gameData.value = data.copy(
            caveExplorationTeams = updatedTeams,
            cultivatorCaves = updatedCaves
        )

        addEvent("${team.caveName}的探索队伍已召回", EventType.INFO)
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
        val playerSect = _gameData.value.worldMapSects.find { it.isPlayerSect }

        if (playerSect == null) {
            addEvent("无法找到玩家宗门，探查失败", EventType.WARNING)
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
            memberNames = memberIds.mapNotNull { id -> _disciples.value.find { it.id == id }?.name },
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

        _teams.value = _teams.value + team

        // Update disciple statuses
        memberIds.forEach { memberId ->
            updateDiscipleStatus(memberId, DiscipleStatus.SCOUTING)
        }

        addEvent("探查队伍出发前往${targetSect.name}进行探查，预计${travelDays}天后到达", EventType.INFO)
    }

    // ==================== 辅助方法 ====================

    /**
     * Update disciple status
     */
    private fun updateDiscipleStatus(discipleId: String, status: DiscipleStatus) {
        _disciples.value = _disciples.value.map {
            if (it.id == discipleId) it.copy(status = status) else it
        }
    }

    /**
     * Mark disciple as dead
     */
    private fun markDiscipleDead(discipleId: String) {
        _disciples.value = _disciples.value.map {
            if (it.id == discipleId) it.copy(isAlive = false, status = DiscipleStatus.IDLE) else it
        }
    }

    /**
     * Get active exploration teams count
     */
    fun getActiveExplorationTeamsCount(): Int {
        return _teams.value.count {
            it.status == ExplorationStatus.TRAVELING ||
            it.status == ExplorationStatus.EXPLORING ||
            it.status == ExplorationStatus.SCOUTING
        }
    }

    /**
     * Get active cave exploration teams count
     */
    fun getActiveCaveExplorationTeamsCount(): Int {
        val data = _gameData.value
        return data.caveExplorationTeams.count {
            it.status == CaveExplorationStatus.TRAVELING ||
            it.status == CaveExplorationStatus.EXPLORING
        }
    }
}
