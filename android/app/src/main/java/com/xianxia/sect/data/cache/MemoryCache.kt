package com.xianxia.sect.data.cache

import android.util.Log
import android.util.LruCache
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** 具名 LruCache 子类（避免匿名内部类触发 KSP getSimpleName NPE） */
private class SizedLruCache(maxSize: Int, private val onEvicted: (String, CacheEntry) -> Unit) : LruCache<String, CacheEntry>(maxSize) {
    override fun sizeOf(key: String, value: CacheEntry): Int = value.size.coerceAtLeast(1)
    override fun entryRemoved(evicted: Boolean, key: String, oldValue: CacheEntry, newValue: CacheEntry?) {
        if (evicted) onEvicted(key, oldValue)
    }
}

class MemoryCache(private val maxSize: Int) {
    
    companion object {
        private const val TAG = "MemoryCache"
    }

    private val cache = SizedLruCache(maxSize) { key, value -> onEntryEvicted(key, value) }

    private val hitCount = AtomicLong(0)
    private val missCount = AtomicLong(0)
    private val evictionListeners = CopyOnWriteArrayList<(String, CacheEntry) -> Unit>()

    fun addEvictionListener(listener: (String, CacheEntry) -> Unit) {
        evictionListeners.add(listener)
    }

    fun removeEvictionListener(listener: (String, CacheEntry) -> Unit) {
        evictionListeners.remove(listener)
    }

    private fun onEntryEvicted(key: String, entry: CacheEntry) {
        evictionListeners.forEach { listener ->
            try {
                listener(key, entry)
            } catch (e: Exception) {
                Log.e(TAG, "Error in eviction listener", e)
            }
        }
    }

    @Synchronized
    fun put(key: String, entry: CacheEntry) {
        cache.put(key, entry)
        Log.d(TAG, "Put: $key, size=${entry.size}, totalSize=${cache.size()}")
    }

    @Synchronized
    fun get(key: String): CacheEntry? {
        val entry = cache.get(key)
        if (entry != null) {
            if (!entry.isExpired()) {
                hitCount.incrementAndGet()
                return entry
            } else {
                cache.remove(key)
                missCount.incrementAndGet()
                Log.d(TAG, "Entry expired: $key")
                return null
            }
        }
        missCount.incrementAndGet()
        return null
    }

    @Synchronized
    fun remove(key: String): CacheEntry? {
        return cache.remove(key)
    }

    @Synchronized
    fun contains(key: String): Boolean {
        val entry = cache.get(key) ?: return false
        if (entry.isExpired()) {
            cache.remove(key)
            return false
        }
        return true
    }

    @Synchronized
    fun clear() {
        cache.evictAll()
        Log.i(TAG, "Cache cleared")
    }

    @Synchronized
    fun trimToSize(maxSize: Int) {
        cache.trimToSize(maxSize)
    }

    fun size(): Int = cache.size()

    fun maxSize(): Int = cache.maxSize()

    fun hitCount(): Long = hitCount.get()

    fun missCount(): Long = missCount.get()

    fun hitRate(): Double {
        val total = hitCount.get() + missCount.get()
        return if (total > 0) hitCount.get().toDouble() / total else 0.0
    }

    @Synchronized
    fun snapshot(): Map<String, CacheEntry> {
        return cache.snapshot()
    }

    @Synchronized
    fun cleanExpired(): Int {
        var removed = 0
        val snapshot = cache.snapshot()
        for ((key, entry) in snapshot) {
            if (entry.isExpired()) {
                cache.remove(key)
                removed++
            }
        }
        if (removed > 0) {
            Log.d(TAG, "Cleaned $removed expired entries")
        }
        return removed
    }

    @Synchronized
    fun getStats(): Pair<Int, Int> {
        return Pair(cache.size(), cache.maxSize())
    }
}
