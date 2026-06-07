package com.xianxia.sect.core.engine.domain.settlement

import android.util.Log
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
import com.xianxia.sect.core.engine.system.PartnerSystem
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.roundToInt
import kotlin.random.Random

@Singleton
class SettlementCoordinator @Inject constructor(
    private val cultivationService: CultivationService,
    private val productionSubsystem: ProductionSubsystem,
    private val economySubsystem: EconomySubsystem,
    private val explorationService: ExplorationService,
    private val childBirthSystem: ChildBirthSystem,
    private val partnerSystem: PartnerSystem,
    private val stateStore: GameStateStore,
    private val scheduler: SettlementScheduler,
    private val metricsCollector: SettlementMetricsCollector
) {
    @Volatile
    private var shadowState: MutableGameState? = null
    @Volatile
    private var currentCache: SettlementCache? = null

    private var lastFingerprint: CultivationRateFingerprint? = null
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
        } catch (e: Exception) {
            Log.e(TAG, "Settlement executeStep failed — resetting coordinator to recover", e)
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
            totalDiscipleCount = shadow.disciples.count { it.isAlive },
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
        val disciple = shadow.disciples.find { it.id == focusedId && it.isAlive } ?: run {
            metricsBuilder.focusedDiscipleMs = timer.stop()
            return
        }

        val data = shadow.gameData
        val monthSeconds = GameConfig.Time.SECONDS_PER_REAL_MONTH.toDouble()
        val rate = cache.cultivationRateCache[disciple.id] ?: 0.0
        val monthlyGain = rate * monthSeconds

        val focusedGains = cultivationService.getHighFrequencyData().value.cultivationUpdates
        val focusedProfGains = cultivationService.getHighFrequencyData().value.proficiencyUpdates
        val focusedNurtureGains = cultivationService.getHighFrequencyData().value.nurtureUpdates

        val alreadyGained = focusedGains[disciple.id] ?: 0.0
        val netGain = (monthlyGain - alreadyGained).coerceAtLeast(0.0)

        var updatedDisciple = disciple.copy(
            cultivation = (disciple.cultivation + netGain).coerceIn(0.0, disciple.maxCultivation)
        )

        if (updatedDisciple.cultivation >= updatedDisciple.maxCultivation &&
            isDiscipleFullHpMp(updatedDisciple)
        ) {
            updatedDisciple = processBreakthroughForDisciple(updatedDisciple, shadow, cache)
            // 突破时结算累积薪水（突破前旧境界薪资）
            settleSalaryOnBreakthrough(updatedDisciple.id, shadow)
        }

        val profUpdates = calculateProficiencyGains(updatedDisciple, data, cache, monthSeconds, focusedProfGains[disciple.id] ?: emptyMap())
        val nurtureUpdates = calculateNurtureGains(updatedDisciple, shadow, cache, monthSeconds, focusedNurtureGains[disciple.id] ?: emptyMap())

        // 薪水处理
        val salaryResult = calculateSalaryChange(updatedDisciple, data)
        if (salaryResult != null) {
            updatedDisciple = applySalaryChange(updatedDisciple, salaryResult)
            if (salaryResult.isPaid) {
                shadow.gameData = data.copy(
                    spiritStones = data.spiritStones - salaryResult.amount
                )
            }
        }
        // 忠诚度
        val loyaltyDelta = calculateLoyaltyDelta(updatedDisciple, cache, data)
        if (loyaltyDelta != 0) {
            updatedDisciple = updatedDisciple.copyWith(
                loyalty = (updatedDisciple.skills.loyalty + loyaltyDelta).coerceAtLeast(0)
            )
        }

        // 结算后计算下一次完成时间
        val currentAbsoluteMonth = LazyEvaluationDispatcher.toAbsoluteMonth(data.gameYear, data.gameMonth)
        val cultivationRate = cache.cultivationRateCache[updatedDisciple.id] ?: 0.0
        updatedDisciple = updatedDisciple.withNextCultivationCompletion(currentAbsoluteMonth, cultivationRate)

        shadow.disciples = shadow.disciples.map { d ->
            if (d.id == focusedId) updatedDisciple else d
        }

        applyNurtureUpdates(shadow, nurtureUpdates)
        applyProficiencyUpdates(shadow, profUpdates)

        cultivationService.resetHighFrequencyData()
        metricsBuilder.focusedDiscipleMs = timer.stop()
    }

    private suspend fun processCleanDiscipleBatch(shadow: MutableGameState, cache: SettlementCache) {
        timer.start()
        val data = shadow.gameData
        val monthSeconds = GameConfig.Time.SECONDS_PER_REAL_MONTH.toDouble()
        val focusedId = stateStore.focusedDiscipleId
        val currentAbsoluteMonth = LazyEvaluationDispatcher.toAbsoluteMonth(data.gameYear, data.gameMonth)

        val cleanDisciplesWithIndex = shadow.disciples.mapIndexedNotNull { index, disciple ->
            if (!disciple.isAlive || disciple.id == focusedId) return@mapIndexedNotNull null
            if (disciple.id !in cache.cleanDiscipleIds) return@mapIndexedNotNull null
            if (disciple.id in cache.farFromCompletionIds) return@mapIndexedNotNull null
            index to disciple
        }

        if (cleanDisciplesWithIndex.isEmpty()) {
            metricsBuilder.cleanBatchMs = timer.stop()
            return
        }

        val chunks = cleanDisciplesWithIndex.chunked(100)
        val results = coroutineScope {
            chunks.map { chunk ->
                async(Dispatchers.Default) {
                    chunk.map { (index, disciple) ->
                        var d = disciple
                        val rate = cache.cultivationRateCache[d.id] ?: 0.0
                        val cultivationDelta = rate * monthSeconds
                        d = d.copy(
                            cultivation = (d.cultivation + cultivationDelta).coerceIn(0.0, d.maxCultivation)
                        )

                        var spiritStoneDelta = 0L
                        val salaryResult = calculateSalaryChange(d, data)
                        if (salaryResult != null) {
                            d = applySalaryChange(d, salaryResult)
                            if (salaryResult.isPaid) spiritStoneDelta -= salaryResult.amount.toLong()
                        }
                        val loyaltyDelta = calculateLoyaltyDelta(d, cache, data)
                        if (loyaltyDelta != 0) {
                            d = d.copyWith(loyalty = (d.skills.loyalty + loyaltyDelta).coerceAtLeast(0))
                        }
                        d = d.withNextCultivationCompletion(currentAbsoluteMonth, rate)

                        Triple(index, d, spiritStoneDelta)
                    }
                }
            }.awaitAll()
        }

        val updatedDisciples = shadow.disciples.toMutableList()
        var totalSpiritStoneDelta = 0L
        for (chunkResults in results) {
            for ((index, disciple, spiritStoneDelta) in chunkResults) {
                updatedDisciples[index] = disciple
                totalSpiritStoneDelta += spiritStoneDelta
            }
        }

        shadow.disciples = updatedDisciples.toList()
        if (totalSpiritStoneDelta != 0L) {
            shadow.gameData = data.copy(spiritStones = data.spiritStones + totalSpiritStoneDelta)
        }
        metricsBuilder.cleanBatchMs = timer.stop()
    }

    private suspend fun processDirtyDiscipleBatch(shadow: MutableGameState, cache: SettlementCache, offset: Int): Int {
        timer.start()
        val data = shadow.gameData
        val monthSeconds = GameConfig.Time.SECONDS_PER_REAL_MONTH.toDouble()
        val focusedId = stateStore.focusedDiscipleId
        val currentAbsoluteMonth = LazyEvaluationDispatcher.toAbsoluteMonth(data.gameYear, data.gameMonth)

        val dirtyDisciples = shadow.disciples.filter {
            it.isAlive && it.id != focusedId && it.id in cache.dirtyDiscipleIds
                && it.id !in cache.farFromCompletionIds
        }

        val batch = dirtyDisciples.drop(offset).take(MAX_DIRTY_BATCH_SIZE)
        if (batch.isEmpty()) {
            metricsBuilder.dirtyBatchMs += timer.stop()
            return 0
        }

        val focusedGains = cultivationService.getHighFrequencyData().value.cultivationUpdates
        val focusedProfGains = cultivationService.getHighFrequencyData().value.proficiencyUpdates
        val focusedNurtureGains = cultivationService.getHighFrequencyData().value.nurtureUpdates

        // 分片并行处理：纯计算部分（修炼、薪水、忠诚度、熟练度、养成）
        val chunks = batch.chunked(50)
        val parallelResults = coroutineScope {
            chunks.map { chunk ->
                async(Dispatchers.Default) {
                    chunk.map { disciple ->
                        var d = disciple
                        val rate = cache.cultivationRateCache[d.id] ?: 0.0
                        val monthlyGain = rate * monthSeconds
                        val alreadyGained = focusedGains[d.id] ?: 0.0
                        val netGain = (monthlyGain - alreadyGained).coerceAtLeast(0.0)

                        d = d.copy(cultivation = (d.cultivation + netGain).coerceIn(0.0, d.maxCultivation))

                        val needsBreakthrough = DiscipleDirtyFlag.BREAKTHROUGH in (cache.dirtyFlags[d.id] ?: emptySet()) &&
                            d.cultivation >= d.maxCultivation && isDiscipleFullHpMp(d)

                        var profUpdates = emptyMap<String, List<ManualProficiencyData>>()
                        if (DiscipleDirtyFlag.MANUAL in (cache.dirtyFlags[d.id] ?: emptySet())) {
                            val profAlreadyGained = focusedProfGains[d.id] ?: emptyMap()
                            profUpdates = calculateProficiencyGains(d, data, cache, monthSeconds, profAlreadyGained)
                        }

                        var nurtureUpdates = emptyMap<String, EquipmentInstance>()
                        if (DiscipleDirtyFlag.EQUIPMENT in (cache.dirtyFlags[d.id] ?: emptySet())) {
                            val nurtureAlreadyGained = focusedNurtureGains[d.id] ?: emptyMap()
                            nurtureUpdates = calculateNurtureGains(d, shadow, cache, monthSeconds, nurtureAlreadyGained)
                        }

                        var spiritStoneDelta = 0L
                        val salaryResult = calculateSalaryChange(d, data)
                        if (salaryResult != null) {
                            d = applySalaryChange(d, salaryResult)
                            spiritStoneDelta += if (salaryResult.isPaid) -salaryResult.amount.toLong() else 0L
                        }

                        val loyaltyDelta = calculateLoyaltyDelta(d, cache, data)
                        if (loyaltyDelta != 0) {
                            d = d.copyWith(loyalty = (d.skills.loyalty + loyaltyDelta).coerceAtLeast(0))
                        }

                        d = d.withNextCultivationCompletion(currentAbsoluteMonth, rate)

                        DirtyDiscipleParallelResult(d, needsBreakthrough, spiritStoneDelta, profUpdates, nurtureUpdates)
                    }
                }
            }.awaitAll()
        }

        // 串行合并结果
        val updatedDisciples = shadow.disciples.toMutableList()
        val equipmentInstanceUpdates = mutableMapOf<String, EquipmentInstance>()
        var updatedManualProficiencies = data.manualProficiencies.toMutableMap()
        var spiritStoneDelta = 0L

        for (chunkResults in parallelResults) {
            for (result in chunkResults) {
                var d = result.disciple

                // 串行处理突破（需要消费 shadow.pills）
                if (result.needsBreakthrough) {
                    d = processBreakthroughForDisciple(d, shadow, cache)
                    settleSalaryOnBreakthrough(d.id, shadow)
                }

                val idx = updatedDisciples.indexOfFirst { it.id == d.id }
                if (idx >= 0) updatedDisciples[idx] = d

                spiritStoneDelta += result.spiritStoneDelta
                equipmentInstanceUpdates.putAll(result.nurtureUpdates)
                for ((discipleId, profList) in result.profUpdates) {
                    updatedManualProficiencies[discipleId] = profList
                }
            }
        }

        shadow.disciples = updatedDisciples.toList()

        if (equipmentInstanceUpdates.isNotEmpty()) {
            shadow.equipmentInstances = shadow.equipmentInstances.map { eq ->
                equipmentInstanceUpdates[eq.id] ?: eq
            }
        }

        if (updatedManualProficiencies != data.manualProficiencies) {
            shadow.gameData = data.copy(manualProficiencies = updatedManualProficiencies)
        }

        if (spiritStoneDelta != 0L) {
            shadow.gameData = shadow.gameData.copy(
                spiritStones = shadow.gameData.spiritStones + spiritStoneDelta
            )
        }

        metricsBuilder.dirtyDiscipleCount += batch.size
        metricsBuilder.dirtyBatchMs += timer.stop()
        return batch.size
    }

    private data class DirtyDiscipleParallelResult(
        val disciple: Disciple,
        val needsBreakthrough: Boolean,
        val spiritStoneDelta: Long,
        val profUpdates: Map<String, List<ManualProficiencyData>>,
        val nurtureUpdates: Map<String, EquipmentInstance>
    )

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
            cultivationService.processMonthlyEventsOnShadow(shadow)
            explorationService.processMonthlyWorldLevels(shadow)
            childBirthSystem.onMonthTick(shadow)
            partnerSystem.onMonthTick(shadow)
            processBloodRefinementProgress(shadow)
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

        for ((buildingId, progress) in shadow.gameData.activeBloodRefinements) {
            val d = shadow.disciples.find { it.id == progress.discipleId }
            if (d == null || !d.isAlive) {
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
                val newDisciple = d.copy(
                    combat = newCombat,
                    status = DiscipleStatus.IDLE,
                    statusData = emptyMap()
                )
                shadow.disciples = shadow.disciples.map { if (it.id == d.id) newDisciple else it }

                val currentRefinements = shadow.gameData.bloodRefinements.toMutableMap()
                currentRefinements[d.id] = (currentRefinements[d.id] ?: emptyList()) + progress.materialId
                shadow.gameData = shadow.gameData.copy(bloodRefinements = currentRefinements)

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
            cultivationService.processYearlyEventsOnShadow(shadow)
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
                    shadow.pills = shadow.pills - warehousePill
                    pillBonus = warehousePill.effects.breakthroughChance
                }
            }

            if (pillBonus == 0.0) {
                val bestPill = d.equipment.storageBagItems
                    .filter { it.itemType == "pill" && it.effect?.pillType == "breakthrough" && it.effect?.targetRealm == pillTargetRealm }
                    .maxByOrNull { it.effect?.breakthroughChance ?: 0.0 }
                if (bestPill != null) {
                    d = d.copyWith(storageBagItems = d.equipment.storageBagItems - bestPill)
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

                d = d.copyWith(
                    cultivation = 0.0,
                    realm = newRealm,
                    realmLayer = newRealmLayer,
                    lifespan = d.lifespan + lifespanGain + extraLifespan
                )
            } else {
                val curHp = if (d.combat.currentHp < 0) d.maxHp else d.combat.currentHp
                val curMp = if (d.combat.currentMp < 0) d.maxMp else d.combat.currentMp
                d = d.copyWith(
                    cultivation = 0.0,
                    currentHp = (curHp * 0.1).toInt().coerceAtLeast(1),
                    currentMp = (curMp * 0.1).toInt().coerceAtLeast(1)
                )
                shouldContinue = false
            }
        }
        return d
    }

    /**
     * 弟子突破时结算累积薪水。
     * 突破后境界改变，必须先用旧境界薪资结算到突破当月，年度结算再按新境界算。
     */
    private fun settleSalaryOnBreakthrough(discipleId: String, shadow: MutableGameState) {
        val disciple = shadow.disciples.find { it.id == discipleId && it.isAlive } ?: return
        val data = shadow.gameData
        val enabledConfig = data.monthlySalaryEnabled
        if (enabledConfig[disciple.realm] != true) return

        val salaryConfig = data.monthlySalary
        val maxLoyalty = GameConfig.Disciple.MAX_LOYALTY
        if (disciple.skills.loyalty >= maxLoyalty) return

        val yearlySalary = (salaryConfig[disciple.realm] ?: 0).toLong() * 12
        if (yearlySalary <= 0) return

        if (data.spiritStones >= yearlySalary) {
            shadow.disciples = shadow.disciples.map {
                if (it.id == discipleId) it.copyWith(
                    storageBagSpiritStones = it.equipment.storageBagSpiritStones + yearlySalary,
                    salaryPaidCount = it.skills.salaryPaidCount + 12,
                    loyalty = (it.skills.loyalty + 1).coerceAtMost(maxLoyalty)
                ) else it
            }
            shadow.gameData = data.copy(spiritStones = data.spiritStones - yearlySalary)
        } else {
            shadow.disciples = shadow.disciples.map {
                if (it.id == discipleId) it.copyWith(
                    salaryMissedCount = it.skills.salaryMissedCount + 12,
                    loyalty = (it.skills.loyalty - 1).coerceAtLeast(0)
                ) else it
            }
        }
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
            ((comprehension - 80) / 4) * 0.01
        } else 0.0
    }

    private fun calculateProficiencyGains(
        disciple: Disciple,
        data: com.xianxia.sect.core.model.GameData,
        cache: SettlementCache,
        monthSeconds: Double,
        alreadyGained: Map<String, Double>
    ): Map<String, List<ManualProficiencyData>> {
        val result = mutableMapOf<String, List<ManualProficiencyData>>()
        if (disciple.manualIds.isEmpty()) return result

        val inLibrary = data.librarySlots.any { it.discipleId == disciple.id }
        val libraryBonus = if (inLibrary) ManualProficiencySystem.LIBRARY_PROFICIENCY_BONUS_RATE else 0.0
        val proficiencyGainPerSecond = ManualProficiencySystem.calculateProficiencyGainPerSecond(disciple.comprehension, libraryBonus)
        val proficiencyGain = proficiencyGainPerSecond * monthSeconds
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
        monthSeconds: Double,
        alreadyGained: Map<String, Double>
    ): Map<String, EquipmentInstance> {
        val updates = mutableMapOf<String, EquipmentInstance>()
        val monthlyNurtureGain = 5.0 * monthSeconds

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

    private fun calculateSalaryChange(
        disciple: Disciple,
        data: com.xianxia.sect.core.model.GameData
    ): SalaryChange? {
        val realm = disciple.realm
        val enabledConfig = data.monthlySalaryEnabled
        if (enabledConfig[realm] != true) return null

        val salary = data.monthlySalary[realm] ?: 0
        val canPay = data.spiritStones >= salary

        return if (canPay) {
            SalaryChange(
                amount = salary,
                paidCountDelta = 1,
                loyaltyDelta = 1,
                isPaid = true
            )
        } else {
            SalaryChange(
                amount = 0,
                missedCountDelta = 1,
                loyaltyDelta = -1,
                isPaid = false
            )
        }
    }

    private fun applySalaryChange(disciple: Disciple, change: SalaryChange): Disciple {
        return if (change.isPaid) {
            disciple.copyWith(
                storageBagSpiritStones = disciple.equipment.storageBagSpiritStones + change.amount.toLong(),
                salaryPaidCount = disciple.skills.salaryPaidCount + change.paidCountDelta,
                loyalty = (disciple.skills.loyalty + change.loyaltyDelta).coerceAtLeast(0)
            )
        } else {
            disciple.copyWith(
                salaryMissedCount = disciple.skills.salaryMissedCount + change.missedCountDelta,
                loyalty = (disciple.skills.loyalty + change.loyaltyDelta).coerceAtLeast(0)
            )
        }
    }

    private fun calculateLoyaltyDelta(
        disciple: Disciple,
        cache: SettlementCache,
        data: com.xianxia.sect.core.model.GameData
    ): Int {
        var delta = 0
        val enabledConfig = data.monthlySalaryEnabled
        val realm = disciple.realm
        if (enabledConfig[realm] == true) {
            val salary = data.monthlySalary[realm] ?: 0
            delta += if (data.spiritStones >= salary) 1 else -1
        }
        if (disciple.id in cache.residenceDiscipleIds) {
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
     * 结算完成后计算弟子下一次完成时间。
     */
    private fun Disciple.withNextCultivationCompletion(
        currentMonth: Int,
        cultivationRate: Double
    ): Disciple {
        val remaining = maxCultivation - cultivation
        val monthsToNext = LazyEvaluationDispatcher.estimateMonthsToNextBreakthrough(remaining, cultivationRate)
        return copy(
            cultivationCompletionMonth = currentMonth + monthsToNext,
            cultivationCompletionPhase = 1  // 修炼始终上旬
        )
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
