@file:Suppress("DEPRECATION")

package com.xianxia.sect.data.cache

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.data.memory.DynamicMemoryManager
import com.xianxia.sect.data.memory.MemoryEventListener
import com.xianxia.sect.data.memory.MemoryPressureLevel
import com.xianxia.sect.data.memory.MemorySnapshot
import com.xianxia.sect.data.memory.DegradationStrategy
import com.xianxia.sect.core.util.CoroutineScopeProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.BitSet
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class CacheLayer @Inject constructor(
    private val cacheManager: GameDataCacheManager
) {
    suspend fun <T : Any> get(key: CacheKey, loader: suspend () -> T): T =
        cacheManager.get(key, loader)

    suspend fun <T : Any> getOrNull(key: CacheKey): T? =
        cacheManager.getOrNull(key)

    suspend fun put(key: CacheKey, value: Any?) =
        cacheManager.put(key, value)

    fun putWithoutTracking(key: CacheKey, value: Any?) =
        cacheManager.putWithoutTracking(key, value)

    fun remove(key: CacheKey) =
        cacheManager.remove(key)

    fun invalidateSlot(slot: Int) =
        cacheManager.invalidateSlot(slot)

    fun clear() =
        cacheManager.clearCache()

    fun evictColdData(): Int =
        cacheManager.evictColdData()

    fun getStats(): CacheStats =
        cacheManager.getStats()

    fun warmup(keys: List<CacheKey>, loader: suspend (CacheKey) -> Any?) =
        cacheManager.warmupCache(keys, loader)

    fun preload(dataMap: Map<CacheKey, Any>) =
        cacheManager.preloadData(dataMap)

    fun shutdown() =
        cacheManager.shutdown()
}

/**
 * 简化版 BloomFilter 实现
 *
 * 用于缓存穿透防护，从 GameDataCacheManager 中提取的独立组件。
 * 使用双重哈希（Murmur-style 简化版）减少误判率。
 *
 * ## 设计原理
 * BloomFilter 是一种空间效率很高的概率型数据结构：
 * - **插入 (put)**: 将元素通过 k 个哈希函数映射到位数组的 k 个位置，置为 1
 * - **查询 (mightContain)**: 检查 k 个位置是否都为 1，若有任一为 0 则一定不存在
 * - **特性**: 不存在误判（false negative），但可能存在误判（false positive）
 *
 * ## 适用场景
 * - 缓存穿透防护：快速判断 key 是否可能存在，避免无效查询
 * - 去重判断：大规模数据去重
 * - 爬虫 URL 去重
 *
 * ## 参数调优建议
 * - expectedItems: 预期插入数量（影响位数组大小）
 * - falsePositiveRate: 可接受的误判率（影响哈希函数数量）
 *   - 0.01 (1%): 适合大多数缓存场景
 *   - 0.001 (0.1%): 高精度要求场景
 *   - 0.05 (5%): 容忍度较高的场景
 *
 * @param expectedItems 预期插入的元素数量
 * @param falsePositiveRate 可接受的误判率 (0-1)
 */
