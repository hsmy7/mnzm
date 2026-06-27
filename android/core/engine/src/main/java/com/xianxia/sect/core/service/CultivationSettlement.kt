package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.domain.disciple.DiscipleSlotCleanup
import com.xianxia.sect.core.engine.domain.disciple.DiscipleService
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.domain.building.HerbGardenSystem
import com.xianxia.sect.core.engine.domain.building.HerbGardenAuraService
import com.xianxia.sect.core.engine.domain.exploration.CaveExplorationSystem
import com.xianxia.sect.core.engine.domain.exploration.MissionSystem
import com.xianxia.sect.core.engine.domain.battle.AISectAttackManager
import com.xianxia.sect.core.engine.domain.battle.AISectGarrisonManager
import com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager
import com.xianxia.sect.core.engine.WorldMapGenerator
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.registry.*
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.config.DiplomaticEventConfig
import com.xianxia.sect.core.util.BuildingNames
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.core.util.SpiritRootGenerator
import com.xianxia.sect.core.util.NameService
import com.xianxia.sect.core.util.PortraitPool
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.util.CoroutineScopeProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import com.xianxia.sect.core.util.DomainLog

@Singleton
@GameService("CultivationSettlement")
class CultivationSettlement @Inject constructor(
    private val stateStore: GameStateStore,
    private val inventorySystem: InventorySystem,
    private val inventoryConfig: InventoryConfig,
    private val battleSystem: BattleSystem,
    private val productionCoordinator: ProductionCoordinator,
    private val productionSlotRepository: ProductionSlotRepository,
    private val discipleService: DiscipleService,
    private val cultivationCore: CultivationCore,
    private val breakthroughHandler: DiscipleBreakthroughHandler,
    private val scopeProvider: CoroutineScopeProvider
) {
    private val scope get() = scopeProvider.scope

    // Diplomacy event counter
    internal var diplomacyEventsThisMonth = 0
    internal var diplomacyEventsMonth = 0

    /**
     * 年度年俸发放 — 每年 1 月在年度结算路径执行。
     *
     * 规则：
     * - 遍历所有存活弟子，按境界计算年俸
     * - 宗门灵石充足 → 全员发放：扣灵石，弟子忠诚 +1，灵石进储物袋
     * - 宗门灵石不足 → 全员不发（开启中品/上品补差价则自动折合，找零退回下品）
     * - 忠诚度已满的弟子跳过
     */
    suspend fun processAnnualSalary(year: Int) {
        val maxLoyalty = GameConfig.Disciple.MAX_LOYALTY
        val midRatio = SpiritStoneExchange.EFFECTIVE_RATIO
        val highRatio = SpiritStoneExchange.EFFECTIVE_RATIO * SpiritStoneExchange.EFFECTIVE_RATIO

        stateStore.update {
            val data = gameData
            val salaryConfig = data.yearlySalary
            val enabledConfig = data.yearlySalaryEnabled

            // 筛选应发弟子
            val currentDisciples = discipleTables.assembleAll()
            val eligible = currentDisciples
                .filter { it.isAlive && enabledConfig[it.realm] == true }
                .filter { it.skills.loyalty < maxLoyalty }
                .map { it to (salaryConfig[it.realm]?.toLong() ?: 0L) }
                .filter { it.second > 0L }

            val totalRequired = eligible.sumOf { it.second }
            if (totalRequired <= 0L) return@update

            // 计算可用灵石（下品 + 可选的中品/上品折合）
            var available = data.spiritStones
            if (data.autoSellMidGradeForPurchase) {
                available += data.midGradeSpiritStones * midRatio
            }
            if (data.autoSellHighGradeForPurchase) {
                available += data.highGradeSpiritStones * highRatio
            }
            if (available < totalRequired) return@update  // 不够，全员不发

            // 扣除灵石：下品 → 中品 → 上品，找零退下品
            var remaining = totalRequired
            val lowDeduct = minOf(remaining, data.spiritStones)
            remaining -= lowDeduct
            var midDeduct = 0L
            var highDeduct = 0L

            if (remaining > 0 && data.autoSellMidGradeForPurchase) {
                val need = (remaining + midRatio - 1) / midRatio  // ceil
                midDeduct = minOf(need, data.midGradeSpiritStones)
                remaining -= midDeduct * midRatio
            }
            if (remaining > 0 && data.autoSellHighGradeForPurchase) {
                val need = (remaining + highRatio - 1) / highRatio
                highDeduct = minOf(need, data.highGradeSpiritStones)
                remaining -= highDeduct * highRatio
            }
            // 找零：多扣的退回下品
            val change = if (remaining < 0) -remaining else 0L

            gameData = data.copy(
                spiritStones = data.spiritStones - lowDeduct + change,
                midGradeSpiritStones = data.midGradeSpiritStones - midDeduct,
                highGradeSpiritStones = data.highGradeSpiritStones - highDeduct
            )

            // 发放年俸：忠诚 +1，灵石进储物袋
            discipleTables.clear()
            currentDisciples.forEach { disciple ->
                val salary = eligible.firstOrNull { it.first.id == disciple.id }?.second
                if (salary != null && salary > 0L) {
                    discipleTables.insert(disciple.copy(
                        equipment = disciple.equipment.copy(
                            storageBagSpiritStones = disciple.equipment.storageBagSpiritStones + salary
                        ),
                        skills = disciple.skills.copy(
                            salaryPaidCount = disciple.skills.salaryPaidCount + 1,
                            loyalty = (disciple.skills.loyalty + 1).coerceAtMost(maxLoyalty)
                        )
                    ))
                } else {
                    discipleTables.insert(disciple)
                }
            }
        }
    }

    /**
     * 突破时补发当年年俸 — 仅发当年 1 年份，不累年。
     * 灵石不足则不发。
     */
    suspend fun settleSalaryOnBreakthrough(discipleId: String, currentYear: Int) {
        val maxLoyalty = GameConfig.Disciple.MAX_LOYALTY

        stateStore.update {
            val data = gameData
            val enabledConfig = data.yearlySalaryEnabled
            val currentDisciples = discipleTables.assembleAll()
            val disciple = currentDisciples.find { it.id == discipleId && it.isAlive } ?: return@update
            if (enabledConfig[disciple.realm] != true) return@update
            if (disciple.skills.loyalty >= maxLoyalty) return@update

            val salaryConfig = data.yearlySalary
            val salary = (salaryConfig[disciple.realm] ?: 0).toLong()
            if (salary <= 0) return@update
            if (data.spiritStones < salary) return@update  // 不够则不发

            gameData = data.copy(spiritStones = data.spiritStones - salary)
            discipleTables.clear()
            currentDisciples.forEach {
                if (it.id == discipleId) {
                    discipleTables.insert(it.copy(
                        equipment = it.equipment.copy(
                            storageBagSpiritStones = it.equipment.storageBagSpiritStones + salary
                        ),
                        skills = it.skills.copy(
                            salaryPaidCount = it.skills.salaryPaidCount + 1,
                            loyalty = (it.skills.loyalty + 1).coerceAtMost(maxLoyalty)
                        )
                    ))
                } else {
                    discipleTables.insert(it)
                }
            }
        }
    }

    suspend fun processResidenceLoyalty() {
        val maxLoyalty = GameConfig.Disciple.MAX_LOYALTY
        stateStore.update {
            val data = gameData
            val residentIds = data.residenceSlots.filter { it.isActive }.map { it.discipleId }.toSet()
            val updatedDisciples = discipleTables.assembleAll().map { d ->
                if (d.id in residentIds && d.skills.loyalty < maxLoyalty) {
                    d.copy(skills = d.skills.copy(loyalty = (d.skills.loyalty + 1).coerceAtMost(maxLoyalty)))
                } else d
            }
            discipleTables.clear()
            updatedDisciples.forEach { discipleTables.insert(it) }
        }
    }

    /**
     * 政策月度灵石扣除。
     * 直接操作影子状态，同步执行。
     */
    fun processPolicyCosts(state: MutableGameState) {
        val data = state.gameData
        val policies = data.sectPolicies
        var currentStones = data.spiritStones
        var updatedPolicies = policies
        val disabledPolicies = mutableListOf<String>()
        val deductedPolicies = mutableListOf<Pair<String, Long>>()

        fun checkAndDeduct(cost: Long, name: String, isEnabled: Boolean, disable: (SectPolicies) -> SectPolicies) {
            if (!isEnabled) return@checkAndDeduct
            if (currentStones >= cost) {
                currentStones -= cost
                deductedPolicies.add(name to cost)
            } else {
                updatedPolicies = disable(updatedPolicies)
                disabledPolicies.add(name)
            }
        }

        checkAndDeduct(GameConfig.PolicyConfig.ENHANCED_SECURITY_COST.toLong(), "增强治安", policies.enhancedSecurity) { it.copy(enhancedSecurity = false) }
        checkAndDeduct(GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_COST.toLong(), "丹道激励", policies.alchemyIncentive) { it.copy(alchemyIncentive = false) }
        checkAndDeduct(GameConfig.PolicyConfig.FORGE_INCENTIVE_COST.toLong(), "锻造激励", policies.forgeIncentive) { it.copy(forgeIncentive = false) }
        checkAndDeduct(GameConfig.PolicyConfig.HERB_CULTIVATION_COST.toLong(), "灵药培育", policies.herbCultivation) { it.copy(herbCultivation = false) }
        checkAndDeduct(GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_COST.toLong(), "修行津贴", policies.cultivationSubsidy) { it.copy(cultivationSubsidy = false) }
        checkAndDeduct(GameConfig.PolicyConfig.MANUAL_RESEARCH_COST.toLong(), "功法研习", policies.manualResearch) { it.copy(manualResearch = false) }

        if (deductedPolicies.isNotEmpty() || disabledPolicies.isNotEmpty()) {
            var updatedGameData = data
            if (deductedPolicies.isNotEmpty()) {
                updatedGameData = updatedGameData.copy(spiritStones = currentStones)
            }
            if (disabledPolicies.isNotEmpty()) {
                updatedGameData = updatedGameData.copy(sectPolicies = updatedPolicies)
            }
            state.gameData = updatedGameData
        }
    }

    /**
     * 灵矿月度产出结算。
     * 直接操作影子状态，同步执行，不使用异步协程。
     */
    fun processSpiritMineProduction(state: MutableGameState) {
        val data = state.gameData
        val tables = state.discipleTables
        val minerCount = data.spiritMineSlots.count { it.discipleId.isNotEmpty() }
        val baseOutput = GameConfig.Production.SPIRIT_MINE_BASE_OUTPUT_PER_MINER

        var miningBonus = 0.0
        data.spiritMineSlots.forEach { slot ->
            val discipleId = slot.discipleId
            if (discipleId.isNotEmpty()) {
                val idInt = discipleId.toIntOrNull() ?: return@forEach
                if (tables.ids.contains(idInt) && tables.isAlive[idInt] == 1) {
                    val mining = tables.minings[idInt] ?: 0
                    if (mining > GameConfig.Production.SPIRIT_MINE_MINING_THRESHOLD) {
                        miningBonus += (mining - GameConfig.Production.SPIRIT_MINE_MINING_THRESHOLD) *
                            GameConfig.Production.SPIRIT_MINE_MINING_BONUS_RATE
                    }
                }
            }
        }

        val avgMiningBonus = if (minerCount > 0) miningBonus / minerCount else 0.0
        val baseSpiritStones = minerCount * baseOutput.toDouble()
        val boostMultiplier = if (data.sectPolicies.spiritMineBoost) 1.2 else 1.0

        val deaconBonus = data.elderSlots.spiritMineDeaconDisciples.mapNotNull { slot ->
            slot.discipleId?.let { discipleId ->
                val idInt = discipleId.toIntOrNull()
                if (idInt != null && tables.ids.contains(idInt) && tables.isAlive[idInt] == 1) {
                    tables.assemble(idInt)
                } else null
            }
        }.sumOf { disciple ->
            val baseline = GameConfig.PolicyConfig.ELDER_SKILL_BASELINE
            val diff = (DiscipleStatCalculator.getBaseStats(disciple).morality - baseline)
                .coerceAtLeast(0)
            diff * 0.01
        }

        val totalSpiritStones = (
            baseSpiritStones * (1 + avgMiningBonus) * (1 + deaconBonus) * boostMultiplier
        ).toInt()

        // 矿工忠诚度：每连续挖矿3月扣1点（直接操作组件表）
        val updatedSlots = data.spiritMineSlots.map { slot ->
            if (slot.discipleId.isNotEmpty()) {
                val idInt = slot.discipleId.toIntOrNull()
                if (idInt != null && tables.ids.contains(idInt)) {
                    val newMonths = slot.consecutiveMiningMonths + 1
                    if (newMonths >= 3) {
                        val current = tables.loyalties[idInt] ?: 0
                        tables.loyalties[idInt] = (current - 1).coerceAtLeast(0)
                        slot.copy(consecutiveMiningMonths = 0)  // 扣完重置
                    } else {
                        slot.copy(consecutiveMiningMonths = newMonths)
                    }
                } else {
                    slot.copy(consecutiveMiningMonths = 0)  // 弟子无效则重置
                }
            } else {
                slot.copy(consecutiveMiningMonths = 0)  // 空槽位重置
            }
        }
        state.gameData = data.copy(spiritMineSlots = updatedSlots)

        // 灵石产出
        if (totalSpiritStones > 0) {
            state.gameData = state.gameData.copy(
                spiritStones = data.spiritStones + totalSpiritStones
            )
        }
    }

    // ── 灵矿月度结算（常驻月度事件，不依赖域路由）──

    /** 每 phase 的灵矿产出快照 */
    data class SpiritMinePhaseSnapshot(
        val gamePhase: Int,
        /** 产出指纹，用于判断月内产出率是否变化 */
        val fingerprint: Int,
        /** 当日产出率（灵石/天） */
        val dailyRate: Long
    )

    /** 当前月各 phase 的产出快照，月度结算时消费并清空 */
    private val phaseSnapshots = mutableListOf<SpiritMinePhaseSnapshot>()

    /**
     * 计算灵矿产出指纹 — 捕获影响产出率的结构因素。
     * 指纹变化意味着产出率变化（矿工增减、技能变化、执事调整、政策切换）。
     */
    private fun computeSpiritMineFingerprint(
        data: GameData, tables: DiscipleTables
    ): Int {
        var h = 1
        // 矿工槽位分配
        h = 31 * h + data.spiritMineSlots
            .filter { it.discipleId.isNotEmpty() }
            .map { "${it.discipleId}:${it.buildingInstanceId}" }
            .hashCode()
        // 矿工挖掘技能 + 境界
        data.spiritMineSlots.forEach { slot ->
            slot.discipleId.toIntOrNull()?.let { id ->
                if (tables.ids.contains(id)) {
                    h = 31 * h + (tables.minings[id] ?: 0)
                    h = 31 * h + tables.realms.getOrDefault(id, 9)
                }
            }
        }
        // 灵矿执事弟子
        h = 31 * h + data.elderSlots.spiritMineDeaconDisciples
            .map { it.discipleId ?: "" }.hashCode()
        // 灵矿加成政策
        h = 31 * h + data.sectPolicies.spiritMineBoost.hashCode()
        return h
    }

    /**
     * 计算灵矿当日产出率（灵石/天），不含忠诚度扣减和槽位状态变更。
     * 仅用于 phase 快照的产出率记录。
     */
    private fun calculateSpiritMineDailyRate(
        data: GameData, tables: DiscipleTables
    ): Long {
        val minerCount = data.spiritMineSlots.count { it.discipleId.isNotEmpty() }
        if (minerCount == 0) return 0L

        val baseOutput = GameConfig.Production.SPIRIT_MINE_BASE_OUTPUT_PER_MINER

        var miningBonus = 0.0
        data.spiritMineSlots.forEach { slot ->
            val discipleId = slot.discipleId
            if (discipleId.isNotEmpty()) {
                val idInt = discipleId.toIntOrNull() ?: return@forEach
                if (tables.ids.contains(idInt) && tables.isAlive[idInt] == 1) {
                    val mining = tables.minings[idInt] ?: 0
                    if (mining > GameConfig.Production.SPIRIT_MINE_MINING_THRESHOLD) {
                        miningBonus += (mining - GameConfig.Production.SPIRIT_MINE_MINING_THRESHOLD) *
                            GameConfig.Production.SPIRIT_MINE_MINING_BONUS_RATE
                    }
                }
            }
        }

        val avgMiningBonus = if (minerCount > 0) miningBonus / minerCount else 0.0
        val baseSpiritStones = minerCount * baseOutput.toDouble()
        val boostMultiplier = if (data.sectPolicies.spiritMineBoost) 1.2 else 1.0

        val deaconBonus = data.elderSlots.spiritMineDeaconDisciples.mapNotNull { slot ->
            slot.discipleId?.let { discipleId ->
                val idInt = discipleId.toIntOrNull()
                if (idInt != null && tables.ids.contains(idInt) && tables.isAlive[idInt] == 1) {
                    tables.assemble(idInt)
                } else null
            }
        }.sumOf { disciple ->
            val baseline = GameConfig.PolicyConfig.ELDER_SKILL_BASELINE
            val diff = (DiscipleStatCalculator.getBaseStats(disciple).morality - baseline)
                .coerceAtLeast(0)
            diff * 0.01
        }

        // 月总产出 / 30 = 日产出
        val monthlyTotal = baseSpiritStones * (1 + avgMiningBonus) * (1 + deaconBonus) * boostMultiplier
        return (monthlyTotal / 30).toLong()
    }

    /**
     * 在每个游戏 phase 推进后记录灵矿产出快照。
     * 由 [GameEngineCore.tickInternal] 的 phase loop 调用，
     * 在 [GameStateStore.update] 块内执行以确保状态一致性。
     */
    fun recordPhaseSnapshot(state: MutableGameState) {
        val data = state.gameData
        val tables = state.discipleTables
        val fp = computeSpiritMineFingerprint(data, tables)
        val dailyRate = calculateSpiritMineDailyRate(data, tables)
        phaseSnapshots.add(SpiritMinePhaseSnapshot(data.gamePhase, fp, dailyRate))
    }

    /**
     * 灵矿月度产出结算 — 常驻月度事件，不依赖域路由。
     *
     * 按当月各 phase 记录的产出快照分别计算产出（每 phase 10 天），
     * 自动处理月内产出率变化（矿工增减/技能变化/执事调整等）。
     * 同时处理矿工忠诚度扣减（每连续挖矿 3 月扣 1 点）。
     *
     * 由 [CultivationEventProcessor.processMonthlyEvents] 调用。
     */
    suspend fun processSpiritMineProductionMonthly() {
        val snapshots = phaseSnapshots.toList()
        phaseSnapshots.clear()

        if (snapshots.isEmpty()) {
            // 兜底：无快照时用当前状态直接算全月
            stateStore.update {
                val data = gameData
                val tables = discipleTables
                val dailyRate = calculateSpiritMineDailyRate(data, tables)
                val monthly = dailyRate * 30
                if (monthly > 0) {
                    gameData = data.copy(
                        spiritStones = data.spiritStones + monthly
                    )
                }
                // 处理忠诚度
                applyMinerLoyaltyDecay(this)
            }
            return
        }

        // 按 phase 快照求和（每 phase 10 天）
        val totalProduction = snapshots.sumOf { it.dailyRate * 10 }

        stateStore.update {
            if (totalProduction > 0) {
                gameData = gameData.copy(
                    spiritStones = gameData.spiritStones + totalProduction
                )
            }
            // 处理忠诚度
            applyMinerLoyaltyDecay(this)
        }
    }

    /**
     * 矿工忠诚度扣减：每连续挖矿 3 月扣 1 点。
     * 在 [stateStore.update] 块内调用。
     */
    private fun applyMinerLoyaltyDecay(state: MutableGameState) {
        val data = state.gameData
        val tables = state.discipleTables
        val updatedSlots = data.spiritMineSlots.map { slot ->
            if (slot.discipleId.isNotEmpty()) {
                val idInt = slot.discipleId.toIntOrNull()
                if (idInt != null && tables.ids.contains(idInt)) {
                    val newMonths = slot.consecutiveMiningMonths + 1
                    if (newMonths >= 3) {
                        val current = tables.loyalties[idInt] ?: 0
                        tables.loyalties[idInt] = (current - 1).coerceAtLeast(0)
                        slot.copy(consecutiveMiningMonths = 0)
                    } else {
                        slot.copy(consecutiveMiningMonths = newMonths)
                    }
                } else {
                    slot.copy(consecutiveMiningMonths = 0)
                }
            } else {
                slot.copy(consecutiveMiningMonths = 0)
            }
        }
        state.gameData = data.copy(spiritMineSlots = updatedSlots)
    }

    companion object {
        private const val TAG = "CultivationSettlement"
    }
}
