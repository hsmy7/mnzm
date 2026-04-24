package com.xianxia.sect.core.util

import com.xianxia.sect.core.model.StorageBagItem

object StorageBagUtils {

    private const val COOLING_PERIOD_MONTHS = 3

    fun isInCoolingPeriod(item: StorageBagItem, currentYear: Int, currentMonth: Int): Boolean {
        val forgetYear = item.forgetYear ?: return false
        val forgetMonth = item.forgetMonth ?: return false
        val forgetTotalMonths = forgetYear * 12 + forgetMonth
        val currentTotalMonths = currentYear * 12 + currentMonth
        return currentTotalMonths - forgetTotalMonths < COOLING_PERIOD_MONTHS
    }
    
    fun decreaseItemQuantity(
        items: List<StorageBagItem>,
        itemId: String,
        amount: Int = 1
    ): List<StorageBagItem> {
        val mutableItems = items.toMutableList()
        val index = mutableItems.indexOfFirst { it.itemId == itemId }
        
        if (index < 0) return items
        
        val item = mutableItems[index]
        val newQuantity = item.quantity - amount
        
        if (newQuantity > 0) {
            mutableItems[index] = item.copy(quantity = newQuantity)
        } else {
            mutableItems.removeAt(index)
        }
        
        return mutableItems.toList()
    }
    
    fun increaseItemQuantity(
        items: List<StorageBagItem>,
        item: StorageBagItem
    ): List<StorageBagItem> {
        val mutableItems = items.toMutableList()
        val existingIndex = mutableItems.indexOfFirst { 
            it.itemId == item.itemId && it.itemType == item.itemType 
        }
        
        if (existingIndex >= 0) {
            val existing = mutableItems[existingIndex]
            mutableItems[existingIndex] = existing.copy(
                quantity = existing.quantity + item.quantity
            )
        } else {
            mutableItems.add(item)
        }
        
        return mutableItems.toList()
    }
    
    fun decreaseMultipleItems(
        items: List<StorageBagItem>,
        itemIds: List<String>
    ): List<StorageBagItem> {
        var result = items
        itemIds.forEach { itemId ->
            result = decreaseItemQuantity(result, itemId)
        }
        return result
    }
    
    fun hasEnoughItems(
        items: List<StorageBagItem>,
        itemId: String,
        requiredQuantity: Int = 1
    ): Boolean {
        val item = items.find { it.itemId == itemId }
        return item != null && item.quantity >= requiredQuantity
    }
    
    fun getItemQuantity(items: List<StorageBagItem>, itemId: String): Int {
        return items.find { it.itemId == itemId }?.quantity ?: 0
    }
}

fun List<StorageBagItem>.decreaseItem(itemId: String, amount: Int = 1): List<StorageBagItem> =
    StorageBagUtils.decreaseItemQuantity(this, itemId, amount)

fun List<StorageBagItem>.increaseItem(item: StorageBagItem): List<StorageBagItem> =
    StorageBagUtils.increaseItemQuantity(this, item)

fun List<StorageBagItem>.hasItem(itemId: String, amount: Int = 1): Boolean =
    StorageBagUtils.hasEnoughItems(this, itemId, amount)

fun List<StorageBagItem>.getItemQty(itemId: String): Int =
    StorageBagUtils.getItemQuantity(this, itemId)
