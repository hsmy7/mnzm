package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.AdFreeWhitelist
import com.xianxia.sect.core.GameConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 广告播放委托测试（2026-08-11 新增，clock 注入后确定性）。
 *
 * 覆盖：60s 冷却 / 每日 20 次上限与回滚 / 跨天重置 / 白名单跳过冷却与上限 /
 * 上限后冷却过期仍不可看。
 */
class AdsDelegateTest {

    private var nowMs = 0L
    private lateinit var delegate: AdsDelegate

    @Before
    fun setup() {
        AdsDelegate.resetForTest()
        AdFreeWhitelist.initialize(null)
        // 固定某日正午（本地时区，跨天判定依赖 getTodayStartMs 变化）
        nowMs = 1_700_000_000_000L
        delegate = AdsDelegate { nowMs }
    }

    @After
    fun tearDown() {
        AdsDelegate.resetForTest()
        AdFreeWhitelist.initialize(null)
    }

    // ── 冷却 ──

    @Test
    fun `观看后进入 60 秒冷却，超时解除`() {
        assertFalse("初始无冷却", delegate.isAdOnCooldown())
        assertTrue(delegate.tryMarkAdWatched())
        assertTrue(delegate.isAdOnCooldown())
        nowMs += 59_000L
        assertTrue("冷却期内仍冷却", delegate.isAdOnCooldown())
        nowMs += 1_000L
        assertFalse("满 60s 解除冷却", delegate.isAdOnCooldown())
    }

    // ── 每日上限 ──

    @Test
    fun `第 21 次观看返回 false 且计数回滚`() {
        repeat(20) { assertTrue("第 ${it + 1} 次应成功", delegate.tryMarkAdWatched()) }
        assertTrue("满 20 次后达上限", delegate.isDailyAdLimitReached())
        assertFalse("第 21 次应被拒", delegate.tryMarkAdWatched())
        assertEquals("计数回滚后剩余 0", 0, delegate.getRemainingDailyAds())
    }

    @Test
    fun `达上限后冷却过期仍不可观看`() {
        repeat(20) { delegate.tryMarkAdWatched() }
        nowMs += 60_000L
        assertFalse("上限与冷却独立判定", delegate.isAdOnCooldown())
        assertTrue("跨冷却后仍达上限", delegate.isDailyAdLimitReached())
        assertFalse(delegate.tryMarkAdWatched())
    }

    // ── 跨天 ──

    @Test
    fun `跨天后计数重置可继续观看`() {
        repeat(20) { delegate.tryMarkAdWatched() }
        nowMs += 24 * 60 * 60 * 1000L // 次日同刻（无夏令时地区必然跨天）
        assertFalse("次日重置", delegate.isDailyAdLimitReached())
        assertTrue(delegate.tryMarkAdWatched())
        assertEquals(19, delegate.getRemainingDailyAds())
    }

    // ── 白名单 ──

    @Test
    fun `白名单用户跳过冷却与每日上限`() {
        val whitelistedId = GameConfig.Whitelist.AD_FREE_UNION_IDS.first()
        AdFreeWhitelist.initialize(whitelistedId)
        assertFalse("白名单恒无冷却", delegate.isAdOnCooldown())
        repeat(30) { assertTrue("白名单无视上限", delegate.tryMarkAdWatched()) }
        assertFalse(delegate.isDailyAdLimitReached())
        assertEquals(Int.MAX_VALUE, delegate.getRemainingDailyAds())
        // 白名单不写冷却：时钟推进后依旧无冷却
        nowMs += 1L
        assertFalse(delegate.isAdOnCooldown())
    }
}
