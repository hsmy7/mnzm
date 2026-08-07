package com.xianxia.sect.core.util

import kotlin.math.pow

/**
 * 品阶随游戏年份的时间进度曲线（旅行商人 / 收购 / 宗门交易共用）。
 *
 * 分段开放品阶范围（左闭右开）：
 * [1,20) 凡品 → [20,80) 凡~灵 → [80,300) 凡~宝 → [300,500) 凡~玄 →
 * [500,1500) 凡~地 → [1500,∞) 凡~天。
 *
 * 段内按时间比重线性插值：段起点凡品 [RARITY_ONE_WEIGHT_AT_SEGMENT_START] 占比，
 * 段终点凡品降至 [RARITY_ONE_WEIGHT_AT_SEGMENT_END]，其余品阶按幂份额曲线分配
 * （高品阶保持稀有性）；3000 年后进入爬升轨道，每 100 年从低品阶抽取权重
 * 反哺高品阶，天品以 [MAX_MYTHIC_PROBABILITY] 封顶。
 *
 * 注意：**段间权重有意不连续**——每段起点重新从"凡品主导"爬升
 * （如 79 年灵品权重高，80 年进入宝品段后重置为凡品 90%）。这是设计特性，
 * 勿"修正"为跨段连续曲线（用户数据点：第 20 年凡品 90% 灵品 10% 即分段起点）。
 */
internal object RarityTimeProgression {

    /** 段起点凡品权重（段内线性插值起点；可配置） */
    const val RARITY_ONE_WEIGHT_AT_SEGMENT_START: Double = 0.9

    /** 段终点凡品权重（可配置） */
    const val RARITY_ONE_WEIGHT_AT_SEGMENT_END: Double = 0.1

    /** 其余品阶幂份额指数：越大高品阶越稀有（可配置） */
    const val SHARE_POWER: Double = 2.0

    /** 末段插值终点年份；超过后进入 3000 年后爬升轨道 */
    const val FINAL_SEGMENT_END_YEAR: Int = 3000

    /** 3000 年后爬升步进（年） */
    const val POST_3000_STEP_YEARS: Int = 100

    /** 每爬升档玄品权重增量 */
    const val XUAN_STEP_PER_CENTURY: Double = 0.0004

    /** 每爬升档地品权重增量 */
    const val EARTH_STEP_PER_CENTURY: Double = 0.0002

    /** 每爬升档天品权重增量 */
    const val MYTHIC_STEP_PER_CENTURY: Double = 0.0004

    /** 每爬升档从凡品抽取的权重 */
    const val FANPIN_STEP_PER_CENTURY: Double = 0.0006

    /** 每爬升档从灵品抽取的权重 */
    const val LINGPIN_STEP_PER_CENTURY: Double = 0.0004

    /** 天品概率硬上限（用户约束：不得超过 2%） */
    const val MAX_MYTHIC_PROBABILITY: Double = 0.02

    /** 凡品权重下限保护（爬升不抽到低于此值） */
    const val FANPIN_FLOOR: Double = 0.04

    /** 灵品权重下限保护 */
    const val LINGPIN_FLOOR: Double = 0.15

    /** 分段表：(分段起始年, 分段最高品阶)，按起始年升序 */
    val SEGMENTS: List<Pair<Int, Int>> = listOf(
        1 to 1, 20 to 2, 80 to 3, 300 to 4, 500 to 5, 1500 to 6
    )

    /** 3000 年后爬升总档数：天品从末段终点权重爬升到 [MAX_MYTHIC_PROBABILITY] 所需档数（floor，保证不超上限） */
    private val post3000MaxSteps: Int by lazy {
        val w6Base = (1.0 - RARITY_ONE_WEIGHT_AT_SEGMENT_END) / (1..5).sumOf { it.toDouble().pow(SHARE_POWER) }
        ((MAX_MYTHIC_PROBABILITY - w6Base) / MYTHIC_STEP_PER_CENTURY).toInt()
    }

    /**
     * 该年份可出现的最高品阶。
     * @param year 游戏年份；year < 1 防御性返回 1
     */
    fun maxRarityForYear(year: Int): Int {
        val y = year.coerceAtLeast(1)
        var result = 1
        for ((startYear, rarity) in SEGMENTS) {
            if (y >= startYear) result = rarity
        }
        return result
    }

    /**
     * 保底品阶：**下一分段**的最高品阶（如 60 年属凡~灵段 → 保底宝品 3）。
     * 末段（凡~天）无下一分段，保底取自身最高品阶天品 6。
     */
    fun pityRarityForYear(year: Int): Int {
        val max = maxRarityForYear(year)
        val index = SEGMENTS.indexOfFirst { it.second == max }
        return if (index == SEGMENTS.lastIndex) max else SEGMENTS[index + 1].second
    }

