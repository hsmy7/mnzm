package com.xianxia.sect.data.cache

import android.content.Context
import android.util.Log
import com.xianxia.sect.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

data class UnifiedCacheEntry<T>(
    val data: T,
    val key: String,
    val createdAt: Long,
    val lastAccessedAt: Long,
    val accessCount: Int,
    val size: Int,
    val priority: CachePriority,
    val ttl: Long,
    val zone: CacheZone,
    val isDirty: Boolean = false
)

data class UnifiedCacheStats(
    val hitCount: Long = 0,
    val missCount: Long = 0,
    val evictionCount: Long = 0,
    val totalSize: Long = 0,
    val entryCount: Int = 0,
    val hitRate: Float = 0f,
    val pendingWrites: Int = 0,
    val memoryUsage: Long = 0,
    val hotZoneSize: Long = 0,
    val warmZoneSize: Long = 0,
    val coldZoneSize: Long = 0
)

data class DiskCacheEntry(
    val key: String,
    val file: File,
    val createdAt: Long,
    val ttl: Long,
    val size: Long
)

@Singleton
class UnifiedCacheManager @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "UnifiedCacheManager"
        private const val DISK_CACHE_DIR = "unified_cache"
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val memoryCache = ConcurrentHashMap<String, UnifiedCacheEntry<Any>>()
    private val diskCacheIndex = ConcurrentHashMap<String, DiskCacheEntry>()
    private val writeBehindQueue = ConcurrentHashMap<String, Any>()
    private val dirtyKeys = ConcurrentHashMap.newKeySet<String>()
    
    private val hitCount = AtomicLong(0)
    private val missCount = AtomicLong(0)
    private val evictionCount = AtomicLong(0)
    private val totalSize = AtomicLong(0)
    
    private val hotZoneSize = AtomicLong(0)
    private val warmZoneSize = AtomicLong(0)
    private val coldZoneSize = AtomicLong(0)
    
    private val _stats = MutableStateFlow(UnifiedCacheStats())
    val stats: StateFlow<UnifiedCacheStats> = _stats.asStateFlow()
    
    private val policies = ConcurrentHashMap<String, CachePolicy>()
    
    private var cleanupJob: Job? = null
    private var statsJob: Job? = null
    private var flushJob: Job? = null
    private var isShuttingDown = false
    
    private val diskCacheDir: File = File(context.cacheDir, DISK_CACHE_DIR)
    
    init {
        if (!diskCacheDir.exists()) {
            diskCacheDir.mkdirs()
        }
        loadDiskIndex()
        initializePolicies()
        startBackgroundTasks()
    }
    
    private fun initializePolicies() {
        CacheTypeConfig.DATA_TYPE_PRIORITIES.forEach { (type, priority) ->
            val ttl = CacheTypeConfig.DATA_TYPE_TTL[type] ?: CacheTypeConfig.WARM_DATA_TTL_MS
            val strategy = CacheTypeConfig.DATA_TYPE_STRATEGY[type] ?: CacheStrategy.WRITE_BEHIND
            
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
                delay(CacheTypeConfig.CLEANUP_INTERVAL_MS)
                performCleanup()
            }
        }
        
        statsJob = scope.launch {
            while (isActive && !isShuttingDown) {
                delay(CacheTypeConfig.STATS_UPDATE_INTERVAL_MS)
                updateStats()
            }
        }
        
        flushJob = scope.launch {
            while (isActive && !isShuttingDown) {
                delay(CacheTypeConfig.FLUSH_INTERVAL_MS)
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
                updateZoneSize(entry.zone, -entry.size.toLong())
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
                updateZoneSize(entry.zone, -entry.size.toLong())
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
    
    fun put(key: CacheKey, value: Any, customTTL: Long? = null, customZone: CacheZone? = null) {
        val cacheKey = key.toString()
        val policy = policies[key.type] ?: getDefaultPolicy()
        val ttl = customTTL ?: policy.ttl
        val targetZone = customZone ?: getZoneForPriority(policy.priority)
        val size = estimateSize(value)
        
        ensureCapacity(policy, size, targetZone)
        
        val entry = UnifiedCacheEntry(
            data = value,
            key = cacheKey,
            createdAt = System.currentTimeMillis(),
            lastAccessedAt = System.currentTimeMillis(),
            accessCount = 1,
            size = size,
            priority = policy.priority,
            ttl = ttl,
            zone = targetZone
        )
        
        val existing = memoryCache.put(cacheKey, entry)
        if (existing != null) {
            totalSize.addAndGet(-existing.size.toLong())
            updateZoneSize(existing.zone, -existing.size.toLong())
        }
        totalSize.addAndGet(size.toLong())
        updateZoneSize(targetZone, size.toLong())
        
        when (policy.strategy) {
            CacheStrategy.WRITE_THROUGH -> {
                writeToDisk(cacheKey, value, ttl)
            }
            CacheStrategy.WRITE_BEHIND -> {
                writeBehindQueue[cacheKey] = value
                dirtyKeys.add(cacheKey)
            }
            CacheStrategy.WRITE_AROUND -> {
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
            updateZoneSize(entry.zone, -entry.size.toLong())
        }
        deleteFromDisk(cacheKey)
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
        hotZoneSize.set(0)
        warmZoneSize.set(0)
        coldZoneSize.set(0)
        clearDiskCache()
    }
    
    fun flush() {
        flushWriteBehindQueue()
        saveDiskIndex()
    }
    
    fun invalidateSlot(slot: Int) {
        val slotPattern1 = "_${slot}_"
        val slotPattern2 = "_$slot"
        
        val keysToRemove = memoryCache.keys.filter { 
            it.contains(slotPattern1) || it.endsWith(slotPattern2)
        }
        
        keysToRemove.forEach { key ->
            val entry = memoryCache.remove(key)
            if (entry != null) {
                totalSize.addAndGet(-entry.size.toLong())
                updateZoneSize(entry.zone, -entry.size.toLong())
            }
        }
        
        invalidateDiskPattern(slotPattern1)
        invalidateDiskPattern(slotPattern2)
    }
    
    suspend fun warmupCriticalData(data: GameData) {
        val key = CacheKey(type = "game_data", slot = data.currentSlot, id = "current")
        put(key, data)
    }
    
    fun warmupByTypes(slot: Int, dataTypes: List<String>) {
        dataTypes.forEach { type ->
            val policy = policies[type] ?: return@forEach
            if (policy.priority == CachePriority.CRITICAL || policy.priority == CachePriority.HIGH) {
            }
        }
    }
    
    suspend fun verifyIntegrity(slot: Int): Boolean {
        return try {
            val key = CacheKey(type = "game_data", slot = slot, id = "current")
            val entry = memoryCache[key.toString()]
            entry != null && !isExpired(entry)
        } catch (e: Exception) {
            Log.e(TAG, "Cache integrity check failed for slot $slot", e)
            false
        }
    }
    
    fun getStats(): UnifiedCacheStats {
        updateStats()
        return _stats.value
    }
    
    private fun ensureCapacity(policy: CachePolicy, requiredSize: Int, zone: CacheZone) {
        val maxSizeBytes = getMaxMemorySize(zone)
        
        while (totalSize.get() + requiredSize > maxSizeBytes && memoryCache.isNotEmpty()) {
            evictOneEntry(policy, zone)
        }
    }
    
    private fun getMaxMemorySize(zone: CacheZone): Long {
        val baseSize = CacheTypeConfig.DEFAULT_MEMORY_CACHE_SIZE
        return when (zone) {
            CacheZone.HOT -> (baseSize * 0.4).toLong()
            CacheZone.WARM -> (baseSize * 0.35).toLong()
            CacheZone.COLD -> (baseSize * 0.25).toLong()
        }
    }
    
    private fun evictOneEntry(policy: CachePolicy, zone: CacheZone) {
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
            updateZoneSize(toEvict.value.zone, -toEvict.value.size.toLong())
            evictionCount.incrementAndGet()
            
            if (toEvict.value.isDirty || dirtyKeys.contains(toEvict.key)) {
                writeToDisk(toEvict.key, toEvict.value.data, toEvict.value.ttl)
            }
        }
    }
    
    private fun updateZoneSize(zone: CacheZone, delta: Long) {
        when (zone) {
            CacheZone.HOT -> hotZoneSize.addAndGet(delta)
            CacheZone.WARM -> warmZoneSize.addAndGet(delta)
            CacheZone.COLD -> coldZoneSize.addAndGet(delta)
        }
    }
    
    private fun isExpired(entry: UnifiedCacheEntry<Any>): Boolean {
        return System.currentTimeMillis() - entry.createdAt > entry.ttl
    }
    
    private fun updateAccessInfo(cacheKey: String, entry: UnifiedCacheEntry<Any>) {
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
                updateZoneSize(entry.zone, -entry.size.toLong())
                evictionCount.incrementAndGet()
            }
        }
        
        cleanupDiskCache()
        
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
                    writeToDisk(key, value, entry.ttl)
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
        
        _stats.value = UnifiedCacheStats(
            hitCount = hits,
            missCount = misses,
            evictionCount = evictionCount.get(),
            totalSize = totalSize.get(),
            entryCount = memoryCache.size,
            hitRate = if (total > 0) hits.toFloat() / total else 0f,
            pendingWrites = writeBehindQueue.size,
            memoryUsage = totalSize.get(),
            hotZoneSize = hotZoneSize.get(),
            warmZoneSize = warmZoneSize.get(),
            coldZoneSize = coldZoneSize.get()
        )
    }
    
    private fun estimateSize(value: Any): Int {
        return when (value) {
            is GameData -> {
                var size = 1024
                size += value.sectName.toByteArray().size
                size += 64
                size
            }
            is Disciple -> {
                var size = 256
                size += value.name.toByteArray().size
                size += value.manualIds.size * 16
                size += value.talentIds.size * 8
                size.coerceAtLeast(128)
            }
            is Equipment -> {
                var size = 128
                size += value.name.toByteArray().size
                size += value.description.toByteArray().size
                size.coerceAtLeast(64)
            }
            is Manual -> 128
            is Pill -> 64
            is Material -> 32
            is Herb -> 32
            is Seed -> 32
            is BattleLog -> {
                var size = 128
                size += value.details.toByteArray().size
                size += value.teamMembers.size * 32
                size += value.enemies.size * 32
                size.coerceAtLeast(64)
            }
            is GameEvent -> {
                var size = 64
                size += value.message.toByteArray().size
                size.coerceAtLeast(32)
            }
            else -> 64
        }
    }
    
    private fun getDefaultPolicy(): CachePolicy {
        return CachePolicy(
            priority = CachePriority.NORMAL,
            ttl = CacheTypeConfig.WARM_DATA_TTL_MS,
            strategy = CacheStrategy.WRITE_BEHIND
        )
    }
    
    private fun getZoneForPriority(priority: CachePriority): CacheZone {
        return when (priority) {
            CachePriority.CRITICAL -> CacheZone.HOT
            CachePriority.HIGH -> CacheZone.HOT
            CachePriority.NORMAL -> CacheZone.WARM
            CachePriority.LOW -> CacheZone.WARM
            CachePriority.BACKGROUND -> CacheZone.COLD
        }
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
            if (evictedSize < CacheTypeConfig.DEFAULT_MEMORY_CACHE_SIZE / 2) {
                memoryCache.remove(entry.key)
                totalSize.addAndGet(-entry.value.size.toLong())
                updateZoneSize(entry.value.zone, -entry.value.size.toLong())
                evictionCount.incrementAndGet()
                evictedSize += entry.value.size
                
                if (entry.value.isDirty || dirtyKeys.contains(entry.key)) {
                    writeToDisk(entry.key, entry.value.data, entry.value.ttl)
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
        scope.cancel()
        
        runBlocking {
            flushWriteBehindQueue()
            saveDiskIndex()
        }
        
        Log.i(TAG, "UnifiedCacheManager shutdown completed")
    }
    
    private fun writeToDisk(key: String, value: Any, ttl: Long) {
        try {
            val sanitizedKey = sanitizeKey(key)
            val file = File(diskCacheDir, sanitizedKey)
            val bytes = serializeValue(value)
            file.writeBytes(bytes)
            
            diskCacheIndex[key] = DiskCacheEntry(
                key = key,
                file = file,
                createdAt = System.currentTimeMillis(),
                ttl = ttl,
                size = bytes.size.toLong()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to disk: $key", e)
        }
    }
    
    private fun deleteFromDisk(key: String) {
        try {
            val entry = diskCacheIndex.remove(key)
            entry?.file?.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete from disk: $key", e)
        }
    }
    
    private fun invalidateDiskPattern(pattern: String) {
        try {
            val sanitizedPattern = sanitizeKey(pattern)
            diskCacheIndex.keys.toList().forEach { key ->
                if (key.contains(sanitizedPattern)) {
                    deleteFromDisk(key)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to invalidate disk pattern: $pattern", e)
        }
    }
    
    private fun cleanupDiskCache() {
        try {
            val now = System.currentTimeMillis()
            diskCacheIndex.entries.toList().forEach { (key, entry) ->
                if (now - entry.createdAt > entry.ttl) {
                    deleteFromDisk(key)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup disk cache", e)
        }
    }
    
    private fun clearDiskCache() {
        try {
            diskCacheDir.listFiles()?.forEach { it.delete() }
            diskCacheIndex.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear disk cache", e)
        }
    }
    
    private fun loadDiskIndex() {
        try {
            val indexFile = File(diskCacheDir, "index")
            if (!indexFile.exists()) return
            
            indexFile.readLines().forEach { line ->
                val parts = line.split("|")
                if (parts.size >= 5) {
                    val key = parts[0]
                    val file = File(diskCacheDir, parts[1])
                    if (file.exists()) {
                        diskCacheIndex[key] = DiskCacheEntry(
                            key = key,
                            file = file,
                            createdAt = parts[2].toLongOrNull() ?: 0L,
                            ttl = parts[3].toLongOrNull() ?: 0L,
                            size = parts[4].toLongOrNull() ?: 0L
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load disk index", e)
        }
    }
    
    private fun saveDiskIndex() {
        try {
            val indexFile = File(diskCacheDir, "index")
            val lines = diskCacheIndex.values.map { entry ->
                "${entry.key}|${entry.file.name}|${entry.createdAt}|${entry.ttl}|${entry.size}"
            }
            indexFile.writeText(lines.joinToString("\n"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save disk index", e)
        }
    }
    
    private fun sanitizeKey(key: String): String {
        return key.replace(":", "_").replace("/", "_").replace("\\", "_")
    }
    
    private fun serializeValue(value: Any): ByteArray {
        return when (value) {
            is String -> value.toByteArray(Charsets.UTF_8)
            is ByteArray -> value
            else -> value.toString().toByteArray(Charsets.UTF_8)
        }
    }
}

