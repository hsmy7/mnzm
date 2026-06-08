package com.xianxia.sect.core.engine.domain.inventory

import com.xianxia.sect.core.model.*
import kotlinx.coroutines.flow.StateFlow

interface InventoryFacade {
    val equipmentStacks: StateFlow<List<EquipmentStack>>
    val equipmentInstances: StateFlow<List<EquipmentInstance>>
    val manualStacks: StateFlow<List<ManualStack>>
    val manualInstances: StateFlow<List<ManualInstance>>
    val pills: StateFlow<List<Pill>>
    val materials: StateFlow<List<Material>>
    val herbs: StateFlow<List<Herb>>
    val seeds: StateFlow<List<Seed>>
    val storageBags: StateFlow<List<StorageBag>>

    fun addEquipmentStack(stack: EquipmentStack)
    fun removeEquipment(equipmentId: String): Boolean
    fun addManualStackToWarehouse(stack: ManualStack)
    fun addPillToWarehouse(pill: Pill)
    fun addMaterialToWarehouse(material: Material)
    fun addHerbToWarehouse(herb: Herb)
    fun addSeedToWarehouse(seed: Seed)
    fun sortWarehouse()
    suspend fun confiscateStorageBagItem(discipleId: String, item: StorageBagItem)
    fun createEquipmentStackFromRecipe(recipe: com.xianxia.sect.core.registry.ForgeRecipeDatabase.ForgeRecipe): EquipmentStack
    fun createEquipmentStackFromMerchantItem(item: MerchantItem): EquipmentStack
    fun createManualStackFromMerchantItem(item: MerchantItem): ManualStack
    fun createPillFromMerchantItem(item: MerchantItem): Pill
    fun createMaterialFromMerchantItem(item: MerchantItem): Material
    fun createHerbFromMerchantItem(item: MerchantItem): Herb
    fun createSeedFromMerchantItem(item: MerchantItem): Seed

    // Sell operations
    suspend fun sellEquipment(equipmentId: String, quantity: Int = 1): Boolean
    suspend fun sellManual(manualId: String, quantity: Int): Boolean
    suspend fun sellPill(pillId: String, quantity: Int): Boolean
    suspend fun sellMaterial(materialId: String, quantity: Int): Boolean
    suspend fun sellHerb(herbId: String, quantity: Int): Boolean
    suspend fun sellSeed(seedId: String, quantity: Int): Boolean
    suspend fun consumeMaterialByName(name: String, rarity: Int, quantity: Int): Boolean

    // Bulk sell
    data class BulkSellOperation(val id: String, val name: String, val quantity: Int, val itemType: String)
    data class BulkSellResult(val soldCount: Int, val totalEarned: Long, val soldItemNames: List<String>, val failedItemNames: List<String>)
    suspend fun bulkSellItems(operations: List<BulkSellOperation>): BulkSellResult

    // Lock toggle
    fun toggleItemLock(itemId: String, itemType: String)

    // Merchant trading
    suspend fun buyMerchantItem(itemId: String, quantity: Int)
    suspend fun listItemsToMerchant(items: List<Pair<String, Int>>)
    suspend fun removePlayerListedItem(itemId: String)

    // Storage bag
    suspend fun openStorageBag(bagId: String): List<BattleRewardItem>
}
