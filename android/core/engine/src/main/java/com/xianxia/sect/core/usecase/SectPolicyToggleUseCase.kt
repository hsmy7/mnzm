package com.xianxia.sect.core.usecase

import com.xianxia.sect.core.engine.*
import com.xianxia.sect.core.model.SectPolicies
import com.xianxia.sect.core.model.guide.GuideCounterKeys
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 宗门政策开关 UseCase。
 *
 * 所有政策的开启/关闭操作，无需检查灵石消耗（开启消耗已移除），
 * 仅翻转布尔值并更新引导计数器。
 * 月消耗在 [com.xianxia.sect.core.service.CultivationSettlement.processPolicyCosts] 中结算。
 */
@Singleton
class SectPolicyToggleUseCase @Inject constructor(
    private val gameEngine: GameEngine
) {
    sealed class ToggleResult {
        data object Success : ToggleResult()
        data class Error(val message: String) : ToggleResult()
    }

    /**
     * 翻转指定的政策开关。
     * @param getter 从 SectPolicies 读取当前值
     * @param setter 创建 SectPolicies 新副本
     */
    private suspend fun toggle(
        getter: (SectPolicies) -> Boolean,
        setter: (SectPolicies, Boolean) -> SectPolicies
    ): ToggleResult {
        gameEngine.stateStore.update {
            val gd = gameData
            val wasEnabled = getter(gd.sectPolicies)
            gameData = gd.copy(
                sectPolicies = setter(gd.sectPolicies, !wasEnabled),
                guideCounters = if (!wasEnabled)
                    gd.guideCounters + (GuideCounterKeys.POLICY_ACTIVATED to
                        ((gd.guideCounters[GuideCounterKeys.POLICY_ACTIVATED] ?: 0L) + 1))
                else gd.guideCounters
            )
        }
        return ToggleResult.Success
    }

    // ── 灵矿增产 ──────────────────────────────────
    suspend fun toggleSpiritMineBoost(): ToggleResult {
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
        return ToggleResult.Success
    }
    fun isSpiritMineBoostEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.spiritMineBoost ?: false

    // ── 增强治安 ──────────────────────────────────
    suspend fun toggleEnhancedSecurity() = toggle(
        getter = { it.enhancedSecurity },
        setter = { p, v -> p.copy(enhancedSecurity = v) }
    )
    fun isEnhancedSecurityEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.enhancedSecurity ?: false

    // ── 丹道激励 ──────────────────────────────────
    suspend fun toggleAlchemyIncentive() = toggle(
        getter = { it.alchemyIncentive },
        setter = { p, v -> p.copy(alchemyIncentive = v) }
    )
    fun isAlchemyIncentiveEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.alchemyIncentive ?: false

    // ── 锻造激励 ──────────────────────────────────
    suspend fun toggleForgeIncentive() = toggle(
        getter = { it.forgeIncentive },
        setter = { p, v -> p.copy(forgeIncentive = v) }
    )
    fun isForgeIncentiveEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.forgeIncentive ?: false

    // ── 灵药培育 ──────────────────────────────────
    suspend fun toggleHerbCultivation() = toggle(
        getter = { it.herbCultivation },
        setter = { p, v -> p.copy(herbCultivation = v) }
    )
    fun isHerbCultivationEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.herbCultivation ?: false

    // ── 修行津贴 ──────────────────────────────────
    suspend fun toggleCultivationSubsidy() = toggle(
        getter = { it.cultivationSubsidy },
        setter = { p, v -> p.copy(cultivationSubsidy = v) }
    )
    fun isCultivationSubsidyEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.cultivationSubsidy ?: false

    // ── 功法研习 ──────────────────────────────────
    suspend fun toggleManualResearch() = toggle(
        getter = { it.manualResearch },
        setter = { p, v -> p.copy(manualResearch = v) }
    )
    fun isManualResearchEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.manualResearch ?: false

    // ── 新增政策 ──────────────────────────────────

    // 广纳门徒（关闭时重置冷却月份，防止反复开关逃费）
    suspend fun toggleOpenRecruitment(): ToggleResult {
        gameEngine.stateStore.update {
            val gd = gameData
            val wasEnabled = gd.sectPolicies.openRecruitment
            val currentMonth = gd.gameYear * 12 + gd.gameMonth
            gameData = gd.copy(
                sectPolicies = gd.sectPolicies.copy(openRecruitment = !wasEnabled),
                guideCounters = if (!wasEnabled)
                    gd.guideCounters + (GuideCounterKeys.POLICY_ACTIVATED to ((gd.guideCounters[GuideCounterKeys.POLICY_ACTIVATED] ?: 0L) + 1))
                else gd.guideCounters,
                // 不论开/关都重置冷却：开启时开始计时，关闭时防止重开逃费
                openRecruitmentLastPaidMonth = currentMonth
            )
        }
        return ToggleResult.Success
    }
    fun isOpenRecruitmentEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.openRecruitment ?: false

    // 苦修令
    suspend fun toggleAsceticTraining() = toggle(
        getter = { it.asceticTraining },
        setter = { p, v -> p.copy(asceticTraining = v) }
    )
    fun isAsceticTrainingEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.asceticTraining ?: false

    // 宵禁
    suspend fun toggleCurfew() = toggle(
        getter = { it.curfew },
        setter = { p, v -> p.copy(curfew = v) }
    )
    fun isCurfewEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.curfew ?: false

    // 赏善罚恶
    suspend fun toggleRewardPunish() = toggle(
        getter = { it.rewardPunish },
        setter = { p, v -> p.copy(rewardPunish = v) }
    )
    fun isRewardPunishEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.rewardPunish ?: false

    // 严苛训练
    suspend fun toggleStrictTraining() = toggle(
        getter = { it.strictTraining },
        setter = { p, v -> p.copy(strictTraining = v) }
    )
    fun isStrictTrainingEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.strictTraining ?: false

    // 松弛管理
    suspend fun toggleRelaxedMgmt() = toggle(
        getter = { it.relaxedMgmt },
        setter = { p, v -> p.copy(relaxedMgmt = v) }
    )
    fun isRelaxedMgmtEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.relaxedMgmt ?: false

    // 灵泉灌溉
    suspend fun toggleSpiritSpring() = toggle(
        getter = { it.spiritSpring },
        setter = { p, v -> p.copy(spiritSpring = v) }
    )
    fun isSpiritSpringEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.spiritSpring ?: false

    // 开源节流
    suspend fun toggleFrugality() = toggle(
        getter = { it.frugality },
        setter = { p, v -> p.copy(frugality = v) }
    )
    fun isFrugalityEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.frugality ?: false

    // 教化之道
    suspend fun toggleMoralEducation() = toggle(
        getter = { it.moralEducation },
        setter = { p, v -> p.copy(moralEducation = v) }
    )
    fun isMoralEducationEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.moralEducation ?: false

    // 仁政爱徒
    suspend fun toggleBenevolentGovernance() = toggle(
        getter = { it.benevolentGovernance },
        setter = { p, v -> p.copy(benevolentGovernance = v) }
    )
    fun isBenevolentGovernanceEnabled(): Boolean =
        gameEngine.gameData.value?.sectPolicies?.benevolentGovernance ?: false

    // ── 副宗主智力加成（保留，非政策特有） ──
    fun getViceSectMasterIntelligenceBonus(viceSectMasterIntelligence: Int): Double {
        val baseIntelligence = com.xianxia.sect.core.GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_BASE
        val step = com.xianxia.sect.core.GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_STEP
        val bonusPerStep = com.xianxia.sect.core.GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_BONUS_PER_STEP
        return ((viceSectMasterIntelligence - baseIntelligence) / step.toDouble() * bonusPerStep).coerceAtLeast(0.0)
    }
}
