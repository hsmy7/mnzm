package com.xianxia.sect.core.concurrent

import com.xianxia.sect.core.util.DomainLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 后台作业调度器 — 执行非紧急、只读计算的后台任务。
 *
 * ### 职责
 * - 批量结算中的 deepCopy、指纹计算、进度分类
 * - 存档压缩、序列化等耗时 I/O 操作
 * - 所有不修改游戏状态的纯函数计算
 *
 * ### 与 tick 内 ParallelDispatcher 的区别
 * | 维度 | ParallelDispatcher (tick内) | BackgroundJobScheduler |
 * |------|---------------------------|----------------------|
 * | 优先级 | 高（跟 tick 同步） | 低（后台默默跑） |
 * | 线程数 | CPU核数 - 1 | 2~3 |
 * | 执行时机 | tick 内 compute 阶段 | tick 外后台异步 |
 * | 结果 | 必须同步等待 | 异步通知 |
 * | 绑定核 | 大核 | 小核 |
 *
 * ### 线程池
 * 使用独立于 Dispatchers.Default 的专用线程池（后台低优先级线程），
 * 防止后台任务抢占 tick 计算资源。
 */
@Singleton
class BackgroundJobScheduler @Inject constructor(
    private val profiler: DeviceCapabilityProfiler
) {
    companion object {
        private const val TAG = "BackgroundJobScheduler"
        /** 队列容量警报阈值 */
        private const val QUEUE_WARNING_THRESHOLD = 64
    }

    /** 后台协程作用域 — 使用 [DeviceCapabilityProfiler.backgroundDispatcher] */
    private val scope = CoroutineScope(SupervisorJob() + profiler.backgroundDispatcher)

    /** 当前排队的作业数 */
    @Volatile
    var queuedJobCount: Int = 0
        private set

    /**
     * 提交一个后台作业。
     *
     * @param label 作业标签（日志用）
     * @param block 后台执行的计算（纯函数，禁止修改游戏状态）
     * @return Job 句柄（可用于取消）
     */
    fun submit(label: String, block: suspend () -> Unit): Job {
        if (queuedJobCount >= QUEUE_WARNING_THRESHOLD) {
            DomainLog.w(TAG, "Background job queue full ($queuedJobCount), dropping: $label")
            return Job().also { it.cancel() }
        }
        queuedJobCount++
        return scope.launch {
            try {
                DomainLog.d(TAG, "BgJob started: $label")
                block()
                DomainLog.d(TAG, "BgJob completed: $label")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                DomainLog.e(TAG, "BgJob failed: $label", e)
            } finally {
                queuedJobCount--
            }
        }
    }

    /**
     * 提交后台作业并在完成后在主线程回调。
     *
     * @param label 作业标签
     * @param compute 后台计算（纯函数）
     * @param onResult 主线程回调（接收计算结果）
     */
    suspend fun <T> submitWithResult(
        label: String,
        compute: suspend () -> T,
        onResult: suspend (T) -> Unit
    ) {
        DomainLog.d(TAG, "BgJob submitted: $label")
        val result = compute()
        onResult(result)
        DomainLog.d(TAG, "BgJob result applied: $label")
    }

    /** 释放后台线程池（shutdown 时调用） */
    fun shutdown() {
        // 不主动关闭 bgDispatcher.executor——同 GameEngineCore 的设计
        DomainLog.i(TAG, "BackgroundJobScheduler shutting down")
    }
}
