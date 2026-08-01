package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.data.GameStateRepository
import com.xianxia.sect.di.ApplicationScopeProvider
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
        // 等 sample(100) 窗口 + 聚合计算（轮询目标状态，慢 CI 不抖动）
        TestPolling.awaitCondition("300 弟子聚合快照就绪") {
            store.discipleAggregatesSnapshot.size == 300
        }

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
        TestPolling.awaitCondition("100 弟子聚合快照就绪") {
            store.discipleAggregatesSnapshot.size == 100
        }
        val before = store.discipleAggregatesSnapshot

        // 变更单个弟子（修为列级写入）
        store.update {
            discipleTables.cultivations[50] = 999.0
        }
        TestPolling.awaitCondition(
            "单弟子变更后聚合生效",
            condition = {
                store.discipleAggregatesSnapshot.size == 100 &&
                    store.discipleAggregatesSnapshot.firstOrNull { it.id == "50" }?.cultivation == 999.0
            },
            stateSnapshot = { store.discipleAggregatesSnapshot.size.toString() }
        )
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
        TestPolling.awaitCondition("初始快照 1 弟子") {
            store.discipleAggregatesSnapshot.size == 1
        }

        // 不订阅任何聚合 StateFlow（Eagerly 保证无订阅也计算）
        store.update {
            discipleTables.insert(disciple(2))
        }
        TestPolling.awaitCondition("无订阅时缓存仍更新为 2 弟子") {
            store.discipleAggregatesSnapshot.size == 2
        }
    }

    @Test
    fun `死亡弟子仍在快照中`() = runBlocking {
        val store = store()
        store.update {
            discipleTables.insert(disciple(1))
            discipleTables.insert(disciple(2))
        }
        TestPolling.awaitCondition("2 弟子聚合快照就绪") {
            store.discipleAggregatesSnapshot.size == 2
        }

        store.update {
            discipleTables.markDead(2, currentYear = 10, cause = "battle")
        }
        TestPolling.awaitCondition(
            "死亡弟子聚合生效",
            condition = {
                store.discipleAggregatesSnapshot.size == 2 &&
                    store.discipleAggregatesSnapshot.firstOrNull { it.id == "2" }?.isAlive == false
            },
            stateSnapshot = {
                "size=${store.discipleAggregatesSnapshot.size}, " +
                    "id2.isAlive=${store.discipleAggregatesSnapshot.firstOrNull { it.id == "2" }?.isAlive}"
            }
        )

        val snapshot = store.discipleAggregatesSnapshot
        assertEquals("死亡弟子仍在快照中（aggregates 覆盖全部弟子）", 2, snapshot.size)
        assertEquals("弟子 2 标记为死亡", false, snapshot.first { it.id == "2" }.isAlive)
    }
}
