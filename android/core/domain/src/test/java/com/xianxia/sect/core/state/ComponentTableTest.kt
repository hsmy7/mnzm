package com.xianxia.sect.core.state

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ComponentTableTest {

    @Test
    fun `put and get`() {
        val table = ComponentTable<String>()
        table[1] = "hello"
        table[2] = "world"
        assertEquals("hello", table[1])
        assertEquals("world", table[2])
    }

    @Test
    fun `getOrNull returns null for missing key`() {
        val table = ComponentTable<String>()
        assertNull(table.getOrNull(999))
    }

    @Test
    fun `getOrDefault returns default for missing key`() {
        val table = ComponentTable<String>()
        assertEquals("default", table.getOrDefault(999, "default"))
    }

    @Test
    fun `update modifies value in place`() {
        val table = ComponentTable<Int>()
        table[1] = 10
        table.update(1) { it + 5 }
        assertEquals(15, table[1])
    }

    @Test
    fun `contains returns true for existing key`() {
        val table = ComponentTable<String>()
        table[5] = "test"
        assertTrue(table.contains(5))
        assertFalse(table.contains(6))
    }

    @Test
    fun `remove deletes entry`() {
        val table = ComponentTable<String>()
        table[1] = "a"
        table[2] = "b"
        table.remove(1)
        assertFalse(table.contains(1))
        assertEquals(1, table.size)
    }

    @Test
    fun `clear removes all entries`() {
        val table = ComponentTable<String>()
        table[1] = "a"
        table[2] = "b"
        table.clear()
        assertEquals(0, table.size)
        assertTrue(table.isEmpty())
    }

    @Test
    fun `forEach iterates all entries`() {
        val table = ComponentTable<String>()
        table[1] = "a"
        table[2] = "b"
        val result = mutableMapOf<Int, String>()
        table.forEach { id, value -> result[id] = value }
        assertEquals(mapOf(1 to "a", 2 to "b"), result)
    }

    @Test
    fun `ids returns all keys`() {
        val table = ComponentTable<String>()
        table[10] = "x"
        table[20] = "y"
        val ids = table.ids().toSet()
        assertEquals(setOf(10, 20), ids)
    }

    @Test
    fun `IntComponentTable basic operations`() {
        val table = IntComponentTable()
        table[1] = 100
        table[2] = 200
        assertEquals(100, table[1])
        table.update(1) { it + 50 }
        assertEquals(150, table[1])
        assertEquals(2, table.size)
    }

    @Test
    fun `DoubleComponentTable basic operations`() {
        val table = DoubleComponentTable()
        table[1] = 1.5
        table[2] = 2.5
        assertEquals(1.5, table[1], 0.001)
        table.update(1) { it * 2.0 }
        assertEquals(3.0, table[1], 0.001)
    }

    // === onWrite callback tests ===

    @Test
    fun `onWrite callback invoked on set update remove put clear`() {
        val table = IntComponentTable()
        var writeCount = 0
        table.onWrite = { writeCount++ }
        table[1] = 10
        assertEquals(1, writeCount)
        table.update(1) { it + 1 }
        assertEquals(2, writeCount)
        table.remove(1)
        assertEquals(3, writeCount)
        table.put(2, 20)
        assertEquals(4, writeCount)
        table.clear()
        assertEquals(5, writeCount)
    }

    @Test
    fun `onWrite defaults to null no crash`() {
        val table1 = ComponentTable<String>()
        table1[1] = "hello"
        table1.clear()

        val table2 = IntComponentTable()
        table2[1] = 42
        table2.clear()

        val table3 = DoubleComponentTable()
        table3[1] = 3.14
        table3.clear()
        // No assertion needed — just verifying no crash
    }

    @Test
    fun `onWrite not invoked on reads`() {
        val table = IntComponentTable()
        var writeCount = 0
        table.onWrite = { writeCount++ }
        table[1] = 100
        assertEquals(1, writeCount)
        val v = table[1]
        assertEquals(100, v)
        assertEquals(1, writeCount) // read did not bump
        table.contains(1)
        assertEquals(1, writeCount) // contains did not bump
        table.size
        assertEquals(1, writeCount) // size did not bump
    }
}
