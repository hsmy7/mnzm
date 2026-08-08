package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L3a 年变延迟队列单元测试。
 *
 * 覆盖：FIFO 顺序、预算边界（首个 op 超预算仍执行、预算耗尽停止）、
 * forceDrain 全量清空、空队列、clear 防御。
 */
class YearlyOpsQueueTest {

    private fun <T> opProbe(collector: MutableList<T>, value: T): MutableGameState.() -> Unit =
        { collector.add(value) }

    private fun stubState(): MutableGameState {
        val tables = DiscipleTables().apply { writeAllowed = true }
        return MutableGameState(
            gameData = GameData(),
            discipleTables = tables,
            equipmentStacks = EntityStore(emptyList()),
            equipmentInstances = EntityStore(emptyList()),
            manualStacks = EntityStore(emptyList()),
            manualInstances = EntityStore(emptyList()),
            pills = EntityStore(emptyList()),
            materials = EntityStore(emptyList()),
            herbs = EntityStore(emptyList()),
            seeds = EntityStore(emptyList()),
            storageBags = EntityStore(emptyList()),
            teams = emptyList(),
            battleLogs = emptyList(),
            isPaused = false,
            isLoading = false,
            isSaving = false
        )
    }

    @Test
    fun `drain executes ops in FIFO order`() {
        val queue = YearlyOpsQueue()
        val order = mutableListOf<Int>()
        queue.enqueue(opProbe(order, 1))
        queue.enqueue(opProbe(order, 2))
        queue.enqueue(opProbe(order, 3))

        val cleared = queue.drain(timeBudgetMs = 100, runOp = { op -> op.invoke(stubState()) })

        assertTrue("预算充足应清空", cleared)
        assertEquals(listOf(1, 2, 3), order)
        assertEquals("队列应空", 0, queue.size)
    }

    @Test
    fun `drain executes first op even if it exceeds budget`() {
        val queue = YearlyOpsQueue()
        var firstExecuted = false
        var secondExecuted = false
        queue.enqueue {
            // 首个 op 超预算（> 10ms）
            Thread.sleep(50)
            firstExecuted = true
        }
        queue.enqueue { secondExecuted = true }

        val cleared = queue.drain(timeBudgetMs = 10, runOp = { op -> op.invoke(stubState()) })

        assertFalse("预算耗尽不应清空", cleared)
        assertTrue("首个 op 超预算也必须执行", firstExecuted)
        assertFalse("后续 op 不应执行", secondExecuted)
        assertEquals("剩余 1 个 op", 1, queue.size)
    }

    @Test
    fun `drain stops when budget exhausted mid-way`() {
        val queue = YearlyOpsQueue()
        var executedCount = 0
        repeat(5) {
            queue.enqueue {
                Thread.sleep(30)
                executedCount++
            }
        }

        // 首个 op 执行后已 30ms > 20ms 预算，但"至少执行 1 个"语义保证其执行；
        // 第二个 op 开始前检查预算已耗尽 → 停止
        val cleared = queue.drain(timeBudgetMs = 20, runOp = { op -> op.invoke(stubState()) })

        assertFalse(cleared)
        assertEquals("仅执行 1 个 op", 1, executedCount)
        assertEquals(4, queue.size)
    }

    @Test
    fun `empty queue drains immediately as cleared`() {
        val queue = YearlyOpsQueue()
        val cleared = queue.drain(timeBudgetMs = 10, runOp = { op -> op.invoke(stubState()) })
        assertTrue("空队列视为已清空", cleared)
    }

    @Test
    fun `forceDrain clears all ops regardless of budget`() {
        val queue = YearlyOpsQueue()
        var executedCount = 0
        repeat(4) {
            queue.enqueue {
                Thread.sleep(40)
                executedCount++
            }
        }

        val cleared = queue.forceDrain(runOp = { op -> op.invoke(stubState()) })

        assertTrue(cleared)
        assertEquals("forceDrain 全量执行", 4, executedCount)
        assertEquals(0, queue.size)
    }

    @Test
    fun `clear drops pending ops`() {
        val queue = YearlyOpsQueue()
        var executed = false
        queue.enqueue { executed = true }

        queue.clear()

        assertEquals(0, queue.size)
        val cleared = queue.drain(timeBudgetMs = 100, runOp = { op -> op.invoke(stubState()) })
        assertTrue(cleared)
        assertFalse("clear 后 op 不应执行", executed)
    }

    @Test
    fun `concurrent drain and forceDrain preserve FIFO order`() {
        // 对抗性审查 F2 守卫：drain（引擎 tick）与 forceDrain（存档线程）双消费者
        // 并发时，若 forceDrain 在 drain 执行 op1 期间插队 poll op2，顺序将倒置为
        // [2,1,3]。consumerLock 互斥保证同刻至多一个消费者 → 顺序恒为 [1,2,3]。
        val queue = YearlyOpsQueue()
        val order = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val drainStarted = java.util.concurrent.CountDownLatch(1)
        val op1Done = java.util.concurrent.CountDownLatch(1)
        queue.enqueue {
            drainStarted.countDown()
            Thread.sleep(100) // 保证 forceDrain 在 op1 执行中启动
            order.add(1)
            op1Done.countDown()
        }
        queue.enqueue { order.add(2) }
        queue.enqueue { order.add(3) }

        val drainThread = Thread {
            queue.drain(timeBudgetMs = 0, runOp = { op -> op.invoke(stubState()) })
        }
        drainThread.start()
        drainStarted.await() // drain 已 poll op1 并执行中（持 consumerLock）
        val forceThread = Thread {
            queue.forceDrain(runOp = { op -> op.invoke(stubState()) })
        }
        forceThread.start()
        op1Done.await() // op1 完成，drain 即将释放锁
        forceThread.join(5_000)
        drainThread.join(5_000)

        assertEquals("并发 drain/forceDrain 下 FIFO 顺序不倒置", listOf(1, 2, 3), order)
        assertEquals(0, queue.size)
    }
}
