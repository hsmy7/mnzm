package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.usecase.DisciplePositionQueryUseCase
import com.xianxia.sect.core.usecase.SectPolicyToggleUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SectViewModel @Inject constructor(
    private val gameEngine: GameEngine,
    private val disciplePositionQuery: DisciplePositionQueryUseCase,
    private val sectPolicyToggle: SectPolicyToggleUseCase
) : BaseViewModel() {
    
    companion object {
        private const val TAG = "SectViewModel"
        private const val REALM_VICE_SECT_MASTER = 4
        private const val REALM_ELDER = 6
    }

    val gameData: StateFlow<GameData> = gameEngine.gameData
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), gameEngine.gameData.value)

    /**
     * 转换后的弟子列表（使用新的 DiscipleAggregate 模型）
     * 用于 UI 层展示，避免使用废弃的 Disciple 类
     */
    val disciplesAggregates: StateFlow<List<DiscipleAggregate>> = gameEngine.discipleAggregates
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val disciples: StateFlow<List<DiscipleAggregate>> = disciplesAggregates
    
    fun getViceSectMaster(): DiscipleAggregate? {
        val viceSectMasterId = gameEngine.gameData.value?.elderSlots?.viceSectMaster
        return getElderDisciple(viceSectMasterId)
    }

    fun getElderDisciple(elderId: String?): DiscipleAggregate? {
        if (elderId == null) return null
        return disciples.value.find { it.id == elderId }
    }
    
    fun setViceSectMaster(discipleId: String) {
        viewModelScope.launch {
            try {
                val disciple = disciples.value.find { it.id == discipleId }
                if (disciple == null) {
                    showError(("弟子不存在"))
                    return@launch
                }
                if (disciple.realm > REALM_VICE_SECT_MASTER) {
                    showError(("副宗主需要达到炼虚境界"))
                    return@launch
                }

                val currentGameData = gameEngine.gameData.value
                val elderSlots = currentGameData.elderSlots
                val allElderIds = listOf(
                    elderSlots.viceSectMaster,
                    elderSlots.herbGardenElder,
                    elderSlots.alchemyElder,
                    elderSlots.forgeElder,
                    elderSlots.outerElder,
                    elderSlots.preachingElder,
                    elderSlots.lawEnforcementElder,
                    elderSlots.innerElder,
                    elderSlots.qingyunPreachingElder
                ).filterNotNull().filter { it.isNotBlank() }

                if (!allElderIds.contains(discipleId)) {
                    showError(("副宗主需要由长老担任"))
                    return@launch
                }

                gameEngine.updateGameData {
                    it.copy(elderSlots = it.elderSlots.copy(viceSectMaster = discipleId))
                }
                showSuccess(("副宗主任命成功"))
            } catch (e: Exception) {
                showError((e.message ?: "任命失败"))
            }
        }
    }

    fun removeViceSectMaster() {
        viewModelScope.launch {
            try {
                val currentGameData = gameEngine.gameData.value
                gameEngine.updateGameData {
                    it.copy(elderSlots = it.elderSlots.copy(viceSectMaster = ""))
                }
                showSuccess(("副宗主已卸任"))
            } catch (e: Exception) {
                showError((e.message ?: "卸任失败"))
            }
        }
    }
    
    fun assignElder(slotType: ElderSlotType, discipleId: String) {
        viewModelScope.launch {
            try {
                val disciple = disciples.value.find { it.id == discipleId }
                if (disciple == null) {
                    showError(("弟子不存在"))
                    return@launch
                }
                
                val minRealm = when (slotType) {
                    ElderSlotType.VICE_SECT_MASTER -> REALM_VICE_SECT_MASTER
                    else -> REALM_ELDER
                }
                if (disciple.realm > minRealm) {
                    val realmName = when (minRealm) {
                        REALM_VICE_SECT_MASTER -> "炼虚"
                        REALM_ELDER -> "元婴"
                        else -> "元婴"
                    }
                    val positionName = when (slotType) {
                        ElderSlotType.VICE_SECT_MASTER -> "副宗主"
                        else -> "长老"
                    }
                    showError(("${positionName}需要达到${realmName}境界"))
                    return@launch
                }
                
                val currentGameData = gameEngine.gameData.value
                val elderSlots = currentGameData.elderSlots
                
                val allElderIds = listOf(
                    elderSlots.viceSectMaster,
                    elderSlots.herbGardenElder,
                    elderSlots.alchemyElder,
                    elderSlots.forgeElder,
                    elderSlots.outerElder,
                    elderSlots.preachingElder,
                    elderSlots.lawEnforcementElder,
                    elderSlots.innerElder,
                    elderSlots.qingyunPreachingElder
                ).filter { it.isNotBlank() }
                val allDirectDiscipleIds = listOf(
                    elderSlots.herbGardenDisciples,
                    elderSlots.alchemyDisciples,
                    elderSlots.forgeDisciples,
                    elderSlots.preachingMasters,
                    elderSlots.lawEnforcementDisciples,
                    elderSlots.lawEnforcementReserveDisciples,
                    elderSlots.qingyunPreachingMasters,
                    elderSlots.spiritMineDeaconDisciples
                ).flatten().mapNotNull { it.discipleId }.filter { it.isNotBlank() }

                if (allElderIds.contains(discipleId)) {
                    showError(("该弟子已担任长老职位"))
                    return@launch
                }

                if (allDirectDiscipleIds.contains(discipleId)) {
                    showError(("该弟子已是其他长老的亲传弟子"))
                    return@launch
                }
                
                val newElderSlots = when (slotType) {
                    ElderSlotType.HERB_GARDEN -> elderSlots.copy(
                        herbGardenElder = discipleId,
                        herbGardenDisciples = emptyList()
                    )
                    ElderSlotType.ALCHEMY -> elderSlots.copy(
                        alchemyElder = discipleId,
                        alchemyDisciples = emptyList()
                    )
                    ElderSlotType.FORGE -> elderSlots.copy(
                        forgeElder = discipleId,
                        forgeDisciples = emptyList()
                    )
                    ElderSlotType.VICE_SECT_MASTER -> elderSlots.copy(
                        viceSectMaster = discipleId
                    )
                    ElderSlotType.OUTER_ELDER -> elderSlots.copy(
                        outerElder = discipleId
                    )
                    ElderSlotType.PREACHING -> elderSlots.copy(
                        preachingElder = discipleId,
                        preachingMasters = emptyList()
                    )
                    ElderSlotType.LAW_ENFORCEMENT -> elderSlots.copy(
                        lawEnforcementElder = discipleId,
                        lawEnforcementDisciples = emptyList()
                    )
                    ElderSlotType.INNER_ELDER -> elderSlots.copy(
                        innerElder = discipleId
                    )
                    ElderSlotType.CLOUD_PREACHING -> elderSlots.copy(
                        qingyunPreachingElder = discipleId,
                        qingyunPreachingMasters = emptyList()
                    )
                }
                gameEngine.updateElderSlots(newElderSlots)
                showSuccess(("长老任命成功"))
            } catch (e: Exception) {
                showError((e.message ?: "任命失败"))
            }
        }
    }
    
    fun removeElder(slotType: ElderSlotType) {
        viewModelScope.launch {
            try {
                val currentGameData = gameEngine.gameData.value
                val elderSlots = currentGameData.elderSlots
                val newElderSlots = when (slotType) {
                    ElderSlotType.HERB_GARDEN -> elderSlots.copy(
                        herbGardenElder = "",
                        herbGardenDisciples = emptyList()
                    )
                    ElderSlotType.ALCHEMY -> elderSlots.copy(
                        alchemyElder = "",
                        alchemyDisciples = emptyList()
                    )
                    ElderSlotType.FORGE -> elderSlots.copy(
                        forgeElder = "",
                        forgeDisciples = emptyList()
                    )
                    ElderSlotType.VICE_SECT_MASTER -> elderSlots.copy(
                        viceSectMaster = ""
                    )
                    ElderSlotType.OUTER_ELDER -> elderSlots.copy(
                        outerElder = ""
                    )
                    ElderSlotType.PREACHING -> elderSlots.copy(
                        preachingElder = "",
                        preachingMasters = emptyList()
                    )
                    ElderSlotType.LAW_ENFORCEMENT -> elderSlots.copy(
                        lawEnforcementElder = "",
                        lawEnforcementDisciples = emptyList()
                    )
                    ElderSlotType.INNER_ELDER -> elderSlots.copy(
                        innerElder = ""
                    )
                    ElderSlotType.CLOUD_PREACHING -> elderSlots.copy(
                        qingyunPreachingElder = "",
                        qingyunPreachingMasters = emptyList()
                    )
                }
                gameEngine.updateElderSlots(newElderSlots)
                showSuccess(("长老已卸任"))
            } catch (e: Exception) {
                showError((e.message ?: "卸任失败"))
            }
        }
    }
    
    fun assignDirectDisciple(elderSlotType: String, slotIndex: Int, discipleId: String) {
        viewModelScope.launch {
            try {
                val disciple = disciples.value.find { it.id == discipleId }
                if (disciple == null) {
                    showError(("弟子不存在"))
                    return@launch
                }
                
                val currentGameData = gameEngine.gameData.value
                val elderSlots = currentGameData.elderSlots
                
                val allElderIds = listOf(
                    elderSlots.herbGardenElder,
                    elderSlots.alchemyElder,
                    elderSlots.forgeElder
                )
                val allDirectDiscipleIds = listOf(
                    elderSlots.herbGardenDisciples,
                    elderSlots.alchemyDisciples,
                    elderSlots.forgeDisciples,
                    elderSlots.spiritMineDeaconDisciples
                ).flatten().mapNotNull { it.discipleId }

                if (allElderIds.contains(discipleId)) {
                    showError(("该弟子已担任长老职位"))
                    return@launch
                }

                if (allDirectDiscipleIds.contains(discipleId)) {
                    showError(("该弟子已是其他长老的亲传弟子"))
                    return@launch
                }

                gameEngine.assignDirectDisciple(
                    elderSlotType = elderSlotType,
                    slotIndex = slotIndex,
                    discipleId = discipleId,
                    discipleName = disciple.name,
                    discipleRealm = disciple.realmName,
                    discipleSpiritRootColor = disciple.spiritRoot.countColor
                )
                showSuccess(("亲传弟子任命成功"))
            } catch (e: Exception) {
                showError((e.message ?: "分配失败"))
            }
        }
    }
    
    fun removeDirectDisciple(elderSlotType: String, slotIndex: Int) {
        viewModelScope.launch {
            try {
                gameEngine.removeDirectDisciple(elderSlotType, slotIndex)
                showSuccess(("亲传弟子已移除"))
            } catch (e: Exception) {
                showError((e.message ?: "卸任失败"))
            }
        }
    }
    
    // TODO: 迁移到 DiscipleAggregate
    fun getOuterElder(): DiscipleAggregate? {
        val outerElderId = gameEngine.gameData.value?.elderSlots?.outerElder
        return getElderDisciple(outerElderId)
    }

    // TODO: 迁移到 DiscipleAggregate
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

    fun getLawEnforcementReserveDisciples(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value?.elderSlots?.lawEnforcementReserveDisciples ?: emptyList()
    }

    // TODO: 迁移到 DiscipleAggregate
    fun getLawEnforcementReserveDisciplesWithInfo(): List<DiscipleAggregate> {
        val reserveSlots = gameEngine.gameData.value?.elderSlots?.lawEnforcementReserveDisciples ?: emptyList()
        val reserveIds = reserveSlots.mapNotNull { it.discipleId }.toSet()
        return disciples.value
            .filter { it.id in reserveIds }
            .sortedByDescending { it.intelligence }
    }
    
    fun addReserveDisciple(discipleId: String) {
        viewModelScope.launch {
            try {
                val disciple = disciples.value.find { it.id == discipleId }
                if (disciple == null) {
                    showError(("弟子不存在"))
                    return@launch
                }
                
                if (disciplePositionQuery.hasDisciplePosition(discipleId)) {
                    val position = disciplePositionQuery.getDisciplePosition(discipleId)
                    showError(("该弟子已担任${position}，不可同时担任多个职务"))
                    return@launch
                }
                
                if (disciplePositionQuery.isReserveDisciple(discipleId)) {
                    showError(("该弟子已是其他部门的储备弟子"))
                    return@launch
                }
                
                val currentReserveDisciples = gameEngine.gameData.value.elderSlots.lawEnforcementReserveDisciples
                if (currentReserveDisciples.any { it.discipleId == discipleId }) {
                    showError(("该弟子已是储备弟子"))
                    return@launch
                }
                
                val newIndex = if (currentReserveDisciples.isEmpty()) 0 else currentReserveDisciples.maxOf { it.index } + 1
                val newSlot = DirectDiscipleSlot(
                    index = newIndex,
                    discipleId = discipleId,
                    discipleName = disciple.name,
                    discipleRealm = disciple.realmName,
                    discipleSpiritRootColor = disciple.spiritRoot.countColor
                )
                
                val updatedReserveDisciples = currentReserveDisciples + newSlot
                val updatedElderSlots = gameEngine.gameData.value.elderSlots.copy(
                    lawEnforcementReserveDisciples = updatedReserveDisciples
                )
                gameEngine.updateGameData { it.copy(elderSlots = updatedElderSlots) }
                gameEngine.syncAllDiscipleStatuses()
                showSuccess(("储备弟子添加成功"))
            } catch (e: Exception) {
                showError((e.message ?: "添加失败"))
            }
        }
    }
    
    fun removeReserveDisciple(discipleId: String) {
        viewModelScope.launch {
            try {
                val currentReserveDisciples = gameEngine.gameData.value.elderSlots.lawEnforcementReserveDisciples
                val updatedReserveDisciples = currentReserveDisciples.filter { it.discipleId != discipleId }
                val updatedElderSlots = gameEngine.gameData.value.elderSlots.copy(
                    lawEnforcementReserveDisciples = updatedReserveDisciples
                )
                gameEngine.updateGameData { it.copy(elderSlots = updatedElderSlots) }
                gameEngine.syncAllDiscipleStatuses()
                showSuccess(("储备弟子已移除"))
            } catch (e: Exception) {
                showError((e.message ?: "移除失败"))
            }
        }
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
    
    fun getSpiritMineBoostEffect(): Double = sectPolicyToggle.getSpiritMineBoostEffect()
    
    fun toggleEnhancedSecurity(): Boolean {
        val currentGameData = gameEngine.gameData.value ?: return false
        viewModelScope.launch {
            val result = sectPolicyToggle.toggleEnhancedSecurity()
            if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
        }
        return true
    }

    fun isEnhancedSecurityEnabled(): Boolean = sectPolicyToggle.isEnhancedSecurityEnabled()
    
    fun getEnhancedSecurityBaseBonus(): Double = sectPolicyToggle.getEnhancedSecurityBaseBonus()
    
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
        return sectPolicyToggle.getViceSectMasterIntelligenceBonus(viceSectMaster.intelligence)
    }
    
}
