package com.xianxia.sect.core.warehouse

import com.xianxia.sect.core.model.WarehouseItem

data class ItemFilter(
    val types: Set<String>? = null,
    val minRarity: Int? = null,
    val maxRarity: Int? = null,
    val keyword: String? = null,
    val sortBy: SortBy = SortBy.RARITY_DESC,
    val capacityLimit: Int? = null
) {
    enum class SortBy {
        RARITY_DESC,
        RARITY_ASC,
        NAME_ASC,
        NAME_DESC,
        TYPE_ASC,
        QUANTITY_DESC,
        QUANTITY_ASC
    }
    
    fun matches(item: WarehouseItem): Boolean {
        if (types != null && item.itemType !in types) return false
        if (minRarity != null && item.rarity < minRarity) return false
        if (maxRarity != null && item.rarity > maxRarity) return false
        if (keyword != null && keyword.isNotBlank()) {
            if (!item.itemName.contains(keyword, ignoreCase = true) &&
                !item.itemId.contains(keyword, ignoreCase = true)) {
                return false
            }
        }
        return true
    }
}

data class WarehousePage(
    val items: List<WarehouseItem>,
    val pageIndex: Int,
    val pageSize: Int,
    val totalItems: Int,
    val totalPages: Int,
    val hasMore: Boolean
) {
    val isEmpty: Boolean get() = items.isEmpty()
    val isFirstPage: Boolean get() = pageIndex == 0
    val isLastPage: Boolean get() = !hasMore
}

data class WarehouseDiff(
    val addedItems: List<WarehouseItem> = emptyList(),
    val removedItemIds: Set<String> = emptySet(),
    val quantityChanges: Map<String, Int> = emptyMap(),
    val spiritStonesDelta: Long = 0,
    val timestamp: Long = System.currentTimeMillis()
) {
    val hasChanges: Boolean get() = 
        addedItems.isNotEmpty() || 
        removedItemIds.isNotEmpty() || 
        quantityChanges.isNotEmpty() ||
        spiritStonesDelta != 0L
}

data class CompactWarehouseItem(
    val id: Int,
    val nameId: Int,
    val type: Byte,
    val rarity: Byte,
    val quantity: Short
) {
    companion object {
        const val TYPE_PILL: Byte = 1
        const val TYPE_EQUIPMENT: Byte = 2
        const val TYPE_MANUAL: Byte = 3
        const val TYPE_MATERIAL: Byte = 4
        const val TYPE_HERB: Byte = 5
        const val TYPE_SEED: Byte = 6
    }
}

data class WarehouseStats(
    val totalItems: Int = 0,
    val totalQuantity: Int = 0,
    val itemsByType: Map<String, Int> = emptyMap(),
    val itemsByRarity: Map<Int, Int> = emptyMap(),
    val uniqueItemCount: Int = 0,
    val lastUpdated: Long = System.currentTimeMillis()
)

sealed class WarehouseChange {
    data class Add(val item: WarehouseItem) : WarehouseChange()
    data class Remove(val itemId: String, val count: Int) : WarehouseChange()
    data class Update(val itemId: String, val newQuantity: Int) : WarehouseChange()
    data class SpiritStones(val delta: Long) : WarehouseChange()
}

data class WarehouseCapacity(
    val maxSlots: Int = 200,
    val maxStack: Int = 999,
    val usedSlots: Int = 0,
    val expansionLevel: Int = 0
) {
    val availableSlots: Int get() = (maxSlots + expansionLevel * 50) - usedSlots
    val isFull: Boolean get() = availableSlots <= 0
    val utilizationRate: Float get() = 
        if (maxSlots + expansionLevel * 50 == 0) 0f 
        else usedSlots.toFloat() / (maxSlots + expansionLevel * 50)
}
