package com.xianxia.sect.core.warehouse

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.data.EquipmentDatabase
import com.xianxia.sect.core.data.ManualDatabase
import com.xianxia.sect.core.data.PillRecipeDatabase
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MaterialCategory
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.SectWarehouse
import com.xianxia.sect.core.model.WarehouseItem
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.engine.CaveRewards
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

object OptimizedWarehouseManager {
    
    const val MIN_YEARLY_ITEMS = 30
    const val MAX_YEARLY_ITEMS = 50
    const val MAX_RARITY = 4
    
    private val itemIndex = ConcurrentHashMap<String, Int>()
    private val typeIndex = ConcurrentHashMap<String, MutableList<Int>>()
    private val rarityIndex = ConcurrentHashMap<Int, MutableList<Int>>()
    private var currentWarehouseItems: List<WarehouseItem> = emptyList()
    
    fun generateYearlyItemsForAISect(sect: WorldSect): SectWarehouse {
        val itemCount = Random.nextInt(MIN_YEARLY_ITEMS, MAX_YEARLY_ITEMS + 1)
        val items = mutableListOf<WarehouseItem>()
        
        val itemTypes = listOf("pill", "equipment", "manual", "material", "herb", "seed")
        
        repeat(itemCount) {
            val itemType = itemTypes.random()
            val rarity = Random.nextInt(1, MAX_RARITY + 1)
            
            val item = when (itemType) {
                "pill" -> generateWarehousePill(rarity)
                "equipment" -> generateWarehouseEquipment(rarity)
                "manual" -> generateWarehouseManual(rarity)
                "material" -> generateWarehouseMaterial(rarity)
                "herb" -> generateWarehouseHerb(rarity)
                "seed" -> generateWarehouseSeed(rarity)
                else -> null
            }
            
            if (item != null) {
                items.add(item)
            }
        }
        
        val spiritStones = Random.nextLong(10000, 100000)
        
        return buildOptimizedWarehouse(items, spiritStones)
    }
    
    private fun generateWarehousePill(rarity: Int): WarehouseItem? {
        var currentRarity = rarity
        while (currentRarity >= 1) {
            val recipes = PillRecipeDatabase.getRecipesByTier(currentRarity)
            if (recipes.isNotEmpty()) {
                val recipe = recipes.random()
                return WarehouseItemPool.acquire(
                    itemId = recipe.id,
                    itemName = recipe.name,
                    itemType = "pill",
                    rarity = currentRarity,
                    quantity = Random.nextInt(1, 6)
                )
            }
            currentRarity--
        }
        return null
    }
    
    private fun generateWarehouseEquipment(rarity: Int): WarehouseItem? {
        var currentRarity = rarity
        while (currentRarity >= 1) {
            val allEquipment = EquipmentDatabase.weapons.values.filter { it.rarity == currentRarity } +
                               EquipmentDatabase.armors.values.filter { it.rarity == currentRarity } +
                               EquipmentDatabase.boots.values.filter { it.rarity == currentRarity } +
                               EquipmentDatabase.accessories.values.filter { it.rarity == currentRarity }
            
            if (allEquipment.isNotEmpty()) {
                val template = allEquipment.random()
                return WarehouseItemPool.acquire(
                    itemId = template.id,
                    itemName = template.name,
                    itemType = "equipment",
                    rarity = currentRarity,
                    quantity = 1
                )
            }
            currentRarity--
        }
        return null
    }
    
    private fun generateWarehouseManual(rarity: Int): WarehouseItem? {
        var currentRarity = rarity
        while (currentRarity >= 1) {
            val allManuals = ManualDatabase.getByRarity(currentRarity)
                .filter { it.type != ManualType.SUPPORT }
            
            if (allManuals.isNotEmpty()) {
                val template = allManuals.random()
                return WarehouseItemPool.acquire(
                    itemId = template.id,
                    itemName = template.name,
                    itemType = "manual",
                    rarity = currentRarity,
                    quantity = 1
                )
            }
            currentRarity--
        }
        return null
    }
    
    private fun generateWarehouseMaterial(rarity: Int): WarehouseItem? {
        val materialTypes = MaterialCategory.values()
        val type = materialTypes.random()
        val beastPrefixes = listOf("虎", "狼", "蛇", "熊", "鹰", "狐", "龙", "龟")
        val prefix = beastPrefixes.random()
        
        val name = when (type) {
            MaterialCategory.BEAST_HIDE -> "${prefix}皮"
            MaterialCategory.BEAST_BONE -> "${prefix}骨"
            MaterialCategory.BEAST_TOOTH -> "${prefix}牙"
            MaterialCategory.BEAST_CORE -> "${prefix}内丹"
            MaterialCategory.BEAST_CLAW -> "${prefix}爪"
            MaterialCategory.BEAST_FEATHER -> "${prefix}羽"
            MaterialCategory.BEAST_TAIL -> "${prefix}尾"
            MaterialCategory.BEAST_SCALE -> "${prefix}鳞"
            MaterialCategory.BEAST_HORN -> "${prefix}角"
            MaterialCategory.BEAST_SHELL -> "${prefix}壳"
            MaterialCategory.BEAST_PLASTRON -> "${prefix}甲"
        }
        
        return WarehouseItemPool.acquire(
            itemId = "material_${type.name}_$rarity",
            itemName = name,
            itemType = "material",
            rarity = rarity,
            quantity = Random.nextInt(1, 11)
        )
    }
    
