package com.xianxia.sect.core.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "disciples_combat",
    indices = [
        Index(value = ["discipleId"], unique = true)
    ],
    foreignKeys = [
        ForeignKey(
            entity = DiscipleCore::class,
            parentColumns = ["id"],
            childColumns = ["discipleId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE
        )
    ]
)
data class DiscipleCombatStats(
    @PrimaryKey
    var discipleId: String = "",
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
    var pillPhysicalAttackBonus: Double = 0.0,
    var pillMagicAttackBonus: Double = 0.0,
    var pillPhysicalDefenseBonus: Double = 0.0,
    var pillMagicDefenseBonus: Double = 0.0,
    var pillHpBonus: Double = 0.0,
    var pillMpBonus: Double = 0.0,
    var pillSpeedBonus: Double = 0.0,
    var pillEffectDuration: Int = 0,
    var battlesWon: Int = 0,
    var totalCultivation: Long = 0,
    var breakthroughCount: Int = 0,
    var breakthroughFailCount: Int = 0
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
                pillEffectDuration = disciple.pillEffectDuration,
                battlesWon = disciple.battlesWon,
                totalCultivation = disciple.totalCultivation,
                breakthroughCount = disciple.breakthroughCount,
                breakthroughFailCount = disciple.breakthroughFailCount
            )
        }
        
        fun calculateBaseStatsWithVariance(
            hpVariance: Int,
            mpVariance: Int,
            physicalAttackVariance: Int,
            magicAttackVariance: Int,
            physicalDefenseVariance: Int,
            magicDefenseVariance: Int,
            speedVariance: Int
        ): BaseCombatStats {
            return BaseCombatStats(
                baseHp = (120 * (1.0 + hpVariance / 100.0)).toInt(),
                baseMp = (60 * (1.0 + mpVariance / 100.0)).toInt(),
                basePhysicalAttack = (12 * (1.0 + physicalAttackVariance / 100.0)).toInt(),
                baseMagicAttack = (12 * (1.0 + magicAttackVariance / 100.0)).toInt(),
                basePhysicalDefense = (10 * (1.0 + physicalDefenseVariance / 100.0)).toInt(),
                baseMagicDefense = (8 * (1.0 + magicDefenseVariance / 100.0)).toInt(),
                baseSpeed = (15 * (1.0 + speedVariance / 100.0)).toInt()
            )
        }
    }
}
