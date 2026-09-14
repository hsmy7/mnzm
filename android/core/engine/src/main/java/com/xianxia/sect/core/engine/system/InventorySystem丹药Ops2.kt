package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MaterialCategory
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.model.PillGrade
import com.xianxia.sect.core.model.Seed

// ── InventorySystem 拆分域 2/7（行为零变更） ──
internal fun InventorySystem.currentPills(): List<Pill> = stateStore.pills.value

internal fun InventorySystem.currentMaterials(): List<Material> = stateStore.materials.value

internal fun InventorySystem.currentHerbs(): List<Herb> = stateStore.herbs.value

internal fun InventorySystem.currentSeeds(): List<Seed> = stateStore.seeds.value

fun InventorySystem.getCapacityInfo(): CapacityInfo = inventoryCapacityInfo(stateStore)

fun InventorySystem.canAddItem(): Boolean = inventoryCanAddItem(stateStore)

/** 在 MutableGameState 事务内检查是否有空余槽位（读到事务内最新状态）。 */

fun InventorySystem.canAddItemInTransaction(state: MutableGameState): Boolean =
    state.computeSlotCount() < state.computeMaxSlots()

fun InventorySystem.canAddItems(count: Int): Boolean = inventoryCanAddItems(stateStore, count)

fun InventorySystem.canAddEquipment(name: String, rarity: Int, slot: EquipmentSlot): Boolean {
    val current = stateStore.equipmentStacks.value
    val maxStack = getMaxStackForType("equipment_stack")
    val totalFree = current.filter { it.name == name && it.rarity == rarity && it.slot == slot }
        .sumOf { maxStack - it.quantity }
    return totalFree > 0 || canAddItem()
}

fun InventorySystem.canAddPill(
    name: String, rarity: Int, category: PillCategory, grade: PillGrade = PillGrade.MEDIUM
): Boolean {
    val current = stateStore.pills.value
    val maxStack = getMaxStackForType("pill")
    val totalFree = current.filter { it.name == name && it.rarity == rarity && it.category == category && it
        .grade == grade }
        .sumOf { maxStack - it.quantity }
    return totalFree > 0 || canAddItem()
}

fun InventorySystem.canAddManual(name: String, rarity: Int, type: ManualType): Boolean {
    val current = stateStore.manualStacks.value
    val maxStack = getMaxStackForType("manual_stack")
    val totalFree = current.filter { it.name == name && it.rarity == rarity && it.type == type }
        .sumOf { maxStack - it.quantity }
    return totalFree > 0 || canAddItem()
}

fun InventorySystem.canAddMaterial(name: String, rarity: Int, category: MaterialCategory): Boolean {
    val current = stateStore.materials.value
    val maxStack = getMaxStackForType("material")
    val totalFree = current.filter { it.name == name && it.rarity == rarity && it.category == category }
        .sumOf { maxStack - it.quantity }
    return totalFree > 0 || canAddItem()
}

fun InventorySystem.canAddHerb(name: String, rarity: Int, category: String): Boolean {
    val current = stateStore.herbs.value
    val maxStack = getMaxStackForType("herb")
    val totalFree = current.filter { it.name == name && it.rarity == rarity && it.category == category }
        .sumOf { maxStack - it.quantity }
    return totalFree > 0 || canAddItem()
}

fun InventorySystem.canAddSeed(name: String, rarity: Int, growTime: Int): Boolean {
    val current = stateStore.seeds.value
    val maxStack = getMaxStackForType("seed")
    val totalFree = current.filter { it.name == name && it.rarity == rarity && it.growTime == growTime }
        .sumOf { maxStack - it.quantity }
    return totalFree > 0 || canAddItem()
}
