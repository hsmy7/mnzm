package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.claimGuideReward
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.guide.GuideTask
import com.xianxia.sect.core.model.guide.GuideTaskRegistry
import com.xianxia.sect.core.state.DiscipleTables

/**
 * 新手引导任务委托 — 数据提供与状态检测。
 *
 * 职责：提供任务定义、检查任务完成状态。
 * 奖励领取操作通过 [GameEngineGuideOps] 扩展函数执行。
 */
class GuideDelegate(
    private val gameEngine: GameEngine
) {

    /** 领取引导任务奖励（引擎线程执行） */
    fun claimGuideReward(taskId: Int) {
        gameEngine.launchOnEngine {
            gameEngine.claimGuideReward(taskId)
        }
    }

    /** 获取所有引导任务列表 */
    fun getAllTasks(): List<GuideTask> = GuideTaskRegistry.ALL_TASKS

    /** 获取已领取奖励的任务ID集合 */
    fun getClaimedRewardIds(gameData: GameData): Set<Int> = gameData.guideClaimedRewardIds

    /**
     * 检查任务是否已完成（所有条件均已满足）。
     */
    fun isTaskCompleted(taskId: Int, gameData: GameData, discipleTables: DiscipleTables? = null): Boolean {
        val task = GuideTaskRegistry.getTask(taskId) ?: return false
        return task.conditions.all { it.isMet(gameData, discipleTables) }
    }

    /**
     * 检查任务奖励是否已领取。
     */
    fun isRewardClaimed(taskId: Int, gameData: GameData): Boolean =
        taskId in gameData.guideClaimedRewardIds
}
