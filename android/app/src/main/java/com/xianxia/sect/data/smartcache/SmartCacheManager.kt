@file:OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)

package com.xianxia.sect.data.smartcache

import android.content.Context
import android.util.Log
import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.cache.CacheKey
import com.xianxia.sect.data.cache.CachePolicy
import com.xianxia.sect.data.cache.CachePriority
import com.xianxia.sect.data.cache.CacheStrategy
import com.xianxia.sect.data.cache.DiskCacheLayer
import com.xianxia.sect.data.serialization.UnifiedSerializationConstants
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

object SmartCacheConfig {
    const val DEFAULT_MEMORY_CACHE_SIZE = 64 * 1024 * 1024
    const val DEFAULT_DISK_CACHE_SIZE = 128 * 1024 * 1024
    
    const val HOT_DATA_TTL_MS = 30 * 60 * 1000L
    const val WARM_DATA_TTL_MS = 2 * 60 * 60 * 1000L
    const val COLD_DATA_TTL_MS = 24 * 60 * 60 * 1000L
    
    const val CLEANUP_INTERVAL_MS = 60_000L
    const val STATS_UPDATE_INTERVAL_MS = 10_000L
    
    val DATA_TYPE_PRIORITIES = mapOf(
        "game_data" to CachePriority.CRITICAL,
        "disciple" to CachePriority.HIGH,
        "equipment" to CachePriority.NORMAL,
        "manual" to CachePriority.NORMAL,
        "pill" to CachePriority.LOW,
        "material" to CachePriority.LOW,
        "herb" to CachePriority.LOW,
        "seed" to CachePriority.LOW,
        "battle_log" to CachePriority.BACKGROUND,
        "event" to CachePriority.BACKGROUND
    )
    
    val DATA_TYPE_TTL = mapOf(
        "game_data" to HOT_DATA_TTL_MS,
        "disciple" to HOT_DATA_TTL_MS,
        "equipment" to WARM_DATA_TTL_MS,
        "manual" to WARM_DATA_TTL_MS,
        "pill" to COLD_DATA_TTL_MS,
        "material" to COLD_DATA_TTL_MS,
        "herb" to COLD_DATA_TTL_MS,
        "seed" to COLD_DATA_TTL_MS,
        "battle_log" to COLD_DATA_TTL_MS,
        "event" to COLD_DATA_TTL_MS
    )
}

data class CacheEntry<T>(
    val data: T,
    val key: String,
    val createdAt: Long,
    val lastAccessedAt: Long,
    val accessCount: Int,
    val size: Int,
    val priority: CachePriority,
    val ttl: Long,
    val isDirty: Boolean = false
)

data class SmartCacheStats(
    val hitCount: Long = 0,
    val missCount: Long = 0,
    val evictionCount: Long = 0,
    val totalSize: Long = 0,
    val entryCount: Int = 0,
    val hitRate: Float = 0f,
    val pendingWrites: Int = 0,
    val memoryUsage: Long = 0
)

