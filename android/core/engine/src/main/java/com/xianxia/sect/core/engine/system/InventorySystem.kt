package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.StackKeys
import com.xianxia.sect.core.state.StackableItemStore
import com.xianxia.sect.core.util.StackableItem
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.registry.ForgeRecipeDatabase.ForgeRecipe
import com.xianxia.sect.core.model.StorageBag
import com.xianxia.sect.core.overflow.NoOpOverflowMailHandler
import com.xianxia.sect.core.overflow.OverflowMailHandler
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.AppError
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton
import com.xianxia.sect.core.overflow.OverflowMailDraft


// TickSystem: "InventorySystem"
@com.xianxia.sect.core.engine.annotation.GameService("InventorySystem")
@SystemPriority(order = 50)
@Singleton
class InventorySystem @Inject constructor(
    internal val stateStore: GameStateStore,
    internal val inventoryConfig: InventoryConfig,
    /** 溢出邮件处理器（启动时排空上次崩溃遗留的持久化草稿；默认 Noop 供测试） */
    internal val overflowMailHandler: OverflowMailHandler = NoOpOverflowMailHandler,
) : GameSystem, ItemAdder {

    companion object {
        /**
         * 单用户定向补偿邮件（MailService 扩展，独立文件）。
         *
         * 拆分原因：MailService 类主体接近 detekt LargeClass（800 行）阈值，
         * 补偿邮件属独立运营配置，放独立文件保持 MailService 规模稳定；
         * stateStore/mailRepo 已放宽为 internal 供本扩展读取（三重防护）。
         */
        internal const val TAG = "InventorySystem"
        const val SYSTEM_NAME = "InventorySystem"
        internal val VALID_RARITY_RANGE = 1..6

        /** 储物袋槽位预算：储物袋不占仓库建筑容量（computeSlotCount 不含 storageBags），
         *  此值仅防止极端情况下堆叠无限增长（6 种稀有度各若干堆）。 */
        internal const val STORAGE_BAG_SLOT_BUDGET = 64

        /** 死亡物化年度报告来源（materializeDiscipleBagAndMarkDead 用） */
        const val SOURCE_DISCIPLE_DEATH = "disciple_death"
    }

    /** 年度报告物品来源上下文——引擎单线程安全。在调用 add* 前设置来源 */
    internal var trackingSource: String = "unknown"

    /** 溢出转邮件抑制标志——MailService 等"事务回滚"语义路径使用 */
    internal var overflowMailSuppressed = false

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

    /**
     * 初始化建筑配置（每次 boot 经 `ResourcePreloader.preloadGameResources` 调用）。
     * 重复调用直接跳过，避免 `config/buildings.json` 重复 I/O（首次加载失败已回退默认配置，
     * 无需重试语义）。
     */
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
                        annualEquipmentBySource = gameData.annualEquipmentBySource + (srcKey to (gameData
                            .annualEquipmentBySource[srcKey] ?: 0) + item.quantity)
                    )
                }
                is DomainResult.Partial -> {
                    val actualAdded = item.quantity - result.overflow
                    val srcKey = "$trackingSource:${item.rarity}"
                    gameData = gameData.copy(
                        annualEquipmentBySource = gameData.annualEquipmentBySource + (srcKey to (gameData
                            .annualEquipmentBySource[srcKey] ?: 0) + actualAdded)
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
        if (item.rarity !in VALID_RARITY_RANGE) return DomainResult.Failure(AppError.Domain.Inventory.InvalidRarity(item
            .rarity))

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
        if (item.rarity !in VALID_RARITY_RANGE) return DomainResult.Failure(AppError.Domain.Inventory.InvalidRarity(item
            .rarity))

        return stateStore.updateAndReturn {
            if (manualInstances.any { it.id == item.id }) {
                return@updateAndReturn DomainResult.Failure(AppError.Domain.Inventory.DuplicateId(item.id))
            }
            manualInstances = manualInstances + item
            DomainResult.Success(item)
        }
    }

    override fun addPill(item: Pill): DomainResult<Pill> = addPill(item, merge = true)
    override fun addMaterial(item: Material): DomainResult<Material> = addMaterial(item, merge = true)
    override fun addHerb(item: Herb): DomainResult<Herb> = addHerb(item, merge = true)
    override fun addSeed(item: Seed): DomainResult<Seed> = addSeed(item, merge = true)

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
     * 公开溢出转邮件入口（供"state 参数直传"路径使用——如灵田收获直接操作
     * 事务缓冲 state，不走本类 addXxx 时自行处理 Partial/Failure 溢出）。
     *
     * @param source 物品来源（与 withTrackingSource 的 source 值一致）
     * @param itemType 与 MailAttachment.type 对齐
     * @param itemName 物品名称
     * @param rarity 稀有度
     * @param quantity 溢出数量（>0 才发送）
     * @param itemId 物品模板 id（精确还原用；调用方有模板时传，缺省空串）
     */
    fun sendOverflowMail(
        source: String,
        itemType: String,
        itemName: String,
        rarity: Int,
        quantity: Int,
        itemId: String = ""
    ) {
        if (overflowMailSuppressed) return
        if (quantity <= 0) return
        overflowMailHandler.sendOverflowMails(listOf(
            OverflowMailDraft(
                slotId = stateStore.gameData.value.currentSlot,
                source = source,
                itemType = itemType,
                itemName = itemName,
                itemId = itemId,
                rarity = rarity,
                quantity = quantity
            )
        ))
    }

    fun getSeedById(id: String): Seed? = getById(currentSeeds(), id)

    /**
     * 死亡统一入口：袋物品物化回仓库（玩家保留，溢出自动转邮件）→ 清空袋条目
     * （幂等）→ 标记死亡。必须在 [stateStore.update] 事务内调用（与 markDead 同事务，
     * 防"死亡已标记但袋物品未物化"窗口导致物品随死弟子记录 cull 永久丢失）。
     *
     * 所有死亡标记路径（宗门战/世界战斗/侦查/探索队/秘境/寿元）统一经此入口。
     * 物化后清空袋条目：重复死亡处理不重复物化（防物品复制）。
     *
     * 年报死亡计数（annualDeceasedDisciples）：以 wasAlive 守卫防双计——
     * 世界关卡/侦查/秘境路径会经 CombatService.processBattleCasualties
     * 对同一弟子二次调用本方法（首次 isAlive=1 计、二次 isAlive=0 跳过）；
     * 探索队/宗门战为单次调用正常计数。全部 5 条路径恰好计 1 次。
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
        // 双计防线：世界/侦查/秘境二次调用时 isAlive 已为 0，跳过计数
        val wasAlive = state.discipleTables.isAlive.getOrNull(discipleId) == 1
        val bagItems = state.discipleTables.storageBagItems.getOrNull(discipleId)
        if (!bagItems.isNullOrEmpty()) {
            withTrackingSource(SOURCE_DISCIPLE_DEATH) {
                materializeBagItemsToWarehouse(bagItems)
            }
            // 幂等：清空袋条目——重复死亡处理不重复物化
            state.discipleTables.storageBagItems[discipleId] = emptyList()
        }
        state.discipleTables.markDead(discipleId, deathYear, cause)
        if (wasAlive) {
            state.gameData = state.gameData.copy(
                annualDeceasedDisciples = state.gameData.annualDeceasedDisciples + 1
            )
        }
    }


    fun createEquipmentFromRecipe(recipe: ForgeRecipe): EquipmentStack =
        InventoryFactories.createEquipmentFromRecipe(recipe)


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

}

// ===== 溢出邮件物品模板 id 解析（InventorySystem.resolveOverflowItemId 按类型拆分，
//       单函数圈复杂度 ≤15——detekt CyclomaticComplexMethod 阈值） =====

internal fun resolvePillTemplateId(item: StackableItem): String =
    (item as? Pill)?.let {
        ItemDatabase.allPills.values.firstOrNull { p ->
            p.name == it.name && p.rarity == it.rarity &&
                p.category == it.category && p.grade == it.grade
        }?.id ?: ""
    } ?: ""

internal fun resolveMaterialTemplateId(item: StackableItem): String =
    (item as? Material)?.let {
        ItemDatabase.allMaterials.values.firstOrNull { m ->
            m.name == it.name && m.rarity == it.rarity && m.category == it.category
        }?.id ?: ""
    } ?: ""

internal fun resolveHerbTemplateId(item: StackableItem): String =
    (item as? Herb)?.let {
        HerbDatabase.getHerbsByTier(it.rarity).firstOrNull { h ->
            h.name == it.name && h.category == it.category
        }?.id ?: ""
    } ?: ""

internal fun resolveSeedTemplateId(item: StackableItem): String =
    (item as? Seed)?.let {
        HerbDatabase.getAllSeeds().firstOrNull { s ->
            s.name == it.name && s.rarity == it.rarity && s.growTime == it.growTime
        }?.id ?: ""
    } ?: ""

internal fun resolveEquipmentTemplateId(item: StackableItem): String =
    (item as? EquipmentStack)?.let {
        EquipmentDatabase.getTemplateByName(it.name)?.id ?: ""
    } ?: ""

internal fun resolveManualTemplateId(item: StackableItem): String =
    (item as? ManualStack)?.let {
        ManualDatabase.getByName(it.name)?.id ?: ""
    } ?: ""
