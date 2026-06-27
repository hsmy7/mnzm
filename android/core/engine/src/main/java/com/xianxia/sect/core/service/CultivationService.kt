package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.xianxia.sect.core.GameConfig
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
    val cultivationPerPhase: Double = 0.0,
    val totalDisciples: Int = 0,
    val lastBreakthroughCheckTime: Long = 0L,
    val timestamp: Long = 0L,
    val cultivationUpdates: Map<String, Double> = emptyMap(),
    val realtimeCultivation: Map<String, Double>? = null,
    val proficiencyUpdates: Map<String, Map<String, Double>> = emptyMap(),
    val nurtureUpdates: Map<String, Map<String, Double>> = emptyMap(),
    /** 本月焦点域已处理的旬数，用于月结时扣除已应用的 HP/MP 恢复和衰减 */
    val focusedPhaseCount: Int = 0
)

@GameService("CultivationService")
@Singleton
class CultivationService @Inject constructor(
    private val stateStore: GameStateStore,
    private val cultivationCore: CultivationCore,
    internal val breakthroughHandler: DiscipleBreakthroughHandler,
    private val cultivationSettlement: CultivationSettlement,
    private val eventProcessor: CultivationEventProcessor,
    private val productionProcessor: ProductionProcessor,
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

    fun recoverHpMpForAllDisciples(state: MutableGameState, phasesToSettle: Int = 1) {
        cultivationCore.recoverHpMpForAllDisciples(state, phasesToSettle)
    }

    /**
     * 批量轨累积结算：直接将 N 旬修炼值写入 discipleTables.cultivations，
     * 跳过 HFD 中转。月度结算通过 focusedPhaseCount 自动扣除不双计。
     */
    fun batchSettleCultivation(state: MutableGameState, phasesToSettle: Int) {
        cultivationCore.batchSettleCultivation(state, phasesToSettle)
    }

    /** 月度 HP/MP 恢复（月结制专用） */
    fun recoverMonthlyHpMp(
        tables: com.xianxia.sect.core.state.DiscipleTables, id: Int,
        focusedPhaseCount: Int = 0
    ) {
        cultivationCore.recoverMonthlyHpMp(tables, id, focusedPhaseCount)
    }

    /** 月度自动从仓库装备/学习（月结制专用） */
    fun processAutoFromWarehouseMonthly(
        year: Int, month: Int, state: MutableGameState
    ) {
        eventProcessor.processAutoFromWarehouseMonthly(year, month, state)
    }

    /** 月度持续效果衰减（月结制专用） */
    fun applyMonthlyDurationDecay(
        tables: com.xianxia.sect.core.state.DiscipleTables, id: Int,
        focusedPhaseCount: Int = 0
    ) {
        cultivationCore.applyMonthlyDurationDecay(tables, id, focusedPhaseCount)
    }

    fun updateMonthlyCultivation(state: MutableGameState) {
        val newHfd = cultivationCore.updateMonthlyCultivation(state, _highFrequencyData.value)
        _highFrequencyData.value = newHfd
    }

    fun processBreakthroughs(state: MutableGameState) {
        val livingDisciples = state.discipleTables.assembleAll().filter { it.isAlive }
        breakthroughHandler.processRealtimeBreakthroughs(livingDisciples, state.gameData, state)
    }


    suspend fun settleSalaryOnBreakthrough(discipleId: String, currentYear: Int) {
        cultivationSettlement.settleSalaryOnBreakthrough(discipleId, currentYear)
    }

    suspend fun processSalaryYearly(year: Int) {
        cultivationSettlement.processSalaryYearly(year)
    }

    suspend fun processResidenceLoyalty() {
        cultivationSettlement.processResidenceLoyalty()
    }

    internal fun processPolicyCosts(state: MutableGameState) {
        cultivationSettlement.processPolicyCosts(state)
    }

    internal fun processSpiritMineProduction(state: MutableGameState) {
        productionProcessor.processSpiritMineProduction(state)
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

    /**
     * 处理年度事件（招募刷新、商人刷新、俸禄、弟子成长、外交等）。
     * 必须在 shadow transaction 外部调用，因为内部方法使用
     * [GameStateStore.update] 不可在 shadow 期间调用。
     */
    suspend fun processYearlyEvents() {
        val year = stateStore.gameData.value.gameYear
        eventProcessor.processYearlyEvents(year)
    }

    /**
     * 处理月度事件（盗窃检测、任务刷新、侦察过期、外交月度事件等）。
     * 必须在 shadow transaction 外部调用，因为内部方法使用
     * [GameStateStore.update] 不可在 shadow 期间调用。
     */
    suspend fun processMonthlyEvents() {
        val data = stateStore.gameData.value
        eventProcessor.processMonthlyEvents(data.gameYear, data.gameMonth)
    }

    /**
     * @deprecated 月度事件已移至 shadow 外部处理（见 [processMonthlyEvents]），
     * 此方法不再从 SettlementCoordinator 调用。保留供兼容。
     */
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

    // ── 空闲模式专用方法 ────────────────────────────────────────────

    /**
     * 空闲期间焦点弟子轻量 HFD 累积。
     *
     * 仅更新焦点弟子一人的修炼值/功法熟练度/装备孕养，
     *
     * @param focusedId 焦点弟子 ID
     * @param state 可变游戏状态
     */

    /**
     * 空闲期间战斗中弟子 HP/MP 轻量恢复。
     *
     * 仅恢复正在战斗中的弟子的 HP/MP（通过 DiscipleTables 直接操作），
     * 不组装完整 Disciple 对象，适用于空闲期间高频调用。
     *
     * @param state 可变游戏状态
     */
    fun recoverHpMpForCombatDisciples(state: MutableGameState) {
        val tables = state.discipleTables
        val data = state.gameData
        // 收集所有在战斗队或驻防中的弟子 ID
        val combatIds = mutableSetOf<Int>()
        for (team in data.battleTeams) {
            for (slot in team.slots) {
                if (slot.discipleId.isNotEmpty()) {
                    slot.discipleId.toIntOrNull()?.let { combatIds.add(it) }
                }
            }
        }
        // 驻防弟子
        for (garrison in data.warehouseGarrisons) {
            garrison.discipleId.toIntOrNull()?.let { combatIds.add(it) }
        }
        if (combatIds.isEmpty()) return

        val multiplier = 10.0  // phaseMultiplier
        val recoveryRate = GameConfig.Cultivation.DAILY_HP_MP_RECOVERY_RATE
        for (id in combatIds) {
            if (tables.isAlive[id] != 1) continue
            val curHp = tables.currentHps[id]
            val curMp = tables.currentMps[id]
            if (curHp < 0 && curMp < 0) continue

            val baseHp = tables.baseHps[id]
            val baseMp = tables.baseMps[id]
            val hpRecovery = (baseHp * recoveryRate * multiplier).toInt().coerceAtLeast(1)
            val mpRecovery = (baseMp * recoveryRate * multiplier).toInt().coerceAtLeast(1)

            val newHp = if (curHp < 0) curHp
                else (curHp + hpRecovery).coerceAtMost(baseHp)
            val newMp = if (curMp < 0) curMp
                else (curMp + mpRecovery).coerceAtMost(baseMp)

            if (newHp != curHp) tables.currentHps[id] = newHp
            if (newMp != curMp) tables.currentMps[id] = newMp
        }
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

    // ── 委托方法：ProductionProcessor ─────────────────────────────────

    internal suspend fun processBuildingProduction(year: Int, month: Int) {
        productionProcessor.processBuildingProduction(year, month)
    }

    internal suspend fun processHerbGardenGrowth(state: MutableGameState) {
        productionProcessor.processHerbGardenGrowth(state)
    }

    internal suspend fun processAutoPlant(state: MutableGameState) {
        productionProcessor.processAutoPlant(state)
    }

    internal suspend fun processSpiritFieldHarvest(state: MutableGameState) {
        productionProcessor.processSpiritFieldHarvest(state)
    }

    internal suspend fun processAutoAlchemy() {
        productionProcessor.processAutoAlchemy()
    }

    internal suspend fun processAutoForge() {
        productionProcessor.processAutoForge()
    }

    internal suspend fun processAutoAssign() {
        productionProcessor.processAutoAssign()
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
