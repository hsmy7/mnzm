package com.xianxia.sect.core.engine

object HerbGardenSystem {

    const val MAX_PLANT_SLOTS = 3
    
    fun calculateReducedDuration(baseDuration: Int, speedBonus: Double): Int {
        if (speedBonus <= 0) return baseDuration
        val reductionPercent = (speedBonus / 2.5).coerceAtMost(0.80)
        val reducedMonths = (baseDuration * reductionPercent).toInt()
        return (baseDuration - reducedMonths).coerceAtLeast(1)
    }
    
    fun calculateIncreasedYield(baseYield: Int, yieldBonus: Double): Int {
        if (yieldBonus <= 0) return baseYield
        val bonusYield = (baseYield * yieldBonus).toInt()
        return (baseYield + bonusYield).coerceAtLeast(1)
    }
}
