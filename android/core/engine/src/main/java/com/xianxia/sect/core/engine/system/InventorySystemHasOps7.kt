package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.MerchantItem
import kotlin.random.Random
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed

// ── InventorySystem 拆分域 7/7（行为零变更） ──
fun InventorySystem.hasEnoughSpiritStones(currentStones: Long, required: Long): Boolean {
    return currentStones >= required
}

// ── Spirit Stone operations (delegated to SpiritStoneWallet) ──────────

fun InventorySystem.createEquipmentFromMerchantItem(item: MerchantItem, rng: Random): EquipmentInstance =
    InventoryFactories.createEquipmentFromMerchantItem(item, rng)

fun InventorySystem.createManualFromMerchantItem(item: MerchantItem): ManualStack =
    InventoryFactories.createManualFromMerchantItem(item)

fun InventorySystem.createPillFromMerchantItem(item: MerchantItem): Pill =
    InventoryFactories.createPillFromMerchantItem(item)

fun InventorySystem.createMaterialFromMerchantItem(item: MerchantItem): Material =
    InventoryFactories.createMaterialFromMerchantItem(item)

fun InventorySystem.createHerbFromMerchantItem(item: MerchantItem): Herb =
    InventoryFactories.createHerbFromMerchantItem(item)

fun InventorySystem.createSeedFromMerchantItem(item: MerchantItem): Seed =
    InventoryFactories.createSeedFromMerchantItem(item)
