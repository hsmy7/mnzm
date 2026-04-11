@file:Suppress("DEPRECATION")

package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.GameConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
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
import com.xianxia.sect.core.util.BuildingNames
import kotlin.random.Random

class BuildingService constructor(
    private val _gameData: MutableStateFlow<GameData>,
    private val _disciples: MutableStateFlow<List<Disciple>>,
    private val _herbs: MutableStateFlow<List<Herb>>,
    private val _materials: MutableStateFlow<List<Material>>,
    private val _equipment: MutableStateFlow<List<Equipment>>,
    private val _pills: MutableStateFlow<List<Pill>>,
    private val productionCoordinator: ProductionCoordinator,
    private val productionSlotRepository: ProductionSlotRepository,
    private val addEvent: (String, EventType) -> Unit,
    private val transactionMutex: Any,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "BuildingService"
    }

    fun getBuildingSlots(): List<BuildingSlot> {
        return productionSlotRepository.getSlotsByBuildingId("forge").map { it.toBuildingSlot() }
    }

    fun getAlchemySlots(): List<AlchemySlot> {
        return productionSlotRepository.getSlotsByType(BuildingType.ALCHEMY).map { it.toAlchemySlot() }
    }

    fun getPlantSlots(): List<PlantSlotData> {
        return productionSlotRepository.getSlotsByType(BuildingType.HERB_GARDEN).map { it.toPlantSlotData() }
    }

    fun assignDiscipleToBuilding(buildingId: String, slotIndex: Int, discipleId: String) {
        synchronized(transactionMutex) {
            if (discipleId.isEmpty()) {
                removeDiscipleFromBuildingInternal(buildingId, slotIndex)
                return
            }

            val disciple = _disciples.value.find { it.id == discipleId } ?: return
            if (!disciple.isAlive || disciple.status != DiscipleStatus.IDLE) {
                addEvent("${disciple.name}无法被安排工作", EventType.WARNING)
                return
            }

            if (disciple.age < 5) {
                addEvent("${disciple.name}年龄太小，无法工作", EventType.WARNING)
                return
            }

            val existingSlot = productionSlotRepository.getSlotByBuildingId(buildingId, slotIndex)

            if (existingSlot != null && existingSlot.isWorking) {
                addEvent("该槽位正在工作中，无法更换弟子", EventType.WARNING)
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
    }

    fun removeDiscipleFromBuilding(buildingId: String, slotIndex: Int) {
        synchronized(transactionMutex) {
            removeDiscipleFromBuildingInternal(buildingId, slotIndex)
        }
    }

    private fun removeDiscipleFromBuildingInternal(buildingId: String, slotIndex: Int) {
        val existingSlot = productionSlotRepository.getSlotByBuildingId(buildingId, slotIndex) ?: return

        if (existingSlot.isWorking) {
            addEvent("该槽位正在工作中，无法移除弟子", EventType.WARNING)
            return
        }

        existingSlot.assignedDiscipleId?.let { oldDiscipleId ->
            val disciple = _disciples.value.find { it.id == oldDiscipleId }
            disciple?.let {
                addEvent("${it.name}从${getBuildingName(buildingId)}移除", EventType.INFO)
            }
        }

        scope.launch {
            productionSlotRepository.updateSlotByBuildingId(buildingId, slotIndex) { slot ->
                slot.copy(assignedDiscipleId = null, assignedDiscipleName = "")
            }
        }
    }

    fun getBuildingSlotsForBuilding(buildingId: String): List<BuildingSlot> {
        return productionSlotRepository.getSlotsByBuildingId(buildingId).map { it.toBuildingSlot() }
    }

    suspend fun startAlchemy(slotIndex: Int, recipeId: String): Boolean {
        val maxSlotCount = 3
        if (slotIndex < 0 || slotIndex >= maxSlotCount) {
            addEvent("无效的炼丹槽位索引: $slotIndex (有效范围: 0-$maxSlotCount)", EventType.WARNING)
            return false
        }

        val data = _gameData.value

        val alchemySlot = productionSlotRepository.getSlotByBuildingId("alchemy", slotIndex)
        if (alchemySlot != null && alchemySlot.isWorking) {
            addEvent("该炼丹槽位正在工作中", EventType.WARNING)
            return false
        }

        val result = productionCoordinator.startAlchemyAtomic(
            slotIndex = slotIndex,
            recipeId = recipeId,
            currentYear = data.gameYear,
            currentMonth = data.gameMonth,
            herbs = _herbs.value,
            buildingId = "alchemy",
            alchemyPolicyBonus = if (data.sectPolicies.alchemyIncentive) com.xianxia.sect.core.GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_BASE_EFFECT else 0.0
        )

        when {
            !result.success -> {
                val error = result.error
                when (error) {
                    is ProductionError.InsufficientMaterials -> addEvent("草药材料不足，无法开始炼丹", EventType.WARNING)
                    is ProductionError.RecipeNotFound -> addEvent("配方不存在", EventType.ERROR)
                    else -> addEvent("无法开始炼丹", EventType.WARNING)
                }
                return false
            }
            result.materialUpdate != null -> {
                _herbs.value = result.materialUpdate.herbs

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

                addEvent("开始在炼丹炉工作", EventType.INFO)
                return true
            }
        }

        return false
    }

    suspend fun startForging(slotIndex: Int, recipeId: String): Boolean {
        val data = _gameData.value

        val forgeSlot = productionSlotRepository.getSlotByBuildingId("forge", slotIndex)
        if (forgeSlot != null && forgeSlot.isWorking) {
            addEvent("该锻造槽位正在工作中", EventType.WARNING)
            return false
        }

        val result = productionCoordinator.startForgingAtomic(
            slotIndex = slotIndex,
            recipeId = recipeId,
            currentYear = data.gameYear,
            currentMonth = data.gameMonth,
            materials = _materials.value,
            buildingId = "forge",
            forgePolicyBonus = if (data.sectPolicies.forgeIncentive) com.xianxia.sect.core.GameConfig.PolicyConfig.FORGE_INCENTIVE_BASE_EFFECT else 0.0
        )

        when {
            !result.success -> {
                val error = result.error
                when (error) {
                    is ProductionError.InsufficientMaterials -> addEvent("材料不足，无法开始锻造", EventType.WARNING)
                    is ProductionError.RecipeNotFound -> addEvent("配方不存在", EventType.ERROR)
                    else -> addEvent("无法开始锻造", EventType.WARNING)
                }
                return false
            }
            result.materialUpdate != null -> {
                _materials.value = result.materialUpdate.materials

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

                addEvent("开始在锻造坊工作", EventType.INFO)
                return true
            }
        }

        return false
    }

    fun collectBuildingResult(slotId: String) {
        val slot = productionSlotRepository.getSlotById(slotId) ?: return

        if (slot.status != ProductionSlotStatus.COMPLETED) {
            addEvent("工作尚未完成", EventType.WARNING)
            return
        }

        completeBuildingTaskFromProductionSlot(slot)

        slot.assignedDiscipleId?.let { discipleId ->
            updateDiscipleStatus(discipleId, DiscipleStatus.IDLE)
        }

        scope.launch {
            productionCoordinator.resetSlotByBuildingIdAtomic(slot.buildingId, slot.slotIndex)
        }
    }

    fun collectForgeResult(slotIndex: Int) {
        val slot = productionSlotRepository.getSlotByBuildingId("forge", slotIndex) ?: return

        if (slot.status != ProductionSlotStatus.COMPLETED) {
            addEvent("工作尚未完成", EventType.WARNING)
            return
        }

        completeBuildingTaskFromProductionSlot(slot)

        slot.assignedDiscipleId?.let { discipleId ->
            updateDiscipleStatus(discipleId, DiscipleStatus.IDLE)
        }

        scope.launch {
            productionCoordinator.resetSlotByBuildingIdAtomic("forge", slotIndex)
        }
    }

    fun collectAlchemyResult(slotIndex: Int): AlchemyResult? {
        synchronized(transactionMutex) {
            val data = _gameData.value
            val slot = productionSlotRepository.getSlotByBuildingId("alchemy", slotIndex) ?: run {
                addEvent("无效的炼丹槽位索引: $slotIndex", EventType.WARNING)
                return null
            }

            if (!slot.isWorking) {
                addEvent("该槽位没有正在炼制的丹药", EventType.WARNING)
                return null
            }

            if (!slot.isFinished(data.gameYear, data.gameMonth)) {
                addEvent("炼制尚未完成", EventType.WARNING)
                return null
            }

            val success = Random.nextDouble() <= slot.successRate

            var pill: Pill? = null
            if (success) {
                pill = Pill(
                    name = slot.outputItemName,
                    rarity = slot.outputItemRarity,
                    description = "通过炼丹炉炼制而成",
                    minRealm = GameConfig.Realm.getMinRealmForRarity(slot.outputItemRarity),
                    quantity = 1
                )
                val currentPills = _pills.value.toMutableList()
                currentPills.add(pill)
                _pills.value = currentPills
                addEvent("炼制成功！获得${slot.outputItemName}，已放入宗门仓库", EventType.INFO)
            } else {
                addEvent("炼制失败，材料损毁", EventType.ERROR)
            }

            scope.launch {
                productionCoordinator.resetSlotByBuildingIdAtomic("alchemy", slotIndex)
            }

            return AlchemyResult(
                success = success,
                pill = pill,
                message = if (success) "成功" else "失败"
            )
        }
    }

    fun checkAndCollectCompletedAlchemySlots(): List<AlchemyResult> {
        synchronized(transactionMutex) {
            val data = _gameData.value
            val results = mutableListOf<AlchemyResult>()
            val alchemySlots = productionSlotRepository.getSlotsByType(BuildingType.ALCHEMY)
            alchemySlots.forEach { slot ->
                if (slot.isWorking && slot.isFinished(data.gameYear, data.gameMonth)) {
                    collectAlchemyResult(slot.slotIndex)?.let { results.add(it) }
                }
            }
            return results
        }
    }

    fun clearPlantSlot(slotIndex: Int) {
        scope.launch {
            val slot = productionSlotRepository.getSlotByBuildingId("herbGarden", slotIndex)
            if (slot != null) {
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
        addEvent("已移除种植任务", EventType.INFO)
    }

    private fun updateDiscipleStatus(discipleId: String, status: DiscipleStatus) {
        _disciples.value = _disciples.value.map {
            if (it.id == discipleId) it.copy(status = status) else it
        }
    }

    private fun getBuildingName(buildingId: String): String = BuildingNames.getDisplayName(buildingId)

    private fun calculateWorkDurationWithAllDisciples(baseDuration: Int, buildingId: String): Int {
        var totalSpeedBonus = 0.0
        val data = _gameData.value

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
        val data = _gameData.value
        val elderSlots = data.elderSlots

        val elderDiscipleId = when (buildingId) {
            "forge" -> elderSlots.forgeElder
            "alchemy" -> elderSlots.alchemyElder
            "herbGarden" -> elderSlots.herbGardenElder
            else -> null
        } ?: return 0.0

        val elderDisciple = _disciples.value.find { it.id == elderDiscipleId } ?: return 0.0

        return when (buildingId) {
            "forge" -> {
                val baseline = 80
                val diff = (elderDisciple.artifactRefining - baseline).coerceAtLeast(0)
                diff * 0.01
            }
            "alchemy" -> {
                val baseline = 80
                val diff = (elderDisciple.pillRefining - baseline).coerceAtLeast(0)
                diff * 0.01
            }
            "herbGarden" -> {
                val baseline = 80
                val diff = (elderDisciple.spiritPlanting - baseline).coerceAtLeast(0)
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
            addEvent("${BuildingNames.getDisplayName(slot.buildingId)}工作已完成，但无配方信息", EventType.WARNING)
            return
        }

        when (slot.buildingId) {
            "forge" -> {
                val recipe = ForgeRecipeDatabase.getRecipeById(recipeId)
                if (recipe != null) {
                    val equipment = Equipment(
                        name = recipe.name,
                        rarity = recipe.rarity,
                        description = recipe.description,
                        slot = recipe.type,
                        minRealm = recipe.tier
                    )
                    val currentEquipment = _equipment.value.toMutableList()
                    currentEquipment.add(equipment)
                    _equipment.value = currentEquipment
                    addEvent("锻造完成！获得${recipe.name}，已放入宗门仓库", EventType.INFO)
                } else {
                    addEvent("锻造完成，但配方[$recipeId]不存在", EventType.ERROR)
                }
            }
            "alchemy" -> {
                val recipe = PillRecipeDatabase.getRecipeById(recipeId)
                if (recipe != null) {
                    val pill = Pill(
                        name = recipe.name,
                        rarity = recipe.rarity,
                        description = "通过炼丹炉炼制而成",
                        minRealm = GameConfig.Realm.getMinRealmForRarity(recipe.rarity),
                        quantity = 1
                    )
                    val currentPills = _pills.value.toMutableList()
                    currentPills.add(pill)
                    _pills.value = currentPills
                    addEvent("炼制完成！获得${recipe.name}，已放入宗门仓库", EventType.INFO)
                } else {
                    addEvent("炼制完成，但配方[$recipeId]不存在", EventType.ERROR)
                }
            }
            else -> {
                addEvent("${BuildingNames.getDisplayName(slot.buildingId)}工作已完成，产出类型暂不支持", EventType.INFO)
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
        expectedYield = expectedYield,
        harvestAmount = harvestAmount,
        harvestHerbId = outputItemId ?: ""
    )
}
