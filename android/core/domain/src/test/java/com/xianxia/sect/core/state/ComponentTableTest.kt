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
        table.setMutationCallback { writeCount++ }
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
        table.setMutationCallback { writeCount++ }
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

    // ============================================================
    // IntFlatArray 容量增长测试（Bug #1: ensureCapacity 空循环回归）
    // ============================================================

    @Test
    fun `IntFlatArray put at initial capacity boundary`() {
        // 初始容量 64，put key=64 触发 ensureCapacity(64)
        val arr = IntFlatArray()
        for (i in 1..63) arr.put(i, i * 10)
        assertEquals(63, arr.size())
        arr.put(64, 640)
        assertEquals(64, arr.size())
        assertEquals(640, arr[64])
        // 验证前 63 个值不受影响
        for (i in 1..63) assertEquals(i * 10, arr[i])
    }

    @Test
    fun `IntFlatArray put across second capacity boundary`() {
        val arr = IntFlatArray()
        for (i in 1..128) arr.put(i, i)
        assertEquals(128, arr.size())
        // 验证第 64 个（第一次扩容边界）
        assertEquals(64, arr[64])
        // 验证第 129 个（第二次扩容边界）
        arr.put(129, 129)
        assertEquals(129, arr.size())
        assertEquals(129, arr[129])
    }

    @Test
    fun `IntFlatArray update triggers ensureCapacity`() {
        val arr = IntFlatArray()
        for (i in 1..63) arr.put(i, i)
        // update 不存在的 key=64 应触发扩容并注册
        arr.update(64) { it + 1 }
        assertTrue(arr.contains(64))
        assertEquals(1, arr[64]) // 不存在时默认 0，block(0) + 1 = 1
        assertEquals(64, arr.size())
    }

    @Test
    fun `IntFlatArray update existing key across capacity boundary`() {
        val arr = IntFlatArray()
        for (i in 1..64) arr.put(i, i * 10)
        arr.update(64) { it + 5 }
        assertEquals(645, arr[64])
    }

    @Test
    fun `IntFlatArray delete with non-zero size after boundary works`() {
        val arr = IntFlatArray()
        for (i in 1..64) arr.put(i, i * 10)
        assertEquals(64, arr.size())
        arr.delete(64)
        assertEquals(63, arr.size())
        assertFalse(arr.contains(64))
        // 验证其他值不受影响
        for (i in 1..63) assertEquals(i * 10, arr[i])
    }

    @Test
    fun `IntFlatArray delete middle key after boundary preserves remaining`() {
        val arr = IntFlatArray()
        for (i in 1..70) arr.put(i, i)
        assertEquals(70, arr.size())
        arr.delete(35) // 中间值
        assertEquals(69, arr.size())
        assertFalse(arr.contains(35))
        // 验证全部 69 个剩余值正确
        for (i in 1..34) assertEquals(i, arr[i])
        for (i in 36..70) assertEquals(i, arr[i])
    }

    @Test
    fun `IntFlatArray delete empty table no crash`() {
        val arr = IntFlatArray()
        arr.delete(0) // 空表 delete 应静默返回
        arr.delete(64) // key >= values.size → 静默返回
        assertTrue(true) // 走到这里说明没崩溃
    }

    @Test
    fun `IntFlatArray put delete cycle across boundary`() {
        val arr = IntFlatArray()
        for (i in 1..64) arr.put(i, i)
        // 删除前 32 个
        for (i in 1..32) arr.delete(i)
        assertEquals(32, arr.size())
        // 再添加 32 个（新 ID 65..96）
        for (i in 65..96) arr.put(i, i * 2)
        assertEquals(64, arr.size())
        // 验证原第 33..64 个不受影响
        for (i in 33..64) assertEquals(i, arr[i])
        // 验证新 65..96 个正确
        for (i in 65..96) assertEquals(i * 2, arr[i])
    }

    @Test
    fun `IntFlatArray contains correctly identifies boundary entries`() {
        val arr = IntFlatArray()
        arr.put(63, 1)
        arr.put(64, 2)
        arr.put(129, 3)
        assertTrue(arr.contains(63))
        assertTrue(arr.contains(64))
        assertTrue(arr.contains(129))
        assertFalse(arr.contains(62))
        assertFalse(arr.contains(130))
    }

    @Test
    fun `IntFlatArray get with default at boundary`() {
        val arr = IntFlatArray()
        arr.put(64, 100)
        assertEquals(100, arr.get(64, 999))
        assertEquals(999, arr.get(65, 999))
        assertEquals(999, arr.get(200, 999))
    }

    @Test
    fun `IntFlatArray forEach correctly iterates after boundary`() {
        val arr = IntFlatArray()
        for (i in 1..70) arr.put(i, i * 10)
        val sum = arr.keys.sumOf { arr.values[it] }
        // keys 是 IntFlatArray 的 @PublishedApi 内部字段，测试不直接访问
        // 改为通过 indexOfKey 间接验证
        var count = 0
        for (i in 0 until arr.size()) {
            val key = arr.keyAt(i)
            assertTrue(key in 1..70)
            assertEquals(key * 10, arr.valueAt(i))
            count++
        }
        assertEquals(70, count)
    }

    @Test
    fun `IntFlatArray partial fill compact iteration`() {
        val arr = IntFlatArray()
        // 在扩容边界创建离散键
        arr.put(10, 100)
        arr.put(50, 500)
        arr.put(64, 640)
        arr.put(128, 1280)
        assertEquals(4, arr.size())
        assertEquals(100, arr[10])
        assertEquals(500, arr[50])
        assertEquals(640, arr[64])
        assertEquals(1280, arr[128])
        // 验证迭代器
        val map = mutableMapOf<Int, Int>()
        for (i in 0 until arr.size()) {
            map[arr.keyAt(i)] = arr.valueAt(i)
        }
        assertEquals(4, map.size)
        assertEquals(100, map[10])
        assertEquals(640, map[64])
        assertEquals(1280, map[128])
    }

    @Test
    fun `IntFlatArray indexOfKey works after boundary`() {
        val arr = IntFlatArray()
        arr.put(1, 10)     // slot 0
        arr.put(64, 640)   // slot 1
        assertEquals(0, arr.indexOfKey(1))
        assertEquals(1, arr.indexOfKey(64))
        assertEquals(-1, arr.indexOfKey(99))
    }

    // ============================================================
    // DoubleFlatArray 容量增长测试
    // ============================================================

    @Test
    fun `DoubleFlatArray put at capacity boundary`() {
        val arr = DoubleFlatArray()
        for (i in 1..63) arr.put(i, i * 1.5)
        arr.put(64, 64.0)
        assertEquals(64, arr.size())
        assertEquals(64.0, arr[64], 0.001)
        for (i in 1..63) assertEquals(i * 1.5, arr[i], 0.001)
    }

    @Test
    fun `DoubleFlatArray update triggers ensureCapacity`() {
        val arr = DoubleFlatArray()
        for (i in 1..63) arr.put(i, i * 1.0)
        arr.update(64) { it + 1.0 }
        assertTrue(arr.contains(64))
        assertEquals(1.0, arr[64], 0.001)
        assertEquals(64, arr.size())
    }

    @Test
    fun `DoubleFlatArray delete after boundary preserves other values`() {
        val arr = DoubleFlatArray()
        for (i in 1..65) arr.put(i, i * 10.0)
        arr.delete(65)
        assertEquals(64, arr.size())
        assertFalse(arr.contains(65))
        for (i in 1..64) assertEquals(i * 10.0, arr[i], 0.001)
    }

    @Test
    fun `DoubleFlatArray clear and refill across boundary`() {
        val arr = DoubleFlatArray()
        for (i in 1..70) arr.put(i, i * 1.0)
        arr.clear()
        assertEquals(0, arr.size())
        // 重新填充
        for (i in 1..70) arr.put(i, i * 2.0)
        assertEquals(70, arr.size())
        for (i in 1..70) assertEquals(i * 2.0, arr[i], 0.001)
    }

    @Test
    fun `DoubleFlatArray second capacity boundary`() {
        val arr = DoubleFlatArray()
        for (i in 1..129) arr.put(i, i * 0.5)
        assertEquals(129, arr.size())
        assertEquals(64.0, arr[128], 0.001)
        assertEquals(64.5, arr[129], 0.001)
    }

    // ============================================================
    // IntComponentTable / DoubleComponentTable 边界测试
    // ============================================================

    @Test
    fun `IntComponentTable auto-grows past initial capacity`() {
        val table = IntComponentTable()
        for (i in 1..70) table[i] = i * 3
        assertEquals(70, table.size)
        for (i in 1..70) assertEquals(i * 3, table[i])
    }

    @Test
    fun `IntComponentTable remove across capacity boundary`() {
        val table = IntComponentTable()
        for (i in 1..70) table[i] = i
        table.remove(64)
        assertEquals(69, table.size)
        assertFalse(table.contains(64))
    }

    @Test
    fun `DoubleComponentTable auto-grows past initial capacity`() {
        val table = DoubleComponentTable()
        for (i in 1..70) table[i] = i * 2.5
        assertEquals(70, table.size)
        for (i in 1..70) assertEquals(i * 2.5, table[i], 0.001)
    }
}
