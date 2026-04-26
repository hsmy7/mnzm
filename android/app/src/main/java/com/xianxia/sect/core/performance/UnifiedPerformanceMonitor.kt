package com.xianxia.sect.core.performance

import android.util.Log
import android.view.Choreographer
import com.xianxia.sect.core.util.GCOptimizer
import com.xianxia.sect.core.util.MemoryMonitor
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
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
        private const val SAMPLE_INTERVAL_MS = 1000L
        private const val MAX_SAMPLES = 100
        private const val FPS_WARNING_THRESHOLD = 30f
        private const val MEMORY_WARNING_THRESHOLD = 80f
        private const val TICK_TIME_WARNING_THRESHOLD = 50f
        private const val FRAME_TIME_WARNING_THRESHOLD_NS = 33_333_333L
        private const val FRAME_TIME_CRITICAL_THRESHOLD_NS = 50_000_000L
        private const val FPS_PM_WARNING_THRESHOLD = 25.0
        private const val FPS_PM_CRITICAL_THRESHOLD = 15.0
        private const val MAX_FRAME_SAMPLES = 120
        private const val MONITOR_INTERVAL_MS = 1000L
        private const val MONTHLY_EVENT_SLOW_THRESHOLD_MS = 50L
        private const val MAX_RECENT_MONTHLY_EVENTS = 100
        private const val MAX_EVENTS_PER_OPERATION = 50
        private const val SAVE_QUEUE_WARNING_THRESHOLD = 5
        private const val ENTITY_COUNT_WARNING_THRESHOLD = 1000
    }

    private val scope get() = applicationScopeProvider.scope
    private val metricsCollectors = ConcurrentHashMap<String, MetricCollector>()
    private val metricDefinitions = ConcurrentHashMap<String, MetricDefinition>()
    private val listeners = CopyOnWriteArrayList<MetricsListener>()
    private val activeTraces = ConcurrentHashMap<String, Trace>()

    private var reportJob: Job? = null
    private var isRunning = false

    private var monitorJob: Job? = null
    private var tickMonitorJob: Job? = null

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

    private var choreographer: Choreographer? = null
    private val frameTimesPm = ConcurrentLinkedQueue<Long>()
    private val operationMetrics = mutableMapOf<String, OperationMetric>()
    private val monthlyEventMetrics = ConcurrentHashMap<String, MutableList<MonthlyEventMetric>>()
    private val monthlyEventSummaries = ConcurrentHashMap<String, MonthlyEventSummary>()
    private val recentMonthlyEvents = ConcurrentLinkedQueue<MonthlyEventMetric>()
    private var lastFrameTimePm = AtomicLong(0)
    private var frameCountPm = AtomicLong(0)
    private var droppedFrames = AtomicLong(0)
    private var totalFrames = AtomicLong(0)
    private val performanceEventListeners = mutableListOf<PerformanceEventListener>()
    private var isFrameMonitoringActive = false

    private val performanceListeners = CopyOnWriteArrayList<PerformanceListener>()
    private val _performanceWarnings = mutableListOf<PerformanceWarning>()

    private inner class MonitorFrameCallback : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isFrameMonitoringActive) return
            val lastTime = lastFrameTimePm.getAndSet(frameTimeNanos)
            if (lastTime > 0L) processFrameTime(frameTimeNanos - lastTime)
            choreographer?.postFrameCallback(this)
        }
    }

    private val frameCallback = MonitorFrameCallback()

    fun registerMetric(definition: MetricDefinition) {
        if (metricsCollectors.size >= MAX_COLLECTORS) {
            Log.w(TAG, "Maximum metric collectors reached, cannot register: ${definition.name}")
            return
        }

        metricDefinitions[definition.name] = definition
        metricsCollectors.getOrPut(definition.name) { MetricCollector(definition.name) }
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
        val collector = metricsCollectors.getOrPut(name) { MetricCollector(name) }
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
        return metricsCollectors.mapValues { it.value.getStats() }
    }

    fun getMetric(name: String): MetricStats? {
        return metricsCollectors[name]?.getStats()
    }

    fun getMetricCollector(name: String): MetricCollector? {
        return metricsCollectors[name]
    }

    fun getMetricDefinition(name: String): MetricDefinition? {
        return metricDefinitions[name]
    }

    fun getAllMetricDefinitions(): Map<String, MetricDefinition> {
        return metricDefinitions.toMap()
    }

    fun resetMetric(name: String) {
        metricsCollectors[name]?.reset()
    }

    fun resetAllMetrics() {
        metricsCollectors.values.forEach { it.reset() }
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

    fun start() {
        if (tickMonitorJob?.isActive == true) return

        tickMonitorJob = scope.launch {
            while (isActive) {
                updateGamePerformanceMetrics()
                delay(SAMPLE_INTERVAL_MS)
            }
        }

        Log.i(TAG, "Performance monitor started")
    }

    fun stop() {
        tickMonitorJob?.cancel()
        tickMonitorJob = null
        Log.i(TAG, "Performance monitor stopped")
    }

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

        if (size > SAVE_QUEUE_WARNING_THRESHOLD) {
            emitWarning(PerformanceWarning(
                type = WarningType.SAVE_QUEUE_BACKUP,
                message = "Save queue backup: $size items pending",
                value = size,
                threshold = SAVE_QUEUE_WARNING_THRESHOLD
            ))
        }
    }

    fun recordEntityCount(count: Int) {
        currentEntityCount = count

        if (count > ENTITY_COUNT_WARNING_THRESHOLD) {
            emitWarning(PerformanceWarning(
                type = WarningType.ENTITY_OVERFLOW,
                message = "High entity count: $count",
                value = count,
                threshold = ENTITY_COUNT_WARNING_THRESHOLD
            ))
        }
    }

    fun addPerformanceListener(listener: PerformanceListener) {
        performanceListeners.add(listener)
    }

    fun removePerformanceListener(listener: PerformanceListener) {
        performanceListeners.remove(listener)
    }

    fun clearWarnings() {
        synchronized(_performanceWarnings) {
            _performanceWarnings.clear()
        }
    }

    fun getWarnings(): List<PerformanceWarning> {
        synchronized(_performanceWarnings) {
            return _performanceWarnings.toList()
        }
    }

    fun forceGc() {
        Log.i(TAG, "Forcing garbage collection")
        System.gc()
    }

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

    @Volatile
    private var currentFps: Float = 0f

    private fun updateGamePerformanceMetrics() {
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

        checkGamePerformanceMetrics(newMetrics)

        performanceListeners.forEach { it.onMetricsUpdate(newMetrics) }
    }

    private fun checkGamePerformanceMetrics(metrics: PerformanceMetrics) {
        if (metrics.fps < FPS_WARNING_THRESHOLD && metrics.fps > 0) {
            emitWarning(PerformanceWarning(
                type = WarningType.LOW_FPS,
                message = "Low FPS detected: ${"%.1f".format(metrics.fps)}",
                value = metrics.fps,
                threshold = FPS_WARNING_THRESHOLD
            ))
        }

        if (metrics.memoryUsagePercent > MEMORY_WARNING_THRESHOLD) {
            emitWarning(PerformanceWarning(
                type = WarningType.HIGH_MEMORY,
                message = "High memory usage: ${metrics.memoryUsagePercent.toInt()}%",
                value = metrics.memoryUsagePercent,
                threshold = MEMORY_WARNING_THRESHOLD
            ))
        }

        if (metrics.maxTickTimeMs > TICK_TIME_WARNING_THRESHOLD) {
            emitWarning(PerformanceWarning(
                type = WarningType.SLOW_TICK,
                message = "Slow tick detected: ${"%.2f".format(metrics.maxTickTimeMs)}ms",
                value = metrics.maxTickTimeMs,
                threshold = TICK_TIME_WARNING_THRESHOLD
            ))
        }
    }

    private fun emitWarning(warning: PerformanceWarning) {
        synchronized(_performanceWarnings) {
            val existingOfType = _performanceWarnings.filter { it.type == warning.type }
            if (existingOfType.isEmpty() ||
                warning.timestamp - existingOfType.last().timestamp > 10000) {
                _performanceWarnings.add(0, warning)

                if (_performanceWarnings.size > 20) {
                    _performanceWarnings.removeAt(_performanceWarnings.size - 1)
                }

                Log.w(TAG, warning.message)

                performanceListeners.forEach { it.onWarning(warning) }
            }
        }
    }

    fun initialize() {
        choreographer = Choreographer.getInstance()
        Log.i(TAG, "UnifiedPerformanceMonitor initialized")
    }

    fun startMonitoring() {
        if (monitorJob?.isActive == true) {
            Log.w(TAG, "Performance monitoring already running")
            return
        }

        isFrameMonitoringActive = true
        choreographer?.postFrameCallback(frameCallback)

        monitorJob = scope.launch {
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

    fun addPerformanceEventListener(listener: PerformanceEventListener) {
        performanceEventListeners.add(listener)
    }

    fun removePerformanceEventListener(listener: PerformanceEventListener) {
        performanceEventListeners.remove(listener)
    }

    private fun processFrameTime(frameTimeNs: Long) {
        totalFrames.incrementAndGet()
        frameTimesPm.offer(frameTimeNs)

        while (frameTimesPm.size > MAX_FRAME_SAMPLES) {
            frameTimesPm.poll()
        }

        if (frameTimeNs > FRAME_TIME_WARNING_THRESHOLD_NS) {
            val droppedCount = (frameTimeNs / FRAME_TIME_WARNING_THRESHOLD_NS).toInt()
            droppedFrames.addAndGet(droppedCount.toLong())

            if (frameTimeNs > FRAME_TIME_CRITICAL_THRESHOLD_NS) {
                Log.w(TAG, "Critical frame drop: ${frameTimeNs / 1_000_000.0}ms (${droppedCount} frames dropped)")
                notifyPerformanceEventListeners { it.onFrameDropped(frameTimeNs, droppedCount) }
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
                notifyPerformanceEventListeners { it.onOperationSlow(operation, durationMs) }
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
        val samples = frameTimesPm.toList()
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

        val isWarning = frameStats != null && frameStats.fps < FPS_PM_WARNING_THRESHOLD
        val isCritical = frameStats != null && frameStats.fps < FPS_PM_CRITICAL_THRESHOLD

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
            notifyPerformanceEventListeners { it.onPerformanceCritical(frameStats.fps, frameStats.averageFrameTime) }
        } else if (snapshot.isPerformanceWarning) {
            Log.w(TAG, "PERFORMANCE WARNING: FPS=${String.format("%.1f", frameStats.fps)}, AvgFrameTime=${String.format("%.2f", frameStats.averageFrameTime / 1_000_000)}ms")
            notifyPerformanceEventListeners { it.onPerformanceWarning(frameStats.fps, frameStats.averageFrameTime) }
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
        tickTimes.clear()
        frameTimesGpm.clear()
        frameTimesPm.clear()
        droppedFrames.set(0)
        totalFrames.set(0)
        frameCountPm.set(0)
        tickCounter.set(0)
        currentFps = 0f
        currentSaveQueueSize = 0
        currentEntityCount = 0
        synchronized(operationMetrics) {
            operationMetrics.clear()
        }
        synchronized(_performanceWarnings) {
            _performanceWarnings.clear()
        }
        Log.i(TAG, "Performance stats reset")
    }

    fun isPerformanceAcceptable(): Boolean {
        val stats = getFrameStats() ?: return true
        return stats.fps >= FPS_PM_WARNING_THRESHOLD && stats.droppedFramePercent < 10.0
    }

    fun getRecommendedOptimizationLevel(): OptimizationLevel {
        val stats = getFrameStats() ?: return OptimizationLevel.NORMAL

        return when {
            stats.fps < FPS_PM_CRITICAL_THRESHOLD -> OptimizationLevel.AGGRESSIVE
            stats.fps < FPS_PM_WARNING_THRESHOLD -> OptimizationLevel.MODERATE
            stats.droppedFramePercent > 5.0 -> OptimizationLevel.LIGHT
            else -> OptimizationLevel.NORMAL
        }
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
            notifyPerformanceEventListeners { it.onOperationSlow(operationName, durationMs) }
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

    private fun notifyPerformanceEventListeners(action: (PerformanceEventListener) -> Unit) {
        scope.launch {
            performanceEventListeners.forEach { listener ->
                try {
                    action(listener)
                } catch (e: Exception) {
                    Log.e(TAG, "Error notifying listener", e)
                }
            }
        }
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
        stopMonitoring()
        stop()
        listeners.clear()
        performanceListeners.clear()
        performanceEventListeners.clear()
        metricsCollectors.clear()
        metricDefinitions.clear()
        activeTraces.clear()
        tickTimes.clear()
        frameTimesGpm.clear()
        frameTimesPm.clear()
        operationMetrics.clear()
        monthlyEventMetrics.clear()
        monthlyEventSummaries.clear()
        recentMonthlyEvents.clear()
        synchronized(_performanceWarnings) {
            _performanceWarnings.clear()
        }
        choreographer = null
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

data class PerformanceWarning(
    val type: WarningType,
    val message: String,
    val value: Any,
    val threshold: Any,
    val timestamp: Long = System.currentTimeMillis()
)

enum class WarningType {
    LOW_FPS,
    HIGH_MEMORY,
    SLOW_TICK,
    SAVE_QUEUE_BACKUP,
    ENTITY_OVERFLOW
}

interface PerformanceListener {
    fun onMetricsUpdate(metrics: PerformanceMetrics)
    fun onWarning(warning: PerformanceWarning)
}

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

enum class OptimizationLevel {
    NORMAL,
    LIGHT,
    MODERATE,
    AGGRESSIVE
}
