package com.xianxia.sect.analytics

import com.xianxia.sect.core.util.AnalyticsEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 事件字典守卫测试（CLAUDE.md 9.5 跨域变更守卫）。
 *
 * 新增事件必须同步三处：`AnalyticsEvents` 常量 + 知识库事件字典 + TapDB 后台「事件管理」；
 * 本测试拦截"加事件漏登记"的漂移（rules/data-analytics.md 1.2 事件字典维护）。
 */
class AnalyticsEventsDictionaryTest {

    @Test
    fun `自定义事件遵循 TapDB 井号前缀规范且兼容事件保持旧名`() {
        val customEvents = listOf(
            AnalyticsEvents.GAME_NEW_SAVE,
            AnalyticsEvents.BATTLE_FIRST_WIN,
            AnalyticsEvents.BREAKTHROUGH_SUCCESS,
            AnalyticsEvents.BREAKTHROUGH_FIRST,
            AnalyticsEvents.AD_REWARD_CLAIM
        )
        customEvents.forEach { event ->
            assertTrue("自定义事件 $event 必须以 # 开头（TapDB 规范）", event.startsWith("#"))
        }
        assertTrue("预置特殊事件 #ad_show 以 # 开头", AnalyticsEvents.AD_SHOW.startsWith("#"))
        assertTrue(
            "兼容事件 game_start 保持旧名（不得加 #）",
            !AnalyticsEvents.GAME_START.startsWith("#")
        )
        assertTrue(
            "兼容事件 battle_end 保持旧名（不得加 #）",
            !AnalyticsEvents.BATTLE_END.startsWith("#")
        )
    }

    @Test
    fun `事件常量与知识库事件字典登记一一对应`() {
        val constants = setOf(
            AnalyticsEvents.AD_SHOW,
            AnalyticsEvents.GAME_NEW_SAVE,
            AnalyticsEvents.BATTLE_FIRST_WIN,
            AnalyticsEvents.BREAKTHROUGH_SUCCESS,
            AnalyticsEvents.BREAKTHROUGH_FIRST,
            AnalyticsEvents.AD_REWARD_CLAIM,
            AnalyticsEvents.GAME_START,
            AnalyticsEvents.BATTLE_END
        )
        // 知识库「事件字典」章节登记镜像：新增事件两处同步登记，否则本断言失败
        val dictionary = setOf(
            "#ad_show",
            "#game_new_save",
            "#battle_first_win",
            "#breakthrough_success",
            "#breakthrough_first",
            "#ad_reward_claim",
            "game_start",
            "battle_end"
        )
        assertEquals("事件常量与字典登记必须一一对应（新增事件漏登记即失败）", dictionary, constants)
    }

    @Test
    fun `事件名互不重复`() {
        val values = listOf(
            AnalyticsEvents.AD_SHOW,
            AnalyticsEvents.GAME_NEW_SAVE,
            AnalyticsEvents.BATTLE_FIRST_WIN,
            AnalyticsEvents.BREAKTHROUGH_SUCCESS,
            AnalyticsEvents.BREAKTHROUGH_FIRST,
            AnalyticsEvents.AD_REWARD_CLAIM,
            AnalyticsEvents.GAME_START,
            AnalyticsEvents.BATTLE_END
        )
        assertEquals("事件名不得重复", values.size, values.toSet().size)
    }
}
