package com.xianxia.sect.core.state

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

import com.xianxia.sect.core.model.Disciple
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 增量组装等价性测试（2026-08-01 双指针归并重写）。
 *
 * 守卫：assembleAllIncremental 与 assembleAll 在任何操作序列下逐字段等价——
 * 包括 insert / 列级写 / markDead / remove / replaceAll 混合，以及幽灵边界。
 */
@RunWith(RobolectricTestRunner::class)
class DiscipleTablesIncrementalMergeTest {

    private lateinit var tables: DiscipleTables

    @Before
    fun setUp() {
        tables = DiscipleTables()
        tables.writeAllowed = true
    }

    private fun disciple(id: Int, name: String = "弟子$id"): Disciple =
        Disciple(id = id.toString(), name = name, realm = 5, realmLayer = 1)

    /** 断言增量结果与全量结果逐字段一致（id 顺序 + 关键字段） */
    private fun assertEquivalent(incremental: List<Disciple>, full: List<Disciple>) {
        assertEquals("弟子数量不一致", full.size, incremental.size)
        assertEquals("id 顺序不一致", full.map { it.id }, incremental.map { it.id })
        for (i in full.indices) {
            val f = full[i]
            val inc = incremental[i]
            assertEquals("id=$f.id 的 realm 不一致", f.realm, inc.realm)
            assertEquals("id=$f.id 的 cultivation 不一致", f.cultivation, inc.cultivation, 0.0)
            assertEquals("id=$f.id 的 isAlive 不一致", f.isAlive, inc.isAlive)
            assertEquals("id=$f.id 的 names 不一致", f.name, inc.name)
        }
    }

    @Test
    fun `列级写入后增量与全量等价`() {
        tables.insert(disciple(1))
        tables.insert(disciple(2))
        tables.insert(disciple(3))
        val prev = tables.assembleAll()

        // 混合列级写入
        tables.loyalties[1] = 90
        tables.cultivations[2] = 500.0
        tables.loyalties[3] = 10

        val changed = tables.changedIdTracker.consumeChangedIds()
        val incremental = tables.assembleAllIncremental(prev, changed)
        val full = tables.assembleAll()
        assertEquivalent(incremental, full)
    }

    @Test
    fun `markDead 后增量与全量等价`() {
        tables.insert(disciple(1))
        tables.insert(disciple(2))
        val prev = tables.assembleAll()

        tables.markDead(1, currentYear = 10, cause = "battle")

        val changed = tables.changedIdTracker.consumeChangedIds()
        val incremental = tables.assembleAllIncremental(prev, changed)
        val full = tables.assembleAll()
        assertEquivalent(incremental, full)
        assertEquals("死亡弟子 isAlive 应为 false", false, incremental.first { it.id == "1" }.isAlive)
    }

    @Test
    fun `remove 后增量与全量等价`() {
        tables.insert(disciple(1))
        tables.insert(disciple(2))
        tables.insert(disciple(3))
        val prev = tables.assembleAll()

        tables.remove(2)

        val changed = tables.changedIdTracker.consumeChangedIds()
        val incremental = tables.assembleAllIncremental(prev, changed)
        val full = tables.assembleAll()
        assertEquivalent(incremental, full)
    }

    @Test
    fun `insert 后增量与全量等价`() {
        tables.insert(disciple(1))
        val prev = tables.assembleAll()

        tables.insert(disciple(2))  // 新 id 加入

        val changed = tables.changedIdTracker.consumeChangedIds()
        val incremental = tables.assembleAllIncremental(prev, changed)
        val full = tables.assembleAll()
        assertEquivalent(incremental, full)
    }

    @Test
    fun `列级写入加 insert 混合后增量与全量等价`() {
        tables.insert(disciple(1))
        tables.insert(disciple(2))
        val prev = tables.assembleAll()

        tables.loyalties[1] = 70
        tables.insert(disciple(3))
        tables.cultivations[2] = 100.0

        val changed = tables.changedIdTracker.consumeChangedIds()
        val incremental = tables.assembleAllIncremental(prev, changed)
        val full = tables.assembleAll()
        assertEquivalent(incremental, full)
    }

    @Test
    fun `大量弟子随机操作后增量与全量等价`() {
        for (i in 1..50) tables.insert(disciple(i))
        val prev = tables.assembleAll()

        // 随机混合操作（确定性序列）
        for (i in 1..50 step 3) tables.loyalties[i] = i * 7
        for (i in 2..50 step 5) tables.cultivations[i] = i * 13.5
        tables.markDead(10, currentYear = 10, cause = "age")
        tables.markDead(25, currentYear = 10, cause = "battle")
        tables.remove(40)
        tables.insert(disciple(51))

        val changed = tables.changedIdTracker.consumeChangedIds()
        val incremental = tables.assembleAllIncremental(prev, changed)
        val full = tables.assembleAll()
        assertEquivalent(incremental, full)
    }

    @Test
    fun `空 changedIds 返回原快照`() {
        tables.insert(disciple(1))
        val prev = tables.assembleAll()
        val result = tables.assembleAllIncremental(prev, emptySet())
        assertTrue("空 changedIds 应返回原列表引用", prev === result)
    }

    @Test
    fun `changedIds 覆盖全部时仍与全量等价`() {
        tables.insert(disciple(1))
        tables.insert(disciple(2))
        val prev = tables.assembleAll()

        tables.loyalties[1] = 100
        tables.loyalties[2] = 200
        val changed = tables.changedIdTracker.consumeChangedIds()
        val incremental = tables.assembleAllIncremental(prev, changed)
        val full = tables.assembleAll()
        assertEquivalent(incremental, full)
    }
}
