package com.xianxia.sect.ui.game

import com.xianxia.sect.core.engine.BloodRefinementStartResult
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.cancelBloodRefinement
import com.xianxia.sect.core.engine.confirmAssignDisciple
import com.xianxia.sect.core.engine.getAllDiscipleAggregates
import com.xianxia.sect.core.engine.releaseDiscipleAssignment
import com.xianxia.sect.core.engine.startBloodRefinementAtomic
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.BloodRefinementProgress
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.SlotCategory
import com.xianxia.sect.core.model.SlotRef
import com.xianxia.sect.core.model.spiritStones
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.util.GameRngManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    private val gameEngine: GameEngine,
    private val rngManager: GameRngManager
) : BaseViewModel() {

    private val _uiState = MutableStateFlow(BloodRefiningUiState())
    val uiState: StateFlow<BloodRefiningUiState> = _uiState.asStateFlow()

    companion object {
        const val REQUIRED_MATERIAL_COUNT = 100
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
            // 血炼进行中：从引擎聚合数据找回血炼中的弟子填充槽位——
            // 跨会话重开血炼池时 selectedDisciple 可能为 null，槽位会显示空"+"，
            // 看不到血炼中的弟子、也没有"卸任/更换"入口（预存问题修复）
            val refiningDisciple = gameEngine.getAllDiscipleAggregates()
                .firstOrNull { it.id == progress.discipleId }
            _uiState.update { it.copy(
                isRefining = true,
                currentProgress = progress,
                remainingMonths = remaining,
                selectedDisciple = refiningDisciple ?: it.selectedDisciple
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
            showError("材料不足${REQUIRED_MATERIAL_COUNT}个")
            return
        }

        val bloodType = BeastMaterialDatabase.getBloodTypeFromMaterialId(material.id) ?: return
        val selectedStat = DiscipleStatCalculator.randomBloodRefineStat(bloodType, rngManager)
        val bonusPercent = BeastMaterialDatabase.getTierPercentage(material.tier)
        val durationMonths = BeastMaterialDatabase.getTierDuration(material.tier)

        gameEngine.launchOnEngine {
            // 构造 BloodRefinementProgress
            val progress = buildBloodRefinementProgress(
                disciple = disciple,
                material = material,
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

            handleStartRefineResult(
                result = result,
                buildingInstanceId = buildingInstanceId,
                durationMonths = durationMonths,
                disciple = disciple
            )
        }
    }

    /** 血炼进度构造（startRefine 拆分）：引擎事务外组装进度对象（startYear/startMonth 由引擎侧填充） */
    private fun buildBloodRefinementProgress(
        disciple: DiscipleAggregate,
        material: BeastMaterialDatabase.BeastMaterial,
        durationMonths: Int,
        selectedStat: String,
        bonusPercent: Double
    ): BloodRefinementProgress = BloodRefinementProgress(
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

    /** 血炼启动结果处理（startRefine 拆分）：失败提示 / 成功登记进度与血炼分配 */
    private fun handleStartRefineResult(
        result: BloodRefinementStartResult,
        buildingInstanceId: String,
        durationMonths: Int,
        disciple: DiscipleAggregate
    ) {
        if (result !is BloodRefinementStartResult.Success) {
            val msg = when (result) {
                is BloodRefinementStartResult.InsufficientStones -> "灵石不足，洗炼失败"
                is BloodRefinementStartResult.InsufficientMaterials -> "兽血材料不足，洗炼失败"
                is BloodRefinementStartResult.Error -> "资源不足，洗炼失败"
                else -> null
            }
            if (msg != null) { showError(msg) }
        } else {
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

            // 登记血炼分配
            val slotRef = SlotRef(
                category = SlotCategory.BLOOD_REFINEMENT,
                slotType = buildingInstanceId,
                slotId = "blood_$buildingInstanceId"
            )
            gameEngine.confirmAssignDisciple(disciple.id, slotRef)
        }
    }

    fun cancelRefine(buildingInstanceId: String) {
        val state = _uiState.value
        val progress = state.currentProgress ?: return
        gameEngine.launchOnEngine {
            gameEngine.cancelBloodRefinement(
                buildingInstanceId = buildingInstanceId,
                discipleId = progress.discipleId
            )
            gameEngine.releaseDiscipleAssignment(progress.discipleId)
        }
        _uiState.update { it.copy(
            isRefining = false,
            currentProgress = null,
            remainingMonths = 0,
            selectedDisciple = null
        ) }
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