    /**
     * 该年份的品阶权重表，键为 1..[maxRarityForYear]，归一化后和为 1.0。
     * 权重公式：
     * - 凡品：段起点 [RARITY_ONE_WEIGHT_AT_SEGMENT_START] 线性过渡到 [RARITY_ONE_WEIGHT_AT_SEGMENT_END]
     * - 其余品阶：共享剩余权重，按 (R - r + 1)^[SHARE_POWER] 幂份额分配
     * - 3000 年后：每 [POST_3000_STEP_YEARS] 年一档，从凡品/灵品抽取权重反哺玄/地/天，
     *   天品以 [MAX_MYTHIC_PROBABILITY] 封顶
     */
    fun rarityWeightsForYear(year: Int): Map<Int, Double> {
        val maxRarity = maxRarityForYear(year)
        if (maxRarity == 1) return mapOf(1 to 1.0) // 纯凡品段（防除零）

        val segIndex = SEGMENTS.indexOfFirst { it.second == maxRarity }
        val segStart = SEGMENTS[segIndex].first
        val segEnd = if (segIndex == SEGMENTS.lastIndex) FINAL_SEGMENT_END_YEAR else SEGMENTS[segIndex + 1].first
        // 用 Double 计算防 Int 溢出；t 越界钳制（3000 年后饱和在 1.0）
        val t = ((year.coerceAtLeast(segStart).toDouble() - segStart) / (segEnd - segStart)).coerceIn(0.0, 1.0)

        val base = baseWeights(maxRarity, t)
        val adjusted = if (maxRarity == 6 && year > FINAL_SEGMENT_END_YEAR) {
            applyPost3000Climb(base, year)
        } else {
            base
        }
        return normalize(adjusted)
    }

    /**
     * 按权重累计抽样（仿现旧 selectRarity 结构）。
     * **恰好消费 1 次 [DeterministicRng.nextDouble]**——保证 SYSTEM 分区随机流的
     * draw 结构不受年份影响，其他系统（突破/战斗等）的随机序列在升级前后不变。
     */
    fun rollRarity(rng: DeterministicRng, year: Int): Int {
        val weights = rarityWeightsForYear(year)
        val rand = rng.nextDouble()
        var cumulative = 0.0
        for ((rarity, prob) in weights.entries.sortedByDescending { it.key }) {
            cumulative += prob
            if (rand < cumulative) return rarity
        }
        return 1 // fallback：权重表恒含品阶 1
    }

    private fun baseWeights(maxRarity: Int, t: Double): Map<Int, Double> {
        val w1 = RARITY_ONE_WEIGHT_AT_SEGMENT_START +
            (RARITY_ONE_WEIGHT_AT_SEGMENT_END - RARITY_ONE_WEIGHT_AT_SEGMENT_START) * t
        val otherTotal = 1.0 - w1
        val weights = mutableMapOf<Int, Double>(1 to w1)
        if (maxRarity == 1) return weights
        val shares = (2..maxRarity).associateWith { r -> (maxRarity - r + 1).toDouble().pow(SHARE_POWER) }
        val shareSum = shares.values.sum()
        for ((rarity, share) in shares) {
            weights[rarity] = otherTotal * share / shareSum
        }
        return weights
    }

    /** 3000 年后爬升：每 100 年从凡品/灵品抽取权重，反哺玄/地/天（抽取总量 = 注入总量） */
    private fun applyPost3000Climb(base: Map<Int, Double>, year: Int): Map<Int, Double> {
        val k = ((year - FINAL_SEGMENT_END_YEAR) / POST_3000_STEP_YEARS).coerceIn(0, post3000MaxSteps)
        val adjusted = base.toMutableMap()
        adjusted[6] = base.getValue(6) + k * MYTHIC_STEP_PER_CENTURY
        adjusted[5] = base.getValue(5) + k * EARTH_STEP_PER_CENTURY
        adjusted[4] = base.getValue(4) + k * XUAN_STEP_PER_CENTURY
        adjusted[1] = (base.getValue(1) - k * FANPIN_STEP_PER_CENTURY).coerceAtLeast(FANPIN_FLOOR)
        adjusted[2] = (base.getValue(2) - k * LINGPIN_STEP_PER_CENTURY).coerceAtLeast(LINGPIN_FLOOR)
        return adjusted
    }

    private fun normalize(weights: Map<Int, Double>): Map<Int, Double> {
        val sum = weights.values.sum()
        return weights.mapValues { it.value / sum }
    }
}
