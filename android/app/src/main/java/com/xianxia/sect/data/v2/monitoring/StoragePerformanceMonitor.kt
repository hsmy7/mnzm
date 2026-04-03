package com.xianxia.sect.data.v2.monitoring

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.v2.StorageArchitecture
import com.xianxia.sect.data.v2.StorageMetrics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class AtomicDouble(initialValue: Double = 0.0) {
    private val value = AtomicReference(initialValue)
    
    fun get(): Double = value.get()
    
    fun set(newValue: Double) {
        value.set(newValue)
    }
    
    fun addAndGet(delta: Double): Double {
        var prev: Double
        var next: Double
        do {
            prev = value.get()
            next = prev + delta
        } while (!value.compareAndSet(prev, next))
        return next
    }
    
    fun compareAndSet(expect: Double, update: Double): Boolean {
        return value.compareAndSet(expect, update)
    }
}

data class PerformanceMetric(
    val name: String,
    val value: Double,
    val unit: String,
    val timestamp: Long = System.currentTimeMillis(),
    val tags: Map<String, String> = emptyMap()
)

data class Alert(
    val id: String,
    val severity: AlertSeverity,
    val type: AlertType,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: Map<String, Any> = emptyMap(),
    var acknowledged: Boolean = false,
    var resolvedAt: Long? = null
)

enum class AlertSeverity {
    INFO,
    WARNING,
    ERROR,
    CRITICAL
}

enum class AlertType {
    MEMORY_PRESSURE,
    DISK_SPACE_LOW,
    SLOW_OPERATION,
    QUEUE_BACKUP,
    CACHE_MISS_RATE_HIGH,
    DATABASE_SIZE_LARGE,
    CORRUPTION_DETECTED,
    MIGRATION_REQUIRED
}

data class PerformanceThresholds(
    val maxReadTimeMs: Double = 100.0,
    val maxWriteTimeMs: Double = 200.0,
    val maxCacheMissRate: Double = 0.3,
    val maxQueueSize: Int = 100,
    val maxMemoryUsagePercent: Double = 85.0,
    val maxDiskUsagePercent: Double = 90.0,
    val maxDatabaseSizeMB: Double = 500.0,
    val maxWalSizeMB: Double = 50.0
)

data class MonitoringConfig(
    val collectionIntervalMs: Long = 5000L,
    val alertCheckIntervalMs: Long = 10000L,
    val metricsRetentionMs: Long = 24 * 60 * 60 * 1000L,
    val maxMetricsPerType: Int = 10000,
    val enableAutoAlerts: Boolean = true,
    val thresholds: PerformanceThresholds = PerformanceThresholds()
)

data class MonitoringStats(
    val totalMetricsCollected: Long = 0,
    val totalAlertsGenerated: Long = 0,
    val activeAlerts: Int = 0,
    val avgCollectionTimeMs: Double = 0.0,
    val lastCollectionTime: Long = 0,
    val uptimeMs: Long = 0
)

