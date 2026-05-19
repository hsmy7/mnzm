package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.*

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
    gameDay: Int,
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
    gameDay: Int,
    maxStackSize: Int
): AddToBagResult {
    val mergeResult = mergeManualStackToWarehouse(excludeStackId, maxStackSize, instance)

    manualInstances = manualInstances.filter { it.id != instance.id }

    return AddToBagResult(storageItemId = mergeResult.storageItemId, updatedDisciple = disciple)
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
