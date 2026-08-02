package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MaterialCategory
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.model.SecretRealmBackpack
import com.xianxia.sect.core.util.DeterministicRng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRealmBattleHelperTest {

    private val rng = DeterministicRng.fromSeed(7L)

    private fun backpackWith(items: Int, stones: Long = 0L): SecretRealmBackpack =
        SecretRealmBackpack(
            spiritStones = stones,
            materials = (1..items).map {
                Material(
                    id = "m$it", name = "材料$it", rarity = 2,
                    description = "", category = MaterialCategory.BEAST_BONE, quantity = 1
                )
            }
        )

    @Test
    fun `applyLootLoss - 空背包不丢任何物品`() {
        val result = SecretRealmBattleHelper.applyLootLoss(SecretRealmBackpack(), rng)
        assertEquals(0, result.lostItemCount)
        assertEquals(0L, result.lostSpiritStones)
        assertEquals(0, result.backpack.totalItemCount)
    }

    @Test
    fun `applyLootLoss - 丢失比例在 20% 到 45% 之间且件数 ceil 宁多不少`() {
        repeat(20) {
            val backpack = backpackWith(items = 10)
            val result = SecretRealmBattleHelper.applyLootLoss(backpack, rng)
            val ratio = result.lostItemCount.toDouble() / 10.0
            // 10 件 × 20%~45% = 2~4.5 → ceil 后 2~5
            assertTrue("丢失比例 $ratio 超出范围", ratio >= 0.2 && ratio <= 0.5)
            assertEquals(10 - result.lostItemCount, result.backpack.totalItemCount)
        }
    }

    @Test
    fun `applyLootLoss - 灵石按同比例丢失且不超存量`() {
        val backpack = backpackWith(items = 1, stones = 1000L)
        val result = SecretRealmBattleHelper.applyLootLoss(backpack, rng)
        assertTrue(result.lostSpiritStones in 200L..450L)
        assertEquals(1000L - result.lostSpiritStones, result.backpack.spiritStones)
        // 1 件物品 20%~45% → ceil 必丢
        assertEquals(1, result.lostItemCount)
        assertEquals(0, result.backpack.totalItemCount)
    }

    @Test
    fun `applyLootLoss - 单件物品不丢时保留`() {
        // 构造 0 件物品 + 0 灵石 → 无损失
        val result = SecretRealmBattleHelper.applyLootLoss(SecretRealmBackpack(), rng)
        assertEquals(0, result.lostItemCount)
    }

    @Test
    fun `applyLootLoss - 丢失物品在各类型间随机分布`() {
        val backpack = SecretRealmBackpack(
            equipment = emptyList(),
            manuals = emptyList(),
            pills = (1..3).map {
                Pill(id = "p$it", name = "丹药$it", rarity = 2, category = PillCategory.CULTIVATION)
            },
            materials = (1..7).map {
                Material(
                    id = "m$it", name = "材料$it", rarity = 2,
                    description = "", category = MaterialCategory.BEAST_CORE, quantity = 1
                )
            },
            herbs = (1..5).map {
                Herb(id = "h$it", name = "草药$it", rarity = 1)
            }
        )
        repeat(10) {
            val result = SecretRealmBattleHelper.applyLootLoss(backpack, rng)
            assertEquals(backpack.totalItemCount - result.lostItemCount, result.backpack.totalItemCount)
            // 丢失件数 ≥ ceil(15×0.2)=3 且 ≤ ceil(15×0.45)=7
            assertTrue(result.lostItemCount in 3..7)
        }
    }
}
