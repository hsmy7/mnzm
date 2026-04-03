package com.xianxia.sect.data.monitor

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import java.io.File

data class StorageMetric(
    val operation: String,
    val durationMs: Long,
    val success: Boolean,
    val dataSize: Long,
    val memoryBefore: Long,
    val memoryAfter: Long,
    val timestamp: Long = System.currentTimeMillis()
) {
    val memoryDelta: Long get() = memoryAfter - memoryBefore
}

data class OperationStats(
    val count: Long = 0,
    val totalDurationMs: Long = 0,
    val avgDurationMs: Double = 0.0,
    val minDurationMs: Long = Long.MAX_VALUE,
    val maxDurationMs: Long = 0,
    val p50DurationMs: Long = 0,
    val p95DurationMs: Long = 0,
    val p99DurationMs: Long = 0,
    val successCount: Long = 0,
    val failureCount: Long = 0,
    val totalDataSize: Long = 0,
    val avgDataSize: Long = 0
) {
    val successRate: Double
        get() = if (count > 0) successCount.toDouble() / count else 0.0
    
    val errorRate: Double
        get() = if (count > 0) failureCount.toDouble() / count else 0.0
    
    val throughput: Double
        get() = if (totalDurationMs > 0) count.toDouble() / (totalDurationMs / 1000.0) else 0.0
}

data class StorageReport(
    val operations: Map<String, OperationStats>,
    val totalOperations: Long,
    val totalSuccessRate: Double,
    val avgLatencyMs: Double,
    val totalDataTransferred: Long,
    val memoryUsage: Long,
    val diskUsage: Long,
    val recommendations: List<Recommendation>,
    val healthScore: Double,
    val timestamp: Long = System.currentTimeMillis()
)

enum class Severity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

data class Recommendation(
    val severity: Severity,
    val message: String,
    val action: String
)

data class StorageAlert(
    val operation: String,
    val type: AlertType,
    val message: String,
    val value: Any,
    val threshold: Any,
    val timestamp: Long = System.currentTimeMillis()
)

enum class AlertType {
    SLOW_OPERATION,
    HIGH_ERROR_RATE,
    MEMORY_SPIKE,
    DISK_SPACE_LOW,
    CACHE_HIT_RATE_LOW,
    OPERATION_TIMEOUT
}

data class AlertThresholds(
    val maxDurationMs: Long = 5000L,
    val maxErrorRate: Double = 0.05,
    val maxMemoryIncreasePercent: Double = 0.3,
    val minDiskSpaceMB: Long = 100L,
    val minCacheHitRate: Double = 0.7,
    val operationTimeoutMs: Long = 10000L
)

