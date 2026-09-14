package com.xianxia.sect.core.usecase

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.checkpointAllDisciples
import com.xianxia.sect.core.engine.checkpointAllProduction
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.ElderSlotType
import com.xianxia.sect.core.model.SectPolicies
import com.xianxia.sect.core.model.guide.GuideCounterKeys
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.nativebridge.ActionIds
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.params
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.nativebridge.StateSyncService
import com.xianxia.sect.core.wallet.SpiritStoneReason
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.core.wallet.DeductResult
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton
import com.xianxia.sect.core.engine.domain.disciple.getPositionEffectBonus

/**
 * 生产类政策字段集（影响炼丹/锻造/灵田速率与成功率）。
 *
 * CLAUDE.md 6.4 🔴 / 13.3 🔴：影响生产系统的政策在开启后**必须**重算所有活跃
 * 生产槽位的 `duration` / `completionMonth` / `successRate`（
 * `ProductionProcessor.recalculateAllCompletionMonths` 同时重算 successRate，
 * 故炼丹/锻造激励亦在列）。灵矿增产（`spiritMineBoost`）不在此列：其产出倍率
 * 变化由 `spiritMineLastSettledMonth` 时间戳差分承担，无需 duration 重算。
 */
private val PRODUCTION_CLASS_POLICY_FIELDS = setOf(
    "alchemyIncentive", "forgeIncentive", "herbCultivation", "spiritSpring"
)

/**
 * 政策开关 native 臂公共门控（batch-18b）。
 *
 * 门控序：AUTHORITATIVE → 镜像服务可空局部判空（handover findings 13：测试
 * mock 未 stub `stateSyncService` 时返回 null，非空参数的内在检查会在函数入口
 * 直接抛 NPE）→ `tryExecuteNative` 转发。返回 true = C++ 已提交且镜像已回读
 * （调用方直接返回，不再执行 Kotlin 原路径）；false = flag 关/未加载/信封失败
 * → 回退 Kotlin 臂（双实现并行契约，回退臂语义与下沉前逐字一致）。
 */
private fun GameEngine.tryNativePolicyTx(
    actionId: Int,
    paramsJson: ByteArray = params {}
): Boolean {
    if (!NativeEngineFlag.authoritative) return false
    val sync: StateSyncService? = stateSyncService
    if (sync == null) return false
    return GameEngineNativeOps.tryExecuteNative(
        stateSyncService = sync,
        actionId = actionId,
        paramsJson = paramsJson
    ) != null
}

/**
 * 宗门政策开关 UseCase。
 *
 * 开启政策时立即扣除首月消耗（扣不起则不给开），
 * 关闭政策仅翻转布尔值+更新引导计数器。
 * 后续月消耗在 [com.xianxia.sect.core.service.CultivationSettlement.processPolicyCosts] 中结算。
 */
