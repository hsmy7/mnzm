package com.xianxia.sect.core.engine

import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.model.SectWarehouse
import com.xianxia.sect.core.model.WarehouseItem
import com.xianxia.sect.core.warehouse.ItemFilter
import com.xianxia.sect.core.warehouse.OptimizedWarehouseManager
import com.xianxia.sect.core.warehouse.WarehousePage
import com.xianxia.sect.core.warehouse.WarehouseStats
import com.xianxia.sect.core.warehouse.WarehouseDiff
import com.xianxia.sect.core.warehouse.PerformanceMetrics

object SectWarehouseManager {
    
    private var useOptimized = true
    var inventoryConfig: InventoryConfig = InventoryConfig.DEFAULT
    
    fun addItemToWarehouse(warehouse: SectWarehouse, item: WarehouseItem): SectWarehouse {
        return if (useOptimized) {
            OptimizedWarehouseManager.addItem(warehouse, item)
        } else {
            addItemToWarehouseLegacy(warehouse, item)
        }
    }
    
    private fun addItemToWarehouseLegacy(warehouse: SectWarehouse, item: WarehouseItem): SectWarehouse {
        val existingIndex = warehouse.items.indexOfFirst { 
            it.itemId == item.itemId && 
            it.itemType == item.itemType && 
            it.rarity == item.rarity &&
            it.itemName == item.itemName
        }
        
        return if (existingIndex >= 0) {
            val updatedItems = warehouse.items.toMutableList()
            val existing = updatedItems[existingIndex]
            val maxStack = inventoryConfig.getMaxStackSize(item.itemType)
            val newQty = (existing.quantity + item.quantity).coerceAtMost(maxStack)
            updatedItems[existingIndex] = existing.copy(quantity = newQty)
            warehouse.copy(items = updatedItems)
        } else {
            warehouse.copy(items = warehouse.items + item)
        }
    }
    
    fun addItemsToWarehouse(warehouse: SectWarehouse, items: List<WarehouseItem>): SectWarehouse {
        return if (useOptimized) {
            OptimizedWarehouseManager.addItemsBatch(warehouse, items)
        } else {
            addItemsToWarehouseLegacy(warehouse, items)
        }
    }
    
    private fun addItemsToWarehouseLegacy(warehouse: SectWarehouse, items: List<WarehouseItem>): SectWarehouse {
        var result = warehouse
        items.forEach { item ->
            result = addItemToWarehouseLegacy(result, item)
        }
        return result
    }
    
    fun addSpiritStonesToWarehouse(warehouse: SectWarehouse, amount: Long): SectWarehouse {
        return if (useOptimized) {
            OptimizedWarehouseManager.addSpiritStones(warehouse, amount)
        } else {
            warehouse.copy(spiritStones = warehouse.spiritStones + amount)
        }
    }
    
    fun transferWarehouse(source: SectWarehouse): SectWarehouse {
        return if (useOptimized) {
            OptimizedWarehouseManager.transferWarehouse(source)
        } else {
            source.copy()
        }
    }
    
