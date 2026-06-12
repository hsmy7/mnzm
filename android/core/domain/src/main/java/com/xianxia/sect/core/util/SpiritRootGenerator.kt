package com.xianxia.sect.core.util

import com.xianxia.sect.core.GameConfig
import kotlin.random.Random

object SpiritRootGenerator {

    private val ELEMENTS = listOf("metal", "wood", "water", "fire", "earth")

    fun generate(random: Random = Random): String {
        val rootCount = when (random.nextInt(100)) {
            in 0..4 -> 1
            in 5..24 -> 2
            in 25..54 -> 3
            in 55..84 -> 4
            else -> 5
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
