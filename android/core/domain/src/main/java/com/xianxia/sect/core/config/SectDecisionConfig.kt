package com.xianxia.sect.core.config

import com.xianxia.sect.core.model.SectRelationLevel

/**
 * AI 宗门决策引擎配置 — 攻击判定、结盟判定、附属判定各有独立权重。
 *
 * 每种决策类型使用四因素加权模型：
 * 1. 战力差 (power) — 自身战力与目标战力的比值
 * 2. 占领丢失 (occupy) — 宗门征服与丢失的比例
 * 3. 胜负 (skirmish) — 战斗胜率
 * 4. 好感度 (favor) — 双边关系，按 [SectRelationLevel] 五级分档，每档有固定分值
 *
 * 好感度不再使用原始 0-100 连续数值，而是通过 [FAVOR_SCORE_BY_LEVEL] 映射
 * 将等级转换为固定贡献分数，消除微调不可预测性。
 *
 * 配置理念：
 * - 攻击判定：战力差和胜负权重更高，攻击是实力导向
 * - 结盟判定：好感度权重最高，结盟是信任导向
 * - 附属判定：与附属系统的权重一致
 */
object SectDecisionConfig {

    // ═══════════════════════════════════════════
    // 好感度等级分值配置（所有决策类型共享等级定义）
    //
    // 各决策类型中，好感度等级的贡献分数（作为四因素之一参与加权计算）。
    // 分数范围 0.0~1.0：
    //   - 0.0 = 该等级下好感度不贡献任何概率（可能还阻止决策）
    //   - 1.0 = 好感度在该等级下贡献满分概率
    // ═══════════════════════════════════════════

    /** 攻击判定：各等级的好感度分值 */
    val ATTACK_FAVOR_SCORE: Map<SectRelationLevel, Double> = mapOf(
        SectRelationLevel.HOSTILE to 1.0,         // 仇恨 → 全力攻击
        SectRelationLevel.ANTAGONISTIC to 0.7,     // 交恶 → 较高攻击意愿
        SectRelationLevel.NORMAL to 0.3,           // 普通 → 低攻击意愿
        SectRelationLevel.FRIENDLY to 0.0,         // 友善 → 不攻击
        SectRelationLevel.INTIMATE to 0.0,          // 至交 → 不攻击
    )

    /** 结盟判定：各等级的好感度分值 */
    val ALLIANCE_FAVOR_SCORE: Map<SectRelationLevel, Double> = mapOf(
        SectRelationLevel.HOSTILE to 0.0,          // 敌对 → 不可能结盟
        SectRelationLevel.ANTAGONISTIC to 0.1,     // 交恶 → 极难结盟
        SectRelationLevel.NORMAL to 0.4,           // 普通 → 一般
        SectRelationLevel.FRIENDLY to 0.7,         // 友善 → 较易结盟
        SectRelationLevel.INTIMATE to 1.0,          // 至交 → 最易结盟
    )

    /** 附属接受判定：各等级的好感度分值 */
    val VASSAL_FAVOR_SCORE: Map<SectRelationLevel, Double> = mapOf(
        SectRelationLevel.HOSTILE to 0.0,          // 敌对 → 不可能附属
        SectRelationLevel.ANTAGONISTIC to 0.1,     // 交恶 → 极难
        SectRelationLevel.NORMAL to 0.4,           // 普通 → 一般
        SectRelationLevel.FRIENDLY to 0.7,         // 友善 → 较易接受
        SectRelationLevel.INTIMATE to 0.9,          // 至交 → 几乎接受（但不等于结盟满分）
    )

    /** 附属脱离判定：各等级的好感度分值（反向：值越高越不易脱离） */
    val BREAKAWAY_FAVOR_SCORE: Map<SectRelationLevel, Double> = mapOf(
        SectRelationLevel.HOSTILE to 1.0,          // 敌对 → 极力脱离
        SectRelationLevel.ANTAGONISTIC to 0.8,     // 交恶 → 较想脱离
        SectRelationLevel.NORMAL to 0.4,           // 普通 → 一般
        SectRelationLevel.FRIENDLY to 0.2,         // 友善 → 不太想脱离
        SectRelationLevel.INTIMATE to 0.0,          // 至交 → 不脱离
    )

