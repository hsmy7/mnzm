package com.xianxia.sect.core.warehouse

import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.model.SectWarehouse
import com.xianxia.sect.core.model.WarehouseItem
import com.xianxia.sect.core.engine.CaveRewards
import java.util.concurrent.ConcurrentHashMap

object OptimizedWarehouseManager {
    
    var inventoryConfig: InventoryConfig = InventoryConfig.DEFAULT
    
    private val itemIndex = ConcurrentHashMap<String, Int>()
    private val typeIndex = ConcurrentHashMap<String, MutableList<Int>>()
    private val rarityIndex = ConcurrentHashMap<Int, MutableList<Int>>()
    private var currentWarehouseItems: List<WarehouseItem> = emptyList()
    
    fun buildOptimizedWarehouse(items: List<WarehouseItem>, spiritStones: Long = 0): SectWarehouse {
        rebuildIndex(items)
        currentWarehouseItems = items
        WarehouseCache.putAll(items)
        WarehouseCache.updateStats(WarehousePager.calculateStats(items))
        WarehouseDiffManager.setSnapshot(SectWarehouse(items = items, spiritStones = spiritStones))
        
        return SectWarehouse(items = items, spiritStones = spiritStones)
    }
    
    fun rebuildIndex(items: List<WarehouseItem>) {
        itemIndex.clear()
        typeIndex.clear()
        rarityIndex.clear()
        
        items.forEachIndexed { idx, item ->
            addToIndexInternal(item, idx)
        }
    }
    
    private fun addToIndexInternal(item: WarehouseItem, index: Int) {
        val key = StorageKeyUtil.generateKey(item)
        itemIndex[key] = index
        
        typeIndex.getOrPut(item.itemType) { 
            java.util.Collections.synchronizedList(mutableListOf()) 
        }.apply {
            synchronized(this) {
                if (!contains(index)) add(index)
            }
        }
        
        rarityIndex.getOrPut(item.rarity) { 
            java.util.Collections.synchronizedList(mutableListOf()) 
        }.apply {
            synchronized(this) {
                if (!contains(index)) add(index)
            }
        }
    }
    
    private fun removeFromIndexInternal(item: WarehouseItem, index: Int) {
        val key = StorageKeyUtil.generateKey(item)
        itemIndex.remove(key)
        
        typeIndex[item.itemType]?.apply {
            synchronized(this) {
                remove(index)
            }
        }
        
        rarityIndex[item.rarity]?.apply {
            synchronized(this) {
                remove(index)
            }
        }
    }
    
    private fun addToIndex(item: WarehouseItem, index: Int) {
        addToIndexInternal(item, index)
    }
    
    private fun removeFromIndex(item: WarehouseItem, index: Int) {
        removeFromIndexInternal(item, index)
    }
    
    fun findByItemId(warehouse: SectWarehouse, itemId: String): WarehouseItem? {
        val cached = WarehouseCache.getItem(itemId).firstOrNull()
        if (cached != null) return cached
        
        val indices = itemIndex.entries
            .filter { it.key.startsWith("$itemId:") }
            .map { it.value }
        
        if (indices.isNotEmpty()) {
            return warehouse.items.getOrNull(indices.first())
        }
        
        val idx = warehouse.items.indexOfFirst { it.itemId == itemId }
        return if (idx >= 0) warehouse.items[idx] else null
    }
    
    fun findByType(warehouse: SectWarehouse, type: String): List<WarehouseItem> {
        val cached = WarehouseCache.getItemsByType(type)
        if (cached.isNotEmpty()) return cached
        
        val indices = typeIndex[type] ?: return emptyList()
        return indices.mapNotNull { idx -> warehouse.items.getOrNull(idx) }
    }
    
    fun findByRarity(warehouse: SectWarehouse, rarity: Int): List<WarehouseItem> {
        val cached = WarehouseCache.getItemsByRarity(rarity)
        if (cached.isNotEmpty()) return cached
        
        val indices = rarityIndex[rarity] ?: return emptyList()
        return indices.mapNotNull { idx -> warehouse.items.getOrNull(idx) }
    }
    
    fun addItem(warehouse: SectWarehouse, item: WarehouseItem): SectWarehouse {
        WarehouseDiffManager.queueAdd(item)
        
        val existingKey = StorageKeyUtil.generateKey(item)
        val existingIndex = itemIndex[existingKey]
        
        val newWarehouse = if (existingIndex != null && existingIndex >= 0 && existingIndex < warehouse.items.size) {
            val updatedItems = warehouse.items.toMutableList()
            val existing = updatedItems[existingIndex]
            val maxStack = inventoryConfig.getMaxStackSize(item.itemType)
            val newQty = (existing.quantity + item.quantity).coerceAtMost(maxStack)
            updatedItems[existingIndex] = existing.copy(quantity = newQty)
            warehouse.copy(items = updatedItems)
        } else {
            val newItems = warehouse.items + item
            val newIndex = newItems.size - 1
            addToIndex(item, newIndex)
            warehouse.copy(items = newItems)
        }
        
        currentWarehouseItems = newWarehouse.items
        WarehouseCache.put(item)
        WarehouseCache.updateStats(WarehousePager.calculateStats(newWarehouse.items))
        
        return newWarehouse
    }
    
