package com.xianxia.sect.core.engine.domain.settlement

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.EquipmentNurtureSystem
import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.engine.service.HighFrequencyData
import com.xianxia.sect.core.engine.LazyEvaluationDispatcher
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameNotification
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * 月度结算协调器：负责编排一次月度/年度结算的全流程阶段调度。
 *
 * 主要职责：
 * - 接收 shadow 状态，构建 [SettlementCache]，按阶段调度 [SettlementScheduler]
 * - 焦点弟子即时结算（每 100ms tick 推进修炼值，月度结算时合并差额）
 * - 非焦点弟子分批结算（clean / dirty 两类批次）
 * - 生产、世界事件、年度老化等阶段串联
 * - 非焦点域热控分批：根据 [ThermalMonitor] 决定非焦点弟子结算间隔
 *
 * 关键约束（项目硬约束）：
 * - 突破检查直接验证 `cultivation >= maxCultivation && full health/mana`，不依赖 BREAKTHROUGH dirtyFlag
 * - HFD（HighFrequencyData）必须在每次月度结算后重置
 * - SettlementCache 必须每月从零重建
 */
@Singleton
class SettlementCoordinator @Inject constructor(
    private val domain: SettlementDomainFacade,
    private val world: SettlementWorldFacade,
    private val stateStore: GameStateStore,
    private val scheduler: SettlementScheduler,
    private val metricsCollector: SettlementMetricsCollector
) {
    @Volatile
    private var shadowState: MutableGameState? = null
    @Volatile
    private var currentCache: SettlementCache? = null

    // 修炼速率指纹缓存：仅在结构变化时重建 SettlementCache
    private var lastFingerprint: CultivationRateFingerprint? = null
    private var reusableCache: SettlementCache? = null

    // ═══ 非焦点域批量结算状态（空闲 + 活跃共用） ═══

    // 分批影子 — 跨月持有的可变状态
    @Volatile
    private var batchShadow: MutableGameState? = null
    // 分批缓存 — 批次开始时的修炼速率
    @Volatile
    private var batchCache: SettlementCache? = null
    // 双指纹快照
    private var batchCultFingerprint: CultivationRateFingerprint? = null
    private var batchProdFingerprint: ProductionRateFingerprint? = null
    // 累积计数
    private var batchAccumulatedPhases: Int = 0
    private var batchAccumulatedMonths: Int = 0
    private var batchHasYearlyChange: Boolean = false
    // 80% 实时轨槽位
    private var batchRealtimeSlots: Set<String> = emptySet()
    // 上次全量结算墙壁时钟（空闲 30s 触发用）
    private var lastBatchSettleWallMs: Long = 0L
    // 分批模式
    private var batchMode: BatchMode = BatchMode.NONE
    // 焦点域状态 — 实时轨正在处理时，批量轨跳过对应领域
    private var batchCultivationFocused: Boolean = false
    private var batchProductionFocused: Boolean = false

    /**
     * 分批模式。
     */
    enum class BatchMode {
        /** 未在分批模式中 */
        NONE,
        /** 空闲模式 — 全部域进入分批 */
        IDLE,
        /** 活跃模式 — 仅非焦点域进入分批 */
        ACTIVE_NON_FOCUS
    }

    /**
     * [accumulateBatch] 的返回结果。
     */
    sealed interface BatchAccumulateResult {
        /** 正常累积，无需额外操作 */
        data object Accumulated : BatchAccumulateResult
        /** 指纹或实时轨变化触发微结算 */
        data object MicroSettled : BatchAccumulateResult
        /** 全量结算 + swap 已完成 */
        data object FullSettled : BatchAccumulateResult
    }

    /** 当前是否处于分批模式 */
    val isInBatchMode: Boolean get() = batchMode != BatchMode.NONE

    /**
     * 实时轨槽位映射为需要激活的域集合。
     * 调用方应在 [com.xianxia.sect.core.GameEngineCore.getActiveDomains]
     * 中合并这些域，使 ≥80% 进度的槽位绕过热控分批，获得 100ms tick 处理。
     */
    val batchRealtimeDomains: Set<com.xianxia.sect.core.engine.system.FocusDomain>
        get() {
            if (batchRealtimeSlots.isEmpty()) return emptySet()
            val domains = mutableSetOf<com.xianxia.sect.core.engine.system.FocusDomain>()
            for (slot in batchRealtimeSlots) {
                when {
                    slot.startsWith("cultivation:") ||
                    slot.startsWith("nurture:") ||
                    slot.startsWith("proficiency:") ||
                    slot.startsWith("reflection:") -> domains.add(
                        com.xianxia.sect.core.engine.system.FocusDomain.DISCIPLE_LIST
                    )
                    slot.startsWith("bloodRefinement:") ||
                    slot.startsWith("spiritField:") ||
                    slot.startsWith("production:") -> domains.add(
                        com.xianxia.sect.core.engine.system.FocusDomain.BUILDING_LIST
                    )
                    slot.startsWith("mission:") -> domains.add(
                        com.xianxia.sect.core.engine.system.FocusDomain.MISSION_HALL
                    )
                }
            }
            return domains
        }

    /**
     * 是否仍有未完成的结算阶段待执行。
     */
    val hasPendingWork: Boolean get() = scheduler.hasPendingWork

    fun resetBatchClock() { lastBatchSettleWallMs = System.currentTimeMillis() }

    private val timer = SettlementTimer()
    private var metricsBuilder = SettlementMetricsBuilder()

    // ── 分批模式 API ──────────────────────────────────────────

    /**
     * 进入分批模式。
     *
     * @param shadow 分批期间持有的可变游戏状态
     * @param cache  批次开始时的修炼速率缓存
     * @param mode   分批模式（IDLE / ACTIVE_NON_FOCUS）
     */
    fun enterBatchMode(
        shadow: MutableGameState,
        cache: SettlementCache,
        mode: BatchMode
    ) {
        batchShadow = shadow
        batchCache = cache
        batchMode = mode
        batchAccumulatedPhases = 0
        batchAccumulatedMonths = 0
        batchHasYearlyChange = false
        batchCultFingerprint = computeCultivationFingerprint(shadow)
        batchProdFingerprint = computeProductionFingerprint(shadow)
        batchRealtimeSlots = classifySlotsProgress(shadow)
        lastBatchSettleWallMs = System.currentTimeMillis()
        DomainLog.i(TAG, "Entered batch mode: $mode " +
            "realtimeSlots=${batchRealtimeSlots.size} " +
            "disciples=${shadow.discipleTables.ids.count { shadow.discipleTables.isAlive[it] == 1 }}")
    }

    /**
     * 每帧/每月累积。
     *
     * 执行双指纹检测 + 实时轨检测，在结构或进度变化时触发微结算。
     *
     * @return [BatchAccumulateResult] 指示本次累积的结果
     */
    fun accumulateBatch(phasesToAdd: Int, monthChanged: Boolean, yearChanged: Boolean) {
        if (phasesToAdd > 0) batchAccumulatedPhases += phasesToAdd
        if (monthChanged) batchAccumulatedMonths++

        val now = System.currentTimeMillis()
        if (now - lastBatchSettleWallMs < 30_000L) return

        lastBatchSettleWallMs = now

        // 临时影子做指纹检测（只读，用完即弃不 swap）
        val tempShadow = stateStore.createSettlementShadow()
        try {
            val newFp = computeFingerprint(tempShadow)
            val newRt = classifySlotsProgress(tempShadow)
            if (newFp != lastFingerprint || newRt != batchRealtimeSlots) {
                DomainLog.d(TAG, "Fingerprint/80% changed")
                if (batchAccumulatedPhases > 0 || batchAccumulatedMonths > 0)
                    domain.cultivationService.resetHighFrequencyData()
                val cache = SettlementCache(tempShadow)
                reusableCache = cache
                domain.cultivationService.cachedCultivationRates = cache.cultivationRateCache
                lastFingerprint = newFp; batchRealtimeSlots = newRt
                batchAccumulatedPhases = 0; batchAccumulatedMonths = 0
            }
        } finally {
            shadowState = null; currentCache = null; scheduler.reset()
        }
    }

    /** 指纹或实时轨变化 → 微结算 + 重建缓存 */
    private suspend fun handleBatchChange(
        shadow: MutableGameState,
        newCultFp: CultivationRateFingerprint,
        newProdFp: ProductionRateFingerprint,
        newRealtime: Set<String>
    ): BatchAccumulateResult {
        if (batchAccumulatedPhases > 0 || batchAccumulatedMonths > 0) {
            DomainLog.d(TAG, "Batch trigger: phases=$batchAccumulatedPhases " +
                "months=$batchAccumulatedMonths")
            val cache = batchCache ?: return BatchAccumulateResult.Accumulated
            // 焦点域在实时轨处理，批量轨跳过对应领域
            if (!batchCultivationFocused) {
                cultivationMicroSettle(shadow, cache, batchAccumulatedPhases)
            }
            if (!batchProductionFocused && batchAccumulatedMonths > 0) {
                productionMicroSettle(shadow, batchAccumulatedMonths)
            }
        }
        batchCache = SettlementCache(shadow)
        batchCultFingerprint = newCultFp
        batchProdFingerprint = newProdFp
        batchRealtimeSlots = newRealtime
        batchAccumulatedPhases = 0
        batchAccumulatedMonths = 0
        batchHasYearlyChange = false
        return BatchAccumulateResult.MicroSettled
    }

    // checkBatchClock 已删除 — accumulateBatch 使用临时影子指纹检测替代

    /**
     * 退出分批模式，强制执行全量结算 + swap。
     */
    suspend fun exitBatchMode() {
        if (batchMode == BatchMode.NONE) return
        if (batchAccumulatedPhases > 0 || batchAccumulatedMonths > 0) {
            DomainLog.i(TAG, "Exit batch mode — settling accumulated " +
                "phases=$batchAccumulatedPhases months=$batchAccumulatedMonths")
            doBatchFullSettle()
        }
        cancelBatch()
    }

    /**
     * 取消分批模式，丢弃累积的中间状态（不 swap）。
     */
    fun cancelBatch() {
        batchShadow = null
        batchCache = null
        batchCultFingerprint = null
        batchProdFingerprint = null
        batchAccumulatedPhases = 0
        batchAccumulatedMonths = 0
        batchHasYearlyChange = false
        batchRealtimeSlots = emptySet()
        batchMode = BatchMode.NONE
        shadowState = null
        currentCache = null
        scheduler.reset()
    }

    /**
     * 全量结算：末段微结算 + swap + 重建窗口。
     */
    private suspend fun doBatchFullSettle() {
        val shadow = batchShadow ?: return
        val cache = batchCache ?: return

        // 焦点域在实时轨处理，批量轨跳过对应领域
        if (!batchCultivationFocused) {
            cultivationMicroSettle(shadow, cache, batchAccumulatedPhases)
        }
        if (!batchProductionFocused && batchAccumulatedMonths > 0) {
            productionMicroSettle(shadow, batchAccumulatedMonths)
        }
        if (batchHasYearlyChange) {
            processAgingAndDeath(shadow)
        }

        stateStore.swapFromShadow(shadow)
        domain.cultivationService.cachedCultivationRates = cache.cultivationRateCache
        if (batchAccumulatedMonths > 0 || batchAccumulatedPhases > 0) {
            domain.cultivationService.resetHighFrequencyData()
        }

        // 重建影子 + 缓存为新窗口
        val newShadow = stateStore.createSettlementShadow()
        batchShadow = newShadow
        batchCache = SettlementCache(newShadow)
        batchCultFingerprint = computeCultivationFingerprint(newShadow)
        batchProdFingerprint = computeProductionFingerprint(newShadow)
        batchRealtimeSlots = classifySlotsProgress(newShadow)
        batchAccumulatedPhases = 0
        batchAccumulatedMonths = 0
        batchHasYearlyChange = false
        lastBatchSettleWallMs = System.currentTimeMillis()

        DomainLog.i(TAG, "Batch full settle: phases=$batchAccumulatedPhases " +
            "months=$batchAccumulatedMonths " +
            "realtimeSlots=${batchRealtimeSlots.size}")
    }

    /**
     * 执行一次结算步骤（一帧）。
     *
     * 由外部驱动循环按帧调用，将单步委托给 [SettlementScheduler.executeStep]。
     * 当 scheduler 报告当前批次已完成时，触发 [onSettlementComplete] 收尾。
     *
     * 若执行过程抛出非取消异常，会重置协调器状态以避免脏状态残留。
     *
     * @return true 表示当前调度批次已全部完成（可进入下一轮调度）；false 表示仍有后续步骤。
     */
    suspend fun executeStep(): Boolean {
        val shadow = shadowState ?: return true

        return try {
            val completed = scheduler.executeStep(shadow)
            if (completed) {
                onSettlementComplete()
            }
            completed
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainLog.e(TAG, "Settlement executeStep failed — resetting coordinator to recover", e)
            resetOnError()
            false
        }
    }

    private fun resetOnError() {
        shadowState = null
        currentCache = null
        reusableCache = null
        lastFingerprint = null
        scheduler.reset()
    }

    /**
     * 调度一次月度结算。
     *
     * 活跃模式下每月调用，不受热控影响（热控分批走 [accumulateBatch] 路径）。
     * 设置 shadow 状态、重置 metrics，然后依次注册阶段到 [SettlementScheduler]。
     *
     * @param shadow 本次结算使用的可变游戏状态（shadow）
     * @param isProductionFocused 生产域是否焦点（保留兼容，当前所有域按月结算）
     */
    fun scheduleYearly(shadow: MutableGameState) {
        shadowState = shadow
        scheduler.scheduleYearly(
            shadow,
            Phase_AgingAndDeath { s -> world.childBirthSystem.onYearTick(s) },
            Phase_RecruitRefresh { },
            Phase_AISectYearly { },
            Phase_AllianceExpiry { }
        )
    }

    /**
     * 结算完成后的收尾流程。
     *
     * 1. 将 shadow 状态原子换入 [GameStateStore]；
     * 2. 将当月修炼速率缓存同步给 [CultivationService]，供焦点域 100ms tick 推进修炼值；
     * 3. 重置 HFD（HighFrequencyData）—— 满足"每次月度结算后必须重置"硬约束；
     * 4. 构建并记录 [SettlementMetrics]；
     * 5. 清空 shadowState / currentCache 并重置 scheduler。
     */
    suspend fun onSettlementComplete() {
        val shadow = shadowState ?: return
        timer.start()
        stateStore.swapFromShadow(shadow)
        val swapMs = timer.stop()

        // 同步修炼速率缓存到 CultivationService，供焦点域 100ms tick 推进修炼值
        currentCache?.let { cache ->
            domain.cultivationService.cachedCultivationRates = cache.cultivationRateCache
        }

        // 正常月度/年度结算始终重置 HFD
        domain.cultivationService.resetHighFrequencyData()

        val metrics = metricsBuilder.build(
            monthYear = shadow.gameData.gameYear to shadow.gameData.gameMonth,
            totalDiscipleCount = shadow.discipleTables.ids.count { shadow.discipleTables.isAlive[it] == 1 },
            shadowSwapMs = swapMs,
            frameCount = scheduler.getFrameCount()
        )
        metricsCollector.record(metrics)

        shadowState = null
        currentCache = null
        scheduler.reset()
    }

    /**
     * 取消所有待执行的结算工作。
     *
     * 丢弃当前 shadow 与 cache，并重置 scheduler。已写入 shadow 的中间状态不会被回滚到 store。
     */
    fun cancelPendingWork() {
        shadowState = null
        currentCache = null
        reusableCache = null
        lastFingerprint = null
        scheduler.reset()
        // 也清理分批状态
        cancelBatch()
    }

    /**
     * 焦点弟子即时结算（每 100ms tick 已推进修炼值的月度合并）。
     *
     * 焦点弟子由 [CultivationService] 在高频 tick（约 100ms）中持续推进修炼、HP/MP 恢复、
     * 持续效果衰减等。本方法在月度结算时合并 HFD 中已结算的部分与月度总额的差额，
     * 避免重复结算。
     *
     * 步骤：
     * 1. 读取焦点弟子 ID，校验存活；
     * 2. 修炼值：月度总额 = rate × 3旬，扣除 HFD 已推进部分（alreadyGained），写入组件表；
     * 3. HP/MP 月度恢复：扣除焦点域已处理旬数（[hfd.focusedPhaseCount]）；
     * 4. 持续效果月度衰减：同样扣除焦点域已处理旬数；
     * 5. 突破检查：当 `cultivation >= maxCultivation && full health/mana` 时触发，
     *    不依赖 BREAKTHROUGH dirtyFlag（项目硬约束）；
     *    突破成功将溢出修为带入新境界；
     * 6. 重新组装弟子，计算功法熟练度、装备温养、忠诚度（居住加成）增量；
     * 7. 写入下一次修炼完成时间。
     *
     * 关键约束：
     * - 突破条件为 `cultivation >= maxCultivation && isDiscipleFullHpMp(disciple)`，
     *   不依赖 BREAKTHROUGH flag；
     * - HFD 必须在每次月度结算后由 [onSettlementComplete] 重置；
     * - SettlementCache 必须每月从零重建。
     *
     * @param shadow 本次结算使用的可变游戏状态
     * @param cache 当月构建的结算缓存（修炼速率、弟子映射等）
     */
    private suspend fun processFocusedDiscipleImmediate(shadow: MutableGameState, cache: SettlementCache) {
        timer.start()
        val focusedId = null ?: run {
            metricsBuilder.focusedDiscipleMs = timer.stop()
            return
        }
        val tables = shadow.discipleTables
        val focusedIdInt = focusedId.toInt()
        if (!tables.ids.contains(focusedIdInt) || tables.isAlive[focusedIdInt] != 1) {
            metricsBuilder.focusedDiscipleMs = timer.stop()
            return
        }

        val disciple = tables.assemble(focusedIdInt)
        val data = shadow.gameData

        // HFD 快照在月结期间只读不写（仅在 onSettlementComplete 中重置），
        // 缓存一次避免 5 个子方法各自调用 getHighFrequencyData().value
        val hfd = domain.cultivationService.getHighFrequencyData().value

        val (newCultivation, overflow) = mergeFocusedCultivation(disciple, cache, tables, focusedIdInt, hfd)
        recoverFocusedHpMp(tables, focusedIdInt, hfd)
        applyFocusedDurationDecay(tables, focusedIdInt, hfd)
        checkFocusedBreakthrough(disciple, newCultivation, overflow, shadow, cache, tables, focusedIdInt)

        // 重新组装以获取最新状态（突破可能已改变）
        val discipleAfterBreakthrough = tables.assemble(focusedIdInt)
        updateFocusedProficiency(discipleAfterBreakthrough, data, cache, shadow, tables, focusedIdInt, hfd)

        metricsBuilder.focusedDiscipleMs = timer.stop()
    }

    /**
     * 修炼值合并：月度总额 = rate × 3旬，扣除 HFD 已推进部分（alreadyGained），写入组件表。
     *
     * @param hfd 月结期间缓存的 HFD 快照（只读）
     * @return (newCultivation, overflow) — 突破检查需要这两个值
     */
    private fun mergeFocusedCultivation(
        disciple: Disciple,
        cache: SettlementCache,
        tables: DiscipleTables,
        focusedIdInt: Int,
        hfd: HighFrequencyData
    ): Pair<Double, Double> {
        val rate = cache.cultivationRateCache[disciple.id] ?: 0.0
        val monthlyGain = rate * 3  // 3旬/月

        val focusedGains = hfd.cultivationUpdates
        val alreadyGained = focusedGains[disciple.id] ?: 0.0
        val netGain = (monthlyGain - alreadyGained).coerceAtLeast(0.0)

        val rawCultivation = disciple.cultivation + netGain
        val overflow = if (rawCultivation > disciple.maxCultivation)
            rawCultivation - disciple.maxCultivation else 0.0
        val newCultivation = rawCultivation.coerceAtMost(disciple.maxCultivation)
        tables.cultivations[focusedIdInt] = newCultivation

        return Pair(newCultivation, overflow)
    }

    /**
     * 月度 HP/MP 恢复（扣除焦点域已处理旬数 [hfd.focusedPhaseCount]）。
     *
     * @param hfd 月结期间缓存的 HFD 快照（只读）
     */
    private fun recoverFocusedHpMp(tables: DiscipleTables, focusedIdInt: Int, hfd: HighFrequencyData) {
        domain.cultivationService.recoverMonthlyHpMp(tables, focusedIdInt, hfd.focusedPhaseCount)
    }

    /**
     * 月度持续效果衰减（扣除焦点域已处理旬数 [hfd.focusedPhaseCount]）。
     *
     * @param hfd 月结期间缓存的 HFD 快照（只读）
     */
    private fun applyFocusedDurationDecay(tables: DiscipleTables, focusedIdInt: Int, hfd: HighFrequencyData) {
        domain.cultivationService.applyMonthlyDurationDecay(tables, focusedIdInt, hfd.focusedPhaseCount)
    }

    /**
     * 突破检查：当 `cultivation >= maxCultivation && full health/mana` 时触发，
     * 不依赖 BREAKTHROUGH dirtyFlag（项目硬约束）。
     * 突破成功将溢出修为带入新境界。
     */
    private fun checkFocusedBreakthrough(
        disciple: Disciple,
        newCultivation: Double,
        overflow: Double,
        shadow: MutableGameState,
        cache: SettlementCache,
        tables: DiscipleTables,
        focusedIdInt: Int
    ) {
        if (newCultivation >= disciple.maxCultivation && isDiscipleFullHpMp(disciple)) {
            val updatedDisciple = processBreakthroughForDisciple(disciple, shadow, cache)
            writeDiscipleToTables(updatedDisciple, tables)
            // 溢出修为带入新境界
            if (overflow > 0 && updatedDisciple.realm > 0) {
                tables.cultivations[focusedIdInt] =
                    overflow.coerceAtMost(updatedDisciple.maxCultivation)
            }
        }
    }

    /**
     * 熟练度/温养/忠诚度增量：重新计算功法熟练度、装备温养、忠诚度（居住加成），
     * 并写入下一次修炼完成时间。
     *
     * @param hfd 月结期间缓存的 HFD 快照（只读）
     */
    private fun updateFocusedProficiency(
        disciple: Disciple,
        data: com.xianxia.sect.core.model.GameData,
        cache: SettlementCache,
        shadow: MutableGameState,
        tables: DiscipleTables,
        focusedIdInt: Int,
        hfd: HighFrequencyData
    ) {
        val focusedProfGains = hfd.proficiencyUpdates
        val focusedNurtureGains = hfd.nurtureUpdates

        val profUpdates = calculateProficiencyGains(disciple, data, cache, focusedProfGains[disciple.id] ?: emptyMap())
        val nurtureUpdates = calculateNurtureGains(disciple, shadow, cache, focusedNurtureGains[disciple.id] ?: emptyMap())

        // 忠诚度（居住加成）
        val loyaltyDelta = calculateLoyaltyDelta(disciple, cache)
        if (loyaltyDelta != 0) {
            tables.loyalties[focusedIdInt] = (tables.loyalties[focusedIdInt] + loyaltyDelta).coerceAtLeast(0)
        }

        // 结算后计算下一次完成时间
        val currentAbsoluteMonth = LazyEvaluationDispatcher.toAbsoluteMonth(data.gameYear, data.gameMonth)
        val cultivationRate = cache.cultivationRateCache[disciple.id] ?: 0.0
        writeCultivationCompletionToTables(disciple, tables, currentAbsoluteMonth, cultivationRate)

        applyNurtureUpdates(shadow, nurtureUpdates)
        applyProficiencyUpdates(shadow, profUpdates)
    }

    private fun processCleanDiscipleBatch(shadow: MutableGameState, cache: SettlementCache) {
        timer.start()
        val data = shadow.gameData
        val focusedId = null
        val currentAbsoluteMonth = LazyEvaluationDispatcher.toAbsoluteMonth(data.gameYear, data.gameMonth)
        val tables = shadow.discipleTables

        val cleanIds = tables.ids.filter { id ->
            tables.isAlive[id] == 1 &&
            id.toString() != focusedId &&
            id.toString() in cache.cleanDiscipleIds
        }

        if (cleanIds.isEmpty()) {
            metricsBuilder.cleanBatchMs = timer.stop()
            return
        }

        val hfd = domain.cultivationService.getHighFrequencyData().value
        val focusedGains = hfd.cultivationUpdates
        val focusedPhaseCount = hfd.focusedPhaseCount

        for (id in cleanIds) {
            val batchMonths = 1  // 正常月度结算，热控分批由 accumulateBatch 处理

            val disciple = tables.assemble(id)
            val rate = cache.cultivationRateCache[disciple.id] ?: 0.0

            if (batchMonths > 0) {
                // 非焦点域热控分批：修炼总值 = 月修炼值 × 批次数
                // alreadyGained 应从总额扣除一次，而非从每月扣除
                val monthlyGain = rate * 3
                val totalMonthlyGain = monthlyGain * batchMonths
                // 非焦点弟子仅累积 HFD、不写入实际修为，
                // 直接用 totalMonthlyGain，无需 alreadyGained 加减
                val totalGain = totalMonthlyGain
                val rawCultivation = disciple.cultivation + totalGain
                val overflow = (rawCultivation - disciple.maxCultivation).coerceAtLeast(0.0)
                val newCultivation = rawCultivation.coerceAtMost(disciple.maxCultivation)
                tables.cultivations[id] = newCultivation

                // 月度 HP/MP 恢复：首月扣除 phase tick 已处理部分，
                // 剩余批次月全额处理（历史月份无 phase tick 可扣）
                domain.cultivationService.recoverMonthlyHpMp(tables, id, focusedPhaseCount)
                repeat(batchMonths - 1) {
                    domain.cultivationService.recoverMonthlyHpMp(tables, id, 0)
                }

                // 月度持续效果衰减：同上，首月扣除，剩余月全额
                domain.cultivationService.applyMonthlyDurationDecay(tables, id, focusedPhaseCount)
                repeat(batchMonths - 1) {
                    domain.cultivationService.applyMonthlyDurationDecay(tables, id, 0)
                }

                // 突破检查：仅修为 ≥80% 时检查完整条件
                val nearMaxCult = newCultivation >= disciple.maxCultivation * 0.8
                val needsBreakthrough = nearMaxCult &&
                    newCultivation >= disciple.maxCultivation &&
                    isDiscipleFullHpMp(disciple)
                var dAfterBreakthrough: Disciple? = null
                if (needsBreakthrough) {
                    dAfterBreakthrough = processBreakthroughForDisciple(disciple, shadow, cache)
                    writeDiscipleToTables(dAfterBreakthrough, tables)
                    // 溢出修为带入新境界
                    if (overflow > 0 && dAfterBreakthrough.realm > 0) {
                        tables.cultivations[id] = overflow.coerceAtMost(dAfterBreakthrough.maxCultivation)
                    }
                }

                val loyaltyDelta = calculateLoyaltyDelta(disciple, cache)
                if (loyaltyDelta != 0) {
                    tables.loyalties[id] = (tables.loyalties[id] + loyaltyDelta * batchMonths).coerceAtLeast(0)
                }

                val remaining = (dAfterBreakthrough?.maxCultivation ?: disciple.maxCultivation) -
                    tables.cultivations[id]
                val monthsToNext = LazyEvaluationDispatcher.estimateMonthsToNextBreakthrough(remaining, rate)
                tables.cultivationCompletionMonths[id] = currentAbsoluteMonth + monthsToNext
                tables.cultivationCompletionPhases[id] = 1
            }
            // batchMonths == 0: 跳过非焦点修炼结算，等待累积到批次阈值
        }

        metricsBuilder.cleanBatchMs = timer.stop()
    }

    private fun processDirtyDiscipleBatch(shadow: MutableGameState, cache: SettlementCache, offset: Int): Int {
        timer.start()
        val data = shadow.gameData
        val focusedId = null
        val currentAbsoluteMonth = LazyEvaluationDispatcher.toAbsoluteMonth(data.gameYear, data.gameMonth)
        val tables = shadow.discipleTables

        val dirtyIds = tables.ids.filter { id ->
            tables.isAlive[id] == 1 &&
            id.toString() != focusedId &&
            id.toString() in cache.dirtyDiscipleIds
        }

        val batch = dirtyIds.drop(offset).take(MAX_DIRTY_BATCH_SIZE)
        if (batch.isEmpty()) {
            metricsBuilder.dirtyBatchMs += timer.stop()
            return 0
        }

        val hfd = domain.cultivationService.getHighFrequencyData().value
        val focusedGains = hfd.cultivationUpdates
        val focusedProfGains = hfd.proficiencyUpdates
        val focusedNurtureGains = hfd.nurtureUpdates
        val focusedPhaseCount = hfd.focusedPhaseCount

        val equipmentInstanceUpdates = mutableMapOf<String, EquipmentInstance>()
        var updatedManualProficiencies = data.manualProficiencies.toMutableMap()

        for (id in batch) {
            val isFocused = id.toString() == focusedId
            val batchMonths = 1  // 正常月度结算，热控分批由 accumulateBatch 处理

            val disciple = tables.assemble(id)
            val rate = cache.cultivationRateCache[disciple.id] ?: 0.0

            // 突破前溢出修为（在 cap 之前计算，确保不丢失）
            var overflowBeforeBreakthrough = 0.0
            var profUpdates = emptyMap<String, List<ManualProficiencyData>>()
            var nurtureUpdates = emptyMap<String, EquipmentInstance>()

            if (batchMonths > 0) {
                // 修炼总值 = 月修炼值 × 批次数
                val monthlyGain = rate * 3
                val totalMonthlyGain = monthlyGain * batchMonths
                // 非焦点弟子仅累积 HFD、不写入实际修为，
                // 直接用 totalMonthlyGain
                val totalGain = totalMonthlyGain
                val rawCultivation = disciple.cultivation + totalGain
                overflowBeforeBreakthrough = (rawCultivation - disciple.maxCultivation).coerceAtLeast(0.0)
                val newCultivation = rawCultivation.coerceAtMost(disciple.maxCultivation)
                tables.cultivations[id] = newCultivation

                // 月度 HP/MP 恢复：首月扣除 phase tick 已处理部分，
                // 剩余批次月全额处理（历史月份无 phase tick 可扣）
                domain.cultivationService.recoverMonthlyHpMp(tables, id, focusedPhaseCount)
                repeat(batchMonths - 1) {
                    domain.cultivationService.recoverMonthlyHpMp(tables, id, 0)
                }

                // 月度持续效果衰减：同上，首月扣除，剩余月全额
                domain.cultivationService.applyMonthlyDurationDecay(tables, id, focusedPhaseCount)
                repeat(batchMonths - 1) {
                    domain.cultivationService.applyMonthlyDurationDecay(tables, id, 0)
                }

                // 功法熟练度（与修炼结算同频，避免 batchMonths=0 时越权执行）
                if (DiscipleDirtyFlag.MANUAL in (cache.dirtyFlags[disciple.id] ?: emptySet())) {
                    val profAlreadyGained = focusedProfGains[disciple.id] ?: emptyMap()
                    profUpdates = calculateProficiencyGains(disciple, data, cache, profAlreadyGained)
                }

                // 装备温养（同上）
                if (DiscipleDirtyFlag.EQUIPMENT in (cache.dirtyFlags[disciple.id] ?: emptySet())) {
                    val nurtureAlreadyGained = focusedNurtureGains[disciple.id] ?: emptyMap()
                    nurtureUpdates = calculateNurtureGains(disciple, shadow, cache, nurtureAlreadyGained)
                }
            }

            // 突破检查：仅修为 ≥80% 时检查完整条件（低于80%不可能突破，跳过以节省性能）
            val currentCult = tables.cultivations[id]
            val nearMaxCultivation = currentCult >= disciple.maxCultivation * 0.8
            val needsBreakthrough = nearMaxCultivation &&
                currentCult >= disciple.maxCultivation &&
                isDiscipleFullHpMp(disciple)

            val effectiveBatch = if (batchMonths > 0) batchMonths else 1

            val loyaltyDelta = calculateLoyaltyDelta(disciple, cache)
            if (loyaltyDelta != 0) {
                tables.loyalties[id] = (tables.loyalties[id] + loyaltyDelta * effectiveBatch).coerceAtLeast(0)
            }

            // 修炼完成时间
            val cultAfter = tables.cultivations[id]
            val maxCult = if (needsBreakthrough) {
                // 突破后重新获取 maxCultivation
                val dAfter = tables.assemble(id)
                dAfter.maxCultivation
            } else {
                disciple.maxCultivation
            }
            val remaining = maxCult - cultAfter
            val monthsToNext = LazyEvaluationDispatcher.estimateMonthsToNextBreakthrough(remaining, rate)
            tables.cultivationCompletionMonths[id] = currentAbsoluteMonth + monthsToNext
            tables.cultivationCompletionPhases[id] = 1

            // 突破处理（需要消费 shadow.pills，串行）
            if (needsBreakthrough) {
                val afterBreakthrough = processBreakthroughForDisciple(disciple, shadow, cache)
                writeDiscipleToTables(afterBreakthrough, tables)
                // 溢出修为带入新境界（在 cap 之前计算，确保不丢失）
                if (overflowBeforeBreakthrough > 0 && afterBreakthrough.realm > 0) {
                    tables.cultivations[id] =
                        overflowBeforeBreakthrough.coerceAtMost(afterBreakthrough.maxCultivation)
                }
            }

            equipmentInstanceUpdates.putAll(nurtureUpdates)
            for ((discipleId, profList) in profUpdates) {
                updatedManualProficiencies[discipleId] = profList
            }
        }

        if (equipmentInstanceUpdates.isNotEmpty()) {
            shadow.equipmentInstances = shadow.equipmentInstances.map { eq ->
                equipmentInstanceUpdates[eq.id] ?: eq
            }
        }

        if (updatedManualProficiencies != data.manualProficiencies) {
            shadow.gameData = data.copy(manualProficiencies = updatedManualProficiencies)
        }

        metricsBuilder.dirtyDiscipleCount += batch.size
        metricsBuilder.dirtyBatchMs += timer.stop()
        return batch.size
    }

    private suspend fun processProduction(shadow: MutableGameState) {
        val months = if (batchMode == BatchMode.NONE) 1
                     else batchAccumulatedMonths.coerceAtLeast(1)
        if (months <= 0) return
        timer.start()
        // shadow tx removed
        try {
            repeat(months) { domain.productionSubsystem.onMonthTick(shadow) }
        } finally {

        }
        metricsBuilder.productionMs = timer.stop()
    }

    /** 可分批的世界事件：经济（政策开销）+ 血炼进度 */
    private suspend fun processBatchableWorldEvents(shadow: MutableGameState, months: Int) {
        if (months <= 0) return
        // shadow tx removed
        try {
            repeat(months) {
                domain.economySubsystem.onMonthTick(shadow)
                processBloodRefinementProgress(shadow)
            }
        } finally {

        }
    }

    private suspend fun processWorldEvents(shadow: MutableGameState) {
        timer.start()
        // shadow tx removed
        try {
            processWorldEventsMonthlyOnly(shadow)
            // 可分批部分仅在非分批模式执行
            if (batchMode == BatchMode.NONE) {
                domain.economySubsystem.onMonthTick(shadow)
                processBloodRefinementProgress(shadow)
            }
        } finally {

        }
        metricsBuilder.worldEventsMs = timer.stop()
    }

    /**
     * 检查血炼进度，到期则完成洗炼。
     */
    private fun processBloodRefinementProgress(shadow: MutableGameState) {
        val currentYear = shadow.gameData.gameYear
        val currentMonth = shadow.gameData.gameMonth
        val completedRefinements = mutableListOf<String>()
        val cancelledRefinements = mutableListOf<String>()
        val tables = shadow.discipleTables

        for ((buildingId, progress) in shadow.gameData.activeBloodRefinements) {
            val dId = progress.discipleId.toInt()
            val d = if (tables.ids.contains(dId) && tables.isAlive[dId] == 1) tables.assemble(dId) else null
            if (d == null) {
                // 弟子死亡或脱离 → 取消洗炼，不扣除次数
                cancelledRefinements.add(buildingId)
                continue
            }
            if (com.xianxia.sect.core.util.TimeProgressUtil.isTimeElapsed(
                    progress.startYear, progress.startMonth,
                    progress.durationMonths, currentYear, currentMonth
            )) {
                val bonus = (DiscipleStatCalculator.getBaseStatValue(d.combat, progress.selectedStat) * progress.bonusPercent).toInt().coerceAtLeast(1)
                val newCombat = DiscipleStatCalculator.applyStatBonus(d.combat, progress.selectedStat, bonus)
                // 直接更新组件表
                tables.baseHps[dId] = newCombat.baseHp
                tables.baseMps[dId] = newCombat.baseMp
                tables.basePhysicalAttacks[dId] = newCombat.basePhysicalAttack
                tables.baseMagicAttacks[dId] = newCombat.baseMagicAttack
                tables.basePhysicalDefenses[dId] = newCombat.basePhysicalDefense
                tables.baseMagicDefenses[dId] = newCombat.baseMagicDefense
                tables.baseSpeeds[dId] = newCombat.baseSpeed
                tables.hpVariances[dId] = newCombat.hpVariance
                tables.mpVariances[dId] = newCombat.mpVariance
                tables.physicalAttackVariances[dId] = newCombat.physicalAttackVariance
                tables.magicAttackVariances[dId] = newCombat.magicAttackVariance
                tables.physicalDefenseVariances[dId] = newCombat.physicalDefenseVariance
                tables.magicDefenseVariances[dId] = newCombat.magicDefenseVariance
                tables.speedVariances[dId] = newCombat.speedVariance
                tables.totalCultivations[dId] = newCombat.totalCultivation
                tables.breakthroughCounts[dId] = newCombat.breakthroughCount
                tables.breakthroughFailCounts[dId] = newCombat.breakthroughFailCount
                tables.currentHps[dId] = newCombat.currentHp
                tables.currentMps[dId] = newCombat.currentMp
                tables.statuses[dId] = DiscipleStatus.IDLE
                tables.statusData[dId] = emptyMap()

                val currentRefinements = shadow.gameData.bloodRefinements.toMutableMap()
                currentRefinements[d.id] = (currentRefinements[d.id] ?: emptyList()) + progress.materialId
                shadow.gameData = shadow.gameData.copy(bloodRefinements = currentRefinements)

                // 发出完成通知
                if (shadow.pendingNotification == null) {
                    shadow.pendingNotification = GameNotification.BloodRefinementComplete(
                        discipleName = d.name,
                        statName = progress.selectedStat
                    )
                }

                completedRefinements.add(buildingId)
            }
        }

        val removedIds = (completedRefinements + cancelledRefinements).toSet()
        if (removedIds.isNotEmpty()) {
            val remaining = shadow.gameData.activeBloodRefinements.toMutableMap()
            removedIds.forEach { remaining.remove(it) }
            shadow.gameData = shadow.gameData.copy(activeBloodRefinements = remaining)
        }
    }

    private suspend fun processAgingAndDeath(shadow: MutableGameState) {
        // shadow tx removed
        try {
            // 年度事件（招募、俸禄、外交等）已在 GameEngineCore.tickInternal()
            // 中于 shadow 创建前处理完毕。此处仅保留需 shadow 隔离的子嗣系统。
            world.childBirthSystem.onYearTick(shadow)
        } finally {

        }
    }

    private fun processRecruitRefresh(shadow: MutableGameState) {}

    private fun processAISectYearly(shadow: MutableGameState) {}

    private fun processAllianceExpiry(shadow: MutableGameState) {}

    private fun processBreakthroughForDisciple(
        disciple: Disciple,
        shadow: MutableGameState,
        cache: SettlementCache
    ): Disciple {
        return domain.cultivationService.breakthroughHandler.performBreakthrough(disciple, shadow, shadow.gameData)
    }

    private fun calculateProficiencyGains(
        disciple: Disciple,
        data: com.xianxia.sect.core.model.GameData,
        cache: SettlementCache,
        alreadyGained: Map<String, Double>
    ): Map<String, List<ManualProficiencyData>> {
        val result = mutableMapOf<String, List<ManualProficiencyData>>()
        if (disciple.manualIds.isEmpty()) return result

        val inLibrary = data.librarySlots.any { it.discipleId == disciple.id }
        val libraryBonus = if (inLibrary) ManualProficiencySystem.LIBRARY_PROFICIENCY_BONUS_RATE else 0.0
        val proficiencyGainPerPhase = ManualProficiencySystem.calculateProficiencyGainPerPhase(disciple.comprehension, libraryBonus)
        val proficiencyGain = proficiencyGainPerPhase * 3  // 3旬/月
        val maxProf = ManualProficiencySystem.MAX_PROFICIENCY.toInt()

        val profList = data.manualProficiencies.getOrDefault(disciple.id, emptyList()).toMutableList()

        disciple.manualIds.forEach { manualId ->
            cache.manualInstanceMap[manualId]?.let { manual ->
                val alreadyGainedProf = alreadyGained[manualId] ?: 0.0
                val netProfGain = (proficiencyGain - alreadyGainedProf).coerceAtLeast(0.0)
                val profIndex = profList.indexOfFirst { it.manualId == manualId }
                if (profIndex >= 0) {
                    val cp = profList[profIndex]
                    val fixedMaxProf = if (cp.maxProficiency != maxProf) maxProf else cp.maxProficiency
                    val newProf = (cp.proficiency + netProfGain).coerceAtMost(fixedMaxProf.toDouble())
                    profList[profIndex] = cp.copy(
                        proficiency = newProf,
                        maxProficiency = fixedMaxProf,
                        masteryLevel = ManualProficiencySystem.MasteryLevel.fromProficiency(newProf).level
                    )
                } else {
                    val initProf = netProfGain.coerceAtMost(maxProf.toDouble())
                    profList.add(
                        ManualProficiencyData(
                            manualId = manualId,
                            manualName = manual.name,
                            proficiency = initProf,
                            maxProficiency = maxProf,
                            masteryLevel = ManualProficiencySystem.MasteryLevel.fromProficiency(initProf).level
                        )
                    )
                }
            }
        }

        result[disciple.id] = profList
        return result
    }

    private fun calculateNurtureGains(
        disciple: Disciple,
        shadow: MutableGameState,
        cache: SettlementCache,
        alreadyGained: Map<String, Double>
    ): Map<String, EquipmentInstance> {
        val updates = mutableMapOf<String, EquipmentInstance>()
        // 温养每旬基础值：5.0/s × MS_PER_PHASE_1X/1000 → × 3旬/月
        val monthlyNurtureGain = 5.0 * com.xianxia.sect.core.engine.system.GameTimeClock.MS_PER_PHASE_1X / 1000.0 * 3

        fun processNurture(eqId: String?) {
            eqId?.let { id ->
                cache.equipmentInstanceMap[id]?.let { eq ->
                    val already = alreadyGained[id] ?: 0.0
                    val netGain = (monthlyNurtureGain - already).coerceAtLeast(0.0)
                    updates[id] = EquipmentNurtureSystem.updateNurtureExp(eq, netGain).equipment
                }
            }
        }

        processNurture(disciple.equipment.weaponId)
        processNurture(disciple.equipment.armorId)
        processNurture(disciple.equipment.bootsId)
        processNurture(disciple.equipment.accessoryId)

        return updates
    }

    private fun applyNurtureUpdates(shadow: MutableGameState, updates: Map<String, EquipmentInstance>) {
        if (updates.isEmpty()) return
        shadow.equipmentInstances = shadow.equipmentInstances.map { eq ->
            updates[eq.id] ?: eq
        }
    }

    private fun applyProficiencyUpdates(
        shadow: MutableGameState,
        profUpdates: Map<String, List<ManualProficiencyData>>
    ) {
        if (profUpdates.isEmpty()) return
        val data = shadow.gameData
        val updatedProficiencies = data.manualProficiencies.toMutableMap()
        updatedProficiencies.putAll(profUpdates)
        if (updatedProficiencies != data.manualProficiencies) {
            shadow.gameData = data.copy(manualProficiencies = updatedProficiencies)
        }
    }

    private fun calculateLoyaltyDelta(
        disciple: Disciple,
        cache: SettlementCache
    ): Int {
        var delta = 0
        if (disciple.id in cache.residenceDiscipleIds &&
            disciple.skills.loyalty < GameConfig.Disciple.MAX_LOYALTY
        ) {
            delta += 1
        }
        return delta
    }

    private fun isDiscipleFullHpMp(disciple: Disciple): Boolean {
        val hp = if (disciple.combat.currentHp < 0) disciple.maxHp else disciple.combat.currentHp
        val mp = if (disciple.combat.currentMp < 0) disciple.maxMp else disciple.combat.currentMp
        return hp >= disciple.maxHp && mp >= disciple.maxMp
    }

    private fun getLifespanGainForRealm(realm: Int): Int {
        return when (realm) {
            8 -> 50; 7 -> 100; 6 -> 200; 5 -> 400
            4 -> 800; 3 -> 1500; 2 -> 3000; 1 -> 5000
            0 -> 10000; else -> 0
        }
    }

    /**
     * 将 Disciple 对象的变更字段写回组件表。
     * 仅在 processBreakthroughForDisciple 返回后调用。
     */
    private fun writeDiscipleToTables(disciple: Disciple, tables: DiscipleTables) {
        val id = disciple.id.toInt()
        tables.cultivations[id] = disciple.cultivation
        tables.realms[id] = disciple.realm
        tables.realmLayers[id] = disciple.realmLayer
        tables.lifespans[id] = disciple.lifespan
        tables.currentHps[id] = disciple.combat.currentHp
        tables.currentMps[id] = disciple.combat.currentMp
        tables.storageBagItems[id] = disciple.equipment.storageBagItems
    }

    /**
     * 计算并写入弟子下一次修炼完成时间到组件表。
     */
    private fun writeCultivationCompletionToTables(
        disciple: Disciple,
        tables: DiscipleTables,
        currentAbsoluteMonth: Int,
        cultivationRate: Double
    ) {
        val id = disciple.id.toInt()
        val remaining = disciple.maxCultivation - disciple.cultivation
        val monthsToNext = LazyEvaluationDispatcher.estimateMonthsToNextBreakthrough(remaining, cultivationRate)
        tables.cultivationCompletionMonths[id] = currentAbsoluteMonth + monthsToNext
        tables.cultivationCompletionPhases[id] = 1
    }

    /**
     * 计算修炼速率缓存指纹 — 检测影响修炼速率计算的变化。
     *
     * 检测以下变化（任一变化即触发缓存重建）：
     * - 住所布局、长老分配、宗门政策（结构性因素）
     * - 弟子境界分布（境界变化 → 修炼速率变化）
     * - 存活弟子集合（弟子增删）
     * - 丹药效果/修炼加速/丧亲状态（持续效果开始或结束 → 速率变化）
     * - 功法装备状态（功法熟练度/装备温养 → 速率变化）
     *
     * 不依赖弟子当前修炼值等逐旬变化的动态属性。
     * 所有哈希均通过 DiscipleTables 组件列直接计算，无需 assemble()。
     * 指纹比较为纯 Int 比较 O(1)，计算为 O(n) 仅做 hashCode()，~100弟子 <<1ms。
     */
    private fun computeFingerprint(shadow: MutableGameState): CultivationRateFingerprint {
        val data = shadow.gameData
        val tables = shadow.discipleTables
        val aliveIds = tables.ids.filter { tables.isAlive[it] == 1 }
        val discipleIdsHash = aliveIds.hashCode()
        val realmHash = aliveIds.map { tables.realms[it] }.hashCode()
        // 逐弟子哈希（丹药/丧亲/功法/寿命衰减）：通过组件列直接读取，无需 assemble()
        // 装备变更由 EQUIPMENT dirty flag 单独跟踪，不在指纹中检测
        val perDiscipleHash = aliveIds.map { id ->
            var h = 1
            h = 31 * h + tables.cultivationSpeedBonuses.getOrDefault(id, 0.0).hashCode()
            h = 31 * h + tables.cultivationSpeedDurations.getOrDefault(id, 0)
            h = 31 * h + tables.pillEffectDurations.getOrDefault(id, 0)
            h = 31 * h + tables.pillCultivationSpeedBonuses.getOrDefault(id, 0.0).hashCode()
            h = 31 * h + (tables.griefEndYears.getOrNull(id)?.hashCode() ?: 0)
            h = 31 * h + (tables.manualIds.getOrNull(id)?.hashCode() ?: 0)
            // 功法熟练度：熟练度增长影响功法提供的修炼速度加成
            val manualProfs = data.manualProficiencies[id.toString()] ?: emptyList()
            h = 31 * h + manualProfs.map {
                "${it.manualId}:${it.proficiency.hashCode()}"
            }.hashCode()
            // 寿命衰减检测：剩余寿命<20%时修炼速度降低（每少1%降5%），
            // 年龄每年+1、突破时寿命变化，取剩余%整数值捕获所有变化
            val lifespan = tables.lifespans.getOrDefault(id, 0)
            val age = tables.ages.getOrDefault(id, 0)
            val remainingPct = if (lifespan > 0) {
                ((lifespan - age) * 100 / lifespan).coerceIn(0, 100)
            } else 100
            h = 31 * h + remainingPct
            // 师徒关系：masterId 变化（拜师/解绑）或师父大境界突破（masterRealm 变化）
            // 都会改变徒弟的修炼速度加成，必须触发重算
            val masterIdStr = tables.masterIds.getOrNull(id)
            h = 31 * h + (masterIdStr?.hashCode() ?: 0)
            val masterIdInt = masterIdStr?.toIntOrNull()
            h = 31 * h + (masterIdInt?.let { tables.realms.getOrDefault(it, 9) } ?: 9)
            // 父母灵根加成：父母存活状态或灵根数变化影响修炼速度
            val parent1IdStr = tables.parentId1s.getOrNull(id)
            h = 31 * h + (parent1IdStr?.hashCode() ?: 0)
            val p1Int = parent1IdStr?.toIntOrNull()
            h = 31 * h + (p1Int?.let { tables.isAlive.getOrDefault(it, 0) } ?: 0)
            h = 31 * h + (p1Int?.let { tables.spiritRootTypes.getOrDefault(it, "").split(",").size } ?: 0)
            val parent2IdStr = tables.parentId2s.getOrNull(id)
            h = 31 * h + (parent2IdStr?.hashCode() ?: 0)
            val p2Int = parent2IdStr?.toIntOrNull()
            h = 31 * h + (p2Int?.let { tables.isAlive.getOrDefault(it, 0) } ?: 0)
            h = 31 * h + (p2Int?.let { tables.spiritRootTypes.getOrDefault(it, "").split(",").size } ?: 0)
            h
        }.hashCode()
        return CultivationRateFingerprint(
            residenceLayout = data.residenceSlots.hashCode() * 31 +
                data.placedBuildings.hashCode(),
            elderAssignments = data.elderSlots.hashCode(),
            preachingAssignments = (
                data.elderSlots.preachingElder.hashCode() * 31 +
                data.elderSlots.preachingMasters.hashCode() * 31 +
                data.elderSlots.qingyunPreachingElder.hashCode() * 31 +
                data.elderSlots.qingyunPreachingMasters.hashCode()
            ),
            policyFlags = data.sectPolicies.hashCode(),
            aliveDiscipleIdsHash = discipleIdsHash,
            realmHash = realmHash,
            perDiscipleHash = perDiscipleHash
        )
    }

    // ── 空闲模式：双指纹 + 80% 分类 + 微结算 ──────────────────────

    /**
     * 计算修炼速率指纹，复用 [computeFingerprint] 逻辑。
     */
    fun computeCultivationFingerprint(shadow: MutableGameState): CultivationRateFingerprint {
        return computeFingerprint(shadow)
    }

    /**
     * 计算生产系统指纹，委托给 [ProductionRateFingerprint.compute]。
     */
    fun computeProductionFingerprint(shadow: MutableGameState): ProductionRateFingerprint {
        return ProductionRateFingerprint.compute(shadow)
    }

    /**
     * 空闲修炼微结算：用旧缓存中的速率结算 [phases] 旬的修炼值。
     *
     * 直接操作 shadow 中的 DiscipleTables，不经过 Scheduler。
     * 处理内容：修炼值累加、HP/MP 恢复、持续效果衰减、突破检查。
     *
     * @param shadow 空闲期间持有的可变游戏状态
     * @param cache 变化前的修炼速率缓存（旧速率）
     * @param phases 本累积段内的旬数
     */
    fun cultivationMicroSettle(
        shadow: MutableGameState,
        cache: SettlementCache,
        phases: Int
    ) {
        if (phases <= 0) return
        val tables = shadow.discipleTables

        for (dId in cache.aliveDisciples) {
            val id = dId.id.toInt()
            if (tables.isAlive[id] != 1) continue

            // 修炼值：旧速率 × 旬数
            val rate = cache.cultivationRateCache[dId.id] ?: 0.0
            if (rate > 0.0) {
                val gain = rate * phases
                val rawCult = tables.cultivations[id] + gain
                val maxCult = dId.maxCultivation
                tables.cultivations[id] = rawCult.coerceAtMost(maxCult)

                // 突破检查
                if (rawCult >= maxCult &&
                    tables.currentHps[id] >= dId.maxHp &&
                    tables.currentMps[id] >= dId.maxMp
                ) {
                    val updated = processBreakthroughForDisciple(dId, shadow, cache)
                    writeDiscipleToTables(updated, tables)
                }
            }

            // HP/MP 恢复：ceil(phases/3) 个月
            val fullMonths = (phases + 2) / 3
            repeat(fullMonths) { domain.cultivationService.recoverMonthlyHpMp(tables, id, 0) }

            // 持续效果衰减：同上
            repeat(fullMonths) { domain.cultivationService.applyMonthlyDurationDecay(tables, id, 0) }

            // 忠诚度：按足月计算（<1月不累加）
            val loyaltyDelta = calculateLoyaltyDelta(dId, cache)
            if (loyaltyDelta != 0 && phases >= 3) {
                val totalLoyaltyDelta = loyaltyDelta * fullMonths
                tables.loyalties[id] = (tables.loyalties[id] + totalLoyaltyDelta).coerceAtLeast(0)
            }
        }
    }

    /**
     * 空闲生产微结算：逐月推进生产系统和世界事件。
     *
     * 直接操作 shadow，处理 [months] 个月的生产/经济/邮件/子嗣等。
     *
     * @param shadow 空闲期间持有的可变游戏状态
     * @param months 需结算的月份数
     */
    suspend fun productionMicroSettle(shadow: MutableGameState, months: Int) {
        if (months <= 0) return
        // shadow tx removed
        try {
            // 生产系统按批次数全额处理
            repeat(months) { domain.productionSubsystem.onMonthTick(shadow) }
            // 经济 + 血炼按批次数全额处理
            processBatchableWorldEvents(shadow, months)
        } finally {

        }
        // 始终每月的事件（不分批，执行一次即可）
        processWorldEventsMonthlyOnly(shadow)
    }

    /** 始终每月执行的事件（不分批） */
    private suspend fun processWorldEventsMonthlyOnly(shadow: MutableGameState) {
        // shadow tx removed
        try {
            domain.explorationService.processMonthlyWorldLevels(shadow)
            world.mailSystem.onMonthTick(shadow)
            world.childBirthSystem.onMonthTick(shadow)
            world.partnerSystem.onMonthTick(shadow)
            domain.cultivationService.processAutoFromWarehouseMonthly(
                shadow.gameData.gameYear, shadow.gameData.gameMonth, shadow
            )
        } finally {

        }
    }

    /**
     * 空闲全量结算：修炼末段 + 生产末段 + swap。
     *
     * 在 30s 墙壁时钟到期或退出空闲时调用。
     *
     * @param shadow 空闲期间持有的可变游戏状态
     * @param cache 当前修炼速率缓存
     * @param phases 末段累积旬数
     * @param months 末段累积月数
     * @param hasYearlyChange 是否发生过年变
     */
    suspend fun fullIdleSettle(
        shadow: MutableGameState,
        cache: SettlementCache,
        phases: Int,
        months: Int,
        hasYearlyChange: Boolean
    ) {
        // 1. 修炼末段
        cultivationMicroSettle(shadow, cache, phases)

        // 2. 生产末段
        productionMicroSettle(shadow, months)

        // 3. 年度事件
        if (hasYearlyChange) {
            processAgingAndDeath(shadow)
        }

        // 4. 原子写入
        stateStore.swapFromShadow(shadow)

        // 5. 同步修炼速率缓存
        domain.cultivationService.cachedCultivationRates = cache.cultivationRateCache

        // 6. 重置 HFD
        if (months > 0 || phases > 0) {
            domain.cultivationService.resetHighFrequencyData()
        }

        shadowState = null
        currentCache = null
        scheduler.reset()
    }

    /**
     * 80% 进度分类：扫描全部槽位，返回应进入实时轨的集合。
     *
     * 覆盖 9 类有完成周期的系统（不含采矿——持续收入型）。
     *
     * @return 实时轨槽位标识集合（格式："type:id"）
     */
    fun classifySlotsProgress(shadow: MutableGameState): Set<String> {
        val realtime = mutableSetOf<String>()
        val data = shadow.gameData
        val tables = shadow.discipleTables
        val currentYear = data.gameYear
        val currentMonth = data.gameMonth

        // 1. 弟子修炼 ≥80%：组装弟子获取 maxCultivation
        for (id in tables.ids) {
            if (tables.isAlive[id] != 1) continue
            val disciple = tables.assemble(id)
            val maxCult = disciple.maxCultivation
            if (maxCult > 0.0 && disciple.cultivation / maxCult >= 0.8) {
                realtime.add("cultivation:$id")
            }
        }

        // 2. 装备孕养：当前等级内进度 ≥80%
        for (eq in shadow.equipmentInstances) {
            if (eq.isEquipped &&
                EquipmentNurtureSystem.getNurtureProgressPercent(eq) >= 80
            ) {
                realtime.add("nurture:${eq.id}")
            }
        }

        // 3. 功法熟练度：当前境界到下一境界的进度 ≥80%
        val thresholds = ManualProficiencySystem.PROFICIENCY_THRESHOLDS
        val maxOrdinal =
            ManualProficiencySystem.MasteryLevel.values().size
        for ((dId, profList) in data.manualProficiencies) {
            for (prof in profList) {
                val currentLv =
                    ManualProficiencySystem.MasteryLevel
                        .fromLevel(prof.masteryLevel)
                val nextOrdinal = prof.masteryLevel + 1
                if (nextOrdinal >= maxOrdinal) continue
                val current = thresholds[currentLv] ?: 0.0
                val nextLv =
                    ManualProficiencySystem.MasteryLevel
                        .fromLevel(nextOrdinal)
                val next = thresholds[nextLv] ?: 0.0
                val range = next - current
                val progressInLevel =
                    (prof.proficiency - current) / range
                if (range > 0 && progressInLevel >= 0.8) {
                    realtime.add("proficiency:$dId:${prof.manualId}")
                }
            }
        }

        // 4. 血炼 ≥80%
        for ((buildingId, progress) in data.activeBloodRefinements) {
            if (progress.durationMonths > 0) {
                val elapsed = (currentYear - progress.startYear) * 12 +
                    (currentMonth - progress.startMonth)
                if (elapsed.toDouble() / progress.durationMonths >= 0.8) {
                    realtime.add("bloodRefinement:$buildingId")
                }
            }
        }

        // 5. 种植（灵田） ≥80%
        for (plant in data.spiritFieldPlants) {
            if (plant.growTime > 0 && plant.seedId.isNotEmpty()) {
                val elapsed = (currentYear - plant.plantYear) * 12 +
                    (currentMonth - plant.plantMonth)
                if (elapsed.toDouble() / plant.growTime >= 0.8) {
                    realtime.add("spiritField:${plant.buildingInstanceId}")
                }
            }
        }

        // 6. 任务 ≥80%
        for (mission in data.activeMissions) {
            if (mission.duration > 0) {
                val elapsed = (currentYear - mission.startYear) * 12 +
                    (currentMonth - mission.startMonth)
                if (elapsed.toDouble() / mission.duration >= 0.8) {
                    realtime.add("mission:${mission.id}")
                }
            }
        }

        // 7. 炼丹/炼器 ≥80%
        for (slot in data.productionSlots) {
            val progressPct = slot.getProgressPercent(currentYear, currentMonth)
            if (progressPct >= 80) {
                realtime.add("production:${slot.id}")
            }
        }

        // 8. 思过 ≥80%
        for (id in tables.ids) {
            if (tables.isAlive[id] != 1) continue
            if (tables.statuses[id] == DiscipleStatus.REFLECTING) {
                val statusData = tables.statusData[id] ?: continue
                val startYear = statusData["reflectionStartYear"]?.toIntOrNull() ?: continue
                val endYear = statusData["reflectionEndYear"]?.toIntOrNull() ?: continue
                val totalDuration = endYear - startYear
                if (totalDuration > 0) {
                    val elapsed = currentYear - startYear
                    if (elapsed.toDouble() / totalDuration >= 0.8) {
                        realtime.add("reflection:$id")
                    }
                }
            }
        }

        return realtime
    }

    companion object {
        private const val TAG = "SettlementCoordinator"
        private const val MAX_DIRTY_BATCH_SIZE = 100
        private const val IDLE_FULL_SETTLE_MS = 30_000L
    }
}

