package com.xianxia.sect.core.util

import kotlin.math.roundToInt

/**
 * 乘区法（Zone Multiplier System）核心计算工具。
 *
 * 统一公式：最终值 = 基础值 × Π(1 + 乘区_i)
 *
 * 每个乘区内部为加法叠加（含正负值），乘区之间为乘法叠加。
 * 参考实现：DiscipleStatCalculator.CultivationSpeedZones
 */
object ZoneCalculator {

    /**
     * 核心乘区法公式：base × (1 + z₁) × (1 + z₂) × ... × (1 + zₙ)
     *
     * @param base 基础值
     * @param zones 各乘区的加算和（可正可负）
     * @return 最终值
     */
    fun calculate(base: Double, vararg zones: Double): Double {
        var result = base
        for (zone in zones) {
            result *= 1.0 + zone
        }
        return result
    }

    /**
     * Int 版本的乘区法公式。
     */
    fun calculateInt(base: Int, vararg zones: Double): Int =
        calculate(base.toDouble(), *zones).toInt()

    /**
     * 将乘数转换为乘区加算值。
     * 例如：buildingMultiplier = 1.4 → 加算值 = 0.4
     */
    fun multiplierToZone(multiplier: Double): Double = multiplier - 1.0

    /**
     * 将乘区加算值转换回乘数。
     * 例如：加算值 = 0.4 → multiplier = 1.4
     */
    fun zoneToMultiplier(zone: Double): Double = 1.0 + zone

    /**
     * 构建概率型乘区公式（保证结果在 [0, 1] 范围内）。
     *
     * 适用于突破概率等 bounded [0,1] 的乘区计算。
     * 正效乘区作为 (1 + positiveSum) 相乘，负效乘区作为 (1 - penaltySum) 相乘。
     *
     * @param baseProb 基础概率 [0, 1]
     * @param positiveSum 正效乘区加算和（所有正面加成加总）
     * @param penaltySum 惩罚乘区加算和（所有惩罚加总，正值）
     * @return 最终概率，clamp 到 [0, 1]
     */
    fun calculateProbability(
        baseProb: Double,
        positiveSum: Double = 0.0,
        penaltySum: Double = 0.0
    ): Double {
        val positiveMult = 1.0 + positiveSum
        val penaltyMult = (1.0 - penaltySum).coerceAtLeast(0.0)
        return (baseProb * positiveMult * penaltyMult).coerceIn(0.0, 1.0)
    }

    /**
     * 计算持续时间缩减（用于生产时间等）。
     *
     * 每个缩减乘区以 (1 - reduction) 形式乘算。
     *
     * @param baseDuration 基础持续时间
     * @param reductions 各缩减率（0~1，表示减少的百分比）
     * @return 缩减后的持续时间，至少为 1
     */
    fun calculateReducedDuration(baseDuration: Int, vararg reductions: Double): Int {
        var factor = 1.0
        for (reduction in reductions) {
            factor *= (1.0 - reduction.coerceIn(0.0, 1.0))
        }
        return (baseDuration.toDouble() * factor).roundToInt().coerceAtLeast(1)
    }

    /**
     * 计算加速后的等效时间（用于生长/生产速度）。
     *
     * effectiveTime = ceil(baseTime / ((1 + z₁) × (1 + z₂) × ... × (1 + zₙ)))
     *
     * @param baseTime 基础时间
     * @param speedBonuses 各加速乘区加算值（如 0.2 = +20%）
     * @return 加速后的等效时间，至少为 1
     */
    fun calculateAcceleratedTime(baseTime: Int, vararg speedBonuses: Double): Int {
        var multiplier = 1.0
        for (bonus in speedBonuses) {
            multiplier *= 1.0 + bonus
        }
        return kotlin.math.ceil(baseTime / multiplier).toInt().coerceAtLeast(1)
    }
}
