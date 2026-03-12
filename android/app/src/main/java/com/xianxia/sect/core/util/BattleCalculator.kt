package com.xianxia.sect.core.util

import com.xianxia.sect.core.GameConfig
import kotlin.random.Random

object BattleCalculator {
    
    data class DamageResult(
        val damage: Int,
        val isCrit: Boolean,
        val isPhysical: Boolean,
        val isDodged: Boolean = false,
        val skillName: String? = null,
        val hits: Int = 1
    )
    
    interface CombatantStats {
        val physicalAttack: Int
        val magicAttack: Int
        val physicalDefense: Int
        val magicDefense: Int
        val speed: Int
        val critRate: Double
    }
    
    fun calculateDamage(
        attacker: CombatantStats,
        defender: CombatantStats,
        skillDamageMultiplier: Double = 1.0,
        isPhysicalAttack: Boolean? = null,
        skillName: String? = null,
        skillHits: Int = 1,
        dodgeChanceModifier: Double = 0.5
    ): DamageResult {
        val dodgeChance = calculateDodgeChance(attacker, defender, dodgeChanceModifier)
        if (Random.nextDouble() < dodgeChance) {
            return DamageResult(
                damage = 0,
                isCrit = false,
                isPhysical = isPhysicalAttack ?: true,
                isDodged = true,
                skillName = skillName,
                hits = skillHits
            )
        }
        
        val usePhysical = isPhysicalAttack ?: (attacker.physicalAttack >= attacker.magicAttack)
        val attack = if (usePhysical) attacker.physicalAttack else attacker.magicAttack
        val defense = if (usePhysical) defender.physicalDefense else defender.magicDefense
        
        val isCrit = Random.nextDouble() < attacker.critRate
        val critMultiplier = if (isCrit) GameConfig.Battle.CRIT_MULTIPLIER else 1.0
        
        val baseDamage = (attack * skillDamageMultiplier * critMultiplier - defense).coerceAtLeast(1.0)
        val variance = Random.nextDouble(0.9, 1.1)
        val finalDamage = (baseDamage * variance).toInt().coerceAtMost(Int.MAX_VALUE / 2)
        
        return DamageResult(
            damage = finalDamage,
            isCrit = isCrit,
            isPhysical = usePhysical,
            isDodged = false,
            skillName = skillName,
            hits = skillHits
        )
    }
    
    fun calculateDodgeChance(
        attacker: CombatantStats,
        defender: CombatantStats,
        modifier: Double = 0.5
    ): Double {
        val speedDiff = attacker.speed - defender.speed
        val totalSpeed = (attacker.speed + defender.speed).coerceAtLeast(1)
        return (speedDiff.toDouble() / totalSpeed * modifier).coerceIn(0.0, 0.5)
    }
    
    fun calculatePhysicalDamage(
        attacker: CombatantStats,
        defender: CombatantStats,
        multiplier: Double = 1.0
    ): Int {
        val isCrit = Random.nextDouble() < attacker.critRate
        val critMultiplier = if (isCrit) GameConfig.Battle.CRIT_MULTIPLIER else 1.0
        val baseDamage = (attacker.physicalAttack * multiplier * critMultiplier - defender.physicalDefense)
            .coerceAtLeast(1.0)
        val variance = Random.nextDouble(0.9, 1.1)
        return (baseDamage * variance).toInt().coerceAtMost(Int.MAX_VALUE / 2)
    }
    
    fun calculateMagicDamage(
        attacker: CombatantStats,
        defender: CombatantStats,
        multiplier: Double = 1.0
    ): Int {
        val isCrit = Random.nextDouble() < attacker.critRate
        val critMultiplier = if (isCrit) GameConfig.Battle.CRIT_MULTIPLIER else 1.0
        val baseDamage = (attacker.magicAttack * multiplier * critMultiplier - defender.magicDefense)
            .coerceAtLeast(1.0)
        val variance = Random.nextDouble(0.9, 1.1)
        return (baseDamage * variance).toInt().coerceAtMost(Int.MAX_VALUE / 2)
    }
    
    fun generateBattleMessage(
        attackerName: String,
        targetName: String,
        result: DamageResult
    ): String {
        if (result.isDodged) {
            return "$targetName 闪避了 $attackerName 的攻击！"
        }
        
        val damageType = if (result.isPhysical) "物理" else "法术"
        val skillPrefix = result.skillName?.let { "使用[$it] " } ?: ""
        var message = "$attackerName ${skillPrefix}对 $targetName 造成 ${result.damage} 点${damageType}伤害"
        
        if (result.isCrit) message += "（暴击！）"
        if (result.hits > 1) message += "（${result.hits}连击）"
        
        return message
    }
}
