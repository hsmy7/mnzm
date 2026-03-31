@file:OptIn(InternalSerializationApi::class)
package com.xianxia.sect.data.cache

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.serialization.unified.UnifiedSerializationEngine
import com.xianxia.sect.data.serialization.unified.SerializationContext as UnifiedSerializationContext
import com.xianxia.sect.data.serialization.unified.SerializationFormat
import com.xianxia.sect.data.serialization.unified.CompressionType
import com.xianxia.sect.data.serialization.unified.DataType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import java.lang.ref.SoftReference
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

object TieredCacheConfig {
    private var _l1MaxSize: Long = 16 * 1024 * 1024L
    private var _l2MaxSize: Long = 64 * 1024 * 1024L
    private var _l3MaxSize: Long = 256 * 1024 * 1024L
    
    private var _l1MaxEntries: Int = 500
    private var _l2MaxEntries: Int = 2000
    private var _l3MaxEntries: Int = 10000
    
    const val CLEANUP_INTERVAL_MS = 60_000L
    const val STATS_UPDATE_INTERVAL_MS = 10_000L
    const val DECAY_INTERVAL_MS = 300_000L
    const val DECAY_FACTOR = 0.95
    const val PROMOTE_THRESHOLD = 5
    const val DEMOTE_THRESHOLD = 0.1
    
    val l1MaxSize: Long
        get() {
            if (_l1MaxSize == 16 * 1024 * 1024L) {
                _l1MaxSize = calculateL1MaxSize()
            }
            return _l1MaxSize
        }
    
    val l2MaxSize: Long
        get() {
            if (_l2MaxSize == 64 * 1024 * 1024L) {
                _l2MaxSize = calculateL2MaxSize()
            }
            return _l2MaxSize
        }
    
    val l3MaxSize: Long
        get() {
            if (_l3MaxSize == 256 * 1024 * 1024L) {
                _l3MaxSize = calculateL3MaxSize()
            }
            return _l3MaxSize
        }
    
    val l1MaxEntries: Int
        get() {
            if (_l1MaxEntries == 500) {
                _l1MaxEntries = calculateL1MaxEntries()
            }
            return _l1MaxEntries
        }
    
    val l2MaxEntries: Int
        get() {
            if (_l2MaxEntries == 2000) {
                _l2MaxEntries = calculateL2MaxEntries()
            }
            return _l2MaxEntries
        }
    
    val l3MaxEntries: Int
        get() {
            if (_l3MaxEntries == 10000) {
                _l3MaxEntries = calculateL3MaxEntries()
            }
            return _l3MaxEntries
        }
    
    private fun calculateL1MaxSize(): Long {
        val maxMemory = Runtime.getRuntime().maxMemory()
        val ratio = when {
            maxMemory < 128 * 1024 * 1024 -> 0.0625
            maxMemory < 256 * 1024 * 1024 -> 0.08
            maxMemory < 512 * 1024 * 1024 -> 0.10
            else -> 0.125
        }
        return (maxMemory * ratio).toLong().coerceIn(4L * 1024 * 1024, 64L * 1024 * 1024)
    }
    
    private fun calculateL2MaxSize(): Long {
        val maxMemory = Runtime.getRuntime().maxMemory()
        val ratio = when {
            maxMemory < 128 * 1024 * 1024 -> 0.125
            maxMemory < 256 * 1024 * 1024 -> 0.15
            maxMemory < 512 * 1024 * 1024 -> 0.20
            else -> 0.25
        }
        return (maxMemory * ratio).toLong().coerceIn(16L * 1024 * 1024, 128L * 1024 * 1024)
    }
    
    private fun calculateL3MaxSize(): Long {
        val maxMemory = Runtime.getRuntime().maxMemory()
        val ratio = when {
            maxMemory < 128 * 1024 * 1024 -> 0.25
            maxMemory < 256 * 1024 * 1024 -> 0.30
            maxMemory < 512 * 1024 * 1024 -> 0.35
            else -> 0.50
        }
        return (maxMemory * ratio).toLong().coerceIn(64L * 1024 * 1024, 512L * 1024 * 1024)
    }
    
    private fun calculateL1MaxEntries(): Int {
        val maxMemory = Runtime.getRuntime().maxMemory()
        val base = when {
            maxMemory < 128 * 1024 * 1024 -> 200
            maxMemory < 256 * 1024 * 1024 -> 400
            maxMemory < 512 * 1024 * 1024 -> 600
            else -> 800
        }
        return base.coerceIn(100, 1000)
    }
    
    private fun calculateL2MaxEntries(): Int {
        val maxMemory = Runtime.getRuntime().maxMemory()
        val base = when {
            maxMemory < 128 * 1024 * 1024 -> 1000
            maxMemory < 256 * 1024 * 1024 -> 2000
            maxMemory < 512 * 1024 * 1024 -> 3000
            else -> 4000
        }
        return base.coerceIn(500, 4000)
    }
    
