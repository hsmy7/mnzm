@file:Suppress("DEPRECATION")

package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.data.*
import com.xianxia.sect.core.engine.production.ProductionCoordinator
import com.xianxia.sect.core.engine.HerbGardenSystem
import com.xianxia.sect.core.model.production.ProductionError
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.util.BuildingNames
import kotlin.random.Random

/**
 * 建筑槽位服务 - 主要服务于锻造(forge)建筑槽位的弟子分配与生产管理
 *
 * ## 类名说明
 * 类名为 `BuildingService`（保留原名以兼容构造函数调用方），但当前实际职责范围
 * 集中于 **锻造(forge)建筑槽位** 的管理。炼丹(alchemy)和灵药园(herbGarden)的
 * 生产逻辑分别由独立的 `startAlchemy`/`collectAlchemyResult` 和
 * `harvestHerb`/`clearPlantSlot` 方法处理。
 *
 * ## 核心职责域（forge 槽位）
 * - [assignDiscipleToBuilding] - 分配/移除弟子到锻造槽位
 * - [removeDiscipleFromBuilding] - 从锻造槽位移除弟子
 * - [getBuildingSlots] / [getBuildingSlotsForBuilding] - 查询 forgeSlots
 * - [startBuildingWork] - 启动锻造槽位的生产工作
 * - [collectBuildingResult] - 收集锻造槽位的生产结果
 *
 * ## 附加职责（非 forge 槽位，独立方法组）
 * - 炼丹生产: [startAlchemy], [collectAlchemyResult], [checkAndCollectCompletedAlchemySlots]
 * - 灵药园: [harvestHerb], [clearPlantSlot]
 */
