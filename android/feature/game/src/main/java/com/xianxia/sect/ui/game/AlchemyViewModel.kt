package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.PillRecipeDatabase
import com.xianxia.sect.core.engine.*
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.usecase.ElderManagementUseCase
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.DomainResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class AlchemyViewModel @Inject constructor(
    private val gameEngine: GameEngine,
    private val elderManagement: ElderManagementUseCase
) : BaseViewModel() {

    val alchemySlots: StateFlow<List<AlchemySlot>> = gameEngine.productionSlots
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
                    requiredMaterials = slot.requiredMaterials,
                    autoRestartEnabled = slot.autoRestartEnabled,
                    assignedDiscipleId = slot.assignedDiscipleId,
                    assignedDiscipleName = slot.assignedDiscipleName
                )
            }
        }
        .stateIn(viewModelScope, sharingStarted, emptyList())

    private val _isStartingAlchemy = MutableStateFlow(false)
    val isStartingAlchemy: StateFlow<Boolean> = _isStartingAlchemy.asStateFlow()

    fun isAutoEnabled(buildingIndex: Int): Boolean {
        return gameEngine.productionSlots.value
            .find { it.buildingType == BuildingType.ALCHEMY && it.slotIndex == buildingIndex }
            ?.autoRestartEnabled ?: false
    }

    fun startAlchemy(slotIndex: Int, recipe: PillRecipeDatabase.PillRecipe) {
        if (_isStartingAlchemy.value) return
        _isStartingAlchemy.value = true

        gameEngine.launchOnEngine {
            try {
                val result = gameEngine.startAlchemy(slotIndex, recipe.id)
                if (result is DomainResult.Failure) {
                    showError(result.error.message)
                }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                showError(e.message ?: "开始炼制失败")
            } finally {
                _isStartingAlchemy.value = false
            }
        }
    }


    private suspend fun startBestAlchemyRecipe(slotIndex: Int): DomainResult<ProductionSlot> {
        val currentHerbs = gameEngine.getCurrentHerbs()
        val slot = gameEngine.productionSlots.value.find {
            it.buildingType == BuildingType.ALCHEMY && it.slotIndex == slotIndex
        }
        val recipe = slot?.recipeId
            ?.let { prevRecipeId ->
                PillRecipeDatabase.getRecipeById(prevRecipeId)?.takeIf { recipe ->
                    recipe.materials.all { (materialId, requiredQuantity) ->
                        val herbData = HerbDatabase.getHerbById(materialId) ?: return@all false
                        currentHerbs.filter {
                            it.name == herbData.name && it.rarity == herbData.rarity
                        }.sumOf { it.quantity } >= requiredQuantity
                    }
                }
            }
            ?: PillRecipeDatabase.findBestCraftableRecipe(currentHerbs)
            ?: return DomainResult.Failure(AppError.Domain.Production.InsufficientMaterials())
        return gameEngine.startAlchemy(slotIndex, recipe.id)
    }

    fun toggleAuto(buildingIndex: Int) {
        val currentValue = isAutoEnabled(buildingIndex)
        val newValue = !currentValue
        gameEngine.launchOnEngine {
            gameEngine.toggleAutoRestart(BuildingType.ALCHEMY, buildingIndex)

            if (newValue) {
                try {
                    val slot = gameEngine.productionSlots.value.find {
                        it.buildingType == BuildingType.ALCHEMY && it.slotIndex == buildingIndex
                    } ?: return@launchOnEngine
                    if (slot.status == ProductionSlotStatus.IDLE && !slot.assignedDiscipleId.isNullOrEmpty()) {
                        startBestAlchemyRecipe(slot.slotIndex)
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
        gameEngine.assignDiscipleToProductionSlot(BuildingType.ALCHEMY, buildingIndex, discipleId, discipleName)
    }

    fun removeWorker(buildingIndex: Int) {
        gameEngine.removeDiscipleFromProductionSlot(BuildingType.ALCHEMY, buildingIndex)
    }

    fun cancelAlchemy(slotIndex: Int) {
        val result = gameEngine.clearAlchemySlot(slotIndex)
        if (result is DomainResult.Failure) {
            android.util.Log.w("AlchemyViewModel", "取消炼丹失败: ${result.error.message}")
        }
    }

    fun getAvailableWorkers(): List<DiscipleAggregate> {
        val all = gameEngine.discipleAggregatesSnapshot
        return all.filter { it.isAlive }
            .sortedByDescending { it.pillRefining }
    }
}
