package com.xianxia.sect.core.util

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CircularBufferTest {

    private lateinit var buffer: CircularBuffer<Int>

    @Before
    fun setUp() {
        buffer = CircularBuffer(3)
    }

    @Test
    fun newBuffer_isEmpty_andSizeIsZero() {
        assertTrue(buffer.isEmpty())
        assertEquals(0, buffer.size())
    }

    @Test
    fun addItems_sizeIncreases() {
        buffer.add(1)
        assertEquals(1, buffer.size())
        buffer.add(2)
        assertEquals(2, buffer.size())
        buffer.add(3)
        assertEquals(3, buffer.size())
    }

    @Test
    fun capacityExceeded_oldestItemsRemoved() {
        buffer.add(1)
        buffer.add(2)
        buffer.add(3)
        buffer.add(4)
        assertEquals(3, buffer.size())
        assertEquals(listOf(2, 3, 4), buffer.toList())

        buffer.add(5)
        assertEquals(listOf(3, 4, 5), buffer.toList())
    }

    @Test
    fun average_returnsCorrectAverage() {
        buffer.add(2)
        buffer.add(4)
        buffer.add(6)
        assertEquals(4.0, buffer.average(), 0.001)
    }

    @Test
    fun average_onEmptyBuffer_returnsZero() {
        assertEquals(0.0, buffer.average(), 0.001)
    }

    @Test
    fun sum_returnsCorrectSum() {
        buffer.add(1)
        buffer.add(2)
        buffer.add(3)
        assertEquals(6.0, buffer.sum(), 0.001)
    }

    @Test
    fun maxAndMin_returnCorrectValues() {
        buffer.add(1)
        buffer.add(5)
        buffer.add(3)
        assertEquals(5.0, buffer.max(), 0.001)
        assertEquals(1.0, buffer.min(), 0.001)
    }

    @Test
    fun maxAndMin_onEmptyBuffer_returnZero() {
        assertEquals(0.0, buffer.max(), 0.001)
        assertEquals(0.0, buffer.min(), 0.001)
    }

    @Test
    fun clear_emptiesBuffer() {
        buffer.add(1)
        buffer.add(2)
        buffer.clear()
        assertTrue(buffer.isEmpty())
        assertEquals(0, buffer.size())
    }

    @Test
    fun toList_returnsInsertionOrder() {
        buffer.add(10)
        buffer.add(20)
        buffer.add(30)
        assertEquals(listOf(10, 20, 30), buffer.toList())
    }

    @Test
    fun isNotEmpty_worksCorrectly() {
        assertFalse(buffer.isNotEmpty())
        buffer.add(1)
        assertTrue(buffer.isNotEmpty())
    }

    @Test
    fun capacityOne_worksCorrectly() {
        val buf1 = CircularBuffer<Int>(1)
        buf1.add(10)
        assertEquals(1, buf1.size())
        assertEquals(listOf(10), buf1.toList())

        buf1.add(20)
        assertEquals(1, buf1.size())
        assertEquals(listOf(20), buf1.toList())
        assertEquals(20.0, buf1.average(), 0.001)
    }
}
