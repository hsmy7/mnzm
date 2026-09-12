package com.xianxia.sect.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 奖励卡片聚合（[mergeRewardCards]）单元测试。
 *
 * 守护语义：多个相同物品必须堆叠为一张 "xN" 卡片一次飞出，
 * 而非逐张卡片依次飞出（奖励卡片动画回归防线）。
 */
class RewardCardMergeTest {

    /** 构造测试卡片，id 默认按 名称_品阶 生成，保证不同物品 id 不同 */
    private fun card(
        name: String,
        type: String = "material",
        rarity: Int = 2,
        quantity: Int = 1,
        id: String = "id_${name}_$rarity"
    ) = RewardCardItem(id = id, itemName = name, itemType = type, rarity = rarity, quantity = quantity)

    @Test
    fun `mergeRewardCards - 同名同品阶合并 quantity 求和并保留首条 id`() {
        val cards = listOf(
            card(name = "兽血", id = "first"),
            card(name = "兽血", id = "second"),
            card(name = "兽血", id = "third", quantity = 3)
        )

        val merged = cards.mergeRewardCards()

        assertEquals(1, merged.size)
        assertEquals("first", merged[0].id)
        assertEquals(5, merged[0].quantity)
    }

    @Test
    fun `mergeRewardCards - 合并后保持首次出现顺序`() {
        val cards = listOf(
            card(name = "灵石", type = "spiritStones", rarity = 1),
            card(name = "兽血"),
            card(name = "灵石", type = "spiritStones", rarity = 1),
            card(name = "丹药", type = "pill", rarity = 3)
        )

        val merged = cards.mergeRewardCards()

        assertEquals(listOf("灵石", "兽血", "丹药"), merged.map { it.itemName })
        assertEquals(2, merged[0].quantity)
    }

    @Test
    fun `mergeRewardCards - 不同名物品不合并`() {
        val cards = listOf(
            card(name = "兽血"),
            card(name = "妖丹")
        )

        val merged = cards.mergeRewardCards()

        assertEquals(2, merged.size)
    }

    @Test
    fun `mergeRewardCards - 同名不同品阶不合并`() {
        val cards = listOf(
            card(name = "兽血", rarity = 1),
            card(name = "兽血", rarity = 2)
        )

        val merged = cards.mergeRewardCards()

        assertEquals(2, merged.size)
    }

    @Test
    fun `mergeRewardCards - 同名不同类型不合并`() {
        val cards = listOf(
            card(name = "回春丹", type = "pill"),
            card(name = "回春丹", type = "herb")
        )

        val merged = cards.mergeRewardCards()

        assertEquals(2, merged.size)
    }

    @Test
    fun `mergeRewardCards - 空列表返回空`() {
        assertTrue(emptyList<RewardCardItem>().mergeRewardCards().isEmpty())
    }

    @Test
    fun `mergeRewardCards - 单张卡片原样返回`() {
        val single = listOf(card(name = "兽血"))

        assertEquals(single, single.mergeRewardCards())
    }

    @Test
    fun `mergeRewardCards - 合并结果幂等`() {
        val cards = listOf(
            card(name = "兽血", quantity = 2),
            card(name = "兽血"),
            card(name = "妖丹")
        )

        val once = cards.mergeRewardCards()

        assertEquals(once, once.mergeRewardCards())
    }

    @Test
    fun `mergeRewardCards - 数量求和溢出时饱和到 Int 最大值`() {
        val cards = listOf(
            card(name = "兽血", quantity = Int.MAX_VALUE),
            card(name = "兽血", quantity = 1)
        )

        val merged = cards.mergeRewardCards()

        assertEquals(Int.MAX_VALUE, merged[0].quantity)
    }

    @Test
    fun `mergeRewardCards - 跨批次入队合并保留队列已有卡片 id 并累加数量`() {
        // 模拟 enqueueRewardCards 两次调用：队列中已有卡片 + 新批次入队
        val queue = listOf(card(name = "兽血", id = "in-flight", quantity = 1))
        val incoming = listOf(card(name = "兽血", id = "new-batch", quantity = 2))

        val merged = (queue + incoming).mergeRewardCards()

        assertEquals(1, merged.size)
        // 保留已有卡片 id：动画播放中 Compose key 稳定，不重建不打断动画
        assertEquals("in-flight", merged[0].id)
        assertEquals(3, merged[0].quantity)
    }
}
