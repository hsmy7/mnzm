package com.xianxia.sect.ui.game.dialogs.heavenlytrial

import com.xianxia.sect.core.SkillType
import com.xianxia.sect.core.HealType
import com.xianxia.sect.core.model.CombatSkill
import com.xianxia.sect.core.engine.domain.battle.BattleAI
import com.xianxia.sect.core.engine.domain.battle.CombatBuff
import com.xianxia.sect.core.engine.domain.battle.Combatant
import com.xianxia.sect.core.util.BattleCalculator

/**
 * 普攻伤害计算（带防御减伤）。
 * isDefending 在 BattleCalculator 结果上再叠加 25% 减伤（UI 特有机制）。
 */
internal fun computeNormalAttackDamage(
    attacker: Combatant, defender: Combatant, isDefending: Boolean
): BattleCalculator.DamageResult {
    val result = BattleCalculator.calculateCombatantDamage(attacker, defender)
    return if (isDefending && result.damage > 0) {
        val reducedDmg = (result.damage * 0.75).toInt().coerceAtLeast(1)
        result.copy(damage = reducedDmg)
    } else result
}

/**
 * 技能伤害计算（带防御减伤）。
 */
internal fun computeSkillDamage(
    attacker: Combatant, defender: Combatant,
    skill: CombatSkill, isDefending: Boolean
): BattleCalculator.DamageResult {
    val result = BattleCalculator.calculateCombatantDamage(attacker, defender, skill)
    return if (isDefending && result.damage > 0) {
        val reducedDmg = (result.damage * 0.75).toInt().coerceAtLeast(1)
        result.copy(damage = reducedDmg)
    } else result
}

/**
 * 应用普攻伤害并返回更新后的防御者。
 */
internal fun applyNormalAttack(
    attacker: Combatant, defender: Combatant, isDefending: Boolean
): Combatant {
    val finalDmg = computeNormalAttackDamage(attacker, defender, isDefending).damage
    return defender.copy(hp = (defender.hp - finalDmg).coerceAtLeast(0))
}

/**
 * 应用技能伤害并返回更新后的防御者。
 */
internal fun applySkillDamage(
    attacker: Combatant, defender: Combatant,
    skill: CombatSkill, isDefending: Boolean
): Combatant {
    val finalDmg = computeSkillDamage(attacker, defender, skill, isDefending).damage
    return defender.copy(hp = (defender.hp - finalDmg).coerceAtLeast(0))
}

/**
 * 玩家施放技能，返回更新后的双方队伍。
 */
internal fun executePlayerSkill(
    attacker: Combatant,
    skill: CombatSkill,
    selectedTargetId: String?,
    selectedIsAlly: Boolean,
    playerTeam: List<Combatant>,
    enemyTeam: List<Combatant>,
    isDefending: Set<String>
): Pair<List<Combatant>, List<Combatant>> {
    var updatedPlayers = playerTeam.toMutableList()
    var updatedEnemies = enemyTeam.toMutableList()

    val attackerIdx = updatedPlayers.indexOfFirst { it.id == attacker.id }
    if (attackerIdx >= 0) {
        updatedPlayers[attackerIdx] = updatedPlayers[attackerIdx].copy(
            mp = (updatedPlayers[attackerIdx].mp - skill.mpCost).coerceAtLeast(0)
        )
    }

    val isAttackSkill = skill.skillType == SkillType.ATTACK || skill.damageMultiplier > 0

    if (skill.isAoe) {
        if (isAttackSkill) {
            updatedEnemies = updatedEnemies.map { e ->
                if (!e.isDead) applySkillDamage(attacker, e, skill, isDefending.contains(e.id)) else e
            }.toMutableList()
        } else {
            updatedPlayers = updatedPlayers.map { a ->
                if (!a.isDead) applyBuffToTarget(a, skill) else a
            }.toMutableList()
        }
    } else {
        if (isAttackSkill) {
            val target = if (!selectedIsAlly && selectedTargetId != null)
                updatedEnemies.find { it.id == selectedTargetId }
            else updatedEnemies.filter { !it.isDead }.randomOrNull()
            if (target != null) {
                val updated = applySkillDamage(attacker, target, skill, false)
                updatedEnemies = updatedEnemies.map {
                    if (it.id == target.id) updated else it
                }.toMutableList()
            }
        } else {
            if (skill.targetScope == "self") {
                val updated = applyBuffToTarget(attacker, skill)
                if (attackerIdx >= 0) updatedPlayers[attackerIdx] = updated
            } else {
                val target = if (selectedIsAlly && selectedTargetId != null)
                    updatedPlayers.find { it.id == selectedTargetId }
                else updatedPlayers.filter { !it.isDead }.randomOrNull()
                if (target != null) {
                    val updated = applyBuffToTarget(target, skill)
                    updatedPlayers = updatedPlayers.map {
                        if (it.id == target.id) updated else it
                    }.toMutableList()
                }
            }
        }
    }
    return updatedPlayers.toList() to updatedEnemies.toList()
}

