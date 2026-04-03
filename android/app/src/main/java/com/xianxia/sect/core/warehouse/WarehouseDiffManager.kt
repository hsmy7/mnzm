package com.xianxia.sect.core.warehouse

import com.xianxia.sect.core.model.SectWarehouse
import com.xianxia.sect.core.model.WarehouseItem
import java.util.concurrent.ConcurrentLinkedQueue

object WarehouseDiffManager {
    private val pendingChanges = ConcurrentLinkedQueue<WarehouseChange>()
    private var lastSnapshot: SectWarehouse? = null
    private var lastDiff: WarehouseDiff? = null
    private var lastFlushTime = 0L
    private const val FLUSH_INTERVAL = 1000L
    private const val MAX_PENDING_CHANGES = 100
    
    fun setSnapshot(warehouse: SectWarehouse) {
        lastSnapshot = warehouse
    }
    
    fun computeDiff(current: SectWarehouse): WarehouseDiff {
        val last = lastSnapshot ?: return WarehouseDiff(
            addedItems = current.items,
            spiritStonesDelta = current.spiritStones
        )
        
        val addedItems = mutableListOf<WarehouseItem>()
        val removedItemIds = mutableSetOf<String>()
        val quantityChanges = mutableMapOf<String, Int>()
        
        val currentKeyMap = mutableMapOf<String, WarehouseItem>()
        current.items.forEach { item ->
            val key = generateKey(item)
            currentKeyMap[key] = item
        }
        
        val lastKeyMap = mutableMapOf<String, WarehouseItem>()
        last.items.forEach { item ->
            val key = generateKey(item)
            lastKeyMap[key] = item
        }
        
        currentKeyMap.forEach { (key, item) ->
            if (!lastKeyMap.containsKey(key)) {
                addedItems.add(item)
            } else {
                val lastItem = lastKeyMap[key]
                if (lastItem != null && lastItem.quantity != item.quantity) {
                    quantityChanges[item.itemId] = item.quantity - lastItem.quantity
                }
            }
        }
        
        lastKeyMap.forEach { (key, item) ->
            if (!currentKeyMap.containsKey(key)) {
                removedItemIds.add(item.itemId)
            }
        }
        
        val spiritStonesDelta = current.spiritStones - last.spiritStones
        
        return WarehouseDiff(
            addedItems = addedItems,
            removedItemIds = removedItemIds,
            quantityChanges = quantityChanges,
            spiritStonesDelta = spiritStonesDelta
        )
    }
    
    fun applyDiff(base: SectWarehouse, diff: WarehouseDiff): SectWarehouse {
        val items = base.items.toMutableList()
        
        items.removeAll { it.itemId in diff.removedItemIds }
        
        diff.addedItems.forEach { newItem ->
            val existingIndex = items.indexOfFirst { 
                it.itemId == newItem.itemId && 
                it.itemType == newItem.itemType && 
                it.rarity == newItem.rarity 
            }
            
            if (existingIndex >= 0) {
                items[existingIndex] = items[existingIndex].copy(
                    quantity = items[existingIndex].quantity + newItem.quantity
                )
            } else {
                items.add(newItem)
            }
        }
        
        diff.quantityChanges.forEach { (itemId, delta) ->
            val idx = items.indexOfFirst { it.itemId == itemId }
            if (idx >= 0) {
                items[idx] = items[idx].copy(
                    quantity = (items[idx].quantity + delta).coerceAtLeast(0)
                )
            }
        }
        
        return SectWarehouse(
            items = items.filter { it.quantity > 0 },
            spiritStones = base.spiritStones + diff.spiritStonesDelta
        )
    }
    
    fun queueChange(change: WarehouseChange) {
        pendingChanges.add(change)
        
        if (pendingChanges.size >= MAX_PENDING_CHANGES) {
            flush()
        }
    }
    
    fun queueAdd(item: WarehouseItem) {
        queueChange(WarehouseChange.Add(item))
    }
    
    fun queueRemove(itemId: String, count: Int) {
        queueChange(WarehouseChange.Remove(itemId, count))
    }
    
    fun queueUpdate(itemId: String, newQuantity: Int) {
        queueChange(WarehouseChange.Update(itemId, newQuantity))
    }
    
