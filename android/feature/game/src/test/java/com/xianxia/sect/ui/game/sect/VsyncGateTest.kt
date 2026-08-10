package com.xianxia.sect.ui.game.sect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ★ Robolectric 必需：VsyncGate 内部引用 android.util.Log（失败路径），
 * 纯 JVM 下未 mock 会抛异常。
 *
 * ## 为什么注入 Fake 而非 ShadowChoreographer
 * Robolectric 不触发真实 Choreographer 帧回调；Fake FrameCallbackSource
 * 直接驱动 [VsyncGate] 的帧回调闭包，覆盖信号量语义/释放语义/中断语义。
 */
@RunWith(RobolectricTestRunner::class)
class VsyncGateTest {

    /** 可手动触发的帧回调源（记录订阅/取消） */
    private class FakeFrameCallbackSource : FrameCallbackSource {
        var callback: (() -> Unit)? = null
        var cancelled = false

        override fun subscribe(onFrame: () -> Unit): () -> Unit {
            callback = onFrame
            return { cancelled = true }
        }

        /** 模拟一个 vsync 帧回调 */
        fun emitTick() {
            callback?.invoke()
        }
    }

    @Test
    fun `awaitTick returns true when tick delivered`() {
        val source = FakeFrameCallbackSource()
        val gate = VsyncGate(source)

        source.emitTick()
        assertTrue(gate.awaitTick(TIMEOUT_MS))
        gate.release()
    }

    @Test
    fun `awaitTick returns false on timeout without tick`() {
        val gate = VsyncGate(FakeFrameCallbackSource())

        assertFalse(gate.awaitTick(TIMEOUT_MS))
        gate.release()
    }

    @Test
    fun `tick backlog collapses to single permit`() {
        // 防积压：多个 vsync 未消费时只留 1 个 permit（tryAcquire 清积压 + release 净置 1）
        val source = FakeFrameCallbackSource()
        val gate = VsyncGate(source)

        source.emitTick()
        source.emitTick()
        source.emitTick()
        assertTrue(gate.awaitTick(TIMEOUT_MS))
        // 第二个 awaitTick 不应再有 permit
        assertFalse(gate.awaitTick(SHORT_TIMEOUT_MS))
        gate.release()
    }

    @Test
    fun `awaitTick before emit also receives the tick`() {
        val source = FakeFrameCallbackSource()
        val gate = VsyncGate(source)

        // 先阻塞等待，后触发（真实时序：渲染线程先 await，vsync 后到）
        val waiter = Thread {
            assertTrue(gate.awaitTick(WAIT_MS))
        }
        waiter.start()
        Thread.sleep(50)
        source.emitTick()
        waiter.join(WAIT_MS * 2)
        assertFalse(waiter.isAlive)
        gate.release()
    }

    @Test
    fun `release cancels subscription`() {
        val source = FakeFrameCallbackSource()
        val gate = VsyncGate(source)

        gate.release()
        assertTrue(source.cancelled)
    }

    @Test
    fun `release is idempotent`() {
        val source = FakeFrameCallbackSource()
        val gate = VsyncGate(source)

        gate.release()
        gate.release()
        gate.close() // AutoCloseable 路径同样幂等
        assertTrue(source.cancelled)
    }

    @Test
    fun `awaitTick after release returns false`() {
        val gate = VsyncGate(FakeFrameCallbackSource())

        gate.release()
        assertFalse(gate.awaitTick(TIMEOUT_MS))
    }

    @Test
    fun `frame callback after release does not leak permit`() {
        val source = FakeFrameCallbackSource()
        val gate = VsyncGate(source)
        gate.release()

        // released 守卫：释放后迟到的 vsync 回调不得重新置 permit
        source.emitTick()
        assertFalse(gate.awaitTick(SHORT_TIMEOUT_MS))
    }

    @Test
    fun `awaitTick returns false when thread interrupted`() {
        val gate = VsyncGate(FakeFrameCallbackSource())

        Thread.currentThread().interrupt()
        try {
            assertFalse(gate.awaitTick(TIMEOUT_MS))
        } finally {
            // 清除中断标志，避免污染后续测试线程
            Thread.interrupted()
        }
        gate.release()
    }

    companion object {
        /** 超时断言窗口（真实时钟等待，留足调度余量） */
        private const val TIMEOUT_MS = 200L

        /** 短超时：验证"无 permit"场景（真实阻塞等待窗口） */
        private const val SHORT_TIMEOUT_MS = 80L

        /** awaitTick 阻塞等待窗口 */
        private const val WAIT_MS = 500L
    }
}
