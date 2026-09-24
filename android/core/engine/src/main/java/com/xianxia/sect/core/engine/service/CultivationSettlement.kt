package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.SectPolicies
import com.xianxia.sect.core.model.SpiritMineSlot
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
import com.xianxia.sect.core.engine.domain.disciple.getBaseStats



/**
 * 政策月度扣除结果。
 */
sealed interface PolicyCostResult {
    /** 所有政策正常扣除 */
    data object AllPaid : PolicyCostResult
    /** 部分政策因灵石不足被自动关闭 */
    data class SomeDisabled(
        /** 政策费用不足被自动禁用的政策名列表（事务外 checkpointAllProduction 决策） */
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
    private val gameConfigProvider: GameConfigProvider
) {
    /** 后台协程作用域 — 使用 [DeviceCapabilityProfiler.backgroundDispatcher] */
    private val scope get() = scopeProvider.scope

    // Diplomacy event counter
    internal var diplomacyEventsThisMonth = 0
    internal var diplomacyEventsMonth = 0

    /**
     * 年度年俸发放 — 每年 1 月在年度结算路径执行。
     *
     * 受开源节流政策影响：年俸金额-30%。
     */
    @Suppress("UnusedParameter") // year: 语义时点形参：标注年变/月变触发编排的可读契约，函数体当前不消费
    fun processAnnualSalary(year: Int) {
        val plan = calculateSalaryPlan() ?: return

        // 灵石不足 → 不发俸禄
        if (!spiritStoneWallet.canAfford(plan.totalRequired)) return

        stateStore.update {
            val data = gameData
            val isFrugality = data.sectPolicies.frugality
            val salaryMultiplier = if (isFrugality) (1.0 - GameConfig.PolicyConfig.FRUGALITY_SALARY_REDUCTION) else 1.0

            val result = spiritStoneWallet.deduct(this, plan.totalRequired, SpiritStoneGrade.LOW,
                SpiritStoneReason.Salary, SpiritStoneSource.Salary, true)
            if (result !is DeductResult.Success) return@update

            // 列直写：直接读写目标列，避免全量 assemble
            for ((idStr, salary) in plan.eligibleSalaries) {
                payAnnualSalaryToDisciple(discipleTables, idStr, salary, salaryMultiplier)
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
     * 列直读：assembleAll 需组装全部 67 列字段（含字符串分配），年俸计划只需
     * isAlive/realms 两列 ⇒ 列直读 O(D) 常数列访问、零对象分配。
     * 等价性：assembleAll 的 Disciple 字段即列数据（isAlive = getOrDefault(id,1)==1、
     * realm = getOrDefault(id,9)），过滤谓词逐字段一致；幽灵防御（三表齐全 +
     * 空名跳过，assembleAll 的 isCompleteId/isBlank 逻辑）原样保留。
     * 由 SalaryPlanColumnEquivalenceTest 逐位守卫。
     */
    @Suppress("UnusedParameter") // currentYear: 语义时点形参：标注年变/月变触发编排的可读契约，函数体当前不消费
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
     * 受开源节流政策影响：金额-30%。
     */
    @Suppress("UnusedParameter") // currentYear: 语义时点形参：标注年变/月变触发编排的可读契约，函数体当前不消费
    fun settleSalaryOnBreakthrough(discipleId: String, currentYear: Int) {
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
            // 列直写：直接读写目标列，避免全量 assemble
            val currentStones = tables.storageBagSpiritStones.getOrDefault(discipleIntId, 0L)
            tables.storageBagSpiritStones[discipleIntId] = currentStones + actualSalary
            tables.salaryPaidCounts[discipleIntId] =
                tables.salaryPaidCounts.getOrDefault(discipleIntId, 0) + 1
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
        /** 政策费用不足被自动禁用的政策名列表（事务外 checkpointAllProduction 决策） */
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
        tryDeduct(GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_MONTHLY, "丹道激励",
            data.sectPolicies.alchemyIncentive) { it.copy(alchemyIncentive = false) }
        tryDeduct(GameConfig.PolicyConfig.FORGE_INCENTIVE_MONTHLY, "锻造激励",
            data.sectPolicies.forgeIncentive) { it.copy(forgeIncentive = false) }
        tryDeduct(GameConfig.PolicyConfig.HERB_CULTIVATION_MONTHLY, "灵药培育",
            data.sectPolicies.herbCultivation) { it.copy(herbCultivation = false) }
        tryDeduct(GameConfig.PolicyConfig.MANUAL_RESEARCH_MONTHLY, "功法研习",
            data.sectPolicies.manualResearch) { it.copy(manualResearch = false) }
        tryDeduct(GameConfig.PolicyConfig.ENHANCED_SECURITY_MONTHLY, "增强治安",
            data.sectPolicies.enhancedSecurity) { it.copy(enhancedSecurity = false) }
        tryDeduct(GameConfig.PolicyConfig.CURFEW_MONTHLY, "宵禁", data.sectPolicies.curfew) { it.copy(curfew = false) }
        tryDeduct(GameConfig.PolicyConfig.REWARD_PUNISH_MONTHLY, "赏善罚恶",
            data.sectPolicies.rewardPunish) { it.copy(rewardPunish = false) }
        tryDeduct(GameConfig.PolicyConfig.STRICT_TRAINING_MONTHLY, "严苛训练",
            data.sectPolicies.strictTraining) { it.copy(strictTraining = false) }
        tryDeduct(GameConfig.PolicyConfig.RELAXED_MGMT_MONTHLY, "松弛管理",
            data.sectPolicies.relaxedMgmt) { it.copy(relaxedMgmt = false) }
        tryDeduct(GameConfig.PolicyConfig.SPIRIT_SPRING_MONTHLY, "灵泉灌溉",
            data.sectPolicies.spiritSpring) { it.copy(spiritSpring = false) }

        // ── 按弟子数计费 + 周期性消耗 ──（data 为进入本函数时的快照，与逐项禁用顺序语义一致）
        processVariablePolicyCosts(state, data, deductedPolicies, ::tryDeduct)

        return if (disabledPolicies.isNotEmpty()) {
            PolicyCostResult.SomeDisabled(disabledPolicies, deductedPolicies)
        } else {
            PolicyCostResult.AllPaid
        }
    }

    /** 按弟子数计费政策（修行津贴/苦修令/教化之道/仁政爱徒）与周期性消耗（广纳门徒）。 */
    private fun processVariablePolicyCosts(
        state: MutableGameState,
        data: GameData,
        deductedPolicies: List<Pair<String, Long>>,
        tryDeduct: (Long, String, Boolean, (SectPolicies) -> SectPolicies) -> Unit
    ) {
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
            if (currentMonth - data.openRecruitmentLastPaidMonth >= GameConfig.PolicyConfig
                .OPEN_RECRUITMENT_COOLDOWN_MONTHS) {
                tryDeduct(GameConfig.PolicyConfig.OPEN_RECRUITMENT_COST, "广纳门徒",
                    true) { it.copy(openRecruitment = false) }
                // 记录本次付费月份
                if (deductedPolicies.any { it.first == "广纳门徒" }) {
                    state.gameData = state.gameData.copy(openRecruitmentLastPaidMonth = currentMonth)
                }
            }
        }
    }

    /**
     * 政策月度非消耗类效果。
     * 在月度 tick 中 processPolicyCosts 之后调用。
     * - 教化之道：所有弟子道德+1（上限70）
     */
    fun processPolicyMonthlyEffects(state: MutableGameState) {
        val data = state.gameData
        val tables = state.discipleTables

        var moralCount = 0
        // 单次遍历所有活弟子，合并所有政策的道德效果
        for (id in tables.ids) {
            if (tables.isAlive.getOrDefault(id, 0) != 1) continue

            // 道德变化（教化之道）
            if (applyMoralEducationForDisciple(state, tables, id)) moralCount++
        }
        if (data.sectPolicies.moralEducation) {
            DomainLog.d(TAG, "processPolicyMonthlyEffects: moralEducation↑${moralCount}人")
        }
    }

    /**
     * 教化之道单弟子效果：道德低于上限才提升。
     *
     * @return 是否发生了道德提升（用于 moralCount 计数）
     */
    private fun applyMoralEducationForDisciple(
        state: MutableGameState,
        tables: DiscipleTables,
        id: Int
    ): Boolean {
        if (!state.gameData.sectPolicies.moralEducation) return false
        val maxMoral = GameConfig.PolicyConfig.MORAL_EDUCATION_MAX
        /** 当前设备的电源管理配置 */
        val current = tables.moralities.getOrDefault(id, 50)
        if (current >= maxMoral) return false
        val newMoral = (current + GameConfig.PolicyConfig.MORAL_EDUCATION_PER_MONTH).coerceIn(0, maxMoral)
        tables.moralities[id] = newMoral
        return true
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
            miningBonus += miningBonusForSlot(slot, tables)
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
     * 单矿工采矿技能加成：非活弟子或低于阈值矿工零贡献。
     */
    private fun miningBonusForSlot(slot: SpiritMineSlot, tables: DiscipleTables): Double {
        val discipleId = slot.discipleId
        if (discipleId.isEmpty()) return 0.0
        val idInt = discipleId.toIntOrNull() ?: return 0.0
        if (!(tables.ids.contains(idInt) && tables.isAlive[idInt] == 1)) return 0.0
        val mining = tables.minings[idInt] ?: 0
        val threshold = gameConfigProvider.production.spiritMineMiningThreshold
        if (mining <= threshold) return 0.0
        return (mining - threshold) * gameConfigProvider.production.spiritMineMiningBonusRate
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
                guideCounters = state.gameData.guideCounters + (GuideCounterKeys
                    .MINING_OUTPUT to currentCount + totalOutput)
            )
        }
        state.gameData = state.gameData.copy(spiritMineLastSettledMonth = currentMonth)
    }

    companion object {
        /**
         * 单用户定向补偿邮件（MailService 扩展，独立文件）。
         *
         * 拆分原因：MailService 类主体接近 detekt LargeClass（800 行）阈值，
         * 补偿邮件属独立运营配置，放独立文件保持 MailService 规模稳定；
         * stateStore/mailRepo 已放宽为 internal 供本扩展读取（三重防护）。
         */
        private const val TAG = "CultivationSettlement"
        private const val SPIRIT_MINE_BOOST_MULTIPLIER = 1.2
        private const val DEACON_MORALITY_BONUS_RATE = 0.01
    }
}

/** 单弟子年俸发放：列直写俸禄/已发计数 */
private fun payAnnualSalaryToDisciple(
    tables: DiscipleTables,
    idStr: String,
    salary: Long,
    salaryMultiplier: Double
) {
    val id = idStr.toIntOrNull()
    if (id == null || id !in tables.ids || tables.isAlive[id] != 1) return
    val actualSalary = (salary * salaryMultiplier).roundToLong()
    val currentStones = tables.storageBagSpiritStones.getOrDefault(id, 0L)
    tables.storageBagSpiritStones[id] = currentStones + actualSalary
    tables.salaryPaidCounts[id] =
        tables.salaryPaidCounts.getOrDefault(id, 0) + 1
}

