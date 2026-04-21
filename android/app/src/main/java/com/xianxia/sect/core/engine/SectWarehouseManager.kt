package com.xianxia.sect.core.engine

import com.xianxia.sect.core.config.InventoryConfig
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
import com.xianxia.sect.core.warehouse.ItemFilter
import com.xianxia.sect.core.warehouse.OptimizedWarehouseManager
import com.xianxia.sect.core.warehouse.WarehousePage
import com.xianxia.sect.core.warehouse.WarehouseStats
import com.xianxia.sect.core.warehouse.WarehouseDiff
import com.xianxia.sect.core.warehouse.PerformanceMetrics
import kotlin.random.Random

object SectWarehouseManager {
    
    const val MIN_YEARLY_ITEMS = 30
    const val MAX_YEARLY_ITEMS = 50
    const val MAX_RARITY = 4
    
    private var useOptimized = true
    var inventoryConfig: InventoryConfig = InventoryConfig.DEFAULT
    
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
        
        return SectWarehouse(
            items = items,
            spiritStones = spiritStones
        )
    }
    
    private fun generateWarehousePill(rarity: Int): WarehouseItem? {
        var currentRarity = rarity
        while (currentRarity >= 1) {
            val recipes = PillRecipeDatabase.getRecipesByTier(currentRarity)
            if (recipes.isNotEmpty()) {
                val recipe = recipes.random()
                return WarehouseItem(
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
                return WarehouseItem(
                    itemId = template.id,
                    itemName = template.name,
                    itemType = "equipment_stack",
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
                return WarehouseItem(
                    itemId = template.id,
                    itemName = template.name,
                    itemType = "manual_stack",
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
        
        return WarehouseItem(
            itemId = "material_${type.name}_${rarity}",
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
        
        return WarehouseItem(
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
        
        return WarehouseItem(
            itemId = "seed_${rarity}_${Random.nextInt(1000)}",
            itemName = seedNames.random(),
            itemType = "seed",
            rarity = rarity,
            quantity = Random.nextInt(1, 6)
        )
    }
    
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
                items.add(WarehouseItem(
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
                items.add(WarehouseItem(
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
                items.add(WarehouseItem(
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
                items.add(WarehouseItem(
                    itemId = pill.id,
                    itemName = pill.name,
                    itemType = "pill",
                    rarity = pill.rarity,
                    quantity = quantity
                ))
                return@forEach
            }
            
            items.add(WarehouseItem(
                itemId = "loot_$name",
                itemName = name,
                itemType = "material",
                rarity = 1,
                quantity = quantity
            ))
        }
        
        return items
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
