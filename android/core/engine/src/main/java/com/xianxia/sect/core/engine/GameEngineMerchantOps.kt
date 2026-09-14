package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.AutoBuyCatalogItem
import com.xianxia.sect.core.engine.domain.inventory.InventoryFacade


suspend fun GameEngine.sellEquipment(equipmentId: String,
    quantity: Int = 1) = inventoryFacade.sellEquipment(equipmentId, quantity)
suspend fun GameEngine.sellManual(manualId: String, quantity: Int) = inventoryFacade.sellManual(manualId, quantity)
suspend fun GameEngine.sellPill(pillId: String, quantity: Int) = inventoryFacade.sellPill(pillId, quantity)
suspend fun GameEngine.sellMaterial(materialId: String, quantity: Int) = inventoryFacade.sellMaterial(materialId,
    quantity)
suspend fun GameEngine.sellHerb(herbId: String, quantity: Int) = inventoryFacade.sellHerb(herbId, quantity)
suspend fun GameEngine.sellSeed(seedId: String, quantity: Int) = inventoryFacade.sellSeed(seedId, quantity)
suspend fun GameEngine.sellToMerchant(acquisitionItemId: String, quantity: Int) = 
    inventoryFacade.sellToMerchant(acquisitionItemId, quantity)

suspend fun GameEngine.buyMerchantItem(itemId: String, quantity: Int) = inventoryFacade.buyMerchantItem(itemId,
    quantity)
suspend fun GameEngine.listItemsToMerchant(items: List<Pair<String, Int>>) = inventoryFacade.listItemsToMerchant(items)
suspend fun GameEngine.removePlayerListedItem(itemId: String) = inventoryFacade.removePlayerListedItem(itemId)
suspend fun GameEngine.bulkSellItems(operations: List<GameEngine.BulkSellOperation>): GameEngine.BulkSellResult {
    val facadeResult = inventoryFacade.bulkSellItems(operations.map { InventoryFacade.BulkSellOperation(it.id, it.name,
        it.quantity, it.itemType) })
    return GameEngine.BulkSellResult(facadeResult.soldCount, facadeResult.totalEarned, facadeResult.soldItemNames,
        facadeResult.failedItemNames)
}

// ── 自动购买 ────────────────────────────────────────────────────────

/** 返回所有可被商人出售的物品目录，供自动购买选择界面使用 */
fun GameEngine.getAllAutoBuyableItems(): List<AutoBuyCatalogItem> =
    autoBuyService.getAllAutoBuyableItems()

/** 执行自动购买（引擎层调用，1月和12月） */
suspend fun GameEngine.executeAutoBuy(year: Int, month: Int) =
    autoBuyService.executeAutoBuy(year, month)
