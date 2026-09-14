package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.flow.StateFlow
import com.xianxia.sect.core.model.secretRealmMemberIds
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
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
    /** 本月焦点域已处理的旬数，用于月结时扣除已应用的 HP/MP 恢复和衰减 */
    val focusedPhaseCount: Int = 0
)

@GameService("CultivationService")
@Singleton
    // LongParameterList 豁免：修炼域聚合 Facade 的 10 个协作域均为独立注入面（结算/
    // 突破/事件/生产/招募/商人/洞府惰性/共享缓存）——人为聚合只为降参数计数，
    // 不改善内聚且劣化 DI 可读性，按 §2.24 惯例附理由压制
    @Suppress("LongParameterList")
class CultivationService @Inject constructor(
    internal val stateStore: GameStateStore,
    internal val cultivationCore: CultivationCore,
    internal val breakthroughHandler: DiscipleBreakthroughHandler,
    internal val cultivationSettlement: CultivationSettlement,
    internal val eventProcessor: CultivationEventProcessor,
    internal val productionProcessor: ProductionProcessor,
    internal val recruitService: RecruitService,
    internal val merchantAndRecruitService: MerchantAndRecruitService,
    internal val caveExplorationProcessor: Provider<CaveExplorationProcessor>,
    private val sharedState: CultivationSharedState,
) {
    // ── 共享状态委托 ──────────────────────────────────────────────────

    private val _highFrequencyData get() = sharedState.highFrequencyData

    /**
     * 月变/年变事件编排中枢访问入口（月变真相源切换后 GameEngineCore
     * 经此访问残留执行器所需的事件域服务——caveExplorationProcessor/
     * aiSectBeastAttackProcessor/secretRealmService 等 internal 成员）。
     * 独立访问器而非构造参数可见性变更——detekt baseline 按构造签名匹配，
     * 保持 private 参数避免 LongParameterList 豁免失配。
     */
    internal val eventProcessorForMonthSettlement: CultivationEventProcessor
        get() = eventProcessor

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

    fun processBreakthroughs(state: MutableGameState) {
        val tables = state.discipleTables
        val data = state.gameData

        // 1. 列级直读：快速筛选需要突破判定的弟子，避免 assembleAll() 全量组装
        // 远古秘境：探索中弟子不可突破（跳过判定候选）
        val secretRealmMemberIds = data.secretRealmMemberIds()
        val candidateDiscipleIds = mutableListOf<Int>()
        for (id in tables.ids) {
            val realm = tables.realms.getOrDefault(id, 9)
            val realmLayer = tables.realmLayers.getOrDefault(id, 1)
            val cultivation = tables.cultivations.getOrDefault(id, 0.0)
            val maxCultivation = computeMaxCultivation(realm, realmLayer, cultivation)
            // 死亡/远古秘境探索中/境界非法的弟子不参与突破判定
            val isEligibleState = tables.isAlive[id] == 1 && id !in secretRealmMemberIds && realm > 0
            // 未满修为或非满血蓝的弟子不参与突破判定（&& 短路与原守卫序逐位一致）
            val isCandidate = isEligibleState && cultivation >= maxCultivation &&
                cultivationCore.isDiscipleFullHpMp(id, tables, state)
            if (!isCandidate) continue
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
     * HP/MP 恢复由每旬结算统一处理（每旬 20%），战斗前不额外补血
     * （血量随时为最新值，战前补血=白送血量且可反复触发）。
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

    // ── 委托方法：CultivationEventProcessor ────────────────────────────

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

    // ── 委托方法：ProductionProcessor ─────────────────────────────────

    // ── 委托方法：MerchantAndRecruitService ────────────────────────────

    // ── 委托方法：CaveExplorationProcessor ─────────────────────────────

    // ── P0.3 优化辅助 ────────────────────────────────────────────

    internal suspend fun refreshTravelingMerchant(year: Int, month: Int) {
        merchantAndRecruitService.refreshTravelingMerchant(year, month)
    }

    internal suspend fun refreshRecruitList(year: Int) {
        recruitService.refreshRecruitList(year)
    }

    internal suspend fun refreshMerchantAcquisition(year: Int, month: Int) {
        merchantAndRecruitService.refreshMerchantAcquisition(year, month)
    }

    suspend fun processCaveLifecycle(year: Int, month: Int) {
        caveExplorationProcessor.get().processCaveLifecycle(year, month)
    }

    /**
     * L3a：丢弃队列中所有未执行延迟组（读档/切档入口调用，
     * 防旧档残留 op 作用于新档）。
     */
    fun clearYearlyOpsQueue() {
        eventProcessor.clearYearlyOpsQueue()
    }

    /**
     * L3a：存档前全量清空年变延迟队列（保证"快照 ⇒ 队列已空"不变量）。
     */
    fun flushYearlyOpsQueue() {
        eventProcessor.flushYearlyOpsQueue()
    }

    /**
     * L3a：逐 tick 预算 drain 年变延迟队列（GameEngineCore.tickInternal 每 tick 调用）。
     * 必须在 shadow transaction 外部调用（内部使用 [GameStateStore.update]）。
     */
    fun drainYearlyOpsQueue() {
        eventProcessor.drainYearlyOpsQueue()
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


    internal suspend fun processAutoForge() {
            productionProcessor.processAutoForge()
    }

    internal fun processBuildingProduction(year: Int, month: Int) {
            productionProcessor.processBuildingProduction(year, month)
    }

}