@Singleton
class StorageMonitor @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "StorageMonitor"
        const val MAX_SAMPLES = 10000
        private const val REPORT_INTERVAL_MS = 60_000L
    }
    
    private val metrics = ConcurrentHashMap<String, MetricCollector>()
    private val alertListeners = mutableListOf<AlertListener>()
    private val thresholds = ConcurrentHashMap<String, AlertThresholds>()
    
    private val _alerts = MutableStateFlow<List<StorageAlert>>(emptyList())
    val alerts: StateFlow<List<StorageAlert>> = _alerts.asStateFlow()
    
    private val _report = MutableStateFlow<StorageReport?>(null)
    val report: StateFlow<StorageReport?> = _report.asStateFlow()
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reportJob: Job? = null
    
    private val totalOperations = AtomicLong(0)
    private val totalSuccesses = AtomicLong(0)
    private val totalFailures = AtomicLong(0)
    
    init {
        setupDefaultThresholds()
        startReportGeneration()
    }
    
    private fun setupDefaultThresholds() {
        thresholds["save"] = AlertThresholds(maxDurationMs = 3000L)
        thresholds["load"] = AlertThresholds(maxDurationMs = 1000L)
        thresholds["cache_get"] = AlertThresholds(maxDurationMs = 100L)
        thresholds["cache_put"] = AlertThresholds(maxDurationMs = 100L)
        thresholds["encrypt"] = AlertThresholds(maxDurationMs = 500L)
        thresholds["decrypt"] = AlertThresholds(maxDurationMs = 500L)
    }
    
    private fun startReportGeneration() {
        reportJob = scope.launch {
            while (isActive) {
                delay(REPORT_INTERVAL_MS)
                generateReport()
            }
        }
    }
    
    fun recordOperation(metric: StorageMetric) {
        totalOperations.incrementAndGet()
        if (metric.success) {
            totalSuccesses.incrementAndGet()
        } else {
            totalFailures.incrementAndGet()
        }
        
        metrics.getOrPut(metric.operation) { MetricCollector() }
            .record(metric)
        
        checkThresholds(metric)
    }
    
    fun recordOperation(
        operation: String,
        durationMs: Long,
        success: Boolean,
        dataSize: Long = 0
    ) {
        val runtime = Runtime.getRuntime()
        val memoryBefore = runtime.totalMemory() - runtime.freeMemory()
        
        recordOperation(StorageMetric(
            operation = operation,
            durationMs = durationMs,
            success = success,
            dataSize = dataSize,
            memoryBefore = memoryBefore,
            memoryAfter = memoryBefore
        ))
    }
    
    private fun checkThresholds(metric: StorageMetric) {
        val threshold = thresholds[metric.operation] ?: AlertThresholds()
        
        if (metric.durationMs > threshold.maxDurationMs) {
            triggerAlert(StorageAlert(
                operation = metric.operation,
                type = AlertType.SLOW_OPERATION,
                message = "Operation ${metric.operation} took ${metric.durationMs}ms, exceeding threshold of ${threshold.maxDurationMs}ms",
                value = metric.durationMs,
                threshold = threshold.maxDurationMs
            ))
        }
        
        if (!metric.success) {
            val stats = metrics[metric.operation]?.getStats()
            if (stats != null && stats.errorRate > threshold.maxErrorRate) {
                triggerAlert(StorageAlert(
                    operation = metric.operation,
                    type = AlertType.HIGH_ERROR_RATE,
                    message = "Operation ${metric.operation} has high error rate: ${(stats.errorRate * 100).toInt()}%",
                    value = stats.errorRate,
                    threshold = threshold.maxErrorRate
                ))
            }
        }
        
        val memoryIncreasePercent = if (metric.memoryBefore > 0) {
            (metric.memoryDelta.toDouble() / metric.memoryBefore)
        } else 0.0
        
        if (memoryIncreasePercent > threshold.maxMemoryIncreasePercent) {
            triggerAlert(StorageAlert(
                operation = metric.operation,
                type = AlertType.MEMORY_SPIKE,
                message = "Operation ${metric.operation} caused memory spike: ${(memoryIncreasePercent * 100).toInt()}% increase",
                value = memoryIncreasePercent,
                threshold = threshold.maxMemoryIncreasePercent
            ))
        }
    }
    
    private fun triggerAlert(alert: StorageAlert) {
        Log.w(TAG, "Storage alert: ${alert.message}")
        
        val currentAlerts = _alerts.value.toMutableList()
        currentAlerts.add(0, alert)
        if (currentAlerts.size > 100) {
            currentAlerts.removeAt(currentAlerts.size - 1)
        }
        _alerts.value = currentAlerts
        
        notifyAlertListeners(alert)
    }
    
    private fun notifyAlertListeners(alert: StorageAlert) {
        synchronized(alertListeners) {
            alertListeners.forEach { listener ->
                try {
                    listener.onAlert(alert)
                } catch (e: Exception) {
                    Log.e(TAG, "Alert listener error", e)
                }
            }
        }
    }
    
    fun addAlertListener(listener: AlertListener) {
        synchronized(alertListeners) {
            alertListeners.add(listener)
        }
    }
    
    fun removeAlertListener(listener: AlertListener) {
        synchronized(alertListeners) {
            alertListeners.remove(listener)
        }
    }
    
    fun setThresholds(operation: String, thresholds: AlertThresholds) {
        this.thresholds[operation] = thresholds
    }
    
    fun getThresholds(operation: String): AlertThresholds? {
        return thresholds[operation]
    }
    
    fun getOperationStats(operation: String): OperationStats? {
        return metrics[operation]?.getStats()
    }
    
    fun generateReport(): StorageReport {
        val operationStats = metrics.mapValues { it.value.getStats() }
        
        val totalOps = totalOperations.get()
        val successes = totalSuccesses.get()
        val totalSuccessRate = if (totalOps > 0) successes.toDouble() / totalOps else 0.0
        
        val avgLatency = if (operationStats.isNotEmpty()) {
            operationStats.values.map { it.avgDurationMs }.average()
        } else 0.0
        
        val totalData = operationStats.values.sumOf { it.totalDataSize }
        
        val runtime = Runtime.getRuntime()
        val memoryUsage = runtime.totalMemory() - runtime.freeMemory()
        
        val diskUsage = calculateDiskUsage()
        
        val recommendations = generateRecommendations(operationStats)
        
        val healthScore = calculateHealthScore(operationStats, totalSuccessRate)
        
        val report = StorageReport(
            operations = operationStats,
            totalOperations = totalOps,
            totalSuccessRate = totalSuccessRate,
            avgLatencyMs = avgLatency,
            totalDataTransferred = totalData,
            memoryUsage = memoryUsage,
            diskUsage = diskUsage,
            recommendations = recommendations,
            healthScore = healthScore
        )
        
        _report.value = report
        return report
    }
    
    private fun calculateDiskUsage(): Long {
        var totalSize = 0L
        
        val savesDir = File(context.filesDir, "saves")
        if (savesDir.exists()) {
            totalSize += savesDir.walkTopDown().sumOf { it.length() }
        }
        
        val cacheDir = context.cacheDir
        if (cacheDir.exists()) {
            totalSize += cacheDir.walkTopDown().sumOf { it.length() }
        }
        
        return totalSize
    }
    
    private fun generateRecommendations(operationStats: Map<String, OperationStats>): List<Recommendation> {
        val recommendations = mutableListOf<Recommendation>()
        
        operationStats.forEach { (op, stats) ->
            if (stats.errorRate > 0.01) {
                recommendations.add(Recommendation(
                    severity = Severity.HIGH,
                    message = "Operation '$op' has high error rate: ${(stats.errorRate * 100).toInt()}%",
                    action = "Check error logs and fix root cause"
                ))
            }
            
            if (stats.p99DurationMs > stats.avgDurationMs * 10) {
                recommendations.add(Recommendation(
                    severity = Severity.MEDIUM,
                    message = "Operation '$op' has high latency variance",
                    action = "Optimize slow paths or add caching"
                ))
            }
            
            if (stats.avgDurationMs > 1000) {
                recommendations.add(Recommendation(
                    severity = Severity.MEDIUM,
                    message = "Operation '$op' is slow: avg ${stats.avgDurationMs.toLong()}ms",
                    action = "Consider optimization or caching"
                ))
            }
        }
        
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val memoryUsagePercent = usedMemory.toDouble() / maxMemory
        
        if (memoryUsagePercent > 0.8) {
            recommendations.add(Recommendation(
                severity = Severity.HIGH,
                message = "High memory usage: ${(memoryUsagePercent * 100).toInt()}%",
                action = "Consider clearing caches or reducing memory footprint"
            ))
        }
        
        val diskUsage = calculateDiskUsage()
        if (diskUsage > 500 * 1024 * 1024) {
            recommendations.add(Recommendation(
                severity = Severity.LOW,
                message = "High disk usage: ${diskUsage / 1024 / 1024}MB",
                action = "Consider cleaning up old save files or backups"
            ))
        }
        
        return recommendations.sortedByDescending { it.severity.ordinal }
    }
    
    private fun calculateHealthScore(
        operationStats: Map<String, OperationStats>,
        totalSuccessRate: Double
    ): Double {
        var score = 100.0
        
        operationStats.forEach { (_, stats) ->
            if (stats.errorRate > 0.05) score -= 20.0
            else if (stats.errorRate > 0.01) score -= 10.0
            
            if (stats.avgDurationMs > 1000) score -= 15.0
            else if (stats.avgDurationMs > 500) score -= 5.0
            
            if (stats.p99DurationMs > 5000) score -= 10.0
        }
        
        if (totalSuccessRate < 0.95) score -= 15.0
        else if (totalSuccessRate < 0.99) score -= 5.0
        
        val runtime = Runtime.getRuntime()
        val memoryUsage = (runtime.totalMemory() - runtime.freeMemory()).toDouble() / runtime.maxMemory()
        if (memoryUsage > 0.9) score -= 20.0
        else if (memoryUsage > 0.8) score -= 10.0
        
        return score.coerceIn(0.0, 100.0)
    }
    
    fun reset() {
        metrics.clear()
        totalOperations.set(0)
        totalSuccesses.set(0)
        totalFailures.set(0)
        _alerts.value = emptyList()
        _report.value = null
    }
    
    fun shutdown() {
        reportJob?.cancel()
        scope.cancel()
        Log.i(TAG, "StorageMonitor shutdown completed")
    }
    
    interface AlertListener {
        fun onAlert(alert: StorageAlert)
    }
}

