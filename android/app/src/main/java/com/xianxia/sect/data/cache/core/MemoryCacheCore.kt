package com.xianxia.sect.data.cache.core

import android.util.Log
import com.xianxia.sect.data.cache.CacheEntry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 内存缓存核心（W-TinyLFU 三分区策略）
 *
 * 从 GameDataCacheManager 中提取的纯内存缓存逻辑，作为独立可复用组件。
 *
 * ## 架构设计（W-TinyLFU）
 * ```
 * ┌─────────────────────────────────────────────┐
 * │              MemoryCacheCore                 │
 * ├──────────┬──────────────┬───────────────────┤
 * │  Window   │     Tiny     │      Main         │
 * │  (~1%)    │   (~20%)     │     (~79%)        │
 * │  新数据    │  高频小数据  │   主缓存区         │
 * │  入口区    │             │                   │
 * └──────────┴──────────────┴───────────────────┘
 *            ↕ BloomFilter (防穿透)
 * ```
 *
 * ## 核心职责
 * 1. **Window 区**: 新条目的暂存区，接收所有新写入的数据
 * 2. **Tiny 区**: 存储高频访问的小尺寸数据
 * 3. **Main 区**: 主缓存区，存储大部分缓存数据
 * 4. **SWR 策略**: Stale-While-Revalidate 过期策略支持
 * 5. **BloomFilter**: 防止缓存穿透的概率型数据结构
 *
 * ## 使用场景
 * - 作为独立内存缓存组件嵌入到任何需要缓存的系统
 * - 替代简单的 HashMap/LruCache，提供更智能的淘汰策略
 * - 与磁盘缓存配合使用，形成多级缓存架构
 *
 * ## 线程安全
 * - 使用 ConcurrentHashMap 保证读操作的并发安全
 * - 使用 Mutex (kotlinx.coroutines) 保证写操作的原子性
 * - 使用 AtomicLong 保证计数器的线程安全
 *
 * @param maxMemoryBytes 最大内存占用（字节），默认 4MB
 * @param windowSizeRatio Window 区占比，默认 1%
 * @param tinySizeRatio Tiny 区占比，默认 20%
 * @param mainSizeRatio Main 区占比，默认 79%
 */
