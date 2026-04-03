package com.xianxia.sect.core.extensions

import com.xianxia.sect.core.engine.system.inventory.AddResult
import com.xianxia.sect.core.engine.system.inventory.ItemType
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.service.UnifiedItemService

suspend fun UnifiedItemService.addEquipment(item: Equipment): AddResult = 
    add(ItemType.EQUIPMENT, item, false)

suspend fun UnifiedItemService.addManual(item: Manual, merge: Boolean = true): AddResult = 
    add(ItemType.MANUAL, item, merge)

suspend fun UnifiedItemService.addPill(item: Pill, merge: Boolean = true): AddResult = 
    add(ItemType.PILL, item, merge)

suspend fun UnifiedItemService.addMaterial(item: Material, merge: Boolean = true): AddResult = 
    add(ItemType.MATERIAL, item, merge)

suspend fun UnifiedItemService.addHerb(item: Herb, merge: Boolean = true): AddResult = 
    add(ItemType.HERB, item, merge)

suspend fun UnifiedItemService.addSeed(item: Seed, merge: Boolean = true): AddResult = 
    add(ItemType.SEED, item, merge)

suspend fun UnifiedItemService.removeEquipment(id: String): Boolean = 
    remove<Equipment>(ItemType.EQUIPMENT, id)

suspend fun UnifiedItemService.removeManual(id: String, quantity: Int = 1): Boolean = 
    remove<Manual>(ItemType.MANUAL, id, quantity)

suspend fun UnifiedItemService.removePill(id: String, quantity: Int = 1): Boolean = 
    remove<Pill>(ItemType.PILL, id, quantity)

suspend fun UnifiedItemService.removeMaterial(id: String, quantity: Int = 1): Boolean = 
    remove<Material>(ItemType.MATERIAL, id, quantity)

suspend fun UnifiedItemService.removeHerb(id: String, quantity: Int = 1): Boolean = 
    remove<Herb>(ItemType.HERB, id, quantity)

suspend fun UnifiedItemService.removeSeed(id: String, quantity: Int = 1): Boolean = 
    remove<Seed>(ItemType.SEED, id, quantity)

suspend fun UnifiedItemService.getEquipment(id: String): Equipment? = 
    getById(ItemType.EQUIPMENT, id)

suspend fun UnifiedItemService.getManual(id: String): Manual? = 
    getById(ItemType.MANUAL, id)

suspend fun UnifiedItemService.getPill(id: String): Pill? = 
    getById(ItemType.PILL, id)

suspend fun UnifiedItemService.getMaterial(id: String): Material? = 
    getById(ItemType.MATERIAL, id)

suspend fun UnifiedItemService.getHerb(id: String): Herb? = 
    getById(ItemType.HERB, id)

suspend fun UnifiedItemService.getSeed(id: String): Seed? = 
    getById(ItemType.SEED, id)

suspend fun UnifiedItemService.hasEquipment(id: String): Boolean = 
    hasItem<Equipment>(ItemType.EQUIPMENT, id)

suspend fun UnifiedItemService.hasManual(id: String): Boolean = 
    hasItem<Manual>(ItemType.MANUAL, id)

suspend fun UnifiedItemService.hasPill(id: String): Boolean = 
    hasItem<Pill>(ItemType.PILL, id)

suspend fun UnifiedItemService.hasMaterial(id: String): Boolean = 
    hasItem<Material>(ItemType.MATERIAL, id)

suspend fun UnifiedItemService.hasHerb(id: String): Boolean = 
    hasItem<Herb>(ItemType.HERB, id)

suspend fun UnifiedItemService.hasSeed(id: String): Boolean = 
    hasItem<Seed>(ItemType.SEED, id)

suspend fun UnifiedItemService.getEquipmentQuantity(id: String): Int = 
    getQuantity<Equipment>(ItemType.EQUIPMENT, id)

suspend fun UnifiedItemService.getManualQuantity(id: String): Int = 
    getQuantity<Manual>(ItemType.MANUAL, id)

suspend fun UnifiedItemService.getPillQuantity(id: String): Int = 
    getQuantity<Pill>(ItemType.PILL, id)

suspend fun UnifiedItemService.getMaterialQuantity(id: String): Int = 
    getQuantity<Material>(ItemType.MATERIAL, id)

suspend fun UnifiedItemService.getHerbQuantity(id: String): Int = 
    getQuantity<Herb>(ItemType.HERB, id)

suspend fun UnifiedItemService.getSeedQuantity(id: String): Int = 
    getQuantity<Seed>(ItemType.SEED, id)

fun GameItem.itemType(): ItemType = when (this) {
    is Equipment -> ItemType.EQUIPMENT
    is Manual -> ItemType.MANUAL
    is Pill -> ItemType.PILL
    is Material -> ItemType.MATERIAL
    is Herb -> ItemType.HERB
    is Seed -> ItemType.SEED
}
