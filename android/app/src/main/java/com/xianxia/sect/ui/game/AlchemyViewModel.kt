package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.PillRecipeDatabase
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.usecase.DisciplePositionQueryUseCase
import com.xianxia.sect.core.usecase.ElderManagementUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlchemyViewModel @Inject constructor(
    private val gameEngine: GameEngine,
    private val disciplePositionQuery: DisciplePositionQueryUseCase,
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
                    requiredMaterials = slot.requiredMaterials
                )
            }
        }
        .stateIn(viewModelScope, sharingStarted, emptyList())

    private val _isStartingAlchemy = MutableStateFlow(false)
    val isStartingAlchemy: StateFlow<Boolean> = _isStartingAlchemy.asStateFlow()

    val autoAlchemyEnabled: StateFlow<Boolean> = gameEngine.gameData
        .map { it.sectPolicies.autoAlchemy }
        .distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, false)

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

    fun toggleAutoAlchemy() {
        viewModelScope.launch {
            gameEngine.updateGameData { it.copy(sectPolicies = it.sectPolicies.copy(autoAlchemy = !it.sectPolicies.autoAlchemy)) }
        }
    }

    fun getAlchemyReserveDisciplesWithInfo(): List<DiscipleAggregate> {
        val reserveSlots = gameEngine.gameData.value?.elderSlots?.alchemyReserveDisciples ?: emptyList()
        val reserveIds = reserveSlots.mapNotNull { it.discipleId }.toSet()
        return gameEngine.discipleAggregates.value
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

        return gameEngine.discipleAggregates.value
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

    fun getAlchemyReserveDisciples(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value?.elderSlots?.alchemyReserveDisciples ?: emptyList()
    }

    private fun ElderSlots.getAllElderIds(): List<String> {
        return elderManagement.run { getAllElderIds() }
    }

    private fun ElderSlots.getAllDirectDiscipleIds(): List<String> {
        return elderManagement.run { getAllDirectDiscipleIds() }
    }
}