    fun clearWarehouse(warehouse: SectWarehouse): SectWarehouse {
        return if (useOptimized) {
            OptimizedWarehouseManager.clearWarehouse(warehouse)
        } else {
            SectWarehouse()
        }
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
    
    fun convertWarRewardsToWarehouseItems(rewards: WarRewards): List<WarehouseItem> {
        val items = mutableListOf<WarehouseItem>()
        
        rewards.equipmentStacks.forEach { stack ->
            items.add(WarehouseItem(
                itemId = stack.id,
                itemName = stack.name,
                itemType = "equipment_stack",
                rarity = stack.rarity,
                quantity = stack.quantity
            ))
        }
        
        rewards.manualStacks.forEach { stack ->
            items.add(WarehouseItem(
                itemId = stack.id,
                itemName = stack.name,
                itemType = "manual_stack",
                rarity = stack.rarity,
                quantity = stack.quantity
            ))
        }
        
        rewards.pills.forEach { pill ->
            items.add(WarehouseItem(
                itemId = pill.id,
                itemName = pill.name,
                itemType = "pill",
                rarity = pill.rarity,
                quantity = pill.quantity
            ))
        }
        
        rewards.materials.forEach { material ->
            items.add(WarehouseItem(
                itemId = material.id,
                itemName = material.name,
                itemType = "material",
                rarity = material.rarity,
                quantity = material.quantity
            ))
        }
        
        rewards.herbs.forEach { herb ->
            items.add(WarehouseItem(
                itemId = herb.id,
                itemName = herb.name,
                itemType = "herb",
                rarity = herb.rarity,
                quantity = herb.quantity
            ))
        }
        
        rewards.seeds.forEach { seed ->
            items.add(WarehouseItem(
                itemId = seed.id,
                itemName = seed.name,
                itemType = "seed",
                rarity = seed.rarity,
                quantity = seed.quantity
            ))
        }
        
        return items
    }
    
    fun calculateWarehouseLootLoss(warehouse: SectWarehouse): PlayerLootLossResult {
        val lostSpiritStones = (warehouse.spiritStones * 0.4).toLong().coerceAtLeast(0)
        val lostMaterials = mutableMapOf<String, Int>()
        warehouse.items.filter { it.quantity > 0 }.forEach { item ->
            val loss = (item.quantity * 0.4).toInt().coerceAtLeast(1)
            lostMaterials[item.itemId] = loss
        }
        return PlayerLootLossResult(lostSpiritStones, lostMaterials)
    }
    
    fun applyLootLossToWarehouse(warehouse: SectWarehouse, loss: PlayerLootLossResult): SectWarehouse {
        val updatedSpiritStones = (warehouse.spiritStones - loss.lostSpiritStones).coerceAtLeast(0)
        val removals = loss.lostMaterials
        val updatedItems = warehouse.items.mapNotNull { item ->
            val removeCount = removals[item.itemId] ?: 0
            if (removeCount <= 0) return@mapNotNull item
            val newQuantity = item.quantity - removeCount
            if (newQuantity > 0) {
                item.copy(quantity = newQuantity)
            } else {
                null
            }
        }
        return warehouse.copy(items = updatedItems, spiritStones = updatedSpiritStones)
    }
    
    fun removeItem(warehouse: SectWarehouse, itemId: String, count: Int = 1): SectWarehouse {
        return OptimizedWarehouseManager.removeItem(warehouse, itemId, count)
    }
    
    fun removeItemsBatch(warehouse: SectWarehouse, removals: Map<String, Int>): SectWarehouse {
        return OptimizedWarehouseManager.removeItemsBatch(warehouse, removals)
    }
    
    fun findByItemId(warehouse: SectWarehouse, itemId: String): WarehouseItem? {
        return OptimizedWarehouseManager.findByItemId(warehouse, itemId)
    }
    
    fun findByType(warehouse: SectWarehouse, type: String): List<WarehouseItem> {
        return OptimizedWarehouseManager.findByType(warehouse, type)
    }
    
    fun findByRarity(warehouse: SectWarehouse, rarity: Int): List<WarehouseItem> {
        return OptimizedWarehouseManager.findByRarity(warehouse, rarity)
    }
    
    fun loadPage(
        warehouse: SectWarehouse,
        page: Int,
        pageSize: Int = 20,
        filter: ItemFilter? = null
    ): WarehousePage {
        return OptimizedWarehouseManager.loadPage(warehouse, page, pageSize, filter)
    }
    
    fun search(warehouse: SectWarehouse, keyword: String, limit: Int = 50): List<WarehouseItem> {
        return OptimizedWarehouseManager.search(warehouse, keyword, limit)
    }
    
    fun getStats(warehouse: SectWarehouse): WarehouseStats {
        return OptimizedWarehouseManager.getStats(warehouse)
    }
    
    fun computeDiff(warehouse: SectWarehouse): WarehouseDiff {
        return OptimizedWarehouseManager.computeDiff(warehouse)
    }
    
    fun applyDiff(warehouse: SectWarehouse, diff: WarehouseDiff): SectWarehouse {
        return OptimizedWarehouseManager.applyDiff(warehouse, diff)
    }
    
    fun applyPendingChanges(warehouse: SectWarehouse): SectWarehouse {
        return OptimizedWarehouseManager.applyPendingChanges(warehouse)
    }
    
    fun hasPendingChanges(): Boolean {
        return OptimizedWarehouseManager.getPerformanceMetrics().pendingChanges > 0
    }
    
    fun compressWarehouse(warehouse: SectWarehouse): ByteArray {
        return OptimizedWarehouseManager.compressWarehouse(warehouse)
    }
    
    fun decompressWarehouse(bytes: ByteArray, spiritStones: Long = 0): SectWarehouse {
        return OptimizedWarehouseManager.decompressWarehouse(bytes, spiritStones)
    }
    
    fun getPerformanceMetrics(): PerformanceMetrics {
        return OptimizedWarehouseManager.getPerformanceMetrics()
    }
    
    fun warmUpCache(items: List<WarehouseItem>) {
        OptimizedWarehouseManager.warmUpCache(items)
    }
    
    fun setOptimizedMode(enabled: Boolean) {
        useOptimized = enabled
    }
    
    fun shutdown() {
        OptimizedWarehouseManager.shutdown()
    }
}
