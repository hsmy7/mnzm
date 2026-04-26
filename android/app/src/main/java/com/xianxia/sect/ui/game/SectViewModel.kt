package com.xianxia.sect.ui.game

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SectViewModel @Inject constructor(
    private val gameEngine: GameEngine
) : BaseViewModel() {
    
    companion object {
        private const val TAG = "SectViewModel"
        private const val REALM_VICE_SECT_MASTER = 4
        private const val REALM_ELDER = 6
    }

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _successMessage = MutableStateFlow<String?>(null)
    val successMessage: StateFlow<String?> = _successMessage.asStateFlow()

    fun clearErrorMessage() { _errorMessage.value = null }
    fun clearSuccessMessage() { _successMessage.value = null }
    
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
                    _errorMessage.value = "弟子不存在"
                    return@launch
                }
                if (disciple.realm > REALM_VICE_SECT_MASTER) {
                    _errorMessage.value = "副宗主需要达到炼虚境界"
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
                    _errorMessage.value = "副宗主需要由长老担任"
                    return@launch
                }

                gameEngine.updateGameData {
                    it.copy(elderSlots = it.elderSlots.copy(viceSectMaster = discipleId))
                }
                _successMessage.value = "副宗主任命成功"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "任命失败"
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
                _successMessage.value = "副宗主已卸任"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "卸任失败"
            }
        }
    }
    
    fun assignElder(slotType: ElderSlotType, discipleId: String) {
        viewModelScope.launch {
            try {
                val disciple = disciples.value.find { it.id == discipleId }
                if (disciple == null) {
                    _errorMessage.value = "弟子不存在"
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
                    _errorMessage.value = "${positionName}需要达到${realmName}境界"
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
                    _errorMessage.value = "该弟子已担任长老职位"
                    return@launch
                }

                if (allDirectDiscipleIds.contains(discipleId)) {
                    _errorMessage.value = "该弟子已是其他长老的亲传弟子"
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
                _successMessage.value = "长老任命成功"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "任命失败"
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
                _successMessage.value = "长老已卸任"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "卸任失败"
            }
        }
    }
    
    fun assignDirectDisciple(elderSlotType: String, slotIndex: Int, discipleId: String) {
        viewModelScope.launch {
            try {
                val disciple = disciples.value.find { it.id == discipleId }
                if (disciple == null) {
                    _errorMessage.value = "弟子不存在"
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
                    _errorMessage.value = "该弟子已担任长老职位"
                    return@launch
                }

                if (allDirectDiscipleIds.contains(discipleId)) {
                    _errorMessage.value = "该弟子已是其他长老的亲传弟子"
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
                _successMessage.value = "亲传弟子任命成功"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "分配失败"
            }
        }
    }
    
    fun removeDirectDisciple(elderSlotType: String, slotIndex: Int) {
        viewModelScope.launch {
            try {
                gameEngine.removeDirectDisciple(elderSlotType, slotIndex)
                _successMessage.value = "亲传弟子已移除"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "卸任失败"
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
                    _errorMessage.value = "弟子不存在"
                    return@launch
                }
                
                if (hasDisciplePosition(discipleId)) {
                    val position = getDisciplePosition(discipleId)
                    _errorMessage.value = "该弟子已担任${position}，不可同时担任多个职务"
                    return@launch
                }
                
                if (isReserveDisciple(discipleId)) {
                    _errorMessage.value = "该弟子已是其他部门的储备弟子"
                    return@launch
                }
                
                val currentReserveDisciples = gameEngine.gameData.value.elderSlots.lawEnforcementReserveDisciples
                if (currentReserveDisciples.any { it.discipleId == discipleId }) {
                    _errorMessage.value = "该弟子已是储备弟子"
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
                _successMessage.value = "储备弟子添加成功"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "添加失败"
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
                _successMessage.value = "储备弟子已移除"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "移除失败"
            }
        }
    }
    
    fun toggleSpiritMineBoost(): Boolean {
        val currentGameData = gameEngine.gameData.value ?: return false
        viewModelScope.launch {
            gameEngine.updateGameData { it.copy(sectPolicies = it.sectPolicies.copy(spiritMineBoost = !it.sectPolicies.spiritMineBoost)) }
        }
        return true
    }

    fun isSpiritMineBoostEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.spiritMineBoost ?: false
    }
    
    fun getSpiritMineBoostEffect(): Double = GameConfig.PolicyConfig.SPIRIT_MINE_BOOST_BASE_EFFECT
    
    fun toggleEnhancedSecurity(): Boolean {
        val currentGameData = gameEngine.gameData.value ?: return false
        val currentPolicies = currentGameData.sectPolicies
        val requiredStones = GameConfig.PolicyConfig.ENHANCED_SECURITY_COST

        if (!currentPolicies.enhancedSecurity) {
            viewModelScope.launch {
                gameEngine.updateGameData {
                    if (it.spiritStones < requiredStones) {
                        _errorMessage.value = "灵石不足${requiredStones}，无法开启增强治安政策"
                        it
                    } else {
                        it.copy(
                            spiritStones = it.spiritStones - requiredStones,
                            sectPolicies = it.sectPolicies.copy(enhancedSecurity = true)
                        )
                    }
                }
            }
        } else {
            viewModelScope.launch {
                gameEngine.updateGameData { it.copy(sectPolicies = it.sectPolicies.copy(enhancedSecurity = false)) }
            }
        }
        return true
    }

    fun isEnhancedSecurityEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.enhancedSecurity ?: false
    }
    
    fun getEnhancedSecurityBaseBonus(): Double = GameConfig.PolicyConfig.ENHANCED_SECURITY_BASE_EFFECT
    
    fun toggleAlchemyIncentive(): Boolean {
        val currentGameData = gameEngine.gameData.value ?: return false
        val currentPolicies = currentGameData.sectPolicies
        val requiredStones = GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_COST

        if (!currentPolicies.alchemyIncentive) {
            viewModelScope.launch {
                gameEngine.updateGameData {
                    if (it.spiritStones < requiredStones) {
                        _errorMessage.value = "灵石不足${requiredStones}，无法开启丹道激励政策"
                        it
                    } else {
                        it.copy(
                            spiritStones = it.spiritStones - requiredStones,
                            sectPolicies = it.sectPolicies.copy(alchemyIncentive = true)
                        )
                    }
                }
            }
        } else {
            viewModelScope.launch {
                gameEngine.updateGameData { it.copy(sectPolicies = it.sectPolicies.copy(alchemyIncentive = false)) }
            }
        }
        return true
    }

    fun isAlchemyIncentiveEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.alchemyIncentive ?: false
    }

    fun toggleForgeIncentive(): Boolean {
        val currentGameData = gameEngine.gameData.value ?: return false
        val currentPolicies = currentGameData.sectPolicies
        val requiredStones = GameConfig.PolicyConfig.FORGE_INCENTIVE_COST

        if (!currentPolicies.forgeIncentive) {
            viewModelScope.launch {
                gameEngine.updateGameData {
                    if (it.spiritStones < requiredStones) {
                        _errorMessage.value = "灵石不足${requiredStones}，无法开启锻造激励政策"
                        it
                    } else {
                        it.copy(
                            spiritStones = it.spiritStones - requiredStones,
                            sectPolicies = it.sectPolicies.copy(forgeIncentive = true)
                        )
                    }
                }
            }
        } else {
            viewModelScope.launch {
                gameEngine.updateGameData { it.copy(sectPolicies = it.sectPolicies.copy(forgeIncentive = false)) }
            }
        }
        return true
    }

    fun isForgeIncentiveEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.forgeIncentive ?: false
    }

    fun toggleHerbCultivation(): Boolean {
        val currentGameData = gameEngine.gameData.value ?: return false
        val currentPolicies = currentGameData.sectPolicies
        val requiredStones = GameConfig.PolicyConfig.HERB_CULTIVATION_COST

        if (!currentPolicies.herbCultivation) {
            viewModelScope.launch {
                gameEngine.updateGameData {
                    if (it.spiritStones < requiredStones) {
                        _errorMessage.value = "灵石不足${requiredStones}，无法开启灵药培育政策"
                        it
                    } else {
                        it.copy(
                            spiritStones = it.spiritStones - requiredStones,
                            sectPolicies = it.sectPolicies.copy(herbCultivation = true)
                        )
                    }
                }
            }
        } else {
            viewModelScope.launch {
                gameEngine.updateGameData { it.copy(sectPolicies = it.sectPolicies.copy(herbCultivation = false)) }
            }
        }
        return true
    }

    fun isHerbCultivationEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.herbCultivation ?: false
    }

    fun toggleCultivationSubsidy(): Boolean {
        val currentGameData = gameEngine.gameData.value ?: return false
        val currentPolicies = currentGameData.sectPolicies
        val requiredStones = GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_COST

        if (!currentPolicies.cultivationSubsidy) {
            viewModelScope.launch {
                gameEngine.updateGameData {
                    if (it.spiritStones < requiredStones) {
                        _errorMessage.value = "灵石不足${requiredStones}，无法开启修行津贴政策"
                        it
                    } else {
                        it.copy(
                            spiritStones = it.spiritStones - requiredStones,
                            sectPolicies = it.sectPolicies.copy(cultivationSubsidy = true)
                        )
                    }
                }
            }
        } else {
            viewModelScope.launch {
                gameEngine.updateGameData { it.copy(sectPolicies = it.sectPolicies.copy(cultivationSubsidy = false)) }
            }
        }
        return true
    }

    fun isCultivationSubsidyEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.cultivationSubsidy ?: false
    }

    fun toggleManualResearch(): Boolean {
        val currentGameData = gameEngine.gameData.value ?: return false
        val currentPolicies = currentGameData.sectPolicies
        val requiredStones = GameConfig.PolicyConfig.MANUAL_RESEARCH_COST

        if (!currentPolicies.manualResearch) {
            viewModelScope.launch {
                gameEngine.updateGameData {
                    if (it.spiritStones < requiredStones) {
                        _errorMessage.value = "灵石不足${requiredStones}，无法开启功法研习政策"
                        it
                    } else {
                        it.copy(
                            spiritStones = it.spiritStones - requiredStones,
                            sectPolicies = it.sectPolicies.copy(manualResearch = true)
                        )
                    }
                }
            }
        } else {
            viewModelScope.launch {
                gameEngine.updateGameData { it.copy(sectPolicies = it.sectPolicies.copy(manualResearch = false)) }
            }
        }
        return true
    }

    fun isManualResearchEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.manualResearch ?: false
    }
    
    fun getViceSectMasterIntelligenceBonus(): Double {
        val viceSectMaster = getViceSectMaster() ?: return 0.0
        val intelligence = viceSectMaster.intelligence
        val baseIntelligence = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_BASE
        val step = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_STEP
        val bonusPerStep = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_BONUS_PER_STEP
        return ((intelligence - baseIntelligence) / step.toDouble() * bonusPerStep).coerceAtLeast(0.0)
    }
    
    fun getDisciplePosition(discipleId: String): String? {
        val gameData = gameEngine.gameData.value ?: return null
        return DisciplePositionHelper.getDisciplePosition(discipleId, gameData)
    }

    fun hasDisciplePosition(discipleId: String): Boolean {
        val gameData = gameEngine.gameData.value ?: return false
        return DisciplePositionHelper.hasDisciplePosition(discipleId, gameData)
    }

    fun isReserveDisciple(discipleId: String): Boolean {
        val gameData = gameEngine.gameData.value ?: return false
        return DisciplePositionHelper.isReserveDisciple(discipleId, gameData)
    }
}
