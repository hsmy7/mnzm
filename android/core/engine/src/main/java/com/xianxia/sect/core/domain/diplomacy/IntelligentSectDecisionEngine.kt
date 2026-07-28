package com.xianxia.sect.core.engine.domain.diplomacy

import com.xianxia.sect.core.config.SectDecisionConfig
import com.xianxia.sect.core.model.AISectPersonality
import com.xianxia.sect.core.model.SectRelationLevel

/**
 * 决策配置文件：定义特定决策类型使用的权重和阈值。
 *
 * 每种决策类型（攻击/结盟/附属）使用相同的四因素评估框架，
 * 但通过 [DecisionProfile] 配置独立的权重、阈值和好感度等级分值来实现差异化行为。
 *
 * @param powerWeight 战力差权重，最终概率中战力因素的占比
 * @param occupyWeight 占领丢失权重，宗门征服/丢失的比例贡献
 * @param skirmishWeight 胜负权重，战斗胜率的贡献
 * @param favorWeight 好感度权重，双边关系的贡献
 * @param maxChance 概率上限，防止极端情况
 * @param powerHardThreshold 战力硬门槛：
 *   - 攻击场景：低于此值不攻击（实力不够）
 *   - 结盟场景：低于此值不结盟（太弱不值得结盟）
 * @param favorScoreByLevel 好感度等级→分值映射（等级贡献分数 * 权重 = 最终好感度得分）
 *   等级分 0.0 且权重 > 0 时直接返回 0（该等级不可行）
 * @param personalityEffect 个性修正因子函数，接收(基础概率, AI个性) → 修正后概率
 */
data class DecisionProfile(
    val powerWeight: Double,
    val occupyWeight: Double,
    val skirmishWeight: Double,
    val favorWeight: Double,
    val maxChance: Double,
    val powerHardThreshold: Double,
    val favorScoreByLevel: Map<SectRelationLevel, Double>,
    val personalityEffect: (Double, AISectPersonality) -> Double = { chance, _ -> chance }
) {
    init {
        require(powerWeight >= 0.0) { "powerWeight must be non-negative" }
        require(occupyWeight >= 0.0) { "occupyWeight must be non-negative" }
        require(skirmishWeight >= 0.0) { "skirmishWeight must be non-negative" }
        require(favorWeight >= 0.0) { "favorWeight must be non-negative" }
        require(maxChance in 0.0..1.0) { "maxChance must be in [0, 1]" }
        require(favorScoreByLevel.size == SectRelationLevel.entries.size) {
            "favorScoreByLevel must contain all ${SectRelationLevel.entries.size} levels"
        }
        require(favorScoreByLevel.values.all { it in 0.0..1.0 }) {
            "favor scores must be in [0, 1]"
        }
    }
}

/**
 * AI 智能判定引擎 — 纯函数，无状态，无注入依赖。
 *
 * ### 核心算法：四因素加权概率模型
 *
 * 从 [VassalService] 的附属判定模型提取为共享引擎，统一供以下场景使用：
 * - **攻击判定**：AI 决定是否攻击另一宗门（AI vs AI / AI vs 玩家）
 * - **结盟判定**：AI 决定是否接受/发起结盟
 * - **附属判定**：AI 决定是否接受成为附属 / 脱离附属
 *
 * 每种场景通过 [DecisionProfile] 配置独立的权重和阈值。
 *
 * ### 四个因素
 * 1. **战力差 (Power)** — 通过战力比分档（5x/3x/2x/1.5x）映射到分值
 * 2. **占领丢失 (Occupy)** — 征服次数与丢失次数的比例
 * 3. **胜负 (Skirmish)** — 胜利次数与总战斗次数的比例
 * 4. **好感度 (Favor)** — 按 [SectRelationLevel] 五级分档，每档有固定分值
 *
 * ### 额外特性
 * - NaN/Infinity 防御
 * - 硬门槛拦截（战力不达标时直接返回 0）
 * - 好感度等级分值为 0 时直接返回 0（该等级不可行）
 * - AI 个性修正（通过 [DecisionProfile.personalityEffect]）
 */
