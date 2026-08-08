package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.config.GameConfigProvider
import com.xianxia.sect.core.registry.ForgeRecipeDatabase.ForgeRecipe
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.HasId
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.StackKey
import com.xianxia.sect.core.state.StackKeys
import com.xianxia.sect.core.state.StackableItemStore
import com.xianxia.sect.core.util.StackableItem
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MaterialCategory
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.model.PillGrade
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.StorageBag
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.overflow.NoOpOverflowMailHandler
import com.xianxia.sect.core.overflow.OverflowMailDraft
import com.xianxia.sect.core.overflow.OverflowMailHandler
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.AppError
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton
import com.xianxia.sect.core.model.StorageBagItem


// TickSystem: "InventorySystem"
@com.xianxia.sect.core.engine.annotation.GameService("InventorySystem")
@SystemPriority(order = 50)
@Singleton
class InventorySystem @Inject constructor(
    private val stateStore: GameStateStore,
    private val inventoryConfig: InventoryConfig,
    private val spiritStoneWallet: SpiritStoneWallet,
    private val gameConfigProvider: GameConfigProvider,
    private val overflowMailHandler: OverflowMailHandler = NoOpOverflowMailHandler,
) : GameSystem, ItemAdder {

    companion object {
        private const val TAG = "InventorySystem"
        const val SYSTEM_NAME = "InventorySystem"
        private val VALID_RARITY_RANGE = 1..6

        /** 储物袋槽位预算：储物袋不占仓库建筑容量（computeSlotCount 不含 storageBags），
         *  此值仅防止极端情况下堆叠无限增长（6 种稀有度各若干堆）。 */
        private const val STORAGE_BAG_SLOT_BUDGET = 64

        /** 死亡物化年度报告来源（materializeDiscipleBagAndMarkDead 用） */
        const val SOURCE_DISCIPLE_DEATH = "disciple_death"
    }

    /** 年度报告物品来源上下文——引擎单线程安全。在调用 add* 前设置来源 */
    private var trackingSource: String = "unknown"

    /** 溢出转邮件抑制标志——MailService 等"事务回滚"语义路径使用 */
    private var overflowMailSuppressed = false

    /** 在指定 source 上下文中执行 block，用于年度报告物品来源追踪 */
    fun <T> withTrackingSource(source: String, block: () -> T): T {
        val prev = trackingSource
        trackingSource = source
        try { return block() } finally { trackingSource = prev }
    }

    /**
     * 在抑制溢出转邮件的上下文中执行 block。
     *
     * 仅用于"发放失败时整个事务回滚"的路径（如 MailService 领取：Partial 抛异常
     * 回滚，若此时已入队邮件草稿会造成"物品回滚但邮件已发"的双重发放）。
     */
    fun <T> withOverflowMailSuppressed(block: () -> T): T {
        val prev = overflowMailSuppressed
        overflowMailSuppressed = true
        try { return block() } finally { overflowMailSuppressed = prev }
    }

    /**
     * addXxx 的统一溢出收尾（事务外调用）：把未入仓部分转为邮件草稿。
     *
     * 转换规则：
     * - [DomainResult.Partial]：溢出量转邮件（发放类路径——战斗/灵田/开袋等，
     *   物品已生成无凭据可重试，不转即丢失）
     * - [DomainResult.Failure]（仓库满 Full）：全部数量转邮件（同上，
     *   零合并且无空槽时物品全部无法入仓，不转即丢失）
     * - [withOverflowMailSuppressed] 内（凭据类路径——兑换码/宗门等级/
     *   引导/邮件领取）：不转邮件，由调用方拒绝并保留凭据，玩家清理后可重试补齐
     *
     * @param itemType 与 MailAttachment.type 对齐（equipment/manual/pill/...）
     */
    private fun <T> handleOverflowResult(result: DomainResult<T>, itemType: String, item: T) {
        if (overflowMailSuppressed) return
        val stackable = item as? StackableItem ?: return
        val overflowQty = when (result) {
            is DomainResult.Partial -> result.overflow
            is DomainResult.Failure -> {
                if (result.error !is AppError.Domain.Inventory.Full) return
                stackable.quantity
            }
            else -> return
        }
        sendOverflowMail(trackingSource, itemType, stackable.name, stackable.rarity, overflowQty)
    }

    /**
     * 公开溢出转邮件入口（供"state 参数直传"路径使用——如灵田收获直接操作
     * 事务缓冲 state，不走本类 addXxx 时自行处理 Partial/Failure 溢出）。
     *
     * @param source 物品来源（与 withTrackingSource 的 source 值一致）
     * @param itemType 与 MailAttachment.type 对齐
     * @param itemName 物品名称
     * @param rarity 稀有度
     * @param quantity 溢出数量（>0 才发送）
     */
    fun sendOverflowMail(source: String, itemType: String, itemName: String, rarity: Int, quantity: Int) {
        if (overflowMailSuppressed) return
        if (quantity <= 0) return
        overflowMailHandler.sendOverflowMails(listOf(
            OverflowMailDraft(
                slotId = stateStore.gameData.value.currentSlot,
                source = source,
                itemType = itemType,
                itemName = itemName,
                rarity = rarity,
                quantity = quantity
            )
        ))
    }


    private fun getMaxSlots(): Int {
        val buildings = stateStore.gameData.value.placedBuildings
        val warehouseCount = buildings.count {
            it.displayName == BuildingType.WAREHOUSE.displayName
        }
        return gameConfigProvider.warehouse.baseCapacity +
               warehouseCount * gameConfigProvider.warehouse.capacityPerBuilding
    }

    val equipmentStacks: StateFlow<List<EquipmentStack>> get() = stateStore.equipmentStacks
    val equipmentInstances: StateFlow<List<EquipmentInstance>> get() = stateStore.equipmentInstances
    val manualStacks: StateFlow<List<ManualStack>> get() = stateStore.manualStacks
    val manualInstances: StateFlow<List<ManualInstance>> get() = stateStore.manualInstances
    val pills: StateFlow<List<Pill>> get() = stateStore.pills
    val materials: StateFlow<List<Material>> get() = stateStore.materials
    val herbs: StateFlow<List<Herb>> get() = stateStore.herbs
    val seeds: StateFlow<List<Seed>> get() = stateStore.seeds
    val storageBags: StateFlow<List<StorageBag>> get() = stateStore.storageBags

    override val systemName: String = SYSTEM_NAME

    override fun initialize() {
        DomainLog.d(TAG, "InventorySystem initialized")
    }

    override fun release() {
        DomainLog.d(TAG, "InventorySystem released")
    }

    override fun clear() {
        stateStore.update {
            equipmentStacks = EntityStore(emptyList())
            equipmentInstances = EntityStore(emptyList())
            manualStacks = EntityStore(emptyList())
            manualInstances = EntityStore(emptyList())
            pills = EntityStore(emptyList())
            materials = EntityStore(emptyList())
            herbs = EntityStore(emptyList())
            seeds = EntityStore(emptyList())
        }
    }

    override fun clearForSlot(slotId: Int) {
        clear()
    }

    fun loadInventory(
        equipmentStacksList: List<EquipmentStack>,
        equipmentInstancesList: List<EquipmentInstance>,
        manualStacksList: List<ManualStack>,
        manualInstancesList: List<ManualInstance>,
        pillsList: List<Pill>,
        materialsList: List<Material>,
        herbsList: List<Herb>,
        seedsList: List<Seed>
    ) {
        stateStore.update {
            equipmentStacks.replaceAll(equipmentStacksList)
            equipmentInstances.replaceAll(equipmentInstancesList)
            manualStacks.replaceAll(manualStacksList)
            manualInstances.replaceAll(manualInstancesList)
            pills.replaceAll(pillsList)
            materials.replaceAll(materialsList)
            herbs.replaceAll(herbsList)
            seeds.replaceAll(seedsList)
        }
    }

    private fun validateQuantity(quantity: Int, name: String = "quantity"): Boolean {
        if (quantity <= 0) {
            DomainLog.w(TAG, "Invalid $name: $quantity, must be positive")
            return false
        }
        return true
    }

    private fun logWarning(msg: String) = DomainLog.w(TAG, msg)

    private fun getMaxStackForType(type: String): Int = inventoryConfig.getMaxStackSize(type)

    // ── StackableItemStore 统一合并键（单一事实来源见 StackKeys，根除 6 套不一致）──

    /** 通用 getById */
    private fun <T : HasId> getById(items: List<T>, id: String): T? = items.find { it.id == id }

    /** 通用 getQuantity */
    private fun <T> getQuantity(items: List<T>, id: String): Int where T : StackableItem =
        (items.find { (it as HasId).id == id })?.quantity ?: 0

    private fun currentEquipmentStacks(): List<EquipmentStack> = stateStore.equipmentStacks.value

    private fun currentEquipmentInstances(): List<EquipmentInstance> = stateStore.equipmentInstances.value

    private fun currentManualStacks(): List<ManualStack> = stateStore.manualStacks.value

    private fun currentManualInstances(): List<ManualInstance> = stateStore.manualInstances.value

    private fun currentPills(): List<Pill> = stateStore.pills.value

    private fun currentMaterials(): List<Material> = stateStore.materials.value

    private fun currentHerbs(): List<Herb> = stateStore.herbs.value

    private fun currentSeeds(): List<Seed> = stateStore.seeds.value

    fun getCapacityInfo(): CapacityInfo = inventoryCapacityInfo(stateStore)

    fun canAddItem(): Boolean = inventoryCanAddItem(stateStore)

    /** 在 MutableGameState 事务内检查是否有空余槽位（读到事务内最新状态）。 */
    fun canAddItemInTransaction(state: MutableGameState): Boolean =
        state.computeSlotCount() < state.computeMaxSlots()

    fun canAddItems(count: Int): Boolean = inventoryCanAddItems(stateStore, count)

    fun canAddEquipment(name: String, rarity: Int, slot: EquipmentSlot): Boolean {
        val current = stateStore.equipmentStacks.value
        val maxStack = getMaxStackForType("equipment_stack")
        val totalFree = current.filter { it.name == name && it.rarity == rarity && it.slot == slot }
            .sumOf { maxStack - it.quantity }
        return totalFree > 0 || canAddItem()
    }

    fun canAddPill(name: String, rarity: Int, category: PillCategory, grade: PillGrade = PillGrade.MEDIUM): Boolean {
        val current = stateStore.pills.value
        val maxStack = getMaxStackForType("pill")
        val totalFree = current.filter { it.name == name && it.rarity == rarity && it.category == category && it.grade == grade }
            .sumOf { maxStack - it.quantity }
        return totalFree > 0 || canAddItem()
    }

    fun canAddManual(name: String, rarity: Int, type: ManualType): Boolean {
        val current = stateStore.manualStacks.value
        val maxStack = getMaxStackForType("manual_stack")
        val totalFree = current.filter { it.name == name && it.rarity == rarity && it.type == type }
            .sumOf { maxStack - it.quantity }
        return totalFree > 0 || canAddItem()
    }

    fun canAddMaterial(name: String, rarity: Int, category: MaterialCategory): Boolean {
        val current = stateStore.materials.value
        val maxStack = getMaxStackForType("material")
        val totalFree = current.filter { it.name == name && it.rarity == rarity && it.category == category }
            .sumOf { maxStack - it.quantity }
        return totalFree > 0 || canAddItem()
    }

    fun canAddHerb(name: String, rarity: Int, category: String): Boolean {
        val current = stateStore.herbs.value
        val maxStack = getMaxStackForType("herb")
        val totalFree = current.filter { it.name == name && it.rarity == rarity && it.category == category }
            .sumOf { maxStack - it.quantity }
        return totalFree > 0 || canAddItem()
    }

    fun canAddSeed(name: String, rarity: Int, growTime: Int): Boolean {
        val current = stateStore.seeds.value
        val maxStack = getMaxStackForType("seed")
        val totalFree = current.filter { it.name == name && it.rarity == rarity && it.growTime == growTime }
            .sumOf { maxStack - it.quantity }
        return totalFree > 0 || canAddItem()
    }

    private fun validateStackableItem(name: String, rarity: Int, quantity: Int): DomainResult<Unit> {
        if (name.isBlank()) return DomainResult.Failure(AppError.Domain.Inventory.InvalidName())
        if (rarity !in VALID_RARITY_RANGE) return DomainResult.Failure(AppError.Domain.Inventory.InvalidRarity(rarity))
        if (quantity <= 0) return DomainResult.Failure(AppError.Domain.Inventory.InvalidQuantity(quantity))
        return DomainResult.Success(Unit)
    }

    /**
     * 添加装备堆叠（合并 + 溢出转邮件 + 年度来源追踪）。
     *
     * @param item 待添加的装备堆叠
     */
    override fun addEquipmentStack(item: EquipmentStack): DomainResult<EquipmentStack> {
        val validation = validateStackableItem(item.name, item.rarity, item.quantity)
        if (validation is DomainResult.Failure) return validation

        return stateStore.updateAndReturn {
            val otherTypes = manualStacks.size + pills.size + materials.size + herbs.size + seeds.size
            val store = StackableItemStore(
                initialItems = equipmentStacks.all(),
                stackKeyOf = StackKeys::equipment,
                maxStack = getMaxStackForType("equipment_stack"),
                maxSlots = { computeMaxSlots() - otherTypes },
                notFound = { AppError.Domain.Inventory.NotFound(it) }
            )
            val result = store.add(item)
            equipmentStacks.replaceAll(store.all())
            when (result) {
                is DomainResult.Success -> {
                    val srcKey = "$trackingSource:${item.rarity}"
                    gameData = gameData.copy(
                        annualEquipmentBySource = gameData.annualEquipmentBySource + (srcKey to (gameData.annualEquipmentBySource[srcKey] ?: 0) + item.quantity)
                    )
                }
                is DomainResult.Partial -> {
                    val actualAdded = item.quantity - result.overflow
                    val srcKey = "$trackingSource:${item.rarity}"
                    gameData = gameData.copy(
                        annualEquipmentBySource = gameData.annualEquipmentBySource + (srcKey to (gameData.annualEquipmentBySource[srcKey] ?: 0) + actualAdded)
                    )
                }
                is DomainResult.Failure -> { }
            }
            handleOverflowResult(result, "equipment", item)
            result
        }
    }

    override fun addEquipmentInstance(item: EquipmentInstance): DomainResult<EquipmentInstance> {
        if (item.id.isBlank()) return DomainResult.Failure(AppError.Domain.Inventory.NotFound(item.id))
        if (item.name.isBlank()) return DomainResult.Failure(AppError.Domain.Inventory.InvalidName())
        if (item.rarity !in VALID_RARITY_RANGE) return DomainResult.Failure(AppError.Domain.Inventory.InvalidRarity(item.rarity))

        return stateStore.updateAndReturn {
            if (equipmentInstances.any { it.id == item.id }) {
                return@updateAndReturn DomainResult.Failure(AppError.Domain.Inventory.DuplicateId(item.id))
            }
            equipmentInstances = equipmentInstances + item
            DomainResult.Success(item)
        }
    }

    /**
     * 添加功法堆叠（合并 + 溢出转邮件 + 年度来源追踪）。
     *
     * @param item 待添加的功法堆叠
     * @param merge 是否尝试合并（默认 true）
     */
    override fun addManualStack(
        item: ManualStack,
        merge: Boolean
    ): DomainResult<ManualStack> {
        val validation = validateStackableItem(item.name, item.rarity, item.quantity)
        if (validation is DomainResult.Failure) return validation

        return stateStore.updateAndReturn {
            val otherTypes = equipmentStacks.size + pills.size + materials.size + herbs.size + seeds.size
            val store = StackableItemStore(
                initialItems = manualStacks.all(),
                stackKeyOf = StackKeys::manual,
                maxStack = getMaxStackForType("manual_stack"),
                maxSlots = { computeMaxSlots() - otherTypes },
                notFound = { AppError.Domain.Inventory.NotFound(it) }
            )
            val result = store.add(item, merge = merge)
            manualStacks.replaceAll(store.all())
            handleOverflowResult(result, "manual", item)
            result
        }
    }

    override fun addManualInstance(item: ManualInstance): DomainResult<ManualInstance> {
        if (item.id.isBlank()) return DomainResult.Failure(AppError.Domain.Inventory.NotFound(item.id))
        if (item.name.isBlank()) return DomainResult.Failure(AppError.Domain.Inventory.InvalidName())
        if (item.rarity !in VALID_RARITY_RANGE) return DomainResult.Failure(AppError.Domain.Inventory.InvalidRarity(item.rarity))

        return stateStore.updateAndReturn {
            if (manualInstances.any { it.id == item.id }) {
                return@updateAndReturn DomainResult.Failure(AppError.Domain.Inventory.DuplicateId(item.id))
            }
            manualInstances = manualInstances + item
            DomainResult.Success(item)
        }
    }

    fun returnEquipmentToStack(instance: EquipmentInstance): DomainResult<EquipmentStack> {
        return stateStore.updateAndReturn {
            val otherTypes = manualStacks.size + pills.size + materials.size + herbs.size + seeds.size
            val store = StackableItemStore(
                initialItems = equipmentStacks.all(),
                stackKeyOf = StackKeys::equipment,
                maxStack = getMaxStackForType("equipment_stack"),
                maxSlots = { computeMaxSlots() - otherTypes },
                notFound = { AppError.Domain.Inventory.NotFound(it) }
            )
            val item = instance.toStack(quantity = 1)
            val result = store.add(item)
            equipmentStacks.replaceAll(store.all())
            handleOverflowResult(result, "equipment", item)
            result
        }
    }

    /**
     * D-03：袋条目物化回仓库（发放类——溢出自动转邮件，物品不丢）。
     *
     * 调用时机：弟子死亡/逐出时，袋物品物化回仓库（玩家保留物品，不随弟子消失）。
     * 调用方在 [stateStore.update] 事务内调用（本方法经 updateAndReturn 重入同一缓冲）。
     * 调用方包裹 [withTrackingSource] 归因年度报告。
     *
     * - 实例条目（equipment_instance/manual_instance）：[returnEquipmentToStack]/
     *   [returnManualToStack] 完整保真（含 nurture）
     * - 堆叠条目（equipment_stack/manual_stack/pill/material/herb/seed）：模板重建
     *   （[BagItemReconstructor]，minRealm/quantity 条目保真）
     * - 模板缺失：丢弃 + 日志（无法重建，不阻塞删除流程）
     * - 未物化条目（payload 空）：忽略（读档物化器已处理，运行期不应出现）
     *
     * @return 物化成功的条目数（供日志）
     */
    fun materializeBagItemsToWarehouse(items: List<StorageBagItem>): Int =
        items.count { materializeSingleBagItem(it) }

    /** D-17 单个袋条目物化（materializeBagItemsToWarehouse 拆分）。@return 是否物化成功 */
    private fun materializeSingleBagItem(item: StorageBagItem): Boolean {
        // 跨模块 public API 属性不能 smart cast，先取局部变量
        val eqInstance = item.equipmentInstance
        val mnInstance = item.manualInstance
        return when {
            eqInstance != null -> materializeEquipmentInstance(eqInstance, item)
            mnInstance != null -> materializeManualInstance(mnInstance, item)
            item.stackedData != null -> materializeStackedItem(item)
            // 未物化条目（payload 空）：忽略
            else -> false
        }
    }

    /** D-17 装备实例物化（materializeBagItemsToWarehouse 拆分）：入仓成功或溢出转邮件即完成 */
    private fun materializeEquipmentInstance(
        eqInstance: EquipmentInstance,
        item: StorageBagItem
    ): Boolean {
        val result = returnEquipmentToStack(eqInstance)
        // 物化完成判据：入仓成功（Success/Partial）或仓库满已转邮件（Failure Full——
        // handleOverflowResult 已把物品转邮件，实例删除防复制）；其他失败保留实例
        val completed = result is DomainResult.Success || result is DomainResult.Partial ||
            (result is DomainResult.Failure && result.error is AppError.Domain.Inventory.Full)
        if (completed) {
            // 防双持有：实例已物化入栈（或溢出转邮件），从实例表删除——
            // 外层（死亡/逐出）大事务包裹本方法，回滚时删除一并回滚，原子性由外层保证
            stateStore.update { equipmentInstances = equipmentInstances.filter { it.id != eqInstance.id } }
        } else {
            DomainLog.w(TAG, "物化装备失败 ${item.name}: ${(result as? DomainResult.Failure)?.error}")
        }
        return completed
    }

    /** D-17 功法实例物化（materializeBagItemsToWarehouse 拆分）：入仓成功或溢出转邮件即完成 */
    private fun materializeManualInstance(
        mnInstance: ManualInstance,
        item: StorageBagItem
    ): Boolean {
        val result = returnManualToStack(mnInstance)
        val completed = result is DomainResult.Success || result is DomainResult.Partial ||
            (result is DomainResult.Failure && result.error is AppError.Domain.Inventory.Full)
        if (completed) {
            // 防双持有：同装备分支
            stateStore.update { manualInstances = manualInstances.filter { it.id != mnInstance.id } }
        } else {
            DomainLog.w(TAG, "物化功法失败 ${item.name}: ${(result as? DomainResult.Failure)?.error}")
        }
        return completed
    }

    /** D-17 堆叠条目物化前置（materializeStackedItem 拆分）：数量合法性检查 + 模板重建 */
    private fun reconstructStackedItem(item: StorageBagItem): ReconstructedBagStack? {
        // 篡改防御：堆叠条目数量非法（<=0）拒绝物化——防 0/负数量白得物品
        if (item.quantity <= 0) {
            DomainLog.w(TAG, "物化失败（数量非法 quantity=${item.quantity}，跳过）：${item.name}")
            return null
        }
        val reconstructed = BagItemReconstructor.reconstruct(item)
        if (reconstructed == null) {
            DomainLog.w(TAG, "物化失败（无模板，随弟子删除）：${item.name}")
        }
        return reconstructed
    }

    /** D-17 堆叠条目模板重建物化（materializeBagItemsToWarehouse 拆分） */
    private fun materializeStackedItem(item: StorageBagItem): Boolean {
        val reconstructed = reconstructStackedItem(item) ?: return false
        val result = when (reconstructed) {
            is ReconstructedBagStack.Equipment -> addEquipmentStack(reconstructed.stack)
            is ReconstructedBagStack.Manual -> addManualStack(reconstructed.stack)
            is ReconstructedBagStack.Pill -> addPill(reconstructed.stack)
            is ReconstructedBagStack.Herb -> addHerb(reconstructed.stack)
            is ReconstructedBagStack.Seed -> addSeed(reconstructed.stack)
            is ReconstructedBagStack.Material -> addMaterial(reconstructed.stack)
        }
        val completed = result is DomainResult.Success || result is DomainResult.Partial
        if (!completed) {
            DomainLog.w(TAG, "物化失败 ${item.name}: ${(result as? DomainResult.Failure)?.error}")
        }
        return completed
    }

    /**
     * D-03 死亡统一入口：袋物品物化回仓库（玩家保留，溢出自动转邮件）→ 清空袋条目
     * （幂等）→ 标记死亡。必须在 [stateStore.update] 事务内调用（与 markDead 同事务，
     * 防"死亡已标记但袋物品未物化"窗口导致物品随死弟子记录 cull 永久丢失）。
     *
     * 所有死亡标记路径（宗门战/世界战斗/侦查/探索队/秘境/寿元）统一经此入口。
     * 物化后清空袋条目：重复死亡处理不重复物化（防物品复制）。
     *
     * @param state 事务内 MutableGameState（调用方在 stateStore.update 中传入 this）
     * @param discipleId 死弟子 id
     * @param deathYear 死亡年份
     * @param cause 死亡原因（与 DiscipleTables.markDead 的 cause 对齐：battle/scout/exploration/...）
     */
    fun materializeDiscipleBagAndMarkDead(
        state: MutableGameState,
        discipleId: Int,
        deathYear: Int,
        cause: String
    ) {
        val bagItems = state.discipleTables.storageBagItems.getOrNull(discipleId)
        if (!bagItems.isNullOrEmpty()) {
            withTrackingSource(SOURCE_DISCIPLE_DEATH) {
                materializeBagItemsToWarehouse(bagItems)
            }
            // 幂等：清空袋条目——重复死亡处理不重复物化
            state.discipleTables.storageBagItems[discipleId] = emptyList()
        }
        state.discipleTables.markDead(discipleId, deathYear, cause)
    }

    fun returnManualToStack(instance: ManualInstance): DomainResult<ManualStack> {
        return stateStore.updateAndReturn {
            val otherTypes = equipmentStacks.size + pills.size + materials.size + herbs.size + seeds.size
            val store = StackableItemStore(
                initialItems = manualStacks.all(),
                stackKeyOf = StackKeys::manual,
                maxStack = getMaxStackForType("manual_stack"),
                maxSlots = { computeMaxSlots() - otherTypes },
                notFound = { AppError.Domain.Inventory.NotFound(it) }
            )
            val item = instance.toStack(quantity = 1)
            val result = store.add(item)
            manualStacks.replaceAll(store.all())
            handleOverflowResult(result, "manual", item)
            result
        }
    }

    fun removeEquipment(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        if (quantity <= 0) return false
        return stateStore.updateAndReturn {
            val existing = equipmentStacks.find { it.id == id } ?: return@updateAndReturn false
            if (!bypassLock && existing.isLocked) {
                logWarning("Cannot remove locked equipment: ${existing.hashCode()}")
                return@updateAndReturn false
            }
            if (existing.quantity < quantity) {
                logWarning("Cannot remove $quantity equipment, only ${existing.quantity} available")
                return@updateAndReturn false
            }
            var removed = false
            equipmentStacks = equipmentStacks.mapNotNull { item ->
                if (item.id == id && !removed) {
                    val newQty = item.quantity - quantity
                    when {
                        newQty < 0 -> item
                        newQty == 0 -> { removed = true; null }
                        else -> { removed = true; item.copy(quantity = newQty) }
                    }
                } else item
            }
            true
        }
    }

    fun removeEquipmentInstance(id: String): Boolean {
        return stateStore.updateAndReturn {
            val oldSize = equipmentInstances.size
            equipmentInstances = equipmentInstances.filter { it.id != id }
            equipmentInstances.size < oldSize
        }
    }

    fun updateEquipmentStack(id: String, transform: (EquipmentStack) -> EquipmentStack): Boolean {
        return stateStore.updateAndReturn {
            var found = false
            equipmentStacks = equipmentStacks.map {
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
            found
        }
    }

    fun updateEquipmentInstance(id: String, transform: (EquipmentInstance) -> EquipmentInstance): Boolean {
        return stateStore.updateAndReturn {
            var found = false
            equipmentInstances = equipmentInstances.map {
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
            found
        }
    }

    fun getEquipmentStackById(id: String): EquipmentStack? = getById(currentEquipmentStacks(), id)
    fun getEquipmentInstanceById(id: String): EquipmentInstance? = getById(currentEquipmentInstances(), id)

    fun removeManual(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        if (quantity <= 0) return false
        return stateStore.updateAndReturn {
            val existing = manualStacks.find { it.id == id } ?: return@updateAndReturn false
            if (!bypassLock && existing.isLocked) {
                logWarning("Cannot remove locked manual: ${existing.hashCode()}")
                return@updateAndReturn false
            }
            if (existing.quantity < quantity) {
                logWarning("Cannot remove $quantity manual, only ${existing.quantity} available")
                return@updateAndReturn false
            }
            var removed = false
            manualStacks = manualStacks.mapNotNull { item ->
                if (item.id == id && !removed) {
                    val newQty = item.quantity - quantity
                    when {
                        newQty < 0 -> item
                        newQty == 0 -> { removed = true; null }
                        else -> { removed = true; item.copy(quantity = newQty) }
                    }
                } else item
            }
            true
        }
    }

    fun removeManualInstance(id: String): Boolean {
        return stateStore.updateAndReturn {
            val oldSize = manualInstances.size
            manualInstances = manualInstances.filter { it.id != id }
            manualInstances.size < oldSize
        }
    }

    fun updateManualStack(id: String, transform: (ManualStack) -> ManualStack): Boolean {
        return stateStore.updateAndReturn {
            var found = false
            manualStacks = manualStacks.map {
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
            found
        }
    }

    fun updateManualInstance(id: String, transform: (ManualInstance) -> ManualInstance): Boolean {
        return stateStore.updateAndReturn {
            var found = false
            manualInstances = manualInstances.map {
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
            found
        }
    }

    fun getManualStackById(id: String): ManualStack? = getById(currentManualStacks(), id)
    fun getManualInstanceById(id: String): ManualInstance? = getById(currentManualInstances(), id)

    fun addPill(item: Pill, merge: Boolean = true): DomainResult<Pill> {
        val validation = validateStackableItem(item.name, item.rarity, item.quantity)
        if (validation is DomainResult.Failure) return validation

        return stateStore.updateAndReturn {
            val otherTypes = equipmentStacks.size + manualStacks.size + materials.size + herbs.size + seeds.size
            val store = StackableItemStore(
                initialItems = pills.all(),
                stackKeyOf = StackKeys::pill,
                maxStack = getMaxStackForType("pill"),
                maxSlots = { computeMaxSlots() - otherTypes },
                notFound = { AppError.Domain.Inventory.NotFound(it) }
            )
            val result = store.add(item, merge = merge)
            pills.replaceAll(store.all())
            when (result) {
                is DomainResult.Success -> {
                    val pillGrade = item.grade?.name ?: "LOW"
                    val srcKey = "$trackingSource:$pillGrade"
                    gameData = gameData.copy(
                        annualPillBySource = gameData.annualPillBySource + (srcKey to (gameData.annualPillBySource[srcKey] ?: 0) + item.quantity)
                    )
                }
                is DomainResult.Partial -> {
                    val actualAdded = item.quantity - result.overflow
                    val pillGrade = item.grade?.name ?: "LOW"
                    val srcKey = "$trackingSource:$pillGrade"
                    gameData = gameData.copy(
                        annualPillBySource = gameData.annualPillBySource + (srcKey to (gameData.annualPillBySource[srcKey] ?: 0) + actualAdded)
                    )
                }
                is DomainResult.Failure -> { }
            }
            handleOverflowResult(result, "pill", item)
            result
        }
    }

    fun removePill(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        if (quantity <= 0) return false
        return stateStore.updateAndReturn {
            val existing = pills.find { it.id == id } ?: return@updateAndReturn false
            if (!bypassLock && existing.isLocked) {
                logWarning("Cannot remove locked pill: ${existing.hashCode()}")
                return@updateAndReturn false
            }
            if (existing.quantity < quantity) {
                logWarning("Cannot remove $quantity pill, only ${existing.quantity} available")
                return@updateAndReturn false
            }
            var removed = false
            pills = pills.mapNotNull { item ->
                if (item.id == id && !removed) {
                    val newQty = item.quantity - quantity
                    when {
                        newQty < 0 -> item
                        newQty == 0 -> { removed = true; null }
                        else -> { removed = true; item.copy(quantity = newQty) }
                    }
                } else item
            }
            true
        }
    }

    fun removePillByName(name: String, rarity: Int, quantity: Int = 1, bypassLock: Boolean = false, grade: PillGrade? = null): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        val existing = currentPills().find {
            it.name == name && it.rarity == rarity && (grade == null || it.grade == grade)
        }
            ?: return false
        if (!bypassLock && existing.isLocked) {
            logWarning("Cannot remove locked pill: ${existing.name}")
            return false
        }
        if (existing.quantity < quantity) {
            logWarning("Cannot remove $quantity items '$name', only ${existing.quantity} available")
            return false
        }

        return stateStore.updateAndReturn {
            var removed = false
            pills = pills.mapNotNull { pill ->
                if (pill.id == existing.id && !removed) {
                    val newQty = pill.quantity - quantity
                    when {
                        newQty < 0 -> {
                            logWarning("Cannot remove $quantity items, only ${pill.quantity} available")
                            pill
                        }
                        newQty == 0 -> {
                            removed = true
                            null
                        }
                        else -> {
                            removed = true
                            pill.copy(quantity = newQty)
                        }
                    }
                } else pill
            }
            removed
        }
    }

    fun updatePill(id: String, transform: (Pill) -> Pill): Boolean {
        return stateStore.updateAndReturn {
            var found = false
            pills = pills.map {
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
            found
        }
    }

    fun getPillById(id: String): Pill? = getById(currentPills(), id)
    fun getPillQuantity(id: String): Int = getQuantity(currentPills(), id)

    fun hasPill(name: String, rarity: Int, quantity: Int = 1, grade: PillGrade? = null): Boolean {
        val item = currentPills().find {
            it.name == name && it.rarity == rarity && (grade == null || it.grade == grade)
        } ?: return false
        return item.quantity >= quantity
    }

    fun addMaterial(item: Material, merge: Boolean = true): DomainResult<Material> {
        val validation = validateStackableItem(item.name, item.rarity, item.quantity)
        if (validation is DomainResult.Failure) return validation

        return stateStore.updateAndReturn {
            val otherTypes = equipmentStacks.size + manualStacks.size + pills.size + herbs.size + seeds.size
            val store = StackableItemStore(
                initialItems = materials.all(),
                stackKeyOf = StackKeys::material,
                maxStack = getMaxStackForType("material"),
                maxSlots = { computeMaxSlots() - otherTypes },
                notFound = { AppError.Domain.Inventory.NotFound(it) }
            )
            val result = store.add(item, merge = merge)
            materials.replaceAll(store.all())
            handleOverflowResult(result, "material", item)
            result
        }
    }

    fun removeMaterial(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        if (quantity <= 0) return false
        return stateStore.updateAndReturn {
            val existing = materials.find { it.id == id } ?: return@updateAndReturn false
            if (!bypassLock && existing.isLocked) {
                logWarning("Cannot remove locked material: ${existing.hashCode()}")
                return@updateAndReturn false
            }
            if (existing.quantity < quantity) {
                logWarning("Cannot remove $quantity material, only ${existing.quantity} available")
                return@updateAndReturn false
            }
            var removed = false
            materials = materials.mapNotNull { item ->
                if (item.id == id && !removed) {
                    val newQty = item.quantity - quantity
                    when {
                        newQty < 0 -> item
                        newQty == 0 -> { removed = true; null }
                        else -> { removed = true; item.copy(quantity = newQty) }
                    }
                } else item
            }
            true
        }
    }

    fun removeMaterialByName(name: String, rarity: Int, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        val existing = currentMaterials().find { it.name == name && it.rarity == rarity }
            ?: return false
        if (!bypassLock && existing.isLocked) {
            logWarning("Cannot remove locked material: ${existing.name}")
            return false
        }
        if (existing.quantity < quantity) {
            logWarning("Cannot remove $quantity items '$name', only ${existing.quantity} available")
            return false
        }

        return stateStore.updateAndReturn {
            var removed = false
            materials = materials.mapNotNull { material ->
                if (material.id == existing.id && !removed) {
                    val newQty = material.quantity - quantity
                    when {
                        newQty < 0 -> {
                            logWarning("Cannot remove $quantity items, only ${material.quantity} available")
                            material
                        }
                        newQty == 0 -> {
                            removed = true
                            null
                        }
                        else -> {
                            removed = true
                            material.copy(quantity = newQty)
                        }
                    }
                } else material
            }
            removed
        }
    }

    fun updateMaterial(id: String, transform: (Material) -> Material): Boolean {
        return stateStore.updateAndReturn {
            var found = false
            materials = materials.map {
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
            found
        }
    }

    fun getMaterialById(id: String): Material? = getById(currentMaterials(), id)
    fun getMaterialQuantity(id: String): Int = getQuantity(currentMaterials(), id)

    fun hasMaterial(name: String, rarity: Int, quantity: Int = 1): Boolean {
        val item = currentMaterials().find { it.name == name && it.rarity == rarity } ?: return false
        return item.quantity >= quantity
    }

    fun addHerb(item: Herb, merge: Boolean = true): DomainResult<Herb> {
        val validation = validateStackableItem(item.name, item.rarity, item.quantity)
        if (validation is DomainResult.Failure) return validation

        return stateStore.updateAndReturn {
            val otherTypes = equipmentStacks.size + manualStacks.size + pills.size + materials.size + seeds.size
            val store = StackableItemStore(
                initialItems = herbs.all(),
                stackKeyOf = StackKeys::herb,
                maxStack = getMaxStackForType("herb"),
                maxSlots = { computeMaxSlots() - otherTypes },
                notFound = { AppError.Domain.Inventory.NotFound(it) }
            )
            val result = store.add(item, merge = merge)
            herbs.replaceAll(store.all())
            when (result) {
                is DomainResult.Success -> {
                    val srcKey = trackingSource
                    gameData = gameData.copy(
                        annualHerbBySource = gameData.annualHerbBySource + (srcKey to (gameData.annualHerbBySource[srcKey] ?: 0) + item.quantity)
                    )
                }
                is DomainResult.Partial -> {
                    val actualAdded = item.quantity - result.overflow
                    val srcKey = trackingSource
                    gameData = gameData.copy(
                        annualHerbBySource = gameData.annualHerbBySource + (srcKey to (gameData.annualHerbBySource[srcKey] ?: 0) + actualAdded)
                    )
                }
                is DomainResult.Failure -> { }
            }
            handleOverflowResult(result, "herb", item)
            result
        }
    }

    fun removeHerb(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        if (quantity <= 0) return false
        return stateStore.updateAndReturn {
            val existing = herbs.find { it.id == id } ?: return@updateAndReturn false
            if (!bypassLock && existing.isLocked) {
                logWarning("Cannot remove locked herb: ${existing.hashCode()}")
                return@updateAndReturn false
            }
            if (existing.quantity < quantity) {
                logWarning("Cannot remove $quantity herb, only ${existing.quantity} available")
                return@updateAndReturn false
            }
            var removed = false
            herbs = herbs.mapNotNull { item ->
                if (item.id == id && !removed) {
                    val newQty = item.quantity - quantity
                    when {
                        newQty < 0 -> item
                        newQty == 0 -> { removed = true; null }
                        else -> { removed = true; item.copy(quantity = newQty) }
                    }
                } else item
            }
            true
        }
    }

    fun removeHerbByName(name: String, rarity: Int, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        val existing = currentHerbs().find { it.name == name && it.rarity == rarity }
            ?: return false
        if (!bypassLock && existing.isLocked) {
            logWarning("Cannot remove locked herb: ${existing.name}")
            return false
        }
        if (existing.quantity < quantity) {
            logWarning("Cannot remove $quantity items '$name', only ${existing.quantity} available")
            return false
        }

        return stateStore.updateAndReturn {
            var removed = false
            herbs = herbs.mapNotNull { herb ->
                if (herb.id == existing.id && !removed) {
                    val newQty = herb.quantity - quantity
                    when {
                        newQty < 0 -> {
                            logWarning("Cannot remove $quantity items, only ${herb.quantity} available")
                            herb
                        }
                        newQty == 0 -> {
                            removed = true
                            null
                        }
                        else -> {
                            removed = true
                            herb.copy(quantity = newQty)
                        }
                    }
                } else herb
            }
            removed
        }
    }

    fun updateHerb(id: String, transform: (Herb) -> Herb): Boolean {
        return stateStore.updateAndReturn {
            var found = false
            herbs = herbs.map {
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
            found
        }
    }

    fun getHerbById(id: String): Herb? = getById(currentHerbs(), id)
    fun getHerbQuantity(id: String): Int = getQuantity(currentHerbs(), id)

    fun hasHerb(name: String, rarity: Int, quantity: Int = 1): Boolean {
        val item = currentHerbs().find { it.name == name && it.rarity == rarity } ?: return false
        return item.quantity >= quantity
    }

    fun addSeed(item: Seed, merge: Boolean = true): DomainResult<Seed> {
        val validation = validateStackableItem(item.name, item.rarity, item.quantity)
        if (validation is DomainResult.Failure) return validation

        return stateStore.updateAndReturn {
            val otherTypes = equipmentStacks.size + manualStacks.size + pills.size + materials.size + herbs.size
            val store = StackableItemStore(
                initialItems = seeds.all(),
                stackKeyOf = StackKeys::seed,
                maxStack = getMaxStackForType("seed"),
                maxSlots = { computeMaxSlots() - otherTypes },
                notFound = { AppError.Domain.Inventory.NotFound(it) }
            )
            val result = store.add(item, merge = merge)
            seeds.replaceAll(store.all())
            handleOverflowResult(result, "seed", item)
            result
        }
    }

    /**
     * 添加储物袋。
     *
     * 走 [StackableItemStore] 统一合并：同稀有度的储物袋自动合并为单个堆叠。
     * 储物袋不占仓库槽位预算（[computeSlotCount] 本就不含 storageBags）。
     *
     * @param item 待添加的储物袋
     * @return [DomainResult.Success] 全部成功 / [DomainResult.Partial] 部分成功 / [DomainResult.Failure] 失败
     */
    fun addStorageBag(item: StorageBag): DomainResult<StorageBag> {
        if (item.quantity <= 0) {
            return DomainResult.Failure(AppError.Domain.Inventory.InvalidQuantity(item.quantity))
        }
        return stateStore.updateAndReturn {
            val store = StackableItemStore(
                initialItems = storageBags.all(),
                stackKeyOf = StackKeys::storageBag,
                maxStack = getMaxStackForType("storageBag"),
                maxSlots = { STORAGE_BAG_SLOT_BUDGET },
                notFound = { AppError.Domain.Inventory.NotFound(it) }
            )
            val result = store.add(item)
            storageBags.replaceAll(store.all())
            handleOverflowResult(result, "storageBag", item)
            result
        }
    }

    fun removeSeed(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        if (quantity <= 0) return false
        return stateStore.updateAndReturn {
            val existing = seeds.find { it.id == id } ?: return@updateAndReturn false
            if (!bypassLock && existing.isLocked) {
                logWarning("Cannot remove locked seed: ${existing.name}")
                return@updateAndReturn false
            }
            if (existing.quantity < quantity) {
                logWarning("Cannot remove $quantity seed, only ${existing.quantity} available")
                return@updateAndReturn false
            }
            var removed = false
            seeds = seeds.mapNotNull { seed ->
                if (seed.id == id && !removed) {
                    val newQty = seed.quantity - quantity
                    when {
                        newQty < 0 -> seed
                        newQty == 0 -> { removed = true; null }
                        else -> { removed = true; seed.copy(quantity = newQty) }
                    }
                } else seed
            }
            true
        }
    }

    fun addSeedSync(item: Seed, merge: Boolean = true): DomainResult<Seed> {
        val validation = validateStackableItem(item.name, item.rarity, item.quantity)
        if (validation is DomainResult.Failure) return validation

        return stateStore.updateAndReturn {
            val otherTypes = equipmentStacks.size + manualStacks.size + pills.size + materials.size + herbs.size
            val store = StackableItemStore(
                initialItems = seeds.all(),
                stackKeyOf = StackKeys::seed,
                maxStack = getMaxStackForType("seed"),
                maxSlots = { computeMaxSlots() - otherTypes },
                notFound = { AppError.Domain.Inventory.NotFound(it) }
            )
            val result = store.add(item, merge = merge)
            seeds.replaceAll(store.all())
            handleOverflowResult(result, "seed", item)
            result
        }
    }

    fun removeSeedSync(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        if (quantity <= 0) return false
        return stateStore.updateAndReturn {
            val existing = seeds.find { it.id == id } ?: return@updateAndReturn false
            if (!bypassLock && existing.isLocked) {
                logWarning("Cannot remove locked seed: ${existing.name}")
                return@updateAndReturn false
            }
            if (existing.quantity < quantity) {
                logWarning("Cannot remove $quantity items, only ${existing.quantity} available")
                return@updateAndReturn false
            }
            var removed = false
            seeds = seeds.mapNotNull { seed ->
                if (seed.id == id && !removed) {
                    val newQty = seed.quantity - quantity
                    when {
                        newQty < 0 -> seed
                        newQty == 0 -> { removed = true; null }
                        else -> { removed = true; seed.copy(quantity = newQty) }
                    }
                } else seed
            }
            removed
        }
    }

    fun removeSeedByName(name: String, rarity: Int, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        val existing = currentSeeds().find { it.name == name && it.rarity == rarity }
            ?: return false
        if (!bypassLock && existing.isLocked) {
            logWarning("Cannot remove locked seed: ${existing.name}")
            return false
        }
        if (existing.quantity < quantity) {
            logWarning("Cannot remove $quantity items '$name', only ${existing.quantity} available")
            return false
        }

        return stateStore.updateAndReturn {
            var removed = false
            seeds = seeds.mapNotNull { seed ->
                if (seed.id == existing.id && !removed) {
                    val newQty = seed.quantity - quantity
                    when {
                        newQty < 0 -> {
                            logWarning("Cannot remove $quantity items, only ${seed.quantity} available")
                            seed
                        }
                        newQty == 0 -> {
                            removed = true
                            null
                        }
                        else -> {
                            removed = true
                            seed.copy(quantity = newQty)
                        }
                    }
                } else seed
            }
            removed
        }
    }

    fun updateSeed(id: String, transform: (Seed) -> Seed): Boolean {
        return stateStore.updateAndReturn {
            var found = false
            seeds = seeds.map {
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
            found
        }
    }

    fun getSeedById(id: String): Seed? = getById(currentSeeds(), id)
    fun getSeedQuantity(id: String): Int = getQuantity(currentSeeds(), id)

    fun hasSeed(name: String, rarity: Int, quantity: Int = 1): Boolean {
        val item = currentSeeds().find { it.name == name && it.rarity == rarity } ?: return false
        return item.quantity >= quantity
    }

    fun getItemCountByType(type: String): Int {
        return when (type.lowercase(java.util.Locale.getDefault())) {
            "equipment_stack" -> currentEquipmentStacks().size
            "equipment_instance" -> currentEquipmentInstances().size
            "manual_stack" -> currentManualStacks().size
            "manual_instance" -> currentManualInstances().size
            "equipment" -> currentEquipmentStacks().size + currentEquipmentInstances().size
            "manual" -> currentManualStacks().size + currentManualInstances().size
            "pill" -> currentPills().size
            "material" -> currentMaterials().size
            "herb" -> currentHerbs().size
            "seed" -> currentSeeds().size
            else -> 0
        }
    }

    /**
     * 在 MutableGameState 事务内合并分散堆叠。
     * 由 [consolidateStacks] 和 [sortWarehouse] 共用。
     *
     * 单遍合并保证终止（见 consolidate 内注释）；锁定堆叠**允许作为合并目标**
     * （吸收数量、自身 ID 与 isLocked 不变），**禁止作为合并来源**
     * （锁定堆叠绝不被删除/减少，否则锁定标记与 ID 丢失）。
     * 与 [StackableItemStore.add]（本就合并进锁定堆叠）行为一致。
     */
    private fun MutableGameState.consolidateAllStacks() {
        /**
         * 单遍合并分散堆叠：每组以第一个未满堆叠为合并目标，顺序吸收后续**未满**堆叠。
         *
         * 终止性保证（对抗性审查修复，2026-08-01）：
         * - 单遍（无 while 循环）——每堆叠最多被处理一次，必然终止；
         * - 满堆叠（quantity >= maxStack）跳过——禁止"从满堆叠抽回"，
         *   否则 ≥3 个同键堆叠且总数 > maxStack 时会在"满/半满"之间无限振荡
         *   （如 [999,543,999] ↔ [999,999,543] 死循环）。
         *
         * 锁定策略：锁定堆叠**允许作为合并目标**（吸收数量、自身 ID 与 isLocked 不变），
         * **禁止作为合并来源**（锁定堆叠绝不被删除/减少）。
         */
        fun <T> consolidate(items: EntityStore<T>, keyOf: (T) -> StackKey, maxStack: Int)
                where T : HasId, T : StackableItem {
            val groups = items.all().groupBy { keyOf(it) }
            for ((_, list) in groups) {
                if (list.size <= 1) continue
                // 首选第一个未满堆叠（可含锁定）作为合并目标
                var primaryId = list.firstOrNull { it.quantity < maxStack }?.id ?: continue
                for (i in 1 until list.size) {
                    val secondary = list[i]
                    if (secondary.id == primaryId || secondary.isLocked) continue
                    if (secondary.quantity >= maxStack) continue
                    val primary = items.get(primaryId) ?: break
                    val space = maxStack - primary.quantity
                    if (space <= 0) { primaryId = secondary.id; continue }
                    val transfer = minOf(space, secondary.quantity)
                    @Suppress("UNCHECKED_CAST")
                    items.update(primaryId) {
                        (it as StackableItem).withQuantity(it.quantity + transfer) as T
                    }
                    if (transfer >= secondary.quantity) items.remove(secondary.id)
                    else {
                        @Suppress("UNCHECKED_CAST")
                        items.update(secondary.id) {
                            (it as StackableItem).withQuantity(it.quantity - transfer) as T
                        }
                    }
                }
            }
        }
        val maxEq = getMaxStackForType("equipment_stack")
        val maxMn = getMaxStackForType("manual_stack")
        val maxPill = getMaxStackForType("pill")
        val maxMat = getMaxStackForType("material")
        val maxHerb = getMaxStackForType("herb")
        val maxSeed = getMaxStackForType("seed")
        val maxBag = getMaxStackForType("storageBag")
        consolidate(equipmentStacks, StackKeys::equipment, maxEq)
        consolidate(manualStacks, StackKeys::manual, maxMn)
        consolidate(pills, StackKeys::pill, maxPill)
        consolidate(materials, StackKeys::material, maxMat)
        consolidate(herbs, StackKeys::herb, maxHerb)
        consolidate(seeds, StackKeys::seed, maxSeed)
        consolidate(storageBags, StackKeys::storageBag, maxBag)
    }

    fun consolidateStacks() {
        stateStore.update { consolidateAllStacks() }
    }

    fun sortWarehouse() {
        stateStore.update {
            consolidateAllStacks() // 先合并后排序，同一事务内
            equipmentStacks.replaceAll(equipmentStacks.items.sortedWith(compareByDescending<EquipmentStack> { it.rarity }.thenBy { it.name }))
            equipmentInstances.replaceAll(equipmentInstances.items.sortedWith(compareByDescending<EquipmentInstance> { it.rarity }.thenBy { it.name }))
            manualStacks.replaceAll(manualStacks.items.sortedWith(compareByDescending<ManualStack> { it.rarity }.thenBy { it.name }))
            manualInstances.replaceAll(manualInstances.items.sortedWith(compareByDescending<ManualInstance> { it.rarity }.thenBy { it.name }))
            pills.replaceAll(pills.items.sortedWith(compareByDescending<Pill> { it.rarity }.thenBy { it.name }))
            materials.replaceAll(materials.all().sortedWith(compareByDescending<Material> { it.rarity }.thenBy { it.name }))
            herbs.replaceAll(herbs.all().sortedWith(compareByDescending<Herb> { it.rarity }.thenBy { it.name }))
            seeds.replaceAll(seeds.all().sortedWith(compareByDescending<Seed> { it.rarity }.thenBy { it.name }))
        }
    }

    fun hasEnoughSpiritStones(currentStones: Long, required: Long): Boolean {
        return currentStones >= required
    }

    // ── Spirit Stone operations (delegated to SpiritStoneWallet) ──────────

    fun createEquipmentFromRecipe(recipe: ForgeRecipe): EquipmentStack =
        InventoryFactories.createEquipmentFromRecipe(recipe)

    fun createEquipmentFromMerchantItem(item: MerchantItem): EquipmentStack =
        InventoryFactories.createEquipmentFromMerchantItem(item)

    fun createManualFromMerchantItem(item: MerchantItem): ManualStack =
        InventoryFactories.createManualFromMerchantItem(item)

    fun createPillFromMerchantItem(item: MerchantItem): Pill =
        InventoryFactories.createPillFromMerchantItem(item)

    fun createMaterialFromMerchantItem(item: MerchantItem): Material =
        InventoryFactories.createMaterialFromMerchantItem(item)

    fun createHerbFromMerchantItem(item: MerchantItem): Herb =
        InventoryFactories.createHerbFromMerchantItem(item)

    fun createSeedFromMerchantItem(item: MerchantItem): Seed =
        InventoryFactories.createSeedFromMerchantItem(item)

    override fun addPill(item: Pill): DomainResult<Pill> = addPill(item, merge = true)
    override fun addMaterial(item: Material): DomainResult<Material> = addMaterial(item, merge = true)
    override fun addHerb(item: Herb): DomainResult<Herb> = addHerb(item, merge = true)
    override fun addSeed(item: Seed): DomainResult<Seed> = addSeed(item, merge = true)
}
