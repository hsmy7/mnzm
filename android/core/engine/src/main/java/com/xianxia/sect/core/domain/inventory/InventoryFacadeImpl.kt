package com.xianxia.sect.core.engine.domain.inventory

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.system.MerchantItemConverter
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.mergeStackable
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InventoryFacadeImpl @Inject constructor(
    private val inventorySystem: InventorySystem,
    private val stateStore: GameStateStore,
    private val inventoryConfig: InventoryConfig,
    private val gameEngineCore: GameEngineCore
) : InventoryFacade {
    companion object {
        private const val TAG = "InventoryFacade"
    }

    override val equipmentStacks: StateFlow<List<EquipmentStack>> get() = inventorySystem.equipmentStacks
    override val equipmentInstances: StateFlow<List<EquipmentInstance>> get() = inventorySystem.equipmentInstances
    override val manualStacks: StateFlow<List<ManualStack>> get() = inventorySystem.manualStacks
    override val manualInstances: StateFlow<List<ManualInstance>> get() = inventorySystem.manualInstances
    override val pills: StateFlow<List<Pill>> get() = inventorySystem.pills
    override val materials: StateFlow<List<Material>> get() = inventorySystem.materials
    override val herbs: StateFlow<List<Herb>> get() = inventorySystem.herbs
    override val seeds: StateFlow<List<Seed>> get() = inventorySystem.seeds
    override val storageBags: StateFlow<List<StorageBag>> get() = stateStore.storageBags

    override suspend fun addEquipmentStack(stack: EquipmentStack) { inventorySystem.addEquipmentStack(stack) }
    override suspend fun removeEquipment(equipmentId: String): Boolean = inventorySystem.removeEquipment(equipmentId)
    override suspend fun addManualStackToWarehouse(stack: ManualStack) { inventorySystem.addManualStack(stack) }
    override suspend fun addPillToWarehouse(pill: Pill) { inventorySystem.addPill(pill) }
    override suspend fun addMaterialToWarehouse(material: Material) { inventorySystem.addMaterial(material) }
    override suspend fun addHerbToWarehouse(herb: Herb) { inventorySystem.addHerb(herb) }
    override suspend fun addSeedToWarehouse(seed: Seed) { inventorySystem.addSeed(seed) }
    override suspend fun sortWarehouse() = inventorySystem.sortWarehouse()

    override suspend fun confiscateStorageBagItem(discipleId: String, item: StorageBagItem) {
        stateStore.update {
            val id = discipleId.toIntOrNull() ?: return@update
            if (!discipleTables.ids.contains(id)) return@update
            val disciple = discipleTables.assemble(id)

            val updatedItems = com.xianxia.sect.core.util.StorageBagUtils.decreaseItemQuantity(
                disciple.equipment.storageBagItems, item.itemId, 1
            )
            val updatedDisciple = disciple.copy(
                equipment = disciple.equipment.copy(storageBagItems = updatedItems)
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
                        val existingEqStack = equipmentStacks.all().find { it.name == stack.name && it.rarity == stack.rarity && it.slot == stack.slot }
                        if (existingEqStack != null) {
                            equipmentStacks.update(existingEqStack.id) { it.copy(quantity = it.quantity + 1) }
                        } else {
                            equipmentStacks.add(stack.copy(quantity = 1))
                        }
                    }
                }
                "manual" -> {
                    val template = com.xianxia.sect.core.registry.ManualDatabase.getByName(item.name)
                    if (template != null) {
                        val existingManualStack = manualStacks.all().find { it.name == template.name && it.rarity == template.rarity && it.type == template.type }
                        if (existingManualStack != null) {
                            manualStacks.update(existingManualStack.id) { it.copy(quantity = it.quantity + 1) }
                        } else {
                            manualStacks.add(com.xianxia.sect.core.registry.ManualDatabase.createFromTemplate(template).copy(quantity = 1))
                        }
                    }
                }
                "pill" -> {
                    val template = com.xianxia.sect.core.registry.ItemDatabase.getPillById(item.itemId)
                        ?: com.xianxia.sect.core.registry.ItemDatabase.getPillByName(item.name)
                    if (template != null) {
                        val pill = com.xianxia.sect.core.registry.ItemDatabase.createPillFromTemplate(template, quantity = 1)
                        val existingPill = pills.all().find { it.name == pill.name && it.rarity == pill.rarity && it.category == pill.category && it.grade == pill.grade }
                        if (existingPill != null) {
                            pills.update(existingPill.id) { it.copy(quantity = it.quantity + 1) }
                        } else {
                            pills.add(pill)
                        }
                    }
                }
                "herb" -> {
                    val herbTemplate = com.xianxia.sect.core.registry.HerbDatabase.getHerbByName(item.name)
                    val herbCategory = herbTemplate?.category ?: ""
                    val existingHerb = herbs.all().find { it.name == item.name && it.rarity == item.rarity && it.category == herbCategory }
                    if (existingHerb != null) {
                        herbs.update(existingHerb.id) { it.copy(quantity = it.quantity + 1) }
                    } else {
                        herbs.add(Herb(
                            name = item.name,
                            rarity = item.rarity,
                            description = herbTemplate?.description ?: "",
                            category = herbTemplate?.category ?: "",
                            quantity = 1
                        ))
                    }
                }
                "seed" -> {
                    val seedTemplate = com.xianxia.sect.core.registry.HerbDatabase.getSeedByName(item.name)
                    val seedGrowTime = seedTemplate?.growTime ?: 0
                    val existingSeed = seeds.all().find { it.name == item.name && it.rarity == item.rarity && it.growTime == seedGrowTime }
                    if (existingSeed != null) {
                        seeds.update(existingSeed.id) { it.copy(quantity = it.quantity + 1) }
                    } else {
                        seeds.add(Seed(
                            name = item.name,
                            rarity = item.rarity,
                            description = seedTemplate?.description ?: "",
                            growTime = seedGrowTime,
                            quantity = 1
                        ))
                    }
                }
                "material" -> {
                    val matTemplate = com.xianxia.sect.core.registry.BeastMaterialDatabase.getMaterialByName(item.name)
                    val matCategory: com.xianxia.sect.core.model.MaterialCategory = try {
                        com.xianxia.sect.core.model.MaterialCategory.valueOf(matTemplate?.category ?: "BEAST_HIDE")
                    } catch (_: IllegalArgumentException) {
                        com.xianxia.sect.core.model.MaterialCategory.BEAST_HIDE
                    }
                    val existingMat = materials.all().find { it.name == item.name && it.rarity == item.rarity && it.category == matCategory }
                    if (existingMat != null) {
                        materials.update(existingMat.id) { it.copy(quantity = it.quantity + 1) }
                    } else {
                        materials.add(Material(
                            name = item.name,
                            rarity = item.rarity,
                            description = matTemplate?.description ?: "",
                            category = matCategory,
                            quantity = 1
                        ))
                    }
                }
            }

            discipleTables.update(updatedDisciple)
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

    // ── Sell operations ──────────────────────────────────────────────────

    override suspend fun sellEquipment(equipmentId: String, quantity: Int): Boolean {
        var success = false
        stateStore.update {
            val stack = equipmentStacks.get(equipmentId)
            if (stack != null && !stack.isLocked && quantity in 1..stack.quantity) {
                val newQty = stack.quantity - quantity
                if (newQty <= 0) {
                    equipmentStacks.remove(equipmentId)
                } else {
                    equipmentStacks.update(equipmentId) { it.copy(quantity = newQty) }
                }
                gameData = gameData.copy(spiritStones = gameData.spiritStones + GameConfig.Rarity.calculateSellPrice(stack.basePrice, quantity))
                success = true
            }
        }
        return success
    }

    override suspend fun sellManual(manualId: String, quantity: Int): Boolean {
        var success = false
        stateStore.update {
            val stack = manualStacks.get(manualId)
            if (stack != null && !stack.isLocked && quantity in 1..stack.quantity) {
                val newQty = stack.quantity - quantity
                if (newQty <= 0) {
                    manualStacks.remove(manualId)
                } else {
                    manualStacks.update(manualId) { it.copy(quantity = newQty) }
                }
                gameData = gameData.copy(spiritStones = gameData.spiritStones + GameConfig.Rarity.calculateSellPrice(stack.basePrice, quantity))
                success = true
            }
        }
        return success
    }

    override suspend fun sellPill(pillId: String, quantity: Int): Boolean {
        var success = false
        stateStore.update {
            val pill = pills.get(pillId)
            if (pill != null && !pill.isLocked && quantity in 1..pill.quantity) {
                val newQty = pill.quantity - quantity
                if (newQty <= 0) {
                    pills.remove(pillId)
                } else {
                    pills.update(pillId) { it.copy(quantity = newQty) }
                }
                gameData = gameData.copy(spiritStones = gameData.spiritStones + GameConfig.Rarity.calculateSellPrice(pill.basePrice, quantity))
                success = true
            }
        }
        return success
    }

    override suspend fun sellMaterial(materialId: String, quantity: Int): Boolean {
        var success = false
        stateStore.update {
            val material = materials.get(materialId)
            if (material != null && !material.isLocked && quantity in 1..material.quantity) {
                val newQty = material.quantity - quantity
                if (newQty <= 0) {
                    materials.remove(materialId)
                } else {
                    materials.update(materialId) { it.copy(quantity = newQty) }
                }
                gameData = gameData.copy(spiritStones = gameData.spiritStones + GameConfig.Rarity.calculateSellPrice(material.basePrice, quantity))
                success = true
            }
        }
        return success
    }

    override suspend fun sellHerb(herbId: String, quantity: Int): Boolean {
        var success = false
        stateStore.update {
            val herb = herbs.get(herbId)
            if (herb != null && !herb.isLocked && quantity in 1..herb.quantity) {
                val newQty = herb.quantity - quantity
                if (newQty <= 0) {
                    herbs.remove(herbId)
                } else {
                    herbs.update(herbId) { it.copy(quantity = newQty) }
                }
                gameData = gameData.copy(spiritStones = gameData.spiritStones + GameConfig.Rarity.calculateSellPrice(herb.basePrice, quantity))
                success = true
            }
        }
        return success
    }

    override suspend fun sellSeed(seedId: String, quantity: Int): Boolean {
        var success = false
        stateStore.update {
            val seed = seeds.get(seedId)
            if (seed != null && !seed.isLocked && quantity in 1..seed.quantity) {
                val newQty = seed.quantity - quantity
                if (newQty <= 0) {
                    seeds.remove(seedId)
                } else {
                    seeds.update(seedId) { it.copy(quantity = newQty) }
                }
                gameData = gameData.copy(spiritStones = gameData.spiritStones + GameConfig.Rarity.calculateSellPrice(seed.basePrice, quantity))
                success = true
            }
        }
        return success
    }

    override suspend fun consumeMaterialByName(name: String, rarity: Int, quantity: Int): Boolean {
        var remaining = quantity
        stateStore.update {
            val matching = materials.all().filter {
                it.name == name && it.rarity == rarity && !it.isLocked
            }
            for (mat in matching) {
                if (remaining <= 0) break
                val take = minOf(remaining, mat.quantity)
                val newQty = mat.quantity - take
                if (newQty <= 0) {
                    materials.remove(mat.id)
                } else {
                    materials.update(mat.id) { it.copy(quantity = newQty) }
                }
                remaining -= take
            }
        }
        return remaining == 0
    }

    // ── Bulk sell ────────────────────────────────────────────────────────

    override suspend fun bulkSellItems(operations: List<InventoryFacade.BulkSellOperation>): InventoryFacade.BulkSellResult {
        var totalEarned = 0L
        var soldCount = 0
        val soldItemNames = mutableListOf<String>()
        val failedItemNames = mutableListOf<String>()

        stateStore.update {
            for (op in operations) {
                var sold = false
                when (op.itemType) {
                    "equipment" -> {
                        val stack = equipmentStacks.get(op.id)
                        if (stack != null && !stack.isLocked && op.quantity in 1..stack.quantity) {
                            val newQty = stack.quantity - op.quantity
                            if (newQty <= 0) {
                                equipmentStacks.remove(op.id)
                            } else {
                                equipmentStacks.update(op.id) { it.copy(quantity = newQty) }
                            }
                            totalEarned += GameConfig.Rarity.calculateSellPrice(stack.basePrice, op.quantity)
                            soldCount++; sold = true
                        }
                    }
                    "manual" -> {
                        val stack = manualStacks.get(op.id)
                        if (stack != null && !stack.isLocked && op.quantity in 1..stack.quantity) {
                            val newQty = stack.quantity - op.quantity
                            if (newQty <= 0) {
                                manualStacks.remove(op.id)
                            } else {
                                manualStacks.update(op.id) { it.copy(quantity = newQty) }
                            }
                            totalEarned += GameConfig.Rarity.calculateSellPrice(stack.basePrice, op.quantity)
                            soldCount++; sold = true
                        }
                    }
                    "pill" -> {
                        val pill = pills.get(op.id)
                        if (pill != null && !pill.isLocked && op.quantity in 1..pill.quantity) {
                            val newQty = pill.quantity - op.quantity
                            if (newQty <= 0) {
                                pills.remove(op.id)
                            } else {
                                pills.update(op.id) { it.copy(quantity = newQty) }
                            }
                            totalEarned += GameConfig.Rarity.calculateSellPrice(pill.basePrice, op.quantity)
                            soldCount++; sold = true
                        }
                    }
                    "material" -> {
                        val material = materials.get(op.id)
                        if (material != null && !material.isLocked && op.quantity in 1..material.quantity) {
                            val newQty = material.quantity - op.quantity
                            if (newQty <= 0) {
                                materials.remove(op.id)
                            } else {
                                materials.update(op.id) { it.copy(quantity = newQty) }
                            }
                            totalEarned += GameConfig.Rarity.calculateSellPrice(material.basePrice, op.quantity)
                            soldCount++; sold = true
                        }
                    }
                    "herb" -> {
                        val herb = herbs.get(op.id)
                        if (herb != null && !herb.isLocked && op.quantity in 1..herb.quantity) {
                            val newQty = herb.quantity - op.quantity
                            if (newQty <= 0) {
                                herbs.remove(op.id)
                            } else {
                                herbs.update(op.id) { it.copy(quantity = newQty) }
                            }
                            totalEarned += GameConfig.Rarity.calculateSellPrice(herb.basePrice, op.quantity)
                            soldCount++; sold = true
                        }
                    }
                    "seed" -> {
                        val seed = seeds.get(op.id)
                        if (seed != null && !seed.isLocked && op.quantity in 1..seed.quantity) {
                            val newQty = seed.quantity - op.quantity
                            if (newQty <= 0) {
                                seeds.remove(op.id)
                            } else {
                                seeds.update(op.id) { it.copy(quantity = newQty) }
                            }
                            totalEarned += GameConfig.Rarity.calculateSellPrice(seed.basePrice, op.quantity)
                            soldCount++; sold = true
                        }
                    }
                }
                if (sold) soldItemNames.add("${op.name} ${op.quantity}") else failedItemNames.add(op.name)
            }
            if (totalEarned > 0) {
                gameData = gameData.copy(spiritStones = gameData.spiritStones + totalEarned)
            }
        }
        return InventoryFacade.BulkSellResult(soldCount, totalEarned, soldItemNames, failedItemNames)
    }

    // ── Lock toggle ──────────────────────────────────────────────────────

    override fun toggleItemLock(itemId: String, itemType: String) {
        gameEngineCore.launchInScope {
            stateStore.update {
                when (itemType) {
                    "equipment" -> equipmentStacks.update(itemId) { it.copy(isLocked = !it.isLocked) }
                    "manual" -> manualStacks.update(itemId) { it.copy(isLocked = !it.isLocked) }
                    "pill" -> pills.update(itemId) { it.copy(isLocked = !it.isLocked) }
                    "material" -> materials.update(itemId) { it.copy(isLocked = !it.isLocked) }
                    "herb" -> herbs.update(itemId) { it.copy(isLocked = !it.isLocked) }
                    "seed" -> seeds.update(itemId) { it.copy(isLocked = !it.isLocked) }
                }
            }
        }
    }

    // ── Merchant trading ─────────────────────────────────────────────────

    override suspend fun buyMerchantItem(itemId: String, quantity: Int) {
        val merchantItem = stateStore.gameData.value.travelingMerchantItems.find { it.id == itemId } ?: return
        val cost = merchantItem.price * quantity
        if (stateStore.gameData.value.spiritStones < cost || quantity > merchantItem.quantity) return

        when (merchantItem.type.lowercase(java.util.Locale.getDefault())) {
            "equipment" -> {
                val eq = MerchantItemConverter.toEquipment(merchantItem)
                if (!inventorySystem.canAddEquipment(eq.name, eq.rarity, eq.slot)) return
            }
            "manual" -> {
                val m = MerchantItemConverter.toManual(merchantItem)
                if (!inventorySystem.canAddManual(m.name, m.rarity, m.type)) return
            }
            "pill" -> {
                val p = MerchantItemConverter.toPill(merchantItem)
                if (!inventorySystem.canAddPill(p.name, p.rarity, p.category, p.grade)) return
            }
            "material" -> {
                val m = MerchantItemConverter.toMaterial(merchantItem)
                if (!inventorySystem.canAddMaterial(m.name, m.rarity, m.category)) return
            }
            "herb" -> {
                val h = MerchantItemConverter.toHerb(merchantItem)
                if (!inventorySystem.canAddHerb(h.name, h.rarity, h.category)) return
            }
            "seed" -> {
                val s = MerchantItemConverter.toSeed(merchantItem)
                if (!inventorySystem.canAddSeed(s.name, s.rarity, s.growTime)) return
            }
            "spiritstone" -> { /* 灵石不占用仓库槽位 */ }
        }

        stateStore.update {
            gameData = gameData.copy(
                spiritStones = gameData.spiritStones - cost,
                travelingMerchantItems = gameData.travelingMerchantItems.map { item ->
                    if (item.id == itemId) {
                        if (quantity >= item.quantity) null else item.copy(quantity = item.quantity - quantity)
                    } else item
                }.filterNotNull()
            )

            when (merchantItem.type.lowercase(java.util.Locale.getDefault())) {
                "equipment" -> {
                    val stack = MerchantItemConverter.toEquipment(merchantItem).copy(quantity = quantity)
                    val existing = equipmentStacks.all().find { it.name == stack.name && it.rarity == stack.rarity && it.slot == stack.slot }
                    if (existing != null) {
                        equipmentStacks.update(existing.id) { it.copy(quantity = it.quantity + stack.quantity) }
                    } else {
                        equipmentStacks.add(stack)
                    }
                }
                "manual" -> {
                    val m = MerchantItemConverter.toManual(merchantItem).copy(quantity = quantity)
                    // 使用 mergeStackable 处理溢出，与其他类型保持一致。
                    // 修复历史 bug：旧实现直接相加 quantity 不检查 maxStack，
                    // 导致购买可堆叠功法时超出上限静默丢失。
                    manualStacks = manualStacks.mergeStackable(
                        item = m,
                        matchPredicate = { it.name == m.name && it.rarity == m.rarity && it.type == m.type },
                        maxStack = inventoryConfig.getMaxStackSize("manual")
                    )
                }
                "pill" -> {
                    val p = MerchantItemConverter.toPill(merchantItem).copy(quantity = quantity)
                    pills = pills.mergeStackable(
                        item = p,
                        matchPredicate = { it.name == p.name && it.rarity == p.rarity && it.category == p.category && it.grade == p.grade },
                        maxStack = inventoryConfig.getMaxStackSize("pill")
                    )
                }
                "material" -> {
                    val m = MerchantItemConverter.toMaterial(merchantItem).copy(quantity = quantity)
                    materials = materials.mergeStackable(
                        item = m,
                        matchPredicate = { it.name == m.name && it.rarity == m.rarity && it.category == m.category },
                        maxStack = inventoryConfig.getMaxStackSize("material")
                    )
                }
                "herb" -> {
                    val h = MerchantItemConverter.toHerb(merchantItem).copy(quantity = quantity)
                    herbs = herbs.mergeStackable(
                        item = h,
                        matchPredicate = { it.name == h.name && it.rarity == h.rarity && it.category == h.category },
                        maxStack = inventoryConfig.getMaxStackSize("herb")
                    )
                }
                "seed" -> {
                    val s = MerchantItemConverter.toSeed(merchantItem).copy(quantity = quantity)
                    seeds = seeds.mergeStackable(
                        item = s,
                        matchPredicate = { it.name == s.name && it.rarity == s.rarity && it.growTime == s.growTime },
                        maxStack = inventoryConfig.getMaxStackSize("seed")
                    )
                }
                "spiritstone" -> {
                    when (merchantItem.name) {
                        "中品灵石" -> gameData = gameData.copy(
                            midGradeSpiritStones = gameData.midGradeSpiritStones + quantity
                        )
                        "上品灵石" -> gameData = gameData.copy(
                            highGradeSpiritStones = gameData.highGradeSpiritStones + quantity
                        )
                    }
                }
            }
        }

        // 商人物品购买是手动操作，弹出奖励卡片
        stateStore.enqueueRewardCards(listOf(
            RewardCardItem(
                itemName = merchantItem.name,
                itemType = merchantItem.type.lowercase(),
                rarity = merchantItem.rarity.coerceIn(1, 6),
                quantity = quantity
            )
        ))
    }

    override suspend fun sellToMerchant(acquisitionItemId: String, quantity: Int) {
        val acquisitionItem = stateStore.gameData.value.merchantAcquisitionItems.find { it.id == acquisitionItemId } ?: return
        if (quantity <= 0 || quantity > acquisitionItem.quantity) return

        stateStore.update {
            val warehouseQty = warehouseCount(acquisitionItem)
            val actualQuantity = quantity.coerceAtMost(warehouseQty).coerceAtMost(acquisitionItem.quantity)
            if (actualQuantity <= 0) return@update

            // 从仓库移除物品
            var remaining = actualQuantity
            when (acquisitionItem.type.lowercase(java.util.Locale.getDefault())) {
                "equipment" -> {
                    equipmentStacks.replaceAll(removeMatching(equipmentStacks.all(),
                        { it.name == acquisitionItem.name && it.rarity == acquisitionItem.rarity && !it.isLocked },
                        { it.quantity }, { s, q -> s.copy(quantity = q) }, remaining))
                }
                "manual" -> {
                    manualStacks.replaceAll(removeMatching(manualStacks.all(),
                        { it.name == acquisitionItem.name && it.rarity == acquisitionItem.rarity && !it.isLocked },
                        { it.quantity }, { s, q -> s.copy(quantity = q) }, remaining))
                }
                "pill" -> {
                    pills.replaceAll(removeMatching(pills.all(),
                        { it.name == acquisitionItem.name && it.rarity == acquisitionItem.rarity && it.grade.displayName == (acquisitionItem.grade ?: "") && !it.isLocked },
                        { it.quantity }, { p, q -> p.copy(quantity = q) }, remaining))
                }
                "material" -> {
                    materials.replaceAll(removeMatching(materials.all(),
                        { it.name == acquisitionItem.name && it.rarity == acquisitionItem.rarity && !it.isLocked },
                        { it.quantity }, { m, q -> m.copy(quantity = q) }, remaining))
                }
                "herb" -> {
                    herbs.replaceAll(removeMatching(herbs.all(),
                        { it.name == acquisitionItem.name && it.rarity == acquisitionItem.rarity && !it.isLocked },
                        { it.quantity }, { h, q -> h.copy(quantity = q) }, remaining))
                }
                "seed" -> {
                    seeds.replaceAll(removeMatching(seeds.all(),
                        { it.name == acquisitionItem.name && it.rarity == acquisitionItem.rarity && !it.isLocked },
                        { it.quantity }, { s, q -> s.copy(quantity = q) }, remaining))
                }
                "spiritstone" -> {
                    when (acquisitionItem.name) {
                        "中品灵石" -> gameData = gameData.copy(
                            midGradeSpiritStones = (gameData.midGradeSpiritStones - actualQuantity).coerceAtLeast(0L)
                        )
                        "上品灵石" -> gameData = gameData.copy(
                            highGradeSpiritStones = (gameData.highGradeSpiritStones - actualQuantity).coerceAtLeast(0L)
                        )
                    }
                }
            }

            val totalPrice = acquisitionItem.price * actualQuantity
            gameData = gameData.copy(
                spiritStones = gameData.spiritStones + totalPrice,
                merchantAcquisitionItems = gameData.merchantAcquisitionItems.map { item ->
                    if (item.id == acquisitionItemId) item.copy(quantity = item.quantity - actualQuantity) else item
                }
            )
        }
    }

    private fun warehouseCount(item: MerchantItem): Int = when (item.type.lowercase(java.util.Locale.getDefault())) {
        "equipment" -> equipmentStacks.value.filter { it.name == item.name && it.rarity == item.rarity }.sumOf { it.quantity }
        "manual" -> manualStacks.value.filter { it.name == item.name && it.rarity == item.rarity }.sumOf { it.quantity }
        "pill" -> pills.value.filter { it.name == item.name && it.rarity == item.rarity && it.grade.displayName == (item.grade ?: "") }.sumOf { it.quantity }
        "material" -> materials.value.filter { it.name == item.name && it.rarity == item.rarity }.sumOf { it.quantity }
        "herb" -> herbs.value.filter { it.name == item.name && it.rarity == item.rarity }.sumOf { it.quantity }
        "seed" -> seeds.value.filter { it.name == item.name && it.rarity == item.rarity }.sumOf { it.quantity }
        "spiritstone" -> {
            val grade = SpiritStoneGrade.fromDisplayName(item.name) ?: return 0
            stateStore.gameData.value.spiritStoneCount(grade).toInt().coerceAtLeast(0)
        }
        else -> 0
    }

    /**
     * Deduct up to [amount] from [items] where [match] holds, processing in list order.
     * Each matched item has its quantity reduced; items reaching zero are removed.
     * @return updated list with deductions applied.
     */
    private inline fun <T> removeMatching(
        items: List<T>,
        crossinline match: (T) -> Boolean,
        crossinline getQty: (T) -> Int,
        crossinline setQty: (T, Int) -> T,
        amount: Int
    ): List<T> {
        var remaining = amount
        return items.mapNotNull { item ->
            if (remaining > 0 && match(item)) {
                val deduct = remaining.coerceAtMost(getQty(item))
                val newQty = getQty(item) - deduct
                remaining -= deduct
                if (newQty <= 0) null else setQty(item, newQty)
            } else item
        }
    }

    override suspend fun listItemsToMerchant(items: List<Pair<String, Int>>) {
        val newItems = mutableListOf<MerchantItem>()
        stateStore.update {
            items.forEach { (itemId, quantity) ->
                val eqStack = equipmentStacks.get(itemId)
                if (eqStack != null && !eqStack.isLocked && quantity in 1..eqStack.quantity) {
                    val n = eqStack.quantity - quantity
                    if (n <= 0) {
                        equipmentStacks.remove(itemId)
                    } else {
                        equipmentStacks.update(itemId) { it.copy(quantity = n) }
                    }
                    newItems.add(MerchantItem(id = java.util.UUID.randomUUID().toString(), name = eqStack.name, type = "equipment", itemId = itemId, rarity = eqStack.rarity, price = GameConfig.Rarity.calculateSellPrice(eqStack.basePrice, 1), quantity = quantity))
                    return@forEach
                }
                val manualStack = manualStacks.get(itemId)
                if (manualStack != null && !manualStack.isLocked && quantity in 1..manualStack.quantity) {
                    val n = manualStack.quantity - quantity
                    if (n <= 0) {
                        manualStacks.remove(itemId)
                    } else {
                        manualStacks.update(itemId) { it.copy(quantity = n) }
                    }
                    newItems.add(MerchantItem(id = java.util.UUID.randomUUID().toString(), name = manualStack.name, type = "manual", itemId = itemId, rarity = manualStack.rarity, price = GameConfig.Rarity.calculateSellPrice(manualStack.basePrice, 1), quantity = quantity))
                    return@forEach
                }
                val pill = pills.get(itemId)
                if (pill != null && !pill.isLocked && quantity in 1..pill.quantity) {
                    val n = pill.quantity - quantity
                    if (n <= 0) {
                        pills.remove(itemId)
                    } else {
                        pills.update(itemId) { it.copy(quantity = n) }
                    }
                    newItems.add(MerchantItem(id = java.util.UUID.randomUUID().toString(), name = pill.name, type = "pill", itemId = itemId, rarity = pill.rarity, price = GameConfig.Rarity.calculateSellPrice(pill.basePrice, 1), quantity = quantity, grade = pill.grade.displayName))
                    return@forEach
                }
            }
            if (newItems.isNotEmpty()) {
                gameData = gameData.copy(playerListedItems = gameData.playerListedItems + newItems)
            }
        }
    }

    override suspend fun removePlayerListedItem(itemId: String) {
        val data = stateStore.gameData.value
        val item = data.playerListedItems.find { it.id == itemId } ?: return

        when (item.type.lowercase(java.util.Locale.getDefault())) {
            "equipment" -> stateStore.equipmentStacks.value.find { it.id == item.itemId }?.let {
                inventorySystem.addEquipmentStack(it.copy(quantity = (it.quantity + item.quantity)))
            }
            "manual" -> stateStore.manualStacks.value.find { it.id == item.itemId }?.let {
                inventorySystem.addManualStack(it.copy(quantity = (it.quantity + item.quantity)))
            }
            "pill" -> stateStore.pills.value.find { it.id == item.itemId }?.let {
                stateStore.update { pills.update(item.itemId) { it.copy(quantity = (it.quantity + item.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("pill"))) } }
            }
            "material" -> stateStore.materials.value.find { it.id == item.itemId }?.let {
                stateStore.update { materials.update(item.itemId) { it.copy(quantity = (it.quantity + item.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("material"))) } }
            }
            "herb" -> stateStore.herbs.value.find { it.id == item.itemId }?.let {
                stateStore.update { herbs.update(item.itemId) { it.copy(quantity = (it.quantity + item.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("herb"))) } }
            }
            "seed" -> stateStore.seeds.value.find { it.id == item.itemId }?.let {
                stateStore.update { seeds.update(item.itemId) { it.copy(quantity = (it.quantity + item.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("seed"))) } }
            }
        }
        stateStore.update { gameData = gameData.copy(playerListedItems = gameData.playerListedItems.filter { it.id != itemId }) }
    }

    // ── Storage bag ──────────────────────────────────────────────────────

    override suspend fun openStorageBag(bagId: String): Pair<List<BattleRewardItem>, List<RewardCardItem>> {
        val bag = stateStore.storageBags.value.find { it.id == bagId }
            ?: return Pair(emptyList(), emptyList())
        val rarity = bag.rarity
        val count = kotlin.random.Random.nextInt(5, 21)
        val rewards = mutableListOf<BattleRewardItem>()

        stateStore.update {
            if (bag.quantity <= 1) {
                storageBags.remove(bagId)
            } else {
                storageBags.update(bagId) { it.copy(quantity = it.quantity - 1) }
            }
        }

        repeat(count) {
            val type = kotlin.random.Random.nextInt(7)
            when (type) {
                0 -> {
                    val stack = com.xianxia.sect.core.registry.EquipmentDatabase.generateRandom(rarity, rarity)
                    stateStore.update { equipmentStacks.add(stack) }
                    rewards.add(BattleRewardItem(itemId = stack.id, name = stack.name, quantity = 1, rarity = stack.rarity, type = "equipment"))
                }
                1 -> {
                    if (com.xianxia.sect.core.registry.ManualDatabase.isInitialized) {
                        val templates = com.xianxia.sect.core.registry.ManualDatabase.getByRarity(rarity)
                        if (templates.isNotEmpty()) {
                            val stack = com.xianxia.sect.core.registry.ManualDatabase.createFromTemplate(templates.random())
                            stateStore.update { manualStacks.add(stack) }
                            rewards.add(BattleRewardItem(itemId = stack.id, name = stack.name, quantity = 1, rarity = stack.rarity, type = "manual"))
                        }
                    }
                }
                2 -> {
                    val pill = com.xianxia.sect.core.registry.ItemDatabase.generateRandomPill(rarity, rarity)
                    stateStore.update {
                        val existing = pills.all().find { it.name == pill.name && it.rarity == pill.rarity && it.category == pill.category && it.grade == pill.grade }
                        if (existing != null) {
                            pills.update(existing.id) { it.copy(quantity = it.quantity + 1) }
                        } else {
                            pills.add(pill)
                        }
                    }
                    rewards.add(BattleRewardItem(itemId = pill.id, name = pill.name, quantity = 1, rarity = pill.rarity, type = "pill"))
                }
                3 -> {
                    val templates = com.xianxia.sect.core.registry.HerbDatabase.getHerbsByTier(rarity)
                    if (templates.isNotEmpty()) {
                        val h = templates.random()
                        var herbId = ""; var herbName = ""; var herbRarity = 0
                        stateStore.update {
                            val existing = herbs.all().find { it.name == h.name && it.rarity == h.rarity && it.category == h.category }
                            if (existing != null) {
                                herbId = existing.id; herbName = existing.name; herbRarity = existing.rarity
                                herbs.update(existing.id) { it.copy(quantity = it.quantity + 1) }
                            } else {
                                val newHerb = com.xianxia.sect.core.model.Herb(id = java.util.UUID.randomUUID().toString(), name = h.name, rarity = h.rarity, description = h.description, category = h.category, quantity = 1)
                                herbId = newHerb.id; herbName = newHerb.name; herbRarity = newHerb.rarity
                                herbs.add(newHerb)
                            }
                        }
                        rewards.add(BattleRewardItem(itemId = herbId, name = herbName, quantity = 1, rarity = herbRarity, type = "herb"))
                    }
                }
                4 -> {
                    val templates = com.xianxia.sect.core.registry.HerbDatabase.getAllSeeds().filter { it.rarity == rarity }
                    if (templates.isNotEmpty()) {
                        val s = templates.random()
                        var seedId = ""; var seedName = ""; var seedRarity = 0
                        stateStore.update {
                            val existing = seeds.all().find { it.name == s.name && it.rarity == s.rarity && it.growTime == s.growTime }
                            if (existing != null) {
                                seedId = existing.id; seedName = existing.name; seedRarity = existing.rarity
                                seeds.update(existing.id) { it.copy(quantity = it.quantity + 1) }
                            } else {
                                val newSeed = com.xianxia.sect.core.model.Seed(id = java.util.UUID.randomUUID().toString(), name = s.name, rarity = s.rarity, description = s.description, growTime = s.growTime, yield = s.yield, quantity = 1)
                                seedId = newSeed.id; seedName = newSeed.name; seedRarity = newSeed.rarity
                                seeds.add(newSeed)
                            }
                        }
                        rewards.add(BattleRewardItem(itemId = seedId, name = seedName, quantity = 1, rarity = seedRarity, type = "seed"))
                    }
                }
                5 -> {
                    val mat = com.xianxia.sect.core.registry.ItemDatabase.generateRandomMaterial(rarity, rarity)
                    stateStore.update {
                        val existing = materials.all().find { it.name == mat.name && it.rarity == mat.rarity && it.category == mat.category }
                        if (existing != null) {
                            materials.update(existing.id) { it.copy(quantity = it.quantity + 1) }
                        } else {
                            materials.add(mat)
                        }
                    }
                    rewards.add(BattleRewardItem(itemId = mat.id, name = mat.name, quantity = 1, rarity = mat.rarity, type = "material"))
                }
                6 -> {
                    val amount = StorageBag.SPIRIT_STONE_AMOUNTS.getOrElse(rarity - 1) { 500L }
                    stateStore.update { gameData = gameData.copy(spiritStones = gameData.spiritStones + amount) }
                    val existing = rewards.find { it.type == "spiritStones" }
                    if (existing != null) {
                        rewards[rewards.indexOf(existing)] = existing.copy(quantity = existing.quantity + amount.toInt())
                    } else {
                        rewards.add(BattleRewardItem(name = "灵石", quantity = amount.toInt(), rarity = 1, type = "spiritStones"))
                    }
                }
            }
        }
        // 储物袋开启是手动操作，展示奖励卡片（卡片由 UI 在对话框关闭后入队）
        val cards = rewards.map { reward ->
            RewardCardItem(
                itemName = reward.name,
                itemType = reward.type,
                rarity = reward.rarity.coerceIn(1, 6),
                quantity = reward.quantity
            )
        }
        return Pair(rewards.toList(), cards)
    }
}
