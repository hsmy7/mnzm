package com.xianxia.sect.core.performance

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.perf.FrameMetricsMonitor
import com.xianxia.sect.core.util.GCOptimizerProvider
import com.xianxia.sect.core.util.MemoryMonitorProvider
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UnifiedPerformanceMonitor @Inject constructor(
    private val memoryMonitor: MemoryMonitorProvider,
    private val gcOptimizer: GCOptimizerProvider,
    private val frameMetricsMonitor: FrameMetricsMonitor
) {
    companion object {
        private const val TAG = "UnifiedPerformanceMonitor"
        private const val MAX_COLLECTORS = 100
        private const val MAX_SAMPLES = 100
        private const val FPS_WARNING_THRESHOLD = 30f
        private const val MEMORY_WARNING_THRESHOLD = 80f
        private const val TICK_TIME_WARNING_THRESHOLD = 50f
        private const val SAVE_QUEUE_WARNING_THRESHOLD = 5
        private const val ENTITY_COUNT_WARNING_THRESHOLD = 1000
        private const val MONITOR_INTERVAL_MS = 1000L
    }

    private val metricsCollectors = ConcurrentHashMap<String, MetricCollector>()
    private val metricDefinitions = ConcurrentHashMap<String, MetricDefinition>()
    private val listeners = CopyOnWriteArrayList<MetricsListener>()

    private val tickTimes = ConcurrentLinkedQueue<Float>()
    private val frameTimesGpm = ConcurrentLinkedQueue<Float>()
    private val tickCounter = AtomicLong(0)
    private var lastFpsCalculation = System.currentTimeMillis()
    @Volatile
    private var frameCountGpm = 0
    @Volatile
    private var currentSaveQueueSize = 0
    @Volatile
    private var currentEntityCount = 0

    // ── 帧质量追踪 ──
    enum class FrameQuality { SMOOTH, ACCEPTABLE, JANKY, FREEZE }
    private var consecutiveJankyFrames = 0
    @Volatile private var _loadReductionRequested = false
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

    fun clearLoadReductionRequest() { _loadReductionRequested = false }

    // ── Metric 注册与采集 ──

    fun registerMetric(definition: MetricDefinition) {
        if (metricsCollectors.size >= MAX_COLLECTORS) {
            DomainLog.w(TAG, "Maximum metric collectors reached, cannot register: ${definition.name}")
            return
        }
        metricDefinitions[definition.name] = definition
        metricsCollectors.getOrPut(definition.name) { MetricCollector(definition.name) }
        DomainLog.d(TAG, "Registered metric: ${definition.name} (${definition.category})")
    }

    fun recordMetric(name: String, value: Long) {
        val collector = metricsCollectors.getOrPut(name) { MetricCollector(name) }
        collector.record(value)
        val stats = collector.getStats()
        notifyMetricRecorded(name, value, stats)
        checkThresholds(name, value)
    }

    fun recordMetric(name: String, value: Double) { recordMetric(name, value.toLong()) }
    fun recordMetric(name: String, value: Int) { recordMetric(name, value.toLong()) }

    fun getMetrics(): Map<String, MetricStats> = metricsCollectors.mapValues { it.value.getStats() }
    fun getMetric(name: String): MetricStats? = metricsCollectors[name]?.getStats()
    fun getMetricCollector(name: String): MetricCollector? = metricsCollectors[name]
    fun getMetricDefinition(name: String): MetricDefinition? = metricDefinitions[name]
    fun getAllMetricDefinitions(): Map<String, MetricDefinition> = metricDefinitions.toMap()
    fun resetMetric(name: String) { metricsCollectors[name]?.reset() }

    fun addListener(listener: MetricsListener) { listeners.add(listener) }
    fun removeListener(listener: MetricsListener) { listeners.remove(listener) }

    // ── 生命周期代理（已迁移至 BackgroundTaskScheduler）──

    fun stopReporting() {
        DomainLog.d(TAG, "Performance reporting stop (delegated to scheduler)")
    }

    fun start() {
        DomainLog.d(TAG, "Performance monitor start (delegated to scheduler)")
    }

    fun stop() {
        DomainLog.d(TAG, "Performance monitor stop (delegated to scheduler)")
    }

    // ── 核心记录方法 ──

    fun recordTick(durationMs: Float) {
        tickTimes.offer(durationMs)
        if (tickTimes.size > MAX_SAMPLES) {
            tickTimes.poll()
        }
        tickCounter.incrementAndGet()
    }

    fun recordFrame(durationMs: Float) {
        frameTimesGpm.offer(durationMs)
        if (frameTimesGpm.size > MAX_SAMPLES) {
            frameTimesGpm.poll()
        }
        frameCountGpm++
    }

    fun recordSaveQueueSize(size: Int) {
        currentSaveQueueSize = size
    }

    fun recordEntityCount(count: Int) {
        currentEntityCount = count
    }

    // ── FPS 计算 ──

    @Volatile
    private var currentFps: Float = 0f

    fun updateGamePerformanceMetrics() {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory() / (1024 * 1024)
        val totalMemory = runtime.totalMemory() / (1024 * 1024)
        val freeMemory = runtime.freeMemory() / (1024 * 1024)
        val usedMemory = totalMemory - freeMemory
        val memoryPercent = if (maxMemory > 0) (usedMemory.toFloat() / maxMemory) * 100 else 0f

        val now = System.currentTimeMillis()
        val elapsed = (now - lastFpsCalculation) / 1000f
        val fps = if (elapsed > 0) frameCountGpm / elapsed else 0f
        lastFpsCalculation = now
        frameCountGpm = 0
        currentFps = fps

        val avgTickTime = if (tickTimes.isNotEmpty()) tickTimes.average().toFloat() else 0f
        val maxTickTime = if (tickTimes.isNotEmpty()) tickTimes.maxOrNull() ?: 0f else 0f
        val avgFrameTime = if (frameTimesGpm.isNotEmpty()) frameTimesGpm.average().toFloat() else 0f

        val newMetrics = PerformanceMetrics(
            fps = fps,
            frameTimeMs = avgFrameTime,
            memoryUsedMB = usedMemory.toLong(),
            memoryMaxMB = maxMemory.toLong(),
            memoryFreeMB = freeMemory.toLong(),
            memoryUsagePercent = memoryPercent,
            tickCount = tickCounter.get(),
            averageTickTimeMs = avgTickTime,
            maxTickTimeMs = maxTickTime,
            saveQueueSize = currentSaveQueueSize,
            entityCount = currentEntityCount
        )

        // 通知监听器（MetricsListener 而非已删除的 PerformanceListener）
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

    fun initialize() {
        DomainLog.i(TAG, "UnifiedPerformanceMonitor initialized")
    }

    fun startMonitoring() {
        DomainLog.d(TAG, "Performance monitoring start (delegated to scheduler)")
    }

    fun stopMonitoring() {
        DomainLog.d(TAG, "Performance monitoring stop (delegated to scheduler)")
    }

    // ── 快照与报告（简化版，不再依赖已删除的 FrameStats/OperationMetric）──

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
                mem.isCritical -> recommendations.add("CRITICAL: Memory usage is at ${(mem.usedPercent * 100).toInt()}%. Immediate action required.")
                mem.isWarning -> recommendations.add("WARNING: Memory usage is high at ${(mem.usedPercent * 100).toInt()}%. Consider freeing resources.")
                else -> { }
            }
        }

        if (gcStats.averageGCTimeMs > 100) {
            recommendations.add("High average GC time (${String.format(Locale.ROOT, "%.1f", gcStats.averageGCTimeMs)}ms). Review object allocation patterns.")
        }

        metrics.forEach { (name, stats) ->
            val definition = metricDefinitions[name]
            definition?.criticalThreshold?.let { threshold ->
                if (stats.lastValue > threshold) {
                    recommendations.add("CRITICAL: $name exceeded critical threshold: ${stats.lastValue} > $threshold")
                }
            }
            definition?.warningThreshold?.let { threshold ->
                if (stats.lastValue > threshold && (definition.criticalThreshold == null || stats.lastValue <= definition.criticalThreshold)) {
                    recommendations.add("WARNING: $name exceeded warning threshold: ${stats.lastValue} > $threshold")
                }
            }
        }

        return recommendations
    }

    private fun checkThresholds(name: String, value: Long) {
        val definition = metricDefinitions[name] ?: return

        definition.criticalThreshold?.let { threshold ->
            if (value > threshold) {
                notifyThresholdExceeded(name, value, threshold, isCritical = true)
            }
        }

        definition.warningThreshold?.let { threshold ->
            if (value > threshold) {
                notifyThresholdExceeded(name, value, threshold, isCritical = false)
            }
        }
    }

    private fun notifyMetricRecorded(name: String, value: Long, stats: MetricStats) {
        listeners.forEach { listener ->
            try {
                listener.onMetricRecorded(name, value, stats)
            } catch (e: Exception) {
                DomainLog.e(TAG, "Error notifying listener", e)
            }
        }
    }

    private fun notifyThresholdExceeded(name: String, value: Long, threshold: Long, isCritical: Boolean) {
        listeners.forEach { listener ->
            try {
                listener.onThresholdExceeded(name, value, threshold, isCritical)
            } catch (e: Exception) {
                DomainLog.e(TAG, "Error notifying listener", e)
            }
        }
    }

    fun logPerformanceSummary() {
        val report = generateReport()

        DomainLog.i(TAG, """
            |=== Performance Summary ===
            |Timestamp: ${report.timestamp}
            |Metrics Count: ${report.metrics.size}
            |Memory: ${report.memoryInfo?.let { "${(it.usedPercent * 100).toInt()}% used" } ?: "N/A"}
            |GC Count: ${report.gcStats.totalGCCount}
            |Recommendations: ${report.recommendations.size}
            |${if (report.recommendations.isNotEmpty()) "Issues:\n  - " + report.recommendations.joinToString("\n  - ") else "No issues detected"}
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
    val tickCount: Long = 0,
    val averageTickTimeMs: Float = 0f,
    val maxTickTimeMs: Float = 0f,
    val saveQueueSize: Int = 0,
    val entityCount: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
) {
    val isHealthy: Boolean get() = fps >= 30f && memoryUsagePercent < 80f

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
