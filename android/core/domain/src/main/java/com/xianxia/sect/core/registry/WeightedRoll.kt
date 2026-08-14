package com.xianxia.sect.core.registry

import com.xianxia.sect.core.util.DomainLog
import kotlin.math.abs
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
 * 洗炼/新增（玉符消耗玩法）单特质品阶分布：1=下品(40%) / 2=中品(30%) / 3=上品(30%)，
 * **无负面**。洗炼天赋/体质/词条与新增天赋/体质/词条共用（2026-08-15 需求变更）。
 */
internal val WASH_TRAIT_QUALITY_DISTRIBUTION = listOf(
    1 to 0.40,
    2 to 0.30,
    3 to 0.30
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

/**
 * 抽取洗炼/新增单特质品阶（1-3，无负面），单次 nextDouble。
 */
internal fun rollWashTraitQuality(random: Random): Int =
    rollCumulative(WASH_TRAIT_QUALITY_DISTRIBUTION, random)

/**
 * 从**正向**候选池按洗炼/新增品阶分布抽取一条（单次 nextDouble 消费三档：
 * 下品40% / 中品30% / 上品30%）。候选池必须已过滤负面条目（调用方保证）。
 *
 * 品阶分布命中后精确匹配该品阶；无精确匹配时取差值最小档（平局取较高档），
 * 与生成路径 [pickPositiveByRarity] 语义一致。
 *
 * 池空抛 [IllegalArgumentException]——调用方必须先经 `hasXxxCandidates` 预检
 * （预检与抽取同池同过滤，预检通过后此处不可能为空）。
 */
internal fun <T> pickPositiveWash(
    candidates: List<T>,
    random: Random,
    rarityOf: (T) -> Int
): T {
    require(candidates.isNotEmpty()) { "candidates cannot be empty" }
    val quality = rollWashTraitQuality(random)
    candidates.filter { rarityOf(it) == quality }.randomOrNull(random)?.let { return it }
    // 无精确匹配：取差值最小档（平局取较高档），与生成路径语义一致
    val fallbackRarity = candidates.map { rarityOf(it) }.distinct()
        .minWithOrNull(compareBy<Int> { abs(it - quality) }.thenByDescending { it })
        ?: quality
    return candidates.filter { rarityOf(it) == fallbackRarity }
        .randomOrNull(random)
        ?: candidates.random(random)
}

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