    private fun calculateL3MaxEntries(): Int {
        val maxMemory = Runtime.getRuntime().maxMemory()
        val base = when {
            maxMemory < 128 * 1024 * 1024 -> 4000
            maxMemory < 256 * 1024 * 1024 -> 8000
            maxMemory < 512 * 1024 * 1024 -> 12000
            else -> 16000
        }
        return base.coerceIn(2000, 20000)
    }
    
    val cleanupIntervalMs: Long get() = CLEANUP_INTERVAL_MS
    val statsUpdateIntervalMs: Long get() = STATS_UPDATE_INTERVAL_MS
    val decayIntervalMs: Long get() = DECAY_INTERVAL_MS
    val decayFactor: Double get() = DECAY_FACTOR
    val promoteThreshold: Int get() = PROMOTE_THRESHOLD
    val demoteThreshold: Double get() = DEMOTE_THRESHOLD
}

enum class CacheLevel {
    L1_HOT,
    L2_WARM,
    L3_COLD
}

enum class CachePriority {
    CRITICAL,
    HIGH,
    NORMAL,
    LOW,
    BACKGROUND
}

enum class EvictionPolicy {
    LRU,
    LFU,
    LFU_WITH_DECAY,
    ARC
}

data class CacheEntryMetadata(
    val key: String,
    val size: Int,
    val createdAt: Long,
    var lastAccessedAt: Long,
    var accessCount: Int,
    var frequency: Double = 1.0,
    val ttl: Long = Long.MAX_VALUE,
    var isDirty: Boolean = false
) {
    val isExpired: Boolean
        get() = System.currentTimeMillis() - createdAt > ttl
    
    val age: Long
        get() = System.currentTimeMillis() - createdAt
    
    fun touch() {
        lastAccessedAt = System.currentTimeMillis()
        accessCount++
    }
    
    fun decay(factor: Double) {
        frequency *= factor
    }
}

data class TieredCacheStats(
    val l1HitCount: Long = 0,
    val l1MissCount: Long = 0,
    val l2HitCount: Long = 0,
    val l2MissCount: Long = 0,
    val l3HitCount: Long = 0,
    val l3MissCount: Long = 0,
    val l1Size: Long = 0,
    val l2Size: Long = 0,
    val l3Size: Long = 0,
    val l1Entries: Int = 0,
    val l2Entries: Int = 0,
    val l3Entries: Int = 0,
    val evictionCount: Long = 0,
    val promotionCount: Long = 0,
    val demotionCount: Long = 0
) {
    val totalHitCount: Long get() = l1HitCount + l2HitCount + l3HitCount
    val totalMissCount: Long get() = l1MissCount + l2MissCount + l3MissCount
    val totalRequests: Long get() = totalHitCount + totalMissCount
    
    val l1HitRate: Float get() = if (l1HitCount + l1MissCount > 0) l1HitCount.toFloat() / (l1HitCount + l1MissCount) else 0f
    val l2HitRate: Float get() = if (l2HitCount + l2MissCount > 0) l2HitCount.toFloat() / (l2HitCount + l2MissCount) else 0f
    val overallHitRate: Float get() = if (totalRequests > 0) totalHitCount.toFloat() / totalRequests else 0f
    
    val totalSize: Long get() = l1Size + l2Size + l3Size
    val totalEntries: Int get() = l1Entries + l2Entries + l3Entries
}

