package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.engine.system.BagItemReconstructor
import com.xianxia.sect.core.engine.system.ReconstructedBagStack
import com.xianxia.sect.core.engine.system.StackUpdate
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.model.baseMagicAttack
import com.xianxia.sect.core.model.basePhysicalAttack
import com.xianxia.sect.core.model.currentHp
import com.xianxia.sect.core.model.currentMp
import com.xianxia.sect.core.util.StorageBagUtils
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class DiscipleManualManager @Inject constructor() {

    data class ManualLearnResult(
        val disciple: Disciple,
        val newInstance: ManualInstance?,
        /** B（2026-08-31）：袋内实例条目直接学习（实例可能不在实例表——防双持有），
         *  调用方需将本实例加入实例表 */
        val attachedInstance: ManualInstance?,
        /** 被更高品阶候选替换遗忘的旧实例（已回弟子储物袋），调用方需从实例表移除 */
        val replacedInstance: ManualInstance?,
        val stackUpdate: StackUpdate?
    )

    // ── 候选模型（B：仓库堆叠 + 储物袋条目统一） ──────────────────────

    private sealed interface ManualSource {
        data class Warehouse(val stack: ManualStack) : ManualSource
        data class BagInstance(val bagItem: StorageBagItem, val instance: ManualInstance) : ManualSource
        data class BagStack(val bagItem: StorageBagItem, val instance: ManualInstance) : ManualSource
    }

    private data class ManualCandidate(
        val name: String,
        val type: ManualType,
        val rarity: Int,
        val minRealm: Int,
        val hasPhysical: Boolean,
        val hasMagic: Boolean,
        val source: ManualSource
    )

    private fun typeMatch(disciple: Disciple, candidate: ManualCandidate): Int {
        val prefersPhysical = disciple.basePhysicalAttack >= disciple.baseMagicAttack
        return if (prefersPhysical && candidate.hasPhysical) 1
        else if (!prefersPhysical && candidate.hasMagic) 1
        else 0
    }

    /** 统一比较键（降序）：品阶 → 类型匹配（严格有序防震荡） */
    private fun manualComparator(disciple: Disciple): Comparator<ManualCandidate> =
        Comparator { a, b ->
            val rarityCmp = a.rarity.compareTo(b.rarity)
            if (rarityCmp != 0) return@Comparator rarityCmp
            typeMatch(disciple, a).compareTo(typeMatch(disciple, b))
        }

    private fun candidateFromInstance(instance: ManualInstance): ManualCandidate =
        ManualCandidate(
            name = instance.name,
            type = instance.type,
            rarity = instance.rarity,
            minRealm = instance.minRealm,
            hasPhysical = instance.skillDamageType == "physical",
            hasMagic = instance.skillDamageType == "magic",
            source = ManualSource.BagInstance(StorageBagItem("", "", "", 0), instance)
        )

    /** 储物袋条目 → 候选（manual_instance 完整实例 / manual_stack 模板重建） */
    private fun candidateFromBagItem(item: StorageBagItem): ManualCandidate? = when (item.itemType) {
        ITEM_TYPE_MANUAL_INSTANCE ->
            item.manualInstance?.let { instance ->
                ManualCandidate(
                    name = instance.name,
                    type = instance.type,
                    rarity = instance.rarity,
                    minRealm = instance.minRealm,
                    hasPhysical = instance.skillDamageType == "physical",
                    hasMagic = instance.skillDamageType == "magic",
                    source = ManualSource.BagInstance(item, instance)
                )
            }
        ITEM_TYPE_MANUAL_STACK -> {
            val reconstructed = BagItemReconstructor.reconstruct(item)
                as? ReconstructedBagStack.Manual ?: return null
            val stack = reconstructed.stack
            ManualCandidate(
                name = stack.name,
                type = stack.type,
                rarity = stack.rarity,
                minRealm = stack.minRealm,
                hasPhysical = stack.skillDamageType == "physical",
                hasMagic = stack.skillDamageType == "magic",
                source = ManualSource.BagStack(item, stack.toInstance(
                    id = item.itemId, ownerId = "", isLearned = false
                ))
            )
        }
        else -> null
    }

    fun canLearn(disciple: Disciple, stack: ManualStack, manualInstances: Map<String, ManualInstance>): Boolean {
        if (disciple.realm > stack.minRealm) return false
        val maxSlots = DiscipleStatCalculator.getMaxManualSlots(disciple)
        if (disciple.manualIds.size >= maxSlots) return false
        if (stack.type == ManualType.MIND && disciple.manualIds.any { mid -> manualInstances[mid]?.type == ManualType.MIND }) return false
        if (disciple.manualIds.any { mid -> manualInstances[mid]?.name == stack.name }) return false
        return true
    }

    @Suppress("ReturnCount")  // 多出口=逐条件早退天然结构：槽位为 0/无候选/最优缺失/替换条件不满足/成功
    fun processAutoLearnFromWarehouse(
        disciple: Disciple,
        warehouseStacks: List<ManualStack>,
        manualInstances: Map<String, ManualInstance>,
        gameYear: Int,
        gameMonth: Int
    ): ManualLearnResult {
        val maxSlots = DiscipleStatCalculator.getMaxManualSlots(disciple)
        if (maxSlots <= 0) {
            return ManualLearnResult(disciple, null, null, null, null)
        }

        val learnedInstances = disciple.manualIds.mapNotNull { manualInstances[it] }
        val learnedNames = learnedInstances.map { it.name }.toSet()
        val hasMindManual = learnedInstances.any { it.type == ManualType.MIND }

        val candidates = collectManualCandidates(
            disciple, warehouseStacks, learnedNames, hasMindManual
        )
        if (candidates.isEmpty()) {
            return ManualLearnResult(disciple, null, null, null, null)
        }

        val best = candidates.maxWithOrNull(manualComparator(disciple))
            ?: return ManualLearnResult(disciple, null, null, null, null)

        var updatedDisciple = disciple
        var replacedInstance: ManualInstance? = null

        // 替换判定：槽位已满且候选不严格优于"最差已学功法" → 不动
        if (updatedDisciple.manualIds.size >= maxSlots) {
            val replace = tryReplaceWorst(
                updatedDisciple, learnedInstances, best, gameYear, gameMonth
            ) ?: return ManualLearnResult(updatedDisciple, null, null, null, null)
            updatedDisciple = replace.first
            replacedInstance = replace.second
        }

        // 学习
        val outcome = applyManualLearn(updatedDisciple, best, gameYear, gameMonth)

        return ManualLearnResult(
            disciple = outcome.disciple,
            newInstance = outcome.newInstance,
            attachedInstance = outcome.attachedInstance,
            replacedInstance = replacedInstance,
            stackUpdate = outcome.stackUpdate
        )
    }

    /** 学习结果（B：新建 / 袋内实例装配 + 仓库堆叠扣减） */
    private data class ManualLearnOutcome(
        val disciple: Disciple,
        val newInstance: ManualInstance?,
        val attachedInstance: ManualInstance?,
        val stackUpdate: StackUpdate?
    )

    /** 候选收集：仓库堆叠 + 储物袋条目（境界/锁/同名/心法唯一过滤） */
    private fun collectManualCandidates(
        disciple: Disciple,
        warehouseStacks: List<ManualStack>,
        learnedNames: Set<String>,
        hasMindManual: Boolean
    ): List<ManualCandidate> {
        val candidates = mutableListOf<ManualCandidate>()
        warehouseStacks.forEach { stack ->
            if (disciple.realm > stack.minRealm) return@forEach
            if (stack.isLocked) return@forEach
            if (stack.name in learnedNames) return@forEach
            if (hasMindManual && stack.type == ManualType.MIND) return@forEach
            candidates += ManualCandidate(
                name = stack.name, type = stack.type, rarity = stack.rarity,
                minRealm = stack.minRealm,
                hasPhysical = stack.skillDamageType == "physical",
                hasMagic = stack.skillDamageType == "magic",
                source = ManualSource.Warehouse(stack)
            )
        }
        disciple.equipment.storageBagItems.forEach { item ->
            val candidate = candidateFromBagItem(item) ?: return@forEach
            if (disciple.realm > candidate.minRealm) return@forEach
            if (candidate.name in learnedNames) return@forEach
            if (hasMindManual && candidate.type == ManualType.MIND) return@forEach
            candidates += candidate
        }
        return candidates
    }

    /**
     * 槽位满时的替换：候选严格优于最差已学功法时，遗忘最差入袋
     * （熟练度清理由调用方经 manualIds 差集处理）。
     * @return (更新后的弟子, 被遗忘的旧实例)；不满足替换条件返回 null
     */
    @Suppress("ReturnCount")  // 多出口=最差缺失/不更优/成功三分支
    private fun tryReplaceWorst(
        disciple: Disciple,
        learnedInstances: List<ManualInstance>,
        best: ManualCandidate,
        gameYear: Int,
        gameMonth: Int
    ): Pair<Disciple, ManualInstance>? {
        val comparator = manualComparator(disciple)
        val worst = learnedInstances.minWithOrNull(
            Comparator { a, b ->
                comparator.compare(candidateFromInstance(a), candidateFromInstance(b))
            }
        ) ?: return null
        val worstCandidate = candidateFromInstance(worst)
        if (comparator.compare(best, worstCandidate) <= 0) return null
        return forgetToBag(disciple, worst, gameYear, gameMonth) to worst
    }

    /** 学习动作（仓库堆叠 / 袋内实例 / 袋内堆叠模板重建） */
    private fun applyManualLearn(
        disciple: Disciple,
        best: ManualCandidate,
        gameYear: Int,
        gameMonth: Int
    ): ManualLearnOutcome = when (val source = best.source) {
        is ManualSource.Warehouse -> learnFromWarehouseStack(disciple, source)
        is ManualSource.BagInstance -> learnFromBagInstance(disciple, source)
        is ManualSource.BagStack -> learnFromBagStack(disciple, source)
    }

    /** 仓库堆叠学习（Kotlin learnManual 语义：HP/MP 增量仅 raw >= 0 且增益为正） */
    private fun learnFromWarehouseStack(
        disciple: Disciple,
        source: ManualSource.Warehouse
    ): ManualLearnOutcome {
        val instanceId = java.util.UUID.randomUUID().toString()
        val instance = source.stack.toInstance(
            id = instanceId, ownerId = disciple.id, isLearned = true
        )
        val hpDelta = source.stack.stats["hp"] ?: source.stack.stats["maxHp"] ?: 0
        val mpDelta = source.stack.stats["mp"] ?: source.stack.stats["maxMp"] ?: 0
        val rawCurrentHp = disciple.combat.currentHp
        val rawCurrentMp = disciple.combat.currentMp
        val newHp = if (rawCurrentHp >= 0 && hpDelta > 0) rawCurrentHp + hpDelta else rawCurrentHp
        val newMp = if (rawCurrentMp >= 0 && mpDelta > 0) rawCurrentMp + mpDelta else rawCurrentMp
        val updated = disciple.copy(
            manualIds = disciple.manualIds + instanceId,
            combat = disciple.combat.copy(currentHp = newHp, currentMp = newMp)
        )
        val newQty = source.stack.quantity - 1
        val stackUpdate = if (newQty <= 0) {
            StackUpdate(stackId = source.stack.id, newQuantity = 0, isDeletion = true)
        } else {
            StackUpdate(stackId = source.stack.id, newQuantity = newQty, isDeletion = false)
        }
        return ManualLearnOutcome(updated, instance, null, stackUpdate)
    }

    /** 袋内完整实例学习（实例可能不在实例表——防双持有，调用方重建入表） */
    private fun learnFromBagInstance(
        disciple: Disciple,
        source: ManualSource.BagInstance
    ): ManualLearnOutcome {
        val attached = source.instance.copy(isLearned = true, ownerId = disciple.id)
        val updated = disciple.copy(
            manualIds = disciple.manualIds + attached.id,
            equipment = disciple.equipment.copy(
                storageBagItems = disciple.equipment.storageBagItems
                    .filterNot { it.itemId == source.bagItem.itemId }
            )
        )
        return ManualLearnOutcome(updated, null, attached, null)
    }

    /** 袋内堆叠模板重建学习（reconstruct 已保证模板存在） */
    private fun learnFromBagStack(
        disciple: Disciple,
        source: ManualSource.BagStack
    ): ManualLearnOutcome {
        val instanceId = java.util.UUID.randomUUID().toString()
        val instance = source.instance.copy(
            id = instanceId, ownerId = disciple.id, isLearned = true
        )
        val hpDelta = source.instance.stats["hp"] ?: source.instance.stats["maxHp"] ?: 0
        val mpDelta = source.instance.stats["mp"] ?: source.instance.stats["maxMp"] ?: 0
        val rawCurrentHp = disciple.combat.currentHp
        val rawCurrentMp = disciple.combat.currentMp
        val newHp = if (rawCurrentHp >= 0 && hpDelta > 0) rawCurrentHp + hpDelta else rawCurrentHp
        val newMp = if (rawCurrentMp >= 0 && mpDelta > 0) rawCurrentMp + mpDelta else rawCurrentMp
        val updated = disciple.copy(
            manualIds = disciple.manualIds + instanceId,
            combat = disciple.combat.copy(currentHp = newHp, currentMp = newMp),
            equipment = disciple.equipment.copy(
                storageBagItems = StorageBagUtils.decreaseItemQuantity(
                    disciple.equipment.storageBagItems,
                    source.bagItem.itemId
                )
            )
        )
        return ManualLearnOutcome(updated, instance, null, null)
    }

    /** 遗忘功法入袋（实例保真入袋，对齐 forgetManual 语义） */
    private fun forgetToBag(
        disciple: Disciple,
        old: ManualInstance,
        gameYear: Int,
        gameMonth: Int
    ): Disciple = disciple.copy(
        manualIds = disciple.manualIds.filterNot { it == old.id },
        equipment = disciple.equipment.copy(
            storageBagItems = StorageBagUtils.increaseItemQuantity(
                disciple.equipment.storageBagItems,
                StorageBagItem(
                    itemId = old.id, itemType = ITEM_TYPE_MANUAL_INSTANCE,
                    name = old.name, rarity = old.rarity, quantity = 1,
                    obtainedYear = gameYear, obtainedMonth = gameMonth,
                    manualInstance = old
                )
            )
        )
    )

    fun canLearn(disciple: Disciple, instance: ManualInstance, manualInstances: Map<String, ManualInstance>): Boolean {
        if (disciple.realm > instance.minRealm) return false
        val maxSlots = DiscipleStatCalculator.getMaxManualSlots(disciple)
        if (disciple.manualIds.size >= maxSlots) return false
        if (instance.type == ManualType.MIND && disciple.manualIds.any { mid -> manualInstances[mid]?.type == ManualType.MIND }) return false
        if (disciple.manualIds.any { mid -> manualInstances[mid]?.name == instance.name }) return false
        return true
    }
}
