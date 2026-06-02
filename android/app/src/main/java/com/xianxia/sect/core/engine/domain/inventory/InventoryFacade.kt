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
}
