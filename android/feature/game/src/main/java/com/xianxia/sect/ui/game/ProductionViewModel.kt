package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.assignDirectDisciple
import com.xianxia.sect.core.engine.assignDiscipleToLibrarySlot
import com.xianxia.sect.core.engine.assignWarehouseGarrisonAtomic
import com.xianxia.sect.core.engine.getDiscipleAggregate
import com.xianxia.sect.core.engine.releaseDiscipleAssignment
import com.xianxia.sect.core.engine.removeDirectDisciple
import com.xianxia.sect.core.engine.removeDiscipleFromLibrarySlot
import com.xianxia.sect.core.engine.updateGameDataAndSync
import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.ElderSlotType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.usecase.ElderManagementUseCase
import com.xianxia.sect.core.usecase.SectPolicyToggleUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject



@HiltViewModel
class ProductionViewModel @Inject constructor(
    private val gameEngine: GameEngine,
    private val sectPolicyToggle: SectPolicyToggleUseCase,
    private val elderManagement: ElderManagementUseCase
) : BaseViewModel() {

    companion object {
        private const val TAG = "ProductionViewModel"
    }

    val productionSlots: StateFlow<List<ProductionSlot>> = gameEngine.productionSlots
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val discipleAggregates: StateFlow<List<DiscipleAggregate>> = gameEngine.discipleAggregates
        .stateIn(viewModelScope, sharingStarted, emptyList())

    fun getElderDisciple(elderId: String?): DiscipleAggregate? {
        if (elderId == null) return null
        return gameEngine.getDiscipleAggregate(elderId)
    }

    fun assignElder(slotType: ElderSlotType, discipleId: String) =
        launchElderAction({ elderManagement.assignElder(slotType, discipleId) }, "任命失败")

    fun removeElder(slotType: ElderSlotType) =
        launchElderAction({ elderManagement.removeElder(slotType) }, "卸任失败")

    fun assignDirectDisciple(elderSlotType: String, slotIndex: Int, discipleId: String) =
        launchElderAction({ elderManagement.assignDirectDisciple(elderSlotType, slotIndex, discipleId) }, "分配失败")

    fun removeDirectDisciple(elderSlotType: String, slotIndex: Int) =
        launchElderAction({ elderManagement.removeDirectDisciple(elderSlotType, slotIndex) }, "卸任失败")

    fun assignWarehouseGarrison(
        buildingInstanceId: String,
        discipleId: String,
        discipleName: String,
        sectId: String
    ) {
        // 统一走引擎原子方法：事务内清理旧槽位 + gate 登记，防同一弟子多槽位
        // （回归：此前 ViewModel 直写 GameData，无互斥清理）
        gameEngine.assignWarehouseGarrisonAtomic(
            buildingInstanceId, discipleId, discipleName, sectId
        )
    }

    suspend fun removeWarehouseGarrison(buildingInstanceId: String) {
        val currentDiscipleId = gameEngine.gameDataSnapshot.warehouseGarrisons
            .find { it.buildingInstanceId == buildingInstanceId }?.discipleId.orEmpty()
        gameEngine.updateGameDataAndSync { data ->
            data.copy(warehouseGarrisons = data.warehouseGarrisons.filter {
                it.buildingInstanceId != buildingInstanceId
            })
        }
        if (currentDiscipleId.isNotEmpty()) {
            gameEngine.releaseDiscipleAssignment(currentDiscipleId)
        }
    }

    fun toggleSpiritMineBoost(): Boolean {
        val currentGameData = gameEngine.gameDataSnapshot ?: return false
        viewModelScope.launch {
            val result = sectPolicyToggle.toggleSpiritMineBoost()
            if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
        }
        return true
    }

    fun isSpiritMineBoostEnabled(): Boolean = sectPolicyToggle.isSpiritMineBoostEnabled()

    fun toggleEnhancedSecurity(): Boolean {
        val currentGameData = gameEngine.gameDataSnapshot ?: return false
        viewModelScope.launch {
            val result = sectPolicyToggle.toggleEnhancedSecurity()
            if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
        }
        return true
    }

    fun isEnhancedSecurityEnabled(): Boolean = sectPolicyToggle.isEnhancedSecurityEnabled()

    fun toggleAlchemyIncentive(): Boolean {
        val currentGameData = gameEngine.gameDataSnapshot ?: return false
        viewModelScope.launch {
            val result = sectPolicyToggle.toggleAlchemyIncentive()
            if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
        }
        return true
    }

    fun isAlchemyIncentiveEnabled(): Boolean = sectPolicyToggle.isAlchemyIncentiveEnabled()

    fun toggleForgeIncentive(): Boolean {
        val currentGameData = gameEngine.gameDataSnapshot ?: return false
        viewModelScope.launch {
            val result = sectPolicyToggle.toggleForgeIncentive()
            if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
        }
        return true
    }

    fun isForgeIncentiveEnabled(): Boolean = sectPolicyToggle.isForgeIncentiveEnabled()

    fun toggleHerbCultivation(): Boolean {
        val currentGameData = gameEngine.gameDataSnapshot ?: return false
        viewModelScope.launch {
            val result = sectPolicyToggle.toggleHerbCultivation()
            if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
        }
        return true
    }

    fun isHerbCultivationEnabled(): Boolean = sectPolicyToggle.isHerbCultivationEnabled()

    fun toggleCultivationSubsidy(): Boolean {
        val currentGameData = gameEngine.gameDataSnapshot ?: return false
        viewModelScope.launch {
            val result = sectPolicyToggle.toggleCultivationSubsidy()
            if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
        }
        return true
    }

    fun isCultivationSubsidyEnabled(): Boolean = sectPolicyToggle.isCultivationSubsidyEnabled()

    fun toggleManualResearch(): Boolean {
        val currentGameData = gameEngine.gameDataSnapshot ?: return false
        viewModelScope.launch {
            val result = sectPolicyToggle.toggleManualResearch()
            if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
        }
        return true
    }

    fun isManualResearchEnabled(): Boolean = sectPolicyToggle.isManualResearchEnabled()

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

    fun assignDiscipleToLibrarySlot(slotIndex: Int, discipleId: String, discipleName: String) {
        viewModelScope.launch {
            try {
                gameEngine.assignDiscipleToLibrarySlot(slotIndex, discipleId, discipleName)
            } catch (e: Exception) {
                showError(e.message ?: "分配失败")
            }
        }
    }

    fun removeDiscipleFromLibrarySlot(slotIndex: Int) {
        viewModelScope.launch {
            try {
                gameEngine.removeDiscipleFromLibrarySlot(slotIndex)
            } catch (e: Exception) {
                showError(e.message ?: "卸任失败")
            }
        }
    }

    fun setViceSectMaster(discipleId: String) =
        launchElderAction({ elderManagement.assignElder(ElderSlotType.VICE_SECT_MASTER, discipleId) }, "任命副宗主失败")

    fun removeViceSectMaster() =
        launchElderAction({ elderManagement.removeElder(ElderSlotType.VICE_SECT_MASTER) }, "卸任副宗主失败")

    fun getViceSectMaster(): DiscipleAggregate? {
        val viceSectMasterId = gameEngine.gameDataSnapshot?.elderSlots?.viceSectMaster
        return getElderDisciple(viceSectMasterId)
    }

    fun getViceSectMasterIntelligenceBonus(): Double {
        val viceSectMaster = getViceSectMaster() ?: return 0.0
        return sectPolicyToggle.getViceSectMasterIntelligenceBonus(viceSectMaster)
    }

    fun getOuterElder(): DiscipleAggregate? {
        val outerElderId = gameEngine.gameDataSnapshot?.elderSlots?.outerElder
        return getElderDisciple(outerElderId)
    }

    fun getPreachingElder(): DiscipleAggregate? {
        val preachingElderId = gameEngine.gameDataSnapshot?.elderSlots?.preachingElder
        return getElderDisciple(preachingElderId)
    }

    fun getPreachingMasters(): List<DirectDiscipleSlot> {
        return gameEngine.gameDataSnapshot?.elderSlots?.preachingMasters ?: emptyList()
    }

    fun getLawEnforcementElder(): DiscipleAggregate? {
        val elderId = gameEngine.gameDataSnapshot?.elderSlots?.lawEnforcementElder
        return getElderDisciple(elderId)
    }

    fun getLawEnforcementDisciples(): List<DirectDiscipleSlot> {
        return gameEngine.gameDataSnapshot?.elderSlots?.lawEnforcementDisciples ?: emptyList()
    }

    fun getAvailableDisciplesForOuterElder(): List<DiscipleAggregate> {
        return gameEngine.discipleAggregatesSnapshot
            .eligibleElderCandidates()
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForPreachingElder(): List<DiscipleAggregate> {
        return gameEngine.discipleAggregatesSnapshot
            .eligibleElderCandidates()
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForPreachingMaster(): List<DiscipleAggregate> {
        return gameEngine.discipleAggregatesSnapshot
            .eligibleElderCandidates()
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getInnerElder(): DiscipleAggregate? {
        val innerElderId = gameEngine.gameDataSnapshot?.elderSlots?.innerElder
        return getElderDisciple(innerElderId)
    }

    fun getQingyunPreachingElder(): DiscipleAggregate? {
        val preachingElderId = gameEngine.gameDataSnapshot?.elderSlots?.qingyunPreachingElder
        return getElderDisciple(preachingElderId)
    }

    fun getQingyunPreachingMasters(): List<DirectDiscipleSlot> {
        return gameEngine.gameDataSnapshot?.elderSlots?.qingyunPreachingMasters ?: emptyList()
    }

    fun getAvailableDisciplesForInnerElder(): List<DiscipleAggregate> {
        return gameEngine.discipleAggregatesSnapshot
            .eligibleElderCandidates()
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForQingyunPreachingElder(): List<DiscipleAggregate> {
        return gameEngine.discipleAggregatesSnapshot
            .eligibleElderCandidates()
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForQingyunPreachingMaster(): List<DiscipleAggregate> {
        return gameEngine.discipleAggregatesSnapshot
            .eligibleElderCandidates()
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }
}
