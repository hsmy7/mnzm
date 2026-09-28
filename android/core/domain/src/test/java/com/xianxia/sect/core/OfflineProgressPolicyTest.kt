package com.xianxia.sect.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 离线收益折算口径测试（结算改造 2026-09-27 §10 B7；§1.4 已拍板口径锁死）。
 *
 * 口径：12 现实小时全额（1x 速率）→ 之后 50% 速率 → 24 现实小时硬顶，
 * 再超出不再累积；结果按整旬交付（floor 到 2000 游戏毫秒）。
 *
 * 边界 7 档（方案 §5.4 对抗性审查第 3 条 + 派发件）：0 / 短时段 / 12h 整 /
 * 12–24h 线性段 / 24h 硬顶 / 超 24h / 边界毫秒级 ±1（附负数时钟回拨防御）。
 */
class OfflineProgressPolicyTest {

    private companion object {
        const val PHASE_MS = 2000L
        const val H = 3_600_000L    // 1 现实小时
        const val FULL = 12 * H     // 全额段边界：12h
        const val CAP = 24 * H      // 硬顶边界：24h
        /** 硬顶注入总量：12h 全额 + 12h × 50% = 18h 游戏时间 */
        const val CAP_GAME_MS = 18 * H
    }

    // ── 档 1：零时段 ────────────────────────────────────────────────

    @Test
    fun zeroOfflineTimeInjectsNothing() {
        assertEquals(0L, GameConfig.Time.offlineGameMs(0L))
    }

    // ── 档 2：短时段（全额段内） ─────────────────────────────────────

    @Test
    fun shortDurationInsideFullRateWindow() {
        // 1h 整旬对齐 → 全额无损失
        assertEquals(1 * H, GameConfig.Time.offlineGameMs(1 * H))
        // 30 分钟
        assertEquals(30L * 60_000L, GameConfig.Time.offlineGameMs(30L * 60_000L))
    }

    @Test
    fun subPhaseDurationFloorsToZero() {
        // 不足一旬（<2 游戏秒）：整旬交付语义 floor 到 0（损失 <1 旬可忽略）
        assertEquals(0L, GameConfig.Time.offlineGameMs(1000L))
        assertEquals(0L, GameConfig.Time.offlineGameMs(1999L))
        // 恰好 1 旬起有产出
        assertEquals(PHASE_MS, GameConfig.Time.offlineGameMs(2000L))
    }

    // ── 档 3：12h 整（全额段上边界） ────────────────────────────────

    @Test
    fun exactlyFullRateWindowDeliversFullAmount() {
        assertEquals(FULL, GameConfig.Time.offlineGameMs(FULL))
    }

    // ── 档 4：12–24h 线性段（50% 速率） ─────────────────────────────

    @Test
    fun reducedRateSegmentIsLinearHalfRate() {
        // 13h = 12h 全额 + 1h × 50%
        assertEquals(FULL + H / 2, GameConfig.Time.offlineGameMs(13 * H))
        // 18h = 12h + 6h × 50%
        assertEquals(FULL + 3 * H, GameConfig.Time.offlineGameMs(18 * H))
    }

    // ── 档 5：24h 硬顶 ──────────────────────────────────────────────

    @Test
    fun exactlyHardCapDeliversCapTotal() {
        assertEquals(CAP_GAME_MS, GameConfig.Time.offlineGameMs(CAP))
    }

    // ── 档 6：超 24h（不再累积） ─────────────────────────────────────

    @Test
    fun beyondHardCapStopsAccruing() {
        assertEquals(CAP_GAME_MS, GameConfig.Time.offlineGameMs(48 * H))
        assertEquals(CAP_GAME_MS, GameConfig.Time.offlineGameMs(72 * H))
        assertEquals(CAP_GAME_MS, GameConfig.Time.offlineGameMs(7L * 24 * H))
    }

    // ── 档 7：边界毫秒级 ±1（连续性） ───────────────────────────────

    @Test
    fun boundaryMillisecondContinuity() {
        // 12h±1：全额段边界两侧差 ≤1 旬（floor 粒度）
        assertEquals(FULL - PHASE_MS, GameConfig.Time.offlineGameMs(FULL - 1L))
        assertEquals(FULL, GameConfig.Time.offlineGameMs(FULL + 1L))

        // 24h±1：硬顶边界两侧差 ≤1 旬，且 +1 侧已达硬顶总量
        assertEquals(CAP_GAME_MS - PHASE_MS, GameConfig.Time.offlineGameMs(CAP - 1L))
        assertEquals(CAP_GAME_MS, GameConfig.Time.offlineGameMs(CAP + 1L))
    }

    // ── 附：负数（时钟回拨防御） ────────────────────────────────────

    @Test
    fun negativeOfflineTimeClampedToZero() {
        assertEquals(0L, GameConfig.Time.offlineGameMs(-1L))
        assertEquals(0L, GameConfig.Time.offlineGameMs(-24 * H))
    }

    // ── 结构不变量：结果恒为旬长整数倍（权威轴旬网格对齐） ──────────

    @Test
    fun resultIsAlwaysPhaseGridAligned() {
        for (hours in 0L..48L) {
            val result = GameConfig.Time.offlineGameMs(hours * H + 1234L)
            assertEquals(
                "离线 ${hours}h+1234ms 折算结果必须整旬对齐",
                0L, result % PHASE_MS
            )
        }
    }

    @Test
    fun monotonicNonDecreasingInOfflineTime() {
        var previous = 0L
        for (hours in 0L..50L) {
            val result = GameConfig.Time.offlineGameMs(hours * H)
            assertTrue("折算必须随时段单调不减", result >= previous)
            previous = result
        }
    }
}
