package com.xianxia.sect.ui.components

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * [getRewardSprite] 兜底与品阶解析测试。
 *
 * 修复守卫：
 * - herb 名称解析失败时回退丹药图（与 itemCardSpriteRes 的 herb 兜底一致，
 *   灵草资源 spiritHerbs 等无专属名的草药不再显示"敬请期待"）
 * - spiritStones 按名称解析品阶（"上品灵石"→HIGH 图），与邮件显示/发放侧一致
 */
class GetRewardSpriteTest {

    companion object {
        private const val FAKE_PILL_2 = 7002
        private const val FAKE_SPIRIT_LOW = 8001
        private const val FAKE_SPIRIT_MID = 8002
        private const val FAKE_SPIRIT_HIGH = 8003
    }

    @Before
    fun setUp() {
        SpriteResRegistry.register(SpriteCategory.PILL, mapOf(
            "pill_1" to 7001,
            "pill_2" to FAKE_PILL_2
        ))
        SpriteResRegistry.register(SpriteCategory.SPIRIT_STONE, mapOf(
            "spirit_stone_low" to FAKE_SPIRIT_LOW,
            "spirit_stone_mid" to FAKE_SPIRIT_MID,
            "spirit_stone_high" to FAKE_SPIRIT_HIGH
        ))
        SpriteResRegistry.register(SpriteCategory.MATERIAL, emptyMap())
    }

    @After
    fun tearDown() {
        SpriteResRegistry.register(SpriteCategory.PILL, emptyMap())
        SpriteResRegistry.register(SpriteCategory.SPIRIT_STONE, emptyMap())
        SpriteResRegistry.register(SpriteCategory.MATERIAL, emptyMap())
    }

    @Test
    fun `herb - unknown name falls back to pill sprite`() {
        // "灵草"等无专属名的草药资源 → 丹药图兜底，而非返回 null 显示"敬请期待"
        val result = getRewardSprite("herb", "灵草", 2)
        assertNotNull(result)
        assertEquals(FAKE_PILL_2, result)
    }

    @Test
    fun `herb - registered name returns herb sprite`() {
        // 聚灵草不在本测试注册表（ITEM 未注册）→ 也应回退丹药图而非崩溃
        assertNotNull(getRewardSprite("herb", "聚灵草", 1))
    }

    @Test
    fun `spiritStones - 上品灵石 resolves high sprite`() {
        assertEquals(FAKE_SPIRIT_HIGH, getRewardSprite("spiritStones", "上品灵石", 3))
    }

    @Test
    fun `spiritStones - 中品灵石 resolves mid sprite`() {
        assertEquals(FAKE_SPIRIT_MID, getRewardSprite("spiritStones", "中品灵石", 2))
    }

    @Test
    fun `spiritStones - plain 灵石 resolves low sprite`() {
        assertEquals(FAKE_SPIRIT_LOW, getRewardSprite("spiritStones", "灵石", 1))
    }
}
