package com.xianxia.sect.core.util

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.util.Log
import com.xianxia.sect.di.ApplicationScopeProvider
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
    }
    
    @Volatile private var context: Context? = null
    @Volatile private var activityManager: ActivityManager? = null
    
    private val memoryHistory = mutableListOf<MemorySnapshot>()
    
    
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
    
    @Suppress("UnusedParameter") // intervalMs: 监控 API 兼容形参（调度器接管后保留调用契约）
    fun initialize(context: Context) {
        this.context = context.applicationContext
        this.activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        Log.i(TAG, "MemoryMonitor initialized. Memory class: ${activityManager?.memoryClass}MB, " +
            "Large memory class: ${activityManager?.largeMemoryClass}MB")
    }
    
    @Suppress("UnusedParameter") // intervalMs: 监控 API 兼容形参（调度器接管后保留调用契约）
    fun startMonitoring(intervalMs: Long = DEFAULT_MONITOR_INTERVAL_MS) {
        Log.d(TAG, "Memory monitoring start (delegated to scheduler)")
    }

    fun stopMonitoring() {
        Log.d(TAG, "Memory monitoring stop (delegated to scheduler)")
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
    
    
    fun getMemoryHistory(): List<MemorySnapshot> {
        return synchronized(memoryHistory) {
            memoryHistory.toList()
        }
    }
    
    fun logMemoryStatus(tag: String = TAG) {
        val info = getCurrentMemoryInfo() ?: return

        val runtime = Runtime.getRuntime()
        val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val dalvikUsed = MemoryFormatUtil.formatMemory(info.usedMemory)
        val dalvikMax = MemoryFormatUtil.formatMemory(runtime.maxMemory())
        val usedPercentText = String.format(Locale.US, "%.1f", info.usedPercent * 100)

        Log.i(tag, """
            |=== Memory Status at ${dateFormat.format(Date())} ===
            |Dalvik Heap: $dalvikUsed / $dalvikMax ($usedPercentText%)
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
