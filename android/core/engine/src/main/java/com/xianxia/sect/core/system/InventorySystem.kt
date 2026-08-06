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
     * @param excludeStackId 排除的堆叠 id（F1 对抗性审查修复：放背包路径刚扣减
     *   源堆叠 1 份后调用本方法，若合并回源堆叠则数量净 0 但背包引用 +1，
     *   可无限刷引用并经储物袋回收洗白为真实堆叠——排除后合并到其他同键
     *   堆叠或新建，与旧手写实现的有界语义一致）
     */
    override fun addEquipmentStack(
        item: EquipmentStack,
        excludeStackId: String?
    ): DomainResult<EquipmentStack> {
        val validation = validateStackableItem(item.name, item.rarity, item.quantity)
        if (validation is DomainResult.Failure) return validation

        return stateStore.updateAndReturn {
            val otherTypes = manualStacks.size + pills.size + materials.size + herbs.size + seeds.size
            val allStacks = equipmentStacks.all()
            val excluded = excludeStackId?.let { id -> allStacks.find { it.id == id } }
            val candidates = if (excluded != null) allStacks - excluded else allStacks
            val store = StackableItemStore(
                initialItems = candidates,
                stackKeyOf = StackKeys::equipment,
                maxStack = getMaxStackForType("equipment_stack"),
                maxSlots = { computeMaxSlots() - otherTypes },
                notFound = { AppError.Domain.Inventory.NotFound(it) }
            )
            val result = store.add(item)
            // 排除的堆叠放回列表尾部（放背包路径：源堆叠保持扣减后的状态）
            val finalStacks = if (excluded != null) store.all() + excluded else store.all()
            equipmentStacks.replaceAll(finalStacks)
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
     * @param excludeStackId 排除的堆叠 id（F1 对抗性审查修复，语义同
     *   [addEquipmentStack]——放背包路径须排除刚扣减的源堆叠，防刷引用）
     */
    override fun addManualStack(
        item: ManualStack,
        merge: Boolean,
        excludeStackId: String?
    ): DomainResult<ManualStack> {
        val validation = validateStackableItem(item.name, item.rarity, item.quantity)
        if (validation is DomainResult.Failure) return validation

        return stateStore.updateAndReturn {
            val otherTypes = equipmentStacks.size + pills.size + materials.size + herbs.size + seeds.size
            val allStacks = manualStacks.all()
            val excluded = excludeStackId?.let { id -> allStacks.find { it.id == id } }
            val candidates = if (excluded != null) allStacks - excluded else allStacks
            val store = StackableItemStore(
                initialItems = candidates,
                stackKeyOf = StackKeys::manual,
                maxStack = getMaxStackForType("manual_stack"),
                maxSlots = { computeMaxSlots() - otherTypes },
                notFound = { AppError.Domain.Inventory.NotFound(it) }
            )
            val result = store.add(item, merge = merge)
            val finalStacks = if (excluded != null) store.all() + excluded else store.all()
            manualStacks.replaceAll(finalStacks)
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
     * 卸装/换装路径：装备实例转回仓库堆叠并移除实例（P-20 从 domain StorageBagUtils 迁入）。
     *
     * 修复了原 domain 实现的三个缺陷：
     * 1. `maxSlots = { candidates.size + 1 }` 绕过仓库总容量 → 改为真实容量
     *    `computeMaxSlots() - otherTypes`（卸装也受仓库容量约束，溢出转邮件）
     * 2. Partial 被当作成功 → 溢出经 [handleOverflowResult] 转邮件（不丢玩家物品）
     * 3. 无来源追踪 → 年度报告可归因（调用方自行包裹 [withTrackingSource]）
     *
     * @param instance 待转回堆叠的装备实例
     * @param excludeStackId 排除的堆叠 id（背包引用指向的堆叠，防合并到自身后引用错位）
     */
    fun addEquipmentInstanceToBag(
        instance: EquipmentInstance,
        excludeStackId: String? = null
    ): DomainResult<EquipmentStack> {
        return stateStore.updateAndReturn {
            val allStacks = equipmentStacks.all()
            val excluded = excludeStackId?.let { id -> allStacks.find { it.id == id } }
            val candidates = if (excluded != null) allStacks - excluded else allStacks
            val store = StackableItemStore(
                initialItems = candidates,
                stackKeyOf = StackKeys::equipment,
                maxStack = getMaxStackForType("equipment_stack"),
                maxSlots = {
                    computeMaxSlots() - (manualStacks.size + pills.size + materials.size + herbs.size + seeds.size)
                },
                notFound = { AppError.Domain.Inventory.NotFound(it) }
            )
            val item = instance.toStack(quantity = 1)
            val result = store.add(item)
            // 排除的堆叠放回列表尾部（保持背包引用指向的源堆叠不被扣减）
            val finalStacks = if (excluded != null) store.all() + excluded else store.all()
            equipmentStacks.replaceAll(finalStacks)
            equipmentInstances = equipmentInstances.filter { it.id != instance.id }
            handleOverflowResult(result, "equipment", item)
            result
        }
    }

    /**
     * 卸功法路径：功法实例转回仓库堆叠并移除实例（P-20 从 domain StorageBagUtils 迁入）。
     * 语义与 [addEquipmentInstanceToBag] 一致。
     *
     * @param instance 待转回堆叠的功法实例
     * @param excludeStackId 排除的堆叠 id（背包引用指向的堆叠）
     */
    fun addManualInstanceToBag(
        instance: ManualInstance,
        excludeStackId: String? = null
    ): DomainResult<ManualStack> {
        return stateStore.updateAndReturn {
            val allStacks = manualStacks.all()
            val excluded = excludeStackId?.let { id -> allStacks.find { it.id == id } }
            val candidates = if (excluded != null) allStacks - excluded else allStacks
            val store = StackableItemStore(
                initialItems = candidates,
                stackKeyOf = StackKeys::manual,
                maxStack = getMaxStackForType("manual_stack"),
                maxSlots = {
                    computeMaxSlots() - (equipmentStacks.size + pills.size + materials.size + herbs.size + seeds.size)
                },
                notFound = { AppError.Domain.Inventory.NotFound(it) }
            )
            val item = instance.toStack(quantity = 1)
            val result = store.add(item)
            val finalStacks = if (excluded != null) store.all() + excluded else store.all()
            manualStacks.replaceAll(finalStacks)
            manualInstances = manualInstances.filter { it.id != instance.id }
            handleOverflowResult(result, "manual", item)
            result
        }
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
