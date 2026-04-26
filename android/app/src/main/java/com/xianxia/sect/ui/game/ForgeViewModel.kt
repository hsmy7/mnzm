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
                    }
                )
            }
        }
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val allForgeRecipes: StateFlow<List<ForgeRecipeDatabase.ForgeRecipe>> = flow {
        emit(ForgeRecipeDatabase.getAllRecipes())
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _isStartingForge = MutableStateFlow(false)
    val isStartingForge: StateFlow<Boolean> = _isStartingForge.asStateFlow()

    val autoForgeEnabled: StateFlow<Boolean> = gameEngine.gameData
        .map { it.sectPolicies.autoForge }
        .distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, false)

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

    fun toggleAutoForge() {
        viewModelScope.launch {
            gameEngine.updateGameData { it.copy(sectPolicies = it.sectPolicies.copy(autoForge = !it.sectPolicies.autoForge)) }
        }
    }

    fun getForgeReserveDisciplesWithInfo(): List<DiscipleAggregate> {
        val reserveSlots = gameEngine.gameData.value.elderSlots.forgeReserveDisciples
        val reserveIds = reserveSlots.mapNotNull { it.discipleId }.toSet()
        return gameEngine.discipleAggregates.value
            .filter { it.id in reserveIds && it.status != DiscipleStatus.REFLECTING }
            .sortedByDescending { it.artifactRefining }
    }

    fun addForgeReserveDisciples(discipleIds: List<String>) {
        viewModelScope.launch {
            try {
                val currentReserveDisciples = gameEngine.gameData.value.elderSlots.forgeReserveDisciples.toMutableList()
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

        return gameEngine.discipleAggregates.value
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

    fun getForgeReserveDisciples(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value.elderSlots.forgeReserveDisciples
    }

    private fun ElderSlots.getAllElderIds(): List<String> {
        return elderManagement.run { getAllElderIds() }
    }

    private fun ElderSlots.getAllDirectDiscipleIds(): List<String> {
        return elderManagement.run { getAllDirectDiscipleIds() }
    }
}