object IntelligentSectDecisionEngine {

    // ═══════════════════════════
    // 预定义的决策配置文件
    // ═══════════════════════════

    /** 攻击判定配置：战力差和胜负权重高，好感度等级 FRIENDLY/INTIMATE 不攻击 */
    val ATTACK_PROFILE = DecisionProfile(
        powerWeight = SectDecisionConfig.Attack.POWER_WEIGHT,
        occupyWeight = SectDecisionConfig.Attack.OCCUPY_WEIGHT,
        skirmishWeight = SectDecisionConfig.Attack.SKIRMISH_WEIGHT,
        favorWeight = SectDecisionConfig.Attack.FAVOR_WEIGHT,
        maxChance = SectDecisionConfig.Attack.MAX_CHANCE,
        powerHardThreshold = SectDecisionConfig.Attack.POWER_HARD_THRESHOLD,
        favorScoreByLevel = SectDecisionConfig.ATTACK_FAVOR_SCORE,
        personalityEffect = { chance, personality ->
            val factor = when (personality) {
                AISectPersonality.AGGRESSIVE -> SectDecisionConfig.PersonalityModifiers.AGGRESSIVE_ATTACK
                AISectPersonality.BALANCED -> SectDecisionConfig.PersonalityModifiers.BALANCED_ATTACK
                AISectPersonality.CONSERVATIVE -> SectDecisionConfig.PersonalityModifiers.CONSERVATIVE_ATTACK
                AISectPersonality.RECLUSIVE -> SectDecisionConfig.PersonalityModifiers.RECLUSIVE_ATTACK
            }
            (chance * factor).coerceIn(0.0, 1.0)
        }
    )

    /** 结盟判定配置：好感度权重最高，战力门槛要求势均力敌 */
    val ALLIANCE_PROFILE = DecisionProfile(
        powerWeight = SectDecisionConfig.Alliance.POWER_WEIGHT,
        occupyWeight = SectDecisionConfig.Alliance.OCCUPY_WEIGHT,
        skirmishWeight = SectDecisionConfig.Alliance.SKIRMISH_WEIGHT,
        favorWeight = SectDecisionConfig.Alliance.FAVOR_WEIGHT,
        maxChance = SectDecisionConfig.Alliance.MAX_CHANCE,
        powerHardThreshold = SectDecisionConfig.Alliance.POWER_HARD_THRESHOLD,
        favorScoreByLevel = SectDecisionConfig.ALLIANCE_FAVOR_SCORE,
        personalityEffect = { chance, personality ->
            val factor = when (personality) {
                AISectPersonality.AGGRESSIVE -> SectDecisionConfig.PersonalityModifiers.AGGRESSIVE_ALLIANCE
                AISectPersonality.BALANCED -> SectDecisionConfig.PersonalityModifiers.BALANCED_ALLIANCE
                AISectPersonality.CONSERVATIVE -> SectDecisionConfig.PersonalityModifiers.CONSERVATIVE_ALLIANCE
                AISectPersonality.RECLUSIVE -> SectDecisionConfig.PersonalityModifiers.RECLUSIVE_ALLIANCE
            }
            (chance * factor).coerceIn(0.0, 1.0)
        }
    )

    /** 附属判定配置：使用等级分值映射 */
    val VASSAL_PROFILE = DecisionProfile(
        powerWeight = SectDecisionConfig.Vassal.POWER_WEIGHT,
        occupyWeight = SectDecisionConfig.Vassal.OCCUPY_WEIGHT,
        skirmishWeight = SectDecisionConfig.Vassal.SKIRMISH_WEIGHT,
        favorWeight = SectDecisionConfig.Vassal.FAVOR_WEIGHT,
        maxChance = SectDecisionConfig.Vassal.MAX_CHANCE,
        powerHardThreshold = SectDecisionConfig.Vassal.POWER_HARD_THRESHOLD,
        favorScoreByLevel = SectDecisionConfig.VASSAL_FAVOR_SCORE
        // 附属判断不受 AI 个性影响（玩家单方面要求）
    )

