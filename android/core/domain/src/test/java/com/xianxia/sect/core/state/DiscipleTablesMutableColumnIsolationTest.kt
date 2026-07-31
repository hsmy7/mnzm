package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.Disciple
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Mutable 列浅共享隔离测试（2026-08-01 修复）。
 *
 * 旧实现：13 张 List/Map/Set 列每事务 adoptDeep 急切深拷贝（O(D×均值长度) 分配/GC），
 * 全库审计无原地修改模式——纯浪费。新实现 O(1) 浅共享 + Debug unmodifiable 包装。
 * 本测试守卫：
 * 1. 浅共享后副本整体替换写不影响源快照（快照隔离仍成立）
 * 2. Debug guard 开启时原地修改立即抛异常（防御机制有效）
 * 3. 兜底路径（forceFullCopy）与 COW 路径读取值全等
 */
@RunWith(RobolectricTestRunner::class)
class DiscipleTablesMutableColumnIsolationTest {

    private lateinit var tables: DiscipleTables

    @Before
    fun setUp() {
        tables = DiscipleTables()
        tables.writeAllowed = true
        DiscipleTables.mutableValueGuardEnabled = true
    }

    private fun insertWithMutable(id: Int): Disciple {
        val d = Disciple(
            id = id.toString(), name = "弟子$id",
            manualIds = listOf("m$id"),
            talentIds = listOf("t$id")
        )
        // lifeEvents 是 class body 属性（非构造参数），需显式赋值
        d.lifeEvents = mutableListOf("事件$id")
        tables.insert(d)
        return d
    }

    @Test
    fun `副本整体替换写不影响源快照`() {
        insertWithMutable(1)
        val source = tables
        val copy = tables.deepCopy()

        // 副本写（触发 COW 私有化）——整体替换语义
        copy.writeAllowed = true
        copy.lifeEvents[1] = listOf("新事件")
        copy.writeAllowed = false

        // 源快照不变
        assertEquals(listOf("事件1"), source.lifeEvents[1])
        assertEquals(listOf("事件1"), tables.lifeEvents[1])
    }

    @Test
    fun `guard 开启时原地修改抛异常`() {
        insertWithMutable(2)
        val copy = tables.deepCopy()
        copy.writeAllowed = true

        // 副本的 Mutable 列值被包装为 unmodifiable——原地修改必须抛异常
        @Suppress("UNCHECKED_CAST")
        val listValue = copy.lifeEvents[2] as MutableList<Any>
        assertThrows(UnsupportedOperationException::class.java) {
            listValue.add("破坏")
        }
        copy.writeAllowed = false
    }

    @Test
    fun `guard 关闭时浅共享行为兼容`() {
        DiscipleTables.mutableValueGuardEnabled = false
        insertWithMutable(3)
        val copy = tables.deepCopy()
        copy.writeAllowed = true

        // guard 关闭：不包装（纯共享）
        @Suppress("UNCHECKED_CAST")
        val listValue = copy.lifeEvents[3] as MutableList<Any>
        assertEquals(1, listValue.size)
        copy.writeAllowed = false
    }

    @Test
    fun `forceFullCopy 兜底与 COW 路径读取值全等`() {
        DiscipleTables.mutableValueGuardEnabled = false
        insertWithMutable(4)
        val cowCopy = tables.deepCopy()

        DiscipleTables.forceFullCopy = true
        try {
            val fullCopy = tables.deepCopy()
            assertEquals(cowCopy.lifeEvents[4], fullCopy.lifeEvents[4])
            assertEquals(cowCopy.manualIds[4], fullCopy.manualIds[4])
            assertEquals(cowCopy.talentIds[4], fullCopy.talentIds[4])
        } finally {
            DiscipleTables.forceFullCopy = false
        }
    }

    @Test
    fun `Map 列浅共享后整体替换写隔离`() {
        insertWithMutable(5)
        tables.manualMasteries[5] = mapOf("m5" to 50)
        val copy = tables.deepCopy()

        copy.writeAllowed = true
        copy.manualMasteries[5] = mapOf("m5" to 99)
        copy.writeAllowed = false

        assertEquals(mapOf("m5" to 50), tables.manualMasteries[5])
        assertEquals(mapOf("m5" to 99), copy.manualMasteries[5])
    }
}