class TieredCacheSystem(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "TieredCacheSystem"
    }
    
    private val l1HotCache: L1HotCache
    private val l2WarmCache: L2WarmCache
    private val l3ColdCache: L3ColdCache
    
    private val l1HitCount = AtomicLong(0)
    private val l1MissCount = AtomicLong(0)
    private val l2HitCount = AtomicLong(0)
    private val l2MissCount = AtomicLong(0)
    private val l3HitCount = AtomicLong(0)
    private val l3MissCount = AtomicLong(0)
    private val evictionCount = AtomicLong(0)
    private val promotionCount = AtomicLong(0)
    private val demotionCount = AtomicLong(0)
    
    private val _stats = MutableStateFlow(TieredCacheStats())
    val stats: StateFlow<TieredCacheStats> = _stats.asStateFlow()
    
    private val accessPattern = AccessPatternTracker()
    
    private var cleanupJob: Job? = null
    private var statsJob: Job? = null
    private var decayJob: Job? = null
    private var isShuttingDown = false
    
    init {
        l1HotCache = L1HotCache(TieredCacheConfig.l1MaxSize, TieredCacheConfig.l1MaxEntries)
        l2WarmCache = L2WarmCache(TieredCacheConfig.l2MaxSize, TieredCacheConfig.l2MaxEntries)
        l3ColdCache = L3ColdCache(context, TieredCacheConfig.l3MaxSize, TieredCacheConfig.l3MaxEntries)
        startBackgroundTasks()
    }
    
    private fun startBackgroundTasks() {
        cleanupJob = scope.launch {
            while (isActive && !isShuttingDown) {
                delay(TieredCacheConfig.cleanupIntervalMs)
                performCleanup()
            }
        }
        
        statsJob = scope.launch {
            while (isActive && !isShuttingDown) {
                delay(TieredCacheConfig.statsUpdateIntervalMs)
                updateStats()
            }
        }
        
        decayJob = scope.launch {
            while (isActive && !isShuttingDown) {
                delay(TieredCacheConfig.decayIntervalMs)
                performDecay()
            }
        }
    }
    
    suspend fun <T : Any> get(key: CacheKey): T? {
        val keyStr = key.toString()
        
        l1HotCache.get(keyStr)?.let { entry ->
            l1HitCount.incrementAndGet()
            accessPattern.recordAccess(keyStr, CacheLevel.L1_HOT)
            @Suppress("UNCHECKED_CAST")
            return entry as T
        }
        
        l2WarmCache.get(keyStr)?.let { entry ->
            l2HitCount.incrementAndGet()
            accessPattern.recordAccess(keyStr, CacheLevel.L2_WARM)
            
            if (shouldPromote(keyStr)) {
                promoteToL1(keyStr, entry)
            }
            
            @Suppress("UNCHECKED_CAST")
            return entry as T
        }
        
        l3ColdCache.get(keyStr)?.let { entry ->
            l3HitCount.incrementAndGet()
            accessPattern.recordAccess(keyStr, CacheLevel.L3_COLD)
            
            if (shouldPromote(keyStr)) {
                promoteToL2(keyStr, entry)
            }
            
            @Suppress("UNCHECKED_CAST")
            return entry as T
        }
        
        l1MissCount.incrementAndGet()
        l2MissCount.incrementAndGet()
        l3MissCount.incrementAndGet()
        
        return null
    }
    
    fun <T : Any> getSync(key: CacheKey): T? {
        val keyStr = key.toString()
        
        l1HotCache.getSync(keyStr)?.let { entry ->
            l1HitCount.incrementAndGet()
            @Suppress("UNCHECKED_CAST")
            return entry as T
        }
        
        l2WarmCache.getSync(keyStr)?.let { entry ->
            l2HitCount.incrementAndGet()
            @Suppress("UNCHECKED_CAST")
            return entry as T
        }
        
        return null
    }
    
    suspend fun <T : Any> getOrLoad(key: CacheKey, loader: suspend () -> T): T {
        return get(key) ?: run {
            val value = loader()
            put(key, value)
            value
        }
    }
    
    fun put(key: CacheKey, value: Any, ttl: Long = Long.MAX_VALUE) {
        val keyStr = key.toString()
        val size = estimateSize(value)
        
        val metadata = CacheEntryMetadata(
            key = keyStr,
            size = size,
            createdAt = System.currentTimeMillis(),
            lastAccessedAt = System.currentTimeMillis(),
            accessCount = 1,
            ttl = ttl
        )
        
        val level = determineCacheLevel(key, value, size)
        
        when (level) {
            CacheLevel.L1_HOT -> {
                l1HotCache.put(keyStr, value, metadata)
                l2WarmCache.remove(keyStr)
                l3ColdCache.remove(keyStr)
            }
            CacheLevel.L2_WARM -> {
                l2WarmCache.put(keyStr, value, metadata)
                l3ColdCache.remove(keyStr)
            }
            CacheLevel.L3_COLD -> {
                l3ColdCache.put(keyStr, value, metadata)
            }
        }
        
        accessPattern.recordAccess(keyStr, level)
    }
    
    fun putAll(entries: Map<CacheKey, Any>) {
        entries.forEach { (key, value) -> put(key, value) }
    }
    
    fun remove(key: CacheKey) {
        val keyStr = key.toString()
        l1HotCache.remove(keyStr)
        l2WarmCache.remove(keyStr)
        l3ColdCache.remove(keyStr)
        accessPattern.remove(keyStr)
    }
    
    fun contains(key: CacheKey): Boolean {
        val keyStr = key.toString()
        return l1HotCache.contains(keyStr) || 
               l2WarmCache.contains(keyStr) || 
               l3ColdCache.contains(keyStr)
    }
    
    fun markDirty(key: CacheKey) {
        val keyStr = key.toString()
        l1HotCache.markDirty(keyStr)
        l2WarmCache.markDirty(keyStr)
    }
    
    fun clear() {
        l1HotCache.clear()
        l2WarmCache.clear()
        l3ColdCache.clear()
        accessPattern.clear()
        
        l1HitCount.set(0)
        l1MissCount.set(0)
        l2HitCount.set(0)
        l2MissCount.set(0)
        l3HitCount.set(0)
        l3MissCount.set(0)
        evictionCount.set(0)
        promotionCount.set(0)
        demotionCount.set(0)
    }
    
    fun invalidateSlot(slot: Int) {
        val pattern = "_$slot"
        
        l1HotCache.invalidatePattern(pattern)
        l2WarmCache.invalidatePattern(pattern)
        l3ColdCache.invalidatePattern(pattern)
    }
    
    private fun determineCacheLevel(key: CacheKey, value: Any, size: Int): CacheLevel {
        val priority = getDataPriority(key.type)
        
        return when {
            priority == CachePriority.CRITICAL -> CacheLevel.L1_HOT
            priority == CachePriority.HIGH && size < 1024 -> CacheLevel.L1_HOT
            priority == CachePriority.HIGH -> CacheLevel.L2_WARM
            priority == CachePriority.NORMAL -> CacheLevel.L2_WARM
            priority == CachePriority.LOW -> CacheLevel.L3_COLD
            priority == CachePriority.BACKGROUND -> CacheLevel.L3_COLD
            else -> CacheLevel.L2_WARM
        }
    }
    
    private fun getDataPriority(type: String): CachePriority {
        return when (type) {
            CacheKey.TYPE_GAME_DATA -> CachePriority.CRITICAL
            CacheKey.TYPE_DISCIPLE -> CachePriority.HIGH
            CacheKey.TYPE_EQUIPMENT -> CachePriority.NORMAL
            CacheKey.TYPE_MANUAL -> CachePriority.NORMAL
            CacheKey.TYPE_PILL -> CachePriority.LOW
            CacheKey.TYPE_MATERIAL -> CachePriority.LOW
            CacheKey.TYPE_HERB -> CachePriority.LOW
            CacheKey.TYPE_SEED -> CachePriority.LOW
            CacheKey.TYPE_BATTLE_LOG -> CachePriority.BACKGROUND
            CacheKey.TYPE_EVENT -> CachePriority.BACKGROUND
            else -> CachePriority.NORMAL
        }
    }
    
    private fun shouldPromote(key: String): Boolean {
        val accessInfo = accessPattern.getAccessInfo(key)
        return accessInfo != null && accessInfo.recentAccessCount >= TieredCacheConfig.promoteThreshold
    }
    
    private fun promoteToL1(key: String, value: Any) {
        val metadata = l2WarmCache.getMetadata(key) ?: return
        
        if (l1HotCache.put(key, value, metadata)) {
            l2WarmCache.remove(key)
            promotionCount.incrementAndGet()
            Log.d(TAG, "Promoted $key to L1")
        }
    }
    
    private fun promoteToL2(key: String, value: Any) {
        val metadata = l3ColdCache.getMetadata(key) ?: return
        
        if (l2WarmCache.put(key, value, metadata)) {
            l3ColdCache.remove(key)
            promotionCount.incrementAndGet()
            Log.d(TAG, "Promoted $key to L2")
        }
    }
    
    private fun performCleanup() {
        val expiredL1 = l1HotCache.cleanExpired()
        val expiredL2 = l2WarmCache.cleanExpired()
        val expiredL3 = l3ColdCache.cleanExpired()
        
        val totalExpired = expiredL1 + expiredL2 + expiredL3
        if (totalExpired > 0) {
            evictionCount.addAndGet(totalExpired.toLong())
            Log.d(TAG, "Cleaned up $totalExpired expired entries")
        }
    }
    
    private fun performDecay() {
        l1HotCache.applyDecay(TieredCacheConfig.decayFactor)
        l2WarmCache.applyDecay(TieredCacheConfig.decayFactor)
    }
    
    private fun updateStats() {
        _stats.value = TieredCacheStats(
            l1HitCount = l1HitCount.get(),
            l1MissCount = l1MissCount.get(),
            l2HitCount = l2HitCount.get(),
            l2MissCount = l2MissCount.get(),
            l3HitCount = l3HitCount.get(),
            l3MissCount = l3MissCount.get(),
            l1Size = l1HotCache.size(),
            l2Size = l2WarmCache.size(),
            l3Size = l3ColdCache.size(),
            l1Entries = l1HotCache.entryCount(),
            l2Entries = l2WarmCache.entryCount(),
            l3Entries = l3ColdCache.entryCount(),
            evictionCount = evictionCount.get(),
            promotionCount = promotionCount.get(),
            demotionCount = demotionCount.get()
        )
    }
    
    private fun estimateSize(value: Any): Int {
        return when (value) {
            is String -> value.toByteArray(Charsets.UTF_8).size
            is ByteArray -> value.size
            is List<*> -> value.size * 64
            is Map<*, *> -> value.size * 128
            else -> 256
        }
    }
    
    fun getStats(): TieredCacheStats {
        updateStats()
        return _stats.value
    }
    
    fun onLowMemory() {
        Log.w(TAG, "Low memory detected, evicting non-critical cache entries")
        
        val evictedL1 = l1HotCache.evictNonCritical()
        val evictedL2 = l2WarmCache.evictNonCritical()
        
        evictionCount.addAndGet((evictedL1 + evictedL2).toLong())
        
        Log.i(TAG, "Evicted ${evictedL1 + evictedL2} entries due to low memory")
    }
    
    fun shutdown() {
        isShuttingDown = true
        
        cleanupJob?.cancel()
        statsJob?.cancel()
        decayJob?.cancel()
        
        runBlocking {
            l3ColdCache.flush()
        }
        
        l1HotCache.clear()
        l2WarmCache.clear()
        l3ColdCache.shutdown()
        
        scope.cancel()
        Log.i(TAG, "TieredCacheSystem shutdown completed")
    }
}

