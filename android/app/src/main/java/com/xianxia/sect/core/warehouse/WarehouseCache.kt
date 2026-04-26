package com.xianxia.sect.core.warehouse

import com.xianxia.sect.core.model.WarehouseItem
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

object WarehouseCache {
    private const val DEFAULT_MAX_CACHE_SIZE = 500
    private const val MIN_CACHE_SIZE = 200
    private const val MAX_CACHE_SIZE_LIMIT = 2000
    private const val CACHE_TTL = 5 * 60 * 1000L
    private const val CACHE_RATIO = 0.25f
    
    private lateinit var applicationScopeProvider: ApplicationScopeProvider
    
    fun initialize(provider: ApplicationScopeProvider) {
        applicationScopeProvider = provider
    }
    
    private val evictionScope get() = applicationScopeProvider.scope
    
    private val cacheLock = Any()
    
    private var maxCacheSize = DEFAULT_MAX_CACHE_SIZE
    private val itemCache = LinkedHashMap<String, WarehouseItem>(16, 0.75f, true)
    private val accessOrder = ConcurrentHashMap<String, Long>()
    private val typeCache = ConcurrentHashMap<String, MutableList<WarehouseItem>>()
    private val rarityCache = ConcurrentHashMap<Int, MutableList<WarehouseItem>>()
    private val statsCacheRef = AtomicReference<WarehouseStats?>(null)
    private val lastAccessTime = AtomicLong(System.currentTimeMillis())
    
    private val itemIdToKeys = ConcurrentHashMap<String, MutableSet<String>>()
    
    private val hitCount = AtomicLong(0)
    private val missCount = AtomicLong(0)
    private val evictionCount = AtomicLong(0)
    
    private var evictionJob: Job? = null
    private var isRunning = false
    
    fun adjustCacheSize(inventoryMaxSize: Int) {
        maxCacheSize = (inventoryMaxSize * CACHE_RATIO).toInt()
            .coerceIn(MIN_CACHE_SIZE, MAX_CACHE_SIZE_LIMIT)
        
        synchronized(cacheLock) {
            while (itemCache.size > maxCacheSize) {
                evictEldest()
            }
        }
    }
    
    fun startEvictionTask() {
        if (isRunning) return
        
        isRunning = true
        evictionJob = evictionScope.launch {
            while (isRunning) {
                try {
                    delay(CACHE_TTL / 2)
                    evictExpired()
                } catch (e: Exception) {
                    break
                }
            }
        }
    }
    
    fun stopEvictionTask() {
        isRunning = false
        evictionJob?.cancel()
        evictionJob = null
    }
    
    fun getItem(itemId: String): List<WarehouseItem> {
        val keys = itemIdToKeys[itemId]
        if (keys.isNullOrEmpty()) {
            missCount.incrementAndGet()
            return emptyList()
        }
        
        hitCount.incrementAndGet()
        synchronized(cacheLock) {
            return keys.mapNotNull { key ->
                itemCache[key]?.also { 
                    accessOrder[key] = System.currentTimeMillis() 
                }
            }
        }
    }
    
    fun getItemByKey(key: String): WarehouseItem? {
        synchronized(cacheLock) {
            val item = itemCache[key]
            if (item != null) {
                hitCount.incrementAndGet()
                accessOrder[key] = System.currentTimeMillis()
            } else {
                missCount.incrementAndGet()
            }
            return item
        }
    }
    
    fun getItemsByType(type: String): List<WarehouseItem> {
        synchronized(cacheLock) {
            return typeCache[type]?.toList() ?: emptyList()
        }
    }
    
    fun getItemsByRarity(rarity: Int): List<WarehouseItem> {
        synchronized(cacheLock) {
            return rarityCache[rarity]?.toList() ?: emptyList()
        }
    }
    
    fun getStats(): WarehouseStats? = statsCacheRef.get()
    
    fun put(item: WarehouseItem) {
        val key = StorageKeyUtil.generateKey(item)
        
        synchronized(cacheLock) {
            while (itemCache.size >= maxCacheSize && !itemCache.containsKey(key)) {
                evictEldest()
            }
            
            itemCache[key] = item
            accessOrder[key] = System.currentTimeMillis()
            
            itemIdToKeys.getOrPut(item.itemId) { 
                ConcurrentHashMap.newKeySet() 
            }.add(key)
            
            typeCache.getOrPut(item.itemType) { mutableListOf() }.apply {
                removeAll { StorageKeyUtil.generateKey(it) == key }
                add(item)
            }
            
            rarityCache.getOrPut(item.rarity) { mutableListOf() }.apply {
                removeAll { StorageKeyUtil.generateKey(it) == key }
                add(item)
            }
        }
        
        lastAccessTime.set(System.currentTimeMillis())
    }
    
    fun putAll(items: List<WarehouseItem>) {
        items.forEach { put(it) }
    }
    
