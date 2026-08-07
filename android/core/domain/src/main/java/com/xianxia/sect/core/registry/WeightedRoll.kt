package com.xianxia.sect.core.registry

import com.xianxia.sect.core.util.DomainLog
import kotlin.random.Random

/**
 * 弟子特质生成公共分布工具 —— 体质/天赋/词条三分类共用（DRY）。
 *
 * 每次调用恰好消费 1 次 [Random.nextDouble]，保证确定性 RNG 固定消费次数。
 * 数量分布：0个35% / 1个35% / 2个20% / 3个6% / 4个3% / 5个1%
 * 品阶分布：负面(rarity=0)30% / 下品(rarity=1)50% / 中品(rarity=2)18% / 上品(rarity=3)2%
 */
internal val DISCIPLE_TRAIT_COUNT_DISTRIBUTION = listOf(
    0 to 0.35,
    1 to 0.35,
    2 to 0.20,
    3 to 0.06,
    4 to 0.03,
    5 to 0.01
)

/** 单特质品阶分布：0=负面(30%) / 1=下品(50%) / 2=中品(18%) / 3=上品(2%) */
internal val DISCIPLE_TRAIT_QUALITY_DISTRIBUTION = listOf(
    0 to 0.30,
    1 to 0.50,
    2 to 0.18,
    3 to 0.02
)

/**
 * 抽取弟子特质数量（0-5），单次 nextDouble。
 */
internal fun rollTraitCount(random: Random): Int =
    rollCumulative(DISCIPLE_TRAIT_COUNT_DISTRIBUTION, random)

/**
 * 抽取单特质品阶（0=负面，1-3=品阶），单次 nextDouble。
 */
internal fun rollTraitQuality(random: Random): Int =
    rollCumulative(DISCIPLE_TRAIT_QUALITY_DISTRIBUTION, random)

private fun rollCumulative(
    distribution: List<Pair<Int, Double>>,
    random: Random
): Int {
    val roll = random.nextDouble()
    var cumulative = 0.0
    for ((value, probability) in distribution) {
        cumulative += probability
        if (roll <= cumulative) return value
    }
    // 浮点兜底：权重和 < 1.0 时回退末档
    DomainLog.w("WeightedRoll", "分布权重和<1.0（累积=$cumulative），回退末档")
    return distribution.last().first
}
