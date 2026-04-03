@file:OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)

package com.xianxia.sect.data.partition

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.cache.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.reflect.KClass

class WarmZoneManager(
    private val context: Context,
    override val config: ZoneConfig = ZoneConfig.WARM_DEFAULT,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : ZoneManager {

    companion object {
        private const val TAG = "WarmZoneManager"
        private const val CACHE_ID = "warm_zone_cache"
    }

    override val zone: DataZone = DataZone.WARM

    private val memoryCache: MemoryCache by lazy {
        MemoryCache((config.maxSizeBytes * 0.2).toInt())
    }

    private val diskCache: DiskCache by lazy {
        DiskCache(
            context = context.applicationContext,
            cacheId = CACHE_ID,
            maxSize = config.maxSizeBytes
        )
    }

    private val compressor: DataCompressor by lazy {
        DataCompressor(config.compressionType.toCacheCompressionAlgorithm())
    }

    private val dirtyTracker = DirtyTracker()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }
    
    private val serializerCache = ConcurrentHashMap<KClass<*>, KSerializer<*>>()
    private val writeQueue = WriteQueue(
        config = CacheConfig(
            memoryCacheSize = (config.maxSizeBytes * 0.2).toLong(),
            diskCacheSize = config.maxSizeBytes,
            writeBatchSize = 100,
            writeDelayMs = 1000
        ),
        scope = scope
    )

    private val accessTracker = ConcurrentHashMap<String, AccessInfo>()
    private val hitCount = AtomicLong(0)
    private val missCount = AtomicLong(0)
    private val evictionCount = AtomicLong(0)
    private val compressionSavedBytes = AtomicLong(0)
    private val startTime = System.currentTimeMillis()

    private var writeHandler: (suspend (CacheKey, Any?) -> Boolean)? = null

    init {
        memoryCache.addEvictionListener { key, entry ->
            evictionCount.incrementAndGet()
            Log.d(TAG, "Entry evicted from warm memory cache: $key")
        }

        writeQueue.setBatchWriteHandler { tasks ->
            var successCount = 0
            tasks.forEach { task ->
                val key = CacheKey.fromString(task.key)
                if (writeHandler?.invoke(key, task.data) == true) {
                    successCount++
                }
            }
            successCount
        }
    }

    fun setWriteHandler(handler: suspend (CacheKey, Any?) -> Boolean) {
        this.writeHandler = handler
    }

    override suspend fun <T : Any> get(key: CacheKey): T? {
        trackAccess(key)

        val memoryEntry = memoryCache.get(key.toString())
        if (memoryEntry != null) {
            hitCount.incrementAndGet()
            @Suppress("UNCHECKED_CAST")
            return memoryEntry.value as? T
        }

        val diskEntry = diskCache.get(key.toString())
        if (diskEntry != null) {
            hitCount.incrementAndGet()
            
            val value = diskEntry.value
            memoryCache.put(key.toString(), CacheEntry.create(value, config.ttlMs))
            
            @Suppress("UNCHECKED_CAST")
            return value as? T
        }

        missCount.incrementAndGet()
        return null
    }

    override suspend fun <T : Any> put(key: CacheKey, value: T): Boolean {
        val entry = CacheEntry.create(value, config.ttlMs)
        
        memoryCache.put(key.toString(), entry)
        
        val serialized = serializeValue(value)
        if (serialized != null) {
            val compressed = compressor.compress(serialized)
            val saved = serialized.size - compressed.data.size
            compressionSavedBytes.addAndGet(saved.toLong())
            diskCache.put(key.toString(), CacheEntry.create(compressed, config.ttlMs))
        } else {
            diskCache.put(key.toString(), entry)
        }
        
        dirtyTracker.markInsert(key.toString(), value)
        
        if (config.writeStrategy == WriteStrategy.IMMEDIATE) {
            writeQueue.enqueue(WriteTask.insert(key.toString(), value))
        }
        
        Log.d(TAG, "Put: $key to warm zone")
        return true
    }

    override suspend fun remove(key: CacheKey): Boolean {
        memoryCache.remove(key.toString())
        diskCache.remove(key.toString())
        accessTracker.remove(key.toString())
        
        dirtyTracker.markDelete(key.toString())
        
        if (config.writeStrategy == WriteStrategy.IMMEDIATE) {
            writeQueue.enqueue(WriteTask.delete(key.toString()))
        }
        
        Log.d(TAG, "Removed: $key from warm zone")
        return true
    }

    override suspend fun contains(key: CacheKey): Boolean {
        return memoryCache.contains(key.toString()) || diskCache.contains(key.toString())
    }

    override suspend fun clear() {
        memoryCache.clear()
        diskCache.clear()
        accessTracker.clear()
        dirtyTracker.clear()
        writeQueue.clear()
        Log.i(TAG, "Warm zone cleared")
    }

    suspend fun <T : Any> getOrLoad(key: CacheKey, loader: suspend () -> T): T {
        val cached = get<T>(key)
        if (cached != null) {
            return cached
        }

        val value = loader()
        put(key, value)
        return value
    }

    suspend fun preload(keys: List<CacheKey>, loader: suspend (CacheKey) -> Any?): Int {
        var loaded = 0
        keys.forEach { key ->
            if (!contains(key)) {
                val value = loader(key)
                if (value != null) {
                    put(key, value)
                    loaded++
                }
            }
        }
        Log.i(TAG, "Preloaded $loaded/${keys.size} entries")
        return loaded
    }

    suspend fun flush(): Int {
        return writeQueue.flush()
    }

    fun startWriteProcessing() {
        writeQueue.startProcessing()
        Log.i(TAG, "Write processing started")
    }

    fun stopWriteProcessing() {
        writeQueue.stopProcessing()
        Log.i(TAG, "Write processing stopped")
    }

    fun cleanExpired(): Int {
        val memoryCleaned = memoryCache.cleanExpired()
        val diskCleaned = diskCache.cleanExpired()
        val total = memoryCleaned + diskCleaned
        
        if (total > 0) {
            Log.d(TAG, "Cleaned $total expired entries (memory: $memoryCleaned, disk: $diskCleaned)")
        }
        return total
    }

    fun getAccessInfo(key: CacheKey): AccessInfo? {
        return accessTracker[key.toString()]
    }

    fun getAccessFrequency(key: CacheKey): AccessFrequency {
        val info = accessTracker[key.toString()] ?: return AccessFrequency.RARE
        val accessesPerHour = info.getAccessesPerHour()
        
        return when {
            accessesPerHour >= 60 -> AccessFrequency.HIGH
            accessesPerHour >= 10 -> AccessFrequency.MEDIUM
            accessesPerHour >= 1 -> AccessFrequency.LOW
            else -> AccessFrequency.RARE
        }
    }

    private fun trackAccess(key: CacheKey) {
        val info = accessTracker.getOrPut(key.toString()) {
            AccessInfo(key = key.toString())
        }
        info.recordAccess()
    }

    @Suppress("UNCHECKED_CAST")
    private fun serializeValue(value: Any?): ByteArray? {
        if (value == null) return null
        
        return when (value) {
            is ByteArray -> value
            is String -> value.toByteArray(Charsets.UTF_8)
            is CacheEntry -> value.serialize()
            is com.xianxia.sect.data.cache.CompressedData -> value.toStorageFormat()
            else -> {
                try {
                    val kClass = value::class
                    val serializer = serializerCache.getOrPut(kClass) {
                        kClass.serializer()
                    } as KSerializer<Any>
                    json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to serialize value", e)
                    null
                }
            }
        }
    }

    override fun getStats(): ZoneStats {
        val memoryStats = memoryCache.getStats()
        val diskStats = diskCache.getStats()
        
        return ZoneStats(
            zone = zone,
            entryCount = getEntryCount(),
            totalSizeBytes = memoryStats.first.toLong() + diskStats.totalSize,
            maxSizeBytes = config.maxSizeBytes,
            hitCount = hitCount.get(),
            missCount = missCount.get(),
            evictionCount = evictionCount.get(),
            compressionSavedBytes = compressionSavedBytes.get(),
            averageAccessTimeMs = calculateAverageAccessTime()
        )
    }

    override fun getEntryCount(): Int {
        return memoryCache.snapshot().size + diskCache.count().toInt()
    }

    override fun getSizeBytes(): Long {
        return memoryCache.size().toLong() + diskCache.size()
    }

    private fun calculateAverageAccessTime(): Double {
        if (accessTracker.isEmpty()) return 0.0
        
        val times = accessTracker.values.map { it.getAverageAccessInterval() }
        return if (times.isNotEmpty()) times.average() else 0.0
    }

    fun getColdDataKeys(thresholdMs: Long): Set<String> {
        val now = System.currentTimeMillis()
        return accessTracker.entries
            .filter { now - it.value.lastAccessTime > thresholdMs }
            .map { it.key }
            .toSet()
    }

    fun getActiveKeys(thresholdMs: Long): Set<String> {
        val now = System.currentTimeMillis()
        return accessTracker.entries
            .filter { now - it.value.lastAccessTime <= thresholdMs }
            .map { it.key }
            .toSet()
    }

    fun getMemoryCacheStats(): Pair<Int, Int> = memoryCache.getStats()
    
    fun getDiskCacheStats(): DiskCacheStats = diskCache.getStats()

    override fun shutdown() {
        stopWriteProcessing()
        scope.cancel()
        accessTracker.clear()
        Log.i(TAG, "WarmZoneManager shutdown complete")
    }
}
