package com.xianxia.sect.core.util

import android.content.Context
import android.util.Log
import com.xianxia.sect.XianxiaApplication
import com.xianxia.sect.core.performance.MetricCategory
import com.xianxia.sect.core.performance.MetricDefinition
import com.xianxia.sect.core.performance.MetricsListener
import com.xianxia.sect.core.performance.MetricStats
import com.xianxia.sect.core.performance.Trace
import com.xianxia.sect.core.performance.UnifiedPerformanceMonitor
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** 具名 MemoryPressureListener 实现（避免匿名内部类触发 KSP getSimpleName NPE） */
private class GameMemoryPressureListener : XianxiaApplication.MemoryPressureListener {
    override fun onMemoryPressure(level: Int) { /* handled by manager */ }
    override fun onLowMemory() { /* handled by manager */ }
}

@Suppress("DEPRECATION") // TODO: Migrate to UnifiedPerformanceMonitor (P1/P3 task)
@Singleton
class GameMonitorManager @Inject constructor(
    private val memoryMonitor: MemoryMonitor,
    private val performanceMonitor: PerformanceMonitor,
    private val gcOptimizer: GCOptimizer,
    private val unifiedPerformanceMonitor: UnifiedPerformanceMonitor,
    private val applicationScopeProvider: ApplicationScopeProvider
) {
    
    companion object {
        private const val TAG = "GameMonitorManager"
        private const val STATUS_REPORT_INTERVAL_MS = 300_000L
    }
    
    private var reportJob: Job? = null
    private val managerScope get() = applicationScopeProvider.scope
    private var isInitialized = false
    private var application: XianxiaApplication? = null
    
    private val memoryPressureListener = GameMemoryPressureListener()
    
    fun initialize(context: Context) {
        if (isInitialized) {
            Log.w(TAG, "GameMonitorManager already initialized")
            return
        }
        
        memoryMonitor.initialize(context)
        performanceMonitor.initialize()
        
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
        
        Log.i(TAG, "Starting all monitoring systems")
        
        memoryMonitor.startMonitoring()
        performanceMonitor.startMonitoring()
        gcOptimizer.startOptimization()
        unifiedPerformanceMonitor.startReporting()
        
        startPeriodicReport()
    }
    
    fun stopMonitoring() {
        Log.i(TAG, "Stopping all monitoring systems")
        
        memoryMonitor.stopMonitoring()
        performanceMonitor.stopMonitoring()
        gcOptimizer.stopOptimization()
        unifiedPerformanceMonitor.stopReporting()
        
        reportJob?.cancel()
        reportJob = null
    }
    
    private fun startPeriodicReport() {
        reportJob = managerScope.launch {
            while (isActive) {
                delay(STATUS_REPORT_INTERVAL_MS)
                logFullStatus()
            }
        }
    }
    
    fun logFullStatus() {
        Log.i(TAG, "=== Game Monitor Status Report ===")
        memoryMonitor.logMemoryStatus(TAG)
        performanceMonitor.logPerformanceStatus(TAG)
        gcOptimizer.logGCStatus(TAG)
        unifiedPerformanceMonitor.logPerformanceSummary()
        Log.i(TAG, "==================================")
    }
    
    fun getMemoryMonitor(): MemoryMonitor = memoryMonitor
    
    @Suppress("DEPRECATION") // TODO: Migrate to UnifiedPerformanceMonitor (P1/P3 task)
    fun getPerformanceMonitor(): PerformanceMonitor = performanceMonitor
    
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
    
    @Suppress("DEPRECATION") // TODO: Migrate to UnifiedPerformanceMonitor (P1/P3 task)
    fun isPerformanceHealthy(): Boolean {
        val memoryInfo = memoryMonitor.getCurrentMemoryInfo()
        val isPerformanceOk = performanceMonitor.isPerformanceAcceptable()
        
        return isPerformanceOk && (memoryInfo == null || !memoryInfo.isCritical)
    }
    
    @Suppress("DEPRECATION") // TODO: Migrate to UnifiedPerformanceMonitor (P1/P3 task)
    fun getOptimizationRecommendation(): OptimizationRecommendation {
        val memoryInfo = memoryMonitor.getCurrentMemoryInfo()
        val perfLevel = performanceMonitor.getRecommendedOptimizationLevel()
        val gcType = gcOptimizer.getRecommendedGCType()
        
        return OptimizationRecommendation(
            performanceLevel = perfLevel,
            recommendedGCType = gcType,
            memoryUsedPercent = memoryInfo?.usedPercent ?: 0.0,
            isLowMemory = memoryInfo?.isLowMemory ?: false,
            shouldReduceQuality = perfLevel != PerformanceMonitor.OptimizationLevel.NORMAL
        )
    }
    
    @Suppress("DEPRECATION") // TODO: Migrate to UnifiedPerformanceMonitor (P1/P3 task)
    data class OptimizationRecommendation(
        val performanceLevel: PerformanceMonitor.OptimizationLevel,
        val recommendedGCType: GCOptimizer.GCType,
        val memoryUsedPercent: Double,
        val isLowMemory: Boolean,
        val shouldReduceQuality: Boolean
    )
    
    fun cleanup() {
        application?.unregisterMemoryPressureListener(memoryPressureListener)
        stopMonitoring()
        memoryMonitor.cleanup()
        performanceMonitor.cleanup()
        gcOptimizer.cleanup()
        unifiedPerformanceMonitor.cleanup()
        isInitialized = false
    }
}
