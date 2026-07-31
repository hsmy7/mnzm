package com.xianxia.sect.core.state

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

import com.xianxia.sect.core.model.Disciple
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 列级写入 changedId 追踪测试（2026-08-01 增量组装基建）。
 *
 * 修复前：列级 setter（cultivations[id]=v 等）只标记 dirtyTracker 列，不记录弟子 id，
 * 导致每旬事务 changedIds 恒空 → 提交段全量 assembleAll 兜底（"增量组装"承诺落空）。
 * 本测试守卫：任意列级写入必须产生对应弟子 id 的 changedId。
 */
@RunWith(RobolectricTestRunner::class)
class DiscipleTablesChangedIdTest {

    private lateinit var tables: DiscipleTables

    @Before
    fun setUp() {
        tables = DiscipleTables()
        tables.writeAllowed = true
        tables.changedIdTracker.consumeChangedIds()  // 清空初始状态
    }

    private fun insertDisciple(id: Int): Int {
        val d = Disciple(id = id.toString(), name = "弟子$id", realm = 5, realmLayer = 1)
        tables.insert(d)
        tables.changedIdTracker.consumeChangedIds()  // 清空 insert 的记录
        return id
    }

    @Test
    fun `Int 列级写入记录弟子 id`() {
        val id = insertDisciple(1)
        tables.loyalties[id] = 66
        val changed = tables.changedIdTracker.consumeChangedIds()
        assertTrue("Int 列写入应记录 id=$id，实际=$changed", id in changed)
    }

    @Test
    fun `Double 列级写入记录弟子 id`() {
        val id = insertDisciple(2)
        tables.cultivations[id] = 1234.5
        val changed = tables.changedIdTracker.consumeChangedIds()
        assertTrue("Double 列写入应记录 id=$id，实际=$changed", id in changed)
    }

    @Test
    fun `Ref 列级写入记录弟子 id`() {
        val id = insertDisciple(3)
        tables.names[id] = "改名"
        val changed = tables.changedIdTracker.consumeChangedIds()
        assertTrue("Ref 列写入应记录 id=$id，实际=$changed", id in changed)
    }

    @Test
    fun `Mutable 列级写入记录弟子 id`() {
        val id = insertDisciple(4)
        tables.manualIds[id] = listOf("m1")
        val changed = tables.changedIdTracker.consumeChangedIds()
        assertTrue("Mutable 列写入应记录 id=$id，实际=$changed", id in changed)
    }

    @Test
    fun `update 操作记录弟子 id`() {
        val id = insertDisciple(5)
        tables.loyalties.update(id) { it + 10 }
        val changed = tables.changedIdTracker.consumeChangedIds()
        assertTrue("update 应记录 id=$id，实际=$changed", id in changed)
    }

    @Test
    fun `同值写入不记录 changedId（短路）`() {
        val id = insertDisciple(6)
        tables.loyalties[id] = 66
        tables.changedIdTracker.consumeChangedIds()

        // 同值重写：短路跳过，不产生 changedId
        tables.loyalties[id] = 66
        val changed = tables.changedIdTracker.consumeChangedIds()
        assertTrue("同值重写不应记录 changedId，实际=$changed", changed.isEmpty())
    }

    @Test
    fun `同值 Double 写入不记录 changedId`() {
        val id = insertDisciple(7)
        tables.cultivations[id] = 100.0
        tables.changedIdTracker.consumeChangedIds()

        tables.cultivations[id] = 100.0
        val changed = tables.changedIdTracker.consumeChangedIds()
        assertTrue("同值 Double 重写不应记录 changedId，实际=$changed", changed.isEmpty())
    }

    @Test
    fun `不同值写入记录 changedId`() {
        val id = insertDisciple(8)
        tables.loyalties[id] = 66
        tables.changedIdTracker.consumeChangedIds()

        tables.loyalties[id] = 60  // 不同值
        val changed = tables.changedIdTracker.consumeChangedIds()
        assertTrue("不同值写入应记录 id=$id，实际=$changed", id in changed)
    }

    @Test
    fun `同一 id 多次写入仅记录一次`() {
        val id = insertDisciple(9)
        tables.loyalties[id] = 10
        tables.loyalties[id] = 20
        tables.cultivations[id] = 1.0
        val changed = tables.changedIdTracker.consumeChangedIds()
        assertEquals("同 id 多次写入应去重为 1 条", 1, changed.size)
        assertTrue(id in changed)
    }

    @Test
    fun `多弟子写入记录全部 id 且升序`() {
        insertDisciple(10)
        insertDisciple(11)
        insertDisciple(12)
        tables.loyalties[10] = 1
        tables.loyalties[12] = 2
        tables.cultivations[11] = 3.0
        val changed = tables.changedIdTracker.consumeChangedIds()
        assertEquals(setOf(10, 11, 12), changed)
    }

    @Test
    fun `remove 产生 changedId 且增量组装剔除陈尸`() {
        insertDisciple(20)
        insertDisciple(21)
        val prev = tables.assembleAll()
        tables.remove(20)
        val changed = tables.changedIdTracker.consumeChangedIds()
        val merged = tables.assembleAllIncremental(prev, changed)
        assertEquals("移除的弟子不应残留", listOf(21), merged.map { it.id.toInt() })
    }
}
