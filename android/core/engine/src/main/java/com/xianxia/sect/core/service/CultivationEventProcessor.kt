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
    private val recruitService: RecruitService,
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
    private val lawEnforcementProcessor: LawEnforcementProcessor,
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
        if (state != null) {
            state.gameData = updatedData
            // 新月份开始时重置招募月度计数，使年变/月变中的招募共享同一月配额
            state.gameData = state.gameData.copy(recruitCountThisMonth = 0)
        } else {
            stateStore.update {
                gameData = updatedData.copy(recruitCountThisMonth = 0)
            }
        }
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
        val resetData = updatedData.copy(recruitCountThisMonth = 0)
        if (state != null) state.gameData = resetData else stateStore.update { gameData = resetData }
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

    /**
     * 带状态版本的月度事件处理 — 在已存在的事务内使用。
     * 与 [processMonthlyEvents] 功能相同，但操作在传入的 state 上，
     * 而非打开新的 [stateStore.update]。
     */
    fun processMonthlyEvents(year: Int, month: Int, state: MutableGameState) {
        state.gameData = state.gameData.copy(recruitCountThisMonth = 0)
        state.safelyRunInState("autoRecruit") {
            RecruitService.processAutoRecruit(state)
        }
        state.safelyRunInState("theft") { lawEnforcementProcessor.processTheftIfNeeded() }
        state.safelyRunInState("lawEnforcement") { lawEnforcementProcessor.processLawEnforcementMonthly() }
        state.safelyRunInState("completedMissions") { processCompletedMissionsLazy(year, month) }
        state.safelyRunInState("aiSectOperations") { caveExplorationProcessor.get().processAISectOperations(year, month, state) }
        state.safelyRunInState("gameOverCheck") { checkGameOverCondition(state) }
        state.safelyRunInState("scoutExpiry") { processScoutInfoExpiryLazy(year, month, state) }
        state.safelyRunInState("aiBeastAttacksRemaining") { aiSectBeastAttackProcessor.processRemainingTargets(state) }
        if (month == 12) {
            state.safelyRunInState("autoBuy") { autoBuyService.executeAutoBuy(year, month, state) }
        }
        state.safelyRunInState("spiritMineProduction") { cultivationSettlement.processSpiritMineProductionMonthly(state) }
        state.safelyRunInState("disciplePurchase") { disciplePurchaseService.executePurchase(year, month, state) }
        state.safelyRunInState("monthlyCultivation") { processMonthlyCultivationAndAuto(state) }
        state.safelyRunInState("vassalBreakaway") { vassalService.processMonthlyBreakawayCheck(state) }
        state.safelyRunInState("missionRefresh") { processMissionRefreshIfDue(month, state) }
    }

    fun processMonthlyEvents(year: Int, month: Int) {
        // 单事务：所有月度事件原子提交
        stateStore.update {
            // 每月开始时重置招募月度计数，使当月招募享有完整上限配额
            gameData = gameData.copy(recruitCountThisMonth = 0)
            safelyRunInState("autoRecruit") {
                RecruitService.processAutoRecruit(this)
            }
            safelyRunInState("theft") { lawEnforcementProcessor.processTheftIfNeeded() }
            safelyRunInState("lawEnforcement") { lawEnforcementProcessor.processLawEnforcementMonthly() }
            safelyRunInState("completedMissions") { processCompletedMissionsLazy(year, month) }
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
    private fun processMonthlyCultivationAndAuto(state: MutableGameState) {
        val data = state.gameData
        val tables = state.discipleTables
        val aliveIds = tables.ids.filter { tables.isAlive[it] == 1 }
        if (aliveIds.isEmpty()) return
        // HP/MP 恢复（兜底，已由每旬检查补充）。
        // 使用 recoverHpMpForAllDisciples（非逐弟子循环）— 此方法遍历时 equipmentMap/manualMap
        // 只构建一次，比 N 次 recoverHpMpSingle 调用更高效。
    }
    fun processYearlyEvents(year: Int) {
        // 单事务：所有年变事件原子提交
        // 子服务内部 stateStore.update 通过重入缓冲共享同一副本
        stateStore.update {
            safelyRunInState("yearlyTribute") { vassalService.processYearlyTribute() }
            safelyRunInState("yearlyVassalTribute") {
                vassalService.processYearlyVassalTribute(year)
            }
            safelyRunInState("discipleAging") {
                discipleLifecycleProcessor.processDiscipleAging(year)
            }
            safelyRunInState("sectDisciplesAging") {
                caveExplorationProcessor.get().processSectDisciplesAging(year)
            }
            safelyRunInState("refreshRecruitList") {
                if (year % 3 == 1) recruitService.refreshRecruitList(year)
            }
            safelyRunInState("autoReject") {
                RecruitService.processAutoReject(this)
            }
            safelyRunInState("merchantRefreshChance") {
                merchantAndRecruitService.giveMerchantRefreshChanceIfDue(year)
            }
            safelyRunInState("yearlyAging") {
                discipleLifecycleProcessor.processYearlyAging(year)
            }
            safelyRunInState("recruitAging") {
                recruitService.ageRecruitList(year)
            }
            safelyRunInState("sectYearlyRecruitment") {
                caveExplorationProcessor.get().processSectDisciplesYearlyRecruitment(year)
            }
            safelyRunInState("autoBuy") { autoBuyService.executeAutoBuy(year, 1) }
            safelyRunInState("refreshAcquisition") {
                merchantAndRecruitService.refreshMerchantAcquisition(year, 1)
            }
            safelyRunInState("partnerMatching") {
                diplomacyEventProcessor.processCrossSectPartnerMatching(year, 1)
            }
            safelyRunInState("allianceExpiry") {
                diplomacyEventProcessor.checkAllianceExpiry(year)
            }
            safelyRunInState("allianceFavorDrop") {
                diplomacyEventProcessor.checkAllianceFavorDrop()
            }
            safelyRunInState("aiAlliances") { diplomacyEventProcessor.processAIAlliances(year) }
            safelyRunInState("reflectionRelease") {
                discipleLifecycleProcessor.processReflectionRelease(year)
            }
            safelyRunInState("favorDecay") { diplomacyEventProcessor.processFavorDecay(year) }
            // 年度报告 + 驻军轮换
            safelyRunInState("garrisonAndReport") { runGarrisonAndReport(year) }
            safelyRunInState("griefExpiry") {
                discipleLifecycleProcessor.processGriefExpiry(year)
            }
        }
    }

    /**
     * 年变：驻军轮换 + 年度报告快照（单次原子 update）。
     * 已从 [processYearlyEvents] 内联代码提取，降低函数复杂度。
     */
    private fun runGarrisonAndReport(year: Int) {
        val rotated = AISectGarrisonManager.rotateGarrisonSlots(
            stateStore.gameData.value
        )
        val currentData = stateStore.gameData.value
        val report = YearlyReport(
            year = currentData.gameYear - 1,
            totalIncome = currentData.annualTotalIncome,
            totalExpenditure = currentData.annualTotalExpenditure,
            incomeBySource = currentData.annualIncomeBySource,
            expenditureByReason = currentData.annualExpenditureByReason,
            equipmentBySource = currentData.annualEquipmentBySource,
            pillBySource = currentData.annualPillBySource,
            herbBySource = currentData.annualHerbBySource,
            alchemyCompleted = currentData.annualAlchemyCount,
            forgeCompleted = currentData.annualForgeCount,
            herbsHarvested = currentData.annualHerbCount,
            newDisciples = currentData.annualNewDisciples,
            deceasedDisciples = currentData.annualDeceasedDisciples,
            desertedDisciples = currentData.annualDesertedDisciples
        )
        stateStore.update {
            gameData = gameData.copy(
                worldMapSects = rotated.worldMapSects,
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
                annualDesertedDisciples = 0,
                annualTheftCount = 0
            )
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
    // ── 任务 ──────────────────────────────────────────────────────────
    /** 处理已完成的任务并发放奖励（内部使用 stateStore.update 重入锁，可在已有事务内调用） */
    fun processCompletedMissionsLazy(year: Int, month: Int) {
        // 注意：Phase 2 使用 stateStore.update 重入锁（ReentrantLock），
        // 在外层 update 内部调用时通过锁重入机制在同一事务内生效。
        val data = stateStore.gameData.value
        val currentAbsoluteMonth = com.xianxia.sect.core.engine.LazyEvaluationDispatcher.toAbsoluteMonth(year, month)
        val remainingActive = mutableListOf<ActiveMission>()

        // ── Phase 1: 事务外计算（仅收集奖励数据，不变更任何状态） ──
        data class MissionReward(
            val missionId: String,
            val spiritStones: Int,
            val survivors: Set<String>,
            val discipleIds: List<String>,
            val materials: List<Material>,
            val pills: List<Pill>,
            val equipmentStacks: List<EquipmentStack>,
            val manualStacks: List<ManualStack>
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
            val missionReward = runCatching {
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
                // 仅收集奖励，不再调用 inventorySystem.addXxx（统一到 Phase 2 单事务处理）
                val survivors = if (result.combatTriggered && result.victory && result.battleResult != null) {
                    result.battleResult.log.teamMembers.filter { it.isAlive }.map { it.id }.toSet()
                } else emptySet()
                MissionReward(
                    missionId = activeMission.id,
                    spiritStones = result.spiritStones,
                    survivors = survivors,
                    discipleIds = activeMission.discipleIds,
                    materials = result.materials,
                    pills = result.pills,
                    equipmentStacks = result.equipmentStacks,
                    manualStacks = result.manualStacks
                )
            }
            if (missionReward.isSuccess && missionReward.getOrNull() != null) {
                missionReward.getOrNull()?.let { rewards.add(it) }
            } else {
                remainingActive.add(activeMission) // 失败的任务保留到下次
            }
        }

        // ── Phase 2: 单事务写入所有状态（物品 + 灵石 + 弟子状态 + 任务清理） ──
        // 注意：使用 re-entrant stateStore.update 而非直接操作传入的 state，
        // 因为 MissionReward 为局部 data class 无法在函数外引用。
        // ReentrantLock 允许嵌套 update — 内层操作同一 MutableGameState。
        stateStore.update {
            for (reward in rewards) {
                // 发放物品（通过重入缓冲在同一事务内生效）
                reward.materials.forEach { material ->
                    val r = inventorySystem.addMaterial(material)
                    when (r) {
                        is DomainResult.Success -> {}
                        is DomainResult.Partial -> DomainLog.w(TAG, "${material.name} 溢出 ${r.overflow} 个")
                        is DomainResult.Failure -> DomainLog.w(TAG, "添加 ${material.name} 失败: ${r.error}")
                    }
                }
                inventorySystem.withTrackingSource("trial") {
                    reward.pills.forEach { pill ->
                        val r = inventorySystem.addPill(pill)
                        when (r) {
                            is DomainResult.Success -> {}
                            is DomainResult.Partial -> DomainLog.w(TAG, "${pill.name} 溢出 ${r.overflow} 个")
                            is DomainResult.Failure -> DomainLog.w(TAG, "添加 ${pill.name} 失败: ${r.error}")
                        }
                    }
                    reward.equipmentStacks.forEach { equip ->
                        val r = inventorySystem.addEquipmentStack(equip)
                        when (r) {
                            is DomainResult.Success -> {}
                            is DomainResult.Partial -> DomainLog.w(TAG, "${equip.name} 溢出 ${r.overflow} 个")
                            is DomainResult.Failure -> DomainLog.w(TAG, "添加 ${equip.name} 失败: ${r.error}")
                        }
                    }
                }
                reward.manualStacks.forEach { manual ->
                    val r = inventorySystem.addManualStack(manual)
                    when (r) {
                        is DomainResult.Success -> {}
                        is DomainResult.Partial -> DomainLog.w(TAG, "${manual.name} 溢出 ${r.overflow} 个")
                        is DomainResult.Failure -> DomainLog.w(TAG, "添加 ${manual.name} 失败: ${r.error}")
                    }
                }
                // 灵石
                if (reward.spiritStones > 0) {
                    spiritStoneWallet.add(this, reward.spiritStones.toLong(), SpiritStoneGrade.LOW, SpiritStoneSource.Quest)
                }
                // 弟子状态
                for (did in reward.discipleIds) {
                    val tid = did.toIntOrNull() ?: continue
                    val dTables = discipleTables
                    val tableIds = dTables.ids
                    if (tid < 0 || tid >= tableIds.size || dTables.isAlive[tid] != 1) continue
                    // ★ 修复：重置状态为 IDLE — processCompletedMissionsLazy 此前漏掉了状态重置，
                    // 导致任务已从 activeMissions 移除但弟子永远卡在 ON_MISSION。
                    // 随后 syncAllDiscipleStatuses() 看到 IDLE 状态后推导正确，不会触发 ON_MISSION 保护守卫。
                    dTables.statuses[tid] = DiscipleStatus.IDLE
                    if (did in reward.survivors) {
                        dTables.soulPowers[tid] = dTables.soulPowers.getOrDefault(tid, 0) + 1
                    }
                }
            }
            gameData = gameData.copy(activeMissions = remainingActive)
        }
        discipleService.syncAllDiscipleStatuses()
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
