package com.xianxia.sect.core.exploration

import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatusService
import com.xianxia.sect.core.event.DeathEvent
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.event.ExplorationCompletedEvent
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
    private val eventBus: EventBusPort,
    private val discipleStatusService: DiscipleStatusService,
    // D-03：死亡统一入口（袋物品物化回仓库 + markDead）
    private val inventorySystem: com.xianxia.sect.core.engine.system.InventorySystem
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

            DomainLog.d(TAG, "recallDiscipleFromTeam: team=$teamId, disciple=$discipleId")
            true
        }
        discipleStatusService.syncSingleDiscipleStatus(discipleId)
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

            // ★ 列直写替代 assembleAll → map → replaceAll
            team.memberIds.forEach { memberId ->
                val idInt = memberId.toIntOrNull()
                if (memberId in survivorIds) {
                    // 存活弟子：状态由 syncAllDiscipleStatuses() 统一推导
                } else {
                    // 阵亡弟子：收集死亡事件 + markDead
                    val name = if (idInt != null) {
                        discipleTables.names.getOrNull(idInt) ?: memberId
                    } else memberId
                    deadEvents.add(DeathEvent(memberId, name, "探索阵亡"))
                    if (idInt != null) {
                        // D-03：死亡统一入口——袋物品物化回仓库（玩家保留）+ 清袋 + markDead
                        inventorySystem.materializeDiscipleBagAndMarkDead(this, idInt, team.startYear, "exploration")
                    }
                }
            }

            // 仅成功时对亲属施加悲痛状态（列直写）
            if (success) {
                val deadMemberIds = team.memberIds.filter { it !in survivorIds }
                if (deadMemberIds.isNotEmpty()) {
                    val allForGrief = discipleTables.assembleAll()
                    val deadDisciples = allForGrief.filter { it.id in deadMemberIds }
                    if (deadDisciples.isNotEmpty()) {
                        val griefMap = DiscipleStatCalculator.computeGriefEndYearMap(
                            allForGrief, deadDisciples, team.startYear
                        )
                        griefMap.forEach { (idInt, griefEndYear) ->
                            discipleTables.griefEndYears[idInt] = griefEndYear
                        }
                    }
                }
            }

            DomainLog.d(TAG, "completeExploration: team=$teamId, " +
                "success=$success, survivors=${survivorIds.size}/${team.memberIds.size}")

            deadEvents
        }

        // 状态同步在事务外执行，避免部分状态窗口
        discipleStatusService.syncAllDiscipleStatuses()

        // 死亡事件在事务外 emit，避免部分状态窗口
        deathEvents.forEach { eventBus.emitSync(it) }
        eventBus.emitSync(ExplorationCompletedEvent(teamId, success, survivorIds.size))
    }
}
