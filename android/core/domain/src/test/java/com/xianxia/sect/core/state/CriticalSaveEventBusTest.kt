package com.xianxia.sect.core.state

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 关键事件自动存档总线测试（方案 §2.5"关键事件立即落盘"的事件传输面）。
 *
 * 守卫契约：
 * 1. [CriticalSaveEventBus.notify] 发出的事件按序到达订阅流（不丢、不改类别）；
 * 2. [CriticalSaveEventBus.awaitNextSaveCompletion] 挂起直到保存完成信号——
 *    涉钱同步落盘承诺的应答面（事件方法返回前数据已落盘的机制保证）；
 * 3. 超时返回 false（保存被前置门控拒绝等场景），等待方不被拖死；
 * 4. 保存完成信号在无等待方时发出不丢给后续等待方之前的到达序
 *    （SharedFlow 无 replay：先 notifySaveCompleted 后 await 的调用方等到的是
 *    **下一次**保存——与"事件方法发出请求后才等待"的调用序一致）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CriticalSaveEventBusTest {

    @Test
    fun `notify delivers every kind to subscribers in order`() = runTest {
        val bus = CriticalSaveEventBus()
        val received = mutableListOf<CriticalSaveKind>()
        val collector = launch { bus.events.collect { received += it } }
        runCurrent()

        assertTrue(bus.notify(CriticalSaveKind.MONEY))
        assertTrue(bus.notify(CriticalSaveKind.GACHA))
        assertTrue(bus.notify(CriticalSaveKind.IRREVERSIBLE_CONSUME))
        assertTrue(bus.notify(CriticalSaveKind.MILESTONE))
        runCurrent()

        assertEquals(
            listOf(
                CriticalSaveKind.MONEY,
                CriticalSaveKind.GACHA,
                CriticalSaveKind.IRREVERSIBLE_CONSUME,
                CriticalSaveKind.MILESTONE
            ),
            received
        )
        collector.cancel()
    }

    @Test
    fun `awaitNextSaveCompletion suspends until save completes`() = runTest {
        val bus = CriticalSaveEventBus()
        val awaiting = async { bus.awaitNextSaveCompletion(MONEY_SAVE_ACK_TIMEOUT_MS) }
        runCurrent()
        assertFalse("信号未到前不得返回（挂起等待中）", awaiting.isCompleted)

        bus.notifySaveCompleted()
        runCurrent()

        assertTrue("保存完成信号到达后必须返回 true", awaiting.await())
    }

    @Test
    fun `awaitNextSaveCompletion times out when no save completes`() = runTest {
        val bus = CriticalSaveEventBus()

        val acked = bus.awaitNextSaveCompletion(timeoutMs = 1_000L)

        assertFalse("超时必须返回 false（等待方不被拖死，节拍兜底）", acked)
    }

    @Test
    fun `timeout does not consume a later completion signal`() = runTest {
        val bus = CriticalSaveEventBus()
        assertFalse(bus.awaitNextSaveCompletion(timeoutMs = 500L)) // 虚拟时间自动推进至超时

        // 超时后的第一次信号发出时已无等待方（SharedFlow 无 replay ⇒ 丢弃）；
        // 后续等待方等到的是它发起之后的下一次保存——与"事件方法先请求后等待"的调用序一致
        bus.notifySaveCompleted()
        val awaiting = async { bus.awaitNextSaveCompletion(MONEY_SAVE_ACK_TIMEOUT_MS) }
        runCurrent()
        assertFalse("先于新等待方发出的信号不得被其消费", awaiting.isCompleted)

        bus.notifySaveCompleted()
        runCurrent()
        assertTrue(awaiting.await())
    }
}
