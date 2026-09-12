package com.xianxia.sect.core.config

/**
 * 好感度系统常量配置。
 *
 * 将散落在 GameConfig.Diplomacy、GameConfig.WorldMap、GiftConfig 中的
 * 好感度相关常量集中管理。
 */
object FavorConfig {

    // ═══════════ 好感度范围 ═══════════

    /** 好感度最小值 */
    const val MIN_FAVOR = 0

    /** 好感度最大值 */
    const val MAX_FAVOR = 100

    /** 初始好感度默认值 */
    const val INITIAL_FAVOR = 50

    // ═══════════ 好感度衰减 ═══════════

    /** 超过 N 年未送礼开始衰减 */
    const val DECAY_NO_GIFT_YEARS = 1

    /** 每次衰减的点数 */
    const val DECAY_AMOUNT = 1

    /** 衰减下限（好感度高于此值才会衰减，衰减不会低于此值） */
    const val DECAY_THRESHOLD = 80

    // ═══════════ 交易价格 ═══════════

    /** 盟友价格最低折扣倍率 */
    const val ALLY_PRICE_MIN = 0.85

    /** 好感度折扣最低倍率 */
    const val FAVOR_PRICE_MIN = 0.85

    /** 好感度折扣生效阈值（好感 ≥ 此值时有折扣） */
    const val FAVOR_DISCOUNT_THRESHOLD = 70

    // ═══════════ 联盟好感度 ═══════════

    /** 维持联盟所需的最小好感度 */
    const val MIN_ALLIANCE_FAVOR = 80

    /** 联盟持续时间（年） */
    const val ALLIANCE_DURATION_YEARS = 5

    // ═══════════ 邂逅好感度 ═══════════

    /** 邂逅事件中好感度变化量（负值表示减少） */
    const val ENCOUNTER_FAVOR_DELTA = -3
}
