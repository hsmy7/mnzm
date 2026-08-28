package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.AutoBuyCatalogItem
import com.xianxia.sect.core.model.BattleRewardItem
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.ForgeRecipe
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.RewardCardItem
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.engine.domain.inventory.InventoryFacade
import com.xianxia.sect.core.nativebridge.ActionIds
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.bool
import kotlinx.serialization.json.put



// ── 库存 add/remove 家族 native 转发（计划 v2 批 8-2，C-06 转发收尾）──
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
suspend fun GameEngine.sortWarehouse() = inventoryFacade.sortWarehouse()
suspend fun GameEngine.consolidateStacks() {
    // 测试场景中 inventoryFacade 可能为 null
    @Suppress("UNNECESSARY_SAFE_CALL")
    inventoryFacade?.consolidateStacks()
}
suspend fun GameEngine.confiscateStorageBagItem(discipleId: String, item: StorageBagItem) = inventoryFacade.confiscateStorageBagItem(discipleId, item)
fun GameEngine.createEquipmentStackFromRecipe(recipe: com.xianxia.sect.core.registry.ForgeRecipeDatabase.ForgeRecipe): EquipmentStack = inventoryFacade.createEquipmentStackFromRecipe(recipe)
fun GameEngine.createEquipmentStackFromMerchantItem(item: MerchantItem): EquipmentStack = inventoryFacade.createEquipmentStackFromMerchantItem(item)
fun GameEngine.createManualStackFromMerchantItem(item: MerchantItem): ManualStack = inventoryFacade.createManualStackFromMerchantItem(item)
fun GameEngine.createPillFromMerchantItem(item: MerchantItem): Pill = inventoryFacade.createPillFromMerchantItem(item)
fun GameEngine.createMaterialFromMerchantItem(item: MerchantItem): Material = inventoryFacade.createMaterialFromMerchantItem(item)
fun GameEngine.createHerbFromMerchantItem(item: MerchantItem): Herb = inventoryFacade.createHerbFromMerchantItem(item)
fun GameEngine.createSeedFromMerchantItem(item: MerchantItem): Seed = inventoryFacade.createSeedFromMerchantItem(item)
suspend fun GameEngine.sellEquipment(equipmentId: String, quantity: Int = 1) = inventoryFacade.sellEquipment(equipmentId, quantity)
suspend fun GameEngine.sellManual(manualId: String, quantity: Int) = inventoryFacade.sellManual(manualId, quantity)
suspend fun GameEngine.sellPill(pillId: String, quantity: Int) = inventoryFacade.sellPill(pillId, quantity)
suspend fun GameEngine.sellMaterial(materialId: String, quantity: Int) = inventoryFacade.sellMaterial(materialId, quantity)
suspend fun GameEngine.sellHerb(herbId: String, quantity: Int) = inventoryFacade.sellHerb(herbId, quantity)
suspend fun GameEngine.sellSeed(seedId: String, quantity: Int) = inventoryFacade.sellSeed(seedId, quantity)
suspend fun GameEngine.consumeMaterialByName(name: String, rarity: Int, quantity: Int) = inventoryFacade.consumeMaterialByName(name, rarity, quantity)
fun GameEngine.toggleItemLock(itemId: String, itemType: String) = inventoryFacade.toggleItemLock(itemId, itemType)
suspend fun GameEngine.sellToMerchant(acquisitionItemId: String, quantity: Int) = 
    inventoryFacade.sellToMerchant(acquisitionItemId, quantity)

suspend fun GameEngine.buyMerchantItem(itemId: String, quantity: Int) = inventoryFacade.buyMerchantItem(itemId, quantity)
fun GameEngine.refreshTravelingMerchantManual(): Boolean = cultivationService.refreshTravelingMerchantManual()

/** 观看广告后发放玉符（必须在引擎线程调用，调用方负责 launchOnEngine 派发）。
 *
 * 玉符绝对值覆盖写模型受守卫测试约束，发放必须收敛于 [JadeSymbolService.grantFromAd]，
 * 禁止在此直接 stateStore.update 写 jadeSymbols。
 */
fun GameEngine.grantJadeSymbolsFromAd(amount: Int): Boolean =
    jadeSymbolService.grantFromAd(amount)

suspend fun GameEngine.listItemsToMerchant(items: List<Pair<String, Int>>) = inventoryFacade.listItemsToMerchant(items)
suspend fun GameEngine.removePlayerListedItem(itemId: String) = inventoryFacade.removePlayerListedItem(itemId)
suspend fun GameEngine.openStorageBag(bagId: String): Pair<List<BattleRewardItem>, List<RewardCardItem>> = inventoryFacade.openStorageBag(bagId)

suspend fun GameEngine.bulkSellItems(operations: List<GameEngine.BulkSellOperation>): GameEngine.BulkSellResult {
    val facadeResult = inventoryFacade.bulkSellItems(operations.map { InventoryFacade.BulkSellOperation(it.id, it.name, it.quantity, it.itemType) })
    return GameEngine.BulkSellResult(facadeResult.soldCount, facadeResult.totalEarned, facadeResult.soldItemNames, facadeResult.failedItemNames)
}

// ── 自动购买 ────────────────────────────────────────────────────────

/** 返回所有可被商人出售的物品目录，供自动购买选择界面使用 */
fun GameEngine.getAllAutoBuyableItems(): List<AutoBuyCatalogItem> =
    autoBuyService.getAllAutoBuyableItems()

/** 执行自动购买（引擎层调用，1月和12月） */
suspend fun GameEngine.executeAutoBuy(year: Int, month: Int) =
    autoBuyService.executeAutoBuy(year, month)
