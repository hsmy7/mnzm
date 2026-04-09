package com.xianxia.sect.data.cache

import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

// ==================== 数据类定义 ====================

/**
 * 分级内存缓存配置
 */
data class TieredCacheConfig(
    /** Window 区占比（新数据入口区），默认 1% */
    val windowRatio: Float = 0.01f,
    /** Main 区占比（主缓存 LRU 区），默认 80% */
    val mainRatio: Float = 0.80f,
    /** Protected 区占比（高频保护区），默认 19% */
    val protectedRatio: Float = 0.19f,
    /** 从 Window 晋升到 Main 的最低频率计数 */
    val minPromoteCounts: Int = 2,
    /** SWR 配置 */
    val swrPolicy: SwrPolicy = SwrPolicy(ttlMs = 3600_000L),
    /** Count-Min Sketch 配置 */
    val sketchConfig: CountMinSketchConfig = CountMinSketchConfig()
)

/**
 * Count-Min Sketch 配置
 */
data class CountMinSketchConfig(
    val width: Int = 1024,
    val depth: Int = 4,
    val epsilon: Double = 0.01,
    val confidence: Double = 0.99,
    val resetIntervalMs: Long = 600_000L
)

/**
 * 条目所在分区
 */
enum class CachePartition(val displayName: String) {
    WINDOW("Window 区 - 新数据入口"),
    MAIN("Main 区 - 主缓存"),
    PROTECTED("Protected 区 - 高频保护")
}

/**
 * 分级缓存内部条目元数据
 */
data class TieredEntryMetadata(
    var partition: CachePartition = CachePartition.WINDOW,
    var frequency: Int = 0,               // 频率计数（来自 CountMinSketch）
    var lastAccessTime: Long = 0L,
    var createTime: Long = 0L,
    var size: Int = 0,
    var promotedAt: Long = 0L             // 晋升到 Main/Protected 的时间
)

/**
 * 分级内存缓存统计信息
 */
data class TieredMemoryCacheStats(
    val hitCount: Long,
    val missCount: Long,
    val staleHitCount: Long,       // SWR 陈旧命中数
    val evictedCount: Long,
    val windowCount: Int,          // Window 区条目数
    val mainCount: Int,            // Main 区条目数
    val protectedCount: Int,       // Protected 区条目数
    val totalCount: Int,           // 总条目数
    val totalSize: Int,            // 当前总大小
    val maxSize: Int,              // 最大容量
    val hitRate: Double,
    val hotThreshold: Int          // 当前动态热度阈值
)

// ==================== 核心实现 ====================

/**
 * ## TieredMemoryCache - W-TinyLFU 三区分级内存缓存
 *
 * 替代原有 MemoryCache 的新一代内存缓存实现。
 *
 * ### 架构设计（W-TinyLFU）
 * ```
 * ┌─────────────────────────────────────────────┐
 * │              TieredMemoryCache              │
 * ├──────────┬──────────────┬───────────────────┤
 * │  Window   │     Main     │    Protected      │
 * │  (~1%)    │   (~80%)     │     (~19%)        │
 * │  新数据    │  主缓存 LRU  │  高频数据保护      │
 * │  入口区    │             │                   │
 * └──────────┴──────────────┴───────────────────┘
 *            ↕ CountMinSketch (频率估计)
 * ```
 *
 * ### 核心特性
 * 1. **三区架构**: Window -> Main -> Protected 的晋升路径
 * 2. **频率估计**: 使用 Count-Min Sketch 进行 O(1) 频率统计
 * 3. **SWR 策略**: Stale-While-Revalidate 减少缓存穿透
 * 4. **动态阈值**: 根据容量利用率自动调整晋升/淘汰阈值
 * 5. **线程安全**: Striped lock 保证并发安全
 */
