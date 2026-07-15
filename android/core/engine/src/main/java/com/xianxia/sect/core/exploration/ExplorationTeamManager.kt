package com.xianxia.sect.core.exploration

import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.event.DeathEvent
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.event.ExplorationCompletedEvent
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.ExplorationStatus
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.DomainLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 探索队伍管理器 — 从 [ExplorationService] 提取的队伍管理操作。
 *
 * 负责探索队伍的召回、完成等原子操作，确保单事务内完成所有状态变更。
 * 死亡事件在事务外 emit，避免 UI 层读到部分状态窗口。
 *
 * ## recallDiscipleFromTeam
 * 全部在 [GameStateStore.updateAndReturn] 内完成，indexOfFirst 在事务内部，
 * 消除跨 update 的 teamIndex 竞态。
 *
 * ## completeExploration
 * 单事务内完成：标记队伍 COMPLETED + 弟子状态更新 + 收集死亡事件。
 * 死亡事件和 [ExplorationCompletedEvent] 在事务外 emit。
 * [COMPLETED] 守卫防止重复执行。
 * [success] 参数区分：仅成功时 [applyGriefToRelatives]。
 */
@Singleton
class ExplorationTeamManager @Inject constructor(
    private val stateStore: GameStateStore,
    private val eventBus: EventBusPort
) {
    companion object {
        private const val TAG = "ExplorationTeamManager"
    }

    /**
     * 单事务内完成召回，避免 teamIndex 竞态。
     *
     * @param teamId 队伍 ID
     * @param discipleId 要召回的弟子 ID
     * @return true 召回成功，false 队伍/弟子不存在
     */
    suspend fun recallDiscipleFromTeam(teamId: String, discipleId: String): Boolean {
        return stateStore.updateAndReturn {
            val teams = this.teams
            val teamIndex = teams.indexOfFirst { it.id == teamId }
            if (teamIndex < 0) return@updateAndReturn false

            val team = teams[teamIndex]
            if (!team.memberIds.contains(discipleId)) return@updateAndReturn false

            // 从队伍成员的 ID 和名字列表中移除该弟子
            val remainingMemberIds = team.memberIds.filter { it != discipleId }
            val remainingMemberNames = team.memberNames.toMutableList()
            val removedIndex = team.memberIds.indexOf(discipleId)
            if (removedIndex in remainingMemberNames.indices) {
                remainingMemberNames.removeAt(removedIndex)
            }

            // 更新队伍列表：若无人则删除整队，否则更新
            if (remainingMemberIds.isEmpty()) {
                this.teams = this.teams.filter { it.id != teamId }
            } else {
                val updatedTeam = team.copy(
                    memberIds = remainingMemberIds,
                    memberNames = remainingMemberNames
                )
                val mutableTeams = this.teams.toMutableList()
                mutableTeams[teamIndex] = updatedTeam
                this.teams = mutableTeams
            }

            // 更新弟子状态为 IDLE
            val currentList = discipleTables.assembleAll()
            val updated = currentList.map {
                if (it.id == discipleId) it.copy(status = DiscipleStatus.IDLE) else it
            }
            discipleTables.replaceAll(updated)

            DomainLog.d(TAG, "recallDiscipleFromTeam: team=$teamId, disciple=$discipleId")
            true
        }
    }

    /**
     * 单事务内原子完成：标记队伍 COMPLETED + 弟子状态更新 + 死亡事件事务外发出。
     *
     * @param teamId 队伍 ID
     * @param success 是否成功（仅成功时对亲属施加悲痛状态）
     * @param survivorIds 幸存弟子 ID 列表（其余标记为死亡）
     */
    suspend fun completeExploration(
        teamId: String,
        success: Boolean,
        survivorIds: List<String>
    ) {
        val deathEvents = stateStore.updateAndReturn {
            val teams = this.teams
            val teamIndex = teams.indexOfFirst { it.id == teamId }
            if (teamIndex < 0) return@updateAndReturn emptyList<DeathEvent>()

            val team = teams[teamIndex]

            // COMPLETED 守卫防止重复执行
            if (team.status == ExplorationStatus.COMPLETED) {
                DomainLog.w(TAG, "completeExploration: team=$teamId already COMPLETED, skipping")
                return@updateAndReturn emptyList<DeathEvent>()
            }

            // 标记队伍 COMPLETED
            val updatedTeam = team.copy(status = ExplorationStatus.COMPLETED)
            val mutableTeams = teams.toMutableList()
            mutableTeams[teamIndex] = updatedTeam
            this.teams = mutableTeams

            val deadEvents = mutableListOf<DeathEvent>()
            val currentList = discipleTables.assembleAll()
            var modifiedList = currentList

            team.memberIds.forEach { memberId ->
                if (memberId in survivorIds) {
                    modifiedList = modifiedList.map {
                        if (it.id == memberId) it.copy(status = DiscipleStatus.IDLE) else it
                    }
                } else {
                    val deadDisciple = currentList.find { it.id == memberId }
                    modifiedList = modifiedList.map {
                        if (it.id == memberId) it.copy(
                            isAlive = false,
                            status = DiscipleStatus.DEAD
                        ) else it
                    }
                    if (deadDisciple != null) {
                        deadEvents.add(
                            DeathEvent(deadDisciple.id, deadDisciple.name, "探索阵亡")
                        )
                    }
                }
            }

            // 仅成功时对亲属施加悲痛状态
            if (success) {
                val deadDisciples = currentList.filter {
                    it.id in team.memberIds && it.id !in survivorIds
                }
                if (deadDisciples.isNotEmpty()) {
                    modifiedList = DiscipleStatCalculator.applyGriefToRelatives(
                        modifiedList, deadDisciples, team.startYear
                    )
                }
            }

            discipleTables.replaceAll(modifiedList)

            DomainLog.d(TAG, "completeExploration: team=$teamId, " +
                "success=$success, survivors=${survivorIds.size}/${team.memberIds.size}")

            deadEvents
        }

        // 死亡事件在事务外 emit，避免部分状态窗口
        deathEvents.forEach { eventBus.emitSync(it) }
        eventBus.emitSync(ExplorationCompletedEvent(teamId, success, survivorIds.size))
    }
}
