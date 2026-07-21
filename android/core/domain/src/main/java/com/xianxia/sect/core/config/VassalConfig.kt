package com.xianxia.sect.core.config

/**
 * 附属宗门（Vassal）系统常量配置。
 *
 * 从 GameConfig.Vassal 迁移而来，GameConfig.Vassal 已废弃。
 */
object VassalConfig {

    /** 年贡金额（按宗门等级 0=小型, 1=中型, 2=大型, 3=顶级） */
    val TRIBUTE_BY_SECT_LEVEL: Map<Int, Long> = mapOf(
        0 to 200_000L,
        1 to 800_000L,
        2 to 3_000_000L,
        3 to 10_000_000L,
    )

    /** 战力比低于此值AI直接拒绝 */
    const val POWER_RATIO_MIN = 1.5

    // ═══ 附庸接受概率配置 ═══

    /** 战力硬门槛：玩家必须比 AI 强才有资格要求附庸 */
    const val VASSALIZE_HARD_THRESHOLD = 1.0

    /** 战力差权重 */
    const val POWER_WEIGHT = 0.40

    /** 占领丢失权重 */
    const val OCCUPY_WEIGHT = 0.30

    /** 胜负权重 */
    const val SKIRMISH_WEIGHT = 0.15

    /** 好感度权重 */
    const val FAVOR_WEIGHT = 0.15

    /** 附庸接受概率上限 */
    const val MAX_VASSAL_CHANCE = 0.95

    // ═══ 战力分档阈值及对应分数 ═══

    /** 战力 5 倍门槛 */
    const val POWER_TIER_5X = 5.0

    /** 战力 3 倍门槛 */
    const val POWER_TIER_3X = 3.0

    /** 战力 2 倍门槛 */
    const val POWER_TIER_2X = 2.0

    /** 战力 5 倍分数 */
    const val POWER_SCORE_5X = 0.40

    /** 战力 3 倍分数 */
    const val POWER_SCORE_3X = 0.30

    /** 战力 2 倍分数 */
    const val POWER_SCORE_2X = 0.20

    /** 战力最低档分数 */
    const val POWER_SCORE_MIN = 0.10

    // ═══ AI 脱离概率配置 ═══

    /** 脱离概率上限 */
    const val MAX_BREAKAWAY_CHANCE = 0.40

    /** 脱离：战力极大优势时脱离基率 */
    const val BREAKAWAY_BASE_5X = 0.0

    /** 脱离：战力较大优势时脱离基率 */
    const val BREAKAWAY_BASE_3X = 0.05

    /** 脱离：战力优势时脱离基率 */
    const val BREAKAWAY_BASE_2X = 0.12

    /** 脱离：战力小幅优势时脱离基率 */
    const val BREAKAWAY_BASE_1_5X = 0.20

    /** 脱离：战力无优势时脱离基率 */
    const val BREAKAWAY_BASE_WEAK = 0.35

    /** 脱离好感度基线（低于此值好感度越高则脱离意愿越低） */
    const val BREAKAWAY_FAVOR_BASELINE = 50
}
