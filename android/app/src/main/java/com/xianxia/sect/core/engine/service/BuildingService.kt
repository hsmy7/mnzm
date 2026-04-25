package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.GameConfig
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.data.*
import com.xianxia.sect.core.engine.production.ProductionCoordinator
import com.xianxia.sect.core.engine.HerbGardenSystem
import com.xianxia.sect.core.model.production.ProductionError
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.engine.system.GameSystem
import com.xianxia.sect.core.engine.system.SystemPriority
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.util.BuildingNames
import com.xianxia.sect.di.ApplicationScopeProvider
import android.util.Log
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Singleton

@SystemPriority(order = 210)
@Singleton
class BuildingService @Inject constructor(
    private val stateStore: GameStateStore,
    private val productionCoordinator: ProductionCoordinator,
    private val productionSlotRepository: ProductionSlotRepository,
    private val eventService: EventService,
    private val inventorySystem: InventorySystem,
    private val applicationScopeProvider: ApplicationScopeProvider
) : GameSystem {
    override val systemName: String = "BuildingService"
    private val scope get() = applicationScopeProvider.ioScope

    override fun initialize() {
        Log.d(TAG, "BuildingService initialized as GameSystem")
    }

    override fun release() {
        Log.d(TAG, "BuildingService released")
    }

    override suspend fun clear() {}
    private var currentGameData: GameData
        get() = stateStore.currentTransactionMutableState()?.gameData ?: stateStore.gameData.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.gameData = value; return }
            scope.launch(kotlinx.coroutines.Dispatchers.IO) { stateStore.update { gameData = value } }
        }

    private var currentDisciples: List<Disciple>
        get() = stateStore.currentTransactionMutableState()?.disciples ?: stateStore.disciples.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.disciples = value; return }
            scope.launch(kotlinx.coroutines.Dispatchers.IO) { stateStore.update { disciples = value } }
        }

    private var currentHerbs: List<Herb>
        get() = stateStore.currentTransactionMutableState()?.herbs ?: stateStore.getCurrentHerbs()
        private set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.herbs = value; return }
            scope.launch(kotlinx.coroutines.Dispatchers.IO) { stateStore.update { herbs = value } }
        }

    private var currentMaterials: List<Material>
        get() = stateStore.currentTransactionMutableState()?.materials ?: stateStore.getCurrentMaterials()
        private set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.materials = value; return }
            scope.launch(kotlinx.coroutines.Dispatchers.IO) { stateStore.update { materials = value } }
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

    private var currentEquipmentInstances: List<EquipmentInstance>
        get() = stateStore.currentTransactionMutableState()?.equipmentInstances ?: stateStore.equipmentInstances.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.equipmentInstances = value; return }
            scope.launch(kotlinx.coroutines.Dispatchers.IO) { stateStore.update { equipmentInstances = value } }
        }

    private var currentPills: List<Pill>
        get() = stateStore.currentTransactionMutableState()?.pills ?: stateStore.pills.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.pills = value; return }
            scope.launch(kotlinx.coroutines.Dispatchers.IO) { stateStore.update { pills = value } }
        }

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

        val disciple = currentDisciples.find { it.id == discipleId } ?: return
        if (!disciple.isAlive || disciple.status != DiscipleStatus.IDLE) {
            eventService.addGameEvent("${disciple.name}无法被安排工作", EventType.WARNING)
            return
        }

        if (disciple.age < 5) {
            eventService.addGameEvent("${disciple.name}年龄太小，无法工作", EventType.WARNING)
            return
        }

        val existingSlot = productionSlotRepository.getSlotByBuildingId(buildingId, slotIndex)

        if (existingSlot != null && existingSlot.isWorking) {
            eventService.addGameEvent("该槽位正在工作中，无法更换弟子", EventType.WARNING)
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
            eventService.addGameEvent("该槽位正在工作中，无法移除弟子", EventType.WARNING)
            return
        }

        existingSlot.assignedDiscipleId?.let { oldDiscipleId ->
            val disciple = currentDisciples.find { it.id == oldDiscipleId }
            disciple?.let {
                eventService.addGameEvent("${it.name}从${getBuildingName(buildingId)}移除", EventType.INFO)
            }
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
        val maxSlotCount = 3
        if (slotIndex < 0 || slotIndex >= maxSlotCount) {
            eventService.addGameEvent("无效的炼丹槽位索引: $slotIndex (有效范围: 0-$maxSlotCount)", EventType.WARNING)
            return false
        }

        val data = currentGameData

        val alchemySlot = productionSlotRepository.getSlotByBuildingId("alchemy", slotIndex)
        if (alchemySlot != null && alchemySlot.isWorking) {
            eventService.addGameEvent("该炼丹槽位正在工作中", EventType.WARNING)
            return false
        }

        val result = productionCoordinator.startAlchemyAtomic(
            slotIndex = slotIndex,
            recipeId = recipeId,
            currentYear = data.gameYear,
            currentMonth = data.gameMonth,
            herbs = currentHerbs,
            buildingId = "alchemy",
            alchemyPolicyBonus = if (data.sectPolicies.alchemyIncentive) com.xianxia.sect.core.GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_BASE_EFFECT else 0.0
        )

        when {
            !result.success -> {
                val error = result.error
                when (error) {
                    is ProductionError.InsufficientMaterials -> eventService.addGameEvent("草药材料不足，无法开始炼丹", EventType.WARNING)
                    is ProductionError.RecipeNotFound -> eventService.addGameEvent("配方不存在", EventType.ERROR)
                    else -> eventService.addGameEvent("无法开始炼丹", EventType.WARNING)
                }
                return false
            }
            result.materialUpdate != null -> {
                updateHerbsSync(result.materialUpdate.herbs)

                val recipe = PillRecipeDatabase.getRecipeById(recipeId) ?: return false
                val actualDuration = calculateWorkDurationWithAllDisciples(recipe.duration, "alchemy")

                scope.launch {
                    val existingSlot = productionSlotRepository.getSlotByBuildingId("alchemy", slotIndex)
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
                                outputItemRarity = recipe.rarity
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
                            outputItemRarity = recipe.rarity
                        ))
                    }
                }

                eventService.addGameEvent("开始在炼丹炉工作", EventType.INFO)
                return true
            }
        }

        return false
    }

    suspend fun startForging(slotIndex: Int, recipeId: String): Boolean {
        val data = currentGameData

        val forgeSlot = productionSlotRepository.getSlotByBuildingId("forge", slotIndex)
        if (forgeSlot != null && forgeSlot.isWorking) {
            eventService.addGameEvent("该锻造槽位正在工作中", EventType.WARNING)
            return false
        }

        val result = productionCoordinator.startForgingAtomic(
            slotIndex = slotIndex,
            recipeId = recipeId,
            currentYear = data.gameYear,
            currentMonth = data.gameMonth,
            materials = currentMaterials,
            buildingId = "forge",
            forgePolicyBonus = if (data.sectPolicies.forgeIncentive) com.xianxia.sect.core.GameConfig.PolicyConfig.FORGE_INCENTIVE_BASE_EFFECT else 0.0
        )

        when {
            !result.success -> {
                val error = result.error
                when (error) {
                    is ProductionError.InsufficientMaterials -> eventService.addGameEvent("材料不足，无法开始锻造", EventType.WARNING)
                    is ProductionError.RecipeNotFound -> eventService.addGameEvent("配方不存在", EventType.ERROR)
                    else -> eventService.addGameEvent("无法开始锻造", EventType.WARNING)
                }
                return false
            }
            result.materialUpdate != null -> {
                updateMaterialsSync(result.materialUpdate.materials)

                val recipe = ForgeRecipeDatabase.getRecipeById(recipeId) ?: return false
                val baseDuration = ForgeRecipeDatabase.getDurationByTier(recipe.tier)
                val actualDuration = calculateWorkDurationWithAllDisciples(baseDuration, "forge")

                scope.launch {
                    val existingSlot = productionSlotRepository.getSlotByBuildingId("forge", slotIndex)
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
                                outputItemSlot = recipe.type.name
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
                            assignedDiscipleName = existingSlot?.assignedDiscipleName ?: ""
                        ))
                    }
                }

                eventService.addGameEvent("开始在锻造坊工作", EventType.INFO)
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
            eventService.addGameEvent("炼制成功！获得${grade.displayName}${slot.outputItemName}，已放入宗门仓库", EventType.INFO)
        } else {
            eventService.addGameEvent("炼制失败，材料损毁", EventType.ERROR)
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
        val data = currentGameData
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
        currentDisciples = currentDisciples.map {
            if (it.id == discipleId) it.copy(status = status) else it
        }
    }

    private fun getBuildingName(buildingId: String): String = BuildingNames.getDisplayName(buildingId)

    private fun calculateWorkDurationWithAllDisciples(baseDuration: Int, buildingId: String): Int {
        var totalSpeedBonus = 0.0
        val data = currentGameData

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
        val data = currentGameData
        val elderSlots = data.elderSlots

        val elderDiscipleId = when (buildingId) {
            "forge" -> elderSlots.forgeElder
            "alchemy" -> elderSlots.alchemyElder
            "herbGarden" -> elderSlots.herbGardenElder
            else -> null
        } ?: return 0.0

        val elderDisciple = currentDisciples.find { it.id == elderDiscipleId } ?: return 0.0

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
            eventService.addGameEvent("${BuildingNames.getDisplayName(slot.buildingId)}工作已完成，但无配方信息", EventType.WARNING)
            return
        }

        when (slot.buildingId) {
            "forge" -> {
                val recipe = ForgeRecipeDatabase.getRecipeById(recipeId)
                if (recipe != null) {
                    val equipment = EquipmentStack(
                        name = recipe.name,
                        rarity = recipe.rarity,
                        description = recipe.description,
                        slot = recipe.type,
                        minRealm = GameConfig.Realm.getMinRealmForRarity(recipe.rarity)
                    )
                    inventorySystem.addEquipmentStack(equipment)
                    eventService.addGameEvent("锻造完成！获得${recipe.name}，已放入宗门仓库", EventType.INFO)
                } else {
                    eventService.addGameEvent("锻造完成，但配方[$recipeId]不存在", EventType.ERROR)
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
                    eventService.addGameEvent("炼制完成！获得${grade.displayName}${recipe.name}，已放入宗门仓库", EventType.INFO)
                } else {
                    eventService.addGameEvent("炼制完成，但配方[$recipeId]不存在", EventType.ERROR)
                }
            }
            "herbGarden" -> {
                val herb = HerbDatabase.getHerbFromSeedName(slot.recipeName)
                    ?: slot.recipeId?.let { HerbDatabase.getHerbFromSeed(it) }
                if (herb != null) {
                    val data = currentGameData
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
                    eventService.addGameEvent("${herb.name}已成熟，收获${actualYield}个，已放入宗门仓库", EventType.INFO)
                } else {
                    eventService.addGameEvent("${BuildingNames.getDisplayName(slot.buildingId)}工作已完成，但无法识别种子对应的草药", EventType.WARNING)
                }
            }
            else -> {
                eventService.addGameEvent("${BuildingNames.getDisplayName(slot.buildingId)}工作已完成，产出类型暂不支持", EventType.INFO)
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
