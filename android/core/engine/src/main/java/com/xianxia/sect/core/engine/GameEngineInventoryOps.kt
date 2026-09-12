package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.nativebridge.ActionIds
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.bool
import kotlinx.serialization.json.put
import com.xianxia.sect.core.engine.service.refreshTravelingMerchantManual





// ── 库存 add/remove 家族 native 转发 ──
// AUTHORITATIVE 模式经 C++ handleInventory 执行（InventorySystem 全语义对拍），
// 溢出邮件由 InventoryNativeForward 走 Kotlin 同一解析/投递通道补齐；
// flag 关闭 / native 不可用 / 顶层失败 → 回退 Kotlin 原实现（双实现并行契约）。

suspend fun GameEngine.addEquipmentStack(stack: EquipmentStack) {
    val data = InventoryNativeForward.tryForward(this, ActionIds.INV_ADD_EQUIPMENT_STACK) {
        put("id", stack.id)
        put("name", stack.name)
        put("rarity", stack.rarity)
        put("slot", stack.slot.name)
        put("quantity", stack.quantity)
    } ?: return inventoryFacade.addEquipmentStack(stack)
}

suspend fun GameEngine.removeEquipment(equipmentId: String): Boolean {
    val data = InventoryNativeForward.tryForward(this, ActionIds.INV_REMOVE_EQUIPMENT) {
        put("id", equipmentId)
    } ?: return inventoryFacade.removeEquipment(equipmentId)
    return data.bool("removed") ?: false
}

suspend fun GameEngine.addManualStackToWarehouse(stack: ManualStack) {
    val data = InventoryNativeForward.tryForward(this, ActionIds.INV_ADD_MANUAL_STACK) {
        put("id", stack.id)
        put("name", stack.name)
        put("rarity", stack.rarity)
        put("type", stack.type.name)
        put("quantity", stack.quantity)
    } ?: return inventoryFacade.addManualStackToWarehouse(stack)
}

suspend fun GameEngine.addPillToWarehouse(pill: Pill) {
    val data = InventoryNativeForward.tryForward(this, ActionIds.INV_ADD_PILL) {
        put("id", pill.id)
        put("name", pill.name)
        put("rarity", pill.rarity)
        put("category", pill.category.name)
        put("grade", pill.grade.name)
        put("quantity", pill.quantity)
    } ?: return inventoryFacade.addPillToWarehouse(pill)
}

suspend fun GameEngine.addMaterialToWarehouse(material: Material) {
    val data = InventoryNativeForward.tryForward(this, ActionIds.INV_ADD_MATERIAL) {
        put("id", material.id)
        put("name", material.name)
        put("rarity", material.rarity)
        put("category", material.category.name)
        put("quantity", material.quantity)
    } ?: return inventoryFacade.addMaterialToWarehouse(material)
}

suspend fun GameEngine.addHerbToWarehouse(herb: Herb) {
    val data = InventoryNativeForward.tryForward(this, ActionIds.INV_ADD_HERB) {
        put("id", herb.id)
        put("name", herb.name)
        put("rarity", herb.rarity)
        put("category", herb.category)
        put("quantity", herb.quantity)
    } ?: return inventoryFacade.addHerbToWarehouse(herb)
}

suspend fun GameEngine.addSeedToWarehouse(seed: Seed) {
    val data = InventoryNativeForward.tryForward(this, ActionIds.INV_ADD_SEED) {
        put("id", seed.id)
        put("name", seed.name)
        put("rarity", seed.rarity)
        put("growTime", seed.growTime)
        put("yield", seed.yield)
        put("quantity", seed.quantity)
    } ?: return inventoryFacade.addSeedToWarehouse(seed)
}
suspend fun GameEngine.sortWarehouse() {
    // 线程契约：库存转发家族中 sortWarehouse/consolidateStacks 在函数内
    // 自持引擎上下文——AUTHORITATIVE 下 tryForward → nativeExecute 命中 C++ 侧
    // jniRequireEngineThread 线程契约守卫（非引擎线程调用会破坏 g_gameCore
    // 无锁单线程模型）。与其他全部引擎操作一致：suspend 入口自带引擎上下文，
    // 调用方（boot/UI 任意线程）无需（也无法从非引擎线程安全地）负责派发。
    val engine = this
    engineContextDispatcher.withEngineContext {
        InventoryNativeForward.tryForward(engine, ActionIds.INV_SORT) { }
            ?: // 测试场景中 inventoryFacade 可能为 null
            inventoryFacade?.sortWarehouse()
    }
}
suspend fun GameEngine.consolidateStacks() {
    val engine = this
    engineContextDispatcher.withEngineContext {
        InventoryNativeForward.tryForward(engine, ActionIds.INV_CONSOLIDATE) { }
            ?: run {
                // 测试场景中 inventoryFacade 可能为 null
                @Suppress("UNNECESSARY_SAFE_CALL")
                inventoryFacade?.consolidateStacks()
            }
    }
}
suspend fun GameEngine.confiscateStorageBagItem(discipleId: String,
    item: StorageBagItem) = inventoryFacade.confiscateStorageBagItem(discipleId, item)
suspend fun GameEngine.consumeMaterialByName(name: String, rarity: Int,
    quantity: Int) = inventoryFacade.consumeMaterialByName(name, rarity, quantity)
fun GameEngine.toggleItemLock(itemId: String, itemType: String) {
    InventoryNativeForward.tryForward(this, ActionIds.INV_TOGGLE_LOCK) {
        put("itemId", itemId)
        put("itemType", itemType)
    } ?: return inventoryFacade.toggleItemLock(itemId, itemType)
}
fun GameEngine.refreshTravelingMerchantManual(): Boolean = cultivationService.refreshTravelingMerchantManual()

/** 观看广告后发放玉符（必须在引擎线程调用，调用方负责 launchOnEngine 派发）。
 *
 * 玉符绝对值覆盖写模型受守卫测试约束，发放必须收敛于 [JadeSymbolService.grantFromAd]，
 * 禁止在此直接 stateStore.update 写 jadeSymbols。
 */
fun GameEngine.grantJadeSymbolsFromAd(amount: Int): Boolean =
    jadeSymbolService.grantFromAd(amount)