class BuildingService constructor(
    private val _gameData: MutableStateFlow<GameData>,
    private val _disciples: MutableStateFlow<List<Disciple>>,
    private val _herbs: MutableStateFlow<List<Herb>>,
    private val _materials: MutableStateFlow<List<Material>>,
    private val _equipment: MutableStateFlow<List<Equipment>>,
    private val _pills: MutableStateFlow<List<Pill>>,
    private val productionCoordinator: ProductionCoordinator,
    private val addEvent: (String, EventType) -> Unit,
    private val transactionMutex: Any
) {
    companion object {
        private const val TAG = "BuildingService"
    }

    // ==================== StateFlow 暴露 ====================

    /**
     * Get building slots (forge) StateFlow via GameData
     */
    fun getBuildingSlots(): List<BuildingSlot> {
        return _gameData.value.forgeSlots
    }

    /**
     * Get alchemy slots StateFlow via GameData
     */
    fun getAlchemySlots(): List<AlchemySlot> {
        return _gameData.value.alchemySlots
    }

    // ==================== 建筑槽位管理 ====================

    /**
     * Assign disciple to building slot
     */
    fun assignDiscipleToBuilding(buildingId: String, slotIndex: Int, discipleId: String) {
        synchronized(transactionMutex) {
            val data = _gameData.value

            // Handle removal case (empty string means remove)
            if (discipleId.isEmpty()) {
                removeDiscipleFromBuildingInternal(buildingId, slotIndex)
                return
            }

            val disciple = _disciples.value.find { it.id == discipleId } ?: return
            if (!disciple.isAlive || disciple.status != DiscipleStatus.IDLE) {
                addEvent("${disciple.name}无法被安排工作", EventType.WARNING)
                return
            }

            // Age check
            if (disciple.age < 5) {
                addEvent("${disciple.name}年龄太小，无法工作", EventType.WARNING)
                return
            }

            val existingSlot = data.forgeSlots.find {
                it.buildingId == buildingId && it.slotIndex == slotIndex
            }

            // Check if slot is working
            if (existingSlot != null && existingSlot.status == SlotStatus.WORKING) {
                addEvent("该槽位正在工作中，无法更换弟子", EventType.WARNING)
                return
            }

            // Clear old disciple from slot
            existingSlot?.discipleId?.let { oldDiscipleId ->
                updateDiscipleStatus(oldDiscipleId, DiscipleStatus.IDLE)
            }

            // Create or update slot
            val newSlot = existingSlot?.copy(discipleId = discipleId) ?: BuildingSlot(
                buildingId = buildingId,
                slotIndex = slotIndex,
                discipleId = discipleId,
                status = SlotStatus.IDLE
            )

            _gameData.value = data.copy(forgeSlots = data.forgeSlots
                .filter { !(it.buildingId == buildingId && it.slotIndex == slotIndex) } + newSlot)
        }
    }

    /**
     * Remove disciple from building slot
     */
    fun removeDiscipleFromBuilding(buildingId: String, slotIndex: Int) {
        synchronized(transactionMutex) {
            removeDiscipleFromBuildingInternal(buildingId, slotIndex)
        }
    }

    /**
     * Internal implementation for removing disciple from building
     */
    private fun removeDiscipleFromBuildingInternal(buildingId: String, slotIndex: Int) {
        val data = _gameData.value
        val existingSlot = data.forgeSlots.find {
            it.buildingId == buildingId && it.slotIndex == slotIndex
        } ?: return

        // Check if working
        if (existingSlot.status == SlotStatus.WORKING) {
            addEvent("该槽位正在工作中，无法移除弟子", EventType.WARNING)
            return
        }

        existingSlot.discipleId?.let { oldDiscipleId ->
            val disciple = _disciples.value.find { it.id == oldDiscipleId }
            disciple?.let {
                addEvent("${it.name}从${getBuildingName(buildingId)}移除", EventType.INFO)
            }
        }

        // Remove slot
        _gameData.value = data.copy(forgeSlots = data.forgeSlots
            .filter { !(it.buildingId == buildingId && it.slotIndex == slotIndex) })
    }

    /**
     * Get all slots for a specific building
     */
    fun getBuildingSlotsForBuilding(buildingId: String): List<BuildingSlot> {
        return _gameData.value.forgeSlots.filter { it.buildingId == buildingId }
    }

    // ==================== 生产操作 ====================

    /**
     * Start alchemy work in a slot
     */
    suspend fun startAlchemy(slotIndex: Int, recipeId: String): Boolean {
        val maxSlotCount = 3
        if (slotIndex < 0 || slotIndex >= maxSlotCount) {
            addEvent("无效的炼丹槽位索引: $slotIndex (有效范围: 0-$maxSlotCount)", EventType.WARNING)
            return false
        }

        val data = _gameData.value

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
                    is ProductionError.SlotBusy -> addEvent("该炼丹槽位正在工作中", EventType.WARNING)
                    is ProductionError.InsufficientMaterials -> addEvent("草药材料不足，无法开始炼丹", EventType.WARNING)
                    is ProductionError.RecipeNotFound -> addEvent("配方不存在", EventType.ERROR)
                    else -> addEvent("无法开始炼丹", EventType.WARNING)
                }
                return false
            }
            result.materialUpdate != null -> {
                _herbs.value = result.materialUpdate.herbs

                val recipe = PillRecipeDatabase.getRecipeById(recipeId) ?: return false

                val newAlchemySlot = AlchemySlot(
                    slotIndex = slotIndex,
                    recipeId = recipeId,
                    recipeName = recipe.name,
                    pillName = recipe.name,
                    pillRarity = recipe.rarity,
                    startYear = data.gameYear,
                    startMonth = data.gameMonth,
                    duration = recipe.duration,
                    status = AlchemySlotStatus.WORKING,
                    successRate = recipe.successRate,
                    requiredMaterials = recipe.materials
                )

                val currentAlchemySlots = data.alchemySlots
                _gameData.value = if (slotIndex < currentAlchemySlots.size) {
                    data.copy(alchemySlots = currentAlchemySlots.mapIndexed { index, s ->
                        if (index == slotIndex) newAlchemySlot else s
                    })
                } else {
                    val filledSlots = currentAlchemySlots.toMutableList()
                    while (filledSlots.size < slotIndex) {
                        filledSlots.add(AlchemySlot(slotIndex = filledSlots.size))
                    }
                    filledSlots.add(newAlchemySlot)
                    data.copy(alchemySlots = filledSlots)
                }

                return true
            }
        }

        return false
    }

    /**
     * Start forging work in a slot
     */
    suspend fun startForging(slotIndex: Int, recipeId: String): Boolean {
        val data = _gameData.value

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
                    is ProductionError.SlotBusy -> addEvent("该锻造槽位正在工作中", EventType.WARNING)
                    is ProductionError.InsufficientMaterials -> addEvent("材料不足，无法开始锻造", EventType.WARNING)
                    is ProductionError.RecipeNotFound -> addEvent("配方不存在", EventType.ERROR)
                    else -> addEvent("无法开始锻造", EventType.WARNING)
                }
                return false
            }
            result.materialUpdate != null -> {
                _materials.value = result.materialUpdate.materials

                val recipe = ForgeRecipeDatabase.getRecipeById(recipeId) ?: return false
                val duration = ForgeRecipeDatabase.getDurationByTier(recipe.tier)
                startBuildingWork("forge", slotIndex, recipeId, duration)
                return true
            }
        }

        return false
    }

    /**
     * Start work on a building slot
     */
    fun startBuildingWork(buildingId: String, slotIndex: Int, recipeId: String, baseDuration: Int) {
        val data = _gameData.value
        val existingSlot = data.forgeSlots.find {
            it.buildingId == buildingId && it.slotIndex == slotIndex
        }

        // Check if already working
        if (existingSlot?.status == SlotStatus.WORKING) {
            addEvent("该槽位已经在工作中", EventType.WARNING)
            return
        }

        // Calculate actual duration with bonuses
        val actualDuration = calculateWorkDurationWithAllDisciples(baseDuration, buildingId)

        // Create or update slot
        val updatedSlot = existingSlot?.copy(
            recipeId = recipeId,
            startYear = data.gameYear,
            startMonth = data.gameMonth,
            duration = actualDuration,
            status = SlotStatus.WORKING
        ) ?: BuildingSlot(
            buildingId = buildingId,
            slotIndex = slotIndex,
            recipeId = recipeId,
            startYear = data.gameYear,
            startMonth = data.gameMonth,
            duration = actualDuration,
            status = SlotStatus.WORKING
        )

        // Update game data
        _gameData.value = if (existingSlot != null) {
            data.copy(forgeSlots = data.forgeSlots.map {
                if (it.id == existingSlot.id) updatedSlot else it
            })
        } else {
            data.copy(forgeSlots = data.forgeSlots + updatedSlot)
        }

        val workName = getBuildingName(buildingId)
        addEvent("开始在${workName}工作", EventType.INFO)
    }

    /**
     * Collect completed building result
     */
    fun collectBuildingResult(slotId: String) {
        val data = _gameData.value
        val slot = data.forgeSlots.find { it.id == slotId } ?: return

        if (slot.status != SlotStatus.COMPLETED) {
            addEvent("工作尚未完成", EventType.WARNING)
            return
        }

        // Process completion (would delegate to appropriate service based on building type)
        completeBuildingTask(slot)

        // Reset disciple status
        slot.discipleId?.let { discipleId ->
            updateDiscipleStatus(discipleId, DiscipleStatus.IDLE)
        }

        // Remove completed slot
        _gameData.value = data.copy(forgeSlots = data.forgeSlots.filter { it.id != slotId })
    }

    /**
     * Collect alchemy result from slot
     */
    fun collectAlchemyResult(slotIndex: Int): AlchemyResult? {
        synchronized(transactionMutex) {
            val data = _gameData.value
            val currentSlots = data.alchemySlots
            val slot = currentSlots.getOrNull(slotIndex) ?: run {
                addEvent("无效的炼丹槽位索引: $slotIndex", EventType.WARNING)
                return null
            }

            if (slot.status != AlchemySlotStatus.WORKING) {
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
                    name = slot.pillName,
                    rarity = slot.pillRarity,
                    description = "通过炼丹炉炼制而成",
                    quantity = 1
                )
                val currentPills = _pills.value.toMutableList()
                currentPills.add(pill)
                _pills.value = currentPills
                addEvent("炼制成功！获得${slot.pillName}，已放入宗门仓库", EventType.INFO)
            } else {
                addEvent("炼制失败，材料损毁", EventType.ERROR)
            }

            _gameData.value = data.copy(alchemySlots = currentSlots.mapIndexed { index, s ->
                if (index == slotIndex) {
                    s.copy(
                        status = AlchemySlotStatus.IDLE,
                        recipeId = null,
                        recipeName = "",
                        pillName = "",
                        startYear = 0,
                        startMonth = 0,
                        duration = 0
                    )
                } else {
                    s
                }
            })

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
            data.alchemySlots.forEachIndexed { index, slot ->
                if (slot.status == AlchemySlotStatus.WORKING && slot.isFinished(data.gameYear, data.gameMonth)) {
                    collectAlchemyResult(index)?.let { results.add(it) }
                }
            }
            return results
        }
    }

    // ==================== 灵药园操作 ====================

    /**
     * Harvest herb from garden slot
     */
    fun harvestHerb(slotIndex: Int) {
        val data = _gameData.value
        val currentSlots = data.herbGardenPlantSlots

        if (slotIndex < 0 || slotIndex >= currentSlots.size) {
            addEvent("无效的种植槽位", EventType.WARNING)
            return
        }

        val slot = currentSlots[slotIndex]

        if (slot.status != "mature") {
            addEvent("该槽位没有成熟的草药", EventType.WARNING)
            return
        }

        // Get herb info and calculate yield
        val herbId = slot.harvestHerbId ?: slot.seedId?.let { HerbDatabase.getHerbIdFromSeedId(it) }
        if (herbId == null) {
            addEvent("草药数据错误", EventType.ERROR)
            return
        }

        val herb = HerbDatabase.getHerbById(herbId)
        if (herb == null) {
            addEvent("草药数据错误", EventType.ERROR)
            return
        }

        // Calculate yield with bonuses
        val actualYield = HerbGardenSystem.calculateIncreasedYield(slot.harvestAmount, 0.0)

        // Add to warehouse (would delegate to InventoryService)
        val currentHerbs = _herbs.value.toMutableList()
        val existingHerbIndex = currentHerbs.indexOfFirst {
            it.name == herb.name && it.rarity == herb.rarity
        }

        if (existingHerbIndex >= 0) {
            val existingHerb = currentHerbs[existingHerbIndex]
            currentHerbs[existingHerbIndex] = existingHerb.copy(
                quantity = existingHerb.quantity + actualYield
            )
        } else {
            currentHerbs.add(Herb(
                id = java.util.UUID.randomUUID().toString(),
                name = herb.name,
                rarity = herb.rarity,
                description = herb.description,
                category = herb.category,
                quantity = actualYield
            ))
        }

        _herbs.value = currentHerbs.toList()

        // Reset slot
        val updatedSlots = currentSlots.toMutableList()
        updatedSlots[slotIndex] = PlantSlotData(index = slotIndex)
        _gameData.value = data.copy(herbGardenPlantSlots = updatedSlots)

        addEvent("收获${herb.name} x$actualYield", EventType.SUCCESS)
    }

    /**
     * Clear plant slot
     */
    fun clearPlantSlot(slotIndex: Int) {
        val data = _gameData.value
        val currentSlots = data.herbGardenPlantSlots.toMutableList()

        if (slotIndex < 0 || slotIndex >= currentSlots.size) {
            return
        }

        currentSlots[slotIndex] = PlantSlotData(index = slotIndex)
        _gameData.value = data.copy(herbGardenPlantSlots = currentSlots)
        addEvent("已移除种植任务", EventType.INFO)
    }

    // ==================== 辅助方法 ====================

    /**
     * Update disciple status
     */
    private fun updateDiscipleStatus(discipleId: String, status: DiscipleStatus) {
        _disciples.value = _disciples.value.map {
            if (it.id == discipleId) it.copy(status = status) else it
        }
    }

    /**
     * Get building display name
     */
    private fun getBuildingName(buildingId: String): String = BuildingNames.getDisplayName(buildingId)

    /**
     * Calculate work duration with all disciple bonuses
     * Delegates bonus calculation logic (elder position + disciple speed bonuses)
     */
    private fun calculateWorkDurationWithAllDisciples(baseDuration: Int, buildingId: String): Int {
        var totalSpeedBonus = 0.0
        val data = _gameData.value

        // Elder position bonus
        totalSpeedBonus += getElderPositionBonusLocal(buildingId)

        // Disciple speed bonus from assigned disciples
        when (buildingId) {
            "forge", "alchemy", "herbGarden" -> {
                val assignedDiscipleIds = when (buildingId) {
                    "forge" -> data.forgeSlots.mapNotNull { it.discipleId }
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

    /**
     * Get elder position bonus for a specific building (local implementation)
     */
    private fun getElderPositionBonusLocal(buildingId: String): Double {
        val data = _gameData.value
        val elderSlots = data.elderSlots

        // Find the elder disciple ID for this building type
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
                val maxBonus = 0.20
                val diff = (elderDisciple.artifactRefining - baseline).coerceAtLeast(0)
                (diff * 0.01).coerceAtMost(maxBonus)
            }
            "alchemy" -> {
                val diff = elderDisciple.pillRefining - 50
                (diff * 0.02).coerceIn(-0.10, 0.30)
            }
            "herbGarden" -> {
                val diff = elderDisciple.spiritPlanting - 50
                (diff * 0.02).coerceIn(-0.10, 0.30)
            }
            else -> 0.0
        }
    }

    /**
     * Calculate reduced duration based on speed bonus (local implementation)
     */
    private fun calculateReducedDurationLocal(baseDuration: Int, speedBonus: Double): Int {
        if (speedBonus <= 0) return baseDuration
        val reductionPercent = speedBonus / 4.0
        val reducedMonths = (baseDuration * reductionPercent).toInt()
        return (baseDuration - reducedMonths).coerceAtLeast(1)
    }

    /**
     * Complete building task - produce items based on slot recipe and add to inventory
     */
    private fun completeBuildingTask(slot: BuildingSlot) {
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
}
