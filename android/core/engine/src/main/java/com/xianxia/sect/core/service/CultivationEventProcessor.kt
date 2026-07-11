package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.random.Random
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.*
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.BattleMemberData
import com.xianxia.sect.core.engine.domain.disciple.DiscipleSlotCleanup
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.domain.disciple.DiscipleEquipmentManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleManualManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleService
import com.xianxia.sect.core.engine.domain.exploration.MissionSystem
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.config.DiplomaticEventConfig
import com.xianxia.sect.core.engine.domain.battle.AISectGarrisonManager
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.perf.ThermalMonitor
import com.xianxia.sect.core.engine.system.GameTimeClock
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.engine.domain.diplomacy.VassalService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@GameService("CultivationEventProcessor")
class CultivationEventProcessor @Inject constructor(
    private val stateStore: GameStateStore,
    private val inventorySystem: InventorySystem,
    private val inventoryConfig: InventoryConfig,
    private val scopeProvider: CoroutineScopeProvider,
    private val discipleService: DiscipleService,
    private val thermalMonitor: ThermalMonitor,
    private val gameClock: GameTimeClock,
    private val cultivationCore: CultivationCore,
    private val breakthroughHandler: DiscipleBreakthroughHandler,
    private val cultivationSettlement: CultivationSettlement,
    private val productionSlotRepository: ProductionSlotRepository,
    private val sharedState: CultivationSharedState,
    private val battleSystem: BattleSystem,
    private val merchantAndRecruitService: MerchantAndRecruitService,
    private val caveExplorationProcessor: javax.inject.Provider<CaveExplorationProcessor>,
    private val discipleLifecycleProcessor: DiscipleLifecycleProcessor,
    private val diplomacyEventProcessor: DiplomacyEventProcessor,
    private val equipmentManager: DiscipleEquipmentManager,
    private val manualManager: DiscipleManualManager,
    private val autoBuyService: AutoBuyService,
    private val vassalService: VassalService,
    private val disciplePurchaseService: DisciplePurchaseService
) {
    private val scope get() = scopeProvider.scope

    companion object {
        private const val TAG = "CultivationEventProc"
    }

    private val phaseMultiplier: Int get() = 10

    // ── 时间推进 ──────────────────────────────────────────────────────

    suspend fun advancePhase(state: MutableGameState? = null) {
        val targetState = state ?: return
        val data = targetState.gameData
        val phase = data.gamePhase
        val month = data.gameMonth
        val year = data.gameYear

        // advancePhase 在游戏循环 tick 的 stateStore.update{} 块内被同步调用，
        // 此时持有 transactionMutex。processPhaseEvents 内部必须直接操作传入的
        // targetState（即事务内状态），不能再调用 stateStore.update{}（不可重入锁会死锁）。
        processPhaseEvents(phase, month, year, targetState)
    }

    suspend fun advanceMonth(state: MutableGameState? = null) {
        val data = state?.gameData ?: stateStore.gameData.value
        var newMonth = data.gameMonth + 1
        var newYear = data.gameYear

        if (newMonth > 12) {
            newMonth = 1
            newYear++
        }

        val isYearChanged = newYear > data.gameYear

        val updatedData = data.copy(
            gameMonth = newMonth,
            gameYear = newYear,
            gamePhase = 0
        )
        if (state != null) state.gameData = updatedData else stateStore.update { gameData = updatedData }

        if (isYearChanged) {
            processYearlyEvents(newYear)
        }

        processMonthlyEvents(newYear, newMonth)
    }

    suspend fun advanceYear(state: MutableGameState? = null) {
        val data = state?.gameData ?: stateStore.gameData.value
        val newYear = data.gameYear + 1

        val updatedData = data.copy(
            gameYear = newYear,
            gameMonth = 1,
            gamePhase = 0
        )
        if (state != null) state.gameData = updatedData else stateStore.update { gameData = updatedData }

        processYearlyEvents(newYear)

        processMonthlyEvents(newYear, 1)
    }

    private suspend fun safelyRun(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainLog.e(TAG, "Error in $name", e)
        }
    }

    private suspend fun processPhaseEvents(phase: Int, month: Int, year: Int, state: MutableGameState) {
        safelyRun("checkGameOverCondition") { checkGameOverCondition() }
        safelyRun("processPhaseTick") { processPhaseTick(year, month, phase, state) }
        safelyRun("syncAllDiscipleStatuses") { discipleService.syncAllDiscipleStatuses() }
    }

    // ── Phase Tick ──────────────────────────────────────────────────────

    private suspend fun processPhaseTick(year: Int, month: Int, phase: Int, state: MutableGameState) {
        val equipmentMap = stateStore.equipmentInstances.value.associateBy { it.id }
        val manualMap = stateStore.manualInstances.value.associateBy { it.id }
        val proficienciesMap = stateStore.gameData.value.manualProficiencies
        val multiplier = phaseMultiplier.toDouble()
        val decay = phaseMultiplier
        val equipmentStacksList = stateStore.equipmentStacks.value
        val manualStacksList = stateStore.manualStacks.value
        val maxEquipStack = inventoryConfig.getMaxStackSize("equipment_stack")
        val maxManualStack = inventoryConfig.getMaxStackSize("manual_stack")

        val aliveDisciples = stateStore.disciples.value.filter { it.isAlive }

        // 确保所有存活弟子的修炼速率缓存已填充（首月结算前缓存可能为空）
        val data = state.gameData
        val tables = state.discipleTables
        val existingRates = sharedState.cachedCultivationRates
        val missingIds = aliveDisciples.filter { it.id !in existingRates }
        if (missingIds.isNotEmpty()) {
            val updatedRates = existingRates.toMutableMap()
            for (d in missingIds) {
                updatedRates[d.id] = cultivationCore.calculateDiscipleCultivationPerPhase(d, data, tables)
            }
            sharedState.cachedCultivationRates = updatedRates
        }

        val acc = PhaseTickAccumulator()

        // 循环常量：构造一次，所有弟子共享（避免每迭代分配 5 个上下文对象）
        val tickTime = TickTimeContext(
            year = year, month = month, phase = phase,
            multiplier = multiplier, decay = decay
        )
        val tickEquip = TickEquipContext(
            instanceMap = equipmentMap,
            stacks = equipmentStacksList,
            maxStack = maxEquipStack
        )
        val tickManual = TickManualContext(
            instanceMap = manualMap,
            proficienciesMap = proficienciesMap,
            stacks = manualStacksList,
            maxStack = maxManualStack
        )
        val tickShared = TickSharedContext(
            cachedCultivationRates = sharedState.cachedCultivationRates,
            highFrequencyData = sharedState.highFrequencyData.value,
            autoEquipDirty = sharedState.autoEquipDirty,
            autoLearnDirty = sharedState.autoLearnDirty
        )

        val batchSize = 50
        val processedAlive = mutableListOf<Disciple>()
        for ((index, disciple) in aliveDisciples.withIndex()) {
            processedAlive.add(
                cultivationCore.processDiscipleTick(
                    DiscipleTickParams(
                        disciple = disciple,
                        time = tickTime,
                        equip = tickEquip,
                        manual = tickManual,
                        shared = tickShared,
                        acc = acc
                    )
                )
            )
            if ((index + 1) % batchSize == 0) {
                yield()
                if (thermalMonitor.shouldReduceWorkload()) {
                    delay(5)
                }
            }
        }

        val currentHfd = sharedState.highFrequencyData.value
        val accumGains = currentHfd.cultivationUpdates.toMutableMap()
        processedAlive.forEach { d ->
            val cultivationRate = sharedState.cachedCultivationRates[d.id] ?: 0.0
            if (cultivationRate > 0 && d.cultivation < d.maxCultivation) {
                accumGains[d.id] = (accumGains[d.id] ?: 0.0) + cultivationRate
            }
        }
        sharedState.highFrequencyData.value = currentHfd.copy(
            cultivationUpdates = accumGains,
            focusedPhaseCount = currentHfd.focusedPhaseCount + 1
        )

        // 精准字段写回：仅写回 processDiscipleTick 实际修改的字段，
        // 不执行全量 clear()+insert()。
        // cultivations/realms/realmLayers/lifespans/loyalties 等字段
        // 由 SettlementCoordinator 月度结算单独管理，phaseTick 不碰。
        for (disciple in processedAlive) {
            val id = disciple.id.toInt()
            state.discipleTables.currentHps[id] = disciple.combat.currentHp
            state.discipleTables.currentMps[id] = disciple.combat.currentMp
            state.discipleTables.cultivationSpeedBonuses[id] = disciple.cultivationSpeedBonus
            state.discipleTables.cultivationSpeedDurations[id] = disciple.cultivationSpeedDuration
            state.discipleTables.pillCultivationSpeedBonuses[id] = disciple.pillEffects.pillCultivationSpeedBonus
            state.discipleTables.pillEffectDurations[id] = disciple.pillEffects.pillEffectDuration
            state.discipleTables.storageBagItems[id] = disciple.equipment.storageBagItems
            state.discipleTables.weaponIds[id] = disciple.equipment.weaponId
            state.discipleTables.armorIds[id] = disciple.equipment.armorId
            state.discipleTables.bootsIds[id] = disciple.equipment.bootsId
            state.discipleTables.accessoryIds[id] = disciple.equipment.accessoryId
            state.discipleTables.manualIds[id] = disciple.manualIds
        }

        cultivationCore.applyAccumulator(acc, state, maxEquipStack, maxManualStack)
    }

    // ── 自动从仓库装备/学习 ──────────────────────────────────────────

    /**
     * 实时轨专用：自动从仓库装备/学习。
     * 仅由 [CultivationTickSystem.onPhaseTick] 在 phasesToSettle==1 时调用。
     */
    fun processAutoFromWarehouseRealtime(state: MutableGameState) {
        val d = state.gameData
        processAutoFromWarehouse(d.gameYear, d.gameMonth, d.gamePhase, state)
    }

    private fun processAutoFromWarehouse(
        year: Int, month: Int, phase: Int, state: MutableGameState
    ) {
        val gameData = state.gameData
        val equipFocused = gameData.autoEquipFromWarehouseFocused
        val equipRootCounts = gameData.autoEquipFromWarehouseRootCounts
        val learnFocused = gameData.autoLearnFromWarehouseFocused
        val learnRootCounts = gameData.autoLearnFromWarehouseRootCounts
        val hasAutoEquip = equipFocused || equipRootCounts.isNotEmpty()
        val hasAutoLearn = learnFocused || learnRootCounts.isNotEmpty()

        if (!hasAutoEquip && !hasAutoLearn) return

        val tables = state.discipleTables
        val updatedDisciples = tables.ids.filter { tables.isAlive[it] == 1 }
            .map { tables.assemble(it) }.toMutableList()

        val bagEqIds = mutableSetOf<String>()
        val bagMnIds = mutableSetOf<String>()
        for (disciple in updatedDisciples) {
            for (item in disciple.equipment.storageBagItems) {
                when (item.itemType) {
                    "equipment_stack" -> bagEqIds.add(item.itemId)
                    "manual_stack" -> bagMnIds.add(item.itemId)
                }
            }
        }

        var eqStacks = state.equipmentStacks.all().filter { it.id !in bagEqIds }
        var mnStacks = state.manualStacks.all().filter { it.id !in bagMnIds }
        val eqInstancesById = state.equipmentInstances.associateById()
        val mnInstancesById = state.manualInstances.associateById()
        val newEqInstances = mutableListOf<EquipmentInstance>()
        val newMnInstances = mutableListOf<ManualInstance>()

        val sortedIndices = updatedDisciples.indices
            .filter { updatedDisciples[it].isAlive }
            .sortedWith(compareByDescending<Int> { updatedDisciples[it].statusData["followed"] == "true" }
                .thenBy { updatedDisciples[it].realm }
                .thenByDescending { updatedDisciples[it].realmLayer })

        for (idx in sortedIndices) {
            val disciple = updatedDisciples[idx]
            var d = disciple

            if (qualifiesForSectAutoPublic(d, equipFocused, equipRootCounts)) {
                val result = equipmentManager.processAutoEquipFromWarehouse(
                    disciple = d,
                    warehouseStacks = eqStacks,
                    equipmentInstances = eqInstancesById,
                    gameYear = year,
                    gameMonth = month,
                    gamePhase = phase,
                    maxStack = inventoryConfig.getMaxStackSize("equipment_stack")
                )
                if (result.newInstances.isNotEmpty()) {
                    d = result.disciple
                    newEqInstances.addAll(result.newInstances)
                    // 记录自动装备日志
                    val equipName = result.newInstances.firstOrNull()?.name ?: ""
                    if (equipName.isNotEmpty()) {
                        val age = tables.ages[d.id.toInt()]
                        discipleService.addLifeEvent(d.id, "${age}岁：自动装备了${equipName}")
                    }
                    result.stackUpdates.forEach { update ->
                        if (update.isDeletion) {
                            eqStacks = eqStacks.filter { it.id != update.stackId }
                        } else {
                            eqStacks = eqStacks.map {
                                if (it.id == update.stackId) it.copy(quantity = update.newQuantity) else it
                            }
                        }
                    }
                }
            }

            if (qualifiesForSectAutoPublic(d, learnFocused, learnRootCounts)) {
                val result = manualManager.processAutoLearnFromWarehouse(
                    disciple = d,
                    warehouseStacks = mnStacks,
                    manualInstances = mnInstancesById,
                    gameYear = year,
                    gameMonth = month,
                    gamePhase = phase,
                    maxStack = inventoryConfig.getMaxStackSize("manual_stack")
                )
                if (result.newInstance != null) {
                    d = result.disciple
                    newMnInstances.add(result.newInstance)
                    // 记录自动学习日志
                    val manualName = result.newInstance?.name ?: ""
                    if (manualName.isNotEmpty()) {
                        val age = tables.ages[d.id.toInt()]
                        discipleService.addLifeEvent(d.id, "${age}岁：自动学习了${manualName}")
                    }
                    result.stackUpdate?.let { update ->
                        if (update.isDeletion) {
                            mnStacks = mnStacks.filter { it.id != update.stackId }
                        } else {
                            mnStacks = mnStacks.map {
                                if (it.id == update.stackId) it.copy(quantity = update.newQuantity) else it
                            }
                        }
                    }
                }
            }

            if (d !== disciple) {
                updatedDisciples[idx] = d
            }
        }

        // 精准字段写回：仅写回自动装备/学习实际修改的字段，
        // 不执行全量 clear()+insert()
        for (disciple in updatedDisciples) {
            val id = disciple.id.toInt()
            tables.storageBagItems[id] = disciple.equipment.storageBagItems
            tables.weaponIds[id] = disciple.equipment.weaponId
            tables.armorIds[id] = disciple.equipment.armorId
            tables.bootsIds[id] = disciple.equipment.bootsId
            tables.accessoryIds[id] = disciple.equipment.accessoryId
            tables.manualIds[id] = disciple.manualIds
        }
        state.equipmentStacks.setItems(
            state.equipmentStacks.all().filter { it.id in bagEqIds } + eqStacks
        )
        state.manualStacks.setItems(
            state.manualStacks.all().filter { it.id in bagMnIds } + mnStacks
        )
        newEqInstances.forEach { state.equipmentInstances.add(it) }
        newMnInstances.forEach { state.manualInstances.add(it) }
    }

    // ── 月度/年度事件 ──────────────────────────────────────────────────

    suspend fun processMonthlyEvents(year: Int, month: Int) = withContext(NonCancellable) {
        safelyRun("aiSectOperations") {
            caveExplorationProcessor.get().processAISectOperations(year, month)
        }
        safelyRun("gameOverCheck") { checkGameOverCondition() }
        safelyRun("scoutExpiry") { processScoutInfoExpiryLazy(year, month) }
        safelyRun("monthlyFavorEvents") {
            diplomacyEventProcessor.processDiplomacyMonthlyEventsCapped(year, month)
        }
        safelyRun("monthlyBreakaway") {
            vassalService.processMonthlyBreakawayCheck(year, month)
        }
        safelyRun("theft") { processTheftIfNeeded() }
        safelyRun("lawEnforcement") { processLawEnforcementMonthly() }
        safelyRun("missionRefresh") { processMissionRefreshIfDue(month) }
        safelyRun("completedMissions") {
            processCompletedMissionsLazy(year, month)
        }
        if (month == 12) {
            safelyRun("autoBuy") { autoBuyService.executeAutoBuy(year, month) }
        }
        // 灵矿月度产出结算（含政策月度灵石扣除，保证事务原子性）
        safelyRun("spiritMineProduction") {
            cultivationSettlement.processSpiritMineProductionMonthly()
        }
        // 弟子智能购买上架物品
        safelyRun("disciplePurchase") {
            disciplePurchaseService.executePurchase(year, month)
        }
        // 月度修炼结算 + HP/MP恢复 + 自动装备/丹药
        safelyRun("monthlyCultivation") { processMonthlyCultivationAndAuto() }
    }

    /**
     * 月度修炼结算 + 自动后台型系统。
     *
     * 对标 RimWorld Long Tick 模式 — 每月一次性处理
     * 修炼经验累积、HP/MP恢复、自动装备/学习/丹药。
     */
    private suspend fun processMonthlyCultivationAndAuto() {
        stateStore.update {
            val data = gameData
            val tables = discipleTables
            val aliveIds = tables.ids.filter { tables.isAlive[it] == 1 }
            if (aliveIds.isEmpty()) return@update

            // HP/MP 恢复（兜底，已由每旬检查补充）
            cultivationCore.recoverHpMpForAllDisciples(this, phasesToSettle = 3)
        }
    }

    suspend fun processYearlyEvents(year: Int) = withContext(NonCancellable) {
        safelyRun("yearlyTribute") {
            vassalService.processYearlyTribute()
        }
        safelyRun("yearlyVassalTribute") {
            vassalService.processYearlyVassalTribute(year)
        }
        safelyRun("discipleAging") {
            discipleLifecycleProcessor.processDiscipleAging(year)
        }
        safelyRun("sectDisciplesAging") {
            caveExplorationProcessor.get().processSectDisciplesAging(year)
        }
        safelyRun("refreshRecruitList") {
            merchantAndRecruitService.refreshRecruitList(year)
        }
        safelyRun("yearlyAging") {
            discipleLifecycleProcessor.processYearlyAging(year)
        }
        safelyRun("sectYearlyRecruitment") {
            caveExplorationProcessor.get().processSectDisciplesYearlyRecruitment(year)
        }
        safelyRun("refreshTravelingMerchant") {
            merchantAndRecruitService.refreshTravelingMerchant(year, 1)
        }
        safelyRun("autoBuy") {
            autoBuyService.executeAutoBuy(year, 1)
        }
        safelyRun("refreshAcquisition") {
            merchantAndRecruitService.refreshMerchantAcquisition(year, 1)
        }
        safelyRun("partnerMatching") {
            diplomacyEventProcessor.processCrossSectPartnerMatching(year, 1)
        }
        safelyRun("allianceExpiry") {
            diplomacyEventProcessor.checkAllianceExpiry(year)
        }
        safelyRun("allianceFavorDrop") {
            diplomacyEventProcessor.checkAllianceFavorDrop()
        }
        safelyRun("aiAlliances") {
            diplomacyEventProcessor.processAIAlliances(year)
        }
        safelyRun("reflectionRelease") {
            discipleLifecycleProcessor.processReflectionRelease(year)
        }
        safelyRun("favorDecay") {
            diplomacyEventProcessor.processFavorDecay(year)
        }
        safelyRun("garrisonRotation") {
            val rotated = AISectGarrisonManager.rotateGarrisonSlots(
                stateStore.gameData.value
            )
            stateStore.update { gameData = rotated }
        }
        safelyRun("griefExpiry") {
            discipleLifecycleProcessor.processGriefExpiry(year)
        }
    }

    // ── 执法/盗窃 ──────────────────────────────────────────────────────

    fun calculateCaptureRate(): Double {
        val data = stateStore.gameData.value
        val elderSlots = data.elderSlots
        val allDisciples = stateStore.disciples.value.associateBy { it.id }

        var captureRate = GameConfig.LawEnforcementConfig.BASE_CAPTURE_RATE

        elderSlots.lawEnforcementElder?.let { elderId ->
            if (elderId.isNotEmpty()) {
                allDisciples[elderId]?.let { elder ->
                    val intelligenceAboveBase = (DiscipleStatCalculator.getBaseStats(elder).intelligence - GameConfig.LawEnforcementConfig.INTELLIGENCE_BASE).coerceAtLeast(0)
                    captureRate += intelligenceAboveBase * GameConfig.LawEnforcementConfig.ELDER_BONUS_PER_POINT
                }
            }
        }

        elderSlots.lawEnforcementDisciples.forEach { slot ->
            if (slot.discipleId.isNotEmpty()) {
                allDisciples[slot.discipleId]?.let { disciple ->
                    val intelligenceAboveBase = (DiscipleStatCalculator.getBaseStats(disciple).intelligence - GameConfig.LawEnforcementConfig.INTELLIGENCE_BASE).coerceAtLeast(0)
                    captureRate += (intelligenceAboveBase / GameConfig.LawEnforcementConfig.DISCIPLE_INTELLIGENCE_STEP) * GameConfig.LawEnforcementConfig.DISCIPLE_BONUS_PER_STEP
                }
            }
        }

        if (data.sectPolicies.enhancedSecurity) {
            captureRate += GameConfig.PolicyConfig.ENHANCED_SECURITY_BASE_EFFECT
        }

        return captureRate.coerceIn(0.0, 1.0)
    }

    suspend fun processLawEnforcementMonthly() {
        val data = stateStore.gameData.value
        val captureRate = calculateCaptureRate()
        val currentMonthValue = data.gameYear * 12 + data.gameMonth
        val tables = stateStore.discipleTables
        val threshold = GameConfig.LawEnforcementConfig.LOYALTY_THRESHOLD
        val protectionMonths =
            GameConfig.LawEnforcementConfig.NEW_DISCIPLE_PROTECTION_MONTHS

        // 直接用 discipleTables 读实时数据，避免 StateFlow 快照滞后
        val atRiskIds = tables.ids.filter { id ->
            tables.isAlive.getOrDefault(id, 0) == 1 &&
                tables.statuses.getOrDefault(id, DiscipleStatus.IDLE) ==
                    DiscipleStatus.IDLE &&
                tables.loyalties.getOrDefault(id, 0) < threshold &&
                (currentMonthValue -
                    tables.recruitedMonths.getOrDefault(id, 0)) >=
                    protectionMonths
        }

        for (id in atRiskIds) {
            val loyal = tables.loyalties.getOrDefault(id, 0)
            val desertionProb =
                ((threshold - loyal) *
                    GameConfig.LawEnforcementConfig.PROB_PER_POINT)
                    .coerceIn(
                        0.0, GameConfig.LawEnforcementConfig.MAX_PROB
                    )
            if (Random.nextDouble() < desertionProb) {
                if (Random.nextDouble() < captureRate) {
                    val currentYear = data.gameYear
                    val endYear = currentYear +
                        GameConfig.LawEnforcementConfig.REFLECTION_YEARS
                    stateStore.update {
                        val d = discipleTables.assemble(id) ?: return@update
                        discipleTables.remove(id)
                        discipleTables.insert(d.copy(
                            status = DiscipleStatus.REFLECTING,
                            statusData = d.statusData + mapOf(
                                "reflectionStartYear" to currentYear.toString(),
                                "reflectionEndYear" to endYear.toString()
                            )
                        ))
                    }
                } else {
                    // 防御性二次校验：确认忠诚度仍低于阈值
                    val currentLoyal =
                        tables.loyalties.getOrDefault(id, 0)
                    if (currentLoyal >= threshold) continue

                    val snapshot = tables.assemble(id) ?: continue
                    discipleLifecycleProcessor
                        .clearDiscipleFromAllSlots(id.toString())
                    stateStore.update {
                        // 二次校验在事务内重做，防悬停点间被修改
                        if (discipleTables.loyalties.getOrDefault(
                                id, 0
                            ) < threshold
                        ) {
                            discipleTables.remove(id)
                        }
                    }
                    stateStore.setPendingNotification(
                        GameNotification.DiscipleDesertion(snapshot)
                    )
                }
            }
        }
    }

    suspend fun processTheftMonthly() {
        val currentData = stateStore.gameData.value
        if (currentData.spiritStones <= 0) return

        val captureRate = calculateCaptureRate()
        val currentMonthValue = currentData.gameYear * 12 + currentData.gameMonth
        val tables = stateStore.discipleTables
        val moralThreshold = GameConfig.LawEnforcementConfig.MORALITY_THRESHOLD
        val loyalThreshold = GameConfig.LawEnforcementConfig.LOYALTY_THRESHOLD
        val protectionMonths =
            GameConfig.LawEnforcementConfig.NEW_DISCIPLE_PROTECTION_MONTHS

        // 直接从 discipleTables 读取实时数据
        val atRiskIds = tables.ids.filter { id ->
            tables.isAlive.getOrDefault(id, 0) == 1 &&
                tables.statuses.getOrDefault(id, DiscipleStatus.IDLE) ==
                    DiscipleStatus.IDLE &&
                tables.moralities.getOrDefault(id, 0) < moralThreshold &&
                tables.loyalties.getOrDefault(id, 0) < loyalThreshold &&
                (currentMonthValue -
                    tables.recruitedMonths.getOrDefault(id, 0)) >=
                    protectionMonths &&
                (currentMonthValue -
                    tables.lastTheftMonths.getOrDefault(id, 0)) >= 12
        }

        val thiefIds = mutableSetOf<Int>()
        val warehouses = currentData.placedBuildings.filter {
            it.displayName == "仓库"
        }
        val garrisons = currentData.warehouseGarrisons

        for (id in atRiskIds) {
            val disciple = tables.assemble(id) ?: continue
            val stats = DiscipleStatCalculator.getBaseStats(disciple)
            val effectiveMorality = stats.morality
            val theftProb =
                ((moralThreshold - effectiveMorality) *
                    GameConfig.LawEnforcementConfig.PROB_PER_POINT)
                    .coerceIn(0.0, GameConfig.LawEnforcementConfig.MAX_PROB)
            if (Random.nextDouble() < theftProb) {
                val caught = if (warehouses.isNotEmpty()) {
                    val warehouse =
                        warehouses[Random.nextInt(warehouses.size)]
                    val garrison = garrisons.find {
                        it.buildingInstanceId == warehouse.instanceId
                            && it.isActive
                    }
                    if (garrison == null) {
                        false
                    } else {
                        val guardDisciple =
                            stateStore.disciples.value.find {
                                it.id == garrison.discipleId
                            }
                        if (guardDisciple == null) {
                            false
                        } else {
                            val thiefStats =
                                DiscipleStatCalculator.getBaseStats(
                                    disciple
                                )
                            val guardStats =
                                DiscipleStatCalculator.getBaseStats(
                                    guardDisciple
                                )
                            val thiefPower = thiefStats.physicalAttack +
                                thiefStats.magicAttack +
                                thiefStats.physicalDefense +
                                thiefStats.magicDefense +
                                thiefStats.speed
                            val guardPower = guardStats.physicalAttack +
                                guardStats.magicAttack +
                                guardStats.physicalDefense +
                                guardStats.magicDefense +
                                guardStats.speed
                            val totalPower =
                                (thiefPower + guardPower)
                                    .coerceAtLeast(1)
                            val thiefWinProb =
                                (thiefPower.toDouble() / totalPower)
                                    .coerceIn(0.1, 0.9)
                            Random.nextDouble() >= thiefWinProb
                        }
                    }
                } else {
                    Random.nextDouble() < captureRate
                }

                if (caught) {
                    stateStore.setPendingNotification(
                        GameNotification.DiscipleTheftCaught(disciple)
                    )
                } else {
                    val currentData2 = stateStore.gameData.value
                    if (currentData2.spiritStones <= 0) break
                    val stolenAmount =
                        (currentData2.spiritStones * Random.nextDouble(
                            GameConfig.LawEnforcementConfig.THEFT_MIN_RATIO,
                            GameConfig.LawEnforcementConfig.THEFT_MAX_RATIO
                        )).toLong().coerceAtLeast(1)
                    stateStore.update {
                        gameData = gameData.copy(
                            spiritStones = (gameData.spiritStones - stolenAmount)
                                .coerceAtLeast(0)
                        )
                        discipleTables.assembleAll().firstOrNull {
                            it.id == disciple.id
                        }?.let { d ->
                            discipleTables.update(
                                d.copy(
                                    equipment = d.equipment.copy(
                                        storageBagSpiritStones =
                                            d.equipment.storageBagSpiritStones + stolenAmount
                                    ),
                                    usage = d.usage.copy(
                                        lastTheftMonth = currentMonthValue
                                    )
                                )
                            )
                        }
                    }

                    val loyalty = stats.loyalty
                    val desertionProb =
                        ((loyalThreshold - loyalty) *
                            GameConfig.LawEnforcementConfig.PROB_PER_POINT)
                            .coerceIn(
                                0.0,
                                GameConfig.LawEnforcementConfig.MAX_PROB
                            )
                    if (Random.nextDouble() < desertionProb) {
                        thiefIds.add(id)
                    }

                    stateStore.setPendingNotification(
                        GameNotification.WarehouseTheft(stolenAmount)
                    )
                }
            }
        }

        // 偷盗后叛逃：设置小卡片通知 + 清除槽位 + 移除弟子
        for (thiefId in thiefIds) {
            // 防御性二次校验
            val currentLoyal =
                tables.loyalties.getOrDefault(thiefId, 0)
            if (currentLoyal >= loyalThreshold) continue

            val snapshot = tables.assemble(thiefId)
            if (snapshot != null) {
                stateStore.setPendingNotification(
                    GameNotification.DiscipleTheftDesertion(snapshot)
                )
            }
            discipleLifecycleProcessor
                .clearDiscipleFromAllSlots(thiefId.toString())
        }
        if (thiefIds.isNotEmpty()) {
            stateStore.update {
                for (thiefId in thiefIds) {
                    if (discipleTables.loyalties.getOrDefault(
                            thiefId, 0
                        ) < loyalThreshold
                    ) {
                        discipleTables.remove(thiefId)
                    }
                }
            }
        }
    }

    // ── 战斗/探索辅助 ──────────────────────────────────────────────────

    suspend fun updateDiscipleHpMpAfterBattle(battleMembers: List<BattleMemberData>) {
        val survivorIds = battleMembers.filter { it.isAlive }.map { it.id }.toSet()
        val disciples = stateStore.disciples.value.toMutableList()
        var changed = false
        team@ for (member in battleMembers) {
            val discipleIndex = disciples.indexOfFirst { it.id == member.id }
            if (discipleIndex < 0) continue@team
            val disciple = disciples[discipleIndex]
            if (!survivorIds.contains(member.id)) {
                disciples[discipleIndex] = disciple.copy(isAlive = false, status = DiscipleStatus.DEAD)
                changed = true
            } else {
                val hp = member.hp.coerceAtMost(member.maxHp)
                val mp = member.mp.coerceAtMost(member.maxMp)
                disciples[discipleIndex] = disciple.copy(combat = disciple.combat.copy(currentHp = hp, currentMp = mp))
                changed = true
            }
        }
        if (changed) {
            stateStore.update {
                discipleTables.clear()
                disciples.forEach { discipleTables.insert(it) }
            }
        }
    }

    suspend fun completeExploration(team: ExplorationTeam, success: Boolean, survivorIds: List<String>, survivorHpMap: Map<String, Int> = emptyMap(), survivorMpMap: Map<String, Int> = emptyMap()) {
        val currentDisciplesList = stateStore.disciples.value.toMutableList()
        team.memberIds.forEach { memberId ->
            val index = currentDisciplesList.indexOfFirst { it.id == memberId }
            if (index >= 0) {
                val disciple = currentDisciplesList[index]
                val shouldKeepAlive = disciple.isAlive && survivorIds.contains(memberId)
                if (shouldKeepAlive) {
                    val hp = survivorHpMap[memberId] ?: disciple.combat.currentHp
                    val mp = survivorMpMap[memberId] ?: disciple.combat.currentMp
                    currentDisciplesList[index] = disciple.copy(status = DiscipleStatus.IDLE, combat = disciple.combat.copy(currentHp = hp, currentMp = mp))
                } else {
                    discipleLifecycleProcessor.handleDiscipleDeath(disciple, isOutsideSect = true)
                    currentDisciplesList[index] = disciple.copy(isAlive = false, status = DiscipleStatus.DEAD)
                }
            }
        }
        stateStore.update {
            discipleTables.clear()
            currentDisciplesList.forEach { discipleTables.insert(it) }
        }
    }

    // ── 侦察/外交 ──────────────────────────────────────────────────────

    suspend fun processScoutInfoExpiryLazy(year: Int, month: Int) {
        val data = stateStore.gameData.value
        val hasExpired = data.scoutInfo.any { (_, info) ->
            year > info.expiryYear || (year == info.expiryYear && month > info.expiryMonth)
        }
        if (!hasExpired) return
        processScoutInfoExpiry(year, month)
    }

    suspend fun processScoutInfoExpiry(year: Int, month: Int) {
        val data = stateStore.gameData.value
        var hasExpired = false

        val updatedScoutInfo = data.scoutInfo.filter { (_, info) ->
            val isExpired = year > info.expiryYear ||
                (year == info.expiryYear && month > info.expiryMonth)
            if (isExpired) {
                hasExpired = true
            }
            !isExpired
        }

        if (hasExpired) {
            val updatedWorldMapSects = data.worldMapSects.map { sect ->
                val sectScoutInfo = updatedScoutInfo[sect.id]
                if (sectScoutInfo == null && data.sectDetails[sect.id]?.scoutInfo?.sectId?.isNotEmpty() == true) {
                    sect.copy(isKnown = false)
                } else {
                    sect
                }
            }

            val updatedDetails = data.sectDetails.toMutableMap()
            updatedScoutInfo.forEach { (sectId, _) ->
                val detail = updatedDetails[sectId] ?: SectDetail(sectId = sectId)
                updatedDetails[sectId] = detail.copy(scoutInfo = updatedScoutInfo[sectId] ?: SectScoutInfo())
            }
            data.sectDetails.forEach { (sectId, detail) ->
                if (updatedScoutInfo[sectId] == null && detail.scoutInfo.sectId.isNotEmpty()) {
                    updatedDetails[sectId] = detail.copy(scoutInfo = SectScoutInfo())
                }
            }

            stateStore.update {
                gameData = gameData.copy(
                    scoutInfo = updatedScoutInfo,
                    worldMapSects = updatedWorldMapSects,
                    sectDetails = updatedDetails
                )
            }
        }
    }

    suspend fun processTheftIfNeeded() {
        if (stateStore.gameData.value.spiritStones <= 0) return
        val tables = stateStore.discipleTables
        val moralThreshold =
            GameConfig.LawEnforcementConfig.MORALITY_THRESHOLD
        val loyalThreshold =
            GameConfig.LawEnforcementConfig.LOYALTY_THRESHOLD
        val hasLowMoralityDisciple = tables.ids.any { id ->
            tables.isAlive.getOrDefault(id, 0) == 1 &&
            tables.statuses.getOrDefault(id, DiscipleStatus.IDLE) ==
                DiscipleStatus.IDLE &&
            tables.moralities.getOrDefault(id, 0) < moralThreshold &&
            tables.loyalties.getOrDefault(id, 0) < loyalThreshold
        }
        if (!hasLowMoralityDisciple) return
        processTheftMonthly()
    }

    // ── 任务 ──────────────────────────────────────────────────────────

    suspend fun processCompletedMissionsLazy(year: Int, month: Int) {
        val data = stateStore.gameData.value
        val currentAbsoluteMonth = com.xianxia.sect.core.engine.LazyEvaluationDispatcher.toAbsoluteMonth(year, month)
        val completedIds = mutableListOf<String>()
        val remainingActive = mutableListOf<ActiveMission>()

        for (activeMission in data.activeMissions) {
            val missionCompletionMonth = com.xianxia.sect.core.engine.LazyEvaluationDispatcher.toAbsoluteMonth(
                activeMission.startYear, activeMission.startMonth
            ) + activeMission.duration
            if (currentAbsoluteMonth < missionCompletionMonth) {
                remainingActive.add(activeMission)
                continue
            }
            if (activeMission.isComplete(year, month)) {
                completedIds.add(activeMission.id)

                val aliveDisciples = activeMission.discipleIds.mapNotNull { did ->
                    stateStore.disciples.value.find { it.id == did && it.isAlive }
                }
                val allDead = aliveDisciples.isEmpty()

                if (!allDead) {
                    val equipMap = stateStore.equipmentInstances.value.associateBy { it.id }
                    val manualMap = stateStore.manualInstances.value.associateBy { it.id }
                    val proficiencies = stateStore.gameData.value.manualProficiencies.mapValues { (_, list) ->
                        list.associateBy { it.manualId }
                    }
                    val result = MissionSystem.processMissionCompletion(
                        activeMission, aliveDisciples, equipMap, manualMap, proficiencies, battleSystem
                    )
                    if (result.spiritStones > 0) {
                        val sp = result.spiritStones.toLong()
                        stateStore.update { gameData = gameData.copy(spiritStones = gameData.spiritStones + sp) }
                    }
                    result.materials.forEach { material ->
                        inventorySystem.addMaterial(material)
                    }
                    result.pills.forEach { pill -> inventorySystem.addPill(pill) }
                    result.equipmentStacks.forEach { equip -> inventorySystem.addEquipmentStack(equip) }
                    result.manualStacks.forEach { manual -> inventorySystem.addManualStack(manual) }

                    if (result.combatTriggered && result.victory && result.battleResult != null) {
                        val missionSurvivorIds = result.battleResult.log.teamMembers
                            .filter { it.isAlive }.map { it.id }.toSet()
                        stateStore.update {
                            val mapped = discipleTables.assembleAll().map { d ->
                                if (d.id in missionSurvivorIds && d.isAlive) d.copy(soulPower = d.soulPower + 1) else d
                            }
                            discipleTables.clear()
                            mapped.forEach { discipleTables.insert(it) }
                        }
                    }
                }

                for (did in activeMission.discipleIds) {
                    stateStore.update {
                        val mapped = discipleTables.assembleAll().map {
                            if (it.id == did && it.isAlive) it.copy(status = DiscipleStatus.IDLE) else it
                        }
                        discipleTables.clear()
                        mapped.forEach { discipleTables.insert(it) }
                    }
                }
            } else {
                remainingActive.add(activeMission)
            }
        }

        if (completedIds.isNotEmpty()) {
            stateStore.update { gameData = gameData.copy(activeMissions = remainingActive) }
        }
    }

    suspend fun processMissionRefreshIfDue(month: Int) {
        if (month % MissionSystem.REFRESH_INTERVAL_MONTHS != 0) return
        processMissionRefresh()
    }

    suspend fun processMissionRefresh() {
        val data = stateStore.gameData.value
        val result = MissionSystem.processMonthlyRefresh(
            data.availableMissions,
            data.gameYear,
            data.gameMonth
        )
        stateStore.update { gameData = gameData.copy(availableMissions = result.cleanedMissions) }
    }

    // ── 游戏结束 ──────────────────────────────────────────────────────

    suspend fun checkGameOverCondition() {
        val currentData = stateStore.gameData.value
        if (currentData.isGameOver) return

        val playerSect = currentData.worldMapSects.find { it.isPlayerSect } ?: return
        val playerSectId = playerSect.id

        val playerControlsAnySect = currentData.worldMapSects.any { sect ->
            (sect.isPlayerSect && sect.occupierSectId.isEmpty()) ||
            (sect.occupierSectId == playerSectId && !sect.isPlayerSect)
        }

        if (!playerControlsAnySect) {
            stateStore.update { this.gameData = this.gameData.copy(isGameOver = true) }
        }
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────

    suspend fun clearDiscipleFromAllSlots(discipleId: String) {
        discipleLifecycleProcessor.clearDiscipleFromAllSlots(discipleId)
    }

    suspend fun handleDiscipleDeath(disciple: Disciple, isOutsideSect: Boolean = false) {
        discipleLifecycleProcessor.handleDiscipleDeath(disciple, isOutsideSect)
    }

    suspend fun returnEquipmentToWarehouse(equipmentId: String) {
        discipleLifecycleProcessor.returnEquipmentToWarehouse(equipmentId)
    }

    suspend fun removeEquipmentFromDisciple(discipleId: String, equipmentId: String) {
        discipleLifecycleProcessor.removeEquipmentFromDisciple(discipleId, equipmentId)
    }

    fun qualifiesForSectAutoPublic(disciple: Disciple, focused: Boolean, rootCounts: Set<Int>): Boolean {
        if (focused || rootCounts.isNotEmpty()) {
            if (focused && disciple.statusData["followed"] == "true") return true
            val rootCount = disciple.spiritRootType.split(",").size
            return rootCount in rootCounts
        }
        return false
    }
}
