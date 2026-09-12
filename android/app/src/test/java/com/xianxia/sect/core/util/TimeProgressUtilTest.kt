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
}
