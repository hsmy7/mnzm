package com.xianxia.sect.core.warehouse

import com.xianxia.sect.core.model.WarehouseItem
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

object WarehouseItemPool {
    private const val MAX_POOL_SIZE = 500
    private const val EVICTION_RATIO = 0.5f
    private const val IDLE_THRESHOLD = 10 * 60 * 1000L
    
    private lateinit var applicationScopeProvider: ApplicationScopeProvider
    
    fun initialize(provider: ApplicationScopeProvider) {
        applicationScopeProvider = provider
    }
    
    private val cleanupScope get() = applicationScopeProvider.scope
    
    private val poolLock = Any()
    private data class PooledItem(
        val item: WarehouseItem,
        val lastAccessTime: Long,
        val accessCount: Long
    )
    
    private val pool = ConcurrentHashMap<String, PooledItem>()
    private val poolStats = PoolStats()
    private val poolSize = AtomicInteger(0)
    
    private var cleanupJob: Job? = null
    private var isRunning = false
    
    fun acquire(
        itemId: String,
        itemName: String,
        itemType: String,
        rarity: Int,
        quantity: Int
    ): WarehouseItem {
        val key = "$itemId:$itemType:$rarity:$itemName"
        
        val pooled = pool[key]
        if (pooled != null) {
            poolStats.recordHit()
            pool[key] = pooled.copy(
                lastAccessTime = System.currentTimeMillis(),
                accessCount = pooled.accessCount + 1
            )
            return pooled.item.copy(itemName = itemName, quantity = quantity)
        }
        
        poolStats.recordMiss()
        return WarehouseItem(itemId, itemName, itemType, rarity, quantity)
    }
    
    fun acquire(item: WarehouseItem): WarehouseItem {
        return acquire(
            itemId = item.itemId,
            itemName = item.itemName,
            itemType = item.itemType,
            rarity = item.rarity,
            quantity = item.quantity
        )
    }
    
    fun release(item: WarehouseItem) {
        synchronized(poolLock) {
            if (poolSize.get() >= MAX_POOL_SIZE) {
                evictLeastUsed()
            }
            
            val key = "${item.itemId}:${item.itemType}:${item.rarity}:${item.itemName}"
            if (!pool.containsKey(key)) {
                pool[key] = PooledItem(
                    item = item.copy(itemName = "", quantity = 1),
                    lastAccessTime = System.currentTimeMillis(),
                    accessCount = 0
                )
                poolSize.incrementAndGet()
            }
        }
        poolStats.recordRelease()
    }
    
    fun releaseAll(items: List<WarehouseItem>) {
        items.forEach { release(it) }
    }
    
    fun clear() {
        synchronized(poolLock) {
            pool.clear()
            poolSize.set(0)
            poolStats.reset()
            stopCleanupTask()
        }
    }
    
    fun size(): Int = synchronized(poolLock) { poolSize.get() }
    
    fun getStats(): PoolStatsSnapshot = synchronized(poolLock) { poolStats.snapshot() }
    
    private fun evictLeastUsed() {
        synchronized(poolLock) {
            val toRemove = pool.entries
                .sortedBy { it.value.lastAccessTime }
                .take((MAX_POOL_SIZE * EVICTION_RATIO).toInt())
                .map { it.key }
                .toList()
            
            toRemove.forEach { key ->
                pool.remove(key)
                poolSize.decrementAndGet()
            }
            poolStats.recordEviction(toRemove.size)
        }
    }
    
    fun startCleanupTask() {
        if (isRunning) return
        
        isRunning = true
        cleanupJob = cleanupScope.launch {
            while (isRunning) {
                try {
                    delay(IDLE_THRESHOLD / 2)
                    cleanupIdle()
                } catch (e: Exception) {
                    break
                }
            }
        }
    }
    
    fun stopCleanupTask() {
        synchronized(poolLock) {
            isRunning = false
            cleanupJob?.cancel()
            cleanupJob = null
        }
    }
    
    private fun cleanupIdle() {
        val now = System.currentTimeMillis()
        val toRemove: List<String>
        
        synchronized(poolLock) {
            toRemove = pool.entries
                .filter { now - it.value.lastAccessTime > IDLE_THRESHOLD }
                .map { it.key }
                .toList()
            
            toRemove.forEach { key ->
                pool.remove(key)
                poolSize.decrementAndGet()
            }
            
            if (toRemove.isNotEmpty()) {
                poolStats.recordEviction(toRemove.size)
            }
        }
    }
    
    fun warmUp(items: List<WarehouseItem>) {
        synchronized(poolLock) {
            items.forEach { item ->
                val key = "${item.itemId}:${item.itemType}:${item.rarity}:${item.itemName}"
                if (!pool.containsKey(key)) {
                    pool[key] = PooledItem(
                        item = item.copy(itemName = "", quantity = 1),
                        lastAccessTime = System.currentTimeMillis(),
                        accessCount = 0
                    )
                    poolSize.incrementAndGet()
                }
            }
        }
    }
}

class PoolStats {
    private val hits = AtomicLong(0)
    private val misses = AtomicLong(0)
    private val releases = AtomicLong(0)
    private val evictions = AtomicLong(0)
    private val lastResetTime = AtomicLong(System.currentTimeMillis())
    private val statsLock = Any()
    
    fun recordHit() { hits.incrementAndGet() }
    fun recordMiss() { misses.incrementAndGet() }
    fun recordRelease() { releases.incrementAndGet() }
    fun recordEviction(count: Int) { evictions.addAndGet(count.toLong()) }
    
    fun reset() {
        synchronized(statsLock) {
            hits.set(0)
            misses.set(0)
            releases.set(0)
            evictions.set(0)
            lastResetTime.set(System.currentTimeMillis())
        }
    }
    
    fun snapshot(): PoolStatsSnapshot = synchronized(statsLock) {
        PoolStatsSnapshot(
            hits = hits.get(),
            misses = misses.get(),
            releases = releases.get(),
            evictions = evictions.get(),
            lastResetTime = lastResetTime.get()
        )
    }
    
    val hitRate: Float
        get() = synchronized(statsLock) {
            val total = hits.get() + misses.get()
            if (total == 0L) 0f else hits.get().toFloat() / total
        }
    
    val totalOperations: Long
        get() = synchronized(statsLock) {
            hits.get() + misses.get() + releases.get()
        }
}

data class PoolStatsSnapshot(
    val hits: Long,
    val misses: Long,
    val releases: Long,
    val evictions: Long,
    val lastResetTime: Long
) {
    private val snapshotLock = Any()
    
    val hitRate: Float
        get() = synchronized(snapshotLock) {
            val total = hits + misses
            if (total == 0L) 0f else hits.toFloat() / total
        }
    
    val totalOperations: Long
        get() = synchronized(snapshotLock) {
            hits + misses + releases
        }
}
