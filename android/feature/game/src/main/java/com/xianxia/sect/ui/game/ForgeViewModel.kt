package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.assignDiscipleToProductionSlot
import com.xianxia.sect.core.engine.clearForgeSlot
import com.xianxia.sect.core.engine.removeDiscipleFromProductionSlot
import com.xianxia.sect.core.engine.startForging
import com.xianxia.sect.core.engine.toggleAutoRestart
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.ForgeRecipe
import com.xianxia.sect.core.profession.ProfessionRules
import com.xianxia.sect.core.model.ForgeSlot
import com.xianxia.sect.core.model.ForgeSlotStatus
import com.xianxia.sect.core.model.artifactRefining
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.usecase.ElderManagementUseCase
import com.xianxia.sect.core.util.AppError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
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

        gameEngine.launchOnEngine {
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


    private suspend fun startBestForgeRecipe(slotIndex: Int): DomainResult<ProductionSlot> {
        val currentMaterials = gameEngine.getCurrentMaterials()
        val materialIndex = currentMaterials.groupBy { it.name to it.rarity }
            .mapValues { (_, list) -> list.sumOf { it.quantity } }
        val allRecipes = ForgeRecipeDatabase.getAllRecipes().sortedByDescending { it.rarity }

        val slot = gameEngine.productionSlots.value.find {
            it.buildingType == BuildingType.FORGE && it.slotIndex == slotIndex
        }

        // 职业门禁：按槽位弟子炼器师职业等级限制可锻品阶（无职业只能锻凡品；
        // 弟子查不到时按无职业兜底，禁止放开到最高阶——对抗性审查）
        val maxTier = slot?.assignedDiscipleId
            ?.let { id -> gameEngine.discipleAggregatesSnapshot.find { it.id == id }?.forgeLevel }
            ?.let { ProfessionRules.maxCraftableTier(it) }
            ?: 1
        val craftableRecipes = allRecipes.filter { it.tier <= maxTier }

        val recipeToStart = slot?.recipeId
            ?.let { prevRecipeId ->
                craftableRecipes.find { it.id == prevRecipeId }?.takeIf { recipe ->
                    recipe.materials.all { (materialId, requiredQuantity) ->
                        val materialData = BeastMaterialDatabase.getMaterialById(materialId)
                        materialData != null && run {
                            val available = materialIndex[materialData.name to materialData.rarity] ?: 0
                            available >= requiredQuantity
                        }
                    }
                }
            }
            ?: craftableRecipes.firstOrNull { recipe ->
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

    /** 槽位无弟子时点击配方的提示框（"需要有弟子才可锻造"） */
    fun showNoWorkerHint() {
        showError("需要有弟子才可锻造")
    }

    /** 弟子职业等级不够时点击配方的提示框（"弟子职业等级不够无法锻造"） */
    fun showTierLockedHint() {
        showError("弟子职业等级不够无法锻造")
    }

    fun toggleAuto(buildingIndex: Int) {
        val currentValue = isAutoEnabled(buildingIndex)
        val newValue = !currentValue
        gameEngine.launchOnEngine {
            gameEngine.toggleAutoRestart(BuildingType.FORGE, buildingIndex)

            if (newValue) {
                try {
                    val slot = gameEngine.productionSlots.value.find {
                        it.buildingType == BuildingType.FORGE && it.slotIndex == buildingIndex
                    } ?: return@launchOnEngine
                    if (slot.status == ProductionSlotStatus.IDLE && !slot.assignedDiscipleId.isNullOrEmpty()) {
                        startBestForgeRecipe(slot.slotIndex)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Best-effort immediate start; monthly tick will retry
                    android.util.Log.w("ForgeViewModel", "自动锻造立即开始失败，等待月变重试", e)
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
        return all.filter { it.isAlive }
            .sortedByDescending { it.artifactRefining }
    }
}
