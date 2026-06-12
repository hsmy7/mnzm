package com.xianxia.sect.core.util

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.state.MutableGameState

object StorageBagUtils {

    private const val COOLING_PERIOD_PHASES = 9
    private const val COOLING_PERIOD_MONTHS = 3

    fun isInCoolingPeriod(item: StorageBagItem, currentYear: Int, currentMonth: Int, currentPhase: Int): Boolean {
        val forgetYear = item.forgetYear ?: return false
        val forgetMonth = item.forgetMonth ?: return false
        val forgetPhase = item.forgetPhase
        if (forgetPhase != null) {
            val forgetTotalPhases = forgetYear * 36 + (forgetMonth - 1) * 3 + forgetPhase
            val currentTotalPhases = currentYear * 36 + (currentMonth - 1) * 3 + currentPhase
            return currentTotalPhases - forgetTotalPhases < COOLING_PERIOD_PHASES
        } else {
            val forgetTotalMonths = forgetYear * 12 + forgetMonth
            val currentTotalMonths = currentYear * 12 + currentMonth
            return currentTotalMonths - forgetTotalMonths < COOLING_PERIOD_MONTHS
        }
    }

    fun decreaseItemQuantity(items: List<StorageBagItem>, itemId: String, amount: Int = 1): List<StorageBagItem> {
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

    fun increaseItemQuantity(items: List<StorageBagItem>, item: StorageBagItem, maxStack: Int = Int.MAX_VALUE): List<StorageBagItem> {
        val mutableItems = items.toMutableList()
        val existingIndex = mutableItems.indexOfFirst { it.itemId == item.itemId && it.itemType == item.itemType }
        if (existingIndex >= 0) {
            val existing = mutableItems[existingIndex]
            mutableItems[existingIndex] = existing.copy(quantity = (existing.quantity + item.quantity).coerceAtMost(maxStack))
        } else {
            mutableItems.add(item.copy(quantity = item.quantity.coerceAtMost(maxStack)))
        }
        return mutableItems.toList()
    }

    fun decreaseMultipleItems(items: List<StorageBagItem>, itemIds: List<String>): List<StorageBagItem> {
        var result = items
        itemIds.forEach { itemId -> result = decreaseItemQuantity(result, itemId) }
        return result
    }

    fun hasEnoughItems(items: List<StorageBagItem>, itemId: String, requiredQuantity: Int = 1): Boolean {
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

data class AddToBagResult(
    val storageItemId: String,
    val updatedDisciple: Disciple
)

private data class StackMergeResult(
    val storageItemId: String,
    val isMerge: Boolean
)

private fun MutableGameState.mergeEquipmentStackToWarehouse(
    excludeStackId: String?,
    maxStackSize: Int,
    instance: EquipmentInstance
): StackMergeResult {
    val existingStack = equipmentStacks.find {
        it.name == instance.name && it.rarity == instance.rarity && it.slot == instance.slot &&
            it.id != excludeStackId && it.quantity < maxStackSize
    }
    return if (existingStack != null) {
        equipmentStacks = equipmentStacks.map { s ->
            if (s.id == existingStack.id) s.copy(quantity = existingStack.quantity + 1) else s
        }
        StackMergeResult(storageItemId = existingStack.id, isMerge = true)
    } else {
        val newStack = instance.toStack(quantity = 1)
        equipmentStacks = equipmentStacks + newStack
        StackMergeResult(storageItemId = newStack.id, isMerge = false)
    }
}

private fun MutableGameState.mergeManualStackToWarehouse(
    excludeStackId: String?,
    maxStackSize: Int,
    instance: ManualInstance
): StackMergeResult {
    val existingStack = manualStacks.find {
        it.name == instance.name && it.rarity == instance.rarity && it.type == instance.type &&
            it.id != excludeStackId && it.quantity < maxStackSize
    }
    return if (existingStack != null) {
        manualStacks = manualStacks.map {
            if (it.id == existingStack.id) it.copy(quantity = existingStack.quantity + 1) else it
        }
        StackMergeResult(storageItemId = existingStack.id, isMerge = true)
    } else {
        val newStack = instance.toStack(quantity = 1)
        manualStacks = manualStacks + newStack
        StackMergeResult(storageItemId = newStack.id, isMerge = false)
    }
}

fun MutableGameState.addEquipmentInstanceToDiscipleBag(
    disciple: Disciple,
    instance: EquipmentInstance,
    bagStackIds: Set<String>,
    excludeStackId: String? = null,
    gameYear: Int,
    gameMonth: Int,
    gamePhase: Int,
    maxStackSize: Int
): AddToBagResult {
    val mergeResult = mergeEquipmentStackToWarehouse(excludeStackId, maxStackSize, instance)
    equipmentInstances = equipmentInstances.filter { it.id != instance.id }
    return AddToBagResult(storageItemId = mergeResult.storageItemId, updatedDisciple = disciple)
}

fun MutableGameState.addManualInstanceToDiscipleBag(
    disciple: Disciple,
    instance: ManualInstance,
    bagStackIds: Set<String>,
    excludeStackId: String? = null,
    gameYear: Int,
    gameMonth: Int,
    gamePhase: Int,
    maxStackSize: Int
): AddToBagResult {
    val mergeResult = mergeManualStackToWarehouse(excludeStackId, maxStackSize, instance)
    manualInstances = manualInstances.filter { it.id != instance.id }
    return AddToBagResult(storageItemId = mergeResult.storageItemId, updatedDisciple = disciple)
}

fun Disciple.equipmentBagStackIds(): Set<String> =
    equipment.storageBagItems.filter { it.itemType == "equipment_stack" }.map { it.itemId }.toSet()

fun Disciple.manualBagStackIds(): Set<String> =
    equipment.storageBagItems.filter { it.itemType == "manual_stack" }.map { it.itemId }.toSet()
