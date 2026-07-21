package com.xianxia.sect.ui.game.dialogs.heavenlytrial

import com.xianxia.sect.core.SkillType
import com.xianxia.sect.core.HealType
import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.model.CombatSkill
import com.xianxia.sect.core.engine.domain.battle.BattleAI
import com.xianxia.sect.core.engine.domain.battle.CombatBuff
import com.xianxia.sect.core.engine.domain.battle.Combatant
import com.xianxia.sect.core.util.BattleCalculator
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition

/** 天道试炼战斗逻辑的 RNG（由 HeavenlyTrialViewModel 初始化时注入） */
var combatLogicRngManager: GameRngManager? = null
private val combatRng get() = (combatLogicRngManager ?: error("CombatLogic RNG not initialized")).getRng(RngPartition.BATTLE)

/**
 * 普攻伤害计算（带防御减伤）。
 * isDefending 在 BattleCalculator 结果上再叠加 25% 减伤（UI 特有机制）。
 */
internal fun computeNormalAttackDamage(
    attacker: Combatant, defender: Combatant, isDefending: Boolean,
    rng: DeterministicRng = combatRng
): BattleCalculator.DamageResult {
    val result = BattleCalculator.calculateCombatantDamage(
        attacker, defender, rng = rng, enableInstantKill = true
    )
    // 斩杀无视防御减伤
    if (result.isInstantKill || !isDefending || result.damage <= 0) return result
    val reducedDmg = (result.damage * 0.75).toInt().coerceAtLeast(1)
    return result.copy(damage = reducedDmg)
}

/**
 * 技能伤害计算（带防御减伤）。
 */
internal fun computeSkillDamage(
    attacker: Combatant, defender: Combatant,
    skill: CombatSkill, isDefending: Boolean,
    rng: DeterministicRng = combatRng
): BattleCalculator.DamageResult {
    val result = BattleCalculator.calculateCombatantDamage(
        attacker, defender, skill, rng = rng, enableInstantKill = true
    )
    // 斩杀无视防御减伤
    if (result.isInstantKill || !isDefending || result.damage <= 0) return result
    val reducedDmg = (result.damage * 0.75).toInt().coerceAtLeast(1)
    return result.copy(damage = reducedDmg)
}

/**
 * 应用普攻伤害并返回更新后的防御者。
 */
internal fun applyNormalAttack(
    attacker: Combatant, defender: Combatant, isDefending: Boolean
): Combatant {
    val result = computeNormalAttackDamage(attacker, defender, isDefending)
    if (result.isInstantKill) return defender.copy(hp = 0)
    return defender.copy(hp = (defender.hp - result.damage).coerceAtLeast(0))
}

/**
 * 应用技能伤害并返回更新后的防御者。
 */
internal fun applySkillDamage(
    attacker: Combatant, defender: Combatant,
    skill: CombatSkill, isDefending: Boolean
): Combatant {
    val result = computeSkillDamage(attacker, defender, skill, isDefending)
    if (result.isInstantKill) return defender.copy(hp = 0)
    return defender.copy(hp = (defender.hp - result.damage).coerceAtLeast(0))
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
    // MP 已在 HeavenlyTrialCombatScreen 调用前统一扣除，此处不再重复扣除
    val attackerIdx = updatedPlayers.indexOfFirst { it.id == attacker.id }

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
 * 支持百分比治疗 ([skill.healPercent]) 和固定数值治疗 ([skill.healFixed])，
 * 以及单 buff ([skill.buffType]) 和多 buff 列表 ([skill.buffs])。
 *
 * 治疗上限使用 [Combatant.effectiveMaxHp] / [Combatant.effectiveMaxMp]
 * （含 HP_BOOST / MP_BOOST buff 加成）。同类型 buff 自动覆盖（刷新持续时间）。
 * 死亡目标直接返回不变。
 */
internal fun applyBuffToTarget(
    target: Combatant, skill: CombatSkill
): Combatant {
    if (target.isDead) return target
    var hpHeal = 0; var mpHeal = 0
    val effMaxHp = target.effectiveMaxHp
    val effMaxMp = target.effectiveMaxMp

    val pct = skill.healPercent.coerceAtLeast(0.0)
    if (pct > 0) {
        if (skill.healType == HealType.HP)
            hpHeal = (effMaxHp * pct).toInt()
        else mpHeal = (effMaxMp * pct).toInt()
    }
    val fixed = skill.healFixed.coerceAtLeast(0)
    if (fixed > 0) {
        if (skill.healType == HealType.HP)
            hpHeal += fixed
        else mpHeal += fixed
    }
    val newHp = (target.hp + hpHeal).coerceAtMost(effMaxHp)
    val newMp = (target.mp + mpHeal).coerceAtMost(effMaxMp)

    // 合并单 buff + buffs 列表，同类型自动覆盖
    val allBuffs = target.buffs.toMutableList()
    val addOrReplace = { type: BuffType, value: Double, dur: Int ->
        val idx = allBuffs.indexOfFirst { it.type == type }
        val buff = CombatBuff(type, value, dur)
        if (idx >= 0) allBuffs[idx] = buff else allBuffs.add(buff)
    }
    skill.buffType?.let { addOrReplace(it, skill.buffValue, skill.buffDuration) }
    skill.buffs.forEach { (type, value, duration) ->
        addOrReplace(type, value, duration)
    }
    return target.copy(hp = newHp, mp = newMp, buffs = allBuffs)
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
    enemyTeam: List<Combatant>,
    rng: DeterministicRng = combatRng
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
            val aiAction = BattleAI.decideAction(unit, friends, foes, rng)
            val result = resolveAIAction(unit, aiAction, isPlayer, players, enemies, rng)
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
    enemies: List<Combatant>,
    rng: DeterministicRng = combatRng
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
                        val r = BattleCalculator.calculateCombatantDamage(
                            actor, e, skill, rng = rng, enableInstantKill = true
                        )
                        if (r.isInstantKill) e.copy(hp = 0)
                        else e.copy(hp = (e.hp - r.damage).coerceAtLeast(0))
                    } else e
                }
                if (actorIsPlayer) updatedEnemies = newEnemyTeam else updatedPlayers = newEnemyTeam
            }
        }
        BattleAI.AIActionType.SKILL_ATTACK_SINGLE -> {
            if (skill != null && target != null) {
                val r = BattleCalculator.calculateCombatantDamage(
                    actor, target, skill, rng = rng, enableInstantKill = true
                )
                val applyDmg: (Combatant) -> Combatant = {
                    if (it.id == target.id) {
                        if (r.isInstantKill) it.copy(hp = 0)
                        else it.copy(hp = (it.hp - r.damage).coerceAtLeast(0))
                    } else it
                }
                if (actorIsPlayer) updatedEnemies = updatedEnemies.map(applyDmg)
                else updatedPlayers = updatedPlayers.map(applyDmg)
            }
        }
        BattleAI.AIActionType.NORMAL_ATTACK -> {
            if (target != null) {
                val r = BattleCalculator.calculateCombatantDamage(
                    actor, target, null, rng = rng, enableInstantKill = true
                )
                val applyDmg: (Combatant) -> Combatant = {
                    if (it.id == target.id) {
                        if (r.isInstantKill) it.copy(hp = 0)
                        else it.copy(hp = (it.hp - r.damage).coerceAtLeast(0))
                    } else it
                }
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
