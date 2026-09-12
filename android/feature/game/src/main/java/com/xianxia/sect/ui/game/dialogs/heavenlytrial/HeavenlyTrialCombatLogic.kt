@file:Suppress("TooManyFunctions") // 私有辅助函数集中在本文件
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

/**
 * 当前战斗专用的本地 PRNG（由 [beginCombat] 在进入战斗时从全局 BATTLE 分区取种子创建）。
 *
 * 确定性设计（对标 Brogue 玩法/装饰 RNG 分离）：UI 战斗模拟是展示型消费，
 * 不得推进全局 BATTLE 分区——否则模拟次数变化会污染引擎侧战斗序列，
 * 破坏读档重放确定性（装饰 RNG 破坏全局状态的行业教训）。
 * 每次进入战斗仅从全局分区消费 1 次种子，此后模拟完全本地化。
 */
private var combatRngLocal: DeterministicRng? = null
private val combatRng get() = combatRngLocal ?: error("CombatLogic RNG not initialized（须先调用 beginCombat）")

/**
 * 开始一场新的试炼战斗：从全局 BATTLE 分区取种子创建本地 PRNG。
 * 调用方（ViewModel.startCombat，UI 线程）负责从 [com.xianxia.sect.core.util.GameRngManager]
 * 取种子——单次消费为 [DeterministicRng.nextLong] 的 @Synchronized 原子操作，线程安全。
 */
internal fun beginCombat(seed: Long) {
    combatRngLocal = DeterministicRng(seed)
}

/**
 * 当前战斗的本地 PRNG（供 CombatScreen 的确定性随机选择使用）。
 * 仅在 beginCombat 之后调用（CombatScreen 只在战斗界面执行）。
 */
internal fun currentCombatRng(): DeterministicRng = combatRng

/**
 * 确定性随机选择：替代 `kotlin.random.Random.randomOrNull`——
 * UI 模拟的目标选择必须走本地 PRNG（当前战斗的 [combatRng]），
 * 与即时结算路径同基准、可重放，不引入非确定性随机源。
 */
