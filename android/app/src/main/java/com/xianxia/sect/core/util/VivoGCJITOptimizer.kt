package com.xianxia.sect.core.util

import android.os.Build
import android.util.Log
import java.lang.reflect.Method

object VivoGCJITOptimizer {

    private const val TAG = "VivoGCJITOptimizer"

    private val jitLock = Any()

    @Volatile
    private var isVivoDevice = false

    @Volatile
    private var initialized = false

    @Volatile
    private var jitPaused = false

    @Volatile
    private var originalJitState = true

    private fun getVMRuntime(): Any {
        return Class.forName("dalvik.system.VMRuntime")
            .getMethod("getRuntime")
            .invoke(null)
            ?: throw IllegalStateException("VMRuntime.getRuntime() returned null")
    }

    data class DeviceInfo(
        val isVivo: Boolean,
        val manufacturer: String,
        val model: String,
        val sdkInt: Int,
        val artVersion: String?
    )

    fun getDeviceInfo(): DeviceInfo {
        val manufacturer = Build.MANUFACTURER.lowercase(java.util.Locale.getDefault())
        val brand = Build.BRAND.lowercase(java.util.Locale.getDefault())
        val isVivo = listOf(manufacturer, brand).any {
            it.contains("vivo") || it.contains("iqoo")
        }
        return DeviceInfo(
            isVivo = isVivo,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            sdkInt = Build.VERSION.SDK_INT,
            artVersion = System.getProperty("java.vm.version")
        )
    }

    fun initialize() {
        if (initialized) {
            Log.d(TAG, "Already initialized, skipping")
            return
        }
        synchronized(this) {
            if (initialized) return
            doInitialize()
            initialized = true
        }
    }

    private fun doInitialize() {
        val info = getDeviceInfo()
        isVivoDevice = info.isVivo

        if (!isVivoDevice) {
            Log.d(TAG, "Not a vivo/iQOO device (${info.manufacturer} ${info.model}), skipping GC/JIT optimization")
            return
        }

        Log.i(TAG, """
            |=== Vivo GC/JIT Optimizer ===
            |Manufacturer: ${info.manufacturer}
            |Model: ${info.model}
            |SDK: ${info.sdkInt}
            |ART Version: ${info.artVersion ?: "unknown"}
            |==============================
        """.trimMargin())

        try {
            preconfigureRuntimeForVivo()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to preconfigure runtime: ${e.message}")
        }
    }

    private fun preconfigureRuntimeForVivo() {
        val runtime = getVMRuntime()

        try {
            val setGrowthLimitMethod = runtime::class.java.getDeclaredMethod(
                "setGrowthLimit", Long::class.javaPrimitiveType
            )
            val maxMem = Runtime.getRuntime().maxMemory()
            val targetLimit = (maxMem * 0.85).toLong()
            setGrowthLimitMethod.invoke(runtime, targetLimit)
            Log.i(TAG, "Growth limit set to ${MemoryFormatUtil.formatMemory(targetLimit)} (max=${MemoryFormatUtil.formatMemory(maxMem)})")
        } catch (e: Exception) {
            Log.w(TAG, "setGrowthLimit not available: ${e.message}")
        }

        try {
            val setTargetHeapUtilizationMethod = runtime::class.java.getDeclaredMethod(
                "setTargetHeapUtilization", Float::class.javaPrimitiveType
            )
            setTargetHeapUtilizationMethod.invoke(runtime, 0.75f)
            Log.i(TAG, "Target heap utilization set to 0.75")
        } catch (e: Exception) {
            Log.w(TAG, "setTargetHeapUtilization not available: ${e.message}")
        }

        try {
            val clampMethod = runtime::class.java.getDeclaredMethod("clampGrowthLimit")
            clampMethod.invoke(runtime)
            Log.d(TAG, "Growth limit clamped")
        } catch (_: Exception) {
            Log.d(TAG, "clampGrowthLimit not available on this device")
        }

        Log.i(TAG, "VMRuntime preconfigured for vivo device")
    }

    fun pauseJitDuringCriticalPath(tag: String = TAG): Boolean {
        if (!isVivoDevice) return false

        synchronized(jitLock) {
            if (jitPaused) {
                Log.w(TAG, "JIT already paused, ignoring pause request from $tag")
                return false
            }

            return tryPauseJit(tag)
        }
    }

    private fun tryPauseJit(tag: String): Boolean {
        try {
            val runtime = getVMRuntime()

        val getJitMethod: Method = try {
            runtime::class.java.getDeclaredMethod("getJitCompilationEnabled")
            } catch (e: Exception) {
                Log.w(TAG, "getJitCompilationEnabled not available via reflection: ${e.message}")
                return false
            }

            originalJitState = getJitMethod.invoke(runtime) as? Boolean ?: true

            val setJitMethod: Method = try {
                runtime::class.java.getDeclaredMethod(
                    "setJitCompilationEnabled", Boolean::class.javaPrimitiveType
                )
            } catch (e: Exception) {
                Log.w(TAG, "setJitCompilationEnabled not available: ${e.message}")
                return false
            }

            setJitMethod.invoke(runtime, false)
            jitPaused = true
            Log.i(TAG, "JIT paused during critical path [$tag] (was enabled=$originalJitState)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing JIT: ${e.message}", e)
            return false
        }
    }

