package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.annotation.GameService
import kotlinx.coroutines.launch
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.registry.*
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.util.BuildingNames
import com.xianxia.sect.core.util.CoroutineScopeProvider
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Singleton

@GameService("BuildingService")
@Singleton
class BuildingService @Inject constructor(
    private val stateStore: GameStateStore,
    private val productionCoordinator: ProductionCoordinator,
    private val productionSlotRepository: ProductionSlotRepository,
private val inventorySystem: InventorySystem,
    private val scopeProvider: CoroutineScopeProvider
) {
    private val scope get() = scopeProvider.ioScope

    companion object {
        private const val TAG = "BuildingService"
    }

    @Suppress("DEPRECATION")
    fun getBuildingSlots(): List<BuildingSlot> {
        return productionSlotRepository.getSlotsByBuildingId("forge").map { it.toBuildingSlot() }
    }

    @Suppress("DEPRECATION")
    fun getAlchemySlots(): List<AlchemySlot> {
        return productionSlotRepository.getSlotsByType(BuildingType.ALCHEMY).map { it.toAlchemySlot() }
    }

    @Suppress("DEPRECATION")
    fun getPlantSlots(): List<PlantSlotData> {
        return productionSlotRepository.getSlotsByType(BuildingType.HERB_GARDEN).map { it.toPlantSlotData() }
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

        // Prevent assigning same disciple to multiple building slots
        val allSlots = productionSlotRepository.getSlots()
        val alreadyAssigned = allSlots.any {
            it.buildingId != buildingId && it.assignedDiscipleId == discipleId
        }
        if (alreadyAssigned) return

        val existingSlot = productionSlotRepository.getSlotByBuildingId(buildingId, slotIndex)

        if (existingSlot != null && existingSlot.isWorking) {
            return
        }

        existingSlot?.assignedDiscipleId?.let { oldDiscipleId ->
            updateDiscipleStatus(oldDiscipleId, DiscipleStatus.IDLE)
        }

        scope.launch {
            if (existingSlot != null) {
                productionSlotRepository.updateSlotByBuildingId(buildingId, slotIndex) { slot ->
                    slot.copy(assignedDiscipleId = discipleId, assignedDiscipleName = disciple.name)
                }
            } else {
                val buildingType = ProductionSlot.resolveBuildingType(buildingId)
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

    private fun removeDiscipleFromBuildingInternal(buildingId: String, slotIndex: Int) {
        val existingSlot = productionSlotRepository.getSlotByBuildingId(buildingId, slotIndex) ?: return

        if (existingSlot.isWorking) {
            return
        }

        scope.launch {
            productionSlotRepository.updateSlotByBuildingId(buildingId, slotIndex) { slot ->
                slot.copy(assignedDiscipleId = null, assignedDiscipleName = "")
            }
        }
    }

    @Suppress("DEPRECATION")
    fun getBuildingSlotsForBuilding(buildingId: String): List<BuildingSlot> {
        return productionSlotRepository.getSlotsByBuildingId(buildingId).map { it.toBuildingSlot() }
    }

    suspend fun startAlchemy(slotIndex: Int, recipeId: String): Boolean {
        if (slotIndex < 0) {
            return false
        }

        val data = stateStore.gameData.value

        val alchemySlot = productionSlotRepository.getSlotByBuildingId("alchemy", slotIndex)
        if (alchemySlot != null && alchemySlot.isWorking) {
            return false
        }
        if (alchemySlot?.assignedDiscipleId.isNullOrEmpty()) {
            return false
        }

        val result = productionCoordinator.startAlchemyAtomic(
            slotIndex = slotIndex,
            recipeId = recipeId,
            currentYear = data.gameYear,
            currentMonth = data.gameMonth,
            herbs = stateStore.getCurrentHerbs(),
            buildingId = "alchemy",
            alchemyPolicyBonus = if (data.sectPolicies.alchemyIncentive) com.xianxia.sect.core.GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_BASE_EFFECT else 0.0
        )

        when {
            !result.success -> {
                return false
            }
            result.materialUpdate != null -> {
                stateStore.update { herbs.replaceAll(result.materialUpdate.herbs) }

                val recipe = PillRecipeDatabase.getRecipeById(recipeId) ?: return false
                val actualDuration = calculateWorkDurationWithAllDisciples(recipe.duration, "alchemy")

                scope.launch {
                    val existingSlot = productionSlotRepository.getSlotByBuildingId("alchemy", slotIndex)
                    val currentAbsoluteMonth = com.xianxia.sect.core.engine.LazyEvaluationDispatcher.toAbsoluteMonth(data.gameYear, data.gameMonth)
                    if (existingSlot != null) {
                        productionSlotRepository.updateSlotByBuildingId("alchemy", slotIndex) { slot ->
                            slot.copy(
                                status = ProductionSlotStatus.WORKING,
                                recipeId = recipeId,
                                recipeName = recipe.name,
                                startYear = data.gameYear,
                                startMonth = data.gameMonth,
                                duration = actualDuration,
                                successRate = recipe.successRate,
                                requiredMaterials = recipe.materials,
                                outputItemId = recipeId,
                                outputItemName = recipe.name,
                                outputItemRarity = recipe.rarity,
                                completionMonth = currentAbsoluteMonth + actualDuration.coerceAtLeast(1),
                                completionPhase = 2  // 炼丹中旬
                            )
                        }
                    } else {
                        productionSlotRepository.addSlot(ProductionSlot(
                            slotIndex = slotIndex,
                            buildingType = BuildingType.ALCHEMY,
                            buildingId = "alchemy",
                            status = ProductionSlotStatus.WORKING,
                            recipeId = recipeId,
                            recipeName = recipe.name,
                            startYear = data.gameYear,
                            startMonth = data.gameMonth,
                            duration = actualDuration,
                            successRate = recipe.successRate,
                            requiredMaterials = recipe.materials,
                            outputItemId = recipeId,
                            outputItemName = recipe.name,
                            outputItemRarity = recipe.rarity,
                            completionMonth = currentAbsoluteMonth + actualDuration.coerceAtLeast(1),
                            completionPhase = 2  // 炼丹中旬
                        ))
                    }
                }

                return true
            }
        }

        return false
    }

    suspend fun startForging(slotIndex: Int, recipeId: String): Boolean {
        val data = stateStore.gameData.value

        val forgeSlot = productionSlotRepository.getSlotByBuildingId("forge", slotIndex)
        if (forgeSlot != null && forgeSlot.isWorking) {
            return false
        }
        if (forgeSlot?.assignedDiscipleId.isNullOrEmpty()) {
            return false
        }

        val result = productionCoordinator.startForgingAtomic(
            slotIndex = slotIndex,
            recipeId = recipeId,
            currentYear = data.gameYear,
            currentMonth = data.gameMonth,
            materials = stateStore.getCurrentMaterials(),
            buildingId = "forge",
            forgePolicyBonus = if (data.sectPolicies.forgeIncentive) com.xianxia.sect.core.GameConfig.PolicyConfig.FORGE_INCENTIVE_BASE_EFFECT else 0.0
        )

        when {
            !result.success -> {
                return false
            }
            result.materialUpdate != null -> {
                stateStore.update { materials.replaceAll(result.materialUpdate.materials) }

                val recipe = ForgeRecipeDatabase.getRecipeById(recipeId) ?: return false
                val baseDuration = ForgeRecipeDatabase.getDurationByTier(recipe.tier)
                val actualDuration = calculateWorkDurationWithAllDisciples(baseDuration, "forge")

                scope.launch {
                    val existingSlot = productionSlotRepository.getSlotByBuildingId("forge", slotIndex)
                    val currentAbsoluteMonth = com.xianxia.sect.core.engine.LazyEvaluationDispatcher.toAbsoluteMonth(data.gameYear, data.gameMonth)
                    if (existingSlot != null) {
                        productionSlotRepository.updateSlotByBuildingId("forge", slotIndex) { slot ->
                            slot.copy(
                                status = ProductionSlotStatus.WORKING,
                                recipeId = recipeId,
                                recipeName = recipe.name,
                                startYear = data.gameYear,
                                startMonth = data.gameMonth,
                                duration = actualDuration,
                                outputItemId = recipeId,
                                outputItemName = recipe.name,
                                outputItemRarity = recipe.rarity,
                                outputItemSlot = recipe.type.name,
                                completionMonth = currentAbsoluteMonth + actualDuration.coerceAtLeast(1),
                                completionPhase = 2  // 锻造中旬
                            )
                        }
                    } else {
                        productionSlotRepository.addSlot(ProductionSlot(
                            slotIndex = slotIndex,
                            buildingType = BuildingType.FORGE,
                            buildingId = "forge",
                            status = ProductionSlotStatus.WORKING,
                            recipeId = recipeId,
                            recipeName = recipe.name,
                            startYear = data.gameYear,
                            startMonth = data.gameMonth,
                            duration = actualDuration,
                            outputItemId = recipeId,
                            outputItemName = recipe.name,
                            outputItemRarity = recipe.rarity,
                            outputItemSlot = recipe.type.name,
                            assignedDiscipleId = existingSlot?.assignedDiscipleId,
                            assignedDiscipleName = existingSlot?.assignedDiscipleName ?: "",
                            completionMonth = currentAbsoluteMonth + actualDuration.coerceAtLeast(1),
                            completionPhase = 2  // 锻造中旬
                        ))
                    }
                }

                return true
            }
        }

        return false
    }

    /**
     * Auto-collect a completed slot's result and reset it to IDLE.
     * Called internally by the auto-harvest system during month advancement.
     */
    private fun autoCollectSlotResult(slot: ProductionSlot) {
        completeBuildingTaskFromProductionSlot(slot)

        slot.assignedDiscipleId?.let { discipleId ->
            updateDiscipleStatus(discipleId, DiscipleStatus.IDLE)
        }

        scope.launch {
            productionCoordinator.resetSlotByBuildingIdAtomic(slot.buildingId, slot.slotIndex)
        }
    }

    /**
     * Auto-collect a completed alchemy slot with success rate check.
     * Returns the alchemy result for event recording.
     */
    private suspend fun autoCollectAlchemyResult(slot: ProductionSlot): AlchemyResult? {
        val success = Random.nextDouble() <= slot.successRate

        var pill: Pill? = null
        if (success) {
            val grade = PillGrade.random()
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
                    description = "通过炼丹炉炼制而成",
                    minRealm = GameConfig.Realm.getMinRealmForRarity(slot.outputItemRarity),
                    quantity = 1
                )
            }
            inventorySystem.addPill(pill)
        }

        scope.launch {
            productionCoordinator.resetSlotByBuildingIdAtomic("alchemy", slot.slotIndex)
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
    fun autoHarvestForgeSlot(slot: ProductionSlot) {
        autoCollectSlotResult(slot)
    }

    fun clearPlantSlot(slotIndex: Int) {
        scope.launch {
            val slot = productionSlotRepository.getSlotByBuildingId("herbGarden", slotIndex)
            if (slot != null) {
                // Auto-harvest if slot is in COMPLETED state before clearing
                if (slot.isCompleted) {
                    completeBuildingTaskFromProductionSlot(slot)
                }
                productionSlotRepository.updateSlotByBuildingId("herbGarden", slotIndex) { s ->
                    ProductionSlot.createIdle(
                        id = s.id,
                        slotIndex = slotIndex,
                        buildingType = BuildingType.HERB_GARDEN,
                        buildingId = "herbGarden"
                    )
                }
            }
        }
    }

    private fun updateDiscipleStatus(discipleId: String, status: DiscipleStatus) {
        scope.launch { stateStore.update {
            val currentList = discipleTables.assembleAll()
            val updated = currentList.map {
                if (it.id == discipleId) it.copy(status = status) else it
            }
            discipleTables.clear()
            updated.forEach { discipleTables.insert(it) }
        } }
    }

    private fun getBuildingName(buildingId: String): String = BuildingNames.getDisplayName(buildingId)

    private fun calculateWorkDurationWithAllDisciples(baseDuration: Int, buildingId: String): Int {
        var totalSpeedBonus = 0.0
        val data = stateStore.gameData.value

        totalSpeedBonus += getElderPositionBonusLocal(buildingId)

        when (buildingId) {
            "forge", "alchemy", "herbGarden" -> {
                val assignedDiscipleIds = when (buildingId) {
                    "forge" -> productionSlotRepository.getSlotsByBuildingId("forge").mapNotNull { it.assignedDiscipleId }
                    "alchemy" -> emptyList()
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
            "forge" -> elderSlots.forgeElder
            "alchemy" -> elderSlots.alchemyElder
            "herbGarden" -> elderSlots.herbGardenElder
            else -> null
        } ?: return 0.0

        val elderDisciple = stateStore.disciples.value.find { it.id == elderDiscipleId } ?: return 0.0

        return when (buildingId) {
            "forge" -> {
                val baseline = 80
                val diff = (elderDisciple.skills.artifactRefining - baseline).coerceAtLeast(0)
                diff * 0.01
            }
            "alchemy" -> {
                val baseline = 80
                val diff = (elderDisciple.skills.pillRefining - baseline).coerceAtLeast(0)
                diff * 0.01
            }
            "herbGarden" -> {
                val baseline = 80
                val diff = (elderDisciple.skills.spiritPlanting - baseline).coerceAtLeast(0)
                diff * 0.01
            }
            else -> 0.0
        }
    }

    private fun calculateReducedDurationLocal(baseDuration: Int, speedBonus: Double): Int {
        if (speedBonus <= 0) return baseDuration
        val reductionPercent = speedBonus / 4.0
        val reducedMonths = (baseDuration * reductionPercent).toInt()
        return (baseDuration - reducedMonths).coerceAtLeast(1)
    }

    private fun completeBuildingTaskFromProductionSlot(slot: ProductionSlot) {
        val recipeId = slot.recipeId
        if (recipeId == null) {
            return
        }

        when (slot.buildingId) {
            "forge" -> {
                val recipe = ForgeRecipeDatabase.getRecipeById(recipeId)
                if (recipe != null) {
                    val equipment = inventorySystem.createEquipmentFromRecipe(recipe)
                    inventorySystem.addEquipmentStack(equipment)
                }
            }
            "alchemy" -> {
                val recipe = PillRecipeDatabase.getRecipeById(recipeId)
                if (recipe != null) {
                    val grade = PillGrade.random()
                    val baseId = recipeId.substringBeforeLast("_")
                    val template = ItemDatabase.getPillById("${baseId}_${grade.name.lowercase()}")
                    val pill = if (template != null) {
                        ItemDatabase.createPillFromTemplate(template)
                    } else {
                        Pill(
                            name = recipe.name,
                            rarity = recipe.rarity,
                            grade = grade,
                            description = "通过炼丹炉炼制而成",
                            minRealm = GameConfig.Realm.getMinRealmForRarity(recipe.rarity),
                            quantity = 1
                        )
                    }
                    inventorySystem.addPill(pill)
                }
            }
            "herbGarden" -> {
                val herb = HerbDatabase.getHerbFromSeedName(slot.recipeName)
                    ?: slot.recipeId?.let { HerbDatabase.getHerbFromSeed(it) }
                if (herb != null) {
                    val data = stateStore.gameData.value
                    val herbGrowthBonus = if (data.sectPolicies.herbCultivation) GameConfig.PolicyConfig.HERB_CULTIVATION_BASE_EFFECT else 0.0
                    val actualYield = HerbGardenSystem.calculateIncreasedYield(slot.expectedYield, herbGrowthBonus)
                    val herbItem = Herb(
                        name = herb.name,
                        rarity = herb.rarity,
                        description = herb.description,
                        category = herb.category,
                        quantity = actualYield
                    )
                    inventorySystem.addHerb(herbItem)
                }
            }
            else -> {
            }
        }
    }

    @Deprecated("Use ProductionSlot directly")
    private fun ProductionSlot.toBuildingSlot(): BuildingSlot = BuildingSlot(
        id = id,
        buildingId = buildingId,
        slotIndex = slotIndex,
        discipleId = assignedDiscipleId,
        discipleName = assignedDiscipleName,
        startYear = startYear,
        startMonth = startMonth,
        duration = duration,
        recipeId = recipeId,
        recipeName = recipeName,
        status = when (status) {
            ProductionSlotStatus.IDLE -> SlotStatus.IDLE
            ProductionSlotStatus.WORKING -> SlotStatus.WORKING
            ProductionSlotStatus.COMPLETED -> SlotStatus.COMPLETED
        }
    )

    @Deprecated("Use ProductionSlot directly")
    private fun ProductionSlot.toAlchemySlot(): AlchemySlot = AlchemySlot(
        id = id,
        slotIndex = slotIndex,
        recipeId = recipeId,
        recipeName = recipeName,
        pillName = outputItemName,
        pillRarity = outputItemRarity,
        startYear = startYear,
        startMonth = startMonth,
        duration = duration,
        status = when (status) {
            ProductionSlotStatus.IDLE -> AlchemySlotStatus.IDLE
            ProductionSlotStatus.WORKING -> AlchemySlotStatus.WORKING
            ProductionSlotStatus.COMPLETED -> AlchemySlotStatus.FINISHED
        },
        successRate = successRate,
        requiredMaterials = requiredMaterials
    )

    @Deprecated("Use ProductionSlot directly")
    private fun ProductionSlot.toPlantSlotData(): PlantSlotData = PlantSlotData(
        index = slotIndex,
        status = when (status) {
            ProductionSlotStatus.IDLE -> "idle"
            ProductionSlotStatus.WORKING -> "growing"
            ProductionSlotStatus.COMPLETED -> "mature"
        },
        seedId = recipeId ?: "",
        seedName = recipeName,
        startYear = startYear,
        startMonth = startMonth,
        growTime = duration,
        expectedYield = expectedYield
    )
}
