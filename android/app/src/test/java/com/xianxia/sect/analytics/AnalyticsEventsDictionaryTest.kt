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
            AnalyticsEvents.AD_REWARD_CLAIM,
            AnalyticsEvents.STORAGE_METRICS_REPORT
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
            AnalyticsEvents.STORAGE_METRICS_REPORT,
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
            "#storage_metrics_report",
            "game_start",
            "battle_end"
        )
        assertEquals("事件常量与字典登记必须一一对应（新增事件漏登记即失败）", dictionary, constants)
    }

    @Test
    fun `storage_metrics_report 属性键与知识库字典登记一一对应`() {
        // 知识库事件字典行（docs/knowledge-base.md #storage_metrics_report）属性清单镜像：
        // 新增属性键两处同步登记，否则本断言失败（SS5-c 三处同步守卫）
        val dictionary = setOf(
            "storage_save_count",
            "storage_load_count",
            "storage_cache_hit_count",
            "storage_cache_miss_count",
            "storage_backup_failure_count",
            "storage_backup_restore_count",
            "storage_backup_skipped_count",
            "storage_jade_drift_count",
            "storage_change_log_pending",
            "storage_archive_battle_log_rows",
            "storage_archive_disciple_rows",
            "storage_incremental_save_count",
            "storage_full_save_count",
            "storage_dirty_fallback_count",
            "storage_last_full_save_reason"
        )
        val constants = setOf(
            AnalyticsEvents.PROP_STORAGE_SAVE_COUNT,
            AnalyticsEvents.PROP_STORAGE_LOAD_COUNT,
            AnalyticsEvents.PROP_STORAGE_CACHE_HIT_COUNT,
            AnalyticsEvents.PROP_STORAGE_CACHE_MISS_COUNT,
            AnalyticsEvents.PROP_STORAGE_BACKUP_FAILURE_COUNT,
            AnalyticsEvents.PROP_STORAGE_BACKUP_RESTORE_COUNT,
            AnalyticsEvents.PROP_STORAGE_BACKUP_SKIPPED_COUNT,
            AnalyticsEvents.PROP_STORAGE_JADE_DRIFT_COUNT,
            AnalyticsEvents.PROP_STORAGE_CHANGE_LOG_PENDING,
            AnalyticsEvents.PROP_STORAGE_ARCHIVE_BATTLE_LOG_ROWS,
            AnalyticsEvents.PROP_STORAGE_ARCHIVE_DISCIPLE_ROWS,
            AnalyticsEvents.PROP_STORAGE_INCREMENTAL_SAVE_COUNT,
            AnalyticsEvents.PROP_STORAGE_FULL_SAVE_COUNT,
            AnalyticsEvents.PROP_STORAGE_DIRTY_FALLBACK_COUNT,
            AnalyticsEvents.PROP_STORAGE_LAST_FULL_SAVE_REASON
        )
        assertEquals(
            "#storage_metrics_report 属性键与知识库字典必须一一对应（新增属性漏登记即失败）",
            dictionary,
            constants
        )
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
            AnalyticsEvents.STORAGE_METRICS_REPORT,
            AnalyticsEvents.GAME_START,
            AnalyticsEvents.BATTLE_END
        )
        assertEquals("事件名不得重复", values.size, values.toSet().size)
    }
}
