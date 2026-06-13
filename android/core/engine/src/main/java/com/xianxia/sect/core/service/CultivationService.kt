package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.engine.domain.battle.BattleMemberData
import com.xianxia.sect.core.engine.domain.disciple.DiscipleService
import com.xianxia.sect.core.engine.annotation.GameService
import javax.inject.Inject
import javax.inject.Singleton

data class HighFrequencyData(
    val lastUpdateTime: Long = 0L,
    val lastCultivationTime: Long = 0L,
    val cultivationPerSecond: Double = 0.0,
    val totalDisciples: Int = 0,
    val lastBreakthroughCheckTime: Long = 0L,
    val timestamp: Long = 0L,
    val cultivationUpdates: Map<String, Double> = emptyMap(),
    val realtimeCultivation: Map<String, Double>? = null,
    val proficiencyUpdates: Map<String, Map<String, Double>> = emptyMap(),
    val nurtureUpdates: Map<String, Map<String, Double>> = emptyMap()
)

@GameService("CultivationService")
@Singleton
class CultivationService @Inject constructor(
    private val stateStore: GameStateStore,
    private val cultivationCore: CultivationCore,
    private val breakthroughHandler: DiscipleBreakthroughHandler,
    private val cultivationSettlement: CultivationSettlement,
    private val eventProcessor: CultivationEventProcessor,
    private val productionSubsystem: ProductionSubsystem,
    private val merchantAndRecruitService: MerchantAndRecruitService,
    private val caveExplorationProcessor: CaveExplorationProcessor,
    private val sharedState: CultivationSharedState,
    private val discipleService: DiscipleService
) {
    // ── 共享状态委托 ──────────────────────────────────────────────────

    private val _highFrequencyData get() = sharedState.highFrequencyData

    fun markAutoEquipDirty(discipleId: String) { sharedState.autoEquipDirty.add(discipleId) }
    fun markAutoLearnDirty(discipleId: String) { sharedState.autoLearnDirty.add(discipleId) }

    var cachedCultivationRates: Map<String, Double>
        get() = sharedState.cachedCultivationRates
        set(value) { sharedState.cachedCultivationRates = value }

    var cachedNurtureRates: Map<String, Double>
        get() = sharedState.cachedNurtureRates
        set(value) { sharedState.cachedNurtureRates = value }

    var cachedProficiencyRates: Map<String, Map<String, Double>>
        get() = sharedState.cachedProficiencyRates
        set(value) { sharedState.cachedProficiencyRates = value }

    // ── 委托方法：CultivationCore ──────────────────────────────────────

    fun recoverHpMpForBattleParticipants(state: MutableGameState, discipleIds: List<String>) {
        cultivationCore.recoverHpMpForBattleParticipants(state, discipleIds)
    }

    fun recoverHpMpForAllDisciples(state: MutableGameState) {
        cultivationCore.recoverHpMpForAllDisciples(state)
    }

    fun updateMonthlyCultivation(state: MutableGameState) {
        val newHfd = cultivationCore.updateMonthlyCultivation(state, _highFrequencyData.value)
        _highFrequencyData.value = newHfd
    }

    fun processMonthlyBreakthroughs(state: MutableGameState) {
        breakthroughHandler.processMonthlyBreakthroughs(state)
    }

    fun updateFocusedDisciple(discipleId: String, state: MutableGameState) {
        val newHfd = cultivationCore.updateFocusedDisciple(discipleId, state, _highFrequencyData.value, breakthroughHandler)
        _highFrequencyData.value = newHfd
    }

    fun settleSalaryOnBreakthrough(discipleId: String, currentYear: Int) {
        cultivationSettlement.settleSalaryOnBreakthrough(discipleId, currentYear)
    }

    internal fun processSalaryYearly(year: Int) {
        cultivationSettlement.processSalaryYearly(year)
    }

    internal fun processResidenceLoyalty() {
        cultivationSettlement.processResidenceLoyalty()
    }

    internal fun processPolicyCosts() {
        cultivationSettlement.processPolicyCosts()
    }

    internal fun processSpiritMineProduction() {
        productionSubsystem.processSpiritMineProduction()
    }

    // ── 委托方法：CultivationEventProcessor ────────────────────────────

    suspend fun advancePhase(state: MutableGameState? = null) {
        eventProcessor.advancePhase(state)
    }

    suspend fun advanceMonth(state: MutableGameState? = null) {
        eventProcessor.advanceMonth(state)
    }

    suspend fun advanceYear(state: MutableGameState? = null) {
        eventProcessor.advanceYear(state)
    }

    suspend fun processMonthlyEventsOnShadow(state: MutableGameState) {
        val data = state.gameData
        eventProcessor.processMonthlyEvents(data.gameYear, data.gameMonth)
    }

    suspend fun processYearlyEventsOnShadow(state: MutableGameState) {
        val data = state.gameData
        eventProcessor.processYearlyEvents(data.gameYear)
    }

    fun getHighFrequencyData(): StateFlow<HighFrequencyData> = _highFrequencyData

    fun resetHighFrequencyData() {
        _highFrequencyData.value = HighFrequencyData()
    }

    fun qualifiesForSectAutoPublic(disciple: Disciple, focused: Boolean, rootCounts: Set<Int>): Boolean {
        return eventProcessor.qualifiesForSectAutoPublic(disciple, focused, rootCounts)
    }

    fun updateDiscipleHpMpAfterBattle(battleMembers: List<BattleMemberData>) {
        eventProcessor.updateDiscipleHpMpAfterBattle(battleMembers)
    }

    suspend fun completeExploration(team: ExplorationTeam, success: Boolean, survivorIds: List<String>, survivorHpMap: Map<String, Int> = emptyMap(), survivorMpMap: Map<String, Int> = emptyMap()) {
        eventProcessor.completeExploration(team, success, survivorIds, survivorHpMap, survivorMpMap)
    }

    // ── 委托方法：ProductionSubsystem ─────────────────────────────────

    internal suspend fun processBuildingProduction(year: Int, month: Int) {
        productionSubsystem.processBuildingProduction(year, month)
    }

    internal suspend fun processHerbGardenGrowth(year: Int, month: Int) {
        productionSubsystem.processHerbGardenGrowth(year, month)
    }

    internal suspend fun processAutoPlant() {
        productionSubsystem.processAutoPlant()
    }

    internal suspend fun processSpiritFieldHarvest() {
        productionSubsystem.processSpiritFieldHarvest()
    }

    internal suspend fun processAutoAlchemy() {
        productionSubsystem.processAutoAlchemy()
    }

    internal suspend fun processAutoForge() {
        productionSubsystem.processAutoForge()
    }

    internal suspend fun processAutoAssign() {
        productionSubsystem.processAutoAssign()
    }

    // ── 委托方法：MerchantAndRecruitService ────────────────────────────

    internal suspend fun refreshTravelingMerchant(year: Int, month: Int) {
        merchantAndRecruitService.refreshTravelingMerchant(year, month)
    }

    internal suspend fun refreshMerchantAcquisition(year: Int, month: Int) {
        merchantAndRecruitService.refreshMerchantAcquisition(year, month)
    }

    internal suspend fun refreshRecruitList(year: Int) {
        merchantAndRecruitService.refreshRecruitList(year)
    }

    // ── 委托方法：CaveExplorationProcessor ─────────────────────────────

    suspend fun processCaveLifecycle(year: Int, month: Int) {
        caveExplorationProcessor.processCaveLifecycle(year, month)
    }
}
