package com.xianxia.sect.core.performance

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.perf.FrameMetricsMonitor
import com.xianxia.sect.core.util.GCOptimizerProvider
import com.xianxia.sect.core.util.MemoryMonitorProvider
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UnifiedPerformanceMonitor @Inject constructor(
    private val memoryMonitor: MemoryMonitorProvider,
    private val gcOptimizer: GCOptimizerProvider,
    private val frameMetricsMonitor: FrameMetricsMonitor
) : PerformanceMetricsRegistry() {
    companion object {
        internal const val MAX_SAMPLES = 100
        private const val FPS_WARNING_THRESHOLD = 30f
    }

    internal val tickTimes = ConcurrentLinkedQueue<Float>()
    internal val frameTimesGpm = ConcurrentLinkedQueue<Float>()
    internal val tickCounter = AtomicLong(0)
    private var lastFpsCalculation = System.currentTimeMillis()
    @Volatile
    internal var frameCountGpm = 0
    @Volatile
    internal var currentSaveQueueSize = 0
    @Volatile
    internal var currentEntityCount = 0

    // ── 帧质量追踪 ──
    enum class FrameQuality { SMOOTH, ACCEPTABLE, JANKY, FREEZE }
    private var consecutiveJankyFrames = 0
    @Suppress("VariableNaming")
    @Volatile internal var _loadReductionRequested = false
    @Volatile private var _frameQuality: FrameQuality = FrameQuality.SMOOTH
    val frameQuality: FrameQuality get() = _frameQuality
    val loadReductionRequested: Boolean get() = _loadReductionRequested

    fun onFrameCompleted(frameDurationMs: Float) {
        val quality = when {
            frameDurationMs < 16.67f -> FrameQuality.SMOOTH
            frameDurationMs < 33.33f -> FrameQuality.ACCEPTABLE
            frameDurationMs < 50f -> FrameQuality.JANKY
            else -> FrameQuality.FREEZE
        }
        _frameQuality = quality
        if (quality >= FrameQuality.JANKY) {
            consecutiveJankyFrames++
            if (consecutiveJankyFrames >= 3 && !_loadReductionRequested) {
                _loadReductionRequested = true
                DomainLog.w(TAG, "3 consecutive janky frames — requesting load reduction")
            }
        } else {
            consecutiveJankyFrames = 0
            _loadReductionRequested = false
        }
    }

    // ── 生命周期代理（调度由 BackgroundTaskScheduler 承担）──

    fun stopReporting() {
        DomainLog.d(TAG, "Performance reporting stop (delegated to scheduler)")
    }

    /** 启动/重置时钟。游戏循环开始时调用。 */
    fun start() {
        DomainLog.d(TAG, "Performance monitor start (delegated to scheduler)")
    }

    /**
     * 停止热状态监控。由 GameEngineCore.shutdown() 调用。
     */
    fun stop() {
        DomainLog.d(TAG, "Performance monitor stop (delegated to scheduler)")
    }

    // ── 核心记录方法 ──

    @Volatile
    private var currentFps: Float = 0f

    fun updateGamePerformanceMetrics() {
        val now = System.currentTimeMillis()
        val elapsed = (now - lastFpsCalculation) / 1000f
        val fps = if (elapsed > 0) frameCountGpm / elapsed else 0f
        lastFpsCalculation = now
        frameCountGpm = 0
        currentFps = fps

        // 通知监听器（MetricsListener）
        listeners.forEach { it.onMetricRecorded("performance_metrics", 0, MetricStats()) }
    }

    // ── 工具方法 ──

    fun getMemoryReport(): String {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory() / (1024 * 1024)
        val totalMemory = runtime.totalMemory() / (1024 * 1024)
        val freeMemory = runtime.freeMemory() / (1024 * 1024)
        val usedMemory = totalMemory - freeMemory
        val memoryPercent = if (maxMemory > 0) (usedMemory.toFloat() / maxMemory) * 100 else 0f
        val avgTickTime = if (tickTimes.isNotEmpty()) tickTimes.average().toFloat() else 0f
        val maxTickTime = if (tickTimes.isNotEmpty()) tickTimes.maxOrNull() ?: 0f else 0f

        return """
            Memory Report:
            - Used: ${usedMemory}MB
            - Free: ${freeMemory}MB
            - Max: ${maxMemory}MB
            - Usage: ${memoryPercent.toInt()}%
            - FPS: ${"%.1f".format(currentFps)}
            - Avg Tick: ${"%.2f".format(avgTickTime)}ms
            - Max Tick: ${"%.2f".format(maxTickTime)}ms
        """.trimIndent()
    }

    /**
     * 初始化建筑配置（每次 boot 经 `ResourcePreloader.preloadGameResources` 调用）。
     * 重复调用直接跳过，避免 `config/buildings.json` 重复 I/O（首次加载失败已回退默认配置，
     * 无需重试语义）。
     */
    fun initialize() {
        DomainLog.i(TAG, "UnifiedPerformanceMonitor initialized")
    }

    fun startMonitoring() {
        DomainLog.d(TAG, "Performance monitoring start (delegated to scheduler)")
    }

    fun stopMonitoring() {
        DomainLog.d(TAG, "Performance monitoring stop (delegated to scheduler)")
    }

    // ── 快照与报告 ──

    fun capturePerformanceSnapshot(): PerformanceSnapshot {
        val fps = currentFps
        return PerformanceSnapshot(
            timestamp = System.currentTimeMillis(),
            fps = fps,
            isPerformanceWarning = fps < FPS_WARNING_THRESHOLD && fps > 0f,
            isPerformanceCritical = fps < 15f && fps > 0f
        )
    }

    fun logPerformanceStatus(tag: String = TAG) {
        DomainLog.i(tag, """
            |=== Performance Status ===
            |FPS: ${"%.1f".format(currentFps)}
            |Entity Count: $currentEntityCount
            |Save Queue Size: $currentSaveQueueSize
            |==========================
        """.trimMargin())
    }

    fun generateReport(): PerformanceReport {
        val allMetrics = getMetrics()
        val memoryInfo = memoryMonitor.getCurrentMemoryInfo()
        val gcStats = gcOptimizer.getGCStats()
        val frameMetricsStats = frameMetricsMonitor.getStats()
        val recommendations = generateRecommendations(allMetrics, memoryInfo, gcStats)

        return PerformanceReport(
            timestamp = System.currentTimeMillis(),
            metrics = allMetrics,
            memoryInfo = memoryInfo?.let {
                MemoryInfoReport(
                    usedMemory = it.usedMemory,
                    totalMemory = it.totalMemory,
                    usedPercent = it.usedPercent,
                    isLowMemory = it.isLowMemory,
                    isWarning = it.isWarning,
                    isCritical = it.isCritical
                )
            },
            gcStats = GCStatsReport(
                totalGCCount = gcStats.totalGCCount,
                totalGCTimeMs = gcStats.totalGCTimeMs,
                averageGCTimeMs = gcStats.averageGCTimeMs,
                timeSinceLastGC = gcStats.timeSinceLastGC
            ),
            frameMetricsStats = FrameMetricsStatsReport(
                totalFrames = frameMetricsStats.totalFrames,
                jankFrames = frameMetricsStats.jankFrames,
                severeJankFrames = frameMetricsStats.severeJankFrames,
                averageFrameTimeMs = frameMetricsStats.averageFrameTimeMs,
                jankRate = frameMetricsStats.jankRate
            ),
            recommendations = recommendations
        )
    }

    private fun generateRecommendations(
        metrics: Map<String, MetricStats>,
        memoryInfo: MemoryMonitorProvider.MemoryInfo?,
        gcStats: GCOptimizerProvider.GCStats
    ): List<String> {
        val recommendations = mutableListOf<String>()

        memoryInfo?.let { mem ->
            when {
                mem.isCritical -> recommendations
                    .add("CRITICAL: Memory usage is at ${(mem.usedPercent * 100).toInt()}%. Immediate action required.")
                mem.isWarning -> recommendations.add("WARNING: Memory usage is high " +
                    "at ${(mem.usedPercent * 100).toInt()}%. Consider freeing resources.")
                else -> { }
            }
        }

        if (gcStats.averageGCTimeMs > 100) {
            recommendations
                .add("High average GC time (${String.format(Locale.ROOT, "%.1f", gcStats.averageGCTimeMs)}ms). " +
                    "Review object allocation patterns.")
        }

        metrics.forEach { (name, stats) ->
            val definition = metricDefinitions[name]
            definition?.criticalThreshold?.let { threshold ->
                if (stats.lastValue > threshold) {
                    recommendations.add("CRITICAL: $name exceeded critical threshold: ${stats.lastValue} > $threshold")
                }
            }
            definition?.warningThreshold?.let { threshold ->
                if (stats.lastValue > threshold && (definition.criticalThreshold == null || stats
                    .lastValue <= definition.criticalThreshold)) {
                    recommendations.add("WARNING: $name exceeded warning threshold: ${stats.lastValue} > $threshold")
                }
            }
        }

        return recommendations
    }

    fun logPerformanceSummary() {
        val report = generateReport()
        val issuesText = if (report.recommendations.isNotEmpty()) {
            "Issues:\n  - " + report.recommendations.joinToString("\n  - ")
        } else {
            "No issues detected"
        }

        DomainLog.i(TAG, """
            |=== Performance Summary ===
            |Timestamp: ${report.timestamp}
            |Metrics Count: ${report.metrics.size}
            |Memory: ${report.memoryInfo?.let { "${(it.usedPercent * 100).toInt()}% used" } ?: "N/A"}
            |GC Count: ${report.gcStats.totalGCCount}
            |Recommendations: ${report.recommendations.size}
            |$issuesText
            |===========================
        """.trimMargin())
    }

    fun isPerformanceAcceptable(): Boolean {
        return currentFps >= FPS_WARNING_THRESHOLD || currentFps <= 0f
    }

    fun getRecommendedOptimizationLevel(): OptimizationLevel {
        return when {
            currentFps < 15f -> OptimizationLevel.AGGRESSIVE
            currentFps < 25f -> OptimizationLevel.MODERATE
            currentFps < 30f -> OptimizationLevel.LIGHT
            else -> OptimizationLevel.NORMAL
        }
    }

    fun cleanup() {
        stopReporting()
        stopMonitoring()
        stop()
        listeners.clear()
        metricsCollectors.clear()
        metricDefinitions.clear()
        tickTimes.clear()
        frameTimesGpm.clear()
    }
}

