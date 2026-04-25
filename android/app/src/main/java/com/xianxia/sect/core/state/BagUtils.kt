package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.util.StorageBagUtils

data class AddToBagResult(
    val storageItemId: String,
    val updatedDisciple: Disciple
)

private data class StackMergeResult(
    val storageItemId: String,
    val isMerge: Boolean
)

private fun MutableGameState.mergeEquipmentStack(
    bagStackIds: Set<String>,
    excludeStackId: String?,
    maxStackSize: Int,
    instance: EquipmentInstance
): StackMergeResult {
    val existingStack = equipmentStacks.find {
        it.name == instance.name && it.rarity == instance.rarity && it.slot == instance.slot &&
            it.id in bagStackIds && it.id != excludeStackId && it.quantity < maxStackSize
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

private fun MutableGameState.mergeManualStack(
    bagStackIds: Set<String>,
    excludeStackId: String?,
    maxStackSize: Int,
    instance: ManualInstance
): StackMergeResult {
    val existingStack = manualStacks.find {
        it.name == instance.name && it.rarity == instance.rarity && it.type == instance.type &&
            it.id in bagStackIds && it.id != excludeStackId && it.quantity < maxStackSize
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

private fun buildUpdatedBagItems(
    disciple: Disciple,
    storageItem: StorageBagItem,
    mergeResult: StackMergeResult,
    itemType: String,
    maxStackSize: Int,
    gameYear: Int,
    gameMonth: Int,
    gameDay: Int
): List<StorageBagItem> {
    val increased = StorageBagUtils.increaseItemQuantity(
        disciple.equipment.storageBagItems, storageItem, maxStackSize
    )
    return if (mergeResult.isMerge) {
        increased.map { bagItem ->
            if (bagItem.itemId == mergeResult.storageItemId && bagItem.itemType == itemType) {
                bagItem.copy(forgetYear = gameYear, forgetMonth = gameMonth, forgetDay = gameDay)
            } else bagItem
        }
    } else {
        increased
    }
}

fun MutableGameState.addEquipmentInstanceToDiscipleBag(
    disciple: Disciple,
    instance: EquipmentInstance,
    bagStackIds: Set<String>,
    excludeStackId: String? = null,
    gameYear: Int,
    gameMonth: Int,
    gameDay: Int,
    maxStackSize: Int
): AddToBagResult {
    val mergeResult = mergeEquipmentStack(bagStackIds, excludeStackId, maxStackSize, instance)

    equipmentInstances = equipmentInstances.filter { it.id != instance.id }

    val storageItem = StorageBagItem(
        itemId = mergeResult.storageItemId,
        itemType = "equipment_stack",
        name = instance.name,
        rarity = instance.rarity,
        quantity = 1,
        obtainedYear = gameYear,
        obtainedMonth = gameMonth,
        forgetYear = gameYear,
        forgetMonth = gameMonth,
        forgetDay = gameDay
    )

    val updatedBagItems = buildUpdatedBagItems(
        disciple, storageItem, mergeResult, "equipment_stack",
        maxStackSize, gameYear, gameMonth, gameDay
    )

    val updatedDisciple = disciple.copyWith(
        storageBagItems = updatedBagItems
    )

    return AddToBagResult(storageItemId = mergeResult.storageItemId, updatedDisciple = updatedDisciple)
}

fun MutableGameState.addManualInstanceToDiscipleBag(
    disciple: Disciple,
    instance: ManualInstance,
    bagStackIds: Set<String>,
    excludeStackId: String? = null,
    gameYear: Int,
    gameMonth: Int,
    gameDay: Int,
    maxStackSize: Int
): AddToBagResult {
    val mergeResult = mergeManualStack(bagStackIds, excludeStackId, maxStackSize, instance)

    manualInstances = manualInstances.filter { it.id != instance.id }

    val storageItem = StorageBagItem(
        itemId = mergeResult.storageItemId,
        itemType = "manual_stack",
        name = instance.name,
        rarity = instance.rarity,
        quantity = 1,
        obtainedYear = gameYear,
        obtainedMonth = gameMonth,
        forgetYear = gameYear,
        forgetMonth = gameMonth,
        forgetDay = gameDay
    )

    val updatedBagItems = buildUpdatedBagItems(
        disciple, storageItem, mergeResult, "manual_stack",
        maxStackSize, gameYear, gameMonth, gameDay
    )

    val updatedDisciple = disciple.copyWith(
        storageBagItems = updatedBagItems
    )

    return AddToBagResult(storageItemId = mergeResult.storageItemId, updatedDisciple = updatedDisciple)
}

fun Disciple.equipmentBagStackIds(): Set<String> =
    equipment.storageBagItems
        .filter { it.itemType == "equipment_stack" }
        .map { it.itemId }
        .toSet()

fun Disciple.manualBagStackIds(): Set<String> =
    equipment.storageBagItems
        .filter { it.itemType == "manual_stack" }
        .map { it.itemId }
        .toSet()
