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
import kotlin.math.roundToLong
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.ZoneCalculator
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.core.wallet.SpiritStoneReason
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.wallet.DeductResult

/**
 * 政策月度扣除结果。
 */
sealed interface PolicyCostResult {
    /** 所有政策正常扣除 */
    data object AllPaid : PolicyCostResult
    /** 部分政策因灵石不足被自动关闭 */
    data class SomeDisabled(
        val disabledPolicies: List<String>,
        val deducted: List<Pair<String, Long>>
    ) : PolicyCostResult
}

/**
 * 宗门结算服务 — 年俸、政策、灵矿产出。
 *
 * 灵矿产出采用 **时间戳差分惰性结算**（对标 Supercell Clash of Clans 模式）：
 * - 不再逐旬记录 phase snapshot
 * - 月末按当前矿工状态计算月产出率
 * - 使用 [spiritMineLastSettledMonth] 做回档保护
 */
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
    private val scopeProvider: CoroutineScopeProvider,
    private val spiritStoneWallet: SpiritStoneWallet
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
            val plan = calculateSalaryPlan(maxLoyalty, midRatio, highRatio) ?: return@update
            applySalaryPlan(plan, maxLoyalty)
        }
    }

    private data class SalaryPlan(
        val eligibleSalaries: Map<String, Long>,
        val lowDeduct: Long,
        val midDeduct: Long,
        val highDeduct: Long,
        val change: Long
    )

    private fun MutableGameState.calculateSalaryPlan(
        maxLoyalty: Int,
        midRatio: Long,
        highRatio: Long
    ): SalaryPlan? {
        val data = gameData
        val salaryConfig = data.yearlySalary
        val enabledConfig = data.yearlySalaryEnabled

        val eligible = discipleTables.assembleAll()
            .filter { it.isAlive && enabledConfig[it.realm] == true }
            .filter { it.skills.loyalty < maxLoyalty }
            .map { it to (salaryConfig[it.realm]?.toLong() ?: 0L) }
            .filter { it.second > 0L }

        val totalRequired = eligible.sumOf { it.second }
        if (totalRequired <= 0L) return null

        var available = data.spiritStones
        if (data.autoSellMidGradeForPurchase) {
            available += data.midGradeSpiritStones * midRatio
        }
        if (data.autoSellHighGradeForPurchase) {
            available += data.highGradeSpiritStones * highRatio
        }
        if (available < totalRequired) return null

        var remaining = totalRequired
        val lowDeduct = minOf(remaining, data.spiritStones)
        remaining -= lowDeduct
        var midDeduct = 0L
        var highDeduct = 0L

        if (remaining > 0 && data.autoSellMidGradeForPurchase) {
            val need = (remaining + midRatio - 1) / midRatio
            midDeduct = minOf(need, data.midGradeSpiritStones)
            remaining -= midDeduct * midRatio
        }
        if (remaining > 0 && data.autoSellHighGradeForPurchase) {
            val need = (remaining + highRatio - 1) / highRatio
            highDeduct = minOf(need, data.highGradeSpiritStones)
            remaining -= highDeduct * highRatio
        }
        val change = if (remaining < 0) -remaining else 0L

        return SalaryPlan(
            eligibleSalaries = eligible.associate { it.first.id to it.second },
            lowDeduct = lowDeduct,
            midDeduct = midDeduct,
            highDeduct = highDeduct,
            change = change
        )
    }

    private fun MutableGameState.applySalaryPlan(plan: SalaryPlan, maxLoyalty: Int) {
        val data = gameData
        gameData = data.copy(
            spiritStones = data.spiritStones - plan.lowDeduct + plan.change,
            midGradeSpiritStones = data.midGradeSpiritStones - plan.midDeduct,
            highGradeSpiritStones = data.highGradeSpiritStones - plan.highDeduct
        )

        val currentDisciples = discipleTables.assembleAll()
        discipleTables.clear()
        currentDisciples.forEach { disciple ->
            val salary = plan.eligibleSalaries[disciple.id]
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
     * @return [PolicyCostResult] — AllPaid 或 SomeDisabled
     */
    fun processPolicyCosts(state: MutableGameState): PolicyCostResult {
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
        return if (disabledPolicies.isNotEmpty()) {
            PolicyCostResult.SomeDisabled(disabledPolicies, deductedPolicies)
        } else {
            PolicyCostResult.AllPaid
        }
    }

    // ── 灵矿产出（时间戳差分惰性结算）──

    /**
     * 灵矿产出乘区（Spirit Mine Zone）。
     *
     * 公式：月总产出 = base × (1 + miningSkillZone) × (1 + deaconZone) × (1 + policyZone)
     */
    data class SpiritMineZones(
        val minerCount: Int = 0,
        val avgMiningSkillBonus: Double = 0.0,  // 矿工采矿技能平均加成
        val deaconMoralityBonus: Double = 0.0,  // 执事道德加成
        val policyBoost: Double = 0.0,           // 灵矿增产政策
    ) {
        /**
         * 计算月总产出（返回 Long，使用 roundToLong 防截断）。
         */
        fun calculateMonthly(basePerMiner: Double): Long {
            val base = minerCount * basePerMiner
            return ZoneCalculator.calculate(
                base, avgMiningSkillBonus, deaconMoralityBonus, policyBoost
            ).roundToLong()
        }
    }

    /**
     * 从 GameData + DiscipleTables 构建灵矿产出乘区。
     */
    private fun buildSpiritMineZones(data: GameData, tables: DiscipleTables): SpiritMineZones {
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

        return SpiritMineZones(
            minerCount = minerCount,
            avgMiningSkillBonus = avgMiningBonus,
            deaconMoralityBonus = deaconBonus,
            policyBoost = ZoneCalculator.multiplierToZone(boostMultiplier)
        )
    }

    /**
     * 灵矿月度产出结算 — 时间戳差分模式（对标 Supercell Clash of Clans）。
     *
     * 计算逻辑：
     * 1) 用当前矿工/执事/政策状态构建乘区，计算月产出率
     * 2) 时间戳差分：产出 = 月产出率 × (当前月份 - 上次结算月份)
     * 3) 回档保护：当 lastSettledMonth ≥ currentMonth 时跳过
     *
     * 由 [CultivationEventProcessor.processMonthlyEvents] 调用。
     */
    suspend fun processSpiritMineProductionMonthly() {
        stateStore.update {
            val data = gameData
            val currentMonth = data.gameYear * 12 + data.gameMonth
            val zones = buildSpiritMineZones(data, discipleTables)
            val baseOutput = GameConfig.Production.SPIRIT_MINE_BASE_OUTPUT_PER_MINER
            val monthlyRate: Long = zones.calculateMonthly(baseOutput.toDouble())
            val lastSettled = data.spiritMineLastSettledMonth
            if (currentMonth > lastSettled && monthlyRate > 0L) {
                val delta = currentMonth - lastSettled
                spiritStoneWallet.applyAdd(this, monthlyRate * delta, SpiritStoneGrade.LOW, SpiritStoneSource.Mine)
            }
            gameData = gameData.copy(spiritMineLastSettledMonth = currentMonth)
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
