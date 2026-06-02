package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.usecase.DisciplePositionQueryUseCase
import com.xianxia.sect.core.usecase.ElderManagementUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ForgeViewModel @Inject constructor(
    private val gameEngine: GameEngine,
    private val disciplePositionQuery: DisciplePositionQueryUseCase,
    private val elderManagement: ElderManagementUseCase
) : BaseViewModel() {

    val forgeSlots: StateFlow<List<ForgeSlot>> = gameEngine.productionSlots
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
                    },
                    autoRestartEnabled = slot.autoRestartEnabled,
                    assignedDiscipleId = slot.assignedDiscipleId,
                    assignedDiscipleName = slot.assignedDiscipleName
                )
            }
        }
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val allForgeRecipes: StateFlow<List<ForgeRecipeDatabase.ForgeRecipe>> = flow {
        emit(ForgeRecipeDatabase.getAllRecipes())
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _isStartingForge = MutableStateFlow(false)
    val isStartingForge: StateFlow<Boolean> = _isStartingForge.asStateFlow()

    fun isAutoEnabled(buildingIndex: Int): Boolean {
        return gameEngine.productionSlots.value
            .find { it.buildingType == BuildingType.FORGE && it.slotIndex == buildingIndex }
            ?.autoRestartEnabled ?: false
    }

    fun startForge(slotIndex: Int, recipe: ForgeRecipeDatabase.ForgeRecipe) {
        if (_isStartingForge.value) return
        _isStartingForge.value = true

        viewModelScope.launch {
            try {
                val success = gameEngine.startForging(slotIndex, recipe.id)
                if (!success) {
                    showError("锻造失败，请检查材料是否充足或是否已分配弟子")
                }
            } catch (e: Exception) {
                showError(e.message ?: "开始锻造失败")
            } finally {
                _isStartingForge.value = false
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
                val idleSlotIndices = forgeSlots
                    .filter { it.status == ProductionSlotStatus.IDLE }
                    .map { it.slotIndex }

                if (idleSlotIndices.isEmpty()) {
                    showError("没有空闲的锻造槽位")
                    return@launch
                }

                var startedCount = 0
                for (slotIndex in idleSlotIndices) {
                    if (startBestForgeRecipe(slotIndex)) startedCount++
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

    private suspend fun startBestForgeRecipe(slotIndex: Int): Boolean {
        val currentMaterials = gameEngine.getCurrentMaterials()
        val materialIndex = currentMaterials.groupBy { it.name to it.rarity }
            .mapValues { (_, list) -> list.sumOf { it.quantity } }
        val allRecipes = ForgeRecipeDatabase.getAllRecipes().sortedByDescending { it.rarity }

        val recipeToStart = allRecipes.firstOrNull { recipe ->
            recipe.materials.all { (materialId, requiredQuantity) ->
                val materialData = BeastMaterialDatabase.getMaterialById(materialId)
                materialData != null && run {
                    val available = materialIndex[materialData.name to materialData.rarity] ?: 0
                    available >= requiredQuantity
                }
            }
        } ?: return false

        return gameEngine.startForging(slotIndex, recipeToStart.id)
    }

    fun toggleAuto(buildingIndex: Int) {
        val currentValue = isAutoEnabled(buildingIndex)
        val newValue = !currentValue
        viewModelScope.launch {
            gameEngine.toggleAutoRestart(BuildingType.FORGE, buildingIndex)

            if (newValue) {
                try {
                    val slot = gameEngine.productionSlots.value.find {
                        it.buildingType == BuildingType.FORGE && it.slotIndex == buildingIndex
                    } ?: return@launch
                    if (slot.status == ProductionSlotStatus.IDLE && !slot.assignedDiscipleId.isNullOrEmpty()) {
                        startBestForgeRecipe(slot.slotIndex)
                    }
                } catch (_: Exception) {
                    // Best-effort immediate start; monthly tick will retry
                }
            }
        }
    }

    fun assignWorker(buildingIndex: Int, discipleId: String, discipleName: String) {
        gameEngine.assignDiscipleToProductionSlot(BuildingType.FORGE, buildingIndex, discipleId, discipleName)
    }

    fun removeWorker(buildingIndex: Int) {
        gameEngine.removeDiscipleFromProductionSlot(BuildingType.FORGE, buildingIndex)
    }

    fun cancelForge(slotIndex: Int) {
        gameEngine.clearForgeSlot(slotIndex)
    }

    fun getAvailableWorkers(): List<DiscipleAggregate> {
        val all = gameEngine.discipleAggregatesSnapshot
        val data = gameEngine.gameDataSnapshot
        val assignedIds = gameEngine.productionSlots.value
            .filter { it.buildingType == BuildingType.FORGE && it.assignedDiscipleId.isNullOrEmpty().not() }
            .mapNotNull { it.assignedDiscipleId }.toSet()
        val elderSlots = data.elderSlots
        val elderIds = setOf(
            elderSlots.alchemyElder, elderSlots.forgeElder,
            elderSlots.herbGardenElder, elderSlots.viceSectMaster
        ).filter { it.isNotEmpty() }
        val directIds = (elderSlots.alchemyDisciples + elderSlots.forgeDisciples + elderSlots.herbGardenDisciples
            + elderSlots.alchemyReserveDisciples + elderSlots.forgeReserveDisciples + elderSlots.herbGardenReserveDisciples)
            .mapNotNull { it.discipleId }.toSet()
        return all.filter { it.isAlive && it.id !in assignedIds && it.id !in elderIds && it.id !in directIds }
            .sortedByDescending { it.artifactRefining }
    }

    fun getForgeReserveDisciplesWithInfo(): List<DiscipleAggregate> {
        val activeSectId = gameEngine.gameDataSnapshot.activeSectId
        val reserveSlots = gameEngine.gameDataSnapshot.elderSlots.forgeReserveDisciples.filter { it.sectId == activeSectId }
        val reserveIds = reserveSlots.mapNotNull { it.discipleId }.toSet()
        return gameEngine.discipleAggregatesSnapshot
            .filter { it.id in reserveIds && it.status != DiscipleStatus.REFLECTING }
            .sortedByDescending { it.artifactRefining }
    }

    fun addForgeReserveDisciples(discipleIds: List<String>) {
        viewModelScope.launch {
            try {
                val currentReserveDisciples = gameEngine.gameDataSnapshot.elderSlots.forgeReserveDisciples.toMutableList()
                val existingIds = currentReserveDisciples.mapNotNull { it.discipleId }.toSet()
                var addedCount = 0

                discipleIds.forEach { discipleId ->
                    if (existingIds.contains(discipleId)) return@forEach
                    val disciple = gameEngine.disciples.value.find { it.id == discipleId } ?: return@forEach
                    if (disciplePositionQuery.hasDisciplePosition(discipleId)) return@forEach
                    if (disciplePositionQuery.isReserveDisciple(discipleId)) return@forEach

                    val newIndex = if (currentReserveDisciples.isEmpty()) 0 else currentReserveDisciples.maxOf { it.index } + 1
                    val newSlot = DirectDiscipleSlot(
                        index = newIndex,
                        discipleId = discipleId,
                        discipleName = disciple.name,
                        discipleRealm = disciple.realmName,
                        discipleSpiritRootColor = disciple.spiritRoot.countColor,
                        sectId = gameEngine.gameDataSnapshot.activeSectId
                    )
                    currentReserveDisciples.add(newSlot)
                    addedCount++
                }

                if (addedCount > 0) {
                    val updatedElderSlots = gameEngine.gameDataSnapshot.elderSlots.copy(
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
                val currentReserveDisciples = gameEngine.gameDataSnapshot.elderSlots.forgeReserveDisciples
                val updatedReserveDisciples = currentReserveDisciples.filter { it.discipleId != discipleId }
                val updatedElderSlots = gameEngine.gameDataSnapshot.elderSlots.copy(
                    forgeReserveDisciples = updatedReserveDisciples
                )
                gameEngine.updateGameDataAndSync { it.copy(elderSlots = updatedElderSlots) }
            } catch (e: Exception) {
                showError(e.message ?: "移除失败")
            }
        }
    }

    fun getAvailableDisciplesForForgeReserve(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameDataSnapshot.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds() +
            elderSlots.alchemyReserveDisciples.mapNotNull { it.discipleId } +
            elderSlots.forgeReserveDisciples.mapNotNull { it.discipleId }

        return gameEngine.discipleAggregatesSnapshot
            .filter { it.isEligibleForProductionPosition && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedByDescending { it.artifactRefining }
    }

    fun getForgeReserveDisciples(): List<DirectDiscipleSlot> {
        val activeSectId = gameEngine.gameDataSnapshot.activeSectId
        return gameEngine.gameDataSnapshot.elderSlots.forgeReserveDisciples.filter { it.sectId == activeSectId }
    }

    private fun ElderSlots.getAllElderIds(): List<String> {
        return elderManagement.run { getAllElderIds() }
    }

    private fun ElderSlots.getAllDirectDiscipleIds(): List<String> {
        return elderManagement.run { getAllDirectDiscipleIds() }
    }
}