class L1HotCache(
    private val maxSize: Long,
    private val maxEntries: Int
) {
    private val cache = ConcurrentHashMap<String, Any>()
    private val metadata = ConcurrentHashMap<String, CacheEntryMetadata>()
    private val accessQueue = ConcurrentLinkedDeque<String>()
    private val lock = ReentrantReadWriteLock()
    
    private val totalSize = AtomicLong(0)
    
    fun get(key: String): Any? {
        return lock.read {
            val entry = cache[key]
            if (entry != null) {
                metadata[key]?.touch()
                accessQueue.addFirst(key)
            }
            entry
        }
    }
    
    fun getSync(key: String): Any? {
        val entry = cache[key]
        if (entry != null) {
            metadata[key]?.touch()
        }
        return entry
    }
    
    fun put(key: String, value: Any, meta: CacheEntryMetadata): Boolean {
        return lock.write {
            ensureCapacity(meta.size)
            
            val existing = cache.put(key, value)
            metadata[key] = meta
            
            if (existing != null) {
                val oldMeta = metadata[key]
                if (oldMeta != null) {
                    totalSize.addAndGet(-oldMeta.size.toLong())
                }
            }
            
            totalSize.addAndGet(meta.size.toLong())
            accessQueue.addFirst(key)
            
            true
        }
    }
    
    fun remove(key: String): Boolean {
        return lock.write {
            val entry = cache.remove(key)
            val meta = metadata.remove(key)
            
            if (entry != null && meta != null) {
                totalSize.addAndGet(-meta.size.toLong())
                accessQueue.remove(key)
                true
            } else {
                false
            }
        }
    }
    
    fun contains(key: String): Boolean = cache.containsKey(key)
    
    fun markDirty(key: String) {
        metadata[key]?.isDirty = true
    }
    
    fun clear() {
        lock.write {
            cache.clear()
            metadata.clear()
            accessQueue.clear()
            totalSize.set(0)
        }
    }
    
    fun size(): Long = totalSize.get()
    
    fun entryCount(): Int = cache.size
    
    fun getMetadata(key: String): CacheEntryMetadata? = metadata[key]
    
    fun cleanExpired(): Int {
        var count = 0
        val now = System.currentTimeMillis()
        
        lock.write {
            val toRemove = metadata.entries
                .filter { now - it.value.createdAt > it.value.ttl }
                .map { it.key }
            
            toRemove.forEach { key ->
                remove(key)
                count++
            }
        }
        
        return count
    }
    
    fun applyDecay(factor: Double) {
        lock.write {
            metadata.values.forEach { it.decay(factor) }
        }
    }
    
    fun evictNonCritical(): Int {
        var count = 0
        
        lock.write {
            val toEvict = metadata.entries
                .sortedBy { it.value.frequency }
                .take((maxEntries * 0.3).toInt())
            
            toEvict.forEach { (key, _) ->
                remove(key)
                count++
            }
        }
        
        return count
    }
    
    fun invalidatePattern(pattern: String) {
        lock.write {
            val toRemove = cache.keys.filter { it.contains(pattern) }
            toRemove.forEach { remove(it) }
        }
    }
    
    private fun ensureCapacity(requiredSize: Int) {
        while (totalSize.get() + requiredSize > maxSize || cache.size >= maxEntries) {
            val key = accessQueue.pollLast() ?: break
            remove(key)
        }
    }
}

