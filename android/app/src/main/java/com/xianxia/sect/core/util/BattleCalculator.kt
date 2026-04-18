@file:Suppress("DEPRECATION")

package com.xianxia.sect.core.util

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.HealType
import com.xianxia.sect.core.SkillType
import com.xianxia.sect.core.engine.CombatBuff
import com.xianxia.sect.core.engine.CombatSkill
import com.xianxia.sect.core.engine.Combatant
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

    data class DotResult(
        val combatant: Combatant,
        val damage: Int,
        val newHp: Int
    )

    data class SupportResult(
        val healAmount: Int,
        val healedIds: List<String>,
        val teamBuffs: Map<String, List<CombatBuff>>
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

    fun calculateCombatantDamage(
        attacker: Combatant,
        defender: Combatant,
        skill: CombatSkill? = null
    ): DamageResult {
        val isSkillAttack = skill != null
        val dodgeModifier = if (isSkillAttack) 0.3 else 0.5
        val maxDodgeChance = if (isSkillAttack) GameConfig.Battle.MAX_SKILL_DODGE_CHANCE else GameConfig.Battle.MAX_DODGE_CHANCE
        val dodgeChance = calculateCombatantDodgeChance(attacker, defender, dodgeModifier, maxDodgeChance)

        if (Random.nextDouble() < dodgeChance) {
            return DamageResult(
                damage = 0,
                isCrit = false,
                isPhysical = if (isSkillAttack) skill!!.damageType == DamageType.PHYSICAL else attacker.physicalAttack >= attacker.magicAttack,
                isDodged = true,
                skillName = skill?.name,
                hits = skill?.hits ?: 1
            )
        }

        val isPhysical = if (isSkillAttack) skill!!.damageType == DamageType.PHYSICAL else attacker.physicalAttack >= attacker.magicAttack
        val attack = if (isPhysical) attacker.effectivePhysicalAttack else attacker.effectiveMagicAttack
        val defense = if (isPhysical) defender.effectivePhysicalDefense else defender.effectiveMagicDefense

        val isCrit = Random.nextDouble() < attacker.effectiveCritRate
        val critMultiplier = if (isCrit) GameConfig.Battle.CRIT_MULTIPLIER else 1.0
        val skillMultiplier = skill?.damageMultiplier ?: 1.0

        val reduction = defense.toDouble() / (defense.toDouble() + GameConfig.Battle.DEFENSE_CONSTANT)
        val realmGapMultiplier = calculateRealmGapMultiplier(attacker.realm, defender.realm)
        val elementMultiplier = calculateElementMultiplier(attacker.element, defender.element)

        val baseDamage = (attack.toDouble() * skillMultiplier * (1.0 - reduction) * critMultiplier * realmGapMultiplier * elementMultiplier).toInt()
        val variance = Random.nextDouble(GameConfig.Battle.DAMAGE_VARIANCE_MIN, GameConfig.Battle.DAMAGE_VARIANCE_MAX)
        val finalDamage = (baseDamage * variance).toInt().coerceAtLeast(GameConfig.Battle.MIN_DAMAGE).coerceAtMost(Int.MAX_VALUE / 2)

        return DamageResult(
            damage = finalDamage,
            isCrit = isCrit,
            isPhysical = isPhysical,
            isDodged = false,
            skillName = skill?.name,
            hits = skill?.hits ?: 1
        )
    }

    private fun calculateCombatantDodgeChance(
        attacker: Combatant,
        defender: Combatant,
        modifier: Double,
        maxDodgeChance: Double = GameConfig.Battle.MAX_DODGE_CHANCE
    ): Double {
        val speedDiff = attacker.effectiveSpeed - defender.effectiveSpeed
        val totalSpeed = (attacker.effectiveSpeed + defender.effectiveSpeed).coerceAtLeast(1)
        return (speedDiff.toDouble() / totalSpeed * modifier).coerceIn(0.0, maxDodgeChance)
    }

    fun selectSkill(
        combatant: Combatant,
        enemies: List<Combatant>,
        allies: List<Combatant>,
        isSilenced: Boolean
    ): CombatSkill? {
        if (isSilenced) return null
        if (combatant.skills.isEmpty()) return null

        val availableSkills = combatant.skills.filter {
            it.currentCooldown == 0 && combatant.mp >= it.mpCost
        }
        if (availableSkills.isEmpty()) return null

        val supportSkills = availableSkills.filter { it.skillType == SkillType.SUPPORT }
        val attackSkills = availableSkills.filter { it.skillType == SkillType.ATTACK }

        val lowHpAllies = allies.filter { it.hpPercent < 0.3 }
        if (lowHpAllies.isNotEmpty() && supportSkills.isNotEmpty() && Random.nextDouble() < 0.8) {
            return supportSkills.first()
        }

        val controlSkills = attackSkills.filter { skill ->
            skill.buffType != null && skill.buffDuration > 0 && skill.buffType.isDebuff &&
                skill.buffType in setOf(BuffType.STUN, BuffType.FREEZE, BuffType.SILENCE, BuffType.TAUNT)
        }
        val uncontrolledEnemies = enemies.filter { enemy -> !enemy.hasControlEffect }
        if (uncontrolledEnemies.isNotEmpty() && controlSkills.isNotEmpty() && Random.nextDouble() < 0.6) {
            return controlSkills.first()
        }

        val aoeSkills = attackSkills.filter { it.isAoe }
        if (enemies.size >= 3 && aoeSkills.isNotEmpty() && Random.nextDouble() < 0.7) {
            return aoeSkills.maxByOrNull { it.damageMultiplier }
        }

        if (combatant.mpPercent < 0.3 && attackSkills.isNotEmpty()) {
            val cheapSkill = attackSkills.minByOrNull { it.mpCost }
            if (cheapSkill != null && combatant.mp >= cheapSkill.mpCost * 2) {
                return cheapSkill
            }
            return null
        }

        if (attackSkills.isNotEmpty()) {
            return attackSkills.maxByOrNull { it.damageMultiplier / it.mpCost.coerceAtLeast(1) }
        }

        return availableSkills.firstOrNull()
    }

    fun selectTarget(attacker: Combatant, targets: List<Combatant>): Combatant {
        val lowHpTargets = targets.filter { it.hpPercent < 0.3 }
        if (lowHpTargets.isNotEmpty() && Random.nextDouble() < 0.7) {
            return lowHpTargets.random()
        }

        val highThreatTargets = targets.filter { target ->
            target.skills.isNotEmpty() && target.effectivePhysicalAttack > attacker.effectivePhysicalDefense
        }
        if (highThreatTargets.isNotEmpty() && Random.nextDouble() < 0.5) {
            return highThreatTargets.random()
        }

        val lowDefenseTargets = targets.filter { target ->
            val avgDefense = (target.effectivePhysicalDefense + target.effectiveMagicDefense) / 2.0
            avgDefense < attacker.effectivePhysicalAttack * 0.5
        }
        if (lowDefenseTargets.isNotEmpty() && Random.nextDouble() < 0.4) {
            return lowDefenseTargets.random()
        }

        val elementAdvantageTargets = targets.filter { target ->
            calculateElementMultiplier(attacker.element, target.element) > 1.0
        }
        if (elementAdvantageTargets.isNotEmpty() && Random.nextDouble() < 0.3) {
            return elementAdvantageTargets.random()
        }

        return targets.random()
    }

    fun processDotEffects(combatants: List<Combatant>): List<DotResult> {
        val results = mutableListOf<DotResult>()

        for (combatant in combatants) {
            val poisonBuffs = combatant.buffs.filter { it.type == BuffType.POISON && it.remainingDuration > 0 }
            val burnBuffs = combatant.buffs.filter { it.type == BuffType.BURN && it.remainingDuration > 0 }

            var dotDamage = 0
            poisonBuffs.forEach { buff -> dotDamage += (combatant.maxHp * buff.value).toInt() }
            burnBuffs.forEach { buff -> dotDamage += (combatant.maxHp * buff.value).toInt() }

            if (dotDamage > 0) {
                val newHp = maxOf(0, combatant.hp - dotDamage)
                results.add(DotResult(combatant, dotDamage, newHp))
            }
        }

        return results
    }

    fun executeSupportSkill(
        caster: Combatant,
        allies: List<Combatant>,
        skill: CombatSkill
    ): SupportResult {
        val targets = if (skill.targetScope == "team") allies else listOf(caster)

        var healAmount = 0
        val teamBuffs = mutableMapOf<String, List<CombatBuff>>()

        if (skill.healPercent > 0) {
            healAmount = if (skill.healType == HealType.MP) {
                (caster.maxMp * skill.healPercent).toInt()
            } else {
                (caster.maxHp * skill.healPercent).toInt()
            }
        }

        if (skill.buffType != null && skill.buffDuration > 0) {
            val buff = CombatBuff(
                type = skill.buffType,
                value = skill.buffValue,
                remainingDuration = skill.buffDuration
            )
            for (member in targets) {
                teamBuffs[member.id] = listOf(buff)
            }
        }

        for ((buffType, buffValue, buffDuration) in skill.buffs) {
            val buff = CombatBuff(
                type = buffType,
                value = buffValue,
                remainingDuration = buffDuration
            )
            for (member in targets) {
                val existing = teamBuffs[member.id] ?: emptyList()
                teamBuffs[member.id] = existing + buff
            }
        }

        return SupportResult(
            healAmount = healAmount,
            healedIds = if (healAmount > 0) targets.map { it.id } else emptyList(),
            teamBuffs = teamBuffs
        )
    }

    fun updateCombatantCooldowns(combatant: Combatant, usedSkill: CombatSkill): Combatant {
        val updatedSkills = combatant.skills.map { skill ->
            if (skill.name == usedSkill.name) {
                skill.copy(currentCooldown = skill.cooldown)
            } else {
                skill.copy(currentCooldown = maxOf(0, skill.currentCooldown - 1))
            }
        }
        val existingBuffs = combatant.buffs
            .map { it.copy(remainingDuration = maxOf(0, it.remainingDuration - 1)) }
            .filter { it.remainingDuration > 0 }
        return combatant.copy(
            mp = combatant.mp - usedSkill.mpCost,
            skills = updatedSkills,
            buffs = existingBuffs
        )
    }

    fun updateCombatantBuffsOnly(combatant: Combatant): Combatant {
        val newBuffs = combatant.buffs
            .map { it.copy(remainingDuration = maxOf(0, it.remainingDuration - 1)) }
            .filter { it.remainingDuration > 0 }
        return combatant.copy(buffs = newBuffs)
    }
}
