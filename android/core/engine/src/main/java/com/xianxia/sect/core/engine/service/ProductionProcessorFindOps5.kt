package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.ForgeRecipe
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.SectPolicies
import com.xianxia.sect.core.model.comprehension
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.registry.PillRecipeDatabase
import com.xianxia.sect.core.engine.domain.building.buildingFeatureDisplayNames
import com.xianxia.sect.core.engine.domain.building.SlotGroup
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.util.TimeProgressUtil
import com.xianxia.sect.core.model.production.BuildingType

// ── ProductionProcessor 拆分域 5/5（行为零变更） ──

private val SINGLE_RESIDENCE_SLOTS = ProductionProcessor.SINGLE_RESIDENCE_SLOTS
private val MULTI_RESIDENCE_SLOTS = ProductionProcessor.MULTI_RESIDENCE_SLOTS
internal fun ProductionProcessor.findForgeRecipe(
    recipes: List<ForgeRecipeDatabase.ForgeRecipe>,
    materialIndex: Map<Pair<String, Int>, Int>,
    maxTier: Int = 1
): ForgeRecipeDatabase.ForgeRecipe? {
    return recipes.firstOrNull { recipe ->
        recipe.tier <= maxTier && recipe.materials.all { (materialId, requiredQty) ->
            val matData = BeastMaterialDatabase.getMaterialById(materialId)
            matData != null && (materialIndex[matData.name to matData.rarity] ?: 0) >= requiredQty
        }
    }
}

internal fun ProductionProcessor.consumeHerbsForRecipeLocal(
    materials: Map<String, Int>,
    herbs: List<Herb>,
    state: MutableGameState
) {
    for ((herbId, requiredQty) in materials) {
        val herbData = HerbDatabase.getHerbById(herbId) ?: continue
        var remaining = requiredQty
        val iter = state.herbs.all().iterator()
        while (iter.hasNext() && remaining > 0) {
            val herb = iter.next()
            if (herb.name != herbData.name || herb.rarity != herbData.rarity) continue
            val consume = minOf(remaining, herb.quantity)
            remaining -= consume
            val newQty = herb.quantity - consume
            if (newQty <= 0) state.herbs.remove(herb.id)
            else state.herbs.update(herb.id) { it.copy(quantity = newQty) }
        }
    }
}

internal fun ProductionProcessor.consumeMaterialsForRecipeLocal(
    materials: Map<String, Int>,
    state: MutableGameState
) {
    for ((materialId, requiredQty) in materials) {
        val matData = BeastMaterialDatabase.getMaterialById(materialId) ?: continue
        var remaining = requiredQty
        val iter = state.materials.all().iterator()
        while (iter.hasNext() && remaining > 0) {
            val item = iter.next()
            if (item.name != matData.name || item.rarity != matData.rarity) continue
            val consume = minOf(remaining, item.quantity)
            remaining -= consume
            val newQty = item.quantity - consume
            if (newQty <= 0) state.materials.remove(item.id)
            else state.materials.update(item.id) { it.copy(quantity = newQty) }
        }
    }
}

// ── Checkpoint 快照法：动态完成检测 ──

/**
 * 动态检查生产槽位是否完成（Checkpoint 快照法）。
 *
 * 每次检查时按当前策略/长老状态重算有效 duration，
 * 替代使用缓存 duration 的 [ProductionSlot.isFinished]。
 */

internal fun ProductionProcessor.isSlotCompleteDynamic(slot: ProductionSlot, year: Int, month: Int): Boolean {
    if (!slot.isWorking) return slot.status == ProductionSlotStatus.COMPLETED
    if (slot.duration <= 0) return true  // 保护：duration=0 → 立即完成

    val effectiveDuration = if (slot.baseDuration > 0) {
        formulaService.calculateWorkDurationWithAllDisciples(
            slot.baseDuration, slot.buildingId)
    } else {
        slot.duration  // 旧数据回退
    }

    return TimeProgressUtil.isTimeElapsed(
        slot.startYear, slot.startMonth, effectiveDuration, year, month)
}

/**
 * 全量重算所有活跃生产槽位的完成时间（Checkpoint 快照法）。
 *
 * 在策略切换/长老变更后调用，确保所有槽位的 completionMonth
 * 反映当前速率。由 [CultivationService.checkpointAllProduction] 委托。
 */

