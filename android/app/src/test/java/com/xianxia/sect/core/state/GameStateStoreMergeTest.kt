package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * mergeAggregatesIncremental 增量归并测试（Q-3 填充，2026-08-02）。
 *
 * 守卫（P-5 聚合与组装对齐后的核心增量逻辑）：
 * 1. 引用相等的未变弟子复用旧 Aggregate 对象（UI 引用稳定）
 * 2. 同 id 新对象必须重建聚合（列级变更不丢失）
 * 3. 新增/删除弟子正确归并
 * 4. 失序列表退化全量（正确性优先）
 * 5. 空 prev 退化全量
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameStateStoreMergeTest {

    private fun disciple(id: Int, cultivation: Double = 0.0): Disciple =
        Disciple(id = id.toString(), name = "弟子$id", realm = 5, realmLayer = 1)
            .copy(cultivation = cultivation)

    private fun store(): GameStateStoreImpl {
        val s = GameStateStoreImpl(
            com.xianxia.sect.di.ApplicationScopeProvider(),
            org.mockito.Mockito.mock(com.xianxia.sect.data.GameStateRepository::class.java)
        )
        s.unsafeAllowMainThreadUpdateForTest = true
        return s
    }

    /** 构造 prev 聚合列表（id 升序） */
    private fun prevAggregates(disciples: List<Disciple>): List<DiscipleAggregate> =
        disciples.sortedBy { it.id.toIntOrNull() }.map { it.toAggregate() }

    @Test
    fun `引用相等的未变弟子复用旧 Aggregate 对象`() {
        val store = store()
        val prevDiscs = (1..5).map { disciple(it) }
        val prev = prevAggregates(prevDiscs)

        // 只有弟子 3 变更（新对象），其余引用相等
        val cur = prevDiscs.map { if (it.id == "3") disciple(3, 999.0) else it }
        val merged = store.mergeAggregatesIncremental(prev, cur)

        assertEquals("归并结果数量一致", 5, merged.size)
        // 未变弟子（1/2/4/5）复用旧 Aggregate 对象
        for (d in cur) {
            if (d.id == "3") continue
            val prevObj = prev.first { it.id == d.id }
            val mergedObj = merged.first { it.id == d.id }
            assertSame("id=${d.id} 未变应复用旧对象", prevObj, mergedObj)
        }
        // 变更弟子（3）必须重建（修为 999 反映到聚合）
        val changed = merged.first { it.id == "3" }
        assertNotSame("id=3 变更应重建", prev.first { it.id == "3" }, changed)
        assertEquals("变更弟子修为反映到聚合", 999.0, changed.cultivation, 0.0)
    }

    @Test
    fun `新增与删除弟子正确归并`() {
        val store = store()
        val prevDiscs = (1..4).map { disciple(it) }
        val prev = prevAggregates(prevDiscs)

        // 删除 2、新增 6
        val cur = listOf(disciple(1), disciple(3), disciple(4), disciple(6, 123.0))
        val merged = store.mergeAggregatesIncremental(prev, cur)

        assertEquals("归并结果数量 = 当前列表数量", 4, merged.size)
        assertEquals("应含新增弟子 6", listOf("1", "3", "4", "6"), merged.map { it.id })
        assertTrue("不应含已删除弟子 2", merged.none { it.id == "2" })
    }

    @Test
    fun `失序列表退化全量`() {
        val store = store()
        val prevDiscs = (1..3).map { disciple(it) }
        val prev = prevAggregates(prevDiscs)

        // 失序列表（3/1/2）
        val cur = listOf(disciple(3), disciple(1), disciple(2))
        val merged = store.mergeAggregatesIncremental(prev, cur)

        assertEquals("失序退化后数量一致", 3, merged.size)
        assertTrue("失序退化覆盖全部", merged.map { it.id }.toSet() == setOf("1", "2", "3"))
    }

    @Test
    fun `空 prev 退化全量`() {
        val store = store()
        val cur = (1..3).map { disciple(it) }
        val merged = store.mergeAggregatesIncremental(emptyList(), cur)
        assertEquals("空 prev 退化后数量一致", 3, merged.size)
        assertTrue("空 prev 退化覆盖全部", merged.map { it.id }.toSet() == setOf("1", "2", "3"))
    }
}
