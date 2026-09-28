package com.xianxia.sect.core.util

import org.junit.Assert.*
import org.junit.Test

class TimeProgressUtilTest {

    // ---- calculateElapsedMonths ----

    @Test
    fun calculateElapsedMonths_sameYearAndMonth_returnsZero() {
        assertEquals(0, TimeProgressUtil.calculateElapsedMonths(1, 3, 1, 3))
    }

    @Test
    fun calculateElapsedMonths_sameYear_differentMonth() {
        assertEquals(5, TimeProgressUtil.calculateElapsedMonths(1, 1, 1, 6))
    }

    @Test
    fun calculateElapsedMonths_differentYear() {
        // 2 years * 12 + (6 - 1) = 29
        assertEquals(29, TimeProgressUtil.calculateElapsedMonths(1, 1, 3, 6))
    }

    @Test
    fun calculateElapsedMonths_zeroStart() {
        assertEquals(14, TimeProgressUtil.calculateElapsedMonths(0, 0, 1, 2))
    }

    // ---- calculateRemainingMonths ----

    @Test
    fun calculateRemainingMonths_notElapsed() {
        // elapsed = 3, duration = 10 → remaining = 7
        assertEquals(7, TimeProgressUtil.calculateRemainingMonths(1, 1, 10, 1, 4))
    }

    @Test
    fun calculateRemainingMonths_fullyElapsed_returnsZero() {
        // elapsed = 12, duration = 10 → remaining = 0 (coerced)
        assertEquals(0, TimeProgressUtil.calculateRemainingMonths(1, 1, 10, 2, 1))
    }

    @Test
    fun calculateRemainingMonths_exactDuration_returnsZero() {
        assertEquals(0, TimeProgressUtil.calculateRemainingMonths(1, 1, 6, 1, 7))
    }

    // ---- calculateProgressPercent ----

    @Test
    fun calculateProgressPercent_zeroDuration_returnsZero() {
        assertEquals(0, TimeProgressUtil.calculateProgressPercent(1, 1, 0, 2, 1))
    }

    @Test
    fun calculateProgressPercent_halfProgress() {
        // elapsed = 5, duration = 10 → 50%
        assertEquals(50, TimeProgressUtil.calculateProgressPercent(1, 1, 10, 1, 6))
    }

    @Test
    fun calculateProgressPercent_fullProgress_cappedAt100() {
        // elapsed = 20, duration = 10 → 200% → capped at 100
        assertEquals(100, TimeProgressUtil.calculateProgressPercent(1, 1, 10, 3, 1))
    }

    @Test
    fun calculateProgressPercent_noProgress_returnsZero() {
        assertEquals(0, TimeProgressUtil.calculateProgressPercent(1, 1, 10, 1, 1))
    }

    // ---- calculateProgressFraction ----

    @Test
    fun calculateProgressFraction_zeroDuration_returnsZero() {
        assertEquals(0f, TimeProgressUtil.calculateProgressFraction(1, 1, 0, 2, 1), 0.001f)
    }

    @Test
    fun calculateProgressFraction_halfProgress() {
        assertEquals(0.5f, TimeProgressUtil.calculateProgressFraction(1, 1, 10, 1, 6), 0.001f)
    }

    @Test
    fun calculateProgressFraction_fullProgress_cappedAt1() {
        assertEquals(1f, TimeProgressUtil.calculateProgressFraction(1, 1, 10, 3, 1), 0.001f)
    }

    @Test
    fun calculateProgressFraction_noProgress_returnsZero() {
        assertEquals(0f, TimeProgressUtil.calculateProgressFraction(1, 1, 10, 1, 1), 0.001f)
    }

    // ---- isTimeElapsed ----

    @Test
    fun isTimeElapsed_notYet_returnsFalse() {
        assertFalse(TimeProgressUtil.isTimeElapsed(1, 1, 10, 1, 5))
    }

    @Test
    fun isTimeElapsed_exactDuration_returnsTrue() {
        assertTrue(TimeProgressUtil.isTimeElapsed(1, 1, 6, 1, 7))
    }

    @Test
    fun isTimeElapsed_pastDuration_returnsTrue() {
        assertTrue(TimeProgressUtil.isTimeElapsed(1, 1, 6, 3, 1))
    }

    @Test
    fun isTimeElapsed_zeroDuration_returnsTrue() {
        assertTrue(TimeProgressUtil.isTimeElapsed(1, 1, 0, 1, 1))
    }

