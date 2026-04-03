package com.xianxia.sect.data.monitor

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.DoubleAdder

object PerformanceMonitorConfig {
    const val METRICS_RETENTION_MS = 3600_000L
    const val SAMPLE_INTERVAL_MS = 60_000L
    const val ALERT_THRESHOLD_SAVE_MS = 2000L
    const val ALERT_THRESHOLD_LOAD_MS = 3000L
    const val ALERT_THRESHOLD_CACHE_HIT_RATE = 0.7
    const val ALERT_THRESHOLD_MEMORY_USAGE = 0.85
    const val MAX_METRICS_HISTORY = 1000
    const val MAX_ALERTS = 100
    
    const val ALERT_THRESHOLD_ZSTD_COMPRESSION_MS = 500L
    const val ALERT_THRESHOLD_SCHEMA_MIGRATION_MS = 1000L
    const val ALERT_THRESHOLD_WRITE_QUEUE_SIZE = 500
    const val ALERT_THRESHOLD_WARMUP_TIME_MS = 5000L
}

enum class MetricType {
    SAVE_OPERATION,
    LOAD_OPERATION,
    CACHE_HIT,
    CACHE_MISS,
    DB_QUERY,
    DB_WRITE,
    SERIALIZATION,
    DESERIALIZATION,
    MEMORY_USAGE,
    DISK_IO,
    ZONE_TRANSITION,
    SNAPSHOT_CREATE,
    SNAPSHOT_RESTORE,
    ZSTD_COMPRESSION,
    ZSTD_DECOMPRESSION,
    ZSTD_DICT_TRAINING,
    SCHEMA_MIGRATION,
    WRITE_QUEUE_ENQUEUE,
    WRITE_QUEUE_BATCH,
    CACHE_WARMUP,
    PRELOAD_PREDICTION
}

enum class AlertLevel {
    INFO,
    WARNING,
    ERROR,
    CRITICAL
}

data class PerformanceMetric(
    val type: MetricType,
    val value: Double,
    val unit: String,
    val timestamp: Long = System.currentTimeMillis(),
    val tags: Map<String, String> = emptyMap()
)

data class PerformanceAlert(
    val level: AlertLevel,
    val type: MetricType,
    val message: String,
    val value: Double,
    val threshold: Double,
    val timestamp: Long = System.currentTimeMillis()
)

data class PerformanceOperationStats(
    val count: Long = 0,
    val totalTimeMs: Long = 0,
    val avgTimeMs: Double = 0.0,
    val minTimeMs: Long = Long.MAX_VALUE,
    val maxTimeMs: Long = 0,
    val p50Ms: Double = 0.0,
    val p95Ms: Double = 0.0,
    val p99Ms: Double = 0.0
)

data class PerformanceSnapshot(
    val timestamp: Long = System.currentTimeMillis(),
    val saveStats: PerformanceOperationStats = PerformanceOperationStats(),
    val loadStats: PerformanceOperationStats = PerformanceOperationStats(),
    val cacheHitRate: Double = 0.0,
    val memoryUsagePercent: Double = 0.0,
    val diskUsageBytes: Long = 0,
    val dbSizeBytes: Long = 0,
    val walSizeBytes: Long = 0,
    val activeOperations: Int = 0,
    val pendingWrites: Int = 0,
    val zstdCompressionStats: PerformanceOperationStats = PerformanceOperationStats(),
    val zstdDecompressionStats: PerformanceOperationStats = PerformanceOperationStats(),
    val schemaMigrationStats: PerformanceOperationStats = PerformanceOperationStats(),
    val writeQueueStats: WriteQueueMetrics = WriteQueueMetrics(),
    val warmupStats: WarmupMetrics = WarmupMetrics()
)

data class WriteQueueMetrics(
    val currentSize: Int = 0,
    val capacity: Int = 0,
    val utilizationPercent: Double = 0.0,
    val avgBatchSize: Double = 0.0,
    val overflowCount: Long = 0,
    val adaptiveResizeCount: Long = 0
)

data class WarmupMetrics(
    val totalWarmups: Long = 0,
    val successfulWarmups: Long = 0,
    val avgWarmupTimeMs: Double = 0.0,
    val hitRateAfterWarmup: Double = 0.0,
    val itemsWarmed: Long = 0
)

