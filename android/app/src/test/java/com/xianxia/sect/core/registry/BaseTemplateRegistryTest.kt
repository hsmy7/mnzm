package com.xianxia.sect.core.registry

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

data class TestItem(val id: String, val name: String, val rarity: Int)

private class TestRegistry : BaseTemplateRegistry<TestItem>() {
    private val templates = mapOf(
        "item1" to TestItem("item1", "common_item", 1),
        "item2" to TestItem("item2", "uncommon_item", 2),
        "item3" to TestItem("item3", "rare_item", 3),
        "item4" to TestItem("item4", "epic_item", 4),
        "item5" to TestItem("item5", "legendary_item", 5),
        "item6" to TestItem("item6", "mythic_item", 6)
    )

    override fun loadTemplates(): Map<String, TestItem> = templates
    override fun extractRarity(template: TestItem): Int = template.rarity

    // 暴露 protected 方法供测试
    fun testPickWeightedRandom(
        candidates: List<TestItem>,
        weightExtractor: (TestItem) -> Double
    ): TestItem = pickWeightedRandom(candidates, weightExtractor)

    fun testGenerateTieredRarity(minRarity: Int, maxRarity: Int): Int =
        generateTieredRarity(minRarity, maxRarity)
}

class BaseTemplateRegistryTest {

    private lateinit var registry: TestRegistry

    @Before
    fun setUp() {
        registry = TestRegistry()
    }

    // ==================== allTemplates 测试 ====================

    @Test
    fun allTemplates_returnsAllLoadedTemplates() {
        val all = registry.allTemplates
        assertEquals(6, all.size)
        assertTrue(all.containsKey("item1"))
        assertTrue(all.containsKey("item6"))
    }

    // ==================== getById 测试 ====================

    @Test
    fun getById_returnsTemplate_forExistingId() {
        val item = registry.getById("item3")
        assertNotNull(item)
        assertEquals("rare_item", item!!.name)
        assertEquals(3, item.rarity)
    }

    @Test
    fun getById_returnsNull_forNonExistingId() {
        val item = registry.getById("nonexistent")
        assertNull(item)
    }

    // ==================== getByRarity 测试 ====================

    @Test
    fun getByRarity_returnsItemsOfSpecificRarity() {
        val items = registry.getByRarity(1)
        assertEquals(1, items.size)
        assertEquals("common_item", items[0].name)
    }

    @Test
    fun getByRarity_returnsEmptyList_forRarityWithNoItems() {
        val items = registry.getByRarity(99)
        assertTrue(items.isEmpty())
    }

    // ==================== getRandom 测试 ====================

    @Test
    fun getRandom_returnsItemWithinRarityRange() {
        val item = registry.getRandom(1, 3)
        assertTrue(item.rarity in 1..3)
    }

    @Test(expected = NoSuchElementException::class)
    fun getRandom_throwsNoSuchElementException_forEmptyRange() {
        registry.getRandom(99, 100)
    }

    // ==================== isInitialized 测试 ====================

    @Test
    fun isInitialized_returnsFalse_beforeAutoInitialize() {
        val freshRegistry = TestRegistry()
        assertFalse(freshRegistry.isInitialized())
    }

    @Test
    fun isInitialized_returnsTrue_afterAutoInitialize() {
        val freshRegistry = TestRegistry()
        freshRegistry.autoInitialize()
        assertTrue(freshRegistry.isInitialized())
    }

    // ==================== getCount 测试 ====================

    @Test
    fun getCount_returnsTotalTemplateCount() {
        assertEquals(6, registry.getCount())
    }

    // ==================== pickWeightedRandom 测试 ====================

    @Test
    fun pickWeightedRandom_selectsFromCandidates() {
        val candidates = listOf(
            TestItem("a", "heavy", 1),
            TestItem("b", "light", 2)
        )
        // 多次调用确保不抛异常且返回候选列表中的元素
        repeat(100) {
            val result = registry.testPickWeightedRandom(candidates) { item ->
                if (item.id == "a") 10.0 else 1.0
            }
            assertTrue(result.id == "a" || result.id == "b")
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun pickWeightedRandom_throwsForEmptyCandidates() {
        registry.testPickWeightedRandom(emptyList()) { 1.0 }
    }

    // ==================== generateTieredRarity 测试 ====================

    @Test
    fun generateTieredRarity_producesValuesInRange() {
        repeat(200) {
            val result = registry.testGenerateTieredRarity(1, 6)
            assertTrue("Generated rarity $result not in range [1, 6]", result in 1..6)
        }
    }

    @Test
    fun generateTieredRarity_withMinEqualsMax_returnsThatValue() {
        repeat(50) {
            assertEquals(3, registry.testGenerateTieredRarity(3, 3))
        }
    }
}