@Singleton
class SectPolicyToggleUseCase @Inject constructor(
    internal val gameEngine: GameEngine,
    private val spiritStoneWallet: SpiritStoneWallet
) {
    sealed class ToggleResult {
        data object Success : ToggleResult()
        data class Error(val message: String) : ToggleResult()
    }

    /**
     * 翻转指定的政策开关。
     * 开启时若 [monthlyCost] > 0，先检查余额再扣首月费用。
     *
     * @param field 政策字段名（Kotlin getter/setter 对应字段；C++ 侧按名映射成员
     *   指针）。逐政策显式声明是 13.3 红线的可审计载体——生产类清单据此判定。
     * @param affectsCultivationRate 政策是否影响修炼速率（修行津贴/苦修令/松弛管理）。
     *   为 true 时切换后必须同步 checkpointAllDisciples——检查点只在速率变化点更新
     *   （每旬不无条件同步），否则投影（getEffectiveCultivation）按新速率
     *   高估此前低速期的修为并随存档持久化。
     */
    @Suppress("LongParameterList") // 5 参（含 setter lambda）；拆分传递面已由 applyPolicyToggleFallback 收敛
    internal suspend fun toggle(
        field: String,
        getter: (SectPolicies) -> Boolean,
        setter: (SectPolicies, Boolean) -> SectPolicies,
        monthlyCost: () -> Long = { 0L },
        affectsCultivationRate: Boolean = false
    ): ToggleResult = gameEngine.gameEngineCore.withEngineContext {
        val gd = gameEngine.gameData.value ?: return@withEngineContext ToggleResult.Error("游戏数据不可用")
        val wasEnabled = getter(gd.sectPolicies)
        val cost = monthlyCost()
        // native 臂（batch-18b）：政策置位 + 首月扣费 + 激活计数 + 修炼全量
        // checkpoint，单事务零 RNG。失败信封（含灵石不足）/降级 → Kotlin 原路径
        // 重执行判定链（用户可见文案由 Kotlin 臂产出，与下沉前逐字一致）。
        if (gameEngine.tryNativePolicyTx(
                actionId = ActionIds.GOV_POLICY_TOGGLE_TX,
                paramsJson = params {
                    put("field", field)
                    put("monthlyCost", cost)
                    put("affectsCultivationRate", affectsCultivationRate)
                }
            )
        ) {
            // 🔴 13.3：生产类政策开启后重算生产槽位 duration/successRate。
            // 生产槽位真源在 Kotlin ProductionSlotRepository（C++ 仅在月结窗口
            // 镜像），故 C++ 只回执 productionCheckpointNeeded 标记，动作归此。
            if (!wasEnabled && field in PRODUCTION_CLASS_POLICY_FIELDS) {
                gameEngine.checkpointAllProduction()
            }
            return@withEngineContext ToggleResult.Success
        }
        applyPolicyToggleFallback(
            field = field,
            wasEnabled = wasEnabled,
            cost = cost,
            setter = setter,
            affectsCultivationRate = affectsCultivationRate
        )
    }

    /**
     * 政策开关 Kotlin 回退臂（双实现并行契约：与 C++
     * `gov::policyToggleTx` 逐字同语义——三分支判定序、扣费口径、
     * 激活计数、修炼 checkpoint 与用户可见文案）。
     *
     * 抽为私有辅助函数的原因：① detekt LongMethod（≤60 行）与
     * `rules/code-quality.md` 长函数拆分口径；② native 臂与回退臂可 1:1 对照
     * 审查（下沉对拍主线）。
     *
     * @return 开启付费分支扣费失败时返回 [ToggleResult.Error]（native 臂同序
     *   不会走到此处——其失败信封已触发本臂，故文案唯一）。
     */
    @Suppress("ReturnCount") // 2 return：灵石不足早退 + 末尾 Success（阈值 5）
    private suspend fun applyPolicyToggleFallback(
        field: String,
        wasEnabled: Boolean,
        cost: Long,
        setter: (SectPolicies, Boolean) -> SectPolicies,
        affectsCultivationRate: Boolean
    ): ToggleResult {
        var enabled = false
        if (!wasEnabled) {
            if (cost > 0L) {
                if (!spiritStoneWallet.canAfford(cost)) {
                    return ToggleResult.Error("灵石不足${cost}，无法开启政策")
                }
                gameEngine.stateStore.update {
                    // 快照必须在 deduct 之后取：wallet.deduct 直接写事务态
                    // gameData，先取快照会让 data.copy 把扣费整体覆盖丢失
                    // （batch-18 gate 对拍暴露；与 C++ policyToggleTx 的
                    // "扣费 + 置位同事务"语义对齐）
                    val result = spiritStoneWallet.deduct(this, cost, SpiritStoneGrade.LOW,
                        SpiritStoneReason.PolicyCost, SpiritStoneSource.Internal, true)
                    if (result !is DeductResult.Success) return@update
                    val data = gameData
                    gameData = data.copy(
                        sectPolicies = setter(data.sectPolicies, true),
                        guideCounters = data.guideCounters + (GuideCounterKeys.POLICY_ACTIVATED to
                            ((data.guideCounters[GuideCounterKeys.POLICY_ACTIVATED] ?: 0L) + 1))
                    )
                    enabled = true
                    if (affectsCultivationRate) {
                        discipleTables.checkpointAllDisciples(
                            data.gameYear * 12 + data.gameMonth
                        )
                    }
                }
            } else {
                gameEngine.stateStore.update {
                    gameData = gameData.copy(
                        sectPolicies = setter(gameData.sectPolicies, true),
                        guideCounters = gameData.guideCounters + (GuideCounterKeys.POLICY_ACTIVATED to
                            ((gameData.guideCounters[GuideCounterKeys.POLICY_ACTIVATED] ?: 0L) + 1))
                    )
                    enabled = true
                    if (affectsCultivationRate) {
                        discipleTables.checkpointAllDisciples(
                            gameData.gameYear * 12 + gameData.gameMonth
                        )
                    }
                }
            }
        } else {
            gameEngine.stateStore.update {
                gameData = gameData.copy(sectPolicies = setter(gameData.sectPolicies, false))
                if (affectsCultivationRate) {
                    discipleTables.checkpointAllDisciples(
                        gameData.gameYear * 12 + gameData.gameMonth
                    )
                }
            }
        }
        // 🔴 13.3：回退臂与 native 臂同语义——生产类政策开启后重算生产槽位
        if (enabled && field in PRODUCTION_CLASS_POLICY_FIELDS) {
            gameEngine.checkpointAllProduction()
        }
        return ToggleResult.Success
    }

    // ── 灵矿增产（免费） ────────────────────────────
    suspend fun toggleSpiritMineBoost(): ToggleResult = gameEngine.gameEngineCore.withEngineContext {
        // native 臂（batch-18b）：置位 + 激活计数 + 结算月戳推前（差分结算语义：
        // 防开启前时长被新倍率追认）。失败/降级 → Kotlin 原路径。
        // 非生产类：灵矿产出倍率由 spiritMineLastSettledMonth 差分承担，
        // 无生产槽位 duration 重算（13.3 清单外）。
        if (gameEngine.tryNativePolicyTx(ActionIds.GOV_SPIRIT_MINE_BOOST_TOGGLE_TX)) {
            return@withEngineContext ToggleResult.Success
        }
        gameEngine.stateStore.update {
            val gd = gameData
            val wasEnabled = gd.sectPolicies.spiritMineBoost
            val newVal = !wasEnabled
            gameData = gd.copy(
                sectPolicies = gd.sectPolicies.copy(spiritMineBoost = newVal),
                guideCounters = if (!wasEnabled)
                    gd.guideCounters + (GuideCounterKeys.POLICY_ACTIVATED to ((gd.guideCounters[GuideCounterKeys
                        .POLICY_ACTIVATED] ?: 0L) + 1))
                else gd.guideCounters,
                spiritMineLastSettledMonth = if (!wasEnabled && gd.gameYear * 12 + gd.gameMonth > gd
                    .spiritMineLastSettledMonth)
                    gd.gameYear * 12 + gd.gameMonth else gd.spiritMineLastSettledMonth
            )
        }
        ToggleResult.Success
    }
    fun isSpiritMineBoostEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.spiritMineBoost ?: false

    // ── 按弟子数计费政策 ──
    private fun countTotalDisciples(): Int {
        val tables = gameEngine.discipleTables
        return tables.ids.count { id -> tables.isAlive.getOrDefault(id, 0) == 1 }
    }
    private fun countHuashenBelow(): Int {
        val tables = gameEngine.discipleTables
        return tables.ids.count { id ->
            tables.isAlive.getOrDefault(id, 0) == 1 &&
                tables.realms.getOrDefault(id, 9) > 5
        }
    }

    suspend fun toggleCultivationSubsidy() = toggle(
        field = "cultivationSubsidy",
        getter = { it.cultivationSubsidy }, setter = { p, v -> p.copy(cultivationSubsidy = v) },
        monthlyCost = { GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_PER_DISCIPLE * countHuashenBelow() },
        affectsCultivationRate = true  // 化神下弟子修炼速度 +15%
    )
    fun isCultivationSubsidyEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.cultivationSubsidy ?: false

    suspend fun toggleAsceticTraining() = toggle(
        field = "asceticTraining",
        getter = { it.asceticTraining }, setter = { p, v -> p.copy(asceticTraining = v) },
        monthlyCost = { GameConfig.PolicyConfig.ASCETIC_TRAINING_PER_DISCIPLE * countTotalDisciples() },
        affectsCultivationRate = true  // 全体修炼速度 +25%
    )
    fun isAsceticTrainingEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.asceticTraining ?: false

    suspend fun toggleMoralEducation() = toggle(
        field = "moralEducation",
        getter = { it.moralEducation }, setter = { p, v -> p.copy(moralEducation = v) },
        monthlyCost = { GameConfig.PolicyConfig.MORAL_EDUCATION_PER_DISCIPLE * countTotalDisciples() }
    )
    fun isMoralEducationEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.moralEducation ?: false

    suspend fun toggleBenevolentGovernance() = toggle(
        field = "benevolentGovernance",
        getter = { it.benevolentGovernance }, setter = { p, v -> p.copy(benevolentGovernance = v) },
        monthlyCost = { GameConfig.PolicyConfig.BENEVOLENT_GOVERNANCE_PER_DISCIPLE * countTotalDisciples() }
    )
    fun isBenevolentGovernanceEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.benevolentGovernance ?: false

    // ── 周期性消耗 ──
    suspend fun toggleOpenRecruitment(): ToggleResult = gameEngine.gameEngineCore.withEngineContext {
        val gd = gameEngine.gameData.value ?: return@withEngineContext ToggleResult.Error("游戏数据不可用")
        val wasEnabled = gd.sectPolicies.openRecruitment
        // native 臂（batch-18b）：固定费用扣款 + 置位 + 激活计数 + 付费月记录。
        // 失败信封（含灵石不足）/降级 → Kotlin 原路径重执行（含 canAfford 判定与
        // 用户可见文案）。非生产类：招募月消耗不涉及生产槽位时长（13.3 清单外）。
        if (gameEngine.tryNativePolicyTx(ActionIds.GOV_OPEN_RECRUITMENT_TOGGLE_TX)) {
            return@withEngineContext ToggleResult.Success
        }
        if (!wasEnabled) {
            val cost = GameConfig.PolicyConfig.OPEN_RECRUITMENT_COST
            if (!spiritStoneWallet.canAfford(cost)) {
                return@withEngineContext ToggleResult.Error("灵石不足${cost}，无法开启广纳门徒")
            }
            val currentMonth = gd.gameYear * 12 + gd.gameMonth
            gameEngine.stateStore.update {
                // 快照在 deduct 之后取（同 applyPolicyToggleFallback：先取会
                // 覆盖丢失扣费，batch-18 gate 对拍暴露；对齐 C++ 原子语义）
                val result = spiritStoneWallet.deduct(this, cost, SpiritStoneGrade.LOW,
                    SpiritStoneReason.PolicyCost, SpiritStoneSource.Internal, true)
                if (result !is DeductResult.Success) return@update
                val data = gameData
                gameData = data.copy(
                    sectPolicies = data.sectPolicies.copy(openRecruitment = true),
                    guideCounters = data.guideCounters + (GuideCounterKeys.POLICY_ACTIVATED to
                        ((data.guideCounters[GuideCounterKeys.POLICY_ACTIVATED] ?: 0L) + 1)),
                    openRecruitmentLastPaidMonth = currentMonth
                )
            }
        } else {
            gameEngine.stateStore.update {
                gameData = gameData.copy(sectPolicies = gameData.sectPolicies.copy(openRecruitment = false))
            }
        }
        ToggleResult.Success
    }
    fun isOpenRecruitmentEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.openRecruitment ?: false

    // ── 副宗主智力加成（保留，非政策特有） ──
    fun getViceSectMasterIntelligenceBonus(viceSectMasterIntelligence: Int): Double {
        val baseIntelligence = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_BASE
        val step = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_STEP
        val bonusPerStep = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_BONUS_PER_STEP
        return ((viceSectMasterIntelligence - baseIntelligence) / step.toDouble() * bonusPerStep).coerceAtLeast(0.0)
    }

    /**
     * 副宗主智力加成（含 PositionBonus 乘算因子）。
     *
     * @param viceSectMaster 副宗主弟子（含天赋/词条中的职务加成）
     * @return 加成值 = 基础智力加成 × (1 + PositionBonus)
     */
    fun getViceSectMasterIntelligenceBonus(viceSectMaster: Disciple): Double {
        val baseBonus = getViceSectMasterIntelligenceBonus(viceSectMaster.skills.intelligence)
        val posBonus = DiscipleStatCalculator.getPositionEffectBonus(viceSectMaster, ElderSlotType.VICE_SECT_MASTER)
        return baseBonus * (1.0 + posBonus)
    }

    /** 副宗主智力加成（DiscipleAggregate 重载，含 PositionBonus） */
    fun getViceSectMasterIntelligenceBonus(viceSectMaster: DiscipleAggregate): Double {
        val baseBonus = getViceSectMasterIntelligenceBonus(viceSectMaster.intelligence)
        val posBonus = DiscipleStatCalculator.getPositionEffectBonus(viceSectMaster, ElderSlotType.VICE_SECT_MASTER)
        return baseBonus * (1.0 + posBonus)
    }
}
