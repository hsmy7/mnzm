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
     * 统一从 [GameConfig.SpiritRoot.COUNT_WEIGHTS] 读取权重，消除与 [generateRandomSpiritRootCount] 的硬编码双路径。
     */
    private fun rollSpiritRootCount(rand: Double): Int {
        var cumulative = 0.0
        for ((count, weight) in GameConfig.SpiritRoot.COUNT_WEIGHTS.toSortedMap()) {
            cumulative += weight
            if (rand < cumulative) return count
        }
        DomainLog.w("SpiritRoot", "灵根权重和<1.0（累积=$cumulative），回退到5灵根，请检查COUNT_WEIGHTS配置")
        return 5
    }

    fun generateWithGameRandom(): String {
        val rootCount = GameConfig.SpiritRoot.generateRandomSpiritRootCount()
        val shuffled = ELEMENTS.toMutableList()
        for (i in shuffled.indices) {
            val j = GameRandom.nextInt(i, shuffled.size)
            val tmp = shuffled[i]
            shuffled[i] = shuffled[j]
            shuffled[j] = tmp
        }
        return shuffled.take(rootCount).joinToString(",")
    }
}
