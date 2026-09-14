package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.state.StackKeys
import com.xianxia.sect.core.state.StackableItemStore
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.StorageBag
import com.xianxia.sect.core.util.AppError

// ── InventorySystem 拆分域 6/7（行为零变更） ──

private val STORAGE_BAG_SLOT_BUDGET = InventorySystem.STORAGE_BAG_SLOT_BUDGET
fun InventorySystem.hasHerb(name: String, rarity: Int, quantity: Int = 1): Boolean {
    val item = currentHerbs().find { it.name == name && it.rarity == rarity } ?: return false
    return item.quantity >= quantity
}

fun InventorySystem.addSeed(item: Seed, merge: Boolean = true): DomainResult<Seed> {
    val validation = validateStackableItem(item.name, item.rarity, item.quantity)
    if (validation is DomainResult.Failure) return validation

    return stateStore.updateAndReturn {
        val otherTypes = equipmentStacks.size + manualStacks.size + pills.size + materials.size + herbs.size
        val store = StackableItemStore(
            initialItems = seeds.all(),
            stackKeyOf = StackKeys::seed,
            maxStack = getMaxStackForType("seed"),
            maxSlots = { computeMaxSlots() - otherTypes },
            notFound = { AppError.Domain.Inventory.NotFound(it) }
        )
        val result = store.add(item, merge = merge)
        seeds.replaceAll(store.all())
        handleOverflowResult(result, "seed", item)
        result
    }
}

/**
 * 添加储物袋。
 *
 * 走 [StackableItemStore] 统一合并：同稀有度的储物袋自动合并为单个堆叠。
 * 储物袋不占仓库槽位预算（[computeSlotCount] 本就不含 storageBags）。
 *
 * @param item 待添加的储物袋
 * @return [DomainResult.Success] 全部成功 / [DomainResult.Partial] 部分成功 / [DomainResult.Failure] 失败
 */

fun InventorySystem.addStorageBag(item: StorageBag): DomainResult<StorageBag> {
    if (item.quantity <= 0) {
        return DomainResult.Failure(AppError.Domain.Inventory.InvalidQuantity(item.quantity))
    }
    return stateStore.updateAndReturn {
        val store = StackableItemStore(
            initialItems = storageBags.all(),
            stackKeyOf = StackKeys::storageBag,
            maxStack = getMaxStackForType("storageBag"),
            maxSlots = { STORAGE_BAG_SLOT_BUDGET },
            notFound = { AppError.Domain.Inventory.NotFound(it) }
        )
        val result = store.add(item)
        storageBags.replaceAll(store.all())
        handleOverflowResult(result, "storageBag", item)
        result
    }
}

fun InventorySystem.removeSeed(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
    if (quantity <= 0) return false
    return stateStore.updateAndReturn {
        val existing = seeds.find { it.id == id } ?: return@updateAndReturn false
        if (!bypassLock && existing.isLocked) {
            logWarning("Cannot remove locked seed: ${existing.name}")
            return@updateAndReturn false
        }
        if (existing.quantity < quantity) {
            logWarning("Cannot remove $quantity seed, only ${existing.quantity} available")
            return@updateAndReturn false
        }
        var removed = false
        seeds = seeds.mapNotNull { seed ->
            if (seed.id == id && !removed) {
                val newQty = seed.quantity - quantity
                when {
                    newQty < 0 -> seed
                    newQty == 0 -> { removed = true; null }
                    else -> { removed = true; seed.copy(quantity = newQty) }
                }
            } else seed
        }
        true
    }
}

fun InventorySystem.addSeedSync(item: Seed, merge: Boolean = true): DomainResult<Seed> {
    val validation = validateStackableItem(item.name, item.rarity, item.quantity)
    if (validation is DomainResult.Failure) return validation

    return stateStore.updateAndReturn {
        val otherTypes = equipmentStacks.size + manualStacks.size + pills.size + materials.size + herbs.size
        val store = StackableItemStore(
            initialItems = seeds.all(),
            stackKeyOf = StackKeys::seed,
            maxStack = getMaxStackForType("seed"),
            maxSlots = { computeMaxSlots() - otherTypes },
            notFound = { AppError.Domain.Inventory.NotFound(it) }
        )
        val result = store.add(item, merge = merge)
        seeds.replaceAll(store.all())
        handleOverflowResult(result, "seed", item)
        result
    }
}

fun InventorySystem.removeSeedSync(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
    if (quantity <= 0) return false
    return stateStore.updateAndReturn {
        val existing = seeds.find { it.id == id } ?: return@updateAndReturn false
        if (!bypassLock && existing.isLocked) {
            logWarning("Cannot remove locked seed: ${existing.name}")
            return@updateAndReturn false
        }
        if (existing.quantity < quantity) {
            logWarning("Cannot remove $quantity items, only ${existing.quantity} available")
            return@updateAndReturn false
        }
        var removed = false
        seeds = seeds.mapNotNull { seed ->
            if (seed.id == id && !removed) {
                val newQty = seed.quantity - quantity
                when {
                    newQty < 0 -> seed
                    newQty == 0 -> { removed = true; null }
                    else -> { removed = true; seed.copy(quantity = newQty) }
                }
            } else seed
        }
        removed
    }
}

