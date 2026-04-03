package com.xianxia.sect.core.performance

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

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

@Singleton
class GamePerformanceMonitor @Inject constructor() {
    
    companion object {
        private const val TAG = "GamePerformanceMonitor"
        private const val SAMPLE_INTERVAL_MS = 1000L
        private const val MAX_SAMPLES = 100
        private const val FPS_WARNING_THRESHOLD = 30f
        private const val MEMORY_WARNING_THRESHOLD = 80f
        private const val TICK_TIME_WARNING_THRESHOLD = 50f
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorJob: Job? = null
    
    private val tickTimes = ConcurrentLinkedQueue<Float>()
    private val frameTimes = ConcurrentLinkedQueue<Float>()
    
    private val _metrics = MutableStateFlow(PerformanceMetrics())
    val metrics: StateFlow<PerformanceMetrics> = _metrics.asStateFlow()
    
    private val _warnings = MutableStateFlow<List<PerformanceWarning>>(emptyList())
    val warnings: StateFlow<List<PerformanceWarning>> = _warnings.asStateFlow()
    
    private val listeners = mutableListOf<PerformanceListener>()
    
    private val tickCounter = AtomicLong(0)
    private var lastTickTime = System.currentTimeMillis()
    
    private var lastFpsCalculation = System.currentTimeMillis()
    private var frameCount = 0
    
    fun start() {
        if (monitorJob?.isActive == true) return
        
        monitorJob = scope.launch {
            while (isActive) {
                updateMetrics()
                delay(SAMPLE_INTERVAL_MS)
            }
        }
        
        Log.i(TAG, "Performance monitor started")
    }
    
    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
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
        frameTimes.offer(durationMs)
        if (frameTimes.size > MAX_SAMPLES) {
            frameTimes.poll()
        }
        frameCount++
    }
    
    fun recordSaveQueueSize(size: Int) {
        _metrics.value = _metrics.value.copy(saveQueueSize = size)
        
        if (size > 5) {
            emitWarning(PerformanceWarning(
                type = WarningType.SAVE_QUEUE_BACKUP,
                message = "Save queue backup: $size items pending",
                value = size,
                threshold = 5
            ))
        }
    }
    
    fun recordEntityCount(count: Int) {
        _metrics.value = _metrics.value.copy(entityCount = count)
        
        if (count > 1000) {
            emitWarning(PerformanceWarning(
                type = WarningType.ENTITY_OVERFLOW,
                message = "High entity count: $count",
                value = count,
                threshold = 1000
            ))
        }
    }
    
    fun addListener(listener: PerformanceListener) {
        listeners.add(listener)
    }
    
    fun removeListener(listener: PerformanceListener) {
        listeners.remove(listener)
    }
    
    fun clearWarnings() {
        _warnings.value = emptyList()
    }
    
    private fun updateMetrics() {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory() / (1024 * 1024)
        val totalMemory = runtime.totalMemory() / (1024 * 1024)
        val freeMemory = runtime.freeMemory() / (1024 * 1024)
        val usedMemory = totalMemory - freeMemory
        val memoryPercent = (usedMemory.toFloat() / maxMemory) * 100
        
        val now = System.currentTimeMillis()
        val elapsed = (now - lastFpsCalculation) / 1000f
        val fps = if (elapsed > 0) frameCount / elapsed else 0f
        lastFpsCalculation = now
        frameCount = 0
        
        val avgTickTime = if (tickTimes.isNotEmpty()) tickTimes.average().toFloat() else 0f
        val maxTickTime = if (tickTimes.isNotEmpty()) tickTimes.maxOrNull() ?: 0f else 0f
        
        val avgFrameTime = if (frameTimes.isNotEmpty()) frameTimes.average().toFloat() else 0f
        
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
            saveQueueSize = _metrics.value.saveQueueSize,
            entityCount = _metrics.value.entityCount
        )
        
        _metrics.value = newMetrics
        
        checkWarnings(newMetrics)
        
        listeners.forEach { it.onMetricsUpdate(newMetrics) }
    }
    
    private fun checkWarnings(metrics: PerformanceMetrics) {
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
        val current = _warnings.value.toMutableList()
        
        val existingOfType = current.filter { it.type == warning.type }
        if (existingOfType.isEmpty() || 
            warning.timestamp - existingOfType.last().timestamp > 10000) {
            current.add(0, warning)
            
            if (current.size > 20) {
                current.removeAt(current.size - 1)
            }
            
            _warnings.value = current
            
            Log.w(TAG, warning.message)
            
            listeners.forEach { it.onWarning(warning) }
        }
    }
    
    fun forceGc() {
        Log.i(TAG, "Forcing garbage collection")
        System.gc()
    }
    
    fun getMemoryReport(): String {
        val metrics = _metrics.value
        return """
            Memory Report:
            - Used: ${metrics.memoryUsedMB}MB
            - Free: ${metrics.memoryFreeMB}MB
            - Max: ${metrics.memoryMaxMB}MB
            - Usage: ${metrics.memoryUsagePercent.toInt()}%
            - FPS: ${"%.1f".format(metrics.fps)}
            - Avg Tick: ${"%.2f".format(metrics.averageTickTimeMs)}ms
            - Max Tick: ${"%.2f".format(metrics.maxTickTimeMs)}ms
        """.trimIndent()
    }
    
    fun dispose() {
        stop()
        scope.cancel()
        listeners.clear()
    }
}
