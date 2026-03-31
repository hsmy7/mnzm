package com.xianxia.sect.data.cache

import android.content.Context
import android.util.Log
import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.local.GameDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

data class SlotCacheStats(
    val hitCount: Long = 0,
    val missCount: Long = 0,
    val memorySize: Long = 0,
    val diskSize: Long = 0,
    val entryCount: Int = 0,
    val dirtyCount: Int = 0,
    val slotCount: Int = 0
) {
    val hitRate: Float get() = if (hitCount + missCount > 0) hitCount.toFloat() / (hitCount + missCount) else 0f
}

@Singleton
class SlotIsolatedCacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: GameDatabase,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "SlotIsolatedCache"
        private const val CLEANUP_INTERVAL_MS = 60_000L
    }
    
    private val memoryCache = mutableMapOf<String, CacheEntry>()
    private val slotIndex = SlotIsolatedCacheIndex()
    private val dirtyTracker = DirtyTracker()
    
    private val hitCount = AtomicLong(0)
    private val missCount = AtomicLong(0)
    
    private val _stats = MutableStateFlow(SlotCacheStats())
    val stats: StateFlow<SlotCacheStats> = _stats.asStateFlow()
    
    private var cleanupJob: Job? = null
    
    init {
        startBackgroundTasks()
        Log.i(TAG, "SlotIsolatedCacheManager initialized")
    }
    
    private fun startBackgroundTasks() {
        cleanupJob = scope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL_MS)
                performCleanup()
            }
        }
    }
    
    @Synchronized
    fun <T : Any> get(key: SlotAwareCacheKey): T? {
        val cacheKey = key.toString()
        val entry = memoryCache[cacheKey]
        
        return if (entry != null && !isExpired(entry)) {
            hitCount.incrementAndGet()
            @Suppress("UNCHECKED_CAST")
            entry.value as T
        } else {
            missCount.incrementAndGet()
            if (entry != null) {
                removeEntry(key)
            }
            null
        }
    }
    
    @Synchronized
    fun <T : Any> getOrLoad(key: SlotAwareCacheKey, loader: suspend () -> T?): T? {
        val cached = get<T>(key)
        if (cached != null) return cached
        
        return runBlocking {
            val value = loader()
            if (value != null) {
                put(key, value)
            }
            value
        }
    }
    
    suspend fun <T : Any> getOrLoadAsync(key: SlotAwareCacheKey, loader: suspend () -> T?): T? {
        val cached = get<T>(key)
        if (cached != null) return cached
        
        val value = loader()
        if (value != null) {
            put(key, value)
        }
        return value
    }
    
    @Synchronized
    fun put(key: SlotAwareCacheKey, value: Any?) {
        if (value == null) {
            remove(key)
            return
        }
        
        val cacheKey = key.toString()
        val entry = CacheEntry.create(value, key.ttl)
        
        memoryCache[cacheKey] = entry
        slotIndex.register(key)
        dirtyTracker.markUpdate(cacheKey, value)
    }
    
    @Synchronized
    fun putAll(entries: Map<SlotAwareCacheKey, Any>) {
        entries.forEach { (key, value) ->
            put(key, value)
        }
    }
    
    @Synchronized
    fun remove(key: SlotAwareCacheKey) {
        removeEntry(key)
    }
    
    private fun removeEntry(key: SlotAwareCacheKey) {
        val cacheKey = key.toString()
        memoryCache.remove(cacheKey)
        slotIndex.unregister(key)
        dirtyTracker.clearDirty(cacheKey)
    }
    
    @Synchronized
    fun invalidateSlot(slot: Int): Int {
        val keysToRemove = slotIndex.getKeysForSlot(slot)
        keysToRemove.forEach { key ->
            memoryCache.remove(key)
            dirtyTracker.clearDirty(key)
        }
        val count = slotIndex.clearSlot(slot)
        Log.i(TAG, "Invalidated $count entries for slot $slot")
        return count
    }
    
    @Synchronized
    fun getKeysForSlot(slot: Int): Set<String> {
        return slotIndex.getKeysForSlot(slot)
    }
    
    @Synchronized
    fun hasSlot(slot: Int): Boolean {
        return slotIndex.hasSlot(slot)
    }
    
    @Synchronized
    fun markDirty(key: SlotAwareCacheKey) {
        dirtyTracker.markDirty(key.toString(), DirtyFlag.UPDATE, null)
    }
    
    @Synchronized
    fun isDirty(key: SlotAwareCacheKey): Boolean {
        return dirtyTracker.isDirty(key.toString())
    }
    
    @Synchronized
    fun clear() {
        memoryCache.clear()
        slotIndex.clearAll()
        dirtyTracker.clear()
        hitCount.set(0)
        missCount.set(0)
        Log.i(TAG, "Cache cleared")
    }
    
    fun getStats(): SlotCacheStats {
        return SlotCacheStats(
            hitCount = hitCount.get().toLong(),
            missCount = missCount.get().toLong(),
            memorySize = estimateMemorySize(),
            diskSize = 0,
            entryCount = memoryCache.size,
            dirtyCount = 0,
            slotCount = slotIndex.getSlotCount()
        )
    }
    
    private fun isExpired(entry: CacheEntry): Boolean {
        return System.currentTimeMillis() - entry.createdAt > entry.ttl
    }
    
    private fun performCleanup() {
        val now = System.currentTimeMillis()
        var expiredCount = 0
        
        synchronized(this) {
            val expiredKeys = memoryCache.entries
                .filter { now - it.value.createdAt > it.value.ttl }
                .map { it.key }
            
            expiredKeys.forEach { key ->
                memoryCache.remove(key)
                SlotAwareCacheKey.fromString(key)?.let { slotIndex.unregister(it) }
                expiredCount++
            }
        }
        
        if (expiredCount > 0) {
            Log.d(TAG, "Cleaned up $expiredCount expired entries")
        }
        
        updateStats()
    }
    
    private fun updateStats() {
        _stats.value = getStats()
    }
    
    private fun estimateMemorySize(): Long {
        return memoryCache.size * 256L
    }
    
    fun shutdown() {
        cleanupJob?.cancel()
        scope.cancel()
        Log.i(TAG, "SlotIsolatedCacheManager shutdown completed")
    }
}
