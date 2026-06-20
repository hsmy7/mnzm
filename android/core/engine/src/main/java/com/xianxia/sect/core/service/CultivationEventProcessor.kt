package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    private val autoBuyService: AutoBuyService
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

        val focusedId = stateStore.focusedDiscipleId
        if (focusedId != null) {
            val newHfd = cultivationCore.updateFocusedDisciple(focusedId, targetState, sharedState.highFrequencyData.value, breakthroughHandler)
            sharedState.highFrequencyData.value = newHfd
        }
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

        val batchSize = 50
        val processedAlive = mutableListOf<Disciple>()
        for ((index, disciple) in aliveDisciples.withIndex()) {
            processedAlive.add(
                cultivationCore.processDiscipleTick(
                    disciple, year, month, phase, equipmentMap, manualMap, proficienciesMap,
                    multiplier, decay, equipmentStacksList, manualStacksList, maxEquipStack, maxManualStack,
                    acc,
                    stateStore.focusedDiscipleId,
                    sharedState.cachedCultivationRates,
                    sharedState.highFrequencyData.value,
                    sharedState.autoEquipDirty,
                    sharedState.autoLearnDirty
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
            if (d.id != stateStore.focusedDiscipleId) {
                val cultivationRate = sharedState.cachedCultivationRates[d.id] ?: 0.0
                if (cultivationRate > 0 && d.cultivation < d.maxCultivation) {
                    accumGains[d.id] = (accumGains[d.id] ?: 0.0) + cultivationRate
                }
            }
        }
        sharedState.highFrequencyData.value = currentHfd.copy(
            cultivationUpdates = accumGains,
            focusedPhaseCount = currentHfd.focusedPhaseCount + 1
        )

        val aliveMap = processedAlive.associateBy { it.id }
        val updatedDisciplesList = stateStore.disciples.value.map { if (it.isAlive) aliveMap[it.id] ?: it else it }
        // 直接操作事务内状态 state（即外层 tick 持有的 reusableMutableState）。
        // 绝不能调用 stateStore.update{}——advancePhase 处于 tick 的 update 块内，
        // transactionMutex 不可重入，嵌套调用会死锁（核心修复前的根因）。
        state.discipleTables.clear()
        updatedDisciplesList.forEach { state.discipleTables.insert(it) }

        cultivationCore.applyAccumulator(acc, state, maxEquipStack, maxManualStack)

        processAutoFromWarehouse(year, month, phase, state)
    }

    // ── 自动从仓库装备/学习 ──────────────────────────────────────────

    /**
     * 自动从仓库装备/学习。
     * 直接操作事务内状态 [state]，不使用异步协程，避免影子事务覆盖问题。
     */
    /** 月度自动从仓库装备/学习（月结制专用） */
    fun processAutoFromWarehouseMonthly(year: Int, month: Int, state: MutableGameState) {
        processAutoFromWarehouse(year, month, 0, state)
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

        // 直接写回事务内状态，不使用异步协程
        tables.clear()
        updatedDisciples.forEach { tables.insert(it) }
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

    suspend fun processMonthlyEvents(year: Int, month: Int) {
        caveExplorationProcessor.get().processAISectOperations(year, month)
        checkGameOverCondition()
        processScoutInfoExpiryLazy(year, month)
        diplomacyEventProcessor.processDiplomacyMonthlyEventsCapped(year, month)
        processTheftIfNeeded()
        processMissionRefreshIfDue(month)
        processCompletedMissionsLazy(year, month)
        if (month == 12) {
            autoBuyService.executeAutoBuy(year, month)
        }
    }

    suspend fun processYearlyEvents(year: Int) {
        discipleLifecycleProcessor.processDiscipleAging(year)
        caveExplorationProcessor.get().processSectDisciplesAging(year)
        discipleLifecycleProcessor.processYearlyAging(year)
        cultivationSettlement.processSalaryYearly(year)
        merchantAndRecruitService.refreshRecruitList(year)
        merchantAndRecruitService.refreshTravelingMerchant(year, 1)
        autoBuyService.executeAutoBuy(year, 1)
        merchantAndRecruitService.refreshMerchantAcquisition(year, 1)
        diplomacyEventProcessor.processCrossSectPartnerMatching(year, 1)
        diplomacyEventProcessor.checkAllianceExpiry(year)
        diplomacyEventProcessor.checkAllianceFavorDrop()
        diplomacyEventProcessor.processAIAlliances(year)
        discipleLifecycleProcessor.processReflectionRelease(year)
        diplomacyEventProcessor.processFavorDecay(year)
        val rotated = AISectGarrisonManager.rotateGarrisonSlots(stateStore.gameData.value)
        stateStore.update { gameData = rotated }
        discipleLifecycleProcessor.processGriefExpiry(year)
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

        val atRiskDisciples = stateStore.disciples.value.filter {
            it.isAlive &&
            it.status == DiscipleStatus.IDLE &&
            DiscipleStatCalculator.getBaseStats(it).loyalty < GameConfig.LawEnforcementConfig.LOYALTY_THRESHOLD &&
            (currentMonthValue - it.usage.recruitedMonth) >= GameConfig.LawEnforcementConfig.NEW_DISCIPLE_PROTECTION_MONTHS
        }

        for (disciple in atRiskDisciples) {
            val effectiveLoyalty = DiscipleStatCalculator.getBaseStats(disciple).loyalty
            val desertionProb = ((GameConfig.LawEnforcementConfig.LOYALTY_THRESHOLD - effectiveLoyalty) * GameConfig.LawEnforcementConfig.PROB_PER_POINT).coerceIn(0.0, GameConfig.LawEnforcementConfig.MAX_PROB)
            if (Random.nextDouble() < desertionProb) {
                if (Random.nextDouble() < captureRate) {
                    val currentYear = data.gameYear
                    val endYear = currentYear + GameConfig.LawEnforcementConfig.REFLECTION_YEARS
                    stateStore.update {
                        val mapped = discipleTables.assembleAll().map {
                            if (it.id == disciple.id) it.copy(
                                status = DiscipleStatus.REFLECTING,
                                statusData = it.statusData + mapOf(
                                    "reflectionStartYear" to currentYear.toString(),
                                    "reflectionEndYear" to endYear.toString()
                                )
                            ) else it
                        }
                        discipleTables.clear()
                        mapped.forEach { discipleTables.insert(it) }
                    }
                } else {
                    val discipleSnapshot = disciple.copy()
                    discipleLifecycleProcessor.clearDiscipleFromAllSlots(disciple.id)
                    stateStore.update {
                        val filtered = discipleTables.assembleAll().filter { it.id != disciple.id }
                        discipleTables.clear()
                        filtered.forEach { discipleTables.insert(it) }
                    }
                    stateStore.setPendingNotification(GameNotification.DiscipleDesertion(discipleSnapshot))
                }
            }
        }

        processTheftMonthly()
    }

    suspend fun processTheftMonthly() {
        val currentData = stateStore.gameData.value
        if (currentData.spiritStones <= 0) return

        val captureRate = calculateCaptureRate()
        val currentMonthValue = currentData.gameYear * 12 + currentData.gameMonth

        val atRiskDisciples = stateStore.disciples.value.filter {
            it.isAlive &&
            it.status == DiscipleStatus.IDLE &&
            DiscipleStatCalculator.getBaseStats(it).morality < GameConfig.LawEnforcementConfig.MORALITY_THRESHOLD &&
            DiscipleStatCalculator.getBaseStats(it).loyalty < GameConfig.LawEnforcementConfig.LOYALTY_THRESHOLD &&
            (currentMonthValue - it.usage.recruitedMonth) >= GameConfig.LawEnforcementConfig.NEW_DISCIPLE_PROTECTION_MONTHS
        }

        val thiefIds = mutableSetOf<String>()
        val warehouses = currentData.placedBuildings.filter {
            it.displayName == "仓库"
        }
        val garrisons = currentData.warehouseGarrisons

        for (disciple in atRiskDisciples) {
            val stats = DiscipleStatCalculator.getBaseStats(disciple)
            val effectiveMorality = stats.morality
            val theftProb = ((GameConfig.LawEnforcementConfig.MORALITY_THRESHOLD - effectiveMorality) * GameConfig.LawEnforcementConfig.PROB_PER_POINT).coerceIn(0.0, GameConfig.LawEnforcementConfig.MAX_PROB)
            if (Random.nextDouble() < theftProb) {
                val caught = if (warehouses.isNotEmpty()) {
                    val warehouse = warehouses[Random.nextInt(warehouses.size)]
                    val garrison = garrisons.find { it.buildingInstanceId == warehouse.instanceId && it.isActive }
                    if (garrison == null) {
                        false
                    } else {
                        val guardDisciple = stateStore.disciples.value.find { it.id == garrison.discipleId }
                        if (guardDisciple == null) {
                            false
                        } else {
                            val thiefStats = DiscipleStatCalculator.getBaseStats(disciple)
                            val guardStats = DiscipleStatCalculator.getBaseStats(guardDisciple)
                            val thiefPower = thiefStats.physicalAttack + thiefStats.magicAttack +
                                    thiefStats.physicalDefense + thiefStats.magicDefense + thiefStats.speed
                            val guardPower = guardStats.physicalAttack + guardStats.magicAttack +
                                    guardStats.physicalDefense + guardStats.magicDefense + guardStats.speed
                            val thiefWinProb = (thiefPower.toDouble() / (thiefPower + guardPower).coerceAtLeast(1)).coerceIn(0.1, 0.9)
                            Random.nextDouble() >= thiefWinProb
                        }
                    }
                } else {
                    Random.nextDouble() < captureRate
                }

                if (caught) {
                    stateStore.setPendingNotification(GameNotification.DiscipleTheftCaught(disciple))
                } else {
                    val currentData = stateStore.gameData.value
                    if (currentData.spiritStones <= 0) break
                    val stolenAmount = (currentData.spiritStones * Random.nextDouble(GameConfig.LawEnforcementConfig.THEFT_MIN_RATIO, GameConfig.LawEnforcementConfig.THEFT_MAX_RATIO)).toLong().coerceAtLeast(1)
                    stateStore.update {
                        gameData = gameData.copy(spiritStones = (gameData.spiritStones - stolenAmount).coerceAtLeast(0))
                        discipleTables.assembleAll().firstOrNull { it.id == disciple.id }?.let { d ->
                            discipleTables.update(
                                d.copy(equipment = d.equipment.copy(storageBagSpiritStones = d.equipment.storageBagSpiritStones + stolenAmount))
                            )
                        }
                    }

                    val loyalty = stats.loyalty
                    val desertionProb = ((GameConfig.LawEnforcementConfig.LOYALTY_THRESHOLD - loyalty) * GameConfig.LawEnforcementConfig.PROB_PER_POINT).coerceIn(0.0, GameConfig.LawEnforcementConfig.MAX_PROB)
                    if (Random.nextDouble() < desertionProb) {
                        thiefIds.add(disciple.id)
                    }

                    stateStore.setPendingNotification(GameNotification.WarehouseTheft(stolenAmount))
                }
            }
        }

        for (thiefId in thiefIds) {
            discipleLifecycleProcessor.clearDiscipleFromAllSlots(thiefId)
        }
        if (thiefIds.isNotEmpty()) {
            stateStore.update {
                val filtered = discipleTables.assembleAll().filter { it.id !in thiefIds }
                discipleTables.clear()
                filtered.forEach { discipleTables.insert(it) }
            }
        }
    }

    // ── 战斗/探索辅助 ──────────────────────────────────────────────────

    fun updateDiscipleHpMpAfterBattle(battleMembers: List<BattleMemberData>) {
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
            scope.launch {
                stateStore.update {
                    discipleTables.clear()
                    disciples.forEach { discipleTables.insert(it) }
                }
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

    fun processScoutInfoExpiryLazy(year: Int, month: Int) {
        val data = stateStore.gameData.value
        val hasExpired = data.scoutInfo.any { (_, info) ->
            year > info.expiryYear || (year == info.expiryYear && month > info.expiryMonth)
        }
        if (!hasExpired) return
        processScoutInfoExpiry(year, month)
    }

    fun processScoutInfoExpiry(year: Int, month: Int) {
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

            scope.launch {
                stateStore.update {
                    gameData = gameData.copy(
                        scoutInfo = updatedScoutInfo,
                        worldMapSects = updatedWorldMapSects,
                        sectDetails = updatedDetails
                    )
                }
            }
        }
    }

    suspend fun processTheftIfNeeded() {
        if (stateStore.gameData.value.spiritStones <= 0) return
        val hasLowMoralityDisciple = stateStore.disciples.value.any {
            it.isAlive && it.status == DiscipleStatus.IDLE &&
            DiscipleStatCalculator.getBaseStats(it).morality < GameConfig.LawEnforcementConfig.MORALITY_THRESHOLD &&
            DiscipleStatCalculator.getBaseStats(it).loyalty < GameConfig.LawEnforcementConfig.LOYALTY_THRESHOLD
        }
        if (!hasLowMoralityDisciple) return
        processTheftMonthly()
    }

    // ── 任务 ──────────────────────────────────────────────────────────

    fun processCompletedMissionsLazy(year: Int, month: Int) {
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
                        scope.launch { stateStore.update { gameData = gameData.copy(spiritStones = gameData.spiritStones + sp) } }
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
                        scope.launch { stateStore.update {
                            val mapped = discipleTables.assembleAll().map { d ->
                                if (d.id in missionSurvivorIds && d.isAlive) d.copy(soulPower = d.soulPower + 1) else d
                            }
                            discipleTables.clear()
                            mapped.forEach { discipleTables.insert(it) }
                        } }
                    }
                }

                for (did in activeMission.discipleIds) {
                    scope.launch { stateStore.update {
                        val mapped = discipleTables.assembleAll().map {
                            if (it.id == did && it.isAlive) it.copy(status = DiscipleStatus.IDLE) else it
                        }
                        discipleTables.clear()
                        mapped.forEach { discipleTables.insert(it) }
                    } }
                }
            } else {
                remainingActive.add(activeMission)
            }
        }

        if (completedIds.isNotEmpty()) {
            scope.launch { stateStore.update { gameData = gameData.copy(activeMissions = remainingActive) } }
        }
    }

    fun processMissionRefreshIfDue(month: Int) {
        if (month % MissionSystem.REFRESH_INTERVAL_MONTHS != 1) return
        processMissionRefresh()
    }

    fun processMissionRefresh() {
        val data = stateStore.gameData.value
        val result = MissionSystem.processMonthlyRefresh(
            data.availableMissions,
            data.gameYear,
            data.gameMonth
        )
        scope.launch { stateStore.update { gameData = gameData.copy(availableMissions = result.cleanedMissions) } }
    }

    // ── 游戏结束 ──────────────────────────────────────────────────────

    fun checkGameOverCondition() {
        val currentData = stateStore.gameData.value
        if (currentData.isGameOver) return

        val playerSect = currentData.worldMapSects.find { it.isPlayerSect } ?: return
        val playerSectId = playerSect.id

        val playerControlsAnySect = currentData.worldMapSects.any { sect ->
            (sect.isPlayerSect && sect.occupierSectId.isEmpty()) ||
            (sect.occupierSectId == playerSectId && !sect.isPlayerSect)
        }

        if (!playerControlsAnySect) {
            scope.launch { stateStore.update { this.gameData = this.gameData.copy(isGameOver = true) } }
        }
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────

    suspend fun clearDiscipleFromAllSlots(discipleId: String) {
        discipleLifecycleProcessor.clearDiscipleFromAllSlots(discipleId)
    }

    suspend fun handleDiscipleDeath(disciple: Disciple, isOutsideSect: Boolean = false) {
        discipleLifecycleProcessor.handleDiscipleDeath(disciple, isOutsideSect)
    }

    fun returnEquipmentToWarehouse(equipmentId: String) {
        discipleLifecycleProcessor.returnEquipmentToWarehouse(equipmentId)
    }

    fun removeEquipmentFromDisciple(discipleId: String, equipmentId: String) {
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