    fun resumeJitAfterCriticalPath(tag: String = TAG): Boolean {
        if (!isVivoDevice) return false

        synchronized(jitLock) {
            if (!jitPaused) return false

            return tryResumeJit(tag)
        }
    }

    private fun tryResumeJit(tag: String): Boolean {
        try {
            val runtime = getVMRuntime()
            val setJitMethod = runtime::class.java.getDeclaredMethod(
                "setJitCompilationEnabled", Boolean::class.javaPrimitiveType
            )

            setJitMethod.invoke(runtime, originalJitState)
            jitPaused = false
            Log.i(TAG, "JIT resumed after critical path [$tag] (restored enabled=$originalJitState)")

            triggerBackgroundGc()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming JIT: ${e.message}, JIT may still be paused!", e)
            return false
        }
    }

    fun runWithJitPaused(block: () -> Unit, tag: String = "block") {
        if (!isVivoDevice) {
            block()
            return
        }
        val paused = pauseJitDuringCriticalPath(tag)
        try {
            block()
        } finally {
            if (paused) {
                resumeJitAfterCriticalPath(tag)
            }
        }
    }

    data class LowLatencyGcResult(
        val success: Boolean,
        val strategy: String
    )

    fun requestLowLatencyGc(): LowLatencyGcResult {
        if (!isVivoDevice) return LowLatencyGcResult(false, "not_vivo_device")

        return try {
            val runtime = getVMRuntime()

            try {
                val requestMethod = runtime::class.java.getDeclaredMethod(
                    "requestLatencySensitiveGc"
                )
                requestMethod.invoke(runtime)
                Log.d(TAG, "Requested latency-sensitive GC")
                LowLatencyGcResult(true, "latency_sensitive")
            } catch (_: Exception) {}

            try {
                val clearMethod = runtime::class.java.getDeclaredMethod("clearGrowthLimit")
                clearMethod.invoke(runtime)
                Log.d(TAG, "Cleared growth limit for low-latency GC window")
                LowLatencyGcResult(true, "growth_limit_cleared")
            } catch (_: Exception) {}

            Runtime.getRuntime().gc()
            Log.d(TAG, "Fallback: triggered System.gc()")
            LowLatencyGcResult(true, "system_gc")
        } catch (e: Exception) {
            Log.e(TAG, "Error in requestLowLatencyGc: ${e.message}", e)
            LowLatencyGcResult(false, "error")
        }
    }

    private fun triggerBackgroundGc() {
        try {
            val runtime = getVMRuntime()
            val gcMethod = runtime::class.java.getDeclaredMethod(
                "requestConcurrentGc"
            )
            gcMethod.invoke(runtime)
            Log.d(TAG, "Triggered concurrent background GC after JIT resume")
        } catch (_: Exception) {
            try {
                Runtime.getRuntime().gc()
            } catch (_: Exception) {}
        }
    }

    fun extendGcDelayForMs(delayMs: Long): Boolean {
        if (!isVivoDevice) return false
        if (delayMs <= 0 || delayMs > 60_000L) {
            Log.w(TAG, "extendGcDelayForMs: invalid delay $delayMs, valid range (0, 60000]")
            return false
        }

        try {
            val runtime = getVMRuntime()

            val method = runtime::class.java.getDeclaredMethod(
                "disableGcForDuration", Long::class.javaPrimitiveType
            )
            method.invoke(runtime, delayMs)
            Log.i(TAG, "Extended GC delay by ${delayMs}ms on vivo device")
            return true
        } catch (e: Exception) {
            Log.d(TAG, "disableGcForDuration not available, skipping (no fallback)")
            return false
        }
    }

    fun isOptimizationActive(): Boolean = isVivoDevice

    fun isJitCurrentlyPaused(): Boolean = jitPaused

    fun logStatus() {
        val info = getDeviceInfo()
        val rt = Runtime.getRuntime()
        val total = rt.totalMemory()
        val free = rt.freeMemory()
        val used = (total - free).coerceAtLeast(0L)
        Log.i(TAG, """
            |=== Vivo GC/JIT Status ===
            |Is Vivo Device: $isVivoDevice
            |Device: ${info.manufacturer} ${info.model}
            |SDK: ${info.sdkInt}
            |ART: ${info.artVersion ?: "unknown"}
            |JIT Paused: $jitPaused
            |Original JIT State: $originalJitState
            |Initialized: $initialized
            |Heap: used=${MemoryFormatUtil.formatMemory(used)}, max=${MemoryFormatUtil.formatMemory(rt.maxMemory())}
            |========================
        """.trimMargin())
    }

}
