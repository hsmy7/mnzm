package com.xianxia.sect.core.engine.service
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.*
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.BattleMemberData
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.domain.disciple.DiscipleEquipmentManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleManualManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleService
import com.xianxia.sect.core.engine.domain.disciple.ITEM_TYPE_EQUIPMENT_INSTANCE
import com.xianxia.sect.core.engine.domain.disciple.ITEM_TYPE_EQUIPMENT_STACK
import com.xianxia.sect.core.engine.domain.disciple.ITEM_TYPE_MANUAL_INSTANCE
import com.xianxia.sect.core.engine.domain.disciple.ITEM_TYPE_MANUAL_STACK
import com.xianxia.sect.core.engine.domain.exploration.MissionSystem
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.domain.battle.AISectGarrisonManager
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.engine.domain.diplomacy.VassalService
import com.xianxia.sect.core.exploration.AISectBeastAttackProcessor
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
@GameService("CultivationEventProcessor")
class CultivationEventProcessor @Inject constructor(
    private val stateStore: GameStateStore,
    private val spiritStoneWallet: SpiritStoneWallet,
    private val inventorySystem: InventorySystem,
    private val inventoryConfig: InventoryConfig,
    private val scopeProvider: CoroutineScopeProvider,
    private val discipleService: DiscipleService,
    private val cultivationCore: CultivationCore,
    private val breakthroughHandler: DiscipleBreakthroughHandler,
    private val cultivationSettlement: CultivationSettlement,
    private val battleSystem: BattleSystem,
    private val merchantAndRecruitService: MerchantAndRecruitService,
    private val caveExplorationProcessor: javax.inject.Provider<CaveExplorationProcessor>,
    private val discipleLifecycleProcessor: DiscipleLifecycleProcessor,
    private val diplomacyEventProcessor: DiplomacyEventProcessor,
    private val equipmentManager: DiscipleEquipmentManager,
    private val manualManager: DiscipleManualManager,
    private val autoBuyService: AutoBuyService,
    private val vassalService: VassalService,
    private val disciplePurchaseService: DisciplePurchaseService,
    private val aiSectBeastAttackProcessor: AISectBeastAttackProcessor,
    private val rngManager: GameRngManager
) {
    private val scope get() = scopeProvider.scope
    companion object {
        private const val TAG = "CultivationEventProc"
    }
    // ── 时间推进 ──────────────────────────────────────────────────────
    fun advanceMonth(state: MutableGameState? = null) {
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
    fun advanceYear(state: MutableGameState? = null) {
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
    private fun safelyRun(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainLog.e(TAG, "Error in $name", e)
        }
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

        // Phase 1: 列级直读收集所有储物袋中的物品ID（无需 assemble）
        val bagEqIds = mutableSetOf<String>()
        val bagMnIds = mutableSetOf<String>()
        for (id in tables.ids) {
            if (tables.isAlive[id] != 1) continue
            for (item in tables.storageBagItems.getOrDefault(id, emptyList())) {
                when (item.itemType) {
                    "equipment_stack" -> bagEqIds.add(item.itemId)
                    "manual_stack" -> bagMnIds.add(item.itemId)
                }
            }
        }

        // Phase 2: 列级预过滤后只 assemble 储物袋有匹配物品的弟子
        val updatedDisciples = tables.ids.filter { tables.isAlive[it] == 1 }
            .filter { id ->
                val bags = tables.storageBagItems.getOrDefault(id, emptyList())
                bags.any { item ->
                    (item.itemType == "equipment_stack" && hasAutoEquip) ||
                    (item.itemType == "manual_stack" && hasAutoLearn)
                }
            }
            .mapNotNull { tables.assemble(it)?.takeIf { d -> d.isAlive } }
            .toMutableList()
        var eqStacks = state.equipmentStacks.all().filter { it.id !in bagEqIds }
        var mnStacks = state.manualStacks.all().filter { it.id !in bagMnIds }
        val eqInstancesById = state.equipmentInstances.associateById()
        val mnInstancesById = state.manualInstances.associateById()
        val newEqInstances = mutableListOf<EquipmentInstance>()
        val newMnInstances = mutableListOf<ManualInstance>()
        val sortedIndices = updatedDisciples.indices
            .sortedWith(compareByDescending<Int> { updatedDisciples[it].statusData["followed"] == "true" }
                .thenBy { updatedDisciples[it].realm }
                .thenByDescending { updatedDisciples[it].realmLayer })
        for (idx in sortedIndices) {
            val disciple = updatedDisciples[idx]
            var d = disciple
            if (qualifiesForSectAutoPublic(d, equipFocused, equipRootCounts)) {
                val result = processSingleAutoEquip(d, year, month, phase, tables, eqStacks, eqInstancesById, newEqInstances)
                d = result.first
                eqStacks = result.second
            }
            if (qualifiesForSectAutoPublic(d, learnFocused, learnRootCounts)) {
                val result = processSingleAutoLearn(d, year, month, phase, tables, mnStacks, mnInstancesById, newMnInstances)
                d = result.first
                mnStacks = result.second
            }
            if (d !== disciple) {
                updatedDisciples[idx] = d
            }
        }
        writeAutoWarehouseResults(state, tables, updatedDisciples, bagEqIds, bagMnIds, eqStacks, mnStacks, newEqInstances, newMnInstances)
    }

    /**
     * 处理单个弟子的自动装备：调用 equipmentManager 后更新堆叠状态并记录日志。
     * @return (更新后的弟子, 更新后的装备堆叠列表)
     */
    private fun processSingleAutoEquip(
        d: Disciple, year: Int, month: Int, phase: Int, tables: DiscipleTables,
        eqStacks: List<EquipmentStack>, eqInstancesById: Map<String, EquipmentInstance>,
        newEqInstances: MutableList<EquipmentInstance>
    ): Pair<Disciple, List<EquipmentStack>> {
        val result = equipmentManager.processAutoEquipFromWarehouse(
            disciple = d, warehouseStacks = eqStacks, equipmentInstances = eqInstancesById,
            gameYear = year, gameMonth = month, gamePhase = phase,
            maxStack = inventoryConfig.getMaxStackSize("equipment_stack")
        )
        if (result.newInstances.isEmpty()) return d to eqStacks
        var stacks = eqStacks
        newEqInstances.addAll(result.newInstances)
        val equipName = result.newInstances.firstOrNull()?.name ?: ""
        if (equipName.isNotEmpty()) {
            discipleService.addLifeEvent(d.id, "${tables.ages[d.id.toInt()]}岁：自动装备了${equipName}")
        }
        for (update in result.stackUpdates) {
            stacks = if (update.isDeletion) stacks.filter { it.id != update.stackId }
            else stacks.map { if (it.id == update.stackId) it.copy(quantity = update.newQuantity) else it }
        }
        return result.disciple to stacks
    }

    /**
     * 处理单个弟子的自动学习功法：调用 manualManager 后更新堆叠状态并记录日志。
     * @return (更新后的弟子, 更新后的功法堆叠列表)
     */
    private fun processSingleAutoLearn(
        d: Disciple, year: Int, month: Int, phase: Int, tables: DiscipleTables,
        mnStacks: List<ManualStack>, mnInstancesById: Map<String, ManualInstance>,
        newMnInstances: MutableList<ManualInstance>
    ): Pair<Disciple, List<ManualStack>> {
        val result = manualManager.processAutoLearnFromWarehouse(
            disciple = d, warehouseStacks = mnStacks, manualInstances = mnInstancesById,
            gameYear = year, gameMonth = month, gamePhase = phase,
            maxStack = inventoryConfig.getMaxStackSize("manual_stack")
        )
        if (result.newInstance == null) return d to mnStacks
        var stacks = mnStacks
        newMnInstances.add(result.newInstance)
        val manualName = result.newInstance.name
        if (manualName.isNotEmpty()) {
            discipleService.addLifeEvent(d.id, "${tables.ages[d.id.toInt()]}岁：自动学习了${manualName}")
        }
        result.stackUpdate?.let { update ->
            stacks = if (update.isDeletion) stacks.filter { it.id != update.stackId }
            else stacks.map { if (it.id == update.stackId) it.copy(quantity = update.newQuantity) else it }
        }
        return result.disciple to stacks
    }

    /**
     * 精准字段写回：仅写回自动装备/学习实际修改的字段，不执行全量 clear()+insert()。
     */
    private fun writeAutoWarehouseResults(
        state: MutableGameState, tables: DiscipleTables,
        updatedDisciples: List<Disciple>, bagEqIds: Set<String>, bagMnIds: Set<String>,
        eqStacks: List<EquipmentStack>, mnStacks: List<ManualStack>,
        newEqInstances: List<EquipmentInstance>, newMnInstances: List<ManualInstance>
    ) {
        for (disciple in updatedDisciples) {
            val id = disciple.id.toInt()
            tables.storageBagItems[id] = disciple.equipment.storageBagItems
            tables.weaponIds[id] = disciple.equipment.weaponId
            tables.armorIds[id] = disciple.equipment.armorId
            tables.bootsIds[id] = disciple.equipment.bootsId
            tables.accessoryIds[id] = disciple.equipment.accessoryId

            // 清理被替换功法的残留熟练度
            val oldManualIds = tables.manualIds.getOrDefault(id, emptyList())
            tables.manualIds[id] = disciple.manualIds
            val removedIds = oldManualIds - disciple.manualIds.toSet()
            if (removedIds.isNotEmpty()) {
                val profMap = state.gameData.manualProficiencies.toMutableMap()
                profMap[disciple.id]?.let { list ->
                    val filtered = list.filter { it.manualId !in removedIds }
                    if (filtered.isEmpty()) profMap.remove(disciple.id)
                    else profMap[disciple.id] = filtered
                }
                state.gameData = state.gameData.copy(manualProficiencies = profMap)
            }
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

    /**
     * 在 [stateStore.update] 事务内安全执行子服务。
     * 单个子服务的异常不会阻断整个事务。
     */
    private fun MutableGameState.safelyRunInState(name: String, block: MutableGameState.() -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainLog.e(TAG, "月度事件[$name] 异常", e)
        }
    }

    fun processMonthlyEvents(year: Int, month: Int) {
        // ── 复杂子服务（内部仍有独立 stateStore.update，待优化） ──
        safelyRun("theft") { processTheftIfNeeded() }
        safelyRun("lawEnforcement") { processLawEnforcementMonthly() }
        safelyRun("completedMissions") { processCompletedMissionsLazy(year, month) }

        // ── 已迁移 MutableGameState 的子服务：单事务内执行 ──
        stateStore.update {
            safelyRunInState("aiSectOperations") { caveExplorationProcessor.get().processAISectOperations(year, month, this) }
            safelyRunInState("gameOverCheck") { checkGameOverCondition(this) }
            safelyRunInState("scoutExpiry") { processScoutInfoExpiryLazy(year, month, this) }
            safelyRunInState("aiBeastAttacksRemaining") { aiSectBeastAttackProcessor.processRemainingTargets(this) }
            if (month == 12) {
                safelyRunInState("autoBuy") { autoBuyService.executeAutoBuy(year, month, this) }
            }
            safelyRunInState("spiritMineProduction") { cultivationSettlement.processSpiritMineProductionMonthly(this) }
            safelyRunInState("disciplePurchase") { disciplePurchaseService.executePurchase(year, month, this) }
            safelyRunInState("monthlyCultivation") { processMonthlyCultivationAndAuto(this) }
            safelyRunInState("vassalBreakaway") { vassalService.processMonthlyBreakawayCheck(this) }
            safelyRunInState("missionRefresh") { processMissionRefreshIfDue(month, this) }
        }
    }
    /**
     * 月度 HP/MP 恢复兜底。
     *
     * 修炼累积、自动装备/学习/丹药已在每旬 [checkBreakthroughsAndPills] 中实时处理，
     * 此方法仅做月度 HP/MP 恢复兜底（phaseMultiplier×3 旬的恢复量），
     * 确保批量轨跳过时弟子仍能回满状态。
     *
     * 对标 RimWorld Long Tick — 每月一次性 HP/MP 恢复。
     */
    private fun processMonthlyCultivationAndAuto() {
        stateStore.update { processMonthlyCultivationAndAuto(this) }
    }
    private fun processMonthlyCultivationAndAuto(state: MutableGameState) {
        val data = state.gameData
        val tables = state.discipleTables
        val aliveIds = tables.ids.filter { tables.isAlive[it] == 1 }
        if (aliveIds.isEmpty()) return
        // HP/MP 恢复（兜底，已由每旬检查补充）
        cultivationCore.recoverHpMpForAllDisciples(state, phasesToSettle = 3)
    }
    fun processYearlyEvents(year: Int) {
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
            if (year % 3 == 1) merchantAndRecruitService.refreshRecruitList(year)
        }
        safelyRun("merchantRefreshChance") {
            merchantAndRecruitService.giveMerchantRefreshChanceIfDue(year)
        }
        safelyRun("yearlyAging") {
            discipleLifecycleProcessor.processYearlyAging(year)
        }
        safelyRun("sectYearlyRecruitment") {
            caveExplorationProcessor.get().processSectDisciplesYearlyRecruitment(year)
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
        // 年度报告：年变快照
        safelyRun("annualReportSnapshot") {
            stateStore.update {
                val report = YearlyReport(
                    year = year - 1,
                    totalIncome = gameData.annualTotalIncome,
                    totalExpenditure = gameData.annualTotalExpenditure,
                    incomeBySource = gameData.annualIncomeBySource,
                    expenditureByReason = gameData.annualExpenditureByReason,
                    equipmentBySource = gameData.annualEquipmentBySource,
                    pillBySource = gameData.annualPillBySource,
                    herbBySource = gameData.annualHerbBySource,
                    alchemyCompleted = gameData.annualAlchemyCount,
                    forgeCompleted = gameData.annualForgeCount,
                    herbsHarvested = gameData.annualHerbCount,
                    newDisciples = gameData.annualNewDisciples,
                    deceasedDisciples = gameData.annualDeceasedDisciples,
                    desertedDisciples = gameData.annualDesertedDisciples
                )
                gameData = gameData.copy(
                    yearlyReports = (gameData.yearlyReports + report)
                        .takeLast(GameConfig.Logs.MAX_YEARLY_REPORTS),
                    annualIncomeBySource = emptyMap(),
                    annualExpenditureByReason = emptyMap(),
                    annualTotalIncome = 0L,
                    annualTotalExpenditure = 0L,
                    annualEquipmentBySource = emptyMap(),
                    annualPillBySource = emptyMap(),
                    annualHerbBySource = emptyMap(),
                    annualAlchemyCount = 0,
                    annualForgeCount = 0,
                    annualHerbCount = 0,
                    annualNewDisciples = 0,
                    annualDeceasedDisciples = 0,
                    annualDesertedDisciples = 0
                )
            }
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
    fun processLawEnforcementMonthly() {
        val data = stateStore.gameData.value
        val captureRate = calculateCaptureRate()
        val currentMonthValue = data.gameYear * 12 + data.gameMonth
        val tables = stateStore.discipleTables
        val threshold = GameConfig.LawEnforcementConfig.LOYALTY_THRESHOLD
        val protectionMonths = GameConfig.LawEnforcementConfig.NEW_DISCIPLE_PROTECTION_MONTHS

        val atRiskIds = findAtRiskDiscipleIds(currentMonthValue, threshold, protectionMonths, tables)

        for (id in atRiskIds) {
            val loyal = tables.loyalties.getOrDefault(id, 0)
            val desertionProb = calcDesertionProbability(
                threshold, loyal
            )
            if (rngManager.getRng(RngPartition.SYSTEM).nextDouble()
                >= desertionProb
            ) continue

            enforceDiscipleDesertion(
                id, data.gameYear, captureRate, threshold, tables
            )
        }
    }

    /** 筛选本月忠诚度低于阈值且已过保护期的弟子 */
    private fun findAtRiskDiscipleIds(
        currentMonthValue: Int,
        threshold: Int,
        protectionMonths: Int,
        tables: DiscipleTables
    ): List<Int> {
        return tables.ids.filter { id ->
            tables.isAlive.getOrDefault(id, 0) == 1 &&
                tables.statuses.getOrDefault(
                    id, DiscipleStatus.IDLE
                ) == DiscipleStatus.IDLE &&
                tables.loyalties.getOrDefault(id, 0) < threshold &&
                (currentMonthValue -
                    tables.recruitedMonths.getOrDefault(id, 0)) >=
                    protectionMonths
        }
    }

    /** 计算单个弟子的叛逃概率 */
    private fun calcDesertionProbability(
        threshold: Int, loyal: Int
    ): Double {
        return ((threshold - loyal) *
            GameConfig.LawEnforcementConfig.PROB_PER_POINT)
            .coerceIn(0.0, GameConfig.LawEnforcementConfig.MAX_PROB)
    }

    /** 执行叛逃结果：捕获→面壁 / 逃脱→清理装备+通知 */
    private fun enforceDiscipleDesertion(
        id: Int, currentYear: Int, captureRate: Double, threshold: Int, tables: DiscipleTables
    ) {
        if (rngManager.getRng(RngPartition.SYSTEM).nextDouble() < captureRate) {
            captureDiscipleForReflection(id, currentYear)
        } else {
            escapeDiscipleWithCleanup(id, threshold, tables)
        }
    }
    private fun enforceDiscipleDesertion(
        id: Int, currentYear: Int, captureRate: Double, threshold: Int, tables: DiscipleTables, state: MutableGameState
    ) {
        if (rngManager.getRng(RngPartition.SYSTEM).nextDouble() < captureRate) {
            captureDiscipleForReflection(id, currentYear, state)
        } else {
            escapeDiscipleWithCleanup(id, threshold, tables, state)
        }
    }

    /** 捕获叛逃弟子 → 面壁反省 */
    private fun captureDiscipleForReflection(
        id: Int, currentYear: Int
    ) {
        stateStore.update { captureDiscipleForReflection(id, currentYear, this) }
    }
    private fun captureDiscipleForReflection(
        id: Int, currentYear: Int, state: MutableGameState
    ) {
        val endYear = currentYear + GameConfig.LawEnforcementConfig.REFLECTION_YEARS
        val tables = state.discipleTables
        val d = tables.assemble(id) ?: return
        tables.remove(id)
        tables.insert(d.copy(
            status = DiscipleStatus.REFLECTING,
            statusData = d.statusData + mapOf(
                "reflectionStartYear" to currentYear.toString(),
                "reflectionEndYear" to endYear.toString()
            )
        ))
    }

    /** 叛逃弟子逃脱：删除装备/功法实例 + 清理槽位 + 通知 */
    private fun escapeDiscipleWithCleanup(
        id: Int, threshold: Int, tables: DiscipleTables
    ) {
        val preData = collectDesertionCleanupData(id, threshold, tables) ?: return
        discipleLifecycleProcessor.clearDiscipleFromAllSlots(id.toString())
        stateStore.update { applyDesertionCleanup(id, threshold, preData) }
    }
    private fun escapeDiscipleWithCleanup(
        id: Int, threshold: Int, tables: DiscipleTables, state: MutableGameState
    ) {
        val preData = collectDesertionCleanupData(id, threshold, tables) ?: return
        discipleLifecycleProcessor.clearDiscipleFromAllSlots(id.toString())
        state.applyDesertionCleanup(id, threshold, preData)
    }
    private data class DesertionPreData(
        val desertEquipIds: List<String>,
        val desertManualIds: Set<String>,
        val desertProfId: String,
        val snapshotName: String,
        val snapshotId: String
    )
    private fun collectDesertionCleanupData(id: Int, threshold: Int, tables: DiscipleTables): DesertionPreData? {
        if (tables.loyalties.getOrDefault(id, 0) >= threshold) return null
        val snapshot = tables.assemble(id) ?: return null
        val desertEquipIds = mutableListOf<String>()
        snapshot.equipment.weaponId?.let { desertEquipIds.add(it) }
        snapshot.equipment.armorId?.let { desertEquipIds.add(it) }
        snapshot.equipment.bootsId?.let { desertEquipIds.add(it) }
        snapshot.equipment.accessoryId?.let { desertEquipIds.add(it) }
        snapshot.equipment.storageBagItems
            .filter { it.itemType == ITEM_TYPE_EQUIPMENT_STACK || it.itemType == ITEM_TYPE_EQUIPMENT_INSTANCE }
            .map { it.itemId }.forEach { desertEquipIds.add(it) }
        val desertManualIds = snapshot.manualIds.toSet() +
            snapshot.equipment.storageBagItems
                .filter { it.itemType == ITEM_TYPE_MANUAL_STACK || it.itemType == ITEM_TYPE_MANUAL_INSTANCE }
                .map { it.itemId }
        return DesertionPreData(desertEquipIds, desertManualIds, id.toString(), snapshot.name, snapshot.id)
    }
    private fun MutableGameState.applyDesertionCleanup(id: Int, threshold: Int, preData: DesertionPreData) {
        if (discipleTables.loyalties.getOrDefault(id, 0) < threshold) {
            equipmentInstances = equipmentInstances.filter { it.id !in preData.desertEquipIds }
            manualInstances = manualInstances.filter { it.id !in preData.desertManualIds }
            val mutableProf = gameData.manualProficiencies.toMutableMap()
            mutableProf.remove(preData.desertProfId)
            gameData = gameData.copy(manualProficiencies = mutableProf)
            discipleTables.remove(id)
        }
        recordGameEvent(GameEventCategory.SECT, GameEventType.DESERTION,
            "${preData.snapshotName}叛逃脱离了宗门", preData.snapshotId, preData.snapshotName)
    }

    /**
     * 守卫对战判定 — 选择最近仓库的守卫与盗贼交战，返回是否被抓。
     *
     * @return true 表示盗贼被守卫抓获
     */
    private fun tryGuardCatch(
        disciple: Disciple,
        warehouses: List<GridBuildingData>,
        garrisons: List<WarehouseGarrisonSlot>,
        captureRate: Double
    ): Boolean {
        if (warehouses.isEmpty()) return rngManager.getRng(RngPartition.SYSTEM).nextDouble() < captureRate
        val warehouse = warehouses[rngManager.getRng(RngPartition.SYSTEM).nextInt(warehouses.size)]
        val garrison = garrisons.find { it.buildingInstanceId == warehouse.instanceId && it.isActive } ?: return false
        val guardDisciple = stateStore.disciples.value.find { it.id == garrison.discipleId } ?: return false
        val thiefStats = DiscipleStatCalculator.getBaseStats(disciple)
        val guardStats = DiscipleStatCalculator.getBaseStats(guardDisciple)
        val thiefPower = thiefStats.physicalAttack + thiefStats.magicAttack +
            thiefStats.physicalDefense + thiefStats.magicDefense + thiefStats.speed
        val guardPower = guardStats.physicalAttack + guardStats.magicAttack +
            guardStats.physicalDefense + guardStats.magicDefense + guardStats.speed
        val thiefWinProb = (thiefPower.toDouble() / (thiefPower + guardPower).coerceAtLeast(1))
            .coerceIn(0.1, 0.9)
        return rngManager.getRng(RngPartition.SYSTEM).nextDouble() >= thiefWinProb
    }

    /**
     * 执行偷窃：从宗门灵石中按比例盗取，存入弟子储物袋，更新最近偷窃月份。
     * @return 实际盗取的灵石数量（≤0 表示无可偷灵石）
     */
    private fun executeTheftStolen(
        disciple: Disciple, currentMonthValue: Int, tables: DiscipleTables
    ): Long {
        val currentData = stateStore.gameData.value
        val stolenAmount = calcTheftAmount(currentData)
        if (stolenAmount <= 0L) return 0L
        stateStore.update {
            gameData = gameData.copy(spiritStones = (gameData.spiritStones - stolenAmount).coerceAtLeast(0))
            tables.assembleAll().firstOrNull { it.id == disciple.id }?.let { d ->
                tables.update(d.copy(
                    equipment = d.equipment.copy(storageBagSpiritStones = d.equipment.storageBagSpiritStones + stolenAmount),
                    usage = d.usage.copy(lastTheftMonth = currentMonthValue)
                ))
            }
        }
        return stolenAmount
    }
    private fun executeTheftStolen(
        disciple: Disciple, currentMonthValue: Int, tables: DiscipleTables, state: MutableGameState
    ): Long {
        if (state.gameData.spiritStones <= 0) return 0L
        val stolenAmount = calcTheftAmount(state.gameData)
        if (stolenAmount <= 0L) return 0L
        state.gameData = state.gameData.copy(spiritStones = (state.gameData.spiritStones - stolenAmount).coerceAtLeast(0))
        tables.assembleAll().firstOrNull { it.id == disciple.id }?.let { d ->
            tables.update(d.copy(
                equipment = d.equipment.copy(storageBagSpiritStones = d.equipment.storageBagSpiritStones + stolenAmount),
                usage = d.usage.copy(lastTheftMonth = currentMonthValue)
            ))
        }
        return stolenAmount
    }
    private fun calcTheftAmount(data: GameData): Long {
        if (data.spiritStones <= 0) return 0L
        return (data.spiritStones * (
            GameConfig.LawEnforcementConfig.THEFT_MIN_RATIO +
            (GameConfig.LawEnforcementConfig.THEFT_MAX_RATIO - GameConfig.LawEnforcementConfig.THEFT_MIN_RATIO) *
            rngManager.getRng(RngPartition.SYSTEM).nextDouble()
        )).toLong().coerceAtLeast(1)
    }

    fun processTheftMonthly() {
        val currentData = stateStore.gameData.value
        if (currentData.spiritStones <= 0) return
        val captureRate = calculateCaptureRate()
        val currentMonthValue = currentData.gameYear * 12 + currentData.gameMonth
        val tables = stateStore.discipleTables
        val moralThreshold = GameConfig.LawEnforcementConfig.MORALITY_THRESHOLD
        val loyalThreshold = GameConfig.LawEnforcementConfig.LOYALTY_THRESHOLD
        val protectionMonths = GameConfig.LawEnforcementConfig.NEW_DISCIPLE_PROTECTION_MONTHS
        val atRiskIds = tables.ids.filter { id ->
            tables.isAlive.getOrDefault(id, 0) == 1 && tables.statuses.getOrDefault(id, DiscipleStatus.IDLE) == DiscipleStatus.IDLE &&
                tables.moralities.getOrDefault(id, 0) < moralThreshold && tables.loyalties.getOrDefault(id, 0) < loyalThreshold &&
                (currentMonthValue - tables.recruitedMonths.getOrDefault(id, 0)) >= protectionMonths &&
                (currentMonthValue - tables.lastTheftMonths.getOrDefault(id, 0)) >= 12
        }
        val warehouses = currentData.placedBuildings.filter { it.displayName == "仓库" }
        val garrisons = currentData.warehouseGarrisons
        val thiefIds = executeTheftLoop(atRiskIds, tables, captureRate, currentMonthValue, moralThreshold, loyalThreshold, warehouses, garrisons)
        processTheftDesertionCleanup(thiefIds, tables, loyalThreshold)
    }
    fun processTheftMonthly(state: MutableGameState) {
        val currentData = state.gameData
        if (currentData.spiritStones <= 0) return
        val captureRate = calculateCaptureRate()
        val currentMonthValue = currentData.gameYear * 12 + currentData.gameMonth
        val tables = state.discipleTables
        val moralThreshold = GameConfig.LawEnforcementConfig.MORALITY_THRESHOLD
        val loyalThreshold = GameConfig.LawEnforcementConfig.LOYALTY_THRESHOLD
        val protectionMonths = GameConfig.LawEnforcementConfig.NEW_DISCIPLE_PROTECTION_MONTHS
        val atRiskIds = tables.ids.filter { id ->
            tables.isAlive.getOrDefault(id, 0) == 1 && tables.statuses.getOrDefault(id, DiscipleStatus.IDLE) == DiscipleStatus.IDLE &&
                tables.moralities.getOrDefault(id, 0) < moralThreshold && tables.loyalties.getOrDefault(id, 0) < loyalThreshold &&
                (currentMonthValue - tables.recruitedMonths.getOrDefault(id, 0)) >= protectionMonths &&
                (currentMonthValue - tables.lastTheftMonths.getOrDefault(id, 0)) >= 12
        }
        val warehouses = currentData.placedBuildings.filter { it.displayName == "仓库" }
        val garrisons = currentData.warehouseGarrisons
        val thiefIds = executeTheftLoopState(atRiskIds, tables, captureRate, currentMonthValue, moralThreshold, loyalThreshold, warehouses, garrisons, state)
        processTheftDesertionCleanup(thiefIds, tables, loyalThreshold, state)
    }

    /** 从 atRiskIds 列表执行偷窃判定循环，返回偷窃后应叛逃的弟子 ID 集合 */
    private fun executeTheftLoop(
        atRiskIds: List<Int>, tables: DiscipleTables, captureRate: Double, currentMonthValue: Int,
        moralThreshold: Int, loyalThreshold: Int, warehouses: List<GridBuildingData>, garrisons: List<WarehouseGarrisonSlot>
    ): Set<Int> {
        return executeTheftLoopInternal(atRiskIds, tables, captureRate, currentMonthValue, moralThreshold, loyalThreshold, warehouses, garrisons,
            onCaught = { disciple, _, _ ->
                stateStore.update {
                    val tid = disciple.id.toIntOrNull() ?: return@update
                    if (discipleTables.ids.contains(tid) && discipleTables.isAlive[tid] == 1) {
                        discipleTables.statuses[tid] = DiscipleStatus.REFLECTING
                    }
                    recordGameEvent(GameEventCategory.SECT, GameEventType.THEFT_CAUGHT, "${disciple.name}偷盗被捕", disciple.id, disciple.name)
                }
            },
            onStolen = { disciple, amount ->
                stateStore.update { recordGameEvent(GameEventCategory.SECT, GameEventType.WAREHOUSE_THEFT, "宗门仓库被盗，损失了 $amount 灵石") }
            }
        )
    }
    private fun executeTheftLoopState(
        atRiskIds: List<Int>, tables: DiscipleTables, captureRate: Double, currentMonthValue: Int,
        moralThreshold: Int, loyalThreshold: Int, warehouses: List<GridBuildingData>, garrisons: List<WarehouseGarrisonSlot>,
        state: MutableGameState
    ): Set<Int> {
        return executeTheftLoopInternal(atRiskIds, tables, captureRate, currentMonthValue, moralThreshold, loyalThreshold, warehouses, garrisons,
            onCaught = { disciple, _, _ ->
                val tid = disciple.id.toIntOrNull() ?: return@executeTheftLoopInternal
                if (state.discipleTables.ids.contains(tid) && state.discipleTables.isAlive[tid] == 1) {
                    state.discipleTables.statuses[tid] = DiscipleStatus.REFLECTING
                }
                state.recordGameEvent(GameEventCategory.SECT, GameEventType.THEFT_CAUGHT, "${disciple.name}偷盗被捕", disciple.id, disciple.name)
            },
            onStolen = { _, amount ->
                state.recordGameEvent(GameEventCategory.SECT, GameEventType.WAREHOUSE_THEFT, "宗门仓库被盗，损失了 $amount 灵石")
            }
        )
    }
    private fun executeTheftLoopInternal(
        atRiskIds: List<Int>, tables: DiscipleTables, captureRate: Double, currentMonthValue: Int,
        moralThreshold: Int, loyalThreshold: Int, warehouses: List<GridBuildingData>, garrisons: List<WarehouseGarrisonSlot>,
        onCaught: (Disciple, Int, Int) -> Unit, onStolen: (Disciple, Long) -> Unit
    ): Set<Int> {
        val thiefIds = mutableSetOf<Int>()
        for (id in atRiskIds) {
            val disciple = tables.assemble(id) ?: continue
            val stats = DiscipleStatCalculator.getBaseStats(disciple)
            val theftProb = ((moralThreshold - stats.morality) * GameConfig.LawEnforcementConfig.PROB_PER_POINT).coerceIn(0.0, GameConfig.LawEnforcementConfig.MAX_PROB)
            if (rngManager.getRng(RngPartition.SYSTEM).nextDouble() >= theftProb) continue
            if (tryGuardCatch(disciple, warehouses, garrisons, captureRate)) {
                onCaught(disciple, currentMonthValue, captureRate.toInt())
            } else {
                val stolenAmount = executeTheftStolen(disciple, currentMonthValue, tables)
                if (stolenAmount <= 0L) break
                if (rngManager.getRng(RngPartition.SYSTEM).nextDouble() < ((loyalThreshold - stats.loyalty) * GameConfig.LawEnforcementConfig.PROB_PER_POINT).coerceIn(0.0, GameConfig.LawEnforcementConfig.MAX_PROB)) {
                    thiefIds.add(id)
                }
                onStolen(disciple, stolenAmount)
            }
        }
        return thiefIds
    }
    /**
     * 偷盗后叛逃清理：通知 → 清除槽位 → 事务内移除弟子+清理装备/功法。
     */
    private fun processTheftDesertionCleanup(thiefIds: Set<Int>, tables: DiscipleTables, loyalThreshold: Int) {
        clearThiefSlots(thiefIds)
        val cleanupData = collectTheftDesertionData(thiefIds, tables, loyalThreshold) ?: return
        stateStore.update { applyTheftDesertionUpdate(thiefIds, loyalThreshold, cleanupData) }
    }
    private fun processTheftDesertionCleanup(thiefIds: Set<Int>, tables: DiscipleTables, loyalThreshold: Int, state: MutableGameState) {
        clearThiefSlots(thiefIds)
        val cleanupData = collectTheftDesertionData(thiefIds, tables, loyalThreshold) ?: return
        state.applyTheftDesertionUpdate(thiefIds, loyalThreshold, cleanupData)
    }
    private fun clearThiefSlots(thiefIds: Set<Int>) {
        for (thiefId in thiefIds) {
            discipleLifecycleProcessor.clearDiscipleFromAllSlots(thiefId.toString())
        }
    }
        private fun collectTheftDesertionData(thiefIds: Set<Int>, tables: DiscipleTables, loyalThreshold: Int): Map<Int, Triple<List<String>, Set<String>, String>>? {
        if (thiefIds.isEmpty()) return null
        val result = mutableMapOf<Int, Triple<List<String>, Set<String>, String>>()
        for (thiefId in thiefIds) {
            if (tables.loyalties.getOrDefault(thiefId, 0) >= loyalThreshold) continue
            val snapshot = tables.assemble(thiefId) ?: continue
            val equipIds = mutableListOf<String>()
            snapshot.equipment.weaponId?.let { equipIds.add(it) }
            snapshot.equipment.armorId?.let { equipIds.add(it) }
            snapshot.equipment.bootsId?.let { equipIds.add(it) }
            snapshot.equipment.accessoryId?.let { equipIds.add(it) }
            snapshot.equipment.storageBagItems.filter { it.itemType == ITEM_TYPE_EQUIPMENT_STACK || it.itemType == ITEM_TYPE_EQUIPMENT_INSTANCE }.map { it.itemId }.forEach { equipIds.add(it) }
            val manualIds = snapshot.manualIds.toSet() + snapshot.equipment.storageBagItems.filter { it.itemType == ITEM_TYPE_MANUAL_STACK || it.itemType == ITEM_TYPE_MANUAL_INSTANCE }.map { it.itemId }
            result[thiefId] = Triple(equipIds, manualIds, snapshot.name)
        }
        return result
    }
    private fun MutableGameState.applyTheftDesertionUpdate(thiefIds: Set<Int>, loyalThreshold: Int, cleanupData: Map<Int, Triple<List<String>, Set<String>, String>>) {
        for (thiefId in thiefIds) {
            if (discipleTables.loyalties.getOrDefault(thiefId, 0) >= loyalThreshold) continue
            val (equipIds, manualIds, thiefName) = cleanupData[thiefId] ?: continue
            equipmentInstances = equipmentInstances.filter { it.id !in equipIds }
            manualInstances = manualInstances.filter { it.id !in manualIds }
            val mutableProf = gameData.manualProficiencies.toMutableMap()
            mutableProf.remove(thiefId.toString())
            gameData = gameData.copy(manualProficiencies = mutableProf)
            discipleTables.remove(thiefId)
            recordGameEvent(GameEventCategory.SECT, GameEventType.THEFT_DESERTION, "${thiefName}偷盗后叛逃", thiefId.toString(), thiefName)
            gameData = gameData.copy(annualDesertedDisciples = gameData.annualDesertedDisciples + 1)
        }
    }
    fun processLawEnforcementMonthly(state: MutableGameState) {
        val data = state.gameData
        val captureRate = calculateCaptureRate()
        val currentMonthValue = data.gameYear * 12 + data.gameMonth
        val tables = state.discipleTables
        val threshold = GameConfig.LawEnforcementConfig.LOYALTY_THRESHOLD
        val protectionMonths = GameConfig.LawEnforcementConfig.NEW_DISCIPLE_PROTECTION_MONTHS

        val atRiskIds = findAtRiskDiscipleIds(currentMonthValue, threshold, protectionMonths, tables)
        for (id in atRiskIds) {
            val loyal = tables.loyalties.getOrDefault(id, 0)
            val desertionProb = calcDesertionProbability(threshold, loyal)
            if (rngManager.getRng(RngPartition.SYSTEM).nextDouble() >= desertionProb) continue
            enforceDiscipleDesertion(id, data.gameYear, captureRate, threshold, tables, state)
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
            stateStore.update {
                discipleTables.replaceAll(disciples)
                // 为阵亡弟子补充 deathYears（replaceAll 已清空，单独恢复）
                val battleYear = stateStore.gameData.value.gameYear
                disciples.filter { !it.isAlive }.forEach {
                    val idInt = it.id.toIntOrNull()
                    if (idInt != null) discipleTables.deathYears[idInt] = battleYear
                }
            }
        }
    }
    fun completeExploration(team: ExplorationTeam, success: Boolean, survivorIds: List<String>, survivorHpMap: Map<String, Int> = emptyMap(), survivorMpMap: Map<String, Int> = emptyMap()) {
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
            discipleTables.replaceAll(currentDisciplesList)
            // handleDiscipleDeath 已设置 deathYears 但被 replaceAll 清空，单独恢复
            val explorationYear = stateStore.gameData.value.gameYear
            currentDisciplesList.filter { !it.isAlive }.forEach {
                val idInt = it.id.toIntOrNull()
                if (idInt != null && !discipleTables.deathYears.contains(idInt)) {
                    discipleTables.deathYears[idInt] = explorationYear
                }
            }
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
    fun processScoutInfoExpiryLazy(year: Int, month: Int, state: MutableGameState) {
        val data = state.gameData
        val hasExpired = data.scoutInfo.any { (_, info) ->
            year > info.expiryYear || (year == info.expiryYear && month > info.expiryMonth)
        }
        if (!hasExpired) return
        processScoutInfoExpiry(year, month, state)
    }
    fun processScoutInfoExpiry(year: Int, month: Int) {
        val data = stateStore.gameData.value
        var hasExpired = false
        val updatedScoutInfo = data.scoutInfo.filter { (_, info) ->
            val isExpired = year > info.expiryYear ||
                (year == info.expiryYear && month > info.expiryMonth)
            if (isExpired) hasExpired = true
            !isExpired
        }
        if (hasExpired) {
            stateStore.update { applyScoutInfoExpiry(this, year, month) }
        }
    }
    fun processScoutInfoExpiry(year: Int, month: Int, state: MutableGameState) {
        applyScoutInfoExpiry(state, year, month)
    }
    private fun applyScoutInfoExpiry(state: MutableGameState, year: Int, month: Int) {
        val data = state.gameData
        val updatedScoutInfo = data.scoutInfo.filter { (_, info) ->
            !(year > info.expiryYear || (year == info.expiryYear && month > info.expiryMonth))
        }
        val hasExpired = updatedScoutInfo.size < data.scoutInfo.size
        if (!hasExpired) return
        val updatedWorldMapSects = data.worldMapSects.map { sect ->
            val sectScoutInfo = updatedScoutInfo[sect.id]
            if (sectScoutInfo == null && data.sectDetails[sect.id]?.scoutInfo?.sectId?.isNotEmpty() == true) {
                sect.copy(isKnown = false)
            } else sect
        }
        val updatedDetails = data.sectDetails.toMutableMap()
        updatedScoutInfo.forEach { (sectId, _) ->
            updatedDetails[sectId] = (updatedDetails[sectId] ?: SectDetail(sectId = sectId)).copy(scoutInfo = updatedScoutInfo[sectId] ?: SectScoutInfo())
        }
        data.sectDetails.forEach { (sectId, detail) ->
            if (updatedScoutInfo[sectId] == null && detail.scoutInfo.sectId.isNotEmpty()) {
                updatedDetails[sectId] = detail.copy(scoutInfo = SectScoutInfo())
            }
        }
        state.gameData = state.gameData.copy(scoutInfo = updatedScoutInfo, worldMapSects = updatedWorldMapSects, sectDetails = updatedDetails)
    }
    fun processTheftIfNeeded() {
        if (stateStore.gameData.value.spiritStones <= 0) return
        val tables = stateStore.discipleTables
        val moralThreshold = GameConfig.LawEnforcementConfig.MORALITY_THRESHOLD
        val loyalThreshold = GameConfig.LawEnforcementConfig.LOYALTY_THRESHOLD
        val hasLowMoralityDisciple = tables.ids.any { id ->
            tables.isAlive.getOrDefault(id, 0) == 1 &&
            tables.statuses.getOrDefault(id, DiscipleStatus.IDLE) == DiscipleStatus.IDLE &&
            tables.moralities.getOrDefault(id, 0) < moralThreshold &&
            tables.loyalties.getOrDefault(id, 0) < loyalThreshold
        }
        if (!hasLowMoralityDisciple) return
        processTheftMonthly()
    }
    fun processTheftIfNeeded(state: MutableGameState) {
        if (state.gameData.spiritStones <= 0) return
        val tables = state.discipleTables
        val moralThreshold = GameConfig.LawEnforcementConfig.MORALITY_THRESHOLD
        val loyalThreshold = GameConfig.LawEnforcementConfig.LOYALTY_THRESHOLD
        val hasLowMoralityDisciple = tables.ids.any { id ->
            tables.isAlive.getOrDefault(id, 0) == 1 &&
            tables.statuses.getOrDefault(id, DiscipleStatus.IDLE) == DiscipleStatus.IDLE &&
            tables.moralities.getOrDefault(id, 0) < moralThreshold &&
            tables.loyalties.getOrDefault(id, 0) < loyalThreshold
        }
        if (!hasLowMoralityDisciple) return
        processTheftMonthly(state)
    }
    // ── 任务 ──────────────────────────────────────────────────────────
    fun processCompletedMissionsLazy(year: Int, month: Int) {
        val data = stateStore.gameData.value
        val currentAbsoluteMonth = com.xianxia.sect.core.engine.LazyEvaluationDispatcher.toAbsoluteMonth(year, month)
        val remainingActive = mutableListOf<ActiveMission>()

        // ── Phase 1: 事务外计算 + IO 操作（状态变更收集，不执行 stateStore.update） ──
        data class MissionReward(
            val missionId: String,
            val spiritStones: Int,
            val survivors: Set<String>,
            val discipleIds: List<String>
        )
        val rewards = mutableListOf<MissionReward>()

        for (activeMission in data.activeMissions) {
            val missionCompletionMonth = com.xianxia.sect.core.engine.LazyEvaluationDispatcher.toAbsoluteMonth(
                activeMission.startYear, activeMission.startMonth
            ) + activeMission.duration
            if (currentAbsoluteMonth < missionCompletionMonth) {
                remainingActive.add(activeMission); continue
            }
            if (!activeMission.isComplete(year, month)) {
                remainingActive.add(activeMission); continue
            }
            val reward = runCatching {
                val aliveDisciples = activeMission.discipleIds.mapNotNull { did ->
                    stateStore.disciples.value.find { it.id == did && it.isAlive }
                }
                if (aliveDisciples.isEmpty()) return@runCatching null
                val equipMap = stateStore.equipmentInstances.value.associateBy { it.id }
                val manualMap = stateStore.manualInstances.value.associateBy { it.id }
                val proficiencies = stateStore.gameData.value.manualProficiencies.mapValues { (_, list) ->
                    list.associateBy { it.manualId }
                }
                val result = MissionSystem.processMissionCompletion(
                    activeMission, aliveDisciples, equipMap, manualMap, proficiencies, battleSystem
                )
                // IO 操作留在 Phase 1（事务外允许）
                result.materials.forEach { material ->
                    val r = inventorySystem.addMaterial(material)
                    when (r) {
                        is DomainResult.Success -> {}
                        is DomainResult.Partial -> DomainLog.w(TAG, "${material.name} 溢出 ${r.overflow} 个")
                        is DomainResult.Failure -> DomainLog.w(TAG, "添加 ${material.name} 失败: ${r.error}")
                    }
                }
                inventorySystem.withTrackingSource("trial") {
                    result.pills.forEach { pill ->
                        val r = inventorySystem.addPill(pill)
                        when (r) {
                            is DomainResult.Success -> {}
                            is DomainResult.Partial -> DomainLog.w(TAG, "${pill.name} 溢出 ${r.overflow} 个")
                            is DomainResult.Failure -> DomainLog.w(TAG, "添加 ${pill.name} 失败: ${r.error}")
                        }
                    }
                    result.equipmentStacks.forEach { equip ->
                        val r = inventorySystem.addEquipmentStack(equip)
                        when (r) {
                            is DomainResult.Success -> {}
                            is DomainResult.Partial -> DomainLog.w(TAG, "${equip.name} 溢出 ${r.overflow} 个")
                            is DomainResult.Failure -> DomainLog.w(TAG, "添加 ${equip.name} 失败: ${r.error}")
                        }
                    }
                }
                result.manualStacks.forEach { manual ->
                    val r = inventorySystem.addManualStack(manual)
                    when (r) {
                        is DomainResult.Success -> {}
                        is DomainResult.Partial -> DomainLog.w(TAG, "${manual.name} 溢出 ${r.overflow} 个")
                        is DomainResult.Failure -> DomainLog.w(TAG, "添加 ${manual.name} 失败: ${r.error}")
                    }
                }
                val survivors = if (result.combatTriggered && result.victory && result.battleResult != null) {
                    result.battleResult.log.teamMembers.filter { it.isAlive }.map { it.id }.toSet()
                } else emptySet()
                MissionReward(activeMission.id, result.spiritStones, survivors, activeMission.discipleIds)
            }
            if (reward.isSuccess && reward.getOrNull() != null) {
                reward.getOrNull()?.let { rewards.add(it) }
            } else {
                remainingActive.add(activeMission) // 失败的任务保留到下次
            }
        }

        if (rewards.isEmpty()) {
            if (remainingActive.size < data.activeMissions.size) {
                stateStore.update { gameData = gameData.copy(activeMissions = remainingActive) }
            }
            return
        }

        // ── Phase 2: 单事务写入状态 ──
        stateStore.update {
            for (reward in rewards) {
                if (reward.spiritStones > 0) {
                    spiritStoneWallet.add(this, reward.spiritStones.toLong(), SpiritStoneGrade.LOW, SpiritStoneSource.Quest)
                }
                for (did in reward.discipleIds) {
                    val tid = did.toIntOrNull() ?: continue
                    val tableIds = discipleTables.ids
                    if (tid < 0 || tid >= tableIds.size || discipleTables.isAlive[tid] != 1) continue
                    if (did in reward.survivors) {
                        discipleTables.soulPowers[tid] = discipleTables.soulPowers.getOrDefault(tid, 0) + 1
                    }
                    discipleTables.statuses[tid] = DiscipleStatus.IDLE
                }
            }
            gameData = gameData.copy(activeMissions = remainingActive)
        }
    }
    fun processMissionRefreshIfDue(month: Int) {
        if (month % MissionSystem.REFRESH_INTERVAL_MONTHS != 0) return
        processMissionRefresh()
    }
    fun processMissionRefreshIfDue(month: Int, state: MutableGameState) {
        if (month % MissionSystem.REFRESH_INTERVAL_MONTHS != 0) return
        processMissionRefresh(state)
    }
    fun processMissionRefresh() {
        stateStore.update { processMissionRefresh(this) }
    }
    fun processMissionRefresh(state: MutableGameState) {
        val data = state.gameData
        val result = MissionSystem.processMonthlyRefresh(
            data.availableMissions,
            data.gameYear,
            data.gameMonth
        )
        state.gameData = state.gameData.copy(availableMissions = result.cleanedMissions)
    }
    // ── 游戏结束 ──────────────────────────────────────────────────────
    fun checkGameOverCondition() {
        stateStore.update { checkGameOverCondition(this) }
    }
    fun checkGameOverCondition(state: MutableGameState) {
        val currentData = state.gameData
        if (currentData.isGameOver) return
        val playerSect = currentData.worldMapSects.find { it.isPlayerSect } ?: return
        val playerSectId = playerSect.id
        val playerControlsAnySect = currentData.worldMapSects.any { sect ->
            (sect.isPlayerSect && sect.occupierSectId.isEmpty()) ||
            (sect.occupierSectId == playerSectId && !sect.isPlayerSect)
        }
        if (!playerControlsAnySect) {
            state.gameData = state.gameData.copy(isGameOver = true)
        }
    }
    // ── 辅助方法 ──────────────────────────────────────────────────────
    fun clearDiscipleFromAllSlots(discipleId: String) {
        discipleLifecycleProcessor.clearDiscipleFromAllSlots(discipleId)
    }
    fun handleDiscipleDeath(disciple: Disciple, isOutsideSect: Boolean = false) {
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