    // ═══════════════════════════
    // 公开 API
    // ═══════════════════════════

    /**
     * 四因素加权判定概率计算（纯函数）。
     *
     * 计算流程：
     * 1. NaN/Infinity 防御
     * 2. 战力硬门槛检查
     * 3. 好感度等级检查（分值为 0 且有权重 → 直接返回 0）
     * 4. 四因素加权评分
     * 5. 个性修正
     * 6. 结果裁剪到 [0, maxChance]
     *
     * @param profile 决策配置文件
     * @param powerRatio 战力比 (攻击方/防御方 或 玩家/AI，依场景而定)
     * @param conquestCount 征服次数（近 3 年）
     * @param lostSectCount 丢失宗门次数（近 3 年）
     * @param battleWinCount 战斗胜利次数（近 3 年）
     * @param battleLossCount 战斗失败次数（近 3 年）
     * @param favorLevel 好感度等级（五级分档）
     * @param personality AI 个性（可选，不传则无个性修正）
     * @return 判定概率 (0.0 - maxChance)，硬门槛不满足时返回 0.0
     */
    fun calculateChance(
        profile: DecisionProfile,
        powerRatio: Double,
        conquestCount: Int,
        lostSectCount: Int,
        battleWinCount: Int,
        battleLossCount: Int,
        favorLevel: SectRelationLevel,
        personality: AISectPersonality? = null
    ): Double {
        // ── 1. NaN/Infinity 防御 ──
        if (powerRatio.isNaN() || powerRatio.isInfinite()) return 0.0

        // ── 2. 战力硬门槛检查 ──
        if (powerRatio < profile.powerHardThreshold) return 0.0

        // ── 3. 好感度等级检查 ──
        // 等级分值为 0 且该场景给好感度分配了权重 → 该等级不可行
        val favorMultiplier = profile.favorScoreByLevel[favorLevel] ?: 0.0
        if (favorMultiplier == 0.0 && profile.favorWeight > 0.0) return 0.0

        // ── 4. 四因素加权评分 ──
        val powerScore = calculatePowerScore(powerRatio, profile.powerWeight)

        val totalOccupy = maxOf(conquestCount, 0) + maxOf(lostSectCount, 0)
        val occupyScore = if (totalOccupy > 0) {
            (maxOf(conquestCount, 0).toDouble() / totalOccupy) * profile.occupyWeight
        } else 0.0

        val totalSkirmish = maxOf(battleWinCount, 0) + maxOf(battleLossCount, 0)
        val skirmishScore = if (totalSkirmish > 0) {
            (maxOf(battleWinCount, 0).toDouble() / totalSkirmish) * profile.skirmishWeight
        } else 0.0

        val favorScore = favorMultiplier * profile.favorWeight

        var finalChance = (powerScore + occupyScore + skirmishScore + favorScore)
            .coerceIn(0.0, profile.maxChance)

        // ── 5. 个性修正 ──
        if (personality != null) {
            finalChance = profile.personalityEffect(finalChance, personality)
                .coerceIn(0.0, profile.maxChance)
        }

        return finalChance
    }

