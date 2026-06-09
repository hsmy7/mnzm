package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.engine.domain.inventory.InventoryFacade

fun GameEngine.addEquipmentStack(stack: EquipmentStack) = inventoryFacade.addEquipmentStack(stack)
fun GameEngine.removeEquipment(equipmentId: String): Boolean = inventoryFacade.removeEquipment(equipmentId)
fun GameEngine.addManualStackToWarehouse(stack: ManualStack) = inventoryFacade.addManualStackToWarehouse(stack)
fun GameEngine.addPillToWarehouse(pill: Pill) = inventoryFacade.addPillToWarehouse(pill)
fun GameEngine.addMaterialToWarehouse(material: Material) = inventoryFacade.addMaterialToWarehouse(material)
fun GameEngine.addHerbToWarehouse(herb: Herb) = inventoryFacade.addHerbToWarehouse(herb)
fun GameEngine.addSeedToWarehouse(seed: Seed) = inventoryFacade.addSeedToWarehouse(seed)
fun GameEngine.sortWarehouse() = inventoryFacade.sortWarehouse()
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
suspend fun GameEngine.listItemsToMerchant(items: List<Pair<String, Int>>) = inventoryFacade.listItemsToMerchant(items)
suspend fun GameEngine.removePlayerListedItem(itemId: String) = inventoryFacade.removePlayerListedItem(itemId)
suspend fun GameEngine.openStorageBag(bagId: String): List<BattleRewardItem> = inventoryFacade.openStorageBag(bagId)

suspend fun GameEngine.bulkSellItems(operations: List<GameEngine.BulkSellOperation>): GameEngine.BulkSellResult {
    val facadeResult = inventoryFacade.bulkSellItems(operations.map { InventoryFacade.BulkSellOperation(it.id, it.name, it.quantity, it.itemType) })
    return GameEngine.BulkSellResult(facadeResult.soldCount, facadeResult.totalEarned, facadeResult.soldItemNames, facadeResult.failedItemNames)
}
