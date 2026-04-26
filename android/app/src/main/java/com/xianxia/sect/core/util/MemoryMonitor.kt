package com.xianxia.sect.core.util

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import android.util.Log
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryMonitor @Inject constructor(
    private val applicationScopeProvider: ApplicationScopeProvider
) {
    
    companion object {
        private const val TAG = "MemoryMonitor"
        private const val DEFAULT_MONITOR_INTERVAL_MS = 30_000L
        private const val MEMORY_WARNING_THRESHOLD = 0.85
        private const val MEMORY_CRITICAL_THRESHOLD = 0.95
        private const val MEMORY_LOG_THRESHOLD = 0.70
    }
    
    private var monitorJob: Job? = null
    @Volatile private var context: Context? = null
    @Volatile private var activityManager: ActivityManager? = null
    
    private val memoryHistory = mutableListOf<MemorySnapshot>()
    private val maxHistorySize = 100
    
    private var lastMemoryWarningTime = 0L
    private val warningCooldownMs = 60_000L
    
    private val listeners = CopyOnWriteArrayList<MemoryEventListener>()
    private val scope get() = applicationScopeProvider.scope
    
    data class MemorySnapshot(
        val timestamp: Long,
        val totalMemory: Long,
        val availableMemory: Long,
        val usedMemory: Long,
        val usedPercent: Double,
        val nativeMemory: Long,
        val dalvikMemory: Long,
        val memoryClass: Int,
        val isLowMemory: Boolean
    )
    
    data class MemoryInfo(
        val totalMemory: Long,
        val availableMemory: Long,
        val usedMemory: Long,
        val usedPercent: Double,
        val isLowMemory: Boolean,
        val isWarning: Boolean,
        val isCritical: Boolean
    )
    
    interface MemoryEventListener {
        fun onMemoryWarning(info: MemoryInfo)
        fun onMemoryCritical(info: MemoryInfo)
        fun onMemorySnapshot(snapshot: MemorySnapshot)
    }
    
    fun initialize(context: Context) {
        this.context = context.applicationContext
        this.activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        Log.i(TAG, "MemoryMonitor initialized. Memory class: ${activityManager?.memoryClass}MB, Large memory class: ${activityManager?.largeMemoryClass}MB")
    }
    
    fun startMonitoring(intervalMs: Long = DEFAULT_MONITOR_INTERVAL_MS) {
        if (monitorJob?.isActive == true) {
            Log.w(TAG, "Memory monitoring already running")
            return
        }
        
        monitorJob = scope.launch {
            Log.i(TAG, "Starting memory monitoring with interval ${intervalMs}ms")
            while (isActive) {
                try {
                    val snapshot = captureMemorySnapshot()
                    processMemorySnapshot(snapshot)
                    delay(intervalMs)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in memory monitoring", e)
                    delay(5000)
                }
            }
        }
    }
    
    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        Log.i(TAG, "Memory monitoring stopped")
    }
    
    fun addListener(listener: MemoryEventListener) {
        listeners.add(listener)
    }
    
    fun removeListener(listener: MemoryEventListener) {
        listeners.remove(listener)
    }
    
    fun getCurrentMemoryInfo(): MemoryInfo? {
        val activityManager = this.activityManager ?: return null
        
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val totalMemory = runtime.totalMemory()
        val maxMemory = runtime.maxMemory()
        
        val usedPercent = usedMemory.toDouble() / maxMemory.toDouble()
        
        return MemoryInfo(
            totalMemory = totalMemory,
            availableMemory = memoryInfo.availMem,
            usedMemory = usedMemory,
            usedPercent = usedPercent,
            isLowMemory = memoryInfo.lowMemory,
            isWarning = usedPercent >= MEMORY_WARNING_THRESHOLD,
            isCritical = usedPercent >= MEMORY_CRITICAL_THRESHOLD
        )
    }
    
    fun captureMemorySnapshot(): MemorySnapshot {
        val activityManager = this.activityManager
        
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val totalMemory = runtime.totalMemory()
        val maxMemory = runtime.maxMemory()
        
        val nativeMemory = Debug.getNativeHeapAllocatedSize()
        val dalvikMemory = usedMemory
        
        val systemMemoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(systemMemoryInfo)
        
        val usedPercent = usedMemory.toDouble() / maxMemory.toDouble()
        
        return MemorySnapshot(
            timestamp = System.currentTimeMillis(),
            totalMemory = totalMemory,
            availableMemory = systemMemoryInfo.availMem,
            usedMemory = usedMemory,
            usedPercent = usedPercent,
            nativeMemory = nativeMemory,
            dalvikMemory = dalvikMemory,
            memoryClass = activityManager?.memoryClass ?: 0,
            isLowMemory = systemMemoryInfo.lowMemory
        )
    }
    
    private fun processMemorySnapshot(snapshot: MemorySnapshot) {
        synchronized(memoryHistory) {
            memoryHistory.add(snapshot)
            if (memoryHistory.size > maxHistorySize) {
                memoryHistory.removeAt(0)
            }
        }
        
        val info = MemoryInfo(
            totalMemory = snapshot.totalMemory,
            availableMemory = snapshot.availableMemory,
            usedMemory = snapshot.usedMemory,
            usedPercent = snapshot.usedPercent,
            isLowMemory = snapshot.isLowMemory,
            isWarning = snapshot.usedPercent >= MEMORY_WARNING_THRESHOLD,
            isCritical = snapshot.usedPercent >= MEMORY_CRITICAL_THRESHOLD
        )
        
        val currentTime = System.currentTimeMillis()
        
        if (info.isCritical) {
            Log.e(TAG, "CRITICAL MEMORY: Used ${MemoryFormatUtil.formatMemory(snapshot.usedMemory)} / ${MemoryFormatUtil.formatMemory(Runtime.getRuntime().maxMemory())} (${String.format("%.1f", snapshot.usedPercent * 100)}%)")
            if (currentTime - lastMemoryWarningTime > warningCooldownMs) {
                lastMemoryWarningTime = currentTime
                notifyListeners { it.onMemoryCritical(info) }
            }
        } else if (info.isWarning) {
            Log.w(TAG, "MEMORY WARNING: Used ${MemoryFormatUtil.formatMemory(snapshot.usedMemory)} / ${MemoryFormatUtil.formatMemory(Runtime.getRuntime().maxMemory())} (${String.format("%.1f", snapshot.usedPercent * 100)}%)")
            if (currentTime - lastMemoryWarningTime > warningCooldownMs) {
                lastMemoryWarningTime = currentTime
                notifyListeners { it.onMemoryWarning(info) }
            }
        } else if (snapshot.usedPercent >= MEMORY_LOG_THRESHOLD) {
            Log.d(TAG, "Memory usage: ${MemoryFormatUtil.formatMemory(snapshot.usedMemory)} / ${MemoryFormatUtil.formatMemory(Runtime.getRuntime().maxMemory())} (${String.format("%.1f", snapshot.usedPercent * 100)}%)")
        }
        
        notifyListeners { it.onMemorySnapshot(snapshot) }
    }
    
    fun getMemoryHistory(): List<MemorySnapshot> {
        return synchronized(memoryHistory) {
            memoryHistory.toList()
        }
    }
    
    fun logMemoryStatus(tag: String = TAG) {
        val info = getCurrentMemoryInfo() ?: return
        
        val runtime = Runtime.getRuntime()
        val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        
        Log.i(tag, """
            |=== Memory Status at ${dateFormat.format(Date())} ===
            |Dalvik Heap: ${MemoryFormatUtil.formatMemory(info.usedMemory)} / ${MemoryFormatUtil.formatMemory(runtime.maxMemory())} (${String.format("%.1f", info.usedPercent * 100)}%)
            |Native Heap: ${MemoryFormatUtil.formatMemory(Debug.getNativeHeapAllocatedSize())}
            |System Available: ${MemoryFormatUtil.formatMemory(info.availableMemory)}
            |Low Memory: ${info.isLowMemory}
            |Memory Class: ${activityManager?.memoryClass}MB
            |===============================================
        """.trimMargin())
    }
    
    fun logDetailedMemoryInfo(tag: String = TAG) {
        val runtime = Runtime.getRuntime()
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        
        Log.i(tag, """
            |=== Detailed Memory Info ===
            |Dalvik:
            |  - Total: ${MemoryFormatUtil.formatMemory(runtime.totalMemory())}
            |  - Free: ${MemoryFormatUtil.formatMemory(runtime.freeMemory())}
            |  - Used: ${MemoryFormatUtil.formatMemory(runtime.totalMemory() - runtime.freeMemory())}
            |  - Max: ${MemoryFormatUtil.formatMemory(runtime.maxMemory())}
            |Native:
            |  - Allocated: ${MemoryFormatUtil.formatMemory(Debug.getNativeHeapAllocatedSize())}
            |  - Free: ${MemoryFormatUtil.formatMemory(Debug.getNativeHeapFreeSize())}
            |  - Size: ${MemoryFormatUtil.formatMemory(Debug.getNativeHeapSize())}
            |Memory Info:
            |  - Dalvik PSS: ${memoryInfo.dalvikPss}KB
            |  - Native PSS: ${memoryInfo.nativePss}KB
            |  - Other PSS: ${memoryInfo.otherPss}KB
            |  - Total PSS: ${memoryInfo.getTotalPss()}KB
            |=============================
        """.trimMargin())
    }
    
    fun canPerformMemoryIntensiveOperation(requiredMemoryMB: Int = 50): Boolean {
        val info = getCurrentMemoryInfo() ?: return true
        val runtime = Runtime.getRuntime()
        val availableMemory = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        val requiredBytes = requiredMemoryMB * 1024L * 1024L
        
        return availableMemory >= requiredBytes && !info.isCritical
    }
    
    fun getRecommendedGCTrigger(): Boolean {
        val info = getCurrentMemoryInfo() ?: return false
        return info.usedPercent >= MEMORY_WARNING_THRESHOLD
    }
    
    private fun notifyListeners(action: (MemoryEventListener) -> Unit) {
        scope.launch(Dispatchers.Main) {
            listeners.forEach { listener ->
                try {
                    action(listener)
                } catch (e: Exception) {
                    Log.e(TAG, "Error notifying listener", e)
                }
            }
        }
    }
    
    fun cleanup() {
        stopMonitoring()
        listeners.clear()
        synchronized(memoryHistory) {
            memoryHistory.clear()
        }
        context = null
        activityManager = null
    }
}
