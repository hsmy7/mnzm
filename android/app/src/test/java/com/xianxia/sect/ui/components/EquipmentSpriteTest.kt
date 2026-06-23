package com.xianxia.sect.ui.components

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 测试 [fallbackToTier1]、[herbSpriteRes]、[seedSpriteRes]、[growingSpriteRes]
 * 在精灵图注册变更后的行为。
 *
 * 关键变更：fallbackToTier1 对 ID 7-18（Tier 3-6）返回 null，
 * 而非回退到 Tier 1 精灵图。
 */
class EquipmentSpriteTest {

    companion object {
        // 假精灵图资源 ID，用于测试 SpriteResRegistry 查找
        private const val FAKE_HERB_GRASS1 = 1001
        private const val FAKE_HERB_GRASS4 = 1004
        private const val FAKE_SEED_GRASS1 = 2001
        private const val FAKE_SEED_GRASS4 = 2004
        private const val FAKE_GROWING_GRASS1 = 3001
        private const val FAKE_GROWING_GRASS4 = 3004
    }

    @Before
    fun setUp() {
        // 初始化 SpriteResRegistry，模拟 Tier 1-2 已注册的场景
        SpriteResRegistry.initialize(
            equipmentSprites = emptyMap(),
            manualSprites = emptyMap(),
            pillSprites = emptyMap(),
            spiritStoneRes = 0,
            materialSprites = emptyMap(),
            storageBagSprites = emptyMap(),
            sectIconSprites = emptyMap(),
            allEquipmentResIds = emptyList(),
            herbSprites = mapOf(
                "spiritGrass1" to FAKE_HERB_GRASS1,
                "spiritGrass2" to 1002,
                "spiritGrass3" to 1003,
                "spiritGrass4" to FAKE_HERB_GRASS4,
                "spiritGrass5" to 1005,
                "spiritGrass6" to 1006,
                "spiritFlower1" to 1101,
                "spiritFlower2" to 1102,
                "spiritFlower3" to 1103,
                "spiritFlower4" to 1104,
                "spiritFlower5" to 1105,
                "spiritFlower6" to 1106,
                "spiritFruit1" to 1201,
                "spiritFruit2" to 1202,
                "spiritFruit3" to 1203,
                "spiritFruit4" to 1204,
                "spiritFruit5" to 1205,
                "spiritFruit6" to 1206
            ),
            seedSprites = mapOf(
                "spiritGrass1" to FAKE_SEED_GRASS1,
                "spiritGrass2" to 2002,
                "spiritGrass3" to 2003,
                "spiritGrass4" to FAKE_SEED_GRASS4,
                "spiritGrass5" to 2005,
                "spiritGrass6" to 2006,
                "spiritFlower1" to 2101,
                "spiritFlower2" to 2102,
                "spiritFlower3" to 2103,
                "spiritFlower4" to 2104,
                "spiritFlower5" to 2105,
                "spiritFlower6" to 2106,
                "spiritFruit1" to 2201,
                "spiritFruit2" to 2202,
                "spiritFruit3" to 2203,
                "spiritFruit4" to 2204,
                "spiritFruit5" to 2205,
                "spiritFruit6" to 2206
            ),
            growingSprites = mapOf(
                "spiritGrass1" to FAKE_GROWING_GRASS1,
                "spiritGrass2" to 3002,
                "spiritGrass3" to 3003,
                "spiritGrass4" to FAKE_GROWING_GRASS4,
                "spiritGrass5" to 3005,
                "spiritGrass6" to 3006,
                "spiritFlower1" to 3101,
                "spiritFlower2" to 3102,
                "spiritFlower3" to 3103,
                "spiritFlower4" to 3104,
                "spiritFlower5" to 3105,
                "spiritFlower6" to 3106,
                "spiritFruit1" to 3201,
                "spiritFruit2" to 3202,
                "spiritFruit3" to 3203,
                "spiritFruit4" to 3204,
                "spiritFruit5" to 3205,
                "spiritFruit6" to 3206
            )
        )
    }

    @After
    fun tearDown() {
        // 恢复空状态，避免影响其他测试类
        SpriteResRegistry.initialize(
            equipmentSprites = emptyMap(),
            manualSprites = emptyMap(),
            pillSprites = emptyMap(),
            spiritStoneRes = 0,
            materialSprites = emptyMap(),
            storageBagSprites = emptyMap(),
            sectIconSprites = emptyMap(),
            allEquipmentResIds = emptyList()
        )
    }

    // ============================================================
    // fallbackToTier1 测试
    // ============================================================

    @Test
    fun `fallbackToTier1 - empty string returns null`() {
        assertNull(fallbackToTier1(""))
    }

    @Test
    fun `fallbackToTier1 - no digits returns null`() {
        assertNull(fallbackToTier1("spiritGrass"))
    }

