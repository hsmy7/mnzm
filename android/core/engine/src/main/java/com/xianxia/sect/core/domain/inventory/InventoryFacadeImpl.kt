package com.xianxia.sect.core.engine.domain.inventory

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.system.MerchantItemConverter
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.GameStateStore
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

    // ── Sell operations ──────────────────────────────────────────────────

    override suspend fun sellEquipment(equipmentId: String, quantity: Int): Boolean {
        var success = false
        stateStore.update {
            val stack = equipmentStacks.find { it.id == equipmentId }
            if (stack != null && !stack.isLocked && quantity in 1..stack.quantity) {
                equipmentStacks = equipmentStacks.mapNotNull { s ->
                    if (s.id == equipmentId) {
                        val newQty = s.quantity - quantity
                        if (newQty == 0) null else s.copy(quantity = newQty)
                    } else s
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
            val stack = manualStacks.find { it.id == manualId }
            if (stack != null && !stack.isLocked && quantity in 1..stack.quantity) {
                manualStacks = manualStacks.mapNotNull { s ->
                    if (s.id == manualId) {
                        val newQty = s.quantity - quantity
                        if (newQty == 0) null else s.copy(quantity = newQty)
                    } else s
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
            val pill = pills.find { it.id == pillId }
            if (pill != null && !pill.isLocked && quantity in 1..pill.quantity) {
                pills = pills.mapNotNull { p ->
                    if (p.id == pillId) {
                        val newQty = p.quantity - quantity
                        if (newQty == 0) null else p.copy(quantity = newQty)
                    } else p
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
            val material = materials.find { it.id == materialId }
            if (material != null && !material.isLocked && quantity in 1..material.quantity) {
                materials = materials.mapNotNull { m ->
                    if (m.id == materialId) {
                        val newQty = m.quantity - quantity
                        if (newQty == 0) null else m.copy(quantity = newQty)
                    } else m
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
            val herb = herbs.find { it.id == herbId }
            if (herb != null && !herb.isLocked && quantity in 1..herb.quantity) {
                herbs = herbs.mapNotNull { h ->
                    if (h.id == herbId) {
                        val newQty = h.quantity - quantity
                        if (newQty == 0) null else h.copy(quantity = newQty)
                    } else h
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
            val seed = seeds.find { it.id == seedId }
            if (seed != null && !seed.isLocked && quantity in 1..seed.quantity) {
                seeds = seeds.mapNotNull { s ->
                    if (s.id == seedId) {
                        val newQty = s.quantity - quantity
                        if (newQty == 0) null else s.copy(quantity = newQty)
                    } else s
                }
                gameData = gameData.copy(spiritStones = gameData.spiritStones + GameConfig.Rarity.calculateSellPrice(seed.basePrice, quantity))
                success = true
            }
        }
        return success
    }

    override suspend fun consumeMaterialByName(name: String, rarity: Int, quantity: Int): Boolean {
        var success = false
        stateStore.update {
            val material = materials.find { it.name == name && it.rarity == rarity }
            if (material != null && !material.isLocked && quantity in 1..material.quantity) {
                materials = materials.mapNotNull { m ->
                    if (m.id == material.id) {
                        val newQty = m.quantity - quantity
                        if (newQty == 0) null else m.copy(quantity = newQty)
                    } else m
                }
                success = true
            }
        }
        return success
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
                        val stack = equipmentStacks.find { it.id == op.id }
                        if (stack != null && !stack.isLocked && op.quantity in 1..stack.quantity) {
                            equipmentStacks = equipmentStacks.mapNotNull { s ->
                                if (s.id == op.id) {
                                    val newQty = s.quantity - op.quantity
                                    if (newQty == 0) null else s.copy(quantity = newQty)
                                } else s
                            }
                            totalEarned += GameConfig.Rarity.calculateSellPrice(stack.basePrice, op.quantity)
                            soldCount++; sold = true
                        }
                    }
                    "manual" -> {
                        val stack = manualStacks.find { it.id == op.id }
                        if (stack != null && !stack.isLocked && op.quantity in 1..stack.quantity) {
                            manualStacks = manualStacks.mapNotNull { s ->
                                if (s.id == op.id) {
                                    val newQty = s.quantity - op.quantity
                                    if (newQty == 0) null else s.copy(quantity = newQty)
                                } else s
                            }
                            totalEarned += GameConfig.Rarity.calculateSellPrice(stack.basePrice, op.quantity)
                            soldCount++; sold = true
                        }
                    }
                    "pill" -> {
                        val pill = pills.find { it.id == op.id }
                        if (pill != null && !pill.isLocked && op.quantity in 1..pill.quantity) {
                            pills = pills.mapNotNull { p ->
                                if (p.id == op.id) {
                                    val newQty = p.quantity - op.quantity
                                    if (newQty == 0) null else p.copy(quantity = newQty)
                                } else p
                            }
                            totalEarned += GameConfig.Rarity.calculateSellPrice(pill.basePrice, op.quantity)
                            soldCount++; sold = true
                        }
                    }
                    "material" -> {
                        val material = materials.find { it.id == op.id }
                        if (material != null && !material.isLocked && op.quantity in 1..material.quantity) {
                            materials = materials.mapNotNull { m ->
                                if (m.id == op.id) {
                                    val newQty = m.quantity - op.quantity
                                    if (newQty == 0) null else m.copy(quantity = newQty)
                                } else m
                            }
                            totalEarned += GameConfig.Rarity.calculateSellPrice(material.basePrice, op.quantity)
                            soldCount++; sold = true
                        }
                    }
                    "herb" -> {
                        val herb = herbs.find { it.id == op.id }
                        if (herb != null && !herb.isLocked && op.quantity in 1..herb.quantity) {
                            herbs = herbs.mapNotNull { h ->
                                if (h.id == op.id) {
                                    val newQty = h.quantity - op.quantity
                                    if (newQty == 0) null else h.copy(quantity = newQty)
                                } else h
                            }
                            totalEarned += GameConfig.Rarity.calculateSellPrice(herb.basePrice, op.quantity)
                            soldCount++; sold = true
                        }
                    }
                    "seed" -> {
                        val seed = seeds.find { it.id == op.id }
                        if (seed != null && !seed.isLocked && op.quantity in 1..seed.quantity) {
                            seeds = seeds.mapNotNull { s ->
                                if (s.id == op.id) {
                                    val newQty = s.quantity - op.quantity
                                    if (newQty == 0) null else s.copy(quantity = newQty)
                                } else s
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
                    "equipment" -> equipmentStacks = equipmentStacks.map { if (it.id == itemId) it.copy(isLocked = !it.isLocked) else it }
                    "manual" -> manualStacks = manualStacks.map { if (it.id == itemId) it.copy(isLocked = !it.isLocked) else it }
                    "pill" -> pills = pills.map { if (it.id == itemId) it.copy(isLocked = !it.isLocked) else it }
                    "material" -> materials = materials.map { if (it.id == itemId) it.copy(isLocked = !it.isLocked) else it }
                    "herb" -> herbs = herbs.map { if (it.id == itemId) it.copy(isLocked = !it.isLocked) else it }
                    "seed" -> seeds = seeds.map { if (it.id == itemId) it.copy(isLocked = !it.isLocked) else it }
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
                    val existing = equipmentStacks.find { it.name == stack.name && it.rarity == stack.rarity && it.slot == stack.slot }
                    if (existing != null) {
                        equipmentStacks = equipmentStacks.map { if (it.id == existing.id) it.copy(quantity = it.quantity + stack.quantity) else it }
                    } else {
                        equipmentStacks = equipmentStacks + stack
                    }
                }
                "manual" -> {
                    val stack = MerchantItemConverter.toManual(merchantItem).copy(quantity = quantity)
                    val existing = manualStacks.find { it.name == stack.name && it.rarity == stack.rarity && it.type == stack.type }
                    if (existing != null) {
                        manualStacks = manualStacks.map { if (it.id == existing.id) it.copy(quantity = it.quantity + stack.quantity) else it }
                    } else {
                        manualStacks = manualStacks + stack
                    }
                }
                "pill" -> {
                    val p = MerchantItemConverter.toPill(merchantItem).copy(quantity = quantity)
                    val existing = pills.find { it.name == p.name && it.rarity == p.rarity && it.category == p.category && it.grade == p.grade }
                    if (existing != null) {
                        val newQty = (existing.quantity + p.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("pill"))
                        pills = pills.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                    } else {
                        pills = pills + p
                    }
                }
                "material" -> {
                    val m = MerchantItemConverter.toMaterial(merchantItem).copy(quantity = quantity)
                    val existing = materials.find { it.name == m.name && it.rarity == m.rarity && it.category == m.category }
                    if (existing != null) {
                        val newQty = (existing.quantity + m.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("material"))
                        materials = materials.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                    } else {
                        materials = materials + m
                    }
                }
                "herb" -> {
                    val h = MerchantItemConverter.toHerb(merchantItem).copy(quantity = quantity)
                    val existing = herbs.find { it.name == h.name && it.rarity == h.rarity && h.category == h.category }
                    if (existing != null) {
                        val newQty = (existing.quantity + h.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("herb"))
                        herbs = herbs.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                    } else {
                        herbs = herbs + h
                    }
                }
                "seed" -> {
                    val s = MerchantItemConverter.toSeed(merchantItem).copy(quantity = quantity)
                    val existing = seeds.find { it.name == s.name && it.rarity == s.rarity && s.growTime == s.growTime }
                    if (existing != null) {
                        val newQty = (existing.quantity + s.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("seed"))
                        seeds = seeds.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                    } else {
                        seeds = seeds + s
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
                    equipmentStacks = removeMatching(equipmentStacks,
                        { it.name == acquisitionItem.name && it.rarity == acquisitionItem.rarity && !it.isLocked },
                        { it.quantity }, { s, q -> s.copy(quantity = q) }, remaining)
                }
                "manual" -> {
                    manualStacks = removeMatching(manualStacks,
                        { it.name == acquisitionItem.name && it.rarity == acquisitionItem.rarity && !it.isLocked },
                        { it.quantity }, { s, q -> s.copy(quantity = q) }, remaining)
                }
                "pill" -> {
                    pills = removeMatching(pills,
                        { it.name == acquisitionItem.name && it.rarity == acquisitionItem.rarity && it.grade.displayName == (acquisitionItem.grade ?: "") && !it.isLocked },
                        { it.quantity }, { p, q -> p.copy(quantity = q) }, remaining)
                }
                "material" -> {
                    materials = removeMatching(materials,
                        { it.name == acquisitionItem.name && it.rarity == acquisitionItem.rarity && !it.isLocked },
                        { it.quantity }, { m, q -> m.copy(quantity = q) }, remaining)
                }
                "herb" -> {
                    herbs = removeMatching(herbs,
                        { it.name == acquisitionItem.name && it.rarity == acquisitionItem.rarity && !it.isLocked },
                        { it.quantity }, { h, q -> h.copy(quantity = q) }, remaining)
                }
                "seed" -> {
                    seeds = removeMatching(seeds,
                        { it.name == acquisitionItem.name && it.rarity == acquisitionItem.rarity && !it.isLocked },
                        { it.quantity }, { s, q -> s.copy(quantity = q) }, remaining)
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
                val eqStack = equipmentStacks.find { it.id == itemId }
                if (eqStack != null && !eqStack.isLocked && quantity in 1..eqStack.quantity) {
                    equipmentStacks = equipmentStacks.mapNotNull { s ->
                        if (s.id == itemId) { val n = s.quantity - quantity; if (n == 0) null else s.copy(quantity = n) } else s
                    }
                    newItems.add(MerchantItem(id = java.util.UUID.randomUUID().toString(), name = eqStack.name, type = "equipment", itemId = itemId, rarity = eqStack.rarity, price = GameConfig.Rarity.calculateSellPrice(eqStack.basePrice, 1), quantity = quantity))
                    return@forEach
                }
                val manualStack = manualStacks.find { it.id == itemId }
                if (manualStack != null && !manualStack.isLocked && quantity in 1..manualStack.quantity) {
                    manualStacks = manualStacks.mapNotNull { s ->
                        if (s.id == itemId) { val n = s.quantity - quantity; if (n == 0) null else s.copy(quantity = n) } else s
                    }
                    newItems.add(MerchantItem(id = java.util.UUID.randomUUID().toString(), name = manualStack.name, type = "manual", itemId = itemId, rarity = manualStack.rarity, price = GameConfig.Rarity.calculateSellPrice(manualStack.basePrice, 1), quantity = quantity))
                    return@forEach
                }
                val pill = pills.find { it.id == itemId }
                if (pill != null && !pill.isLocked && quantity in 1..pill.quantity) {
                    pills = pills.mapNotNull { p ->
                        if (p.id == itemId) { val n = p.quantity - quantity; if (n == 0) null else p.copy(quantity = n) } else p
                    }
                    newItems.add(MerchantItem(id = java.util.UUID.randomUUID().toString(), name = pill.name, type = "pill", itemId = itemId, rarity = pill.rarity, price = GameConfig.Rarity.calculateSellPrice(pill.basePrice, 1), quantity = quantity, grade = pill.grade.displayName))
                    return@forEach
                }
                val material = materials.find { it.id == itemId }
                if (material != null && !material.isLocked && quantity in 1..material.quantity) {
                    materials = materials.mapNotNull { m ->
                        if (m.id == itemId) { val n = m.quantity - quantity; if (n == 0) null else m.copy(quantity = n) } else m
                    }
                    newItems.add(MerchantItem(id = java.util.UUID.randomUUID().toString(), name = material.name, type = "material", itemId = itemId, rarity = material.rarity, price = GameConfig.Rarity.calculateSellPrice(material.basePrice, 1), quantity = quantity))
                    return@forEach
                }
                val herb = herbs.find { it.id == itemId }
                if (herb != null && !herb.isLocked && quantity in 1..herb.quantity) {
                    herbs = herbs.mapNotNull { h ->
                        if (h.id == itemId) { val n = h.quantity - quantity; if (n == 0) null else h.copy(quantity = n) } else h
                    }
                    newItems.add(MerchantItem(id = java.util.UUID.randomUUID().toString(), name = herb.name, type = "herb", itemId = itemId, rarity = herb.rarity, price = GameConfig.Rarity.calculateSellPrice(herb.basePrice, 1), quantity = quantity))
                    return@forEach
                }
                val seed = seeds.find { it.id == itemId }
                if (seed != null && !seed.isLocked && quantity in 1..seed.quantity) {
                    seeds = seeds.mapNotNull { s ->
                        if (s.id == itemId) { val n = s.quantity - quantity; if (n == 0) null else s.copy(quantity = n) } else s
                    }
                    newItems.add(MerchantItem(id = java.util.UUID.randomUUID().toString(), name = seed.name, type = "seed", itemId = itemId, rarity = seed.rarity, price = GameConfig.Rarity.calculateSellPrice(seed.basePrice, 1), quantity = quantity))
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
                stateStore.update { pills = pills.map { p -> if (p.id == item.itemId) p.copy(quantity = (p.quantity + item.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("pill"))) else p } }
            }
            "material" -> stateStore.materials.value.find { it.id == item.itemId }?.let {
                stateStore.update { materials = materials.map { m -> if (m.id == item.itemId) m.copy(quantity = (m.quantity + item.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("material"))) else m } }
            }
            "herb" -> stateStore.herbs.value.find { it.id == item.itemId }?.let {
                stateStore.update { herbs = herbs.map { h -> if (h.id == item.itemId) h.copy(quantity = (h.quantity + item.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("herb"))) else h } }
            }
            "seed" -> stateStore.seeds.value.find { it.id == item.itemId }?.let {
                stateStore.update { seeds = seeds.map { s -> if (s.id == item.itemId) s.copy(quantity = (s.quantity + item.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("seed"))) else s } }
            }
        }
        stateStore.update { gameData = gameData.copy(playerListedItems = gameData.playerListedItems.filter { it.id != itemId }) }
    }

    // ── Storage bag ──────────────────────────────────────────────────────

    override suspend fun openStorageBag(bagId: String): List<BattleRewardItem> {
        val bag = stateStore.storageBags.value.find { it.id == bagId } ?: return emptyList()
        val rarity = bag.rarity
        val count = kotlin.random.Random.nextInt(5, 21)
        val rewards = mutableListOf<BattleRewardItem>()

        stateStore.update {
            if (bag.quantity <= 1) {
                storageBags = storageBags.filter { it.id != bagId }
            } else {
                storageBags = storageBags.map { if (it.id == bagId) it.copy(quantity = it.quantity - 1) else it }
            }
        }

        repeat(count) {
            val type = kotlin.random.Random.nextInt(7)
            when (type) {
                0 -> {
                    val stack = com.xianxia.sect.core.registry.EquipmentDatabase.generateRandom(rarity, rarity)
                    stateStore.update { equipmentStacks = equipmentStacks + stack }
                    rewards.add(BattleRewardItem(itemId = stack.id, name = stack.name, quantity = 1, rarity = stack.rarity, type = "equipment"))
                }
                1 -> {
                    if (com.xianxia.sect.core.registry.ManualDatabase.isInitialized) {
                        val templates = com.xianxia.sect.core.registry.ManualDatabase.getByRarity(rarity)
                        if (templates.isNotEmpty()) {
                            val stack = com.xianxia.sect.core.registry.ManualDatabase.createFromTemplate(templates.random())
                            stateStore.update { manualStacks = manualStacks + stack }
                            rewards.add(BattleRewardItem(itemId = stack.id, name = stack.name, quantity = 1, rarity = stack.rarity, type = "manual"))
                        }
                    }
                }
                2 -> {
                    val pill = com.xianxia.sect.core.registry.ItemDatabase.generateRandomPill(rarity, rarity)
                    stateStore.update {
                        val existing = pills.find { it.name == pill.name && it.rarity == pill.rarity && it.category == pill.category && it.grade == pill.grade }
                        if (existing != null) {
                            pills = pills.map { if (it.id == existing.id) it.copy(quantity = it.quantity + 1) else it }
                        } else {
                            pills = pills + pill
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
                            val existing = herbs.find { it.name == h.name && it.rarity == h.rarity && it.category == h.category }
                            if (existing != null) {
                                herbId = existing.id; herbName = existing.name; herbRarity = existing.rarity
                                herbs = herbs.map { if (it.id == existing.id) it.copy(quantity = it.quantity + 1) else it }
                            } else {
                                val newHerb = com.xianxia.sect.core.model.Herb(id = java.util.UUID.randomUUID().toString(), name = h.name, rarity = h.rarity, description = h.description, category = h.category, quantity = 1)
                                herbId = newHerb.id; herbName = newHerb.name; herbRarity = newHerb.rarity
                                herbs = herbs + newHerb
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
                            val existing = seeds.find { it.name == s.name && it.rarity == s.rarity && it.growTime == s.growTime }
                            if (existing != null) {
                                seedId = existing.id; seedName = existing.name; seedRarity = existing.rarity
                                seeds = seeds.map { if (it.id == existing.id) it.copy(quantity = it.quantity + 1) else it }
                            } else {
                                val newSeed = com.xianxia.sect.core.model.Seed(id = java.util.UUID.randomUUID().toString(), name = s.name, rarity = s.rarity, description = s.description, growTime = s.growTime, yield = s.yield, quantity = 1)
                                seedId = newSeed.id; seedName = newSeed.name; seedRarity = newSeed.rarity
                                seeds = seeds + newSeed
                            }
                        }
                        rewards.add(BattleRewardItem(itemId = seedId, name = seedName, quantity = 1, rarity = seedRarity, type = "seed"))
                    }
                }
                5 -> {
                    val mat = com.xianxia.sect.core.registry.ItemDatabase.generateRandomMaterial(rarity, rarity)
                    stateStore.update {
                        val existing = materials.find { it.name == mat.name && it.rarity == mat.rarity && it.category == mat.category }
                        if (existing != null) {
                            materials = materials.map { if (it.id == existing.id) it.copy(quantity = it.quantity + 1) else it }
                        } else {
                            materials = materials + mat
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
        // 储物袋开启是手动操作，展示奖励卡片
        val cards = rewards.map { reward ->
            RewardCardItem(
                itemName = reward.name,
                itemType = reward.type,
                rarity = reward.rarity.coerceIn(1, 6),
                quantity = reward.quantity
            )
        }
        if (cards.isNotEmpty()) {
            stateStore.enqueueRewardCards(cards)
        }
        return rewards
    }
}
