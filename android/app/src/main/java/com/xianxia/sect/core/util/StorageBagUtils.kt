package com.xianxia.sect.core.util

import com.xianxia.sect.core.model.StorageBagItem

object StorageBagUtils {

    private const val COOLING_PERIOD_DAYS = 90

    fun isInCoolingPeriod(item: StorageBagItem, currentYear: Int, currentMonth: Int, currentDay: Int): Boolean {
        val forgetYear = item.forgetYear ?: return false
        val forgetMonth = item.forgetMonth ?: return false
        val forgetDay = item.forgetDay ?: return false
        val forgetTotalDays = forgetYear * 360 + (forgetMonth - 1) * 30 + forgetDay
        val currentTotalDays = currentYear * 360 + (currentMonth - 1) * 30 + currentDay
        return currentTotalDays - forgetTotalDays < COOLING_PERIOD_DAYS
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
        item: StorageBagItem,
        maxStack: Int = Int.MAX_VALUE
    ): List<StorageBagItem> {
        val mutableItems = items.toMutableList()
        val existingIndex = mutableItems.indexOfFirst { 
            it.itemId == item.itemId && it.itemType == item.itemType 
        }
        
        if (existingIndex >= 0) {
            val existing = mutableItems[existingIndex]
            mutableItems[existingIndex] = existing.copy(
                quantity = (existing.quantity + item.quantity).coerceAtMost(maxStack)
            )
        } else {
            mutableItems.add(item.copy(quantity = item.quantity.coerceAtMost(maxStack)))
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

fun List<StorageBagItem>.increaseItem(item: StorageBagItem, maxStack: Int = Int.MAX_VALUE): List<StorageBagItem> =
    StorageBagUtils.increaseItemQuantity(this, item, maxStack)

fun List<StorageBagItem>.hasItem(itemId: String, amount: Int = 1): Boolean =
    StorageBagUtils.hasEnoughItems(this, itemId, amount)

fun List<StorageBagItem>.getItemQty(itemId: String): Int =
    StorageBagUtils.getItemQuantity(this, itemId)
