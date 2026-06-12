package com.xianxia.sect.core.engine.domain.building

object HerbGardenSystem {

    fun calculateIncreasedYield(baseYield: Int, yieldBonus: Double): Int {
        if (yieldBonus <= 0) return baseYield
        val bonusYield = (baseYield * yieldBonus).toInt()
        return (baseYield + bonusYield).coerceAtLeast(1)
    }
}