    fun addItemsBatch(warehouse: SectWarehouse, items: List<WarehouseItem>): SectWarehouse {
        items.forEach { WarehouseDiffManager.queueAdd(it) }
        
        val itemMap = warehouse.items.associateBy { generateKey(it) }.toMutableMap()
        val existingIndices = mutableMapOf<String, Int>()
        
        warehouse.items.forEachIndexed { idx, item ->
            existingIndices[generateKey(item)] = idx
        }
        
        var nextIndex = warehouse.items.size
        items.forEach { newItem ->
            val key = generateKey(newItem)
            val existing = itemMap[key]
            if (existing != null) {
                val maxStack = inventoryConfig.getMaxStackSize(newItem.itemType)
                itemMap[key] = existing.copy(quantity = (existing.quantity + newItem.quantity).coerceAtMost(maxStack))
            } else {
                itemMap[key] = newItem
            }
        }
        
        val newItems = itemMap.values.toList()
        val newWarehouse = warehouse.copy(items = newItems)
        
        newItems.forEachIndexed { idx, item ->
            val key = generateKey(item)
            if (!itemIndex.containsKey(key)) {
                addToIndex(item, idx)
            }
        }
        
        currentWarehouseItems = newItems
        WarehouseCache.putAll(items)
        WarehouseCache.updateStats(WarehousePager.calculateStats(newItems))
        
        return newWarehouse
    }
    
    fun removeItem(warehouse: SectWarehouse, itemId: String, count: Int = 1): SectWarehouse {
        WarehouseDiffManager.queueRemove(itemId, count)
        
        var removedIndex: Int? = null
        var removedItem: WarehouseItem? = null
        
        val items = warehouse.items.mapNotNullIndexed { index, item ->
            if (item.itemId == itemId) {
                val newQuantity = item.quantity - count
                if (newQuantity > 0) {
                    item.copy(quantity = newQuantity)
                } else {
                    removedIndex = index
                    removedItem = item
                    null
                }
            } else {
                item
            }
        }
        
        val newWarehouse = warehouse.copy(items = items)
        
        removedItem?.let { item ->
            removedIndex?.let { idx ->
                removeFromIndex(item, idx)
                shiftIndicesAfter(idx)
            }
        }
        
        currentWarehouseItems = items
        WarehouseCache.remove(itemId)
        WarehouseCache.updateStats(WarehousePager.calculateStats(items))
        
        return newWarehouse
    }
    