/**
 * 应用 Buff/治疗到目标，返回更新后的 Combatant。
 */
internal fun applyBuffToTarget(
    target: Combatant, skill: CombatSkill
): Combatant {
    var newHp = target.hp; var newMp = target.mp
    if (skill.healPercent > 0) {
        if (skill.healType == HealType.HP)
            newHp = (target.hp + (target.maxHp * skill.healPercent).toInt()).coerceAtMost(target.maxHp)
        else newMp = (target.mp + (target.maxMp * skill.healPercent).toInt()).coerceAtMost(target.maxMp)
    }
    val newBuffs = skill.buffType?.let { bt ->
        target.buffs + CombatBuff(bt, skill.buffValue, skill.buffDuration)
    } ?: target.buffs
    return target.copy(hp = newHp, mp = newMp, buffs = newBuffs)
}

/**
 * 推进回合，根据存活情况返回下一状态。
 */
internal fun advanceTurn(
    alivePlayers: List<Combatant>,
    aliveEnemies: List<Combatant>,
    currentIdx: Int,
    isDefending: MutableSet<String>,
    onResult: (Int, BattlePhase, MutableSet<String>) -> Unit
) {
    if (aliveEnemies.all { it.isDead }) {
        onResult(currentIdx, BattlePhase.WON, isDefending); return
    }
    if (alivePlayers.all { it.isDead }) {
        onResult(currentIdx, BattlePhase.LOST, isDefending); return
    }
    val nextIdx = currentIdx + 1
    if (nextIdx >= alivePlayers.size)
        onResult(0, BattlePhase.ENEMY_TURN, isDefending)
    else onResult(nextIdx, BattlePhase.PLAYER_TURN, isDefending)
}

/**
 * 即时结算：模拟整场战斗，跳过所有动画，直接返回最终双方状态。
 * 双方均使用统一 [BattleAI.decideAction] 决策。
 */
internal fun simulateInstantResolve(
    playerTeam: List<Combatant>,
    enemyTeam: List<Combatant>
): Pair<List<Combatant>, List<Combatant>> {
    var players = playerTeam.map { p ->
        p.copy(skills = p.skills.map { it.copy() })
    }
    var enemies = enemyTeam.map { e ->
        e.copy(skills = e.skills.map { it.copy() })
    }
    val maxRounds = 100
    var round = 0
    while (round < maxRounds) {
        if (enemies.all { it.isDead } || players.all { it.isDead }) break

        // 冷却 -1
        players = players.map { p ->
            p.copy(skills = p.skills.map { s -> s.copy(currentCooldown = (s.currentCooldown - 1).coerceAtLeast(0)) })
        }
        enemies = enemies.map { e ->
            e.copy(skills = e.skills.map { s -> s.copy(currentCooldown = (s.currentCooldown - 1).coerceAtLeast(0)) })
        }

        val turnOrder = (players.filter { !it.isDead } + enemies.filter { !it.isDead })
            .sortedByDescending { it.effectiveSpeed }

        for (unit in turnOrder) {
            if (enemies.all { it.isDead } || players.all { it.isDead }) break
            val isPlayer = players.any { it.id == unit.id }
            val friends = if (isPlayer) players else enemies
            val foes = if (isPlayer) enemies else players
            val aiAction = BattleAI.decideAction(unit, friends, foes)
            val result = resolveAIAction(unit, aiAction, isPlayer, players, enemies)
            players = result.first
            enemies = result.second
        }
        round++
    }
    return players to enemies
}

/**
 * 结算单次 [BattleAI.AIAction]，更新双方队伍状态。
 */
