package com.xianxia.sect.core.engine.domain.inventory


import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.wallet.SpiritStoneReason
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.wallet.DeductResult
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.rebaselineNativeMirror
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
import com.xianxia.sect.core.model.spiritStones
import com.xianxia.sect.core.model.storageBagItems
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import java.util.UUID
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState

import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.util.StackableItem
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import com.xianxia.sect.core.engine.system.removeEquipment
import com.xianxia.sect.core.engine.system.sortWarehouse
import com.xianxia.sect.core.engine.system.consolidateStacks
import com.xianxia.sect.core.engine.system.returnEquipmentToStack
import com.xianxia.sect.core.engine.system.returnManualToStack
import com.xianxia.sect.core.engine.system.createEquipmentFromMerchantItem
import com.xianxia.sect.core.engine.system.createManualFromMerchantItem
import com.xianxia.sect.core.engine.system.createPillFromMerchantItem
import com.xianxia.sect.core.engine.system.createMaterialFromMerchantItem
import com.xianxia.sect.core.engine.system.createHerbFromMerchantItem
import com.xianxia.sect.core.engine.system.createSeedFromMerchantItem
import com.xianxia.sect.core.engine.system.canAddEquipment
import com.xianxia.sect.core.engine.system.canAddManual
import com.xianxia.sect.core.engine.system.canAddPill
import com.xianxia.sect.core.engine.system.canAddMaterial
import com.xianxia.sect.core.engine.system.canAddHerb
import com.xianxia.sect.core.engine.system.canAddSeed



