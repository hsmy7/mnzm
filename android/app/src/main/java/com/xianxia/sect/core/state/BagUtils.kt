package com.xianxia.sect.core.state

import android.util.Log
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.util.StorageBagUtils

private const val TAG = "BagUtils"

data class AddToBagResult(
    val storageItemId: String,
    val updatedDisciple: Disciple
)

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
    val existingStack = equipmentStacks.find {
        it.name == instance.name && it.rarity == instance.rarity && it.slot == instance.slot &&
            it.id in bagStackIds && it.id != excludeStackId && it.quantity < maxStackSize
    }

    val storageItemId: String
    if (existingStack != null) {
        val newQty = existingStack.quantity + 1
        equipmentStacks = equipmentStacks.map { s ->
            if (s.id == existingStack.id) s.copy(quantity = newQty) else s
        }
        storageItemId = existingStack.id
    } else {
        val newStack = instance.toStack(quantity = 1)
        equipmentStacks = equipmentStacks + newStack
        storageItemId = newStack.id
    }
    equipmentInstances = equipmentInstances.filter { it.id != instance.id }

    val storageItem = StorageBagItem(
        itemId = storageItemId,
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
    val updatedDisciple = disciple.copyWith(
        storageBagItems = StorageBagUtils.increaseItemQuantity(
            disciple.equipment.storageBagItems, storageItem, maxStackSize
        ).map { bagItem ->
            if (bagItem.itemId == storageItemId && bagItem.itemType == "equipment_stack") {
                bagItem.copy(forgetYear = gameYear, forgetMonth = gameMonth, forgetDay = gameDay)
            } else bagItem
        }
    )

    return AddToBagResult(storageItemId = storageItemId, updatedDisciple = updatedDisciple)
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
    val existingStack = manualStacks.find {
        it.name == instance.name && it.rarity == instance.rarity && it.type == instance.type &&
            it.id in bagStackIds && it.id != excludeStackId && it.quantity < maxStackSize
    }

    val storageItemId: String
    if (existingStack != null) {
        val newQty = existingStack.quantity + 1
        manualStacks = manualStacks.map {
            if (it.id == existingStack.id) it.copy(quantity = newQty) else it
        }
        storageItemId = existingStack.id
    } else {
        val newStack = instance.toStack(quantity = 1)
        manualStacks = manualStacks + newStack
        storageItemId = newStack.id
    }
    manualInstances = manualInstances.filter { it.id != instance.id }

    val storageItem = StorageBagItem(
        itemId = storageItemId,
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
    val updatedDisciple = disciple.copyWith(
        storageBagItems = StorageBagUtils.increaseItemQuantity(
            disciple.equipment.storageBagItems, storageItem, maxStackSize
        ).map { bagItem ->
            if (bagItem.itemId == storageItemId && bagItem.itemType == "manual_stack") {
                bagItem.copy(forgetYear = gameYear, forgetMonth = gameMonth, forgetDay = gameDay)
            } else bagItem
        }
    )

    return AddToBagResult(storageItemId = storageItemId, updatedDisciple = updatedDisciple)
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
