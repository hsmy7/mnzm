package com.xianxia.sect.core.util

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GCOptimizer @Inject constructor(
    private val memoryMonitor: MemoryMonitor
) {
    
    companion object {
        private const val TAG = "GCOptimizer"
        private const val GC_CHECK_INTERVAL_MS = 60_000L
        private const val GC_COOLDOWN_MS = 30_000L
        private const val MEMORY_THRESHOLD_SOFT_GC = 0.75
        private const val MEMORY_THRESHOLD_HARD_GC = 0.85
        private const val MEMORY_THRESHOLD_CRITICAL_GC = 0.92
    }
    
    private var optimizerJob: Job? = null
    private var lastGCTime = 0L
    private var gcCount = 0L
    private var totalGCTime = 0L
    private var isManualGCPending = false
    
    private val listeners = CopyOnWriteArrayList<GCEventListener>()
    
    data class GCStats(
        val totalGCCount: Long,
        val totalGCTimeMs: Long,
        val averageGCTimeMs: Double,
        val lastGCTimeMs: Long,
        val timeSinceLastGC: Long
    )
    
    data class GCOptimizationResult(
        val performed: Boolean,
        val type: GCType,
        val durationMs: Long,
        val memoryBefore: Long,
        val memoryAfter: Long,
        val memoryFreed: Long
    )
    
    enum class GCType {
        NONE,
        SOFT,
        HARD,
        CRITICAL,
        MANUAL
    }
    
    interface GCEventListener {
        fun onGCPerformed(result: GCOptimizationResult)
        fun onGCSuggested(memoryUsedPercent: Double)
    }
    
    fun startOptimization() {
        if (optimizerJob?.isActive == true) {
            Log.w(TAG, "GC optimization already running")
            return
        }
        
        optimizerJob = CoroutineScope(Dispatchers.Default).launch {
            Log.i(TAG, "Starting GC optimization")
            while (isActive) {
                try {
                    checkAndPerformGC()
                    delay(GC_CHECK_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in GC optimization", e)
                    delay(10_000)
                }
            }
        }
    }
    
    fun stopOptimization() {
        optimizerJob?.cancel()
        optimizerJob = null
        Log.i(TAG, "GC optimization stopped")
    }
    
    fun addListener(listener: GCEventListener) {
        listeners.add(listener)
    }
    
    fun removeListener(listener: GCEventListener) {
        listeners.remove(listener)
    }
    
    private fun checkAndPerformGC() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastGCTime < GC_COOLDOWN_MS) {
            return
        }
        
        val memoryInfo = memoryMonitor.getCurrentMemoryInfo() ?: return
        val usedPercent = memoryInfo.usedPercent
        
        val gcType = when {
            usedPercent >= MEMORY_THRESHOLD_CRITICAL_GC -> GCType.CRITICAL
            usedPercent >= MEMORY_THRESHOLD_HARD_GC -> GCType.HARD
            usedPercent >= MEMORY_THRESHOLD_SOFT_GC -> GCType.SOFT
            else -> GCType.NONE
        }
        
        if (gcType != GCType.NONE) {
            Log.i(TAG, "GC triggered by memory threshold: ${String.format("%.1f", usedPercent * 100)}% (${gcType.name})")
            performGC(gcType)
        }
    }
    
    fun performManualGC(reason: String = "manual"): GCOptimizationResult {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastGCTime < 5000) {
            return GCOptimizationResult(
                performed = false,
                type = GCType.MANUAL,
                durationMs = 0,
                memoryBefore = 0,
                memoryAfter = 0,
                memoryFreed = 0
            )
        }
        
        Log.i(TAG, "Manual GC requested: $reason")
        return performGC(GCType.MANUAL)
    }
    
    private fun performGC(type: GCType): GCOptimizationResult {
        val runtime = Runtime.getRuntime()
        val memoryBefore = runtime.totalMemory() - runtime.freeMemory()
        val startTime = System.currentTimeMillis()
        
        System.gc()
        
        val endTime = System.currentTimeMillis()
        val durationMs = endTime - startTime
        val memoryAfter = runtime.totalMemory() - runtime.freeMemory()
        val memoryFreed = memoryBefore - memoryAfter
        
        lastGCTime = endTime
        gcCount++
        totalGCTime += durationMs
        
        val result = GCOptimizationResult(
            performed = true,
            type = type,
            durationMs = durationMs,
            memoryBefore = memoryBefore,
            memoryAfter = memoryAfter,
            memoryFreed = memoryFreed
        )
        
        Log.i(TAG, """
            |GC Completed (${type.name}):
            |  - Duration: ${durationMs}ms
            |  - Memory before: ${formatMemory(memoryBefore)}
            |  - Memory after: ${formatMemory(memoryAfter)}
            |  - Memory freed: ${formatMemory(memoryFreed.coerceAtLeast(0))}
        """.trimMargin())
        
        notifyListeners { it.onGCPerformed(result) }
        
        return result
    }
    
    fun suggestGC(): Boolean {
        val memoryInfo = memoryMonitor.getCurrentMemoryInfo() ?: return false
        val usedPercent = memoryInfo.usedPercent
        
        if (usedPercent >= MEMORY_THRESHOLD_SOFT_GC) {
            notifyListeners { it.onGCSuggested(usedPercent) }
            return true
        }
        
        return false
    }
    
    fun getGCStats(): GCStats {
        val currentTime = System.currentTimeMillis()
        return GCStats(
            totalGCCount = gcCount,
            totalGCTimeMs = totalGCTime,
            averageGCTimeMs = if (gcCount > 0) totalGCTime.toDouble() / gcCount else 0.0,
            lastGCTimeMs = if (lastGCTime > 0) currentTime - lastGCTime else 0,
            timeSinceLastGC = if (lastGCTime > 0) currentTime - lastGCTime else Long.MAX_VALUE
        )
    }
    
    fun shouldPerformGC(): Boolean {
        val memoryInfo = memoryMonitor.getCurrentMemoryInfo() ?: return false
        val currentTime = System.currentTimeMillis()
        
        return memoryInfo.usedPercent >= MEMORY_THRESHOLD_SOFT_GC &&
               currentTime - lastGCTime >= GC_COOLDOWN_MS
    }
    
    fun getRecommendedGCType(): GCType {
        val memoryInfo = memoryMonitor.getCurrentMemoryInfo() ?: return GCType.NONE
        val usedPercent = memoryInfo.usedPercent
        
        return when {
            usedPercent >= MEMORY_THRESHOLD_CRITICAL_GC -> GCType.CRITICAL
            usedPercent >= MEMORY_THRESHOLD_HARD_GC -> GCType.HARD
            usedPercent >= MEMORY_THRESHOLD_SOFT_GC -> GCType.SOFT
            else -> GCType.NONE
        }
    }
    
    fun logGCStatus(tag: String = TAG) {
        val stats = getGCStats()
        val memoryInfo = memoryMonitor.getCurrentMemoryInfo()
        
        Log.i(tag, """
            |=== GC Status ===
            |Total GC Count: ${stats.totalGCCount}
            |Total GC Time: ${stats.totalGCTimeMs}ms
            |Average GC Time: ${String.format("%.2f", stats.averageGCTimeMs)}ms
            |Time Since Last GC: ${stats.timeSinceLastGC}ms
            |Memory Usage: ${if (memoryInfo != null) String.format("%.1f", memoryInfo.usedPercent * 100) + "%" else "N/A"}
            |Recommended GC: ${getRecommendedGCType().name}
            |=================
        """.trimMargin())
    }
    
    fun optimizeForLowMemory(): GCOptimizationResult? {
        val memoryInfo = memoryMonitor.getCurrentMemoryInfo() ?: return null
        
        if (!memoryInfo.isLowMemory && memoryInfo.usedPercent < MEMORY_THRESHOLD_HARD_GC) {
            return null
        }
        
        Log.w(TAG, "Low memory situation detected, performing aggressive GC")
        return performGC(GCType.CRITICAL)
    }
    
    fun runFinalizers() {
        Log.i(TAG, "Running finalizers")
        System.runFinalization()
    }
    
    fun performFullGC(): GCOptimizationResult {
        Log.i(TAG, "Performing full GC with finalization")
        
        runFinalizers()
        val result = performManualGC("full_gc")
        
        runFinalizers()
        
        return result
    }
    
    private fun notifyListeners(action: (GCEventListener) -> Unit) {
        CoroutineScope(Dispatchers.Main).launch {
            listeners.forEach { listener ->
                try {
                    action(listener)
                } catch (e: Exception) {
                    Log.e(TAG, "Error notifying listener", e)
                }
            }
        }
    }
    
    private fun formatMemory(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
    
    fun cleanup() {
        stopOptimization()
        listeners.clear()
    }
}
