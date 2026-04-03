package com.xianxia.sect.di

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用级 CoroutineScope 提供者
 *
 * 职责：
 * - 提供与应用生命周期绑定的 CoroutineScope 实例
 * - 实现 [Closeable] 确保在应用终止时自动取消所有协程
 * - 监控活跃协程数量，超过阈值时告警
 *
 * 使用方式：
 * - 通过 Hilt @Inject 注入使用
 * - 在 [com.xianxia.sect.XianxiaApplication.onTerminate] 中调用 [close]
 */
@Singleton
class ApplicationScopeProvider @Inject constructor() : Closeable {

    companion object {
        private const val TAG = "AppScopeProvider"
        /** 活跃协程告警阈值 */
        private const val ACTIVE_COROUTINE_WARN_THRESHOLD = 200
    }

    private val supervisorJob = SupervisorJob()

    /** 应用级默认 Scope (Dispatchers.Default) */
    val scope: CoroutineScope = CoroutineScope(supervisorJob + Dispatchers.Default)

    /** IO 密集型 Scope (Dispatchers.IO)，共享同一 SupervisorJob 以便统一取消 */
    val ioScope: CoroutineScope = CoroutineScope(supervisorJob + Dispatchers.IO)

    /** 活跃子 Job 计数器（用于监控） */
    private val activeChildrenCount = AtomicInteger(0)

    /** 是否已关闭 */
    @Volatile
    private var closed = false

    /**
     * 取消所有由本 Provider 管理的协程。
     *
     * 由于 scope 和 ioScope 共享同一个 supervisorJob，
     * 调用此方法会同时取消两个 Scope 下所有子协程。
     */
    fun cancel() {
        if (!closed) {
            Log.i(TAG, "Cancelling all application-scoped coroutines")
            supervisorJob.cancel()
        }
    }

    /**
     * 注册一个活跃子协程（用于监控计数）
     */
    fun registerActiveChild() {
        val count = activeChildrenCount.incrementAndGet()
        if (count > ACTIVE_COROUTINE_WARN_THRESHOLD && count % 50 == 0) {
            Log.w(TAG, "High active coroutine count detected: $count (threshold: $ACTIVE_COROUTINE_WARN_THRESHOLD)")
        }
    }

    /**
     * 注销一个已完成的子协程
     */
    fun unregisterActiveChild() {
        activeChildrenCount.decrementAndGet()
    }

    /**
     * 获取当前活跃子协程数量（近似值）
     */
    fun getActiveChildrenCount(): Int = activeChildrenCount.get()

    /**
     * 关闭 Provider 并取消所有协程。
     *
     * 此方法实现了 [Closeable] 接口，应在应用终止时调用。
     * 可安全多次调用（幂等）。
     */
    override fun close() {
        if (closed) return
        synchronized(this) {
            if (closed) return
            closed = true
        }
        Log.i(TAG, "ApplicationScopeProvider closing, active children approx: ${activeChildrenCount.get()}")
        cancel()
        Log.i(TAG, "ApplicationScopeProvider closed successfully")
    }

    /**
     * 检查是否已关闭
     */
    fun isClosed(): Boolean = closed
}
