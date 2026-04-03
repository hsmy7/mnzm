package com.xianxia.sect.data.cache

import android.content.Context
import android.util.Log
import com.tencent.mmkv.MMKV
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class DiskCache(
    context: Context,
    private val cacheId: String = "game_cache",
    private val maxSize: Long = 100 * 1024 * 1024
) {
    companion object {
        private const val TAG = "DiskCache"
        private const val KEY_PREFIX = "cache_"
        private const val META_SIZE = "_meta_size"
        private const val META_COUNT = "_meta_count"
        private const val FLUSH_INTERVAL_MS = 30_000L
        private const val MAX_PENDING_UPDATES = 100
        
        private var isInitialized = false

        fun initialize(context: Context) {
            if (!isInitialized) {
                MMKV.initialize(context)
                isInitialized = true
                Log.i(TAG, "MMKV initialized")
            }
        }
    }

    private val mmkv: MMKV by lazy {
        initialize(context.applicationContext)
        MMKV.mmkvWithID(
            cacheId,
            MMKV.MULTI_PROCESS_MODE,
            "game_cache_key"
        )
    }

    private val totalSize = AtomicLong(0)
    private val entryCount = AtomicLong(0)
    private val hitCount = AtomicLong(0)
    private val missCount = AtomicLong(0)
    private val evictionCount = AtomicLong(0)
    
    private val pendingUpdates = ConcurrentHashMap<String, CacheEntry>()
    private var lastFlushTime = System.currentTimeMillis()
    private val flushLock = Any()

    init {
        loadMetadata()
    }

    private fun loadMetadata() {
        totalSize.set(mmkv.decodeLong(META_SIZE, 0))
        entryCount.set(mmkv.decodeLong(META_COUNT, 0))
        Log.d(TAG, "Loaded metadata: size=${totalSize.get()}, count=${entryCount.get()}")
    }

    private fun saveMetadata() {
        mmkv.encode(META_SIZE, totalSize.get())
        mmkv.encode(META_COUNT, entryCount.get())
    }
    
    fun flush() {
        synchronized(flushLock) {
            if (pendingUpdates.isEmpty()) return
            
            val updates = pendingUpdates.toMap()
            pendingUpdates.clear()
            
            var flushed = 0
            for ((key, entry) in updates) {
                try {
                    val internalKey = KEY_PREFIX + key
                    val data = serializeEntry(entry)
                    mmkv.encode(internalKey, data)
                    mmkv.encode("${internalKey}_size", data.size)
                    flushed++
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to flush entry: $key", e)
                }
            }
            
            lastFlushTime = System.currentTimeMillis()
            if (flushed > 0) {
                Log.d(TAG, "Flushed $flushed pending updates to disk")
            }
        }
    }
    
    private fun shouldFlush(): Boolean {
        val now = System.currentTimeMillis()
        return pendingUpdates.size >= MAX_PENDING_UPDATES || 
               (pendingUpdates.isNotEmpty() && now - lastFlushTime >= FLUSH_INTERVAL_MS)
    }
    
    private fun tryFlush() {
        if (shouldFlush()) {
            flush()
        }
    }

    fun put(key: String, entry: CacheEntry): Boolean {
        return try {
            val data = serializeEntry(entry)
            val internalKey = KEY_PREFIX + key

            val oldSize = mmkv.decodeInt("${internalKey}_size", 0)
            if (oldSize > 0) {
                totalSize.addAndGet(-oldSize.toLong())
            } else {
                entryCount.incrementAndGet()
            }

            if (totalSize.get() + data.size > maxSize) {
                Log.w(TAG, "Disk cache full, size=${totalSize.get()}, maxSize=$maxSize")
                if (!evictToFit(data.size.toLong())) {
                    Log.e(TAG, "Failed to evict enough space for key: $key")
                    return false
                }
            }

            mmkv.encode(internalKey, data)
            mmkv.encode("${internalKey}_size", data.size)
            totalSize.addAndGet(data.size.toLong())
            saveMetadata()

            Log.d(TAG, "Put: $key, size=${data.size}, totalSize=${totalSize.get()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to put entry: $key", e)
            false
        }
    }

    fun get(key: String): CacheEntry? {
        return try {
            val pendingEntry = pendingUpdates[key]
            if (pendingEntry != null) {
                hitCount.incrementAndGet()
                val touchedEntry = pendingEntry.touch()
                pendingUpdates[key] = touchedEntry
                return touchedEntry
            }
            
            val internalKey = KEY_PREFIX + key
            val data = mmkv.decodeBytes(internalKey)
            
            if (data == null) {
                missCount.incrementAndGet()
                return null
            }

            val entry = deserializeEntry(data)
            if (entry == null) {
                Log.w(TAG, "Failed to deserialize entry for key: $key, removing")
                remove(key)
                missCount.incrementAndGet()
                return null
            }
            
            if (entry.isExpired()) {
                remove(key)
                Log.d(TAG, "Entry expired: $key")
                missCount.incrementAndGet()
                return null
            }
            
            hitCount.incrementAndGet()
            
            val touchedEntry = entry.touch()
            pendingUpdates[key] = touchedEntry
            
            tryFlush()
            
            touchedEntry
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get entry: $key", e)
            missCount.incrementAndGet()
            null
        }
    }

    fun remove(key: String): Boolean {
        return try {
            pendingUpdates.remove(key)
            
            val internalKey = KEY_PREFIX + key
            val size = mmkv.decodeInt("${internalKey}_size", 0)

            if (size > 0) {
                mmkv.remove(internalKey)
                mmkv.remove("${internalKey}_size")
                totalSize.addAndGet(-size.toLong())
                entryCount.decrementAndGet()
                saveMetadata()
                Log.d(TAG, "Removed: $key, size=$size")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove entry: $key", e)
            false
        }
    }

    fun contains(key: String): Boolean {
        val internalKey = KEY_PREFIX + key
        return mmkv.contains(internalKey)
    }

    fun clear() {
        try {
            pendingUpdates.clear()
            
            val allKeys = mmkv.allKeys()?.filter { it.startsWith(KEY_PREFIX) } ?: emptyList<String>()
            for (key in allKeys) {
                mmkv.remove(key)
                mmkv.remove("${key}_size")
            }
            totalSize.set(0)
            entryCount.set(0)
            saveMetadata()
            Log.i(TAG, "Cache cleared, removed ${allKeys.size} entries")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear cache", e)
        }
    }

    fun cleanExpired(): Int {
        var removed = 0
        try {
            val allKeys = mmkv.allKeys()?.filter { it.startsWith(KEY_PREFIX) && !it.endsWith("_size") } ?: emptyList<String>()
            for (internalKey in allKeys) {
                val data = mmkv.decodeBytes(internalKey) ?: continue
                try {
                    val entry = deserializeEntry(data)
                    if (entry == null || entry.isExpired()) {
                        val key = internalKey.removePrefix(KEY_PREFIX)
                        remove(key)
                        removed++
                    }
                } catch (e: Exception) {
                    val key = internalKey.removePrefix(KEY_PREFIX)
                    remove(key)
                    removed++
                }
            }
            if (removed > 0) {
                Log.d(TAG, "Cleaned $removed expired entries")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean expired entries", e)
        }
        return removed
    }

    private fun evictToFit(requiredSize: Long): Boolean {
        val allKeys = mmkv.allKeys()?.filter { it.startsWith(KEY_PREFIX) && !it.endsWith("_size") } ?: emptyList<String>()
        
        if (allKeys.isEmpty()) {
            return requiredSize <= maxSize
        }

        val entries = mutableListOf<Pair<String, CacheEntry?>>()
        for (internalKey in allKeys) {
            val data = mmkv.decodeBytes(internalKey) ?: continue
            try {
                val entry = deserializeEntry(data)
                entries.add(Pair(internalKey.removePrefix(KEY_PREFIX), entry))
            } catch (e: Exception) {
                val key = internalKey.removePrefix(KEY_PREFIX)
                remove(key)
            }
        }

        entries.sortBy { it.second?.lastAccessedAt ?: it.second?.createdAt ?: 0 }

        var freed = 0L
        var evictedCount = 0
        for ((key, entry) in entries) {
            if (totalSize.get() - freed + requiredSize <= maxSize) {
                break
            }
            val internalKey = KEY_PREFIX + key
            val size = mmkv.decodeInt("${internalKey}_size", 0)
            val accessCount = entry?.accessCount ?: 0
            Log.d(TAG, "LRU evicting: $key, size=$size, accessCount=$accessCount, idleTime=${entry?.idleTime()}")
            remove(key)
            freed += size
            evictedCount++
            evictionCount.incrementAndGet()
        }

        Log.d(TAG, "LRU evicted $evictedCount entries, freed $freed bytes")
        return totalSize.get() + requiredSize <= maxSize
    }

    fun size(): Long = totalSize.get()

    fun count(): Long = entryCount.get()

    fun maxSize(): Long = maxSize

    private fun serializeEntry(entry: CacheEntry): ByteArray {
        return entry.serialize()
    }

    private fun deserializeEntry(data: ByteArray): CacheEntry? {
        return CacheEntry.deserialize(data)
    }

    fun getStats(): DiskCacheStats {
        return DiskCacheStats(
            totalSize = totalSize.get(),
            entryCount = entryCount.get(),
            maxSize = maxSize,
            hitCount = hitCount.get(),
            missCount = missCount.get(),
            evictionCount = evictionCount.get()
        )
    }
}

data class DiskCacheStats(
    val totalSize: Long,
    val entryCount: Long,
    val maxSize: Long,
    val hitCount: Long = 0,
    val missCount: Long = 0,
    val evictionCount: Long = 0
) {
    val usagePercent: Double
        get() = if (maxSize > 0) totalSize.toDouble() / maxSize * 100 else 0.0
    
    val hitRate: Double
        get() = if (hitCount + missCount > 0) hitCount.toDouble() / (hitCount + missCount) else 0.0
}
