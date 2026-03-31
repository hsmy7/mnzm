package com.xianxia.sect.data.v2.memory

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.xianxia.sect.data.v2.StorageArchitecture
import com.xianxia.sect.data.v2.StoragePriority
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.lang.ref.SoftReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

data class MemoryConfig(
    val maxMemoryMB: Int = StorageArchitecture.Memory.MAX_MEMORY_MB,
    val hotZoneRatio: Float = StorageArchitecture.Memory.HOT_ZONE_RATIO,
    val warmZoneRatio: Float = StorageArchitecture.Memory.WARM_ZONE_RATIO,
    val coldZoneRatio: Float = StorageArchitecture.Memory.COLD_ZONE_RATIO,
    val gcThresholdRatio: Float = StorageArchitecture.Memory.GC_THRESHOLD_RATIO,
    val criticalMemoryRatio: Float = StorageArchitecture.Memory.CRITICAL_MEMORY_RATIO,
    val enableAdaptiveSizing: Boolean = true,
    val enablePrefetch: Boolean = true
)

data class MemoryStats(
    val totalMemoryMB: Long = 0,
    val availableMemoryMB: Long = 0,
    val usedMemoryMB: Long = 0,
    val hotZoneUsageMB: Long = 0,
    val warmZoneUsageMB: Long = 0,
    val coldZoneUsageMB: Long = 0,
    val cacheHitRate: Double = 0.0,
    val evictionCount: Long = 0,
    val gcCount: Long = 0,
    val pressureLevel: MemoryPressureLevel = MemoryPressureLevel.NORMAL
) {
    val usagePercent: Double get() = if (totalMemoryMB > 0) usedMemoryMB.toDouble() / totalMemoryMB * 100 else 0.0
    val availablePercent: Double get() = if (totalMemoryMB > 0) availableMemoryMB.toDouble() / totalMemoryMB * 100 else 0.0
}

enum class MemoryPressureLevel {
    LOW,
    NORMAL,
    MODERATE,
    HIGH,
    CRITICAL
}

enum class MemoryZone {
    HOT,
    WARM,
    COLD
}

interface MemoryEvictionListener {
    fun onEvicted(key: String, value: Any?, reason: String)
}