class L2WarmCache(
    private val maxSize: Long,
    private val maxEntries: Int
) {
    private val cache = ConcurrentHashMap<String, SoftReference<Any>>()
    private val metadata = ConcurrentHashMap<String, CacheEntryMetadata>()
    private val lock = ReentrantReadWriteLock()
    
    private val totalSize = AtomicLong(0)
    
    fun get(key: String): Any? {
        return lock.read {
            val ref = cache[key]
            val entry = ref?.get()
            
            if (entry != null) {
                metadata[key]?.touch()
            } else if (ref != null) {
                cache.remove(key)
                metadata.remove(key)
            }
            
            entry
        }
    }
    
    fun getSync(key: String): Any? {
        val ref = cache[key]
        return ref?.get()
    }
    
    fun put(key: String, value: Any, meta: CacheEntryMetadata): Boolean {
        return lock.write {
            ensureCapacity(meta.size)
            
            val existing = cache.put(key, SoftReference(value))
            metadata[key] = meta
            
            if (existing != null) {
                val oldMeta = metadata[key]
                if (oldMeta != null) {
                    totalSize.addAndGet(-oldMeta.size.toLong())
                }
            }
            
            totalSize.addAndGet(meta.size.toLong())
            true
        }
    }
    
    fun remove(key: String): Boolean {
        return lock.write {
            val entry = cache.remove(key)
            val meta = metadata.remove(key)
            
            if (entry != null && meta != null) {
                totalSize.addAndGet(-meta.size.toLong())
                true
            } else {
                false
            }
        }
    }
    
    fun contains(key: String): Boolean = cache[key]?.get() != null
    
    fun markDirty(key: String) {
        metadata[key]?.isDirty = true
    }
    
    fun clear() {
        lock.write {
            cache.clear()
            metadata.clear()
            totalSize.set(0)
        }
    }
    
    fun size(): Long = totalSize.get()
    
    fun entryCount(): Int = cache.size
    
    fun getMetadata(key: String): CacheEntryMetadata? = metadata[key]
    
    fun cleanExpired(): Int {
        var count = 0
        val now = System.currentTimeMillis()
        
        lock.write {
            val toRemove = metadata.entries
                .filter { now - it.value.createdAt > it.value.ttl }
                .map { it.key }
            
            toRemove.forEach { key ->
                remove(key)
                count++
            }
        }
        
        return count
    }
    
    fun applyDecay(factor: Double) {
        lock.write {
            metadata.values.forEach { it.decay(factor) }
        }
    }
    
    fun evictNonCritical(): Int {
        var count = 0
        
        lock.write {
            val toEvict = metadata.entries
                .sortedBy { it.value.frequency }
                .take((maxEntries * 0.5).toInt())
            
            toEvict.forEach { (key, _) ->
                remove(key)
                count++
            }
        }
        
        return count
    }
    
    fun invalidatePattern(pattern: String) {
        lock.write {
            val toRemove = cache.keys.filter { it.contains(pattern) }
            toRemove.forEach { remove(it) }
        }
    }
    
    private fun ensureCapacity(requiredSize: Int) {
        while (totalSize.get() + requiredSize > maxSize || cache.size >= maxEntries) {
            val key = metadata.entries.minByOrNull { it.value.frequency }?.key ?: break
            remove(key)
        }
    }
}

