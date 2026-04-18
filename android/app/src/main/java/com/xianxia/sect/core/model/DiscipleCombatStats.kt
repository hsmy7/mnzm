@file:Suppress("DEPRECATION")

package com.xianxia.sect.core.model

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "disciples_combat",
    primaryKeys = ["discipleId", "slot_id"]
)
data class DiscipleCombatStats(
    @ColumnInfo(name = "discipleId")
    var discipleId: String = "",

    @ColumnInfo(name = "slot_id")
    var slotId: Int = 0,

    var baseHp: Int = 120,
    var baseMp: Int = 60,
    var basePhysicalAttack: Int = 12,
    var baseMagicAttack: Int = 12,
    var basePhysicalDefense: Int = 10,
    var baseMagicDefense: Int = 8,
    var baseSpeed: Int = 15,
    var hpVariance: Int = 0,
    var mpVariance: Int = 0,
    var physicalAttackVariance: Int = 0,
    var magicAttackVariance: Int = 0,
    var physicalDefenseVariance: Int = 0,
    var magicDefenseVariance: Int = 0,
    var speedVariance: Int = 0,
    var pillPhysicalAttackBonus: Int = 0,
    var pillMagicAttackBonus: Int = 0,
    var pillPhysicalDefenseBonus: Int = 0,
    var pillMagicDefenseBonus: Int = 0,
    var pillHpBonus: Int = 0,
    var pillMpBonus: Int = 0,
    var pillSpeedBonus: Int = 0,
    var pillCritRateBonus: Double = 0.0,
    var pillCritEffectBonus: Double = 0.0,
    var pillCultivationSpeedBonus: Double = 0.0,
    var pillSkillExpSpeedBonus: Double = 0.0,
    var pillNurtureSpeedBonus: Double = 0.0,
    var pillEffectDuration: Int = 0,
    var activePillCategory: String = "",
    var totalCultivation: Long = 0,
    var breakthroughCount: Int = 0,
    var breakthroughFailCount: Int = 0,
    var currentHp: Int = -1,
    var currentMp: Int = -1
) {
    companion object {
        fun fromDisciple(disciple: Disciple): DiscipleCombatStats {
            return DiscipleCombatStats(
                discipleId = disciple.id,
                baseHp = disciple.baseHp,
                baseMp = disciple.baseMp,
                basePhysicalAttack = disciple.basePhysicalAttack,
                baseMagicAttack = disciple.baseMagicAttack,
                basePhysicalDefense = disciple.basePhysicalDefense,
                baseMagicDefense = disciple.baseMagicDefense,
                baseSpeed = disciple.baseSpeed,
                hpVariance = disciple.hpVariance,
                mpVariance = disciple.mpVariance,
                physicalAttackVariance = disciple.physicalAttackVariance,
                magicAttackVariance = disciple.magicAttackVariance,
                physicalDefenseVariance = disciple.physicalDefenseVariance,
                magicDefenseVariance = disciple.magicDefenseVariance,
                speedVariance = disciple.speedVariance,
                pillPhysicalAttackBonus = disciple.pillPhysicalAttackBonus,
                pillMagicAttackBonus = disciple.pillMagicAttackBonus,
                pillPhysicalDefenseBonus = disciple.pillPhysicalDefenseBonus,
                pillMagicDefenseBonus = disciple.pillMagicDefenseBonus,
                pillHpBonus = disciple.pillHpBonus,
                pillMpBonus = disciple.pillMpBonus,
                pillSpeedBonus = disciple.pillSpeedBonus,
                pillCritRateBonus = disciple.pillCritRateBonus,
                pillCritEffectBonus = disciple.pillCritEffectBonus,
                pillCultivationSpeedBonus = disciple.pillCultivationSpeedBonus,
                pillSkillExpSpeedBonus = disciple.pillSkillExpSpeedBonus,
                pillNurtureSpeedBonus = disciple.pillNurtureSpeedBonus,
                pillEffectDuration = disciple.pillEffectDuration,
                activePillCategory = disciple.activePillCategory,
                totalCultivation = disciple.totalCultivation,
                breakthroughCount = disciple.breakthroughCount,
                breakthroughFailCount = disciple.breakthroughFailCount,
                currentHp = disciple.currentHp,
                currentMp = disciple.currentMp
            )
        }
    }
}
