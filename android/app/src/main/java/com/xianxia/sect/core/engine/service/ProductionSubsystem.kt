package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.launch
import kotlin.random.Random
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.*
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.system.AddResult
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.domain.building.HerbGardenSystem
import com.xianxia.sect.core.engine.domain.building.HerbGardenAuraService
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.di.ApplicationScopeProvider
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProductionSubsystem @Inject constructor(
    private val stateStore: GameStateStore,
    private val inventorySystem: InventorySystem,
    private val inventoryConfig: InventoryConfig,
    private val applicationScopeProvider: ApplicationScopeProvider,
    private val productionCoordinator: ProductionCoordinator,
    private val productionSlotRepository: ProductionSlotRepository,
    private val cultivationSettlement: CultivationSettlement,
    private val sharedState: CultivationSharedState
) {
    private val scope get() = applicationScopeProvider.scope

    companion object {
        private const val TAG = "ProductionSubsystem"
    }

    // ── 状态访问器 ──────────────────────────────────────────────────────

    private var currentGameData: GameData
        get() = stateStore.currentTransactionMutableState()?.gameData ?: stateStore.gameData.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.gameData = value; return }
            scope.launch { stateStore.update { gameData = value } }
        }

    private var currentDisciples: List<Disciple>
        get() = stateStore.currentTransactionMutableState()?.disciples ?: stateStore.disciples.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.disciples = value; return }
            scope.launch { stateStore.update { disciples = value } }
        }

    private var currentHerbs: List<Herb>
        get() = stateStore.currentTransactionMutableState()?.herbs ?: stateStore.getCurrentHerbs()
        private set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.herbs = value; return }
            scope.launch { stateStore.update { herbs = value } }
        }

    private var currentSeeds: List<Seed>
        get() = stateStore.currentTransactionMutableState()?.seeds ?: stateStore.getCurrentSeeds()
        private set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.seeds = value; return }
            scope.launch { stateStore.update { seeds = value } }
        }

    private var currentMaterials: List<Material>
        get() = stateStore.currentTransactionMutableState()?.materials ?: stateStore.getCurrentMaterials()
        private set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.materials = value; return }
            scope.launch { stateStore.update { materials = value } }
        }

    private suspend fun updateHerbsSync(value: List<Herb>) {
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) { ts.herbs = value; return }
        stateStore.update { herbs = value }
    }

    private suspend fun updateMaterialsSync(value: List<Material>) {
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) { ts.materials = value; return }
        stateStore.update { materials = value }
    }

    // ── 建筑生产 ──────────────────────────────────────────────────────

    suspend fun processBuildingProduction(year: Int, month: Int) {
        val forgeSlots = productionSlotRepository.getSlotsByBuildingId("forge")
        forgeSlots.forEach { slot ->
            if (slot.isWorking && slot.assignedDiscipleId.isNullOrEmpty()) return@forEach
            if (slot.isWorking && slot.isFinished(year, month)) {
                val recipeId = slot.recipeId
                if (recipeId != null) {
                    val recipe = ForgeRecipeDatabase.getRecipeById(recipeId)
                    if (recipe != null) {
                        val equipment = inventorySystem.createEquipmentFromRecipe(recipe)
                        inventorySystem.addEquipmentStack(equipment)
                    }
                }

                slot.assignedDiscipleId?.let { discipleId ->
                    currentDisciples = currentDisciples.map {
                        if (it.id == discipleId) it.copy(status = DiscipleStatus.IDLE) else it
                    }
                }

                productionSlotRepository.updateSlotByBuildingId("forge", slot.slotIndex) { s ->
                    ProductionSlot.createIdle(
                        id = s.id,
                        slotIndex = slot.slotIndex,
                        buildingType = com.xianxia.sect.core.model.production.BuildingType.FORGE,
                        buildingId = "forge",
                        autoRestartEnabled = slot.autoRestartEnabled,
                        assignedDiscipleId = slot.assignedDiscipleId,
                        assignedDiscipleName = slot.assignedDiscipleName,
                        recipeId = slot.recipeId
                    )
                }
            }
        }

        val alchemySlots = productionSlotRepository.getSlotsByType(com.xianxia.sect.core.model.production.BuildingType.ALCHEMY)
        alchemySlots.forEach { slot ->
            if (slot.isWorking && slot.assignedDiscipleId.isNullOrEmpty()) return@forEach
            if (slot.isWorking && slot.isFinished(year, month)) {
                val success = Random.nextDouble() <= slot.successRate
                if (success) {
                    val grade = PillGrade.random()
                    val template = slot.recipeId?.let { rid ->
                        val baseId = rid.substringBeforeLast("_")
                        ItemDatabase.getPillById("${baseId}_${grade.name.lowercase()}")
                    }
                    val pill = if (template != null) {
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

                slot.assignedDiscipleId?.let { discipleId ->
                    currentDisciples = currentDisciples.map {
                        if (it.id == discipleId) it.copy(status = DiscipleStatus.IDLE) else it
                    }
                }

                productionSlotRepository.updateSlotByBuildingId("alchemy", slot.slotIndex) { s ->
                    ProductionSlot.createIdle(
                        id = s.id,
                        slotIndex = slot.slotIndex,
                        buildingType = com.xianxia.sect.core.model.production.BuildingType.ALCHEMY,
                        buildingId = "alchemy",
                        autoRestartEnabled = slot.autoRestartEnabled,
                        assignedDiscipleId = slot.assignedDiscipleId,
                        assignedDiscipleName = slot.assignedDiscipleName,
                        recipeId = slot.recipeId
                    )
                }
            }
        }
    }

    suspend fun processHerbGardenGrowth(year: Int, month: Int) {
        val data = currentGameData

        val herbGardenSlots = productionSlotRepository.getSlotsByType(com.xianxia.sect.core.model.production.BuildingType.HERB_GARDEN)
        herbGardenSlots.forEach { slot ->
            if (slot.isWorking && slot.isFinished(year, month)) {
                val herb = HerbDatabase.getHerbFromSeedName(slot.recipeName)
                    ?: slot.recipeId?.let { HerbDatabase.getHerbFromSeed(it) }
                if (herb != null) {
                    val herbGrowthBonus = if (data.sectPolicies.herbCultivation) GameConfig.PolicyConfig.HERB_CULTIVATION_BASE_EFFECT else 0.0
                    val actualYield = HerbGardenSystem.calculateIncreasedYield(slot.expectedYield, herbGrowthBonus)
                    val herbItem = Herb(
                        id = java.util.UUID.randomUUID().toString(),
                        name = herb.name,
                        rarity = herb.rarity,
                        description = herb.description,
                        category = herb.category,
                        quantity = actualYield
                    )
                    val result = inventorySystem.addHerb(herbItem)
                    if (result != AddResult.SUCCESS) {
                        Log.w(TAG, "HerbGarden harvest addHerb failed: ${herb.name} x${actualYield}, result=$result")
                    }
                }

                productionSlotRepository.updateSlotByBuildingId("herbGarden", slot.slotIndex) { s ->
                    ProductionSlot.createIdle(
                        id = s.id,
                        slotIndex = slot.slotIndex,
                        buildingType = com.xianxia.sect.core.model.production.BuildingType.HERB_GARDEN,
                        buildingId = "herbGarden",
                        autoRestartEnabled = slot.autoRestartEnabled,
                        recipeId = slot.recipeId
                    )
                }
            }
        }
    }

    suspend fun processAutoPlant() {
        val data = currentGameData

        val herbGardenSlots = productionSlotRepository.getSlotsByType(com.xianxia.sect.core.model.production.BuildingType.HERB_GARDEN)
        val idleSlots = herbGardenSlots.filter {
            it.autoRestartEnabled && it.status == com.xianxia.sect.core.model.production.ProductionSlotStatus.IDLE
        }
        if (idleSlots.isEmpty()) return

        for (slot in idleSlots) {
            val seeds = currentSeeds.filter { it.quantity > 0 }.sortedByDescending { it.rarity }
            val seedToPlant = seeds.firstOrNull() ?: break

            val herbDbSeedId = HerbDatabase.getSeedByName(seedToPlant.name)?.id
            val herbId = herbDbSeedId?.let { HerbDatabase.getHerbIdFromSeedId(it) }
            val newSlot = com.xianxia.sect.core.model.production.ProductionSlot(
                id = slot.id,
                slotIndex = slot.slotIndex,
                buildingType = com.xianxia.sect.core.model.production.BuildingType.HERB_GARDEN,
                buildingId = "herbGarden",
                status = com.xianxia.sect.core.model.production.ProductionSlotStatus.WORKING,
                recipeId = herbDbSeedId ?: seedToPlant.id,
                recipeName = seedToPlant.name,
                startYear = data.gameYear,
                startMonth = data.gameMonth,
                duration = seedToPlant.growTime,
                outputItemId = herbId ?: "",
                outputItemName = seedToPlant.name,
                expectedYield = seedToPlant.yield,
                autoRestartEnabled = slot.autoRestartEnabled,
                completionMonth = com.xianxia.sect.core.engine.LazyEvaluationDispatcher.toAbsoluteMonth(data.gameYear, data.gameMonth) + seedToPlant.growTime.coerceAtLeast(1),
                completionPhase = 3
            )

            productionSlotRepository.updateSlotByBuildingId("herbGarden", slot.slotIndex) { newSlot }
            inventorySystem.removeSeedSync(seedToPlant.id, 1)
        }
    }

    suspend fun processSpiritFieldHarvest() {
        val data = currentGameData
        val currentYear = data.gameYear
        val currentMonth = data.gameMonth
        val allDisciples = currentDisciples

        val plants = data.spiritFieldPlants
        if (plants.isEmpty()) return

        var updatedPlants = plants
        var hasChanges = false

        plants.forEach { plant ->
            if (plant.seedId.isEmpty() || plant.growTime <= 0) return@forEach

            val elapsedMonths = (currentYear - plant.plantYear) * 12 + (currentMonth - plant.plantMonth)
            val speedBonus = calculateSpiritFieldMaturityBonus(plant, data, allDisciples)
            val effectiveGrowTime = HerbGardenAuraService.calculateEffectiveGrowTime(plant.growTime, speedBonus)

            if (elapsedMonths >= effectiveGrowTime) {
                val dbHerb = HerbDatabase.getHerbFromSeedName(plant.seedName)
                if (dbHerb != null) {
                    val finalYield = plant.expectedYield.coerceAtLeast(1)

                    val herbName = dbHerb.name
                    val herbRarity = dbHerb.rarity
                    val herbCat = dbHerb.category
                    val herbs = currentHerbs
                    val existingIdx = herbs.indexOfFirst { h ->
                        h.name == herbName && h.rarity == herbRarity && h.category == herbCat
                    }
                    if (existingIdx >= 0) {
                        val updated = herbs.toMutableList()
                        updated[existingIdx] = updated[existingIdx].let {
                            Herb(id = it.id, name = it.name, rarity = it.rarity, description = it.description,
                                category = it.category, quantity = it.quantity + finalYield)
                        }
                        currentHerbs = updated
                    } else {
                        currentHerbs = herbs + Herb(
                            id = java.util.UUID.randomUUID().toString(),
                            name = herbName, rarity = herbRarity, description = dbHerb.description,
                            category = herbCat, quantity = finalYield
                        )
                    }
                }

                val idx = updatedPlants.indexOfFirst { it.buildingInstanceId == plant.buildingInstanceId }
                if (idx >= 0) {
                    val matchingSeed = HerbDatabase.getSeedByName(plant.seedName)
                    val existingSeed = currentSeeds.find { s ->
                        s.name == plant.seedName && s.rarity == (matchingSeed?.rarity ?: 1) && s.growTime == plant.growTime && s.quantity > 0
                    }
                    val currentAbsoluteMonth = com.xianxia.sect.core.engine.LazyEvaluationDispatcher.toAbsoluteMonth(currentYear, currentMonth)
                    updatedPlants = updatedPlants.toMutableList().also {
                        if (existingSeed != null) {
                            inventorySystem.removeSeedSync(existingSeed.id, 1)
                            it[idx] = it[idx].copy(
                                plantYear = currentYear, plantMonth = currentMonth,
                                completionMonth = currentAbsoluteMonth + plant.growTime.coerceAtLeast(1),
                                completionPhase = 3
                            )
                        } else {
                            it[idx] = it[idx].copy(
                                seedId = "", seedName = "", growTime = 0, expectedYield = 0,
                                plantYear = 0, plantMonth = 0,
                                completionMonth = 0, completionPhase = 1
                            )
                        }
                    }
                    hasChanges = true
                }
            }
        }

        if (hasChanges) {
            currentGameData = currentGameData.copy(spiritFieldPlants = updatedPlants)
        }
    }

    fun calculateSpiritFieldMaturityBonus(
        plant: SpiritFieldPlant,
        gameData: GameData,
        allDisciples: List<Disciple>
    ): Double {
        val elderBonus = HerbGardenAuraService.calculateElderMaturityBonus(
            gameData.elderSlots, allDisciples
        )
        val policyBonus = if (gameData.sectPolicies.herbCultivation) {
            GameConfig.PolicyConfig.HERB_CULTIVATION_BASE_EFFECT
        } else 0.0
        val auraBonus = if (HerbGardenAuraService.isSpiritFieldInAura(
                plant.buildingInstanceId, gameData.placedBuildings
            )) {
            HerbGardenAuraService.calculateAuraMaturityBonus(gameData.elderSlots, allDisciples)
        } else 0.0

        return policyBonus + elderBonus + auraBonus
    }

    suspend fun processAutoAlchemy() {
        val data = currentGameData

        val alchemySlots = productionSlotRepository.getSlotsByType(com.xianxia.sect.core.model.production.BuildingType.ALCHEMY)
        val idleSlotIndices = alchemySlots
            .filter { it.autoRestartEnabled
                && it.status == com.xianxia.sect.core.model.production.ProductionSlotStatus.IDLE
                && it.assignedDiscipleId.isNullOrEmpty().not() }
            .map { it.slotIndex }
        if (idleSlotIndices.isEmpty()) return

        val allRecipes = PillRecipeDatabase.getAllRecipes().sortedByDescending { it.rarity }
        val alchemyPolicyBonus = if (data.sectPolicies.alchemyIncentive) GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_BASE_EFFECT else 0.0

        for (slotIndex in idleSlotIndices) {
            val herbs = currentHerbs
            val slot = alchemySlots.find { it.slotIndex == slotIndex } ?: break

            val recipeToStart = slot.recipeId
                ?.let { prevRecipeId ->
                    allRecipes.find { it.id == prevRecipeId }?.takeIf { recipe ->
                        recipe.materials.all { (materialId, requiredQuantity) ->
                            val herbData = HerbDatabase.getHerbById(materialId)
                            val herbName = herbData?.name
                            val herbRarity = herbData?.rarity ?: 1
                            val herb = herbs.find { it.name == herbName && it.rarity == herbRarity }
                            herb != null && herb.quantity >= requiredQuantity
                        }
                    }
                }
                ?: allRecipes.firstOrNull { recipe ->
                    recipe.materials.all { (materialId, requiredQuantity) ->
                        val herbData = HerbDatabase.getHerbById(materialId)
                        val herbName = herbData?.name
                        val herbRarity = herbData?.rarity ?: 1
                        val herb = herbs.find { it.name == herbName && it.rarity == herbRarity }
                        herb != null && herb.quantity >= requiredQuantity
                    }
                } ?: break

            val result = productionCoordinator.startAlchemyAtomic(
                slotIndex = slotIndex,
                recipeId = recipeToStart.id,
                currentYear = data.gameYear,
                currentMonth = data.gameMonth,
                herbs = herbs,
                buildingId = "alchemy",
                alchemyPolicyBonus = alchemyPolicyBonus
            )

            if (result.success) {
                if (result.materialUpdate != null) {
                    updateHerbsSync(result.materialUpdate.herbs)
                }
            } else {
                break
            }
        }
    }

    suspend fun processAutoForge() {
        val data = currentGameData

        val forgeSlots = productionSlotRepository.getSlotsByBuildingId("forge")
        val idleSlotIndices = forgeSlots
            .filter { it.autoRestartEnabled
                && it.status == com.xianxia.sect.core.model.production.ProductionSlotStatus.IDLE
                && it.assignedDiscipleId.isNullOrEmpty().not() }
            .map { it.slotIndex }
        if (idleSlotIndices.isEmpty()) return

        val allRecipes = ForgeRecipeDatabase.getAllRecipes().sortedByDescending { it.rarity }
        val forgePolicyBonus = if (data.sectPolicies.forgeIncentive) GameConfig.PolicyConfig.FORGE_INCENTIVE_BASE_EFFECT else 0.0

        for (slotIndex in idleSlotIndices) {
            val materials = currentMaterials
            val materialIndex = materials.groupBy { it.name to it.rarity }
                .mapValues { (_, list) -> list.sumOf { it.quantity } }
            val slot = forgeSlots.find { it.slotIndex == slotIndex } ?: break

            val recipeToStart = slot.recipeId
                ?.let { prevRecipeId ->
                    allRecipes.find { it.id == prevRecipeId }?.takeIf { recipe ->
                        recipe.materials.all { (materialId, requiredQuantity) ->
                            val materialData = BeastMaterialDatabase.getMaterialById(materialId)
                            materialData != null && run {
                                val available = materialIndex[materialData.name to materialData.rarity] ?: 0
                                available >= requiredQuantity
                            }
                        }
                    }
                }
                ?: allRecipes.firstOrNull { recipe ->
                    recipe.materials.all { (materialId, requiredQuantity) ->
                        val materialData = BeastMaterialDatabase.getMaterialById(materialId)
                        materialData != null && run {
                            val available = materialIndex[materialData.name to materialData.rarity] ?: 0
                            available >= requiredQuantity
                        }
                    }
                } ?: break

            val result = productionCoordinator.startForgingAtomic(
                slotIndex = slotIndex,
                recipeId = recipeToStart.id,
                currentYear = data.gameYear,
                currentMonth = data.gameMonth,
                materials = materials,
                buildingId = "forge",
                forgePolicyBonus = forgePolicyBonus
            )

            if (result.success) {
                if (result.materialUpdate != null) {
                    updateMaterialsSync(result.materialUpdate.materials)
                }
            } else {
                break
            }
        }
    }

    suspend fun processAutoAssign() {
        val data = currentGameData
        val policies = data.sectPolicies
        val idleDisciples = mutableListOf<Disciple>().also { it.addAll(currentDisciples.filter { d -> d.status == DiscipleStatus.IDLE && d.isAlive }) }

        fun takeCandidate(focused: Boolean, rootCounts: List<Int>, threshold: Int, attr: (Disciple) -> Int): Disciple? {
            val enabled = focused || rootCounts.isNotEmpty()
            if (!enabled || idleDisciples.isEmpty()) return null
            val candidate = idleDisciples
                .filter { d ->
                    val matchesFilter = (focused && isDiscipleFollowed(d)) || d.spiritRoot.types.size in rootCounts
                    matchesFilter && attr(d) >= threshold
                }
                .maxByOrNull { attr(it) }
            if (candidate != null) idleDisciples.remove(candidate)
            return candidate
        }

        if (policies.autoMineFocused || policies.autoMineRootCounts.isNotEmpty()) {
            val candidate = takeCandidate(policies.autoMineFocused, policies.autoMineRootCounts, policies.autoMineThreshold) { it.mining }
            if (candidate != null) {
                val emptyIndex = data.spiritMineSlots.indexOfFirst { it.discipleId.isEmpty() }
                if (emptyIndex >= 0) {
                    currentGameData = data.copy(spiritMineSlots = data.spiritMineSlots.mapIndexed { i, slot ->
                        if (i == emptyIndex) slot.copy(discipleId = candidate.id, discipleName = candidate.name) else slot
                    })
                    markDiscipleAssigned(candidate.id, DiscipleStatus.MINING)
                }
            }
        }

        if (policies.autoPlantFocused || policies.autoPlantRootCounts.isNotEmpty()) {
            val candidate = takeCandidate(policies.autoPlantFocused, policies.autoPlantRootCounts, policies.autoPlantThreshold) { it.spiritPlanting }
            if (candidate != null) {
                val slots = productionSlotRepository.getSlotsByType(com.xianxia.sect.core.model.production.BuildingType.HERB_GARDEN)
                val emptySlot = slots.firstOrNull { it.assignedDiscipleId.isNullOrEmpty() && it.status == com.xianxia.sect.core.model.production.ProductionSlotStatus.IDLE }
                if (emptySlot != null) {
                    productionSlotRepository.updateSlotByBuildingId("herbGarden", emptySlot.slotIndex) { s ->
                        s.copy(assignedDiscipleId = candidate.id, assignedDiscipleName = candidate.name)
                    }
                    markDiscipleAssigned(candidate.id, DiscipleStatus.IDLE)
                }
            }
        }

        if (policies.autoAlchemyFocused || policies.autoAlchemyRootCounts.isNotEmpty()) {
            assignToProductionSlot(
                takeCandidate(policies.autoAlchemyFocused, policies.autoAlchemyRootCounts, policies.autoAlchemyThreshold) { it.pillRefining },
                com.xianxia.sect.core.model.production.BuildingType.ALCHEMY, "alchemy"
            )
        }

        if (policies.autoForgeFocused || policies.autoForgeRootCounts.isNotEmpty()) {
            assignToProductionSlot(
                takeCandidate(policies.autoForgeFocused, policies.autoForgeRootCounts, policies.autoForgeThreshold) { it.artifactRefining },
                com.xianxia.sect.core.model.production.BuildingType.FORGE, "forge"
            )
        }
    }

    private suspend fun assignToProductionSlot(
        candidate: Disciple?, type: com.xianxia.sect.core.model.production.BuildingType, buildingId: String
    ) {
        if (candidate == null) return
        val slots = productionSlotRepository.getSlotsByType(type)
        val emptySlot = slots.firstOrNull { it.assignedDiscipleId.isNullOrEmpty() && it.status == com.xianxia.sect.core.model.production.ProductionSlotStatus.IDLE }
        if (emptySlot != null) {
            productionSlotRepository.updateSlotByBuildingId(buildingId, emptySlot.slotIndex) { s ->
                s.copy(assignedDiscipleId = candidate.id, assignedDiscipleName = candidate.name)
            }
            markDiscipleAssigned(candidate.id, DiscipleStatus.IDLE)
        }
    }

    private fun markDiscipleAssigned(discipleId: String, status: DiscipleStatus) {
        currentDisciples = currentDisciples.map { d ->
            if (d.id == discipleId) d.copy(status = status) else d
        }
    }

    fun isDiscipleFollowed(d: Disciple): Boolean {
        return d.statusData["followed"] == "true"
    }

    fun processSpiritMineProduction() {
        cultivationSettlement.processSpiritMineProduction()
    }
}
