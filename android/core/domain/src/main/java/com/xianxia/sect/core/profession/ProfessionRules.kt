package com.xianxia.sect.core.profession

import com.xianxia.sect.core.model.Disciple

/**
 * 炼丹师/锻造师职业规则（纯逻辑单例，无引擎依赖，engine/feature/test 全链路复用）。
 *
 * 等级模型：5 级职业 + 无职业。等级 N 可炼制最高品阶 tier = N+1：
 * 无职业(0) 可炼凡品(tier 1)，炼丹师(1) 可炼灵品(tier 2) … 丹圣/器圣(5) 可炼天品(tier 6)。
 *
 * 晋升条件（N → N+1）三重门槛：
 * 1. 成功次数：仅统计**当前解锁最高阶**（tier = N+1）的成功炼制次数，低阶不充数
 * 2. 境界：realm 值 <= 门槛（realm 数值越小境界越高，对齐 [GameConfig.Realm.meetsRealmRequirement]）
 * 3. 炼丹/锻造属性（pillRefining/artifactRefining）
 */
/** 职业晋升进度结算结果：成功炼制后的弟子快照与是否晋升 */
data class PromotionProgress(
    val disciple: Disciple,
    val promoted: Boolean,
    val newLevel: Int
)

/**
 * 成功炼制后结算职业晋升进度（纯函数，不修改任何状态）。
 *
 * 规则：
 * - 仅统计**当前解锁最高阶**（tier == maxCraftableTier(level)）的成功炼制，低阶不充数
 * - 次数/境界/属性三门槛全满足 → 晋升一级并清零计数
 * - 五级（丹圣/器圣）封顶不再计数
 *
 * @param recipeTier 炼制配方的品阶（1~6）
 * @param isAlchemy true=炼丹职业，false=锻造（炼器）职业
 */
fun Disciple.applyPromotionProgress(
    recipeTier: Int,
    isAlchemy: Boolean
): PromotionProgress {
    val level = if (isAlchemy) skills.alchemyLevel else skills.forgeLevel
    // 封顶（丹圣/器圣）不再计数；低阶不充数：只统计当前解锁最高阶的成功炼制
    if (level >= ProfessionRules.MAX_LEVEL ||
        recipeTier != ProfessionRules.maxCraftableTier(level)
    ) {
        return PromotionProgress(this, false, level)
    }
    // 溢出防护（对抗性审查）：计数接近 Int.MAX_VALUE 时 +1 溢出为负，
    // 会重新触发"未达标"判断；封顶后不再增长（满足要求即晋升的语义不受影响）
    val newCount = (if (isAlchemy) skills.alchemyPromotionCount else skills.forgePromotionCount)
        .coerceAtMost(Int.MAX_VALUE - 1) + 1
    val meetsCount = newCount >= ProfessionRules.promotionSuccessRequirement(level)
    val meetsRealm = realm <= ProfessionRules.promotionRealmRequirement(level)
    val meetsSkill = (if (isAlchemy) skills.pillRefining else skills.artifactRefining) >=
        ProfessionRules.promotionSkillRequirement(level)
    val promoted = meetsCount && meetsRealm && meetsSkill
    val newSkills = if (isAlchemy) {
        if (promoted) {
            skills.copy(alchemyLevel = level + 1, alchemyPromotionCount = 0)
        } else {
            skills.copy(alchemyPromotionCount = newCount)
        }
    } else {
        if (promoted) {
            skills.copy(forgeLevel = level + 1, forgePromotionCount = 0)
        } else {
            skills.copy(forgePromotionCount = newCount)
        }
    }
    return PromotionProgress(
        disciple = copy(skills = newSkills),
        promoted = promoted,
        newLevel = if (promoted) level + 1 else level
    )
}

object ProfessionRules {

    /** 最高职业等级（5 = 丹圣/器圣） */
    const val MAX_LEVEL = 5

    /** 最高可炼品阶（tier 6 = 天品） */
    const val MAX_TIER = 6

