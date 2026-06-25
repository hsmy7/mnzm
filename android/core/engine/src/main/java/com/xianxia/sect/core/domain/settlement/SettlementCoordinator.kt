package com.xianxia.sect.core.engine.domain.settlement

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.EquipmentNurtureSystem
import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.service.HighFrequencyData
import com.xianxia.sect.core.engine.domain.exploration.ExplorationService
import com.xianxia.sect.core.engine.domain.production.EconomySubsystem
import com.xianxia.sect.core.engine.LazyEvaluationDispatcher
import com.xianxia.sect.core.engine.domain.production.ProductionSubsystem
import com.xianxia.sect.core.engine.system.ChildBirthSystem
import com.xianxia.sect.core.engine.system.MailSystem
import com.xianxia.sect.core.engine.system.PartnerSystem
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameNotification
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.perf.ThermalMonitor
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
    private val cultivationService: CultivationService,
    private val productionSubsystem: ProductionSubsystem,
    private val economySubsystem: EconomySubsystem,
    private val explorationService: ExplorationService,
    private val mailSystem: MailSystem,
    private val childBirthSystem: ChildBirthSystem,
    private val partnerSystem: PartnerSystem,
    private val stateStore: GameStateStore,
    private val scheduler: SettlementScheduler,
    private val metricsCollector: SettlementMetricsCollector,
    private val gameClock: com.xianxia.sect.core.engine.system.GameTimeClock,
    private val thermalMonitor: ThermalMonitor
) {
    @Volatile
    private var shadowState: MutableGameState? = null
    @Volatile
    private var currentCache: SettlementCache? = null

    // 非焦点域热控分批：记录上次结算的绝对月份和当前批次数
    private var nonFocusedLastSettleMonth: Int = 0
    private var nonFocusedBatchMonths: Int = 1

    /**
     * 是否仍有未完成的结算阶段待执行。
     */
    val hasPendingWork: Boolean get() = scheduler.hasPendingWork

    private val timer = SettlementTimer()
    private var metricsBuilder = SettlementMetricsBuilder()

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
        scheduler.reset()
    }

    /**
     * 调度一次月度结算。
     *
     * 设置 shadow 状态、重置 metrics、按热控策略计算非焦点域分批月数，
     * 然后依次注册以下阶段到 [SettlementScheduler]：
     * BuildCache → FocusedDisciple → CleanDiscipleBatch → DirtyDiscipleBatch
     * → Production → WorldEvents。
     *
     * @param shadow 本次结算使用的可变游戏状态（shadow）
     */
    fun scheduleMonthly(shadow: MutableGameState) {
        shadowState = shadow
        metricsBuilder = SettlementMetricsBuilder()

        // 非焦点域热控分批：始终计算，月度结算永不跳过修炼
        val currentAbsMonth = LazyEvaluationDispatcher.toAbsoluteMonth(
            shadow.gameData.gameYear, shadow.gameData.gameMonth
        )
        computeNonFocusedBatch(currentAbsMonth)

        timer.start()
        val cachePhase = Phase_BuildCache { state ->
            val cache = SettlementCache(state)
            currentCache = cache
            metricsBuilder.cacheBuildMs = timer.stop()
            cache
        }

        val focusedPhase = Phase_FocusedDisciple(
            onProcess = { s, cache -> processFocusedDiscipleImmediate(s, cache) },
            cacheProvider = { currentCache }
        )

        val cleanPhase = Phase_CleanDiscipleBatch(
            onProcess = { s, cache -> processCleanDiscipleBatch(s, cache) },
            cacheProvider = { currentCache }
        )

        val dirtyPhase = Phase_DirtyDiscipleBatch(
            onProcess = { s, cache, offset -> processDirtyDiscipleBatch(s, cache, offset) },
            cacheProvider = { currentCache }
        )

        val productionPhase = Phase_Production(
            onProcess = { s -> processProduction(s) }
        )

        val worldEventsPhase = Phase_WorldEvents(
            onProcess = { s -> processWorldEvents(s) }
        )

        scheduler.scheduleMonthly(
            shadow, cachePhase, focusedPhase, cleanPhase,
            dirtyPhase, productionPhase, worldEventsPhase
        )
    }

    /**
     * 调度一次年度结算。
     *
     * 与 [scheduleMonthly] 类似，但额外追加年度专属阶段：
     * AgingAndDeath、RecruitRefresh、AISectYearly、AllianceExpiry。
     *
     * @param shadow 本次结算使用的可变游戏状态（shadow）
     */
    fun scheduleYearly(shadow: MutableGameState) {
        shadowState = shadow
        metricsBuilder = SettlementMetricsBuilder()

        // 非焦点域热控分批：始终计算，月度结算永不跳过修炼
        val currentAbsMonth = LazyEvaluationDispatcher.toAbsoluteMonth(
            shadow.gameData.gameYear, shadow.gameData.gameMonth
        )
        computeNonFocusedBatch(currentAbsMonth)

        timer.start()
        val cachePhase = Phase_BuildCache { state ->
            val cache = SettlementCache(state)
            currentCache = cache
            metricsBuilder.cacheBuildMs = timer.stop()
            cache
        }

        val focusedPhase = Phase_FocusedDisciple(
            onProcess = { s, cache -> processFocusedDiscipleImmediate(s, cache) },
            cacheProvider = { currentCache }
        )

        val cleanPhase = Phase_CleanDiscipleBatch(
            onProcess = { s, cache -> processCleanDiscipleBatch(s, cache) },
            cacheProvider = { currentCache }
        )

        val dirtyPhase = Phase_DirtyDiscipleBatch(
            onProcess = { s, cache, offset -> processDirtyDiscipleBatch(s, cache, offset) },
            cacheProvider = { currentCache }
        )

        val productionPhase = Phase_Production(
            onProcess = { s -> processProduction(s) }
        )

        val worldEventsPhase = Phase_WorldEvents(
            onProcess = { s -> processWorldEvents(s) }
        )

        val agingPhase = Phase_AgingAndDeath(
            onProcess = { s -> processAgingAndDeath(s) }
        )

        scheduler.scheduleYearly(
            shadow, cachePhase, focusedPhase, cleanPhase,
            dirtyPhase, productionPhase, worldEventsPhase,
            agingPhase,
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
            cultivationService.cachedCultivationRates = cache.cultivationRateCache
        }

        cultivationService.resetHighFrequencyData()

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
        scheduler.reset()
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
        val focusedId = stateStore.focusedDiscipleId ?: run {
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
        val hfd = cultivationService.getHighFrequencyData().value

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
        cultivationService.recoverMonthlyHpMp(tables, focusedIdInt, hfd.focusedPhaseCount)
    }

    /**
     * 月度持续效果衰减（扣除焦点域已处理旬数 [hfd.focusedPhaseCount]）。
     *
     * @param hfd 月结期间缓存的 HFD 快照（只读）
     */
    private fun applyFocusedDurationDecay(tables: DiscipleTables, focusedIdInt: Int, hfd: HighFrequencyData) {
        cultivationService.applyMonthlyDurationDecay(tables, focusedIdInt, hfd.focusedPhaseCount)
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
        val focusedId = stateStore.focusedDiscipleId
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

        val hfd = cultivationService.getHighFrequencyData().value
        val focusedGains = hfd.cultivationUpdates
        val focusedPhaseCount = hfd.focusedPhaseCount

        for (id in cleanIds) {
            val isFocused = id.toString() == focusedId
            val batchMonths = if (isFocused) 1 else nonFocusedBatchMonths

            val disciple = tables.assemble(id)
            val rate = cache.cultivationRateCache[disciple.id] ?: 0.0

            if (batchMonths > 0) {
                // 非焦点域热控分批：修炼总值 = 月修炼值 × 批次数
                // alreadyGained 应从总额扣除一次，而非从每月扣除
                val monthlyGain = rate * 3
                val alreadyGained = focusedGains[disciple.id] ?: 0.0
                val totalMonthlyGain = monthlyGain * batchMonths
                val netTotalGain = (totalMonthlyGain - alreadyGained).coerceAtLeast(0.0)
                val totalGain = netTotalGain + alreadyGained
                val rawCultivation = disciple.cultivation + totalGain
                val (newCultivation, overflow) = if (rawCultivation > disciple.maxCultivation) {
                    Pair(disciple.maxCultivation, rawCultivation - disciple.maxCultivation)
                } else {
                    Pair(rawCultivation, 0.0)
                }
                tables.cultivations[id] = newCultivation

                // 月度 HP/MP 恢复 × 批次（扣除焦点域已处理旬数）
                val phaseCountForBatch = if (batchMonths == 1) focusedPhaseCount else 0
                repeat(batchMonths) {
                    cultivationService.recoverMonthlyHpMp(tables, id, phaseCountForBatch)
                }

                // 月度持续效果衰减 × 批次（扣除焦点域已处理旬数）
                repeat(batchMonths) {
                    cultivationService.applyMonthlyDurationDecay(tables, id, phaseCountForBatch)
                }

                // 突破检查
                val needsBreakthrough = newCultivation >= disciple.maxCultivation &&
                    isDiscipleFullHpMp(disciple)
                var dAfterBreakthrough: Disciple? = null
                if (needsBreakthrough) {
                    dAfterBreakthrough = processBreakthroughForDisciple(disciple, shadow, cache)
                    writeDiscipleToTables(dAfterBreakthrough, tables)
                    // 溢出修为带入新境界
                    if (overflow > 0 && dAfterBreakthrough.realm > 0) {
                        val newMaxCult = dAfterBreakthrough.maxCultivation
                        tables.cultivations[id] = overflow.coerceAtMost(newMaxCult)
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
        val focusedId = stateStore.focusedDiscipleId
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

        val hfd = cultivationService.getHighFrequencyData().value
        val focusedGains = hfd.cultivationUpdates
        val focusedProfGains = hfd.proficiencyUpdates
        val focusedNurtureGains = hfd.nurtureUpdates
        val focusedPhaseCount = hfd.focusedPhaseCount

        val equipmentInstanceUpdates = mutableMapOf<String, EquipmentInstance>()
        var updatedManualProficiencies = data.manualProficiencies.toMutableMap()

        for (id in batch) {
            val isFocused = id.toString() == focusedId
            val batchMonths = if (isFocused) 1 else nonFocusedBatchMonths

            val disciple = tables.assemble(id)
            val rate = cache.cultivationRateCache[disciple.id] ?: 0.0

            if (batchMonths > 0) {
                // 修炼总值 = 月修炼值 × 批次数
                // alreadyGained 应从总额扣除一次，而非从每月扣除
                val monthlyGain = rate * 3
                val alreadyGained = focusedGains[disciple.id] ?: 0.0
                val totalMonthlyGain = monthlyGain * batchMonths
                val netTotalGain = (totalMonthlyGain - alreadyGained).coerceAtLeast(0.0)
                val totalGain = netTotalGain + alreadyGained
                val rawCultivation = disciple.cultivation + totalGain
                val (newCultivation, overflow) = if (rawCultivation > disciple.maxCultivation) {
                    Pair(disciple.maxCultivation, rawCultivation - disciple.maxCultivation)
                } else {
                    Pair(rawCultivation, 0.0)
                }
                tables.cultivations[id] = newCultivation

                // 月度 HP/MP 恢复 × 批次（扣除焦点域已处理旬数）
                val phaseCountForBatch = if (batchMonths == 1) focusedPhaseCount else 0
                repeat(batchMonths) {
                    cultivationService.recoverMonthlyHpMp(tables, id, phaseCountForBatch)
                }

                // 月度持续效果衰减 × 批次（扣除焦点域已处理旬数）
                repeat(batchMonths) {
                    cultivationService.applyMonthlyDurationDecay(tables, id, phaseCountForBatch)
                }
            }

            // 突破检查（与 clean batch 一致，不依赖 BREAKTHROUGH dirtyFlag）
            val currentCult = tables.cultivations[id]
            val needsBreakthrough = currentCult >= disciple.maxCultivation &&
                isDiscipleFullHpMp(disciple)

            val effectiveBatch = if (batchMonths > 0) batchMonths else 1
            var profUpdates = emptyMap<String, List<ManualProficiencyData>>()
            if (DiscipleDirtyFlag.MANUAL in (cache.dirtyFlags[disciple.id] ?: emptySet())) {
                val profAlreadyGained = focusedProfGains[disciple.id] ?: emptyMap()
                profUpdates = calculateProficiencyGains(disciple, data, cache, profAlreadyGained)
            }

            var nurtureUpdates = emptyMap<String, EquipmentInstance>()
            if (DiscipleDirtyFlag.EQUIPMENT in (cache.dirtyFlags[disciple.id] ?: emptySet())) {
                val nurtureAlreadyGained = focusedNurtureGains[disciple.id] ?: emptyMap()
                nurtureUpdates = calculateNurtureGains(disciple, shadow, cache, nurtureAlreadyGained)
            }

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
                // 计算溢出（突破前修为超出 maxCultivation 的部分）
                val overflowBeforeBreakthrough =
                    (tables.cultivations[id] - disciple.maxCultivation).coerceAtLeast(0.0)
                val afterBreakthrough = processBreakthroughForDisciple(disciple, shadow, cache)
                writeDiscipleToTables(afterBreakthrough, tables)
                // 溢出修为带入新境界
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
        timer.start()
        stateStore.beginShadowTransaction(shadow)
        try {
            productionSubsystem.onMonthTick(shadow)
        } finally {
            stateStore.endShadowTransaction()
        }
        metricsBuilder.productionMs = timer.stop()
    }

    private suspend fun processWorldEvents(shadow: MutableGameState) {
        timer.start()
        stateStore.beginShadowTransaction(shadow)
        try {
            // 月度事件（盗窃检测、任务刷新、侦察过期等）已移至
            // GameEngineCore.tickInternal() 中于 shadow 创建前处理。
            // 此处仅保留直接操作 shadow 的方法。
            explorationService.processMonthlyWorldLevels(shadow)
            economySubsystem.onMonthTick(shadow)
            mailSystem.onMonthTick(shadow)
            childBirthSystem.onMonthTick(shadow)
            partnerSystem.onMonthTick(shadow)
            processBloodRefinementProgress(shadow)
            // 月结制：自动从仓库装备/学习
            cultivationService.processAutoFromWarehouseMonthly(
                shadow.gameData.gameYear, shadow.gameData.gameMonth, shadow
            )
        } finally {
            stateStore.endShadowTransaction()
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
        stateStore.beginShadowTransaction(shadow)
        try {
            // 年度事件（招募、俸禄、外交等）已在 GameEngineCore.tickInternal()
            // 中于 shadow 创建前处理完毕。此处仅保留需 shadow 隔离的子嗣系统。
            childBirthSystem.onYearTick(shadow)
        } finally {
            stateStore.endShadowTransaction()
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
        var d = disciple
        var shouldContinue = true

        while (shouldContinue && d.realm > 0) {
            if (d.cultivation < d.maxCultivation) break

            val isMajorBreakthrough = d.realmLayer >= GameConfig.Realm.get(d.realm).maxLayers
            val pillTargetRealm = if (isMajorBreakthrough) d.realm - 1 else d.realm
            var pillBonus = 0.0

            val autoPill = cultivationService.qualifiesForSectAutoPublic(
                d, shadow.gameData.breakthroughAutoPillFocused,
                shadow.gameData.breakthroughAutoPillRootCounts
            )
            if (autoPill) {
                val warehousePill = shadow.pills
                    .filter { it.pillType == "breakthrough" && it.effects.targetRealm == pillTargetRealm }
                    .maxByOrNull { it.effects.breakthroughChance }
                if (warehousePill != null) {
                    shadow.pills = shadow.pills - listOf(warehousePill)
                    pillBonus = warehousePill.effects.breakthroughChance
                }
            }

            if (pillBonus == 0.0) {
                val bestPill = d.equipment.storageBagItems
                    .filter { it.itemType == "pill" && it.effect?.pillType == "breakthrough" && it.effect?.targetRealm == pillTargetRealm }
                    .maxByOrNull { it.effect?.breakthroughChance ?: 0.0 }
                if (bestPill != null) {
                    d = d.copy(equipment = d.equipment.copy(storageBagItems = d.equipment.storageBagItems - bestPill))
                    pillBonus = bestPill.effect?.breakthroughChance ?: 0.0
                }
            }

            val allDisciples = cache.discipleMap
            val chance = calculateBreakthroughChance(d, shadow.gameData, allDisciples, pillBonus)
            val success = Random.nextDouble() < chance

            if (success) {
                var newRealm = d.realm
                var newRealmLayer = d.realmLayer
                val oldRealm = newRealm
                if (newRealmLayer < GameConfig.Realm.get(newRealm).maxLayers) {
                    newRealmLayer++
                } else {
                    newRealm--
                    newRealmLayer = 1
                }
                var lifespanGain = 0
                var extraLifespan = 0
                if (newRealm != oldRealm) {
                    lifespanGain = getLifespanGainForRealm(newRealm)
                    val lifespanTalentBonus = TalentDatabase.calculateTalentEffects(d.talentIds)["lifespan"] ?: 0.0
                    if (lifespanTalentBonus != 0.0) {
                        extraLifespan = (getLifespanGainForRealm(newRealm) * lifespanTalentBonus).toInt()
                    }
                }

                d = d.copy(
                    cultivation = 0.0,
                    realm = newRealm,
                    realmLayer = newRealmLayer,
                    lifespan = d.lifespan + lifespanGain + extraLifespan
                )
            } else {
                val curHp = if (d.combat.currentHp < 0) d.maxHp else d.combat.currentHp
                val curMp = if (d.combat.currentMp < 0) d.maxMp else d.combat.currentMp
                d = d.copy(
                    cultivation = 0.0,
                    combat = d.combat.copy(
                        currentHp = (curHp * 0.1).toInt().coerceAtLeast(1),
                        currentMp = (curMp * 0.1).toInt().coerceAtLeast(1)
                    )
                )
                shouldContinue = false
            }
        }
        return d
    }

    private fun calculateBreakthroughChance(
        disciple: Disciple,
        data: com.xianxia.sect.core.model.GameData,
        allDisciples: Map<String, Disciple>,
        pillBonus: Double
    ): Double {
        val elderSlots = data.elderSlots
        val innerElderId = elderSlots.innerElder
        val innerElderComprehension = if (innerElderId.isNotEmpty() && disciple.discipleType == "inner") {
            val elder = allDisciples[innerElderId]
            if (elder != null && elder.isAlive && disciple.realm >= elder.realm) {
                elder.skills.comprehension
            } else 0
        } else 0

        val outerElderComprehensionBonus = calculateOuterElderBreakthroughBonus(disciple, data, allDisciples)

        // 亲人逝世对突破率的影响
        val griefBreakthroughPenalty = if (DiscipleStatCalculator.isGrieving(disciple.social.griefEndYear, data.gameYear)) {
            DiscipleStatCalculator.GRIEF_BREAKTHROUGH_CHANCE_PENALTY
        } else {
            0.0
        }

        return DiscipleStatCalculator.getBreakthroughChance(
            disciple = disciple,
            innerElderComprehension = innerElderComprehension,
            outerElderComprehensionBonus = outerElderComprehensionBonus,
            pillBonus = pillBonus,
            griefBreakthroughPenalty = griefBreakthroughPenalty
        )
    }

    private fun calculateOuterElderBreakthroughBonus(
        disciple: Disciple,
        data: com.xianxia.sect.core.model.GameData,
        allDisciples: Map<String, Disciple>
    ): Double {
        if (disciple.discipleType != "outer") return 0.0
        val outerElderId = data.elderSlots.outerElder
        if (outerElderId.isEmpty()) return 0.0
        val elder = allDisciples[outerElderId] ?: return 0.0
        if (disciple.realm < elder.realm) return 0.0
        val comprehension = elder.skills.comprehension
        return if (comprehension >= 80) {
            ((comprehension - GameConfig.PolicyConfig.ELDER_SKILL_BASELINE) / GameConfig.PolicyConfig.ELDER_BONUS_DIVISOR) * 0.01
        } else 0.0
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
     * 非焦点域热控分批：根据手机发热程度决定结算间隔。
     * - 常温 → 每月结算
     * - 发热(shouldReduceWorkload) → 每 6 月结算一次
     * - 发热严重(shouldEmergencySave) → 每 12 月结算一次
     */
    private fun computeNonFocusedBatch(currentAbsoluteMonth: Int) {
        if (nonFocusedLastSettleMonth == 0) {
            // 首次结算
            nonFocusedLastSettleMonth = currentAbsoluteMonth
            nonFocusedBatchMonths = 1
            return
        }
        val monthsSince = currentAbsoluteMonth - nonFocusedLastSettleMonth
        if (monthsSince <= 0) {
            nonFocusedBatchMonths = 1
            return
        }
        val batchSize = when {
            thermalMonitor.shouldEmergencySave() -> 12
            thermalMonitor.shouldReduceWorkload() -> 6
            else -> 1
        }
        nonFocusedBatchMonths = if (monthsSince >= batchSize) {
            nonFocusedLastSettleMonth = currentAbsoluteMonth
            monthsSince
        } else {
            // 未达批次阈值，但最少处理1个月（永不跳过修炼）
            1
        }
    }

    companion object {
        private const val TAG = "SettlementCoordinator"
        private const val MAX_DIRTY_BATCH_SIZE = 100
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