class L3ColdCache(
    private val context: Context,
    private val maxSize: Long,
    private val maxEntries: Int
) {
    private val cache = ConcurrentHashMap<String, WeakReference<Any>>()
    private val metadata = ConcurrentHashMap<String, CacheEntryMetadata>()
    private val diskCache = DiskCacheLayer(context)
    
    private val totalSize = AtomicLong(0)
    
    fun get(key: String): Any? {
        val ref = cache[key]
        val entry = ref?.get()
        
        if (entry != null) {
            metadata[key]?.touch()
            return entry
        }
        
        val diskEntry = diskCache.get(key)
        if (diskEntry != null) {
            cache[key] = WeakReference(diskEntry)
            return diskEntry
        }
        
        return null
    }
    
    fun put(key: String, value: Any, meta: CacheEntryMetadata): Boolean {
        cache[key] = WeakReference(value)
        metadata[key] = meta
        
        diskCache.put(key, value, meta.ttl)
        totalSize.addAndGet(meta.size.toLong())
        
        return true
    }
    
    fun remove(key: String): Boolean {
        cache.remove(key)
        metadata.remove(key)
        diskCache.remove(key)
        return true
    }
    
    fun contains(key: String): Boolean = cache[key]?.get() != null || diskCache.contains(key)
    
    fun clear() {
        cache.clear()
        metadata.clear()
        diskCache.clear()
        totalSize.set(0)
    }
    
    fun size(): Long = totalSize.get()
    
    fun entryCount(): Int = cache.size + diskCache.entryCount()
    
    fun getMetadata(key: String): CacheEntryMetadata? = metadata[key]
    
    fun cleanExpired(): Int {
        val now = System.currentTimeMillis()
        var count = 0
        
        val toRemove = metadata.entries
            .filter { now - it.value.createdAt > it.value.ttl }
            .map { it.key }
        
        toRemove.forEach { key ->
            remove(key)
            count++
        }
        
        diskCache.cleanupExpired()
        
        return count
    }
    
    fun invalidatePattern(pattern: String) {
        val toRemove = cache.keys.filter { it.contains(pattern) }
        toRemove.forEach { remove(it) }
        
        diskCache.invalidatePattern(pattern)
    }
    
    fun flush() {
        diskCache.flush()
    }
    
    fun shutdown() {
        flush()
        diskCache.shutdown()
    }
}

