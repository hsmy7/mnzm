package com.xianxia.sect.core.warehouse

import com.xianxia.sect.core.model.WarehouseItem

object WarehousePager {
    const val DEFAULT_PAGE_SIZE = 72
    const val MAX_PAGE_SIZE = 100
    
    fun loadPage(
        items: List<WarehouseItem>,
        page: Int,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        filter: ItemFilter? = null
    ): WarehousePage {
        val effectivePageSize = pageSize.coerceIn(1, MAX_PAGE_SIZE)
        
        val filtered = if (filter != null) {
            items.filter { filter.matches(it) }
        } else {
            items
        }
        
        val sorted = applySorting(filtered, filter?.sortBy ?: ItemFilter.SortBy.RARITY_DESC)
        
        val totalItems = sorted.size
        val totalPages = (totalItems + effectivePageSize - 1) / effectivePageSize
        val effectivePage = page.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
        
        val start = effectivePage * effectivePageSize
        val end = minOf(start + effectivePageSize, totalItems)
        
        val pageItems = if (start < totalItems) {
            sorted.subList(start, end)
        } else {
            emptyList()
        }
        
        return WarehousePage(
            items = pageItems,
            pageIndex = effectivePage,
            pageSize = effectivePageSize,
            totalItems = totalItems,
            totalPages = totalPages.coerceAtLeast(1),
            hasMore = end < totalItems
        )
    }
    
    fun loadAllPages(
        items: List<WarehouseItem>,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        filter: ItemFilter? = null
    ): List<WarehousePage> {
        val pages = mutableListOf<WarehousePage>()
        var currentPage = 0
        
        do {
            val page = loadPage(items, currentPage, pageSize, filter)
            pages.add(page)
            currentPage++
        } while (page.hasMore)
        
        return pages
    }
    
    fun search(
        items: List<WarehouseItem>,
        keyword: String,
        limit: Int = 50
    ): List<WarehouseItem> {
        if (keyword.isBlank()) return items.take(limit)
        
        return items.filter { item ->
            item.itemName.contains(keyword, ignoreCase = true) ||
            item.itemId.contains(keyword, ignoreCase = true) ||
            item.itemType.contains(keyword, ignoreCase = true)
        }.take(limit)
    }
    
    fun groupByType(items: List<WarehouseItem>): Map<String, List<WarehouseItem>> {
        return items.groupBy { it.itemType }
    }
    
    fun groupByRarity(items: List<WarehouseItem>): Map<Int, List<WarehouseItem>> {
        return items.groupBy { it.rarity }
    }
    
    private fun applySorting(
        items: List<WarehouseItem>,
        sortBy: ItemFilter.SortBy
    ): List<WarehouseItem> {
        return when (sortBy) {
            ItemFilter.SortBy.RARITY_DESC -> items.sortedByDescending { it.rarity }
            ItemFilter.SortBy.RARITY_ASC -> items.sortedBy { it.rarity }
            ItemFilter.SortBy.NAME_ASC -> items.sortedBy { it.itemName }
            ItemFilter.SortBy.NAME_DESC -> items.sortedByDescending { it.itemName }
            ItemFilter.SortBy.TYPE_ASC -> items.sortedBy { it.itemType }
            ItemFilter.SortBy.QUANTITY_DESC -> items.sortedByDescending { it.quantity }
            ItemFilter.SortBy.QUANTITY_ASC -> items.sortedBy { it.quantity }
        }
    }
    
    fun calculateStats(items: List<WarehouseItem>): WarehouseStats {
        val itemsByType = items.groupingBy { it.itemType }.eachCount()
        val itemsByRarity = items.groupingBy { it.rarity }.eachCount()
        val totalQuantity = items.sumOf { it.quantity }
        
        return WarehouseStats(
            totalItems = items.size,
            totalQuantity = totalQuantity,
            itemsByType = itemsByType,
            itemsByRarity = itemsByRarity,
            uniqueItemCount = items.map { it.itemId }.distinct().size,
            lastUpdated = System.currentTimeMillis()
        )
    }
}
