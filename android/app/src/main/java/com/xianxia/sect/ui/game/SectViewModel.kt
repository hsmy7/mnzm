package com.xianxia.sect.ui.game

import android.util.Log
import androidx.lifecycle.ViewModel
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
) : ViewModel() {
    
    companion object {
        private const val TAG = "SectViewModel"
    }
    
    val gameData: StateFlow<GameData> = gameEngine.gameData
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), gameEngine.gameData.value)
    
    val disciples: StateFlow<List<Disciple>> = gameEngine.disciples
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    private val _successMessage = MutableStateFlow<String?>(null)
    val successMessage: StateFlow<String?> = _successMessage.asStateFlow()
    
    fun clearErrorMessage() {
        _errorMessage.value = null
    }
    
    fun clearSuccessMessage() {
        _successMessage.value = null
    }
    
    fun getViceSectMaster(): Disciple? {
        val viceSectMasterId = gameEngine.gameData.value.elderSlots.viceSectMaster
        return getElderDisciple(viceSectMasterId)
    }
    
    fun getElderDisciple(elderId: String?): Disciple? {
        if (elderId == null) return null
        return disciples.value.find { it.id == elderId }
    }
    
    fun setViceSectMaster(discipleId: String) {
        val disciple = disciples.value.find { it.id == discipleId }
        if (disciple == null) {
            _errorMessage.value = "弟子不存在"
            return
        }
        
        if (disciple.realm > 4) {
            _errorMessage.value = "副宗主需要达到炼虚境界"
            return
        }
        
        val currentSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = listOf(
            currentSlots.herbGardenElder,
            currentSlots.alchemyElder,
            currentSlots.forgeElder,
            currentSlots.outerElder,
            currentSlots.preachingElder,
            currentSlots.lawEnforcementElder,
            currentSlots.innerElder,
            currentSlots.qingyunPreachingElder
        ).filterNotNull()
        
        if (!allElderIds.contains(discipleId)) {
            _errorMessage.value = "副宗主需要由长老担任"
            return
        }
        
        viewModelScope.launch {
            gameEngine.updateGameData { 
                it.copy(elderSlots = currentSlots.copy(viceSectMaster = discipleId))
            }
        }
        _successMessage.value = "副宗主任命成功"
    }
    
    fun removeViceSectMaster() {
        val currentSlots = gameEngine.gameData.value.elderSlots
        viewModelScope.launch {
            gameEngine.updateGameData { 
                it.copy(elderSlots = currentSlots.copy(viceSectMaster = null))
            }
        }
        _successMessage.value = "副宗主已卸任"
    }
    
    fun assignElder(slotType: String, discipleId: String) {
        viewModelScope.launch {
            try {
                val disciple = disciples.value.find { it.id == discipleId }
                if (disciple == null) {
                    _errorMessage.value = "弟子不存在"
                    return@launch
                }
                
                val minRealm = when (slotType) {
                    "viceSectMaster" -> 4
                    else -> 6
                }
                if (disciple.realm > minRealm) {
                    val realmName = when (minRealm) {
                        4 -> "炼虚"
                        6 -> "元婴"
                        else -> "元婴"
                    }
                    val positionName = when (slotType) {
                        "viceSectMaster" -> "副宗主"
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
                )
                val allDirectDiscipleIds = listOf(
                    elderSlots.herbGardenDisciples,
                    elderSlots.alchemyDisciples,
                    elderSlots.forgeDisciples,
                    elderSlots.preachingMasters,
                    elderSlots.lawEnforcementDisciples,
                    elderSlots.lawEnforcementReserveDisciples,
                    elderSlots.qingyunPreachingMasters,
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
                
                val newElderSlots = when (slotType) {
                    "herbGarden" -> elderSlots.copy(
                        herbGardenElder = discipleId,
                        herbGardenDisciples = emptyList()
                    )
                    "alchemy" -> elderSlots.copy(
                        alchemyElder = discipleId,
                        alchemyDisciples = emptyList()
                    )
                    "forge" -> elderSlots.copy(
                        forgeElder = discipleId,
                        forgeDisciples = emptyList()
                    )
                    "viceSectMaster" -> elderSlots.copy(
                        viceSectMaster = discipleId
                    )
                    "outerElder" -> elderSlots.copy(
                        outerElder = discipleId
                    )
                    "preachingElder" -> elderSlots.copy(
                        preachingElder = discipleId,
                        preachingMasters = emptyList()
                    )
                    "lawEnforcementElder" -> elderSlots.copy(
                        lawEnforcementElder = discipleId,
                        lawEnforcementDisciples = emptyList()
                    )
                    "innerElder" -> elderSlots.copy(
                        innerElder = discipleId
                    )
                    "qingyunPreachingElder" -> elderSlots.copy(
                        qingyunPreachingElder = discipleId,
                        qingyunPreachingMasters = emptyList()
                    )
                    else -> elderSlots
                }
                gameEngine.updateElderSlots(newElderSlots)
                gameEngine.syncAllDiscipleStatuses()
                _successMessage.value = "长老任命成功"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "任命失败"
            }
        }
    }
    
    fun removeElder(slotType: String) {
        viewModelScope.launch {
            try {
                val currentGameData = gameEngine.gameData.value
                val elderSlots = currentGameData.elderSlots
                val newElderSlots = when (slotType) {
                    "herbGarden" -> elderSlots.copy(
                        herbGardenElder = null,
                        herbGardenDisciples = emptyList()
                    )
                    "alchemy" -> elderSlots.copy(
                        alchemyElder = null,
                        alchemyDisciples = emptyList()
                    )
                    "forge" -> elderSlots.copy(
                        forgeElder = null,
                        forgeDisciples = emptyList()
                    )
                    "viceSectMaster" -> elderSlots.copy(
                        viceSectMaster = null
                    )
                    "outerElder" -> elderSlots.copy(
                        outerElder = null
                    )
                    "preachingElder" -> elderSlots.copy(
                        preachingElder = null,
                        preachingMasters = emptyList()
                    )
                    "lawEnforcementElder" -> elderSlots.copy(
                        lawEnforcementElder = null,
                        lawEnforcementDisciples = emptyList()
                    )
                    "innerElder" -> elderSlots.copy(
                        innerElder = null
                    )
                    "qingyunPreachingElder" -> elderSlots.copy(
                        qingyunPreachingElder = null,
                        qingyunPreachingMasters = emptyList()
                    )
                    else -> elderSlots
                }
                gameEngine.updateElderSlots(newElderSlots)
                gameEngine.syncAllDiscipleStatuses()
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
    
    fun getOuterElder(): Disciple? {
        val outerElderId = gameEngine.gameData.value.elderSlots.outerElder
        return getElderDisciple(outerElderId)
    }
    
    fun getPreachingElder(): Disciple? {
        val preachingElderId = gameEngine.gameData.value.elderSlots.preachingElder
        return getElderDisciple(preachingElderId)
    }
    
    fun getPreachingMasters(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value.elderSlots.preachingMasters
    }
    
    fun getLawEnforcementElder(): Disciple? {
        val elderId = gameEngine.gameData.value.elderSlots.lawEnforcementElder
        return getElderDisciple(elderId)
    }
    
    fun getLawEnforcementDisciples(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value.elderSlots.lawEnforcementDisciples
    }
    
    fun getLawEnforcementReserveDisciples(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value.elderSlots.lawEnforcementReserveDisciples
    }
    
    fun getLawEnforcementReserveDisciplesWithInfo(): List<Disciple> {
        val reserveSlots = gameEngine.gameData.value.elderSlots.lawEnforcementReserveDisciples
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
                _successMessage.value = "储备弟子已移除"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "移除失败"
            }
        }
    }
    
    fun toggleSpiritMineBoost() {
        val currentPolicies = gameEngine.gameData.value.sectPolicies
        gameEngine.updateGameDataSync { it.copy(sectPolicies = currentPolicies.copy(spiritMineBoost = !currentPolicies.spiritMineBoost)) }
    }
    
    fun isSpiritMineBoostEnabled(): Boolean {
        return gameEngine.gameData.value.sectPolicies.spiritMineBoost
    }
    
    fun getSpiritMineBoostEffect(): Double = GameConfig.PolicyConfig.SPIRIT_MINE_BOOST_BASE_EFFECT
    
    fun toggleEnhancedSecurity(): Boolean {
        val currentPolicies = gameEngine.gameData.value.sectPolicies
        val currentStones = gameEngine.gameData.value.spiritStones
        val requiredStones = GameConfig.PolicyConfig.ENHANCED_SECURITY_COST
        
        if (!currentPolicies.enhancedSecurity && currentStones < requiredStones) {
            _errorMessage.value = "灵石不足${requiredStones}，无法开启增强治安政策"
            return false
        }
        
        gameEngine.updateGameDataSync { it.copy(sectPolicies = currentPolicies.copy(enhancedSecurity = !currentPolicies.enhancedSecurity)) }
        return true
    }
    
    fun isEnhancedSecurityEnabled(): Boolean {
        return gameEngine.gameData.value.sectPolicies.enhancedSecurity
    }
    
    fun getEnhancedSecurityBaseBonus(): Double = GameConfig.PolicyConfig.ENHANCED_SECURITY_BASE_EFFECT
    
    fun toggleAlchemyIncentive(): Boolean {
        val currentPolicies = gameEngine.gameData.value.sectPolicies
        val currentStones = gameEngine.gameData.value.spiritStones
        val requiredStones = GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_COST
        
        if (!currentPolicies.alchemyIncentive && currentStones < requiredStones) {
            _errorMessage.value = "灵石不足${requiredStones}，无法开启丹道激励政策"
            return false
        }
        
        gameEngine.updateGameDataSync { it.copy(sectPolicies = currentPolicies.copy(alchemyIncentive = !currentPolicies.alchemyIncentive)) }
        return true
    }
    
    fun isAlchemyIncentiveEnabled(): Boolean {
        return gameEngine.gameData.value.sectPolicies.alchemyIncentive
    }
    
    fun toggleForgeIncentive(): Boolean {
        val currentPolicies = gameEngine.gameData.value.sectPolicies
        val currentStones = gameEngine.gameData.value.spiritStones
        val requiredStones = GameConfig.PolicyConfig.FORGE_INCENTIVE_COST
        
        if (!currentPolicies.forgeIncentive && currentStones < requiredStones) {
            _errorMessage.value = "灵石不足${requiredStones}，无法开启锻造激励政策"
            return false
        }
        
        gameEngine.updateGameDataSync { it.copy(sectPolicies = currentPolicies.copy(forgeIncentive = !currentPolicies.forgeIncentive)) }
        return true
    }
    
    fun isForgeIncentiveEnabled(): Boolean {
        return gameEngine.gameData.value.sectPolicies.forgeIncentive
    }
    
    fun toggleHerbCultivation(): Boolean {
        val currentPolicies = gameEngine.gameData.value.sectPolicies
        val currentStones = gameEngine.gameData.value.spiritStones
        val requiredStones = GameConfig.PolicyConfig.HERB_CULTIVATION_COST
        
        if (!currentPolicies.herbCultivation && currentStones < requiredStones) {
            _errorMessage.value = "灵石不足${requiredStones}，无法开启灵药培育政策"
            return false
        }
        
        gameEngine.updateGameDataSync { it.copy(sectPolicies = currentPolicies.copy(herbCultivation = !currentPolicies.herbCultivation)) }
        return true
    }
    
    fun isHerbCultivationEnabled(): Boolean {
        return gameEngine.gameData.value.sectPolicies.herbCultivation
    }
    
    fun toggleCultivationSubsidy(): Boolean {
        val currentPolicies = gameEngine.gameData.value.sectPolicies
        val currentStones = gameEngine.gameData.value.spiritStones
        val requiredStones = GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_COST
        
        if (!currentPolicies.cultivationSubsidy && currentStones < requiredStones) {
            _errorMessage.value = "灵石不足${requiredStones}，无法开启修行津贴政策"
            return false
        }
        
        gameEngine.updateGameDataSync { it.copy(sectPolicies = currentPolicies.copy(cultivationSubsidy = !currentPolicies.cultivationSubsidy)) }
        return true
    }
    
    fun isCultivationSubsidyEnabled(): Boolean {
        return gameEngine.gameData.value.sectPolicies.cultivationSubsidy
    }
    
    fun toggleManualResearch(): Boolean {
        val currentPolicies = gameEngine.gameData.value.sectPolicies
        val currentStones = gameEngine.gameData.value.spiritStones
        val requiredStones = GameConfig.PolicyConfig.MANUAL_RESEARCH_COST
        
        if (!currentPolicies.manualResearch && currentStones < requiredStones) {
            _errorMessage.value = "灵石不足${requiredStones}，无法开启功法研习政策"
            return false
        }
        
        gameEngine.updateGameDataSync { it.copy(sectPolicies = currentPolicies.copy(manualResearch = !currentPolicies.manualResearch)) }
        return true
    }
    
    fun isManualResearchEnabled(): Boolean {
        return gameEngine.gameData.value.sectPolicies.manualResearch
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
        return DisciplePositionHelper.getDisciplePosition(discipleId, gameEngine.gameData.value)
    }
    
    fun hasDisciplePosition(discipleId: String): Boolean {
        return DisciplePositionHelper.hasDisciplePosition(discipleId, gameEngine.gameData.value)
    }
    
    fun isReserveDisciple(discipleId: String): Boolean {
        return DisciplePositionHelper.isReserveDisciple(discipleId, gameEngine.gameData.value)
    }
}
