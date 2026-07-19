package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.launch
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

        viewModelScope.launch {
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

                var startedCount = 0
                for (slotIndex in idleSlotIndices) {
                    if (startBestAlchemyRecipe(slotIndex).isSuccess) startedCount++
                }

                if (startedCount > 0) {
                    showSuccess("自动炼丹完成，已启动${startedCount}个槽位")
                } else {
                    showError("没有足够的草药进行炼丹")
                }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                showError(e.message ?: "自动炼丹失败")
            }
        }
    }

    private suspend fun startBestAlchemyRecipe(slotIndex: Int): DomainResult<ProductionSlot> {
        val currentHerbs = gameEngine.getCurrentHerbs()
        val recipe = PillRecipeDatabase.findBestCraftableRecipe(currentHerbs)
            ?: return DomainResult.Failure(AppError.Domain.Production.InsufficientMaterials())
        return gameEngine.startAlchemy(slotIndex, recipe.id)
    }

    fun toggleAuto(buildingIndex: Int) {
        val currentValue = isAutoEnabled(buildingIndex)
        val newValue = !currentValue
        viewModelScope.launch {
            gameEngine.toggleAutoRestart(BuildingType.ALCHEMY, buildingIndex)

            if (newValue) {
                try {
                    val slot = gameEngine.productionSlots.value.find {
                        it.buildingType == BuildingType.ALCHEMY && it.slotIndex == buildingIndex
                    } ?: return@launch
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
        return all.filter { it.isAlive && !gameEngine.isDiscipleAssigned(it.id) }
            .sortedByDescending { it.pillRefining }
    }
}