class DiskCacheLayer(private val context: Context) {
    private val cacheDir = context.cacheDir.resolve("tiered_cache_l3")
    private val index = ConcurrentHashMap<String, DiskCacheEntry>()
    private val diskLock = ReentrantReadWriteLock()
    
    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }
    
    private val lz4Compressor = net.jpountz.lz4.LZ4Factory.fastestInstance().fastCompressor()
    private val lz4Decompressor = net.jpountz.lz4.LZ4Factory.fastestInstance().fastDecompressor()
    
    companion object {
        private const val MAGIC: Short = 0x4C33
        private const val VERSION: Byte = 1
    }
    
    init {
        if (!cacheDir.exists()) cacheDir.mkdirs()
        loadIndex()
    }
    
    data class DiskCacheEntry(
        val key: String,
        val file: java.io.File,
        val createdAt: Long,
        val ttl: Long,
        val size: Long,
        val typeName: String = ""
    )
    
    fun put(key: String, value: Any, ttl: Long) {
        diskLock.write {
            try {
                val safeKey = key.replace(":", "_").replace("/", "_")
                val file = cacheDir.resolve(safeKey)
                val bytes = serialize(value)
                file.writeBytes(bytes)
                
                index[key] = DiskCacheEntry(
                    key = key,
                    file = file,
                    createdAt = System.currentTimeMillis(),
                    ttl = ttl,
                    size = bytes.size.toLong(),
                    typeName = value::class.qualifiedName ?: ""
                )
                
                saveIndexInternal()
            } catch (e: Exception) {
                Log.e("DiskCacheLayer", "Failed to put cache entry: $key", e)
            }
        }
    }
    
    fun get(key: String): Any? {
        return diskLock.read {
            val entry = index[key] ?: return@read null
            
            if (System.currentTimeMillis() - entry.createdAt > entry.ttl) {
                removeInternal(key)
                return@read null
            }
            
            try {
                val bytes = entry.file.readBytes()
                deserialize(bytes, entry.typeName)
            } catch (e: Exception) {
                null
            }
        }
    }
    
    fun remove(key: String) {
        diskLock.write {
            removeInternal(key)
        }
    }
    
    private fun removeInternal(key: String) {
        val entry = index.remove(key)
        entry?.file?.delete()
    }
    
    fun contains(key: String): Boolean {
        return diskLock.read {
            val entry = index[key] ?: return@read false
            System.currentTimeMillis() - entry.createdAt <= entry.ttl
        }
    }
    
    fun entryCount(): Int = index.size
    
    fun clear() {
        diskLock.write {
            index.values.forEach { it.file.delete() }
            index.clear()
            saveIndexInternal()
        }
    }
    
    fun invalidatePattern(pattern: String) {
        diskLock.write {
            val toRemove = index.keys.filter { it.contains(pattern) }
            toRemove.forEach { removeInternal(it) }
        }
    }
    
    fun cleanupExpired() {
        diskLock.write {
            val now = System.currentTimeMillis()
            val expired = index.entries.filter { now - it.value.createdAt > it.value.ttl }
            expired.forEach { removeInternal(it.key) }
        }
    }
    
    fun flush() {
        diskLock.write {
            saveIndexInternal()
        }
    }
    
    fun shutdown() {
        diskLock.write {
            saveIndexInternal()
        }
    }
    
    private fun loadIndex() {
        val indexFile = cacheDir.resolve("index")
        if (indexFile.exists()) {
            try {
                indexFile.readLines().forEach { line ->
                    val parts = line.split("|")
                    if (parts.size >= 5) {
                        val key = parts[0]
                        val file = cacheDir.resolve(parts[1])
                        if (file.exists()) {
                            index[key] = DiskCacheEntry(
                                key = key,
                                file = file,
                                createdAt = parts[2].toLong(),
                                ttl = parts[3].toLong(),
                                size = parts[4].toLongOrNull() ?: file.length(),
                                typeName = if (parts.size > 5) parts[5] else ""
                            )
                        }
                    } else if (parts.size >= 4) {
                        val key = parts[0]
                        val file = cacheDir.resolve(parts[1])
                        if (file.exists()) {
                            index[key] = DiskCacheEntry(
                                key = key,
                                file = file,
                                createdAt = parts[2].toLong(),
                                ttl = parts[3].toLong(),
                                size = file.length(),
                                typeName = ""
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("DiskCacheLayer", "Failed to load index", e)
            }
        }
    }
    
    private fun saveIndexInternal() {
        val indexFile = cacheDir.resolve("index")
        val lines = index.values.map { entry ->
            "${entry.key}|${entry.file.name}|${entry.createdAt}|${entry.ttl}|${entry.size}|${entry.typeName}"
        }
        indexFile.writeText(lines.joinToString("\n"))
    }
    
    private fun serialize(value: Any): ByteArray {
        val typeName = value::class.qualifiedName ?: "Unknown"
        val typeNameBytes = typeName.toByteArray(Charsets.UTF_8)
        
        val jsonStr = try {
            @Suppress("UNCHECKED_CAST")
            val serializer = value::class.serializer() as KSerializer<Any>
            json.encodeToString(serializer, value)
        } catch (e: Exception) {
            value.toString()
        }
        
        val jsonBytes = jsonStr.toByteArray(Charsets.UTF_8)
        
        val maxCompressed = lz4Compressor.maxCompressedLength(jsonBytes.size)
        val compressed = ByteArray(maxCompressed)
        val compressedSize = lz4Compressor.compress(jsonBytes, 0, jsonBytes.size, compressed, 0)
        
        val baos = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(baos)
        
        dos.writeShort(MAGIC.toInt())
        dos.writeByte(VERSION.toInt())
        dos.writeInt(typeNameBytes.size)
        dos.write(typeNameBytes)
        dos.writeInt(jsonBytes.size)
        dos.write(compressed, 0, compressedSize)
        
        return baos.toByteArray()
    }
    
    private fun deserialize(bytes: ByteArray, expectedTypeName: String): Any? {
        if (bytes.size < 10) return null
        
        return try {
            val dis = java.io.DataInputStream(java.io.ByteArrayInputStream(bytes))
            
            val magic = dis.readShort()
            if (magic != MAGIC) return null
            
            dis.readByte()
            val typeNameLen = dis.readInt()
            val typeNameBytes = ByteArray(typeNameLen)
            dis.readFully(typeNameBytes)
            val typeName = String(typeNameBytes, Charsets.UTF_8)
            
            val originalSize = dis.readInt()
            val compressedData = bytes.copyOfRange(10 + typeNameLen, bytes.size)
            
            val decompressed = ByteArray(originalSize)
            lz4Decompressor.decompress(compressedData, 0, decompressed, 0, originalSize)
            
            val jsonStr = String(decompressed, Charsets.UTF_8)
            
            deserializeByTypeName(jsonStr, typeName.ifEmpty { expectedTypeName })
        } catch (e: Exception) {
            Log.w("DiskCacheLayer", "Failed to deserialize: ${e.message}")
            null
        }
    }
    
    private fun deserializeByTypeName(jsonStr: String, typeName: String): Any? {
        return try {
            when {
                typeName.contains("GameData") -> {
                    json.decodeFromString(
                        kotlinx.serialization.serializer<com.xianxia.sect.core.model.GameData>(),
                        jsonStr
                    )
                }
                typeName.contains("Disciple") -> {
                    json.decodeFromString(
                        kotlinx.serialization.serializer<com.xianxia.sect.core.model.Disciple>(),
                        jsonStr
                    )
                }
                typeName.contains("Equipment") -> {
                    json.decodeFromString(
                        kotlinx.serialization.serializer<com.xianxia.sect.core.model.Equipment>(),
                        jsonStr
                    )
                }
                typeName.contains("Manual") -> {
                    json.decodeFromString(
                        kotlinx.serialization.serializer<com.xianxia.sect.core.model.Manual>(),
                        jsonStr
                    )
                }
                typeName.contains("Pill") -> {
                    json.decodeFromString(
                        kotlinx.serialization.serializer<com.xianxia.sect.core.model.Pill>(),
                        jsonStr
                    )
                }
                typeName.contains("Material") -> {
                    json.decodeFromString(
                        kotlinx.serialization.serializer<com.xianxia.sect.core.model.Material>(),
                        jsonStr
                    )
                }
                typeName.contains("Herb") -> {
                    json.decodeFromString(
                        kotlinx.serialization.serializer<com.xianxia.sect.core.model.Herb>(),
                        jsonStr
                    )
                }
                typeName.contains("Seed") -> {
                    json.decodeFromString(
                        kotlinx.serialization.serializer<com.xianxia.sect.core.model.Seed>(),
                        jsonStr
                    )
                }
                typeName.contains("BattleLog") -> {
                    json.decodeFromString(
                        kotlinx.serialization.serializer<com.xianxia.sect.core.model.BattleLog>(),
                        jsonStr
                    )
                }
                typeName.contains("GameEvent") -> {
                    json.decodeFromString(
                        kotlinx.serialization.serializer<com.xianxia.sect.core.model.GameEvent>(),
                        jsonStr
                    )
                }
                typeName.contains("Map") -> {
                    json.decodeFromString(
                        kotlinx.serialization.serializer<Map<String, Any?>>(),
                        jsonStr
                    )
                }
                typeName.contains("List") -> {
                    json.decodeFromString(
                        kotlinx.serialization.serializer<List<Any?>>(),
                        jsonStr
                    )
                }
                typeName == "kotlin.String" -> jsonStr
                typeName == "kotlin.Int" -> jsonStr.toIntOrNull()
                typeName == "kotlin.Long" -> jsonStr.toLongOrNull()
                typeName == "kotlin.Double" -> jsonStr.toDoubleOrNull()
                typeName == "kotlin.Boolean" -> jsonStr.toBooleanStrictOrNull()
                else -> {
                    Log.d("DiskCacheLayer", "Unknown type: $typeName, returning raw string")
                    jsonStr
                }
            }
        } catch (e: Exception) {
            Log.w("DiskCacheLayer", "Failed to deserialize type $typeName: ${e.message}")
            null
        }
    }
}

class AccessPatternTracker {
    private val accessHistory = ConcurrentHashMap<String, AccessInfo>()
    private val timeWindowMs = 60_000L
    
    data class AccessInfo(
        val key: String,
        val totalAccessCount: Int = 0,
        val recentAccessCount: Int = 0,
        val lastAccessTime: Long = 0,
        val lastLevel: CacheLevel = CacheLevel.L2_WARM
    )
    
    fun recordAccess(key: String, level: CacheLevel) {
        val now = System.currentTimeMillis()
        
        accessHistory.compute(key) { _, existing ->
            val info = existing ?: AccessInfo(key)
            val recentCount = if (now - info.lastAccessTime < timeWindowMs) {
                info.recentAccessCount + 1
            } else {
                1
            }
            
            info.copy(
                totalAccessCount = info.totalAccessCount + 1,
                recentAccessCount = recentCount,
                lastAccessTime = now,
                lastLevel = level
            )
        }
    }
    
    fun getAccessInfo(key: String): AccessInfo? = accessHistory[key]
    
    fun remove(key: String) {
        accessHistory.remove(key)
    }
    
    fun clear() {
        accessHistory.clear()
    }
}
