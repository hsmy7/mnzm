package com.xianxia.sect.core.engine.domain.inventory

import com.xianxia.sect.core.util.ItemNames

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.wallet.SpiritStoneReason
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.wallet.DeductResult
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.system.BagItemReconstructor
import com.xianxia.sect.core.engine.system.ReconstructedBagStack
import com.xianxia.sect.core.engine.system.MerchantItemConverter
import com.xianxia.sect.core.model.BattleRewardItem
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.ForgeRecipe
import com.xianxia.sect.core.model.HasId
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.RewardCardItem
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.model.StorageBag
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.spiritStones
import com.xianxia.sect.core.model.storageBagItems
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import java.util.UUID
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState

import com.xianxia.sect.core.engine.system.computeSlotCount
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.util.StackableItem
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import com.xianxia.sect.core.util.StorageBagUtils



@Singleton
class InventoryFacadeImpl @Inject constructor(
    override val inventorySystem: InventorySystem,
    private val stateStore: GameStateStore,
    override val inventoryConfig: InventoryConfig,
    private val gameEngineCore: GameEngineCore,
    private val spiritStoneWallet: SpiritStoneWallet,
    private val gameRngManager: GameRngManager
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

    override suspend fun consolidateStacks() = inventorySystem.consolidateStacks()

    /** 在 MutableGameState 事务内计算除当前类型以外的槽位数。 */
    private fun MutableGameState.otherSlotsCount(excludeType: String): Int {
        val total = computeSlotCount()
        val exclude = when (excludeType) {
            "equipment" -> equipmentStacks.size
            "manual" -> manualStacks.size
            "pill" -> pills.size
            "material" -> materials.size
            "herb" -> herbs.size
            "seed" -> seeds.size
            else -> 0
        }
        return total - exclude
    }

    override suspend fun confiscateStorageBagItem(discipleId: String, item: StorageBagItem) {
        // 在 stateStore.update 事务内：先尝试添加仓库，成功后才移除弟子储物袋物品
        // 避免仓库满时物品从弟子身上删除后无法回滚的永久丢失问题
        stateStore.update {
            val id = discipleId.toIntOrNull() ?: return@update
            if (!discipleTables.ids.contains(id)) return@update
            val disciple = discipleTables.assemble(id)

            // [严重-幂等] 以袋内当前条目为准：调用方传入的 item 可能是 UI 旧快照
            //（双击/重复调用）——袋内已无匹配条目即已没收，直接返回防物品复制
            val currentItem = disciple.equipment.storageBagItems.firstOrNull { it.itemId == item.itemId }
                ?: return@update

            // 凭据类路径：抑制溢出转邮件——仅全部入仓成功（Success）才从弟子袋移除物品；
            // Partial/Failure 保留袋内物品，玩家清理后重试补齐（已入仓部分合并不重复），
            // 避免"邮件已发 + 袋内保留"造成物品复制（对抗性审查 C1 修复）
            inventorySystem.withOverflowMailSuppressed {
                inventorySystem.withTrackingSource("confiscate") {
                    val eqInstance = currentItem.equipmentInstance
                    val mnInstance = currentItem.manualInstance
                    // 堆叠条目篡改防御：数量 <=0 拒绝物化（防 0 数量白得物品）
                    if (eqInstance == null && mnInstance == null && currentItem.quantity <= 0) {
                        DomainLog.w(TAG, "没收物品失败：数量非法（quantity=${currentItem.quantity}）${currentItem.name}")
                        return@withTrackingSource
                    }
                    val result = materializeConfiscatedItem(currentItem = currentItem)
                    applyConfiscationResult(
                        disciple = disciple,
                        currentItem = currentItem,
                        hasInstance = eqInstance != null || mnInstance != null,
                        result = result
                    )
                }
            }
        }
    }

    /** 没收物物化（confiscateStorageBagItem 拆分）：实例条目保真回仓 / 堆叠条目按模板重建 */
    private fun MutableGameState.materializeConfiscatedItem(currentItem: StorageBagItem): DomainResult<*>? {
        val eqInstance = currentItem.equipmentInstance
        val mnInstance = currentItem.manualInstance
        // null = 模板不存在（堆叠类条目无法重建，丢弃处理）
        return when {
            // 实例条目（卸装/忘功法入袋）：持完整实例，保真物化回仓库堆叠——
            // 不走模板重建（equipment_instance/manual_instance 不在重建分支，
            // 且模板重建丢实例数据）；仓库满（Failure）保留袋内实例待重试
            eqInstance != null -> inventorySystem.returnEquipmentToStack(eqInstance)
            mnInstance != null -> inventorySystem.returnManualToStack(mnInstance)
            // 堆叠类条目：经 BagItemReconstructor 按数据库模板重建
            // 完整堆叠（minRealm 用条目 stackedData 保真）
            else -> {
                val reconstructed = BagItemReconstructor.reconstruct(currentItem)
                if (reconstructed == null) {
                    null
                } else {
                    when (reconstructed) {
                        is ReconstructedBagStack.Equipment ->
                            inventorySystem.addEquipmentStack(reconstructed.stack.copy(quantity = 1))
                        is ReconstructedBagStack.Manual ->
                            inventorySystem.addManualStack(reconstructed.stack.copy(quantity = 1))
                        is ReconstructedBagStack.Pill ->
                            inventorySystem.addPill(reconstructed.stack.copy(quantity = 1))
                        is ReconstructedBagStack.Herb ->
                            inventorySystem.addHerb(reconstructed.stack.copy(quantity = 1))
                        is ReconstructedBagStack.Seed ->
                            inventorySystem.addSeed(reconstructed.stack.copy(quantity = 1))
                        is ReconstructedBagStack.Material ->
                            inventorySystem.addMaterial(reconstructed.stack.copy(quantity = 1))
                    }
                }
            }
        }
    }

    /** 没收结果落地（confiscateStorageBagItem 拆分）：成功移除袋条目，溢出/失败保留待重试 */
    private fun MutableGameState.applyConfiscationResult(
        disciple: Disciple,
        currentItem: StorageBagItem,
        hasInstance: Boolean,
        result: DomainResult<*>?
    ) {
        when (result) {
            // 模板不存在：仅引用无法重建，物品丢弃（袋条目保留，玩家可再次尝试）
            null -> DomainLog.w(TAG, "没收物品失败：找不到 ${currentItem.name} 的模板")
            // 仓库已入仓，从弟子储物袋移除（实例整条删除，堆叠减 1）
            is DomainResult.Success -> {
                val updatedItems = if (hasInstance) {
                    // 实例条目：整条移除（实例不可分，防 quantity>1 实例重复没收复制）
                    disciple.equipment.storageBagItems.filterNot { it.itemId == currentItem.itemId }
                } else {
                    // 堆叠条目：每次没收 1 个，袋内剩余数量保留
                    StorageBagUtils.decreaseItemQuantity(
                        disciple.equipment.storageBagItems, currentItem.itemId, 1
                    )
                }
                discipleTables.update(disciple.copy(
                    equipment = disciple.equipment.copy(storageBagItems = updatedItems)
                ))
            }
            // 溢出：保留袋内物品，玩家清理后重试补齐（已入仓部分合并不重复）
            is DomainResult.Partial ->
                DomainLog.w(TAG, "没收物品溢出：${currentItem.name} 溢出 ${result.overflow} 个，保留袋内物品待重试")
            // 仓库满：保留袋内物品待重试（C1 防复制）
            is DomainResult.Failure -> {}
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

    /** 在 [stateStore.update] 事务内直接扣减堆叠物品，避免绕过容量守卫的临时 StackableItemStore */
    @Suppress("UNCHECKED_CAST")
    private inline fun <T> MutableGameState.sellStack(
        itemId: String,
        quantity: Int,
        store: EntityStore<T>,
        getBasePrice: (T) -> Int,
        itemType: String
    ): Boolean where T : HasId, T : StackableItem {
        val item = store.get(itemId) ?: return false
        if (item.isLocked || quantity !in 1..item.quantity) return false
        val amount = GameConfig.Rarity.calculateSellPrice(getBasePrice(item), quantity)
        spiritStoneWallet.add(this, amount, SpiritStoneGrade.LOW, SpiritStoneSource.Sell(itemType))
        val newQty = item.quantity - quantity
        if (newQty <= 0) store.remove(itemId) else store.update(itemId) { it.withQuantity(newQty) as T }
        return true
    }

    override suspend fun sellEquipment(equipmentId: String, quantity: Int): Boolean {
        var success = false
        stateStore.update { success = sellStack(equipmentId, quantity, equipmentStacks, { it.basePrice }, "equipment") }
        return success
    }

    override suspend fun sellManual(manualId: String, quantity: Int): Boolean {
        var success = false
        stateStore.update { success = sellStack(manualId, quantity, manualStacks, { it.basePrice }, "manual") }
        return success
    }

    override suspend fun sellPill(pillId: String, quantity: Int): Boolean {
        var success = false
        stateStore.update { success = sellStack(pillId, quantity, pills, { it.basePrice }, "pill") }
        return success
    }

    override suspend fun sellMaterial(materialId: String, quantity: Int): Boolean {
        var success = false
        stateStore.update { success = sellStack(materialId, quantity, materials, { it.basePrice }, "material") }
        return success
    }

    override suspend fun sellHerb(herbId: String, quantity: Int): Boolean {
        var success = false
        stateStore.update { success = sellStack(herbId, quantity, herbs, { it.basePrice }, "herb") }
        return success
    }

    override suspend fun sellSeed(seedId: String, quantity: Int): Boolean {
        var success = false
        stateStore.update { success = sellStack(seedId, quantity, seeds, { it.basePrice }, "seed") }
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

    /** 在 [MutableGameState] 事务内直接扣减堆叠物品（无需临时 StackableItemStore）。 */
    @Suppress("UNCHECKED_CAST")
    private fun <T> MutableGameState.deductStack(
        id: String,
        quantity: Int,
        store: EntityStore<T>,
        getBasePrice: (T) -> Int
    ): Long where T : HasId, T : StackableItem {
        val item = store.get(id) ?: return 0L
        if (item.isLocked || quantity !in 1..item.quantity) return 0L
        val newQty = item.quantity - quantity
        if (newQty <= 0) store.remove(id) else store.update(id) { it.withQuantity(newQty) as T }
        return GameConfig.Rarity.calculateSellPrice(getBasePrice(item), quantity)
    }

    override suspend fun bulkSellItems(operations: List<InventoryFacade.BulkSellOperation>): InventoryFacade.BulkSellResult {
        var totalEarned = 0L
        var soldCount = 0
        val soldItemNames = mutableListOf<String>()
        val failedItemNames = mutableListOf<String>()

        stateStore.update {
            for (op in operations) {
                val earned = when (op.itemType) {
                    "equipment" -> deductStack(op.id, op.quantity, equipmentStacks) { it.basePrice }
                    "manual" -> deductStack(op.id, op.quantity, manualStacks) { it.basePrice }
                    "pill" -> deductStack(op.id, op.quantity, pills) { it.basePrice }
                    "material" -> deductStack(op.id, op.quantity, materials) { it.basePrice }
                    "herb" -> deductStack(op.id, op.quantity, herbs) { it.basePrice }
                    "seed" -> deductStack(op.id, op.quantity, seeds) { it.basePrice }
                    else -> 0L
                }
                if (earned > 0) {
                    totalEarned += earned
                    soldCount++
                    soldItemNames.add("${op.name} ${op.quantity}")
                } else {
                    failedItemNames.add(op.name)
                }
            }
            if (totalEarned > 0) {
                spiritStoneWallet.add(this, totalEarned, SpiritStoneGrade.LOW, SpiritStoneSource.Sell("bulk"))
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
        var itemName = ""; var itemType = ""; var itemRarity = 0
        stateStore.update {
            val merchantItem = gameData.travelingMerchantItems.find { it.id == itemId } ?: run {
                DomainLog.w(TAG, "购买失败:商品不存在 itemId=$itemId")
                return@update
            }
            // D-21 存档完整性防御:商人商品价格本应恒正,篡改档负价/0 价拒绝购买
            if (merchantItem.price <= 0 || quantity <= 0) {
                DomainLog.w(TAG, "购买被拒:非法价格或数量 itemId=$itemId price=${merchantItem.price} qty=$quantity")
                return@update
            }
            val cost = merchantItem.price * quantity
            if (gameData.spiritStones < cost || quantity > merchantItem.quantity) return@update

            // 容量检查（在事务内基于最新状态做只读预测）
            if (!canAddMerchantItem(merchantItem = merchantItem)) return@update

            // ★ 先加物品后扣灵石——统一委托 addXxx（重入事务同一缓冲）；
            // 语义升级：Partial（溢出转邮件）视为成功，灵石照扣，玩家实得全部物品
            val addOk = addMerchantItemToInventory(
                merchantItem = merchantItem,
                quantity = quantity
            )
            // 物品添加完全失败（零合并且仓库满，溢出已转邮件）→ 不扣灵石，不更新商家库存
            if (!addOk) return@update

            // 扣灵石（现在物品已成功添加）
            val deductResult = spiritStoneWallet.deduct(this, cost, SpiritStoneGrade.LOW, SpiritStoneReason.Purchase, SpiritStoneSource.MerchantTrade)
            if (deductResult !is DeductResult.Success) return@update

            // 减少商家库存
            reduceMerchantStock(itemId = itemId, quantity = quantity)
            // 年度报告由 addXxx 按 "merchant" 来源自动累加（删除原手写统计防双计）
            itemName = merchantItem.name
            itemType = merchantItem.type
            itemRarity = merchantItem.rarity
        }

        // 商人物品购买是手动操作，弹出奖励卡片（仅成功购买时）
        if (itemName.isNotEmpty()) {
            stateStore.enqueueRewardCards(listOf(
                RewardCardItem(
                    itemName = itemName,
                    itemType = itemType.lowercase(),
                    rarity = itemRarity.coerceIn(1, 6),
                    quantity = quantity
                )
            ))
        }
    }

    /** 商人商品容量检查（buyMerchantItem 拆分）：基于最新状态做只读预测，灵石/未知类型不占槽位 */
    private fun MutableGameState.canAddMerchantItem(merchantItem: MerchantItem): Boolean =
        when (merchantItem.type.lowercase(java.util.Locale.getDefault())) {
            "equipment" -> {
                val eq = MerchantItemConverter.toEquipment(merchantItem)
                inventorySystem.canAddEquipment(eq.name, eq.rarity, eq.slot)
            }
            "manual" -> {
                val m = MerchantItemConverter.toManual(merchantItem)
                inventorySystem.canAddManual(m.name, m.rarity, m.type)
            }
            "pill" -> {
                val p = MerchantItemConverter.toPill(merchantItem)
                inventorySystem.canAddPill(p.name, p.rarity, p.category, p.grade)
            }
            "material" -> {
                val m = MerchantItemConverter.toMaterial(merchantItem)
                inventorySystem.canAddMaterial(m.name, m.rarity, m.category)
            }
            "herb" -> {
                val h = MerchantItemConverter.toHerb(merchantItem)
                inventorySystem.canAddHerb(h.name, h.rarity, h.category)
            }
            "seed" -> {
                val s = MerchantItemConverter.toSeed(merchantItem)
                inventorySystem.canAddSeed(s.name, s.rarity, s.growTime)
            }
            "spiritstone" -> true // 灵石不占用仓库槽位
            else -> true
        }

    /** 商人商品入库（buyMerchantItem 拆分）：统一委托 addXxx，返回是否全部添加成功 */
    private fun MutableGameState.addMerchantItemToInventory(merchantItem: MerchantItem, quantity: Int): Boolean {
        val addOk = inventorySystem.withTrackingSource("merchant") {
            when (merchantItem.type.lowercase(java.util.Locale.getDefault())) {
                "equipment" -> addMerchantEquipment(merchantItem = merchantItem, quantity = quantity)
                "manual" -> addMerchantManual(merchantItem = merchantItem, quantity = quantity)
                "pill" -> addMerchantPill(merchantItem = merchantItem, quantity = quantity)
                "material" -> addMerchantMaterial(merchantItem = merchantItem, quantity = quantity)
                "herb" -> addMerchantHerb(merchantItem = merchantItem, quantity = quantity)
                "seed" -> addMerchantSeed(merchantItem = merchantItem, quantity = quantity)
                "spiritstone" -> {
                    grantMerchantSpiritStones(merchantItem = merchantItem, quantity = quantity)
                    true
                }
                // 未知类型：原实现不做任何处理（addOk 保持 true，照常扣灵石）
                else -> true
            }
        }
        return addOk
    }

    /** 商人装备入库（addMerchantItemToInventory 拆分） */
    private fun MutableGameState.addMerchantEquipment(merchantItem: MerchantItem, quantity: Int): Boolean {
        val result = inventorySystem.addEquipmentStack(
            MerchantItemConverter.toEquipment(merchantItem).copy(quantity = quantity)
        )
        if (result is DomainResult.Failure) {
            DomainLog.w(TAG, "购买装备失败：${merchantItem.name}")
            return false
        }
        return true
    }

    /** 商人功法入库（addMerchantItemToInventory 拆分） */
    private fun MutableGameState.addMerchantManual(merchantItem: MerchantItem, quantity: Int): Boolean {
        val result = inventorySystem.addManualStack(
            MerchantItemConverter.toManual(merchantItem).copy(quantity = quantity)
        )
        if (result is DomainResult.Failure) {
            DomainLog.w(TAG, "购买功法失败：${merchantItem.name}")
            return false
        }
        return true
    }

    /** 商人丹药入库（addMerchantItemToInventory 拆分） */
    private fun MutableGameState.addMerchantPill(merchantItem: MerchantItem, quantity: Int): Boolean {
        val result = inventorySystem.addPill(
            MerchantItemConverter.toPill(merchantItem).copy(quantity = quantity)
        )
        if (result is DomainResult.Failure) {
            DomainLog.w(TAG, "购买丹药失败：${merchantItem.name}")
            return false
        }
        return true
    }

    /** 商人材料入库（addMerchantItemToInventory 拆分） */
    private fun MutableGameState.addMerchantMaterial(merchantItem: MerchantItem, quantity: Int): Boolean {
        val result = inventorySystem.addMaterial(
            MerchantItemConverter.toMaterial(merchantItem).copy(quantity = quantity)
        )
        if (result is DomainResult.Failure) {
            DomainLog.w(TAG, "购买材料失败：${merchantItem.name}")
            return false
        }
        return true
    }

    /** 商人草药入库（addMerchantItemToInventory 拆分） */
    private fun MutableGameState.addMerchantHerb(merchantItem: MerchantItem, quantity: Int): Boolean {
        val result = inventorySystem.addHerb(
            MerchantItemConverter.toHerb(merchantItem).copy(quantity = quantity)
        )
        if (result is DomainResult.Failure) {
            DomainLog.w(TAG, "购买草药失败：${merchantItem.name}")
            return false
        }
        return true
    }

    /** 商人种子入库（addMerchantItemToInventory 拆分） */
    private fun MutableGameState.addMerchantSeed(merchantItem: MerchantItem, quantity: Int): Boolean {
        val result = inventorySystem.addSeed(
            MerchantItemConverter.toSeed(merchantItem).copy(quantity = quantity)
        )
        if (result is DomainResult.Failure) {
            DomainLog.w(TAG, "购买种子失败：${merchantItem.name}")
            return false
        }
        return true
    }

    /** 商人灵石商品入账（addMerchantItemToInventory 拆分）：中品/上品灵石直加余额 */
    private fun MutableGameState.grantMerchantSpiritStones(merchantItem: MerchantItem, quantity: Int) {
        when (merchantItem.name) {
            "中品灵石" -> gameData = gameData.copy(
                midGradeSpiritStones = gameData.midGradeSpiritStones + quantity
            )
            "上品灵石" -> gameData = gameData.copy(
                highGradeSpiritStones = gameData.highGradeSpiritStones + quantity
            )
        }
    }

    /** 商家库存扣减（buyMerchantItem 拆分） */
    private fun MutableGameState.reduceMerchantStock(itemId: String, quantity: Int) {
        gameData = gameData.copy(
            travelingMerchantItems = gameData.travelingMerchantItems.map { item ->
                if (item.id == itemId) {
                    if (quantity >= item.quantity) null else item.copy(quantity = item.quantity - quantity)
                } else item
            }.filterNotNull()
        )
    }

    override suspend fun sellToMerchant(acquisitionItemId: String, quantity: Int) {
        val acquisitionItem = stateStore.gameData.value.merchantAcquisitionItems.find { it.id == acquisitionItemId } ?: return
        if (isInvalidTradeRequest(acquisitionItemId, quantity, acquisitionItem.price, acquisitionItem.quantity)) return

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
            spiritStoneWallet.add(this, totalPrice, SpiritStoneGrade.LOW, SpiritStoneSource.MerchantTrade)
            gameData = gameData.copy(
                merchantAcquisitionItems = gameData.merchantAcquisitionItems.map { item ->
                    if (item.id == acquisitionItemId) item.copy(quantity = item.quantity - actualQuantity) else item
                }
            )
        }
    }

    /**
     * D-21 存档完整性防御:数量越界或价格非正(篡改档)拒绝收购——
     * 防"先移除仓库物品、后 wallet.add 拒绝入账"致物品丢失。
     * @return true 表示交易请求非法,应拒绝
     */
    private fun isInvalidTradeRequest(
        acquisitionItemId: String, quantity: Int, price: Long, maxQuantity: Int
    ): Boolean {
        if (quantity <= 0 || quantity > maxQuantity || price <= 0) {
            DomainLog.w(TAG, "收购被拒:非法参数 id=$acquisitionItemId price=$price qty=$quantity")
            return true
        }
        return false
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
                    // 设计要求：上架时不扣减仓库数量，物品仍在仓库显示
                    val alreadyListed = gameData.playerListedItems
                        .filter { it.itemId == itemId && it.type == "equipment" }
                        .sumOf { it.quantity }
                    if (alreadyListed + quantity > eqStack.quantity) return@forEach
                    val eqTemplate = EquipmentDatabase.getTemplateByName(eqStack.name)
                    val eqOriginal = eqTemplate?.price ?: GameConfig.Rarity.get(eqStack.rarity).basePrice
                    val eqPrice = (eqOriginal.toDouble() * GameConfig.Rarity.SELL_PRICE_MULTIPLIER).roundToInt().toLong()
                    newItems.add(MerchantItem(id = java.util.UUID.randomUUID().toString(), name = eqStack.name, type = "equipment", itemId = itemId, rarity = eqStack.rarity, price = eqPrice, quantity = quantity))
                    return@forEach
                }
                val manualStack = manualStacks.get(itemId)
                if (manualStack != null && !manualStack.isLocked && quantity in 1..manualStack.quantity) {
                    val alreadyListed = gameData.playerListedItems
                        .filter { it.itemId == itemId && it.type == "manual" }
                        .sumOf { it.quantity }
                    if (alreadyListed + quantity > manualStack.quantity) return@forEach
                    val mTemplate = ManualDatabase.getByName(manualStack.name)
                    val mOriginal = mTemplate?.price ?: GameConfig.Rarity.get(manualStack.rarity).basePrice
                    val mPrice = (mOriginal.toDouble() * GameConfig.Rarity.SELL_PRICE_MULTIPLIER).roundToInt().toLong()
                    newItems.add(MerchantItem(id = java.util.UUID.randomUUID().toString(), name = manualStack.name, type = "manual", itemId = itemId, rarity = manualStack.rarity, price = mPrice, quantity = quantity))
                    return@forEach
                }
                val pill = pills.get(itemId)
                if (pill != null && !pill.isLocked && quantity in 1..pill.quantity) {
                    val alreadyListed = gameData.playerListedItems
                        .filter { it.itemId == itemId && it.type == "pill" }
                        .sumOf { it.quantity }
                    if (alreadyListed + quantity > pill.quantity) return@forEach
                    val pOriginal = GameConfig.Rarity.get(pill.rarity).pillBasePrice * pill.grade.priceMultiplier
                    val pPrice = (pOriginal * GameConfig.Rarity.SELL_PRICE_MULTIPLIER).roundToInt().toLong()
                    newItems.add(MerchantItem(id = java.util.UUID.randomUUID().toString(), name = pill.name, type = "pill", itemId = itemId, rarity = pill.rarity, price = pPrice, quantity = quantity, grade = pill.grade.displayName))
                    return@forEach
                }
            }
            if (newItems.isNotEmpty()) {
                gameData = gameData.copy(playerListedItems = gameData.playerListedItems + newItems)
            }
        }
    }

    override suspend fun removePlayerListedItem(itemId: String) {
        stateStore.update {
            gameData = gameData.copy(
                playerListedItems = gameData.playerListedItems.filter { it.id != itemId }
            )
        }
    }

    // ── Storage bag ──────────────────────────────────────────────────────

    override suspend fun openStorageBag(bagId: String): Pair<List<BattleRewardItem>, List<RewardCardItem>> {
        val rng = gameRngManager.getRng(RngPartition.EXPLORATION)

        // 先读 rarity（锁外快照），用于决定奖励生成参数
        val rarity = stateStore.storageBags.value.find { it.id == bagId }?.rarity
            ?: return Pair(emptyList(), emptyList())

        // 生成奖励（不变更状态，仅生成物品实例）
        val batch = generateStorageBagRewards(rng = rng, rarity = rarity)

        // 单事务原子写入：消耗储物袋 + 发放所有奖励
        // （手动-消耗类路径：统一委托 addXxx，仓库满时溢出自动转邮件，物品不丢失）
        stateStore.update {
            consumeStorageBagAndGrant(bagId = bagId, batch = batch)
        }

        // 储物袋开启是手动操作，展示奖励卡片
        val cards = batch.rewards.map { reward ->
            RewardCardItem(
                itemName = reward.name,
                itemType = reward.type,
                rarity = reward.rarity.coerceIn(1, 6),
                quantity = reward.quantity
            )
        }
        return Pair(batch.rewards, cards)
    }

    /** 储物袋奖励批次（openStorageBag 拆分）：生成期暂存物品，事务内统一入仓 */
    private class StorageBagRewardBatch {
        val rewards = mutableListOf<BattleRewardItem>()
        val equipment = mutableListOf<EquipmentStack>()
        val manuals = mutableListOf<ManualStack>()
        val pills = mutableListOf<Pill>()
        val herbs = mutableListOf<Herb>()
        val seeds = mutableListOf<Seed>()
        val materials = mutableListOf<Material>()
        var spiritStones = 0L
    }

    /** 储物袋奖励生成（openStorageBag 拆分）：不变更状态，仅生成物品实例 */
    private fun generateStorageBagRewards(rng: DeterministicRng, rarity: Int): StorageBagRewardBatch {
        val batch = StorageBagRewardBatch()
        val count = 5 + rng.nextInt(16)
        repeat(count) {
            when (rng.nextInt(7)) {
                0 -> batch.generateEquipmentReward(rarity = rarity)
                1 -> batch.generateManualReward(rarity = rarity)
                2 -> batch.generatePillReward(rarity = rarity)
                3 -> batch.generateHerbReward(rarity = rarity)
                4 -> batch.generateSeedReward(rarity = rarity)
                5 -> batch.generateMaterialReward(rarity = rarity)
                6 -> batch.generateSpiritStoneReward(rarity = rarity)
            }
        }
        return batch
    }

    /** 装备条目（openStorageBag 拆分） */
    private fun StorageBagRewardBatch.generateEquipmentReward(rarity: Int) {
        val stack = EquipmentDatabase.generateRandom(rarity, rarity)
        equipment.add(stack)
        rewards.add(BattleRewardItem(itemId = stack.id, name = stack.name, quantity = 1, rarity = stack.rarity, type = "equipment"))
    }

    /** 功法条目（openStorageBag 拆分） */
    private fun StorageBagRewardBatch.generateManualReward(rarity: Int) {
        if (ManualDatabase.isInitialized) {
            val templates = ManualDatabase.getByRarity(rarity)
            if (templates.isNotEmpty()) {
                val stack = ManualDatabase.createFromTemplate(templates.random())
                manuals.add(stack)
                rewards.add(BattleRewardItem(itemId = stack.id, name = stack.name, quantity = 1, rarity = stack.rarity, type = "manual"))
            }
        }
    }

    /** 丹药条目（openStorageBag 拆分） */
    private fun StorageBagRewardBatch.generatePillReward(rarity: Int) {
        val pill = ItemDatabase.generateRandomPill(rarity, rarity)
        pills.add(pill)
        rewards.add(BattleRewardItem(itemId = pill.id, name = pill.name, quantity = 1, rarity = pill.rarity, type = "pill"))
    }

    /** 草药条目（openStorageBag 拆分） */
    private fun StorageBagRewardBatch.generateHerbReward(rarity: Int) {
        val templates = HerbDatabase.getHerbsByTier(rarity)
        if (templates.isNotEmpty()) {
            val h = templates.random()
            val herb = Herb(id = UUID.randomUUID().toString(), name = h.name, rarity = h.rarity, description = h.description, category = h.category, quantity = 1)
            herbs.add(herb)
            rewards.add(BattleRewardItem(itemId = herb.id, name = herb.name, quantity = 1, rarity = herb.rarity, type = "herb"))
        }
    }

    /** 种子条目（openStorageBag 拆分） */
    private fun StorageBagRewardBatch.generateSeedReward(rarity: Int) {
        val templates = HerbDatabase.getAllSeeds().filter { it.rarity == rarity }
        if (templates.isNotEmpty()) {
            val s = templates.random()
            val seed = Seed(id = UUID.randomUUID().toString(), name = s.name, rarity = s.rarity, description = s.description, growTime = s.growTime, yield = s.yield, quantity = 1)
            seeds.add(seed)
            rewards.add(BattleRewardItem(itemId = seed.id, name = seed.name, quantity = 1, rarity = seed.rarity, type = "seed"))
        }
    }

    /** 材料条目（openStorageBag 拆分） */
    private fun StorageBagRewardBatch.generateMaterialReward(rarity: Int) {
        val mat = ItemDatabase.generateRandomMaterial(rarity, rarity)
        materials.add(mat)
        rewards.add(BattleRewardItem(itemId = mat.id, name = mat.name, quantity = 1, rarity = mat.rarity, type = "material"))
    }

    /** 灵石条目（openStorageBag 拆分）：合并进 spiritStones 奖励卡片 */
    private fun StorageBagRewardBatch.generateSpiritStoneReward(rarity: Int) {
        val amount = StorageBag.SPIRIT_STONE_AMOUNTS.getOrElse(rarity - 1) { 500L }
        spiritStones += amount
        val existing = rewards.find { it.type == "spiritStones" }
        if (existing != null) {
            rewards[rewards.indexOf(existing)] = existing.copy(quantity = existing.quantity + amount.toInt())
        } else {
            rewards.add(BattleRewardItem(name = ItemNames.SPIRIT_STONE, quantity = amount.toInt(), rarity = 1, type = "spiritStones"))
        }
    }

    /** 储物袋消耗与奖励入仓（openStorageBag 拆分）：单事务原子写入 */
    private fun MutableGameState.consumeStorageBagAndGrant(bagId: String, batch: StorageBagRewardBatch) {
        // 消耗袋子
        val bag = storageBags.get(bagId) ?: return
        if (bag.quantity <= 1) storageBags.remove(bagId)
        else storageBags.update(bagId) { it.copy(quantity = it.quantity - 1) }

        // 全部物品统一委托 addXxx（重入事务操作同一缓冲；年度统计由 addXxx 按
        // "storage_bag" 来源自动累加，键格式与原手写一致，删除手写 annual 防双计）
        inventorySystem.withTrackingSource("storage_bag") {
            for (stack in batch.equipment) inventorySystem.addEquipmentStack(stack)
            for (stack in batch.manuals) inventorySystem.addManualStack(stack)
            for (pill in batch.pills) inventorySystem.addPill(pill)
            for (herb in batch.herbs) inventorySystem.addHerb(herb)
            for (seed in batch.seeds) inventorySystem.addSeed(seed)
            for (mat in batch.materials) inventorySystem.addMaterial(mat)
        }
        // 灵石
        if (batch.spiritStones > 0) {
            spiritStoneWallet.add(this, batch.spiritStones, SpiritStoneGrade.LOW, SpiritStoneSource.StorageBag)
        }
    }
}