class StoragePerformanceMonitor(
    private val context: Context,
    private val config: MonitoringConfig = MonitoringConfig(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "StoragePerfMonitor"
    }
    
    private val metrics = ConcurrentHashMap<String, ConcurrentLinkedQueue<PerformanceMetric>>()
    private val activeAlerts = ConcurrentHashMap<String, Alert>()
    private val alertHistory = ConcurrentLinkedQueue<Alert>()
    
    private val totalMetricsCollected = AtomicLong(0)
    private val totalAlertsGenerated = AtomicLong(0)
    private val totalCollectionTime = AtomicLong(0)
    private val collectionCount = AtomicLong(0)
    private val startTime = System.currentTimeMillis()
    
    private val _stats = MutableStateFlow(MonitoringStats())
    val stats: StateFlow<MonitoringStats> = _stats.asStateFlow()
    
    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())
    val alerts: StateFlow<List<Alert>> = _alerts.asStateFlow()
    
    private val _currentMetrics = MutableStateFlow<Map<String, PerformanceMetric>>(emptyMap())
    val currentMetrics: StateFlow<Map<String, PerformanceMetric>> = _currentMetrics.asStateFlow()
    
    private val _healthScore = MutableStateFlow(100.0)
    val healthScore: StateFlow<Double> = _healthScore.asStateFlow()
    
    private var collectionJob: Job? = null
    private var alertCheckJob: Job? = null
    private var cleanupJob: Job? = null
    private var isShuttingDown = false
    
    private val alertIdCounter = AtomicLong(0)
    
    init {
        startMonitoring()
    }
    
    private fun startMonitoring() {
        collectionJob = scope.launch {
            while (isActive && !isShuttingDown) {
                delay(config.collectionIntervalMs)
                collectMetrics()
            }
        }
        
        alertCheckJob = scope.launch {
            while (isActive && !isShuttingDown) {
                delay(config.alertCheckIntervalMs)
                checkAlerts()
            }
        }
        
        cleanupJob = scope.launch {
            while (isActive && !isShuttingDown) {
                delay(60 * 60 * 1000L)
                cleanupOldMetrics()
            }
        }
    }
    
    fun recordMetric(name: String, value: Double, unit: String, tags: Map<String, String> = emptyMap()) {
        val metric = PerformanceMetric(name, value, unit, tags = tags)
        
        val metricQueue = metrics.getOrPut(name) { ConcurrentLinkedQueue() }
        metricQueue.add(metric)
        
        if (metricQueue.size > config.maxMetricsPerType) {
            metricQueue.poll()
        }
        
        totalMetricsCollected.incrementAndGet()
        
        val current = _currentMetrics.value.toMutableMap()
        current[name] = metric
        _currentMetrics.value = current
        
        checkThreshold(name, metric)
    }
    
    fun recordOperation(type: OperationType, durationMs: Long, success: Boolean) {
        val metricName = when (type) {
            OperationType.READ -> "operation_read_time"
            OperationType.WRITE -> "operation_write_time"
            OperationType.CACHE_HIT -> "cache_hit"
            OperationType.CACHE_MISS -> "cache_miss"
            OperationType.COMPRESSION -> "compression_time"
            OperationType.DECOMPRESSION -> "decompression_time"
        }
        
        recordMetric(metricName, durationMs.toDouble(), "ms", mapOf(
            "success" to success.toString(),
            "type" to type.name
        ))
        
        if (!success) {
            generateAlert(
                severity = AlertSeverity.ERROR,
                type = AlertType.SLOW_OPERATION,
                message = "${type.name} operation failed after ${durationMs}ms",
                metadata = mapOf(
                    "operationType" to type.name,
                    "durationMs" to durationMs
                )
            )
        }
    }
    
    private fun checkThreshold(name: String, metric: PerformanceMetric) {
        if (!config.enableAutoAlerts) return
        
        when (name) {
            "operation_read_time" -> {
                if (metric.value > config.thresholds.maxReadTimeMs) {
                    generateAlert(
                        severity = if (metric.value > config.thresholds.maxReadTimeMs * 2) AlertSeverity.ERROR else AlertSeverity.WARNING,
                        type = AlertType.SLOW_OPERATION,
                        message = "Read operation took ${metric.value}ms (threshold: ${config.thresholds.maxReadTimeMs}ms)",
                        metadata = mapOf("value" to metric.value, "threshold" to config.thresholds.maxReadTimeMs)
                    )
                }
            }
            "operation_write_time" -> {
                if (metric.value > config.thresholds.maxWriteTimeMs) {
                    generateAlert(
                        severity = if (metric.value > config.thresholds.maxWriteTimeMs * 2) AlertSeverity.ERROR else AlertSeverity.WARNING,
                        type = AlertType.SLOW_OPERATION,
                        message = "Write operation took ${metric.value}ms (threshold: ${config.thresholds.maxWriteTimeMs}ms)",
                        metadata = mapOf("value" to metric.value, "threshold" to config.thresholds.maxWriteTimeMs)
                    )
                }
            }
            "cache_miss_rate" -> {
                if (metric.value > config.thresholds.maxCacheMissRate) {
                    generateAlert(
                        severity = AlertSeverity.WARNING,
                        type = AlertType.CACHE_MISS_RATE_HIGH,
                        message = "Cache miss rate is ${(metric.value * 100).toInt()}% (threshold: ${(config.thresholds.maxCacheMissRate * 100).toInt()}%)",
                        metadata = mapOf("value" to metric.value, "threshold" to config.thresholds.maxCacheMissRate)
                    )
                }
            }
            "memory_usage_percent" -> {
                if (metric.value > config.thresholds.maxMemoryUsagePercent) {
                    generateAlert(
                        severity = if (metric.value > 95) AlertSeverity.CRITICAL else AlertSeverity.WARNING,
                        type = AlertType.MEMORY_PRESSURE,
                        message = "Memory usage is ${metric.value.toInt()}% (threshold: ${config.thresholds.maxMemoryUsagePercent.toInt()}%)",
                        metadata = mapOf("value" to metric.value, "threshold" to config.thresholds.maxMemoryUsagePercent)
                    )
                }
            }
            "queue_size" -> {
                if (metric.value > config.thresholds.maxQueueSize) {
                    generateAlert(
                        severity = AlertSeverity.WARNING,
                        type = AlertType.QUEUE_BACKUP,
                        message = "Write queue has ${metric.value.toInt()} pending items (threshold: ${config.thresholds.maxQueueSize})",
                        metadata = mapOf("value" to metric.value, "threshold" to config.thresholds.maxQueueSize)
                    )
                }
            }
            "database_size_mb" -> {
                if (metric.value > config.thresholds.maxDatabaseSizeMB) {
                    generateAlert(
                        severity = AlertSeverity.WARNING,
                        type = AlertType.DATABASE_SIZE_LARGE,
                        message = "Database size is ${metric.value.toInt()}MB (threshold: ${config.thresholds.maxDatabaseSizeMB.toInt()}MB)",
                        metadata = mapOf("value" to metric.value, "threshold" to config.thresholds.maxDatabaseSizeMB)
                    )
                }
            }
            "wal_size_mb" -> {
                if (metric.value > config.thresholds.maxWalSizeMB) {
                    generateAlert(
                        severity = AlertSeverity.ERROR,
                        type = AlertType.DATABASE_SIZE_LARGE,
                        message = "WAL file size is ${metric.value.toInt()}MB (threshold: ${config.thresholds.maxWalSizeMB.toInt()}MB)",
                        metadata = mapOf("value" to metric.value, "threshold" to config.thresholds.maxWalSizeMB)
                    )
                }
            }
        }
    }
    
    fun generateAlert(
        severity: AlertSeverity,
        type: AlertType,
        message: String,
        metadata: Map<String, Any> = emptyMap()
    ): Alert {
        val alert = Alert(
            id = "alert_${System.currentTimeMillis()}_${alertIdCounter.incrementAndGet()}",
            severity = severity,
            type = type,
            message = message,
            metadata = metadata
        )
        
        activeAlerts[alert.id] = alert
        alertHistory.add(alert)
        
        if (alertHistory.size > 1000) {
            alertHistory.poll()
        }
        
        totalAlertsGenerated.incrementAndGet()
        
        _alerts.value = activeAlerts.values.toList().sortedByDescending { it.timestamp }
        
        updateHealthScore()
        
        Log.w(TAG, "Alert generated: [$severity] $type - $message")
        
        return alert
    }
    
    fun acknowledgeAlert(alertId: String): Boolean {
        val alert = activeAlerts[alertId] ?: return false
        alert.acknowledged = true
        _alerts.value = activeAlerts.values.toList()
        return true
    }
    
    fun resolveAlert(alertId: String): Boolean {
        val alert = activeAlerts.remove(alertId) ?: return false
        alert.resolvedAt = System.currentTimeMillis()
        alertHistory.add(alert)
        _alerts.value = activeAlerts.values.toList()
        updateHealthScore()
        return true
    }
    
    fun clearAllAlerts() {
        activeAlerts.clear()
        _alerts.value = emptyList()
        updateHealthScore()
    }
    
    private fun updateHealthScore() {
        var score = 100.0
        
        activeAlerts.values.forEach { alert ->
            val penalty = when (alert.severity) {
                AlertSeverity.INFO -> 0.0
                AlertSeverity.WARNING -> 5.0
                AlertSeverity.ERROR -> 15.0
                AlertSeverity.CRITICAL -> 30.0
            }
            score -= penalty
        }
        
        _healthScore.value = score.coerceIn(0.0, 100.0)
    }
    
    private suspend fun collectMetrics() {
        val collectionStart = System.currentTimeMillis()
        
        try {
            val runtime = Runtime.getRuntime()
            val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            val maxMemory = runtime.maxMemory() / (1024 * 1024)
            val memoryUsagePercent = (usedMemory.toDouble() / maxMemory) * 100
            
            recordMetric("memory_used_mb", usedMemory.toDouble(), "MB")
            recordMetric("memory_max_mb", maxMemory.toDouble(), "MB")
            recordMetric("memory_usage_percent", memoryUsagePercent, "%")
            
            val filesDir = context.filesDir
            val totalSpace = filesDir.totalSpace / (1024 * 1024)
            val freeSpace = filesDir.freeSpace / (1024 * 1024)
            val usedSpace = totalSpace - freeSpace
            val diskUsagePercent = (usedSpace.toDouble() / totalSpace) * 100
            
            recordMetric("disk_total_mb", totalSpace.toDouble(), "MB")
            recordMetric("disk_free_mb", freeSpace.toDouble(), "MB")
            recordMetric("disk_usage_percent", diskUsagePercent, "%")
            
            totalCollectionTime.addAndGet(System.currentTimeMillis() - collectionStart)
            collectionCount.incrementAndGet()
            
            updateStats()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to collect metrics", e)
        }
    }
    
    private fun checkAlerts() {
        val diskUsage = _currentMetrics.value["disk_usage_percent"]?.value ?: 0.0
        if (diskUsage > config.thresholds.maxDiskUsagePercent) {
            generateAlert(
                severity = if (diskUsage > 95) AlertSeverity.CRITICAL else AlertSeverity.ERROR,
                type = AlertType.DISK_SPACE_LOW,
                message = "Disk usage is ${diskUsage.toInt()}% (threshold: ${config.thresholds.maxDiskUsagePercent.toInt()}%)",
                metadata = mapOf("value" to diskUsage)
            )
        }
    }
    
    private fun cleanupOldMetrics() {
        val cutoff = System.currentTimeMillis() - config.metricsRetentionMs
        
        metrics.forEach { (_, queue) ->
            while (queue.isNotEmpty() && queue.peek().timestamp < cutoff) {
                queue.poll()
            }
        }
        
        while (alertHistory.isNotEmpty() && alertHistory.peek().timestamp < cutoff) {
            alertHistory.poll()
        }
        
        Log.d(TAG, "Cleaned up old metrics and alerts")
    }
    
    private fun updateStats() {
        val count = collectionCount.get()
        val avgTime = if (count > 0) totalCollectionTime.get().toDouble() / count else 0.0
        
        _stats.value = MonitoringStats(
            totalMetricsCollected = totalMetricsCollected.get(),
            totalAlertsGenerated = totalAlertsGenerated.get(),
            activeAlerts = activeAlerts.size,
            avgCollectionTimeMs = avgTime,
            lastCollectionTime = System.currentTimeMillis(),
            uptimeMs = System.currentTimeMillis() - startTime
        )
    }
    
    fun getMetricHistory(name: String, limit: Int = 100): List<PerformanceMetric> {
        return metrics[name]?.toList()?.takeLast(limit) ?: emptyList()
    }
    
    fun getMetricStats(name: String): MetricStats? {
        val metricList = metrics[name]?.toList() ?: return null
        if (metricList.isEmpty()) return null
        
        val values = metricList.map { it.value }
        return MetricStats(
            name = name,
            count = values.size,
            min = values.minOrNull() ?: 0.0,
            max = values.maxOrNull() ?: 0.0,
            avg = values.average(),
            latest = values.last()
        )
    }
    
    fun getAlertHistory(limit: Int = 100): List<Alert> {
        return alertHistory.toList().takeLast(limit)
    }
    
    fun exportMetrics(): String {
        val sb = StringBuilder()
        sb.append("Storage Performance Metrics Export\n")
        sb.append("Generated: ${java.util.Date()}\n")
        sb.append("=====================================\n\n")
        
        metrics.forEach { (name, queue) ->
            val stats = getMetricStats(name)
            if (stats != null) {
                sb.append("$name:\n")
                sb.append("  Count: ${stats.count}\n")
                sb.append("  Min: ${"%.2f".format(stats.min)}\n")
                sb.append("  Max: ${"%.2f".format(stats.max)}\n")
                sb.append("  Avg: ${"%.2f".format(stats.avg)}\n")
                sb.append("  Latest: ${"%.2f".format(stats.latest)}\n\n")
            }
        }
        
        sb.append("\nActive Alerts:\n")
        activeAlerts.values.forEach { alert ->
            sb.append("  [${alert.severity}] ${alert.type}: ${alert.message}\n")
        }
        
        return sb.toString()
    }
    
    fun shutdown() {
        isShuttingDown = true
        collectionJob?.cancel()
        alertCheckJob?.cancel()
        cleanupJob?.cancel()
        scope.cancel()
        Log.i(TAG, "StoragePerformanceMonitor shutdown completed")
    }
}