fun InventorySystem.removeSeedByName(
    name: String, rarity: Int, quantity: Int = 1, bypassLock: Boolean = false
): Boolean {
    if (!validateQuantity(quantity, "remove quantity")) return false
    val existing = currentSeeds().find { it.name == name && it.rarity == rarity }
        ?: return false
    if (!bypassLock && existing.isLocked) {
        logWarning("Cannot remove locked seed: ${existing.name}")
        return false
    }
    if (existing.quantity < quantity) {
        logWarning("Cannot remove $quantity items '$name', only ${existing.quantity} available")
        return false
    }

    return stateStore.updateAndReturn {
        var removed = false
        seeds = seeds.mapNotNull { seed ->
            if (seed.id == existing.id && !removed) {
                val newQty = seed.quantity - quantity
                when {
                    newQty < 0 -> {
                        logWarning("Cannot remove $quantity items, only ${seed.quantity} available")
                        seed
                    }
                    newQty == 0 -> {
                        removed = true
                        null
                    }
                    else -> {
                        removed = true
                        seed.copy(quantity = newQty)
                    }
                }
            } else seed
        }
        removed
    }
}

fun InventorySystem.updateSeed(id: String, transform: (Seed) -> Seed): Boolean {
    return stateStore.updateAndReturn {
        var found = false
        seeds = seeds.map {
            if (it.id == id) {
                found = true
                transform(it)
            } else it
        }
        found
    }
}

fun InventorySystem.getSeedById(id: String): Seed? = getById(currentSeeds(), id)

fun InventorySystem.getSeedQuantity(id: String): Int = getQuantity(currentSeeds(), id)

fun InventorySystem.hasSeed(name: String, rarity: Int, quantity: Int = 1): Boolean {
    val item = currentSeeds().find { it.name == name && it.rarity == rarity } ?: return false
    return item.quantity >= quantity
}

fun InventorySystem.getItemCountByType(type: String): Int {
    return when (type.lowercase(java.util.Locale.getDefault())) {
        "equipment_stack" -> currentEquipmentStacks().size
        "equipment_instance" -> currentEquipmentInstances().size
        "manual_stack" -> currentManualStacks().size
        "manual_instance" -> currentManualInstances().size
        "equipment" -> currentEquipmentStacks().size + currentEquipmentInstances().size
        "manual" -> currentManualStacks().size + currentManualInstances().size
        "pill" -> currentPills().size
        "material" -> currentMaterials().size
        "herb" -> currentHerbs().size
        "seed" -> currentSeeds().size
        else -> 0
    }
}

/**
 * 在 MutableGameState 事务内合并分散堆叠。
 * 由 [consolidateStacks] 和 [sortWarehouse] 共用。
 *
 * 单遍合并保证终止（见 consolidate 内注释）；锁定堆叠**允许作为合并目标**
 * （吸收数量、自身 ID 与 isLocked 不变），**禁止作为合并来源**
 * （锁定堆叠绝不被删除/减少，否则锁定标记与 ID 丢失）。
 * 与 [StackableItemStore.add]（本就合并进锁定堆叠）行为一致。
 */

fun InventorySystem.consolidateStacks() {
    stateStore.update { consolidateAllStacks(this) }
}

fun InventorySystem.sortWarehouse() {
    stateStore.update {
        consolidateAllStacks(this) // 先合并后排序，同一事务内
        equipmentStacks.replaceAll(equipmentStacks.items.sortedWith(compareByDescending<EquipmentStack> { it
            .rarity }.thenBy { it.name }))
        equipmentInstances.replaceAll(equipmentInstances.items
            .sortedWith(compareByDescending<EquipmentInstance> { it.rarity }.thenBy { it.name }))
        manualStacks.replaceAll(manualStacks.items.sortedWith(compareByDescending<ManualStack> { it.rarity }
            .thenBy { it.name }))
        manualInstances.replaceAll(manualInstances.items.sortedWith(compareByDescending<ManualInstance> { it
            .rarity }.thenBy { it.name }))
        pills.replaceAll(pills.items.sortedWith(compareByDescending<Pill> { it.rarity }.thenBy { it.name }))
        materials.replaceAll(materials.all().sortedWith(compareByDescending<Material> { it.rarity }.thenBy { it
            .name }))
        herbs.replaceAll(herbs.all().sortedWith(compareByDescending<Herb> { it.rarity }.thenBy { it.name }))
        seeds.replaceAll(seeds.all().sortedWith(compareByDescending<Seed> { it.rarity }.thenBy { it.name }))
    }
}
