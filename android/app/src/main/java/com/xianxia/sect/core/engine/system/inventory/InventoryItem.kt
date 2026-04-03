package com.xianxia.sect.core.engine.system.inventory

import com.xianxia.sect.core.model.GameItem

enum class ItemType {
    EQUIPMENT, MANUAL, PILL, MATERIAL, HERB, SEED;
    
    val displayName: String get() = when (this) {
        EQUIPMENT -> "装备"
        MANUAL -> "功法"
        PILL -> "丹药"
        MATERIAL -> "材料"
        HERB -> "灵药"
        SEED -> "种子"
    }
}

interface InventoryItem {
    val id: String
    val name: String
    val description: String
    val rarity: Int
    val basePrice: Int
    val isLocked: Boolean
    val isStackable: Boolean
    val quantity: Int
    val itemType: ItemType
    val mergeKey: String
    
    fun withQuantity(newQuantity: Int): InventoryItem
    
    companion object {
        const val MAX_STACK_SIZE = 999
    }
}

fun GameItem.toInventoryItem(): InventoryItem = when (this) {
    is com.xianxia.sect.core.model.Equipment -> InventoryItemAdapter.EquipmentAdapter(this)
    is com.xianxia.sect.core.model.Manual -> InventoryItemAdapter.ManualAdapter(this)
    is com.xianxia.sect.core.model.Pill -> InventoryItemAdapter.PillAdapter(this)
    is com.xianxia.sect.core.model.Material -> InventoryItemAdapter.MaterialAdapter(this)
    is com.xianxia.sect.core.model.Herb -> InventoryItemAdapter.HerbAdapter(this)
    is com.xianxia.sect.core.model.Seed -> InventoryItemAdapter.SeedAdapter(this)
}

sealed class InventoryItemAdapter {
    
    data class EquipmentAdapter(private val item: com.xianxia.sect.core.model.Equipment) : InventoryItem {
        override val id: String get() = item.id
        override val name: String get() = item.name
        override val description: String get() = item.description
        override val rarity: Int get() = item.rarity
        override val basePrice: Int get() = item.basePrice
        override val isLocked: Boolean get() = item.isLocked
        override val isStackable: Boolean get() = false
        override val quantity: Int get() = 1
        override val itemType: ItemType get() = ItemType.EQUIPMENT
        override val mergeKey: String get() = item.id
        
        override fun withQuantity(newQuantity: Int): InventoryItem = this
        
        fun unwrap(): com.xianxia.sect.core.model.Equipment = item
    }
    
    data class ManualAdapter(private val item: com.xianxia.sect.core.model.Manual) : InventoryItem {
        override val id: String get() = item.id
        override val name: String get() = item.name
        override val description: String get() = item.description
        override val rarity: Int get() = item.rarity
        override val basePrice: Int get() = item.basePrice
        override val isLocked: Boolean get() = item.isLocked
        override val isStackable: Boolean get() = true
        override val quantity: Int get() = item.quantity
        override val itemType: ItemType get() = ItemType.MANUAL
        override val mergeKey: String get() = "${item.name}_${item.rarity}_${item.type}"
        
        override fun withQuantity(newQuantity: Int): InventoryItem = 
            ManualAdapter(item.copy(quantity = newQuantity))
        
        fun unwrap(): com.xianxia.sect.core.model.Manual = item
    }
    
    data class PillAdapter(private val item: com.xianxia.sect.core.model.Pill) : InventoryItem {
        override val id: String get() = item.id
        override val name: String get() = item.name
        override val description: String get() = item.description
        override val rarity: Int get() = item.rarity
        override val basePrice: Int get() = item.basePrice
        override val isLocked: Boolean get() = item.isLocked
        override val isStackable: Boolean get() = true
        override val quantity: Int get() = item.quantity
        override val itemType: ItemType get() = ItemType.PILL
        override val mergeKey: String get() = "${item.name}_${item.rarity}_${item.category}"
        
        override fun withQuantity(newQuantity: Int): InventoryItem = 
            PillAdapter(item.copy(quantity = newQuantity))
        
        fun unwrap(): com.xianxia.sect.core.model.Pill = item
    }
    
    data class MaterialAdapter(private val item: com.xianxia.sect.core.model.Material) : InventoryItem {
        override val id: String get() = item.id
        override val name: String get() = item.name
        override val description: String get() = item.description
        override val rarity: Int get() = item.rarity
        override val basePrice: Int get() = item.basePrice
        override val isLocked: Boolean get() = item.isLocked
        override val isStackable: Boolean get() = true
        override val quantity: Int get() = item.quantity
        override val itemType: ItemType get() = ItemType.MATERIAL
        override val mergeKey: String get() = "${item.name}_${item.rarity}_${item.category}"
        
        override fun withQuantity(newQuantity: Int): InventoryItem = 
            MaterialAdapter(item.copy(quantity = newQuantity))
        
        fun unwrap(): com.xianxia.sect.core.model.Material = item
    }
    
    data class HerbAdapter(private val item: com.xianxia.sect.core.model.Herb) : InventoryItem {
        override val id: String get() = item.id
        override val name: String get() = item.name
        override val description: String get() = item.description
        override val rarity: Int get() = item.rarity
        override val basePrice: Int get() = item.basePrice
        override val isLocked: Boolean get() = item.isLocked
        override val isStackable: Boolean get() = true
        override val quantity: Int get() = item.quantity
        override val itemType: ItemType get() = ItemType.HERB
        override val mergeKey: String get() = "${item.name}_${item.rarity}_${item.category}"
        
        override fun withQuantity(newQuantity: Int): InventoryItem = 
            HerbAdapter(item.copy(quantity = newQuantity))
        
        fun unwrap(): com.xianxia.sect.core.model.Herb = item
    }
    
    data class SeedAdapter(private val item: com.xianxia.sect.core.model.Seed) : InventoryItem {
        override val id: String get() = item.id
        override val name: String get() = item.name
        override val description: String get() = item.description
        override val rarity: Int get() = item.rarity
        override val basePrice: Int get() = item.basePrice
        override val isLocked: Boolean get() = item.isLocked
        override val isStackable: Boolean get() = true
        override val quantity: Int get() = item.quantity
        override val itemType: ItemType get() = ItemType.SEED
        override val mergeKey: String get() = "${item.name}_${item.rarity}"
        
        override fun withQuantity(newQuantity: Int): InventoryItem = 
            SeedAdapter(item.copy(quantity = newQuantity))
        
        fun unwrap(): com.xianxia.sect.core.model.Seed = item
    }
}
