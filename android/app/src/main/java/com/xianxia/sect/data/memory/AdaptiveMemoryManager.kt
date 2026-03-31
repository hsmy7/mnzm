package com.xianxia.sect.data.memory

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.Reference
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object MemoryConfig {
    const val MEMORY_CHECK_INTERVAL_MS = 5000L
    const val MEMORY_PRESSURE_LOW_THRESHOLD = 0.5
    const val MEMORY_PRESSURE_MODERATE_THRESHOLD = 0.7
    const val MEMORY_PRESSURE_HIGH_THRESHOLD = 0.85
    const val MEMORY_PRESSURE_CRITICAL_THRESHOLD = 0.95
    
    const val GC_SUGGESTION_COOLDOWN_MS = 30000L
    const val EMERGENCY_CLEANUP_COOLDOWN_MS = 60000L
    
    val MEMORY_BUDGET_ALLOCATION = mapOf(
        MemoryZone.HOT to 0.15,
        MemoryZone.WARM to 0.25,
        MemoryZone.COLD to 0.10,
        MemoryZone.BUFFER to 0.10,
        MemoryZone.RESERVED to 0.40
    )
}

enum class MemoryPressure {
    NONE,
    LOW,
    MODERATE,
    HIGH,
    CRITICAL
}

enum class MemoryZone {
    HOT,
    WARM,
    COLD,
    BUFFER,
    RESERVED
}

data class MemoryBudget(
    val hotZoneBytes: Long = 0,
    val warmZoneBytes: Long = 0,
    val coldZoneBytes: Long = 0,
    val bufferBytes: Long = 0,
    val reservedBytes: Long = 0
) {
    val total: Long get() = hotZoneBytes + warmZoneBytes + coldZoneBytes + bufferBytes + reservedBytes
}

data class MemoryStats(
    val totalMemory: Long = 0,
    val availableMemory: Long = 0,
    val usedMemory: Long = 0,
    val maxMemory: Long = 0,
    val nativeMemory: Long = 0,
    val dalvikMemory: Long = 0,
    val pressure: MemoryPressure = MemoryPressure.NONE,
    val pressurePercent: Double = 0.0,
    val allocatedBytes: Long = 0,
    val freedBytes: Long = 0,
    val gcCount: Int = 0
)

data class MemoryAlert(
    val level: MemoryPressure,
    val message: String,
    val stats: MemoryStats,
    val timestamp: Long = System.currentTimeMillis()
)

interface MemoryPressureListener {
    fun onMemoryPressureChanged(pressure: MemoryPressure, stats: MemoryStats)
    fun onMemoryCritical(stats: MemoryStats)
}

class AdaptiveMemoryManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "AdaptiveMemoryManager"
    }
    
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    
    private val _memoryStats = MutableStateFlow(MemoryStats())
    val memoryStats: StateFlow<MemoryStats> = _memoryStats.asStateFlow()
    
    private val _currentPressure = MutableStateFlow(MemoryPressure.NONE)
    val currentPressure: StateFlow<MemoryPressure> = _currentPressure.asStateFlow()
    
    private val _memoryBudget = MutableStateFlow(MemoryBudget())
    val memoryBudget: StateFlow<MemoryBudget> = _memoryBudget.asStateFlow()
    
    private val listeners = mutableListOf<MemoryPressureListener>()
    private val alerts = mutableListOf<MemoryAlert>()
    
    private val allocatedBytes = AtomicLong(0)
    private val freedBytes = AtomicLong(0)
    private val gcCount = AtomicLong(0)
    
    private val trackedObjects = ConcurrentHashMap<String, WeakReference<TrackedObject>>()
    private val referenceQueue = ReferenceQueue<TrackedObject>()
    
    private var monitorJob: Job? = null
    private var cleanupJob: Job? = null
    private var lastGcSuggestionTime = 0L
    private var lastEmergencyCleanupTime = 0L
    private var isShuttingDown = false
    
    private val zoneAllocations = ConcurrentHashMap<MemoryZone, AtomicLong>()
    
    init {
        MemoryZone.values().forEach { zone ->
            zoneAllocations[zone] = AtomicLong(0)
        }
        
        calculateMemoryBudget()
        startMonitoring()
    }
    
    private fun startMonitoring() {
        monitorJob = scope.launch {
            while (isActive && !isShuttingDown) {
                delay(MemoryConfig.MEMORY_CHECK_INTERVAL_MS)
                checkMemory()
            }
        }
        
        cleanupJob = scope.launch {
            while (isActive && !isShuttingDown) {
                delay(60000L)
                cleanupReferences()
            }
        }
    }
    
    private fun checkMemory() {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory
        
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val availableMemory = memInfo.availMem
        val totalDeviceMemory = memInfo.totalMem
        
        val pressurePercent = usedMemory.toDouble() / maxMemory
        
        val pressure = when {
            pressurePercent >= MemoryConfig.MEMORY_PRESSURE_CRITICAL_THRESHOLD -> MemoryPressure.CRITICAL
            pressurePercent >= MemoryConfig.MEMORY_PRESSURE_HIGH_THRESHOLD -> MemoryPressure.HIGH
            pressurePercent >= MemoryConfig.MEMORY_PRESSURE_MODERATE_THRESHOLD -> MemoryPressure.MODERATE
            pressurePercent >= MemoryConfig.MEMORY_PRESSURE_LOW_THRESHOLD -> MemoryPressure.LOW
            else -> MemoryPressure.NONE
        }
        
        val stats = MemoryStats(
            totalMemory = totalMemory,
            availableMemory = availableMemory,
            usedMemory = usedMemory,
            maxMemory = maxMemory,
            nativeMemory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.os.Debug.getNativeHeapAllocatedSize()
            } else 0,
            dalvikMemory = usedMemory,
            pressure = pressure,
            pressurePercent = pressurePercent * 100,
            allocatedBytes = allocatedBytes.get(),
            freedBytes = freedBytes.get(),
            gcCount = gcCount.get().toInt()
        )
        
        _memoryStats.value = stats
        
        val previousPressure = _currentPressure.value
        if (pressure != previousPressure) {
            _currentPressure.value = pressure
            handlePressureChange(previousPressure, pressure, stats)
        }
        
        if (pressure == MemoryPressure.CRITICAL) {
            handleCriticalMemory(stats)
        }
    }
    
    private fun handlePressureChange(oldPressure: MemoryPressure, newPressure: MemoryPressure, stats: MemoryStats) {
        Log.i(TAG, "Memory pressure changed: $oldPressure -> $newPressure (${"%.1f".format(stats.pressurePercent)}%)")
        
        synchronized(listeners) {
            listeners.forEach { listener ->
                listener.onMemoryPressureChanged(newPressure, stats)
            }
        }
        
        when (newPressure) {
            MemoryPressure.LOW -> {
                recalculateBudget()
            }
            MemoryPressure.MODERATE -> {
                suggestGC()
            }
            MemoryPressure.HIGH -> {
                performCleanup()
                suggestGC()
            }
            MemoryPressure.CRITICAL -> {
                performEmergencyCleanup()
            }
            MemoryPressure.NONE -> {
                recalculateBudget()
            }
        }
    }
    
    private fun handleCriticalMemory(stats: MemoryStats) {
        val alert = MemoryAlert(MemoryPressure.CRITICAL, "Critical memory pressure detected", stats)
        
        synchronized(alerts) {
            alerts.add(alert)
            if (alerts.size > 100) alerts.removeAt(0)
        }
        
        synchronized(listeners) {
            listeners.forEach { listener ->
                listener.onMemoryCritical(stats)
            }
        }
        
        performEmergencyCleanup()
        System.gc()
        gcCount.incrementAndGet()
    }
    
    private fun calculateMemoryBudget() {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        
        val allocations = MemoryConfig.MEMORY_BUDGET_ALLOCATION
        
        _memoryBudget.value = MemoryBudget(
            hotZoneBytes = (maxMemory * (allocations[MemoryZone.HOT] ?: 0.15)).toLong(),
            warmZoneBytes = (maxMemory * (allocations[MemoryZone.WARM] ?: 0.25)).toLong(),
            coldZoneBytes = (maxMemory * (allocations[MemoryZone.COLD] ?: 0.10)).toLong(),
            bufferBytes = (maxMemory * (allocations[MemoryZone.BUFFER] ?: 0.10)).toLong(),
            reservedBytes = (maxMemory * (allocations[MemoryZone.RESERVED] ?: 0.40)).toLong()
        )
    }
    
    private fun recalculateBudget() {
        val stats = _memoryStats.value
        val pressure = stats.pressurePercent / 100.0
        
        val adjustment = when {
            pressure < 0.3 -> 1.2
            pressure < 0.5 -> 1.0
            pressure < 0.7 -> 0.8
            else -> 0.6
        }
        
        val baseBudget = _memoryBudget.value
        _memoryBudget.value = MemoryBudget(
            hotZoneBytes = (baseBudget.hotZoneBytes * adjustment).toLong(),
            warmZoneBytes = (baseBudget.warmZoneBytes * adjustment).toLong(),
            coldZoneBytes = (baseBudget.coldZoneBytes * adjustment).toLong(),
            bufferBytes = (baseBudget.bufferBytes * adjustment).toLong(),
            reservedBytes = baseBudget.reservedBytes
        )
    }
    
    fun allocate(zone: MemoryZone, size: Long): Boolean {
        val budget = _memoryBudget.value
        val currentAllocation = zoneAllocations[zone]?.get() ?: 0
        val zoneLimit = when (zone) {
            MemoryZone.HOT -> budget.hotZoneBytes
            MemoryZone.WARM -> budget.warmZoneBytes
            MemoryZone.COLD -> budget.coldZoneBytes
            MemoryZone.BUFFER -> budget.bufferBytes
            MemoryZone.RESERVED -> budget.reservedBytes
        }
        
        if (currentAllocation + size > zoneLimit) {
            return false
        }
        
        zoneAllocations[zone]?.addAndGet(size)
        allocatedBytes.addAndGet(size)
        return true
    }
    
    fun deallocate(zone: MemoryZone, size: Long) {
        zoneAllocations[zone]?.addAndGet(-size)
        freedBytes.addAndGet(size)
    }
    
    fun getZoneAllocation(zone: MemoryZone): Long {
        return zoneAllocations[zone]?.get() ?: 0
    }
    
    fun getZoneLimit(zone: MemoryZone): Long {
        val budget = _memoryBudget.value
        return when (zone) {
            MemoryZone.HOT -> budget.hotZoneBytes
            MemoryZone.WARM -> budget.warmZoneBytes
            MemoryZone.COLD -> budget.coldZoneBytes
            MemoryZone.BUFFER -> budget.bufferBytes
            MemoryZone.RESERVED -> budget.reservedBytes
        }
    }
    
    fun trackObject(id: String, obj: Any, size: Long, zone: MemoryZone = MemoryZone.WARM): TrackedObject {
        val tracked = TrackedObject(id, obj, size, zone)
        trackedObjects[id] = WeakReference(tracked, referenceQueue)
        return tracked
    }
    
    private fun cleanupReferences() {
        var cleaned = 0
        var ref: Reference<out TrackedObject>? = referenceQueue.poll()
        
        while (ref != null) {
            val tracked = ref.get()
            if (tracked != null) {
                trackedObjects.remove(tracked.id)
                deallocate(tracked.zone, tracked.size)
                cleaned++
            }
            ref = referenceQueue.poll()
        }
        
        if (cleaned > 0) {
            Log.d(TAG, "Cleaned up $cleaned tracked object references")
        }
    }
    
    private fun suggestGC() {
        val now = System.currentTimeMillis()
        if (now - lastGcSuggestionTime > MemoryConfig.GC_SUGGESTION_COOLDOWN_MS) {
            lastGcSuggestionTime = now
            System.gc()
            gcCount.incrementAndGet()
            Log.d(TAG, "Suggested garbage collection")
        }
    }
    
    private fun performCleanup() {
        cleanupReferences()
        
        val hotAllocation = zoneAllocations[MemoryZone.HOT]?.get() ?: 0
        val hotLimit = getZoneLimit(MemoryZone.HOT)
        
        if (hotAllocation > hotLimit * 0.8) {
            Log.d(TAG, "Hot zone near capacity, triggering cleanup")
        }
    }
    
    private fun performEmergencyCleanup() {
        val now = System.currentTimeMillis()
        if (now - lastEmergencyCleanupTime > MemoryConfig.EMERGENCY_CLEANUP_COOLDOWN_MS) {
            lastEmergencyCleanupTime = now
            
            Log.w(TAG, "Performing emergency memory cleanup")
            
            cleanupReferences()
            
            trackedObjects.clear()
            
            zoneAllocations.values.forEach { it.set(0) }
            
            System.gc()
            gcCount.incrementAndGet()
            System.runFinalization()
            
            Log.i(TAG, "Emergency memory cleanup completed")
        }
    }
    
    fun addListener(listener: MemoryPressureListener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }
    
    fun removeListener(listener: MemoryPressureListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }
    
    fun getAlerts(): List<MemoryAlert> {
        return synchronized(alerts) { alerts.toList() }
    }
    
    fun getStats(): MemoryStats = _memoryStats.value
    
    fun getPressure(): MemoryPressure = _currentPressure.value
    
    fun getBudget(): MemoryBudget = _memoryBudget.value
    
    fun isMemoryAvailable(requiredBytes: Long): Boolean {
        val stats = _memoryStats.value
        val available = stats.maxMemory - stats.usedMemory
        return available > requiredBytes * 1.5
    }
    
    fun getAvailableMemory(): Long {
        val stats = _memoryStats.value
        return stats.maxMemory - stats.usedMemory
    }
    
    fun forceGc() {
        System.gc()
        gcCount.incrementAndGet()
    }
    
    fun shutdown() {
        isShuttingDown = true
        
        monitorJob?.cancel()
        cleanupJob?.cancel()
        
        trackedObjects.clear()
        listeners.clear()
        alerts.clear()
        
        scope.cancel()
        Log.i(TAG, "AdaptiveMemoryManager shutdown completed")
    }
}

