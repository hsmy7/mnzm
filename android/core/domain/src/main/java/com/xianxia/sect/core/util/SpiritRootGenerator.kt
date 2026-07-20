package com.xianxia.sect.core.util

import com.xianxia.sect.core.GameConfig
import kotlin.random.Random

object SpiritRootGenerator {

    private val ELEMENTS = listOf("metal", "wood", "water", "fire", "earth")

    fun generate(random: Random = Random): String {
        val rootCount = when (val roll = random.nextDouble()) {
            in 0.0..<0.005 -> 1      // 0.5% 单灵根
            in 0.005..<0.020 -> 2    // 1.5% 双灵根
            in 0.020..<0.220 -> 3    // 20%  三灵根
            in 0.220..<0.600 -> 4    // 38%  四灵根
            else -> 5                 // 40%  五灵根
        }
        return ELEMENTS.shuffled(random).take(rootCount).joinToString(",")
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
