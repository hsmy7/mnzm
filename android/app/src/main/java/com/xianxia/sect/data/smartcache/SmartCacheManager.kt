@file:OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)

package com.xianxia.sect.data.smartcache

import android.content.Context
import android.util.Log
import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.cache.CacheKey
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
        "battle_log" to COLD_DATA_TTL_MS * 7,
        "event" to COLD_DATA_TTL_MS * 7
    )
}

enum class CachePriority {
    CRITICAL,
    HIGH,
    NORMAL,
    LOW,
    BACKGROUND
}

enum class CacheStrategy {
    WRITE_THROUGH,
    WRITE_BEHIND,
    WRITE_AROUND,
    READ_THROUGH,
    CACHE_ASIDE
}

data class CachePolicy(
    val priority: CachePriority,
    val ttl: Long,
    val strategy: CacheStrategy,
    val maxSize: Int = 100,
    val compress: Boolean = false,
    val evictOnLowMemory: Boolean = true
)

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

class SmartCacheManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "SmartCacheManager"
    }

    private val memoryCache = ConcurrentHashMap<String, CacheEntry<Any>>()
    private val diskCache = DiskCacheLayer(context)
    
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

    fun shutdown() {
        isShuttingDown = true
        
        cleanupJob?.cancel()
        statsJob?.cancel()
        flushJob?.cancel()
        
        runBlocking {
            flushWriteBehindQueue()
        }
        
        diskCache.shutdown()
        Log.i(TAG, "SmartCacheManager shutdown completed")
    }
}

class DiskCacheLayer(private val context: Context) {
    private val cacheDir = File(context.cacheDir, "smart_cache")
    private val index = ConcurrentHashMap<String, DiskCacheEntry>()
    
    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }
    
    private val lz4Compressor = net.jpountz.lz4.LZ4Factory.fastestInstance().fastCompressor()
    private val lz4Decompressor = net.jpountz.lz4.LZ4Factory.fastestInstance().fastDecompressor()
    
    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        loadIndex()
    }
    
    data class DiskCacheEntry(
        val key: String,
        val file: File,
        val createdAt: Long,
        val ttl: Long,
        val size: Long,
        val typeName: String = ""
    )
    
    fun put(key: String, value: Any, ttl: Long) {
        try {
            val file = File(cacheDir, key.replace(":", "_").replace("/", "_"))
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
            
            saveIndex()
        } catch (e: Exception) {
            Log.e("DiskCacheLayer", "Failed to put cache entry: $key", e)
        }
    }
    
    fun get(key: String): Any? {
        val entry = index[key] ?: return null
        
        if (System.currentTimeMillis() - entry.createdAt > entry.ttl) {
            remove(key)
            return null
        }
        
        return try {
            val bytes = entry.file.readBytes()
            deserialize(bytes, entry.typeName)
        } catch (e: Exception) {
            null
        }
    }
    
    fun remove(key: String) {
        val entry = index.remove(key)
        entry?.file?.delete()
    }
    
    fun contains(key: String): Boolean {
        val entry = index[key] ?: return false
        return System.currentTimeMillis() - entry.createdAt <= entry.ttl
    }
    
    fun clear() {
        index.values.forEach { it.file.delete() }
        index.clear()
        saveIndex()
    }
    
    fun invalidatePattern(pattern: String) {
        val toRemove = index.keys.filter { it.contains(pattern) }
        toRemove.forEach { remove(it) }
    }
    
    fun cleanupExpired() {
        val now = System.currentTimeMillis()
        val expired = index.entries.filter { now - it.value.createdAt > it.value.ttl }
        expired.forEach { remove(it.key) }
    }
    
    fun flush() {
        saveIndex()
    }
    
    private fun loadIndex() {
        val indexFile = File(cacheDir, "index")
        if (indexFile.exists()) {
            try {
                val lines: List<String> = indexFile.readLines()
                for (line in lines) {
                    val parts = line.split("|")
                    if (parts.size >= 5) {
                        val key = parts[0]
                        val file = File(cacheDir, parts[1])
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
                        val file = File(cacheDir, parts[1])
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
    
    private fun saveIndex() {
        val indexFile = File(cacheDir, "index")
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
            Log.w("DiskCacheLayer", "Failed to serialize with Kotlinx, falling back to toString: ${e.message}")
            when (value) {
                is String -> "\"$value\""
                is Number -> value.toString()
                is Boolean -> value.toString()
                else -> {
                    Log.e("DiskCacheLayer", "Cannot serialize type: $typeName")
                    return ByteArray(0)
                }
            }
        }
        val jsonBytes = jsonStr.toByteArray(Charsets.UTF_8)
        
        val maxCompressed = lz4Compressor.maxCompressedLength(jsonBytes.size)
        val compressed = ByteArray(maxCompressed)
        val compressedSize = lz4Compressor.compress(jsonBytes, 0, jsonBytes.size, compressed, 0)
        
        val baos = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(baos)
        
        dos.writeShort(UnifiedSerializationConstants.SmartCache.MAGIC_HEADER.toInt())
        dos.writeByte(UnifiedSerializationConstants.SmartCache.FORMAT_VERSION.toInt())
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
            if (magic != UnifiedSerializationConstants.SmartCache.MAGIC_HEADER) {
                val jsonStr = String(bytes, Charsets.UTF_8)
                return deserializeByTypeName(jsonStr, expectedTypeName)
            }
            
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
    
    fun shutdown() {
        saveIndex()
    }
}