    /** 晋升 N→N+1 的成功次数要求（索引 = 当前等级），等级越高要求越多 */
    private val PROMOTION_SUCCESS_COUNTS = listOf(1, 200, 500, 800, 800)

    /** 晋升 N→N+1 的境界要求（realm 值越小境界越高：9=炼气…3=合体…0=仙人），顶级晋升要求合体 */
    private val PROMOTION_REALM_REQUIREMENTS = listOf(9, 7, 6, 5, 3)

    /** 晋升 N→N+1 的炼丹/锻造属性要求，上限 110 */
    private val PROMOTION_SKILL_REQUIREMENTS = listOf(40, 55, 70, 90, 110)

    /** 职业成功率加成：每低一阶 +0.20（基础率部分，乘区法 baseProb 组成） */
    const val PROFESSION_ZONE_PER_TIER = 0.20

    /** 炼丹/锻造属性成功率加成：属性基准（低于该值无加成） */
    const val SKILL_ZONE_BASELINE = 30

    /** 炼丹/锻造属性成功率加成：每点属性加成比例 */
    const val SKILL_ZONE_RATE = 0.006

    /** 炼丹/锻造属性成功率加成上限（clamp） */
    const val SKILL_ZONE_MAX = 0.50

    /**
     * 等级可炼制的最高品阶。
     *
     * @param level 职业等级（0=无职业 ~ 5=顶级）
     * @return tier 1~6
     */
    fun maxCraftableTier(level: Int): Int =
        (level.coerceIn(0, MAX_LEVEL) + 1).coerceAtMost(MAX_TIER)

    /**
     * 该等级的弟子是否能炼制/锻造指定品阶。
     *
     * @param level 职业等级
     * @param tier 配方品阶（1~6）
     */
    fun canCraftTier(level: Int, tier: Int): Boolean = tier <= maxCraftableTier(level)

    /**
     * 晋升 level→level+1 所需的成功炼制次数（仅计当前解锁最高阶）。
     *
     * @param level 当前等级（0~4）
     * @return 次数要求（level 5 封顶返回 [Int.MAX_VALUE] 表示不再晋升）
     */
    fun promotionSuccessRequirement(level: Int): Int {
        val idx = level.coerceIn(0, MAX_LEVEL)
        return if (idx >= MAX_LEVEL) Int.MAX_VALUE else PROMOTION_SUCCESS_COUNTS[idx]
    }

    /**
     * 晋升 level→level+1 所需的境界（realm 值 <= 该值即满足）。
     *
     * @param level 当前等级（0~4）
     * @return realm 门槛值（数值越小境界越高）
     */
    fun promotionRealmRequirement(level: Int): Int =
        PROMOTION_REALM_REQUIREMENTS[level.coerceIn(0, MAX_LEVEL - 1)]

    /**
     * 晋升 level→level+1 所需的炼丹/锻造属性。
     *
     * @param level 当前等级（0~4）
     * @return 属性要求（默认弟子属性 50，首次晋升自动满足）
     */
    fun promotionSkillRequirement(level: Int): Int =
        PROMOTION_SKILL_REQUIREMENTS[level.coerceIn(0, MAX_LEVEL - 1)]

    /**
     * 职业显示名（修仙题材）。
     *
     * @param level 职业等级（0=无职业）
     * @param isAlchemy true=炼丹师职业，false=炼器师（锻造）职业
     */
    fun displayName(level: Int, isAlchemy: Boolean): String = when (level.coerceIn(0, MAX_LEVEL)) {
        0 -> "无职业"
        1 -> if (isAlchemy) "炼丹师" else "炼器师"
        2 -> if (isAlchemy) "炼丹大师" else "炼器大师"
        3 -> if (isAlchemy) "炼丹宗师" else "炼器宗师"
        4 -> if (isAlchemy) "炼丹大宗师" else "炼器大宗师"
        else -> if (isAlchemy) "丹圣" else "器圣"
    }
}
