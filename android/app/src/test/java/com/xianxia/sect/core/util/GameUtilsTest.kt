package com.xianxia.sect.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameUtilsTest {

    @Test
    fun randomInt_inclusiveBounds() {
        val min = 3
        val max = 3
        val value = GameUtils.randomInt(min, max)
        assertEquals(3, value)
    }

    @Test
    fun randomLong_inclusiveUpperBound() {
        val min = 10L
        val max = 10L
        val value = GameUtils.randomLong(min, max)
        assertEquals(10L, value)
    }

    @Test
    fun randomChance_clampsInvalidValues() {
        repeat(50) {
            assertFalse(GameUtils.randomChance(-1.0))
            assertTrue(GameUtils.randomChance(2.0))
        }
    }

    @Test
    fun randomFromWeighted_ignoresInvalidWeights() {
        val item = GameUtils.randomFromWeighted(
            listOf(
                "A" to -1.0,
                "B" to 0.0,
                "C" to Double.NaN,
                "D" to 1.0
            )
        )
        assertEquals("D", item)
    }

    @Test
    fun truncate_handlesShortMaxLength() {
        assertEquals("", StringUtils.truncate("abcd", 0))
        assertEquals(".", StringUtils.truncate("abcd", 1, "..."))
        assertEquals("ab...", StringUtils.truncate("abcdef", 5, "..."))
    }

    @Test
    fun calculateExperienceForLevel_handlesInvalidInput() {
        assertEquals(0, GameUtils.calculateExperienceForLevel(0))
        assertEquals(0, GameUtils.calculateExperienceForLevel(1, 0))
        assertNotNull(GameUtils.calculateExperienceForLevel(50))
    }
}
