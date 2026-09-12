package com.xianxia.sect.core.model

import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * AI宗门攻击个性，决定该宗门的谴责频率、宣战阈值和攻击冷却。
 *
 * 参考 Stellaris AI Personality 系统设计。
 */
@Serializable
enum class AISectPersonality(
    /** 分配权重（纯随机，不受正邪阵营影响） */
    val weight: Int,
    /** 两次谴责之间的最小间隔（游戏月数），实际值在范围内随机 */
    val denounceIntervalMin: Int,
    val denounceIntervalMax: Int,
    /** 谴责后正式宣战的概率 */
    val warProbability: Double,
    /** 进攻所需的战力比门槛（attackerPower / defenderPower） */
    val powerRatioThreshold: Double,
    /** 一次进攻/宣战后强制冷却的月数 */
    val attackCooldownMonths: Int
) {
    /** 好战型：频繁谴责，敢打强者 */
    AGGRESSIVE(
        weight = 25,
        denounceIntervalMin = 24, denounceIntervalMax = 48,
        warProbability = 0.80,
        powerRatioThreshold = 0.50,
        attackCooldownMonths = 12
    ),
    /** 均衡型：按利益行事，挑弱者下手 */
    BALANCED(
        weight = 40,
        denounceIntervalMin = 48, denounceIntervalMax = 96,
        warProbability = 0.50,
        powerRatioThreshold = 0.70,
        attackCooldownMonths = 24
    ),
    /** 保守型：很少主动进攻，重防守 */
    CONSERVATIVE(
        weight = 25,
        denounceIntervalMin = 96, denounceIntervalMax = 180,
        warProbability = 0.25,
        powerRatioThreshold = 0.90,
        attackCooldownMonths = 48
    ),
    /** 隐世型：几乎不主动进攻 */
    RECLUSIVE(
        weight = 10,
        denounceIntervalMin = 180, denounceIntervalMax = Int.MAX_VALUE,
        warProbability = 0.10,
        powerRatioThreshold = 1.20,
        attackCooldownMonths = 96
    );

    companion object {
        private val weightedPool: List<AISectPersonality> by lazy {
            entries.flatMap { p -> List(p.weight) { p } }
        }

        /** 按权重纯随机分配个性 */
        fun random(): AISectPersonality =
            weightedPool[Random.nextInt(weightedPool.size)]

        /** 获取随机的谴责间隔（月数） */
        fun randomDenounceInterval(personality: AISectPersonality): Int {
            val max = personality.denounceIntervalMax
            // Int.MAX_VALUE + 1 会溢出，对这种极端值直接返回一个大数
            if (max == Int.MAX_VALUE) return Int.MAX_VALUE
            return Random.nextInt(
                personality.denounceIntervalMin,
                max + 1
            )
        }
    }
}
