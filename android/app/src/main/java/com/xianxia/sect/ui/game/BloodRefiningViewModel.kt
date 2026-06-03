package com.xianxia.sect.ui.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.BloodRefinementProgress
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BloodRefiningUiState(
    val selectedMaterial: BeastMaterialDatabase.BeastMaterial? = null,
    val selectedMaterialQuantity: Int = 0,
    val selectedDisciple: DiscipleAggregate? = null,
    val isRefining: Boolean = false,
    val currentProgress: BloodRefinementProgress? = null,
    val remainingMonths: Int = 0,
    val canStartRefine: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class BloodRefiningViewModel @Inject constructor(
    private val gameEngine: GameEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(BloodRefiningUiState())
    val uiState: StateFlow<BloodRefiningUiState> = _uiState.asStateFlow()

    companion object {
        const val REQUIRED_MATERIAL_COUNT = 200
        const val REQUIRED_SPIRIT_STONES = 1_000_000L
    }

    fun selectMaterial(material: BeastMaterialDatabase.BeastMaterial?, quantity: Int) {
        _uiState.value = _uiState.value.copy(
            selectedMaterial = material,
            selectedMaterialQuantity = quantity
        )
        updateCanStartRefine()
    }

    fun selectDisciple(disciple: DiscipleAggregate?) {
        _uiState.value = _uiState.value.copy(selectedDisciple = disciple)
        updateCanStartRefine()
    }

    fun loadActiveProgress(buildingInstanceId: String) {
        val data = gameEngine.gameData.value ?: return
        val progress = data.activeBloodRefinements[buildingInstanceId]
        if (progress != null) {
            val currentYear = data.gameYear
            val currentMonth = data.gameMonth
            val remaining = com.xianxia.sect.core.util.TimeProgressUtil.calculateRemainingMonths(
                progress.startYear, progress.startMonth,
                progress.durationMonths, currentYear, currentMonth
            )
            _uiState.value = _uiState.value.copy(
                isRefining = true,
                currentProgress = progress,
                remainingMonths = remaining
            )
        } else {
            _uiState.value = _uiState.value.copy(
                isRefining = false,
                currentProgress = null,
                remainingMonths = 0
            )
        }
    }

    fun startRefine(buildingInstanceId: String) {
        val state = _uiState.value
        val material = state.selectedMaterial ?: return
        val disciple = state.selectedDisciple ?: return
        val data = gameEngine.gameData.value ?: return

        if (data.spiritStones < REQUIRED_SPIRIT_STONES) {
            _uiState.value = state.copy(errorMessage = "灵石不足100万")
            return
        }

        if (state.selectedMaterialQuantity < REQUIRED_MATERIAL_COUNT) {
            _uiState.value = state.copy(errorMessage = "材料不足200个")
            return
        }

        val bloodType = BeastMaterialDatabase.getBloodTypeFromMaterialId(material.id) ?: return
        val selectedStat = DiscipleStatCalculator.randomBloodRefineStat(bloodType)
        val bonusPercent = BeastMaterialDatabase.getTierPercentage(material.tier)
        val durationMonths = BeastMaterialDatabase.getTierDuration(material.tier)

        viewModelScope.launch {
            // 1. 扣除灵石 + 记录进度
            gameEngine.updateGameData { gameData ->
                if (gameData.spiritStones < REQUIRED_SPIRIT_STONES) return@updateGameData gameData

                val progress = BloodRefinementProgress(
                    discipleId = disciple.id,
                    discipleName = disciple.name,
                    materialId = material.id,
                    materialName = material.name,
                    startYear = gameData.gameYear,
                    startMonth = gameData.gameMonth,
                    durationMonths = durationMonths,
                    selectedStat = selectedStat,
                    bonusPercent = bonusPercent
                )

                gameData.copy(
                    spiritStones = gameData.spiritStones - REQUIRED_SPIRIT_STONES,
                    activeBloodRefinements = gameData.activeBloodRefinements + (buildingInstanceId to progress)
                )
            }

            // 2. 扣除材料
            gameEngine.consumeMaterialByName(material.name, material.rarity, REQUIRED_MATERIAL_COUNT)

            // 3. 更新弟子状态
            gameEngine.updateDisciple(disciple.id) { d ->
                d.copy(
                    status = DiscipleStatus.IDLE,
                    statusData = mapOf("bloodRefining" to "true", "buildingId" to buildingInstanceId)
                )
            }

            // 4. 更新UI状态
            val updatedData = gameEngine.gameData.value
            val savedProgress = updatedData?.activeBloodRefinements?.get(buildingInstanceId)
            if (savedProgress != null) {
                _uiState.value = _uiState.value.copy(
                    isRefining = true,
                    currentProgress = savedProgress,
                    remainingMonths = durationMonths,
                    errorMessage = null
                )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    private fun updateCanStartRefine() {
        val state = _uiState.value
        val data = gameEngine.gameData.value
        val canStart = state.selectedMaterial != null &&
                state.selectedMaterialQuantity >= REQUIRED_MATERIAL_COUNT &&
                state.selectedDisciple != null &&
                data.spiritStones >= REQUIRED_SPIRIT_STONES &&
                !state.isRefining
        _uiState.value = state.copy(canStartRefine = canStart)
    }

    fun refreshCanStartRefine() {
        updateCanStartRefine()
    }
}
