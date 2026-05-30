package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStats
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData

object SectCombatPowerCalculator {

    fun calculateDiscipleCombatPower(stats: DiscipleStats): Long {
        return maxOf(stats.physicalAttack, stats.magicAttack) * 5L +
               stats.maxHp * 2L +
               (stats.physicalDefense + stats.magicDefense) * 3L +
               stats.speed * 2L
    }

    fun calculatePlayerDisciplePower(
        aggregate: DiscipleAggregate,
        equipments: Map<String, EquipmentInstance>,
        manuals: Map<String, ManualInstance>,
        manualProficiencies: Map<String, ManualProficiencyData> = emptyMap()
    ): Long {
        val finalStats = DiscipleStatCalculator.getFinalStats(aggregate, equipments, manuals, manualProficiencies)
        return calculateDiscipleCombatPower(finalStats)
    }

    fun calculateAIDisciplePower(aggregate: DiscipleAggregate): Long {
        val baseStats = DiscipleStatCalculator.getBaseStats(aggregate)
        return calculateDiscipleCombatPower(baseStats) * 3
    }

    fun computePlayerFingerprint(aggregate: DiscipleAggregate): Int {
        var result = 1
        result = 31 * result + aggregate.realm
        result = 31 * result + aggregate.realmLayer
        result = 31 * result + aggregate.basePhysicalAttack
        result = 31 * result + aggregate.baseMagicAttack
        result = 31 * result + aggregate.basePhysicalDefense
        result = 31 * result + aggregate.baseMagicDefense
        result = 31 * result + aggregate.baseSpeed
        result = 31 * result + aggregate.baseHp
        result = 31 * result + aggregate.hpVariance
        result = 31 * result + aggregate.mpVariance
        result = 31 * result + aggregate.physicalAttackVariance
        result = 31 * result + aggregate.magicAttackVariance
        result = 31 * result + aggregate.physicalDefenseVariance
        result = 31 * result + aggregate.magicDefenseVariance
        result = 31 * result + aggregate.speedVariance
        result = 31 * result + aggregate.pillPhysicalAttackBonus
        result = 31 * result + aggregate.pillMagicAttackBonus
        result = 31 * result + aggregate.pillPhysicalDefenseBonus
        result = 31 * result + aggregate.pillMagicDefenseBonus
        result = 31 * result + aggregate.pillHpBonus
        result = 31 * result + aggregate.pillMpBonus
        result = 31 * result + aggregate.pillSpeedBonus
        result = 31 * result + aggregate.pillEffectDuration
        result = 31 * result + aggregate.weaponId.hashCode()
        result = 31 * result + aggregate.armorId.hashCode()
        result = 31 * result + aggregate.bootsId.hashCode()
        result = 31 * result + aggregate.accessoryId.hashCode()
        result = 31 * result + aggregate.manualIds.hashCode()
        result = 31 * result + aggregate.talentIds.hashCode()
        result = 31 * result + aggregate.weaponNurture.nurtureLevel
        result = 31 * result + aggregate.armorNurture.nurtureLevel
        result = 31 * result + aggregate.bootsNurture.nurtureLevel
        result = 31 * result + aggregate.accessoryNurture.nurtureLevel
        return result
    }

    fun computeAIFingerprint(aggregate: DiscipleAggregate): Int {
        var result = 1
        result = 31 * result + aggregate.realm
        result = 31 * result + aggregate.realmLayer
        result = 31 * result + aggregate.talentIds.hashCode()
        result = 31 * result + aggregate.hpVariance
        result = 31 * result + aggregate.physicalAttackVariance
        result = 31 * result + aggregate.magicAttackVariance
        result = 31 * result + aggregate.physicalDefenseVariance
        result = 31 * result + aggregate.magicDefenseVariance
        result = 31 * result + aggregate.speedVariance
        return result
    }
}