    private fun shiftIndicesAfter(removedIndex: Int) {
        val iterator = itemIndex.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value > removedIndex) {
                entry.setValue(entry.value - 1)
            }
        }

        typeIndex.values.forEach { list ->
            synchronized(list) {
                for (i in list.indices) {
                    if (list[i] > removedIndex) {
                        list[i] = list[i] - 1
                    }
                }
            }
        }

        rarityIndex.values.forEach { list ->
            synchronized(list) {
                for (i in list.indices) {
                    if (list[i] > removedIndex) {
                        list[i] = list[i] - 1
                    }
                }
            }
        }
    }
    
    private inline fun <T> List<T>.mapNotNullIndexed(transform: (Int, T) -> T?): List<T> {
        return mapIndexedNotNull { index, element -> transform(index, element) }
    }
    
    fun removeItemsBatch(warehouse: SectWarehouse, removals: Map<String, Int>): SectWarehouse {
        removals.forEach { (itemId, count) -> 
            WarehouseDiffManager.queueRemove(itemId, count) 
        }
        
        val items = warehouse.items.mapNotNull { item ->
            val removeCount = removals[item.itemId] ?: 0
            if (removeCount <= 0) return@mapNotNull item
            
            val newQuantity = item.quantity - removeCount
            if (newQuantity > 0) {
                item.copy(quantity = newQuantity)
            } else {
                null
            }
        }
        
        val newWarehouse = warehouse.copy(items = items)
        
        rebuildIndex(items)
        currentWarehouseItems = items
        removals.keys.forEach { WarehouseCache.remove(it) }
        WarehouseCache.updateStats(WarehousePager.calculateStats(items))
        
        return newWarehouse
    }
    
    fun addSpiritStones(warehouse: SectWarehouse, amount: Long): SectWarehouse {
        WarehouseDiffManager.queueSpiritStones(amount)
        return warehouse.copy(spiritStones = warehouse.spiritStones + amount)
    }
    
    fun loadPage(
        warehouse: SectWarehouse,
        page: Int,
        pageSize: Int = WarehousePager.DEFAULT_PAGE_SIZE,
        filter: ItemFilter? = null
    ): WarehousePage {
        return WarehousePager.loadPage(warehouse.items, page, pageSize, filter)
    }
    
    fun search(warehouse: SectWarehouse, keyword: String, limit: Int = 50): List<WarehouseItem> {
        return WarehousePager.search(warehouse.items, keyword, limit)
    }
    
    fun getStats(warehouse: SectWarehouse): WarehouseStats {
        val cached = WarehouseCache.getStats()
        if (cached != null && cached.totalItems == warehouse.items.size) {
            return cached
        }
        
        val stats = WarehousePager.calculateStats(warehouse.items)
        WarehouseCache.updateStats(stats)
        return stats
    }
    
    fun computeDiff(warehouse: SectWarehouse): WarehouseDiff {
        return WarehouseDiffManager.computeDiff(warehouse)
    }
    
    fun applyDiff(warehouse: SectWarehouse, diff: WarehouseDiff): SectWarehouse {
        val newWarehouse = WarehouseDiffManager.applyDiff(warehouse, diff)
        rebuildIndex(newWarehouse.items)
        currentWarehouseItems = newWarehouse.items
        WarehouseCache.clear()
        WarehouseCache.putAll(newWarehouse.items)
        WarehouseCache.updateStats(WarehousePager.calculateStats(newWarehouse.items))
        return newWarehouse
    }
    
    fun applyPendingChanges(warehouse: SectWarehouse): SectWarehouse {
        if (!WarehouseDiffManager.hasPendingChanges()) return warehouse
        
        val newWarehouse = WarehouseDiffManager.applyPendingChanges(warehouse)
        rebuildIndex(newWarehouse.items)
        currentWarehouseItems = newWarehouse.items
        WarehouseCache.clear()
        WarehouseCache.putAll(newWarehouse.items)
        WarehouseCache.updateStats(WarehousePager.calculateStats(newWarehouse.items))
        return newWarehouse
    }
    
    fun compressWarehouse(warehouse: SectWarehouse): ByteArray {
        return WarehouseCompressor.compressToBytes(warehouse.items)
    }
    
    fun decompressWarehouse(bytes: ByteArray, spiritStones: Long = 0): SectWarehouse {
        val items = WarehouseCompressor.decompressFromBytes(bytes)
        return buildOptimizedWarehouse(items, spiritStones)
    }
    
    fun transferWarehouse(source: SectWarehouse): SectWarehouse {
        return source.copy()
    }
    
    fun clearWarehouse(warehouse: SectWarehouse): SectWarehouse {
        itemIndex.clear()
        typeIndex.clear()
        rarityIndex.clear()
        currentWarehouseItems = emptyList()
        WarehouseCache.clear()
        WarehouseDiffManager.reset()
        return SectWarehouse()
    }
    
    fun convertCaveRewardsToWarehouseItems(rewards: CaveRewards): List<WarehouseItem> {
        return rewards.items.filter { it.type != "spiritStones" }.map { reward ->
            WarehouseItem(
                itemId = reward.itemId,
                itemName = reward.name,
                itemType = reward.type,
                rarity = reward.rarity,
                quantity = reward.quantity
            )
        }
    }
    
    fun getPerformanceMetrics(): PerformanceMetrics {
        return PerformanceMetrics(
            indexSize = itemIndex.size,
            typeIndexCount = typeIndex.size,
            rarityIndexCount = rarityIndex.size,
            cacheMetrics = WarehouseCache.getCacheMetrics(),
            compressionStats = WarehouseCompressor.getPoolStats(),
            pendingChanges = WarehouseDiffManager.getPendingChangeCount()
        )
    }
    
    fun warmUpCache(items: List<WarehouseItem>) {
        WarehouseCache.putAll(items)
    }
    
    fun shutdown() {
        itemIndex.clear()
        typeIndex.clear()
        rarityIndex.clear()
        currentWarehouseItems = emptyList()
        WarehouseCache.clear()
        WarehouseDiffManager.reset()
        WarehouseCompressor.clearPools()
    }
    
    fun generateKey(item: WarehouseItem): String {
        return "${item.itemId}:${item.itemType}:${item.rarity}:${item.itemName}"
    }
}

data class PerformanceMetrics(
    val indexSize: Int,
    val typeIndexCount: Int,
    val rarityIndexCount: Int,
    val cacheMetrics: CacheMetrics,
    val compressionStats: CompressionStats,
    val pendingChanges: Int
)