    // ---- 游戏毫秒族（结算改造 2026-09-27 B5）----

    @Test
    fun elapsedGameMs_clampsNegativeToZero() {
        assertEquals(5000L, TimeProgressUtil.calculateElapsedGameMs(10_000L, 15_000L))
        assertEquals(0L, TimeProgressUtil.calculateElapsedGameMs(15_000L, 10_000L))
    }

    @Test
    fun remainingGameMs_clampsNegativeToZero() {
        assertEquals(1000L, TimeProgressUtil.calculateRemainingGameMs(12_000L, 11_000L))
        assertEquals(0L, TimeProgressUtil.calculateRemainingGameMs(11_000L, 12_000L))
    }

    @Test
    fun progressFractionByGameMs_linearAndClamped() {
        assertEquals(0.5f, TimeProgressUtil.calculateProgressFractionByGameMs(
            0L, 12_000L, 6_000L), 1e-6f)
        assertEquals(1f, TimeProgressUtil.calculateProgressFractionByGameMs(
            0L, 12_000L, 20_000L), 1e-6f)
        assertEquals(0f, TimeProgressUtil.calculateProgressFractionByGameMs(
            0L, 12_000L, -5_000L), 1e-6f)
        // 区间非法（completeAt <= startedAt）→ 0
        assertEquals(0f, TimeProgressUtil.calculateProgressFractionByGameMs(
            6_000L, 6_000L, 7_000L), 1e-6f)
    }

    @Test
    fun isElapsedByGameMs_requiresPositiveCompleteAtAndPassedNow() {
        assertTrue(TimeProgressUtil.isElapsedByGameMs(6_000L, 6_000L))
        assertFalse(TimeProgressUtil.isElapsedByGameMs(6_000L, 5_999L))
        // completeAt 零值（未回填）不算完成
        assertFalse(TimeProgressUtil.isElapsedByGameMs(0L, 100_000L))
    }
    // ---- B8 时间进度投影族（旬进度→时间进度，方案 §10 B8）----

    @Test
    fun monthProgressFraction_climbsContinuouslyAcrossPhases() {
        // 旬序 0 + 旬内 0/3 段：月初 0
        assertEquals(0f, TimeProgressUtil.monthProgressFraction(0, 0f), 1e-6f)
        // 上旬中段：0 + 0.5 → 1/6
        assertEquals(1f / 6f, TimeProgressUtil.monthProgressFraction(0, 0.5f), 1e-6f)
        // 中旬始：1/3；下旬末逼近 1：2 + 0.99 → 0.9967
        assertEquals(1f / 3f, TimeProgressUtil.monthProgressFraction(1, 0f), 1e-6f)
        assertEquals(0.9967f, TimeProgressUtil.monthProgressFraction(2, 0.99f), 1e-3f)
    }

    @Test
    fun monthProgressFraction_clampsOutOfRangeInputs() {
        // 旬序越界与旬内进度越界都收敛到 [0,1]
        assertEquals(0f, TimeProgressUtil.monthProgressFraction(-1, 0f), 1e-6f)
        assertEquals(1f, TimeProgressUtil.monthProgressFraction(9, 2f), 1e-6f)
        assertEquals(0f, TimeProgressUtil.monthProgressFraction(0, -3f), 1e-6f)
    }

    @Test
    fun slotProgressFraction_combinesWholeMonthsAndMonthProgress() {
        // 3 月任务完成 1 整月 + 月内 0.5 → 1.5/3
        assertEquals(0.5f, TimeProgressUtil.slotProgressFraction(1, 0.5f, 3), 1e-6f)
        // 收获前终值逼近 1：最后一月 + 月内 0.99
        assertEquals(0.9967f, TimeProgressUtil.slotProgressFraction(2, 0.99f, 3), 1e-3f)
    }

    @Test
    fun slotProgressFraction_edgeDurationsAndClamp() {
        assertEquals(0f, TimeProgressUtil.slotProgressFraction(0, 0f, 0), 1e-6f)
        assertEquals(0f, TimeProgressUtil.slotProgressFraction(0, 0f, -2), 1e-6f)
        // 超额完成月数收敛 1（月界收割前 UI 不越界）
        assertEquals(1f, TimeProgressUtil.slotProgressFraction(5, 0.5f, 3), 1e-6f)
    }
}
