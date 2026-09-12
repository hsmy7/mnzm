package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.ForgeRecipe
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.model.PillGrade
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.engine.system.InventoryFactories
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.profession.ProfessionRules
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.registry.PillRecipeDatabase
import com.xianxia.sect.core.util.BuildingNames
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.model.production.BuildingType

// ── ProductionProcessor 拆分域 4/5（行为零变更） ──

/** 影子版自动锻造 */
internal fun ProductionProcessor.batchAutoForge(
    slots: MutableList<ProductionSlot>,
    state: MutableGameState
) {
    val gd = state.gameData
    val policyBonus = if (gd.sectPolicies.forgeIncentive)
        GameConfig.PolicyConfig.FORGE_INCENTIVE_EFFECT else 0.0
    val allRecipes = ForgeRecipeDatabase.getAllRecipes().sortedByDescending { it.rarity }
    val materialIndex = state.materials.all().groupBy { it.name to it.rarity }
        .mapValues { (_, list) -> list.sumOf { it.quantity } }

    val idleSlotIndices = slots
        .filter { it.buildingType == BuildingType.FORGE }
        .filter { it.autoRestartEnabled && it.status == ProductionSlotStatus.IDLE
            && !it.assignedDiscipleId.isNullOrEmpty() }
        .map { it.slotIndex }

    for (slotIndex in idleSlotIndices) {
        // 无可锻配方（材料/门禁不满足）中断后续槽位（续炼启动停止扫槽）
        if (!autoForgeRestartSlot(slots, slotIndex, state, gd, allRecipes, materialIndex,
                policyBonus)) break
    }
}

/** 影子版自动锻造单槽：true=继续下一槽，false=中断循环 */

internal fun ProductionProcessor.autoForgeRestartSlot(
    slots: MutableList<ProductionSlot>,
    slotIndex: Int,
    state: MutableGameState,
    gd: GameData,
    allRecipes: List<ForgeRecipeDatabase.ForgeRecipe>,
    materialIndex: Map<Pair<String, Int>, Int>,
    policyBonus: Double
): Boolean {
    val slotIdx = slots.indexOfFirst {
        it.buildingType == BuildingType.FORGE && it.slotIndex == slotIndex
    }
    if (slotIdx < 0) return true

    // 职业门禁：按槽位弟子炼器师职业等级限制可锻品阶（无职业只能锻凡品）
    val worker = slots[slotIdx].assignedDiscipleId
        ?.let { id -> state.discipleTables.assembleAll().find { it.id == id } }
    val maxTier = worker?.let { ProfessionRules.maxCraftableTier(it.skills.forgeLevel) }
        ?: 1
    val recipeToStart = findForgeRecipe(allRecipes, materialIndex, maxTier) ?: return false

    consumeMaterialsForRecipeLocal(recipeToStart.materials, state)
    val absoluteMonth = gd.gameYear * 12 + gd.gameMonth
    val duration = ForgeRecipeDatabase.getDurationByTier(recipeToStart.tier)

    // 公式化成功率（属性+职业合成基础率 × 乘区），不再用配方 successRate
    val effectiveSuccessRate = formulaService.buildSuccessRateZones(
        disciple = worker,
        buildingId = BuildingNames.FORGE,
        recipeTier = recipeToStart.tier,
        policyBonus = policyBonus
    ).calculate()
    slots[slotIdx] = slots[slotIdx].copy(
        status = ProductionSlotStatus.WORKING,
        recipeId = recipeToStart.id,
        recipeName = recipeToStart.name,
        startYear = gd.gameYear,
        startMonth = gd.gameMonth,
        duration = duration,
        successRate = effectiveSuccessRate,
        completionMonth = absoluteMonth + duration.coerceAtLeast(1),
        completionPhase = 3,
        outputItemId = recipeToStart.id,
        outputItemName = recipeToStart.name,
        outputItemRarity = recipeToStart.rarity
    )
    return true
}

/** 影子版生产完成检测 */

internal fun ProductionProcessor.batchBuildingCompletion(
    slots: MutableList<ProductionSlot>,
    state: MutableGameState
) {
    batchForgeCompletion(slots, state)
    batchAlchemyCompletion(slots, state)
}