    // ═══════════════════════════════════════════
    // 攻击判定权重 (用于 AISectAttackManager)
    // ═══════════════════════════════════════════
    object Attack {
        /** 战力差权重 — 攻击方最看重能否打过 */
        const val POWER_WEIGHT = 0.40
        /** 占领丢失权重 — 反映扩张能力 */
        const val OCCUPY_WEIGHT = 0.20
        /** 胜负权重 — 反映实战能力 */
        const val SKIRMISH_WEIGHT = 0.25
        /** 好感度权重 — 仇恨/友谊影响攻击意愿 */
        const val FAVOR_WEIGHT = 0.15

        /** 攻击概率上限 */
        const val MAX_CHANCE = 0.95
        /** 战力硬门槛：低于此值不攻击 */
        const val POWER_HARD_THRESHOLD = 0.5
    }

    // ═══════════════════════════════════════════
    // 结盟判定权重 (用于 DiplomacyService)
    // ═══════════════════════════════════════════
    object Alliance {
        /** 战力差权重 — 势均力敌更易结盟，此处权重较低 */
        const val POWER_WEIGHT = 0.20
        /** 占领丢失权重 — 扩张风格影响结盟意愿 */
        const val OCCUPY_WEIGHT = 0.15
        /** 胜负权重 — 胜率反映宗门实力和信誉 */
        const val SKIRMISH_WEIGHT = 0.25
        /** 好感度权重 — 结盟最看重信任 */
        const val FAVOR_WEIGHT = 0.40

        /** 结盟概率上限 */
        const val MAX_CHANCE = 0.95
        /** 战力硬门槛：低于此值不结盟（实力悬殊不结盟） */
        const val POWER_HARD_THRESHOLD = 0.6
    }

    // ═══════════════════════════════════════════
    // 附属判定权重
    // ═══════════════════════════════════════════
    object Vassal {
        /** 战力差权重 */
        const val POWER_WEIGHT = 0.40
        /** 占领丢失权重 */
        const val OCCUPY_WEIGHT = 0.30
        /** 胜负权重 */
        const val SKIRMISH_WEIGHT = 0.15
        /** 好感度权重 */
        const val FAVOR_WEIGHT = 0.15

        /** 附属接受概率上限 */
        const val MAX_CHANCE = 0.95
        /** 战力硬门槛：玩家必须比 AI 强才有资格要求附庸 */
        const val POWER_HARD_THRESHOLD = 1.0

        /** 脱离概率上限 */
        const val MAX_BREAKAWAY_CHANCE = 0.40
    }

    // ═══════════════════════════════════════════
    // 战力分档阈值（共享，所有决策类型通用）
    // ═══════════════════════════════════════════

    const val POWER_TIER_5X = 5.0
    const val POWER_TIER_3X = 3.0
    const val POWER_TIER_2X = 2.0
    const val POWER_RATIO_MIN = 1.5

    const val POWER_SCORE_5X = 0.40
    const val POWER_SCORE_3X = 0.30
    const val POWER_SCORE_2X = 0.20
    const val POWER_SCORE_MIN = 0.10

    // ═══════════════════════════════════════════
    // 脱离基率（保留，供脱离判定使用）
    // ═══════════════════════════════════════════

    const val BREAKAWAY_BASE_5X = 0.0
    const val BREAKAWAY_BASE_3X = 0.05
    const val BREAKAWAY_BASE_2X = 0.12
    const val BREAKAWAY_BASE_1_5X = 0.20
    const val BREAKAWAY_BASE_WEAK = 0.35

    // ═══════════════════════════════════════════
    // AI 个性修正因子
    // ═══════════════════════════════════════════
    object PersonalityModifiers {
        /**
         * 攻击意愿偏移：好战型 +20%、均衡型 ±0%、保守型 -20%、隐世型 -40%
         * 在引擎中通过 `chance * factor` 方式应用，coerceIn(0, 1)
         */
        const val AGGRESSIVE_ATTACK = 1.20
        const val BALANCED_ATTACK = 1.00
        const val CONSERVATIVE_ATTACK = 0.80
        const val RECLUSIVE_ATTACK = 0.60

        /**
         * 结盟意愿偏移：好战型 -10%（更倾向吞并而非合作）、
         * 均衡型 ±0%、保守型 +15%（寻求保护）、隐世型 -10%
         */
        const val AGGRESSIVE_ALLIANCE = 0.90
        const val BALANCED_ALLIANCE = 1.00
        const val CONSERVATIVE_ALLIANCE = 1.15
        const val RECLUSIVE_ALLIANCE = 0.90
    }
}
