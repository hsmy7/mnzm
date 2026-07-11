package com.xianxia.sect.ui.game

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.engine.*
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.usecase.ElderManagementUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HerbGardenViewModel @Inject constructor(
    private val gameEngine: GameEngine,
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
                    if (startBestSeedForSlot(slot.slotIndex)) plantedCount++ else break
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

    fun isAutoEnabled(slotIndex: Int): Boolean {
        return gameEngine.productionSlots.value
            .find { it.buildingType == BuildingType.HERB_GARDEN && it.slotIndex == slotIndex }
            ?.autoRestartEnabled ?: false
    }

    fun cancelPlantSlot(slotIndex: Int) {
        viewModelScope.launch {
            gameEngine.clearPlantSlot(slotIndex)
        }
    }

    fun toggleAuto(slotIndex: Int) {
        val currentValue = isAutoEnabled(slotIndex)
        val newValue = !currentValue
        viewModelScope.launch {
            gameEngine.toggleAutoRestart(BuildingType.HERB_GARDEN, slotIndex)

            if (newValue) {
                try {
                    val slot = gameEngine.productionSlots.value.find {
                        it.buildingType == BuildingType.HERB_GARDEN && it.slotIndex == slotIndex
                    } ?: return@launch
                    if (slot.status == ProductionSlotStatus.IDLE) {
                        startBestSeedForSlot(slot.slotIndex)
                    }
                } catch (_: Exception) { Log.w("HerbGardenViewModel", "Auto-plant failed for slot $slotIndex") }
            }
        }
    }

    private suspend fun startBestSeedForSlot(slotIndex: Int): Boolean {
        val currentSeeds = gameEngine.getCurrentSeeds()
            .filter { it.quantity > 0 }
            .sortedByDescending { it.rarity }
        val seedToPlant = currentSeeds.firstOrNull() ?: return false
        gameEngine.startManualPlanting(slotIndex, seedToPlant.id)
        return true
    }

}