internal fun ProductionProcessor.batchForgeCompletion(
    slots: MutableList<ProductionSlot>,
    state: MutableGameState
) {
    val year = state.gameData.gameYear
    val month = state.gameData.gameMonth
    for (i in slots.indices) {
        val slot = slots[i]
        if (!isCompleteForgeSlot(slot, year, month)) continue

        // 锻造真实成功率判定（与手动路径 completeForgeSlot 一致）
        val success = rollProductionSuccess(slot)
        if (success) {
            produceForgeEquipmentShadow(slot, state)
        }
        // 职业晋升进度（影子版直接操作 state.discipleTables）
        // B3：弟子死亡/查无此人 → 槽位清空弟子关联（防死弟子占用槽位）
        val discipleAlive = slot.assignedDiscipleId?.let { discipleId ->
            settleForgeCompletionShadow(state, slot, discipleId, success)
        } ?: false
        slots[i] = ProductionSlot.createIdle(
            id = slot.id, slotIndex = slot.slotIndex,
            buildingType = BuildingType.FORGE,
            buildingId = slot.buildingId,
            autoRestartEnabled = slot.autoRestartEnabled,
            assignedDiscipleId = if (discipleAlive) slot.assignedDiscipleId else null,
            assignedDiscipleName = if (discipleAlive) slot.assignedDiscipleName ?: "" else "",
            recipeId = slot.recipeId
        )
    }
}

/** 完成判定：锻造槽 + WORKING + 到期待结算 */

internal fun ProductionProcessor.isCompleteForgeSlot(slot: ProductionSlot, year: Int, month: Int): Boolean =
    slot.buildingType == BuildingType.FORGE &&
        slot.status == ProductionSlotStatus.WORKING &&
        isSlotCompleteDynamic(slot, year, month)

/** 影子版锻造产出（直接入 state.equipmentStacks，与手动版 withTrackingSource 路径分开） */

internal fun ProductionProcessor.produceForgeEquipmentShadow(slot: ProductionSlot, state: MutableGameState) {
    val recipeId = slot.recipeId ?: return
    val recipe = ForgeRecipeDatabase.getRecipeById(recipeId) ?: return
    state.equipmentStacks.add(InventoryFactories.createEquipmentFromRecipe(recipe))
}

/**
 * 影子版锻造结算：弟子回 IDLE + 职业晋升（MutableGameState 直接操作）。
 *
 * @return 弟子是否存在且存活（false → 槽位应清空弟子关联，B3）
 */

internal fun ProductionProcessor.settleForgeCompletionShadow(
    state: MutableGameState,
    slot: ProductionSlot,
    discipleId: String,
    success: Boolean
): Boolean {
    // 配方无效（数据损坏）时 recipeTier=0：低阶不充数规则下不结算晋升
    val recipeTier = slot.recipeId?.let { ForgeRecipeDatabase.getRecipeById(it)?.tier } ?: 0
    val currentList = state.discipleTables.assembleAll()
    var discipleAlive = false
    val updated = currentList.map {
        if (it.id == discipleId && it.isAlive) {
            discipleAlive = true
            state.settleDiscipleProduction(it, recipeTier, success, isAlchemy = false)
        } else it
    }
    state.discipleTables.replaceAll(updated)
    return discipleAlive
}