class AdaptiveMemoryManager(
    private val context: Context,
    private val config: MemoryConfig = MemoryConfig(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "AdaptiveMemoryManager"
        private const val MONITOR_INTERVAL_MS = 5000L
        private const val GC_CHECK_INTERVAL_MS = 10000L
        private const val ADAPTATION_INTERVAL_MS = 60000L
    }
    
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    
    private data class CacheEntryImpl(
        val key: String,
        val value: Any,
        val size: Long,
        val priority: StoragePriority,
        val createdAt: Long,
        var lastAccessedAt: Long,
        var accessCount: Int,
        val ttl: Long,
        var zone: MemoryZone
    )
    
    private val hotZone = ConcurrentHashMap<String, CacheEntryImpl>()
    private val warmZone = ConcurrentHashMap<String, CacheEntryImpl>()
    private val coldZone = ConcurrentHashMap<String, SoftReference<CacheEntryImpl>>()
    
    private val accessQueue = ConcurrentLinkedQueue<String>()
    private val accessFrequency = ConcurrentHashMap<String, AtomicLong>()
    
    private val hotZoneSize = AtomicLong(0)
    private val warmZoneSize = AtomicLong(0)
    private val coldZoneSize = AtomicLong(0)
    
    private val hitCount = AtomicLong(0)
    private val missCount = AtomicLong(0)
    private val evictionCount = AtomicLong(0)
    private val gcCount = AtomicLong(0)
    
    private val zoneLock = ReentrantReadWriteLock()
    
    private val _memoryStats = MutableStateFlow(MemoryStats())
    val memoryStats: StateFlow<MemoryStats> = _memoryStats.asStateFlow()
    
    private val _pressureLevel = MutableStateFlow(MemoryPressureLevel.NORMAL)
    val pressureLevel: StateFlow<MemoryPressureLevel> = _pressureLevel.asStateFlow()
    
    private var evictionListeners = mutableListOf<MemoryEvictionListener>()
    
    private var monitorJob: Job? = null
    private var gcJob: Job? = null
    private var adaptationJob: Job? = null
    private var isShuttingDown = false
    
    private var maxHotZoneBytes: Long = 0
    private var maxWarmZoneBytes: Long = 0
    private var maxColdZoneBytes: Long = 0
    
    init {
        initializeMemoryLimits()
        startBackgroundTasks()
    }
    
    private fun initializeMemoryLimits() {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        
        val totalMemoryMB = memoryInfo.totalMem / (1024 * 1024)
        val availableMemoryMB = memoryInfo.availMem / (1024 * 1024)
        
        val adjustedMaxMemoryMB = if (config.enableAdaptiveSizing) {
            val deviceMemoryClass = activityManager.memoryClass
            val runtimeMaxMemoryMB = Runtime.getRuntime().maxMemory() / (1024 * 1024)
            
            minOf(
                config.maxMemoryMB.toLong(),
                deviceMemoryClass.toLong() * 1024 / 4,
                runtimeMaxMemoryMB.toLong() / 2,
                (availableMemoryMB * 0.6).toLong()
            ).toInt()
        } else {
            config.maxMemoryMB
        }
        
        val maxBytes = adjustedMaxMemoryMB.toLong() * 1024 * 1024
        
        maxHotZoneBytes = (maxBytes * config.hotZoneRatio).toLong()
        maxWarmZoneBytes = (maxBytes * config.warmZoneRatio).toLong()
        maxColdZoneBytes = (maxBytes * config.coldZoneRatio).toLong()
        
        Log.i(TAG, "Memory limits initialized: Hot=${maxHotZoneBytes / (1024 * 1024)}MB, " +
                "Warm=${maxWarmZoneBytes / (1024 * 1024)}MB, Cold=${maxColdZoneBytes / (1024 * 1024)}MB")
    }
    
    private fun startBackgroundTasks() {
        monitorJob = scope.launch {
            while (isActive && !isShuttingDown) {
                delay(MONITOR_INTERVAL_MS)
                monitorMemory()
            }
        }
        
        gcJob = scope.launch {
            while (isActive && !isShuttingDown) {
                delay(GC_CHECK_INTERVAL_MS)
                checkAndPerformGC()
            }
        }
        
        adaptationJob = scope.launch {
            while (isActive && !isShuttingDown) {
                delay(ADAPTATION_INTERVAL_MS)
                adaptMemoryLimits()
            }
        }
    }
    
    suspend fun <T : Any> get(key: String): T? {
        return zoneLock.read {
            val hotEntry = hotZone[key]
            if (hotEntry != null && !isExpired(hotEntry)) {
                hitCount.incrementAndGet()
                updateAccessInfo(key, hotEntry)
                @Suppress("UNCHECKED_CAST")
                return@read hotEntry.value as T
            }
            
            val warmEntry = warmZone[key]
            if (warmEntry != null && !isExpired(warmEntry)) {
                hitCount.incrementAndGet()
                updateAccessInfo(key, warmEntry)
                promoteToHotZone(key, warmEntry)
                @Suppress("UNCHECKED_CAST")
                return@read warmEntry.value as T
            }
            
            val coldRef = coldZone[key]
            val coldEntry = coldRef?.get()
            if (coldEntry != null && !isExpired(coldEntry)) {
                hitCount.incrementAndGet()
                updateAccessInfo(key, coldEntry)
                promoteToWarmZone(key, coldEntry)
                @Suppress("UNCHECKED_CAST")
                return@read coldEntry.value as T
            }
            
            missCount.incrementAndGet()
            null
        }
    }
    
    suspend fun <T : Any> put(
        key: String,
        value: T,
        size: Long = estimateSize(value),
        priority: StoragePriority = StoragePriority.NORMAL,
        ttl: Long = StorageArchitecture.Cache.L2_TTL_MS
    ) {
        zoneLock.write {
            val zone = determineZone(priority)
            val entry = CacheEntryImpl(
                key = key,
                value = value,
                size = size,
                priority = priority,
                createdAt = System.currentTimeMillis(),
                lastAccessedAt = System.currentTimeMillis(),
                accessCount = 1,
                ttl = ttl,
                zone = zone
            )
            
            when (zone) {
                MemoryZone.HOT -> putInHotZone(key, entry)
                MemoryZone.WARM -> putInWarmZone(key, entry)
                MemoryZone.COLD -> putInColdZone(key, entry)
            }
            
            accessFrequency.getOrPut(key) { AtomicLong(1) }.incrementAndGet()
            accessQueue.add(key)
        }
    }
    
    private fun determineZone(priority: StoragePriority): MemoryZone {
        return when (priority) {
            StoragePriority.CRITICAL, StoragePriority.HIGH -> MemoryZone.HOT
            StoragePriority.NORMAL -> MemoryZone.WARM
            StoragePriority.LOW, StoragePriority.BACKGROUND -> MemoryZone.COLD
        }
    }
    
    private fun putInHotZone(key: String, entry: CacheEntryImpl) {
        ensureCapacity(MemoryZone.HOT, entry.size)
        
        val existing = hotZone.put(key, entry)
        if (existing != null) {
            hotZoneSize.addAndGet(-existing.size)
        }
        hotZoneSize.addAndGet(entry.size)
    }
    
    private fun putInWarmZone(key: String, entry: CacheEntryImpl) {
        ensureCapacity(MemoryZone.WARM, entry.size)
        
        val existing = warmZone.put(key, entry)
        if (existing != null) {
            warmZoneSize.addAndGet(-existing.size)
        }
        warmZoneSize.addAndGet(entry.size)
    }
    
    private fun putInColdZone(key: String, entry: CacheEntryImpl) {
        coldZone[key] = SoftReference(entry)
        coldZoneSize.addAndGet(entry.size)
    }
    
    private fun ensureCapacity(zone: MemoryZone, requiredSize: Long) {
        val (zoneMap, zoneSize, maxSize) = when (zone) {
            MemoryZone.HOT -> Triple(hotZone, hotZoneSize, maxHotZoneBytes)
            MemoryZone.WARM -> Triple(warmZone, warmZoneSize, maxWarmZoneBytes)
            MemoryZone.COLD -> return
        }
        
        while (zoneSize.get() + requiredSize > maxSize && zoneMap.isNotEmpty()) {
            evictOne(zone)
        }
    }
    
    private fun evictOne(zone: MemoryZone) {
        val zoneMap = when (zone) {
            MemoryZone.HOT -> hotZone
            MemoryZone.WARM -> warmZone
            MemoryZone.COLD -> return
        }
        
        val candidate = zoneMap.entries
            .sortedWith(compareBy({ it.value.priority.ordinal }, { it.value.lastAccessedAt }))
            .firstOrNull()
        
        if (candidate != null) {
            val key = candidate.key
            val entry = candidate.value
            
            zoneMap.remove(key)
            when (zone) {
                MemoryZone.HOT -> hotZoneSize.addAndGet(-entry.size)
                MemoryZone.WARM -> warmZoneSize.addAndGet(-entry.size)
                MemoryZone.COLD -> {}
            }
            
            evictionCount.incrementAndGet()
            notifyEviction(key, entry.value, "capacity_eviction")
            
            if (zone == MemoryZone.HOT && entry.priority != StoragePriority.CRITICAL) {
                entry.zone = MemoryZone.WARM
                putInWarmZone(key, entry)
            } else if (zone == MemoryZone.WARM) {
                entry.zone = MemoryZone.COLD
                coldZone[key] = SoftReference(entry)
                coldZoneSize.addAndGet(entry.size)
            }
        }
    }
    
    private fun promoteToHotZone(key: String, entry: CacheEntryImpl) {
        if (entry.zone == MemoryZone.HOT) return
        
        when (entry.zone) {
            MemoryZone.WARM -> {
                warmZone.remove(key)
                warmZoneSize.addAndGet(-entry.size)
            }
            MemoryZone.COLD -> {
                coldZone.remove(key)
            }
            MemoryZone.HOT -> return
        }
        
        entry.zone = MemoryZone.HOT
        putInHotZone(key, entry)
    }
    
    private fun promoteToWarmZone(key: String, entry: CacheEntryImpl) {
        if (entry.zone != MemoryZone.COLD) return
        
        coldZone.remove(key)
        entry.zone = MemoryZone.WARM
        putInWarmZone(key, entry)
    }
    
    private fun updateAccessInfo(key: String, entry: CacheEntryImpl) {
        entry.lastAccessedAt = System.currentTimeMillis()
        entry.accessCount++
        accessQueue.add(key)
    }
    
    private fun isExpired(entry: CacheEntryImpl): Boolean {
        return System.currentTimeMillis() - entry.createdAt > entry.ttl
    }
    
    fun remove(key: String) {
        zoneLock.write {
            hotZone[key]?.let {
                hotZone.remove(key)
                hotZoneSize.addAndGet(-it.size)
            }
            
            warmZone[key]?.let {
                warmZone.remove(key)
                warmZoneSize.addAndGet(-it.size)
            }
            
            coldZone.remove(key)
            accessFrequency.remove(key)
        }
    }
    
    fun contains(key: String): Boolean {
        return zoneLock.read {
            hotZone.containsKey(key) || warmZone.containsKey(key) || 
                (coldZone[key]?.get() != null)
        }
    }
    
    fun clear() {
        zoneLock.write {
            hotZone.clear()
            warmZone.clear()
            coldZone.clear()
            hotZoneSize.set(0)
            warmZoneSize.set(0)
            coldZoneSize.set(0)
            accessQueue.clear()
            accessFrequency.clear()
        }
    }
    
    private fun monitorMemory() {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        
        val totalMemoryMB = memoryInfo.totalMem / (1024 * 1024)
        val availableMemoryMB = memoryInfo.availMem / (1024 * 1024)
        val usedMemoryMB = (hotZoneSize.get() + warmZoneSize.get()) / (1024 * 1024)
        
        val pressureLevel = when {
            memoryInfo.availMem < totalMemoryMB * 0.1 -> MemoryPressureLevel.CRITICAL
            memoryInfo.availMem < totalMemoryMB * 0.2 -> MemoryPressureLevel.HIGH
            memoryInfo.availMem < totalMemoryMB * 0.3 -> MemoryPressureLevel.MODERATE
            memoryInfo.availMem < totalMemoryMB * 0.5 -> MemoryPressureLevel.NORMAL
            else -> MemoryPressureLevel.LOW
        }
        
        _pressureLevel.value = pressureLevel
        
        val hits = hitCount.get()
        val misses = missCount.get()
        val hitRate = if (hits + misses > 0) hits.toDouble() / (hits + misses) else 0.0
        
        _memoryStats.value = MemoryStats(
            totalMemoryMB = totalMemoryMB,
            availableMemoryMB = availableMemoryMB,
            usedMemoryMB = usedMemoryMB,
            hotZoneUsageMB = hotZoneSize.get() / (1024 * 1024),
            warmZoneUsageMB = warmZoneSize.get() / (1024 * 1024),
            coldZoneUsageMB = coldZoneSize.get() / (1024 * 1024),
            cacheHitRate = hitRate,
            evictionCount = evictionCount.get(),
            gcCount = gcCount.get(),
            pressureLevel = pressureLevel
        )
        
        if (pressureLevel.ordinal >= MemoryPressureLevel.HIGH.ordinal) {
            handleMemoryPressure(pressureLevel)
        }
    }
    
    private fun handleMemoryPressure(level: MemoryPressureLevel) {
        Log.w(TAG, "Memory pressure detected: $level")
        
        when (level) {
            MemoryPressureLevel.CRITICAL -> {
                clearColdZone()
                trimWarmZone(0.5)
                trimHotZone(0.3)
            }
            MemoryPressureLevel.HIGH -> {
                clearColdZone()
                trimWarmZone(0.3)
            }
            MemoryPressureLevel.MODERATE -> {
                trimWarmZone(0.2)
            }
            else -> {}
        }
        
        System.gc()
        gcCount.incrementAndGet()
    }
    
    private fun clearColdZone() {
        coldZone.entries.toList().forEach { (key, ref) ->
            ref.get()?.let { entry ->
                notifyEviction(key, entry.value, "memory_pressure")
            }
        }
        coldZone.clear()
        coldZoneSize.set(0)
    }
    
    private fun trimWarmZone(ratio: Double) {
        val targetSize = (warmZoneSize.get() * (1 - ratio)).toLong()
        val entries = warmZone.entries.sortedBy { it.value.lastAccessedAt }
        
        for (entry in entries) {
            if (warmZoneSize.get() <= targetSize) break
            
            warmZone.remove(entry.key)
            warmZoneSize.addAndGet(-entry.value.size)
            evictionCount.incrementAndGet()
            notifyEviction(entry.key, entry.value.value, "memory_pressure")
        }
    }
    
    private fun trimHotZone(ratio: Double) {
        val entries = hotZone.entries
            .filter { it.value.priority != StoragePriority.CRITICAL }
            .sortedBy { it.value.lastAccessedAt }
        
        val targetCount = (entries.size * ratio).toInt()
        entries.take(targetCount).forEach { entry ->
            hotZone.remove(entry.key)
            hotZoneSize.addAndGet(-entry.value.size)
            evictionCount.incrementAndGet()
            notifyEviction(entry.key, entry.value.value, "memory_pressure")
        }
    }
    
    private fun checkAndPerformGC() {
        val usageRatio = _memoryStats.value.usagePercent / 100.0
        
        if (usageRatio > config.gcThresholdRatio) {
            cleanupExpired()
        }
        
        if (usageRatio > config.criticalMemoryRatio) {
            handleMemoryPressure(MemoryPressureLevel.CRITICAL)
        }
    }
    
    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        
        hotZone.entries.toList().forEach { (key, entry) ->
            if (now - entry.createdAt > entry.ttl) {
                hotZone.remove(key)
                hotZoneSize.addAndGet(-entry.size)
                evictionCount.incrementAndGet()
                notifyEviction(key, entry.value, "expired")
            }
        }
        
        warmZone.entries.toList().forEach { (key, entry) ->
            if (now - entry.createdAt > entry.ttl) {
                warmZone.remove(key)
                warmZoneSize.addAndGet(-entry.size)
                evictionCount.incrementAndGet()
                notifyEviction(key, entry.value, "expired")
            }
        }
    }
    
    private fun adaptMemoryLimits() {
        if (!config.enableAdaptiveSizing) return
        
        val hitRate = _memoryStats.value.cacheHitRate
        val pressureLevel = _pressureLevel.value
        
        when {
            hitRate < 0.5 && pressureLevel == MemoryPressureLevel.LOW -> {
                maxHotZoneBytes = (maxHotZoneBytes * 1.1).toLong()
                maxWarmZoneBytes = (maxWarmZoneBytes * 1.1).toLong()
                Log.d(TAG, "Increased memory limits due to low hit rate")
            }
            hitRate > 0.9 && pressureLevel.ordinal >= MemoryPressureLevel.MODERATE.ordinal -> {
                maxHotZoneBytes = (maxHotZoneBytes * 0.9).toLong()
                maxWarmZoneBytes = (maxWarmZoneBytes * 0.9).toLong()
                Log.d(TAG, "Decreased memory limits due to high hit rate and memory pressure")
            }
        }
    }
    
    fun addEvictionListener(listener: MemoryEvictionListener) {
        evictionListeners.add(listener)
    }
    
    fun removeEvictionListener(listener: MemoryEvictionListener) {
        evictionListeners.remove(listener)
    }
    
    private fun notifyEviction(key: String, value: Any?, reason: String) {
        evictionListeners.forEach { listener ->
            try {
                listener.onEvicted(key, value, reason)
            } catch (e: Exception) {
                Log.e(TAG, "Error in eviction listener", e)
            }
        }
    }
    
    private fun estimateSize(value: Any): Long {
        return when (value) {
            is String -> (value.length * 2L)
            is ByteArray -> value.size.toLong()
            is List<*> -> value.size * 64L
            is Map<*, *> -> value.size * 128L
            else -> 256L
        }
    }
    
    fun getStats(): MemoryStats = _memoryStats.value
    
    fun getZoneStats(): Map<MemoryZone, Pair<Int, Long>> {
        return mapOf(
            MemoryZone.HOT to Pair(hotZone.size, hotZoneSize.get()),
            MemoryZone.WARM to Pair(warmZone.size, warmZoneSize.get()),
            MemoryZone.COLD to Pair(coldZone.size, coldZoneSize.get())
        )
    }
    
    fun onLowMemory() {
        Log.w(TAG, "onLowMemory called")
        handleMemoryPressure(MemoryPressureLevel.HIGH)
    }
    
    fun onTrimMemory(level: Int) {
        Log.d(TAG, "onTrimMemory called with level $level")
        
        when (level) {
            15 -> handleMemoryPressure(MemoryPressureLevel.CRITICAL)
            10 -> handleMemoryPressure(MemoryPressureLevel.HIGH)
            5 -> handleMemoryPressure(MemoryPressureLevel.MODERATE)
            30 -> trimWarmZone(0.3)
            40 -> clear()
        }
    }
    
    fun shutdown() {
        isShuttingDown = true
        monitorJob?.cancel()
        gcJob?.cancel()
        adaptationJob?.cancel()
        clear()
        scope.cancel()
        Log.i(TAG, "AdaptiveMemoryManager shutdown completed")
    }
}

