package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.model.ClearRewardItem
import com.xianxia.sect.core.model.HeavenlyTrialClearReward
import com.xianxia.sect.core.model.StorageBag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HeavenlyTrialService.checkRewardCapacity] 单元测试。
 *
 * 覆盖历史 bug：
 * - 旧实现在 stateStore.update 块内设置 capacityError，但 claimedRewardLevels flag
 *   无条件写入，导致用户"奖励未实际发放但无法重领"
 * - 旧实现 randomPill 分支达堆叠上限时静默丢弃，未设置 capacityError
 *
 * 修复方案：提取纯函数 [HeavenlyTrialService.checkRewardCapacity] 在事务外预校验，
 * 通过后才进入事务写入 flag。
 *
 * randomEquipment/randomManual 已改为使用 EquipmentDatabase/ManualDatabase
 * 直接生成，不再依赖玩家库存，无需预校验。
 */
class HeavenlyTrialRewardCapacityTest {

    private val inventoryConfig = InventoryConfig()

    // ── storageBag 分支 ──────────────────────────────────────────────

    @Test
    fun `checkRewardCapacity - 储物袋未达上限时返回Ok`() {
        val reward = HeavenlyTrialClearReward(
            levelIndex = 0,
            label = "第一关",
            items = listOf(
                ClearRewardItem("凡品储物袋", 5, "storageBag", 1)
            )
        )
        val storageBags = listOf(
            StorageBag(id = "bag1", name = "凡品储物袋", rarity = 1, quantity = 10)
        )

        val result = HeavenlyTrialService.checkRewardCapacity(
            reward = reward,
            storageBags = storageBags,
            inventoryConfig = inventoryConfig
        )

        assertTrue("未达上限应通过校验", result is HeavenlyTrialService.RewardCapacityCheck.Ok)
    }

    @Test
    fun `checkRewardCapacity - 储物袋已达上限时返回Failed`() {
        val reward = HeavenlyTrialClearReward(
            levelIndex = 0,
            label = "第一关",
            items = listOf(
                ClearRewardItem("凡品储物袋", 5, "storageBag", 1)
            )
        )
        val maxStack = inventoryConfig.getMaxStackSize("storageBag")
        val storageBags = listOf(
            StorageBag(id = "bag1", name = "凡品储物袋", rarity = 1, quantity = maxStack)
        )

        val result = HeavenlyTrialService.checkRewardCapacity(
            reward = reward,
            storageBags = storageBags,
            inventoryConfig = inventoryConfig
        )

        assertTrue("达上限应失败", result is HeavenlyTrialService.RewardCapacityCheck.Failed)
        val message = (result as HeavenlyTrialService.RewardCapacityCheck.Failed).message
        assertTrue("应提示储物袋上限", message.contains("储物袋"))
    }

    @Test
    fun `checkRewardCapacity - 储物袋不存在时返回Ok`() {
        val reward = HeavenlyTrialClearReward(
            levelIndex = 0,
            label = "第一关",
            items = listOf(
                ClearRewardItem("凡品储物袋", 5, "storageBag", 1)
            )
        )

        val result = HeavenlyTrialService.checkRewardCapacity(
            reward = reward,
            storageBags = emptyList(),
            inventoryConfig = inventoryConfig
        )

        assertTrue("无现有储物袋应通过", result is HeavenlyTrialService.RewardCapacityCheck.Ok)
    }

    @Test
    fun `checkRewardCapacity - 不同品阶储物袋达上限不影响其他品阶`() {
        val reward = HeavenlyTrialClearReward(
            levelIndex = 1,
            label = "第二关",
            items = listOf(
                ClearRewardItem("灵品储物袋", 5, "storageBag", 2)
            )
        )
        val maxStack = inventoryConfig.getMaxStackSize("storageBag")
        // 凡品储物袋达上限，但奖励是灵品储物袋
        val storageBags = listOf(
            StorageBag(id = "bag1", name = "凡品储物袋", rarity = 1, quantity = maxStack)
        )

        val result = HeavenlyTrialService.checkRewardCapacity(
            reward = reward,
            storageBags = storageBags,
            inventoryConfig = inventoryConfig
        )

        assertTrue("不同品阶应独立校验", result is HeavenlyTrialService.RewardCapacityCheck.Ok)
    }

    // ── 组合校验 ────────────────────────────────────────────────────

    @Test
    fun `checkRewardCapacity - spiritStones不校验直接通过`() {
        val reward = HeavenlyTrialClearReward(
            levelIndex = 0,
            label = "第一关",
            items = listOf(
                ClearRewardItem("灵石", 100_000, "spiritStones", 1)
            )
        )

        val result = HeavenlyTrialService.checkRewardCapacity(
            reward = reward,
            storageBags = emptyList(),
            inventoryConfig = inventoryConfig
        )

        assertTrue("灵石无上限应通过", result is HeavenlyTrialService.RewardCapacityCheck.Ok)
    }

    @Test
    fun `checkRewardCapacity - randomPill不校验直接通过`() {
        // randomPill 不在预校验范围内，
        // 因为 mergeStackable 会自动新建堆叠处理溢出，不存在"无法发放"的前置失败条件
        val reward = HeavenlyTrialClearReward(
            levelIndex = 4,
            label = "第五关",
            items = listOf(
                ClearRewardItem("随机玄品丹药", 5, "randomPill", 4, isRandom = true)
            )
        )

        val result = HeavenlyTrialService.checkRewardCapacity(
            reward = reward,
            storageBags = emptyList(),
            inventoryConfig = inventoryConfig
        )

        assertTrue("randomPill 应通过预校验", result is HeavenlyTrialService.RewardCapacityCheck.Ok)
    }

    @Test
    fun `checkRewardCapacity - 空物品列表返回Ok`() {
        val reward = HeavenlyTrialClearReward(
            levelIndex = 99,
            label = "测试",
            items = emptyList()
        )

        val result = HeavenlyTrialService.checkRewardCapacity(
            reward = reward,
            storageBags = emptyList(),
            inventoryConfig = inventoryConfig
        )

        assertTrue("空物品列表应通过", result is HeavenlyTrialService.RewardCapacityCheck.Ok)
    }

    @Test
    fun `checkRewardCapacity - 储物袋上限边界值精确匹配`() {
        // 边界值：quantity == maxStack 应失败（>= 判断）
        val reward = HeavenlyTrialClearReward(
            levelIndex = 0,
            label = "第一关",
            items = listOf(
                ClearRewardItem("凡品储物袋", 1, "storageBag", 1)
            )
        )
        val maxStack = inventoryConfig.getMaxStackSize("storageBag")
        val storageBags = listOf(
            StorageBag(id = "bag1", name = "凡品储物袋", rarity = 1, quantity = maxStack)
        )

        val result = HeavenlyTrialService.checkRewardCapacity(
            reward = reward,
            storageBags = storageBags,
            inventoryConfig = inventoryConfig
        )

        assertTrue("quantity==maxStack 应失败", result is HeavenlyTrialService.RewardCapacityCheck.Failed)

        // quantity = maxStack - 1 应通过
        val storageBags2 = listOf(
            StorageBag(id = "bag1", name = "凡品储物袋", rarity = 1, quantity = maxStack - 1)
        )
        val result2 = HeavenlyTrialService.checkRewardCapacity(
            reward = reward,
            storageBags = storageBags2,
            inventoryConfig = inventoryConfig
        )
        assertTrue("quantity==maxStack-1 应通过", result2 is HeavenlyTrialService.RewardCapacityCheck.Ok)
    }
}
