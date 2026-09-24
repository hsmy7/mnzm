package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.GiftRelationshipType
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.StorageBagUtils
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 师徒智能赠送处理器。
 *
 * 当弟子突破境界后，其师父或徒弟有机会从自身储物袋中挑选物品赠送给突破者表示祝贺。
 * 赠送优先级：装备空槽 > 功法空槽 > 突破丹药 > 其他丹药 > 材料/草药/种子。
 *
 * 此处理器在 [DiscipleBreakthroughHandler.processRealtimeBreakthroughs]
 * 的同一 [stateStore.update] 事务内被调用，所有状态读写均为原子操作。
 */
@Singleton
@GameService("RelativeGiftHandler")
class RelativeGiftHandler @Inject constructor(
    private val rngManager: GameRngManager
) {
    private val rng get() = rngManager.getRng(RngPartition.SYSTEM)

    companion object {
        /** 赠送者储物袋最少保留物品数，防止被清空 */
        private const val MIN_BAG_ITEMS_TO_KEEP = 1

        /**
         * 默认功法槽最大数量（不含天赋加成）。
         * 天赋提供的额外槽位极少见（需特定稀有天赋且加成小），
         * 此处使用基准值 6 避免为赠送决策组装完整 Disciple 对象。
         * 即使低估导致功法未赠送，接收者的自动学习机制仍会兜底。
         */
        private const val DEFAULT_MAX_MANUAL_SLOTS = 6

        // 默认赠送概率（与 GameConfigData.RelativeGiftSection 默认值同步）
        private const val MASTER_GIFT_PROB = 0.40
        private const val APPRENTICE_GIFT_PROB = 0.30
    }

    // ==================== 公开入口 ====================

    /**
     * 为突破弟子处理师父与徒弟的智能赠送。
     *
     * @param discipleId 突破弟子的 Int ID
     * @param tables 组件表（已写入突破后的最新状态）
     * @param state 可变游戏状态（用于读取装备/功法仓库数据）
     */
    fun processGiftsForBreakthrough(
        discipleId: Int,
        tables: DiscipleTables,
        state: MutableGameState
    ) {
        val relatives = findRelatives(discipleId, tables)
        if (relatives.isEmpty()) return

        val receiverRealm = tables.realms.getOrDefault(discipleId, 9)

        for (giverId in relatives) {
            val relationship = classifyRelationship(giverId, discipleId, tables)
            val probability = getGiftProbability(relationship)
            if (rng.nextDouble() >= probability) continue

            val result = tryGiveGift(giverId, discipleId, receiverRealm, tables, state)
            // 记录赠礼日志
            if (result is GiftResult.Success) {
                val giverName = tables.names.getOrNull(giverId) ?: "同门"
                val currentEvents = tables.lifeEvents.getOrDefault(
                    discipleId, emptyList()
                )
                tables.lifeEvents[discipleId] = currentEvents +
                    "从${giverName}处获得${result.giftItemName}"
            }
        }
    }

    // ==================== 师徒关系查找 ====================

    /**
     * 查找指定弟子的存活师父与全部存活徒弟。
     * 师父走正向查表 O(log n)，徒弟一次遍历 [tables.ids] 反向匹配。
     */
    internal fun findRelatives(discipleId: Int, tables: DiscipleTables): List<Int> {
        val seen = mutableSetOf<Int>()
        val myIdStr = discipleId.toString()

        // 正向：师父
        tables.masterIds.getOrNull(discipleId)?.toIntOrNull()?.let { mid ->
            if (isAlive(mid, discipleId, tables)) seen.add(mid)
        }

        // 反向：徒弟
        for (id in tables.ids) {
            if (id == discipleId || !isAlive(id, discipleId, tables) || id in seen) continue
            if (tables.masterIds.getOrNull(id) == myIdStr) seen.add(id)
        }

        return seen.toList()
    }

    private fun isAlive(id: Int, selfId: Int, tables: DiscipleTables): Boolean {
        return id != selfId && tables.isAlive.getOrDefault(id, 0) == 1
    }

    // ==================== 关系分类 ====================

    /**
     * 判定 giverId 对 receiverId 的师徒关系类型。
     *
     * 前提：调用方传入的 giverId 由 [findRelatives] 筛出，即二者必为师徒之一。
     * giver 是 receiver 的师父 → [GiftRelationshipType.MASTER]；否则为徒弟。
     */
    internal fun classifyRelationship(
        giverId: Int,
        receiverId: Int,
        tables: DiscipleTables
    ): GiftRelationshipType {
        val gs = giverId.toString()
        return if (tables.masterIds.getOrNull(receiverId) == gs) {
            GiftRelationshipType.MASTER
        } else {
            GiftRelationshipType.APPRENTICE
        }
    }

    // ==================== 概率配置 ====================

    private fun getGiftProbability(type: GiftRelationshipType): Double = when (type) {
        GiftRelationshipType.MASTER -> MASTER_GIFT_PROB
        GiftRelationshipType.APPRENTICE -> APPRENTICE_GIFT_PROB
    }

    // ==================== 赠送执行 ====================

    /**
     * 赠送结果。
     */
    sealed interface GiftResult {
        data class Success(
            val giftItemId: String,
            val giftItemName: String
        ) : GiftResult
        data object BagTooSmall : GiftResult
        data object BagEmpty : GiftResult
        data object NoSuitableItem : GiftResult
    }

    /**
     * 尝试从 giver 储物袋中选物品赠送给 receiver。
     */
    internal fun tryGiveGift(
        giverId: Int,
        receiverId: Int,
        receiverRealm: Int,
        tables: DiscipleTables,
        state: MutableGameState
    ): GiftResult {
        val giverBag = tables.storageBagItems.getOrNull(giverId)
            ?: return GiftResult.BagEmpty
        if (giverBag.isEmpty())
            return GiftResult.BagEmpty
        if (giverBag.size <= MIN_BAG_ITEMS_TO_KEEP)
            return GiftResult.BagTooSmall

        val selected = selectBestGift(giverBag, receiverId, receiverRealm, tables, state)
            ?: return GiftResult.NoSuitableItem

        // 从赠送者储物袋移除
        tables.storageBagItems[giverId] =
            StorageBagUtils.decreaseItemQuantity(giverBag, selected.itemId, 1)

        // 添加到接收者储物袋
        val receiverBag = tables.storageBagItems.getOrNull(receiverId) ?: emptyList()
        tables.storageBagItems[receiverId] =
            StorageBagUtils.increaseItemQuantity(receiverBag, selected.copy(quantity = 1))

        return GiftResult.Success(selected.itemId, selected.name)
    }

    // ==================== 物品选择优先级 ====================

    /**
     * 按优先级从赠送者储物袋中选出最佳赠送物品。
     *
     * 优先级：
     * 1. 装备（接收者有空闲槽位，匹配槽位类型 + 境界要求）
     * 2. 功法（接收者功法槽未满，满足境界要求，未学会同名功法）
     * 3. 突破丹药（匹配接收者当前境界，选突破率加成最高者）
     * 4. 其他丹药（稀有度最高者）
     * 5. 材料/草药/种子（稀有度最高者）
     */
    internal fun selectBestGift(
        bagItems: List<StorageBagItem>,
        receiverId: Int,
        receiverRealm: Int,
        tables: DiscipleTables,
        state: MutableGameState
    ): StorageBagItem? {
        if (bagItems.isEmpty()) return null

        // 1. 装备优先（接收者有空闲槽位，匹配槽位类型 + 境界要求）
        val emptySlots = getEmptyEquipmentSlots(receiverId, tables)
        if (emptySlots.isNotEmpty()) {
            val equipmentMatch = bagItems.asSequence()
                .filter { it.itemType == "equipment_stack" }
                .mapNotNull { item ->
                    val stack = state.equipmentStacks.get(item.itemId) ?: return@mapNotNull null
                    if (stack.slot in emptySlots && receiverRealm <= stack.minRealm)
                        item to stack.rarity
                    else null
                }
                .maxByOrNull { it.second }
            if (equipmentMatch != null) return equipmentMatch.first
        }

        // 2. 功法次优（接收者功法槽未满，满足境界要求，未学会同名功法）
        if (isManualSlotAvailable(receiverId, tables)) {
            val learnedNames = getLearnedManualNames(receiverId, tables, state)
            val manualMatch = bagItems.asSequence()
                .filter { it.itemType == "manual_stack" }
                .mapNotNull { item ->
                    val stack = state.manualStacks.get(item.itemId) ?: return@mapNotNull null
                    if (receiverRealm > stack.minRealm || stack.name in learnedNames) null
                    else item to stack.rarity
                }
                .maxByOrNull { it.second }
            if (manualMatch != null) return manualMatch.first
        }

        // 3-5. 兜底链：突破丹药（匹配境界）→ 其他丹药（稀有度最高）→ 材料/草药/种子
        return bestBreakthroughPill(bagItems, receiverRealm)
            ?: bestPillByRarity(bagItems)
            ?: bestMaterialByRarity(bagItems)
    }

    /** 突破丹药候选：匹配接收者当前境界，选突破率加成最高者 */
    private fun bestBreakthroughPill(bagItems: List<StorageBagItem>, receiverRealm: Int): StorageBagItem? =
        bagItems.asSequence()
            .filter {
                it.itemType == "pill" &&
                    it.effect?.pillType == "breakthrough" &&
                    it.effect?.targetRealm == receiverRealm
            }
            .maxByOrNull { it.effect?.breakthroughChance ?: 0.0 }

    /** 其他丹药候选：稀有度最高者 */
    private fun bestPillByRarity(bagItems: List<StorageBagItem>): StorageBagItem? =
        bagItems.asSequence()
            .filter { it.itemType == "pill" }
            .maxByOrNull { it.rarity }

    /** 材料/草药/种子候选：稀有度最高者 */
    private fun bestMaterialByRarity(bagItems: List<StorageBagItem>): StorageBagItem? =
        bagItems.asSequence()
            .filter { it.itemType in setOf("material", "herb", "seed") }
            .maxByOrNull { it.rarity }

    // ==================== 槽位检查 ====================

    /**
     * 获取接收者的空闲装备槽位列表。
     * 槽位 ID 为空字符串或 null 表示空闲。
     */
    internal fun getEmptyEquipmentSlots(
        discipleId: Int,
        tables: DiscipleTables
    ): List<EquipmentSlot> {
        val empty = mutableListOf<EquipmentSlot>()
        if (tables.weaponIds.getOrNull(discipleId).isNullOrEmpty())
            empty.add(EquipmentSlot.WEAPON)
        if (tables.armorIds.getOrNull(discipleId).isNullOrEmpty())
            empty.add(EquipmentSlot.ARMOR)
        if (tables.bootsIds.getOrNull(discipleId).isNullOrEmpty())
            empty.add(EquipmentSlot.BOOTS)
        if (tables.accessoryIds.getOrNull(discipleId).isNullOrEmpty())
            empty.add(EquipmentSlot.ACCESSORY)
        return empty
    }

    /**
     * 判断接收者功法槽是否还有空位。
     * 默认最大 6 槽，天赋可增加但极少见——此处简化处理，
     * 即使低估导致功法未赠送，自动学习机制会兜底。
     */
    internal fun isManualSlotAvailable(
        discipleId: Int,
        tables: DiscipleTables
    ): Boolean {
        val current = tables.manualIds.getOrNull(discipleId) ?: emptyList()
        return current.size < DEFAULT_MAX_MANUAL_SLOTS
    }

    private fun getLearnedManualNames(
        discipleId: Int,
        tables: DiscipleTables,
        state: MutableGameState
    ): Set<String> {
        val ids = tables.manualIds.getOrNull(discipleId) ?: return emptySet()
        return ids.mapNotNull { state.manualInstances.get(it)?.name }.toSet()
    }
}
