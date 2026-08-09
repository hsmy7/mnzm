package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.PillRecipeDatabase
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.assignDiscipleToProductionSlot
import com.xianxia.sect.core.engine.clearAlchemySlot
import com.xianxia.sect.core.engine.removeDiscipleFromProductionSlot
import com.xianxia.sect.core.engine.startAlchemy
import com.xianxia.sect.core.engine.toggleAutoRestart
import com.xianxia.sect.core.model.AlchemySlot
import com.xianxia.sect.core.model.AlchemySlotStatus
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.pillRefining
import com.xianxia.sect.core.profession.ProfessionRules
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
        // 职业门禁：按槽位弟子炼丹师职业等级限制可炼品阶（无职业只能炼凡品；
        // 弟子查不到时按无职业兜底，禁止放开到最高阶——对抗性审查）
        val maxTier = slot?.assignedDiscipleId
            ?.let { id -> gameEngine.discipleAggregatesSnapshot.find { it.id == id }?.alchemyLevel }
            ?.let { ProfessionRules.maxCraftableTier(it) }
            ?: 1
        val recipe = slot?.recipeId
            ?.let { prevRecipeId ->
                PillRecipeDatabase.getRecipeById(prevRecipeId)?.takeIf { recipe ->
                    recipe.tier <= maxTier && recipe.materials.all { (materialId, requiredQuantity) ->
                        val herbData = HerbDatabase.getHerbById(materialId) ?: return@all false
                        currentHerbs.filter {
                            it.name == herbData.name && it.rarity == herbData.rarity
                        }.sumOf { it.quantity } >= requiredQuantity
                    }
                }
            }
            ?: PillRecipeDatabase.findBestCraftableRecipe(currentHerbs, maxTier)
            ?: return DomainResult.Failure(AppError.Domain.Production.InsufficientMaterials())
        return gameEngine.startAlchemy(slotIndex, recipe.id)
    }

    /** 槽位无弟子时点击配方的提示框（"需要有弟子才可炼制"） */
    fun showNoWorkerHint() {
        showError("需要有弟子才可炼制")
    }

    /** 弟子职业等级不够时点击配方的提示框（"弟子职业等级不够无法炼制"） */
    fun showTierLockedHint() {
        showError("弟子职业等级不够无法炼制")
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
                } catch (e: Exception) {
                    // Best-effort immediate start; monthly tick will retry
                    android.util.Log.w("AlchemyViewModel", "自动炼丹立即开始失败，等待月变重试", e)
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
