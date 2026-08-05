package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.domain.exploration.MissionSystem
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult

/**
 * CultivationEventProcessor 任务域 Ops 扩展（P4D）：任务完成检测/奖励收集/招募刷新。
 * 方法经类 receiver 访问 internal 成员（stateStore/inventorySystem 等）。
 */
internal fun CultivationEventProcessor.processCompletedMissionsLazy(year: Int, month: Int) {
        // 注意：Phase 2 使用 stateStore.update 重入锁（ReentrantLock），
        // 在外层 update 内部调用时通过锁重入机制在同一事务内生效。
        val data = stateStore.gameData.value

        // ── Phase 1: 事务外计算（仅收集奖励数据，不变更任何状态） ──
        val (rewards, remainingActive) = collectCompletedMissionRewards(year, month, data)

        // ── Phase 2: 单事务写入所有状态（物品 + 灵石 + 弟子状态 + 任务清理） ──
        // ReentrantLock 允许嵌套 update — 内层操作同一 MutableGameState。
        stateStore.update {
            applyMissionRewards(rewards)
            gameData = gameData.copy(activeMissions = remainingActive)
        }
        discipleService.syncAllDiscipleStatuses()
    }

    /** 已完成任务的奖励收集结果（Phase 1 只读计算） */
    internal data class MissionReward(
        val missionId: String,
        val spiritStones: Int,
        val survivors: Set<String>,
        val discipleIds: List<String>,
        val materials: List<Material>,
        val pills: List<Pill>,
        val equipmentStacks: List<EquipmentStack>,
        val manualStacks: List<ManualStack>
    )

    /**
     * Phase 1 — 事务外计算：遍历活跃任务，收集已完成任务（含失败保留）的奖励数据。
     */
internal fun CultivationEventProcessor.collectCompletedMissionRewards(
        year: Int,
        month: Int,
        data: GameData
    ): Pair<List<MissionReward>, List<ActiveMission>> {
        val currentAbsoluteMonth = com.xianxia.sect.core.engine.LazyEvaluationDispatcher.toAbsoluteMonth(year, month)
        val remainingActive = mutableListOf<ActiveMission>()
        val rewards = mutableListOf<MissionReward>()

        for (activeMission in data.activeMissions) {
            val missionCompletionMonth = com.xianxia.sect.core.engine.LazyEvaluationDispatcher.toAbsoluteMonth(
                activeMission.startYear, activeMission.startMonth
            ) + activeMission.duration
            if (currentAbsoluteMonth < missionCompletionMonth) {
                remainingActive.add(activeMission); continue
            }
            if (!activeMission.isComplete(year, month)) {
                remainingActive.add(activeMission); continue
            }
            val missionReward = runCatching {
                val aliveDisciples = activeMission.discipleIds.mapNotNull { did ->
                    stateStore.disciples.value.find { it.id == did && it.isAlive }
                }
                if (aliveDisciples.isEmpty()) return@runCatching null
                val equipMap = stateStore.equipmentInstances.value.associateBy { it.id }
                val manualMap = stateStore.manualInstances.value.associateBy { it.id }
                val proficiencies = stateStore.gameData.value.manualProficiencies.mapValues { (_, list) ->
                    list.associateBy { it.manualId }
                }
                val result = MissionSystem.processMissionCompletion(
                    activeMission, aliveDisciples, equipMap, manualMap, proficiencies, battleSystem,
                    stateStore.gameData.value.bloodRefinementPctTotals
                )
                // 仅收集奖励，不再调用 inventorySystem.addXxx（统一到 Phase 2 单事务处理）
                val survivors = if (result.combatTriggered && result.victory && result.battleResult != null) {
                    result.battleResult.log.teamMembers.filter { it.isAlive }.map { it.id }.toSet()
                } else emptySet()
                MissionReward(
                    missionId = activeMission.id,
                    spiritStones = result.spiritStones,
                    survivors = survivors,
                    discipleIds = activeMission.discipleIds,
                    materials = result.materials,
                    pills = result.pills,
                    equipmentStacks = result.equipmentStacks,
                    manualStacks = result.manualStacks
                )
            }
            if (missionReward.isSuccess && missionReward.getOrNull() != null) {
                missionReward.getOrNull()?.let { rewards.add(it) }
            } else {
                remainingActive.add(activeMission) // 失败的任务保留到下次
            }
        }
        return rewards to remainingActive
    }

    /**
     * Phase 2 — 单事务写入：发放任务奖励（物品/灵石）并重置弟子状态。
     * 在调用方 stateStore.update 事务内执行。
     */
internal fun CultivationEventProcessor.processMissionRefreshIfDue(month: Int) {
        if (month % MissionSystem.REFRESH_INTERVAL_MONTHS != 0) return
        processMissionRefresh()
    }
internal fun CultivationEventProcessor.processMissionRefreshIfDue(month: Int, state: MutableGameState) {
        if (month % MissionSystem.REFRESH_INTERVAL_MONTHS != 0) return
        processMissionRefresh(state)
    }
internal fun CultivationEventProcessor.processMissionRefresh() {
        stateStore.update { processMissionRefresh(this) }
    }
internal fun CultivationEventProcessor.processMissionRefresh(state: MutableGameState) {
        val data = state.gameData
        val result = MissionSystem.processMonthlyRefresh(
            data.availableMissions,
            data.gameYear,
            data.gameMonth
        )
        state.gameData = state.gameData.copy(availableMissions = result.cleanedMissions)
    }