internal fun ProductionProcessor.batchAlchemyCompletion(
    slots: MutableList<ProductionSlot>,
    state: MutableGameState
) {
    val year = state.gameData.gameYear
    val month = state.gameData.gameMonth
    for (i in slots.indices) {
        val slot = slots[i]
        // 非炼丹槽/非工作中/未到期的槽位不结算（抽取集不变）
        val isDueAlchemy = slot.buildingType == BuildingType.ALCHEMY &&
            slot.status == ProductionSlotStatus.WORKING &&
            isSlotCompleteDynamic(slot, year, month)
        if (!isDueAlchemy) continue

        val alchemyRng = rngManager.getRng(RngPartition.SYSTEM)
        val success = alchemyRng.nextDouble() <= slot.successRate.coerceIn(0.0, 1.0)
        if (success) {
            produceAlchemyPill(state, slot, alchemyRng)
        }
        // 职业晋升进度（影子版直接操作 state.discipleTables，与锻造影子路径共用
        // settleDiscipleProduction——统一"弟子回 IDLE + 晋升"语义）
        // B3：弟子死亡/查无此人 → 槽位清空弟子关联（防死弟子占用槽位）
        val discipleAlive = slot.assignedDiscipleId?.let { discipleId ->
            settleAlchemyCompletionShadow(state, slot, discipleId, success)
        } ?: false
        slots[i] = ProductionSlot.createIdle(
            id = slot.id, slotIndex = slot.slotIndex,
            buildingType = BuildingType.ALCHEMY,
            buildingId = slot.buildingId,
            autoRestartEnabled = slot.autoRestartEnabled,
            assignedDiscipleId = if (discipleAlive) slot.assignedDiscipleId else null,
            assignedDiscipleName = if (discipleAlive) slot.assignedDiscipleName ?: "" else "",
            recipeId = slot.recipeId
        )
    }
}

/** 炼丹成功产出：grade roll → 模板丹药/兜底丹药入仓 */

internal fun ProductionProcessor.produceAlchemyPill(
    state: MutableGameState,
    slot: ProductionSlot,
    alchemyRng: DeterministicRng
) {
    val roll = alchemyRng.nextDouble()
    val grade = when {
        roll < 0.06 -> PillGrade.HIGH
        roll < 0.40 -> PillGrade.MEDIUM
        else -> PillGrade.LOW
    }
    val baseId = slot.recipeId?.substringBeforeLast("_")
    val pillId = "${baseId}_${grade.name.lowercase()}"
    val template = baseId?.let { ItemDatabase.getPillById(pillId) }
    val pill = if (template != null) ItemDatabase.createPillFromTemplate(template)
    else Pill(
        name = slot.outputItemName, rarity = slot.outputItemRarity,
        grade = grade, category = PillCategory.CULTIVATION,
        description = "通过炼丹炉炼制而成",
        minRealm = GameConfig.Realm.getMinRealmForRarity(slot.outputItemRarity),
        quantity = 1
    )
    state.pills.add(pill)
}

/**
 * 影子版炼丹结算：弟子回 IDLE + 职业晋升（MutableGameState 直接操作，
 * 与锻造影子路径共用 settleDiscipleProduction——统一"弟子回 IDLE + 晋升"语义）。
 *
 * @return 弟子是否存在且存活（false → 槽位应清空弟子关联，B3）
 */

internal fun ProductionProcessor.settleAlchemyCompletionShadow(
    state: MutableGameState,
    slot: ProductionSlot,
    discipleId: String,
    success: Boolean
): Boolean {
    val recipeTier = slot.recipeId?.let { PillRecipeDatabase.getRecipeById(it)?.tier } ?: 0
    val currentList = state.discipleTables.assembleAll()
    var discipleAlive = false
    val updated = currentList.map {
        if (it.id == discipleId && it.isAlive) {
            discipleAlive = true
            state.settleDiscipleProduction(it, recipeTier, success, isAlchemy = true)
        } else it
    }
    state.discipleTables.replaceAll(updated)
    return discipleAlive
}

/** 影子版灵田收获（已用 state，直接复用） */

@Suppress("UnusedParameter") // slots: 影子版收获签名：与正式版保持形参对称（state 已承载槽位）
fun ProductionProcessor.batchSpiritFieldHarvest(
    slots: MutableList<ProductionSlot>,
    state: MutableGameState
) {
    // processSpiritFieldHarvest 已操作 state，只需确保 year/month 来自 state
    processSpiritFieldHarvest(state)
}



// ═══════════════════════════════════════════════════════════════
// 影子版工具方法
// ═══════════════════════════════════════════════════════════════

internal fun ProductionProcessor.findRecipe(
    herbs: List<Herb>,
    maxTier: Int = 1
): PillRecipeDatabase.PillRecipe? {
    return PillRecipeDatabase.findBestCraftableRecipe(herbs, maxTier)
}
