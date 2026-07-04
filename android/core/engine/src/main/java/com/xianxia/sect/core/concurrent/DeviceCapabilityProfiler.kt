package com.xianxia.sect.core.concurrent

import android.os.Build
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设备能力分析器 — 根据 CPU 核数、内存、API 等级推荐并行度。
 *
 * 同时管理三个独立线程池：
 * - [parallelDispatcher] — 高优先级、大核、tick 内并行计算
 * - [backgroundDispatcher] — 低优先级、小核、批量结算/IO
 * - watchdog 仍使用 GameEngineCore 自有的独立线程
 */
@Singleton
class DeviceCapabilityProfiler @Inject constructor() {
    companion object {
        private const val PARALLEL_THREAD_PREFIX = "ParallelWorker-"
        private const val BACKGROUND_THREAD_PREFIX = "BgWorker-"
    }

    // ── 并行计算线程池（高优先级，大核首选） ──
    private val parallelThreadFactory = ThreadFactory { runnable ->
        Thread(runnable, PARALLEL_THREAD_PREFIX + parallelThreadCounter.incrementAndGet()).apply {
            priority = Thread.MAX_PRIORITY - 1  // 仅低于游戏主线程
            isDaemon = false
        }
    }
    private val parallelThreadCounter = java.util.concurrent.atomic.AtomicInteger(0)

    /** tick 内并行计算调度器 — 线程数 = [recommendedWorkerCount]，高优先级 */
    @Volatile
    var parallelDispatcher: CoroutineDispatcher = createParallelDispatcher()
        private set

    // ── 后台任务线程池（低优先级，小核首选） ──
    private val backgroundThreadFactory = ThreadFactory { runnable ->
        Thread(runnable, BACKGROUND_THREAD_PREFIX + backgroundThreadCounter.incrementAndGet()).apply {
            priority = Thread.MIN_PRIORITY + 1
            isDaemon = false
        }
    }
    private val backgroundThreadCounter = java.util.concurrent.atomic.AtomicInteger(0)

    /** 后台任务调度器 — 线程数 = 2-3，低优先级 */
    @Volatile
    var backgroundDispatcher: CoroutineDispatcher = createBackgroundDispatcher()
        private set

    private fun createParallelDispatcher(): CoroutineDispatcher {
        val count = recommendedWorkerCount.coerceIn(1, 8)
        return Executors.newFixedThreadPool(count, parallelThreadFactory).asCoroutineDispatcher()
    }

    private fun createBackgroundDispatcher(): CoroutineDispatcher {
        val count = 2.coerceAtMost(recommendedWorkerCount)
        return Executors.newFixedThreadPool(count, backgroundThreadFactory).asCoroutineDispatcher()
    }

    /** 紧急重建 parallelDispatcher（替换被 OEM 挂起的线程） */
    fun recreateParallelDispatcher() {
        parallelDispatcher = createParallelDispatcher()
    }

    /** 紧急重建 backgroundDispatcher */
    fun recreateBackgroundDispatcher() {
        backgroundDispatcher = createBackgroundDispatcher()
    }

    /** CPU 可用核数 */
    val totalCores: Int =
        try {
            Runtime.getRuntime().availableProcessors()
        } catch (_: Exception) {
            4 // 默认保守值
        }

    /** 总 RAM（GB） */
    val totalRamGB: Int by lazy {
        try {
            val memInfo = File("/proc/meminfo").readLines()
            val totalLine = memInfo.firstOrNull { it.startsWith("MemTotal:") } ?: return@lazy 4
            val kb = totalLine.replace(Regex("[^0-9]"), "").toLongOrNull() ?: return@lazy 4
            (kb / 1_000_000).toInt().coerceAtLeast(2)
        } catch (_: Exception) {
            4
        }
    }

    /** 是否为低端设备（≤4 核 或 ≤3GB RAM） */
    val isLowEnd: Boolean get() = totalCores <= 4 || totalRamGB <= 3

    /** 是否为高端设备（≥8 核 且 ≥6GB RAM） */
    val isHighEnd: Boolean get() = totalCores >= 8 && totalRamGB >= 6

    /** 推荐的并行工作线程数（用于 ParallelDispatcher） */
    val recommendedWorkerCount: Int get() = when {
        isLowEnd -> 1       // 低端机退化为单线程
        isHighEnd -> 4      // 高端机用 4 工作线程
        totalCores >= 6 && totalRamGB >= 4 -> 2  // 中端机用 2
        else -> 1
    }

    /** 运行时强制串行执行（由 [ThermalController] 在发热时设为 true） */
    @Volatile
    var forceSerialByThermal: Boolean = false

    /** 是否启用 tick 内系统并行（仅在 workerCount > 1 且未被热控禁用时开启） */
    val enableParallelTick: Boolean get() = recommendedWorkerCount > 1 && !forceSerialByThermal

    /** 是否启用后台批量 Job（低端机关闭以减少内存压力） */
    val enableBackgroundJobs: Boolean get() = totalRamGB >= 4

    /** 批量结算是否完全退化为单线程 */
    val batchDisabled: Boolean get() = isLowEnd

    /** 并行 tick 中 BatchSize（每批次处理弟子数） */
    val parallelBatchSize: Int get() = when {
        isHighEnd -> 500
        totalCores >= 6 -> 200
        else -> 100
    }

    /** 批处理 Job 队列大小 */
    val backgroundJobQueueCapacity: Int get() = if (isHighEnd) 32 else 8

    /** 设备摘要（用于日志） */
    val summary: String get() =
        "DeviceCapability[cores=$totalCores, ram=${totalRamGB}GB, " +
        "lowEnd=$isLowEnd, highEnd=$isHighEnd, " +
        "workers=$recommendedWorkerCount, " +
        "parallelTick=$enableParallelTick, bgJobs=$enableBackgroundJobs]"
}
