@file:Suppress("DEPRECATION")
package com.xianxia.sect.domain.usecase

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.UnifiedGameStateManager
import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.core.event.NotificationEvent
import com.xianxia.sect.core.event.NotificationSeverity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CombatUseCase @Inject constructor(
    private val gameEngine: GameEngine,
    private val stateManager: UnifiedGameStateManager,
    private val eventBus: EventBus
) {
    data class ExplorationResult(
        val success: Boolean,
        val teamId: String? = null,
        val message: String
    )
    
    data class RecallResult(
        val success: Boolean,
        val message: String
    )
    
    suspend fun startExploration(
        teamName: String,
        discipleIds: List<String>,
        dungeonId: String,
        duration: Int
    ): ExplorationResult {
        return withContext(Dispatchers.Default) {
            try {
                gameEngine.startExploration(teamName, discipleIds, dungeonId, duration)
                val teamId = stateManager.currentState.teams.lastOrNull()?.id 
                    ?: UUID.randomUUID().toString()
                eventBus.emit(NotificationEvent(
                    title = "探索开始",
                    message = "$teamName 已出发探索",
                    severity = NotificationSeverity.SUCCESS
                ))
                ExplorationResult(success = true, teamId = teamId, message = "探索已开始")
            } catch (e: Exception) {
                ExplorationResult(success = false, message = e.message ?: "探索启动失败")
            }
        }
    }
    
    suspend fun recallTeam(teamId: String): RecallResult {
        return withContext(Dispatchers.Default) {
            try {
                gameEngine.recallTeam(teamId)
                RecallResult(success = true, message = "队伍已召回")
            } catch (e: Exception) {
                RecallResult(success = false, message = e.message ?: "召回失败")
            }
        }
    }
    
    suspend fun startCaveExploration(
        cave: CultivatorCave,
        selectedDisciples: List<Disciple>
    ): ExplorationResult {
        return withContext(Dispatchers.Default) {
            try {
                gameEngine.startCaveExploration(cave, selectedDisciples)
                eventBus.emit(NotificationEvent(
                    title = "洞府探索",
                    message = "开始探索${cave.name}",
                    severity = NotificationSeverity.SUCCESS
                ))
                ExplorationResult(success = true, message = "洞府探索已开始")
            } catch (e: Exception) {
                ExplorationResult(success = false, message = e.message ?: "洞府探索启动失败")
            }
        }
    }
    
    suspend fun startMission(
        mission: Mission,
        selectedDisciples: List<Disciple>
    ): ExplorationResult {
        return withContext(Dispatchers.Default) {
            try {
                gameEngine.startMission(mission, selectedDisciples)
                eventBus.emit(NotificationEvent(
                    title = "任务开始",
                    message = "开始执行${mission.name}",
                    severity = NotificationSeverity.SUCCESS
                ))
                ExplorationResult(success = true, message = "任务已开始")
            } catch (e: Exception) {
                ExplorationResult(success = false, message = e.message ?: "任务启动失败")
            }
        }
    }
    
    fun getActiveExplorations(): List<ExplorationTeam> {
        return stateManager.currentState.teams.filter { 
            it.status == ExplorationStatus.EXPLORING 
        }
    }
    
    fun getAvailableDisciplesForExploration(): List<Disciple> {
        val state = stateManager.currentState
        val exploringIds = state.teams
            .filter { it.status == ExplorationStatus.EXPLORING }
            .flatMap { it.memberIds }
        
        return state.disciples.filter { disciple ->
            disciple.isAlive && 
            disciple.status == DiscipleStatus.IDLE &&
            disciple.id !in exploringIds
        }
    }
}