    private fun generateWarehouseHerb(rarity: Int): WarehouseItem? {
        val herbNames = when (rarity) {
            1 -> listOf("灵草", "青灵草", "紫灵草")
            2 -> listOf("金灵草", "银灵草", "玄灵草")
            3 -> listOf("天灵草", "地灵草", "仙灵草")
            4 -> listOf("玄天灵草", "九转灵草", "紫金灵草")
            else -> listOf("灵草")
        }
        
        return WarehouseItemPool.acquire(
            itemId = "herb_${rarity}_${Random.nextInt(1000)}",
            itemName = herbNames.random(),
            itemType = "herb",
            rarity = rarity,
            quantity = Random.nextInt(1, 11)
        )
    }
    
    private fun generateWarehouseSeed(rarity: Int): WarehouseItem? {
        val seedNames = when (rarity) {
            1 -> listOf("灵草种子", "青灵草种子", "紫灵草种子")
            2 -> listOf("金灵草种子", "银灵草种子", "玄灵草种子")
            3 -> listOf("天灵草种子", "地灵草种子", "仙灵草种子")
            4 -> listOf("玄天灵草种子", "九转灵草种子", "紫金灵草种子")
            else -> listOf("灵草种子")
        }
        
        return WarehouseItemPool.acquire(
            itemId = "seed_${rarity}_${Random.nextInt(1000)}",
            itemName = seedNames.random(),
            itemType = "seed",
            rarity = rarity,
            quantity = Random.nextInt(1, 6)
        )
    }
    
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
        val key = generateKey(item)
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
        val key = generateKey(item)
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
        
        val existingKey = generateKey(item)
        val existingIndex = itemIndex[existingKey]
        
        val newWarehouse = if (existingIndex != null && existingIndex >= 0 && existingIndex < warehouse.items.size) {
            val updatedItems = warehouse.items.toMutableList()
            val existing = updatedItems[existingIndex]
            updatedItems[existingIndex] = existing.copy(quantity = existing.quantity + item.quantity)
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
                itemMap[key] = existing.copy(quantity = existing.quantity + newItem.quantity)
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
        val newIndices = ConcurrentHashMap<String, Int>()
        itemIndex.forEach { (key, idx) ->
            newIndices[key] = if (idx > removedIndex) idx - 1 else idx
        }
        itemIndex.clear()
        itemIndex.putAll(newIndices)
        
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
            WarehouseItemPool.acquire(
                itemId = reward.itemId,
                itemName = reward.name,
                itemType = reward.type,
                rarity = reward.rarity,
                quantity = reward.quantity
            )
        }
    }
    
    fun convertLootLossToWarehouseItems(
        lostMaterials: Map<String, Int>,
        materials: List<Material>,
        herbs: List<Herb>,
        seeds: List<Seed>,
        pills: List<Pill>
    ): List<WarehouseItem> {
        val items = mutableListOf<WarehouseItem>()
        
        lostMaterials.forEach { (name, quantity) ->
            val material = materials.find { it.name == name }
            if (material != null) {
                items.add(WarehouseItemPool.acquire(
                    itemId = material.id,
                    itemName = material.name,
                    itemType = "material",
                    rarity = material.rarity,
                    quantity = quantity
                ))
                return@forEach
            }
            
            val herb = herbs.find { it.name == name }
            if (herb != null) {
                items.add(WarehouseItemPool.acquire(
                    itemId = herb.id,
                    itemName = herb.name,
                    itemType = "herb",
                    rarity = herb.rarity,
                    quantity = quantity
                ))
                return@forEach
            }
            
            val seed = seeds.find { it.name == name }
            if (seed != null) {
                items.add(WarehouseItemPool.acquire(
                    itemId = seed.id,
                    itemName = seed.name,
                    itemType = "seed",
                    rarity = seed.rarity,
                    quantity = quantity
                ))
                return@forEach
            }
            
            val pill = pills.find { it.name == name }
            if (pill != null) {
                items.add(WarehouseItemPool.acquire(
                    itemId = pill.id,
                    itemName = pill.name,
                    itemType = "pill",
                    rarity = pill.rarity,
                    quantity = quantity
                ))
                return@forEach
            }
            
            items.add(WarehouseItemPool.acquire(
                itemId = "loot_$name",
                itemName = name,
                itemType = "material",
                rarity = 1,
                quantity = quantity
            ))
        }
        
        return items
    }
    
    fun getPerformanceMetrics(): PerformanceMetrics {
        return PerformanceMetrics(
            indexSize = itemIndex.size,
            typeIndexCount = typeIndex.size,
            rarityIndexCount = rarityIndex.size,
            cacheMetrics = WarehouseCache.getCacheMetrics(),
            poolStats = WarehouseItemPool.getStats(),
            compressionStats = WarehouseCompressor.getPoolStats(),
            pendingChanges = WarehouseDiffManager.getPendingChangeCount()
        )
    }
    
    fun warmUpCache(items: List<WarehouseItem>) {
        WarehouseItemPool.warmUp(items)
        WarehouseCache.putAll(items)
    }
    
    fun shutdown() {
        itemIndex.clear()
        typeIndex.clear()
        rarityIndex.clear()
        currentWarehouseItems = emptyList()
        WarehouseCache.clear()
        WarehouseDiffManager.reset()
        WarehouseItemPool.clear()
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
    val poolStats: PoolStatsSnapshot,
    val compressionStats: CompressionStats,
    val pendingChanges: Int
)