    /**
     * 附属脱离概率计算（纯函数）。
     *
     * 与 [calculateChance] 逻辑反向：
     * - 战力优势越小 → 脱离概率越高
     * - 丢失比例越高 → 脱离概率越高
     * - 失败比例越高 → 脱离概率越高
     * - 好感度等级越低 → 脱离概率越高（使用 [SectDecisionConfig.BREAKAWAY_FAVOR_SCORE] 分值）
     *
     * @param powerRatio 战力比 (玩家/AI)
     * @param conquestCount 征服次数
     * @param lostSectCount 丢失次数
     * @param battleWinCount 胜利次数
     * @param battleLossCount 失败次数
     * @param favorLevel 好感度等级
     * @param personality AI 个性（可选）
     * @return 脱离概率 (0.0 - MAX_BREAKAWAY_CHANCE)
     */
    fun calculateBreakawayChance(
        powerRatio: Double,
        conquestCount: Int,
        lostSectCount: Int,
        battleWinCount: Int,
        battleLossCount: Int,
        favorLevel: SectRelationLevel,
        personality: AISectPersonality? = null
    ): Double {
        // NaN/Infinity 防御
        if (powerRatio.isNaN() || powerRatio.isInfinite()) return 0.0

        // 战力差（反向：玩家战力优势越小 → AI越容易脱离）
        val powerScore = when {
            powerRatio >= SectDecisionConfig.POWER_TIER_5X ->
                SectDecisionConfig.BREAKAWAY_BASE_5X
            powerRatio >= SectDecisionConfig.POWER_TIER_3X ->
                SectDecisionConfig.BREAKAWAY_BASE_3X
            powerRatio >= SectDecisionConfig.POWER_TIER_2X ->
                SectDecisionConfig.BREAKAWAY_BASE_2X
            powerRatio >= SectDecisionConfig.POWER_RATIO_MIN ->
                SectDecisionConfig.BREAKAWAY_BASE_1_5X
            else -> SectDecisionConfig.BREAKAWAY_BASE_WEAK
        }

        // 占领丢失（反向：丢失比例越高越容易脱离）
        val totalOcc = maxOf(conquestCount, 0) + maxOf(lostSectCount, 0)
        val occLoss = if (totalOcc > 0) maxOf(lostSectCount, 0).toDouble() / totalOcc else 0.0
        val occupyScore = occLoss * SectDecisionConfig.Vassal.OCCUPY_WEIGHT

        // 胜负（反向：失败比例越高越容易脱离）
        val totalSk = maxOf(battleWinCount, 0) + maxOf(battleLossCount, 0)
        val skLoss = if (totalSk > 0) maxOf(battleLossCount, 0).toDouble() / totalSk else 0.0
        val skirmishScore = skLoss * SectDecisionConfig.Vassal.SKIRMISH_WEIGHT

        // 好感度（使用脱离专用分值映射：值越高越容易脱离）
        val breakawayFavorScore = SectDecisionConfig.BREAKAWAY_FAVOR_SCORE.getValue(favorLevel) *
            SectDecisionConfig.Vassal.FAVOR_WEIGHT

        val breakChance = (powerScore + occupyScore + skirmishScore + breakawayFavorScore)
            .coerceIn(0.0, SectDecisionConfig.Vassal.MAX_BREAKAWAY_CHANCE)

        return breakChance
    }

    // ═══════════════════════════
    // 私有方法
    // ═══════════════════════════

    /**
     * 计算战力差评分。
     *
     * 核心公式：按战力比分档（5x/3x/2x/1.5x）映射到对应档位分值，
     * 再根据当前 profile 的 powerWeight 相对于参考权重做缩放。
     *
     * 参考权重：VassalConfig.POWER_WEIGHT = 0.40
     * 当攻击使用 POWER_WEIGHT = 0.40 时，分数直接映射。
     * 当结盟使用 POWER_WEIGHT = 0.20 时，分数按 0.20/0.40 = 0.5 缩放。
     */
    private fun calculatePowerScore(powerRatio: Double, profilePowerWeight: Double): Double {
        val tierScore = when {
            powerRatio >= SectDecisionConfig.POWER_TIER_5X -> SectDecisionConfig.POWER_SCORE_5X
            powerRatio >= SectDecisionConfig.POWER_TIER_3X -> SectDecisionConfig.POWER_SCORE_3X
            powerRatio >= SectDecisionConfig.POWER_TIER_2X -> SectDecisionConfig.POWER_SCORE_2X
            powerRatio >= SectDecisionConfig.POWER_RATIO_MIN -> SectDecisionConfig.POWER_SCORE_MIN
            else -> 0.0
        }
        // 按 profile 的 powerWeight 相对于参考权重缩放
        return tierScore * (profilePowerWeight / SectDecisionConfig.Vassal.POWER_WEIGHT)
    }
}
