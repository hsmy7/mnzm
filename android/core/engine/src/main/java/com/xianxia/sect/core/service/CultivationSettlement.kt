package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.SectPolicies
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.model.mining
import com.xianxia.sect.core.model.morality
import com.xianxia.sect.core.model.storageBagSpiritStones
import com.xianxia.sect.core.model.guide.GuideCounterKeys
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.config.GameConfigProvider
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.util.CoroutineScopeProvider
import javax.inject.Inject
import javax.inject.Singleton
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
    private val scopeProvider: CoroutineScopeProvider,
    private val spiritStoneWallet: SpiritStoneWallet,
    private val lawEnforcementProcessor: LawEnforcementProcessor,
    private val gameConfigProvider: GameConfigProvider
) {
    private val scope get() = scopeProvider.scope

    // Diplomacy event counter
    internal var diplomacyEventsThisMonth = 0
    internal var diplomacyEventsMonth = 0

    /**
     * 年度年俸发放 — 每年 1 月在年度结算路径执行。
     *
     * 受开源节流政策影响：
     * - 年俸金额-30%
     * - 不发忠诚度
     */
    fun processAnnualSalary(year: Int) {
        val maxLoyalty = GameConfig.Disciple.MAX_LOYALTY
        val plan = calculateSalaryPlan() ?: return

        // 灵石不足 → 应得俸禄的弟子忠诚 -1，不发俸禄
        if (!spiritStoneWallet.canAfford(plan.totalRequired)) {
            stateStore.update {
                for ((idStr, _) in plan.eligibleSalaries) {
                    val id = idStr.toIntOrNull() ?: continue
                    if (!discipleTables.ids.contains(id) || discipleTables.isAlive[id] != 1) continue
                    val current = discipleTables.loyalties.getOrDefault(id, 50)
                    discipleTables.loyalties[id] = (current - 1).coerceAtLeast(GameConfig.Disciple.MIN_LOYALTY)
                }
            }
            return
        }

        stateStore.update {
            val data = gameData
            val isFrugality = data.sectPolicies.frugality
            val salaryMultiplier = if (isFrugality) (1.0 - GameConfig.PolicyConfig.FRUGALITY_SALARY_REDUCTION) else 1.0

            val result = spiritStoneWallet.deduct(this, plan.totalRequired, SpiritStoneGrade.LOW,
                SpiritStoneReason.Salary, SpiritStoneSource.Salary, true)
            if (result !is DeductResult.Success) return@update

            // ★ 列直写替代 assembleAll → map → replaceAll
            for ((idStr, salary) in plan.eligibleSalaries) {
                val id = idStr.toIntOrNull() ?: continue
                if (!discipleTables.ids.contains(id) || discipleTables.isAlive[id] != 1) continue
                val actualSalary = (salary * salaryMultiplier).roundToLong()
                val currentStones = discipleTables.storageBagSpiritStones.getOrDefault(id, 0L)
                discipleTables.storageBagSpiritStones[id] = currentStones + actualSalary
                discipleTables.salaryPaidCounts[id] =
                    discipleTables.salaryPaidCounts.getOrDefault(id, 0) + 1
                // 开源节流政策下不发忠诚
                if (!isFrugality) {
                    val current = discipleTables.loyalties.getOrDefault(id, 50)
                    discipleTables.loyalties[id] = (current + 1).coerceAtMost(maxLoyalty)
                }
            }
        }
    }

    internal data class SalaryPlan(
        val eligibleSalaries: Map<String, Long>,
        val totalRequired: Long
    )

    /** 幽灵防御判定：三表（isAlive/names/realms）齐全且名非空白，与 assembleAll 的 isCompleteId 等价。 */
    private fun isGhostEntry(tables: DiscipleTables, id: Int): Boolean =
        !tables.isAlive.contains(id) || !tables.names.contains(id) ||
            !tables.realms.contains(id) || tables.names.getOrNull(id)?.isBlank() != false

    /**
     * 计算应得俸禄弟子清单及总需求。
     * 不检查 [spiritStoneWallet.canAfford]（由 [processAnnualSalary] 处理）。
     *
     * L1c 列直读：assembleAll 组装全部 67 列字段（含字符串分配），而年俸计划只需
     * isAlive/realms 两列 ⇒ 改为列直读 O(D) 常数列访问、零对象分配。
     * 等价性：assembleAll 的 Disciple 字段即列数据（isAlive = getOrDefault(id,1)==1、
     * realm = getOrDefault(id,9)），过滤谓词逐字段一致；幽灵防御（三表齐全 +
     * 空名跳过，assembleAll 的 isCompleteId/isBlank 逻辑）原样保留。
     * 由 SalaryPlanColumnEquivalenceTest 逐位守卫。
     */
    internal fun calculateSalaryPlan(): SalaryPlan? {
        val data = stateStore.gameData.value
        val salaryConfig = data.yearlySalary
        val enabledConfig = data.yearlySalaryEnabled
        val tables = stateStore.discipleTables
        val eligible = tables.ids.distinct().mapNotNull { id ->
            // 幽灵防御等价（assembleAll 的 isCompleteId + 空名跳过）
            if (isGhostEntry(tables, id)) return@mapNotNull null
            val realm = tables.realms.getOrDefault(id, 9)
            val alive = tables.isAlive.getOrDefault(id, 1) == 1
            if (!alive || enabledConfig[realm] != true) return@mapNotNull null
            val salary = salaryConfig[realm]?.toLong() ?: 0L
            if (salary <= 0L) null else (id to salary)
        }
        val totalRequired = eligible.sumOf { it.second }
        if (totalRequired <= 0L) return null
        return SalaryPlan(
            eligibleSalaries = eligible.associate { it.first.toString() to it.second },
            totalRequired = totalRequired
        )
    }

    /**
     * 突破时补发当年年俸 — 仅发当年 1 年份，不累年。
     * 灵石不足则不发（自动售卖由 [SpiritStoneWallet] 统一处理）。
     * 受开源节流政策影响：金额-30% 且不发忠诚。
     */
    fun settleSalaryOnBreakthrough(discipleId: String, currentYear: Int) {
        val maxLoyalty = GameConfig.Disciple.MAX_LOYALTY

        stateStore.update {
            val tables = discipleTables
            val data = gameData
            val isFrugality = data.sectPolicies.frugality
            val salaryMultiplier = if (isFrugality) (1.0 - GameConfig.PolicyConfig.FRUGALITY_SALARY_REDUCTION) else 1.0

            val discipleIntId = discipleId.toIntOrNull() ?: return@update
            if (!tables.isAlive.contains(discipleIntId) || tables.isAlive[discipleIntId] != 1) return@update
            val realm = tables.realms.getOrDefault(discipleIntId, 9)
            val enabledConfig = data.yearlySalaryEnabled
            if (enabledConfig[realm] != true) return@update
            val salary = (data.yearlySalary[realm] ?: 0).toLong()
            if (salary <= 0) return@update

            val actualSalary = (salary * salaryMultiplier).roundToLong()
            val result = spiritStoneWallet.deduct(this, actualSalary, SpiritStoneGrade.LOW,
                SpiritStoneReason.Salary, SpiritStoneSource.Salary, true)
            if (result !is DeductResult.Success) return@update
            // ★ 列直写替代 assembleAll → map → replaceAll
            val currentStones = tables.storageBagSpiritStones.getOrDefault(discipleIntId, 0L)
            tables.storageBagSpiritStones[discipleIntId] = currentStones + actualSalary
            tables.salaryPaidCounts[discipleIntId] =
                tables.salaryPaidCounts.getOrDefault(discipleIntId, 0) + 1
            // 开源节流政策下不发忠诚
            if (!isFrugality) {
                tables.loyalties[discipleIntId] =
                    (tables.loyalties.getOrDefault(discipleIntId, 0) + 1).coerceAtMost(maxLoyalty)
            }
        }
    }

    fun processResidenceLoyalty(state: MutableGameState) {
        val maxLoyalty = GameConfig.Disciple.MAX_LOYALTY
        val residentIds = state.gameData.residenceSlots.filter { it.isActive }.map { it.discipleId }.toSet()
        // ★ 列直写替代 assembleAll → map → replaceAll
        for (id in state.discipleTables.ids) {
            if (id.toString() in residentIds && state.discipleTables.loyalties[id] < maxLoyalty) {
                state.discipleTables.loyalties[id] =
                    (state.discipleTables.loyalties[id] + 1).coerceAtMost(maxLoyalty)
            }
        }
    }

    /**
     * 政策月度灵石扣除。
     * 通过 [SpiritStoneWallet] 逐项扣除，账本可追溯。
     * 支持固定月消耗、按弟子数计费、周期性消耗三种模式。
     * @return [PolicyCostResult] — AllPaid 或 SomeDisabled
     */
    fun processPolicyCosts(state: MutableGameState): PolicyCostResult {
        val data = state.gameData
        val disabledPolicies = mutableListOf<String>()
        val deductedPolicies = mutableListOf<Pair<String, Long>>()

        fun tryDeduct(cost: Long, name: String, isEnabled: Boolean, disable: (SectPolicies) -> SectPolicies) {
            if (!isEnabled || cost <= 0L) return@tryDeduct
            when (spiritStoneWallet.deduct(state, cost, SpiritStoneGrade.LOW,
                SpiritStoneReason.PolicyCost, SpiritStoneSource.Internal, true)) {
                is DeductResult.Success -> deductedPolicies.add(name to cost)
                else -> {
                    state.gameData = state.gameData.copy(sectPolicies = disable(state.gameData.sectPolicies))
                    disabledPolicies.add(name)
                }
            }
        }

        // ── 固定月消耗政策 ──
        tryDeduct(GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_MONTHLY, "丹道激励", data.sectPolicies.alchemyIncentive) { it.copy(alchemyIncentive = false) }
        tryDeduct(GameConfig.PolicyConfig.FORGE_INCENTIVE_MONTHLY, "锻造激励", data.sectPolicies.forgeIncentive) { it.copy(forgeIncentive = false) }
        tryDeduct(GameConfig.PolicyConfig.HERB_CULTIVATION_MONTHLY, "灵药培育", data.sectPolicies.herbCultivation) { it.copy(herbCultivation = false) }
        tryDeduct(GameConfig.PolicyConfig.MANUAL_RESEARCH_MONTHLY, "功法研习", data.sectPolicies.manualResearch) { it.copy(manualResearch = false) }
        tryDeduct(GameConfig.PolicyConfig.ENHANCED_SECURITY_MONTHLY, "增强治安", data.sectPolicies.enhancedSecurity) { it.copy(enhancedSecurity = false) }
        tryDeduct(GameConfig.PolicyConfig.CURFEW_MONTHLY, "宵禁", data.sectPolicies.curfew) { it.copy(curfew = false) }
        tryDeduct(GameConfig.PolicyConfig.REWARD_PUNISH_MONTHLY, "赏善罚恶", data.sectPolicies.rewardPunish) { it.copy(rewardPunish = false) }
        tryDeduct(GameConfig.PolicyConfig.STRICT_TRAINING_MONTHLY, "严苛训练", data.sectPolicies.strictTraining) { it.copy(strictTraining = false) }
        tryDeduct(GameConfig.PolicyConfig.RELAXED_MGMT_MONTHLY, "松弛管理", data.sectPolicies.relaxedMgmt) { it.copy(relaxedMgmt = false) }
        tryDeduct(GameConfig.PolicyConfig.SPIRIT_SPRING_MONTHLY, "灵泉灌溉", data.sectPolicies.spiritSpring) { it.copy(spiritSpring = false) }

        // ── 按弟子数计费政策 ──
        val totalDisciples = state.discipleTables.ids.count { id ->
            state.discipleTables.isAlive.getOrDefault(id, 0) == 1
        }
        val huashenBelowCount = state.discipleTables.ids.count { id ->
            state.discipleTables.isAlive.getOrDefault(id, 0) == 1 &&
                state.discipleTables.realms.getOrDefault(id, 9) > 5 // realm 5=化神, >5=化神下
        }

        if (data.sectPolicies.cultivationSubsidy) {
            val cost = GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_PER_DISCIPLE * huashenBelowCount
            tryDeduct(cost, "修行津贴", true) { it.copy(cultivationSubsidy = false) }
        }
        if (data.sectPolicies.asceticTraining) {
            val cost = GameConfig.PolicyConfig.ASCETIC_TRAINING_PER_DISCIPLE * totalDisciples
            tryDeduct(cost, "苦修令", true) { it.copy(asceticTraining = false) }
        }
        if (data.sectPolicies.moralEducation) {
            val cost = GameConfig.PolicyConfig.MORAL_EDUCATION_PER_DISCIPLE * totalDisciples
            tryDeduct(cost, "教化之道", true) { it.copy(moralEducation = false) }
        }
        if (data.sectPolicies.benevolentGovernance) {
            val cost = GameConfig.PolicyConfig.BENEVOLENT_GOVERNANCE_PER_DISCIPLE * totalDisciples
            tryDeduct(cost, "仁政爱徒", true) { it.copy(benevolentGovernance = false) }
        }

        // ── 周期性消耗 ──
        // 广纳门徒：每3年扣一次（冷却期内不扣）
        if (data.sectPolicies.openRecruitment) {
            val currentMonth = data.gameYear * 12 + data.gameMonth
            if (currentMonth - data.openRecruitmentLastPaidMonth >= GameConfig.PolicyConfig.OPEN_RECRUITMENT_COOLDOWN_MONTHS) {
                tryDeduct(GameConfig.PolicyConfig.OPEN_RECRUITMENT_COST, "广纳门徒", true) { it.copy(openRecruitment = false) }
                // 记录本次付费月份
                if (deductedPolicies.any { it.first == "广纳门徒" }) {
                    state.gameData = state.gameData.copy(openRecruitmentLastPaidMonth = currentMonth)
                }
            }
        }

        return if (disabledPolicies.isNotEmpty()) {
            PolicyCostResult.SomeDisabled(disabledPolicies, deductedPolicies)
        } else {
            PolicyCostResult.AllPaid
        }
    }

    /**
     * 政策月度非消耗类效果。
     * 在月度 tick 中 processPolicyCosts 之后调用。
     * - 教化之道：所有弟子道德+1（上限70）
     * - 仁政爱徒：所有弟子忠诚+1（上限100）
     * - 严苛训练：所有弟子忠诚-1（下限0）
     * - 增强治安：所有弟子忠诚-1（下限0）
     * - 宵禁：所有弟子忠诚-1（下限0）
     * - 松弛管理：所有弟子忠诚+2（上限100）
     */
    fun processPolicyMonthlyEffects(state: MutableGameState) {
        val data = state.gameData
        val tables = state.discipleTables
        val maxLoyalty = GameConfig.Disciple.MAX_LOYALTY
        val maxMoral = GameConfig.PolicyConfig.MORAL_EDUCATION_MAX

        var moralCount = 0
        var loyaltyDeltaSum = 0
        // 单次遍历所有活弟子，合并所有政策的忠诚/道德效果
        for (id in tables.ids) {
            if (tables.isAlive.getOrDefault(id, 0) != 1) continue

            // 忠诚净变化（各政策月度忠诚增减汇总）
            var loyaltyDelta = 0
            if (data.sectPolicies.benevolentGovernance) loyaltyDelta += GameConfig.PolicyConfig.BENEVOLENT_LOYALTY_PER_MONTH
            if (data.sectPolicies.relaxedMgmt) loyaltyDelta += GameConfig.PolicyConfig.RELAXED_MGMT_LOYALTY_PER_MONTH
            if (data.sectPolicies.strictTraining) loyaltyDelta += GameConfig.PolicyConfig.STRICT_TRAINING_LOYALTY_PER_MONTH
            if (data.sectPolicies.enhancedSecurity) loyaltyDelta += GameConfig.PolicyConfig.ENHANCED_SECURITY_LOYALTY_PER_MONTH
            if (data.sectPolicies.curfew) loyaltyDelta += GameConfig.PolicyConfig.CURFEW_LOYALTY_PER_MONTH
            if (loyaltyDelta != 0) {
                val current = tables.loyalties.getOrDefault(id, 50)
                tables.loyalties[id] = (current + loyaltyDelta).coerceIn(0, maxLoyalty)
                loyaltyDeltaSum += loyaltyDelta
            }

            // 道德变化（教化之道）
            if (data.sectPolicies.moralEducation) {
                val current = tables.moralities.getOrDefault(id, 50)
                if (current < maxMoral) {
                    val newMoral = (current + GameConfig.PolicyConfig.MORAL_EDUCATION_PER_MONTH).coerceIn(0, maxMoral)
                    tables.moralities[id] = newMoral
                    // 教化之道提升道德，但若仍低于阈值则触发偷盗判定（事务内版本）
                    if (newMoral < GameConfig.LawEnforcementConfig.MORALITY_THRESHOLD) {
                        lawEnforcementProcessor.processSingleDiscipleTheft(id, state)
                    }
                    moralCount++
                }
            }
        }
        if (data.sectPolicies.moralEducation || loyaltyDeltaSum != 0) {
            DomainLog.d(TAG, "processPolicyMonthlyEffects: moralEducation↑${moralCount}人, " +
                "loyalty净变化=${if (loyaltyDeltaSum > 0) "+" else ""}$loyaltyDeltaSum")
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
        val baseOutput = gameConfigProvider.production.spiritMineBaseOutputPerMiner

        var miningBonus = 0.0
        data.spiritMineSlots.forEach { slot ->
            val discipleId = slot.discipleId
            if (discipleId.isNotEmpty()) {
                val idInt = discipleId.toIntOrNull() ?: return@forEach
                if (tables.ids.contains(idInt) && tables.isAlive[idInt] == 1) {
                    val mining = tables.minings[idInt] ?: 0
                    val threshold = gameConfigProvider.production.spiritMineMiningThreshold
                    if (mining > threshold) {
                        miningBonus += (mining - threshold) *
                            gameConfigProvider.production.spiritMineMiningBonusRate
                    }
                }
            }
        }

        val avgMiningBonus = if (minerCount > 0) miningBonus / minerCount else 0.0
        val boostMultiplier = if (data.sectPolicies.spiritMineBoost) SPIRIT_MINE_BOOST_MULTIPLIER else 1.0

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
            diff * DEACON_MORALITY_BONUS_RATE
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
    fun processSpiritMineProductionMonthly() {
        stateStore.update { processSpiritMineProductionMonthly(this) }
    }

    fun processSpiritMineProductionMonthly(state: MutableGameState) {
        val data = state.gameData
        val currentMonth = data.gameYear * 12 + data.gameMonth
        val zones = buildSpiritMineZones(data, state.discipleTables)
        val baseOutput = gameConfigProvider.production.spiritMineBaseOutputPerMiner
        val monthlyRate: Long = zones.calculateMonthly(baseOutput.toDouble())
        val lastSettled = data.spiritMineLastSettledMonth
        if (currentMonth > lastSettled && monthlyRate > 0L) {
            val delta = currentMonth - lastSettled
            val totalOutput = monthlyRate * delta
            spiritStoneWallet.add(state, totalOutput, SpiritStoneGrade.LOW, SpiritStoneSource.Mine)
            // 更新引导系统累计灵矿产出计数器
            val currentCount = state.gameData.guideCounters[GuideCounterKeys.MINING_OUTPUT] ?: 0L
            state.gameData = state.gameData.copy(
                guideCounters = state.gameData.guideCounters + (GuideCounterKeys.MINING_OUTPUT to currentCount + totalOutput)
            )
        }
        state.gameData = state.gameData.copy(spiritMineLastSettledMonth = currentMonth)
        applyMinerLoyaltyDecay(state)
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
        private const val SPIRIT_MINE_BOOST_MULTIPLIER = 1.2
        private const val DEACON_MORALITY_BONUS_RATE = 0.01
    }
}
 
