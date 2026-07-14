package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.engine.service.FormulaService
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.registry.*
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.BuildingNames
import com.xianxia.sect.core.util.DomainResult
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@GameService("BuildingService")
@Singleton
class BuildingService @Inject constructor(
    private val stateStore: GameStateStore,
    private val productionCoordinator: ProductionCoordinator,
    private val productionSlotRepository: ProductionSlotRepository,
    private val inventorySystem: InventorySystem,
    private val formulaService: FormulaService,
    private val rngManager: com.xianxia.sect.core.util.GameRngManager
) {
    companion object {
        private const val TAG = "BuildingService"
    }

    suspend fun assignDiscipleToBuilding(buildingId: String, slotIndex: Int, discipleId: String) {
        if (discipleId.isEmpty()) {
            removeDiscipleFromBuildingInternal(buildingId, slotIndex)
            return
        }

        val disciple = stateStore.disciples.value.find { it.id == discipleId } ?: return
        if (!disciple.isAlive || disciple.status != DiscipleStatus.IDLE) {
            return
        }

        if (disciple.age < 5) {
            return
        }

        // Prevent assigning same disciple to multiple building slots.
        //
        // 历史 bug：旧实现使用 `it.buildingId != buildingId` 做排他判断，
        // 但 buildingId 是类型标识（如 "alchemy"/"forge"），非实例标识。
        // 多个同类型建筑实例共享同一 buildingId，导致排他检查失效。
        // 修复：改用 BuildingFacadeImpl.isDiscipleAssignedToOtherSlot 纯函数，
        // 按 buildingType + slotIndex 排除当前槽位后检查弟子是否已分配到其他任意槽位。
        val allSlots = productionSlotRepository.getSlots()
        val currentBuildingType = ProductionSlot.resolveBuildingType(buildingId)
        val alreadyAssigned = BuildingFacadeImpl.isDiscipleAssignedToOtherSlot(
            discipleId = discipleId,
            slots = allSlots,
            currentBuildingType = currentBuildingType,
            currentSlotIndex = slotIndex
        )
        if (alreadyAssigned) return

        val existingSlot = productionSlotRepository.getSlotByBuildingId(buildingId, slotIndex)

        if (existingSlot != null && existingSlot.isWorking) {
            return
        }

        existingSlot?.assignedDiscipleId?.let { oldDiscipleId ->
            updateDiscipleStatus(oldDiscipleId, DiscipleStatus.IDLE)
        }

        if (existingSlot != null) {
            withContext(Dispatchers.IO) {
                productionSlotRepository.updateSlotByBuildingId(buildingId, slotIndex) { slot ->
                    slot.copy(assignedDiscipleId = discipleId, assignedDiscipleName = disciple.name)
                }
            }
        } else {
            val buildingType = ProductionSlot.resolveBuildingType(buildingId)
            withContext(Dispatchers.IO) {
                productionSlotRepository.addSlot(ProductionSlot.createIdle(
                    slotIndex = slotIndex,
                    buildingType = buildingType,
                    buildingId = buildingId
                ).copy(assignedDiscipleId = discipleId, assignedDiscipleName = disciple.name))
            }
        }
    }

    suspend fun removeDiscipleFromBuilding(buildingId: String, slotIndex: Int) {
        removeDiscipleFromBuildingInternal(buildingId, slotIndex)
    }

    private suspend fun removeDiscipleFromBuildingInternal(buildingId: String, slotIndex: Int) {
        val existingSlot = productionSlotRepository.getSlotByBuildingId(buildingId, slotIndex) ?: return

        if (existingSlot.isWorking) {
            return
        }

        withContext(Dispatchers.IO) {
            productionSlotRepository.updateSlotByBuildingId(buildingId, slotIndex) { slot ->
                slot.copy(assignedDiscipleId = null, assignedDiscipleName = "")
            }
        }
    }

    suspend fun startAlchemy(slotIndex: Int, recipeId: String): DomainResult<ProductionSlot> {
        if (slotIndex < 0) {
            return DomainResult.Failure(AppError.Domain.Production.InvalidSlot(slotIndex = slotIndex))
        }

        val data = stateStore.gameData.value

        val alchemySlot = productionSlotRepository.getSlotByBuildingId(BuildingNames.ALCHEMY, slotIndex)
        if (alchemySlot != null && alchemySlot.isWorking) {
            return DomainResult.Failure(AppError.Domain.Production.SlotBusy(slotIndex = slotIndex))
        }
        if (alchemySlot?.assignedDiscipleId.isNullOrEmpty()) {
            return DomainResult.Failure(AppError.Domain.Production.DiscipleNotAvailable(discipleId = ""))
        }

        val recipe = PillRecipeDatabase.getRecipeById(recipeId)
            ?: return DomainResult.Failure(AppError.Domain.Production.RecipeNotFound(recipeId = recipeId))

        val alchemyPolicyBonus = if (data.sectPolicies.alchemyIncentive)
            com.xianxia.sect.core.GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_BASE_EFFECT else 0.0

        val disciple = alchemySlot?.assignedDiscipleId?.let { id ->
            stateStore.disciples.value.find { it.id == id }
        }
        val effectiveSuccessRate = formulaService.buildSuccessRateZones(
            disciple = disciple,
            buildingId = BuildingNames.ALCHEMY,
            baseRate = recipe.successRate,
            policyBonus = alchemyPolicyBonus
        ).calculate()

        val result = productionCoordinator.startAlchemyAtomic(
            slotIndex = slotIndex,
            recipeId = recipeId,
            currentYear = data.gameYear,
            currentMonth = data.gameMonth,
            herbs = stateStore.getCurrentHerbs(),
            buildingId = BuildingNames.ALCHEMY,
            alchemyPolicyBonus = alchemyPolicyBonus
        )

        val startData = when (result) {
            is DomainResult.Failure -> return result
            is DomainResult.Partial -> result.data
            is DomainResult.Success -> result.data
        }
        stateStore.update { herbs.replaceAll(startData.materialUpdate.herbs) }

        val actualDuration = calculateWorkDurationWithAllDisciples(recipe.duration, BuildingNames.ALCHEMY)

        val currentAbsoluteMonth = com.xianxia.sect.core.engine.LazyEvaluationDispatcher.toAbsoluteMonth(data.gameYear, data.gameMonth)
        // startAlchemyAtomic 已确保槽位存在，此处直接更新为最终值
        withContext(Dispatchers.IO) {
            productionSlotRepository.updateSlotByBuildingId(BuildingNames.ALCHEMY, slotIndex) { slot ->
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
                    completionMonth = currentAbsoluteMonth + actualDuration.coerceAtLeast(1),
                    completionPhase = 2
                )
            }
        }

        return DomainResult.Success(startData.slot)
    }

    suspend fun startForging(slotIndex: Int, recipeId: String): DomainResult<ProductionSlot> {
        if (slotIndex < 0) {
            return DomainResult.Failure(AppError.Domain.Production.InvalidSlot(slotIndex = slotIndex))
        }
        val data = stateStore.gameData.value

        val forgeSlot = productionSlotRepository.getSlotByBuildingId(BuildingNames.FORGE, slotIndex)
        if (forgeSlot != null && forgeSlot.isWorking) {
            return DomainResult.Failure(AppError.Domain.Production.SlotBusy(slotIndex = slotIndex))
        }
        if (forgeSlot?.assignedDiscipleId.isNullOrEmpty()) {
            return DomainResult.Failure(AppError.Domain.Production.DiscipleNotAvailable(discipleId = ""))
        }

        val recipe = ForgeRecipeDatabase.getRecipeById(recipeId)
            ?: return DomainResult.Failure(AppError.Domain.Production.RecipeNotFound(recipeId = recipeId))

        val forgePolicyBonus = if (data.sectPolicies.forgeIncentive)
            com.xianxia.sect.core.GameConfig.PolicyConfig.FORGE_INCENTIVE_BASE_EFFECT else 0.0

        val disciple = forgeSlot?.assignedDiscipleId?.let { id ->
            stateStore.disciples.value.find { it.id == id }
        }
        val effectiveSuccessRate = formulaService.buildSuccessRateZones(
            disciple = disciple,
            buildingId = BuildingNames.FORGE,
            baseRate = recipe.successRate,
            policyBonus = forgePolicyBonus
        ).calculate()

        val result = productionCoordinator.startForgingAtomic(
            slotIndex = slotIndex,
            recipeId = recipeId,
            currentYear = data.gameYear,
            currentMonth = data.gameMonth,
            materials = stateStore.getCurrentMaterials(),
            buildingId = BuildingNames.FORGE,
            forgePolicyBonus = forgePolicyBonus
        )

        val startData = when (result) {
            is DomainResult.Failure -> return result
            is DomainResult.Partial -> result.data
            is DomainResult.Success -> result.data
        }
        stateStore.update { materials.replaceAll(startData.materialUpdate.materials) }

        val baseDuration = ForgeRecipeDatabase.getDurationByTier(recipe.tier)
        val actualDuration = calculateWorkDurationWithAllDisciples(baseDuration, BuildingNames.FORGE)

        val currentAbsoluteMonth = com.xianxia.sect.core.engine.LazyEvaluationDispatcher.toAbsoluteMonth(data.gameYear, data.gameMonth)
        // startForgingAtomic 已确保槽位存在，此处直接更新为最终值
        withContext(Dispatchers.IO) {
            productionSlotRepository.updateSlotByBuildingId(BuildingNames.FORGE, slotIndex) { slot ->
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
                    completionMonth = currentAbsoluteMonth + actualDuration.coerceAtLeast(1),
                    completionPhase = 2
                )
            }
        }

        return DomainResult.Success(startData.slot)
    }

    /**
     * Auto-collect a completed slot's result and reset it to IDLE.
     * Called internally by the auto-harvest system during month advancement.
     */
    private suspend fun autoCollectSlotResult(slot: ProductionSlot) {
        completeBuildingTaskFromProductionSlot(slot)

        slot.assignedDiscipleId?.let { discipleId ->
            updateDiscipleStatus(discipleId, DiscipleStatus.IDLE)
        }

        withContext(Dispatchers.IO) {
            productionCoordinator.resetSlotByBuildingIdAtomic(slot.buildingId, slot.slotIndex)
        }
    }

    /**
     * Auto-collect a completed alchemy slot with success rate check.
     * Returns the alchemy result for event recording.
     */
    private suspend fun autoCollectAlchemyResult(slot: ProductionSlot): AlchemyResult? {
        val rng = rngManager.getRng(com.xianxia.sect.core.util.RngPartition.SYSTEM)
        val success = rng.nextDouble() <= slot.successRate

        var pill: Pill? = null
        if (success) {
            val roll = rng.nextDouble()
            val grade = when {
                roll < 0.06 -> PillGrade.HIGH
                roll < 0.40 -> PillGrade.MEDIUM
                else -> PillGrade.LOW
            }
            val recipeId = slot.recipeId
            val template = recipeId?.let { rid ->
                val baseId = rid.substringBeforeLast("_")
                ItemDatabase.getPillById("${baseId}_${grade.name.lowercase()}")
            }
            pill = if (template != null) {
                ItemDatabase.createPillFromTemplate(template)
            } else {
                Pill(
                    name = slot.outputItemName,
                    rarity = slot.outputItemRarity,
                    grade = grade,
                    category = PillCategory.CULTIVATION,
                    description = "通过炼丹炉炼制而成",
                    minRealm = GameConfig.Realm.getMinRealmForRarity(slot.outputItemRarity),
                    quantity = 1
                )
            }
            inventorySystem.addPill(pill)
        }

        withContext(Dispatchers.IO) {
            productionCoordinator.resetSlotByBuildingIdAtomic(BuildingNames.ALCHEMY, slot.slotIndex)
        }

        slot.assignedDiscipleId?.let { discipleId ->
            updateDiscipleStatus(discipleId, DiscipleStatus.IDLE)
        }

        return AlchemyResult(
            success = success,
            pill = pill,
            message = if (success) "成功" else "失败"
        )
    }

    /**
     * Auto-harvest all completed alchemy slots.
     * Called internally during month advancement.
     */
    suspend fun autoHarvestCompletedAlchemySlots(): List<AlchemyResult> {
        val data = stateStore.gameData.value
        val results = mutableListOf<AlchemyResult>()
        val alchemySlots = productionSlotRepository.getSlotsByType(BuildingType.ALCHEMY)
        alchemySlots.forEach { slot ->
            if (slot.isCompleted || (slot.isWorking && slot.isFinished(data.gameYear, data.gameMonth))) {
                autoCollectAlchemyResult(slot)?.let { results.add(it) }
            }
        }
        return results
    }

    /**
     * Auto-harvest a completed forge slot.
     * Called internally during month advancement.
     */
    suspend fun autoHarvestForgeSlot(slot: ProductionSlot) {
        autoCollectSlotResult(slot)
    }

    private suspend fun updateDiscipleStatus(discipleId: String, status: DiscipleStatus) {
        stateStore.update {
            val currentList = discipleTables.assembleAll()
            val updated = currentList.map {
                if (it.id == discipleId) it.copy(status = status) else it
            }
            discipleTables.clear()
            updated.forEach { discipleTables.insert(it) }
        }
    }

    private fun getBuildingName(buildingId: String): String = BuildingNames.getDisplayName(buildingId)

    private fun calculateWorkDurationWithAllDisciples(baseDuration: Int, buildingId: String): Int {
        var totalSpeedBonus = 0.0
        val data = stateStore.gameData.value

        totalSpeedBonus += getElderPositionBonusLocal(buildingId)

        when (buildingId) {
            BuildingNames.FORGE, BuildingNames.ALCHEMY, "herbGarden" -> {
                val assignedDiscipleIds = when (buildingId) {
                    BuildingNames.FORGE -> productionSlotRepository.getSlotsByBuildingId(BuildingNames.FORGE).mapNotNull { it.assignedDiscipleId }
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

    private fun getElderPositionBonusLocal(buildingId: String): Double {
        val data = stateStore.gameData.value
        val elderSlots = data.elderSlots

        val elderDiscipleId = when (buildingId) {
            BuildingNames.FORGE -> elderSlots.forgeElder
            BuildingNames.ALCHEMY -> elderSlots.alchemyElder
            "herbGarden" -> elderSlots.herbGardenElder
            else -> null
        } ?: return 0.0

        val elderDisciple = stateStore.disciples.value.find { it.id == elderDiscipleId } ?: return 0.0

        return when (buildingId) {
            BuildingNames.FORGE -> {
                val baseline = GameConfig.PolicyConfig.ELDER_SKILL_BASELINE
                val diff = (elderDisciple.skills.artifactRefining - baseline).coerceAtLeast(0)
                diff * 0.01
            }
            BuildingNames.ALCHEMY -> {
                val baseline = GameConfig.PolicyConfig.ELDER_SKILL_BASELINE
                val diff = (elderDisciple.skills.pillRefining - baseline).coerceAtLeast(0)
                diff * 0.01
            }
            "herbGarden" -> {
                val baseline = GameConfig.PolicyConfig.ELDER_SKILL_BASELINE
                val diff = (elderDisciple.skills.spiritPlanting - baseline).coerceAtLeast(0)
                diff * 0.01
            }
            else -> 0.0
        }
    }

    private fun calculateReducedDurationLocal(baseDuration: Int, speedBonus: Double): Int {
        if (speedBonus <= 0) return baseDuration
        val reductionPercent = speedBonus / GameConfig.PolicyConfig.SPEED_REDUCTION_DIVISOR
        val reducedMonths = (baseDuration * reductionPercent).toInt()
        return (baseDuration - reducedMonths).coerceAtLeast(1)
    }

    private suspend fun completeBuildingTaskFromProductionSlot(slot: ProductionSlot) {
        val recipeId = slot.recipeId
        if (recipeId == null) {
            return
        }

        when (slot.buildingId) {
            BuildingNames.FORGE -> {
                val recipe = ForgeRecipeDatabase.getRecipeById(recipeId)
                if (recipe != null) {
                    val equipment = inventorySystem.createEquipmentFromRecipe(recipe)
                    inventorySystem.addEquipmentStack(equipment)
                }
            }
            BuildingNames.ALCHEMY -> {
                val recipe = PillRecipeDatabase.getRecipeById(recipeId)
                if (recipe != null) {
                    val roll = rngManager.getRng(com.xianxia.sect.core.util.RngPartition.SYSTEM).nextDouble()
                    val grade = when {
                        roll < 0.06 -> PillGrade.HIGH
                        roll < 0.40 -> PillGrade.MEDIUM
                        else -> PillGrade.LOW
                    }
                    val baseId = recipeId.substringBeforeLast("_")
                    val template = ItemDatabase.getPillById("${baseId}_${grade.name.lowercase()}")
                    val pill = if (template != null) {
                        ItemDatabase.createPillFromTemplate(template)
                    } else {
                        Pill(
                            name = recipe.name,
                            rarity = recipe.rarity,
                            grade = grade,
                            category = PillCategory.CULTIVATION,
                            description = "通过炼丹炉炼制而成",
                            minRealm = GameConfig.Realm.getMinRealmForRarity(recipe.rarity),
                            quantity = 1
                        )
                    }
                    inventorySystem.addPill(pill)
                }
            }
            else -> {
            }
        }
    }

}