    @Test
    fun `fallbackToTier1 - num 1 returns same ID`() {
        assertEquals("spiritGrass1", fallbackToTier1("spiritGrass1"))
    }

    @Test
    fun `fallbackToTier1 - num 4 falls back to 1`() {
        assertEquals("spiritGrass1", fallbackToTier1("spiritGrass4"))
    }

    @Test
    fun `fallbackToTier1 - num 5 falls back to 2`() {
        assertEquals("spiritFlower2", fallbackToTier1("spiritFlower5"))
    }

    @Test
    fun `fallbackToTier1 - num 6 falls back to 3`() {
        assertEquals("spiritFruit3", fallbackToTier1("spiritFruit6"))
    }

    @Test
    fun `fallbackToTier1 - num 7 returns null - tier 3 no fallback`() {
        assertNull(fallbackToTier1("spiritGrass7"))
    }

    @Test
    fun `fallbackToTier1 - num 10 returns null - tier 4 no fallback`() {
        assertNull(fallbackToTier1("spiritGrass10"))
    }

    @Test
    fun `fallbackToTier1 - num 18 returns null - tier 6 no fallback`() {
        assertNull(fallbackToTier1("spiritFruit18"))
    }

    @Test
    fun `fallbackToTier1 - fruit num 7 returns null`() {
        assertNull(fallbackToTier1("spiritFruit7"))
    }

    @Test
    fun `fallbackToTier1 - flower num 9 returns null`() {
        assertNull(fallbackToTier1("spiritFlower9"))
    }

    // ============================================================
    // herbSpriteRes 测试
    // ============================================================

    @Test
    fun `herbSpriteRes - tier1 registered returns sprite`() {
        val result = herbSpriteRes("聚灵草")
        assertNotNull(result)
        assertEquals(FAKE_HERB_GRASS1, result)
    }

    @Test
    fun `herbSpriteRes - tier2 registered returns sprite`() {
        val result = herbSpriteRes("寒霜草")
        assertNotNull(result)
        assertEquals(FAKE_HERB_GRASS4, result)
    }

    @Test
    fun `herbSpriteRes - tier3 unregistered returns null`() {
        // 龙血草 (spiritGrass7) — Tier 3，未注册，不应回退
        assertNull(herbSpriteRes("龙血草"))
    }

    @Test
    fun `herbSpriteRes - unknown name returns null`() {
        assertNull(herbSpriteRes("不存在的草药"))
    }

    @Test
    fun `herbSpriteRes - tier1 flower registered returns sprite`() {
        val result = herbSpriteRes("云雾花")
        assertNotNull(result)
        assertEquals(1101, result)
    }

    @Test
    fun `herbSpriteRes - tier2 fruit registered returns sprite`() {
        val result = herbSpriteRes("五行果")
        assertNotNull(result)
        assertEquals(1206, result)
    }

    // ============================================================
    // seedSpriteRes 测试
    // ============================================================

    @Test
    fun `seedSpriteRes - tier1 registered returns sprite`() {
        val result = seedSpriteRes("聚灵草种")
        assertNotNull(result)
        assertEquals(FAKE_SEED_GRASS1, result)
    }

    @Test
    fun `seedSpriteRes - tier2 registered returns sprite`() {
        val result = seedSpriteRes("寒霜草种")
        assertNotNull(result)
        assertEquals(FAKE_SEED_GRASS4, result)
    }

    @Test
    fun `seedSpriteRes - tier3 unregistered returns null`() {
        // 龙血草种 — Tier 3，不应回退
        assertNull(seedSpriteRes("龙血草种"))
    }

    @Test
    fun `seedSpriteRes - fruit seed tier2 registered returns sprite`() {
        val result = seedSpriteRes("五行果核")
        assertNotNull(result)
        assertEquals(2206, result)
    }

    // ============================================================
    // growingSpriteRes 测试
    // ============================================================

    @Test
    fun `growingSpriteRes - tier1 registered returns sprite`() {
        val result = growingSpriteRes("spiritGrass1")
        assertNotNull(result)
        assertEquals(FAKE_GROWING_GRASS1, result)
    }

    @Test
    fun `growingSpriteRes - tier2 registered returns sprite`() {
        val result = growingSpriteRes("spiritGrass4")
        assertNotNull(result)
        assertEquals(FAKE_GROWING_GRASS4, result)
    }

    @Test
    fun `growingSpriteRes - tier3 unregistered returns null`() {
        // spiritGrass7 — Tier 3，不应回退
        assertNull(growingSpriteRes("spiritGrass7"))
    }

    @Test
    fun `growingSpriteRes - tier6 unregistered returns null`() {
        assertNull(growingSpriteRes("spiritFruit18"))
    }
}