data class PerformanceReport(
    val snapshot: PerformanceSnapshot,
    val alerts: List<PerformanceAlert> = emptyList(),
    val recommendations: List<String> = emptyList(),
    val trends: Map<MetricType, TrendAnalysis> = emptyMap()
)

data class TrendAnalysis(
    val type: MetricType,
    val currentValue: Double,
    val previousValue: Double,
    val changePercent: Double,
    val trend: Trend,
    val prediction: Double
)

enum class Trend {
    IMPROVING,
    STABLE,
    DEGRADING,
    CRITICAL
}

class StoragePerformanceMonitor(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "StoragePerformanceMonitor"
    }
    
    private val metricsHistory = ConcurrentLinkedDeque<PerformanceMetric>()
    private val alerts = ConcurrentLinkedDeque<PerformanceAlert>()
    private val operationTimes = ConcurrentHashMap<MetricType, ConcurrentLinkedDeque<Long>>()
    
    private val saveCount = AtomicLong(0)
    private val loadCount = AtomicLong(0)
    private val cacheHits = AtomicLong(0)
    private val cacheMisses = AtomicLong(0)
    private val totalTime = ConcurrentHashMap<MetricType, AtomicLong>()
    private val minTime = ConcurrentHashMap<MetricType, AtomicLong>()
    private val maxTime = ConcurrentHashMap<MetricType, AtomicLong>()
    
    private val activeOperations = AtomicLong(0)
    private val pendingWrites = AtomicLong(0)
    
    private val _currentSnapshot = MutableStateFlow(PerformanceSnapshot())
    val currentSnapshot: StateFlow<PerformanceSnapshot> = _currentSnapshot.asStateFlow()
    
    private var monitorJob: Job? = null
    private var isShuttingDown = false
    
    init {
        MetricType.values().forEach { type ->
            operationTimes[type] = ConcurrentLinkedDeque()
            totalTime[type] = AtomicLong(0)
            minTime[type] = AtomicLong(Long.MAX_VALUE)
            maxTime[type] = AtomicLong(0)
        }
        
        startMonitoring()
    }
    
    private fun startMonitoring() {
        monitorJob = scope.launch {
            while (isActive && !isShuttingDown) {
                delay(PerformanceMonitorConfig.SAMPLE_INTERVAL_MS)
                updateSnapshot()
                checkAlerts()
                cleanupOldData()
            }
        }
    }
    
    fun recordOperation(type: MetricType, durationMs: Long, tags: Map<String, String> = emptyMap()) {
        val metric = PerformanceMetric(
            type = type,
            value = durationMs.toDouble(),
            unit = "ms",
            tags = tags
        )
        
        metricsHistory.addLast(metric)
        
        operationTimes[type]?.addLast(durationMs)
        totalTime[type]?.addAndGet(durationMs)
        
        minTime[type]?.updateAndGet { current -> minOf(current, durationMs) }
        maxTime[type]?.updateAndGet { current -> maxOf(current, durationMs) }
        
        when (type) {
            MetricType.SAVE_OPERATION -> saveCount.incrementAndGet()
            MetricType.LOAD_OPERATION -> loadCount.incrementAndGet()
            MetricType.CACHE_HIT -> cacheHits.incrementAndGet()
            MetricType.CACHE_MISS -> cacheMisses.incrementAndGet()
            else -> {}
        }
        
        checkImmediateAlert(type, durationMs)
        
        trimHistory()
    }
    
    fun recordOperationStart(type: MetricType): Long {
        activeOperations.incrementAndGet()
        return System.currentTimeMillis()
    }
    
    fun recordOperationEnd(type: MetricType, startTime: Long, tags: Map<String, String> = emptyMap()) {
        val duration = System.currentTimeMillis() - startTime
        activeOperations.decrementAndGet()
        recordOperation(type, duration, tags)
    }
    
    fun recordMemoryUsage(usedBytes: Long, totalBytes: Long) {
        val usagePercent = (usedBytes.toDouble() / totalBytes) * 100
        
        val metric = PerformanceMetric(
            type = MetricType.MEMORY_USAGE,
            value = usagePercent,
            unit = "%"
        )
        
        metricsHistory.addLast(metric)
        
        if (usagePercent > PerformanceMonitorConfig.ALERT_THRESHOLD_MEMORY_USAGE * 100) {
            addAlert(
                AlertLevel.WARNING,
                MetricType.MEMORY_USAGE,
                "Memory usage is high: ${"%.1f".format(usagePercent)}%",
                usagePercent,
                PerformanceMonitorConfig.ALERT_THRESHOLD_MEMORY_USAGE * 100
            )
        }
    }
    
    fun recordDiskUsage(dbBytes: Long, walBytes: Long, totalBytes: Long) {
        val metric = PerformanceMetric(
            type = MetricType.DISK_IO,
            value = totalBytes.toDouble(),
            unit = "bytes",
            tags = mapOf(
                "db" to dbBytes.toString(),
                "wal" to walBytes.toString()
            )
        )
        
        metricsHistory.addLast(metric)
    }
    
    fun incrementPendingWrites() {
        pendingWrites.incrementAndGet()
    }
    
    fun decrementPendingWrites() {
        pendingWrites.decrementAndGet()
    }
    
    private val writeQueueSize = AtomicLong(0)
    private val writeQueueCapacity = AtomicLong(256)
    private val writeQueueOverflowCount = AtomicLong(0)
    private val writeQueueAdaptiveResizeCount = AtomicLong(0)
    private val writeQueueBatchSizes = ConcurrentLinkedDeque<Int>()
    
    private val warmupCount = AtomicLong(0)
    private val warmupSuccessCount = AtomicLong(0)
    private val warmupTotalTime = AtomicLong(0)
    private val warmupItemsCount = AtomicLong(0)
    private val warmupHitRates = ConcurrentLinkedDeque<Double>()
    
    fun recordWriteQueueMetrics(
        currentSize: Int,
        capacity: Int,
        batchSize: Int,
        isOverflow: Boolean = false,
        isAdaptiveResize: Boolean = false
    ) {
        writeQueueSize.set(currentSize.toLong())
        writeQueueCapacity.set(capacity.toLong())
        
        writeQueueBatchSizes.addLast(batchSize)
        while (writeQueueBatchSizes.size > 100) {
            writeQueueBatchSizes.removeFirst()
        }
        
        if (isOverflow) {
            writeQueueOverflowCount.incrementAndGet()
        }
        
        if (isAdaptiveResize) {
            writeQueueAdaptiveResizeCount.incrementAndGet()
        }
        
        if (currentSize > PerformanceMonitorConfig.ALERT_THRESHOLD_WRITE_QUEUE_SIZE) {
            addAlert(
                AlertLevel.WARNING,
                MetricType.WRITE_QUEUE_ENQUEUE,
                "Write queue size is high: $currentSize",
                currentSize.toDouble(),
                PerformanceMonitorConfig.ALERT_THRESHOLD_WRITE_QUEUE_SIZE.toDouble()
            )
        }
    }
    
    fun recordWarmupMetrics(
        success: Boolean,
        itemsWarmed: Int,
        durationMs: Long,
        hitRate: Double
    ) {
        warmupCount.incrementAndGet()
        if (success) {
            warmupSuccessCount.incrementAndGet()
        }
        warmupTotalTime.addAndGet(durationMs)
        warmupItemsCount.addAndGet(itemsWarmed.toLong())
        
        warmupHitRates.addLast(hitRate)
        while (warmupHitRates.size > 50) {
            warmupHitRates.removeFirst()
        }
        
        recordOperation(MetricType.CACHE_WARMUP, durationMs, mapOf(
            "items" to itemsWarmed.toString(),
            "success" to success.toString()
        ))
    }
    
    fun getWriteQueueMetrics(): WriteQueueMetrics {
        val size = writeQueueSize.get().toInt()
        val capacity = writeQueueCapacity.get().toInt()
        val batchSizes = writeQueueBatchSizes.toList()
        
        return WriteQueueMetrics(
            currentSize = size,
            capacity = capacity,
            utilizationPercent = if (capacity > 0) (size.toDouble() / capacity) * 100 else 0.0,
            avgBatchSize = if (batchSizes.isNotEmpty()) batchSizes.average() else 0.0,
            overflowCount = writeQueueOverflowCount.get(),
            adaptiveResizeCount = writeQueueAdaptiveResizeCount.get()
        )
    }
    
    fun getWarmupMetrics(): WarmupMetrics {
        val total = warmupCount.get()
        val success = warmupSuccessCount.get()
        val hitRates = warmupHitRates.toList()
        
        return WarmupMetrics(
            totalWarmups = total,
            successfulWarmups = success,
            avgWarmupTimeMs = if (success > 0) warmupTotalTime.get().toDouble() / success else 0.0,
            hitRateAfterWarmup = if (hitRates.isNotEmpty()) hitRates.average() else 0.0,
            itemsWarmed = warmupItemsCount.get()
        )
    }
    
    private fun checkImmediateAlert(type: MetricType, durationMs: Long) {
        when (type) {
            MetricType.SAVE_OPERATION -> {
                if (durationMs > PerformanceMonitorConfig.ALERT_THRESHOLD_SAVE_MS) {
                    addAlert(
                        AlertLevel.WARNING,
                        type,
                        "Save operation took ${durationMs}ms, exceeding threshold",
                        durationMs.toDouble(),
                        PerformanceMonitorConfig.ALERT_THRESHOLD_SAVE_MS.toDouble()
                    )
                }
            }
            MetricType.LOAD_OPERATION -> {
                if (durationMs > PerformanceMonitorConfig.ALERT_THRESHOLD_LOAD_MS) {
                    addAlert(
                        AlertLevel.WARNING,
                        type,
                        "Load operation took ${durationMs}ms, exceeding threshold",
                        durationMs.toDouble(),
                        PerformanceMonitorConfig.ALERT_THRESHOLD_LOAD_MS.toDouble()
                    )
                }
            }
            MetricType.ZSTD_COMPRESSION, MetricType.ZSTD_DECOMPRESSION -> {
                if (durationMs > PerformanceMonitorConfig.ALERT_THRESHOLD_ZSTD_COMPRESSION_MS) {
                    addAlert(
                        AlertLevel.WARNING,
                        type,
                        "ZSTD operation took ${durationMs}ms",
                        durationMs.toDouble(),
                        PerformanceMonitorConfig.ALERT_THRESHOLD_ZSTD_COMPRESSION_MS.toDouble()
                    )
                }
            }
            MetricType.SCHEMA_MIGRATION -> {
                if (durationMs > PerformanceMonitorConfig.ALERT_THRESHOLD_SCHEMA_MIGRATION_MS) {
                    addAlert(
                        AlertLevel.WARNING,
                        type,
                        "Schema migration took ${durationMs}ms",
                        durationMs.toDouble(),
                        PerformanceMonitorConfig.ALERT_THRESHOLD_SCHEMA_MIGRATION_MS.toDouble()
                    )
                }
            }
            MetricType.CACHE_WARMUP -> {
                if (durationMs > PerformanceMonitorConfig.ALERT_THRESHOLD_WARMUP_TIME_MS) {
                    addAlert(
                        AlertLevel.WARNING,
                        type,
                        "Cache warmup took ${durationMs}ms",
                        durationMs.toDouble(),
                        PerformanceMonitorConfig.ALERT_THRESHOLD_WARMUP_TIME_MS.toDouble()
                    )
                }
            }
            else -> {}
        }
    }
    
    private fun addAlert(level: AlertLevel, type: MetricType, message: String, value: Double, threshold: Double) {
        val alert = PerformanceAlert(level, type, message, value, threshold)
        alerts.addLast(alert)
        
        if (alerts.size > PerformanceMonitorConfig.MAX_ALERTS) {
            alerts.removeFirst()
        }
        
        Log.w(TAG, "[${level.name}] $message")
    }
    
    private fun updateSnapshot() {
        val saveStats = calculateOperationStats(MetricType.SAVE_OPERATION)
        val loadStats = calculateOperationStats(MetricType.LOAD_OPERATION)
        val zstdCompressionStats = calculateOperationStats(MetricType.ZSTD_COMPRESSION)
        val zstdDecompressionStats = calculateOperationStats(MetricType.ZSTD_DECOMPRESSION)
        val schemaMigrationStats = calculateOperationStats(MetricType.SCHEMA_MIGRATION)
        
        val hits = cacheHits.get()
        val misses = cacheMisses.get()
        val cacheHitRate = if (hits + misses > 0) hits.toDouble() / (hits + misses) else 0.0
        
        _currentSnapshot.value = PerformanceSnapshot(
            saveStats = saveStats,
            loadStats = loadStats,
            cacheHitRate = cacheHitRate,
            activeOperations = activeOperations.get().toInt(),
            pendingWrites = pendingWrites.get().toInt(),
            zstdCompressionStats = zstdCompressionStats,
            zstdDecompressionStats = zstdDecompressionStats,
            schemaMigrationStats = schemaMigrationStats,
            writeQueueStats = getWriteQueueMetrics(),
            warmupStats = getWarmupMetrics()
        )
    }
    
    private fun calculateOperationStats(type: MetricType): PerformanceOperationStats {
        val times = operationTimes[type] ?: return PerformanceOperationStats()
        val timesList = times.toList()
        
        if (timesList.isEmpty()) return PerformanceOperationStats()
        
        val sorted = timesList.sorted()
        val count = sorted.size.toLong()
        val sum = sorted.sum().toLong()
        
        return PerformanceOperationStats(
            count = count,
            totalTimeMs = sum,
            avgTimeMs = sum.toDouble() / count,
            minTimeMs = sorted.first(),
            maxTimeMs = sorted.last(),
            p50Ms = getPercentile(sorted, 0.50),
            p95Ms = getPercentile(sorted, 0.95),
            p99Ms = getPercentile(sorted, 0.99)
        )
    }
    
    private fun getPercentile(sorted: List<Long>, percentile: Double): Double {
        if (sorted.isEmpty()) return 0.0
        
        val index = (sorted.size * percentile).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index].toDouble()
    }
    
    private fun checkAlerts() {
        val snapshot = _currentSnapshot.value
        
        if (snapshot.cacheHitRate < PerformanceMonitorConfig.ALERT_THRESHOLD_CACHE_HIT_RATE) {
            addAlert(
                AlertLevel.WARNING,
                MetricType.CACHE_HIT,
                "Cache hit rate is low: ${"%.1f".format(snapshot.cacheHitRate * 100)}%",
                snapshot.cacheHitRate,
                PerformanceMonitorConfig.ALERT_THRESHOLD_CACHE_HIT_RATE
            )
        }
        
        if (snapshot.saveStats.p95Ms > PerformanceMonitorConfig.ALERT_THRESHOLD_SAVE_MS) {
            addAlert(
                AlertLevel.WARNING,
                MetricType.SAVE_OPERATION,
                "P95 save time is high: ${"%.0f".format(snapshot.saveStats.p95Ms)}ms",
                snapshot.saveStats.p95Ms,
                PerformanceMonitorConfig.ALERT_THRESHOLD_SAVE_MS.toDouble()
            )
        }
    }
    
    private fun cleanupOldData() {
        val cutoff = System.currentTimeMillis() - PerformanceMonitorConfig.METRICS_RETENTION_MS
        
        while (metricsHistory.isNotEmpty() && metricsHistory.first.timestamp < cutoff) {
            metricsHistory.removeFirst()
        }
        
        operationTimes.forEach { (_, deque) ->
            while (deque.size > PerformanceMonitorConfig.MAX_METRICS_HISTORY) {
                deque.removeFirst()
            }
        }
        
        while (alerts.size > PerformanceMonitorConfig.MAX_ALERTS) {
            alerts.removeFirst()
        }
    }
    
    private fun trimHistory() {
        while (metricsHistory.size > PerformanceMonitorConfig.MAX_METRICS_HISTORY) {
            metricsHistory.removeFirst()
        }
    }
    
    fun generateReport(): PerformanceReport {
        val snapshot = _currentSnapshot.value
        val recentAlerts = alerts.toList().takeLast(20)
        val recommendations = generateRecommendations(snapshot, recentAlerts)
        val trends = analyzeTrends()
        
        return PerformanceReport(
            snapshot = snapshot,
            alerts = recentAlerts,
            recommendations = recommendations,
            trends = trends
        )
    }
    
    private fun generateRecommendations(snapshot: PerformanceSnapshot, recentAlerts: List<PerformanceAlert>): List<String> {
        val recommendations = mutableListOf<String>()
        
        if (snapshot.cacheHitRate < 0.7) {
            recommendations.add("Cache hit rate is low. Consider increasing cache size or improving preloading strategy.")
        }
        
        if (snapshot.saveStats.avgTimeMs > 1000) {
            recommendations.add("Average save time is high. Consider implementing incremental saves or optimizing serialization.")
        }
        
        if (snapshot.loadStats.avgTimeMs > 1500) {
            recommendations.add("Average load time is high. Consider lazy loading or data partitioning.")
        }
        
        if (snapshot.pendingWrites > 100) {
            recommendations.add("High number of pending writes. Consider batching or increasing write throughput.")
        }
        
        if (recentAlerts.any { it.level == AlertLevel.CRITICAL }) {
            recommendations.add("Critical alerts detected. Review system health immediately.")
        }
        
        return recommendations
    }
    
    private fun analyzeTrends(): Map<MetricType, TrendAnalysis> {
        val trends = mutableMapOf<MetricType, TrendAnalysis>()
        
        MetricType.values().forEach { type ->
            val analysis = analyzeTrend(type)
            if (analysis != null) {
                trends[type] = analysis
            }
        }
        
        return trends
    }
    
    private fun analyzeTrend(type: MetricType): TrendAnalysis? {
        val times = operationTimes[type]?.toList() ?: return null
        if (times.size < 10) return null
        
        val mid = times.size / 2
        val firstHalf = times.take(mid).average()
        val secondHalf = times.drop(mid).average()
        
        val changePercent = if (firstHalf > 0) ((secondHalf - firstHalf) / firstHalf) * 100 else 0.0
        
        val trend = when {
            changePercent < -10 -> Trend.IMPROVING
            changePercent > 50 -> Trend.CRITICAL
            changePercent > 20 -> Trend.DEGRADING
            else -> Trend.STABLE
        }
        
        return TrendAnalysis(
            type = type,
            currentValue = secondHalf,
            previousValue = firstHalf,
            changePercent = changePercent,
            trend = trend,
            prediction = secondHalf * (1 + changePercent / 100)
        )
    }
    
    fun getMetrics(type: MetricType?, since: Long = 0): List<PerformanceMetric> {
        return metricsHistory.filter { metric ->
            (type == null || metric.type == type) && metric.timestamp >= since
        }
    }
    
    fun getAlerts(level: AlertLevel? = null, since: Long = 0): List<PerformanceAlert> {
        return alerts.filter { alert ->
            (level == null || alert.level == level) && alert.timestamp >= since
        }
    }
    
    fun clearMetrics() {
        metricsHistory.clear()
        operationTimes.values.forEach { it.clear() }
        
        saveCount.set(0)
        loadCount.set(0)
        cacheHits.set(0)
        cacheMisses.set(0)
        
        totalTime.values.forEach { it.set(0) }
        minTime.values.forEach { it.set(Long.MAX_VALUE) }
        maxTime.values.forEach { it.set(0) }
    }
    
    fun shutdown() {
        isShuttingDown = true
        monitorJob?.cancel()
        Log.i(TAG, "StoragePerformanceMonitor shutdown completed")
    }
}

class OperationTimer(
    private val monitor: StoragePerformanceMonitor,
    private val type: MetricType,
    private val tags: Map<String, String> = emptyMap()
) {
    private val startTime = System.currentTimeMillis()
    
    fun stop() {
        monitor.recordOperation(type, System.currentTimeMillis() - startTime, tags)
    }
}

fun StoragePerformanceMonitor.timeOperation(type: MetricType, tags: Map<String, String> = emptyMap()): OperationTimer {
    return OperationTimer(this, type, tags)
}

inline fun <T> StoragePerformanceMonitor.measure(type: MetricType, tags: Map<String, String> = emptyMap(), block: () -> T): T {
    val startTime = recordOperationStart(type)
    try {
        return block()
    } finally {
        recordOperationEnd(type, startTime, tags)
    }
}

suspend fun <T> StoragePerformanceMonitor.measureSuspend(type: MetricType, tags: Map<String, String> = emptyMap(), block: suspend () -> T): T {
    val startTime = recordOperationStart(type)
    try {
        return block()
    } finally {
        recordOperationEnd(type, startTime, tags)
    }
}
