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
                baseHp = disciple.combat.baseHp,
                baseMp = disciple.combat.baseMp,
                basePhysicalAttack = disciple.combat.basePhysicalAttack,
                baseMagicAttack = disciple.combat.baseMagicAttack,
                basePhysicalDefense = disciple.combat.basePhysicalDefense,
                baseMagicDefense = disciple.combat.baseMagicDefense,
                baseSpeed = disciple.combat.baseSpeed,
                hpVariance = disciple.combat.hpVariance,
                mpVariance = disciple.combat.mpVariance,
                physicalAttackVariance = disciple.combat.physicalAttackVariance,
                magicAttackVariance = disciple.combat.magicAttackVariance,
                physicalDefenseVariance = disciple.combat.physicalDefenseVariance,
                magicDefenseVariance = disciple.combat.magicDefenseVariance,
                speedVariance = disciple.combat.speedVariance,
                pillPhysicalAttackBonus = disciple.pillEffects.pillPhysicalAttackBonus,
                pillMagicAttackBonus = disciple.pillEffects.pillMagicAttackBonus,
                pillPhysicalDefenseBonus = disciple.pillEffects.pillPhysicalDefenseBonus,
                pillMagicDefenseBonus = disciple.pillEffects.pillMagicDefenseBonus,
                pillHpBonus = disciple.pillEffects.pillHpBonus,
                pillMpBonus = disciple.pillEffects.pillMpBonus,
                pillSpeedBonus = disciple.pillEffects.pillSpeedBonus,
                pillCritRateBonus = disciple.pillEffects.pillCritRateBonus,
                pillCritEffectBonus = disciple.pillEffects.pillCritEffectBonus,
                pillCultivationSpeedBonus = disciple.pillEffects.pillCultivationSpeedBonus,
                pillSkillExpSpeedBonus = disciple.pillEffects.pillSkillExpSpeedBonus,
                pillNurtureSpeedBonus = disciple.pillEffects.pillNurtureSpeedBonus,
                pillEffectDuration = disciple.pillEffects.pillEffectDuration,
                activePillCategory = disciple.pillEffects.activePillCategory,
                totalCultivation = disciple.combat.totalCultivation,
                breakthroughCount = disciple.combat.breakthroughCount,
                breakthroughFailCount = disciple.combat.breakthroughFailCount,
                currentHp = disciple.combat.currentHp,
                currentMp = disciple.combat.currentMp
            )
        }
    }
}
