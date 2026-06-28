package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.system.MerchantItemConverter
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.DomainLog
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ## 弟子智能购买服务
 *
 * 每月月结触发。遍历所有存活弟子，评估他们对玩家上架物品的需求，
 * 让弟子使用个人灵石从 [GameData.playerListedItems] 中智能购买。
 *
 * ### 购买规则
 * - 只能购买境界允许的物品（disciple.realm <= minRealm）
 * - 优先级：功法 > 装备 > 丹药
 * - 缺位优先：有空槽位的弟子优先于满槽想升级的弟子
 * - 会存钱买高品阶物品（保留至少30%灵石）
 * - 每弟子每月有类别上限：功法≤2、装备≤2、丹药≤10
 * - 弟子随机打乱确保公平分配
 */
@Singleton
@GameService("DisciplePurchaseService")
class DisciplePurchaseService @Inject constructor(
    private val stateStore: GameStateStore,
    private val inventorySystem: InventorySystem,
    private val inventoryConfig: InventoryConfig
) {
    companion object {
        private const val TAG = "DisciplePurchase"

        /** 每弟子每月功法购买上限 */
        const val MAX_MANUAL_PURCHASES = 2
        /** 每弟子每月装备购买上限 */
        const val MAX_EQUIPMENT_PURCHASES = 2
        /** 每弟子每月丹药购买上限 */
        const val MAX_PILL_PURCHASES = 10
        /** 灵石最低保留比例 */
        const val SAVING_RATIO = 0.3
        /** 功法槽位估算上限（用于判断是否有空槽） */
        private const val ESTIMATED_MAX_MANUAL_SLOTS = 4

        // ── 公开纯函数（测试可访问） ──────────────────────────────

        /**
         * 判断弟子是否满足物品的境界要求。
         * 通过品阶推算 minRealm，而非依赖物品自身 minRealm 字段。
         */
        fun canUseItem(discipleRealm: Int, itemRarity: Int): Boolean {
            val minRealm = GameConfig.Realm.getMinRealmForRarity(itemRarity)
            return GameConfig.Realm.meetsRealmRequirement(discipleRealm, minRealm)
        }

        /**
         * 计算弟子的可用采购预算。
         * 保留部分灵石以存钱购买高品阶物品。
         *
         * @param totalFunds 弟子总灵石
         * @param highestNeededPrice 当前可购买物品中的最高价格（0 表示无可用物品）
         * @return 可用于本次采购的灵石
         */
        fun calculateBudget(totalFunds: Long, highestNeededPrice: Long): Long {
            if (totalFunds <= 0L) return 0L
            val baseReserve = (totalFunds * SAVING_RATIO).toLong()
            val targetReserve = if (highestNeededPrice > 0L) {
                (highestNeededPrice * 0.2).toLong()
            } else 0L
            val reserve = maxOf(baseReserve, targetReserve)
            return (totalFunds - reserve).coerceAtLeast(0L)
        }

        /**
         * 判断弟子是否需要某本功法。
         *
         * @param learnedNames 已学功法名称集
         * @param manualName 候选功法名称
         * @param candidateRarity 候选功法品阶
         * @param bestLearnedRarity 已学同名功法中最高品阶（0 表示未学过）
         * @return true 表示需要（未学过 或 候选品阶更高可替换）
         */
        fun needsManual(
            learnedNames: Set<String>,
            manualName: String,
            candidateRarity: Int,
            bestLearnedRarity: Int
        ): Boolean {
            if (manualName.isEmpty()) return false
            if (manualName !in learnedNames) return true
            return candidateRarity > bestLearnedRarity
        }

        /**
         * 判断弟子是否需要某个装备槽位的装备。
         *
         * @param currentEquipId 当前装备 ID（空字符串表示槽位为空）
         * @param candidateRarity 候选装备品阶
         * @param currentRarity 当前装备品阶（槽位为空时为 0）
         * @return true 表示需要（槽位为空 或 候选品阶更高可升级）
         */
        fun needsEquipmentSlot(
            currentEquipId: String,
            candidateRarity: Int,
            currentRarity: Int
        ): Boolean {
            if (currentEquipId.isEmpty()) return true
            return candidateRarity > currentRarity
        }

        /**
         * 计算弟子可购买物品中的最高价格。
         * 只计算境界允许的物品。
         */
        fun calculateHighestNeededPrice(
            items: List<MerchantItem>,
            discipleRealm: Int
        ): Long = items
            .filter { canUseItem(discipleRealm, it.rarity) }
            .maxOfOrNull { it.price } ?: 0L
    }

    // ── 内部数据类（命名后缀匹配 Konsist 排除规则） ────────────

    private data class DisciplePurchaseContext(
        val id: Int,
        val realm: Int,
        val weaponId: String,
        val armorId: String,
        val bootsId: String,
        val accessoryId: String,
        val manualIds: List<String>,
        val totalFunds: Long
    )

    private data class PurchaseEntry(
        val discipleId: Int,
        val item: MerchantItem,
        val itemType: String
    )

    /**
     * 执行弟子智能购买。在 [CultivationEventProcessor.processMonthlyEvents] 中调用。
     */
    suspend fun executePurchase(year: Int, month: Int) {
        val currentData = stateStore.gameData.value
        val listedItems = currentData.playerListedItems
        if (listedItems.isEmpty()) return

        val allDisciples = collectDisciples(stateStore.discipleTables)
        if (allDisciples.isEmpty()) return

        val decisions = buildPurchaseDecisions(
            listedItems, allDisciples,
            stateStore.equipmentInstances.value,
            stateStore.manualInstances.value
        )
        if (decisions.isEmpty()) return

        applyPurchaseDecisions(currentData, decisions, year, month)
    }

    /**
     * 收集有资金的存活弟子，构建购买上下文列表。
     */
    private fun collectDisciples(
        tables: DiscipleTables
    ): List<DisciplePurchaseContext> = tables.ids.mapNotNull { id ->
        if (tables.isAlive.getOrDefault(id, 0) != 1) return@mapNotNull null
        val totalFunds = (tables.storageBagSpiritStones.getOrNull(id) ?: 0L) +
            tables.discipleSpiritStones.getOrDefault(id, 0).toLong()
        if (totalFunds <= 0L) return@mapNotNull null
        DisciplePurchaseContext(
            id = id,
            realm = tables.realms.getOrDefault(id, 9),
            weaponId = tables.weaponIds.getOrNull(id) ?: "",
            armorId = tables.armorIds.getOrNull(id) ?: "",
            bootsId = tables.bootsIds.getOrNull(id) ?: "",
            accessoryId = tables.accessoryIds.getOrNull(id) ?: "",
            manualIds = tables.manualIds.getOrNull(id) ?: emptyList(),
            totalFunds = totalFunds
        )
    }

    /**
     * 按优先级生成购买决策：功法 → 装备 → 丹药。
     */
    private fun buildPurchaseDecisions(
        listedItems: List<MerchantItem>,
        allDisciples: List<DisciplePurchaseContext>,
        equipmentInstances: List<EquipmentInstance>,
        manualInstances: List<ManualInstance>
    ): List<PurchaseEntry> {
        val decisions = mutableListOf<PurchaseEntry>()
        processManualPurchases(
            listedItems, allDisciples, manualInstances, decisions
        )
        processEquipmentPurchases(
            listedItems, allDisciples, equipmentInstances, decisions
        )
        processPillPurchases(
            listedItems, allDisciples, decisions
        )
        return decisions
    }

    /**
     * 在 [stateStore.update] 事务中原子写入所有购买决策。
     */
    private suspend fun applyPurchaseDecisions(
        currentData: GameData,
        decisions: List<PurchaseEntry>,
        year: Int,
        month: Int
    ) {
        stateStore.update {
            val updatedListedItems =
                currentData.playerListedItems.toMutableList()

            for (decision in decisions) {
                val item = decision.item
                val dId = decision.discipleId

                val idx = updatedListedItems.indexOfFirst {
                    it.id == item.id
                }
                if (idx < 0) continue
                val listedItem = updatedListedItems[idx]
                if (listedItem.quantity <= 0) continue

                deductSpiritStones(dId, item.price)
                addToWarehouseAndBag(item, dId, year, month)

                val newQty = listedItem.quantity - 1
                if (newQty <= 0) {
                    updatedListedItems.removeAt(idx)
                } else {
                    updatedListedItems[idx] =
                        listedItem.copy(quantity = newQty)
                }
            }

            gameData = gameData.copy(
                playerListedItems = updatedListedItems
            )
        }

        if (decisions.isNotEmpty()) {
            DomainLog.i(TAG,
                "弟子智能购买: ${decisions.size}件物品 于 ${year}年${month}月")
        }
    }

    /**
     * 扣除弟子灵石（优先储物袋，再扣随身）。
     * 仅在 [MutableGameState] 上下文中调用。
     */
    private fun MutableGameState.deductSpiritStones(
        discipleId: Int,
        price: Long
    ) {
        var bagStones = discipleTables.storageBagSpiritStones[discipleId]
        var pocketStones = discipleTables.discipleSpiritStones[discipleId]
        var remaining = price

        val bagDeduction = remaining.coerceAtMost(bagStones)
        bagStones -= bagDeduction
        remaining -= bagDeduction
        val pocketDeduction =
            remaining.coerceAtMost(pocketStones.toLong())
        pocketStones = (pocketStones - pocketDeduction).toInt()

        discipleTables.storageBagSpiritStones[discipleId] = bagStones
        discipleTables.discipleSpiritStones[discipleId] = pocketStones
    }

    // ── 购买处理 ────────────────────────────────────────────────

    private fun processManualPurchases(
        listedItems: List<MerchantItem>,
        allDisciples: List<DisciplePurchaseContext>,
        manualInstances: List<ManualInstance>,
        decisions: MutableList<PurchaseEntry>
    ) {
        for (item in listedItems.filter { it.type == "manual" }) {
            if (item.quantity <= 0) continue

            val interested = allDisciples.filter { ctx ->
                if (!canUseItem(ctx.realm, item.rarity)) return@filter false
                if (countPurchases(decisions, ctx.id, "manual")
                    >= MAX_MANUAL_PURCHASES) return@filter false
                val highestNeeded =
                    calculateHighestNeededPrice(listOf(item), ctx.realm)
                val budget = calculateBudget(ctx.totalFunds, highestNeeded)
                if (budget < item.price) return@filter false

                val learnedNames = manualInstances
                    .filter { it.id in ctx.manualIds }
                    .map { it.name }.toSet()
                val bestRarity = manualInstances
                    .filter { it.id in ctx.manualIds && it.name == item.name }
                    .maxOfOrNull { it.rarity } ?: 0
                needsManual(learnedNames, item.name, item.rarity, bestRarity)
            }

            if (interested.isEmpty()) continue

            // A组有空槽位优先，B组满槽升级
            val groupA = interested
                .filter { it.manualIds.size < ESTIMATED_MAX_MANUAL_SLOTS }
                .shuffled()
            val groupB = interested
                .filter { it.manualIds.size >= ESTIMATED_MAX_MANUAL_SLOTS }
                .shuffled()

            for (ctx in (groupA + groupB)) {
                if (countPurchases(decisions, ctx.id, "manual")
                    >= MAX_MANUAL_PURCHASES) continue
                decisions.add(PurchaseEntry(ctx.id, item, "manual"))
                break
            }
        }
    }

    private fun processEquipmentPurchases(
        listedItems: List<MerchantItem>,
        allDisciples: List<DisciplePurchaseContext>,
        equipmentInstances: List<EquipmentInstance>,
        decisions: MutableList<PurchaseEntry>
    ) {
        for (item in listedItems.filter { it.type == "equipment" }) {
            if (item.quantity <= 0) continue

            val eq = MerchantItemConverter.toEquipment(item)

            val interested = allDisciples.filter { ctx ->
                if (!canUseItem(ctx.realm, item.rarity)) return@filter false
                if (countPurchases(decisions, ctx.id, "equipment")
                    >= MAX_EQUIPMENT_PURCHASES) return@filter false
                val highestNeeded =
                    calculateHighestNeededPrice(listOf(item), ctx.realm)
                val budget = calculateBudget(ctx.totalFunds, highestNeeded)
                if (budget < item.price) return@filter false

                val currentEquipId = getEquipIdBySlot(ctx, eq.slot)
                val currentRarity = if (currentEquipId.isNotEmpty()) {
                    equipmentInstances.find { it.id == currentEquipId }?.rarity
                        ?: 0
                } else 0
                needsEquipmentSlot(currentEquipId, item.rarity, currentRarity)
            }

            if (interested.isEmpty()) continue

            // A组槽位为空优先，B组升级
            val groupA = interested
                .filter { getEquipIdBySlot(it, eq.slot).isEmpty() }
                .shuffled()
            val groupB = interested
                .filter { getEquipIdBySlot(it, eq.slot).isNotEmpty() }
                .shuffled()

            for (ctx in (groupA + groupB)) {
                if (countPurchases(decisions, ctx.id, "equipment")
                    >= MAX_EQUIPMENT_PURCHASES) continue
                decisions.add(PurchaseEntry(ctx.id, item, "equipment"))
                break
            }
        }
    }

    private fun processPillPurchases(
        listedItems: List<MerchantItem>,
        allDisciples: List<DisciplePurchaseContext>,
        decisions: MutableList<PurchaseEntry>
    ) {
        val pillItems = listedItems
            .filter { it.type == "pill" && it.quantity > 0 }
            .sortedByDescending { it.rarity }

        for (item in pillItems) {
            val interested = allDisciples.filter { ctx ->
                if (!canUseItem(ctx.realm, item.rarity)) return@filter false
                if (countPurchases(decisions, ctx.id, "pill")
                    >= MAX_PILL_PURCHASES) return@filter false
                val budget = calculateBudget(ctx.totalFunds, 0L)
                budget >= item.price
            }.shuffled()

            for (ctx in interested) {
                if (countPurchases(decisions, ctx.id, "pill")
                    >= MAX_PILL_PURCHASES) continue
                decisions.add(PurchaseEntry(ctx.id, item, "pill"))
                break
            }
        }
    }

    // ── 辅助方法 ────────────────────────────────────────────────

    private fun countPurchases(
        decisions: List<PurchaseEntry>,
        discipleId: Int,
        itemType: String
    ): Int = decisions.count {
        it.discipleId == discipleId && it.itemType == itemType
    }

    private fun getEquipIdBySlot(
        ctx: DisciplePurchaseContext,
        slot: EquipmentSlot
    ): String = when (slot) {
        EquipmentSlot.WEAPON -> ctx.weaponId
        EquipmentSlot.ARMOR -> ctx.armorId
        EquipmentSlot.BOOTS -> ctx.bootsId
        EquipmentSlot.ACCESSORY -> ctx.accessoryId
    }
    // ── Pill → ItemEffect ────────────────────────────────────────

    private fun pillToItemEffect(pill: Pill): ItemEffect = ItemEffect(
        tier = pill.rarity,
        cultivationSpeedPercent = pill.effects.cultivationSpeedPercent,
        skillExpSpeedPercent = pill.effects.skillExpSpeedPercent,
        nurtureSpeedPercent = pill.effects.nurtureSpeedPercent,
        breakthroughChance = pill.effects.breakthroughChance,
        targetRealm = pill.effects.targetRealm,
        cultivationAdd = pill.effects.cultivationAdd,
        skillExpAdd = pill.effects.skillExpAdd,
        nurtureAdd = pill.effects.nurtureAdd,
        healMaxHpPercent = pill.effects.healMaxHpPercent,
        mpRecoverMaxMpPercent = pill.effects.mpRecoverMaxMpPercent,
        hpAdd = pill.effects.hpAdd,
        mpAdd = pill.effects.mpAdd,
        extendLife = pill.effects.extendLife,
        physicalAttackAdd = pill.effects.physicalAttackAdd,
        magicAttackAdd = pill.effects.magicAttackAdd,
        physicalDefenseAdd = pill.effects.physicalDefenseAdd,
        magicDefenseAdd = pill.effects.magicDefenseAdd,
        speedAdd = pill.effects.speedAdd,
        critRateAdd = pill.effects.critRateAdd,
        critEffectAdd = pill.effects.critEffectAdd,
        intelligenceAdd = pill.effects.intelligenceAdd,
        charmAdd = pill.effects.charmAdd,
        loyaltyAdd = pill.effects.loyaltyAdd,
        comprehensionAdd = pill.effects.comprehensionAdd,
        artifactRefiningAdd = pill.effects.artifactRefiningAdd,
        pillRefiningAdd = pill.effects.pillRefiningAdd,
        spiritPlantingAdd = pill.effects.spiritPlantingAdd,
        teachingAdd = pill.effects.teachingAdd,
        moralityAdd = pill.effects.moralityAdd,
        miningAdd = pill.effects.miningAdd,
        revive = pill.effects.revive,
        clearAll = pill.effects.clearAll,
        isAscension = pill.effects.isAscension,
        duration = pill.effects.duration,
        cannotStack = pill.effects.cannotStack,
        minRealm = pill.minRealm,
        pillCategory = pill.category.name,
        pillType = pill.pillType
    )

    // ── MutableGameState 上下文方法 ─────────────────────────────

    private fun MutableGameState.addToWarehouseAndBag(
        item: MerchantItem,
        discipleId: Int,
        year: Int,
        month: Int
    ) {
        when (item.type.lowercase(Locale.ROOT)) {
            "equipment" -> {
                val stack = MerchantItemConverter.toEquipment(item)
                    .copy(quantity = 1)
                val existing = equipmentStacks.all().find {
                    it.name == stack.name && it.rarity == stack.rarity &&
                        it.slot == stack.slot
                }
                val stackId = if (existing != null) {
                    equipmentStacks.update(existing.id) {
                        it.copy(quantity = it.quantity + 1)
                    }
                    existing.id
                } else {
                    equipmentStacks.add(stack)
                    stack.id
                }
                val bagItems = discipleTables.storageBagItems[discipleId]
                discipleTables.storageBagItems[discipleId] = bagItems +
                    StorageBagItem(
                        itemId = stackId,
                        itemType = "equipment_stack",
                        name = item.name,
                        rarity = item.rarity,
                        quantity = 1,
                        obtainedYear = year,
                        obtainedMonth = month
                    )
            }
            "manual" -> {
                val stack = MerchantItemConverter.toManual(item)
                    .copy(quantity = 1)
                val existing = manualStacks.all().find {
                    it.name == stack.name && it.rarity == stack.rarity &&
                        it.type == stack.type
                }
                val stackId = if (existing != null) {
                    manualStacks.update(existing.id) {
                        it.copy(quantity = it.quantity + 1)
                    }
                    existing.id
                } else {
                    manualStacks.add(stack)
                    stack.id
                }
                val bagItems = discipleTables.storageBagItems[discipleId]
                discipleTables.storageBagItems[discipleId] = bagItems +
                    StorageBagItem(
                        itemId = stackId,
                        itemType = "manual_stack",
                        name = item.name,
                        rarity = item.rarity,
                        quantity = 1,
                        obtainedYear = year,
                        obtainedMonth = month
                    )
            }
            "pill" -> {
                val pill = MerchantItemConverter.toPill(item)
                    .copy(quantity = 1)
                val existing = pills.all().find {
                    it.name == pill.name && it.rarity == pill.rarity &&
                        it.category == pill.category && it.grade == pill.grade
                }
                val pillId = if (existing != null) {
                    val newQty = (existing.quantity + 1)
                        .coerceAtMost(inventoryConfig.getMaxStackSize("pill"))
                    pills.update(existing.id) {
                        it.copy(quantity = newQty)
                    }
                    existing.id
                } else {
                    pills.add(pill)
                    pill.id
                }
                val bagItems = discipleTables.storageBagItems[discipleId]
                discipleTables.storageBagItems[discipleId] = bagItems +
                    StorageBagItem(
                        itemId = pillId,
                        itemType = "pill",
                        name = item.name,
                        rarity = item.rarity,
                        quantity = 1,
                        effect = pillToItemEffect(pill),
                        grade = item.grade ?: "中品",
                        obtainedYear = year,
                        obtainedMonth = month
                    )
            }
        }
    }
}
