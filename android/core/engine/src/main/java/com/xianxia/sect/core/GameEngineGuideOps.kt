package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.RewardCardItem
import com.xianxia.sect.core.model.SectPolicies
import com.xianxia.sect.core.model.StorageBag
import com.xianxia.sect.core.model.guide.GuideCounterKeys
import com.xianxia.sect.core.util.RngPartition

/**
 * 引导系统引擎操作 — 发放奖励、更新计数器。
 */
fun GameEngine.claimGuideReward(taskId: Int): Boolean {
    val task = com.xianxia.sect.core.model.guide.GuideTaskRegistry.getTask(taskId) ?: return false
    val rng = gameRngManager.getRng(RngPartition.SYSTEM)
    var claimed = false
    stateStore.update {
        val gd = gameData
        if (taskId in gd.guideClaimedRewardIds) return@update
        if (!task.conditions.all { it.isMet(gd) }) return@update

        val bagName = StorageBag.TIER_NAMES[0]
        val quantity = task.rewardItemQuantity
        // 统一委托 addStorageBag（走 StackableItemStore 合并，同稀有度自动合并）
        inventorySystem.addStorageBag(
            StorageBag(
                id = java.util.UUID(rng.nextLong(), rng.nextLong()).toString(),
                name = bagName,
                rarity = 1,
                quantity = quantity
            )
        )
        gameData = gd.copy(
            guideClaimedRewardIds = gd.guideClaimedRewardIds + taskId
        )
        claimed = true
    }
    if (claimed) {
        // 入队奖励卡片，触发 RewardCardHost 飞出动画
        stateStore.enqueueRewardCards(listOf(
            RewardCardItem(
                itemName = StorageBag.TIER_NAMES[0],
                itemType = "storageBag",
                rarity = 1,
                quantity = task.rewardItemQuantity
            )
        ))
    }
    return claimed
}

/**
 * 批量更新自动分配策略与引导计数器（合并为单次事务）。
 */
fun GameEngine.batchUpdateAutoAssignAndGuide(
    oldPolicies: SectPolicies,
    newPolicies: SectPolicies,
    mineActivated: Boolean,
    plantActivated: Boolean,
    productionActivated: Boolean
) {
    stateStore.update {
        val gd = gameData
        var counters = gd.guideCounters
        if (mineActivated) {
            val cur = counters[GuideCounterKeys.AUTO_MINE_ACTIVATED] ?: 0L
            counters = counters + (GuideCounterKeys.AUTO_MINE_ACTIVATED to cur + 1)
        }
        if (plantActivated) {
            val cur = counters[GuideCounterKeys.AUTO_PLANT_ACTIVATED] ?: 0L
            counters = counters + (GuideCounterKeys.AUTO_PLANT_ACTIVATED to cur + 1)
        }
        if (productionActivated) {
            val cur = counters[GuideCounterKeys.AUTO_PRODUCTION_ACTIVATED] ?: 0L
            counters = counters + (GuideCounterKeys.AUTO_PRODUCTION_ACTIVATED to cur + 1)
        }
        gameData = gd.copy(
            sectPolicies = newPolicies,
            guideCounters = counters
        )
    }
}

/**
 * 递增引导计数器。
 */
fun GameEngine.incrementGuideCounter(key: String, amount: Long = 1) {
    stateStore.update {
        val currentCount = gameData.guideCounters[key] ?: 0L
        gameData = gameData.copy(
            guideCounters = gameData.guideCounters + (key to currentCount + amount)
        )
    }
}
