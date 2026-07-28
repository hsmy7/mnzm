package com.xianxia.sect.core.model

/**
 * 宗门关系等级 — 根据好感度数值划分的五个等级。
 *
 * 每个等级包含显示名称、好感度范围、颜色、可交易最大稀有度。
 */
enum class SectRelationLevel(
    val displayName: String,
    val minFavor: Int,
    val maxFavor: Int,
    val colorHex: Long
) {
    /** 敌对 0-19 */
    HOSTILE("敌对", 0, 19, 0xFFF44336),

    /** 交恶 20-39 */
    ANTAGONISTIC("交恶", 20, 39, 0xFFFF5722),

    /** 普通 40-59 */
    NORMAL("普通", 40, 59, 0xFFFF9800),

    /** 友善 60-79 */
    FRIENDLY("友善", 60, 79, 0xFF8BC34A),

    /** 至交 80-100 */
    INTIMATE("至交", 80, 100, 0xFF4CAF50);

    /** 根据好感度等级可购买物品的最高稀有度 */
    val maxAllowedRarity: Int
        get() = when (this) {
            NORMAL -> 2
            FRIENDLY -> 4
            INTIMATE -> 6
            else -> 0
        }

    companion object {
        /**
         * 从好感度数值推断关系等级。
         * @param favor 好感度数值
         * @return 对应的关系等级，默认为 HOSTILE
         */
        fun fromFavor(favor: Int): SectRelationLevel {
            // favor 超出最高等级上限时仍返回最高等级（如 favor=200 → INTIMATE）
            if (favor >= INTIMATE.minFavor) return INTIMATE
            return entries.find { favor in it.minFavor..it.maxFavor } ?: HOSTILE
        }
    }
}
