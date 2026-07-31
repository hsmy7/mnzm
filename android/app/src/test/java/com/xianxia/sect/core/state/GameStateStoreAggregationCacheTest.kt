package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.data.GameStateRepository
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 聚合链缓存测试（2026-08-01，3.5 修复验证）。
 *
 * 修复前：discipleAggregatesSnapshot 在调用线程全量 toAggregate()——
 * UI 打开弹窗触发多次主线程 O(D) 扫描（掉帧）。修复后为 O(1) 缓存读取。
 * 本测试守卫：
 * 1. 快照缓存与聚合链结果一致（覆盖全部弟子，含死亡）
 * 2. 单弟子变更后未变弟子的 Aggregate 对象 === 复用（增量聚合对象复用）
 * 3. Eagerly 下无订阅时缓存仍更新（弹窗读取永远最新）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameStateStoreAggregationCacheTest {

    private fun disciple(id: Int): Disciple =
        Disciple(id = id.toString(), name = "弟子$id", realm = 5, realmLayer = 1)

    private fun store(): GameStateStoreImpl {
        val s = GameStateStoreImpl(ApplicationScopeProvider(), mock(GameStateRepository::class.java))
        s.unsafeAllowMainThreadUpdateForTest = true
        return s
    }

    @Test
    fun `快照缓存与聚合链结果一致且覆盖全部弟子`() = runBlocking {
        val store = store()
        store.update {
            for (i in 1..300) discipleTables.insert(disciple(i))
        }
        // 等 sample(100) 窗口 + 聚合计算
        delay(800)

        val snapshot = store.discipleAggregatesSnapshot
        assertEquals("快照应覆盖全部 300 弟子", 300, snapshot.size)
        assertTrue("快照应含弟子 1", snapshot.any { it.id == "1" })
        assertTrue("快照应含弟子 300", snapshot.any { it.id == "300" })
        // 与聚合链结果一致（WhileSubscribed 订阅后经 sample(100) 计算，等待非空）
        val chainSize = store.discipleAggregates.first { it.isNotEmpty() }.size
        assertEquals("快照与聚合链结果一致", chainSize, snapshot.size)
    }

    @Test
    fun `单弟子变更后未变弟子 Aggregate 对象复用`() = runBlocking {
        val store = store()
        store.update {
            for (i in 1..100) discipleTables.insert(disciple(i))
        }
        delay(800)
        val before = store.discipleAggregatesSnapshot

        // 变更单个弟子（修为列级写入）
        store.update {
            discipleTables.cultivations[50] = 999.0
        }
        delay(800)
        val after = store.discipleAggregatesSnapshot

        assertEquals("数量不变", before.size, after.size)
        // 未变弟子（如 id=1）的 Aggregate 对象必须复用（===）
        val beforeId1 = before.first { it.id == "1" }
        val afterId1 = after.first { it.id == "1" }
        assertSame("未变弟子 Aggregate 对象应复用（===）", beforeId1, afterId1)
        // 变更弟子（id=50）对象应为新实例
        val beforeId50 = before.first { it.id == "50" }
        val afterId50 = after.first { it.id == "50" }
        assertTrue("变更弟子对象不应复用", beforeId50 !== afterId50)
    }

    @Test
    fun `无订阅时缓存仍更新（Eagerly）`() = runBlocking {
        val store = store()
        store.update {
            discipleTables.insert(disciple(1))
        }
        delay(800)
        assertEquals("初始快照 1 弟子", 1, store.discipleAggregatesSnapshot.size)

        // 不订阅任何聚合 StateFlow（Eagerly 保证无订阅也计算）
        store.update {
            discipleTables.insert(disciple(2))
        }
        delay(800)

        assertEquals("无订阅时缓存仍更新", 2, store.discipleAggregatesSnapshot.size)
    }

    @Test
    fun `死亡弟子仍在快照中`() = runBlocking {
        val store = store()
        store.update {
            discipleTables.insert(disciple(1))
            discipleTables.insert(disciple(2))
        }
        delay(800)

        store.update {
            discipleTables.markDead(2, currentYear = 10, cause = "battle")
        }
        delay(800)

        val snapshot = store.discipleAggregatesSnapshot
        assertEquals("死亡弟子仍在快照中（aggregates 覆盖全部弟子）", 2, snapshot.size)
        assertEquals("弟子 2 标记为死亡", false, snapshot.first { it.id == "2" }.isAlive)
    }
}
