package com.xianxia.sect.core.model

import androidx.annotation.Keep
import com.xianxia.sect.core.GameConfig

/**
 * 灵石品阶：下品 / 中品 / 上品。
 *
 * 名义汇率 1:10,000，实际兑换按一键售卖价（×0.8）：
 * - 1 中品灵石 ↔ 8,000 下品灵石
 * - 1 上品灵石 ↔ 8,000 中品灵石 ↔ 64,000,000 下品灵石
 */
@Keep
enum class SpiritStoneGrade {
    LOW,
    MID,
    HIGH;

    val displayName: String
        get() = when (this) {
            LOW -> "下品灵石"
            MID -> "中品灵石"
            HIGH -> "上品灵石"
        }

    /** 下一档（更低）品阶，下品没有更低品阶 */
    val lowerGrade: SpiritStoneGrade?
        get() = when (this) {
            LOW -> null
            MID -> LOW
            HIGH -> MID
        }

    /** 上一档（更高）品阶，上品没有更高品阶 */
    val higherGrade: SpiritStoneGrade?
        get() = when (this) {
            LOW -> MID
            MID -> HIGH
            HIGH -> null
        }

    companion object {
        /** 通过中文名称解析品阶，解析失败返回 null */
        fun fromDisplayName(name: String): SpiritStoneGrade? = when (name) {
            "下品灵石" -> LOW
            "中品灵石" -> MID
            "上品灵石" -> HIGH
            else -> null
        }
    }
}

/**
 * 灵石兑换工具对象。
 *
 * 名义汇率 1:10,000。实际兑换按一键售卖价（×0.8）：
 * - 1 中品 ↔ 8,000 下品
 * - 1 上品 ↔ 8,000 中品 ↔ 64,000,000 下品
 */
@Keep
object SpiritStoneExchange {
    /** 名义汇率：1 中品 = 10,000 下品 */
    const val RATIO: Long = 10_000L

    /** 售卖价汇率：1 中品 ↔ 8,000 下品（RATIO × 0.8） */
    val EFFECTIVE_RATIO: Long =
        (RATIO * GameConfig.Rarity.SELL_PRICE_MULTIPLIER).toLong()

    /**
     * 计算所有品阶灵石按售卖价折算的下品总价值。
     * 公式：下品 + 中品×8000 + 上品×64,000,000
     */
    fun totalSellValue(low: Long, mid: Long, high: Long): Long {
        var total = low
        if (mid > 0) total = safeAdd(total, safeMultiply(mid, EFFECTIVE_RATIO))
        if (high > 0) {
            total = safeAdd(
                total,
                safeMultiply(high, safeMultiply(EFFECTIVE_RATIO, EFFECTIVE_RATIO))
            )
        }
        return total
    }

    /** 将 quantity 个 grade 灵石转换为等值下品灵石数量（按售卖价） */
    fun toLowGrade(quantity: Long, grade: SpiritStoneGrade): Long {
        if (quantity <= 0) return 0L
        return when (grade) {
            SpiritStoneGrade.LOW -> quantity
            SpiritStoneGrade.MID -> safeMultiply(quantity, EFFECTIVE_RATIO)
            SpiritStoneGrade.HIGH -> safeMultiply(
                quantity,
                safeMultiply(EFFECTIVE_RATIO, EFFECTIVE_RATIO)
            )
        }
    }

    /**
     * 用下品灵石兑换成 grade 灵石（按售卖价），
     * 返回 Pair(可兑换数量, 剩余下品)
     */
    fun fromLowGrade(lowGradeAmount: Long, grade: SpiritStoneGrade): Pair<Long, Long> {
        if (lowGradeAmount <= 0) return 0L to 0L
        return when (grade) {
            SpiritStoneGrade.LOW -> lowGradeAmount to 0L
            SpiritStoneGrade.MID -> {
                lowGradeAmount / EFFECTIVE_RATIO to
                        (lowGradeAmount % EFFECTIVE_RATIO)
            }
            SpiritStoneGrade.HIGH -> {
                val unit = safeMultiply(EFFECTIVE_RATIO, EFFECTIVE_RATIO)
                (lowGradeAmount / unit) to (lowGradeAmount % unit)
            }
        }
    }

    /** 跨品阶兑换：source 转 target，返回 Pair(成功转换数量, 剩余 source 品阶数量) */
    fun exchange(
        quantity: Long,
        source: SpiritStoneGrade,
        target: SpiritStoneGrade
    ): Pair<Long, Long> {
        if (source == target) return quantity to 0L
        if (quantity <= 0) return 0L to 0L
        val low = toLowGrade(quantity, source)
        val (converted, remainingLow) = fromLowGrade(low, target)
        // 将剩余的下品灵石折算回源品阶，保持返回值单位与 source 一致
        val remainingSource = fromLowGrade(remainingLow, source).first
        return converted to remainingSource
    }

    /** 将下品灵石数量按售卖价汇率拆分为各品阶表示 */
    fun splitToGrades(lowGradeAmount: Long): Map<SpiritStoneGrade, Long> {
        if (lowGradeAmount <= 0) return emptyMap()
        val unit = safeMultiply(EFFECTIVE_RATIO, EFFECTIVE_RATIO)
        val high = lowGradeAmount / unit
        val mid = (lowGradeAmount % unit) / EFFECTIVE_RATIO
        val low = lowGradeAmount % EFFECTIVE_RATIO
        return buildMap {
            if (high > 0) put(SpiritStoneGrade.HIGH, high)
            if (mid > 0) put(SpiritStoneGrade.MID, mid)
            if (low > 0) put(SpiritStoneGrade.LOW, low)
        }
    }

    private fun safeMultiply(a: Long, b: Long): Long {
        val result = a * b
        if (a != 0L && result / a != b) {
            return Long.MAX_VALUE
        }
        return result
    }

    private fun safeAdd(a: Long, b: Long): Long {
        val result = a + b
        return if (result < 0) Long.MAX_VALUE else result
    }
}
