package com.xianxia.sect.ui.components

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * [manualSpriteRes] / [pillSpriteRes] 无效稀有度回退测试。
 *
 * 修复守卫：邮件附件 rarity 缺失（默认 0）或越界时，功法/丹药应回退到
 * 1 品精灵图（与 [storageBagSpriteRes] 既有回退模式一致），而不是返回 null
 * 导致 UI 显示"敬请期待"。
 */
class EquipmentSpriteRarityFallbackTest {

    companion object {
        private const val FAKE_MANUAL_1 = 5001
        private const val FAKE_MANUAL_3 = 5003
        private const val FAKE_PILL_1 = 6001
        private const val FAKE_PILL_3 = 6003
    }

    @Before
    fun setUp() {
        SpriteResRegistry.register(SpriteCategory.MANUAL, mapOf(
            "manual_1" to FAKE_MANUAL_1,
            "manual_3" to FAKE_MANUAL_3
        ))
        SpriteResRegistry.register(SpriteCategory.PILL, mapOf(
            "pill_1" to FAKE_PILL_1,
            "pill_3" to FAKE_PILL_3
        ))
    }

    @After
    fun tearDown() {
        SpriteResRegistry.register(SpriteCategory.MANUAL, emptyMap())
        SpriteResRegistry.register(SpriteCategory.PILL, emptyMap())
    }

    @Test
    fun `manualSpriteRes - valid rarity returns sprite`() {
        assertEquals(FAKE_MANUAL_3, manualSpriteRes(3))
    }

    @Test
    fun `manualSpriteRes - rarity 0 falls back to tier1 sprite`() {
        // 邮件附件 rarity 缺失（默认 0）时不显示"敬请期待"，回退 1 品功法图
        assertEquals(FAKE_MANUAL_1, manualSpriteRes(0))
    }

    @Test
    fun `manualSpriteRes - out of range rarity falls back to tier1 sprite`() {
        assertEquals(FAKE_MANUAL_1, manualSpriteRes(7))
    }

    @Test
    fun `pillSpriteRes - valid rarity returns sprite`() {
        assertEquals(FAKE_PILL_3, pillSpriteRes(3))
    }

    @Test
    fun `pillSpriteRes - rarity 0 falls back to tier1 sprite`() {
        assertEquals(FAKE_PILL_1, pillSpriteRes(0))
    }

    @Test
    fun `pillSpriteRes - out of range rarity falls back to tier1 sprite`() {
        assertEquals(FAKE_PILL_1, pillSpriteRes(7))
    }

    @Test
    fun `manualSpriteRes - no registration returns null`() {
        SpriteResRegistry.register(SpriteCategory.MANUAL, emptyMap())
        assertNull(manualSpriteRes(1))
    }
}
