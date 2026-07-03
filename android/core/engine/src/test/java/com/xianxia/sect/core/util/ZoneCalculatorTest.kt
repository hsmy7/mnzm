package com.xianxia.sect.core.util

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.ceil

class ZoneCalculatorTest {

    // ── calculate ──────────────────────────────────────────────

    @Test
    fun `calculate - empty zones returns base`() {
        assertEquals(10.0, ZoneCalculator.calculate(10.0), 0.001)
    }

    @Test
    fun `calculate - single zone positive`() {
        assertEquals(12.0, ZoneCalculator.calculate(10.0, 0.2), 0.001)
    }

    @Test
    fun `calculate - single zone negative`() {
        assertEquals(8.0, ZoneCalculator.calculate(10.0, -0.2), 0.001)
    }

    @Test
    fun `calculate - multiple zones`() {
        // 10 * (1 + 0.2) * (1 + 0.3) * (1 - 0.1) = 10 * 1.2 * 1.3 * 0.9 = 14.04
        assertEquals(14.04, ZoneCalculator.calculate(10.0, 0.2, 0.3, -0.1), 0.001)
    }

    @Test
    fun `calculate - zero base returns zero`() {
        assertEquals(0.0, ZoneCalculator.calculate(0.0, 0.5, 0.3), 0.001)
    }

    @Test
    fun `calculate - zone minus one cancels out`() {
        // 100 * (1 + 0.5) * (1 - 0.5) = 100 * 1.5 * 0.5 = 75
        assertEquals(75.0, ZoneCalculator.calculate(100.0, 0.5, -0.5), 0.001)
    }

    // ── calculateInt ───────────────────────────────────────────

    @Test
    fun `calculateInt - truncates to int`() {
        assertEquals(14, ZoneCalculator.calculateInt(10, 0.2, 0.3, -0.1))
    }

    // ── multiplierToZone / zoneToMultiplier ────────────────────

    @Test
    fun `multiplierToZone - normalizes multiplier`() {
        assertEquals(0.4, ZoneCalculator.multiplierToZone(1.4), 0.001)
        assertEquals(0.0, ZoneCalculator.multiplierToZone(1.0), 0.001)
        assertEquals(-0.5, ZoneCalculator.multiplierToZone(0.5), 0.001)
    }

    @Test
    fun `zoneToMultiplier - reconstructs multiplier`() {
        assertEquals(1.4, ZoneCalculator.zoneToMultiplier(0.4), 0.001)
        assertEquals(1.0, ZoneCalculator.zoneToMultiplier(0.0), 0.001)
    }

    @Test
    fun `multiplierToZone and zoneToMultiplier are inverse`() {
        val original = 1.35
        assertEquals(original, ZoneCalculator.zoneToMultiplier(
            ZoneCalculator.multiplierToZone(original)
        ), 0.001)
    }

    // ── calculateProbability ───────────────────────────────────

    @Test
    fun `calculateProbability - base only`() {
        assertEquals(0.30, ZoneCalculator.calculateProbability(0.30), 0.001)
    }

    @Test
    fun `calculateProbability - with positive sum`() {
        // 0.30 * (1 + 0.20) = 0.36
        assertEquals(0.36, ZoneCalculator.calculateProbability(0.30, positiveSum = 0.20), 0.001)
    }

    @Test
    fun `calculateProbability - with penalty`() {
        // 0.30 * (1 - 0.20) = 0.24
        assertEquals(0.24, ZoneCalculator.calculateProbability(0.30, penaltySum = 0.20), 0.001)
    }

    @Test
    fun `calculateProbability - combined positive and penalty`() {
        // 0.30 * (1 + 0.10) * (1 - 0.20) = 0.30 * 1.1 * 0.8 = 0.264
        assertEquals(0.264, ZoneCalculator.calculateProbability(0.30, 0.10, 0.20), 0.001)
    }

    @Test
    fun `calculateProbability - clamps to zero`() {
        // penalty > 1 should reduce to 0
        assertEquals(0.0, ZoneCalculator.calculateProbability(0.30, penaltySum = 2.0), 0.001)
    }

    @Test
    fun `calculateProbability - clamps to max 1`() {
        assertEquals(1.0, ZoneCalculator.calculateProbability(0.60, positiveSum = 1.0), 0.001)
    }

    @Test
    fun `calculateProbability - zero base stays zero`() {
        assertEquals(0.0, ZoneCalculator.calculateProbability(0.0, positiveSum = 0.5), 0.001)
    }

    // ── calculateReducedDuration ───────────────────────────────

    @Test
    fun `calculateReducedDuration - no reductions returns base`() {
        assertEquals(100, ZoneCalculator.calculateReducedDuration(100))
    }

    @Test
    fun `calculateReducedDuration - single reduction`() {
        // 100 * (1 - 0.2) = 80
        assertEquals(80, ZoneCalculator.calculateReducedDuration(100, 0.2))
    }

    @Test
    fun `calculateReducedDuration - multiple reductions multiply`() {
        // 100 * (1 - 0.2) * (1 - 0.3) = 100 * 0.8 * 0.7 = 56
        assertEquals(56, ZoneCalculator.calculateReducedDuration(100, 0.2, 0.3))
    }

    @Test
    fun `calculateReducedDuration - minimum 1`() {
        assertEquals(1, ZoneCalculator.calculateReducedDuration(10, 1.5))
    }

    @Test
    fun `calculateReducedDuration - clamps reduction to 1 max`() {
        assertEquals(1, ZoneCalculator.calculateReducedDuration(100, 1.5, 0.5))
    }

    // ── calculateAcceleratedTime ───────────────────────────────

    @Test
    fun `calculateAcceleratedTime - no bonus returns base`() {
        assertEquals(100, ZoneCalculator.calculateAcceleratedTime(100))
    }

    @Test
    fun `calculateAcceleratedTime - single bonus`() {
        // ceil(100 / (1 + 0.25)) = ceil(80) = 80
        assertEquals(80, ZoneCalculator.calculateAcceleratedTime(100, 0.25))
    }

    @Test
    fun `calculateAcceleratedTime - multiple bonuses multiply`() {
        // ceil(100 / ((1 + 0.2) * (1 + 0.25))) = ceil(100 / 1.5) = ceil(66.67) = 67
        assertEquals(67, ZoneCalculator.calculateAcceleratedTime(100, 0.2, 0.25))
    }

    @Test
    fun `calculateAcceleratedTime - minimum 1`() {
        assertEquals(1, ZoneCalculator.calculateAcceleratedTime(1, 100.0))
    }

    @Test
    fun `calculateAcceleratedTime - negative bonus slows down`() {
        // ceil(100 / (1 - 0.2)) = ceil(125) = 125
        assertEquals(125, ZoneCalculator.calculateAcceleratedTime(100, -0.2))
    }
}
