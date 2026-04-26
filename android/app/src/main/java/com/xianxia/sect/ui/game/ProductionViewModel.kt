package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.data.BeastMaterialDatabase
import com.xianxia.sect.core.data.ForgeRecipeDatabase
import com.xianxia.sect.core.data.HerbDatabase
import com.xianxia.sect.core.data.PillRecipeDatabase
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.usecase.DisciplePositionQueryUseCase
import com.xianxia.sect.core.util.sortedByFollowAndRealm
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProductionViewModel @Inject constructor(
    private val gameEngine: GameEngine,
    private val disciplePositionQuery: DisciplePositionQueryUseCase
) : BaseViewModel() {

    companion object {
        private const val TAG = "ProductionViewModel"
        private const val REALM_VICE_SECT_MASTER = 4
    }

    val productionSlots: StateFlow<List<ProductionSlot>> = gameEngine.productionSlots
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val alchemySlots: StateFlow<List<AlchemySlot>> = productionSlots
        .map { slots ->
            slots.filter { it.buildingType == BuildingType.ALCHEMY }.map { slot ->
                AlchemySlot(
                    id = slot.id,
                    slotIndex = slot.slotIndex,
                    recipeId = slot.recipeId,
                    recipeName = slot.recipeName,
                    pillName = slot.outputItemName,
                    pillRarity = slot.outputItemRarity,
                    startYear = slot.startYear,
                    startMonth = slot.startMonth,
                    duration = slot.duration,
                    status = when (slot.status) {
                        ProductionSlotStatus.IDLE -> AlchemySlotStatus.IDLE
                        ProductionSlotStatus.WORKING -> AlchemySlotStatus.WORKING
                        ProductionSlotStatus.COMPLETED -> AlchemySlotStatus.FINISHED
                    },
                    successRate = slot.successRate,
                    requiredMaterials = slot.requiredMaterials
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val forgeSlots: StateFlow<List<ForgeSlot>> = productionSlots
        .map { slots ->
            slots.filter { it.buildingType == BuildingType.FORGE }.map { slot ->
                val recipe = slot.recipeId?.let {
                    ForgeRecipeDatabase.getRecipeById(it)
                }
                ForgeSlot(
                    id = slot.id,
                    slotIndex = slot.slotIndex,
                    recipeId = slot.recipeId,
                    recipeName = slot.recipeName,
                    equipmentName = recipe?.name ?: "",
                    equipmentRarity = recipe?.rarity ?: 1,
                    startYear = slot.startYear,
                    startMonth = slot.startMonth,
                    duration = slot.duration,
                    status = when (slot.status) {
                        ProductionSlotStatus.WORKING -> ForgeSlotStatus.WORKING
                        ProductionSlotStatus.COMPLETED -> ForgeSlotStatus.FINISHED
                        else -> ForgeSlotStatus.IDLE
                    }
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allForgeRecipes: StateFlow<List<ForgeRecipeDatabase.ForgeRecipe>> = flow {
        emit(ForgeRecipeDatabase.getAllRecipes())
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val herbs: StateFlow<List<Herb>> = gameEngine.herbs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val seeds: StateFlow<List<Seed>> = gameEngine.seeds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val materials: StateFlow<List<Material>> = gameEngine.materials
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isStartingAlchemy = MutableStateFlow(false)
    val isStartingAlchemy: StateFlow<Boolean> = _isStartingAlchemy.asStateFlow()

    private val _isStartingForge = MutableStateFlow(false)
    val isStartingForge: StateFlow<Boolean> = _isStartingForge.asStateFlow()

    private val _selectedPlantSlotIndex = MutableStateFlow<Int?>(null)
    val selectedPlantSlotIndex: StateFlow<Int?> = _selectedPlantSlotIndex.asStateFlow()

    fun startAlchemy(slotIndex: Int, recipe: PillRecipeDatabase.PillRecipe) {
        if (_isStartingAlchemy.value) return
        _isStartingAlchemy.value = true

        viewModelScope.launch {
            try {
                gameEngine.startAlchemy(slotIndex, recipe.id)
            } catch (e: Exception) {
                showError(e.message ?: "开始炼制失败")
            } finally {
                _isStartingAlchemy.value = false
            }
        }
    }

    fun startForge(slotIndex: Int, recipe: ForgeRecipeDatabase.ForgeRecipe) {
        if (_isStartingForge.value) return
        _isStartingForge.value = true

        viewModelScope.launch {
            try {
                gameEngine.startForging(slotIndex, recipe.id)
            } catch (e: Exception) {
                showError(e.message ?: "开始锻造失败")
            } finally {
                _isStartingForge.value = false
            }
        }
    }

    fun startHerbGardenPlanting(slotIndex: Int, seedId: String) {
        viewModelScope.launch {
            try {
                gameEngine.startManualPlanting(slotIndex, seedId)
            } catch (e: Exception) {
                showError(e.message ?: "种植失败")
            }
        }
    }

    fun selectPlantSlot(slotIndex: Int) {
        _selectedPlantSlotIndex.value = slotIndex
    }

    fun plantSeed(slotIndex: Int, seed: Seed) {
        viewModelScope.launch {
            try {
                gameEngine.startManualPlanting(slotIndex, seed.id)
            } catch (e: Exception) {
                showError(e.message ?: "种植失败")
            }
        }
    }

    fun autoPlantAllSlots() {
        viewModelScope.launch {
            try {
                val slots = gameEngine.productionSlots.value
                val herbGardenSlots = slots.filter {
                    it.buildingType == BuildingType.HERB_GARDEN
                }
                val idleSlots = herbGardenSlots.filter { it.status == ProductionSlotStatus.IDLE }
                if (idleSlots.isEmpty()) {
                    showError("没有空闲的种植槽位")
                    return@launch
                }

                var plantedCount = 0
                for (slot in idleSlots) {
                    val currentSeeds = gameEngine.getCurrentSeeds()
                        .filter { it.quantity > 0 }
                        .sortedByDescending { it.rarity }

                    val seedToPlant = currentSeeds.firstOrNull() ?: break

                    gameEngine.startManualPlanting(slot.slotIndex, seedToPlant.id)
                    plantedCount++
                }

                if (plantedCount > 0) {
                    showSuccess("自动种植完成，已种植${plantedCount}个槽位")
                } else {
                    showError("仓库中没有可用的种子")
                }
            } catch (e: Exception) {
                showError(e.message ?: "自动种植失败")
            }
        }
    }

    fun autoAlchemyAllSlots() {
        viewModelScope.launch {
            try {
                val slots = gameEngine.productionSlots.value
                val alchemySlots = slots.filter {
                    it.buildingType == BuildingType.ALCHEMY
                }
                val idleSlotIndices = alchemySlots
                    .filter { it.status == ProductionSlotStatus.IDLE }
                    .map { it.slotIndex }

                if (idleSlotIndices.isEmpty()) {
                    showError("没有空闲的炼丹槽位")
                    return@launch
                }

                val allRecipes = PillRecipeDatabase.getAllRecipes().sortedByDescending { it.rarity }
                var startedCount = 0

                for (slotIndex in idleSlotIndices) {
                    val currentHerbs = gameEngine.getCurrentHerbs()
                    val recipeToStart = allRecipes.firstOrNull { recipe ->
                        recipe.materials.all { (materialId, requiredQuantity) ->
                            val herbData = HerbDatabase.getHerbById(materialId)
                            val herbName = herbData?.name
                            val herbRarity = herbData?.rarity ?: 1
                            val herb = currentHerbs.find { it.name == herbName && it.rarity == herbRarity }
                            herb != null && herb.quantity >= requiredQuantity
                        }
                    }

                    if (recipeToStart == null) break

                    val success = gameEngine.startAlchemy(slotIndex, recipeToStart.id)
                    if (success) startedCount++
                }

                if (startedCount > 0) {
                    showSuccess("自动炼丹完成，已启动${startedCount}个槽位")
                } else {
                    showError("没有足够的草药进行炼丹")
                }
            } catch (e: Exception) {
                showError(e.message ?: "自动炼丹失败")
            }
        }
    }

    fun autoForgeAllSlots() {
        viewModelScope.launch {
            try {
                val slots = gameEngine.productionSlots.value
                val forgeSlots = slots.filter {
                    it.buildingType == BuildingType.FORGE
                }
                val maxSlotCount = 3

                val idleSlotIndices = (0 until maxSlotCount).filter { idx ->
                    val slot = forgeSlots.find { it.slotIndex == idx }
                    slot == null || slot.status == ProductionSlotStatus.IDLE
                }

                if (idleSlotIndices.isEmpty()) {
                    showError("没有空闲的锻造槽位")
                    return@launch
                }

                val allRecipes = ForgeRecipeDatabase.getAllRecipes().sortedByDescending { it.rarity }
                var startedCount = 0

                for (slotIndex in idleSlotIndices) {
                    val currentMaterials = gameEngine.getCurrentMaterials()
                    val materialIndex = currentMaterials.groupBy { it.name to it.rarity }
                        .mapValues { (_, list) -> list.sumOf { it.quantity } }

                    val recipeToStart = allRecipes.firstOrNull { recipe ->
                        recipe.materials.all { (materialId, requiredQuantity) ->
                            val materialData = BeastMaterialDatabase.getMaterialById(materialId)
                            materialData != null && run {
                                val available = materialIndex[materialData.name to materialData.rarity] ?: 0
                                available >= requiredQuantity
                            }
                        }
                    }

                    if (recipeToStart == null) break

                    val success = gameEngine.startForging(slotIndex, recipeToStart.id)
                    if (success) startedCount++
                }

                if (startedCount > 0) {
                    showSuccess("自动炼器完成，已启动${startedCount}个槽位")
                } else {
                    showError("没有足够的材料进行锻造")
                }
            } catch (e: Exception) {
                showError(e.message ?: "自动炼器失败")
            }
        }
    }

    val discipleAggregates: StateFlow<List<DiscipleAggregate>> = gameEngine.discipleAggregates
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val disciples = gameEngine.disciples

    fun getElderDisciple(elderId: String?): DiscipleAggregate? {
        if (elderId == null) return null
        return discipleAggregates.value.find { it.id == elderId }
    }

    fun assignElder(slotType: ElderSlotType, discipleId: String) {
        viewModelScope.launch {
            try {
                val disciple = disciples.value.find { it.id == discipleId }
                if (disciple == null) {
                    showError("弟子不存在")
                    return@launch
                }

                val minRealm = when (slotType) {
                    ElderSlotType.VICE_SECT_MASTER -> 4
                    ElderSlotType.LAW_ENFORCEMENT -> 5
                    else -> 6
                }
                if (disciple.realm > minRealm) {
                    val realmName = when (minRealm) {
                        4 -> "炼虚"
                        5 -> "化神"
                        6 -> "元婴"
                        else -> "元婴"
                    }
                    val positionName = when (slotType) {
                        ElderSlotType.VICE_SECT_MASTER -> "副宗主"
                        else -> "长老"
                    }
                    showError("${positionName}需要达到${realmName}境界")
                    return@launch
                }

                val currentGameData = gameEngine.gameData.value
                val elderSlots = currentGameData.elderSlots

                val allElderIds = elderSlots.getAllElderIds()
                val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

                if (allElderIds.contains(discipleId)) {
                    showError("该弟子已担任长老职位")
                    return@launch
                }

                if (allDirectDiscipleIds.contains(discipleId)) {
                    showError("该弟子已是其他长老的亲传弟子")
                    return@launch
                }

                val newElderSlots = when (slotType) {
                    ElderSlotType.HERB_GARDEN -> elderSlots.copy(
                        herbGardenElder = discipleId,
                        herbGardenDisciples = emptyList()
                    )
                    ElderSlotType.ALCHEMY -> elderSlots.copy(
                        alchemyElder = discipleId,
                        alchemyDisciples = emptyList()
                    )
                    ElderSlotType.FORGE -> elderSlots.copy(
                        forgeElder = discipleId,
                        forgeDisciples = emptyList()
                    )
                    ElderSlotType.VICE_SECT_MASTER -> elderSlots.copy(
                        viceSectMaster = discipleId
                    )
                    ElderSlotType.OUTER_ELDER -> elderSlots.copy(
                        outerElder = discipleId
                    )
                    ElderSlotType.PREACHING -> elderSlots.copy(
                        preachingElder = discipleId
                    )
                    ElderSlotType.LAW_ENFORCEMENT -> elderSlots.copy(
                        lawEnforcementElder = discipleId,
                        lawEnforcementDisciples = emptyList()
                    )
                    ElderSlotType.INNER_ELDER -> elderSlots.copy(
                        innerElder = discipleId
                    )
                    ElderSlotType.CLOUD_PREACHING -> elderSlots.copy(
                        qingyunPreachingElder = discipleId
                    )
                }
                gameEngine.updateElderSlots(newElderSlots)
            } catch (e: Exception) {
                showError(e.message ?: "任命失败")
            }
        }
    }

    fun removeElder(slotType: ElderSlotType) {
        viewModelScope.launch {
            try {
                val currentGameData = gameEngine.gameData.value
                val elderSlots = currentGameData.elderSlots
                val newElderSlots = when (slotType) {
                    ElderSlotType.HERB_GARDEN -> elderSlots.copy(
                        herbGardenElder = "",
                        herbGardenDisciples = emptyList()
                    )
                    ElderSlotType.ALCHEMY -> elderSlots.copy(
                        alchemyElder = "",
                        alchemyDisciples = emptyList()
                    )
                    ElderSlotType.FORGE -> elderSlots.copy(
                        forgeElder = "",
                        forgeDisciples = emptyList()
                    )
                    ElderSlotType.VICE_SECT_MASTER -> elderSlots.copy(
                        viceSectMaster = ""
                    )
                    ElderSlotType.OUTER_ELDER -> elderSlots.copy(
                        outerElder = ""
                    )
                    ElderSlotType.PREACHING -> elderSlots.copy(
                        preachingElder = ""
                    )
                    ElderSlotType.LAW_ENFORCEMENT -> elderSlots.copy(
                        lawEnforcementElder = ""
                    )
                    ElderSlotType.INNER_ELDER -> elderSlots.copy(
                        innerElder = ""
                    )
                    ElderSlotType.CLOUD_PREACHING -> elderSlots.copy(
                        qingyunPreachingElder = ""
                    )
                }
                gameEngine.updateElderSlots(newElderSlots)
            } catch (e: Exception) {
                showError(e.message ?: "卸任失败")
            }
        }
    }

    fun assignDirectDisciple(elderSlotType: String, slotIndex: Int, discipleId: String) {
        viewModelScope.launch {
            try {
                val disciple = disciples.value.find { it.id == discipleId }
                if (disciple == null) {
                    showError("弟子不存在")
                    return@launch
                }

                val currentGameData = gameEngine.gameData.value
                val elderSlots = currentGameData.elderSlots

                val allElderIds = elderSlots.getAllElderIds()
                val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

                if (allElderIds.contains(discipleId)) {
                    showError("该弟子已担任长老职位")
                    return@launch
                }

                if (allDirectDiscipleIds.contains(discipleId)) {
                    showError("该弟子已是其他长老的亲传弟子")
                    return@launch
                }

                gameEngine.assignDirectDisciple(
                    elderSlotType = elderSlotType,
                    slotIndex = slotIndex,
                    discipleId = discipleId,
                    discipleName = disciple.name,
                    discipleRealm = disciple.realmName,
                    discipleSpiritRootColor = disciple.spiritRoot.countColor
                )
            } catch (e: Exception) {
                showError(e.message ?: "分配失败")
            }
        }
    }

    fun removeDirectDisciple(elderSlotType: String, slotIndex: Int) {
        viewModelScope.launch {
            try {
                gameEngine.removeDirectDisciple(elderSlotType, slotIndex)
            } catch (e: Exception) {
                showError(e.message ?: "卸任失败")
            }
        }
    }

    fun getAlchemyReserveDisciplesWithInfo(): List<DiscipleAggregate> {
        val reserveSlots = gameEngine.gameData.value?.elderSlots?.alchemyReserveDisciples ?: emptyList()
        val reserveIds = reserveSlots.mapNotNull { it.discipleId }.toSet()
        return discipleAggregates.value
            .filter { it.id in reserveIds }
            .sortedByDescending { it.pillRefining }
    }

    fun addAlchemyReserveDisciples(discipleIds: List<String>) {
        viewModelScope.launch {
            try {
                val currentReserveDisciples = gameEngine.gameData.value?.elderSlots?.alchemyReserveDisciples?.toMutableList() ?: mutableListOf()
                val existingIds = currentReserveDisciples.mapNotNull { it.discipleId }.toSet()
                val initialSize = currentReserveDisciples.size

                discipleIds.forEach { discipleId ->
                    if (existingIds.contains(discipleId)) return@forEach
                    val disciple = disciples.value.find { it.id == discipleId } ?: return@forEach
                    if (disciplePositionQuery.hasDisciplePosition(discipleId)) return@forEach
                    if (disciplePositionQuery.isReserveDisciple(discipleId)) return@forEach

                    val newIndex = if (currentReserveDisciples.isEmpty()) 0 else currentReserveDisciples.maxOf { it.index } + 1
                    val newSlot = DirectDiscipleSlot(
                        index = newIndex,
                        discipleId = discipleId,
                        discipleName = disciple.name,
                        discipleRealm = disciple.realmName,
                        discipleSpiritRootColor = disciple.spiritRoot.countColor
                    )
                    currentReserveDisciples.add(newSlot)
                }

                val addedCount = currentReserveDisciples.size - initialSize
                if (addedCount > 0) {
                    gameEngine.updateGameData { it.copy(elderSlots = it.elderSlots.copy(alchemyReserveDisciples = currentReserveDisciples)) }
                } else {
                    showError("没有可添加的弟子")
                }
            } catch (e: Exception) {
                showError(e.message ?: "添加失败")
            }
        }
    }

    fun removeAlchemyReserveDisciple(discipleId: String) {
        viewModelScope.launch {
            try {
                val currentReserveDisciples = gameEngine.gameData.value?.elderSlots?.alchemyReserveDisciples ?: emptyList()
                val updatedReserveDisciples = currentReserveDisciples.filter { it.discipleId != discipleId }
                gameEngine.updateGameData { it.copy(elderSlots = it.elderSlots.copy(alchemyReserveDisciples = updatedReserveDisciples)) }
                gameEngine.syncAllDiscipleStatuses()
            } catch (e: Exception) {
                showError(e.message ?: "移除失败")
            }
        }
    }

    fun getAvailableDisciplesForAlchemyReserve(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds() +
            elderSlots.alchemyReserveDisciples.mapNotNull { it.discipleId } +
            elderSlots.forgeReserveDisciples.mapNotNull { it.discipleId }

        return discipleAggregates.value
            .filter {
                it.isAlive &&
                it.discipleType == "inner" &&
                it.realmLayer > 0 &&
                it.status == DiscipleStatus.IDLE &&
                !allElderIds.contains(it.id) &&
                !allDirectDiscipleIds.contains(it.id)
            }
            .sortedByDescending { it.pillRefining }
    }

    fun getForgeReserveDisciplesWithInfo(): List<DiscipleAggregate> {
        val reserveSlots = gameEngine.gameData.value.elderSlots.forgeReserveDisciples
        val reserveIds = reserveSlots.mapNotNull { it.discipleId }.toSet()
        return discipleAggregates.value
            .filter { it.id in reserveIds && it.status != DiscipleStatus.REFLECTING }
            .sortedByDescending { it.artifactRefining }
    }

    private fun ElderSlots.getAllElderIds(): List<String> {
        return listOf(
            viceSectMaster,
            herbGardenElder,
            alchemyElder,
            forgeElder,
            outerElder,
            preachingElder,
            lawEnforcementElder,
            innerElder,
            qingyunPreachingElder
        ).filter { !it.isNullOrBlank() }
    }

    private fun ElderSlots.getAllDirectDiscipleIds(): List<String> {
        return listOf(
            herbGardenDisciples,
            alchemyDisciples,
            forgeDisciples,
            preachingMasters,
            lawEnforcementDisciples,
            lawEnforcementReserveDisciples,
            qingyunPreachingMasters,
            spiritMineDeaconDisciples
        ).flatten().mapNotNull { it.discipleId }
    }

    fun addForgeReserveDisciples(discipleIds: List<String>) {
        viewModelScope.launch {
            try {
                val currentReserveDisciples = gameEngine.gameData.value.elderSlots.forgeReserveDisciples.toMutableList()
                val existingIds = currentReserveDisciples.mapNotNull { it.discipleId }.toSet()
                var addedCount = 0

                discipleIds.forEach { discipleId ->
                    if (existingIds.contains(discipleId)) return@forEach
                    val disciple = disciples.value.find { it.id == discipleId } ?: return@forEach
                    if (disciplePositionQuery.hasDisciplePosition(discipleId)) return@forEach
                    if (disciplePositionQuery.isReserveDisciple(discipleId)) return@forEach

                    val newIndex = if (currentReserveDisciples.isEmpty()) 0 else currentReserveDisciples.maxOf { it.index } + 1
                    val newSlot = DirectDiscipleSlot(
                        index = newIndex,
                        discipleId = discipleId,
                        discipleName = disciple.name,
                        discipleRealm = disciple.realmName,
                        discipleSpiritRootColor = disciple.spiritRoot.countColor
                    )
                    currentReserveDisciples.add(newSlot)
                    addedCount++
                }

                if (addedCount > 0) {
                    val updatedElderSlots = gameEngine.gameData.value.elderSlots.copy(
                        forgeReserveDisciples = currentReserveDisciples
                    )
                    gameEngine.updateGameData { it.copy(elderSlots = updatedElderSlots) }
                }
            } catch (e: Exception) {
                showError(e.message ?: "添加失败")
            }
        }
    }

    fun removeForgeReserveDisciple(discipleId: String) {
        viewModelScope.launch {
            try {
                val currentReserveDisciples = gameEngine.gameData.value.elderSlots.forgeReserveDisciples
                val updatedReserveDisciples = currentReserveDisciples.filter { it.discipleId != discipleId }
                val updatedElderSlots = gameEngine.gameData.value.elderSlots.copy(
                    forgeReserveDisciples = updatedReserveDisciples
                )
                gameEngine.updateGameData { it.copy(elderSlots = updatedElderSlots) }
                gameEngine.syncAllDiscipleStatuses()
            } catch (e: Exception) {
                showError(e.message ?: "移除失败")
            }
        }
    }

    fun getAvailableDisciplesForForgeReserve(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds() +
            elderSlots.alchemyReserveDisciples.mapNotNull { it.discipleId } +
            elderSlots.forgeReserveDisciples.mapNotNull { it.discipleId }

        return discipleAggregates.value
            .filter {
                it.isAlive &&
                it.discipleType == "inner" &&
                it.realmLayer > 0 &&
                it.status == DiscipleStatus.IDLE &&
                !allElderIds.contains(it.id) &&
                !allDirectDiscipleIds.contains(it.id)
            }
            .sortedByDescending { it.artifactRefining }
    }

    val autoPlantEnabled: StateFlow<Boolean> = gameEngine.gameData
        .map { it.sectPolicies.autoPlant }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun toggleAutoPlant() {
        viewModelScope.launch {
            gameEngine.updateGameData { it.copy(sectPolicies = it.sectPolicies.copy(autoPlant = !it.sectPolicies.autoPlant)) }
        }
    }

    val autoAlchemyEnabled: StateFlow<Boolean> = gameEngine.gameData
        .map { it.sectPolicies.autoAlchemy }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun toggleAutoAlchemy() {
        viewModelScope.launch {
            gameEngine.updateGameData { it.copy(sectPolicies = it.sectPolicies.copy(autoAlchemy = !it.sectPolicies.autoAlchemy)) }
        }
    }

    val autoForgeEnabled: StateFlow<Boolean> = gameEngine.gameData
        .map { it.sectPolicies.autoForge }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun toggleAutoForge() {
        viewModelScope.launch {
            gameEngine.updateGameData { it.copy(sectPolicies = it.sectPolicies.copy(autoForge = !it.sectPolicies.autoForge)) }
        }
    }

    fun toggleSpiritMineBoost(): Boolean {
        val currentGameData = gameEngine.gameData.value ?: return false
        viewModelScope.launch {
            gameEngine.updateGameData { it.copy(sectPolicies = it.sectPolicies.copy(spiritMineBoost = !it.sectPolicies.spiritMineBoost)) }
        }
        return true
    }

    fun isSpiritMineBoostEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.spiritMineBoost ?: false
    }

    fun getSpiritMineBoostEffect(): Double {
        return GameConfig.PolicyConfig.SPIRIT_MINE_BOOST_BASE_EFFECT
    }

    fun toggleEnhancedSecurity(): Boolean {
        val currentGameData = gameEngine.gameData.value ?: return false
        val currentPolicies = currentGameData.sectPolicies
        val requiredStones = GameConfig.PolicyConfig.ENHANCED_SECURITY_COST

        if (!currentPolicies.enhancedSecurity) {
            viewModelScope.launch {
                gameEngine.updateGameData {
                    if (it.spiritStones < requiredStones) {
                        showError("灵石不足${requiredStones}，无法开启增强治安政策")
                        it
                    } else {
                        it.copy(
                            spiritStones = it.spiritStones - requiredStones,
                            sectPolicies = it.sectPolicies.copy(enhancedSecurity = true)
                        )
                    }
                }
            }
        } else {
            viewModelScope.launch {
                gameEngine.updateGameData { it.copy(sectPolicies = it.sectPolicies.copy(enhancedSecurity = false)) }
            }
        }
        return true
    }

    fun isEnhancedSecurityEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.enhancedSecurity ?: false
    }

    fun getEnhancedSecurityBaseBonus(): Double {
        return GameConfig.PolicyConfig.ENHANCED_SECURITY_BASE_EFFECT
    }

    fun toggleAlchemyIncentive(): Boolean {
        val currentGameData = gameEngine.gameData.value ?: return false
        val currentPolicies = currentGameData.sectPolicies
        val requiredStones = GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_COST

        if (!currentPolicies.alchemyIncentive) {
            viewModelScope.launch {
                gameEngine.updateGameData {
                    if (it.spiritStones < requiredStones) {
                        showError("灵石不足${requiredStones}，无法开启丹道激励政策")
                        it
                    } else {
                        it.copy(
                            spiritStones = it.spiritStones - requiredStones,
                            sectPolicies = it.sectPolicies.copy(alchemyIncentive = true)
                        )
                    }
                }
            }
        } else {
            viewModelScope.launch {
                gameEngine.updateGameData { it.copy(sectPolicies = it.sectPolicies.copy(alchemyIncentive = false)) }
            }
        }
        return true
    }

    fun isAlchemyIncentiveEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.alchemyIncentive ?: false
    }

    fun toggleForgeIncentive(): Boolean {
        val currentGameData = gameEngine.gameData.value ?: return false
        val currentPolicies = currentGameData.sectPolicies
        val requiredStones = GameConfig.PolicyConfig.FORGE_INCENTIVE_COST

        if (!currentPolicies.forgeIncentive) {
            viewModelScope.launch {
                gameEngine.updateGameData {
                    if (it.spiritStones < requiredStones) {
                        showError("灵石不足${requiredStones}，无法开启锻造激励政策")
                        it
                    } else {
                        it.copy(
                            spiritStones = it.spiritStones - requiredStones,
                            sectPolicies = it.sectPolicies.copy(forgeIncentive = true)
                        )
                    }
                }
            }
        } else {
            viewModelScope.launch {
                gameEngine.updateGameData { it.copy(sectPolicies = it.sectPolicies.copy(forgeIncentive = false)) }
            }
        }
        return true
    }

    fun isForgeIncentiveEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.forgeIncentive ?: false
    }

    fun toggleHerbCultivation(): Boolean {
        val currentGameData = gameEngine.gameData.value ?: return false
        val currentPolicies = currentGameData.sectPolicies
        val requiredStones = GameConfig.PolicyConfig.HERB_CULTIVATION_COST

        if (!currentPolicies.herbCultivation) {
            viewModelScope.launch {
                gameEngine.updateGameData {
                    if (it.spiritStones < requiredStones) {
                        showError("灵石不足${requiredStones}，无法开启灵药培育政策")
                        it
                    } else {
                        it.copy(
                            spiritStones = it.spiritStones - requiredStones,
                            sectPolicies = it.sectPolicies.copy(herbCultivation = true)
                        )
                    }
                }
            }
        } else {
            viewModelScope.launch {
                gameEngine.updateGameData { it.copy(sectPolicies = it.sectPolicies.copy(herbCultivation = false)) }
            }
        }
        return true
    }

    fun isHerbCultivationEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.herbCultivation ?: false
    }

    fun toggleCultivationSubsidy(): Boolean {
        val currentGameData = gameEngine.gameData.value ?: return false
        val currentPolicies = currentGameData.sectPolicies
        val requiredStones = GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_COST

        if (!currentPolicies.cultivationSubsidy) {
            viewModelScope.launch {
                gameEngine.updateGameData {
                    if (it.spiritStones < requiredStones) {
                        showError("灵石不足${requiredStones}，无法开启修行津贴政策")
                        it
                    } else {
                        it.copy(
                            spiritStones = it.spiritStones - requiredStones,
                            sectPolicies = it.sectPolicies.copy(cultivationSubsidy = true)
                        )
                    }
                }
            }
        } else {
            viewModelScope.launch {
                gameEngine.updateGameData { it.copy(sectPolicies = it.sectPolicies.copy(cultivationSubsidy = false)) }
            }
        }
        return true
    }

    fun isCultivationSubsidyEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.cultivationSubsidy ?: false
    }

    fun toggleManualResearch(): Boolean {
        val currentGameData = gameEngine.gameData.value ?: return false
        val currentPolicies = currentGameData.sectPolicies
        val requiredStones = GameConfig.PolicyConfig.MANUAL_RESEARCH_COST

        if (!currentPolicies.manualResearch) {
            viewModelScope.launch {
                gameEngine.updateGameData {
                    if (it.spiritStones < requiredStones) {
                        showError("灵石不足${requiredStones}，无法开启功法研习政策")
                        it
                    } else {
                        it.copy(
                            spiritStones = it.spiritStones - requiredStones,
                            sectPolicies = it.sectPolicies.copy(manualResearch = true)
                        )
                    }
                }
            }
        } else {
            viewModelScope.launch {
                gameEngine.updateGameData { it.copy(sectPolicies = it.sectPolicies.copy(manualResearch = false)) }
            }
        }
        return true
    }

    fun isManualResearchEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.manualResearch ?: false
    }

    fun getSpiritMineDeaconDisciples(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value.elderSlots.spiritMineDeaconDisciples
    }

    fun getAvailableDisciplesForSpiritMineDeacon(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return discipleAggregates.value
            .filter {
                it.isAlive &&
                it.discipleType == "inner" &&
                it.age >= 5 &&
                it.realmLayer > 0 &&
                it.status == DiscipleStatus.IDLE &&
                !allElderIds.contains(it.id) &&
                !allDirectDiscipleIds.contains(it.id)
            }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun assignSpiritMineDeacon(slotIndex: Int, discipleId: String) {
        viewModelScope.launch {
            try {
                val disciple = disciples.value.find { it.id == discipleId }
                if (disciple == null) {
                    showError("弟子不存在")
                    return@launch
                }

                if (disciple.discipleType != "inner") {
                    showError("执事只能由内门弟子担任")
                    return@launch
                }

                val currentGameData = gameEngine.gameData.value
                val elderSlots = currentGameData.elderSlots

                val allElderIds = elderSlots.getAllElderIds()
                val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

                if (allElderIds.contains(discipleId)) {
                    showError("该弟子已担任长老职位")
                    return@launch
                }

                if (allDirectDiscipleIds.contains(discipleId)) {
                    showError("该弟子已是其他长老的亲传弟子")
                    return@launch
                }

                val currentDeacons = elderSlots.spiritMineDeaconDisciples.toMutableList()
                val existingSlot = currentDeacons.find { it.index == slotIndex }

                val newSlot = DirectDiscipleSlot(
                    index = slotIndex,
                    discipleId = discipleId,
                    discipleName = disciple.name,
                    discipleRealm = disciple.realmName,
                    discipleSpiritRootColor = disciple.spiritRoot.countColor
                )

                if (existingSlot != null) {
                    currentDeacons[currentDeacons.indexOf(existingSlot)] = newSlot
                } else {
                    currentDeacons.add(newSlot)
                }

                val updatedElderSlots = elderSlots.copy(spiritMineDeaconDisciples = currentDeacons)
                gameEngine.updateGameData { it.copy(elderSlots = updatedElderSlots) }

                gameEngine.updateDiscipleStatus(discipleId, DiscipleStatus.DEACONING)
            } catch (e: Exception) {
                showError(e.message ?: "任命失败")
            }
        }
    }

    fun removeSpiritMineDeacon(slotIndex: Int) {
        viewModelScope.launch {
            try {
                val currentGameData = gameEngine.gameData.value
                val elderSlots = currentGameData.elderSlots

                val removedDeaconId = elderSlots.spiritMineDeaconDisciples.find { it.index == slotIndex }?.discipleId

                val currentDeacons = elderSlots.spiritMineDeaconDisciples.filter { it.index != slotIndex }
                val updatedElderSlots = elderSlots.copy(spiritMineDeaconDisciples = currentDeacons)
                gameEngine.updateGameData { it.copy(elderSlots = updatedElderSlots) }

                removedDeaconId?.let { gameEngine.updateDiscipleStatus(it, DiscipleStatus.IDLE) }
            } catch (e: Exception) {
                showError(e.message ?: "卸任失败")
            }
        }
    }

    fun validateSpiritMineData() {
        gameEngine.validateAndFixSpiritMineData()
    }

    fun getAvailableDisciplesForSpiritMining(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        val assignedMiningIds = gameEngine.gameData.value.spiritMineSlots.mapNotNull { it.discipleId }.toSet()

        return discipleAggregates.value
            .filter {
                it.isAlive &&
                it.discipleType == "outer" &&
                it.realmLayer > 0 &&
                it.status == DiscipleStatus.IDLE &&
                !allElderIds.contains(it.id) &&
                !allDirectDiscipleIds.contains(it.id) &&
                !assignedMiningIds.contains(it.id)
            }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun assignDisciplesToSpiritMineSlots(selectedDisciples: List<DiscipleAggregate>) {
        viewModelScope.launch {
            try {
                assignDisciplesToEmptyMineSlotsInternal(selectedDisciples)
            } catch (e: Exception) {
                showError(e.message ?: "分配失败")
            }
        }
    }

    fun removeDiscipleFromSpiritMineSlot(slotIndex: Int) {
        viewModelScope.launch {
            try {
                val currentGameData = gameEngine.gameData.value
                val currentSlots = currentGameData.spiritMineSlots.toMutableList()

                if (slotIndex < currentSlots.size) {
                    val discipleId = currentSlots[slotIndex].discipleId
                    discipleId?.let {
                        gameEngine.updateDiscipleStatus(it, DiscipleStatus.IDLE)
                    }

                    currentSlots[slotIndex] = currentSlots[slotIndex].copy(
                        discipleId = "",
                        discipleName = ""
                    )
                    gameEngine.updateSpiritMineSlots(currentSlots)
                }
            } catch (e: Exception) {
                showError(e.message ?: "卸任失败")
            }
        }
    }

    fun autoAssignSpiritMineMiners() {
        viewModelScope.launch {
            try {
                val availableDisciples = getAvailableDisciplesForSpiritMining()
                if (availableDisciples.isEmpty()) return@launch
                assignDisciplesToEmptyMineSlotsInternal(availableDisciples)
            } catch (e: Exception) {
                showError(e.message ?: "一键任命失败")
            }
        }
    }

    private suspend fun assignDisciplesToEmptyMineSlotsInternal(disciples: List<DiscipleAggregate>) {
        val currentGameData = gameEngine.gameData.value
        var currentSlots = currentGameData.spiritMineSlots.toMutableList()
        while (currentSlots.size < 12) {
            currentSlots.add(SpiritMineSlot(index = currentSlots.size))
        }
        val disciplesToAssign = disciples.take(currentSlots.count { it.discipleId.isEmpty() })
        for (disciple in disciplesToAssign) {
            val targetSlotIndex = currentSlots.indexOfFirst { it.discipleId.isEmpty() }
            if (targetSlotIndex == -1) break
            currentSlots[targetSlotIndex] = currentSlots[targetSlotIndex].copy(
                discipleId = disciple.id,
                discipleName = disciple.name
            )
            gameEngine.updateDiscipleStatus(disciple.id, DiscipleStatus.MINING)
        }
        gameEngine.updateSpiritMineSlots(currentSlots)
    }

    fun assignDiscipleToLibrarySlot(slotIndex: Int, discipleId: String, discipleName: String) {
        viewModelScope.launch {
            try {
                gameEngine.assignDiscipleToLibrarySlot(slotIndex, discipleId, discipleName)
            } catch (e: Exception) {
                showError(e.message ?: "分配失败")
            }
        }
    }

    fun removeDiscipleFromLibrarySlot(slotIndex: Int) {
        viewModelScope.launch {
            try {
                gameEngine.removeDiscipleFromLibrarySlot(slotIndex)
            } catch (e: Exception) {
                showError(e.message ?: "卸任失败")
            }
        }
    }

    fun setViceSectMaster(discipleId: String) {
        viewModelScope.launch {
            val disciple = discipleAggregates.value.find { it.id == discipleId }
            if (disciple == null) {
                showError("弟子不存在")
                return@launch
            }

            if (disciple.realm > REALM_VICE_SECT_MASTER) {
                showError("副宗主需要达到炼虚境界")
                return@launch
            }

            val currentSlots = gameEngine.gameData.value.elderSlots
            val allElderIds = currentSlots.getAllElderIds()

            if (!allElderIds.contains(discipleId)) {
                showError("副宗主需要由长老担任")
                return@launch
            }

            gameEngine.updateGameData {
                it.copy(elderSlots = it.elderSlots.copy(viceSectMaster = discipleId))
            }
        }
    }

    fun removeViceSectMaster() {
        viewModelScope.launch {
            gameEngine.updateGameData {
                it.copy(elderSlots = it.elderSlots.copy(viceSectMaster = ""))
            }
        }
    }

    fun getViceSectMaster(): DiscipleAggregate? {
        val viceSectMasterId = gameEngine.gameData.value?.elderSlots?.viceSectMaster
        return getElderDisciple(viceSectMasterId)
    }

    fun getViceSectMasterIntelligenceBonus(): Double {
        val viceSectMaster = getViceSectMaster() ?: return 0.0
        val intelligence = viceSectMaster.intelligence
        val baseIntelligence = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_BASE
        val step = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_STEP
        val bonusPerStep = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_BONUS_PER_STEP
        return ((intelligence - baseIntelligence) / step.toDouble() * bonusPerStep).coerceAtLeast(0.0)
    }

    fun getOuterElder(): DiscipleAggregate? {
        val outerElderId = gameEngine.gameData.value?.elderSlots?.outerElder
        return getElderDisciple(outerElderId)
    }

    fun getPreachingElder(): DiscipleAggregate? {
        val preachingElderId = gameEngine.gameData.value?.elderSlots?.preachingElder
        return getElderDisciple(preachingElderId)
    }

    fun getPreachingMasters(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value?.elderSlots?.preachingMasters ?: emptyList()
    }

    fun getLawEnforcementElder(): DiscipleAggregate? {
        val elderId = gameEngine.gameData.value?.elderSlots?.lawEnforcementElder
        return getElderDisciple(elderId)
    }

    fun getLawEnforcementDisciples(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value?.elderSlots?.lawEnforcementDisciples ?: emptyList()
    }

    fun getLawEnforcementReserveDisciples(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value?.elderSlots?.lawEnforcementReserveDisciples ?: emptyList()
    }

    fun getLawEnforcementReserveDisciplesWithInfo(): List<DiscipleAggregate> {
        val reserveSlots = gameEngine.gameData.value?.elderSlots?.lawEnforcementReserveDisciples ?: emptyList()
        val reserveIds = reserveSlots.mapNotNull { it.discipleId }.toSet()
        return discipleAggregates.value
            .filter { it.id in reserveIds }
            .sortedByFollowAndRealm()
    }

    fun addReserveDisciple(discipleId: String) {
        viewModelScope.launch {
            try {
                val disciple = disciples.value.find { it.id == discipleId }
                if (disciple == null) {
                    showError("弟子不存在")
                    return@launch
                }

                if (disciplePositionQuery.hasDisciplePosition(discipleId)) {
                    showError("该弟子已担任其他职务，不可同时担任多个职务")
                    return@launch
                }

                if (disciplePositionQuery.isReserveDisciple(discipleId)) {
                    showError("该弟子已是其他部门的储备弟子")
                    return@launch
                }

                val currentReserveDisciples = gameEngine.gameData.value?.elderSlots?.lawEnforcementReserveDisciples ?: emptyList()
                if (currentReserveDisciples.any { it.discipleId == discipleId }) {
                    showError("该弟子已是储备弟子")
                    return@launch
                }

                val newIndex = if (currentReserveDisciples.isEmpty()) 0 else currentReserveDisciples.maxOf { it.index } + 1
                val newSlot = DirectDiscipleSlot(
                    index = newIndex,
                    discipleId = discipleId,
                    discipleName = disciple.name,
                    discipleRealm = disciple.realmName,
                    discipleSpiritRootColor = disciple.spiritRoot.countColor
                )

                val updatedReserveDisciples = currentReserveDisciples + newSlot
                gameEngine.updateGameData { it.copy(elderSlots = it.elderSlots.copy(lawEnforcementReserveDisciples = updatedReserveDisciples)) }
                gameEngine.syncAllDiscipleStatuses()
            } catch (e: Exception) {
                showError(e.message ?: "添加失败")
            }
        }
    }

    fun addReserveDisciples(discipleIds: List<String>) {
        viewModelScope.launch {
            try {
                val currentReserveDisciples = gameEngine.gameData.value?.elderSlots?.lawEnforcementReserveDisciples ?: emptyList()
                val existingIds = currentReserveDisciples.mapNotNull { it.discipleId }.toSet()

                val newSlots = mutableListOf<DirectDiscipleSlot>()
                var nextIndex = if (currentReserveDisciples.isEmpty()) 0 else currentReserveDisciples.maxOf { it.index } + 1

                for (discipleId in discipleIds) {
                    if (discipleId in existingIds) continue

                    val disciple = disciples.value.find { it.id == discipleId }
                    if (disciple == null) continue

                    if (disciplePositionQuery.hasDisciplePosition(discipleId)) continue

                    if (disciplePositionQuery.isReserveDisciple(discipleId)) continue

                    newSlots.add(
                        DirectDiscipleSlot(
                            index = nextIndex,
                            discipleId = discipleId,
                            discipleName = disciple.name,
                            discipleRealm = disciple.realmName,
                            discipleSpiritRootColor = disciple.spiritRoot.countColor
                        )
                    )
                    nextIndex++
                }

                if (newSlots.isNotEmpty()) {
                    val updatedReserveDisciples = currentReserveDisciples + newSlots
                    gameEngine.updateGameData { it.copy(elderSlots = it.elderSlots.copy(lawEnforcementReserveDisciples = updatedReserveDisciples)) }
                    gameEngine.syncAllDiscipleStatuses()
                }
            } catch (e: Exception) {
                showError(e.message ?: "添加失败")
            }
        }
    }

    fun removeReserveDisciple(discipleId: String) {
        viewModelScope.launch {
            try {
                val currentReserveDisciples = gameEngine.gameData.value?.elderSlots?.lawEnforcementReserveDisciples ?: emptyList()
                val updatedReserveDisciples = currentReserveDisciples.filter { it.discipleId != discipleId }
                gameEngine.updateGameData { it.copy(elderSlots = it.elderSlots.copy(lawEnforcementReserveDisciples = updatedReserveDisciples)) }
                gameEngine.syncAllDiscipleStatuses()
            } catch (e: Exception) {
                showError(e.message ?: "移除失败")
            }
        }
    }

    private fun isSelectableDisciple(disciple: DiscipleAggregate): Boolean {
        return disciple.isAlive &&
               disciple.discipleType == "inner" &&
               disciple.age >= 5 &&
               disciple.realmLayer > 0 &&
               disciple.status == DiscipleStatus.IDLE
    }

    fun getAvailableDisciplesForSelection(): List<DiscipleAggregate> {
        return discipleAggregates.value.filter { disciple ->
            disciple.isAlive &&
            disciple.status == DiscipleStatus.IDLE &&
            disciple.realmLayer > 0
        }
    }

    fun getAvailableDisciplesForLawEnforcementElder(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return discipleAggregates.value
            .filter { isSelectableDisciple(it) && it.realm <= 5 && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForLawEnforcementDisciple(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return discipleAggregates.value
            .filter { isSelectableDisciple(it) && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedByFollowAndRealm()
    }

    fun getAvailableDisciplesForLawEnforcementReserve(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return discipleAggregates.value
            .filter { isSelectableDisciple(it) && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForOuterElder(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return discipleAggregates.value
            .filter {
                it.isAlive &&
                it.discipleType == "inner" &&
                it.realm <= 6 &&
                it.age >= 5 &&
                it.realmLayer > 0 &&
                it.status == DiscipleStatus.IDLE &&
                !allElderIds.contains(it.id) &&
                !allDirectDiscipleIds.contains(it.id)
            }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForPreachingElder(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return discipleAggregates.value
            .filter { isSelectableDisciple(it) && it.realm <= 6 && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForPreachingMaster(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value?.elderSlots ?: return emptyList()
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return discipleAggregates.value
            .filter { isSelectableDisciple(it) && it.realm <= 7 && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getInnerElder(): DiscipleAggregate? {
        val innerElderId = gameEngine.gameData.value?.elderSlots?.innerElder
        return getElderDisciple(innerElderId)
    }

    fun getQingyunPreachingElder(): DiscipleAggregate? {
        val preachingElderId = gameEngine.gameData.value?.elderSlots?.qingyunPreachingElder
        return getElderDisciple(preachingElderId)
    }

    fun getQingyunPreachingMasters(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value?.elderSlots?.qingyunPreachingMasters ?: emptyList()
    }

    fun getAlchemyReserveDisciples(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value?.elderSlots?.alchemyReserveDisciples ?: emptyList()
    }

    fun getHerbGardenReserveDisciples(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value?.elderSlots?.herbGardenReserveDisciples ?: emptyList()
    }

    fun getHerbGardenReserveDisciplesWithInfo(): List<DiscipleAggregate> {
        val reserveSlots = gameEngine.gameData.value?.elderSlots?.herbGardenReserveDisciples ?: emptyList()
        val reserveIds = reserveSlots.mapNotNull { it.discipleId }.toSet()
        return discipleAggregates.value
            .filter { it.id in reserveIds }
            .sortedByDescending { it.spiritPlanting }
    }

    fun addHerbGardenReserveDisciples(discipleIds: List<String>) {
        viewModelScope.launch {
            try {
                val currentReserveDisciples = gameEngine.gameData.value?.elderSlots?.herbGardenReserveDisciples?.toMutableList() ?: mutableListOf()
                val existingIds = currentReserveDisciples.mapNotNull { it.discipleId }.toSet()
                var addedCount = 0

                discipleIds.forEach { discipleId ->
                    if (existingIds.contains(discipleId)) return@forEach

                    val disciple = disciples.value.find { it.id == discipleId } ?: return@forEach

                    if (disciplePositionQuery.hasDisciplePosition(discipleId)) return@forEach

                    if (disciplePositionQuery.isReserveDisciple(discipleId)) return@forEach

                    val newIndex = if (currentReserveDisciples.isEmpty()) 0 else currentReserveDisciples.maxOf { it.index } + 1
                    val newSlot = DirectDiscipleSlot(
                        index = newIndex,
                        discipleId = discipleId,
                        discipleName = disciple.name,
                        discipleRealm = disciple.realmName,
                        discipleSpiritRootColor = disciple.spiritRoot.countColor
                    )
                    currentReserveDisciples.add(newSlot)
                    addedCount++
                }

                if (addedCount > 0) {
                    gameEngine.updateGameData { it.copy(elderSlots = it.elderSlots.copy(herbGardenReserveDisciples = currentReserveDisciples)) }
                }
            } catch (e: Exception) {
                showError(e.message ?: "添加失败")
            }
        }
    }

    fun removeHerbGardenReserveDisciple(discipleId: String) {
        viewModelScope.launch {
            try {
                val currentReserveDisciples = gameEngine.gameData.value?.elderSlots?.herbGardenReserveDisciples ?: emptyList()
                val updatedReserveDisciples = currentReserveDisciples.filter { it.discipleId != discipleId }
                gameEngine.updateGameData { it.copy(elderSlots = it.elderSlots.copy(herbGardenReserveDisciples = updatedReserveDisciples)) }
            } catch (e: Exception) {
                showError(e.message ?: "移除失败")
            }
        }
    }

    fun getAvailableDisciplesForHerbGardenReserve(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value?.elderSlots ?: return emptyList()
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds() +
            elderSlots.alchemyReserveDisciples.mapNotNull { it.discipleId } +
            elderSlots.herbGardenReserveDisciples.mapNotNull { it.discipleId }

        return discipleAggregates.value
            .filter {
                it.isAlive &&
                it.discipleType == "inner" &&
                it.realmLayer > 0 &&
                it.status == DiscipleStatus.IDLE &&
                !allElderIds.contains(it.id) &&
                !allDirectDiscipleIds.contains(it.id)
            }
            .sortedByDescending { it.spiritPlanting }
    }

    fun getForgeReserveDisciples(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value.elderSlots.forgeReserveDisciples
    }

    fun getAvailableDisciplesForInnerElder(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return discipleAggregates.value
            .filter { isSelectableDisciple(it) && it.realm <= 6 && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForQingyunPreachingElder(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return discipleAggregates.value
            .filter { isSelectableDisciple(it) && it.realm <= 6 && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForQingyunPreachingMaster(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return discipleAggregates.value
            .filter { isSelectableDisciple(it) && it.realm <= 7 && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

}
