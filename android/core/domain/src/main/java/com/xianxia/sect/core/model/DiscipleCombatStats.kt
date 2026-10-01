package com.xianxia.sect.core.model

/**
 * 弟子战斗基值/方差的**内存侧**投影（v53/SR-7 起不再有 `disciples_combat` 表；
 * [DiscipleStatCalculator] 直接从 [DiscipleAggregate.combatStats] 读，真相恒在 `disciples`）。
 *
 * 攻防单列口径（B1，方案 §15）：baseAttack/baseDefense + attackVariance/defenseVariance。
 */
data class DiscipleCombatStats(
    var discipleId: String = "",

    var baseHp: Int = 120,
    var baseMp: Int = 60,
    var baseAttack: Int = 24,
    var baseDefense: Int = 18,
    var baseSpeed: Int = 15,
    var hpVariance: Int = 0,
    var mpVariance: Int = 0,
    var attackVariance: Int = 0,
    var defenseVariance: Int = 0,
    var speedVariance: Int = 0,
    var pillAttackBonus: Int = 0,
    var pillDefenseBonus: Int = 0,
    var pillHpBonus: Int = 0,
    var pillMpBonus: Int = 0,
    var pillSpeedBonus: Int = 0,
    var pillCritRateBonus: Double = 0.0,
    var pillCritEffectBonus: Double = 0.0,
    var pillCultivationSpeedBonus: Double = 0.0,
    var pillSkillExpSpeedBonus: Double = 0.0,
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
                baseAttack = disciple.combat.baseAttack,
                baseDefense = disciple.combat.baseDefense,
                baseSpeed = disciple.combat.baseSpeed,
                hpVariance = disciple.combat.hpVariance,
                mpVariance = disciple.combat.mpVariance,
                attackVariance = disciple.combat.attackVariance,
                defenseVariance = disciple.combat.defenseVariance,
                speedVariance = disciple.combat.speedVariance,
                pillAttackBonus = disciple.pillEffects.pillAttackBonus,
                pillDefenseBonus = disciple.pillEffects.pillDefenseBonus,
                pillHpBonus = disciple.pillEffects.pillHpBonus,
                pillMpBonus = disciple.pillEffects.pillMpBonus,
                pillSpeedBonus = disciple.pillEffects.pillSpeedBonus,
                pillCritRateBonus = disciple.pillEffects.pillCritRateBonus,
                pillCritEffectBonus = disciple.pillEffects.pillCritEffectBonus,
                pillCultivationSpeedBonus = disciple.pillEffects.pillCultivationSpeedBonus,
                pillSkillExpSpeedBonus = disciple.pillEffects.pillSkillExpSpeedBonus,
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