private class SettlementMetricsBuilder {
    var cacheBuildMs: Float = 0f
    var focusedDiscipleMs: Float = 0f
    var cleanBatchMs: Float = 0f
    var dirtyBatchMs: Float = 0f
    var dirtyDiscipleCount: Int = 0
    var productionMs: Float = 0f
    var worldEventsMs: Float = 0f

    fun build(
        monthYear: Pair<Int, Int>,
        totalDiscipleCount: Int,
        shadowSwapMs: Float,
        frameCount: Int
    ): SettlementMetrics {
        return SettlementMetrics(
            monthYear = monthYear,
            totalDurationMs = cacheBuildMs + focusedDiscipleMs + cleanBatchMs + dirtyBatchMs + productionMs + worldEventsMs + shadowSwapMs,
            cacheBuildMs = cacheBuildMs,
            focusedDiscipleMs = focusedDiscipleMs,
            cleanBatchMs = cleanBatchMs,
            dirtyBatchMs = dirtyBatchMs,
            dirtyDiscipleCount = dirtyDiscipleCount,
            totalDiscipleCount = totalDiscipleCount,
            productionMs = productionMs,
            worldEventsMs = worldEventsMs,
            shadowSwapMs = shadowSwapMs,
            frameCount = frameCount
        )
    }
}
