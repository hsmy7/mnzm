package com.xianxia.sect.core.performance

import android.util.Log
import com.xianxia.sect.core.util.GCOptimizer
import com.xianxia.sect.core.util.MemoryMonitor
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UnifiedPerformanceMonitor @Inject constructor(
    private val memoryMonitor: MemoryMonitor,
    private val gcOptimizer: GCOptimizer,
    private val applicationScopeProvider: ApplicationScopeProvider
) {
    companion object {
        private const val TAG = "UnifiedPerformanceMonitor"
        private const val DEFAULT_REPORT_INTERVAL_MS = 60_000L
        private const val MAX_COLLECTORS = 100
    }
    
    private val scope get() = applicationScopeProvider.scope
    private val metrics = ConcurrentHashMap<String, MetricCollector>()
    private val metricDefinitions = ConcurrentHashMap<String, MetricDefinition>()
    private val listeners = CopyOnWriteArrayList<MetricsListener>()
    private val activeTraces = ConcurrentHashMap<String, Trace>()
    
    private var reportJob: Job? = null
    private var isRunning = false
    
    fun registerMetric(definition: MetricDefinition) {
        if (metrics.size >= MAX_COLLECTORS) {
            Log.w(TAG, "Maximum metric collectors reached, cannot register: ${definition.name}")
            return
        }
        
        metricDefinitions[definition.name] = definition
        metrics.getOrPut(definition.name) { MetricCollector(definition.name) }
        Log.d(TAG, "Registered metric: ${definition.name} (${definition.category})")
    }
    
    fun startTrace(name: String, tags: Map<String, String> = emptyMap()): Trace {
        val trace = Trace(
            name = name,
            startTime = System.nanoTime(),
            tags = tags
        )
        activeTraces[name] = trace
        return trace
    }
    
    fun endTrace(trace: Trace): Trace {
        val completed = trace.complete()
        activeTraces.remove(trace.name)
        
        val durationNs = completed.duration
        recordMetric(trace.name, durationNs)
        
        notifyTraceCompleted(completed)
        
        return completed
    }
    
    fun endTrace(name: String): Trace? {
        val trace = activeTraces[name] ?: return null
        return endTrace(trace)
    }
    
    inline fun <T> trace(name: String, tags: Map<String, String> = emptyMap(), block: () -> T): T {
        val trace = startTrace(name, tags)
        return try {
            block()
        } finally {
            endTrace(trace)
        }
    }
    
    fun recordMetric(name: String, value: Long) {
        val collector = metrics.getOrPut(name) { MetricCollector(name) }
        collector.record(value)
        
        val stats = collector.getStats()
        notifyMetricRecorded(name, value, stats)
        
        checkThresholds(name, value)
    }
    
    fun recordMetric(name: String, value: Double) {
        recordMetric(name, value.toLong())
    }
    
    fun recordMetric(name: String, value: Int) {
        recordMetric(name, value.toLong())
    }
    
    fun getMetrics(): Map<String, MetricStats> {
        return metrics.mapValues { it.value.getStats() }
    }
    
    fun getMetric(name: String): MetricStats? {
        return metrics[name]?.getStats()
    }
    
    fun getMetricCollector(name: String): MetricCollector? {
        return metrics[name]
    }
    
    fun getMetricDefinition(name: String): MetricDefinition? {
        return metricDefinitions[name]
    }
    
    fun getAllMetricDefinitions(): Map<String, MetricDefinition> {
        return metricDefinitions.toMap()
    }
    
    fun resetMetric(name: String) {
        metrics[name]?.reset()
    }
    
    fun resetAllMetrics() {
        metrics.values.forEach { it.reset() }
    }
    
    fun addListener(listener: MetricsListener) {
        listeners.add(listener)
    }
    
    fun removeListener(listener: MetricsListener) {
        listeners.remove(listener)
    }
    
    fun startReporting(intervalMs: Long = DEFAULT_REPORT_INTERVAL_MS) {
        if (isRunning) {
            Log.w(TAG, "Performance reporting already running")
            return
        }
        
        isRunning = true
        reportJob = scope.launch {
            Log.i(TAG, "Starting performance reporting with interval ${intervalMs}ms")
            while (isActive) {
                delay(intervalMs)
                generateReport()
            }
        }
    }
    
    fun stopReporting() {
        isRunning = false
        reportJob?.cancel()
        reportJob = null
        Log.i(TAG, "Performance reporting stopped")
    }
    
    fun generateReport(): PerformanceReport {
        val allMetrics = getMetrics()
        val memoryInfo = memoryMonitor.getCurrentMemoryInfo()
        val gcStats = gcOptimizer.getGCStats()
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
            recommendations = recommendations
        )
    }
    
    private fun generateRecommendations(
        metrics: Map<String, MetricStats>,
        memoryInfo: MemoryMonitor.MemoryInfo?,
        gcStats: GCOptimizer.GCStats
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
                Log.e(TAG, "Error notifying listener", e)
            }
        }
    }
    
    private fun notifyThresholdExceeded(name: String, value: Long, threshold: Long, isCritical: Boolean) {
        listeners.forEach { listener ->
            try {
                listener.onThresholdExceeded(name, value, threshold, isCritical)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying listener", e)
            }
        }
    }
    
    private fun notifyTraceCompleted(trace: Trace) {
        listeners.forEach { listener ->
            try {
                listener.onTraceCompleted(trace)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying listener", e)
            }
        }
    }
    
    fun logPerformanceSummary() {
        val report = generateReport()
        
        Log.i(TAG, """
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
    
    fun cleanup() {
        stopReporting()
        listeners.clear()
        metrics.clear()
        metricDefinitions.clear()
        activeTraces.clear()
    }
}

data class PerformanceReport(
    val timestamp: Long,
    val metrics: Map<String, MetricStats>,
    val memoryInfo: MemoryInfoReport?,
    val gcStats: GCStatsReport,
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