class TieredCache(
    private val memoryManager: AdaptiveMemoryManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "TieredCache"
    }
    
    private val l1Cache = ConcurrentHashMap<String, Any>()
    private val l1Size = AtomicLong(0)
    private val maxL1Size = StorageArchitecture.Cache.L1_CACHE_SIZE.toLong()
    
    private val l1Lock = ReentrantReadWriteLock()
    
    suspend fun <T : Any> get(key: String): T? {
        l1Lock.read {
            l1Cache[key]?.let {
                return@read it as T
            }
        }
        
        return memoryManager.get(key)
    }
    
    suspend fun <T : Any> put(
        key: String,
        value: T,
        priority: StoragePriority = StoragePriority.NORMAL,
        ttl: Long = StorageArchitecture.Cache.L2_TTL_MS
    ) {
        if (priority == StoragePriority.CRITICAL || priority == StoragePriority.HIGH) {
            l1Lock.write {
                if (l1Size.get() < maxL1Size) {
                    l1Cache[key] = value
                    l1Size.addAndGet(estimateSize(value))
                }
            }
        }
        
        memoryManager.put(key, value, estimateSize(value), priority, ttl)
    }
    
    fun removeFromL1(key: String) {
        l1Lock.write {
            l1Cache.remove(key)
        }
    }
    
    fun clearL1() {
        l1Lock.write {
            l1Cache.clear()
            l1Size.set(0)
        }
    }
    
    private fun estimateSize(value: Any): Long {
        return when (value) {
            is String -> (value.length * 2L)
            is ByteArray -> value.size.toLong()
            else -> 128L
        }
    }
    
    fun shutdown() {
        clearL1()
        scope.cancel()
    }
}