internal fun <T> List<T>.randomOrNull(rng: DeterministicRng): T? =
    if (isEmpty()) null else this[rng.nextInt(size)]

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
@Suppress("LongParameterList", "CyclomaticComplexMethod", "NestedBlockDepth")
// 预存 UI 战斗编排复杂度（参数 7→8 因 A3 修复加 rng），非本次引入
internal fun executePlayerSkill(
    attacker: Combatant,
    skill: CombatSkill,
    selectedTargetId: String?,
    selectedIsAlly: Boolean,
    playerTeam: List<Combatant>,
    enemyTeam: List<Combatant>,
    isDefending: Set<String>,
    rng: DeterministicRng = combatRng
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
                if (!a.isDead) applyBuffToTarget(a, skill, attacker.realm, attacker.realmLayer) else a
            }.toMutableList()
        }
    } else {
        if (isAttackSkill) {
            val target = if (!selectedIsAlly && selectedTargetId != null)
                updatedEnemies.find { it.id == selectedTargetId }
            else updatedEnemies.filter { !it.isDead }.randomOrNull(rng)
            if (target != null) {
                val updated = applySkillDamage(attacker, target, skill, false)
                updatedEnemies = updatedEnemies.map {
                    if (it.id == target.id) updated else it
                }.toMutableList()
            }
        } else {
            if (skill.targetScope == "self") {
                val updated = applyBuffToTarget(attacker, skill, attacker.realm, attacker.realmLayer)
                if (attackerIdx >= 0) updatedPlayers[attackerIdx] = updated
            } else {
                val target = if (selectedIsAlly && selectedTargetId != null)
                    updatedPlayers.find { it.id == selectedTargetId }
                else updatedPlayers.filter { !it.isDead }.randomOrNull(rng)
                if (target != null) {
                    val updated = applyBuffToTarget(target, skill, attacker.realm, attacker.realmLayer)
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
    target: Combatant,
    skill: CombatSkill,
    sourceRealm: Int = 9,
    sourceRealmLayer: Int = 0
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
        val buff = CombatBuff(type, value, dur, sourceRealm, sourceRealmLayer)
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
 *
 * `isDefending` 以只读 [Set] 传递（本函数从不原地修改，原样回传；
 * UI 侧持有不可变集合）。
 */
internal fun advanceTurn(
    alivePlayers: List<Combatant>,
    aliveEnemies: List<Combatant>,
    currentIdx: Int,
    isDefending: Set<String>,
    onResult: (Int, BattlePhase, Set<String>) -> Unit
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
    val skill = ai.skill

    // 动作分支结算
    val (playersAfterAction, enemiesAfterAction) = applyAIAction(
        actor = actor,
        ai = ai,
        actorIsPlayer = actorIsPlayer,
        players = updatedPlayers,
        enemies = updatedEnemies,
        rng = rng
    )
    updatedPlayers = playersAfterAction
    updatedEnemies = enemiesAfterAction

    // 技能消耗：扣除 MP + 设置冷却
    if (skill != null &&
        ai.actionType != BattleAI.AIActionType.NONE &&
        ai.actionType != BattleAI.AIActionType.NORMAL_ATTACK
    ) {
        val (playersAfterCost, enemiesAfterCost) = deductSkillCost(
            actor = actor,
            skill = skill,
            actorIsPlayer = actorIsPlayer,
            players = updatedPlayers,
            enemies = updatedEnemies
        )
        updatedPlayers = playersAfterCost
        updatedEnemies = enemiesAfterCost
    }

    return updatedPlayers to updatedEnemies
}

/** AI 动作分支结算：按 actionType 分派到对应结算函数 */
private fun applyAIAction(
    actor: Combatant,
    ai: BattleAI.AIAction,
    actorIsPlayer: Boolean,
    players: List<Combatant>,
    enemies: List<Combatant>,
    rng: DeterministicRng
): Pair<List<Combatant>, List<Combatant>> {
    val skill = ai.skill
    val target = ai.target
    return when (ai.actionType) {
        BattleAI.AIActionType.NONE -> players to enemies
        BattleAI.AIActionType.SKILL_ATTACK_AOE -> resolveAoeSkillAttack(
            actor = actor, skill = skill, actorIsPlayer = actorIsPlayer,
            players = players, enemies = enemies, rng = rng
        )
        BattleAI.AIActionType.SKILL_ATTACK_SINGLE -> {
            if (skill != null && target != null) {
                resolveTargetedSkillAttack(
                    actor = actor, skill = skill, target = target,
                    actorIsPlayer = actorIsPlayer,
                    players = players, enemies = enemies, rng = rng
                )
            } else {
                players to enemies
            }
        }
        BattleAI.AIActionType.NORMAL_ATTACK -> {
            if (target != null) {
                resolveTargetedSkillAttack(
                    actor = actor, skill = null, target = target,
                    actorIsPlayer = actorIsPlayer,
                    players = players, enemies = enemies, rng = rng
                )
            } else {
                players to enemies
            }
        }
        BattleAI.AIActionType.SKILL_HEAL_SELF, BattleAI.AIActionType.SKILL_BUFF_SELF -> resolveSelfBuff(
            actor = actor, skill = skill, actorIsPlayer = actorIsPlayer,
            players = players, enemies = enemies
        )
        BattleAI.AIActionType.SKILL_HEAL_ALLY, BattleAI.AIActionType.SKILL_BUFF_ALLY -> resolveAllyBuff(
            actor = actor, skill = skill, target = target,
            actorIsPlayer = actorIsPlayer, players = players,
            enemies = enemies
        )
        BattleAI.AIActionType.SKILL_HEAL_TEAM, BattleAI.AIActionType.SKILL_BUFF_TEAM -> resolveTeamBuff(
            actor = actor, skill = skill, actorIsPlayer = actorIsPlayer,
            players = players, enemies = enemies
        )
    }
}

/** AOE 技能攻击结算：对全体敌方目标同时结算 */
private fun resolveAoeSkillAttack(
    actor: Combatant,
    skill: CombatSkill?,
    actorIsPlayer: Boolean,
    players: List<Combatant>,
    enemies: List<Combatant>,
    rng: DeterministicRng
): Pair<List<Combatant>, List<Combatant>> {
    if (skill == null) return players to enemies
    val foeTeam = if (actorIsPlayer) enemies else players
    val newFoeTeam = foeTeam.map { e ->
        if (!e.isDead) {
            val r = BattleCalculator.calculateCombatantDamage(
                actor, e, skill, rng = rng, enableInstantKill = true
            )
            if (r.isInstantKill) e.copy(hp = 0)
            else e.copy(hp = (e.hp - r.damage).coerceAtLeast(0))
        } else e
    }
    return if (actorIsPlayer) players to newFoeTeam else newFoeTeam to enemies
}

/** 单体技能/普攻结算：skill 为 null 时视为普攻 */
private fun resolveTargetedSkillAttack(
    actor: Combatant,
    skill: CombatSkill?,
    target: Combatant,
    actorIsPlayer: Boolean,
    players: List<Combatant>,
    enemies: List<Combatant>,
    rng: DeterministicRng
): Pair<List<Combatant>, List<Combatant>> {
    val r = BattleCalculator.calculateCombatantDamage(
        actor, target, skill, rng = rng, enableInstantKill = true
    )
    val applyDmg: (Combatant) -> Combatant = {
        if (it.id == target.id) {
            if (r.isInstantKill) it.copy(hp = 0)
            else it.copy(hp = (it.hp - r.damage).coerceAtLeast(0))
        } else it
    }
    val foeTeam = if (actorIsPlayer) enemies else players
    val newFoeTeam = foeTeam.map(applyDmg)
    return if (actorIsPlayer) players to newFoeTeam else newFoeTeam to enemies
}

/** 自身 Buff/治疗结算 */
private fun resolveSelfBuff(
    actor: Combatant,
    skill: CombatSkill?,
    actorIsPlayer: Boolean,
    players: List<Combatant>,
    enemies: List<Combatant>
): Pair<List<Combatant>, List<Combatant>> {
    if (skill == null) return players to enemies
    val buffed = applyBuffToTarget(actor, skill, actor.realm, actor.realmLayer)
    return if (actorIsPlayer) {
        players.map { if (it.id == actor.id) buffed else it } to enemies
    } else {
        players to enemies.map { if (it.id == actor.id) buffed else it }
    }
}

/** 单体队友 Buff/治疗结算 */
private fun resolveAllyBuff(
    actor: Combatant,
    skill: CombatSkill?,
    target: Combatant?,
    actorIsPlayer: Boolean,
    players: List<Combatant>,
    enemies: List<Combatant>
): Pair<List<Combatant>, List<Combatant>> {
    if (skill == null || target == null) return players to enemies
    val buffed = applyBuffToTarget(target, skill, actor.realm, actor.realmLayer)
    return if (actorIsPlayer) {
        players.map { if (it.id == target.id) buffed else it } to enemies
    } else {
        players to enemies.map { if (it.id == target.id) buffed else it }
    }
}

/** 全队 Buff/治疗结算 */
private fun resolveTeamBuff(
    actor: Combatant,
    skill: CombatSkill?,
    actorIsPlayer: Boolean,
    players: List<Combatant>,
    enemies: List<Combatant>
): Pair<List<Combatant>, List<Combatant>> {
    if (skill == null) return players to enemies
    val applyBuffs: (Combatant) -> Combatant = {
        if (!it.isDead) applyBuffToTarget(it, skill, actor.realm, actor.realmLayer) else it
    }
    return if (actorIsPlayer) {
        players.map(applyBuffs) to enemies
    } else {
        players to enemies.map(applyBuffs)
    }
}

/** 技能消耗结算：扣除 MP + 设置冷却 */
private fun deductSkillCost(
    actor: Combatant,
    skill: CombatSkill,
    actorIsPlayer: Boolean,
    players: List<Combatant>,
    enemies: List<Combatant>
): Pair<List<Combatant>, List<Combatant>> {
    val myTeamList = if (actorIsPlayer) players else enemies
    val actorIdx = myTeamList.indexOfFirst { it.id == actor.id }
    if (actorIdx >= 0) {
        val drained = myTeamList[actorIdx].copy(
            mp = (myTeamList[actorIdx].mp - skill.mpCost).coerceAtLeast(0),
            skills = myTeamList[actorIdx].skills.map { s ->
                if (s.name == skill.name) s.copy(currentCooldown = s.cooldown) else s
            }
        )
        return if (actorIsPlayer) {
            players.toMutableList().apply { set(actorIdx, drained) } to enemies
        } else {
            players to enemies.toMutableList().apply { set(actorIdx, drained) }
        }
    }
    return players to enemies
}
