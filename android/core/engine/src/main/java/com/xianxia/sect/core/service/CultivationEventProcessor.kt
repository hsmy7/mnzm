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
    fun processMonthlyEvents(year: Int, month: Int) {
        safelyRun("aiSectOperations") {
            caveExplorationProcessor.get().processAISectOperations(year, month)
        }
        safelyRun("gameOverCheck") { checkGameOverCondition() }
        safelyRun("scoutExpiry") { processScoutInfoExpiryLazy(year, month) }
        safelyRun("aiBeastAttacks") {
            stateStore.update { aiSectBeastAttackProcessor.processMonthly(this, year, month) }
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
        // 附属宗门月度脱离检查
        safelyRun("vassalBreakaway") { vassalService.processMonthlyBreakawayCheck() }
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
        stateStore.update {
            val data = gameData
            val tables = discipleTables
            val aliveIds = tables.ids.filter { tables.isAlive[it] == 1 }
            if (aliveIds.isEmpty()) return@update
            // HP/MP 恢复（兜底，已由每旬检查补充）
            cultivationCore.recoverHpMpForAllDisciples(this, phasesToSettle = 3)
        }
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
        val threshold =
            GameConfig.LawEnforcementConfig.LOYALTY_THRESHOLD
        val protectionMonths =
            GameConfig.LawEnforcementConfig.NEW_DISCIPLE_PROTECTION_MONTHS

        // 直接用 discipleTables 读实时数据，避免 StateFlow 快照滞后
        val atRiskIds = findAtRiskDiscipleIds(
            currentMonthValue, threshold, protectionMonths, tables
        )

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
        id: Int,
        currentYear: Int,
        captureRate: Double,
        threshold: Int,
        tables: DiscipleTables
    ) {
        if (rngManager.getRng(RngPartition.SYSTEM).nextDouble()
            < captureRate
        ) {
            captureDiscipleForReflection(id, currentYear)
        } else {
            escapeDiscipleWithCleanup(id, threshold, tables)
        }
    }

    /** 捕获叛逃弟子 → 面壁反省 */
    private fun captureDiscipleForReflection(
        id: Int, currentYear: Int
    ) {
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
    }

    /** 叛逃弟子逃脱：删除装备/功法实例 + 清理槽位 + 通知 */
    private fun escapeDiscipleWithCleanup(
        id: Int, threshold: Int, tables: DiscipleTables
    ) {
        // 防御性二次校验：确认忠诚度仍低于阈值
        if (tables.loyalties.getOrDefault(id, 0) >= threshold) return
        val snapshot = tables.assemble(id) ?: return
        val desertEquipIds = mutableListOf<String>()
        snapshot.equipment.weaponId?.let { desertEquipIds.add(it) }
        snapshot.equipment.armorId?.let { desertEquipIds.add(it) }
        snapshot.equipment.bootsId?.let { desertEquipIds.add(it) }
        snapshot.equipment.accessoryId?.let { desertEquipIds.add(it) }
        snapshot.equipment.storageBagItems
            .filter { it.itemType == ITEM_TYPE_EQUIPMENT_STACK || it.itemType == ITEM_TYPE_EQUIPMENT_INSTANCE }
            .map { it.itemId }
            .forEach { desertEquipIds.add(it) }
        val desertManualIds = snapshot.manualIds.toSet() +
            snapshot.equipment.storageBagItems
                .filter { it.itemType == ITEM_TYPE_MANUAL_STACK || it.itemType == ITEM_TYPE_MANUAL_INSTANCE }
                .map { it.itemId }
        val desertProfId = id.toString()
        discipleLifecycleProcessor.clearDiscipleFromAllSlots(
            id.toString()
        )
        stateStore.update {
            // 二次校验在事务内重做，防悬停点间被修改
            if (discipleTables.loyalties.getOrDefault(id, 0)
                < threshold
            ) {
                // 直接删除叛逃弟子的装备/功法实例
                equipmentInstances = equipmentInstances.filter {
                    it.id !in desertEquipIds
                }
                manualInstances = manualInstances.filter {
                    it.id !in desertManualIds
                }
                val mutableProf =
                    gameData.manualProficiencies.toMutableMap()
                mutableProf.remove(desertProfId)
                gameData = gameData.copy(
                    manualProficiencies = mutableProf
                )
                discipleTables.remove(id)
            }
            // 事件记录与删除在同一事务内原子完成
            recordGameEvent(
                GameEventCategory.SECT, GameEventType.DESERTION,
                "${snapshot.name}叛逃脱离了宗门", snapshot.id, snapshot.name
            )
        }
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
        disciple: Disciple,
        currentMonthValue: Int,
        tables: DiscipleTables
    ): Long {
        val currentData = stateStore.gameData.value
        if (currentData.spiritStones <= 0) return 0L
        val stolenAmount = (currentData.spiritStones * (
            GameConfig.LawEnforcementConfig.THEFT_MIN_RATIO +
            (GameConfig.LawEnforcementConfig.THEFT_MAX_RATIO - GameConfig.LawEnforcementConfig.THEFT_MIN_RATIO) *
            rngManager.getRng(RngPartition.SYSTEM).nextDouble()
        )).toLong().coerceAtLeast(1)
        stateStore.update {
            gameData = gameData.copy(spiritStones = (gameData.spiritStones - stolenAmount).coerceAtLeast(0))
            discipleTables.assembleAll().firstOrNull { it.id == disciple.id }?.let { d ->
                discipleTables.update(d.copy(
                    equipment = d.equipment.copy(storageBagSpiritStones = d.equipment.storageBagSpiritStones + stolenAmount),
                    usage = d.usage.copy(lastTheftMonth = currentMonthValue)
                ))
            }
        }
        return stolenAmount
    }

    fun processTheftMonthly() {
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
            if (rngManager.getRng(RngPartition.SYSTEM).nextDouble() < theftProb) {
                val caught = tryGuardCatch(disciple, warehouses, garrisons, captureRate)
                if (caught) {
                    stateStore.update {
                        val id = disciple.id.toIntOrNull() ?: return@update
                        if (discipleTables.ids.contains(id) && discipleTables.isAlive[id] == 1) {
                            discipleTables.statuses[id] = DiscipleStatus.REFLECTING
                            val existingData = discipleTables.statusData[id]
                            discipleTables.statusData[id] = existingData + mapOf(
                                "reflectionStartYear" to currentData.gameYear.toString(),
                                "reflectionEndYear" to (currentData.gameYear + GameConfig.LawEnforcementConfig.REFLECTION_YEARS).toString()
                            )
                        }
                        recordGameEvent(GameEventCategory.SECT, GameEventType.THEFT_CAUGHT, "${disciple.name}偷盗被捕", disciple.id, disciple.name)
                    }
                } else {
                    val stolenAmount = executeTheftStolen(
                        disciple, currentMonthValue, tables
                    )
                    if (stolenAmount <= 0L) break
                    val loyalty = stats.loyalty
                    val desertionProb =
                        ((loyalThreshold - loyalty) *
                            GameConfig.LawEnforcementConfig.PROB_PER_POINT)
                            .coerceIn(0.0, GameConfig.LawEnforcementConfig.MAX_PROB)
                    if (rngManager.getRng(RngPartition.SYSTEM).nextDouble() < desertionProb) {
                        thiefIds.add(id)
                    }
                    stateStore.update {
                        recordGameEvent(GameEventCategory.SECT, GameEventType.WAREHOUSE_THEFT, "宗门仓库被盗，损失了 $stolenAmount 灵石")
                    }
                }
            }
        }
        processTheftDesertionCleanup(thiefIds, tables, loyalThreshold)
    }

    /**
     * 偷盗后叛逃清理：通知 → 清除槽位 → 事务内移除弟子+清理装备/功法。
     */
    private fun processTheftDesertionCleanup(
        thiefIds: Set<Int>,
        tables: DiscipleTables,
        loyalThreshold: Int
    ) {
        if (thiefIds.isEmpty()) return
        val theftDesertCleanup = mutableMapOf<Int, Triple<List<String>, Set<String>, String>>()
        for (thiefId in thiefIds) {
            val currentLoyal = tables.loyalties.getOrDefault(thiefId, 0)
            if (currentLoyal >= loyalThreshold) continue
            val snapshot = tables.assemble(thiefId) ?: continue
            val equipIds = mutableListOf<String>()
            snapshot.equipment.weaponId?.let { equipIds.add(it) }
            snapshot.equipment.armorId?.let { equipIds.add(it) }
            snapshot.equipment.bootsId?.let { equipIds.add(it) }
            snapshot.equipment.accessoryId?.let { equipIds.add(it) }
            snapshot.equipment.storageBagItems
                .filter { it.itemType == ITEM_TYPE_EQUIPMENT_STACK || it.itemType == ITEM_TYPE_EQUIPMENT_INSTANCE }
                .map { it.itemId }
                .forEach { equipIds.add(it) }
            val manualIds = snapshot.manualIds.toSet() +
                snapshot.equipment.storageBagItems
                    .filter { it.itemType == ITEM_TYPE_MANUAL_STACK || it.itemType == ITEM_TYPE_MANUAL_INSTANCE }
                    .map { it.itemId }
            theftDesertCleanup[thiefId] = Triple(equipIds, manualIds, snapshot.name)
            discipleLifecycleProcessor.clearDiscipleFromAllSlots(thiefId.toString())
        }
        stateStore.update {
            for (thiefId in thiefIds) {
                if (discipleTables.loyalties.getOrDefault(thiefId, 0) >= loyalThreshold) continue
                val (equipIds, manualIds, thiefName) = theftDesertCleanup[thiefId] ?: continue
                equipmentInstances = equipmentInstances.filter { it.id !in equipIds }
                manualInstances = manualInstances.filter { it.id !in manualIds }
                val mutableProf = gameData.manualProficiencies.toMutableMap()
                mutableProf.remove(thiefId.toString())
                gameData = gameData.copy(manualProficiencies = mutableProf)
                discipleTables.remove(thiefId)
                recordGameEvent(GameEventCategory.SECT, GameEventType.THEFT_DESERTION, "${thiefName}偷盗后叛逃", thiefId.toString(), thiefName)
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
            stateStore.update {
                gameData = gameData.copy(
                    scoutInfo = updatedScoutInfo,
                    worldMapSects = updatedWorldMapSects,
                    sectDetails = updatedDetails
                )
            }
        }
    }
    fun processTheftIfNeeded() {
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
            if (!activeMission.isComplete(year, month)) {
                remainingActive.add(activeMission)
                continue
            }
            var missionRewarded = false
            var missionError: Throwable? = null
            runCatching {
                val aliveDisciples = activeMission.discipleIds.mapNotNull { did ->
                    stateStore.disciples.value.find { it.id == did && it.isAlive }
                }
                if (aliveDisciples.isEmpty()) return@runCatching
                val equipMap = stateStore.equipmentInstances.value.associateBy { it.id }
                val manualMap = stateStore.manualInstances.value.associateBy { it.id }
                val proficiencies = stateStore.gameData.value.manualProficiencies.mapValues { (_, list) ->
                    list.associateBy { it.manualId }
                }
                val result = MissionSystem.processMissionCompletion(
                    activeMission, aliveDisciples, equipMap, manualMap, proficiencies, battleSystem
                )
                // 单事务：灵石 + 灵魂点 + 弟子状态重置，原子提交
                stateStore.update {
                    if (result.spiritStones > 0) {
                        spiritStoneWallet.add(this,
                            result.spiritStones.toLong(),
                            SpiritStoneGrade.LOW,
                            SpiritStoneSource.Quest
                        )
                    }
                    val survivors = if (result.combatTriggered && result.victory && result.battleResult != null) {
                        result.battleResult.log.teamMembers.filter { it.isAlive }.map { it.id }.toSet()
                    } else emptySet()
                    for (did in activeMission.discipleIds) {
                        val tid = did.toIntOrNull() ?: continue
                        if (!discipleTables.ids.contains(tid) || discipleTables.isAlive[tid] != 1) continue
                        if (tid.toString() in survivors) {
                            discipleTables.soulPowers[tid] = discipleTables.soulPowers.getOrDefault(tid, 0) + 1
                        }
                        discipleTables.statuses[tid] = DiscipleStatus.IDLE
                    }
                }
                result.materials.forEach { material -> inventorySystem.addMaterial(material) }
                result.pills.forEach { pill -> inventorySystem.addPill(pill) }
                result.equipmentStacks.forEach { equip -> inventorySystem.addEquipmentStack(equip) }
                result.manualStacks.forEach { manual -> inventorySystem.addManualStack(manual) }
                missionRewarded = true
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) { missionError = e; return@onFailure }
                DomainLog.w(TAG, "processCompletedMissionsLazy: mission ${activeMission.id} failed", e)
            }
            if (missionError != null) {
                remainingActive.add(activeMission)  // 保留任务到下次循环，而非丢失
                throw missionError!!
            }
            if (missionRewarded) {
                completedIds.add(activeMission.id)
            } else {
                remainingActive.add(activeMission)  // 奖励失败，保留下次重试
            }
        }
        if (completedIds.isNotEmpty()) {
            stateStore.update { gameData = gameData.copy(activeMissions = remainingActive) }
        }
    }
    fun processMissionRefreshIfDue(month: Int) {
        if (month % MissionSystem.REFRESH_INTERVAL_MONTHS != 0) return
        processMissionRefresh()
    }
    fun processMissionRefresh() {
        val data = stateStore.gameData.value
        val result = MissionSystem.processMonthlyRefresh(
            data.availableMissions,
            data.gameYear,
            data.gameMonth
        )
        stateStore.update { gameData = gameData.copy(availableMissions = result.cleanedMissions) }
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
            stateStore.update { this.gameData = this.gameData.copy(isGameOver = true) }
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