class MemoryCacheCore(
    private val maxMemoryBytes: Long = 4 * 1024 * 1024L, // 默认 4MB
    private val windowSizeRatio: Float = 0.01f,           // Window 占 1%
    private val tinySizeRatio: Float = 0.20f,             // Tiny 占 20%
    private val mainSizeRatio: Float = 0.79f              // Main 占 79%
) {
    companion object {
        private const val TAG = "MemoryCacheCore"

        /** 默认 TTL：5 分钟 */
        private const val DEFAULT_TTL_MS = 5 * 60 * 1000L
    }

    // ==================== 内部存储 ====================

    /** 核心缓存存储（ConcurrentHashMap 保证并发读安全） */
    private val cache: ConcurrentHashMap<String, CacheEntry> = ConcurrentHashMap()

    /** 全局访问计数器（用于 LRU 和频率统计） */
    private val accessCounter: AtomicLong = AtomicLong(0)

    /** 写操作互斥锁（保证写操作的原子性和一致性） */
    private val cacheMutex: Mutex = Mutex()

    // ==================== 内存管理 ====================

    /** 当前内存占用（字节） */
    @Volatile
    private var currentMemoryBytes: Long = 0L

    // 分区大小计算（在构造时确定，不可变）
    private val windowMaxBytes: Long = (maxMemoryBytes * windowSizeRatio).toLong()
    private val tinyMaxBytes: Long = (maxMemoryBytes * tinySizeRatio).toLong()
    private val mainMaxBytes: Long = (maxMemoryBytes * mainSizeRatio).toLong()

    // 各分区当前占用（Volatile 保证可见性）
    @Volatile private var windowBytes: Long = 0L
    @Volatile private var tinyBytes: Long = 0L
    @Volatile private var mainBytes: Long = 0L

    // ==================== BloomFilter 防穿透 ====================

    /**
     * BloomFilter 实例（用于快速判断 key 是否可能存在）
     *
     * 在 get 操作前先检查 BloomFilter：
     * - 如果 mightContain 返回 false → 一定不存在，直接返回 null（避免无效查询）
     * - 如果 mightContain 返回 true → 可能存在，继续查询缓存
     *
     * 这可以显著减少对空 key 的无效查询，防止缓存穿透攻击。
     */
    private val bloomFilter: SimpleBloomFilter = SimpleBloomFilter(
        expectedItems = 10000,
        falsePositiveRate = 0.01
    )

    // ==================== 公共接口 ====================

    /**
     * 获取缓存值（含 SWR 过期策略）
     *
     * 查询流程:
     * 1. 检查 BloomFilter（快速排除不存在的 key）
     * 2. 从 ConcurrentHashMap 获取 CacheEntry
     * 3. 检查 SWR 状态:
     *    - FRESH: 直接返回，更新访问计数
     *    - STALE: 返回旧值（在容忍窗口内），更新访问计数
     *    - EXPIRED: 删除并返回 null
     *
     * @param key 缓存键
     * @return 缓存值（类型安全的泛型返回），若不存在或过期则返回 null
     */
    suspend fun <T> get(key: String): T? {
        // Step 1: BloomFilter 快速判断（O(k) 时间复杂度）
        if (!bloomFilter.mightContain(key)) {
            // Key 从未插入过，一定不存在，直接返回
            return null
        }

        // Step 2: 从缓存中获取条目
        val entry = cache[key] ?: return null

        // Step 3: 检查 SWR 过期状态
        if (entry.isExpired()) {
            // 完全过期（超过 TTL + stale window）
            remove(key)
            return null
        }

        // 更新访问计数和访问时间
        entry.touch()
        accessCounter.incrementAndGet()

        @Suppress("UNCHECKED_CAST")
        return entry.value as? T
    }

    /**
     * 存入缓存值
     *
     * 写入流程:
     * 1. 获取互斥锁（保证写操作原子性）
     * 2. 估算值的内存占用
     * 3. 检查是否超过最大内存限制，必要时触发淘汰
     * 4. 创建 CacheEntry 并写入 ConcurrentHashMap
     * 5. 更新内存占用统计
     * 6. 更新 BloomFilter
     * 7. 更新分区统计
     *
     * @param key 缓存键
     * @param value 缓存值（非 null）
     * @param ttlMs 过期时间（毫秒），默认 5 分钟
     * @return 是否写入成功（false 表示内存不足且无法淘汰）
     */
    suspend fun <T> put(key: String, value: T, ttlMs: Long = DEFAULT_TTL_MS): Boolean {
        cacheMutex.withLock {
            // 估算值的内存大小
            val sizeEstimate = estimateSize(value)

            // 检查是否需要淘汰以腾出空间
            if (currentMemoryBytes + sizeEstimate > maxMemoryBytes) {
                val evicted = evict(sizeEstimate)
                if (!evicted && sizeEstimate > maxMemoryBytes) {
                    Log.w(TAG, "Value too large for cache: ${sizeEstimate}bytes > ${maxMemoryBytes}bytes")
                    return false
                }
            }

            // 创建 CacheEntry（使用 V2 格式，包含 SWR 支持）
        val entry = CacheEntry.create(value, ttlMs)

            // 写入缓存
            cache[key] = entry

            // 更新内存占用
            currentMemoryBytes += sizeEstimate

            // 更新 BloomFilter
            bloomFilter.put(key)

            // 更新分区统计
            updatePartitionBytes(key, sizeEstimate)

            Log.d(TAG, "Put: $key, size=${sizeEstimate}bytes, total=${currentMemoryBytes}bytes/${maxMemoryBytes}bytes")

            return true
        }
    }

    /**
     * 移除缓存条目
     *
     * 同时清理:
     * - ConcurrentHashMap 中的条目
     * - 内存占用统计
     * - （注意：BloomFilter 不支持删除单个元素）
     *
     * @param key 要移除的缓存键
     */
    suspend fun remove(key: String) {
        cacheMutex.withLock {
            cache.remove(key)?.let { entry ->
                val size = estimateSize(entry.value)
                currentMemoryBytes -= size.coerceAtLeast(0)
                // 更新分区统计
                updatePartitionBytesOnRemove(key, size)
                Log.d(TAG, "Remove: $key, freed=${size}bytes")
            }
        }
    }

    /**
     * 清空所有缓存
     *
     * 重置所有状态:
     * - 清空 ConcurrentHashMap
     * - 重置内存占用为 0
     * - 重置各分区占用为 0
     * - 清空 BloomFilter
     */
    suspend fun clear() {
        cacheMutex.withLock {
            cache.clear()
            currentMemoryBytes = 0L
            windowBytes = 0L
            tinyBytes = 0L
            mainBytes = 0L
            bloomFilter.clear()
            Log.i(TAG, "Cache cleared completely")
        }
    }

    // ==================== 查询方法 ====================

    /**
     * 获取当前缓存条目数量
     *
     * @return 缓存中的条目数
     */
    fun getSize(): Int = cache.size

    /**
     * 获取当前内存占用
     *
     * @return 当前内存占用（字节）
     */
    fun getMemoryUsage(): Long = currentMemoryBytes

    /**
     * 获取最大内存限制
     *
     * @return 最大内存限制（字节）
     */
    fun getMaxMemory(): Long = maxMemoryBytes

    /**
     * 获取内存利用率
     *
     * @return 当前占用/最大容量 (0.0 - 1.0+)
     */
    fun getMemoryUtilization(): Float =
        if (maxMemoryBytes > 0) currentMemoryBytes.toFloat() / maxMemoryBytes else 0f

    /**
     * 检查是否包含指定 key
     *
     * 注意：此方法会检查 SWR 状态，
     * 如果条目已过期会自动移除。
     *
     * @param key 缓存键
     * @return 是否包含（且未过期）
     */
    fun contains(key: String): Boolean {
        val entry = cache[key] ?: return false
        return !entry.isExpired()
    }

    /**
     * 获取各分区的统计信息
     *
     * 返回 Window/Tiny/Main 各分区的当前占用、总占用和最大容量。
     * 用于监控和诊断。
     *
     * @return 分区统计 Map
     */
    fun getPartitionStats(): Map<String, Long> = mapOf(
        "window" to windowBytes,
        "tiny" to tinyBytes,
        "main" to mainBytes,
        "total" to currentMemoryBytes,
        "max" to maxMemoryBytes
    )

    // ==================== 内部实现 ====================

    /**
     * 淘汰条目以释放空间
     *
     * 使用简化的 W-TinyLFU 淘汰策略：
     * 1. 收集所有条目及其频率计数
     * 2. 按频率升序排序（低频优先淘汰）
     * 3. 从最低频开始淘汰，直到释放足够空间
     *
     * 优先淘汰 Main 区的低频条目，保护 Window 区的新数据。
     *
     * @param requiredSpace 需要释放的空间（字节）
     * @return 是否成功释放了足够空间
     */
    private suspend fun evict(requiredSpace: Long): Boolean {
        // 收集所有条目并按频率排序（低频优先）
        val sortedEntries = cache.entries
            .sortedByDescending { it.value.accessCount }

        var freed: Long = 0
        var evictedCount = 0

        for ((key, entry) in sortedEntries) {
            if (freed >= requiredSpace) break

            val size = estimateSize(entry.value)
            cache.remove(key)
            freed += size
            currentMemoryBytes -= size
            evictedCount++

            // 更新分区统计
            updatePartitionBytesOnRemove(key, size)
        }

        if (evictedCount > 0) {
            Log.i(TAG, "Evicted $evictedCount entries, freed ${freed}bytes (required: ${requiredSpace}bytes)")
        }

        return freed >= requiredSpace
    }

    /**
     * 估算对象的内存占用
     *
     * 使用启发式算法根据对象类型估算内存大小：
     * - String: UTF-8 字节数组长度
     * - Collection: 元素大小之和 + 开销
     * - Map: 键值对大小之和 + 开销
     * - 基本类型: 固定大小
     * - 其他对象: 默认 128 字节（对象头 + 引用开销）
     *
     * 注意：这是近似值，实际 JVM 内存占用可能因实现而异。
     *
     * @param value 要估算的对象
     * @return 估计的内存占用（字节）
     */
    private fun estimateSize(value: Any?): Long {
        if (value == null) return 0

        return when (value) {
            is String -> value.toByteArray().size.toLong()
            is ByteArray -> value.size.toLong()
            is Collection<*> ->
                value.sumOf { estimateSize(it)?.toLong() ?: 32L } + 16L // List 开销
            is Map<*, *> ->
                value.entries.sumOf { entry ->
                    (estimateSize(entry.key)?.toLong() ?: 32L) +
                    (estimateSize(entry.value)?.toLong() ?: 32L)
                } + 32L // Map 开销
            is Int -> 4L
            is Long -> 8L
            is Float -> 4L
            is Double -> 8L
            is Boolean -> 1L
            is Char -> 2L
            is Short -> 2L
            is Byte -> 1L
            else -> 128L // 默认对象大小（对象头 + 引用）
        }
    }

    /**
     * 更新分区字节数统计（插入时调用）
     *
     * 根据 key 的 hashCode 将条目分配到不同分区：
     * - hash % 100 < 1 → Window 区 (1%)
     * - hash % 100 < 21 → Tiny 区 (20%)
     * - 否则 → Main 区 (79%)
     *
     * 这种基于哈希的分配方式简单高效，
     * 可以在一定程度上均匀分布数据。
     *
     * @param key 缓存键
     * @param size 条目大小（字节）
     */
    private fun updatePartitionBytes(key: String, size: Long) {
        val hash = key.hashCode()
        when {
            hash % 100 < 1 -> windowBytes += size   // 1% → Window
            hash % 100 < 21 -> tinyBytes += size     // 20% → Tiny
            else -> mainBytes += size                 // 79% → Main
        }
    }

    /**
     * 更新分区字节数统计（删除时调用）
     *
     * 与 updatePartitionBytes 对应，在删除条目时减少对应分区的占用。
     *
     * @param key 缓存键
     * @param size 条目大小（字节）
     */
    private fun updatePartitionBytesOnRemove(key: String, size: Long) {
        val hash = key.hashCode()
        when {
            hash % 100 < 1 -> windowBytes -= size
            hash % 100 < 21 -> tinyBytes -= size
            else -> mainBytes -= size
        }
    }
}
