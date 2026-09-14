package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.LazyEvaluationDispatcher
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.ForgeRecipe
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.artifactRefining
import com.xianxia.sect.core.model.pillRefining
import com.xianxia.sect.core.model.spiritPlanting
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.registry.PillRecipeDatabase
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.util.BuildingNames
import kotlinx.coroutines.withContext
import com.xianxia.sect.core.engine.domain.disciple.getBaseStats
import com.xianxia.sect.core.repository.getSlotsByBuildingId

// ── 建筑槽位支持域（自 BuildingService 拆出，行为零变更） ───────────────────
internal fun BuildingService.buildAlchemySuccessRate(
    alchemySlot: ProductionSlot,
    recipe: PillRecipeDatabase.PillRecipe,
    alchemyPolicyBonus: Double
): Double {
    val disciple = alchemySlot.assignedDiscipleId?.let { id ->
        stateStore.disciples.value.find { it.id == id }
    }
    return formulaService.buildSuccessRateZones(
        disciple = disciple,
        buildingId = BuildingNames.ALCHEMY,
        recipeTier = recipe.tier,
        policyBonus = alchemyPolicyBonus
    ).calculate()
}


internal fun BuildingService.buildForgingSuccessRate(
    forgeSlot: ProductionSlot,
    recipe: ForgeRecipeDatabase.ForgeRecipe,
    forgePolicyBonus: Double
): Double {
    val disciple = forgeSlot.assignedDiscipleId?.let { id ->
        stateStore.disciples.value.find { it.id == id }
    }
    return formulaService.buildSuccessRateZones(
        disciple = disciple,
        buildingId = BuildingNames.FORGE,
        recipeTier = recipe.tier,
        policyBonus = forgePolicyBonus
    ).calculate()
}

/**
 * B5 已知偏差（不修）：仅写 Repository 状态、镜像 productionSlots 的 status 保持旧值。
 * 状态推导（DiscipleStatusService）不读镜像 status，暂不致病；勿依赖镜像 status 为真源。
 */

internal suspend fun BuildingService.updateSlotToWorkingStateAlchemy(
    slotIndex: Int,
    data: GameData,
    recipe: PillRecipeDatabase.PillRecipe,
    recipeId: String,
    actualDuration: Int,
    effectiveSuccessRate: Double
) {
    val currentAbsoluteMonth = LazyEvaluationDispatcher.toAbsoluteMonth(
        data.gameYear, data.gameMonth
    )
    withContext(ioDispatcher.dispatcher) {
        productionSlotRepository.updateSlotByBuildingId(
            BuildingNames.ALCHEMY, slotIndex
        ) { slot ->
            slot.copy(
                status = ProductionSlotStatus.WORKING,
                recipeId = recipeId,
                recipeName = recipe.name,
                startYear = data.gameYear,
                startMonth = data.gameMonth,
                duration = actualDuration,
                baseDuration = recipe.duration,
                successRate = effectiveSuccessRate,
                requiredMaterials = recipe.materials,
                outputItemId = recipeId,
                outputItemName = recipe.name,
                outputItemRarity = recipe.rarity,
                completionMonth = currentAbsoluteMonth +
                    actualDuration.coerceAtLeast(1),
                completionPhase = 2
            )
        }
    }
}

/** B5 已知偏差（不修）：同 [updateSlotToWorkingStateAlchemy]，仅写 Repository 不写镜像 status。 */
internal suspend fun BuildingService.updateSlotToWorkingStateForging(
    slotIndex: Int,
    data: GameData,
    recipe: ForgeRecipeDatabase.ForgeRecipe,
    recipeId: String,
    baseDuration: Int,
    actualDuration: Int,
    effectiveSuccessRate: Double
) {
    val currentAbsoluteMonth = LazyEvaluationDispatcher.toAbsoluteMonth(
        data.gameYear, data.gameMonth
    )
    withContext(ioDispatcher.dispatcher) {
        productionSlotRepository.updateSlotByBuildingId(
            BuildingNames.FORGE, slotIndex
        ) { slot ->
            slot.copy(
                status = ProductionSlotStatus.WORKING,
                recipeId = recipeId,
                recipeName = recipe.name,
                startYear = data.gameYear,
                startMonth = data.gameMonth,
                duration = actualDuration,
                baseDuration = baseDuration,
                successRate = effectiveSuccessRate,
                outputItemId = recipeId,
                outputItemName = recipe.name,
                outputItemRarity = recipe.rarity,
                outputItemSlot = recipe.type.name,
                completionMonth = currentAbsoluteMonth +
                    actualDuration.coerceAtLeast(1),
                completionPhase = 2
            )
        }
    }
}

