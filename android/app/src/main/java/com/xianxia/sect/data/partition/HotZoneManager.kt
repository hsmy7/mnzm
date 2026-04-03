package com.xianxia.sect.data.partition

import android.util.Log
import com.xianxia.sect.data.cache.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

interface ZoneManager {
    val zone: DataZone
    val config: ZoneConfig
    
    suspend fun <T : Any> get(key: CacheKey): T?
    suspend fun <T : Any> put(key: CacheKey, value: T): Boolean
    suspend fun remove(key: CacheKey): Boolean
    suspend fun contains(key: CacheKey): Boolean
    suspend fun clear()
    
    fun getStats(): ZoneStats
    fun getEntryCount(): Int
    fun getSizeBytes(): Long
    
    fun shutdown()
}

class HotZoneManager(
    override val config: ZoneConfig = ZoneConfig.HOT_DEFAULT,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) : ZoneManager {

    companion object {
        private const val TAG = "HotZoneManager"
    }

    override val zone: DataZone = DataZone.HOT

    private val memoryCache = MemoryCache(config.maxSizeBytes.toInt())
    private val dirtyTracker = DirtyTracker()
    private val writeQueue = WriteQueue(
        config = CacheConfig(
            memoryCacheSize = config.maxSizeBytes,
            writeBatchSize = 50,
            writeDelayMs = 100
        ),
        scope = scope
    )

    private val accessTracker = ConcurrentHashMap<String, AccessInfo>()
    private val hitCount = AtomicLong(0)
    private val missCount = AtomicLong(0)
    private val evictionCount = AtomicLong(0)
    private val startTime = System.currentTimeMillis()

    private var writeHandler: (suspend (CacheKey, Any?) -> Boolean)? = null

    init {
        memoryCache.addEvictionListener { key, _ ->
            evictionCount.incrementAndGet()
            val cacheKey = CacheKey.fromString(key)
            accessTracker.remove(cacheKey.toString())
            Log.d(TAG, "Entry evicted from hot zone: $key")
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
        
        val entry = memoryCache.get(key.toString())
        return if (entry != null) {
            hitCount.incrementAndGet()
            @Suppress("UNCHECKED_CAST")
            entry.value as? T
        } else {
            missCount.incrementAndGet()
            null
        }
    }

    override suspend fun <T : Any> put(key: CacheKey, value: T): Boolean {
        val entry = CacheEntry.create(value, config.ttlMs)
        memoryCache.put(key.toString(), entry)
        
        dirtyTracker.markInsert(key.toString(), value)
        
        if (config.writeStrategy == WriteStrategy.IMMEDIATE) {
            writeQueue.enqueue(WriteTask.insert(key.toString(), value))
        }
        
        Log.d(TAG, "Put: $key, size=${entry.size}")
        return true
    }

    override suspend fun remove(key: CacheKey): Boolean {
        val entry = memoryCache.remove(key.toString())
        accessTracker.remove(key.toString())
        
        if (entry != null) {
            dirtyTracker.markDelete(key.toString())
            
            if (config.writeStrategy == WriteStrategy.IMMEDIATE) {
                writeQueue.enqueue(WriteTask.delete(key.toString()))
            }
            
            Log.d(TAG, "Removed: $key")
            return true
        }
        return false
    }

    override suspend fun contains(key: CacheKey): Boolean {
        return memoryCache.contains(key.toString())
    }

    override suspend fun clear() {
        memoryCache.clear()
        accessTracker.clear()
        dirtyTracker.clear()
        writeQueue.clear()
        Log.i(TAG, "Hot zone cleared")
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

    suspend fun <T : Any> update(key: CacheKey, updater: (T?) -> T): Boolean {
        @Suppress("UNCHECKED_CAST")
        val current = get<T>(key)
        val updated = updater(current)
        return put(key, updated)
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

    fun getDirtyKeys(): Set<String> {
        return dirtyTracker.getDirtyKeys()
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

    override fun getStats(): ZoneStats {
        val (size, _) = memoryCache.getStats()
        val writeStats = writeQueue.getStats()
        
        return ZoneStats(
            zone = zone,
            entryCount = getEntryCount(),
            totalSizeBytes = size.toLong(),
            maxSizeBytes = config.maxSizeBytes,
            hitCount = hitCount.get(),
            missCount = missCount.get(),
            evictionCount = evictionCount.get(),
            compressionSavedBytes = 0,
            averageAccessTimeMs = calculateAverageAccessTime()
        )
    }

    override fun getEntryCount(): Int {
        return memoryCache.snapshot().size
    }

    override fun getSizeBytes(): Long {
        return memoryCache.size().toLong()
    }

    private fun calculateAverageAccessTime(): Double {
        if (accessTracker.isEmpty()) return 0.0
        
        val times = accessTracker.values.map { it.getAverageAccessInterval() }
        return if (times.isNotEmpty()) times.average() else 0.0
    }

    fun getHotDataKeys(): Set<String> {
        return accessTracker.entries
            .filter { getAccessFrequency(CacheKey.fromString(it.key)) == AccessFrequency.HIGH }
            .map { it.key }
            .toSet()
    }

    fun getInactiveKeys(thresholdMs: Long): Set<String> {
        val now = System.currentTimeMillis()
        return accessTracker.entries
            .filter { now - it.value.lastAccessTime > thresholdMs }
            .map { it.key }
            .toSet()
    }

    override fun shutdown() {
        stopWriteProcessing()
        scope.cancel()
        accessTracker.clear()
        Log.i(TAG, "HotZoneManager shutdown complete")
    }
}

class AccessInfo(
    val key: String,
    private val _accessCount: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger(0),
    val firstAccessTime: Long = System.currentTimeMillis(),
    private val _lastAccessTime: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis()),
    private val accessTimes: java.util.concurrent.ConcurrentLinkedQueue<Long> = java.util.concurrent.ConcurrentLinkedQueue()
) {
    val accessCount: Int get() = _accessCount.get()
    val lastAccessTime: Long get() = _lastAccessTime.get()

    fun recordAccess() {
        val now = System.currentTimeMillis()
        _accessCount.incrementAndGet()
        _lastAccessTime.set(now)
        accessTimes.add(now)
        
        while (accessTimes.size > 100) {
            accessTimes.poll()
        }
    }

    fun getAccessesPerHour(): Int {
        val durationHours = (System.currentTimeMillis() - firstAccessTime) / 3600_000.0
        return if (durationHours > 0) (accessCount / durationHours).toInt() else 0
    }

    fun getAverageAccessInterval(): Double {
        val times = accessTimes.toList()
        if (times.size < 2) return Double.MAX_VALUE
        
        var totalInterval = 0L
        for (i in 1 until times.size) {
            totalInterval += times[i] - times[i - 1]
        }
        return totalInterval.toDouble() / (times.size - 1)
    }

    fun getRecentAccessCount(windowMs: Long): Int {
        val threshold = System.currentTimeMillis() - windowMs
        return accessTimes.count { it > threshold }
    }
}
