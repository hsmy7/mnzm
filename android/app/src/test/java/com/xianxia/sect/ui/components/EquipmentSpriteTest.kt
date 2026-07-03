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
            spiritStoneSprites = emptyMap(),
            materialSprites = emptyMap(),
            storageBagSprites = emptyMap(),
            sectIconSprites = emptyMap(),
            allEquipmentResIds = emptyList()
        )
        // 通过 register API 注册测试用的精灵图数据（herb/seed/growing 均通过 resolve(name) 查找）
        SpriteResRegistry.register(SpriteCategory.ITEM, mapOf(
            "聚灵草" to FAKE_HERB_GRASS1,
            "寒霜草" to FAKE_HERB_GRASS4,
            "云雾花" to 1101,
            "五行果" to 1206,
            "聚灵草种" to FAKE_SEED_GRASS1,
            "寒霜草种" to FAKE_SEED_GRASS4,
            "五行果核" to 2206,
            "growing_spiritGrass1" to FAKE_GROWING_GRASS1,
            "growing_spiritGrass4" to FAKE_GROWING_GRASS4
        ))
    }

    @After
    fun tearDown() {
        // 恢复空状态，避免影响其他测试类
        SpriteResRegistry.initialize(
            equipmentSprites = emptyMap(),
            manualSprites = emptyMap(),
            pillSprites = emptyMap(),
            spiritStoneSprites = emptyMap(),
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
    fun `fallbackToTier1 - num 7 returns tier1 equivalent`() {
        // num 7-9 (Tier 3) 通过 (num-1)%3+1 回退到 tier1-3
        assertEquals("spiritGrass1", fallbackToTier1("spiritGrass7"))
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
    fun `fallbackToTier1 - fruit num 7 returns tier1 equivalent`() {
        assertEquals("spiritFruit1", fallbackToTier1("spiritFruit7"))
    }

    @Test
    fun `fallbackToTier1 - flower num 9 returns tier3 equivalent`() {
        assertEquals("spiritFlower3", fallbackToTier1("spiritFlower9"))
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
    fun `herbSpriteRes - tier3 unregistered falls back to tier1 sprite`() {
        // 龙血草 (spiritGrass7) — Tier 3，未注册时通过 fallbackToTier1 回退到 tier1
        val result = herbSpriteRes("龙血草")
        assertNotNull(result)
        assertEquals(FAKE_HERB_GRASS1, result)
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
    fun `seedSpriteRes - tier3 unregistered falls back to tier1 sprite`() {
        // 龙血草种 (spiritGrass7Seed) — Tier 3，未注册时回退到 tier1
        val result = seedSpriteRes("龙血草种")
        assertNotNull(result)
        assertEquals(FAKE_SEED_GRASS1, result)
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
    fun `growingSpriteRes - tier3 unregistered falls back to tier1 sprite`() {
        // spiritGrass7 — Tier 3，未注册时通过 growing_spiritGrass1 回退
        val result = growingSpriteRes("spiritGrass7")
        assertNotNull(result)
        assertEquals(FAKE_GROWING_GRASS1, result)
    }

    @Test
    fun `growingSpriteRes - tier6 unregistered returns null`() {
        assertNull(growingSpriteRes("spiritFruit18"))
    }
}
