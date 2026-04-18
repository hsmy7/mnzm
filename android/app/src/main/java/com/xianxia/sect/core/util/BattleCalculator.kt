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
        val realm: Int
        val element: String
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

        val reduction = defense.toDouble() / (defense.toDouble() + GameConfig.Battle.DEFENSE_CONSTANT)
        val realmGapMultiplier = calculateRealmGapMultiplier(attacker.realm, defender.realm)
        val elementMultiplier = calculateElementMultiplier(attacker.element, defender.element)

        val baseDamage = (attack * skillDamageMultiplier * (1.0 - reduction) * critMultiplier * realmGapMultiplier * elementMultiplier).toInt()
        val variance = Random.nextDouble(GameConfig.Battle.DAMAGE_VARIANCE_MIN, GameConfig.Battle.DAMAGE_VARIANCE_MAX)
        val finalDamage = (baseDamage * variance).toInt().coerceAtLeast(GameConfig.Battle.MIN_DAMAGE).coerceAtMost(Int.MAX_VALUE / 2)

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
        return (speedDiff.toDouble() / totalSpeed * modifier).coerceIn(0.0, GameConfig.Battle.MAX_DODGE_CHANCE)
    }

    fun calculateRealmGapMultiplier(attackerRealm: Int, defenderRealm: Int): Double {
        val gap = attackerRealm - defenderRealm
        val absGap = kotlin.math.abs(gap).coerceAtMost(GameConfig.Battle.RealmGap.MAX_REALM_GAP)
        if (absGap == 0) return 1.0

        val ratio = if (gap > 0) {
            1.0 + absGap * GameConfig.Battle.RealmGap.DAMAGE_BONUS_PER_REALM
        } else {
            1.0 - absGap * GameConfig.Battle.RealmGap.DAMAGE_PENALTY_PER_REALM
        }

        return ratio.coerceIn(GameConfig.Battle.RealmGap.MIN_DAMAGE_RATIO, GameConfig.Battle.RealmGap.MAX_DAMAGE_RATIO)
    }

    fun calculateElementMultiplier(attackerElement: String, defenderElement: String): Double {
        if (attackerElement.isEmpty() || defenderElement.isEmpty()) return 1.0
        val advantage = GameConfig.Battle.Element.ADVANTAGES[attackerElement]
        return when {
            advantage == defenderElement -> GameConfig.Battle.Element.ADVANTAGE_MULTIPLIER
            GameConfig.Battle.Element.ADVANTAGES[defenderElement] == attackerElement -> GameConfig.Battle.Element.DISADVANTAGE_MULTIPLIER
            else -> 1.0
        }
    }

    fun calculatePhysicalDamage(
        attacker: CombatantStats,
        defender: CombatantStats,
        multiplier: Double = 1.0
    ): Int {
        val isCrit = Random.nextDouble() < attacker.critRate
        val critMultiplier = if (isCrit) GameConfig.Battle.CRIT_MULTIPLIER else 1.0
        val reduction = defender.physicalDefense.toDouble() / (defender.physicalDefense.toDouble() + GameConfig.Battle.DEFENSE_CONSTANT)
        val realmGapMultiplier = calculateRealmGapMultiplier(attacker.realm, defender.realm)
        val elementMultiplier = calculateElementMultiplier(attacker.element, defender.element)
        val baseDamage = (attacker.physicalAttack * multiplier * (1.0 - reduction) * critMultiplier * realmGapMultiplier * elementMultiplier).toInt()
        val variance = Random.nextDouble(GameConfig.Battle.DAMAGE_VARIANCE_MIN, GameConfig.Battle.DAMAGE_VARIANCE_MAX)
        return (baseDamage * variance).toInt().coerceAtLeast(GameConfig.Battle.MIN_DAMAGE).coerceAtMost(Int.MAX_VALUE / 2)
    }

    fun calculateMagicDamage(
        attacker: CombatantStats,
        defender: CombatantStats,
        multiplier: Double = 1.0
    ): Int {
        val isCrit = Random.nextDouble() < attacker.critRate
        val critMultiplier = if (isCrit) GameConfig.Battle.CRIT_MULTIPLIER else 1.0
        val reduction = defender.magicDefense.toDouble() / (defender.magicDefense.toDouble() + GameConfig.Battle.DEFENSE_CONSTANT)
        val realmGapMultiplier = calculateRealmGapMultiplier(attacker.realm, defender.realm)
        val elementMultiplier = calculateElementMultiplier(attacker.element, defender.element)
        val baseDamage = (attacker.magicAttack * multiplier * (1.0 - reduction) * critMultiplier * realmGapMultiplier * elementMultiplier).toInt()
        val variance = Random.nextDouble(GameConfig.Battle.DAMAGE_VARIANCE_MIN, GameConfig.Battle.DAMAGE_VARIANCE_MAX)
        return (baseDamage * variance).toInt().coerceAtLeast(GameConfig.Battle.MIN_DAMAGE).coerceAtMost(Int.MAX_VALUE / 2)
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
