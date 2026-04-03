package com.xianxia.sect.core.engine.system.inventory

import com.xianxia.sect.core.model.GameItem

data class ItemTypeInfo<T : GameItem>(
    val itemType: ItemType,
    val container: ItemContainer<InventoryItem>,
    val adapterFactory: (T) -> InventoryItem,
    val validator: (T) -> AddResult = { AddResult.SUCCESS }
)

object InventoryValidation {
    fun validateRarity(rarity: Int): Boolean = rarity in 1..6
    
    fun validateName(name: String): Boolean = name.isNotBlank()
    
    fun validateQuantity(quantity: Int, isStackable: Boolean): Boolean {
        return if (isStackable) quantity > 0 else true
    }
    
    fun validateId(id: String): Boolean = id.isNotBlank()
    
    fun <T : GameItem> validateItem(item: T, isStackable: Boolean): AddResult {
        if (!validateId(item.id)) return AddResult.INVALID_ID
        if (!validateName(item.name)) return AddResult.INVALID_NAME
        if (!validateRarity(item.rarity)) return AddResult.INVALID_RARITY
        return AddResult.SUCCESS
    }
}
