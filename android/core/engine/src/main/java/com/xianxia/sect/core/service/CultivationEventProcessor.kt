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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
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
    private val diplomacyEventProcessor: DiplomacyEventProcessor
) {
    private val scope get() = scopeProvider.scope

    companion object {
        private const val TAG = "CultivationEventProc"
    }

    // ── 状态访问器 ──────────────────────────────────────────────────────

    private var currentGameData: GameData
        get() = stateStore.currentTransactionMutableState()?.gameData ?: stateStore.gameData.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.gameData = value; return }
            scope.launch { stateStore.update { gameData = value } }
        }

    private var currentDisciples: List<Disciple>
        get() = stateStore.currentTransactionMutableState()?.disciples ?: stateStore.disciples.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.disciples = value; return }
            scope.launch { stateStore.update { disciples = value } }
        }

    private var pendingNotification: GameNotification?
        get() = stateStore.currentTransactionMutableState()?.pendingNotification
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.pendingNotification = value; return }
            if (value != null) stateStore.setPendingNotification(value)
            else stateStore.clearPendingNotification()
        }

    private var currentEquipmentStacks: List<EquipmentStack>
        get() = stateStore.currentTransactionMutableState()?.equipmentStacks ?: stateStore.equipmentStacks.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.equipmentStacks = value; return }
            scope.launch { stateStore.update { equipmentStacks = value } }
        }

    private var currentEquipmentInstances: List<EquipmentInstance>
        get() = stateStore.currentTransactionMutableState()?.equipmentInstances ?: stateStore.equipmentInstances.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.equipmentInstances = value; return }
            scope.launch { stateStore.update { equipmentInstances = value } }
        }

    private var currentManualStacks: List<ManualStack>
        get() = stateStore.currentTransactionMutableState()?.manualStacks ?: stateStore.manualStacks.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.manualStacks = value; return }
            scope.launch { stateStore.update { manualStacks = value } }
        }

    private var currentManualInstances: List<ManualInstance>
        get() = stateStore.currentTransactionMutableState()?.manualInstances ?: stateStore.manualInstances.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.manualInstances = value; return }
            scope.launch { stateStore.update { manualInstances = value } }
        }

    private val phaseMultiplier: Int get() = 10

    // ── 时间推进 ──────────────────────────────────────────────────────

    suspend fun advancePhase(state: MutableGameState? = null) {
        val targetState = state ?: return
        val data = targetState.gameData
        val phase = data.gamePhase
        val month = data.gameMonth
        val year = data.gameYear

        processPhaseEvents(phase, month, year)

        val focusedId = stateStore.focusedDiscipleId
        if (focusedId != null) {
            val newHfd = cultivationCore.updateFocusedDisciple(focusedId, targetState, sharedState.highFrequencyData.value, breakthroughHandler)
            sharedState.highFrequencyData.value = newHfd
        }
    }

    suspend fun advanceMonth(state: MutableGameState? = null) {
        val data = state?.gameData ?: currentGameData
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
        if (state != null) state.gameData = updatedData else currentGameData = updatedData

        if (isYearChanged) {
            processYearlyEvents(newYear)
        }

        processMonthlyEvents(newYear, newMonth)
    }

    suspend fun advanceYear(state: MutableGameState? = null) {
        val data = state?.gameData ?: currentGameData
        val newYear = data.gameYear + 1

        val updatedData = data.copy(
            gameYear = newYear,
            gameMonth = 1,
            gamePhase = 0
        )
        if (state != null) state.gameData = updatedData else currentGameData = updatedData

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

    private suspend fun processPhaseEvents(phase: Int, month: Int, year: Int) {
        safelyRun("checkGameOverCondition") { checkGameOverCondition() }
        safelyRun("processPhaseTick") { processPhaseTick(year, month, phase) }
        safelyRun("syncAllDiscipleStatuses") { discipleService.syncAllDiscipleStatuses() }
    }

    // ── Phase Tick ──────────────────────────────────────────────────────

    private suspend fun processPhaseTick(year: Int, month: Int, phase: Int) {
        val phaseSecondsValue = gameClock.msPerPhase / 1000.0
        val equipmentMap = currentEquipmentInstances.associateBy { it.id }
        val manualMap = currentManualInstances.associateBy { it.id }
        val proficienciesMap = currentGameData.manualProficiencies
        val multiplier = phaseMultiplier.toDouble()
        val decay = phaseMultiplier
        val equipmentStacksList = currentEquipmentStacks
        val manualStacksList = currentManualStacks
        val maxEquipStack = inventoryConfig.getMaxStackSize("equipment_stack")
        val maxManualStack = inventoryConfig.getMaxStackSize("manual_stack")

        val aliveDisciples = currentDisciples.filter { it.isAlive }

        val acc = PhaseTickAccumulator()

        val batchSize = 50
        val processedAlive = mutableListOf<Disciple>()
        for ((index, disciple) in aliveDisciples.withIndex()) {
            processedAlive.add(
                cultivationCore.processDiscipleTick(
                    disciple, year, month, phase, equipmentMap, manualMap, proficienciesMap,
                    multiplier, decay, equipmentStacksList, manualStacksList, maxEquipStack, maxManualStack,
                    acc, phaseSecondsValue,
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
                    val gain = cultivationRate * phaseSecondsValue
                    accumGains[d.id] = (accumGains[d.id] ?: 0.0) + gain
                }
            }
        }
        sharedState.highFrequencyData.value = currentHfd.copy(cultivationUpdates = accumGains)

        val aliveMap = processedAlive.associateBy { it.id }
        currentDisciples = currentDisciples.map { if (it.isAlive) aliveMap[it.id] ?: it else it }

        cultivationCore.applyAccumulator(acc, maxEquipStack, maxManualStack)

        processAutoFromWarehouse(year, month, phase)
    }

    // ── 自动从仓库装备/学习 ──────────────────────────────────────────

    private fun processAutoFromWarehouse(year: Int, month: Int, phase: Int) {
        val equipFocused = currentGameData.autoEquipFromWarehouseFocused
        val equipRootCounts = currentGameData.autoEquipFromWarehouseRootCounts
        val learnFocused = currentGameData.autoLearnFromWarehouseFocused
        val learnRootCounts = currentGameData.autoLearnFromWarehouseRootCounts
        val hasAutoEquip = equipFocused || equipRootCounts.isNotEmpty()
        val hasAutoLearn = learnFocused || learnRootCounts.isNotEmpty()

        if (!hasAutoEquip && !hasAutoLearn) return

        val updatedDisciples = currentDisciples.toMutableList()
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

        var eqStacks = currentEquipmentStacks.filter { it.id !in bagEqIds }
        var mnStacks = currentManualStacks.filter { it.id !in bagMnIds }
        val eqInstancesById = currentEquipmentInstances.associateBy { it.id }
        val mnInstancesById = currentManualInstances.associateBy { it.id }

        val sortedIndices = updatedDisciples.indices
            .filter { updatedDisciples[it].isAlive }
            .sortedWith(compareByDescending<Int> { updatedDisciples[it].statusData["followed"] == "true" }
                .thenBy { updatedDisciples[it].realm }
                .thenByDescending { updatedDisciples[it].realmLayer })

        for (idx in sortedIndices) {
            val disciple = updatedDisciples[idx]
            var d = disciple

            if (qualifiesForSectAutoPublic(d, equipFocused, equipRootCounts)) {
                val result = DiscipleEquipmentManager.processAutoEquipFromWarehouse(
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
                    currentEquipmentInstances = currentEquipmentInstances + result.newInstances
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
                val result = DiscipleManualManager.processAutoLearnFromWarehouse(
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
                    currentManualInstances = currentManualInstances + result.newInstance
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

        currentDisciples = updatedDisciples.toList()
        currentEquipmentStacks = currentEquipmentStacks.filter { it.id in bagEqIds } + eqStacks
        currentManualStacks = currentManualStacks.filter { it.id in bagMnIds } + mnStacks
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
    }

    suspend fun processYearlyEvents(year: Int) {
        discipleLifecycleProcessor.processDiscipleAging(year)
        caveExplorationProcessor.get().processSectDisciplesAging(year)
        discipleLifecycleProcessor.processYearlyAging(year)
        cultivationSettlement.processSalaryYearly(year)
        merchantAndRecruitService.refreshRecruitList(year)
        merchantAndRecruitService.refreshTravelingMerchant(year, 1)
        merchantAndRecruitService.refreshMerchantAcquisition(year, 1)
        diplomacyEventProcessor.processCrossSectPartnerMatching(year, 1)
        diplomacyEventProcessor.checkAllianceExpiry(year)
        diplomacyEventProcessor.checkAllianceFavorDrop()
        diplomacyEventProcessor.processAIAlliances(year)
        discipleLifecycleProcessor.processReflectionRelease(year)
        diplomacyEventProcessor.processFavorDecay(year)
        currentGameData = AISectGarrisonManager.rotateGarrisonSlots(currentGameData)
        discipleLifecycleProcessor.processGriefExpiry(year)
    }

    // ── 执法/盗窃 ──────────────────────────────────────────────────────

    fun calculateCaptureRate(): Double {
        val data = currentGameData
        val elderSlots = data.elderSlots
        val allDisciples = currentDisciples.associateBy { it.id }

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
        val data = currentGameData
        val captureRate = calculateCaptureRate()
        val currentMonthValue = data.gameYear * 12 + data.gameMonth

        val atRiskDisciples = currentDisciples.filter {
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
                    currentDisciples = currentDisciples.map {
                        if (it.id == disciple.id) it.copy(
                            status = DiscipleStatus.REFLECTING,
                            statusData = it.statusData + mapOf(
                                "reflectionStartYear" to currentYear.toString(),
                                "reflectionEndYear" to endYear.toString()
                            )
                        ) else it
                    }
                } else {
                    val discipleSnapshot = disciple.copy()
                    discipleLifecycleProcessor.clearDiscipleFromAllSlots(disciple.id)
                    currentDisciples = currentDisciples.filter { it.id != disciple.id }
                    pendingNotification = GameNotification.DiscipleDesertion(discipleSnapshot)
                }
            }
        }

        processTheftMonthly()
    }

    suspend fun processTheftMonthly() {
        if (currentGameData.spiritStones <= 0) return

        val captureRate = calculateCaptureRate()
        val currentMonthValue = currentGameData.gameYear * 12 + currentGameData.gameMonth

        val atRiskDisciples = currentDisciples.filter {
            it.isAlive &&
            it.status == DiscipleStatus.IDLE &&
            DiscipleStatCalculator.getBaseStats(it).morality < GameConfig.LawEnforcementConfig.MORALITY_THRESHOLD &&
            DiscipleStatCalculator.getBaseStats(it).loyalty < GameConfig.LawEnforcementConfig.LOYALTY_THRESHOLD &&
            (currentMonthValue - it.usage.recruitedMonth) >= GameConfig.LawEnforcementConfig.NEW_DISCIPLE_PROTECTION_MONTHS
        }

        val thiefIds = mutableSetOf<String>()
        val warehouses = currentGameData.placedBuildings.filter {
            it.displayName == "仓库"
        }
        val garrisons = currentGameData.warehouseGarrisons

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
                        val guardDisciple = currentDisciples.find { it.id == garrison.discipleId }
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
                    pendingNotification = GameNotification.DiscipleTheftCaught(disciple)
                } else {
                    val currentData = currentGameData
                    if (currentData.spiritStones <= 0) break
                    val stolenAmount = (currentData.spiritStones * Random.nextDouble(GameConfig.LawEnforcementConfig.THEFT_MIN_RATIO, GameConfig.LawEnforcementConfig.THEFT_MAX_RATIO)).toLong().coerceAtLeast(1)
                    currentGameData = currentData.copy(spiritStones = (currentData.spiritStones - stolenAmount).coerceAtLeast(0))
                    currentDisciples = currentDisciples.map {
                        if (it.id == disciple.id) it.copy(
                            equipment = it.equipment.copy(storageBagSpiritStones = it.equipment.storageBagSpiritStones + stolenAmount)
                        ) else it
                    }

                    val loyalty = stats.loyalty
                    val desertionProb = ((GameConfig.LawEnforcementConfig.LOYALTY_THRESHOLD - loyalty) * GameConfig.LawEnforcementConfig.PROB_PER_POINT).coerceIn(0.0, GameConfig.LawEnforcementConfig.MAX_PROB)
                    if (Random.nextDouble() < desertionProb) {
                        thiefIds.add(disciple.id)
                    }

                    pendingNotification = GameNotification.WarehouseTheft(stolenAmount)
                }
            }
        }

        for (thiefId in thiefIds) {
            discipleLifecycleProcessor.clearDiscipleFromAllSlots(thiefId)
        }
        if (thiefIds.isNotEmpty()) {
            currentDisciples = currentDisciples.filter { it.id !in thiefIds }
        }
    }

    // ── 战斗/探索辅助 ──────────────────────────────────────────────────

    fun updateDiscipleHpMpAfterBattle(battleMembers: List<BattleMemberData>) {
        val survivorIds = battleMembers.filter { it.isAlive }.map { it.id }.toSet()
        team@ for (member in battleMembers) {
            val discipleIndex = currentDisciples.indexOfFirst { it.id == member.id }
            if (discipleIndex < 0) continue@team
            val disciple = currentDisciples[discipleIndex]
            if (!survivorIds.contains(member.id)) {
                currentDisciples = currentDisciples.toMutableList().also { it[discipleIndex] = disciple.copy(isAlive = false, status = DiscipleStatus.DEAD) }
            } else {
                val hp = member.hp.coerceAtMost(member.maxHp)
                val mp = member.mp.coerceAtMost(member.maxMp)
                currentDisciples = currentDisciples.toMutableList().also {
                    it[discipleIndex] = disciple.copy(combat = disciple.combat.copy(currentHp = hp, currentMp = mp))
                }
            }
        }
    }

    suspend fun completeExploration(team: ExplorationTeam, success: Boolean, survivorIds: List<String>, survivorHpMap: Map<String, Int> = emptyMap(), survivorMpMap: Map<String, Int> = emptyMap()) {
        team.memberIds.forEach { memberId ->
            val disciple = currentDisciples.find { it.id == memberId }
            if (disciple != null) {
                val shouldKeepAlive = disciple.isAlive && survivorIds.contains(memberId)
                currentDisciples = currentDisciples.map { d ->
                    if (d.id == memberId) {
                        if (shouldKeepAlive) {
                            val hp = survivorHpMap[memberId] ?: d.combat.currentHp
                            val mp = survivorMpMap[memberId] ?: d.combat.currentMp
                            d.copy(status = DiscipleStatus.IDLE, combat = d.combat.copy(currentHp = hp, currentMp = mp))
                        } else {
                            discipleLifecycleProcessor.handleDiscipleDeath(d, isOutsideSect = true)
                            d.copy(isAlive = false, status = DiscipleStatus.DEAD)
                        }
                    } else d
                }
            }
        }
    }

    // ── 侦察/外交 ──────────────────────────────────────────────────────

    fun processScoutInfoExpiryLazy(year: Int, month: Int) {
        val data = currentGameData
        val hasExpired = data.scoutInfo.any { (_, info) ->
            year > info.expiryYear || (year == info.expiryYear && month > info.expiryMonth)
        }
        if (!hasExpired) return
        processScoutInfoExpiry(year, month)
    }

    fun processScoutInfoExpiry(year: Int, month: Int) {
        val data = currentGameData
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

            currentGameData = data.copy(
                scoutInfo = updatedScoutInfo,
                worldMapSects = updatedWorldMapSects,
                sectDetails = updatedDetails
            )
        }
    }

    suspend fun processTheftIfNeeded() {
        if (currentGameData.spiritStones <= 0) return
        val hasLowMoralityDisciple = currentDisciples.any {
            it.isAlive && it.status == DiscipleStatus.IDLE &&
            DiscipleStatCalculator.getBaseStats(it).morality < GameConfig.LawEnforcementConfig.MORALITY_THRESHOLD &&
            DiscipleStatCalculator.getBaseStats(it).loyalty < GameConfig.LawEnforcementConfig.LOYALTY_THRESHOLD
        }
        if (!hasLowMoralityDisciple) return
        processTheftMonthly()
    }

    // ── 任务 ──────────────────────────────────────────────────────────

    fun processCompletedMissionsLazy(year: Int, month: Int) {
        val data = currentGameData
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
                    currentDisciples.find { it.id == did && it.isAlive }
                }
                val allDead = aliveDisciples.isEmpty()

                if (!allDead) {
                    val equipMap = currentEquipmentInstances.associateBy { it.id }
                    val manualMap = currentManualInstances.associateBy { it.id }
                    val proficiencies = currentGameData.manualProficiencies.mapValues { (_, list) ->
                        list.associateBy { it.manualId }
                    }
                    val result = MissionSystem.processMissionCompletion(
                        activeMission, aliveDisciples, equipMap, manualMap, proficiencies, battleSystem
                    )
                    if (result.spiritStones > 0) {
                        currentGameData = currentGameData.copy(
                            spiritStones = currentGameData.spiritStones + result.spiritStones.toLong()
                        )
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
                        currentDisciples = currentDisciples.map { d ->
                            if (d.id in missionSurvivorIds && d.isAlive) {
                                d.copyWith(soulPower = d.soulPower + 1)
                            } else d
                        }
                    }
                }

                for (did in activeMission.discipleIds) {
                    val disciple = currentDisciples.find { it.id == did }
                    if (disciple != null && disciple.isAlive) {
                        currentDisciples = currentDisciples.map {
                            if (it.id == did) it.copy(status = DiscipleStatus.IDLE) else it
                        }
                    }
                }
            } else {
                remainingActive.add(activeMission)
            }
        }

        if (completedIds.isNotEmpty()) {
            currentGameData = currentGameData.copy(activeMissions = remainingActive)
        }
    }

    fun processMissionRefreshIfDue(month: Int) {
        if (month % MissionSystem.REFRESH_INTERVAL_MONTHS != 1) return
        processMissionRefresh()
    }

    fun processMissionRefresh() {
        val data = currentGameData
        val result = MissionSystem.processMonthlyRefresh(
            data.availableMissions,
            data.gameYear,
            data.gameMonth
        )
        currentGameData = currentGameData.copy(availableMissions = result.cleanedMissions)
    }

    // ── 游戏结束 ──────────────────────────────────────────────────────

    fun checkGameOverCondition() {
        if (currentGameData.isGameOver) return

        val playerSect = currentGameData.worldMapSects.find { it.isPlayerSect } ?: return
        val playerSectId = playerSect.id

        val playerControlsAnySect = currentGameData.worldMapSects.any { sect ->
            (sect.isPlayerSect && sect.occupierSectId.isEmpty()) ||
            (sect.occupierSectId == playerSectId && !sect.isPlayerSect)
        }

        if (!playerControlsAnySect) {
            currentGameData = currentGameData.copy(isGameOver = true)
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
