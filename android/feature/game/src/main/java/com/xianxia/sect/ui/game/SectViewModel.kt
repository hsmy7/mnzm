package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.engine.*
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.usecase.ElderManagementUseCase
import com.xianxia.sect.core.usecase.SectPolicyToggleUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SectViewModel @Inject constructor(
    private val gameEngine: GameEngine,
    private val sectPolicyToggle: SectPolicyToggleUseCase,
    private val elderManagement: ElderManagementUseCase
) : BaseViewModel() {

    val gameData: StateFlow<GameData> = gameEngine.gameData
        .stateIn(viewModelScope, sharingStarted, gameEngine.gameData.value)

    /**
     * 转换后的弟子列表（使用新的 DiscipleAggregate 模型）
     * 用于 UI 层展示，避免使用废弃的 Disciple 类
     */
    val disciplesAggregates: StateFlow<List<DiscipleAggregate>> = gameEngine.discipleAggregates
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val disciples: StateFlow<List<DiscipleAggregate>> = disciplesAggregates
    
    fun getViceSectMaster(): DiscipleAggregate? {
        val viceSectMasterId = gameEngine.gameData.value?.elderSlots?.viceSectMaster
        return getElderDisciple(viceSectMasterId)
    }

    fun getElderDisciple(elderId: String?): DiscipleAggregate? {
        if (elderId == null) return null
        return gameEngine.getDiscipleAggregate(elderId)
    }
    
    fun setViceSectMaster(discipleId: String) =
        launchElderAction({ elderManagement.assignElder(ElderSlotType.VICE_SECT_MASTER, discipleId) }, "任命失败")

    fun removeViceSectMaster() =
        launchElderAction({ elderManagement.removeElder(ElderSlotType.VICE_SECT_MASTER) }, "卸任失败")

    fun assignElder(slotType: ElderSlotType, discipleId: String) =
        launchElderAction({ elderManagement.assignElder(slotType, discipleId) }, "任命失败")
    
    fun removeElder(slotType: ElderSlotType) =
        launchElderAction({ elderManagement.removeElder(slotType) }, "卸任失败")
    
    fun assignDirectDisciple(elderSlotType: String, slotIndex: Int, discipleId: String) =
        launchElderAction({ elderManagement.assignDirectDisciple(elderSlotType, slotIndex, discipleId) }, "分配失败")
    
    fun removeDirectDisciple(elderSlotType: String, slotIndex: Int) =
        launchElderAction({ elderManagement.removeDirectDisciple(elderSlotType, slotIndex) }, "卸任失败")
    
    fun getOuterElder(): DiscipleAggregate? {
        val outerElderId = gameEngine.gameData.value?.elderSlots?.outerElder
        return getElderDisciple(outerElderId)
    }

    fun getPreachingElder(): DiscipleAggregate? {
        val preachingElderId = gameEngine.gameData.value?.elderSlots?.preachingElder
        return getElderDisciple(preachingElderId)
    }

    fun getPreachingMasters(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value?.elderSlots?.preachingMasters ?: emptyList()
    }

    fun getLawEnforcementElder(): DiscipleAggregate? {
        val elderId = gameEngine.gameData.value?.elderSlots?.lawEnforcementElder
        return getElderDisciple(elderId)
    }

    fun getLawEnforcementDisciples(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value?.elderSlots?.lawEnforcementDisciples ?: emptyList()
    }

    fun toggleSpiritMineBoost(): Boolean {
        val currentGameData = gameEngine.gameData.value ?: return false
        viewModelScope.launch {
            val result = sectPolicyToggle.toggleSpiritMineBoost()
            if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
        }
        return true
    }

    fun isSpiritMineBoostEnabled(): Boolean = sectPolicyToggle.isSpiritMineBoostEnabled()

    fun toggleEnhancedSecurity(): Boolean {
        val currentGameData = gameEngine.gameData.value ?: return false
        viewModelScope.launch {
            val result = sectPolicyToggle.toggleEnhancedSecurity()
            if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
        }
        return true
    }

    fun isEnhancedSecurityEnabled(): Boolean = sectPolicyToggle.isEnhancedSecurityEnabled()

    fun toggleAlchemyIncentive(): Boolean {
        val currentGameData = gameEngine.gameData.value ?: return false
        viewModelScope.launch {
            val result = sectPolicyToggle.toggleAlchemyIncentive()
            if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
        }
        return true
    }

    fun isAlchemyIncentiveEnabled(): Boolean = sectPolicyToggle.isAlchemyIncentiveEnabled()

    fun toggleForgeIncentive(): Boolean {
        val currentGameData = gameEngine.gameData.value ?: return false
        viewModelScope.launch {
            val result = sectPolicyToggle.toggleForgeIncentive()
            if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
        }
        return true
    }

    fun isForgeIncentiveEnabled(): Boolean = sectPolicyToggle.isForgeIncentiveEnabled()

    fun toggleHerbCultivation(): Boolean {
        val currentGameData = gameEngine.gameData.value ?: return false
        viewModelScope.launch {
            val result = sectPolicyToggle.toggleHerbCultivation()
            if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
        }
        return true
    }

    fun isHerbCultivationEnabled(): Boolean = sectPolicyToggle.isHerbCultivationEnabled()

    fun toggleCultivationSubsidy(): Boolean {
        val currentGameData = gameEngine.gameData.value ?: return false
        viewModelScope.launch {
            val result = sectPolicyToggle.toggleCultivationSubsidy()
            if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
        }
        return true
    }

    fun isCultivationSubsidyEnabled(): Boolean = sectPolicyToggle.isCultivationSubsidyEnabled()

    fun toggleManualResearch(): Boolean {
        val currentGameData = gameEngine.gameData.value ?: return false
        viewModelScope.launch {
            val result = sectPolicyToggle.toggleManualResearch()
            if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
        }
        return true
    }

    fun isManualResearchEnabled(): Boolean = sectPolicyToggle.isManualResearchEnabled()
    
    fun getViceSectMasterIntelligenceBonus(): Double {
        val viceSectMaster = getViceSectMaster() ?: return 0.0
        return sectPolicyToggle.getViceSectMasterIntelligenceBonus(viceSectMaster)
    }

    // ══════════════════════════════════════════════
    // 新增政策开关方法
    // ══════════════════════════════════════════════

    fun toggleOpenRecruitment(): Boolean {
        viewModelScope.launch {
            val result = sectPolicyToggle.toggleOpenRecruitment()
            if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
        }
        return true
    }
    fun isOpenRecruitmentEnabled(): Boolean = sectPolicyToggle.isOpenRecruitmentEnabled()

    fun toggleAsceticTraining(): Boolean {
        viewModelScope.launch {
            val result = sectPolicyToggle.toggleAsceticTraining()
            if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
        }
        return true
    }
    fun isAsceticTrainingEnabled(): Boolean = sectPolicyToggle.isAsceticTrainingEnabled()

    fun toggleCurfew(): Boolean {
        viewModelScope.launch {
            val result = sectPolicyToggle.toggleCurfew()
            if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
        }
        return true
    }
    fun isCurfewEnabled(): Boolean = sectPolicyToggle.isCurfewEnabled()

    fun toggleRewardPunish(): Boolean {
        viewModelScope.launch {
            val result = sectPolicyToggle.toggleRewardPunish()
            if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
        }
        return true
    }
    fun isRewardPunishEnabled(): Boolean = sectPolicyToggle.isRewardPunishEnabled()

    fun toggleStrictTraining(): Boolean {
        viewModelScope.launch {
            val result = sectPolicyToggle.toggleStrictTraining()
            if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
        }
        return true
    }
    fun isStrictTrainingEnabled(): Boolean = sectPolicyToggle.isStrictTrainingEnabled()

    fun toggleRelaxedMgmt(): Boolean {
        viewModelScope.launch {
            val result = sectPolicyToggle.toggleRelaxedMgmt()
            if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
        }
        return true
    }
    fun isRelaxedMgmtEnabled(): Boolean = sectPolicyToggle.isRelaxedMgmtEnabled()

    fun toggleSpiritSpring(): Boolean {
        viewModelScope.launch {
            val result = sectPolicyToggle.toggleSpiritSpring()
            if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
        }
        return true
    }
    fun isSpiritSpringEnabled(): Boolean = sectPolicyToggle.isSpiritSpringEnabled()

    fun toggleFrugality(): Boolean {
        viewModelScope.launch {
            val result = sectPolicyToggle.toggleFrugality()
            if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
        }
        return true
    }
    fun isFrugalityEnabled(): Boolean = sectPolicyToggle.isFrugalityEnabled()

    fun toggleMoralEducation(): Boolean {
        viewModelScope.launch {
            val result = sectPolicyToggle.toggleMoralEducation()
            if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
        }
        return true
    }
    fun isMoralEducationEnabled(): Boolean = sectPolicyToggle.isMoralEducationEnabled()

    fun toggleBenevolentGovernance(): Boolean {
        viewModelScope.launch {
            val result = sectPolicyToggle.toggleBenevolentGovernance()
            if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
        }
        return true
    }
    fun isBenevolentGovernanceEnabled(): Boolean = sectPolicyToggle.isBenevolentGovernanceEnabled()

}
