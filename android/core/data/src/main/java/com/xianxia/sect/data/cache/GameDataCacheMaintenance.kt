package com.xianxia.sect.data.cache

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// GameDataCacheManager 的维护域:过期清理/统计发布/冷数据逐出/预热/关闭。
// CacheLayer 门面与挂起 API 的消费路径,扩展函数调用语法不变。模块内消费,故 internal。
// GameDataCacheManager 的维护域:过期清理/统计发布/冷数据逐出/预热/关闭。
// CacheLayer 门面与挂起 API 的消费路径,扩展函数调用语法不变。模块内消费,故 internal。

/**
 * Evict cold data (public interface, called by ProactiveMemoryGuard)
 */
internal fun GameDataCacheManager.evictColdData(): Int {
    val beforeSize = sizeSync()
    val targetSize = beforeSize / 2
    var evicted = 0
    val keysToEvict = synchronized(memoryCache) {
        memoryCache.entries
            .sortedBy { it.value.createdAt }
            .take(beforeSize - targetSize)
            .map { it.key }
    }
    keysToEvict.forEach { key ->
        removeSync(key)
        evicted++
    }
    evictedCount.addAndGet(evicted.toLong())
    if (evicted > 0) {
        Log.d(GameDataCacheManager.TAG, "Evicted $evicted cold entries (before=$beforeSize, after=${sizeSync()})")
    }
    return evicted
}

/**
 * Force evict cold data (convenience method)
 */
internal fun GameDataCacheManager.forceEvictColdData(): Int {
    return evictColdData()
}

internal suspend fun GameDataCacheManager.performCleanup() {
    val now = System.currentTimeMillis()

    if (now - lastCleanupTime.get() >= currentConfig.cleanupIntervalMs) {
        var evicted = 0
        val expiredKeys = synchronized(memoryCache) {
            memoryCache.entries.filter { it.value.isExpired }.map { it.key }
        }
        expiredKeys.forEach { key ->
            removeSync(key)
            evicted++
        }

        while (sizeSync() > currentConfig.maxEntryCount) {
            val eldest = synchronized(memoryCache) { memoryCache.keys.firstOrNull() } ?: break
            removeSync(eldest)
            evicted++
        }
        evictedCount.addAndGet(evicted.toLong())

        lastCleanupTime.set(now)

        if (evicted > 0) {
            Log.d(GameDataCacheManager.TAG, "Cleanup completed: evicted=$evicted (expired=${expiredKeys.size})")
        }
    }

    if (now - lastStatsLogTime.get() >= GameDataCacheManager.STATS_LOG_INTERVAL_MS) {
        updateStats()
        lastStatsLogTime.set(now)
    }
}

internal fun GameDataCacheManager.updateStats() {
    val hits = hitCount.get()
    val misses = missCount.get()
    val total = hits + misses
    val hitRate = if (total > 0) hits.toDouble() / total else 0.0

    val stats = CacheStats(
        memoryHitCount = hits,
        memoryMissCount = misses,
        memorySize = 0L,
        memoryEntryCount = sizeSync(),
        memoryHitRate = hitRate,
        evictedCount = evictedCount.get(),
        memoryPressureLevel = currentPressureLevel.ordinalValue
    )

    _cacheStats.value = stats

    // Hit rate alert detection
    if (hitRate < hitRateAlertThreshold && !hitRateAlertFired && total > 10) {
        hitRateAlertFired = true
        onHitRateDroppedBelowThreshold?.invoke(hitRate.toFloat())
        Log.w(
            GameDataCacheManager.TAG,
            "Hit rate alert: hitRate=${"%.3f".format(hitRate)} below threshold=$hitRateAlertThreshold"
        )
    } else if (hitRate >= hitRateAlertThreshold) {
        hitRateAlertFired = false
    }

    Log.d(GameDataCacheManager.TAG, "Cache stats: hitRate=${"%.2f".format(hitRate)}, " +
            "entries=${sizeSync()}, " +
            "hits=$hits, misses=$misses, " +
            "evicted=${evictedCount.get()}, " +
            "pressure=${currentPressureLevel.name}, " +
            "pressureRatio=${"%.3f".format(currentSmoothedPressureRatio)}")
}

internal fun GameDataCacheManager.getStats(): CacheStats {
    updateStats()
    return _cacheStats.value
}

/**
 * Get current smoothed pressure ratio
 */
internal fun GameDataCacheManager.getCurrentPressureRatio(): Float = currentSmoothedPressureRatio

internal fun GameDataCacheManager.preloadData(dataMap: Map<CacheKey, Any>) {
    dataMap.forEach { (key, value) ->
        putWithoutTracking(key, value)
    }
    Log.i(GameDataCacheManager.TAG, "Preloaded ${dataMap.size} entries")
}

@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal fun GameDataCacheManager.warmupCache(keys: List<CacheKey>, loader: suspend (CacheKey) -> Any?) {
    scope.launch {
        keys.forEach { key ->
            try {
                val value = loader(key)
                if (value != null) {
                    putWithoutTracking(key, value)
                }
            } catch (e: CancellationException) {
                throw e // 取消穿透: 预热协程被取消时中止剩余 key, 不逐 key 误报失败
            } catch (e: Exception) {
                Log.w(GameDataCacheManager.TAG, "Failed to warmup cache for key: $key", e)
            }
        }
        Log.i(GameDataCacheManager.TAG, "Cache warmup completed for ${keys.size} keys")
    }
}

@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal fun GameDataCacheManager.shutdown() {
    Log.i(GameDataCacheManager.TAG, "Shutting down GameDataCacheManager...")

    // 1. Unregister ComponentCallbacks2
    try {
        (context.applicationContext as Application).unregisterComponentCallbacks(this)
        Log.d(GameDataCacheManager.TAG, "ComponentCallbacks2 unregistered")
    } catch (e: Exception) {
        Log.w(GameDataCacheManager.TAG, "Failed to unregister ComponentCallbacks2: ${e.message}")
    }

    // 2. Cancel background tasks
    cleanupJob?.cancel()
    cleanupJob = null
    memoryMonitorJob?.cancel()
    memoryMonitorJob = null

    // 3. Remove memory listener
    memoryManager?.removeListener(this)

    // 4. Clear bloom filter
    bloomFilter.clear()

    clearSync()

    Log.i(
        GameDataCacheManager.TAG,
        "GameDataCacheManager shutdown completed. Final stats: ${getStats().formatSummary()}"
    )
}
