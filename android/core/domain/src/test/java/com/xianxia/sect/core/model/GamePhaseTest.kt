package com.xianxia.sect.core.model

import org.junit.Assert.*
import org.junit.Test

class GamePhaseTest {

    // ---- 枚举值测试 ----

    @Test
    fun enumValues_hasThreeEntries() {
        assertEquals(3, GamePhase.entries.size)
    }

    @Test
    fun enumValues_areEarlyMidLate() {
        assertArrayEquals(
            arrayOf(GamePhase.EARLY, GamePhase.MID, GamePhase.LATE),
            GamePhase.entries.toTypedArray()
        )
    }

    @Test
    fun early_displayName() {
        assertEquals("上旬", GamePhase.EARLY.displayName)
    }

    @Test
    fun mid_displayName() {
        assertEquals("中旬", GamePhase.MID.displayName)
    }

    @Test
    fun late_displayName() {
        assertEquals("下旬", GamePhase.LATE.displayName)
    }

    @Test
    fun early_value() {
        assertEquals(0, GamePhase.EARLY.value)
    }

    @Test
    fun mid_value() {
        assertEquals(1, GamePhase.MID.value)
    }

    @Test
    fun late_value() {
        assertEquals(2, GamePhase.LATE.value)
    }

    // ---- PHASES_PER_MONTH ----

    @Test
    fun phasesPerMonth_is3() {
        assertEquals(3, GamePhase.PHASES_PER_MONTH)
    }

    // ---- fromValue ----

    @Test
    fun fromValue_0_returnsEarly() {
        assertEquals(GamePhase.EARLY, GamePhase.fromValue(0))
    }

    @Test
    fun fromValue_1_returnsMid() {
        assertEquals(GamePhase.MID, GamePhase.fromValue(1))
    }

    @Test
    fun fromValue_2_returnsLate() {
        assertEquals(GamePhase.LATE, GamePhase.fromValue(2))
    }

    @Test
    fun fromValue_outOfRange_returnsEarly() {
        assertEquals(GamePhase.EARLY, GamePhase.fromValue(3))
    }

    @Test
    fun fromValue_negative_returnsEarly() {
        assertEquals(GamePhase.EARLY, GamePhase.fromValue(-1))
    }

    // ---- fromOldGameDay ----

    @Test
    fun fromOldGameDay_1_returnsEarly() {
        assertEquals(GamePhase.EARLY, GamePhase.fromOldGameDay(1))
    }

    @Test
    fun fromOldGameDay_5_returnsEarly() {
        assertEquals(GamePhase.EARLY, GamePhase.fromOldGameDay(5))
    }

    @Test
    fun fromOldGameDay_10_returnsEarly() {
        assertEquals(GamePhase.EARLY, GamePhase.fromOldGameDay(10))
    }

    @Test
    fun fromOldGameDay_11_returnsMid() {
        assertEquals(GamePhase.MID, GamePhase.fromOldGameDay(11))
    }

    @Test
    fun fromOldGameDay_15_returnsMid() {
        assertEquals(GamePhase.MID, GamePhase.fromOldGameDay(15))
    }

    @Test
    fun fromOldGameDay_20_returnsMid() {
        assertEquals(GamePhase.MID, GamePhase.fromOldGameDay(20))
    }

    @Test
    fun fromOldGameDay_21_returnsLate() {
        assertEquals(GamePhase.LATE, GamePhase.fromOldGameDay(21))
    }

    @Test
    fun fromOldGameDay_30_returnsLate() {
        assertEquals(GamePhase.LATE, GamePhase.fromOldGameDay(30))
    }

    @Test
    fun fromOldGameDay_boundaryDay10_isEarly() {
        // day 10 → normalized=9 < 10 → EARLY
        assertEquals(GamePhase.EARLY, GamePhase.fromOldGameDay(10))
    }

    @Test
    fun fromOldGameDay_boundaryDay11_isMid() {
        // day 11 → normalized=10 < 20 → MID
        assertEquals(GamePhase.MID, GamePhase.fromOldGameDay(11))
    }

    @Test
    fun fromOldGameDay_outOfRangeLow_clampsTo0() {
        // day 0 → normalized coerced to 0 → EARLY
        assertEquals(GamePhase.EARLY, GamePhase.fromOldGameDay(0))
    }

    @Test
    fun fromOldGameDay_outOfRangeHigh_clampsTo29() {
        // day 31 → normalized coerced to 29 → LATE
        assertEquals(GamePhase.LATE, GamePhase.fromOldGameDay(31))
    }
}