    fun queueSpiritStones(delta: Long) {
        queueChange(WarehouseChange.SpiritStones(delta))
    }
    
    fun flush(): List<WarehouseChange> {
        if (pendingChanges.isEmpty()) return emptyList()
        
        val changes = mutableListOf<WarehouseChange>()
        while (pendingChanges.isNotEmpty()) {
            pendingChanges.poll()?.let { changes.add(it) }
        }
        
        lastFlushTime = System.currentTimeMillis()
        lastDiff = aggregateChanges(changes)
        
        return changes
    }
    
    fun applyPendingChanges(warehouse: SectWarehouse): SectWarehouse {
        val changes = flush()
        if (changes.isEmpty()) return warehouse
        
        var result = warehouse
        changes.forEach { change ->
            result = when (change) {
                is WarehouseChange.Add -> addItem(result, change.item)
                is WarehouseChange.Remove -> removeItem(result, change.itemId, change.count)
                is WarehouseChange.Update -> updateQuantity(result, change.itemId, change.newQuantity)
                is WarehouseChange.SpiritStones -> result.copy(
                    spiritStones = result.spiritStones + change.delta
                )
            }
        }
        
        return result
    }
    
    fun hasPendingChanges(): Boolean = pendingChanges.isNotEmpty()
    
    fun getPendingChangeCount(): Int = pendingChanges.size
    
    fun getLastDiff(): WarehouseDiff? = lastDiff
    
    fun clearPendingChanges() {
        pendingChanges.clear()
    }
    
    fun reset() {
        pendingChanges.clear()
        lastSnapshot = null
        lastDiff = null
        lastFlushTime = 0L
    }
    
    private fun aggregateChanges(changes: List<WarehouseChange>): WarehouseDiff {
        val addedItems = mutableListOf<WarehouseItem>()
        val removedItemIds = mutableSetOf<String>()
        val quantityChanges = mutableMapOf<String, Int>()
        var spiritStonesDelta = 0L
        
        changes.forEach { change ->
            when (change) {
                is WarehouseChange.Add -> addedItems.add(change.item)
                is WarehouseChange.Remove -> {
                    removedItemIds.add(change.itemId)
                    quantityChanges[change.itemId] = -change.count
                }
                is WarehouseChange.Update -> {
                    quantityChanges[change.itemId] = change.newQuantity
                }
                is WarehouseChange.SpiritStones -> {
                    spiritStonesDelta += change.delta
                }
            }
        }
        
        return WarehouseDiff(
            addedItems = addedItems,
            removedItemIds = removedItemIds,
            quantityChanges = quantityChanges,
            spiritStonesDelta = spiritStonesDelta
        )
    }
    
    private fun addItem(warehouse: SectWarehouse, item: WarehouseItem): SectWarehouse {
        val existingIndex = warehouse.items.indexOfFirst { 
            it.itemId == item.itemId && 
            it.itemType == item.itemType && 
            it.rarity == item.rarity 
        }
        
        return if (existingIndex >= 0) {
            val updatedItems = warehouse.items.toMutableList()
            val existing = updatedItems[existingIndex]
            updatedItems[existingIndex] = existing.copy(quantity = existing.quantity + item.quantity)
            warehouse.copy(items = updatedItems)
        } else {
            warehouse.copy(items = warehouse.items + item)
        }
    }
    
    private fun removeItem(warehouse: SectWarehouse, itemId: String, count: Int): SectWarehouse {
        val items = warehouse.items.mapNotNull { item ->
            if (item.itemId == itemId) {
                val newQuantity = item.quantity - count
                if (newQuantity > 0) {
                    item.copy(quantity = newQuantity)
                } else {
                    null
                }
            } else {
                item
            }
        }
        
        return warehouse.copy(items = items)
    }
    
    private fun updateQuantity(warehouse: SectWarehouse, itemId: String, newQuantity: Int): SectWarehouse {
        val items = warehouse.items.map { item ->
            if (item.itemId == itemId) {
                item.copy(quantity = newQuantity.coerceAtLeast(0))
            } else {
                item
            }
        }.filter { it.quantity > 0 }
        
        return warehouse.copy(items = items)
    }
    
    private fun generateKey(item: WarehouseItem): String {
        return "${item.itemId}:${item.itemType}:${item.rarity}"
    }
}
