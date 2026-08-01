package com.xianxia.sect.core.util

import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemSortUtilsTest {

    // ==================== watchKey ====================

    @Test
    fun watchKey_拼接格式() {
        assertEquals("pill:聚气丹", watchKey("pill", "聚气丹"))
        assertEquals("equipment:精铁剑", watchKey("equipment", "精铁剑"))
    }

    // ==================== normalizeItemType ====================

    @Test
    fun normalizeItemType_别名归一化() {
        assertEquals("material", normalizeItemType("beastMaterial"))
        assertEquals("manual", normalizeItemType("manual_stack"))
        assertEquals("manual", normalizeItemType("manual_instance"))
        assertEquals("equipment", normalizeItemType("equipment_stack"))
        assertEquals("equipment", normalizeItemType("equipment_instance"))
    }

    @Test
    fun normalizeItemType_小写兜底() {
        assertEquals("pill", normalizeItemType("pill"))
        assertEquals("equipment", normalizeItemType("Equipment"))
        assertEquals("pill", normalizeItemType("PILL"))
    }

    @Test
    fun normalizeItemType_未知类型_原样小写() {
        assertEquals("spiritstones", normalizeItemType("spiritStones"))
        assertEquals("storagebag", normalizeItemType("storageBag"))
    }

    // ==================== GameItem.watchKey ====================

    @Test
    fun gameItemWatchKey_各物品类型映射正确() {
        assertEquals(
            "equipment:精铁剑",
            EquipmentStack(name = "精铁剑", rarity = 1).watchKey()
        )
        assertEquals(
            "manual:吐纳术",
            ManualStack(name = "吐纳术", rarity = 2).watchKey()
        )
        assertEquals("pill:聚气丹", Pill(name = "聚气丹", rarity = 3).watchKey())
        assertEquals("material:兽皮", Material(name = "兽皮", rarity = 1).watchKey())
        assertEquals("herb:灵芝", Herb(name = "灵芝", rarity = 2).watchKey())
        assertEquals("seed:灵草种子", Seed(name = "灵草种子", rarity = 1).watchKey())
    }

    // ==================== sortedByWatchedThenRarity ====================

    private data class TestItem(val key: String?, val rarity: Int, val name: String)

    private val items = listOf(
        TestItem("a", 2, "甲"),
        TestItem("b", 5, "乙"),
        TestItem(null, 5, "丙"),
        TestItem("c", 1, "丁")
    )

    @Test
    fun sortedByWatchedThenRarity_关注物品优先_关注组排前() {
        val sorted = items.sortedByWatchedThenRarity(
            watchedKeys = setOf("b"),
            keyOf = { it.key },
            rarityOf = { it.rarity },
            nameOf = { it.name }
        )
        // 关注组：乙(5) 排最前；未关注组按品阶降序：丙(5) 甲(2) 丁(1)
        assertEquals(listOf("乙", "丙", "甲", "丁"), sorted.map { it.name })
    }

    @Test
    fun sortedByWatchedThenRarity_组内品阶降序() {
        val sorted = items.sortedByWatchedThenRarity(
            watchedKeys = setOf("b"),
            keyOf = { it.key },
            rarityOf = { it.rarity },
            nameOf = { it.name }
        )
        // 关注组: 乙(5) 优先；未关注组: 甲(2) 丙(5) 丁(1) → 丙(5) 甲(2) 丁(1)
        assertEquals(listOf("乙", "丙", "甲", "丁"), sorted.map { it.name })
    }

    @Test
    fun sortedByWatchedThenRarity_同品阶按名称升序() {
        // 名称按 Unicode 码点升序："乙"(U+4E59=20057) < "甲"(U+7532=30002)，与仓库原排序行为一致
        val sameRarity = listOf(
            TestItem("x", 3, "甲"),
            TestItem("y", 3, "乙")
        )
        val sorted = sameRarity.sortedByWatchedThenRarity(
            watchedKeys = emptySet(),
            keyOf = { it.key },
            rarityOf = { it.rarity },
            nameOf = { it.name }
        )
        assertEquals(listOf("乙", "甲"), sorted.map { it.name })
    }

    @Test
    fun sortedByWatchedThenRarity_空关注列表_保持品阶排序() {
        val sorted = items.sortedByWatchedThenRarity(
            watchedKeys = emptySet(),
            keyOf = { it.key },
            rarityOf = { it.rarity },
            nameOf = { it.name }
        )
        assertEquals(listOf("丙", "乙", "甲", "丁"), sorted.map { it.name })
    }

    @Test
    fun sortedByWatchedThenRarity_键为null_视为未关注() {
        val keyNullItems = listOf(
            TestItem(null, 1, "无键"),
            TestItem("watched", 1, "有关注键")
        )
        val sorted = keyNullItems.sortedByWatchedThenRarity(
            watchedKeys = setOf("watched"),
            keyOf = { it.key },
            rarityOf = { it.rarity },
            nameOf = { it.name }
        )
        assertEquals(listOf("有关注键", "无键"), sorted.map { it.name })
    }

    // ==================== 键的稳定性 ====================

    @Test
    fun gameItemWatchKey_数量不影响键() {
        val pill1 = Pill(name = "聚气丹", rarity = 3, quantity = 1)
        val pill2 = Pill(name = "聚气丹", rarity = 3, quantity = 99)
        assertEquals(pill1.watchKey(), pill2.watchKey())
    }

    @Test
    fun watchableItemTypes_覆盖六类() {
        assertEquals(
            setOf("equipment", "manual", "pill", "material", "herb", "seed"),
            WATCHABLE_ITEM_TYPES
        )
    }

    @Test
    fun sortedByWatchedThenRarity_游戏物品重载() {
        val items = listOf(
            Pill(name = "丹药甲", rarity = 1),
            Pill(name = "丹药乙", rarity = 3)
        )
        val sorted = items.sortedByWatchedThenRarity(watchedKeys = setOf("pill:丹药甲"))
        assertEquals(listOf("丹药甲", "丹药乙"), sorted.map { it.name })
    }

    @Test
    fun gameItemWatchKey_不同类别同名_键不同() {
        val herb = Herb(name = "人参", rarity = 1)
        val pill = Pill(name = "人参", rarity = 1)
        assertFalse(herb.watchKey() == pill.watchKey())
        assertTrue("herb:人参" == herb.watchKey())
        assertTrue("pill:人参" == pill.watchKey())
    }
}
