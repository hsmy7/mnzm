package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.state.StackKeys
import com.xianxia.sect.core.state.StackableItemStore
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PillGrade
import com.xianxia.sect.core.util.AppError

// ── InventorySystem 拆分域 4/7（行为零变更） ──
fun InventorySystem.getEquipmentStackById(id: String): EquipmentStack? = getById(currentEquipmentStacks(), id)

fun InventorySystem.getEquipmentInstanceById(
    id: String): EquipmentInstance? = getById(currentEquipmentInstances(),
    id
)

fun InventorySystem.removeManual(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
    if (quantity <= 0) return false
    return stateStore.updateAndReturn {
        val existing = manualStacks.find { it.id == id } ?: return@updateAndReturn false
        if (!bypassLock && existing.isLocked) {
            logWarning("Cannot remove locked manual: ${existing.hashCode()}")
            return@updateAndReturn false
        }
        if (existing.quantity < quantity) {
            logWarning("Cannot remove $quantity manual, only ${existing.quantity} available")
            return@updateAndReturn false
        }
        var removed = false
        manualStacks = manualStacks.mapNotNull { item ->
            if (item.id == id && !removed) {
                val newQty = item.quantity - quantity
                when {
                    newQty < 0 -> item
                    newQty == 0 -> { removed = true; null }
                    else -> { removed = true; item.copy(quantity = newQty) }
                }
            } else item
        }
        true
    }
}

fun InventorySystem.removeManualInstance(id: String): Boolean {
    return stateStore.updateAndReturn {
        val oldSize = manualInstances.size
        manualInstances = manualInstances.filter { it.id != id }
        manualInstances.size < oldSize
    }
}

fun InventorySystem.updateManualStack(id: String, transform: (ManualStack) -> ManualStack): Boolean {
    return stateStore.updateAndReturn {
        var found = false
        manualStacks = manualStacks.map {
            if (it.id == id) {
                found = true
                transform(it)
            } else it
        }
        found
    }
}

fun InventorySystem.updateManualInstance(id: String, transform: (ManualInstance) -> ManualInstance): Boolean {
    return stateStore.updateAndReturn {
        var found = false
        manualInstances = manualInstances.map {
            if (it.id == id) {
                found = true
                transform(it)
            } else it
        }
        found
    }
}

fun InventorySystem.getManualStackById(id: String): ManualStack? = getById(currentManualStacks(), id)

fun InventorySystem.getManualInstanceById(id: String): ManualInstance? = getById(currentManualInstances(), id)

fun InventorySystem.addPill(item: Pill, merge: Boolean = true): DomainResult<Pill> {
    val validation = validateStackableItem(item.name, item.rarity, item.quantity)
    if (validation is DomainResult.Failure) return validation

    return stateStore.updateAndReturn {
        val otherTypes = equipmentStacks.size + manualStacks.size + materials.size + herbs.size + seeds.size
        val store = StackableItemStore(
            initialItems = pills.all(),
            stackKeyOf = StackKeys::pill,
            maxStack = getMaxStackForType("pill"),
            maxSlots = { computeMaxSlots() - otherTypes },
            notFound = { AppError.Domain.Inventory.NotFound(it) }
        )
        val result = store.add(item, merge = merge)
        pills.replaceAll(store.all())
        when (result) {
            is DomainResult.Success -> {
                val pillGrade = item.grade?.name ?: "LOW"
                val srcKey = "$trackingSource:$pillGrade"
                gameData = gameData.copy(
                    annualPillBySource = gameData.annualPillBySource + (srcKey to (gameData
                        .annualPillBySource[srcKey] ?: 0) + item.quantity)
                )
            }
            is DomainResult.Partial -> {
                val actualAdded = item.quantity - result.overflow
                val pillGrade = item.grade?.name ?: "LOW"
                val srcKey = "$trackingSource:$pillGrade"
                gameData = gameData.copy(
                    annualPillBySource = gameData.annualPillBySource + (srcKey to (gameData
                        .annualPillBySource[srcKey] ?: 0) + actualAdded)
                )
            }
            is DomainResult.Failure -> { }
        }
        handleOverflowResult(result, "pill", item)
        result
    }
}

fun InventorySystem.removePill(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
    if (quantity <= 0) return false
    return stateStore.updateAndReturn {
        val existing = pills.find { it.id == id } ?: return@updateAndReturn false
        if (!bypassLock && existing.isLocked) {
            logWarning("Cannot remove locked pill: ${existing.hashCode()}")
            return@updateAndReturn false
        }
        if (existing.quantity < quantity) {
            logWarning("Cannot remove $quantity pill, only ${existing.quantity} available")
            return@updateAndReturn false
        }
        var removed = false
        pills = pills.mapNotNull { item ->
            if (item.id == id && !removed) {
                val newQty = item.quantity - quantity
                when {
                    newQty < 0 -> item
                    newQty == 0 -> { removed = true; null }
                    else -> { removed = true; item.copy(quantity = newQty) }
                }
            } else item
        }
        true
    }
}

fun InventorySystem.removePillByName(name: String, rarity: Int, quantity: Int = 1, bypassLock: Boolean = false,
    grade: PillGrade? = null): Boolean {
    if (!validateQuantity(quantity, "remove quantity")) return false
    val existing = currentPills().find {
        it.name == name && it.rarity == rarity && (grade == null || it.grade == grade)
    }
        ?: return false
    if (!bypassLock && existing.isLocked) {
        logWarning("Cannot remove locked pill: ${existing.name}")
        return false
    }
    if (existing.quantity < quantity) {
        logWarning("Cannot remove $quantity items '$name', only ${existing.quantity} available")
        return false
    }

    return stateStore.updateAndReturn {
        var removed = false
        pills = pills.mapNotNull { pill ->
            if (pill.id == existing.id && !removed) {
                val newQty = pill.quantity - quantity
                when {
                    newQty < 0 -> {
                        logWarning("Cannot remove $quantity items, only ${pill.quantity} available")
                        pill
                    }
                    newQty == 0 -> {
                        removed = true
                        null
                    }
                    else -> {
                        removed = true
                        pill.copy(quantity = newQty)
                    }
                }
            } else pill
        }
        removed
    }
}

fun InventorySystem.updatePill(id: String, transform: (Pill) -> Pill): Boolean {
    return stateStore.updateAndReturn {
        var found = false
        pills = pills.map {
            if (it.id == id) {
                found = true
                transform(it)
            } else it
        }
        found
    }
}

fun InventorySystem.getPillById(id: String): Pill? = getById(currentPills(), id)

fun InventorySystem.getPillQuantity(id: String): Int = getQuantity(currentPills(), id)
