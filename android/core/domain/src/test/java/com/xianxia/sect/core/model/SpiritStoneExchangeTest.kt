package com.xianxia.sect.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpiritStoneExchangeTest {

    @Test
    fun `SpiritStoneGrade_fromDisplayName - parses all grades`() {
        assertEquals(SpiritStoneGrade.LOW, SpiritStoneGrade.fromDisplayName("下品灵石"))
        assertEquals(SpiritStoneGrade.MID, SpiritStoneGrade.fromDisplayName("中品灵石"))
        assertEquals(SpiritStoneGrade.HIGH, SpiritStoneGrade.fromDisplayName("上品灵石"))
        assertNull(SpiritStoneGrade.fromDisplayName("极品灵石"))
    }

    @Test
    fun `toLowGrade - low grade returns same amount`() {
        assertEquals(100L, SpiritStoneExchange.toLowGrade(100L, SpiritStoneGrade.LOW))
    }

    @Test
    fun `toLowGrade - mid grade uses effective ratio 8000`() {
        assertEquals(8_000L, SpiritStoneExchange.toLowGrade(1L, SpiritStoneGrade.MID))
        assertEquals(40_000L, SpiritStoneExchange.toLowGrade(5L, SpiritStoneGrade.MID))
    }

    @Test
    fun `toLowGrade - high grade uses effective ratio squared`() {
        assertEquals(64_000_000L, SpiritStoneExchange.toLowGrade(1L, SpiritStoneGrade.HIGH))
        assertEquals(192_000_000L, SpiritStoneExchange.toLowGrade(3L, SpiritStoneGrade.HIGH))
    }

    @Test
    fun `fromLowGrade - low grade returns input and zero remainder`() {
        val (converted, remaining) = SpiritStoneExchange.fromLowGrade(123L, SpiritStoneGrade.LOW)
        assertEquals(123L, converted)
        assertEquals(0L, remaining)
    }

    @Test
    fun `fromLowGrade - mid grade converts with remainder`() {
        val (converted, remaining) = SpiritStoneExchange.fromLowGrade(25_000L, SpiritStoneGrade.MID)
        assertEquals(3L, converted)
        assertEquals(1_000L, remaining)
    }

    @Test
    fun `fromLowGrade - high grade converts with remainder`() {
        val (converted, remaining) = SpiritStoneExchange.fromLowGrade(2_500_000_001L, SpiritStoneGrade.HIGH)
        assertEquals(39L, converted)
        assertEquals(4_000_001L, remaining)
    }

    @Test
    fun `exchange - low to mid`() {
        val (converted, remaining) = SpiritStoneExchange.exchange(25_000L, SpiritStoneGrade.LOW, SpiritStoneGrade.MID)
        assertEquals(3L, converted)
        assertEquals(1_000L, remaining)
    }

    @Test
    fun `exchange - mid to low`() {
        val (converted, remaining) = SpiritStoneExchange.exchange(3L, SpiritStoneGrade.MID, SpiritStoneGrade.LOW)
        assertEquals(24_000L, converted)
        assertEquals(0L, remaining)
    }

    @Test
    fun `exchange - mid to high`() {
        val (converted, remaining) = SpiritStoneExchange.exchange(25_000L, SpiritStoneGrade.MID, SpiritStoneGrade.HIGH)
        assertEquals(3L, converted)
        assertEquals(1_000L, remaining)
    }

    @Test
    fun `exchange - high to low`() {
        val (converted, remaining) = SpiritStoneExchange.exchange(2L, SpiritStoneGrade.HIGH, SpiritStoneGrade.LOW)
        assertEquals(128_000_000L, converted)
        assertEquals(0L, remaining)
    }

    @Test
    fun `exchange - same grade returns input and zero remainder`() {
        val (converted, remaining) = SpiritStoneExchange.exchange(100L, SpiritStoneGrade.MID, SpiritStoneGrade.MID)
        assertEquals(100L, converted)
        assertEquals(0L, remaining)
    }

    @Test
    fun `exchange - low to mid large amount`() {
        val (converted, remaining) = SpiritStoneExchange.exchange(99_999_999L, SpiritStoneGrade.LOW, SpiritStoneGrade.MID)
        assertEquals(12_499L, converted)
        assertEquals(7_999L, remaining)
    }

    @Test
    fun `totalSellValue - only low grade`() {
        val total = SpiritStoneExchange.totalSellValue(500L, 0L, 0L)
        assertEquals(500L, total)
    }

    @Test
    fun `totalSellValue - mixed grades`() {
        // 500低 + 2中×8000 + 1上×64000000 = 500 + 16000 + 64000000
        val total = SpiritStoneExchange.totalSellValue(500L, 2L, 1L)
        assertEquals(64_016_500L, total)
    }

    @Test
    fun `totalSellValue - all zero`() {
        val total = SpiritStoneExchange.totalSellValue(0L, 0L, 0L)
        assertEquals(0L, total)
    }

    @Test
    fun `EFFECTIVE_RATIO equals RATIO times sell price multiplier`() {
        assertEquals(8_000L, SpiritStoneExchange.EFFECTIVE_RATIO)
        assertTrue(SpiritStoneExchange.EFFECTIVE_RATIO < SpiritStoneExchange.RATIO)
    }
}