@Singleton
@Suppress("TooManyFunctions")  // 31 个 override 镜像 InventoryFacade 接口契约下界 + 深耦合成员扩展
// ——TMF 余量为契约骨架，可移动函数已拆出 InventoryFacadeImplApplOps.kt
class InventoryFacadeImpl @Inject constructor(
    override val inventorySystem: InventorySystem,
    internal val stateStore: GameStateStore,
    override val inventoryConfig: InventoryConfig,
    private val gameEngineCore: GameEngineCore,
    private val spiritStoneWallet: SpiritStoneWallet,
    /**
     * RNG 分区管理器（AUTHORITATIVE 委托通道挂载点；Hilt 单例与
     * GameEngine/存档链路同实例）。默认新建仅供测试直构——生产由 Hilt
     * 注入全局单例。
     */
    private val gameRngManager: GameRngManager
) : InventoryFacade {

    /**
     * 出售/上架域 native 事务转发臂（W2-a 写者下沉，转发实现见 [InventoryNativeTx]）。
     * 懒构造——门面构造签名零变化（BuildingFacadeImpl 先例）。
     */
    private val nativeTx by lazy { InventoryNativeTx(gameEngineCore) }

    companion object {
        /**
         * 单用户定向补偿邮件（MailService 扩展，独立文件）。
         *
         * 拆分原因：MailService 类主体接近 detekt LargeClass（800 行）阈值，
         * 补偿邮件属独立运营配置，放独立文件保持 MailService 规模稳定；
         * stateStore/mailRepo 已放宽为 internal 供本扩展读取（三重防护）。
         */
        internal const val TAG = "InventoryFacade"
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

    /**
     * 添加装备堆叠（合并 + 溢出转邮件 + 年度来源追踪）。
     *
     * @param item 待添加的装备堆叠
     */
    override suspend fun addEquipmentStack(stack: EquipmentStack) { inventorySystem.addEquipmentStack(stack) }
    override suspend fun removeEquipment(equipmentId: String): Boolean = inventorySystem.removeEquipment(equipmentId)
    override suspend fun addManualStackToWarehouse(stack: ManualStack) { inventorySystem.addManualStack(stack) }
    override suspend fun addPillToWarehouse(pill: Pill) { inventorySystem.addPill(pill) }
    override suspend fun addMaterialToWarehouse(material: Material) { inventorySystem.addMaterial(material) }
    override suspend fun addHerbToWarehouse(herb: Herb) { inventorySystem.addHerb(herb) }
    override suspend fun addSeedToWarehouse(seed: Seed) { inventorySystem.addSeed(seed) }
    override suspend fun sortWarehouse() = inventorySystem.sortWarehouse()

    override suspend fun consolidateStacks() = inventorySystem.consolidateStacks()

    override suspend fun confiscateStorageBagItem(discipleId: String, item: StorageBagItem) {
        // AUTHORITATIVE 稳态走 C++ 事务（inventory_tx.h）；幂等探测以袋内当前
        // 条目为准（C++ 侧），入参 item 仅取其 itemId（可为陈旧 UI 快照）。
        // 未转发（flag 关/桥未加载/失败信封）→ Kotlin 原路径回退
        if (nativeTx.confiscateStorageBagItem(discipleId, item.itemId) != null) return
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
            // 避免"邮件已发 + 袋内保留"造成物品复制
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

    /** 没收物物化：实例条目保真回仓 / 堆叠条目按模板重建 */
    internal fun MutableGameState.materializeConfiscatedItem(currentItem: StorageBagItem): DomainResult<*>? {
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

    override fun createEquipmentStackFromRecipe(recipe: com.xianxia.sect.core.registry.ForgeRecipeDatabase
        .ForgeRecipe): EquipmentStack =
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
    internal inline fun <T> MutableGameState.sellStack(
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
        // AUTHORITATIVE 稳态走 C++ 事务（inventory_tx.h）；未转发 → Kotlin 原路径
        nativeTx.sellItem("equipment", equipmentId, quantity)?.let { return it }
        var success = false
        stateStore.update { success = sellStack(equipmentId, quantity, equipmentStacks, { it.basePrice }, "equipment") }
        return success
    }

    override suspend fun sellManual(manualId: String, quantity: Int): Boolean {
        nativeTx.sellItem("manual", manualId, quantity)?.let { return it }
        var success = false
        stateStore.update { success = sellStack(manualId, quantity, manualStacks, { it.basePrice }, "manual") }
        return success
    }

    override suspend fun sellPill(pillId: String, quantity: Int): Boolean {
        nativeTx.sellItem("pill", pillId, quantity)?.let { return it }
        var success = false
        stateStore.update { success = sellStack(pillId, quantity, pills, { it.basePrice }, "pill") }
        return success
    }

    override suspend fun sellMaterial(materialId: String, quantity: Int): Boolean {
        nativeTx.sellItem("material", materialId, quantity)?.let { return it }
        var success = false
        stateStore.update { success = sellStack(materialId, quantity, materials, { it.basePrice }, "material") }
        return success
    }

    override suspend fun sellHerb(herbId: String, quantity: Int): Boolean {
        nativeTx.sellItem("herb", herbId, quantity)?.let { return it }
        var success = false
        stateStore.update { success = sellStack(herbId, quantity, herbs, { it.basePrice }, "herb") }
        return success
    }

    override suspend fun sellSeed(seedId: String, quantity: Int): Boolean {
        nativeTx.sellItem("seed", seedId, quantity)?.let { return it }
        var success = false
        stateStore.update { success = sellStack(seedId, quantity, seeds, { it.basePrice }, "seed") }
        return success
    }

    override suspend fun consumeMaterialByName(name: String, rarity: Int, quantity: Int): Boolean {
        nativeTx.consumeMaterialByName(name, rarity, quantity)?.let { return it }
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

    override suspend fun bulkSellItems(operations: List<InventoryFacade.BulkSellOperation>): InventoryFacade
        .BulkSellResult {
        nativeTx.bulkSell(operations)?.let { return it }
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
        // AUTHORITATIVE 稳态走 C++ 事务（inventory_tx.h——判定序/容量预测/
        // 先加后扣/商家库存扣减）；奖励卡片仍由 Kotlin 构造（native 信封
        // 回传 name/type/rarity/quantity）。未转发 → Kotlin 原路径回退
        nativeTx.buyMerchantItem(itemId, quantity, inventorySystem)?.let { result ->
            if (result.bought) {
                stateStore.enqueueRewardCards(listOf(
                    RewardCardItem(
                        itemName = result.itemName,
                        itemType = result.itemType.lowercase(),
                        rarity = result.rarity.coerceIn(1, 6),
                        quantity = result.quantity
                    )
                ))
            }
            return
        }
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
            val deductResult = spiritStoneWallet.deduct(this, cost, SpiritStoneGrade.LOW, SpiritStoneReason.Purchase,
                SpiritStoneSource.MerchantTrade)
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

    /** 商人商品容量检查：基于最新状态做只读预测，灵石/未知类型不占槽位 */
    internal fun MutableGameState.canAddMerchantItem(merchantItem: MerchantItem): Boolean =
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

    /** 商人商品入库：统一委托 addXxx，返回是否全部添加成功 */
    internal fun MutableGameState.addMerchantItemToInventory(merchantItem: MerchantItem, quantity: Int): Boolean {
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

    /** 商人装备入库 */
    internal fun MutableGameState.addMerchantEquipment(merchantItem: MerchantItem, quantity: Int): Boolean {
        val result = inventorySystem.addEquipmentStack(
            MerchantItemConverter.toEquipment(merchantItem).copy(quantity = quantity)
        )
        if (result is DomainResult.Failure) {
            DomainLog.w(TAG, "购买装备失败：${merchantItem.name}")
            return false
        }
        return true
    }

    /** 商人功法入库 */
    internal fun MutableGameState.addMerchantManual(merchantItem: MerchantItem, quantity: Int): Boolean {
        val result = inventorySystem.addManualStack(
            MerchantItemConverter.toManual(merchantItem).copy(quantity = quantity)
        )
        if (result is DomainResult.Failure) {
            DomainLog.w(TAG, "购买功法失败：${merchantItem.name}")
            return false
        }
        return true
    }

    /** 商人丹药入库 */
    internal fun MutableGameState.addMerchantPill(merchantItem: MerchantItem, quantity: Int): Boolean {
        val result = inventorySystem.addPill(
            MerchantItemConverter.toPill(merchantItem).copy(quantity = quantity)
        )
        if (result is DomainResult.Failure) {
            DomainLog.w(TAG, "购买丹药失败：${merchantItem.name}")
            return false
        }
        return true
    }

    /** 商人材料入库 */
    internal fun MutableGameState.addMerchantMaterial(merchantItem: MerchantItem, quantity: Int): Boolean {
        val result = inventorySystem.addMaterial(
            MerchantItemConverter.toMaterial(merchantItem).copy(quantity = quantity)
        )
        if (result is DomainResult.Failure) {
            DomainLog.w(TAG, "购买材料失败：${merchantItem.name}")
            return false
        }
        return true
    }

    /** 商人草药入库 */
    internal fun MutableGameState.addMerchantHerb(merchantItem: MerchantItem, quantity: Int): Boolean {
        val result = inventorySystem.addHerb(
            MerchantItemConverter.toHerb(merchantItem).copy(quantity = quantity)
        )
        if (result is DomainResult.Failure) {
            DomainLog.w(TAG, "购买草药失败：${merchantItem.name}")
            return false
        }
        return true
    }

    /** 商人种子入库 */
    internal fun MutableGameState.addMerchantSeed(merchantItem: MerchantItem, quantity: Int): Boolean {
        val result = inventorySystem.addSeed(
            MerchantItemConverter.toSeed(merchantItem).copy(quantity = quantity)
        )
        if (result is DomainResult.Failure) {
            DomainLog.w(TAG, "购买种子失败：${merchantItem.name}")
            return false
        }
        return true
    }

    override suspend fun sellToMerchant(acquisitionItemId: String, quantity: Int) {
        if (nativeTx.sellToMerchant(acquisitionItemId, quantity) == true) return
        val acquisitionItem = stateStore.gameData.value.merchantAcquisitionItems
            .find { it.id == acquisitionItemId } ?: return
        if (isInvalidTradeRequest(acquisitionItemId, quantity, acquisitionItem.price, acquisitionItem.quantity)) return

        stateStore.update {
            val warehouseQty = warehouseCount(acquisitionItem)
            val actualQuantity = quantity.coerceAtMost(warehouseQty).coerceAtMost(acquisitionItem.quantity)
            if (actualQuantity <= 0) return@update

            // 从仓库移除物品
            deductSoldStock(acquisitionItem, actualQuantity)

            val totalPrice = acquisitionItem.price * actualQuantity
            spiritStoneWallet.add(this, totalPrice, SpiritStoneGrade.LOW, SpiritStoneSource.MerchantTrade)
            gameData = gameData.copy(
                merchantAcquisitionItems = gameData.merchantAcquisitionItems.map { item ->
                    if (item.id == acquisitionItemId) item.copy(quantity = item.quantity - actualQuantity) else item
                }
            )
        }
    }

    /** 出售扣减仓库库存：按物品类型分发——堆叠类扣仓库堆叠，灵石类扣玩家余额 */
    internal fun MutableGameState.deductSoldStock(acquisitionItem: MerchantItem, actualQuantity: Int) {
        when (acquisitionItem.type.lowercase(java.util.Locale.getDefault())) {
            "equipment" -> deductSoldStack(equipmentStacks, acquisitionItem, actualQuantity)
            "manual" -> deductSoldStack(manualStacks, acquisitionItem, actualQuantity)
            "pill" -> deductSoldPillStack(acquisitionItem, actualQuantity)
            "material" -> deductSoldStack(materials, acquisitionItem, actualQuantity)
            "herb" -> deductSoldStack(herbs, acquisitionItem, actualQuantity)
            "seed" -> deductSoldStack(seeds, acquisitionItem, actualQuantity)
            "spiritstone" -> deductSoldSpiritStones(acquisitionItem.name, actualQuantity)
        }
    }

    /**
     * 堆叠类出售扣减：名称+品阶匹配且未锁定的堆叠按列表序扣减，
     * 扣至 0 移除堆叠；[StackableItem.withQuantity] 与原 copy(quantity=) 逐字节等价（均为 copy）。
     */
    @Suppress("UNCHECKED_CAST")
    internal fun <T> MutableGameState.deductSoldStack(
        store: EntityStore<T>,
        acquisitionItem: MerchantItem,
        amount: Int
    ): Unit where T : HasId, T : StackableItem {
        store.replaceAll(removeMatching(store.all(),
            { it.name == acquisitionItem.name && it.rarity == acquisitionItem.rarity && !it.isLocked },
            { it.quantity }, { s, q -> s.withQuantity(q) as T }, amount))
    }

    /** 丹药出售扣减：名称+品阶+品级显示名匹配的未锁定堆叠按序扣减 */
    internal fun MutableGameState.deductSoldPillStack(acquisitionItem: MerchantItem, amount: Int) {
        pills.replaceAll(removeMatching(pills.all(),
            { it.name == acquisitionItem.name && it.rarity == acquisitionItem.rarity && it.grade
                .displayName == (acquisitionItem.grade ?: "") && !it.isLocked },
            { it.quantity }, { p, q -> p.copy(quantity = q) }, amount))
    }

    override suspend fun listItemsToMerchant(items: List<Pair<String, Int>>) {
        if (nativeTx.listItemsToMerchant(items) == true) return
        val newItems = mutableListOf<MerchantItem>()
        stateStore.update {
            items.forEach { (itemId, quantity) ->
                if (listEquipmentForSale(itemId, quantity, newItems)) return@forEach
                if (listManualForSale(itemId, quantity, newItems)) return@forEach
                listPillForSale(itemId, quantity, newItems)
            }
            if (newItems.isNotEmpty()) {
                gameData = gameData.copy(playerListedItems = gameData.playerListedItems + newItems)
            }
        }
    }

    override suspend fun removePlayerListedItem(itemId: String) {
        if (nativeTx.removePlayerListedItem(itemId) == true) return
        stateStore.update {
            gameData = gameData.copy(
                playerListedItems = gameData.playerListedItems.filter { it.id != itemId }
            )
        }
    }

    // ── Storage bag ──────────────────────────────────────────────────────

    /**
     * 开启储物袋（ADR 随机源治理 阶段 1①：抽签归 C++ 真相源）。
     *
     * 抽签（件数 + 逐件种类）走 `EXPLORATION` 分区；**模板选择同样必须显式消费
     * 该分区**（历史上 6 处抽签回落 `Random.Default` 默认实参 ⇒ 同一存档两次开袋
     * 产出不同，不可复现）。两臂同序：
     * - native 臂：C++ `storage_bag_tx.h` 消费分区产出 `draws`，Kotlin 据下标物化
     * - 回退臂：Kotlin 用同一分区 RNG 走 [rollRewardDraws]，产出同序同起点
     *
     * 入库仍走 [consumeStorageBagAndGrant]（13.3 红线：物品发放必须经
     * `InventorySystem.addXxx` 统一入口——`StackableItemStore` 自动合并 +
     * `withTrackingSource` 年度报告 + 溢出转邮件，C++ 侧无该原语）。
     */
    override suspend fun openStorageBag(bagId: String): Pair<List<BattleRewardItem>, List<RewardCardItem>> {
        // 抽签随机流走 EXPLORATION 分区（妖兽移动/关卡生成/掠夺物品同类探索域随机）
        val rng = gameRngManager.getRng(RngPartition.EXPLORATION)

        // 先读 rarity（锁外快照），用于决定奖励生成参数
        val rarity = stateStore.storageBags.value.find { it.id == bagId }?.rarity
            ?: return Pair(emptyList(), emptyList())

        // 抽签：native 优先（C++ 分区真相源）；未转发/失败 → Kotlin 同序抽签
        val draws = nativeTx.drawStorageBag(bagId = bagId, rarity = rarity)
            ?: rollRewardDraws(rng)

        // 生成奖励（不变更状态，仅生成物品实例）
        val batch = generateStorageBagRewardsFromDraws(draws = draws, rarity = rarity, rng = rng)

        // 单事务原子写入：消耗储物袋 + 发放所有奖励
        // （手动-消耗类路径：统一委托 addXxx，仓库满时溢出自动转邮件，物品不丢失）
        // 捕获豁免（updateMirror，§2.80）：开袋写面（钱包/年度账 + 9 类集合）经
        // 尾部基线重建回导 C++（集合段第三段专项前由重建覆盖收敛）
        stateStore.updateMirror {
            consumeStorageBagAndGrant(bagId = bagId, batch = batch)
        }

        // w3-13 通道关闭配套（§2.80）：发放事务为非捕获——开袋后基线重建回导 C++
        gameEngineCore.rebaselineNativeMirror("开袋入库")

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

    /** 储物袋奖励批次：生成期暂存物品，事务内统一入仓 */
    internal class StorageBagRewardBatch {
        val rewards = mutableListOf<BattleRewardItem>()
        val equipment = mutableListOf<EquipmentStack>()
        val manuals = mutableListOf<ManualStack>()
        val pills = mutableListOf<Pill>()
        val herbs = mutableListOf<Herb>()
        val seeds = mutableListOf<Seed>()
        val materials = mutableListOf<Material>()
        var spiritStones = 0L
    }


    /** 储物袋消耗与奖励入仓：单事务原子写入 */
    internal fun MutableGameState.consumeStorageBagAndGrant(bagId: String, batch: StorageBagRewardBatch) {
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

// ── 文件级纯函数助手（类内函数数已超阈值，按纪律落位文件级） ──────────────────

/** 仓库堆叠计数：同名称+品阶堆叠数量求和（纯函数） */
internal fun countWarehouseStacks(stacks: List<StackableItem>, item: MerchantItem): Int =
    stacks.filter { it.name == item.name && it.rarity == item.rarity }.sumOf { it.quantity }

/** 丹药仓库计数：名称+品阶+品级显示名匹配堆叠数量求和（纯函数） */
internal fun countWarehousePills(stacks: List<Pill>, item: MerchantItem): Int =
    stacks.filter {
        it.name == item.name && it.rarity == item.rarity && it.grade.displayName == (item.grade ?: "")
    }.sumOf { it.quantity }

/**
 * 装备上架段。
 * 返回 false 表示该物品不是可上架装备（调用方继续尝试下一类型）；
 * 返回 true 表示该物品已处理完（加入上架列表，或因已上架量超限静默跳过）。
 */
internal fun MutableGameState.listEquipmentForSale(
    itemId: String,
    quantity: Int,
    newItems: MutableList<MerchantItem>
): Boolean {
    val eqStack = equipmentStacks.get(itemId) ?: return false
    if (eqStack.isLocked || quantity !in 1..eqStack.quantity) return false
    // 设计要求：上架时不扣减仓库数量，物品仍在仓库显示
    val alreadyListed = gameData.playerListedItems
        .filter { it.itemId == itemId && it.type == "equipment" }
        .sumOf { it.quantity }
    if (alreadyListed + quantity > eqStack.quantity) return true
    val eqTemplate = EquipmentDatabase.getTemplateByName(eqStack.name)
    val eqOriginal = eqTemplate?.price ?: GameConfig.Rarity.get(eqStack.rarity).basePrice
    val eqPrice = (eqOriginal.toDouble() * GameConfig.Rarity.SELL_PRICE_MULTIPLIER).roundToInt().toLong()
    newItems.add(MerchantItem(id = java.util.UUID.randomUUID().toString(), name = eqStack.name,
        type = "equipment", itemId = itemId, rarity = eqStack.rarity, price = eqPrice, quantity = quantity))
    return true
}

/** 功法上架段：返回值语义同 [listEquipmentForSale] */
internal fun MutableGameState.listManualForSale(
    itemId: String,
    quantity: Int,
    newItems: MutableList<MerchantItem>
): Boolean {
    val manualStack = manualStacks.get(itemId) ?: return false
    if (manualStack.isLocked || quantity !in 1..manualStack.quantity) return false
    val alreadyListed = gameData.playerListedItems
        .filter { it.itemId == itemId && it.type == "manual" }
        .sumOf { it.quantity }
    if (alreadyListed + quantity > manualStack.quantity) return true
    val mTemplate = ManualDatabase.getByName(manualStack.name)
    val mOriginal = mTemplate?.price ?: GameConfig.Rarity.get(manualStack.rarity).basePrice
    val mPrice = (mOriginal.toDouble() * GameConfig.Rarity.SELL_PRICE_MULTIPLIER).roundToInt().toLong()
    newItems.add(MerchantItem(id = java.util.UUID.randomUUID().toString(), name = manualStack.name,
        type = "manual", itemId = itemId, rarity = manualStack.rarity, price = mPrice, quantity = quantity))
    return true
}

/** 丹药上架段：基准价取丹药基准×品阶倍率，返回值语义同 [listEquipmentForSale] */
internal fun MutableGameState.listPillForSale(
    itemId: String,
    quantity: Int,
    newItems: MutableList<MerchantItem>
): Boolean {
    val pill = pills.get(itemId) ?: return false
    if (pill.isLocked || quantity !in 1..pill.quantity) return false
    val alreadyListed = gameData.playerListedItems
        .filter { it.itemId == itemId && it.type == "pill" }
        .sumOf { it.quantity }
    if (alreadyListed + quantity > pill.quantity) return true
    val pOriginal = GameConfig.Rarity.get(pill.rarity).pillBasePrice * pill.grade.priceMultiplier
    val pPrice = (pOriginal * GameConfig.Rarity.SELL_PRICE_MULTIPLIER).roundToInt().toLong()
    newItems.add(MerchantItem(id = java.util.UUID.randomUUID().toString(), name = pill.name,
        type = "pill", itemId = itemId, rarity = pill.rarity, price = pPrice, quantity = quantity,
        grade = pill.grade.displayName))
    return true
}