enum class OperationType {
    READ,
    WRITE,
    CACHE_HIT,
    CACHE_MISS,
    COMPRESSION,
    DECOMPRESSION
}

data class MetricStats(
    val name: String,
    val count: Int,
    val min: Double,
    val max: Double,
    val avg: Double,
    val latest: Double
)

class AlertManager(
    private val monitor: StoragePerformanceMonitor,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val alertHandlers = mutableListOf<AlertHandler>()
    private val suppressedTypes = mutableSetOf<AlertType>()
    
    fun registerHandler(handler: AlertHandler) {
        alertHandlers.add(handler)
    }
    
    fun unregisterHandler(handler: AlertHandler) {
        alertHandlers.remove(handler)
    }
    
    fun suppressType(type: AlertType) {
        suppressedTypes.add(type)
    }
    
    fun unsuppressType(type: AlertType) {
        suppressedTypes.remove(type)
    }
    
    fun handleAlert(alert: Alert) {
        if (alert.type in suppressedTypes) return
        
        alertHandlers.forEach { handler ->
            try {
                if (handler.canHandle(alert)) {
                    handler.handle(alert)
                }
            } catch (e: Exception) {
                Log.e("AlertManager", "Alert handler failed", e)
            }
        }
    }
    
    fun shutdown() {
        alertHandlers.clear()
        scope.cancel()
    }
}

interface AlertHandler {
    fun canHandle(alert: Alert): Boolean
    fun handle(alert: Alert)
}

class LoggingAlertHandler : AlertHandler {
    override fun canHandle(alert: Alert): Boolean = true
    
    override fun handle(alert: Alert) {
        when (alert.severity) {
            AlertSeverity.INFO -> Log.i("AlertHandler", alert.message)
            AlertSeverity.WARNING -> Log.w("AlertHandler", alert.message)
            AlertSeverity.ERROR -> Log.e("AlertHandler", alert.message)
            AlertSeverity.CRITICAL -> Log.e("AlertHandler", "CRITICAL: ${alert.message}")
        }
    }
}
