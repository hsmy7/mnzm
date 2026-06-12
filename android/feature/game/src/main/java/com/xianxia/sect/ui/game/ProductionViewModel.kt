package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.engine.*
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
        return gameEngine.discipleAggregatesSnapshot.find { it.id == elderId }
    }

    fun assignElder(slotType: ElderSlotType, discipleId: String) =
        launchElderAction({ elderManagement.assignElder(slotType, discipleId) }, "任命失败")

    fun removeElder(slotType: ElderSlotType) =
        launchElderAction({ elderManagement.removeElder(slotType) }, "卸任失败")

    fun assignDirectDisciple(elderSlotType: String, slotIndex: Int, discipleId: String) =
        launchElderAction({ elderManagement.assignDirectDisciple(elderSlotType, slotIndex, discipleId) }, "分配失败")

    fun removeDirectDisciple(elderSlotType: String, slotIndex: Int) =
        launchElderAction({ elderManagement.removeDirectDisciple(elderSlotType, slotIndex) }, "卸任失败")

    fun assignWarehouseGarrison(buildingInstanceId: String, discipleId: String, discipleName: String, sectId: String) {
        viewModelScope.launch {
            gameEngine.updateGameData { data ->
                val existing = data.warehouseGarrisons.toMutableList()
                existing.removeAll { it.buildingInstanceId == buildingInstanceId }
                existing.add(WarehouseGarrisonSlot(buildingInstanceId, discipleId, discipleName, sectId))
                data.copy(warehouseGarrisons = existing)
            }
        }
    }

    fun removeWarehouseGarrison(buildingInstanceId: String) {
        viewModelScope.launch {
            gameEngine.updateGameData { data ->
                data.copy(warehouseGarrisons = data.warehouseGarrisons.filter { it.buildingInstanceId != buildingInstanceId })
            }
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

    fun getSpiritMineBoostEffect(): Double = sectPolicyToggle.getSpiritMineBoostEffect()

    fun toggleEnhancedSecurity(): Boolean {
        val currentGameData = gameEngine.gameDataSnapshot ?: return false
        viewModelScope.launch {
            val result = sectPolicyToggle.toggleEnhancedSecurity()
            if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
        }
        return true
    }

    fun isEnhancedSecurityEnabled(): Boolean = sectPolicyToggle.isEnhancedSecurityEnabled()

    fun getEnhancedSecurityBaseBonus(): Double = sectPolicyToggle.getEnhancedSecurityBaseBonus()

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
        return sectPolicyToggle.getViceSectMasterIntelligenceBonus(viceSectMaster.intelligence)
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

    fun getLawEnforcementReserveDisciples(): List<DirectDiscipleSlot> {
        val activeSectId = gameEngine.gameDataSnapshot?.activeSectId ?: ""
        return gameEngine.gameDataSnapshot?.elderSlots?.lawEnforcementReserveDisciples?.filter { it.sectId == activeSectId } ?: emptyList()
    }

    fun getLawEnforcementReserveDisciplesWithInfo(): List<DiscipleAggregate> {
        val activeSectId = gameEngine.gameDataSnapshot?.activeSectId ?: ""
        val reserveSlots = gameEngine.gameDataSnapshot?.elderSlots?.lawEnforcementReserveDisciples?.filter { it.sectId == activeSectId } ?: emptyList()
        val reserveIds = reserveSlots.mapNotNull { it.discipleId }.toSet()
        return gameEngine.discipleAggregatesSnapshot
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

                val currentReserveDisciples = gameEngine.gameDataSnapshot?.elderSlots?.lawEnforcementReserveDisciples ?: emptyList()
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
                    discipleSpiritRootColor = disciple.spiritRoot.countColor,
                    sectId = gameEngine.gameDataSnapshot.activeSectId
                )

                val updatedReserveDisciples = currentReserveDisciples + newSlot
                gameEngine.updateGameDataAndSync { it.copy(elderSlots = it.elderSlots.copy(lawEnforcementReserveDisciples = updatedReserveDisciples)) }
            } catch (e: Exception) {
                showError(e.message ?: "添加失败")
            }
        }
    }

    fun addReserveDisciples(discipleIds: List<String>) {
        viewModelScope.launch {
            try {
                val currentReserveDisciples = gameEngine.gameDataSnapshot?.elderSlots?.lawEnforcementReserveDisciples ?: emptyList()
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
                            discipleSpiritRootColor = disciple.spiritRoot.countColor,
                            sectId = gameEngine.gameDataSnapshot.activeSectId
                        )
                    )
                    nextIndex++
                }

                if (newSlots.isNotEmpty()) {
                    val updatedReserveDisciples = currentReserveDisciples + newSlots
                    gameEngine.updateGameDataAndSync { it.copy(elderSlots = it.elderSlots.copy(lawEnforcementReserveDisciples = updatedReserveDisciples)) }
                }
            } catch (e: Exception) {
                showError(e.message ?: "添加失败")
            }
        }
    }

    fun removeReserveDisciple(discipleId: String) {
        viewModelScope.launch {
            try {
                val currentReserveDisciples = gameEngine.gameDataSnapshot?.elderSlots?.lawEnforcementReserveDisciples ?: emptyList()
                val updatedReserveDisciples = currentReserveDisciples.filter { it.discipleId != discipleId }
                gameEngine.updateGameDataAndSync { it.copy(elderSlots = it.elderSlots.copy(lawEnforcementReserveDisciples = updatedReserveDisciples)) }
            } catch (e: Exception) {
                showError(e.message ?: "移除失败")
            }
        }
    }

    fun getAvailableDisciplesForLawEnforcementElder(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameDataSnapshot.elderSlots

        return gameEngine.discipleAggregatesSnapshot
            .filter { it.isEligibleForInnerPosition && !elderSlots.isDiscipleInAnyPosition(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForLawEnforcementDisciple(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameDataSnapshot.elderSlots

        return gameEngine.discipleAggregatesSnapshot
            .filter { it.isEligibleForInnerPosition && !elderSlots.isDiscipleInAnyPosition(it.id) }
            .sortedByFollowAndRealm()
    }

    fun getAvailableDisciplesForLawEnforcementReserve(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameDataSnapshot.elderSlots

        return gameEngine.discipleAggregatesSnapshot
            .filter { it.isEligibleForInnerPosition && !elderSlots.isDiscipleInAnyPosition(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForOuterElder(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameDataSnapshot.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return gameEngine.discipleAggregatesSnapshot
            .filter { it.isEligibleForInnerPosition && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForPreachingElder(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameDataSnapshot.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return gameEngine.discipleAggregatesSnapshot
            .filter { it.isEligibleForInnerPosition && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForPreachingMaster(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameDataSnapshot?.elderSlots ?: return emptyList()
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return gameEngine.discipleAggregatesSnapshot
            .filter { it.isEligibleForInnerPosition && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
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
        val elderSlots = gameEngine.gameDataSnapshot.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return gameEngine.discipleAggregatesSnapshot
            .filter { it.isEligibleForInnerPosition && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForQingyunPreachingElder(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameDataSnapshot.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return gameEngine.discipleAggregatesSnapshot
            .filter { it.isEligibleForInnerPosition && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForQingyunPreachingMaster(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameDataSnapshot.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return gameEngine.discipleAggregatesSnapshot
            .filter { it.isEligibleForInnerPosition && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    private fun ElderSlots.getAllElderIds(): List<String> {
        return elderManagement.run { getAllElderIds() }
    }

    private fun ElderSlots.getAllDirectDiscipleIds(): List<String> {
        return elderManagement.run { getAllDirectDiscipleIds() }
    }
}
