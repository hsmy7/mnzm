package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.flow.StateFlow
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.ResidenceSlot
import com.xianxia.sect.core.model.secretRealmMemberIds
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.engine.domain.battle.BattleMemberData
import com.xianxia.sect.core.engine.domain.disciple.DiscipleService
import com.xianxia.sect.core.engine.annotation.GameService
import javax.inject.Inject
import javax.inject.Provider
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
    private val recruitService: RecruitService,
    private val merchantAndRecruitService: MerchantAndRecruitService,
    private val caveExplorationProcessor: Provider<CaveExplorationProcessor>,
    private val sharedState: CultivationSharedState,
    private val discipleService: DiscipleService
) {
    // ── 共享状态委托 ──────────────────────────────────────────────────

    private val _highFrequencyData get() = sharedState.highFrequencyData

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

    /** 单弟子旬级 HP/MP 恢复（委托 CultivationCore） */
    fun recoverHpMpSingle(
        state: MutableGameState, id: Int, phasesToSettle: Int = 1,
        equipmentMap: Map<String, EquipmentInstance>? = null,
        manualMap: Map<String, ManualInstance>? = null
    ) {
        cultivationCore.recoverHpMpSingle(
            state, id, phasesToSettle,
            equipmentMap = equipmentMap, manualMap = manualMap
        )
    }

    /**
     * 列直读版 HP/MP 恢复（2026-08-01 每旬热点专用，无 assemble）。
     * @return 是否发生写入
     */
    fun recoverHpMpSingleColumn(
        state: MutableGameState, id: Int, phasesToSettle: Int = 1,
        equipmentMap: Map<String, EquipmentInstance>? = null,
        manualMap: Map<String, ManualInstance>? = null,
        manualProficiencies: Map<String, List<ManualProficiencyData>>? = null
    ): Boolean = cultivationCore.recoverHpMpSingleColumn(
        state, id, phasesToSettle,
        equipmentMap = equipmentMap, manualMap = manualMap,
        manualProficiencies = manualProficiencies
    )

    fun calculateDiscipleCultivationPerPhase(
        disciple: com.xianxia.sect.core.model.Disciple,
        data: com.xianxia.sect.core.model.GameData,
        tables: com.xianxia.sect.core.state.DiscipleTables
    ): Double = cultivationCore.calculateDiscipleCultivationPerPhase(disciple, data, tables)

    /**
     * 每旬修炼累积：按当前速率累加1旬修为。
     *
     * 列直读版（无 assemble）：每旬热点循环（每 2 秒 × 每弟子）不再组装
     * 完整 Disciple 对象，速率计算走 [CultivationRateCalculator.calculateCultivationPerPhaseById]。
     *
     * 不更新检查点（checkpoint 只在速率变化点更新——政策/长老/丹药/突破）。
     * 每旬累积只改变修为、从不改变速率，每旬同步 checkpoint 会让
     * cultivationCheckpoints 恒等于 cultivations，投影退化为恒等函数，纯属浪费 2 次列写。
     */
    fun accumulateCultivationPerPhase(
        id: Int,
        state: com.xianxia.sect.core.state.MutableGameState,
        pendingRealtime: MutableMap<String, Double>? = null,
        residenceByDiscipleId: Map<Int, ResidenceSlot> = emptyMap(),
        buildingByInstanceId: Map<String, GridBuildingData> = emptyMap()
    ) {
        val tables = state.discipleTables
        if (tables.isAlive[id] != 1) return
        val realm = tables.realms.getOrDefault(id, 9)
        val realmLayer = tables.realmLayers.getOrDefault(id, 1)
        val curCult = tables.cultivations.getOrDefault(id, 0.0)
        val maxCultivation = computeMaxCultivation(realm, realmLayer, curCult)
        if (curCult >= maxCultivation) return

        val rate = cultivationCore.calculateCultivationPerPhaseById(
            id, state.gameData, tables, residenceByDiscipleId, buildingByInstanceId
        )
        if (rate <= 0.0) return

        tables.cultivations[id] = (curCult + rate).coerceAtMost(maxCultivation)

        // 2026-08-01 接回投影：realtimeCultivation 填充 getEffectiveCultivation
        // （checkpoint + rate×Δmonth×3）——修复"checkpoint 生产只写不读"的死代码埋雷。
        // 投影值在两次 checkpoint 之间为常数，值未变时跳过 Map 重建（每旬零额外开销）。
        val currentMonth = state.gameData.gameYear * 12 + state.gameData.gameMonth
        val projection = tables.getEffectiveCultivation(id, currentMonth, rate)
        val key = id.toString()
        if (pendingRealtime != null) {
            // P-6 批量模式：只累积投影变化的弟子（与已发射值比较），
            // 循环后由 flushRealtimeCultivation 单次发射（D 次发射 → 1 次）
            val prevMap = _highFrequencyData.value.realtimeCultivation
            if (prevMap == null || prevMap[key] != projection) {
                pendingRealtime[key] = projection
            }
            return
        }
        val cur = _highFrequencyData.value
        val prevMap = cur.realtimeCultivation
        if (prevMap == null || prevMap[key] != projection) {
            // Q-2：写入经共享状态更新入口（对外只读封装）
            sharedState.updateHighFrequencyData { c ->
                c.copy(realtimeCultivation = (c.realtimeCultivation ?: emptyMap()) + (key to projection))
            }
        }
    }

    /**
     * P-6：批量发射 realtimeCultivation 投影（D 次 StateFlow 发射 → 1 次，
     * O(D²) Map 重建 → O(D) 单次合并）。
     *
     * 最终 Map 与逐弟子发射逐 key 相同（prevMap + pending 单次合并）；
     * 无订阅中间值依赖（UI 只消费最终值）。pending 为空时不做任何事。
     *
     * @param pending 由 [accumulateCultivationPerPhase] 批量模式累积的投影变更
     */
    fun flushRealtimeCultivation(pending: MutableMap<String, Double>) {
        if (pending.isEmpty()) return
        // Q-2：写入经共享状态更新入口（对外只读封装）
        sharedState.updateHighFrequencyData { cur ->
            cur.copy(realtimeCultivation = (cur.realtimeCultivation ?: emptyMap()) + pending)
        }
    }

    /** 每旬功法熟练度增长（委托 CultivationCore） */
    fun processManualProficiencyPerPhase(state: MutableGameState) {
        cultivationCore.processManualProficiencyPerPhase(state)
    }

    /** 单弟子每旬功法熟练度增长（委托 CultivationCore） */
    fun processManualProficiencySingle(
        state: MutableGameState, id: Int,
        manualInstanceMap: Map<String, ManualInstance>? = null,
        pendingProficiencies: MutableMap<String, List<ManualProficiencyData>?>? = null,
        libraryDiscipleIds: Set<String>? = null
    ) {
        cultivationCore.processManualProficiencySingle(
            state, id, manualInstanceMap, pendingProficiencies, libraryDiscipleIds
        )
    }

    /** P-1 批量提交功法熟练度（委托 CultivationCore） */
    fun commitManualProficiencies(
        state: MutableGameState,
        pending: MutableMap<String, List<ManualProficiencyData>?>
    ) {
        cultivationCore.commitManualProficiencies(state, pending)
    }

    /** 每旬装备孕养经验增长（委托 CultivationCore） */
    fun processEquipmentNurturePerPhase(state: MutableGameState) {
        cultivationCore.processEquipmentNurturePerPhase(state)
    }

    /** 单弟子每旬装备孕养经验增长（委托 CultivationCore） */
    fun processEquipmentNurtureSingle(
        state: MutableGameState, id: Int,
        equipmentMap: Map<String, EquipmentInstance>? = null,
        sharedUpdates: MutableMap<String, EquipmentInstance>? = null
    ) {
        cultivationCore.processEquipmentNurtureSingle(state, id, equipmentMap, sharedUpdates)
    }

    /** P-2 批量提交装备孕养更新（委托 CultivationCore） */
    fun applyEquipmentUpdates(
        state: MutableGameState,
        updates: Map<String, EquipmentInstance>
    ) {
        cultivationCore.applyEquipmentUpdates(state, updates)
    }

    /**
     * 修炼速率检查点 — 在任意影响速率的操作后调用。
     * 同步 checkpoint 到当前游戏月份，使下次计算用新速率。
     */
    fun checkpointDisciple(id: Int, state: MutableGameState) {
        val currentMonth = state.gameData.gameYear * 12 + state.gameData.gameMonth
        state.discipleTables.checkpointDisciple(id, currentMonth)
    }

    /**
     * 全量弟子检查点 — 对所有存活弟子同步检查点。
     * 在影响全体弟子修炼速率的操作后调用（政策切换、全局丹药等）。
     */
    fun checkpointAllDisciples(state: MutableGameState) {
        val currentMonth = state.gameData.gameYear * 12 + state.gameData.gameMonth
        state.discipleTables.checkpointAllDisciples(currentMonth)
    }

    /**
     * 全量 Checkpoint：策略/长老变化后调用，重算所有生产系统的有效速率。
     * 炼丹/锻造的 completionMonth 会根据当前政策/长老重新计算。
     */
    suspend fun checkpointAllProduction() {
        productionProcessor.recalculateAllCompletionMonths()
    }

    /** 实时轨专用：自动从仓库装备/学习 */
    fun processAutoFromWarehouseRealtime(state: MutableGameState) {
        eventProcessor.processAutoFromWarehouseRealtime(state)
    }

    /** 实时轨专用：自动服用储物袋丹药（突破丹除外） */
    fun processAutoPillsRealtime(state: MutableGameState) {
        val data = state.gameData
        cultivationCore.processRealtimeAutoPills(
            state, data.gameYear, data.gameMonth, data.gamePhase
        )
    }

    /** 月度持续效果衰减（月结制专用） */
    fun applyMonthlyDurationDecay(
        tables: com.xianxia.sect.core.state.DiscipleTables, id: Int,
        focusedPhaseCount: Int = 0
    ) {
        cultivationCore.applyMonthlyDurationDecay(tables, id, focusedPhaseCount)
    }

    fun processBreakthroughs(state: MutableGameState) {
        val tables = state.discipleTables
        val data = state.gameData

        // 1. 列级直读：快速筛选需要突破判定的弟子，避免 assembleAll() 全量组装
        // 远古秘境：探索中弟子不可突破（跳过判定候选）
        val secretRealmMemberIds = data.secretRealmMemberIds()
        val candidateDiscipleIds = mutableListOf<Int>()
        for (id in tables.ids) {
            if (tables.isAlive[id] != 1) continue
            if (id in secretRealmMemberIds) continue
            val realm = tables.realms.getOrDefault(id, 9)
            if (realm <= 0) continue
            val realmLayer = tables.realmLayers.getOrDefault(id, 1)
            val cultivation = tables.cultivations.getOrDefault(id, 0.0)
            val maxCultivation = computeMaxCultivation(realm, realmLayer, cultivation)
            if (cultivation < maxCultivation) continue
            if (!cultivationCore.isDiscipleFullHpMp(id, tables, state)) continue
            candidateDiscipleIds.add(id)
        }

        if (candidateDiscipleIds.isEmpty()) return

        // 2. 仅对候选弟子按需组装完整对象
        val livingDisciples = candidateDiscipleIds.mapNotNull { tables.assemble(it) }
        breakthroughHandler.processRealtimeBreakthroughs(livingDisciples, data, state)
    }

    /**
     * 战斗前对指定出战弟子执行突破检测。
     *
     * 在 [MutableGameState] 事务内调用，仅处理目标弟子（≤10人）。
     * HP/MP 恢复由每旬结算统一处理（每旬 20%），战斗前不再额外补血
     * （2026-08-11 决策：血量随时为最新值，战前补血=白送血量且可反复触发）。
     *
     * @param state 可变游戏状态
     * @param discipleIds 出战弟子 ID 字符串列表
     */
    fun forceSettleDisciplesBeforeBattle(
        state: MutableGameState,
        discipleIds: List<String>
    ) {
        if (discipleIds.isEmpty()) return

        processBreakthroughsForDisciples(state, discipleIds)
    }

    /**
     * 仅对指定弟子列表执行突破检测。
     *
     * 复用 [DiscipleBreakthroughHandler.processRealtimeBreakthroughs]
     * 核心逻辑，但过滤为只处理目标弟子。
     */
    private fun processBreakthroughsForDisciples(
        state: MutableGameState,
        discipleIds: List<String>
    ) {
        val tables = state.discipleTables
        val targetDisciples = discipleIds.mapNotNull { idStr ->
            val id = idStr.toIntOrNull() ?: return@mapNotNull null
            if (tables.isAlive[id] != 1) return@mapNotNull null
            tables.assemble(id)
        }
        if (targetDisciples.isEmpty()) return
        breakthroughHandler.processRealtimeBreakthroughs(
            targetDisciples, state.gameData, state
        )
    }


    suspend fun settleSalaryOnBreakthrough(discipleId: String, currentYear: Int) {
        cultivationSettlement.settleSalaryOnBreakthrough(discipleId, currentYear)
    }

    suspend fun processAnnualSalary(year: Int) {
        cultivationSettlement.processAnnualSalary(year)
    }

    fun processResidenceLoyalty(state: MutableGameState) {
        cultivationSettlement.processResidenceLoyalty(state)
    }

    /** 月度自动排班 + 住所忠诚度，在事务 A 内由 [GameEngineCore.processMonthYearChange] 调用。 */
    fun processMonthlyAutoAssignments(state: MutableGameState) {
        productionProcessor.processAutoAssign(state)
        cultivationSettlement.processResidenceLoyalty(state)
    }

    internal fun processPolicyCosts(state: MutableGameState): PolicyCostResult {
        return cultivationSettlement.processPolicyCosts(state)
    }

    internal fun processPolicyMonthlyEffects(state: MutableGameState) {
        cultivationSettlement.processPolicyMonthlyEffects(state)
    }

    // ── 委托方法：CultivationEventProcessor ────────────────────────────

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
    fun processYearlyEvents() {
        val year = stateStore.gameData.value.gameYear
        eventProcessor.processYearlyEvents(year)
    }

    /**
     * 处理月度事件（盗窃检测、任务刷新、侦察过期、外交月度事件等）。
     * 必须在 shadow transaction 外部调用，因为内部方法使用
     * [GameStateStore.update] 不可在 shadow 期间调用。
     */
    fun processMonthlyEvents() {
        val data = stateStore.gameData.value
        eventProcessor.processMonthlyEvents(data.gameYear, data.gameMonth)
    }

    /**
     * 带状态版本的月度事件处理 — 在已存在的事务内使用。
     * 操作在传入的 state 上，而非打开新的 [stateStore.update]。
     */
    fun processMonthlyEventsOnState(state: MutableGameState) {
        val data = state.gameData
        eventProcessor.processMonthlyEvents(data.gameYear, data.gameMonth, state)
    }

    /**
     * L3a：逐 tick 预算 drain 年变延迟队列（GameEngineCore.tickInternal 每 tick 调用）。
     * 必须在 shadow transaction 外部调用（内部使用 [GameStateStore.update]）。
     */
    fun drainYearlyOpsQueue() {
        eventProcessor.drainYearlyOpsQueue()
    }

    /**
     * L3a：存档前全量清空年变延迟队列（保证"快照 ⇒ 队列已空"不变量）。
     */
    fun flushYearlyOpsQueue() {
        eventProcessor.flushYearlyOpsQueue()
    }

    /**
     * L3a：丢弃队列中所有未执行延迟组（读档/切档入口调用，
     * 防旧档残留 op 作用于新档——对抗性审查 F1 修复）。
     */
    fun clearYearlyOpsQueue() {
        eventProcessor.clearYearlyOpsQueue()
    }

    fun getHighFrequencyData(): StateFlow<HighFrequencyData> = _highFrequencyData

    fun resetHighFrequencyData() {
        // Q-2：写入经共享状态更新入口（对外只读封装）
        sharedState.updateHighFrequencyData { HighFrequencyData() }
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

    suspend fun updateDiscipleHpMpAfterBattle(battleMembers: List<BattleMemberData>) {
        eventProcessor.updateDiscipleHpMpAfterBattle(battleMembers)
    }

    // ── 委托方法：ProductionProcessor ─────────────────────────────────

    internal fun processBuildingProduction(year: Int, month: Int) {
            productionProcessor.processBuildingProduction(year, month)
    }

    internal fun processSpiritFieldHarvest(state: MutableGameState) {
            productionProcessor.processSpiritFieldHarvest(state)
    }

    internal suspend fun processAutoAlchemy() {
            productionProcessor.processAutoAlchemy()
    }

    internal suspend fun processAutoForge() {
            productionProcessor.processAutoForge()
    }

    /** 影子状态批量生产循环（委托 ProductionProcessor 的 shadow 版方法） */
    internal suspend fun processMonthlyProductionOnSlots(
        slots: MutableList<ProductionSlot>,
        state: MutableGameState,
        months: Int
    ) {
        productionProcessor.processMonthlyProductionOnSlots(slots, state, months)
    }

    // ── 委托方法：MerchantAndRecruitService ────────────────────────────

    internal suspend fun refreshTravelingMerchant(year: Int, month: Int) {
        merchantAndRecruitService.refreshTravelingMerchant(year, month)
    }

    internal fun refreshTravelingMerchantManual(): Boolean {
        return merchantAndRecruitService.refreshTravelingMerchantManual()
    }

    internal suspend fun refreshMerchantAcquisition(year: Int, month: Int) {
        merchantAndRecruitService.refreshMerchantAcquisition(year, month)
    }

    internal suspend fun refreshRecruitList(year: Int) {
        recruitService.refreshRecruitList(year)
    }

    // ── 委托方法：CaveExplorationProcessor ─────────────────────────────

    suspend fun processCaveLifecycle(year: Int, month: Int) {
        caveExplorationProcessor.get().processCaveLifecycle(year, month)
    }

    // ── P0.3 优化辅助 ────────────────────────────────────────────

    /**
     * 根据境界/层数/修为计算当前境界满修为值（即突破所需修为上限）。
     * 公式与 [DiscipleCore.maxCultivation] 一致，用于列级直读过滤，
     * 避免为了获取 maxCultivation 而组装完整的 Disciple 对象。
     */
    private fun computeMaxCultivation(realm: Int, realmLayer: Int, cultivation: Double): Double {
        if (realm == 0) return cultivation
        val base = GameConfig.Realm.get(realm).cultivationBase
        val nextBase = GameConfig.Realm.get(realm - 1).cultivationBase
        val maxLayers = GameConfig.Realm.get(realm).maxLayers
        return base + (realmLayer - 1) * (nextBase - base).toDouble() / maxLayers
    }
}
