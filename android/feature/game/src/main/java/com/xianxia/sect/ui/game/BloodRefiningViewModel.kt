package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.engine.*
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.BloodRefinementProgress
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
) : BaseViewModel() {

    private val _uiState = MutableStateFlow(BloodRefiningUiState())
    val uiState: StateFlow<BloodRefiningUiState> = _uiState.asStateFlow()

    companion object {
        const val REQUIRED_MATERIAL_COUNT = 200
        const val REQUIRED_SPIRIT_STONES = 1_000_000L
    }

    fun selectMaterial(material: BeastMaterialDatabase.BeastMaterial?, quantity: Int) {
        _uiState.update { it.copy(
            selectedMaterial = material,
            selectedMaterialQuantity = quantity
        ) }
        updateCanStartRefine()
    }

    fun selectDisciple(disciple: DiscipleAggregate?) {
        _uiState.update { it.copy(selectedDisciple = disciple) }
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
            _uiState.update { it.copy(
                isRefining = true,
                currentProgress = progress,
                remainingMonths = remaining
            ) }
        } else {
            _uiState.update { it.copy(
                isRefining = false,
                currentProgress = null,
                remainingMonths = 0
            ) }
        }
    }

    fun startRefine(buildingInstanceId: String) {
        val state = _uiState.value
        val material = state.selectedMaterial ?: return
        val disciple = state.selectedDisciple ?: return
        val data = gameEngine.gameData.value ?: return

        if (data.spiritStones < REQUIRED_SPIRIT_STONES) {
            showError("灵石不足100万")
            return
        }

        if (state.selectedMaterialQuantity < REQUIRED_MATERIAL_COUNT) {
            showError("材料不足200个")
            return
        }

        val bloodType = BeastMaterialDatabase.getBloodTypeFromMaterialId(material.id) ?: return
        val selectedStat = DiscipleStatCalculator.randomBloodRefineStat(bloodType)
        val bonusPercent = BeastMaterialDatabase.getTierPercentage(material.tier)
        val durationMonths = BeastMaterialDatabase.getTierDuration(material.tier)

        viewModelScope.launch {
            // 构造 BloodRefinementProgress
            val progress = BloodRefinementProgress(
                discipleId = disciple.id,
                discipleName = disciple.name,
                materialId = material.id,
                materialName = material.name,
                startYear = 0,  // 将在引擎侧基于当前 gameData 填充
                startMonth = 0,
                durationMonths = durationMonths,
                selectedStat = selectedStat,
                bonusPercent = bonusPercent
            )

            // 原子化操作：灵石扣除 + 材料消耗 + 进度写入 + 弟子状态更新
            // 在单次 stateStore.update 事务中完成，失败时整体回滚
            val result = gameEngine.startBloodRefinementAtomic(
                materialName = material.name,
                materialRarity = material.rarity,
                materialCount = REQUIRED_MATERIAL_COUNT,
                buildingInstanceId = buildingInstanceId,
                requiredSpiritStones = REQUIRED_SPIRIT_STONES,
                progress = progress
            )

            when (result) {
                is BloodRefinementStartResult.Success -> { /* 继续 */ }
                is BloodRefinementStartResult.InsufficientStones ->
                    { showError("灵石不足，洗炼失败"); return@launch }
                is BloodRefinementStartResult.InsufficientMaterials ->
                    { showError("兽血材料不足，洗炼失败"); return@launch }
                is BloodRefinementStartResult.Error ->
                    { showError("资源不足，洗炼失败"); return@launch }
            }

            // 更新UI状态
            val updatedData = gameEngine.gameData.value
            val savedProgress = updatedData?.activeBloodRefinements?.get(buildingInstanceId)
            if (savedProgress != null) {
                _uiState.update { it.copy(
                    isRefining = true,
                    currentProgress = savedProgress,
                    remainingMonths = durationMonths,
                    errorMessage = null
                ) }
            }
        }
    }

    fun cancelRefine(buildingInstanceId: String) {
        viewModelScope.launch {
            gameEngine.updateGameData { gameData ->
                gameData.copy(
                    activeBloodRefinements = gameData.activeBloodRefinements - buildingInstanceId
                )
            }
            _uiState.update { it.copy(
                isRefining = false,
                currentProgress = null,
                remainingMonths = 0,
                selectedDisciple = null
            ) }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun updateCanStartRefine() {
        val state = _uiState.value
        val data = gameEngine.gameData.value
        val canStart = state.selectedMaterial != null &&
                state.selectedMaterialQuantity >= REQUIRED_MATERIAL_COUNT &&
                state.selectedDisciple != null &&
                data.spiritStones >= REQUIRED_SPIRIT_STONES &&
                !state.isRefining
        _uiState.update { it.copy(canStartRefine = canStart) }
    }

    fun refreshCanStartRefine() {
        updateCanStartRefine()
    }
}
