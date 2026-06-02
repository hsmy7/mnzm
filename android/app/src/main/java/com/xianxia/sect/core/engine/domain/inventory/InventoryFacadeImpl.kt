package com.xianxia.sect.core.engine.domain.inventory

import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.GameStateStore
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InventoryFacadeImpl @Inject constructor(
    private val inventorySystem: InventorySystem,
    private val stateStore: GameStateStore
) : InventoryFacade {
    override val equipmentStacks: StateFlow<List<EquipmentStack>> get() = inventorySystem.equipmentStacks
    override val equipmentInstances: StateFlow<List<EquipmentInstance>> get() = inventorySystem.equipmentInstances
    override val manualStacks: StateFlow<List<ManualStack>> get() = inventorySystem.manualStacks
    override val manualInstances: StateFlow<List<ManualInstance>> get() = inventorySystem.manualInstances
    override val pills: StateFlow<List<Pill>> get() = inventorySystem.pills
    override val materials: StateFlow<List<Material>> get() = inventorySystem.materials
    override val herbs: StateFlow<List<Herb>> get() = inventorySystem.herbs
    override val seeds: StateFlow<List<Seed>> get() = inventorySystem.seeds
    override val storageBags: StateFlow<List<StorageBag>> get() = stateStore.storageBags

    override fun addEquipmentStack(stack: EquipmentStack) { inventorySystem.addEquipmentStack(stack) }
    override fun removeEquipment(equipmentId: String): Boolean = inventorySystem.removeEquipment(equipmentId)
    override fun addManualStackToWarehouse(stack: ManualStack) { inventorySystem.addManualStack(stack) }
    override fun addPillToWarehouse(pill: Pill) { inventorySystem.addPill(pill) }
    override fun addMaterialToWarehouse(material: Material) { inventorySystem.addMaterial(material) }
    override fun addHerbToWarehouse(herb: Herb) { inventorySystem.addHerb(herb) }
    override fun addSeedToWarehouse(seed: Seed) { inventorySystem.addSeed(seed) }
    override fun sortWarehouse() = inventorySystem.sortWarehouse()

    override suspend fun confiscateStorageBagItem(discipleId: String, item: StorageBagItem) {
        stateStore.update {
            val disciple = disciples.find { it.id == discipleId } ?: return@update
            var updatedDisciple = disciple

            val updatedItems = com.xianxia.sect.core.util.StorageBagUtils.decreaseItemQuantity(
                disciple.equipment.storageBagItems, item.itemId, 1
            )
            updatedDisciple = updatedDisciple.copy(
                equipment = updatedDisciple.equipment.copy(storageBagItems = updatedItems)
            )

            when (item.itemType.lowercase(java.util.Locale.getDefault())) {
                "equipment" -> {
                    val template = com.xianxia.sect.core.registry.EquipmentDatabase.getTemplateByName(item.name)
                    if (template != null) {
                        val stack = EquipmentStack(
                            name = template.name,
                            slot = template.slot,
                            rarity = template.rarity,
                            physicalAttack = template.physicalAttack,
                            magicAttack = template.magicAttack,
                            physicalDefense = template.physicalDefense,
                            magicDefense = template.magicDefense,
                            speed = template.speed,
                            hp = template.hp,
                            mp = template.mp,
                            description = template.description,
                            minRealm = com.xianxia.sect.core.GameConfig.Realm.getMinRealmForRarity(template.rarity)
                        )
                        equipmentStacks = equipmentStacks.toMutableList().apply {
                            val existing = find { it.name == stack.name && it.rarity == stack.rarity }
                            if (existing != null) {
                                val idx = indexOf(existing)
                                set(idx, existing.copy(quantity = existing.quantity + 1))
                            } else {
                                add(stack.copy(quantity = 1))
                            }
                        }
                    }
                }
                "manual" -> {
                    val template = com.xianxia.sect.core.registry.ManualDatabase.getByName(item.name)
                    if (template != null) {
                        manualStacks = manualStacks.toMutableList().apply {
                            val existing = find { it.name == template.name && it.rarity == template.rarity }
                            if (existing != null) {
                                val idx = indexOf(existing)
                                set(idx, existing.copy(quantity = existing.quantity + 1))
                            } else {
                                add(com.xianxia.sect.core.registry.ManualDatabase.createFromTemplate(template).copy(quantity = 1))
                            }
                        }
                    }
                }
                "pill" -> {
                    val template = com.xianxia.sect.core.registry.ItemDatabase.getPillById(item.itemId)
                        ?: com.xianxia.sect.core.registry.ItemDatabase.getPillByName(item.name)
                    if (template != null) {
                        val pill = com.xianxia.sect.core.registry.ItemDatabase.createPillFromTemplate(template, quantity = 1)
                        pills = pills.toMutableList().apply {
                            val existing = find { it.name == pill.name && it.rarity == pill.rarity && it.grade == pill.grade }
                            if (existing != null) {
                                val idx = indexOf(existing)
                                set(idx, existing.copy(quantity = existing.quantity + 1))
                            } else {
                                add(pill)
                            }
                        }
                    }
                }
                "herb" -> {
                    val herbTemplate = com.xianxia.sect.core.registry.HerbDatabase.getHerbByName(item.name)
                    herbs = herbs.toMutableList().apply {
                        val existing = find { it.name == item.name && it.rarity == item.rarity }
                        if (existing != null) {
                            val idx = indexOf(existing)
                            set(idx, existing.copy(quantity = existing.quantity + 1))
                        } else {
                            add(Herb(
                                name = item.name,
                                rarity = item.rarity,
                                description = herbTemplate?.description ?: "",
                                category = herbTemplate?.category ?: "",
                                quantity = 1
                            ))
                        }
                    }
                }
                "seed" -> {
                    seeds = seeds.toMutableList().apply {
                        val existing = find { it.name == item.name && it.rarity == item.rarity }
                        if (existing != null) {
                            val idx = indexOf(existing)
                            set(idx, existing.copy(quantity = existing.quantity + 1))
                        } else {
                            add(Seed(
                                name = item.name,
                                rarity = item.rarity,
                                description = "",
                                growTime = 0,
                                quantity = 1
                            ))
                        }
                    }
                }
                "material" -> {
                    val matTemplate = com.xianxia.sect.core.registry.BeastMaterialDatabase.getMaterialByName(item.name)
                    materials = materials.toMutableList().apply {
                        val existing = find { it.name == item.name && it.rarity == item.rarity }
                        if (existing != null) {
                            val idx = indexOf(existing)
                            set(idx, existing.copy(quantity = existing.quantity + 1))
                        } else {
                            add(Material(
                                name = item.name,
                                rarity = item.rarity,
                                description = matTemplate?.description ?: "",
                                quantity = 1
                            ))
                        }
                    }
                }
            }

            disciples = disciples.map { if (it.id == discipleId) updatedDisciple else it }
        }
    }

    override fun createEquipmentStackFromRecipe(recipe: com.xianxia.sect.core.registry.ForgeRecipeDatabase.ForgeRecipe): EquipmentStack =
        inventorySystem.createEquipmentFromRecipe(recipe)

    override fun createEquipmentStackFromMerchantItem(item: MerchantItem): EquipmentStack =
        inventorySystem.createEquipmentFromMerchantItem(item)

    override fun createManualStackFromMerchantItem(item: MerchantItem): ManualStack =
        inventorySystem.createManualFromMerchantItem(item)

    override fun createPillFromMerchantItem(item: MerchantItem): Pill =
        inventorySystem.createPillFromMerchantItem(item)

    override fun createMaterialFromMerchantItem(item: MerchantItem): Material =
        inventorySystem.createMaterialFromMerchantItem(item)

    override fun createHerbFromMerchantItem(item: MerchantItem): Herb =
        inventorySystem.createHerbFromMerchantItem(item)

    override fun createSeedFromMerchantItem(item: MerchantItem): Seed =
        inventorySystem.createSeedFromMerchantItem(item)
}
