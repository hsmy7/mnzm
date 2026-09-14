package com.xianxia.sect.core.util

import com.xianxia.sect.core.GameConfig
import kotlin.random.Random

object SpiritRootGenerator {

    private val ELEMENTS = listOf("metal", "wood", "water", "fire", "earth")

    fun generate(random: Random = Random): String {
        val rootCount = rollSpiritRootCount(random.nextDouble())
        return ELEMENTS.shuffled(random).take(rootCount).joinToString(",")
    }

    /**
     * 根据随机值和 COUNT_WEIGHTS 配置决定灵根数量。
     * 统一从 [GameConfig.SpiritRoot.COUNT_WEIGHTS] 读取权重（单一权重表来源，
     * 与 `GameConfig.SpiritRoot.rollSpiritRootCount` 同式）。
     */
    private fun rollSpiritRootCount(rand: Double): Int =
        GameConfig.SpiritRoot.rollSpiritRootCount(rand)
}
