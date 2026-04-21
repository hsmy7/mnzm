package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot

object EquipmentNurtureSystem {

    const val BASE_EXP_GAIN = 10.0

    const val AUTO_EXP_PER_SECOND = 1.0

    const val NURTURE_BONUS_PER_LEVEL = 0.05

    fun getMaxNurtureLevel(rarity: Int): Int {
        return when (rarity) {
            1 -> 5
            2 -> 9
            3 -> 13
            4 -> 17
            5 -> 21
            6 -> 25
            else -> 5
        }
    }

    fun getExpRequiredForLevelUp(currentLevel: Int, rarity: Int): Double {
        val maxLevel = getMaxNurtureLevel(rarity)
        if (currentLevel >= maxLevel) return Double.MAX_VALUE

        val baseExp = 100.0 * (currentLevel + 1)

        val rarityMultiplier = when (rarity) {
            1 -> 1.0
            2 -> 1.5
            3 -> 2.0
            4 -> 3.0
            5 -> 4.5
            6 -> 6.0
            else -> 1.0
        }

        return baseExp * rarityMultiplier
    }

    fun calculateExpGain(equipment: EquipmentInstance, isVictory: Boolean): Double {
        if (!isVictory) return 0.0
        val expRequired = getExpRequiredForLevelUp(equipment.nurtureLevel, equipment.rarity)
        return expRequired * 0.1
    }

    fun calculateAutoExpGain(rarity: Int): Double {
        val rarityMultiplier = when (rarity) {
            1 -> 1.0
            2 -> 1.2
            3 -> 1.4
            4 -> 1.6
            5 -> 1.8
            6 -> 2.0
            else -> 1.0
        }
        return AUTO_EXP_PER_SECOND * rarityMultiplier
    }

    fun updateNurtureExp(
        equipment: EquipmentInstance,
        expGain: Double
    ): NurtureResult {
        val maxLevel = getMaxNurtureLevel(equipment.rarity)
        if (equipment.nurtureLevel >= maxLevel) {
            return NurtureResult(equipment, false)
        }

        val newProgress = equipment.nurtureProgress + expGain
        val expRequired = getExpRequiredForLevelUp(equipment.nurtureLevel, equipment.rarity)

        return if (newProgress >= expRequired) {
            val newLevel = (equipment.nurtureLevel + 1).coerceAtMost(maxLevel)
            val remainingExp = newProgress - expRequired

            val updatedEquipment = equipment.copy(
                nurtureLevel = newLevel,
                nurtureProgress = if (newLevel >= maxLevel) 0.0 else remainingExp
            )
            NurtureResult(updatedEquipment, true)
        } else {
            val updatedEquipment = equipment.copy(
                nurtureProgress = newProgress
            )
            NurtureResult(updatedEquipment, false)
        }
    }

    fun getNurtureMultiplier(nurtureLevel: Int): Double {
        if (nurtureLevel <= 0) return 1.0
        val maxLevel = 25
        val actualLevel = nurtureLevel.coerceAtMost(maxLevel)
        val totalBonus = actualLevel * (actualLevel + 1) / 2.0 * (3.0 / 325.0)
        return (1.0 + totalBonus).coerceAtMost(4.0)
    }

    fun getNurtureProgressPercent(equipment: EquipmentInstance): Int {
        val maxLevel = getMaxNurtureLevel(equipment.rarity)
        if (equipment.nurtureLevel >= maxLevel) return 100

        val expRequired = getExpRequiredForLevelUp(equipment.nurtureLevel, equipment.rarity)
        return ((equipment.nurtureProgress / expRequired) * 100).toInt().coerceIn(0, 100)
    }

    fun getTotalMultiplier(equipment: EquipmentInstance): Double {
        val rarityMult = getRarityMultiplier(equipment.rarity)
        val nurtureMult = getNurtureMultiplier(equipment.nurtureLevel)
        return rarityMult * nurtureMult
    }

    private fun getRarityMultiplier(rarity: Int): Double {
        return when (rarity) {
            1 -> 1.0
            2 -> 1.3
            3 -> 1.6
            4 -> 2.0
            5 -> 2.5
            6 -> 3.0
            else -> 1.0
        }
    }

    data class NurtureResult(
        val equipment: EquipmentInstance,
        val leveledUp: Boolean
    )
}
