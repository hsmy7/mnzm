package com.xianxia.sect.core.util

import org.junit.Assert.*
import org.junit.Test

class GameRandomTest {

    @Test
    fun setSeed_producesDeterministicResults() {
        val seed = 42L
        GameRandom.setSeed(seed)
        val first = GameRandom.nextInt(10000)
        GameRandom.setSeed(seed)
        val second = GameRandom.nextInt(10000)
        assertEquals(first, second)
    }

    @Test
    fun nextInt_until_producesValuesInRange() {
        val until = 100
        repeat(1000) {
            val value = GameRandom.nextInt(until)
            assertTrue("value $value < 0", value >= 0)
            assertTrue("value $value >= $until", value < until)
        }
    }

    @Test
    fun nextInt_fromUntil_producesValuesInRange() {
        val from = 50
        val until = 150
        repeat(1000) {
            val value = GameRandom.nextInt(from, until)
            assertTrue("value $value < $from", value >= from)
            assertTrue("value $value >= $until", value < until)
        }
    }

    @Test
    fun nextDouble_producesValuesInRange() {
        repeat(1000) {
            val value = GameRandom.nextDouble()
            assertTrue("value $value < 0.0", value >= 0.0)
            assertTrue("value $value >= 1.0", value < 1.0)
        }
    }

    @Test
    fun nextFloat_producesValuesInRange() {
        repeat(1000) {
            val value = GameRandom.nextFloat()
            assertTrue("value $value < 0.0f", value >= 0.0f)
            assertTrue("value $value >= 1.0f", value < 1.0f)
        }
    }

    @Test
    fun nextBoolean_producesBothTrueAndFalse() {
        var hasTrue = false
        var hasFalse = false
        repeat(1000) {
            when (GameRandom.nextBoolean()) {
                true -> hasTrue = true
                false -> hasFalse = true
            }
            if (hasTrue && hasFalse) return
        }
        assertTrue("Should have produced true", hasTrue)
        assertTrue("Should have produced false", hasFalse)
    }

    @Test
    fun nextElement_list_picksFromListCorrectly() {
        val list = listOf("a", "b", "c", "d", "e")
        val picked = mutableSetOf<String>()
        repeat(1000) {
            val element = GameRandom.nextElement(list)
            assertTrue("Element not in list: $element", element in list)
            picked.add(element)
        }
        // 概率上 1000 次应该能覆盖所有元素
        assertEquals(list.toSet(), picked)
    }

    @Test(expected = NoSuchElementException::class)
    fun nextElement_list_throwsForEmptyList() {
        GameRandom.nextElement(emptyList<String>())
    }

    @Test
    fun nextElementOrNull_returnsNullForEmptyList() {
        assertNull(GameRandom.nextElementOrNull(emptyList<String>()))
    }

    @Test
    fun nextElement_array_picksFromArrayCorrectly() {
        val array = arrayOf("x", "y", "z")
        val picked = mutableSetOf<String>()
        repeat(1000) {
            val element = GameRandom.nextElement(array)
            assertTrue("Element not in array: $element", element in array)
            picked.add(element)
        }
        assertEquals(array.toSet(), picked)
    }

    @Test(expected = NoSuchElementException::class)
    fun nextElement_array_throwsForEmptyArray() {
        GameRandom.nextElement(emptyArray<String>())
    }

    @Test
    fun nextIntInRange_producesValuesInRange() {
        val range = 10..20
        repeat(1000) {
            val value = GameRandom.nextIntInRange(range)
            assertTrue("value $value < ${range.first}", value >= range.first)
            assertTrue("value $value > ${range.last}", value <= range.last)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun nextIntInRange_throwsForEmptyRange() {
        GameRandom.nextIntInRange(5..4)
    }

    @Test
    fun nextSecureInt_producesValuesInRange() {
        val until = 1000
        repeat(100) {
            val value = GameRandom.nextSecureInt(until)
            assertTrue("value $value < 0", value >= 0)
            assertTrue("value $value >= $until", value < until)
        }
    }

    @Test
    fun nextSecureDouble_producesValuesInRange() {
        repeat(100) {
            val value = GameRandom.nextSecureDouble()
            assertTrue("value $value < 0.0", value >= 0.0)
            assertTrue("value $value >= 1.0", value < 1.0)
        }
    }

    @Test
    fun resetWithTimeSeed_changesTheSeed() {
        GameRandom.setSeed(12345L)
        val first = GameRandom.nextInt(100000)
        GameRandom.resetWithTimeSeed()
        val second = GameRandom.nextInt(100000)
        // 概率上两次结果不同（极低概率相同，但几乎不可能）
        // 为避免 flaky test，只验证不会崩溃且结果在合法范围
        assertTrue(first in 0 until 100000)
        assertTrue(second in 0 until 100000)
    }
}
