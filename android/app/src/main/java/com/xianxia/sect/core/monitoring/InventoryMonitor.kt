package com.xianxia.sect.core.monitoring

import android.util.Log
import com.xianxia.sect.core.concurrent.ItemLockManager
import com.xianxia.sect.core.warehouse.CacheMetrics
import com.xianxia.sect.core.warehouse.WarehouseCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InventoryMonitor @Inject constructor(
    private val lockManager: ItemLockManager
) {
    private val operationCount = AtomicLong(0)
    private val successCount = AtomicLong(0)
    private val failureCount = AtomicLong(0)
    private val totalLatencyMs = AtomicLong(0)
    private val cacheHits = AtomicLong(0)
    private val cacheMisses = AtomicLong(0)
    
    private val recentOperations = ConcurrentLinkedQueue<OperationRecord>()
    private val _metrics = MutableStateFlow(InventoryMetrics())
    val metrics: StateFlow<InventoryMetrics> = _metrics.asStateFlow()
    
    companion object {
        private const val TAG = "InventoryMonitor"
        const val MAX_RECENT_OPERATIONS = 1000
        const val METRICS_UPDATE_INTERVAL_MS = 1000L
    }
    
    fun recordOperation(
        operation: String,
        itemType: String,
        success: Boolean,
        latencyMs: Long,
        details: Map<String, Any> = emptyMap()
    ) {
        operationCount.incrementAndGet()
        if (success) successCount.incrementAndGet() else failureCount.incrementAndGet()
        totalLatencyMs.addAndGet(latencyMs)
        
        val record = OperationRecord(
            timestamp = System.currentTimeMillis(),
            operation = operation,
            itemType = itemType,
            success = success,
            latencyMs = latencyMs,
            details = details
        )
        
        recentOperations.add(record)
        while (recentOperations.size > MAX_RECENT_OPERATIONS) {
            recentOperations.poll()
        }
        
        updateMetrics()
    }
    
    fun recordCacheHit() {
        cacheHits.incrementAndGet()
        updateMetrics()
    }
    
    fun recordCacheMiss() {
        cacheMisses.incrementAndGet()
        updateMetrics()
    }
    
    fun getCacheMetrics(): CacheMetrics = WarehouseCache.getCacheMetrics()
    
    fun getLockStats(): Map<String, ItemLockManager.LockStats> {
        return lockManager.getLockStats().mapKeys { it.key.name }
    }
    
    fun getRecentOperations(limit: Int = 100): List<OperationRecord> {
        return recentOperations.toList().takeLast(limit)
    }
    
    fun getOperationsByType(itemType: String, limit: Int = 50): List<OperationRecord> {
        return recentOperations.filter { it.itemType.equals(itemType, ignoreCase = true) }
            .takeLast(limit)
    }
    
    fun getOperationsByResult(success: Boolean, limit: Int = 50): List<OperationRecord> {
        return recentOperations.filter { it.success == success }
            .takeLast(limit)
    }
    
    fun getOperationStats(): OperationStats {
        val total = operationCount.get()
        val avgLatency = if (total > 0) totalLatencyMs.get().toDouble() / total else 0.0
        val cacheTotal = cacheHits.get() + cacheMisses.get()
        val cacheHitRate = if (cacheTotal > 0) cacheHits.get().toDouble() / cacheTotal else 0.0
        
        return OperationStats(
            totalOperations = total,
            successCount = successCount.get(),
            failureCount = failureCount.get(),
            successRate = if (total > 0) successCount.get().toDouble() / total else 0.0,
            averageLatencyMs = avgLatency,
            operationsPerSecond = calculateOpsPerSecond(),
            cacheHitRate = cacheHitRate,
            cacheHits = cacheHits.get(),
            cacheMisses = cacheMisses.get()
        )
    }
    
    fun getMetricsSummary(): MetricsSummary {
        val stats = getOperationStats()
        val cacheMetrics = getCacheMetrics()
        val lockStats = getLockStats()
        
        return MetricsSummary(
            timestamp = System.currentTimeMillis(),
            operationStats = stats,
            cacheItemCount = cacheMetrics.itemCount,
            cacheHitRate = cacheMetrics.hitRate,
            activeLocks = lockStats.count { it.value.isWriteLocked },
            totalLockQueueLength = lockStats.values.sumOf { it.queueLength }
        )
    }
    
    fun reset() {
        operationCount.set(0)
        successCount.set(0)
        failureCount.set(0)
        totalLatencyMs.set(0)
        cacheHits.set(0)
        cacheMisses.set(0)
        recentOperations.clear()
        _metrics.value = InventoryMetrics()
        Log.i(TAG, "Monitor reset completed")
    }
    
    fun logSummary() {
        val summary = getMetricsSummary()
        Log.i(TAG, """
            |=== Inventory Monitor Summary ===
            |Total Operations: ${summary.operationStats.totalOperations}
            |Success Rate: ${"%.2f".format(summary.operationStats.successRate * 100)}%
            |Avg Latency: ${"%.2f".format(summary.operationStats.averageLatencyMs)}ms
            |Ops/Second: ${"%.2f".format(summary.operationStats.operationsPerSecond)}
            |Cache Hit Rate: ${"%.2f".format(summary.cacheHitRate * 100)}%
            |Cache Items: ${summary.cacheItemCount}
            |Active Locks: ${summary.activeLocks}
            |================================
        """.trimMargin())
    }
    
    private fun updateMetrics() {
        val stats = getOperationStats()
        val cacheMetrics = getCacheMetrics()
        val lockStats = getLockStats()
        
        _metrics.value = InventoryMetrics(
            operationStats = stats,
            cacheMetrics = cacheMetrics,
            lockStats = lockStats,
            timestamp = System.currentTimeMillis()
        )
    }
    
    private fun calculateOpsPerSecond(): Double {
        val now = System.currentTimeMillis()
        val recentWindow = recentOperations.filter { 
            now - it.timestamp < 1000 
        }
        return recentWindow.size.toDouble()
    }
}

data class OperationRecord(
    val timestamp: Long,
    val operation: String,
    val itemType: String,
    val success: Boolean,
    val latencyMs: Long,
    val details: Map<String, Any>
) {
    val ageMs: Long get() = System.currentTimeMillis() - timestamp
}

data class OperationStats(
    val totalOperations: Long,
    val successCount: Long,
    val failureCount: Long,
    val successRate: Double,
    val averageLatencyMs: Double,
    val operationsPerSecond: Double,
    val cacheHitRate: Double = 0.0,
    val cacheHits: Long = 0,
    val cacheMisses: Long = 0
) {
    val failureRate: Double get() = 1.0 - successRate
}

data class InventoryMetrics(
    val operationStats: OperationStats = OperationStats(0, 0, 0, 0.0, 0.0, 0.0),
    val cacheMetrics: CacheMetrics = CacheMetrics(0, 0, 0, 0, false, 0f),
    val lockStats: Map<String, ItemLockManager.LockStats> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
) {
    val ageMs: Long get() = System.currentTimeMillis() - timestamp
}

data class MetricsSummary(
    val timestamp: Long,
    val operationStats: OperationStats,
    val cacheItemCount: Int,
    val cacheHitRate: Float,
    val activeLocks: Int,
    val totalLockQueueLength: Int
)
