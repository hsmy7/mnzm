package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
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
class HerbGardenViewModel @Inject constructor(
    private val gameEngine: GameEngine,
    private val disciplePositionQuery: DisciplePositionQueryUseCase,
    private val elderManagement: ElderManagementUseCase
) : BaseViewModel() {

    private val _selectedPlantSlotIndex = MutableStateFlow<Int?>(null)
    val selectedPlantSlotIndex: StateFlow<Int?> = _selectedPlantSlotIndex.asStateFlow()

    val autoPlantEnabled: StateFlow<Boolean> = gameEngine.gameData
        .map { it.sectPolicies.autoPlant }
        .distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, false)

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

    fun toggleAutoPlant() {
        viewModelScope.launch {
            gameEngine.updateGameData { it.copy(sectPolicies = it.sectPolicies.copy(autoPlant = !it.sectPolicies.autoPlant)) }
        }
    }

    fun getHerbGardenReserveDisciples(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value?.elderSlots?.herbGardenReserveDisciples ?: emptyList()
    }

    fun getHerbGardenReserveDisciplesWithInfo(): List<DiscipleAggregate> {
        val reserveSlots = gameEngine.gameData.value?.elderSlots?.herbGardenReserveDisciples ?: emptyList()
        val reserveIds = reserveSlots.mapNotNull { it.discipleId }.toSet()
        return gameEngine.discipleAggregates.value
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

        return gameEngine.discipleAggregates.value
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

    private fun ElderSlots.getAllElderIds(): List<String> {
        return elderManagement.run { getAllElderIds() }
    }

    private fun ElderSlots.getAllDirectDiscipleIds(): List<String> {
        return elderManagement.run { getAllDirectDiscipleIds() }
    }
}
