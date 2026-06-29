package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.GiftRelationshipType
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.StorageBagUtils
import com.xianxia.sect.core.engine.annotation.GameService
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * 亲属智能赠送处理器。
 *
 * 当弟子突破境界后，其亲属（道侣/师父/徒弟/父母/子嗣/兄弟姐妹）
 * 有机会从自身储物袋中挑选物品赠送给突破者表示祝贺。
 * 赠送优先级：装备空槽 > 功法空槽 > 突破丹药 > 其他丹药 > 材料/草药/种子。
 *
 * 此处理器在 [DiscipleBreakthroughHandler.processRealtimeBreakthroughs]
 * 的同一 [stateStore.update] 事务内被调用，所有状态读写均为原子操作。
 */
@Singleton
@GameService("RelativeGiftHandler")
class RelativeGiftHandler @Inject constructor() {

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
        private const val PARTNER_GIFT_PROB = 0.45
        private const val MASTER_GIFT_PROB = 0.40
        private const val APPRENTICE_GIFT_PROB = 0.30
        private const val PARENT_GIFT_PROB = 0.35
        private const val CHILD_GIFT_PROB = 0.50
        private const val SIBLING_GIFT_PROB = 0.25
    }

    // ==================== 公开入口 ====================

    /**
     * 为突破弟子处理所有亲属的智能赠送。
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
        val receiverAge = tables.ages[discipleId]

        for (giverId in relatives) {
            val relationship = classifyRelationship(giverId, discipleId, tables)
            val probability = getGiftProbability(relationship)
            if (Random.nextDouble() >= probability) continue

            val result = tryGiveGift(giverId, discipleId, receiverRealm, tables, state)
            // 记录赠礼日志
            if (result is GiftResult.Success) {
                val giverName = tables.names.getOrNull(giverId) ?: "亲属"
                val currentEvents = tables.lifeEvents.getOrDefault(
                    discipleId, emptyList()
                )
                tables.lifeEvents[discipleId] = currentEvents +
                    "${receiverAge}岁：从${giverName}处获得${result.giftItemName}"
            }
        }
    }

    // ==================== 亲属查找 ====================

    /**
     * 查找指定弟子的所有存活亲属。
     * 一次遍历 [tables.ids] 覆盖全部 6 种反向关系，
     * 正向关系（道侣/师父/父母）通过直接查表 O(log n) 获取。
     */
    internal fun findRelatives(discipleId: Int, tables: DiscipleTables): List<Int> {
        val seen = mutableSetOf<Int>()
        val myIdStr = discipleId.toString()

        // 正向查找：道侣、师父、父母
        collectForwardRelatives(discipleId, myIdStr, tables, seen)

        // 反向查找：道侣反向、徒弟、子嗣、兄弟姐妹 —— 一次遍历
        if (seen.size < 6) {
            collectReverseRelatives(discipleId, myIdStr, tables, seen)
        }

        return seen.toList()
    }

    /** 正向查找：直接查表获取道侣/师父/父母（O(log n)） */
    private fun collectForwardRelatives(
        discipleId: Int,
        myIdStr: String,
        tables: DiscipleTables,
        seen: MutableSet<Int>
    ) {
        // 道侣
        tables.partnerIds.getOrNull(discipleId)?.toIntOrNull()?.let { pid ->
            if (isAlive(pid, discipleId, tables)) seen.add(pid)
        }
        // 师父
        tables.masterIds.getOrNull(discipleId)?.toIntOrNull()?.let { mid ->
            if (isAlive(mid, discipleId, tables)) seen.add(mid)
        }
        // 父母
        val p1 = tables.parentId1s.getOrNull(discipleId)
        val p2 = tables.parentId2s.getOrNull(discipleId)
        p1?.toIntOrNull()?.let { if (isAlive(it, discipleId, tables)) seen.add(it) }
        p2?.toIntOrNull()?.let { if (isAlive(it, discipleId, tables)) seen.add(it) }
    }

    /** 反向查找：一次遍历 tables.ids 覆盖道侣反向/徒弟/子嗣/兄弟姐妹 */
    private fun collectReverseRelatives(
        discipleId: Int,
        myIdStr: String,
        tables: DiscipleTables,
        seen: MutableSet<Int>
    ) {
        val myParents = if (seen.size >= 6) emptySet()
            else setOfNotNull(
                tables.parentId1s.getOrNull(discipleId),
                tables.parentId2s.getOrNull(discipleId)
            )

        for (id in tables.ids) {
            if (id == discipleId || !isAlive(id, discipleId, tables)) continue
            if (id in seen) continue

            val partnerId = tables.partnerIds.getOrNull(id)
            val masterId = tables.masterIds.getOrNull(id)
            val cp1 = tables.parentId1s.getOrNull(id)
            val cp2 = tables.parentId2s.getOrNull(id)

            when {
                partnerId == myIdStr -> seen.add(id)                    // 道侣反向
                masterId == myIdStr -> seen.add(id)                     // 徒弟
                cp1 == myIdStr || cp2 == myIdStr -> seen.add(id)        // 子嗣
                myParents.isNotEmpty() && cp1 != null -> {
                    // 兄弟姐妹：有共同父母
                    val otherParents = setOfNotNull(cp1, cp2)
                    if (otherParents.isNotEmpty() &&
                        myParents.intersect(otherParents).isNotEmpty()
                    ) seen.add(id)
                }
            }
        }
    }

    private fun isAlive(id: Int, selfId: Int, tables: DiscipleTables): Boolean {
        return id != selfId && tables.isAlive.getOrDefault(id, 0) == 1
    }

    // ==================== 关系分类 ====================

    /**
     * 判定 giverId 对 receiverId 的亲属关系类型。
     * 同一对弟子可能满足多种关系，按亲密度优先级返回：
     * 道侣 > 父母 > 子嗣 > 师父 > 徒弟 > 兄弟姐妹。
     */
    internal fun classifyRelationship(
        giverId: Int,
        receiverId: Int,
        tables: DiscipleTables
    ): GiftRelationshipType {
        val gs = giverId.toString()
        val rs = receiverId.toString()

        // 道侣
        if (tables.partnerIds.getOrNull(giverId) == rs ||
            tables.partnerIds.getOrNull(receiverId) == gs
        ) return GiftRelationshipType.PARTNER

        // 父母（giver 是 receiver 的父母）
        if (tables.parentId1s.getOrNull(receiverId) == gs ||
            tables.parentId2s.getOrNull(receiverId) == gs
        ) return GiftRelationshipType.PARENT

        // 子嗣（receiver 是 giver 的父母）
        if (tables.parentId1s.getOrNull(giverId) == rs ||
            tables.parentId2s.getOrNull(giverId) == rs
        ) return GiftRelationshipType.CHILD

        // 师父（giver 是 receiver 的师父）
        if (tables.masterIds.getOrNull(receiverId) == gs)
            return GiftRelationshipType.MASTER

        // 徒弟（receiver 是 giver 的师父）
        if (tables.masterIds.getOrNull(giverId) == rs)
            return GiftRelationshipType.APPRENTICE

        // 兄弟姐妹
        return GiftRelationshipType.SIBLING
    }

    // ==================== 概率配置 ====================

    private fun getGiftProbability(type: GiftRelationshipType): Double = when (type) {
        GiftRelationshipType.PARTNER -> PARTNER_GIFT_PROB
        GiftRelationshipType.MASTER -> MASTER_GIFT_PROB
        GiftRelationshipType.APPRENTICE -> APPRENTICE_GIFT_PROB
        GiftRelationshipType.PARENT -> PARENT_GIFT_PROB
        GiftRelationshipType.CHILD -> CHILD_GIFT_PROB
        GiftRelationshipType.SIBLING -> SIBLING_GIFT_PROB
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

        // 1. 装备优先
        val emptySlots = getEmptyEquipmentSlots(receiverId, tables)
        if (emptySlots.isNotEmpty()) {
            bagItems.asSequence()
                .filter { it.itemType == "equipment_stack" }
                .mapNotNull { item ->
                    val stack = state.equipmentStacks.get(item.itemId) ?: return@mapNotNull null
                    if (stack.slot in emptySlots && receiverRealm <= stack.minRealm)
                        item to stack.rarity
                    else null
                }
                .maxByOrNull { it.second }
                ?.let { return it.first }
        }

        // 2. 功法次优
        if (isManualSlotAvailable(receiverId, tables)) {
            val learnedNames = getLearnedManualNames(receiverId, tables, state)
            bagItems.asSequence()
                .filter { it.itemType == "manual_stack" }
                .mapNotNull { item ->
                    val stack = state.manualStacks.get(item.itemId) ?: return@mapNotNull null
                    if (receiverRealm > stack.minRealm || stack.name in learnedNames) null
                    else item to stack.rarity
                }
                .maxByOrNull { it.second }
                ?.let { return it.first }
        }

        // 3. 突破丹药
        bagItems.asSequence()
            .filter {
                it.itemType == "pill" &&
                    it.effect?.pillType == "breakthrough" &&
                    it.effect?.targetRealm == receiverRealm
            }
            .maxByOrNull { it.effect?.breakthroughChance ?: 0.0 }
            ?.let { return it }

        // 4. 其他丹药
        bagItems.asSequence()
            .filter { it.itemType == "pill" }
            .maxByOrNull { it.rarity }
            ?.let { return it }

        // 5. 材料/草药/种子
        return bagItems.asSequence()
            .filter { it.itemType in setOf("material", "herb", "seed") }
            .maxByOrNull { it.rarity }
    }

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
