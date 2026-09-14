package com.xianxia.sect.data.cache

import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import com.xianxia.sect.data.memory.MemoryPressureLevel
import com.xianxia.sect.data.memory.MemorySnapshot
import kotlinx.coroutines.launch

// GameDataCacheManager 的内存压力响应域——onTrimMemory/MemoryEventListener 回调的
// 处理实现(逐出/紧急清空/平滑曲线/启动预算检查/压力自适应)。模块内消费,故 internal。
// GameDataCacheManager 的内存压力响应域——onTrimMemory/MemoryEventListener 回调的
// 处理实现(逐出/紧急清空/平滑曲线/启动预算检查/压力自适应)。模块内消费,故 internal。

internal fun GameDataCacheManager.evictByRatio(ratio: Float) {
    val entries = synchronized(memoryCache) { memoryCache.keys.toList() }
    val toRemove = entries.take((entries.size * (1 - ratio)).toInt())
    toRemove.forEach { removeSync(it) }
    evictedCount.addAndGet(toRemove.size.toLong())
    if (toRemove.isNotEmpty()) {
        Log.d(GameDataCacheManager.TAG, "Evicted ${toRemove.size} entries (ratio=$ratio)")
    }
}

internal fun GameDataCacheManager.emergencyPurge() {
    Log.e(GameDataCacheManager.TAG, "Emergency purge triggered!")
    val beforeSize = sizeSync()
    clearSync()
    evictedCount.addAndGet(beforeSize.toLong())
    memoryManager?.forceGcAndWait()
    Log.e(GameDataCacheManager.TAG, "Emergency purge completed: cleared $beforeSize entries")
}

/**
 * Smoothed sigmoid curve mapping trimLevel to reduction ratio
 */
@Suppress("DEPRECATION")
internal fun GameDataCacheManager.smoothPressureCurve(trimLevel: Int): Float {
    val normalizedLevel = when (trimLevel) {
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> 0.15f
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> 0.35f
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> 0.55f
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> 0.85f
        else -> 0.0f
    }
    return (1.0f / (1.0f + kotlin.math.exp(8.0 * (normalizedLevel - 0.5)).toFloat()))
        .coerceIn(0.1f, 1.0f)
}

/**
 * Startup memory budget check
 */
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal fun GameDataCacheManager.performStartupMemoryBudgetCheck() {
    try {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            ?: run {
                Log.i(GameDataCacheManager.TAG, "Startup memory budget check skipped: ActivityManager unavailable")
                return
            }

        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val totalMemMb = memInfo.totalMem / (1024.0 * 1024)
        val availMemMb = memInfo.availMem / (1024.0 * 1024)
        val lowMemoryThreshold = memInfo.threshold / (1024.0 * 1024)

        Log.i(GameDataCacheManager.TAG, "Startup memory budget: total=${"%.0f".format(totalMemMb)}MB, " +
                "avail=${"%.0f".format(availMemMb)}MB, " +
                "lowThreshold=${"%.0f".format(lowMemoryThreshold)}MB, " +
                "lowMemory=${memInfo.lowMemory}")

        val adjustedConfig = when {
            availMemMb < 512 -> {
                Log.w(GameDataCacheManager.TAG,
                    "Low memory device detected (${"%.0f".format(availMemMb)}MB available), reducing cache limit")
                currentConfig.copy(memoryCacheSize = 2 * 1024 * 1024L)
            }
            availMemMb < 1024 -> {
                Log.w(GameDataCacheManager.TAG,
                    "Medium-low memory device (${"%.0f".format(availMemMb)}MB available), reducing cache limit")
                currentConfig.copy(memoryCacheSize = 3 * 1024 * 1024L)
            }
            else -> {
                Log.i(GameDataCacheManager.TAG, "Sufficient memory available (${"%.0f".format(availMemMb)}MB)")
                null
            }
        }

        if (adjustedConfig != null) {
            this.currentConfig = adjustedConfig
            Log.i(GameDataCacheManager.TAG,
                "Cache config adjusted for low-memory device: ${adjustedConfig.memoryCacheSize / (1024 * 1024)}MB")
        }

        if (memInfo.lowMemory) {
            Log.w(GameDataCacheManager.TAG, "System reports low memory status, performing preemptive cleanup")
            scope.launch { evictColdData() }
        }
    } catch (e: Exception) {
        Log.w(GameDataCacheManager.TAG, "Startup memory budget check failed, using default config", e)
    }
}

internal fun GameDataCacheManager.adjustCacheForPressure(snapshot: MemorySnapshot) {
    val pressureOrdinal = snapshot.pressureLevel.ordinalValue
    val adjustedEntries = currentConfig.getAdjustedMaxEntryCount(pressureOrdinal)

    Log.d(
        GameDataCacheManager.TAG,
        "Adjusting cache for pressure ${snapshot.pressureLevel.name}: maxEntries=$adjustedEntries"
    )

    while (sizeSync() > adjustedEntries) {
        val key = synchronized(memoryCache) { memoryCache.keys.firstOrNull() } ?: break
        removeSync(key)
        evictedCount.incrementAndGet()
    }
}

internal fun GameDataCacheManager.reduceCacheSize(ratio: Float) {
    Log.w(GameDataCacheManager.TAG, "Reducing cache size by ratio: $ratio")
    val targetSize = (sizeSync() * ratio).toInt()
    while (sizeSync() > targetSize) {
        val key = synchronized(memoryCache) { memoryCache.keys.firstOrNull() } ?: break
        removeSync(key)
        evictedCount.incrementAndGet()
    }
}

@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal fun GameDataCacheManager.triggerGcIfNeeded() {
    if (currentConfig.pressureResponseConfig.enableAutoGcOnHighPressure) {
        scope.launch {
            try {
                val freed = memoryManager?.forceGcAndWait() ?: 0L
                if (freed < 0) {
                    Log.d(GameDataCacheManager.TAG, "GC freed ${-freed / 1024} KB")
                }
            } catch (e: Exception) {
                Log.e(GameDataCacheManager.TAG, "Failed to trigger GC", e)
            }
        }
    }
}

internal fun GameDataCacheManager.updateCacheForMemoryPressure() {
    memoryManager?.let { manager ->
        val snapshot = manager.getMemorySnapshot()
        currentPressureLevel = snapshot.pressureLevel
        adjustCacheForPressure(snapshot)
    }
}

@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal fun GameDataCacheManager.checkMemoryPressure() {
    if (memoryManager == null) return

    try {
        val snapshot = memoryManager.getMemorySnapshot()
        val previousPressure = currentPressureLevel
        currentPressureLevel = snapshot.pressureLevel

        if (snapshot.pressureLevel.ordinalValue > previousPressure.ordinalValue) {
            Log.w(
                GameDataCacheManager.TAG,
                "Memory pressure increased: ${previousPressure.name} -> ${snapshot.pressureLevel.name}"
            )
            adjustCacheForPressure(snapshot)

            when (snapshot.pressureLevel) {
                MemoryPressureLevel.HIGH -> {
                    evictColdData()
                    triggerGcIfNeeded()
                }
                MemoryPressureLevel.CRITICAL -> {
                    emergencyPurge()
                }
                else -> { /* No additional action needed */ }
            }
        }
    } catch (e: Exception) {
        Log.e(GameDataCacheManager.TAG, "Error checking memory pressure", e)
    }
}
