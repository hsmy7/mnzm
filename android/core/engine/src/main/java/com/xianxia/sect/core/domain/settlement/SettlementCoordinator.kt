package com.xianxia.sect.core.engine.domain.settlement

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.EquipmentNurtureSystem
import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.engine.service.CultivationService
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

    private var lastFingerprint: CultivationRateFingerprint? = null

    // 非焦点域热控分批：记录上次结算的绝对月份和当前批次数
    private var nonFocusedLastSettleMonth: Int = 0
    private var nonFocusedBatchMonths: Int = 1
    private var reusableCache: SettlementCache? = null

    val hasPendingWork: Boolean get() = scheduler.hasPendingWork

    private val timer = SettlementTimer()
    private var metricsBuilder = SettlementMetricsBuilder()

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

    fun scheduleMonthly(shadow: MutableGameState) {
        shadowState = shadow
        metricsBuilder = SettlementMetricsBuilder()

        // 非焦点域热控分批：始终计算，月度结算永不跳过修炼
        val currentAbsMonth = LazyEvaluationDispatcher.toAbsoluteMonth(
            shadow.gameData.gameYear, shadow.gameData.gameMonth
        )
        computeNonFocusedBatch(currentAbsMonth)

        val fingerprint = computeFingerprint(shadow)
        val cachePhase: Phase_BuildCache?

        if (fingerprint == lastFingerprint && reusableCache != null) {
            currentCache = reusableCache
            cachePhase = null
            metricsBuilder.cacheBuildMs = 0f
        } else {
            timer.start()
            cachePhase = Phase_BuildCache { state ->
                val cache = SettlementCache(state)
                currentCache = cache
                reusableCache = cache
                lastFingerprint = fingerprint
                metricsBuilder.cacheBuildMs = timer.stop()
                cache
            }
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

    fun scheduleYearly(shadow: MutableGameState) {
        shadowState = shadow
        metricsBuilder = SettlementMetricsBuilder()

        // 非焦点域热控分批：始终计算，月度结算永不跳过修炼
        val currentAbsMonth = LazyEvaluationDispatcher.toAbsoluteMonth(
            shadow.gameData.gameYear, shadow.gameData.gameMonth
        )
        computeNonFocusedBatch(currentAbsMonth)

        val fingerprint = computeFingerprint(shadow)
        val cachePhase: Phase_BuildCache?

        if (fingerprint == lastFingerprint && reusableCache != null) {
            currentCache = reusableCache
            cachePhase = null
            metricsBuilder.cacheBuildMs = 0f
        } else {
            timer.start()
            cachePhase = Phase_BuildCache { state ->
                val cache = SettlementCache(state)
                currentCache = cache
                reusableCache = cache
                lastFingerprint = fingerprint
                metricsBuilder.cacheBuildMs = timer.stop()
                cache
            }
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

    suspend fun onSettlementComplete() {
        val shadow = shadowState ?: return
        timer.start()
        stateStore.swapFromShadow(shadow)
        val swapMs = timer.stop()

        // 同步修炼速率缓存到 CultivationService，供焦点域 100ms tick 推进修炼值
        currentCache?.let { cache ->
            cultivationService.cachedCultivationRates = cache.cultivationRateCache
        }

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

    fun cancelPendingWork() {
        shadowState = null
        currentCache = null
        scheduler.reset()
    }

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
        val rate = cache.cultivationRateCache[disciple.id] ?: 0.0
        val monthlyGain = rate * 3  // 3旬/月

        val focusedGains = cultivationService.getHighFrequencyData().value.cultivationUpdates
        val focusedProfGains = cultivationService.getHighFrequencyData().value.proficiencyUpdates
        val focusedNurtureGains = cultivationService.getHighFrequencyData().value.nurtureUpdates

        val alreadyGained = focusedGains[disciple.id] ?: 0.0
        val netGain = (monthlyGain - alreadyGained).coerceAtLeast(0.0)

        val rawCultivation = disciple.cultivation + netGain
        val overflow = if (rawCultivation > disciple.maxCultivation)
            rawCultivation - disciple.maxCultivation else 0.0
        val newCultivation = rawCultivation.coerceAtMost(disciple.maxCultivation)
        tables.cultivations[focusedIdInt] = newCultivation

        // 月度 HP/MP 恢复（扣除焦点域已处理旬数）
        val hfd = cultivationService.getHighFrequencyData().value
        cultivationService.recoverMonthlyHpMp(tables, focusedIdInt, hfd.focusedPhaseCount)

        // 月度持续效果衰减（扣除焦点域已处理旬数）
        cultivationService.applyMonthlyDurationDecay(tables, focusedIdInt, hfd.focusedPhaseCount)

        // 突破检查
        if (newCultivation >= disciple.maxCultivation && isDiscipleFullHpMp(disciple)) {
            val updatedDisciple = processBreakthroughForDisciple(disciple, shadow, cache)
            writeDiscipleToTables(updatedDisciple, tables)
            // 溢出修为带入新境界
            if (overflow > 0 && updatedDisciple.realm > 0) {
                tables.cultivations[focusedIdInt] =
                    overflow.coerceAtMost(updatedDisciple.maxCultivation)
            }
        }

        // 重新组装以获取最新状态（突破可能已改变）
        val discipleAfterBreakthrough = tables.assemble(focusedIdInt)
        val profUpdates = calculateProficiencyGains(discipleAfterBreakthrough, data, cache, focusedProfGains[disciple.id] ?: emptyMap())
        val nurtureUpdates = calculateNurtureGains(discipleAfterBreakthrough, shadow, cache, focusedNurtureGains[disciple.id] ?: emptyMap())

        // 忠诚度（居住加成）
        val loyaltyDelta = calculateLoyaltyDelta(discipleAfterBreakthrough, cache)
        if (loyaltyDelta != 0) {
            tables.loyalties[focusedIdInt] = (tables.loyalties[focusedIdInt] + loyaltyDelta).coerceAtLeast(0)
        }

        // 结算后计算下一次完成时间
        val currentAbsoluteMonth = LazyEvaluationDispatcher.toAbsoluteMonth(data.gameYear, data.gameMonth)
        val cultivationRate = cache.cultivationRateCache[disciple.id] ?: 0.0
        writeCultivationCompletionToTables(discipleAfterBreakthrough, tables, currentAbsoluteMonth, cultivationRate)

        applyNurtureUpdates(shadow, nurtureUpdates)
        applyProficiencyUpdates(shadow, profUpdates)

        cultivationService.resetHighFrequencyData()
        metricsBuilder.focusedDiscipleMs = timer.stop()
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
                // 非焦点域热控分批：每月修炼值 × 批次数，扣除焦点域已获增益
                val monthlyGain = rate * 3
                val alreadyGained = focusedGains[disciple.id] ?: 0.0
                val netMonthlyGain = (monthlyGain - alreadyGained).coerceAtLeast(0.0)
                val totalGain = netMonthlyGain * batchMonths + alreadyGained
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
                // 每月修炼值 = rate × 3旬 × batchMonths，扣除焦点域已获增益
                val monthlyGain = rate * 3
                val alreadyGained = focusedGains[disciple.id] ?: 0.0
                val netMonthlyGain = (monthlyGain - alreadyGained).coerceAtLeast(0.0)
                val totalGain = netMonthlyGain * batchMonths + alreadyGained
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

            // 突破检查（batchMonths == 0 时也可触发，因为装备/功法变更可能改变状态）
            val currentCult = tables.cultivations[id]
            val needsBreakthrough = DiscipleDirtyFlag.BREAKTHROUGH in (cache.dirtyFlags[disciple.id] ?: emptySet()) &&
                currentCult >= disciple.maxCultivation && isDiscipleFullHpMp(disciple)

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
                if (newRealmLayer < GameConfig.Realm.get(newRealm).maxLayers) {
                    newRealmLayer++
                } else {
                    newRealm--
                    newRealmLayer = 1
                }
                val lifespanGain = getLifespanGainForRealm(newRealm)
                val lifespanTalentBonus = TalentDatabase.calculateTalentEffects(d.talentIds)["lifespan"] ?: 0.0
                val extraLifespan = if (lifespanTalentBonus != 0.0) {
                    (getLifespanGainForRealm(newRealm) * lifespanTalentBonus).toInt()
                } else 0

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

    private fun computeFingerprint(shadow: MutableGameState): CultivationRateFingerprint {
        val data = shadow.gameData
        return CultivationRateFingerprint(
            residenceLayout = data.residenceSlots.hashCode() * 31 + data.placedBuildings.hashCode(),
            elderAssignments = data.elderSlots.hashCode(),
            preachingAssignments = (data.elderSlots.preachingElder.hashCode() * 31
                + data.elderSlots.preachingMasters.hashCode() * 31
                + data.elderSlots.qingyunPreachingElder.hashCode() * 31
                + data.elderSlots.qingyunPreachingMasters.hashCode()),
            policyFlags = data.sectPolicies.hashCode()
        )
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