fun ProductionProcessor.recalculateAllCompletionMonths() {
    val data = stateStore.gameData.value
    val currentMonth = data.gameYear * 12 + data.gameMonth

    val allSlots = productionSlotRepository.getSlots()
    for (slot in allSlots) {
        // 旧存档兼容：baseDuration=0 的槽位用当前 duration 作为基础值，
        // 确保政策/长老变化也能影响这些槽位
        val effectiveBase = if (slot.baseDuration > 0) slot.baseDuration else slot.duration
        val oldDuration = slot.duration.coerceAtLeast(1)
        val elapsedMonths = ((data.gameYear - slot.startYear) * 12 +
            (data.gameMonth - slot.startMonth)).coerceAtLeast(0)
        val progressRatio = elapsedMonths.toDouble() / oldDuration
        // 未工作中/无有效基础时长/已完成（进度≥1）/时长无变化的槽位无需重算
        //（&& 短路保持原守卫序与惰性求值）
        val needsRecalc = slot.isWorking && effectiveBase > 0 && progressRatio < 1.0 && run {
            formulaService.calculateWorkDurationWithAllDisciples(
                effectiveBase, slot.buildingId
            ) != slot.duration
        }
        if (!needsRecalc) continue

        val newDuration = formulaService.calculateWorkDurationWithAllDisciples(
            effectiveBase, slot.buildingId
        )

        // 同步更新 successRate（政策/长老变化影响成功率）
        val newSuccessRate = recalculateSuccessRate(data, slot)

        val remainingMonths = ((1.0 - progressRatio) * newDuration)
            .roundToInt().coerceAtLeast(1)
        scopeProvider.scope.launch(ioDispatcher.dispatcher) {
            productionSlotRepository.updateSlot(
                slot.buildingType, slot.slotIndex
            ) { s ->
                s.copy(
                    duration = newDuration,
                    completionMonth = currentMonth + remainingMonths,
                    successRate = newSuccessRate
                )
            }
        }
    }
}

/**
 * 按当前政策/长老/槽位弟子状态重算槽位的 successRate。
 *
 * 当前公式规则：配方 successRate 不参与（恒 0），
 * 基础率由工作弟子属性 + 职业等级合成，走乘区法
 * （buildSuccessRateZones.calculate()）。
 */

internal fun ProductionProcessor.recalculateSuccessRate(data: GameData, slot: ProductionSlot): Double {
    val recipeTier = when (slot.buildingType) {
        BuildingType.ALCHEMY ->
            PillRecipeDatabase.getRecipeById(slot.recipeId ?: "")?.tier
        BuildingType.FORGE ->
            ForgeRecipeDatabase.getRecipeById(slot.recipeId ?: "")?.tier
        else -> null
    } ?: return slot.successRate

    val policyBonus = when (slot.buildingType) {
        BuildingType.ALCHEMY ->
            if (data.sectPolicies.alchemyIncentive)
                GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_EFFECT else 0.0
        BuildingType.FORGE ->
            if (data.sectPolicies.forgeIncentive)
                GameConfig.PolicyConfig.FORGE_INCENTIVE_EFFECT else 0.0
        else -> 0.0
    }
    val disciple = slot.assignedDiscipleId
        ?.let { id -> stateStore.disciples.value.find { it.id == id } }
    return formulaService.buildSuccessRateZones(
        disciple = disciple,
        buildingId = slot.buildingId,
        recipeTier = recipeTier,
        policyBonus = policyBonus
    ).calculate()
}

/**
 * 住所自动分配：按单人/多人住所政策从空闲弟子中筛选（关注/灵根/属性）。
 * 纯计算不修改 state，返回分配映射（buildingInstanceId:slotIndex → 弟子 id/name）。
 */

internal fun ProductionProcessor.computeResidenceAssignments(
    state: MutableGameState,
    data: GameData,
    policies: SectPolicies,
    occupiedResidentIds: Set<String>
): Map<String, Pair<String, String>> {
    val singleResEnabled = policies.autoSingleResidenceFocused || policies.autoSingleResidenceRootCounts
        .isNotEmpty()
    val multiResEnabled = policies.autoMultiResidenceFocused || policies.autoMultiResidenceRootCounts.isNotEmpty()
    if (!singleResEnabled && !multiResEnabled) return emptyMap()

    val singleResBuildingIds = buildResidenceBuildingIds(
        data = data,
        enabled = singleResEnabled,
        slotsPerInstance = SINGLE_RESIDENCE_SLOTS
    )
    val multiResBuildingIds = buildResidenceBuildingIds(
        data = data,
        enabled = multiResEnabled,
        slotsPerInstance = MULTI_RESIDENCE_SLOTS
    )

    val allCandidates = state.discipleTables.assembleAll()
        .filter { d -> d.isAlive && d.id !in occupiedResidentIds }

    val singleAssignments = computeResidenceAssignmentsForSlots(
        allCandidates = allCandidates,
        buildingIds = singleResBuildingIds,
        data = data,
        focused = policies.autoSingleResidenceFocused,
        rootCounts = policies.autoSingleResidenceRootCounts,
        threshold = policies.autoSingleResidenceThreshold,
        excludeAssignedIds = emptySet()
    )

    // 已分配单人住所的弟子不再进入多人候选（避免同弟子占位导致多人槽位空置）
    val multiAssignments = computeResidenceAssignmentsForSlots(
        allCandidates = allCandidates,
        buildingIds = multiResBuildingIds,
        data = data,
        focused = policies.autoMultiResidenceFocused,
        rootCounts = policies.autoMultiResidenceRootCounts,
        threshold = policies.autoMultiResidenceThreshold,
        excludeAssignedIds = singleAssignments.values.map { it.first }.toSet()
    )
    return singleAssignments + multiAssignments
}

