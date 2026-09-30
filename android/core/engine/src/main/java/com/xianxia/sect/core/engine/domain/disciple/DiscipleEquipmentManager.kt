package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.engine.domain.battle.resolvedInnateDamageType
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.util.StorageBagUtils
import javax.inject.Inject
import javax.inject.Singleton


/**
 * 弟子装备自动管理器（装备重构 B3：六部位、单轨实例、等级随实例）。
 *
 * 自动装配候选源 = **储物袋内完整实例**（装备堆叠已随 B3 移除，R6）；
 * 比较键（降序）：品阶 → 部位适配（固有属性 vs 套装流派）→ 等级（严格有序防震荡）。
 * 与 C++ `auto_gear.h` 逐位对齐（对拍 `DiffPhaseSettlementTest`）。
 */
@Singleton
class DiscipleEquipmentManager @Inject constructor() {

    data class EquipmentProcessResult(
        val disciple: Disciple,
        val newInstances: List<EquipmentInstance> = emptyList(),
        /** 袋内实例条目直接装配（实例可能不在实例表——防双持有），
         *  调用方需将本列表加入实例表 */
        val attachedInstances: List<EquipmentInstance>,
        /** 被更高品阶候选替换卸下的旧实例（已回弟子储物袋、保留实例表内下线态），
         *  调用方需同步实例表 isEquipped=false */
        val replacedInstances: List<EquipmentInstance>
    )

    private fun currentEquipId(disciple: Disciple, slot: EquipmentSlot): String =
        disciple.equipment.slotId(slot)

    private fun setEquipId(disciple: Disciple, slot: EquipmentSlot, id: String): Disciple =
        disciple.copy(equipment = disciple.equipment.copy().apply { setSlotId(slot, id) })

    fun canEquip(disciple: Disciple, instance: EquipmentInstance): Boolean {
        return disciple.realm <= instance.minRealm
    }

    // ── 候选模型（B3：储物袋实例单源） ──────────────────────

    /** 候选：品阶/部位/等级 + 套装流派适配面 */
    private data class EquipCandidate(
        val part: EquipmentSlot,
        val rarity: Int,
        val minRealm: Int,
        val setId: String,
        val level: Int,
        val source: EquipmentInstance
    )

    /**
     * 流派匹配度（属性单列口径 B1 §15.4 + 套装流派 §3.3）——
     * 固有物理的弟子优先物理套、固有法术的优先法术套；散件中性。
     */
    private fun schoolMatch(disciple: Disciple, candidate: EquipCandidate): Int {
        if (candidate.setId.isEmpty()) return 0
        val isPhysical = disciple.resolvedInnateDamageType() == DamageType.PHYSICAL
        val physicalSet = candidate.setId == SET_ID_PHYSICAL
        return if (isPhysical == physicalSet) 1 else 0
    }

    /** 统一比较键（降序）：品阶 → 流派匹配 → 等级（严格有序防震荡） */
    private fun equipComparator(disciple: Disciple): Comparator<EquipCandidate> =
        Comparator { a, b ->
            val rarityCmp = a.rarity.compareTo(b.rarity)
            if (rarityCmp != 0) return@Comparator rarityCmp
            val typeCmp = schoolMatch(disciple, a).compareTo(schoolMatch(disciple, b))
            if (typeCmp != 0) return@Comparator typeCmp
            a.level.compareTo(b.level)
        }

    /** 储物袋条目 → 候选（仅 equipment_instance 完整实例；其余装备类条目不参与自动装配） */
    private fun candidateFromBagItem(item: StorageBagItem): EquipCandidate? {
        val instance = item.equipmentInstance ?: return null
        return EquipCandidate(
            part = instance.part,
            rarity = instance.rarity,
            minRealm = instance.minRealm,
            setId = instance.setId,
            level = instance.level,
            source = instance
        )
    }

    /** 单槽候选收集：储物袋实例（境界过滤） */
    private fun collectEquipCandidates(
        disciple: Disciple,
        slotType: EquipmentSlot
    ): List<EquipCandidate> =
        disciple.equipment.storageBagItems.mapNotNull { item ->
            val candidate = candidateFromBagItem(item) ?: return@mapNotNull null
            if (candidate.part != slotType) return@mapNotNull null
            if (!canEquip(disciple, candidate.source)) return@mapNotNull null
            candidate
        }