class MetricCollector {
    private val samples = mutableListOf<StorageMetric>()
    private val durations = mutableListOf<Long>()
    private val dataSizes = mutableListOf<Long>()
    
    private val count = AtomicLong(0)
    private val totalDurationMs = AtomicLong(0)
    private val minDurationMs = AtomicLong(Long.MAX_VALUE)
    private val maxDurationMs = AtomicLong(0)
    private val successCount = AtomicLong(0)
    private val failureCount = AtomicLong(0)
    private val totalDataSize = AtomicLong(0)
    
    fun record(metric: StorageMetric) {
        count.incrementAndGet()
        totalDurationMs.addAndGet(metric.durationMs)
        totalDataSize.addAndGet(metric.dataSize)
        
        if (metric.durationMs < minDurationMs.get()) {
            minDurationMs.set(metric.durationMs)
        }
        if (metric.durationMs > maxDurationMs.get()) {
            maxDurationMs.set(metric.durationMs)
        }
        
        if (metric.success) {
            successCount.incrementAndGet()
        } else {
            failureCount.incrementAndGet()
        }
        
        synchronized(samples) {
            samples.add(metric)
            durations.add(metric.durationMs)
            dataSizes.add(metric.dataSize)
            
            if (samples.size > StorageMonitor.MAX_SAMPLES) {
                samples.removeAt(0)
                durations.removeAt(0)
                dataSizes.removeAt(0)
            }
        }
    }
    
    fun getStats(): OperationStats {
        val cnt = count.get()
        val totalDur = totalDurationMs.get()
        
        return OperationStats(
            count = cnt,
            totalDurationMs = totalDur,
            avgDurationMs = if (cnt > 0) totalDur.toDouble() / cnt else 0.0,
            minDurationMs = minDurationMs.get(),
            maxDurationMs = maxDurationMs.get(),
            p50DurationMs = calculatePercentile(50),
            p95DurationMs = calculatePercentile(95),
            p99DurationMs = calculatePercentile(99),
            successCount = successCount.get(),
            failureCount = failureCount.get(),
            totalDataSize = totalDataSize.get(),
            avgDataSize = if (cnt > 0) totalDataSize.get() / cnt else 0L
        )
    }
    
    private fun calculatePercentile(percentile: Int): Long {
        synchronized(durations) {
            if (durations.isEmpty()) return 0L
            
            val sorted = durations.sorted()
            val index = (sorted.size * percentile / 100).coerceIn(0, sorted.size - 1)
            return sorted[index]
        }
    }
}
