package com.xianxia.sect.core.util

import android.content.Context
import android.util.Log
import com.xianxia.sect.XianxiaApplication
import com.xianxia.sect.core.performance.MetricCategory
import com.xianxia.sect.core.performance.MetricDefinition
import com.xianxia.sect.core.performance.MetricsListener
import com.xianxia.sect.core.performance.MetricStats
import com.xianxia.sect.core.performance.OptimizationLevel
import com.xianxia.sect.core.performance.Trace
import com.xianxia.sect.core.performance.UnifiedPerformanceMonitor
import com.xianxia.sect.di.ApplicationScopeProvider
import javax.inject.Inject
import javax.inject.Singleton

private class GameMemoryPressureListener : XianxiaApplication.MemoryPressureListener {
    override fun onMemoryPressure(level: Int) { /* handled by manager */ }
    override fun onLowMemory() { /* handled by manager */ }
}

@Singleton
class GameMonitorManager @Inject constructor(
    private val memoryMonitor: MemoryMonitor,
    private val gcOptimizer: GCOptimizer,
    private val unifiedPerformanceMonitor: UnifiedPerformanceMonitor,
    private val applicationScopeProvider: ApplicationScopeProvider,
    private val taskScheduler: BackgroundTaskScheduler
) {

    companion object {
        private const val TAG = "GameMonitorManager"
    }

    private var isInitialized = false
    private var application: XianxiaApplication? = null

    private val memoryPressureListener = GameMemoryPressureListener()

    fun initialize(context: Context) {
        if (isInitialized) {
            Log.w(TAG, "GameMonitorManager already initialized")
            return
        }

        memoryMonitor.initialize(context)
        unifiedPerformanceMonitor.initialize()

        registerDefaultMetrics()

        if (context.applicationContext is XianxiaApplication) {
            application = context.applicationContext as XianxiaApplication
            application?.registerMemoryPressureListener(memoryPressureListener)
        }

        isInitialized = true
        Log.i(TAG, "GameMonitorManager initialized successfully")
    }

    private fun registerDefaultMetrics() {
        unifiedPerformanceMonitor.registerMetric(
            MetricDefinition(
                name = "game_tick_duration",
                category = MetricCategory.GAME_LOOP,
                unit = "ns",
                description = "Game tick execution duration",
                warningThreshold = 16_000_000L,
                criticalThreshold = 33_000_000L
            )
        )

        unifiedPerformanceMonitor.registerMetric(
            MetricDefinition(
                name = "save_operation_duration",
                category = MetricCategory.STORAGE,
                unit = "ns",
                description = "Save operation duration",
                warningThreshold = 1_000_000_000L,
                criticalThreshold = 3_000_000_000L
            )
        )

        unifiedPerformanceMonitor.registerMetric(
            MetricDefinition(
                name = "load_operation_duration",
                category = MetricCategory.STORAGE,
                unit = "ns",
                description = "Load operation duration",
                warningThreshold = 1_500_000_000L,
                criticalThreshold = 5_000_000_000L
            )
        )

        unifiedPerformanceMonitor.registerMetric(
            MetricDefinition(
                name = "ui_frame_duration",
                category = MetricCategory.UI,
                unit = "ns",
                description = "UI frame render duration",
                warningThreshold = 16_000_000L,
                criticalThreshold = 33_000_000L
            )
        )

        unifiedPerformanceMonitor.registerMetric(
            MetricDefinition(
                name = "db_query_duration",
                category = MetricCategory.DATABASE,
                unit = "ns",
                description = "Database query duration",
                warningThreshold = 100_000_000L,
                criticalThreshold = 500_000_000L
            )
        )

        unifiedPerformanceMonitor.registerMetric(
            MetricDefinition(
                name = "memory_usage",
                category = MetricCategory.MEMORY,
                unit = "bytes",
                description = "Current memory usage"
            )
        )

        Log.d(TAG, "Registered ${unifiedPerformanceMonitor.getAllMetricDefinitions().size} default metrics")
    }

    fun startMonitoring() {
        if (!isInitialized) {
            Log.e(TAG, "GameMonitorManager not initialized")
            return
        }

        Log.i(TAG, "Starting all monitoring systems via BackgroundTaskScheduler")

        // 1s: 性能指标采样 + 快照
        taskScheduler.register("PerfMetrics", 1) { unifiedPerformanceMonitor.updateGamePerformanceMetrics() }
        taskScheduler.register("PerfSnapshot", 1) { unifiedPerformanceMonitor.capturePerformanceSnapshot() }
        // 60s: GC检查 + 性能报告
        taskScheduler.register("GCOptimizer", 60) { gcOptimizer.checkAndPerformGC() }
        taskScheduler.register("PerfReport", 60) { unifiedPerformanceMonitor.generateReport() }
        // 30s: 内存采样
        taskScheduler.register("MemoryMonitor", 30) { memoryMonitor.captureMemorySnapshot() }
        // 300s: 完整状态日志
        taskScheduler.register("StatusReport", 300) { logFullStatus() }

        taskScheduler.start()
    }

    fun stopMonitoring() {
        Log.i(TAG, "Stopping all monitoring systems")
        taskScheduler.stop()
    }

    fun logFullStatus() {
        Log.i(TAG, "=== Game Monitor Status Report ===")
        memoryMonitor.logMemoryStatus(TAG)
        unifiedPerformanceMonitor.logPerformanceStatus(TAG)
        gcOptimizer.logGCStatus(TAG)
        unifiedPerformanceMonitor.logPerformanceSummary()
        Log.i(TAG, "==================================")
    }

    fun getMemoryMonitor(): MemoryMonitor = memoryMonitor

    @Deprecated("Use getUnifiedPerformanceMonitor() instead", ReplaceWith("getUnifiedPerformanceMonitor()"))
    fun getPerformanceMonitor(): Nothing = throw UnsupportedOperationException(
        "PerformanceMonitor is deprecated. Use getUnifiedPerformanceMonitor() instead."
    )

    fun getGCOptimizer(): GCOptimizer = gcOptimizer

    fun getUnifiedPerformanceMonitor(): UnifiedPerformanceMonitor = unifiedPerformanceMonitor

    fun startTrace(name: String, tags: Map<String, String> = emptyMap()): Trace {
        return unifiedPerformanceMonitor.startTrace(name, tags)
    }

    fun endTrace(trace: Trace): Trace {
        return unifiedPerformanceMonitor.endTrace(trace)
    }

    fun <T> trace(name: String, tags: Map<String, String> = emptyMap(), block: () -> T): T {
        return unifiedPerformanceMonitor.trace(name, tags, block)
    }

    fun recordMetric(name: String, value: Long) {
        unifiedPerformanceMonitor.recordMetric(name, value)
    }

    fun getMetricStats(name: String): com.xianxia.sect.core.performance.MetricStats? {
        return unifiedPerformanceMonitor.getMetric(name)
    }

    fun getAllMetrics(): Map<String, com.xianxia.sect.core.performance.MetricStats> {
        return unifiedPerformanceMonitor.getMetrics()
    }

    fun addMetricsListener(listener: MetricsListener) {
        unifiedPerformanceMonitor.addListener(listener)
    }

    fun removeMetricsListener(listener: MetricsListener) {
        unifiedPerformanceMonitor.removeListener(listener)
    }

    fun isPerformanceHealthy(): Boolean {
        val memoryInfo = memoryMonitor.getCurrentMemoryInfo()
        val isPerformanceOk = unifiedPerformanceMonitor.isPerformanceAcceptable()

        return isPerformanceOk && (memoryInfo == null || !memoryInfo.isCritical)
    }

    fun getOptimizationRecommendation(): OptimizationRecommendation {
        val memoryInfo = memoryMonitor.getCurrentMemoryInfo()
        val perfLevel = unifiedPerformanceMonitor.getRecommendedOptimizationLevel()
        val gcType = gcOptimizer.getRecommendedGCType()

        return OptimizationRecommendation(
            performanceLevel = perfLevel,
            recommendedGCType = gcType,
            memoryUsedPercent = memoryInfo?.usedPercent ?: 0.0,
            isLowMemory = memoryInfo?.isLowMemory ?: false,
            shouldReduceQuality = perfLevel != OptimizationLevel.NORMAL
        )
    }

    data class OptimizationRecommendation(
        val performanceLevel: OptimizationLevel,
        val recommendedGCType: GCOptimizer.GCType,
        val memoryUsedPercent: Double,
        val isLowMemory: Boolean,
        val shouldReduceQuality: Boolean
    )

    fun cleanup() {
        application?.unregisterMemoryPressureListener(memoryPressureListener)
        stopMonitoring()
        memoryMonitor.cleanup()
        gcOptimizer.cleanup()
        unifiedPerformanceMonitor.cleanup()
        isInitialized = false
    }
}