/**
 * 计算所有弟子的工作持续时间加成
 *
 * @param baseDuration 基础持续时间（月）
 * @param buildingId 建筑ID
 * @return 实际持续时间（月）
 */
// -- 原方法保持不变 --


internal fun BuildingService.getElderPositionBonusLocal(buildingId: String): Double {
    val data = stateStore.gameData.value
    val elderSlots = data.elderSlots

    val elderDiscipleId = when (buildingId) {
        BuildingNames.FORGE -> elderSlots.forgeElder
        BuildingNames.ALCHEMY -> elderSlots.alchemyElder
        "herbGarden" -> elderSlots.herbGardenElder
        else -> null
    } ?: return 0.0

    val elderDisciple = stateStore.disciples.value.find {
        it.id == elderDiscipleId
    } ?: return 0.0

    return when (buildingId) {
        BuildingNames.FORGE -> {
            val diff = (
                DiscipleStatCalculator.getBaseStats(elderDisciple).artifactRefining -
                    GameConfig.PolicyConfig.ELDER_SKILL_BASELINE
            ).coerceAtLeast(0)
            diff * 0.01
        }
        BuildingNames.ALCHEMY -> {
            val diff = (
                DiscipleStatCalculator.getBaseStats(elderDisciple).pillRefining -
                    GameConfig.PolicyConfig.ELDER_SKILL_BASELINE
            ).coerceAtLeast(0)
            diff * 0.01
        }
        "herbGarden" -> {
            val diff = (
                DiscipleStatCalculator.getBaseStats(elderDisciple).spiritPlanting -
                    GameConfig.PolicyConfig.ELDER_SKILL_BASELINE
            ).coerceAtLeast(0)
            diff * 0.01
        }
        else -> 0.0
    }
}

internal fun BuildingService.calculateReducedDurationLocal(
    baseDuration: Int, speedBonus: Double
): Int {
    if (speedBonus <= 0) return baseDuration
    val reductionPercent =
        speedBonus / GameConfig.PolicyConfig.SPEED_REDUCTION_DIVISOR
    val reducedMonths = (baseDuration * reductionPercent).toInt()
    return (baseDuration - reducedMonths).coerceAtLeast(1)
}


internal suspend fun BuildingService.updateDiscipleStatus(
    discipleId: String, status: DiscipleStatus
) {
    stateStore.update {
        val currentList = discipleTables.assembleAll()
        val updated = currentList.map {
            if (it.id == discipleId) it.copy(status = status) else it
        }
        discipleTables.replaceAll(updated)
    }
}

internal suspend fun BuildingService.getDiscipleNameIfAvailable(discipleId: String): String {
    var discipleName = ""
    stateStore.update {
        val disciple = discipleTables.assemble(
            discipleId.toIntOrNull() ?: return@update
        )
        if (!disciple.isAlive || disciple.status != DiscipleStatus.IDLE) {
            return@update
        }
        if (disciple.age < 5) {
            return@update
        }
        discipleName = disciple.name
    }
    return discipleName
}
internal fun BuildingService.calculateWorkDurationWithAllDisciples(
    baseDuration: Int, buildingId: String
): Int {
    var totalSpeedBonus = 0.0
    val data = stateStore.gameData.value

    totalSpeedBonus += getElderPositionBonusLocal(buildingId)

    when (buildingId) {
        BuildingNames.FORGE, BuildingNames.ALCHEMY, "herbGarden" -> {
            val assignedDiscipleIds = when (buildingId) {
                BuildingNames.FORGE ->
                    productionSlotRepository.getSlotsByBuildingId(
                        BuildingNames.FORGE
                    ).mapNotNull { it.assignedDiscipleId }
                BuildingNames.ALCHEMY -> emptyList()
                else -> emptyList()
            }
            if (assignedDiscipleIds.isNotEmpty()) {
                val elderBonus = getElderPositionBonusLocal(buildingId)
                totalSpeedBonus += elderBonus
            }
        }
    }

    return calculateReducedDurationLocal(baseDuration, totalSpeedBonus)
}
