@file:OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)

package com.xianxia.sect.data.partition

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.cache.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.reflect.KClass

class ColdZoneManager(
    private val context: Context,
    override val config: ZoneConfig = ZoneConfig.COLD_DEFAULT,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : ZoneManager {

    companion object {
        private const val TAG = "ColdZoneManager"
        private const val CACHE_ID = "cold_zone_cache"
    }

    override val zone: DataZone = DataZone.COLD

    private val memoryCache: MemoryCache by lazy {
        MemoryCache((config.maxSizeBytes * 0.05).toInt())
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
            memoryCacheSize = (config.maxSizeBytes * 0.05).toLong(),
            diskCacheSize = config.maxSizeBytes,
            writeBatchSize = 200,
            writeDelayMs = 5000
        ),
        scope = scope
    )

    private val accessTracker = ConcurrentHashMap<String, AccessInfo>()
    private val hitCount = AtomicLong(0)
    private val missCount = AtomicLong(0)
    private val evictionCount = AtomicLong(0)
    private val compressionSavedBytes = AtomicLong(0)
    private val startTime = System.currentTimeMillis()

    private var databaseLoader: (suspend (CacheKey) -> Any?)? = null
    private var writeHandler: (suspend (CacheKey, Any?) -> Boolean)? = null

    init {
        memoryCache.addEvictionListener { key, _ ->
            evictionCount.incrementAndGet()
            Log.d(TAG, "Entry evicted from cold memory cache: $key")
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

    fun setDatabaseLoader(loader: suspend (CacheKey) -> Any?) {
        this.databaseLoader = loader
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
            
            val value = decompressIfNeeded(diskEntry.value)
            memoryCache.put(key.toString(), CacheEntry.create(value, config.ttlMs))
            
            @Suppress("UNCHECKED_CAST")
            return value as? T
        }

        missCount.incrementAndGet()
        return null
    }

    suspend fun <T : Any> getOrLoadFromDatabase(key: CacheKey): T? {
        val cached = get<T>(key)
        if (cached != null) {
            return cached
        }

        val loader = databaseLoader ?: return null
        val value = loader(key) ?: return null
        
        put(key, value)
        
        @Suppress("UNCHECKED_CAST")
        return value as? T
    }

    override suspend fun <T : Any> put(key: CacheKey, value: T): Boolean {
        val entry = CacheEntry.create(value, config.ttlMs)
        
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
        
        when (config.writeStrategy) {
            WriteStrategy.IMMEDIATE -> writeQueue.enqueue(WriteTask.insert(key.toString(), value))
            WriteStrategy.BATCHED -> writeQueue.enqueue(WriteTask.insert(key.toString(), value))
            WriteStrategy.DEFERRED -> { }
            WriteStrategy.MANUAL -> { }
        }
        
        Log.d(TAG, "Put: $key to cold zone")
        return true
    }

    override suspend fun remove(key: CacheKey): Boolean {
        memoryCache.remove(key.toString())
        diskCache.remove(key.toString())
        accessTracker.remove(key.toString())
        
        dirtyTracker.markDelete(key.toString())
        
        Log.d(TAG, "Removed: $key from cold zone")
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
        Log.i(TAG, "Cold zone cleared")
    }

    suspend fun loadBatch(keys: List<CacheKey>, batchSize: Int = 50): Int {
        if (databaseLoader == null) return 0
        
        var loaded = 0
        keys.chunked(batchSize).forEach { batch ->
            batch.forEach { key ->
                if (!contains(key)) {
                    val value = databaseLoader?.invoke(key)
                    if (value != null) {
                        put(key, value)
                        loaded++
                    }
                }
            }
        }
        
        Log.i(TAG, "Loaded batch: $loaded/${keys.size} entries")
        return loaded
    }

    suspend fun loadPage(
        dataType: DataType,
        page: Int,
        pageSize: Int,
        keyGenerator: (Int) -> CacheKey
    ): List<Any> {
        val results = mutableListOf<Any>()
        val start = page * pageSize
        
        for (i in start until start + pageSize) {
            val key = keyGenerator(i)
            val value = getOrLoadFromDatabase<Any>(key)
            if (value != null) {
                results.add(value)
            }
        }
        
        Log.d(TAG, "Loaded page $page for ${dataType.displayName}: ${results.size} entries")
        return results
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
            Log.d(TAG, "Cleaned $total expired entries")
        }
        return total
    }

    suspend fun archiveOldData(olderThanMs: Long): Int {
        val threshold = System.currentTimeMillis() - olderThanMs
        var archived = 0
        
        val allKeys = accessTracker.entries
            .filter { it.value.lastAccessTime < threshold }
            .map { it.key }
        
        allKeys.forEach { key ->
            memoryCache.remove(key)
            archived++
        }
        
        Log.i(TAG, "Archived $archived old entries")
        return archived
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

    @Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
    private inline fun decompressIfNeeded(value: Any?): Any? {
        if (value == null) return null
        
        return when (value) {
            is com.xianxia.sect.data.cache.CompressedData -> {
                val decompressed = compressor.decompress(value)
                try {
                    val jsonStr = decompressed.toString(Charsets.UTF_8)
                    val jsonElement = json.parseToJsonElement(jsonStr)
                    if (jsonElement is kotlinx.serialization.json.JsonObject) {
                        val typeField = jsonElement["type"]?.jsonPrimitive?.content
                        if (typeField != null) {
                            json.decodeFromString<kotlinx.serialization.json.JsonElement>(jsonStr)
                        } else {
                            jsonStr
                        }
                    } else {
                        json.decodeFromString<kotlinx.serialization.json.JsonElement>(jsonStr)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to deserialize decompressed value", e)
                    decompressed
                }
            }
            is ByteArray -> {
                try {
                    val jsonStr = value.toString(Charsets.UTF_8)
                    json.parseToJsonElement(jsonStr)
                } catch (e: Exception) {
                    value
                }
            }
            else -> value
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

    fun getPromotableKeys(thresholdMs: Long): Set<String> {
        val now = System.currentTimeMillis()
        return accessTracker.entries
            .filter { 
                val freq = getAccessFrequency(CacheKey.fromString(it.key))
                freq == AccessFrequency.HIGH && now - it.value.lastAccessTime <= thresholdMs
            }
            .map { it.key }
            .toSet()
    }

    fun getOldDataKeys(olderThanMs: Long): Set<String> {
        val threshold = System.currentTimeMillis() - olderThanMs
        return accessTracker.entries
            .filter { it.value.lastAccessTime < threshold }
            .map { it.key }
            .toSet()
    }

    fun getMemoryCacheStats(): Pair<Int, Int> = memoryCache.getStats()
    
    fun getDiskCacheStats(): DiskCacheStats = diskCache.getStats()

    fun getCompressionStats(): CompressionStats {
        return CompressionStats(
            totalSavedBytes = compressionSavedBytes.get(),
            algorithm = config.compressionType
        )
    }

    override fun shutdown() {
        stopWriteProcessing()
        scope.cancel()
        accessTracker.clear()
        Log.i(TAG, "ColdZoneManager shutdown complete")
    }
}

data class CompressionStats(
    val totalSavedBytes: Long,
    val algorithm: CompressionType
) {
    val savedMB: Double
        get() = totalSavedBytes / (1024.0 * 1024.0)
}
