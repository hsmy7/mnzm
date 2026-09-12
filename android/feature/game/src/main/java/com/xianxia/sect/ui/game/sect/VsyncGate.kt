package com.xianxia.sect.ui.game.sect

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Choreographer
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * vsync 节拍源抽象 — [subscribe] 注册每帧回调，返回取消闭包。
 *
 * 独立于 [VsyncGate] 以便测试注入 Fake（Robolectric 无真实 vsync 回调）。
 * iOS 对等：CADisplayLink（`add(to:forMode:)` + `invalidate()`）。
 */
fun interface FrameCallbackSource {

    /**
     * 注册帧回调（每个显示帧调用一次，调用线程由实现保证）。
     *
     * @param onFrame 每帧回调（实现需处理自身重注册——Choreographer 回调一次性）
     * @return 取消闭包（幂等）
     */
    fun subscribe(onFrame: () -> Unit): () -> Unit
}

/**
 * Choreographer 实现 — HandlerThread 承载（Choreographer 必须在其 looper 线程
 * 创建/注册），回调每 vsync 触发。
 *
 * ## 失败语义
 * Choreographer 不可用（异常 ROM/测试环境）时 [subscribe] 返回空操作——
 * [VsyncGate.awaitTick] 恒超时 → 渲染循环回退 sleep 节拍（行为 = 现状）。
 */
class ChoreographerFrameCallbackSource : FrameCallbackSource {

    private val handlerThread = HandlerThread(VSYNC_THREAD_NAME).also { it.start() }
    private val handler = Handler(handlerThread.looper)

    /** 本实例回调激活标志（多实例互不干扰——必须为实例字段而非 companion） */
    private val active = AtomicBoolean(false)

    @Volatile
    private var choreographer: Choreographer? = null

    init {
        handler.post { createChoreographer() }
    }

    /**
     * 惰性创建 Choreographer（HandlerThread looper 线程内执行；失败保持 null → subscribe 走空操作路径）。
     *
     * @Suppress(TooGenericExceptionCaught)：Choreographer/异常 ROM 抛精确 RuntimeException
     * 子类不可穷举——vsync 不可用即回退 sleep 节拍（行为 = 现状），
     * 任何异常不得杀死 HandlerThread（否则回调线程崩溃冒泡 RuntimeInit 杀进程）
     */
    @Suppress("TooGenericExceptionCaught")
    private fun createChoreographer() {
        try {
            choreographer = Choreographer.getInstance()
        } catch (t: RuntimeException) {
            // 非 looper 线程/异常 ROM → 保持 null，subscribe 走空操作路径
            Log.w(TAG, "Choreographer unavailable: ${t.message}")
        }
    }

    /**
     * @Suppress(TooGenericExceptionCaught)：doFrame 重注册/removeFrameCallback 对
     * 异常 ROM 抛精确 RuntimeException 子类不可穷举——vsync 不可用即回退 sleep 节拍
     * （行为 = 现状），任何异常不得杀死 HandlerThread
     */
    @Suppress("TooGenericExceptionCaught")
    override fun subscribe(onFrame: () -> Unit): () -> Unit {
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val ch = choreographer ?: return
                if (!active.get()) return // 已取消
                onFrame()
                // Choreographer 回调一次性——每帧重新注册
                try {
                    ch.postFrameCallback(this)
                } catch (t: RuntimeException) {
                    // looper 退出中（shutdown 竞态）——忽略，released 守卫已拦截
                    Log.d(TAG, "postFrameCallback failed (looper exiting): ${t.message}")
                }
            }
        }
        handler.post {
            val ch = choreographer
            if (ch != null) {
                active.set(true)
                ch.postFrameCallback(callback)
            }
        }
        return {
            handler.post {
                if (active.compareAndSet(true, false)) {
                    val ch = choreographer
                    if (ch != null) {
                        try {
                            ch.removeFrameCallback(callback)
                        } catch (t: RuntimeException) {
                            Log.w(TAG, "removeFrameCallback failed: ${t.message}")
                        }
                    }
                }
            }
        }
    }

    /** 停止节拍线程（quitSafely + join 超时双保险防线程泄漏）。幂等。 */
    fun shutdown() {
        handlerThread.quitSafely()
        if (Thread.currentThread() !== handlerThread) {
            try {
                handlerThread.join(SHUTDOWN_JOIN_TIMEOUT_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.w(TAG, "vsync thread join interrupted")
            }
        }
    }

    companion object {
        private const val TAG = "VsyncGate"
        private const val VSYNC_THREAD_NAME = "VsyncGate"
        private const val SHUTDOWN_JOIN_TIMEOUT_MS = 500L
    }
}

/**
 * vsync 节拍门 — RenderThread 与显示刷新率对齐的等待闸（Canvas 软件路径主收益；
 * Vulkan 路径由 FIFO 交换链天然 vsync 对齐，不使用本组件）。
 *
 * ## 设计
 * - [subscribe] 帧回调 → 信号量净置 1（先 tryAcquire 清积压再 release，
 *   每个 vsync 恰好一个 permit，防回调堆积导致突突帧）
 * - [awaitTick] 阻塞等待下一节拍；超时返回 false（调用方 sleep 节拍兜底，
 *   初始化失败时恒超时——行为完全回退 sleep 路径，零崩溃路径）
 * - [release] 幂等：取消回调 + quitSafely + join 超时双保险
 */
class VsyncGate(
    private val callbackSource: FrameCallbackSource = ChoreographerFrameCallbackSource()
) : AutoCloseable {

    private val tick = Semaphore(0)

    @Volatile
    private var released = false

    // 声明顺序敏感：frameCallback 必须在 cancel 之前（cancel 初始化器引用它）
    private val frameCallback: () -> Unit = {
        if (!released) {
            // 清积压 + 净置 1：每个 vsync 恰好一个 permit
            tick.tryAcquire()
            tick.release()
        }
    }

    private val cancel: () -> Unit = runCatching { callbackSource.subscribe(frameCallback) }
        .getOrElse { t ->
            Log.w(TAG, "vsync gate init failed — falling back to sleep pacing: ${t.message}")
            NOOP
        }

    /**
     * 等待下一节拍。
     *
     * @param timeoutMs 超时毫秒（渲染线程按节拍剩余传入）
     * @return true = 节拍送达；false = 超时/已释放/不可用（调用方 sleep 兜底）
     */
    fun awaitTick(timeoutMs: Long): Boolean {
        if (released) return false
        return try {
            tick.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            // 渲染线程中断（surfaceDestroyed）→ 恢复标志后返回 false，
            // 循环条件 running/isReady 负责退出
            Thread.currentThread().interrupt()
            false
        }
    }

    /**
     * 释放资源（幂等）：取消回调 + 停止节拍线程。
     *
     * @Suppress(TooGenericExceptionCaught)：取消闭包在 looper 退出竞态下抛精确
     * RuntimeException 子类不可穷举——防御性兜底，任何异常不得阻断 release
     */
    @Suppress("TooGenericExceptionCaught")
    fun release() {
        if (released) return
        released = true
        try {
            cancel()
        } catch (t: RuntimeException) {
            Log.w(TAG, "vsync unsubscribe failed: ${t.message}")
        }
        if (callbackSource is ChoreographerFrameCallbackSource) {
            callbackSource.shutdown()
        }
    }

    override fun close() = release()

    companion object {
        private const val TAG = "VsyncGate"

        /** 空操作取消闭包（初始化失败路径）——避免 catch 内裸 `{}` 的 Kotlin 解析歧义 */
        private val NOOP: () -> Unit = {}
    }
}