class TrackedObject(
    val id: String,
    value: Any,
    val size: Long,
    val zone: MemoryZone
) {
    private val reference = WeakReference(value)
    
    fun get(): Any? = reference.get()
    fun isAlive(): Boolean = reference.get() != null
}

class MemoryPool<T>(
    private val factory: () -> T,
    private val reset: (T) -> Unit = {},
    private val maxSize: Int = 100,
    private val onAllocate: (T) -> Unit = {},
    private val onRelease: (T) -> Unit = {}
) {
    private val pool = java.util.concurrent.ConcurrentLinkedQueue<T>()
    private val created = AtomicLong(0)
    private val borrowed = AtomicLong(0)
    private val returned = AtomicLong(0)
    
    fun acquire(): T {
        return pool.poll() ?: run {
            created.incrementAndGet()
            factory().also { onAllocate(it) }
        }.also { borrowed.incrementAndGet() }
    }
    
    fun release(obj: T) {
        reset(obj)
        onRelease(obj)
        
        if (pool.size < maxSize) {
            pool.offer(obj)
        }
        returned.incrementAndGet()
    }
    
    inline fun <R> use(block: (T) -> R): R {
        val obj = acquire()
        return try {
            block(obj)
        } finally {
            release(obj)
        }
    }
    
    fun clear() {
        pool.clear()
    }
    
    fun size(): Int = pool.size
    
    fun stats(): PoolStats = PoolStats(
        created = created.get(),
        borrowed = borrowed.get(),
        returned = returned.get(),
        available = pool.size
    )
    
    data class PoolStats(
        val created: Long,
        val borrowed: Long,
        val returned: Long,
        val available: Int
    )
}