@Deprecated(
    message = "Use UnifiedCacheManager instead. This class duplicates functionality and will be removed.",
    replaceWith = ReplaceWith("UnifiedCacheManager", "com.xianxia.sect.data.cache.UnifiedCacheManager"),
    level = DeprecationLevel.WARNING
)
class SmartCacheManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "SmartCacheManager"
    }

    private val memoryCache = ConcurrentHashMap<String, CacheEntry<Any>>()
    private val diskCache = DiskCacheLayer(context, "smart_cache")
    
    private val writeBehindQueue = ConcurrentHashMap<String, Any>()
    private val dirtyKeys = ConcurrentHashMap.newKeySet<String>()
    
    private val hitCount = AtomicLong(0)
    private val missCount = AtomicLong(0)
    private val evictionCount = AtomicLong(0)
    private val totalSize = AtomicLong(0)
    
    private val _stats = MutableStateFlow(SmartCacheStats())
    val stats: StateFlow<SmartCacheStats> = _stats.asStateFlow()
    
    private val policies = ConcurrentHashMap<String, CachePolicy>()
    
    private var cleanupJob: Job? = null
    private var statsJob: Job? = null
    private var flushJob: Job? = null
    private var isShuttingDown = false

    init {
        initializePolicies()
        startBackgroundTasks()
    }

    private fun initializePolicies() {
        SmartCacheConfig.DATA_TYPE_PRIORITIES.forEach { (type, priority) ->
            val ttl = SmartCacheConfig.DATA_TYPE_TTL[type] ?: SmartCacheConfig.WARM_DATA_TTL_MS
            val strategy = when (priority) {
                CachePriority.CRITICAL -> CacheStrategy.WRITE_THROUGH
                CachePriority.HIGH -> CacheStrategy.WRITE_BEHIND
                CachePriority.NORMAL -> CacheStrategy.WRITE_BEHIND
                CachePriority.LOW -> CacheStrategy.WRITE_AROUND
                CachePriority.BACKGROUND -> CacheStrategy.CACHE_ASIDE
            }
            
            policies[type] = CachePolicy(
                priority = priority,
                ttl = ttl,
                strategy = strategy,
                maxSize = getMaxSizeForPriority(priority),
                compress = priority == CachePriority.BACKGROUND,
                evictOnLowMemory = priority != CachePriority.CRITICAL
            )
        }
    }

    private fun getMaxSizeForPriority(priority: CachePriority): Int {
        return when (priority) {
            CachePriority.CRITICAL -> 10
            CachePriority.HIGH -> 50
            CachePriority.NORMAL -> 100
            CachePriority.LOW -> 200
            CachePriority.BACKGROUND -> 500
        }
    }

    private fun startBackgroundTasks() {
        cleanupJob = scope.launch {
            while (isActive && !isShuttingDown) {
                delay(SmartCacheConfig.CLEANUP_INTERVAL_MS)
                performCleanup()
            }
        }
        
        statsJob = scope.launch {
            while (isActive && !isShuttingDown) {
                delay(SmartCacheConfig.STATS_UPDATE_INTERVAL_MS)
                updateStats()
            }
        }
        
        flushJob = scope.launch {
            while (isActive && !isShuttingDown) {
                delay(5000)
                flushWriteBehindQueue()
            }
        }
    }

    suspend fun <T : Any> get(key: CacheKey): T? {
        val cacheKey = key.toString()
        val entry = memoryCache[cacheKey]
        
        return if (entry != null && !isExpired(entry)) {
            hitCount.incrementAndGet()
            updateAccessInfo(cacheKey, entry)
            @Suppress("UNCHECKED_CAST")
            entry.data as T
        } else {
            missCount.incrementAndGet()
            if (entry != null) {
                memoryCache.remove(cacheKey)
            }
            null
        }
    }
    
    fun <T : Any> getSync(key: CacheKey): T? {
        val cacheKey = key.toString()
        val entry = memoryCache[cacheKey]
        
        return if (entry != null && !isExpired(entry)) {
            hitCount.incrementAndGet()
            @Suppress("UNCHECKED_CAST")
            entry.data as T
        } else {
            missCount.incrementAndGet()
            if (entry != null) {
                memoryCache.remove(cacheKey)
            }
            null
        }
    }

    suspend fun <T : Any> getOrLoad(key: CacheKey, loader: suspend () -> T): T {
        val cached = get<T>(key)
        if (cached != null) {
            return cached
        }
        
        val data = loader()
        put(key, data)
        return data
    }

    fun put(key: CacheKey, value: Any, customTTL: Long? = null) {
        val cacheKey = key.toString()
        val policy = policies[key.type] ?: getDefaultPolicy()
        val ttl = customTTL ?: policy.ttl
        
        val size = estimateSize(value)
        val entry = CacheEntry(
            data = value,
            key = cacheKey,
            createdAt = System.currentTimeMillis(),
            lastAccessedAt = System.currentTimeMillis(),
            accessCount = 1,
            size = size,
            priority = policy.priority,
            ttl = ttl
        )
        
        ensureCapacity(policy, size)
        
        val existing = memoryCache.put(cacheKey, entry)
        if (existing != null) {
            totalSize.addAndGet(-existing.size.toLong())
        }
        totalSize.addAndGet(size.toLong())
        
        when (policy.strategy) {
            CacheStrategy.WRITE_THROUGH -> {
                diskCache.put(cacheKey, value, ttl)
            }
            CacheStrategy.WRITE_BEHIND -> {
                writeBehindQueue[cacheKey] = value
                dirtyKeys.add(cacheKey)
            }
            CacheStrategy.WRITE_AROUND -> {
                // Skip disk cache on write
            }
            else -> {
                writeBehindQueue[cacheKey] = value
            }
        }
    }

    fun putAll(entries: Map<CacheKey, Any>) {
        entries.forEach { (key, value) ->
            put(key, value)
        }
    }

    fun remove(key: CacheKey) {
        val cacheKey = key.toString()
        val entry = memoryCache.remove(cacheKey)
        if (entry != null) {
            totalSize.addAndGet(-entry.size.toLong())
        }
        diskCache.remove(cacheKey)
        writeBehindQueue.remove(cacheKey)
        dirtyKeys.remove(cacheKey)
    }

    fun contains(key: CacheKey): Boolean {
        val cacheKey = key.toString()
        val entry = memoryCache[cacheKey]
        return entry != null && !isExpired(entry)
    }

    fun markDirty(key: CacheKey) {
        dirtyKeys.add(key.toString())
    }

    fun clear() {
        memoryCache.clear()
        writeBehindQueue.clear()
        dirtyKeys.clear()
        totalSize.set(0)
        diskCache.clear()
    }

    fun flush() {
        flushWriteBehindQueue()
        diskCache.flush()
    }

    fun invalidateSlot(slot: Int) {
        val keysToRemove = memoryCache.keys.filter { 
            it.contains("_${slot}_") || it.endsWith("_$slot") 
        }
        keysToRemove.forEach { key ->
            val entry = memoryCache.remove(key)
            if (entry != null) {
                totalSize.addAndGet(-entry.size.toLong())
            }
        }
        
        diskCache.invalidatePattern("_${slot}_")
        diskCache.invalidatePattern("_$slot")
    }

    suspend fun warmupCriticalData(data: GameData) {
        val key = CacheKey.forGameData(data.currentSlot)
        put(key, data)
    }

    fun warmupByTypes(slot: Int, dataTypes: List<String>) {
        dataTypes.forEach { type ->
            val policy = policies[type] ?: return@forEach
            if (policy.priority == CachePriority.CRITICAL || policy.priority == CachePriority.HIGH) {
                // Pre-warm cache for high priority data types
            }
        }
    }

    suspend fun verifyIntegrity(slot: Int): Boolean {
        return try {
            val key = CacheKey.forGameData(slot)
            val entry = memoryCache[key.toString()]
            entry != null && !isExpired(entry)
        } catch (e: Exception) {
            Log.e(TAG, "Cache integrity check failed for slot $slot", e)
            false
        }
    }

    fun getStats(): SmartCacheStats {
        updateStats()
        return _stats.value
    }

    private fun ensureCapacity(policy: CachePolicy, requiredSize: Int) {
        val maxSizeBytes = getMaxMemorySize(policy.priority)
        
        while (totalSize.get() + requiredSize > maxSizeBytes && memoryCache.isNotEmpty()) {
            evictOneEntry(policy)
        }
    }

    private fun getMaxMemorySize(priority: CachePriority): Long {
        val baseSize = SmartCacheConfig.DEFAULT_MEMORY_CACHE_SIZE
        return when (priority) {
            CachePriority.CRITICAL -> baseSize.toLong()
            CachePriority.HIGH -> (baseSize * 0.75).toLong()
            CachePriority.NORMAL -> (baseSize * 0.5).toLong()
            CachePriority.LOW -> (baseSize * 0.25).toLong()
            CachePriority.BACKGROUND -> (baseSize * 0.1).toLong()
        }
    }

    private fun evictOneEntry(policy: CachePolicy) {
        val candidates = memoryCache.entries
            .filter { 
                val entryPriority = it.value.priority
                when (policy.priority) {
                    CachePriority.CRITICAL -> entryPriority == CachePriority.BACKGROUND
                    CachePriority.HIGH -> entryPriority == CachePriority.BACKGROUND || entryPriority == CachePriority.LOW
                    else -> true
                }
            }
            .sortedWith(compareBy({ it.value.priority.ordinal }, { it.value.lastAccessedAt }))
        
        if (candidates.isNotEmpty()) {
            val toEvict = candidates.first()
            memoryCache.remove(toEvict.key)
            totalSize.addAndGet(-toEvict.value.size.toLong())
            evictionCount.incrementAndGet()
            
            if (toEvict.value.isDirty || dirtyKeys.contains(toEvict.key)) {
                diskCache.put(toEvict.key, toEvict.value.data, toEvict.value.ttl)
            }
        }
    }

    private fun isExpired(entry: CacheEntry<Any>): Boolean {
        return System.currentTimeMillis() - entry.createdAt > entry.ttl
    }

    private fun updateAccessInfo(cacheKey: String, entry: CacheEntry<Any>) {
        val updated = entry.copy(
            lastAccessedAt = System.currentTimeMillis(),
            accessCount = entry.accessCount + 1
        )
        memoryCache[cacheKey] = updated
    }

    private fun performCleanup() {
        val now = System.currentTimeMillis()
        val expiredKeys = mutableListOf<String>()
        
        memoryCache.forEach { (key, entry) ->
            if (now - entry.createdAt > entry.ttl) {
                expiredKeys.add(key)
            }
        }
        
        expiredKeys.forEach { key ->
            val entry = memoryCache.remove(key)
            if (entry != null) {
                totalSize.addAndGet(-entry.size.toLong())
                evictionCount.incrementAndGet()
            }
        }
        
        diskCache.cleanupExpired()
        
        if (expiredKeys.isNotEmpty()) {
            Log.d(TAG, "Cleaned up ${expiredKeys.size} expired entries")
        }
    }

    private fun flushWriteBehindQueue() {
        if (writeBehindQueue.isEmpty()) return
        
        val toFlush = writeBehindQueue.toMap()
        writeBehindQueue.clear()
        
        toFlush.forEach { (key, value) ->
            try {
                val entry = memoryCache[key]
                if (entry != null) {
                    diskCache.put(key, value, entry.ttl)
                }
                dirtyKeys.remove(key)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to flush cache entry: $key", e)
            }
        }
    }

    private fun updateStats() {
        val hits = hitCount.get()
        val misses = missCount.get()
        val total = hits + misses
        
        _stats.value = SmartCacheStats(
            hitCount = hits,
            missCount = misses,
            evictionCount = evictionCount.get(),
            totalSize = totalSize.get(),
            entryCount = memoryCache.size,
            hitRate = if (total > 0) hits.toFloat() / total else 0f,
            pendingWrites = writeBehindQueue.size,
            memoryUsage = totalSize.get()
        )
    }

    private fun estimateSize(value: Any): Int {
        return when (value) {
            is GameData -> 2048
            is Disciple -> 512
            is Equipment -> 256
            is Manual -> 128
            is Pill -> 64
            is Material -> 32
            is Herb -> 32
            is Seed -> 32
            is BattleLog -> 256
            is GameEvent -> 128
            else -> 64
        }
    }

    private fun getDefaultPolicy(): CachePolicy {
        return CachePolicy(
            priority = CachePriority.NORMAL,
            ttl = SmartCacheConfig.WARM_DATA_TTL_MS,
            strategy = CacheStrategy.WRITE_BEHIND
        )
    }

    fun setCustomPolicy(dataType: String, policy: CachePolicy) {
        policies[dataType] = policy
    }

    fun getPolicy(dataType: String): CachePolicy? {
        return policies[dataType]
    }

    fun onLowMemory() {
        Log.w(TAG, "Low memory detected, evicting non-critical cache entries")
        
        val toEvict = memoryCache.entries
            .filter { it.value.priority != CachePriority.CRITICAL }
            .sortedByDescending { it.value.priority.ordinal }
        
        var evictedSize = 0L
        toEvict.forEach { entry ->
            if (evictedSize < SmartCacheConfig.DEFAULT_MEMORY_CACHE_SIZE / 2) {
                memoryCache.remove(entry.key)
                totalSize.addAndGet(-entry.value.size.toLong())
                evictionCount.incrementAndGet()
                evictedSize += entry.value.size
                
                if (entry.value.isDirty || dirtyKeys.contains(entry.key)) {
                    diskCache.put(entry.key, entry.value.data, entry.value.ttl)
                }
            }
        }
        
        Log.i(TAG, "Evicted ${toEvict.size} entries, freed $evictedSize bytes")
    }

    suspend fun shutdown() {
        isShuttingDown = true
        
        cleanupJob?.cancel()
        statsJob?.cancel()
        flushJob?.cancel()
        
        withTimeoutOrNull(5000L) {
            flushWriteBehindQueue()
        } ?: run {
            Log.w(TAG, "Timeout while flushing write-behind queue during shutdown")
        }
        
        diskCache.shutdown()
        Log.i(TAG, "SmartCacheManager shutdown completed")
    }
    
    fun shutdownSync() {
        isShuttingDown = true
        
        cleanupJob?.cancel()
        statsJob?.cancel()
        flushJob?.cancel()
        
        try {
            flushWriteBehindQueueSync()
        } catch (e: Exception) {
            Log.w(TAG, "Error during sync shutdown", e)
        }
        
        diskCache.shutdown()
        Log.i(TAG, "SmartCacheManager sync shutdown completed")
    }
    
    private fun flushWriteBehindQueueSync() {
        if (writeBehindQueue.isEmpty()) return
        
        val toFlush = writeBehindQueue.toMap()
        writeBehindQueue.clear()
        
        toFlush.forEach { (key, value) ->
            try {
                val entry = memoryCache[key]
                if (entry != null) {
                    diskCache.put(key, value, entry.ttl)
                }
                dirtyKeys.remove(key)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to flush cache entry: $key", e)
            }
        }
    }
}