    fun processAutoEquipFromWarehouse(
        disciple: Disciple,
        warehouseStacks: List<Nothing> = emptyList(),
        equipmentInstances: Map<String, EquipmentInstance> = emptyMap(),
        gameYear: Int = 0,
        gameMonth: Int = 0
    ): EquipmentProcessResult {
        @Suppress("UNUSED_PARAMETER") val unusedStacks = warehouseStacks
        var updatedDisciple = disciple
        val attached = mutableListOf<EquipmentInstance>()
        val replaced = mutableListOf<EquipmentInstance>()

        for (slot in EquipmentSlot.displayOrder) {
            val updated = processEquipSlot(
                updatedDisciple, slot, equipmentInstances, gameYear, gameMonth, attached, replaced
            )
            if (updated != null) updatedDisciple = updated
        }

        return EquipmentProcessResult(
            disciple = updatedDisciple,
            attachedInstances = attached,
            replacedInstances = replaced
        )
    }

    /**
     * 单槽自动装配/替换（更高品阶替换 + 袋内实例装配）。
     * @return 更新后的弟子；无变化返回 null（调用方保留原值）
     */
    @Suppress("ReturnCount")  // 多出口=逐槽判定天然结构：空候选/最优缺失/实例缺失/不更优/成功
    private fun processEquipSlot(
        disciple: Disciple,
        slot: EquipmentSlot,
        equipmentInstances: Map<String, EquipmentInstance>,
        gameYear: Int,
        gameMonth: Int,
        attached: MutableList<EquipmentInstance>,
        replaced: MutableList<EquipmentInstance>
    ): Disciple? {
        var updatedDisciple = disciple
        val currentEquipId = currentEquipId(updatedDisciple, slot)

        val candidates = collectEquipCandidates(updatedDisciple, slot)
        if (candidates.isEmpty()) return null

        val best = candidates.maxWithOrNull(equipComparator(updatedDisciple)) ?: return null

        // 替换判定：槽位已占用且候选不严格更优 → 不动
        if (currentEquipId.isNotEmpty()) {
            val current = equipmentInstances[currentEquipId]
            if (current != null) {
                val currentCandidate = EquipCandidate(
                    part = current.part,
                    rarity = current.rarity,
                    minRealm = current.minRealm,
                    setId = current.setId,
                    level = current.level,
                    source = current
                )
                if (equipComparator(updatedDisciple).compare(best, currentCandidate) <= 0) {
                    return null
                }
                // 旧装备卸装入袋（等级/词条随实例保真入袋）
                updatedDisciple = depositToBag(updatedDisciple, current, gameYear, gameMonth)
                replaced.add(current)
            }
        }

        // 装配：袋内完整实例直接装配（实例可能不在实例表——防双持有，
        // 调用方按 attachedInstances 重建/更新入表）
        val equipped = best.source.copy(isEquipped = true, ownerId = updatedDisciple.id)
        updatedDisciple = setEquipId(updatedDisciple, slot, equipped.id)
        updatedDisciple = updatedDisciple.copy(
            equipment = updatedDisciple.equipment.copy(
                storageBagItems = updatedDisciple.equipment.storageBagItems
                    .filterNot { it.itemId == best.source.id && it.equipmentInstance != null }
            )
        )
        attached.add(equipped)
        return updatedDisciple
    }

    /** 旧装备卸装入袋（实例保真入袋，对齐 unequipEquipmentLogic） */
    private fun depositToBag(
        disciple: Disciple,
        old: EquipmentInstance,
        gameYear: Int,
        gameMonth: Int
    ): Disciple = disciple.copy(
        equipment = disciple.equipment.copy(
            storageBagItems = StorageBagUtils.increaseItemQuantity(
                disciple.equipment.storageBagItems,
                StorageBagItem(
                    itemId = old.id, itemType = ITEM_TYPE_EQUIPMENT_INSTANCE,
                    name = old.name, rarity = old.rarity, quantity = 1,
                    obtainedYear = gameYear, obtainedMonth = gameMonth,
                    equipmentInstance = old
                )
            )
        )
    )

    fun getEquipSlot(disciple: Disciple, slot: EquipmentSlot): String? =
        disciple.equipment.slotId(slot).takeIf { it.isNotEmpty() }

    companion object {
        /** 物理套装 id（与 [com.xianxia.sect.core.registry.EquipmentSetDatabase] 的 lietian 对应） */
        const val SET_ID_PHYSICAL = "lietian"
    }
}