class BufferPool(
    private val initialBufferSize: Int = 4096,
    private val maxBufferSize: Int = 1024 * 1024,
    private val maxPoolSize: Int = 50
) {
    private val pools = ConcurrentHashMap<Int, MemoryPool<ByteArray>>()
    
    fun acquire(size: Int): ByteArray {
        val poolSize = findPoolSize(size)
        val pool = pools.getOrPut(poolSize) {
            MemoryPool(
                factory = { ByteArray(poolSize) },
                maxSize = maxPoolSize
            )
        }
        return pool.acquire()
    }
    
    fun release(buffer: ByteArray) {
        val poolSize = findPoolSize(buffer.size)
        pools[poolSize]?.release(buffer)
    }
    
    inline fun <R> use(size: Int, block: (ByteArray) -> R): R {
        val buffer = acquire(size)
        return try {
            block(buffer)
        } finally {
            release(buffer)
        }
    }
    
    private fun findPoolSize(size: Int): Int {
        var poolSize = initialBufferSize
        while (poolSize < size && poolSize < maxBufferSize) {
            poolSize *= 2
        }
        return poolSize.coerceAtMost(maxBufferSize)
    }
    
    fun clear() {
        pools.values.forEach { it.clear() }
        pools.clear()
    }
    
    fun stats(): Map<Int, MemoryPool.PoolStats> {
        return pools.mapValues { it.value.stats() }
    }
}
