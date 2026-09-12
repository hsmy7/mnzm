package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.RewardCardItem

/**
 * 战斗奖励卡片委托（自 GameViewModel 拆出，行为零变更）。
 *
 * 奖励卡片入队/清空：历战/战斗结算等界面调用，经引擎线程执行。
 */
class BattleRewardDelegate(private val gameEngine: GameEngine) {

    fun enqueueBattleRewardCards() {
        gameEngine.launchOnEngine {
            val cards = gameEngine.pendingBattleRewardCards.value
            if (cards.isNotEmpty()) {
                gameEngine.enqueueRewardCards(cards)
                gameEngine.clearPendingBattleRewardCards()
            }
        }
    }

    /** 奖励卡片入队开始动效（历战/战斗结算等界面调用，引擎线程执行） */
    fun enqueueRewardCards(cards: List<RewardCardItem>) {
        gameEngine.launchOnEngine { gameEngine.enqueueRewardCards(cards) }
    }

    fun clearRewardCardQueue(count: Int = Int.MAX_VALUE) { gameEngine.clearRewardCardQueue(count) }
}
