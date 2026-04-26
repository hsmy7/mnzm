package com.xianxia.sect.core.util

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PerformanceMonitor @Inject constructor(
    private val applicationScopeProvider: ApplicationScopeProvider
) {
    
    companion object {
        private const val TAG = "PerformanceMonitor"
        private const val FRAME_TIME_WARNING_THRESHOLD_NS = 33_333_333L
        private const val FRAME_TIME_CRITICAL_THRESHOLD_NS = 50_000_000L
        private const val FPS_WARNING_THRESHOLD = 25.0
        private const val FPS_CRITICAL_THRESHOLD = 15.0
        private const val MAX_FRAME_SAMPLES = 120
        private const val MONITOR_INTERVAL_MS = 1000L
        private const val SLOW_OPERATION_THRESHOLD_MS = 16L
        private const val MONTHLY_EVENT_SLOW_THRESHOLD_MS = 50L
        private const val MAX_RECENT_MONTHLY_EVENTS = 100
        private const val MAX_EVENTS_PER_OPERATION = 50
    }
    
    private var monitorJob: Job? = null
    private val monitorScope get() = applicationScopeProvider.scope
    private var choreographer: Choreographer? = null
    private val handler = Handler(Looper.getMainLooper())
    
    private val frameTimes = ConcurrentLinkedQueue<Long>()
    private val operationMetrics = mutableMapOf<String, OperationMetric>()
    private val monthlyEventMetrics = ConcurrentHashMap<String, MutableList<MonthlyEventMetric>>()
    private val monthlyEventSummaries = ConcurrentHashMap<String, MonthlyEventSummary>()
    private val recentMonthlyEvents = ConcurrentLinkedQueue<MonthlyEventMetric>()
    
    private var lastFrameTime = AtomicLong(0)
    private var frameCount = AtomicLong(0)
    private var droppedFrames = AtomicLong(0)
    private var totalFrames = AtomicLong(0)
    
    private val listeners = mutableListOf<PerformanceEventListener>()
    
    private var isFrameMonitoringActive = false
    
    /** 具名 FrameCallback 内部类实现（避免匿名内部类触发 KSP getSimpleName NPE） */
    private inner class MonitorFrameCallback : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isFrameMonitoringActive) return
            val lastTime = lastFrameTime.getAndSet(frameTimeNanos)
            if (lastTime > 0L) processFrameTime(frameTimeNanos - lastTime)
            choreographer?.postFrameCallback(this)
        }
    }
    
    private val frameCallback = MonitorFrameCallback()
    
    data class FrameStats(
        val averageFrameTime: Double,
        val maxFrameTime: Long,
        val minFrameTime: Long,
        val fps: Double,
        val droppedFrameCount: Long,
        val totalFrameCount: Long,
        val droppedFramePercent: Double
    )
    
    data class OperationMetric(
        val name: String,
        var totalExecutions: Long = 0,
        var totalTimeMs: Long = 0,
        var maxTimeMs: Long = 0,
        var minTimeMs: Long = Long.MAX_VALUE,
        var lastExecutionTimeMs: Long = 0
    ) {
        val averageTimeMs: Double
            get() = if (totalExecutions > 0) totalTimeMs.toDouble() / totalExecutions else 0.0
    }
    
    data class MonthlyEventMetric(
        val operationName: String,
        val durationMs: Long,
        val timestamp: Long,
        val gameYear: Int,
        val gameMonth: Int,
        val discipleCount: Int,
        val spiritStones: Long,
        val success: Boolean,
        val errorMessage: String? = null
    )
    
    data class MonthlyEventSummary(
        val operationName: String,
        val totalExecutions: Long,
        val successCount: Long,
        val failureCount: Long,
        val averageTimeMs: Double,
        val maxTimeMs: Long,
        val minTimeMs: Long,
        val totalTimeMs: Long,
        val lastExecutionTime: Long,
        val recentSlowExecutions: List<MonthlyEventMetric>
    )
    
    data class PerformanceSnapshot(
        val timestamp: Long,
        val frameStats: FrameStats?,
        val operationMetrics: Map<String, OperationMetric>,
        val isPerformanceWarning: Boolean,
        val isPerformanceCritical: Boolean
    )
    
    interface PerformanceEventListener {
        fun onFrameDropped(frameTimeNs: Long, droppedCount: Int)
        fun onPerformanceWarning(fps: Double, avgFrameTime: Double)
        fun onPerformanceCritical(fps: Double, avgFrameTime: Double)
        fun onOperationSlow(operation: String, durationMs: Long)
    }
    
    fun initialize() {
        choreographer = Choreographer.getInstance()
        Log.i(TAG, "PerformanceMonitor initialized")
    }
    
    fun startMonitoring() {
        if (monitorJob?.isActive == true) {
            Log.w(TAG, "Performance monitoring already running")
            return
        }
        
        isFrameMonitoringActive = true
        choreographer?.postFrameCallback(frameCallback)
        
        monitorJob = monitorScope.launch {
            Log.i(TAG, "Starting performance monitoring")
            while (isActive) {
                try {
                    val snapshot = capturePerformanceSnapshot()
                    processPerformanceSnapshot(snapshot)
                    delay(MONITOR_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in performance monitoring", e)
                    delay(5000)
                }
            }
        }
    }
    
    fun stopMonitoring() {
        isFrameMonitoringActive = false
        choreographer?.removeFrameCallback(frameCallback)
        monitorJob?.cancel()
        monitorJob = null
        Log.i(TAG, "Performance monitoring stopped")
    }
    
    fun addListener(listener: PerformanceEventListener) {
        listeners.add(listener)
    }
    
    fun removeListener(listener: PerformanceEventListener) {
        listeners.remove(listener)
    }
    
    private fun processFrameTime(frameTimeNs: Long) {
        totalFrames.incrementAndGet()
        frameTimes.offer(frameTimeNs)
        
        while (frameTimes.size > MAX_FRAME_SAMPLES) {
            frameTimes.poll()
        }
        
        if (frameTimeNs > FRAME_TIME_WARNING_THRESHOLD_NS) {
            val droppedCount = (frameTimeNs / FRAME_TIME_WARNING_THRESHOLD_NS).toInt()
            droppedFrames.addAndGet(droppedCount.toLong())
            
            if (frameTimeNs > FRAME_TIME_CRITICAL_THRESHOLD_NS) {
                Log.w(TAG, "Critical frame drop: ${frameTimeNs / 1_000_000.0}ms (${droppedCount} frames dropped)")
                notifyListeners { it.onFrameDropped(frameTimeNs, droppedCount) }
            }
        }
    }
    
    fun startOperationTimer(operation: String): Long {
        return System.nanoTime()
    }
    
    fun endOperationTimer(operation: String, startTime: Long) {
        val durationNs = System.nanoTime() - startTime
        val durationMs = durationNs / 1_000_000
        
        synchronized(operationMetrics) {
            val metric = operationMetrics.getOrPut(operation) { OperationMetric(operation) }
            metric.totalExecutions++
            metric.totalTimeMs += durationMs
            metric.maxTimeMs = maxOf(metric.maxTimeMs, durationMs)
            metric.minTimeMs = minOf(metric.minTimeMs, durationMs)
            metric.lastExecutionTimeMs = durationMs
            
            if (durationMs > 100) {
                Log.w(TAG, "Slow operation: $operation took ${durationMs}ms")
                notifyListeners { it.onOperationSlow(operation, durationMs) }
            }
        }
    }
    
    inline fun <T> measureOperation(operation: String, block: () -> T): T {
        val startTime = startOperationTimer(operation)
        try {
            return block()
        } finally {
            endOperationTimer(operation, startTime)
        }
    }
    
    fun getFrameStats(): FrameStats? {
        val samples = frameTimes.toList()
        if (samples.isEmpty()) return null
        
        val avgFrameTime = samples.average()
        val maxFrameTime = samples.maxOrNull() ?: 0
        val minFrameTime = samples.minOrNull() ?: 0
        val fps = if (avgFrameTime > 0) 1_000_000_000.0 / avgFrameTime else 0.0
        val dropped = droppedFrames.get()
        val total = totalFrames.get()
        val droppedPercent = if (total > 0) dropped.toDouble() / total * 100 else 0.0
        
        return FrameStats(
            averageFrameTime = avgFrameTime,
            maxFrameTime = maxFrameTime,
            minFrameTime = minFrameTime,
            fps = fps,
            droppedFrameCount = dropped,
            totalFrameCount = total,
            droppedFramePercent = droppedPercent
        )
    }
    
    fun getOperationMetrics(): Map<String, OperationMetric> {
        return synchronized(operationMetrics) {
            operationMetrics.toMap()
        }
    }
    
    fun capturePerformanceSnapshot(): PerformanceSnapshot {
        val frameStats = getFrameStats()
        val metrics = getOperationMetrics()
        
        val isWarning = frameStats != null && frameStats.fps < FPS_WARNING_THRESHOLD
        val isCritical = frameStats != null && frameStats.fps < FPS_CRITICAL_THRESHOLD
        
        return PerformanceSnapshot(
            timestamp = System.currentTimeMillis(),
            frameStats = frameStats,
            operationMetrics = metrics,
            isPerformanceWarning = isWarning,
            isPerformanceCritical = isCritical
        )
    }
    
    private fun processPerformanceSnapshot(snapshot: PerformanceSnapshot) {
        val frameStats = snapshot.frameStats ?: return
        
        if (snapshot.isPerformanceCritical) {
            Log.e(TAG, "CRITICAL PERFORMANCE: FPS=${String.format("%.1f", frameStats.fps)}, AvgFrameTime=${String.format("%.2f", frameStats.averageFrameTime / 1_000_000)}ms")
            notifyListeners { it.onPerformanceCritical(frameStats.fps, frameStats.averageFrameTime) }
        } else if (snapshot.isPerformanceWarning) {
            Log.w(TAG, "PERFORMANCE WARNING: FPS=${String.format("%.1f", frameStats.fps)}, AvgFrameTime=${String.format("%.2f", frameStats.averageFrameTime / 1_000_000)}ms")
            notifyListeners { it.onPerformanceWarning(frameStats.fps, frameStats.averageFrameTime) }
        }
    }
    
    fun logPerformanceStatus(tag: String = TAG) {
        val frameStats = getFrameStats()
        val metrics = getOperationMetrics()
        
        val frameStatsLog = if (frameStats != null) {
            """
            |Frame Stats:
            |  - FPS: ${String.format(Locale.ROOT, "%.1f", frameStats.fps)}
            |  - Avg Frame Time: ${String.format(Locale.ROOT, "%.2f", frameStats.averageFrameTime / 1_000_000)}ms
            |  - Max Frame Time: ${String.format(Locale.ROOT, "%.2f", frameStats.maxFrameTime / 1_000_000.0)}ms
            |  - Dropped Frames: ${frameStats.droppedFrameCount} (${String.format(Locale.ROOT, "%.1f", frameStats.droppedFramePercent)}%)
            """.trimMargin()
        } else {
            "Frame Stats: No data available"
        }
        
        val slowOperations = metrics.values
            .filter { it.averageTimeMs > 16 }
            .sortedByDescending { it.averageTimeMs }
            .take(5)
        
        val operationsLog = if (slowOperations.isNotEmpty()) {
            slowOperations.joinToString("\n") { op ->
                "  - ${op.name}: avg=${String.format(Locale.ROOT, "%.1f", op.averageTimeMs)}ms, max=${op.maxTimeMs}ms, calls=${op.totalExecutions}"
            }
        } else {
            "  No slow operations detected"
        }
        
        Log.i(tag, """
            |=== Performance Status ===
            |$frameStatsLog
            |Slow Operations:
            |$operationsLog
            |==========================
        """.trimMargin())
    }
    
    fun resetStats() {
        frameTimes.clear()
        droppedFrames.set(0)
        totalFrames.set(0)
        frameCount.set(0)
        synchronized(operationMetrics) {
            operationMetrics.clear()
        }
        Log.i(TAG, "Performance stats reset")
    }
    
    fun isPerformanceAcceptable(): Boolean {
        val stats = getFrameStats() ?: return true
        return stats.fps >= FPS_WARNING_THRESHOLD && stats.droppedFramePercent < 10.0
    }
    
    fun getRecommendedOptimizationLevel(): OptimizationLevel {
        val stats = getFrameStats() ?: return OptimizationLevel.NORMAL
        
        return when {
            stats.fps < FPS_CRITICAL_THRESHOLD -> OptimizationLevel.AGGRESSIVE
            stats.fps < FPS_WARNING_THRESHOLD -> OptimizationLevel.MODERATE
            stats.droppedFramePercent > 5.0 -> OptimizationLevel.LIGHT
            else -> OptimizationLevel.NORMAL
        }
    }
    
    private fun notifyListeners(action: (PerformanceEventListener) -> Unit) {
        monitorScope.launch {
            listeners.forEach { listener ->
                try {
                    action(listener)
                } catch (e: Exception) {
                    Log.e(TAG, "Error notifying listener", e)
                }
            }
        }
    }
    
    enum class OptimizationLevel {
        NORMAL,
        LIGHT,
        MODERATE,
        AGGRESSIVE
    }
    
    fun recordMonthlyEventStart(
        operationName: String,
        gameYear: Int,
        gameMonth: Int,
        discipleCount: Int,
        spiritStones: Long
    ): Long {
        return System.nanoTime()
    }
    
    fun recordMonthlyEventEnd(
        operationName: String,
        startTime: Long,
        gameYear: Int,
        gameMonth: Int,
        discipleCount: Int,
        spiritStones: Long,
        success: Boolean = true,
        errorMessage: String? = null
    ) {
        val durationNs = System.nanoTime() - startTime
        val durationMs = durationNs / 1_000_000
        
        val metric = MonthlyEventMetric(
            operationName = operationName,
            durationMs = durationMs,
            timestamp = System.currentTimeMillis(),
            gameYear = gameYear,
            gameMonth = gameMonth,
            discipleCount = discipleCount,
            spiritStones = spiritStones,
            success = success,
            errorMessage = errorMessage
        )
        
        synchronized(monthlyEventMetrics) {
            val list = monthlyEventMetrics.getOrPut(operationName) { mutableListOf() }
            synchronized(list) {
                list.add(metric)
                while (list.size > MAX_EVENTS_PER_OPERATION) {
                    list.removeAt(0)
                }
            }
        }
        
        recentMonthlyEvents.offer(metric)
        while (recentMonthlyEvents.size > MAX_RECENT_MONTHLY_EVENTS) {
            recentMonthlyEvents.poll()
        }
        
        updateMonthlyEventSummary(operationName)
        
        if (durationMs > MONTHLY_EVENT_SLOW_THRESHOLD_MS) {
            Log.w(TAG, "Slow monthly event: $operationName took ${durationMs}ms " +
                "(Year=$gameYear, Month=$gameMonth, Disciples=$discipleCount)")
            notifyListeners { it.onOperationSlow(operationName, durationMs) }
        }
    }
    
    inline fun <T> measureMonthlyEvent(
        operationName: String,
        gameYear: Int,
        gameMonth: Int,
        discipleCount: Int,
        spiritStones: Long,
        block: () -> T
    ): T {
        val startTime = recordMonthlyEventStart(operationName, gameYear, gameMonth, discipleCount, spiritStones)
        try {
            val result = block()
            recordMonthlyEventEnd(operationName, startTime, gameYear, gameMonth, discipleCount, spiritStones, true)
            return result
        } catch (e: Exception) {
            recordMonthlyEventEnd(operationName, startTime, gameYear, gameMonth, discipleCount, spiritStones, false, e.message)
            throw e
        }
    }
    
    private fun updateMonthlyEventSummary(operationName: String) {
        val metrics = monthlyEventMetrics[operationName] ?: return
        
        synchronized(metrics) {
            if (metrics.isEmpty()) return
            
            val totalExecutions = metrics.size.toLong()
            val successCount = metrics.count { it.success }.toLong()
            val failureCount = totalExecutions - successCount
            val times = metrics.map { it.durationMs }
            val totalTimeMs = times.sum()
            val avgTimeMs = if (totalExecutions > 0) totalTimeMs.toDouble() / totalExecutions else 0.0
            val maxTimeMs = times.maxOrNull() ?: 0
            val minTimeMs = times.minOrNull() ?: 0
            val lastExecutionTime = metrics.last().timestamp
            
            val slowExecutions = metrics
                .filter { it.durationMs > MONTHLY_EVENT_SLOW_THRESHOLD_MS }
                .sortedByDescending { it.durationMs }
                .take(5)
            
            monthlyEventSummaries[operationName] = MonthlyEventSummary(
                operationName = operationName,
                totalExecutions = totalExecutions,
                successCount = successCount,
                failureCount = failureCount,
                averageTimeMs = avgTimeMs,
                maxTimeMs = maxTimeMs,
                minTimeMs = minTimeMs,
                totalTimeMs = totalTimeMs,
                lastExecutionTime = lastExecutionTime,
                recentSlowExecutions = slowExecutions
            )
        }
    }
    
    fun getMonthlyEventSummaries(): Map<String, MonthlyEventSummary> {
        return monthlyEventSummaries.toMap()
    }
    
    fun getMonthlyEventSummary(operationName: String): MonthlyEventSummary? {
        return monthlyEventSummaries[operationName]
    }
    
    fun getRecentMonthlyEvents(): List<MonthlyEventMetric> {
        return recentMonthlyEvents.toList()
    }
    
    fun logMonthlyEventStats() {
        val summaries = getMonthlyEventSummaries()
        if (summaries.isEmpty()) {
            Log.i(TAG, "No monthly event metrics recorded")
            return
        }
        
        val sortedSummaries = summaries.values.sortedByDescending { it.averageTimeMs }
        
        val statsStr = sortedSummaries.joinToString("\n") { summary ->
            val status = if (summary.failureCount > 0) " [${summary.failureCount} failures]" else ""
            "  ${summary.operationName}: avg=${String.format(Locale.ROOT, "%.1f", summary.averageTimeMs)}ms, " +
            "max=${summary.maxTimeMs}ms, calls=${summary.totalExecutions}$status"
        }
        
        Log.i(TAG, """
            |=== Monthly Event Performance ===
            |$statsStr
            |=================================
        """.trimMargin())
    }
    
    fun getSlowMonthlyEvents(thresholdMs: Long = MONTHLY_EVENT_SLOW_THRESHOLD_MS): List<MonthlyEventMetric> {
        return recentMonthlyEvents.filter { it.durationMs > thresholdMs }.sortedByDescending { it.durationMs }
    }
    
    fun getMonthlyEventPerformanceReport(): String {
        val summaries = getMonthlyEventSummaries()
        if (summaries.isEmpty()) return "No monthly event data available"
        
        val sb = StringBuilder()
        sb.appendLine("=== Monthly Event Performance Report ===")
        
        val sortedSummaries = summaries.values.sortedByDescending { it.totalTimeMs }
        var totalTime = 0L
        
        sortedSummaries.forEach { summary ->
            totalTime += summary.totalTimeMs
            sb.appendLine("${summary.operationName}:")
            sb.appendLine("  Executions: ${summary.totalExecutions} (success: ${summary.successCount}, fail: ${summary.failureCount})")
            sb.appendLine("  Time: avg=${String.format(Locale.ROOT, "%.1f", summary.averageTimeMs)}ms, max=${summary.maxTimeMs}ms, total=${summary.totalTimeMs}ms")
            
            if (summary.recentSlowExecutions.isNotEmpty()) {
                sb.appendLine("  Recent slow executions:")
                summary.recentSlowExecutions.take(3).forEach { slow ->
                    sb.appendLine("    - ${slow.durationMs}ms at Year ${slow.gameYear} Month ${slow.gameMonth}")
                }
            }
        }
        
        sb.appendLine("Total monthly event time: ${totalTime}ms")
        return sb.toString()
    }
    
    fun cleanup() {
        stopMonitoring()
        listeners.clear()
        frameTimes.clear()
        synchronized(operationMetrics) {
            operationMetrics.clear()
        }
        monthlyEventMetrics.clear()
        monthlyEventSummaries.clear()
        recentMonthlyEvents.clear()
    }
}
