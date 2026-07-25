package com.xianxia.sect.core.usecase

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.*
import com.xianxia.sect.core.model.SectPolicies
import com.xianxia.sect.core.model.guide.GuideCounterKeys
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.wallet.SpiritStoneReason
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.core.wallet.DeductResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 宗门政策开关 UseCase。
 *
 * 开启政策时立即扣除首月消耗（扣不起则不给开），
 * 关闭政策仅翻转布尔值+更新引导计数器。
 * 后续月消耗在 [com.xianxia.sect.core.service.CultivationSettlement.processPolicyCosts] 中结算。
 */
@Singleton
class SectPolicyToggleUseCase @Inject constructor(
    private val gameEngine: GameEngine,
    private val spiritStoneWallet: SpiritStoneWallet
) {
    sealed class ToggleResult {
        data object Success : ToggleResult()
        data class Error(val message: String) : ToggleResult()
    }

    /**
     * 翻转指定的政策开关。
     * 开启时若 [monthlyCost] > 0，先检查余额再扣首月费用。
     */
    private suspend fun toggle(
        getter: (SectPolicies) -> Boolean,
        setter: (SectPolicies, Boolean) -> SectPolicies,
        monthlyCost: () -> Long = { 0L }
    ): ToggleResult = gameEngine.gameEngineCore.withEngineContext {
        val gd = gameEngine.gameData.value ?: return@withEngineContext ToggleResult.Error("游戏数据不可用")
        val wasEnabled = getter(gd.sectPolicies)
        if (!wasEnabled) {
            val cost = monthlyCost()
            if (cost > 0L) {
                if (!spiritStoneWallet.canAfford(cost)) {
                    return@withEngineContext ToggleResult.Error("灵石不足${cost}，无法开启政策")
                }
                gameEngine.stateStore.update {
                    val data = gameData
                    val result = spiritStoneWallet.deduct(this, cost, SpiritStoneGrade.LOW,
                        SpiritStoneReason.PolicyCost, SpiritStoneSource.Internal, true)
                    if (result !is DeductResult.Success) return@update
                    gameData = data.copy(
                        sectPolicies = setter(data.sectPolicies, true),
                        guideCounters = data.guideCounters + (GuideCounterKeys.POLICY_ACTIVATED to
                            ((data.guideCounters[GuideCounterKeys.POLICY_ACTIVATED] ?: 0L) + 1))
                    )
                }
            } else {
                gameEngine.stateStore.update {
                    gameData = gameData.copy(
                        sectPolicies = setter(gameData.sectPolicies, true),
                        guideCounters = gameData.guideCounters + (GuideCounterKeys.POLICY_ACTIVATED to
                            ((gameData.guideCounters[GuideCounterKeys.POLICY_ACTIVATED] ?: 0L) + 1))
                    )
                }
            }
        } else {
            gameEngine.stateStore.update {
                gameData = gameData.copy(sectPolicies = setter(gameData.sectPolicies, false))
            }
        }
        ToggleResult.Success
    }

    // ── 灵矿增产（免费） ────────────────────────────
    suspend fun toggleSpiritMineBoost(): ToggleResult = gameEngine.gameEngineCore.withEngineContext {
        gameEngine.stateStore.update {
            val gd = gameData
            val wasEnabled = gd.sectPolicies.spiritMineBoost
            val newVal = !wasEnabled
            gameData = gd.copy(
                sectPolicies = gd.sectPolicies.copy(spiritMineBoost = newVal),
                guideCounters = if (!wasEnabled)
                    gd.guideCounters + (GuideCounterKeys.POLICY_ACTIVATED to ((gd.guideCounters[GuideCounterKeys.POLICY_ACTIVATED] ?: 0L) + 1))
                else gd.guideCounters,
                spiritMineLastSettledMonth = if (!wasEnabled && gd.gameYear * 12 + gd.gameMonth > gd.spiritMineLastSettledMonth)
                    gd.gameYear * 12 + gd.gameMonth else gd.spiritMineLastSettledMonth
            )
        }
        ToggleResult.Success
    }
    fun isSpiritMineBoostEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.spiritMineBoost ?: false

    // ── 免费政策（开关无消耗） ──
    suspend fun toggleFrugality() = toggle(
        getter = { it.frugality }, setter = { p, v -> p.copy(frugality = v) }
    )
    fun isFrugalityEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.frugality ?: false

    // ── 固定月消耗政策 ──
    suspend fun toggleEnhancedSecurity() = toggle(
        getter = { it.enhancedSecurity }, setter = { p, v -> p.copy(enhancedSecurity = v) },
        monthlyCost = { GameConfig.PolicyConfig.ENHANCED_SECURITY_MONTHLY }
    )
    fun isEnhancedSecurityEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.enhancedSecurity ?: false

    suspend fun toggleAlchemyIncentive() = toggle(
        getter = { it.alchemyIncentive }, setter = { p, v -> p.copy(alchemyIncentive = v) },
        monthlyCost = { GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_MONTHLY }
    )
    fun isAlchemyIncentiveEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.alchemyIncentive ?: false

    suspend fun toggleForgeIncentive() = toggle(
        getter = { it.forgeIncentive }, setter = { p, v -> p.copy(forgeIncentive = v) },
        monthlyCost = { GameConfig.PolicyConfig.FORGE_INCENTIVE_MONTHLY }
    )
    fun isForgeIncentiveEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.forgeIncentive ?: false

    suspend fun toggleHerbCultivation() = toggle(
        getter = { it.herbCultivation }, setter = { p, v -> p.copy(herbCultivation = v) },
        monthlyCost = { GameConfig.PolicyConfig.HERB_CULTIVATION_MONTHLY }
    )
    fun isHerbCultivationEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.herbCultivation ?: false

    suspend fun toggleManualResearch() = toggle(
        getter = { it.manualResearch }, setter = { p, v -> p.copy(manualResearch = v) },
        monthlyCost = { GameConfig.PolicyConfig.MANUAL_RESEARCH_MONTHLY }
    )
    fun isManualResearchEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.manualResearch ?: false

    suspend fun toggleCurfew() = toggle(
        getter = { it.curfew }, setter = { p, v -> p.copy(curfew = v) },
        monthlyCost = { GameConfig.PolicyConfig.CURFEW_MONTHLY }
    )
    fun isCurfewEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.curfew ?: false

    suspend fun toggleRewardPunish() = toggle(
        getter = { it.rewardPunish }, setter = { p, v -> p.copy(rewardPunish = v) },
        monthlyCost = { GameConfig.PolicyConfig.REWARD_PUNISH_MONTHLY }
    )
    fun isRewardPunishEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.rewardPunish ?: false

    suspend fun toggleStrictTraining() = toggle(
        getter = { it.strictTraining }, setter = { p, v -> p.copy(strictTraining = v) },
        monthlyCost = { GameConfig.PolicyConfig.STRICT_TRAINING_MONTHLY }
    )
    fun isStrictTrainingEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.strictTraining ?: false

    suspend fun toggleRelaxedMgmt() = toggle(
        getter = { it.relaxedMgmt }, setter = { p, v -> p.copy(relaxedMgmt = v) },
        monthlyCost = { GameConfig.PolicyConfig.RELAXED_MGMT_MONTHLY }
    )
    fun isRelaxedMgmtEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.relaxedMgmt ?: false

    suspend fun toggleSpiritSpring() = toggle(
        getter = { it.spiritSpring }, setter = { p, v -> p.copy(spiritSpring = v) },
        monthlyCost = { GameConfig.PolicyConfig.SPIRIT_SPRING_MONTHLY }
    )
    fun isSpiritSpringEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.spiritSpring ?: false

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
        getter = { it.cultivationSubsidy }, setter = { p, v -> p.copy(cultivationSubsidy = v) },
        monthlyCost = { GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_PER_DISCIPLE * countHuashenBelow() }
    )
    fun isCultivationSubsidyEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.cultivationSubsidy ?: false

    suspend fun toggleAsceticTraining() = toggle(
        getter = { it.asceticTraining }, setter = { p, v -> p.copy(asceticTraining = v) },
        monthlyCost = { GameConfig.PolicyConfig.ASCETIC_TRAINING_PER_DISCIPLE * countTotalDisciples() }
    )
    fun isAsceticTrainingEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.asceticTraining ?: false

    suspend fun toggleMoralEducation() = toggle(
        getter = { it.moralEducation }, setter = { p, v -> p.copy(moralEducation = v) },
        monthlyCost = { GameConfig.PolicyConfig.MORAL_EDUCATION_PER_DISCIPLE * countTotalDisciples() }
    )
    fun isMoralEducationEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.moralEducation ?: false

    suspend fun toggleBenevolentGovernance() = toggle(
        getter = { it.benevolentGovernance }, setter = { p, v -> p.copy(benevolentGovernance = v) },
        monthlyCost = { GameConfig.PolicyConfig.BENEVOLENT_GOVERNANCE_PER_DISCIPLE * countTotalDisciples() }
    )
    fun isBenevolentGovernanceEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.benevolentGovernance ?: false

    // ── 周期性消耗 ──
    suspend fun toggleOpenRecruitment(): ToggleResult = gameEngine.gameEngineCore.withEngineContext {
        val gd = gameEngine.gameData.value ?: return@withEngineContext ToggleResult.Error("游戏数据不可用")
        val wasEnabled = gd.sectPolicies.openRecruitment
        if (!wasEnabled) {
            val cost = GameConfig.PolicyConfig.OPEN_RECRUITMENT_COST
            if (!spiritStoneWallet.canAfford(cost)) {
                return@withEngineContext ToggleResult.Error("灵石不足${cost}，无法开启广纳门徒")
            }
            val currentMonth = gd.gameYear * 12 + gd.gameMonth
            gameEngine.stateStore.update {
                val data = gameData
                val result = spiritStoneWallet.deduct(this, cost, SpiritStoneGrade.LOW,
                    SpiritStoneReason.PolicyCost, SpiritStoneSource.Internal, true)
                if (result !is DeductResult.Success) return@update
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
}
