package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.state.StackKeys
import com.xianxia.sect.core.state.StackableItemStore
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.PillGrade
import com.xianxia.sect.core.util.AppError

// ── InventorySystem 拆分域 5/7（行为零变更） ──
fun InventorySystem.hasPill(name: String, rarity: Int, quantity: Int = 1, grade: PillGrade? = null): Boolean {
    val item = currentPills().find {
        it.name == name && it.rarity == rarity && (grade == null || it.grade == grade)
    } ?: return false
    return item.quantity >= quantity
}

fun InventorySystem.addMaterial(item: Material, merge: Boolean = true): DomainResult<Material> {
    val validation = validateStackableItem(item.name, item.rarity, item.quantity)
    if (validation is DomainResult.Failure) return validation

    return stateStore.updateAndReturn {
        val otherTypes = equipmentStacks.size + manualStacks.size + pills.size + herbs.size + seeds.size
        val store = StackableItemStore(
            initialItems = materials.all(),
            stackKeyOf = StackKeys::material,
            maxStack = getMaxStackForType("material"),
            maxSlots = { computeMaxSlots() - otherTypes },
            notFound = { AppError.Domain.Inventory.NotFound(it) }
        )
        val result = store.add(item, merge = merge)
        materials.replaceAll(store.all())
        handleOverflowResult(result, "material", item)
        result
    }
}

fun InventorySystem.removeMaterial(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
    if (quantity <= 0) return false
    return stateStore.updateAndReturn {
        val existing = materials.find { it.id == id } ?: return@updateAndReturn false
        if (!bypassLock && existing.isLocked) {
            logWarning("Cannot remove locked material: ${existing.hashCode()}")
            return@updateAndReturn false
        }
        if (existing.quantity < quantity) {
            logWarning("Cannot remove $quantity material, only ${existing.quantity} available")
            return@updateAndReturn false
        }
        var removed = false
        materials = materials.mapNotNull { item ->
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

fun InventorySystem.removeMaterialByName(
    name: String, rarity: Int, quantity: Int = 1, bypassLock: Boolean = false
): Boolean {
    if (!validateQuantity(quantity, "remove quantity")) return false
    val existing = currentMaterials().find { it.name == name && it.rarity == rarity }
        ?: return false
    if (!bypassLock && existing.isLocked) {
        logWarning("Cannot remove locked material: ${existing.name}")
        return false
    }
    if (existing.quantity < quantity) {
        logWarning("Cannot remove $quantity items '$name', only ${existing.quantity} available")
        return false
    }

    return stateStore.updateAndReturn {
        var removed = false
        materials = materials.mapNotNull { material ->
            if (material.id == existing.id && !removed) {
                val newQty = material.quantity - quantity
                when {
                    newQty < 0 -> {
                        logWarning("Cannot remove $quantity items, only ${material.quantity} available")
                        material
                    }
                    newQty == 0 -> {
                        removed = true
                        null
                    }
                    else -> {
                        removed = true
                        material.copy(quantity = newQty)
                    }
                }
            } else material
        }
        removed
    }
}

fun InventorySystem.updateMaterial(id: String, transform: (Material) -> Material): Boolean {
    return stateStore.updateAndReturn {
        var found = false
        materials = materials.map {
            if (it.id == id) {
                found = true
                transform(it)
            } else it
        }
        found
    }
}

fun InventorySystem.getMaterialById(id: String): Material? = getById(currentMaterials(), id)

fun InventorySystem.getMaterialQuantity(id: String): Int = getQuantity(currentMaterials(), id)

fun InventorySystem.hasMaterial(name: String, rarity: Int, quantity: Int = 1): Boolean {
    val item = currentMaterials().find { it.name == name && it.rarity == rarity } ?: return false
    return item.quantity >= quantity
}

fun InventorySystem.addHerb(item: Herb, merge: Boolean = true): DomainResult<Herb> {
    val validation = validateStackableItem(item.name, item.rarity, item.quantity)
    if (validation is DomainResult.Failure) return validation

    return stateStore.updateAndReturn {
        val otherTypes = equipmentStacks.size + manualStacks.size + pills.size + materials.size + seeds.size
        val store = StackableItemStore(
            initialItems = herbs.all(),
            stackKeyOf = StackKeys::herb,
            maxStack = getMaxStackForType("herb"),
            maxSlots = { computeMaxSlots() - otherTypes },
            notFound = { AppError.Domain.Inventory.NotFound(it) }
        )
        val result = store.add(item, merge = merge)
        herbs.replaceAll(store.all())
        when (result) {
            is DomainResult.Success -> {
                val srcKey = trackingSource
                gameData = gameData.copy(
                    annualHerbBySource = gameData.annualHerbBySource + (srcKey to (gameData
                        .annualHerbBySource[srcKey] ?: 0) + item.quantity)
                )
            }
            is DomainResult.Partial -> {
                val actualAdded = item.quantity - result.overflow
                val srcKey = trackingSource
                gameData = gameData.copy(
                    annualHerbBySource = gameData.annualHerbBySource + (srcKey to (gameData
                        .annualHerbBySource[srcKey] ?: 0) + actualAdded)
                )
            }
            is DomainResult.Failure -> { }
        }
        handleOverflowResult(result, "herb", item)
        result
    }
}

fun InventorySystem.removeHerb(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
    if (quantity <= 0) return false
    return stateStore.updateAndReturn {
        val existing = herbs.find { it.id == id } ?: return@updateAndReturn false
        if (!bypassLock && existing.isLocked) {
            logWarning("Cannot remove locked herb: ${existing.hashCode()}")
            return@updateAndReturn false
        }
        if (existing.quantity < quantity) {
            logWarning("Cannot remove $quantity herb, only ${existing.quantity} available")
            return@updateAndReturn false
        }
        var removed = false
        herbs = herbs.mapNotNull { item ->
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

fun InventorySystem.removeHerbByName(
    name: String, rarity: Int, quantity: Int = 1, bypassLock: Boolean = false
): Boolean {
    if (!validateQuantity(quantity, "remove quantity")) return false
    val existing = currentHerbs().find { it.name == name && it.rarity == rarity }
        ?: return false
    if (!bypassLock && existing.isLocked) {
        logWarning("Cannot remove locked herb: ${existing.name}")
        return false
    }
    if (existing.quantity < quantity) {
        logWarning("Cannot remove $quantity items '$name', only ${existing.quantity} available")
        return false
    }

    return stateStore.updateAndReturn {
        var removed = false
        herbs = herbs.mapNotNull { herb ->
            if (herb.id == existing.id && !removed) {
                val newQty = herb.quantity - quantity
                when {
                    newQty < 0 -> {
                        logWarning("Cannot remove $quantity items, only ${herb.quantity} available")
                        herb
                    }
                    newQty == 0 -> {
                        removed = true
                        null
                    }
                    else -> {
                        removed = true
                        herb.copy(quantity = newQty)
                    }
                }
            } else herb
        }
        removed
    }
}

fun InventorySystem.updateHerb(id: String, transform: (Herb) -> Herb): Boolean {
    return stateStore.updateAndReturn {
        var found = false
        herbs = herbs.map {
            if (it.id == id) {
                found = true
                transform(it)
            } else it
        }
        found
    }
}

fun InventorySystem.getHerbById(id: String): Herb? = getById(currentHerbs(), id)

fun InventorySystem.getHerbQuantity(id: String): Int = getQuantity(currentHerbs(), id)