internal fun resolveAIAction(
    actor: Combatant,
    ai: BattleAI.AIAction,
    actorIsPlayer: Boolean,
    players: List<Combatant>,
    enemies: List<Combatant>
): Pair<List<Combatant>, List<Combatant>> {
    var updatedPlayers = players
    var updatedEnemies = enemies
    val myTeam = if (actorIsPlayer) updatedPlayers else updatedEnemies
    val enemyTeam = if (actorIsPlayer) updatedEnemies else updatedPlayers
    val skill = ai.skill
    val target = ai.target

    when (ai.actionType) {
        BattleAI.AIActionType.NONE -> {}
        BattleAI.AIActionType.SKILL_ATTACK_AOE -> {
            if (skill != null) {
                val newEnemyTeam = enemyTeam.map { e ->
                    if (!e.isDead) {
                        val dmg = BattleCalculator.calculateCombatantDamage(actor, e, skill).damage
                        e.copy(hp = (e.hp - dmg).coerceAtLeast(0))
                    } else e
                }
                if (actorIsPlayer) updatedEnemies = newEnemyTeam else updatedPlayers = newEnemyTeam
            }
        }
        BattleAI.AIActionType.SKILL_ATTACK_SINGLE -> {
            if (skill != null && target != null) {
                val dmg = BattleCalculator.calculateCombatantDamage(actor, target, skill).damage
                val applyDmg: (Combatant) -> Combatant = { it.copy(hp = if (it.id == target.id) (it.hp - dmg).coerceAtLeast(0) else it.hp) }
                if (actorIsPlayer) updatedEnemies = updatedEnemies.map(applyDmg)
                else updatedPlayers = updatedPlayers.map(applyDmg)
            }
        }
        BattleAI.AIActionType.NORMAL_ATTACK -> {
            if (target != null) {
                val dmg = BattleCalculator.calculateCombatantDamage(actor, target, null).damage
                val applyDmg: (Combatant) -> Combatant = { it.copy(hp = if (it.id == target.id) (it.hp - dmg).coerceAtLeast(0) else it.hp) }
                if (actorIsPlayer) updatedEnemies = updatedEnemies.map(applyDmg)
                else updatedPlayers = updatedPlayers.map(applyDmg)
            }
        }
        BattleAI.AIActionType.SKILL_HEAL_SELF, BattleAI.AIActionType.SKILL_BUFF_SELF -> {
            if (skill != null) {
                val buffed = applyBuffToTarget(actor, skill)
                if (actorIsPlayer) updatedPlayers = updatedPlayers.map { if (it.id == actor.id) buffed else it }
                else updatedEnemies = updatedEnemies.map { if (it.id == actor.id) buffed else it }
            }
        }
        BattleAI.AIActionType.SKILL_HEAL_ALLY, BattleAI.AIActionType.SKILL_BUFF_ALLY -> {
            if (skill != null && target != null) {
                val buffed = applyBuffToTarget(target, skill)
                if (actorIsPlayer) updatedPlayers = updatedPlayers.map { if (it.id == target.id) buffed else it }
                else updatedEnemies = updatedEnemies.map { if (it.id == target.id) buffed else it }
            }
        }
        BattleAI.AIActionType.SKILL_HEAL_TEAM, BattleAI.AIActionType.SKILL_BUFF_TEAM -> {
            if (skill != null) {
                val applyBuffs: (Combatant) -> Combatant = { if (!it.isDead) applyBuffToTarget(it, skill) else it }
                if (actorIsPlayer) updatedPlayers = updatedPlayers.map(applyBuffs)
                else updatedEnemies = updatedEnemies.map(applyBuffs)
            }
        }
    }

    // 技能消耗：扣除 MP + 设置冷却
    if (skill != null &&
        ai.actionType != BattleAI.AIActionType.NONE &&
        ai.actionType != BattleAI.AIActionType.NORMAL_ATTACK
    ) {
        val myTeamList = if (actorIsPlayer) updatedPlayers else updatedEnemies
        val actorIdx = myTeamList.indexOfFirst { it.id == actor.id }
        if (actorIdx >= 0) {
            val drained = myTeamList[actorIdx].copy(
                mp = (myTeamList[actorIdx].mp - skill.mpCost).coerceAtLeast(0),
                skills = myTeamList[actorIdx].skills.map { s ->
                    if (s.name == skill.name) s.copy(currentCooldown = s.cooldown) else s
                }
            )
            if (actorIsPlayer) {
                updatedPlayers = updatedPlayers.toMutableList().apply { set(actorIdx, drained) }
            } else {
                updatedEnemies = updatedEnemies.toMutableList().apply { set(actorIdx, drained) }
            }
        }
    }

    return updatedPlayers to updatedEnemies
}
