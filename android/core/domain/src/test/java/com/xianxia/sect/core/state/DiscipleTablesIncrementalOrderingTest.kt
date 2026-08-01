package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.Disciple
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 增量组装升序不变量回归测试（2026-08-01 对抗性审查发现）。
 *
 * 修复前：双指针归并依赖 prevSnapshot 按 id 升序——但读档路径
 * （DiscipleDataDao.getAllSync = ORDER BY realm, cultivation）产出非升序列表，
 * 直接赋值给 _disciplesFlow 后，增量归并在非升序 prev 上产生重复弟子。
 * 修复：assembleAllIncremental 入口校验升序，失序时退化为全量。
 */
@RunWith(RobolectricTestRunner::class)
class DiscipleTablesIncrementalOrderingTest {

    private lateinit var tables: DiscipleTables

    @Before
    fun setUp() {
        tables = DiscipleTables()
        tables.writeAllowed = true
    }

    private fun disciple(id: Int): Disciple =
        Disciple(id = id.toString(), name = "弟子$id", realm = 5, realmLayer = 1)

    @Test
    fun `非升序 prevSnapshot 不产生重复弟子`() {
        // 模拟读档后的顺序（realm 排序，非 id）：[3, 1, 2]
        val prev = listOf(disciple(3), disciple(1), disciple(2))
        // 表中按读档顺序插入
        tables.insert(disciple(3))
        tables.insert(disciple(1))
        tables.insert(disciple(2))
        tables.changedIdTracker.consumeChangedIds()

        // 变更弟子 1
        tables.loyalties[1] = 66
        val changed = tables.changedIdTracker.consumeChangedIds()
        assertEquals(setOf(1), changed)

        val result = tables.assembleAllIncremental(prev, changed)
        val ids = result.map { it.id.toInt() }
        assertEquals("非升序 prev 不应产生重复弟子：$ids", ids.size, ids.distinct().size)
        assertEquals("结果应含全部 3 个弟子", setOf(1, 2, 3), ids.toSet())
    }

    @Test
    fun `升序 prevSnapshot 保持增量收益`() {
        val prev = listOf(disciple(1), disciple(2), disciple(3))
        tables.insert(disciple(1))
        tables.insert(disciple(2))
        tables.insert(disciple(3))
        tables.changedIdTracker.consumeChangedIds()

        tables.loyalties[2] = 66
        val changed = tables.changedIdTracker.consumeChangedIds()

        val result = tables.assembleAllIncremental(prev, changed)
        assertEquals(listOf(1, 2, 3), result.map { it.id.toInt() })
        // 升序时未变弟子对象复用（增量收益标志）
        assertEquals("未变弟子对象应复用", true, result[0] === prev[0])
    }
}