class SimpleBloomFilter(
    private val expectedItems: Int = 10000,
    private val falsePositiveRate: Double = 0.01
) {
    private val bitSetSize: Int = calculateOptimalSize(expectedItems, falsePositiveRate)

    private val numHashFunctions: Int = calculateOptimalHashCount(bitSetSize, expectedItems)

    private val bits: BitSet = BitSet(bitSetSize)

    private val itemCount = AtomicInteger(0)

    private val lock = Any()

    fun put(item: String) {
        val hashes = computeHashes(item)
        synchronized(lock) {
            hashes.forEach { hash ->
                bits.set(toIndex(hash))
            }
            itemCount.incrementAndGet()
        }
    }

    fun mightContain(item: String): Boolean {
        if (itemCount.get() == 0) return false

        val hashes = computeHashes(item)
        synchronized(lock) {
            return hashes.all { hash ->
                bits.get(toIndex(hash))
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            bits.clear()
            itemCount.set(0)
        }
    }

    fun approximateSize(): Int = itemCount.get()

    private fun computeHashes(item: String): List<Int> {
        val baseHash = item.hashCode()
        var hash2 = baseHash xor item.reversed().hashCode()
        if (hash2 == 0) hash2 = 1

        return (0 until numHashFunctions).map { i ->
            baseHash + i * hash2
        }
    }

    private fun toIndex(hash: Int): Int = (hash and 0x7FFFFFFF) % bitSetSize

    companion object {
        private fun calculateOptimalSize(n: Int, p: Double): Int {
            require(n > 0) { "Expected items must be positive" }
            require(p in 0.0..1.0) { "False positive rate must be between 0 and 1" }

            val size = (-n * kotlin.math.ln(p) / (kotlin.math.ln(2.0).pow(2))).toInt()
            return size.coerceAtLeast(64)
        }

        private fun calculateOptimalHashCount(m: Int, n: Int): Int {
            require(m > 0) { "Bit set size must be positive" }
            require(n > 0) { "Expected items must be positive" }

            val count = (m.toDouble() / n * kotlin.math.ln(2.0)).toInt()
            return count.coerceIn(1, 8)
        }

        private fun Double.pow(exp: Int): Double {
            var result = 1.0
            repeat(exp) {
                result *= this
            }
            return result
        }
    }
}

/**
 * ## GameDataCacheManager - Simplified In-Memory Cache
 *
 * ### Architecture
 * ```
 * +-------------------------------------------------------------+
 * |                  GameDataCacheManager                       |
 * |  ConcurrentHashMap + SimpleBloomFilter                      |
 * |  (in-memory cache with key-tracking anti-penetration)       |
 * +------------------------------+------------------------------+
 * | ConcurrentHashMap<String,Any>| SimpleBloomFilter            |
 * | (core in-memory store)       | (anti-penetration)           |
 * +------------------------------+------------------------------+
 * ```
 *
 * Disk cache and write pipeline are handled by StorageEngine.
 */
@Singleton
class GameDataCacheManager @Inject constructor(
    @ApplicationContext internal val context: Context,
    @Suppress("UNUSED_PARAMETER") private val database: GameDatabase,
    //Kept for DI compatibility; disk I/O handled by StorageEngine
    private val config: CacheConfig = CacheConfig.DEFAULT,
    internal val memoryManager: DynamicMemoryManager? = null,
    private val scopeProvider: CoroutineScopeProvider
) : MemoryEventListener, ComponentCallbacks2 {
    companion object {
        internal const val TAG = "GameDataCacheManager"
        internal const val STATS_LOG_INTERVAL_MS = 300_000L
        private const val MEMORY_CHECK_INTERVAL_MS = 10_000L
    }

    // ==================== Core in-memory cache ====================

    internal val memoryCache = object : LinkedHashMap<String, CacheEntry>(
        16, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>): Boolean = false
    }







    internal val bloomFilter: SimpleBloomFilter = SimpleBloomFilter(
        expectedItems = 10000,
        falsePositiveRate = 0.01
    )

    internal val scope get() = scopeProvider.ioScope

    // ==================== Statistics counters ====================

    internal val hitCount = AtomicLong(0)
    internal val missCount = AtomicLong(0)
    internal val evictedCount = AtomicLong(0)

    // ==================== Stats StateFlow ====================

    // internal 镜像通道 backing 属性(GameDataCacheMaintenance updateStats 跨文件写)
    @Suppress("VariableNaming")
    internal val _cacheStats = MutableStateFlow(CacheStats())
    val cacheStats: StateFlow<CacheStats> = _cacheStats.asStateFlow()

    internal val lastCleanupTime = AtomicLong(System.currentTimeMillis())
    internal val lastStatsLogTime = AtomicLong(System.currentTimeMillis())

    /** Current memory pressure level */
    @Volatile
    internal var currentPressureLevel: MemoryPressureLevel = MemoryPressureLevel.LOW

    /** Dynamic config reference (supports runtime updates) */
    @Volatile
    internal var currentConfig: CacheConfig = config

    /** Smoothed pressure ratio [0.1, 1.0] */
    @Volatile
    internal var currentSmoothedPressureRatio: Float = 0.1f

    /** Hit rate alert threshold */
    var hitRateAlertThreshold: Float = 0.5f

    /** Callback when hit rate drops below threshold */
    var onHitRateDroppedBelowThreshold: ((Float) -> Unit)? = null

    /** Whether hit rate alert has been fired (debounce) */
    @Volatile
    internal var hitRateAlertFired: Boolean = false

    // ==================== Slot isolation ====================

    @Volatile

    // ==================== Coroutine Jobs ====================

    internal var cleanupJob: Job? = null
    internal var memoryMonitorJob: Job? = null

    // ==================== Initialization ====================

    init {
        // 防御兜底: 回调注册失败不阻断启动, 异常类型不可枚举
        @Suppress("TooGenericExceptionCaught")
        try {
            (context.applicationContext as Application).registerComponentCallbacks(this)
            Log.i(TAG, "ComponentCallbacks2 registered for onTrimMemory")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register ComponentCallbacks2: ${e.message}")
        }

        performStartupMemoryBudgetCheck()
        startBackgroundTasks()
        registerMemoryListener()

        Log.i(TAG, "GameDataCacheManager initialized (simplified in-memory cache)")
        Log.i(TAG, "  - ConcurrentHashMap (in-memory): ${config.memoryCacheSize / (1024 * 1024)}MB limit")
        Log.i(TAG, "  - SimpleBloomFilter (Anti-penetration)")
        if (memoryManager != null) {
            Log.i(TAG,
                "DynamicMemoryManager integration enabled - Device tier: ${memoryManager.deviceTier.displayName}")
        }
    }

    // ==================== ComponentCallbacks2 ====================

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        val ratio = smoothPressureCurve(level)
        currentSmoothedPressureRatio = ratio

        Log.i(TAG, "onTrimMemory: level=$level, smoothedRatio=${"%.3f".format(ratio)}")

        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                // No special handling needed when UI is hidden
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                scope.launch { evictColdData() }
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                scope.launch { evictByRatio(ratio) }
            }
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                scope.launch { evictByRatio(ratio) }
            }
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                scope.launch { evictColdData() }
            }
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                scope.launch { emergencyPurge() }
            }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onLowMemory() {
        scope.launch { emergencyPurge() }
    }




    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) = Unit


    // ==================== MemoryEventListener ====================

    private fun registerMemoryListener() {
        memoryManager?.addListener(this)
        updateCacheForMemoryPressure()
    }

    override fun onPressureChanged(snapshot: MemorySnapshot) {
        currentPressureLevel = snapshot.pressureLevel
        Log.d(TAG,
            "Memory pressure changed to: ${snapshot.pressureLevel.name} (${snapshot.availablePercent}% available)")
        adjustCacheForPressure(snapshot)
    }

    override fun onWarning(event: com.xianxia.sect.data.memory.MemoryWarningEvent) {
        Log.w(TAG, "Memory warning: ${event.message}, Suggested action: ${event.suggestedAction}")

        when (event.suggestedAction) {
            DegradationStrategy.REDUCE_CACHE_SIZE -> {
                reduceCacheSize(currentConfig.pressureResponseConfig.moderatePressureCacheReduction)
            }
            DegradationStrategy.EVICT_COLD_ZONE -> {
                evictColdData()
                if (currentConfig.pressureResponseConfig.enableAutoGcOnHighPressure) {
                    triggerGcIfNeeded()
                }
            }
            DegradationStrategy.EMERGENCY_PURGE -> {
                emergencyPurge()
            }
            else -> { /* Other strategies not handled here */ }
        }
    }

    override fun onDegradationApplied(strategy: DegradationStrategy, reason: String) {
        Log.w(TAG, "Degradation applied: $strategy, Reason: $reason")
    }

    // ==================== Memory pressure response ====================







    // ==================== Background tasks ====================

    private fun startBackgroundTasks() {
        cleanupJob = scope.launch {
            while (isActive) {
                delay(currentConfig.cleanupIntervalMs)
                performCleanup()
            }
        }

        memoryMonitorJob = scope.launch {
            while (isActive) {
                delay(MEMORY_CHECK_INTERVAL_MS)
                checkMemoryPressure()
            }
        }
    }


    // ==================== Config dynamic update ====================

    fun updateConfig(newConfig: CacheConfig) {
        this.currentConfig = newConfig
        Log.i(TAG, "Configuration updated: $newConfig")
        updateCacheForMemoryPressure()
    }

    // ==================== Core get methods ====================

    /**
     * Get a cache entry (suspend version)
     *
     * Lookup order: ConcurrentHashMap -> loader
     */
    suspend fun <T : Any> get(
        key: CacheKey,
        loader: suspend () -> T
    ): T {
        val cacheKey = key.toString()

        if (!bloomFilter.mightContain(cacheKey)) {
            val value = loader()
            putInternal(key, value)
            return value
        }

        val cached = getSync<T>(cacheKey)
        if (cached != null) {
            hitCount.incrementAndGet()
            return cached
        }

        missCount.incrementAndGet()
        val value = loader()
        putInternal(key, value)

        return value
    }

    /**
     * Get a cache entry without loading
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> getOrNull(key: CacheKey): T? {
        val cacheKey = key.toString()
        val cached = getSync<T>(cacheKey)
        if (cached != null) {
            hitCount.incrementAndGet()
        } else {
            missCount.incrementAndGet()
        }
        return cached
    }

    // ==================== Core put methods ====================

    /**
     * Put a cache entry (suspend version)
     */
    suspend fun put(key: CacheKey, value: Any?) {
        if (value == null) {
            remove(key)
            return
        }
        putInternal(key, value)
    }

    /**
     * Put a cache entry without tracking (fire-and-forget)
     */
    fun putWithoutTracking(key: CacheKey, value: Any?) {
        if (value == null) {
            remove(key)
            return
        }
        scope.launch {
            putInternal(key, value)
        }
    }

    private fun putInternal(key: CacheKey, value: Any) {
        val cacheKey = key.toString()
        putSync(cacheKey, value, key.ttl)
    }

    // ==================== remove / contains ====================

    fun remove(key: CacheKey) {
        val cacheKey = key.toString()
        removeSync(cacheKey)
    }

    fun contains(key: CacheKey): Boolean {
        val cacheKey = key.toString()
        return containsSync(cacheKey)
    }

    // ==================== clear / invalidate ====================

    fun clearCache(key: CacheKey? = null) {
        if (key != null) {
            removeSync(key.toString())
        } else {
            clearSync()
        }
    }

    fun invalidateSlot(slot: Int) {
        val prefix = ":$slot:"
        scope.launch {
            val keysToRemove = synchronized(memoryCache) {
                memoryCache.keys.filter { it.contains(prefix) }
            }
            keysToRemove.forEach { removeSync(it) }
            Log.i(TAG, "Invalidated all cache entries for slot $slot (${keysToRemove.size} entries)")
        }
    }

    fun clearMemoryCache() {
        clearSync()
    }

    // ==================== Periodic cleanup ====================


    // ==================== Statistics ====================




    // ==================== Preload / warmup ====================



    // ==================== Shutdown ====================

}

// ==================== Data classes ====================

/**
 * Simplified cache statistics
 */
data class CacheEntry(
    val data: Any,
    val createdAt: Long = System.currentTimeMillis(),
    val ttl: Long = CacheKey.DEFAULT_TTL
) {
    val isExpired: Boolean get() = System.currentTimeMillis() - createdAt > ttl
}

data class CacheStats(
    val memoryHitCount: Long = 0,
    val memoryMissCount: Long = 0,
    val memorySize: Long = 0,
    val memoryEntryCount: Int = 0,
    val memoryHitRate: Double = 0.0,
    val evictedCount: Long = 0,
    val memoryPressureLevel: Int = 0
) {
    val memoryMissRate: Double get() = 1.0 - memoryHitRate

    fun formatSummary(): String = buildString {
        appendLine("Cache Statistics:")
        appendLine("  Hit Rate: ${"%.2f%%".format(memoryHitRate * 100)}")
        appendLine("  Entries: $memoryEntryCount")
        appendLine("  Hits: $memoryHitCount, Misses: $memoryMissCount")
        appendLine("  Evicted: $evictedCount")
        appendLine("  Pressure Level: $memoryPressureLevel")
    }
}
