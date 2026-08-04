package com.xianxia.sect.core.state

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

import com.xianxia.sect.core.model.Disciple
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    // ════════════════════════════════════════════════════════════
    // T4（2026-08-05）：容量拒绝 → 强制全量组装标志
    // ════════════════════════════════════════════════════════════

    @Test
    fun `record over MAX_SAFE_CAPACITY sets force flag`() {
        tables.changedIdTracker.record(MAX_SAFE_CAPACITY)
        assertTrue("超限 id 应置强制全量标志", tables.changedIdTracker.consumeRejectedRecord())
    }

    @Test
    fun `record normal id does not set flag`() {
        tables.changedIdTracker.record(42)
        assertTrue("正常 id 不应置强制全量标志", !tables.changedIdTracker.consumeRejectedRecord())
    }

    @Test
    fun `consumeRejectedRecord clears flag`() {
        tables.changedIdTracker.markRejectedForTest()
        assertTrue(tables.changedIdTracker.consumeRejectedRecord())
        // 二次消费返回 false（标志已清除）
        assertTrue("标志消费后应清除", !tables.changedIdTracker.consumeRejectedRecord())
    }

    @Test
    fun `recordAll with oversized id sets flag while valid ids still recorded`() {
        tables.changedIdTracker.recordAll(listOf(7, MAX_SAFE_CAPACITY, 8))
        val changed = tables.changedIdTracker.consumeChangedIds()
        assertEquals("合法 id 仍被记录", setOf(7, 8), changed)
        assertTrue("超限 id 应置强制全量标志", tables.changedIdTracker.consumeRejectedRecord())
    }

    @Test
    fun `negative id ignored without flag`() {
        tables.changedIdTracker.record(-1)
        tables.changedIdTracker.recordAll(listOf(-5))
        assertTrue("负 id 不应置强制全量标志", !tables.changedIdTracker.consumeRejectedRecord())
    }

    // ════════════════════════════════════════════════════════════
    // C3（2026-08-05）：MAX_SAFE_CAPACITY 降至 1M 的边界守卫
    // ════════════════════════════════════════════════════════════

    @Test
    fun `insert with id at MAX_SAFE_CAPACITY rejected`() {
        // C3-a：常量 10M→1M 后，1M 起 require 拒绝（原 10M 恰在限制内，id=9,999,999 触发
        // ~60 张平铺表千万级扩容 ≈ 7GB OOM 崩溃且重试即崩溃循环）。
        // 注意：不能测"上限内大 id 可插入"——999,999 仍触发 ~720MB 扩容，JVM 测试 OOM 风险，
        // 正常小 id 插入路径已由本文件其他用例覆盖
        assertThrows(IllegalArgumentException::class.java) {
            tables.insert(com.xianxia.sect.core.model.Disciple(id = MAX_SAFE_CAPACITY.toString(), name = "crafted"))
        }
    }
}
