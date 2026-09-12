package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.engine.system.BagItemReconstructor
import com.xianxia.sect.core.engine.system.ReconstructedBagStack
import com.xianxia.sect.core.engine.system.StackUpdate
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.model.accessoryId
import com.xianxia.sect.core.model.armorId
import com.xianxia.sect.core.model.baseMagicAttack
import com.xianxia.sect.core.model.basePhysicalAttack
import com.xianxia.sect.core.model.bootsId
import com.xianxia.sect.core.model.weaponId
import com.xianxia.sect.core.util.StorageBagUtils
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class DiscipleEquipmentManager @Inject constructor() {

    data class EquipmentProcessResult(
        val disciple: Disciple,
        val newInstances: List<EquipmentInstance>,
        /** 袋内实例条目直接装配（实例可能不在实例表——防双持有），
         *  调用方需将本列表加入实例表 */
        val attachedInstances: List<EquipmentInstance>,
        /** 被更高品阶候选替换卸下的旧实例（已回弟子储物袋），调用方需从实例表移除 */
        val replacedInstances: List<EquipmentInstance>,
        val stackUpdates: List<StackUpdate>
    )

    private data class SlotConfig(
        val slotType: EquipmentSlot,
        val currentEquipIdGetter: (Disciple) -> String?,
        val equipSetter: (Disciple, String) -> Disciple
    )

    private val slotConfigs = listOf(
        SlotConfig(
            slotType = EquipmentSlot.WEAPON,
            currentEquipIdGetter = { it.equipment.weaponId },
            equipSetter = { d, id -> d.copy(equipment = d.equipment.copy(weaponId = id)) }
        ),
        SlotConfig(
            slotType = EquipmentSlot.ARMOR,
            currentEquipIdGetter = { it.equipment.armorId },
            equipSetter = { d, id -> d.copy(equipment = d.equipment.copy(armorId = id)) }
        ),
        SlotConfig(
            slotType = EquipmentSlot.BOOTS,
            currentEquipIdGetter = { it.equipment.bootsId },
            equipSetter = { d, id -> d.copy(equipment = d.equipment.copy(bootsId = id)) }
        ),
        SlotConfig(
            slotType = EquipmentSlot.ACCESSORY,
            currentEquipIdGetter = { it.equipment.accessoryId },
            equipSetter = { d, id -> d.copy(equipment = d.equipment.copy(accessoryId = id)) }
        )
    )

    fun canEquip(disciple: Disciple, stack: EquipmentStack): Boolean {
        return disciple.realm <= stack.minRealm
    }

    // ── 候选模型（B：仓库堆叠 + 储物袋条目统一） ──────────────────────

    private sealed interface EquipSource {
        data class Warehouse(val stack: EquipmentStack) : EquipSource
        data class BagInstance(val bagItem: StorageBagItem, val instance: EquipmentInstance) : EquipSource
        data class BagStack(val bagItem: StorageBagItem, val stack: EquipmentStack) : EquipSource
    }

    private data class EquipCandidate(
        val name: String,
        val slot: EquipmentSlot,
        val rarity: Int,
        val minRealm: Int,
        val hasPhysical: Boolean,
        val hasMagic: Boolean,
        val nurtureLevel: Int,
        val source: EquipSource
    )

    /** 攻击类型匹配度（物攻 ≥ 法攻偏好物理） */
    private fun typeMatch(disciple: Disciple, candidate: EquipCandidate): Int {
        val prefersPhysical = disciple.basePhysicalAttack >= disciple.baseMagicAttack
        return if (prefersPhysical && candidate.hasPhysical) 1
        else if (!prefersPhysical && candidate.hasMagic) 1
        else 0
    }

    /** 统一比较键（降序）：品阶 → 类型匹配 → 孕养等级（严格有序防震荡） */
    private fun equipComparator(disciple: Disciple): Comparator<EquipCandidate> =
        Comparator { a, b ->
            val rarityCmp = a.rarity.compareTo(b.rarity)
            if (rarityCmp != 0) return@Comparator rarityCmp
            val typeCmp = typeMatch(disciple, a).compareTo(typeMatch(disciple, b))
            if (typeCmp != 0) return@Comparator typeCmp
            a.nurtureLevel.compareTo(b.nurtureLevel)
        }

    /** 已装备实例 → 候选（替换比较基准） */
    private fun candidateFromInstance(instance: EquipmentInstance): EquipCandidate =
        EquipCandidate(
            name = instance.name,
            slot = instance.slot,
            rarity = instance.rarity,
            minRealm = instance.minRealm,
            hasPhysical = instance.physicalAttack > 0,
            hasMagic = instance.magicAttack > 0,
            nurtureLevel = instance.nurtureLevel,
            source = EquipSource.BagInstance(StorageBagItem("", "", "", 0), instance)
        )

    /** 单槽处理结果累加器（processEquipSlot 输出面分组，避免超长参数表） */
    private class EquipAccumulator {
        val newInstances = mutableListOf<EquipmentInstance>()
        val attachedInstances = mutableListOf<EquipmentInstance>()
        val replacedInstances = mutableListOf<EquipmentInstance>()
        val stackUpdates = mutableListOf<StackUpdate>()
    }

    /** 储物袋条目 → 候选（equipment_instance 完整实例 / equipment_stack 模板重建） */
    private fun candidateFromBagItem(item: StorageBagItem): EquipCandidate? = when (item.itemType) {
        ITEM_TYPE_EQUIPMENT_INSTANCE ->
            item.equipmentInstance?.let { instance ->
                EquipCandidate(
                    name = item.name,
                    slot = instance.slot,
                    rarity = instance.rarity,
                    minRealm = instance.minRealm,
                    hasPhysical = instance.physicalAttack > 0,
                    hasMagic = instance.magicAttack > 0,
                    nurtureLevel = instance.nurtureLevel,
                    source = EquipSource.BagInstance(item, instance)
                )
            }
        ITEM_TYPE_EQUIPMENT_STACK -> {
            // 模板重建（模板缺失 → 丢弃，对齐 BagItemReconstructor null 语义）
            val reconstructed = BagItemReconstructor.reconstruct(item)
                as? ReconstructedBagStack.Equipment ?: return null
            val stack = reconstructed.stack
            EquipCandidate(
                name = stack.name,
                slot = stack.slot,
                rarity = stack.rarity,
                minRealm = stack.minRealm,
                hasPhysical = stack.physicalAttack > 0,
                hasMagic = stack.magicAttack > 0,
                nurtureLevel = 0,
                source = EquipSource.BagStack(item, stack)
            )
        }
        else -> null
    }

    /** 单槽候选收集：仓库堆叠 + 储物袋条目（境界/锁过滤） */
    private fun collectEquipCandidates(
        disciple: Disciple,
        slotType: EquipmentSlot,
        warehouseStacks: List<EquipmentStack>
    ): List<EquipCandidate> {
        val candidates = mutableListOf<EquipCandidate>()
        warehouseStacks.forEach { stack ->
            if (stack.slot != slotType) return@forEach
            if (!canEquip(disciple, stack)) return@forEach
            if (stack.isLocked) return@forEach
            candidates += EquipCandidate(
                name = stack.name, slot = stack.slot, rarity = stack.rarity,
                minRealm = stack.minRealm,
                hasPhysical = stack.physicalAttack > 0,
                hasMagic = stack.magicAttack > 0,
                nurtureLevel = 0,
                source = EquipSource.Warehouse(stack)
            )
        }
        disciple.equipment.storageBagItems.forEach { item ->
            val candidate = candidateFromBagItem(item) ?: return@forEach
            if (candidate.slot != slotType) return@forEach
            if (!canEquip(disciple, candidate)) return@forEach
            candidates += candidate
        }
        return candidates
    }

    fun processAutoEquipFromWarehouse(
        disciple: Disciple,
        warehouseStacks: List<EquipmentStack>,
        equipmentInstances: Map<String, EquipmentInstance>,
        gameYear: Int,
        gameMonth: Int
    ): EquipmentProcessResult {
        var updatedDisciple = disciple
        val acc = EquipAccumulator()

        slotConfigs.forEach { config ->
            updatedDisciple = processEquipSlot(
                updatedDisciple, config, warehouseStacks, equipmentInstances,
                gameYear, gameMonth, acc
            ) ?: updatedDisciple
        }

        return EquipmentProcessResult(
            disciple = updatedDisciple,
            newInstances = acc.newInstances,
            attachedInstances = acc.attachedInstances,
            replacedInstances = acc.replacedInstances,
            stackUpdates = acc.stackUpdates
        )
    }

    /**
     * 单槽自动装配/替换（B：更高品阶替换 + 袋内实例/堆叠装配）。
     * @return 更新后的弟子；无变化返回 null（调用方保留原值）
     */
    @Suppress("ReturnCount")  // 多出口=逐槽判定天然结构：空候选/最优缺失/实例缺失/不更优/成功
    private fun processEquipSlot(
        disciple: Disciple,
        config: SlotConfig,
        warehouseStacks: List<EquipmentStack>,
        equipmentInstances: Map<String, EquipmentInstance>,
        gameYear: Int,
        gameMonth: Int,
        acc: EquipAccumulator
    ): Disciple? {
        var updatedDisciple = disciple
        val currentEquipId = config.currentEquipIdGetter(updatedDisciple)

        val candidates = collectEquipCandidates(updatedDisciple, config.slotType, warehouseStacks)
        if (candidates.isEmpty()) return null

        val best = candidates.maxWithOrNull(equipComparator(updatedDisciple)) ?: return null

        // 替换判定：槽位已占用且候选不严格更优 → 不动
        if (!currentEquipId.isNullOrEmpty()) {
            val current = equipmentInstances[currentEquipId] ?: return null
            if (equipComparator(updatedDisciple).compare(best, candidateFromInstance(current)) <= 0) {
                return null
            }
            // 旧装备卸装入袋（实例保真入袋 + 从实例表移除由调用方执行）
            updatedDisciple = depositToBag(updatedDisciple, current, gameYear, gameMonth)
            acc.replacedInstances.add(current)
        }

        // 装配
        updatedDisciple = applyEquipAction(updatedDisciple, config, best.source, acc)
        return updatedDisciple
    }

    /** 装配动作（仓库堆叠 / 袋内实例 / 袋内堆叠模板重建） */
    private fun applyEquipAction(
        disciple: Disciple,
        config: SlotConfig,
        source: EquipSource,
        acc: EquipAccumulator
    ): Disciple {
        var updated = disciple
        when (source) {
            is EquipSource.Warehouse -> {
                val instanceId = java.util.UUID.randomUUID().toString()
                val newInstance = source.stack.toInstance(
                    id = instanceId, ownerId = updated.id, isEquipped = true
                )
                val newQty = source.stack.quantity - 1
                val stackUpdate = if (newQty <= 0) {
                    StackUpdate(stackId = source.stack.id, newQuantity = 0, isDeletion = true)
                } else {
                    StackUpdate(stackId = source.stack.id, newQuantity = newQty, isDeletion = false)
                }
                updated = config.equipSetter(updated, instanceId)
                acc.newInstances.add(newInstance)
                acc.stackUpdates.add(stackUpdate)
            }
            is EquipSource.BagInstance -> {
                // 袋内完整实例：直接装配（实例可能不在实例表——防双持有，
                // 调用方按 attachedInstances 重建/更新入表）
                val attached = source.instance.copy(isEquipped = true, ownerId = updated.id)
                updated = config.equipSetter(updated, attached.id)
                updated = updated.copy(
                    equipment = updated.equipment.copy(
                        storageBagItems = updated.equipment.storageBagItems
                            .filterNot { it.itemId == source.bagItem.itemId }
                    )
                )
                acc.attachedInstances.add(attached)
            }
            is EquipSource.BagStack -> {
                // 模板重建实例（reconstruct 已保证模板存在）
                val instanceId = java.util.UUID.randomUUID().toString()
                val newInstance = source.stack.copy(quantity = 1).toInstance(
                    id = instanceId, ownerId = updated.id, isEquipped = true
                )
                updated = config.equipSetter(updated, instanceId)
                updated = updated.copy(
                    equipment = updated.equipment.copy(
                        storageBagItems = StorageBagUtils.decreaseItemQuantity(
                            updated.equipment.storageBagItems,
                            source.bagItem.itemId
                        )
                    )
                )
                acc.newInstances.add(newInstance)
            }
        }
        return updated
    }

    /** 旧装备卸装入袋（实例保真入袋，对齐 unequipEquipmentLogic / depositOldEquipmentToBag） */
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

    fun canEquip(disciple: Disciple, instance: EquipmentInstance): Boolean {
        return disciple.realm <= instance.minRealm
    }

    private fun canEquip(disciple: Disciple, candidate: EquipCandidate): Boolean {
        return disciple.realm <= candidate.minRealm
    }

    fun getEquipSlot(disciple: Disciple, slot: EquipmentSlot): String? {
        return when (slot) {
            EquipmentSlot.WEAPON -> disciple.equipment.weaponId
            EquipmentSlot.ARMOR -> disciple.equipment.armorId
            EquipmentSlot.BOOTS -> disciple.equipment.bootsId
            EquipmentSlot.ACCESSORY -> disciple.equipment.accessoryId
        }
    }
}
