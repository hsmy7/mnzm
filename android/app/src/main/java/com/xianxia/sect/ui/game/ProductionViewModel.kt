package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.usecase.DisciplePositionQueryUseCase
import com.xianxia.sect.core.usecase.ElderManagementUseCase
import com.xianxia.sect.core.usecase.SectPolicyToggleUseCase
import com.xianxia.sect.core.util.sortedByFollowAndRealm
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProductionViewModel @Inject constructor(
    private val gameEngine: GameEngine,
    private val disciplePositionQuery: DisciplePositionQueryUseCase,
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

    private val disciples = gameEngine.disciples

    fun getElderDisciple(elderId: String?): DiscipleAggregate? {
        if (elderId == null) return null
        return discipleAggregates.value.find { it.id == elderId }
    }

    fun assignElder(slotType: ElderSlotType, discipleId: String) =
        launchElderAction({ elderManagement.assignElder(slotType, discipleId) }, "任命失败")

    fun removeElder(slotType: ElderSlotType) =
        launchElderAction({ elderManagement.removeElder(slotType) }, "卸任失败")

    fun assignDirectDisciple(elderSlotType: String, slotIndex: Int, discipleId: String) =
        launchElderAction({ elderManagement.assignDirectDisciple(elderSlotType, slotIndex, discipleId) }, "分配失败")

    fun removeDirectDisciple(elderSlotType: String, slotIndex: Int) =
        launchElderAction({ elderManagement.removeDirectDisciple(elderSlotType, slotIndex) }, "卸任失败")

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

    fun setViceSectMaster(discipleId: String) {
        viewModelScope.launch {
            val disciple = discipleAggregates.value.find { it.id == discipleId }
            if (disciple == null) {
                showError("弟子不存在")
                return@launch
            }

            if (disciple.realm > ElderManagementUseCase.REALM_VICE_SECT_MASTER) {
                showError("副宗主需要达到炼虚境界")
                return@launch
            }

            val currentSlots = gameEngine.gameData.value.elderSlots
            val allElderIds = currentSlots.getAllElderIds()

            if (!allElderIds.contains(discipleId)) {
                showError("副宗主需要由长老担任")
                return@launch
            }

            gameEngine.updateGameData {
                it.copy(elderSlots = it.elderSlots.copy(viceSectMaster = discipleId))
            }
        }
    }

    fun removeViceSectMaster() {
        viewModelScope.launch {
            gameEngine.updateGameData {
                it.copy(elderSlots = it.elderSlots.copy(viceSectMaster = ""))
            }
        }
    }

    fun getViceSectMaster(): DiscipleAggregate? {
        val viceSectMasterId = gameEngine.gameData.value?.elderSlots?.viceSectMaster
        return getElderDisciple(viceSectMasterId)
    }

    fun getViceSectMasterIntelligenceBonus(): Double {
        val viceSectMaster = getViceSectMaster() ?: return 0.0
        return sectPolicyToggle.getViceSectMasterIntelligenceBonus(viceSectMaster.intelligence)
    }

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

    fun getLawEnforcementReserveDisciples(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value?.elderSlots?.lawEnforcementReserveDisciples ?: emptyList()
    }

    fun getLawEnforcementReserveDisciplesWithInfo(): List<DiscipleAggregate> {
        val reserveSlots = gameEngine.gameData.value?.elderSlots?.lawEnforcementReserveDisciples ?: emptyList()
        val reserveIds = reserveSlots.mapNotNull { it.discipleId }.toSet()
        return discipleAggregates.value
            .filter { it.id in reserveIds }
            .sortedByFollowAndRealm()
    }

    fun addReserveDisciple(discipleId: String) {
        viewModelScope.launch {
            try {
                val disciple = disciples.value.find { it.id == discipleId }
                if (disciple == null) {
                    showError("弟子不存在")
                    return@launch
                }

                if (disciplePositionQuery.hasDisciplePosition(discipleId)) {
                    showError("该弟子已担任其他职务，不可同时担任多个职务")
                    return@launch
                }

                if (disciplePositionQuery.isReserveDisciple(discipleId)) {
                    showError("该弟子已是其他部门的储备弟子")
                    return@launch
                }

                val currentReserveDisciples = gameEngine.gameData.value?.elderSlots?.lawEnforcementReserveDisciples ?: emptyList()
                if (currentReserveDisciples.any { it.discipleId == discipleId }) {
                    showError("该弟子已是储备弟子")
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
                gameEngine.updateGameData { it.copy(elderSlots = it.elderSlots.copy(lawEnforcementReserveDisciples = updatedReserveDisciples)) }
                gameEngine.syncAllDiscipleStatuses()
            } catch (e: Exception) {
                showError(e.message ?: "添加失败")
            }
        }
    }

    fun addReserveDisciples(discipleIds: List<String>) {
        viewModelScope.launch {
            try {
                val currentReserveDisciples = gameEngine.gameData.value?.elderSlots?.lawEnforcementReserveDisciples ?: emptyList()
                val existingIds = currentReserveDisciples.mapNotNull { it.discipleId }.toSet()

                val newSlots = mutableListOf<DirectDiscipleSlot>()
                var nextIndex = if (currentReserveDisciples.isEmpty()) 0 else currentReserveDisciples.maxOf { it.index } + 1

                for (discipleId in discipleIds) {
                    if (discipleId in existingIds) continue

                    val disciple = disciples.value.find { it.id == discipleId }
                    if (disciple == null) continue

                    if (disciplePositionQuery.hasDisciplePosition(discipleId)) continue

                    if (disciplePositionQuery.isReserveDisciple(discipleId)) continue

                    newSlots.add(
                        DirectDiscipleSlot(
                            index = nextIndex,
                            discipleId = discipleId,
                            discipleName = disciple.name,
                            discipleRealm = disciple.realmName,
                            discipleSpiritRootColor = disciple.spiritRoot.countColor
                        )
                    )
                    nextIndex++
                }

                if (newSlots.isNotEmpty()) {
                    val updatedReserveDisciples = currentReserveDisciples + newSlots
                    gameEngine.updateGameData { it.copy(elderSlots = it.elderSlots.copy(lawEnforcementReserveDisciples = updatedReserveDisciples)) }
                    gameEngine.syncAllDiscipleStatuses()
                }
            } catch (e: Exception) {
                showError(e.message ?: "添加失败")
            }
        }
    }

    fun removeReserveDisciple(discipleId: String) {
        viewModelScope.launch {
            try {
                val currentReserveDisciples = gameEngine.gameData.value?.elderSlots?.lawEnforcementReserveDisciples ?: emptyList()
                val updatedReserveDisciples = currentReserveDisciples.filter { it.discipleId != discipleId }
                gameEngine.updateGameData { it.copy(elderSlots = it.elderSlots.copy(lawEnforcementReserveDisciples = updatedReserveDisciples)) }
                gameEngine.syncAllDiscipleStatuses()
            } catch (e: Exception) {
                showError(e.message ?: "移除失败")
            }
        }
    }

    private fun isSelectableDisciple(disciple: DiscipleAggregate): Boolean {
        return disciple.isAlive &&
               disciple.discipleType == "inner" &&
               disciple.age >= GameConfig.Disciple.MIN_AGE &&
               disciple.realmLayer > 0 &&
               disciple.status == DiscipleStatus.IDLE
    }

    fun getAvailableDisciplesForSelection(): List<DiscipleAggregate> {
        return discipleAggregates.value.filter { disciple ->
            disciple.isAlive &&
            disciple.status == DiscipleStatus.IDLE &&
            disciple.realmLayer > 0
        }
    }

    fun getAvailableDisciplesForLawEnforcementElder(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return discipleAggregates.value
            .filter { isSelectableDisciple(it) && it.realm <= ElderManagementUseCase.REALM_LAW_ENFORCEMENT && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForLawEnforcementDisciple(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return discipleAggregates.value
            .filter { isSelectableDisciple(it) && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedByFollowAndRealm()
    }

    fun getAvailableDisciplesForLawEnforcementReserve(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return discipleAggregates.value
            .filter { isSelectableDisciple(it) && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForOuterElder(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return discipleAggregates.value
            .filter {
                it.isAlive &&
                it.discipleType == "inner" &&
                it.realm <= ElderManagementUseCase.REALM_ELDER &&
                it.age >= GameConfig.Disciple.MIN_AGE &&
                it.realmLayer > 0 &&
                it.status == DiscipleStatus.IDLE &&
                !allElderIds.contains(it.id) &&
                !allDirectDiscipleIds.contains(it.id)
            }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForPreachingElder(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return discipleAggregates.value
            .filter { isSelectableDisciple(it) && it.realm <= ElderManagementUseCase.REALM_ELDER && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForPreachingMaster(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value?.elderSlots ?: return emptyList()
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return discipleAggregates.value
            .filter { isSelectableDisciple(it) && it.realm <= ElderManagementUseCase.REALM_PREACHING_MASTER && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getInnerElder(): DiscipleAggregate? {
        val innerElderId = gameEngine.gameData.value?.elderSlots?.innerElder
        return getElderDisciple(innerElderId)
    }

    fun getQingyunPreachingElder(): DiscipleAggregate? {
        val preachingElderId = gameEngine.gameData.value?.elderSlots?.qingyunPreachingElder
        return getElderDisciple(preachingElderId)
    }

    fun getQingyunPreachingMasters(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value?.elderSlots?.qingyunPreachingMasters ?: emptyList()
    }

    fun getAvailableDisciplesForInnerElder(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return discipleAggregates.value
            .filter { isSelectableDisciple(it) && it.realm <= ElderManagementUseCase.REALM_ELDER && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForQingyunPreachingElder(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return discipleAggregates.value
            .filter { isSelectableDisciple(it) && it.realm <= ElderManagementUseCase.REALM_ELDER && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForQingyunPreachingMaster(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return discipleAggregates.value
            .filter { isSelectableDisciple(it) && it.realm <= ElderManagementUseCase.REALM_PREACHING_MASTER && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    private fun ElderSlots.getAllElderIds(): List<String> {
        return elderManagement.run { getAllElderIds() }
    }

    private fun ElderSlots.getAllDirectDiscipleIds(): List<String> {
        return elderManagement.run { getAllDirectDiscipleIds() }
    }
}