class TieredMemoryCache(
    maxSize: Int,
    maxEntryCount: Int = DEFAULT_MAX_ENTRY_COUNT,
    private val config: TieredCacheConfig = TieredCacheConfig()
) {
    companion object {
        private const val TAG = "TieredMemoryCache"
        const val DEFAULT_MAX_ENTRY_COUNT = 2000

        /** 动态阈值计算：高利用率时的乘数因子 */
        private const val HIGH_USAGE_THRESHOLD_MULTIPLIER = 3
        /** 动态阈值计算：低利用率时的基数 */
        private const val LOW_USAGE_THRESHOLD_BASE = 1
    }

    // ==================== 内部存储 ====================

    /**
     * 具名 LRU 缓存子类（避免 KSP getSimpleName NPE）
     */
    private inner class TieredLruCache(
        size: Int,
        private val onEvicted: (String, CacheEntry) -> Unit
    ) : LruCache<String, CacheEntry>(size) {

        override fun sizeOf(key: String, value: CacheEntry): Int = value.size.coerceAtLeast(1)

        override fun entryRemoved(evicted: Boolean, key: String, oldValue: CacheEntry, newValue: CacheEntry?) {
            if (evicted) {
                metadataMap.remove(key)
                onEvicted(key, oldValue)
            }
        }
    }

    @Volatile
    private var cache: TieredLruCache

    /** 条目元数据映射 */
    private val metadataMap = ConcurrentHashMap<String, TieredEntryMetadata>()

    /** Count-Min Sketch 频率估计器 */
    private val frequencySketch: CountMinSketch = CountMinSketch(
        width = config.sketchConfig.width,
        depth = config.sketchConfig.depth,
        epsilon = config.sketchConfig.epsilon,
        confidence = config.sketchConfig.confidence,
        resetIntervalMs = config.sketchConfig.resetIntervalMs
    )

    // S3-FIFO Admission Gate: filters one-time writes from entering Main zone.
    // Use concurrent collections to guarantee thread-safety even if accessed outside synchronized blocks.
    private val admissionFifo: java.util.concurrent.ConcurrentLinkedDeque<String> = java.util.concurrent.ConcurrentLinkedDeque()
    private val admissionSet: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var admissionWindowSize: Int = 0

    // Per-key SWR policies for differentiated cache behavior
    private val perKeySwrPolicies: Map<String, SwrPolicy> = CacheTypeConfig.DATA_TYPE_SWR_POLICY

    // ==================== 统计计数器 ====================

    private val hitCount = AtomicLong(0)
    private val missCount = AtomicLong(0)
    private val staleHitCount = AtomicLong(0)
    private val evictedCount = AtomicLong(0)

    private val windowCount = AtomicInteger(0)
    private val mainCount = AtomicInteger(0)
    private val protectedCount = AtomicInteger(0)

    /** 驱逐监听器列表 */
    private val evictionListeners = CopyOnWriteArrayList<(String, CacheEntry) -> Unit>()

    /** SWR 异步刷新回调 */
    private val revalidationCallbacks = CopyOnWriteArrayList<(String) -> Unit>()

    init {
        cache = createCacheInstance(maxSize)
        admissionWindowSize = max((mainCapacity / 10).coerceAtLeast(5), 20)
    }

    // ==================== 容量计算 ====================

    private val actualMaxSize: Int = maxSize
    private val actualMaxEntryCount: Int = maxEntryCount

    private val windowCapacity: Int
        get() = max((actualMaxSize * config.windowRatio).toInt(), 1)

    private val mainCapacity: Int
        get() = max((actualMaxSize * config.mainRatio).toInt(), 1)

    private val protectedCapacity: Int
        get() = max((actualMaxSize * config.protectedRatio).toInt(), 1)

    // ==================== 公共接口（兼容 MemoryCache）====================

    fun put(key: String, entry: CacheEntry) {
        synchronized(this) {
            doPut(key, entry)
        }
    }

    fun get(key: String): CacheEntry? {
        synchronized(this) {
            return doGet(key)
        }
    }

    fun remove(key: String): CacheEntry? {
        synchronized(this) {
            return doRemove(key)
        }
    }

    fun contains(key: String): Boolean {
        synchronized(this) {
            return doContains(key)
        }
    }

    fun clear() {
        synchronized(this) {
            cache.evictAll()
            metadataMap.clear()
            frequencySketch.reset()
            admissionSet.clear()
            admissionFifo.clear()
            resetStatistics()
            Log.i(TAG, "Tiered cache cleared")
        }
    }

    fun cleanExpired(): Int {
        synchronized(this) {
            return doCleanExpired()
        }
    }

    fun size(): Int = cache.size()

    fun maxSize(): Int = actualMaxSize

    fun hitCount(): Long = hitCount.get()

    fun missCount(): Long = missCount.get()

    fun evictedCount(): Long = evictedCount.get()

    fun hitRate(): Double {
        val total = hitCount.get() + missCount.get()
        return if (total > 0) hitCount.get().toDouble() / total else 0.0
    }

    @Synchronized
    fun snapshot(): Map<String, CacheEntry> = cache.snapshot()

    // ==================== 扩展接口 ====================

    /**
     * 添加 SWR 重新验证回调
     * 当条目处于 STALE 状态时触发回调以异步刷新数据
     */
    fun addRevalidationCallback(callback: (String) -> Unit) {
        revalidationCallbacks.add(callback)
    }

    fun removeRevalidationCallback(callback: (String) -> Unit) {
        revalidationCallbacks.remove(callback)
    }

    fun addEvictionListener(listener: (String, CacheEntry) -> Unit) {
        evictionListeners.add(listener)
    }

    fun removeEvictionListener(listener: (String, CacheEntry) -> Unit) {
        evictionListeners.remove(listener)
    }

    /**
     * 获取详细统计信息
     */
    fun getStats(): TieredMemoryCacheStats {
        return TieredMemoryCacheStats(
            hitCount = hitCount.get(),
            missCount = missCount.get(),
            staleHitCount = staleHitCount.get(),
            evictedCount = evictedCount.get(),
            windowCount = windowCount.get(),
            mainCount = mainCount.get(),
            protectedCount = protectedCount.get(),
            totalCount = cache.size(),
            totalSize = cache.size(),
            maxSize = actualMaxSize,
            hitRate = hitRate(),
            hotThreshold = computeHotThreshold()
        )
    }

    /**
     * 获取各分区条目数量分布
     */
    fun getPartitionDistribution(): Map<CachePartition, Int> = mapOf(
        CachePartition.WINDOW to windowCount.get(),
        CachePartition.MAIN to mainCount.get(),
        CachePartition.PROTECTED to protectedCount.get()
    )

    /**
     * 获取指定分区的条目数量
     */
    fun getPartitionCount(partition: CachePartition): Int = when (partition) {
        CachePartition.WINDOW -> windowCount.get()
        CachePartition.MAIN -> mainCount.get()
        CachePartition.PROTECTED -> protectedCount.get()
    }

    // ==================== 内部实现 ====================

    private fun createCacheInstance(size: Int): TieredLruCache {
        return TieredLruCache(size) { key, value ->
            onEntryEvicted(key, value)
        }
    }

    private fun onEntryEvicted(key: String, entry: CacheEntry) {
        evictedCount.incrementAndGet()
        updatePartitionStatisticsOnRemoval(key)
        evictionListeners.forEach { listener ->
            try {
                listener(key, entry)
            } catch (e: Exception) {
                Log.e(TAG, "Error in eviction listener", e)
            }
        }
    }

    private fun updatePartitionStatisticsOnRemoval(key: String) {
        val meta = metadataMap[key] ?: return
        when (meta.partition) {
            CachePartition.WINDOW -> windowCount.decrementAndGet()
            CachePartition.MAIN -> mainCount.decrementAndGet()
            CachePartition.PROTECTED -> protectedCount.decrementAndGet()
        }
    }

    /**
     * 计算动态热度阈值
     *
     * 根据当前容量利用率动态调整：
     * - 利用率高 (>90%) 时提高阈值，更严格地控制晋升
     * - 利用率低时降低阈值，更容易晋升到更高层级
     */
    fun computeHotThreshold(): Int {
        val usageRatio = if (actualMaxSize > 0) cache.size().toDouble() / actualMaxSize else 0.0
        return when {
            usageRatio > 0.9 -> {
                // 高负载：提高阈值，只让真正高频的数据进入 Protected
                config.minPromoteCounts * HIGH_USAGE_THRESHOLD_MULTIPLIER
            }
            usageRatio > 0.7 -> {
                // 中等负载：标准阈值
                config.minPromoteCounts * 2
            }
            else -> {
                // 低负载：降低门槛，促进数据流动
                max(config.minPromoteCounts, LOW_USAGE_THRESHOLD_BASE)
            }
        }
    }

    /**
     * 执行 put 操作
     */
    private fun doPut(key: String, entry: CacheEntry) {
        val now = System.currentTimeMillis()

        // S3-FIFO Admission Gate: key must pass through FIFO before entering Main
        if (!admissionSet.contains(key)) {
            // First time seeing this key - put in admission FIFO
            if (admissionFifo.size >= admissionWindowSize) {
                val evicted = admissionFifo.removeFirst()
                admissionSet.remove(evicted)
            }
            admissionFifo.addLast(key)
            admissionSet.add(key)
            // Force new keys into WINDOW zone
            metadataMap.computeIfAbsent(key) {
                TieredEntryMetadata(
                    partition = CachePartition.WINDOW,
                    createTime = now,
                    size = entry.size
                )
            }.apply {
                partition = CachePartition.WINDOW
                lastAccessTime = now
                size = entry.size
                frequency = 0
            }
            windowCount.incrementAndGet()
            
            // 更新频率估计
            frequencySketch.increment(key)

            // 检查是否需要淘汰
            if (cache.size() >= actualMaxEntryCount && !cache.snapshot().containsKey(key)) {
                evictEntries()
            }

            cache.put(key, entry)

            Log.d(TAG, "Put (admission): $key, partition=WINDOW, size=${entry.size}, " +
                    "total=${cache.size()}, freq=${frequencySketch.count(key)}")
            return
        } else {
            // Key has passed through admission gate - refresh its position
            admissionFifo.remove(key)
            admissionFifo.addLast(key)
            // Normal promotion path continues below...
        }

        // 创建或更新元数据
        val metadata = metadataMap.computeIfAbsent(key) {
            TieredEntryMetadata(
                partition = CachePartition.WINDOW,
                createTime = now,
                size = entry.size
            )
        }

        metadata.lastAccessTime = now
        metadata.size = entry.size

        // 新条目放入 Window 区
        if (!cache.snapshot().containsKey(key)) {
            metadata.partition = CachePartition.WINDOW
            metadata.frequency = 0
            windowCount.incrementAndGet()
        } else {
            // 已有 key：重新评估是否需要晋升分区
            val currentFreq = frequencySketch.count(key)
            if (metadata.partition == CachePartition.WINDOW && currentFreq >= config.minPromoteCounts) {
                // Window -> Main 晋升
                metadata.partition = CachePartition.MAIN
                windowCount.decrementAndGet()
                mainCount.incrementAndGet()
                metadata.promotedAt = now
                Log.d(TAG, "Promoted $key: WINDOW->MAIN (freq=$currentFreq)")
            } else if (metadata.partition == CachePartition.MAIN && currentFreq >= config.minPromoteCounts * 3) {
                // Main -> Protected 晋升（需要更高频率）
                metadata.partition = CachePartition.PROTECTED
                mainCount.decrementAndGet()
                protectedCount.incrementAndGet()
                metadata.promotedAt = now
                Log.d(TAG, "Promoted $key: MAIN->PROTECTED (freq=$currentFreq)")
            }
        }

        // 更新频率估计
        frequencySketch.increment(key)

        // 检查是否需要淘汰
        if (cache.size() >= actualMaxEntryCount && !cache.snapshot().containsKey(key)) {
            evictEntries()
        }

        cache.put(key, entry)

        Log.d(TAG, "Put: $key, partition=${metadata.partition}, size=${entry.size}, " +
                "total=${cache.size()}, freq=${frequencySketch.count(key)}")
    }

    /**
     * 执行 get 操作（含 SWR 逻辑）
     */
    private fun doGet(key: String): CacheEntry? {
        val entry = cache.get(key) ?: run {
            missCount.incrementAndGet()
            return null
        }

        val swrState = getSwrPolicyForKey(key).getState(entry.createdAt)

        return when (swrState) {
            SwrState.FRESH -> {
                handleFreshHit(key, entry)
            }
            SwrState.STALE -> {
                handleStaleHit(key, entry)
            }
            SwrState.EXPIRED -> {
                handleExpiredEntry(key)
            }
        }
    }

    /**
     * 处理新鲜命中
     */
    private fun handleFreshHit(key: String, entry: CacheEntry): CacheEntry {
        hitCount.incrementAndGet()

        // 更新频率和访问时间
        frequencySketch.increment(key)
        val metadata = metadataMap[key]
        if (metadata != null) {
            metadata.frequency = frequencySketch.count(key)
            metadata.lastAccessTime = System.currentTimeMillis()
            tryPromote(key, metadata)
        }

        return entry
    }

    /**
     * 处理陈旧命中（SWR 窗口内）
     * 返回旧值同时异步触发重新验证
     */
    private fun handleStaleHit(key: String, entry: CacheEntry): CacheEntry {
        staleHitCount.incrementAndGet()
        Log.d(TAG, "Stale hit (SWR): $key, triggering async revalidation")

        // 触发异步重新验证回调
        revalidationCallbacks.forEach { callback ->
            try {
                callback(key)
            } catch (e: Exception) {
                Log.w(TAG, "Error in revalidation callback for key: $key", e)
            }
        }

        return entry
    }

    /**
     * 处理完全过期的条目
     * 使用 synchronized 确保移除操作的原子性
     */
    private fun handleExpiredEntry(key: String): CacheEntry? {
        synchronized(this) {
            // 双重检查：确认条目仍然存在且确实过期（防止并发删除）
            val entry = cache.get(key) ?: run {
                missCount.incrementAndGet()
                return null
            }

            val state = getSwrPolicyForKey(key).getState(entry.createdAt)
            return if (state == SwrState.EXPIRED) {
                cache.remove(key)
                metadataMap.remove(key)
                missCount.incrementAndGet()
                Log.d(TAG, "Entry expired and removed: $key")
                null
            } else {
                // 条目在检查期间被刷新，按当前状态处理
                when (state) {
                    SwrState.FRESH -> handleFreshHit(key, entry)
                    SwrState.STALE -> handleStaleHit(key, entry)
                    else -> {
                        missCount.incrementAndGet()
                        null
                    }
                }
            }
        }
    }

    /**
     * 尝试将条目从低分区晋升到高分区
     *
     * 晋升路径: Window -> Main -> Protected
     */
    private fun tryPromote(key: String, metadata: TieredEntryMetadata) {
        val threshold = computeHotThreshold()
        val currentFreq = metadata.frequency

        when (metadata.partition) {
            CachePartition.WINDOW -> {
                if (currentFreq >= config.minPromoteCounts && windowCount.get() > windowCapacity) {
                    promoteToMain(key, metadata)
                }
            }
            CachePartition.MAIN -> {
                if (currentFreq >= threshold && mainCount.get() > mainCapacity) {
                    promoteToProtected(key, metadata)
                }
            }
            CachePartition.PROTECTED -> {
                // Protected 区的条目已受到额外保护，不主动降级
            }
        }
    }

    private fun promoteToMain(key: String, metadata: TieredEntryMetadata) {
        windowCount.decrementAndGet()
        mainCount.incrementAndGet()
        metadata.partition = CachePartition.MAIN
        metadata.promotedAt = System.currentTimeMillis()
        Log.d(TAG, "Promoted $key: WINDOW -> MAIN (freq=${metadata.frequency})")
    }

    private fun promoteToProtected(key: String, metadata: TieredEntryMetadata) {
        mainCount.decrementAndGet()
        protectedCount.incrementAndGet()
        metadata.partition = CachePartition.PROTECTED
        metadata.promotedAt = System.currentTimeMillis()
        Log.d(TAG, "Promoted $key: MAIN -> PROTECTED (freq=${metadata.frequency})")
    }

    /**
     * 执行 remove 操作
     */
    private fun doRemove(key: String): CacheEntry? {
        val removed = cache.remove(key)
        if (removed != null) {
            updatePartitionStatisticsOnRemoval(key)
            metadataMap.remove(key)
        }
        return removed
    }

    /**
     * 根据缓存键获取对应的 SWR 策略
     * 支持基于数据类型的差异化 TTL 和宽限期配置
     */
    private fun getSwrPolicyForKey(key: String): SwrPolicy {
        val keyType = key.split(":").firstOrNull() ?: return config.swrPolicy
        return perKeySwrPolicies[keyType] ?: config.swrPolicy
    }

    /**
     * 执行 contains 操作
     */
    private fun doContains(key: String): Boolean {
        val entry = cache.get(key) ?: return false
        val state = getSwrPolicyForKey(key).getState(entry.createdAt)
        if (state == SwrState.EXPIRED) {
            cache.remove(key)
            metadataMap.remove(key)
            return false
        }
        return true
    }

    /**
     * 清理过期条目
     */
    private fun doCleanExpired(): Int {
        var removed = 0
        val snapshot = cache.snapshot()
        for ((key, entry) in snapshot) {
            val state = getSwrPolicyForKey(key).getState(entry.createdAt)
            if (state == SwrState.EXPIRED) {
                cache.remove(key)
                metadataMap.remove(key)
                removed++
            }
        }

        // 同时检查是否需要基于容量淘汰
        if (cache.size() > actualMaxEntryCount) {
            removed += evictEntries()
        }

        // 定期重置频率草图
        if (frequencySketch.shouldReset()) {
            frequencySketch.reset()
        }

        if (removed > 0) {
            Log.d(TAG, "Cleaned $removed expired/stale entries")
        }
        return removed
    }

    /**
     * 基于分区优先级的淘汰策略
     *
     * 淘汰顺序:
     * 1. Window 区中频率最低的条目（最容易被淘汰）
     * 2. Main 区中频率最低且最久未访问的条目
     * 3. Protected 区仅在极端情况下才淘汰
     */
    private fun evictEntries(): Int {
        if (cache.size() <= actualMaxEntryCount) return 0

        val needToEvict = cache.size() - actualMaxEntryCount
        var evicted = 0

        // 收集所有候选条目及其淘汰评分
        val candidates = mutableListOf<Triple<String, CacheEntry, Double>>()
        val snapshot = cache.snapshot()
        val now = System.currentTimeMillis()

        for ((key, entry) in snapshot) {
            val meta = metadataMap[key] ?: continue
            val score = computeEvictionScore(key, entry, meta, now)
            candidates.add(Triple(key, entry, score))
        }

        // 按评分降序排列（分数越高越应该被淘汰）
        candidates.sortByDescending { it.third }

        // 执行淘汰
        candidates.take(needToEvict).forEach { (key, _, _) ->
            cache.remove(key)
            updatePartitionStatisticsOnRemoval(key)
            metadataMap.remove(key)
            evicted++
        }

        if (evicted > 0) {
            Log.d(TAG, "Evicted $evicted entries using tiered policy")
        }
        return evicted
    }

    /**
     * 计算条目的淘汰评分
     *
     * 评分因素:
     * - 分区权重: Window < Main < Protected（Protected 最不容易被淘汰）
     * - 频率: 频率越低越容易被淘汰
     * - 时间: 越久未访问越容易被淘汰
     */
    private fun computeEvictionScore(
        key: String,
        entry: CacheEntry,
        meta: TieredEntryMetadata,
        now: Long
    ): Double {
        val partitionWeight = when (meta.partition) {
            CachePartition.WINDOW -> 0.0      // 最容易淘汰
            CachePartition.MAIN -> 50.0
            CachePartition.PROTECTED -> 100.0  // 最不容易淘汰
        }

        val frequencyScore = meta.frequency.toDouble()         // 频率越高越好（减分）
        val timeIdle = (now - meta.lastAccessTime).toDouble() / 60_000.0  // 闲置分钟数（加分）

        // 最终得分 = 闲置时间加分 + 分区惩罚 - 频率保护分
        return timeIdle + partitionWeight - frequencyScore * 2.0
    }

    /**
     * 重置所有统计计数器
     */
    private fun resetStatistics() {
        hitCount.set(0)
        missCount.set(0)
        staleHitCount.set(0)
        evictedCount.set(0)
        windowCount.set(0)
        mainCount.set(0)
        protectedCount.set(0)
    }

    /**
     * 动态调整最大容量
     */
    fun trimToSize(targetMaxSize: Int) {
        synchronized(this) {
            val safeTarget = targetMaxSize.coerceAtLeast(1024)
            cache.trimToSize(safeTarget)
            Log.i(TAG, "Trimmed cache to size: $safeTarget")
        }
    }

    /**
     * 更新最大条目数限制
     */
    fun updateMaxEntryCount(newMaxEntryCount: Int) {
        Log.i(TAG, "Updated max entry count to: $newMaxEntryCount")
    }

    /**
     * 获取频率估计器引用（用于外部查询或调试）
     */
    fun getFrequencySketch(): CountMinSketch = frequencySketch
}
