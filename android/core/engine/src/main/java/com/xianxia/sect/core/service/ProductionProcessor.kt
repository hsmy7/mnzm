package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.math.roundToInt
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.engine.system.InventoryFactories
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.*
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.domain.building.HerbGardenSystem
import com.xianxia.sect.core.engine.domain.building.HerbGardenAuraService
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.util.BuildingNames
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.util.ZoneCalculator
import com.xianxia.sect.core.util.TimeProgressUtil
import com.xianxia.sect.core.engine.annotation.GameService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@GameService("ProductionProcessor")
class ProductionProcessor @Inject constructor(
    private val stateStore: GameStateStore,
    private val inventorySystem: InventorySystem,
    private val inventoryConfig: InventoryConfig,
    private val scopeProvider: CoroutineScopeProvider,
    private val productionCoordinator: ProductionCoordinator,
    private val productionSlotRepository: ProductionSlotRepository,
    private val cultivationSettlement: CultivationSettlement,
    private val sharedState: CultivationSharedState,
    private val formulaService: FormulaService
) {

    companion object {
        private const val TAG = "ProductionProcessor"
    }

    // ── 建筑生产 ──────────────────────────────────────────────────────

    suspend fun processBuildingProduction(year: Int, month: Int) {
        val forgeSlots = productionSlotRepository.getSlotsByBuildingId(BuildingNames.FORGE)
        forgeSlots.forEach { slot ->
            if (slot.isWorking && slot.assignedDiscipleId.isNullOrEmpty()) return@forEach
            if (slot.isWorking && isSlotCompleteDynamic(slot, year, month)) {
                val recipeId = slot.recipeId
                if (recipeId != null) {
                    val recipe = ForgeRecipeDatabase.getRecipeById(recipeId)
                    if (recipe != null) {
                        val equipment = inventorySystem.createEquipmentFromRecipe(recipe)
                        inventorySystem.addEquipmentStack(equipment)
                    }
                }

                slot.assignedDiscipleId?.let { discipleId ->
                    stateStore.update {
                        val currentList = discipleTables.assembleAll()
                        val updated = currentList.map {
                            if (it.id == discipleId) it.copy(status = DiscipleStatus.IDLE) else it
                        }
                        discipleTables.clear()
                        updated.forEach { discipleTables.insert(it) }
                    }
                }

                productionSlotRepository.updateSlotByBuildingId(BuildingNames.FORGE, slot.slotIndex) { s ->
                    ProductionSlot.createIdle(
                        id = s.id,
                        slotIndex = slot.slotIndex,
                        buildingType = com.xianxia.sect.core.model.production.BuildingType.FORGE,
                        buildingId = BuildingNames.FORGE,
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
            if (slot.isWorking && isSlotCompleteDynamic(slot, year, month)) {
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
                            category = PillCategory.CULTIVATION,
                            description = "通过炼丹炉炼制而成",
                            minRealm = GameConfig.Realm.getMinRealmForRarity(slot.outputItemRarity),
                            quantity = 1
                        )
                    }
                    inventorySystem.addPill(pill)
                }

                slot.assignedDiscipleId?.let { discipleId ->
                    stateStore.update {
                        val currentList = discipleTables.assembleAll()
                        val updated = currentList.map {
                            if (it.id == discipleId) it.copy(status = DiscipleStatus.IDLE) else it
                        }
                        discipleTables.clear()
                        updated.forEach { discipleTables.insert(it) }
                    }
                }

                productionSlotRepository.updateSlotByBuildingId(BuildingNames.ALCHEMY, slot.slotIndex) { s ->
                    ProductionSlot.createIdle(
                        id = s.id,
                        slotIndex = slot.slotIndex,
                        buildingType = com.xianxia.sect.core.model.production.BuildingType.ALCHEMY,
                        buildingId = BuildingNames.ALCHEMY,
                        autoRestartEnabled = slot.autoRestartEnabled,
                        assignedDiscipleId = slot.assignedDiscipleId,
                        assignedDiscipleName = slot.assignedDiscipleName,
                        recipeId = slot.recipeId
                    )
                }
            }
        }
    }

    suspend fun processHerbGardenGrowth(state: MutableGameState) {
        val data = state.gameData
        val year = data.gameYear
        val month = data.gameMonth

        val herbGardenSlots = productionSlotRepository.getSlotsByType(com.xianxia.sect.core.model.production.BuildingType.HERB_GARDEN)
        herbGardenSlots.forEach { slot ->
            if (slot.isWorking && isSlotCompleteDynamic(slot, year, month)) {
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
                    if (!result.isSuccess) {
                        DomainLog.w(TAG, "HerbGarden harvest addHerb failed: ${herb.name} x${actualYield}, result=$result")
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

    suspend fun processAutoPlant(state: MutableGameState) {
        val data = state.gameData

        val herbGardenSlots = productionSlotRepository.getSlotsByType(com.xianxia.sect.core.model.production.BuildingType.HERB_GARDEN)
        val idleSlots = herbGardenSlots.filter {
            it.autoRestartEnabled && it.status == com.xianxia.sect.core.model.production.ProductionSlotStatus.IDLE
        }
        if (idleSlots.isEmpty()) return

        for (slot in idleSlots) {
            val seeds = state.seeds.all().filter { it.quantity > 0 }.sortedByDescending { it.rarity }
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
                baseDuration = seedToPlant.growTime,
                outputItemId = herbId ?: "",
                outputItemName = seedToPlant.name,
                expectedYield = seedToPlant.yield,
                autoRestartEnabled = slot.autoRestartEnabled,
                completionMonth = com.xianxia.sect.core.engine.LazyEvaluationDispatcher.toAbsoluteMonth(data.gameYear, data.gameMonth) + seedToPlant.growTime.coerceAtLeast(1),
                completionPhase = 3
            )

            productionSlotRepository.updateSlotByBuildingId("herbGarden", slot.slotIndex) { newSlot }
            // 消耗种子：直接在影子状态中扣减
            val newQuantity = seedToPlant.quantity - 1
            if (newQuantity <= 0) {
                state.seeds.remove(seedToPlant.id)
            } else {
                state.seeds.update(seedToPlant.id) { it.copy(quantity = newQuantity) }
            }
        }
    }

    suspend fun processSpiritFieldHarvest(state: MutableGameState) {
        val data = state.gameData
        val currentYear = data.gameYear
        val currentMonth = data.gameMonth
        val tables = state.discipleTables

        // 从影子组件表组装存活弟子（仅用于长老加成的属性查询）
        val allDisciples = tables.ids.filter { tables.isAlive[it] == 1 }
            .map { tables.assemble(it) }

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

                    // 直接在影子 herbs 中合并
                    val currentHerbsList = state.herbs.all()
                    val existingIdx = currentHerbsList.indexOfFirst { h ->
                        h.name == herbName && h.rarity == herbRarity && h.category == herbCat
                    }
                    if (existingIdx >= 0) {
                        val existing = currentHerbsList[existingIdx]
                        state.herbs.update(existing.id) {
                            it.copy(quantity = it.quantity + finalYield)
                        }
                    } else {
                        val newHerb = Herb(
                            id = java.util.UUID.randomUUID().toString(),
                            name = herbName, rarity = herbRarity,
                            description = dbHerb.description,
                            category = herbCat, quantity = finalYield
                        )
                        state.herbs.add(newHerb)
                    }
                }

                val idx = updatedPlants.indexOfFirst { it.buildingInstanceId == plant.buildingInstanceId }
                if (idx >= 0) {
                    val matchingSeed = HerbDatabase.getSeedByName(plant.seedName)
                    val existingSeed = state.seeds.all().find { s ->
                        s.name == plant.seedName &&
                            s.rarity == (matchingSeed?.rarity ?: 1) &&
                            s.growTime == plant.growTime && s.quantity > 0
                    }
                    val currentAbsoluteMonth = com.xianxia.sect.core.engine.LazyEvaluationDispatcher.toAbsoluteMonth(currentYear, currentMonth)
                    updatedPlants = updatedPlants.toMutableList().also {
                        if (existingSeed != null) {
                            // 消耗种子：直接操作影子 seeds
                            val newQty = existingSeed.quantity - 1
                            if (newQty <= 0) {
                                state.seeds.remove(existingSeed.id)
                            } else {
                                state.seeds.update(existingSeed.id) { it.copy(quantity = newQty) }
                            }
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
            state.gameData = data.copy(spiritFieldPlants = updatedPlants)
        }
    }

    /**
     * 灵植成熟速度乘区（Herb Garden Maturity Zone）。
     *
     * 公式：有效生长时间 = ceil(baseGrowTime / ((1 + elderZone) × (1 + auraZone) × (1 + policyZone)))
     */
    data class HerbGardenMaturityZones(
        val elderZone: Double = 0.0,   // 灵植长老乘区
        val auraZone: Double = 0.0,    // 光环弟子乘区
        val policyZone: Double = 0.0,  // 灵药培育政策乘区
    ) {
        /** 计算总加速倍率（用于传入 calculateEffectiveGrowTime） */
        fun totalMultiplier(): Double =
            ZoneCalculator.zoneToMultiplier(
                ZoneCalculator.calculate(1.0, elderZone, auraZone, policyZone)
            ) - 1.0
    }

    fun calculateSpiritFieldMaturityBonus(
        plant: SpiritFieldPlant,
        gameData: GameData,
        allDisciples: List<Disciple>
    ): Double {
        val zones = buildHerbGardenMaturityZones(plant, gameData, allDisciples)
        return zones.totalMultiplier()
    }

    private fun buildHerbGardenMaturityZones(
        plant: SpiritFieldPlant,
        gameData: GameData,
        allDisciples: List<Disciple>
    ): HerbGardenMaturityZones {
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

        return HerbGardenMaturityZones(
            elderZone = elderBonus,
            auraZone = auraBonus,
            policyZone = policyBonus
        )
    }

    suspend fun processAutoAlchemy() {
        val data = stateStore.gameData.value

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
            val currentHerbs = stateStore.getCurrentHerbs()
            val slot = alchemySlots.find { it.slotIndex == slotIndex } ?: break

            val recipeToStart = slot.recipeId
                ?.let { prevRecipeId ->
                    allRecipes.find { it.id == prevRecipeId }?.takeIf { recipe ->
                        recipe.materials.all { (materialId, requiredQuantity) ->
                            val herbData = HerbDatabase.getHerbById(materialId)
                            val herbName = herbData?.name
                            val herbRarity = herbData?.rarity ?: 1
                            val herb = currentHerbs.find { it.name == herbName && it.rarity == herbRarity }
                            herb != null && herb.quantity >= requiredQuantity
                        }
                    }
                }
                ?: allRecipes.firstOrNull { recipe ->
                    recipe.materials.all { (materialId, requiredQuantity) ->
                        val herbData = HerbDatabase.getHerbById(materialId)
                        val herbName = herbData?.name
                        val herbRarity = herbData?.rarity ?: 1
                        val herb = currentHerbs.find { it.name == herbName && it.rarity == herbRarity }
                        herb != null && herb.quantity >= requiredQuantity
                    }
                } ?: break

            val result = productionCoordinator.startAlchemyAtomic(
                slotIndex = slotIndex,
                recipeId = recipeToStart.id,
                currentYear = data.gameYear,
                currentMonth = data.gameMonth,
                herbs = currentHerbs,
                buildingId = BuildingNames.ALCHEMY,
                alchemyPolicyBonus = alchemyPolicyBonus
            )

            if (result is DomainResult.Success) {
                stateStore.update {
                    this.herbs.replaceAll(result.data.materialUpdate.herbs)
                }
            } else {
                break
            }
        }
    }

    suspend fun processAutoForge() {
        val data = stateStore.gameData.value

        val forgeSlots = productionSlotRepository.getSlotsByBuildingId(BuildingNames.FORGE)
        val idleSlotIndices = forgeSlots
            .filter { it.autoRestartEnabled
                && it.status == com.xianxia.sect.core.model.production.ProductionSlotStatus.IDLE
                && it.assignedDiscipleId.isNullOrEmpty().not() }
            .map { it.slotIndex }
        if (idleSlotIndices.isEmpty()) return

        val allRecipes = ForgeRecipeDatabase.getAllRecipes().sortedByDescending { it.rarity }
        val forgePolicyBonus = if (data.sectPolicies.forgeIncentive) GameConfig.PolicyConfig.FORGE_INCENTIVE_BASE_EFFECT else 0.0

        for (slotIndex in idleSlotIndices) {
            val currentMaterials = stateStore.getCurrentMaterials()
            val materialIndex = currentMaterials.groupBy { it.name to it.rarity }
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
                materials = currentMaterials,
                buildingId = BuildingNames.FORGE,
                forgePolicyBonus = forgePolicyBonus
            )

            if (result is DomainResult.Success) {
                stateStore.update {
                    this.materials.replaceAll(result.data.materialUpdate.materials)
                }
            } else {
                break
            }
        }
    }

    suspend fun processAutoAssign() {
        val data = stateStore.gameData.value
        val policies = data.sectPolicies
        val idleDisciples = mutableListOf<Disciple>().also { it.addAll(stateStore.disciples.value.filter { d -> d.status == DiscipleStatus.IDLE && d.isAlive }) }

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
            val emptyIndices = data.spiritMineSlots
                .mapIndexedNotNull { i, slot -> if (slot.discipleId.isEmpty()) i else null }
            if (emptyIndices.isNotEmpty()) {
                val assignments = emptyIndices.mapNotNull {
                    val c = takeCandidate(policies.autoMineFocused, policies.autoMineRootCounts, policies.autoMineThreshold) { it.mining }
                    c?.let { it.id to it.name }
                }
                if (assignments.isNotEmpty()) {
                    val assignIter = assignments.iterator()
                    stateStore.update {
                        gameData = gameData.copy(spiritMineSlots = gameData.spiritMineSlots.map { slot ->
                            if (slot.discipleId.isEmpty() && assignIter.hasNext()) {
                                val (id, name) = assignIter.next()
                                slot.copy(discipleId = id, discipleName = name)
                            } else slot
                        })
                    }
                    assignments.forEach { (id, _) -> markDiscipleAssigned(id, DiscipleStatus.MINING) }
                }
            }
        }

        if (policies.autoPlantFocused || policies.autoPlantRootCounts.isNotEmpty()) {
            batchAssignToProductionSlots(
                com.xianxia.sect.core.model.production.BuildingType.HERB_GARDEN, "herbGarden"
            ) {
                takeCandidate(policies.autoPlantFocused, policies.autoPlantRootCounts, policies.autoPlantThreshold) { it.spiritPlanting }
            }
        }

        if (policies.autoAlchemyFocused || policies.autoAlchemyRootCounts.isNotEmpty()) {
            batchAssignToProductionSlots(
                com.xianxia.sect.core.model.production.BuildingType.ALCHEMY, BuildingNames.ALCHEMY
            ) {
                takeCandidate(policies.autoAlchemyFocused, policies.autoAlchemyRootCounts, policies.autoAlchemyThreshold) { it.pillRefining }
            }
        }

        if (policies.autoForgeFocused || policies.autoForgeRootCounts.isNotEmpty()) {
            batchAssignToProductionSlots(
                com.xianxia.sect.core.model.production.BuildingType.FORGE, BuildingNames.FORGE
            ) {
                takeCandidate(policies.autoForgeFocused, policies.autoForgeRootCounts, policies.autoForgeThreshold) { it.artifactRefining }
            }
        }
    }

    /**
     * 批量安排弟子到指定生产建筑的所有空闲槽位。
     *
     * 依次取候选人填满所有空闲槽位，用 [ProductionSlotRepository.batchUpdate] 一次性写入。
     */
    private suspend fun batchAssignToProductionSlots(
        type: com.xianxia.sect.core.model.production.BuildingType,
        buildingId: String,
        takeNext: () -> Disciple?
    ) {
        val slots = productionSlotRepository.getSlotsByType(type)
        val emptySlots = slots.filter { slot ->
            slot.assignedDiscipleId.isNullOrEmpty()
                && slot.status == com.xianxia.sect.core.model.production.ProductionSlotStatus.IDLE
        }
        if (emptySlots.isEmpty()) return

        val updates = mutableListOf<com.xianxia.sect.core.repository.SlotUpdate>()
        for (emptySlot in emptySlots) {
            val candidate = takeNext() ?: break
            markDiscipleAssigned(candidate.id, DiscipleStatus.IDLE)
            updates.add(com.xianxia.sect.core.repository.SlotUpdate(type, emptySlot.slotIndex) { s ->
                s.copy(assignedDiscipleId = candidate.id, assignedDiscipleName = candidate.name)
            })
        }
        if (updates.isNotEmpty()) {
            productionSlotRepository.batchUpdate(updates)
        }
    }

    /**
     * 同步更新弟子状态（直接列写入，O(1)）。
     *
     * 在 [processAutoAssign] 中连续分配时，
     * 确保状态变更在后续槽位查询前已可见。
     */
    private suspend fun markDiscipleAssigned(discipleId: String, status: DiscipleStatus) {
        stateStore.update {
            discipleTables.statuses[discipleId.toInt()] = status
        }
    }

    fun isDiscipleFollowed(d: Disciple): Boolean {
        return d.statusData["followed"] == "true"
    }

    // ═══════════════════════════════════════════════════════════════
    // 影子状态批量生产方法
    //
    // 操作 [MutableGameState]（shadow）和 [MutableList]（productionSlots），
    // 不走 Repository/stateStore，用于并行 computePhaseTick。
    // 与现有同名方法的区别：所有 I/O 方向改为本地列表 + state 字段。
    // ═══════════════════════════════════════════════════════════════

    /**
     * 在影子状态上模拟 N 个月的生产循环。
     * 可由 [ProductionSubsystem.computePhaseTick] 在 ParallelDispatcher 上调用。
     */
    suspend fun processMonthlyProductionOnSlots(
        slots: MutableList<ProductionSlot>,
        state: MutableGameState,
        months: Int
    ) {
        repeat(months) {
            batchAutoAlchemy(slots, state)
            batchAutoForge(slots, state)
            batchBuildingCompletion(slots, state)
            batchHerbGardenGrowth(slots, state)
            batchSpiritFieldHarvest(slots, state)
            batchAutoPlant(slots, state)
        }
    }

    /** 影子版自动炼丹：从 state 读取政策/草药，直接修改 slots */
    private suspend fun batchAutoAlchemy(
        slots: MutableList<ProductionSlot>,
        state: MutableGameState
    ) {
        val gd = state.gameData
        val policyBonus = if (gd.sectPolicies.alchemyIncentive)
            GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_BASE_EFFECT else 0.0
        val allRecipes = PillRecipeDatabase.getAllRecipes().sortedByDescending { it.rarity }

        val idleSlotIndices = slots
            .filter { it.buildingType == com.xianxia.sect.core.model.production.BuildingType.ALCHEMY }
            .filter { it.autoRestartEnabled && it.status == ProductionSlotStatus.IDLE
                && !it.assignedDiscipleId.isNullOrEmpty() }
            .map { it.slotIndex }

        for (slotIndex in idleSlotIndices) {
            val currentHerbs = state.herbs.all()
            val recipeToStart = findRecipe(allRecipes, currentHerbs) ?: break
            val slotIdx = slots.indexOfFirst { it.buildingType == com.xianxia.sect.core.model.production.BuildingType.ALCHEMY && it.slotIndex == slotIndex }
            if (slotIdx < 0) continue

            // 消耗材料
            consumeHerbsForRecipeLocal(recipeToStart.materials, currentHerbs, state)
            val absoluteMonth = gd.gameYear * 12 + gd.gameMonth

            val assignedId = slots[slotIdx].assignedDiscipleId
            val assignedName = slots[slotIdx].assignedDiscipleName
            slots[slotIdx] = slots[slotIdx].copy(
                status = ProductionSlotStatus.WORKING,
                recipeId = recipeToStart.id,
                recipeName = recipeToStart.name,
                startYear = gd.gameYear,
                startMonth = gd.gameMonth,
                duration = recipeToStart.duration,
                baseDuration = recipeToStart.duration,
                successRate = (recipeToStart.successRate + policyBonus).coerceIn(0.0, 1.0),
                completionMonth = absoluteMonth + recipeToStart.duration.coerceAtLeast(1),
                completionPhase = 3,
                outputItemId = recipeToStart.id,
                outputItemName = recipeToStart.name,
                outputItemRarity = recipeToStart.rarity
            )
        }
    }

    /** 影子版自动锻造 */
    private suspend fun batchAutoForge(
        slots: MutableList<ProductionSlot>,
        state: MutableGameState
    ) {
        val gd = state.gameData
        val policyBonus = if (gd.sectPolicies.forgeIncentive)
            GameConfig.PolicyConfig.FORGE_INCENTIVE_BASE_EFFECT else 0.0
        val allRecipes = ForgeRecipeDatabase.getAllRecipes().sortedByDescending { it.rarity }
        val materialIndex = state.materials.all().groupBy { it.name to it.rarity }
            .mapValues { (_, list) -> list.sumOf { it.quantity } }

        val idleSlotIndices = slots
            .filter { it.buildingType == com.xianxia.sect.core.model.production.BuildingType.FORGE }
            .filter { it.autoRestartEnabled && it.status == ProductionSlotStatus.IDLE
                && !it.assignedDiscipleId.isNullOrEmpty() }
            .map { it.slotIndex }

        for (slotIndex in idleSlotIndices) {
            val recipeToStart = findForgeRecipe(allRecipes, materialIndex) ?: break
            val slotIdx = slots.indexOfFirst { it.buildingType == com.xianxia.sect.core.model.production.BuildingType.FORGE && it.slotIndex == slotIndex }
            if (slotIdx < 0) continue

            consumeMaterialsForRecipeLocal(recipeToStart.materials, state)
            val absoluteMonth = gd.gameYear * 12 + gd.gameMonth
            val duration = ForgeRecipeDatabase.getDurationByTier(recipeToStart.tier)

            slots[slotIdx] = slots[slotIdx].copy(
                status = ProductionSlotStatus.WORKING,
                recipeId = recipeToStart.id,
                recipeName = recipeToStart.name,
                startYear = gd.gameYear,
                startMonth = gd.gameMonth,
                duration = duration,
                successRate = (recipeToStart.successRate + policyBonus).coerceIn(0.0, 1.0),
                completionMonth = absoluteMonth + duration.coerceAtLeast(1),
                completionPhase = 3,
                outputItemId = recipeToStart.id,
                outputItemName = recipeToStart.name,
                outputItemRarity = recipeToStart.rarity
            )
        }
    }

    /** 影子版生产完成检测 */
    private suspend fun batchBuildingCompletion(
        slots: MutableList<ProductionSlot>,
        state: MutableGameState
    ) {
        val year = state.gameData.gameYear
        val month = state.gameData.gameMonth

        for (i in slots.indices) {
            val slot = slots[i]
            if (slot.status != ProductionSlotStatus.WORKING) continue
            if (!isSlotCompleteDynamic(slot, year, month)) continue

            when (slot.buildingType) {
                com.xianxia.sect.core.model.production.BuildingType.FORGE -> {
                    slot.recipeId?.let { rid ->
                        val recipe = ForgeRecipeDatabase.getRecipeById(rid)
                        if (recipe != null) {
                            val equipment = InventoryFactories.createEquipmentFromRecipe(recipe)
                            state.equipmentStacks.add(equipment)
                        }
                    }
                    slot.assignedDiscipleId?.toIntOrNull()?.let { did ->
                        state.discipleTables.statuses[did] = DiscipleStatus.IDLE
                    }
                    slots[i] = ProductionSlot.createIdle(
                        id = slot.id, slotIndex = slot.slotIndex,
                        buildingType = com.xianxia.sect.core.model.production.BuildingType.FORGE,
                        buildingId = slot.buildingId,
                        autoRestartEnabled = slot.autoRestartEnabled,
                        assignedDiscipleId = slot.assignedDiscipleId,
                        assignedDiscipleName = slot.assignedDiscipleName ?: "",
                        recipeId = slot.recipeId
                    )
                }
                com.xianxia.sect.core.model.production.BuildingType.ALCHEMY -> {
                    val success = kotlin.random.Random.nextDouble() <= slot.successRate
                    if (success) {
                        val grade = com.xianxia.sect.core.model.PillGrade.random()
                        val baseId = slot.recipeId?.substringBeforeLast("_")
                        val template = baseId?.let { ItemDatabase.getPillById("${baseId}_${grade.name.lowercase()}") }
                        val pill = if (template != null) ItemDatabase.createPillFromTemplate(template)
                        else com.xianxia.sect.core.model.Pill(
                            name = slot.outputItemName, rarity = slot.outputItemRarity,
                            grade = grade, category = PillCategory.CULTIVATION,
                            description = "通过炼丹炉炼制而成",
                            minRealm = GameConfig.Realm.getMinRealmForRarity(slot.outputItemRarity),
                            quantity = 1
                        )
                        state.pills.add(pill)
                    }
                    slot.assignedDiscipleId?.toIntOrNull()?.let { did ->
                        state.discipleTables.statuses[did] = DiscipleStatus.IDLE
                    }
                    slots[i] = ProductionSlot.createIdle(
                        id = slot.id, slotIndex = slot.slotIndex,
                        buildingType = com.xianxia.sect.core.model.production.BuildingType.ALCHEMY,
                        buildingId = slot.buildingId,
                        autoRestartEnabled = slot.autoRestartEnabled,
                        assignedDiscipleId = slot.assignedDiscipleId,
                        assignedDiscipleName = slot.assignedDiscipleName ?: "",
                        recipeId = slot.recipeId
                    )
                }
                else -> { }
            }
        }
    }

    /** 影子版药园生长完成 */
    private suspend fun batchHerbGardenGrowth(
        slots: MutableList<ProductionSlot>,
        state: MutableGameState
    ) {
        val year = state.gameData.gameYear
        val month = state.gameData.gameMonth
        val herbPolicyBonus = if (state.gameData.sectPolicies.herbCultivation)
            GameConfig.PolicyConfig.HERB_CULTIVATION_BASE_EFFECT else 0.0

        for (i in slots.indices) {
            val slot = slots[i]
            if (slot.buildingType != com.xianxia.sect.core.model.production.BuildingType.HERB_GARDEN) continue
            if (slot.status != ProductionSlotStatus.WORKING) continue
            if (!isSlotCompleteDynamic(slot, year, month)) continue

            val herb = HerbDatabase.getHerbFromSeedName(slot.recipeName)
                ?: slot.recipeId?.let { HerbDatabase.getHerbFromSeed(it) }
            if (herb != null) {
                val baseYield = slot.expectedYield.coerceAtLeast(1)
                val actualYield = if (herbPolicyBonus > 0.0)
                    (baseYield + (baseYield * herbPolicyBonus).toInt()).coerceAtLeast(1)
                else baseYield
                state.herbs.add(Herb(
                    id = java.util.UUID.randomUUID().toString(),
                    name = herb.name, rarity = herb.rarity,
                    description = herb.description, category = herb.category,
                    quantity = actualYield
                ))
            }
            slots[i] = ProductionSlot.createIdle(
                id = slot.id, slotIndex = slot.slotIndex,
                buildingType = com.xianxia.sect.core.model.production.BuildingType.HERB_GARDEN,
                buildingId = slot.buildingId,
                autoRestartEnabled = slot.autoRestartEnabled,
                recipeId = slot.recipeId
            )
        }
    }

    /** 影子版灵田收获（已用 state，直接复用） */
    suspend fun batchSpiritFieldHarvest(
        slots: MutableList<ProductionSlot>,
        state: MutableGameState
    ) {
        // processSpiritFieldHarvest 已操作 state，只需确保 year/month 来自 state
        processSpiritFieldHarvest(state)
    }

    /** 影子版自动种植 */
    private suspend fun batchAutoPlant(
        slots: MutableList<ProductionSlot>,
        state: MutableGameState
    ) {
        val gd = state.gameData
        val idleSlots = slots.filter {
            it.buildingType == com.xianxia.sect.core.model.production.BuildingType.HERB_GARDEN &&
                it.autoRestartEnabled && it.status == ProductionSlotStatus.IDLE
        }
        if (idleSlots.isEmpty()) return

        val sortedSeeds = state.seeds.all().filter { it.quantity > 0 }
            .sortedByDescending { it.rarity }
        val plantedIds = mutableSetOf<String>()

        for (slot in idleSlots) {
            val seed = sortedSeeds.firstOrNull { it.id !in plantedIds } ?: break
            plantedIds.add(seed.id)
            val idx = slots.indexOfFirst { it.id == slot.id }
            if (idx < 0) continue

            val herbDbSeedId = HerbDatabase.getSeedByName(seed.name)?.id
            val herbId = herbDbSeedId?.let { HerbDatabase.getHerbIdFromSeedId(it) }
            val absoluteMonth = gd.gameYear * 12 + gd.gameMonth

            slots[idx] = com.xianxia.sect.core.model.production.ProductionSlot(
                id = slot.id, slotIndex = slot.slotIndex, slotId = slot.slotId,
                buildingType = com.xianxia.sect.core.model.production.BuildingType.HERB_GARDEN,
                buildingId = slot.buildingId,
                status = ProductionSlotStatus.WORKING,
                recipeId = herbDbSeedId ?: seed.id, recipeName = seed.name,
                startYear = gd.gameYear, startMonth = gd.gameMonth,
                duration = seed.growTime,
                outputItemId = herbId ?: "", outputItemName = seed.name,
                expectedYield = seed.yield,
                autoRestartEnabled = slot.autoRestartEnabled,
                completionMonth = absoluteMonth + seed.growTime.coerceAtLeast(1),
                completionPhase = 3
            )
            val newQty = seed.quantity - 1
            state.seeds.remove(seed.id)
            if (newQty > 0) state.seeds.add(seed.copy(quantity = newQty))
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 影子版工具方法
    // ═══════════════════════════════════════════════════════════════

    private fun findRecipe(
        recipes: List<PillRecipeDatabase.PillRecipe>,
        herbs: List<Herb>
    ): PillRecipeDatabase.PillRecipe? {
        return recipes.firstOrNull { recipe ->
            recipe.materials.all { (herbId, requiredQty) ->
                val herbData = HerbDatabase.getHerbById(herbId) ?: return@all false
                herbs.filter { it.name == herbData.name && it.rarity == herbData.rarity }
                    .sumOf { it.quantity } >= requiredQty
            }
        }
    }

    private fun findForgeRecipe(
        recipes: List<ForgeRecipeDatabase.ForgeRecipe>,
        materialIndex: Map<Pair<String, Int>, Int>
    ): ForgeRecipeDatabase.ForgeRecipe? {
        return recipes.firstOrNull { recipe ->
            recipe.materials.all { (materialId, requiredQty) ->
                val matData = com.xianxia.sect.core.registry.BeastMaterialDatabase.getMaterialById(materialId)
                matData != null && (materialIndex[matData.name to matData.rarity] ?: 0) >= requiredQty
            }
        }
    }

    private fun consumeHerbsForRecipeLocal(
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

    private fun consumeMaterialsForRecipeLocal(
        materials: Map<String, Int>,
        state: MutableGameState
    ) {
        for ((materialId, requiredQty) in materials) {
            val matData = com.xianxia.sect.core.registry.BeastMaterialDatabase.getMaterialById(materialId) ?: continue
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
    private fun isSlotCompleteDynamic(slot: ProductionSlot, year: Int, month: Int): Boolean {
        if (!slot.isWorking) return slot.status == ProductionSlotStatus.COMPLETED
        if (slot.duration <= 0) return true  // 保护：duration=0 → 立即完成

        val effectiveDuration = if (slot.baseDuration > 0) {
            formulaService.calculateWorkDurationWithAllDisciples(slot.baseDuration, slot.buildingId)
        } else {
            slot.duration  // 旧数据回退
        }

        return TimeProgressUtil.isTimeElapsed(slot.startYear, slot.startMonth, effectiveDuration, year, month)
    }

    /**
     * 全量重算所有活跃生产槽位的完成时间（Checkpoint 快照法）。
     *
     * 在策略切换/长老变更后调用，确保所有槽位的 completionMonth
     * 反映当前速率。由 [CultivationService.checkpointAllProduction] 委托。
     */
    suspend fun recalculateAllCompletionMonths() {
        val data = stateStore.gameData.value
        val currentMonth = data.gameYear * 12 + data.gameMonth

        val allSlots = productionSlotRepository.getSlots()
        for (slot in allSlots) {
            if (!slot.isWorking || slot.baseDuration <= 0) continue

            val oldDuration = slot.duration.coerceAtLeast(1)
            val elapsedMonths = ((data.gameYear - slot.startYear) * 12 + (data.gameMonth - slot.startMonth)).coerceAtLeast(0)
            val progressRatio = elapsedMonths.toDouble() / oldDuration
            if (progressRatio >= 1.0) continue

            val newDuration = formulaService.calculateWorkDurationWithAllDisciples(
                slot.baseDuration, slot.buildingId
            )
            if (newDuration == slot.duration) continue

            // 同步更新 successRate（政策/长老变化影响成功率）
            val newSuccessRate = recalculateSuccessRate(data, slot)

            val remainingMonths = ((1.0 - progressRatio) * newDuration).roundToInt().coerceAtLeast(1)
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

    /** 根据当前政策重算槽位的 successRate。从配方数据库读取基础值 + 当前政策加成。 */
    private fun recalculateSuccessRate(data: GameData, slot: ProductionSlot): Double {
        val baseRate = when (slot.buildingType) {
            com.xianxia.sect.core.model.production.BuildingType.ALCHEMY ->
                com.xianxia.sect.core.registry.PillRecipeDatabase.getRecipeById(slot.recipeId ?: "")?.successRate
            com.xianxia.sect.core.model.production.BuildingType.FORGE ->
                com.xianxia.sect.core.registry.ForgeRecipeDatabase.getRecipeById(slot.recipeId ?: "")?.successRate
            else -> null
        } ?: return slot.successRate  // 查不到配方则保持原值

        val policyBonus = when (slot.buildingType) {
            com.xianxia.sect.core.model.production.BuildingType.ALCHEMY ->
                if (data.sectPolicies.alchemyIncentive) GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_BASE_EFFECT else 0.0
            com.xianxia.sect.core.model.production.BuildingType.FORGE ->
                if (data.sectPolicies.forgeIncentive) GameConfig.PolicyConfig.FORGE_INCENTIVE_BASE_EFFECT else 0.0
            else -> 0.0
        }
        return (baseRate + policyBonus).coerceIn(0.0, 1.0)
    }
}
