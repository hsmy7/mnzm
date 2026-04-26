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
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap
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
    @ApplicationContext private val context: Context,
    @Suppress("UNUSED_PARAMETER") private val database: GameDatabase, // Kept for DI compatibility; disk I/O handled by StorageEngine
    private val config: CacheConfig = CacheConfig.DEFAULT,
    private val memoryManager: DynamicMemoryManager? = null
) : MemoryEventListener, ComponentCallbacks2 {
    companion object {
        private const val TAG = "GameDataCacheManager"
        private const val CLEANUP_INTERVAL_MS = 30_000L
        private const val STATS_LOG_INTERVAL_MS = 300_000L
        private const val MEMORY_CHECK_INTERVAL_MS = 10_000L
    }

    // ==================== Core in-memory cache ====================

    private val memoryCache = object : LinkedHashMap<String, CacheEntry>(
        16, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>): Boolean {
            return size > currentConfig.maxEntryCount
        }
    }

    @Synchronized
    fun <T : Any> getSync(key: String): T? {
        val entry = memoryCache[key] ?: return null
        if (entry.isExpired) {
            memoryCache.remove(key)
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return entry.data as? T
    }

    @Synchronized
    fun putSync(key: String, value: Any, ttl: Long = CacheKey.DEFAULT_TTL) {
        memoryCache[key] = CacheEntry(value, System.currentTimeMillis(), ttl)
        bloomFilter.put(key)
        while (memoryCache.size > currentConfig.maxEntryCount) {
            val eldest = memoryCache.keys.firstOrNull() ?: break
            memoryCache.remove(eldest)
            evictedCount.incrementAndGet()
        }
    }

    @Synchronized
    fun removeSync(key: String) {
        memoryCache.remove(key)
    }

    @Synchronized
    fun containsSync(key: String): Boolean {
        val entry = memoryCache[key] ?: return false
        if (entry.isExpired) {
            memoryCache.remove(key)
            return false
        }
        return true
    }

    @Synchronized
    fun clearSync() {
        memoryCache.clear()
    }

    @Synchronized
    fun sizeSync(): Int = memoryCache.size

    private val bloomFilter: SimpleBloomFilter = SimpleBloomFilter(
        expectedItems = 10000,
        falsePositiveRate = 0.01
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ==================== Statistics counters ====================

    private val hitCount = AtomicLong(0)
    private val missCount = AtomicLong(0)
    private val evictedCount = AtomicLong(0)

    // ==================== Stats StateFlow ====================

    private val _cacheStats = MutableStateFlow(CacheStats())
    val cacheStats: StateFlow<CacheStats> = _cacheStats.asStateFlow()

    private val lastCleanupTime = AtomicLong(System.currentTimeMillis())
    private val lastStatsLogTime = AtomicLong(System.currentTimeMillis())
    private var lastMemoryCheckTime = AtomicLong(System.currentTimeMillis())

    /** Current memory pressure level */
    @Volatile
    private var currentPressureLevel: MemoryPressureLevel = MemoryPressureLevel.LOW

    /** Dynamic config reference (supports runtime updates) */
    @Volatile
    private var currentConfig: CacheConfig = config

    /** Smoothed pressure ratio [0.1, 1.0] */
    @Volatile
    private var currentSmoothedPressureRatio: Float = 0.1f

    /** Hit rate alert threshold */
    var hitRateAlertThreshold: Float = 0.5f

    /** Callback when hit rate drops below threshold */
    var onHitRateDroppedBelowThreshold: ((Float) -> Unit)? = null

    /** Whether hit rate alert has been fired (debounce) */
    @Volatile
    private var hitRateAlertFired: Boolean = false

    // ==================== Slot isolation ====================

    @Volatile
    private var slotIsolationEnabled: Boolean = true

    // ==================== Coroutine Jobs ====================

    private var cleanupJob: Job? = null
    private var memoryMonitorJob: Job? = null

    // ==================== Initialization ====================

    init {
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
            Log.i(TAG, "DynamicMemoryManager integration enabled - Device tier: ${memoryManager.deviceTier.displayName}")
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

    @Deprecated("Deprecated in ComponentCallbacks2")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onLowMemory() {
        scope.launch { emergencyPurge() }
    }

    private fun evictByRatio(ratio: Float) {
        val entries = synchronized(memoryCache) { memoryCache.keys.toList() }
        val toRemove = entries.take((entries.size * (1 - ratio)).toInt())
        toRemove.forEach { removeSync(it) }
        evictedCount.addAndGet(toRemove.size.toLong())
        if (toRemove.isNotEmpty()) {
            Log.d(TAG, "Evicted ${toRemove.size} entries (ratio=$ratio)")
        }
    }

    private fun emergencyPurge() {
        Log.e(TAG, "Emergency purge triggered!")
        val beforeSize = sizeSync()
        clearSync()
        evictedCount.addAndGet(beforeSize.toLong())
        memoryManager?.forceGcAndWait()
        Log.e(TAG, "Emergency purge completed: cleared $beforeSize entries")
    }

    /**
     * Smoothed sigmoid curve mapping trimLevel to reduction ratio
     */
    @Suppress("DEPRECATION")
    private fun smoothPressureCurve(trimLevel: Int): Float {
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

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}

    /**
     * Startup memory budget check
     */
    private fun performStartupMemoryBudgetCheck() {
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                ?: run {
                    Log.i(TAG, "Startup memory budget check skipped: ActivityManager unavailable")
                    return
                }

            val memInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)

            val totalMemMb = memInfo.totalMem / (1024.0 * 1024)
            val availMemMb = memInfo.availMem / (1024.0 * 1024)
            val lowMemoryThreshold = memInfo.threshold / (1024.0 * 1024)

            Log.i(TAG, "Startup memory budget: total=${"%.0f".format(totalMemMb)}MB, " +
                    "avail=${"%.0f".format(availMemMb)}MB, " +
                    "lowThreshold=${"%.0f".format(lowMemoryThreshold)}MB, " +
                    "lowMemory=${memInfo.lowMemory}")

            val adjustedConfig = when {
                availMemMb < 512 -> {
                    Log.w(TAG, "Low memory device detected (${"%.0f".format(availMemMb)}MB available), reducing cache limit")
                    currentConfig.copy(memoryCacheSize = 2 * 1024 * 1024L)
                }
                availMemMb < 1024 -> {
                    Log.w(TAG, "Medium-low memory device (${"%.0f".format(availMemMb)}MB available), reducing cache limit")
                    currentConfig.copy(memoryCacheSize = 3 * 1024 * 1024L)
                }
                else -> {
                    Log.i(TAG, "Sufficient memory available (${"%.0f".format(availMemMb)}MB)")
                    null
                }
            }

            if (adjustedConfig != null) {
                this.currentConfig = adjustedConfig
                Log.i(TAG, "Cache config adjusted for low-memory device: ${adjustedConfig.memoryCacheSize / (1024 * 1024)}MB")
            }

            if (memInfo.lowMemory) {
                Log.w(TAG, "System reports low memory status, performing preemptive cleanup")
                scope.launch { evictColdData() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Startup memory budget check failed, using default config", e)
        }
    }

    // ==================== MemoryEventListener ====================

    private fun registerMemoryListener() {
        memoryManager?.addListener(this)
        updateCacheForMemoryPressure()
    }

    override fun onPressureChanged(snapshot: MemorySnapshot) {
        currentPressureLevel = snapshot.pressureLevel
        Log.d(TAG, "Memory pressure changed to: ${snapshot.pressureLevel.name} (${snapshot.availablePercent}% available)")
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

    private fun adjustCacheForPressure(snapshot: MemorySnapshot) {
        val pressureOrdinal = snapshot.pressureLevel.ordinalValue
        val adjustedEntries = currentConfig.getAdjustedMaxEntryCount(pressureOrdinal)

        Log.d(TAG, "Adjusting cache for pressure ${snapshot.pressureLevel.name}: maxEntries=$adjustedEntries")

        while (sizeSync() > adjustedEntries) {
            val key = synchronized(memoryCache) { memoryCache.keys.firstOrNull() } ?: break
            removeSync(key)
            evictedCount.incrementAndGet()
        }
    }

    private fun reduceCacheSize(ratio: Float) {
        Log.w(TAG, "Reducing cache size by ratio: $ratio")
        val targetSize = (sizeSync() * ratio).toInt()
        while (sizeSync() > targetSize) {
            val key = synchronized(memoryCache) { memoryCache.keys.firstOrNull() } ?: break
            removeSync(key)
            evictedCount.incrementAndGet()
        }
    }

    /**
     * Evict cold data (public interface, called by ProactiveMemoryGuard)
     */
    fun evictColdData(): Int {
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
            Log.d(TAG, "Evicted $evicted cold entries (before=$beforeSize, after=${sizeSync()})")
        }
        return evicted
    }

    /**
     * Force evict cold data (convenience method)
     */
    fun forceEvictColdData(): Int {
        return evictColdData()
    }

    private fun triggerGcIfNeeded() {
        if (currentConfig.pressureResponseConfig.enableAutoGcOnHighPressure) {
            scope.launch {
                try {
                    val freed = memoryManager?.forceGcAndWait() ?: 0L
                    if (freed < 0) {
                        Log.d(TAG, "GC freed ${-freed / 1024} KB")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to trigger GC", e)
                }
            }
        }
    }

    private fun updateCacheForMemoryPressure() {
        memoryManager?.let { manager ->
            val snapshot = manager.getMemorySnapshot()
            currentPressureLevel = snapshot.pressureLevel
            adjustCacheForPressure(snapshot)
        }
    }

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

    private fun checkMemoryPressure() {
        if (memoryManager == null) return

        try {
            val snapshot = memoryManager.getMemorySnapshot()
            val previousPressure = currentPressureLevel
            currentPressureLevel = snapshot.pressureLevel

            if (snapshot.pressureLevel.ordinalValue > previousPressure.ordinalValue) {
                Log.w(TAG, "Memory pressure increased: ${previousPressure.name} -> ${snapshot.pressureLevel.name}")
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
            Log.e(TAG, "Error checking memory pressure", e)
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

    private suspend fun performCleanup() {
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
                Log.d(TAG, "Cleanup completed: evicted=$evicted (expired=${expiredKeys.size})")
            }
        }

        if (now - lastStatsLogTime.get() >= STATS_LOG_INTERVAL_MS) {
            updateStats()
            lastStatsLogTime.set(now)
        }
    }

    // ==================== Statistics ====================

    private fun updateStats() {
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
            Log.w(TAG, "Hit rate alert: hitRate=${"%.3f".format(hitRate)} below threshold=$hitRateAlertThreshold")
        } else if (hitRate >= hitRateAlertThreshold) {
            hitRateAlertFired = false
        }

        Log.d(TAG, "Cache stats: hitRate=${"%.2f".format(hitRate)}, " +
                "entries=${memoryCache.size}, " +
                "hits=$hits, misses=$misses, " +
                "evicted=${evictedCount.get()}, " +
                "pressure=${currentPressureLevel.name}, " +
                "pressureRatio=${"%.3f".format(currentSmoothedPressureRatio)}")
    }

    fun getStats(): CacheStats {
        updateStats()
        return _cacheStats.value
    }

    /**
     * Get current smoothed pressure ratio
     */
    fun getCurrentPressureRatio(): Float = currentSmoothedPressureRatio

    // ==================== Preload / warmup ====================

    fun preloadData(dataMap: Map<CacheKey, Any>) {
        dataMap.forEach { (key, value) ->
            putWithoutTracking(key, value)
        }
        Log.i(TAG, "Preloaded ${dataMap.size} entries")
    }

    fun warmupCache(keys: List<CacheKey>, loader: suspend (CacheKey) -> Any?) {
        scope.launch {
            keys.forEach { key ->
                try {
                    val value = loader(key)
                    if (value != null) {
                        putWithoutTracking(key, value)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to warmup cache for key: $key", e)
                }
            }
            Log.i(TAG, "Cache warmup completed for ${keys.size} keys")
        }
    }

    // ==================== Shutdown ====================

    fun shutdown() {
        Log.i(TAG, "Shutting down GameDataCacheManager...")

        // 1. Unregister ComponentCallbacks2
        try {
            (context.applicationContext as Application).unregisterComponentCallbacks(this)
            Log.d(TAG, "ComponentCallbacks2 unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister ComponentCallbacks2: ${e.message}")
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

        // 5. Clear in-memory cache
        memoryCache.clear()

        // 6. Cancel all coroutines
        scope.cancel()

        Log.i(TAG, "GameDataCacheManager shutdown completed. Final stats: ${getStats().formatSummary()}")
    }
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