    fun remove(itemId: String) {
        synchronized(cacheLock) {
            val keys = itemIdToKeys.remove(itemId) ?: return
            keys.forEach { key ->
                val item = itemCache.remove(key)
                if (item != null) {
                    typeCache[item.itemType]?.removeAll { StorageKeyUtil.generateKey(it) == key }
                    rarityCache[item.rarity]?.removeAll { StorageKeyUtil.generateKey(it) == key }
                }
                accessOrder.remove(key)
            }
        }
        lastAccessTime.set(System.currentTimeMillis())
    }
    
    fun removeItem(item: WarehouseItem) {
        val key = StorageKeyUtil.generateKey(item)
        
        synchronized(cacheLock) {
            itemCache.remove(key)
            
            itemIdToKeys[item.itemId]?.remove(key)
            if (itemIdToKeys[item.itemId]?.isEmpty() == true) {
                itemIdToKeys.remove(item.itemId)
            }
            
            typeCache[item.itemType]?.removeAll { StorageKeyUtil.generateKey(it) == key }
            rarityCache[item.rarity]?.removeAll { StorageKeyUtil.generateKey(it) == key }
        }
        
        accessOrder.remove(key)
        lastAccessTime.set(System.currentTimeMillis())
    }
    
    fun updateStats(stats: WarehouseStats) {
        statsCacheRef.set(stats)
        lastAccessTime.set(System.currentTimeMillis())
    }
    
    fun clear() {
        synchronized(cacheLock) {
            itemCache.clear()
            typeCache.clear()
            rarityCache.clear()
            itemIdToKeys.clear()
            accessOrder.clear()
            statsCacheRef.set(null)
        }
        lastAccessTime.set(System.currentTimeMillis())
    }
    
    fun invalidate(itemId: String) {
        remove(itemId)
    }
    
    fun isExpired(): Boolean {
        return System.currentTimeMillis() - lastAccessTime.get() > CACHE_TTL
    }
    
    fun evictExpired() {
        val now = System.currentTimeMillis()
        val expiredKeys: List<String>
        
        synchronized(cacheLock) {
            expiredKeys = accessOrder.entries
                .filter { now - it.value > CACHE_TTL }
                .map { it.key }
                .toList()
            
            expiredKeys.forEach { key ->
                val item = itemCache.remove(key)
                if (item != null) {
                    itemIdToKeys[item.itemId]?.remove(key)
                    if (itemIdToKeys[item.itemId]?.isEmpty() == true) {
                        itemIdToKeys.remove(item.itemId)
                    }
                    typeCache[item.itemType]?.removeAll { StorageKeyUtil.generateKey(it) == key }
                    rarityCache[item.rarity]?.removeAll { StorageKeyUtil.generateKey(it) == key }
                }
                accessOrder.remove(key)
                evictionCount.incrementAndGet()
            }
        }
    }
    
    private fun evictEldest() {
        val eldestKey = itemCache.keys.firstOrNull() ?: return
        val eldestItem = itemCache.remove(eldestKey)
        accessOrder.remove(eldestKey)
        
        eldestItem?.let { item ->
            itemIdToKeys[item.itemId]?.remove(eldestKey)
            if (itemIdToKeys[item.itemId]?.isEmpty() == true) {
                itemIdToKeys.remove(item.itemId)
            }
            typeCache[item.itemType]?.removeAll { StorageKeyUtil.generateKey(it) == eldestKey }
            rarityCache[item.rarity]?.removeAll { StorageKeyUtil.generateKey(it) == eldestKey }
        }
        
        evictionCount.incrementAndGet()
    }
    
    fun size(): Int {
        synchronized(cacheLock) {
            return itemCache.size
        }
    }
    
    fun getTypeCount(): Int = typeCache.size
    
    fun getMaxCacheSize(): Int = maxCacheSize
    
    fun getCacheMetrics(): CacheMetrics {
        synchronized(cacheLock) {
            return CacheMetrics(
                itemCount = itemCache.size,
                typeCount = typeCache.size,
                rarityCount = rarityCache.size,
                lastAccessTime = lastAccessTime.get(),
                isExpired = isExpired(),
                hitRate = calculateHitRate(),
                evictionCount = evictionCount.get(),
                maxCacheSize = maxCacheSize
            )
        }
    }
    
    private fun calculateHitRate(): Float {
        val hits = hitCount.get()
        val misses = missCount.get()
        val total = hits + misses
        return if (total == 0L) 0f else hits.toFloat() / total
    }
    
    fun resetStats() {
        hitCount.set(0)
        missCount.set(0)
        evictionCount.set(0)
    }
}

data class CacheMetrics(
    val itemCount: Int,
    val typeCount: Int,
    val rarityCount: Int,
    val lastAccessTime: Long,
    val isExpired: Boolean,
    val hitRate: Float = 0f,
    val evictionCount: Long = 0,
    val maxCacheSize: Int = 500
) {
    val utilizationRate: Float get() = if (maxCacheSize > 0) itemCount.toFloat() / maxCacheSize else 0f
}
