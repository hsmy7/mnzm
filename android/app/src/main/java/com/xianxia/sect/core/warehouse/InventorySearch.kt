package com.xianxia.sect.core.warehouse

import com.xianxia.sect.core.engine.system.inventory.InventorySystemV2
import com.xianxia.sect.core.engine.system.inventory.InventoryItem
import com.xianxia.sect.core.engine.system.inventory.InventoryItemAdapter
import com.xianxia.sect.core.engine.system.inventory.ItemType
import javax.inject.Inject
import javax.inject.Singleton

data class SearchQuery(
    val keywords: List<String> = emptyList(),
    val itemTypes: Set<ItemType>? = null,
    val minRarity: Int? = null,
    val maxRarity: Int? = null,
    val minQuantity: Int? = null,
    val maxQuantity: Int? = null,
    val minPrice: Long? = null,
    val maxPrice: Long? = null,
    val isLocked: Boolean? = null,
    val fuzzyMatch: Boolean = true,
    val sortBy: SearchSortBy = SearchSortBy.RELEVANCE,
    val sortOrder: SearchSortOrder = SearchSortOrder.DESC,
    val limit: Int = 100,
    val offset: Int = 0
)

enum class SearchSortBy {
    RELEVANCE, RARITY, NAME, QUANTITY, PRICE, TYPE
}

enum class SearchSortOrder {
    ASC, DESC
}

data class SearchResult(
    val items: List<InventoryItem>,
    val totalMatches: Int,
    val query: SearchQuery,
    val executionTimeMs: Long
)

@Singleton
class InventorySearchEngine @Inject constructor(
    private val inventory: InventorySystemV2
) {
    
    fun search(query: SearchQuery): SearchResult {
        val startTime = System.currentTimeMillis()
        
        var results = getAllItems()
        
        if (query.keywords.isNotEmpty()) {
            results = results.filter { item ->
                query.keywords.any { keyword ->
                    matchKeyword(item, keyword, query.fuzzyMatch)
                }
            }
        }
        
        query.itemTypes?.let { types ->
            results = results.filter { it.itemType in types }
        }
        
        query.minRarity?.let { min ->
            results = results.filter { it.rarity >= min }
        }
        query.maxRarity?.let { max ->
            results = results.filter { it.rarity <= max }
        }
        
        query.minQuantity?.let { min ->
            results = results.filter { it.quantity >= min }
        }
        query.maxQuantity?.let { max ->
            results = results.filter { it.quantity <= max }
        }
        
        query.minPrice?.let { min ->
            results = results.filter { it.basePrice >= min }
        }
        query.maxPrice?.let { max ->
            results = results.filter { it.basePrice <= max }
        }
        
        query.isLocked?.let { locked ->
            results = results.filter { it.isLocked == locked }
        }
        
        val totalMatches = results.size
        
        results = when (query.sortBy) {
            SearchSortBy.RELEVANCE -> results.sortedByDescending { 
                calculateRelevance(it, query.keywords, query.fuzzyMatch) 
            }
            SearchSortBy.RARITY -> results.sortedBy { it.rarity }
            SearchSortBy.NAME -> results.sortedBy { it.name }
            SearchSortBy.QUANTITY -> results.sortedBy { it.quantity }
            SearchSortBy.PRICE -> results.sortedBy { it.basePrice }
            SearchSortBy.TYPE -> results.sortedBy { it.itemType.ordinal }
        }
        
        if (query.sortOrder == SearchSortOrder.DESC && query.sortBy != SearchSortBy.RELEVANCE) {
            results = results.reversed()
        }
        
        results = results.drop(query.offset).take(query.limit)
        
        val executionTime = System.currentTimeMillis() - startTime
        
        return SearchResult(
            items = results,
            totalMatches = totalMatches,
            query = query,
            executionTimeMs = executionTime
        )
    }
    
    fun searchByKeyword(keyword: String, limit: Int = 100): SearchResult {
        return search(SearchQuery(
            keywords = listOf(keyword),
            limit = limit
        ))
    }
    
    fun searchByType(itemType: ItemType, limit: Int = 100): SearchResult {
        return search(SearchQuery(
            itemTypes = setOf(itemType),
            limit = limit
        ))
    }
    
    fun searchByRarity(minRarity: Int, maxRarity: Int, limit: Int = 100): SearchResult {
        return search(SearchQuery(
            minRarity = minRarity,
            maxRarity = maxRarity,
            limit = limit
        ))
    }
    
    private fun getAllItems(): List<InventoryItem> {
        val items = mutableListOf<InventoryItem>()
        
        inventory.getEquipmentList().forEach { 
            items.add(InventoryItemAdapter.EquipmentAdapter(it)) 
        }
        inventory.getManualList().forEach { 
            items.add(InventoryItemAdapter.ManualAdapter(it)) 
        }
        inventory.getPillList().forEach { 
            items.add(InventoryItemAdapter.PillAdapter(it)) 
        }
        inventory.getMaterialList().forEach { 
            items.add(InventoryItemAdapter.MaterialAdapter(it)) 
        }
        inventory.getHerbList().forEach { 
            items.add(InventoryItemAdapter.HerbAdapter(it)) 
        }
        inventory.getSeedList().forEach { 
            items.add(InventoryItemAdapter.SeedAdapter(it)) 
        }
        
        return items
    }
    
    private fun matchKeyword(item: InventoryItem, keyword: String, fuzzy: Boolean): Boolean {
        val lowerKeyword = keyword.lowercase()
        val lowerName = item.name.lowercase()
        val lowerDesc = item.description.lowercase()
        
        return if (fuzzy) {
            lowerName.contains(lowerKeyword) || 
            lowerDesc.contains(lowerKeyword) ||
            calculateLevenshteinDistance(lowerName, lowerKeyword) <= keyword.length / 3
        } else {
            lowerName.contains(lowerKeyword) || lowerDesc.contains(lowerKeyword)
        }
    }
    
    private fun calculateRelevance(
        item: InventoryItem, 
        keywords: List<String>, 
        fuzzy: Boolean
    ): Int {
        var score = 0
        keywords.forEach { keyword ->
            val lowerKeyword = keyword.lowercase()
            val lowerName = item.name.lowercase()
            
            if (lowerName == lowerKeyword) score += 100
            else if (lowerName.startsWith(lowerKeyword)) score += 80
            else if (lowerName.contains(lowerKeyword)) score += 60
            else if (fuzzy && calculateLevenshteinDistance(lowerName, lowerKeyword) <= keyword.length / 3) {
                score += 40
            }
        }
        return score
    }
    
    private fun calculateLevenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        
        return dp[s1.length][s2.length]
    }
}