/** 提取指定档位（单/多人）住所建筑 instanceId 集合 */

internal fun ProductionProcessor.buildResidenceBuildingIds(
    data: GameData,
    enabled: Boolean,
    slotsPerInstance: Int
): Set<String> = if (enabled) {
    data.placedBuildings
        .filter { it.displayName in buildingFeatureDisplayNames {
            it is SlotGroup.Residence && it.slotsPerInstance == slotsPerInstance
        } }.map { it.instanceId }.toSet()
} else emptySet()

/**
 * 计算指定档位住所的分配映射：
 * 按关注/灵根数/悟性排序，逐空槽分配候选弟子。
 */

internal fun ProductionProcessor.computeResidenceAssignmentsForSlots(
    allCandidates: List<Disciple>,
    buildingIds: Set<String>,
    data: GameData,
    focused: Boolean,
    rootCounts: List<Int>,
    threshold: Int,
    excludeAssignedIds: Set<String>
): Map<String, Pair<String, String>> {
    val assignments = mutableMapOf<String, Pair<String, String>>()
    if (buildingIds.isEmpty()) return assignments
    val candidates = allCandidates.filter { d ->
        val matchesFilter = (focused && isDiscipleFollowed(d)) ||
            d.spiritRoot.types.size in rootCounts
        d.id !in excludeAssignedIds && matchesFilter && d.comprehension >= threshold
    }
    .sortedWith(
        compareByDescending<Disciple> { isDiscipleFollowed(it) }
            .thenBy { it.spiritRoot.types.size }
            .thenByDescending { it.comprehension }
    )
    val emptySlots = data.residenceSlots.filter { s ->
        s.buildingInstanceId in buildingIds && s.discipleId.isEmpty()
    }
    for ((i, slot) in emptySlots.withIndex()) {
        if (i >= candidates.size) break
        val c = candidates[i]
        assignments["${slot.buildingInstanceId}:${slot.slotIndex}"] = c.id to c.name
    }
    return assignments
}

/**
 * 生产槽位候选提取：预排序候选弟子并**仅移除可容纳数量**（[maxCount]）后从池中
 * 弹出。移除数受空槽数上限约束，超出的合格候选保留在池中，供低优先级类型继续分配。
 * 政策未启用时返回空列表。
 *
 * @param maxCount 该类型当前空槽数上限（超出部分不消耗池）
 */

internal fun ProductionProcessor.takeCandidates(
    pool: MutableList<Disciple>,
    maxCount: Int,
    focused: Boolean,
    rootCounts: List<Int>,
    threshold: Int,
    attr: (Disciple) -> Int
): List<Disciple> {
    if (!focused && rootCounts.isEmpty()) return emptyList()
    val sorted = precomputeCandidates(pool, focused, rootCounts, threshold, attr)
    val taken = sorted.take(maxCount.coerceAtLeast(0))
    taken.forEach { pool.remove(it) }
    return taken
}

/**
 * 预排序候选弟子（按关注/灵根数/属性降序），不移除 pool 元素。
 * 从 processAutoAssign 嵌套函数提级（类级私有）。
 */

internal fun ProductionProcessor.precomputeCandidates(
    pool: List<Disciple>,
    focused: Boolean, rootCounts: List<Int>,
    threshold: Int, attr: (Disciple) -> Int
): List<Disciple> {
    /** 是否开启 C++ 引擎转发/集成（非 OFF 即开启） */
    val enabled = focused || rootCounts.isNotEmpty()
    if (!enabled || pool.isEmpty()) return emptyList()
    return pool
        .filter { d ->
            val matchesFilter = (focused && isDiscipleFollowed(d)) ||
                d.spiritRoot.types.size in rootCounts
            matchesFilter && attr(d) >= threshold
        }
        .sortedWith(
            compareByDescending<Disciple> { if (focused) isDiscipleFollowed(it) else false }
                .thenBy { it.spiritRoot.types.size }
                .thenByDescending { attr(it) }
        )
}
