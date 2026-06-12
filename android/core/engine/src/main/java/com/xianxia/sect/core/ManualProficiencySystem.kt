package com.xianxia.sect.core.engine

import kotlin.math.max

object ManualProficiencySystem {

    /** 基础熟练度增长速度（每秒） */
    const val BASE_PROFICIENCY_RATE = 6.0

    /** 悟性基准值，低于此值无额外加成 */
    const val COMPREHENSION_BASELINE = 70

    /** 悟性每超出基准1点增加10% */
    const val COMPREHENSION_BONUS_PER_POINT = 0.1

    /** 最大熟练度（各品阶统一） */
    const val MAX_PROFICIENCY = 30000.0

    /**
     * 藏经阁功法熟练度修炼加成比例。
     *
     * 藏经阁仅对分配到3个槽位内的弟子提供加成效果：
     * - 处于藏经阁槽位的弟子：修炼速度 × (1.0 + 0.5)（即加快 50%）
     * - 未在藏经阁中的弟子：无加成
     */
    const val LIBRARY_PROFICIENCY_BONUS_RATE = 0.5

    // 各品阶统一的熟练度阈值
    val PROFICIENCY_THRESHOLDS = mapOf(
        MasteryLevel.NOVICE to 0.0,
        MasteryLevel.SMALL_SUCCESS to 1000.0,
        MasteryLevel.GREAT_SUCCESS to 10000.0,
        MasteryLevel.PERFECTION to 30000.0
    )

    enum class MasteryLevel(val level: Int, val displayName: String, val bonus: Double) {
        NOVICE(0, "入门", 1.5),
        SMALL_SUCCESS(1, "小成", 2.0),
        GREAT_SUCCESS(2, "大成", 3.0),
        PERFECTION(3, "圆满", 4.0);

        companion object {
            fun fromProficiency(proficiency: Double): MasteryLevel {
                return when {
                    proficiency >= PROFICIENCY_THRESHOLDS.getValue(PERFECTION) -> PERFECTION
                    proficiency >= PROFICIENCY_THRESHOLDS.getValue(GREAT_SUCCESS) -> GREAT_SUCCESS
                    proficiency >= PROFICIENCY_THRESHOLDS.getValue(SMALL_SUCCESS) -> SMALL_SUCCESS
                    else -> NOVICE
                }
            }

            fun fromLevel(level: Int): MasteryLevel {
                return values().find { it.level == level } ?: NOVICE
            }
        }
    }

    data class ManualProficiency(
        val manualId: String,
        val manualName: String,
        val proficiency: Double = 0.0,
        val masteryLevel: Int = 0
    ) {
        val mastery: MasteryLevel get() = MasteryLevel.fromProficiency(proficiency)
        val masteryName: String get() = mastery.displayName
        val bonus: Double get() = mastery.bonus

        val progressToNextLevel: Double
            get() {
                val thresholds = PROFICIENCY_THRESHOLDS
                return when (mastery) {
                    MasteryLevel.NOVICE -> (proficiency / thresholds.getValue(MasteryLevel.SMALL_SUCCESS)).coerceIn(0.0, 1.0)
                    MasteryLevel.SMALL_SUCCESS -> ((proficiency - thresholds.getValue(MasteryLevel.SMALL_SUCCESS)) / (thresholds.getValue(MasteryLevel.GREAT_SUCCESS) - thresholds.getValue(MasteryLevel.SMALL_SUCCESS))).coerceIn(0.0, 1.0)
                    MasteryLevel.GREAT_SUCCESS -> ((proficiency - thresholds.getValue(MasteryLevel.GREAT_SUCCESS)) / (thresholds.getValue(MasteryLevel.PERFECTION) - thresholds.getValue(MasteryLevel.GREAT_SUCCESS))).coerceIn(0.0, 1.0)
                    MasteryLevel.PERFECTION -> 1.0
                }
            }

        val nextLevelName: String
            get() = when (mastery) {
                MasteryLevel.NOVICE -> "小成"
                MasteryLevel.SMALL_SUCCESS -> "大成"
                MasteryLevel.GREAT_SUCCESS -> "圆满"
                MasteryLevel.PERFECTION -> "已圆满"
            }
    }

    /**
     * 统一熟练度增长公式（每秒增长量）
     *
     * 公式：BASE_PROFICIENCY_RATE × 悟性加成 × 藏经阁加成
     * - 悟性加成：1.0 + max(0, comprehension - 70) × 0.1
     * - 藏经阁加成：1.0 + libraryBonus
     */
    fun calculateProficiencyGainPerSecond(
        comprehension: Int,
        libraryBonus: Double = 0.0
    ): Double {
        val comprehensionBonus = 1.0 + max(0, comprehension - COMPREHENSION_BASELINE) * COMPREHENSION_BONUS_PER_POINT
        return BASE_PROFICIENCY_RATE * comprehensionBonus * (1.0 + libraryBonus)
    }

    fun calculateSkillDamageMultiplier(
        baseMultiplier: Double,
        masteryLevel: Int
    ): Double {
        val mastery = MasteryLevel.fromLevel(masteryLevel)
        return baseMultiplier * mastery.bonus
    }
}
