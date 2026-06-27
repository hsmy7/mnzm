package com.xianxia.sect.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressAnimationTest {

    // ========== nextProgressTick ==========

    @Test
    fun `nextProgressTick - target zero snaps to zero`() {
        assertEquals(0f, nextProgressTick(0.5f, 0f, 0.1f))
        assertEquals(0f, nextProgressTick(1f, 0f, 0.1f))
    }

    @Test
    fun `nextProgressTick - target below current snaps to target`() {
        assertEquals(0.3f, nextProgressTick(0.8f, 0.3f, 0.1f))
        assertEquals(0.1f, nextProgressTick(0.9f, 0.1f, 0.05f))
    }

    @Test
    fun `nextProgressTick - advances by rate toward target`() {
        assertEquals(0.15f, nextProgressTick(0.1f, 1f, 0.05f))
        assertEquals(0.6f, nextProgressTick(0.5f, 1f, 0.1f))
    }

    @Test
    fun `nextProgressTick - clamps at target`() {
        assertEquals(1f, nextProgressTick(0.95f, 1f, 0.1f))
        assertEquals(0.5f, nextProgressTick(0.49f, 0.5f, 0.1f))
    }

    @Test
    fun `nextProgressTick - current equals target no change`() {
        assertEquals(0.7f, nextProgressTick(0.7f, 0.7f, 0.1f))
        assertEquals(1f, nextProgressTick(1f, 1f, 0.5f))
    }

    @Test
    fun `nextProgressTick - zero rate no advancement`() {
        assertEquals(0.1f, nextProgressTick(0.1f, 1f, 0f))
        assertEquals(0f, nextProgressTick(0.1f, 0f, 0f))
    }

    @Test
    fun `nextProgressTick - clamps target and rate outside 0-1`() {
        assertEquals(0.6f, nextProgressTick(0.5f, 1.5f, 0.1f))
        assertEquals(0.5f, nextProgressTick(0.5f, 1f, -0.1f))
        assertEquals(1f, nextProgressTick(0.9f, 2.0f, 0.1f))
    }

    @Test
    fun `nextProgressTick - multiple ticks accumulate to target`() {
        var c = 0f
        val rate = 0.15f
        val ticks = mutableListOf<Float>()
        repeat(10) { c = nextProgressTick(c, 1f, rate); ticks.add(c) }
        assertEquals(0.15f, ticks[0], 0.001f)
        assertEquals(0.45f, ticks[2], 0.001f)
        assertEquals(1f, ticks[6], 0.001f)
        assertEquals(1f, ticks.last(), 0.001f)
    }

    // ========== progressRateForMonthsDuration ==========

    @Test
    fun `rateForMonths - 3 months at 1x`() {
        assertEquals(0.0055555f, progressRateForMonthsDuration(3, 1), 0.0001f)
    }

    @Test
    fun `rateForMonths - 2x doubles`() {
        val r1 = progressRateForMonthsDuration(3, 1)
        val r2 = progressRateForMonthsDuration(3, 2)
        assertEquals(r1 * 2f, r2, 0.0001f)
    }

    @Test
    fun `rateForMonths - zero or negative returns zero`() {
        assertEquals(0f, progressRateForMonthsDuration(0, 1))
        assertEquals(0f, progressRateForMonthsDuration(-1, 1))
    }

    @Test
    fun `rateForMonths - zero gameSpeed treated as 1`() {
        assertEquals(
            progressRateForMonthsDuration(3, 1),
            progressRateForMonthsDuration(3, 0), 0.0001f
        )
    }

    // ========== progressRateForPerSecond ==========

    @Test
    fun `ratePerSec - basic`() {
        assertEquals(0.001f, progressRateForPerSecond(10.0, 1000.0), 0.0001f)
    }

    @Test
    fun `ratePerSec - zero or negative returns zero`() {
        assertEquals(0f, progressRateForPerSecond(10.0, 0.0))
        assertEquals(0f, progressRateForPerSecond(0.0, 100.0))
        assertEquals(0f, progressRateForPerSecond(-1.0, 100.0))
        assertEquals(0f, progressRateForPerSecond(10.0, -1.0))
    }

    @Test
    fun `ratePerSec - clamps to 1f`() {
        assertEquals(1f, progressRateForPerSecond(10000.0, 10.0), 0f)
    }

    // ========== Constants ==========

    @Test
    fun `PROGRESS_TICK_MS equals 100`() = assertEquals(100L, PROGRESS_TICK_MS)

    @Test
    fun `PROGRESS_MS_PER_PHASE_1X equals 2000`() =
        assertEquals(2000L, PROGRESS_MS_PER_PHASE_1X)
}