data class PerformanceReport(
    val timestamp: Long,
    val metrics: Map<String, MetricStats>,
    val memoryInfo: MemoryInfoReport?,
    val gcStats: GCStatsReport,
    val frameMetricsStats: FrameMetricsStatsReport,
    val recommendations: List<String>
)

data class MemoryInfoReport(
    val usedMemory: Long,
    val totalMemory: Long,
    val usedPercent: Double,
    val isLowMemory: Boolean,
    val isWarning: Boolean,
    val isCritical: Boolean
)

data class GCStatsReport(
    val totalGCCount: Long,
    val totalGCTimeMs: Long,
    val averageGCTimeMs: Double,
    val timeSinceLastGC: Long
)

data class FrameMetricsStatsReport(
    /** 总帧数（Vulkan + Canvas） */
    val totalFrames: Long,
    val jankFrames: Long,
    val severeJankFrames: Long,
    val averageFrameTimeMs: Double,
    val jankRate: Double
)

data class PerformanceMetrics(
    val fps: Float = 0f,
    val frameTimeMs: Float = 0f,
    val memoryUsedMB: Long = 0,
    val memoryMaxMB: Long = 0,
    val memoryFreeMB: Long = 0,
    val memoryUsagePercent: Float = 0f,
    /** 循环 tick 计数（假运行时也递增，不能单独作为推进判据） */
    val tickCount: Long = 0,
    val averageTickTimeMs: Float = 0f,
    val maxTickTimeMs: Float = 0f,
    val saveQueueSize: Int = 0,
    val entityCount: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
) {
    val isHealthy: Boolean get() = fps >= 30f && memoryUsagePercent < 80f

    /** 设备摘要（用于日志） */
    val summary: String get() = """
        FPS: ${"%.1f".format(fps)} | Memory: ${memoryUsedMB}MB/${memoryMaxMB}MB (${memoryUsagePercent.toInt()}%)
        Tick: ${"%.2f".format(averageTickTimeMs)}ms avg, ${"%.2f".format(maxTickTimeMs)}ms max
    """.trimIndent()
}

data class PerformanceSnapshot(
    val timestamp: Long = System.currentTimeMillis(),
    val fps: Float = 0f,
    val isPerformanceWarning: Boolean = false,
    val isPerformanceCritical: Boolean = false
)

enum class OptimizationLevel {
    NORMAL,
    LIGHT,
    MODERATE,
    AGGRESSIVE
}
