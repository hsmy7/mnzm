package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.engine.*
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.usecase.ElderManagementUseCase
import com.xianxia.sect.core.util.AppError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ForgeViewModel @Inject constructor(
    private val gameEngine: GameEngine,
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
                    successRate = slot.successRate,
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
                val result = gameEngine.startForging(slotIndex, recipe.id)
                if (result is DomainResult.Failure) {
                    showError(result.error.message)
                }
            } catch (e: CancellationException) {
                throw e
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
                    if (startBestForgeRecipe(slotIndex).isSuccess) startedCount++
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

    private suspend fun startBestForgeRecipe(slotIndex: Int): DomainResult<ProductionSlot> {
        val currentMaterials = gameEngine.getCurrentMaterials()
        val materialIndex = currentMaterials.groupBy { it.name to it.rarity }
            .mapValues { (_, list) -> list.sumOf { it.quantity } }
        val allRecipes = ForgeRecipeDatabase.getAllRecipes().sortedByDescending { it.rarity }

        val slot = gameEngine.productionSlots.value.find {
            it.buildingType == BuildingType.FORGE && it.slotIndex == slotIndex
        }

        val recipeToStart = slot?.recipeId
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
            }
            ?: return DomainResult.Failure(AppError.Domain.Production.InsufficientMaterials())

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
                } catch (e: CancellationException) {
                    throw e
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
        return all.filter { it.isAlive && !gameEngine.isDiscipleAssigned(it.id) }
            .sortedByDescending { it.artifactRefining }
    }
}
