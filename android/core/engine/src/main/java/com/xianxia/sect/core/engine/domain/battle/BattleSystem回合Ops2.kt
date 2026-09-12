package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.HealType
import com.xianxia.sect.core.model.CombatSkill
import com.xianxia.sect.core.util.BattleCalculator
import com.xianxia.sect.core.util.updateCombatantCooldowns
import com.xianxia.sect.core.engine.domain.battle.BattleSystem.ActionRecordData
import com.xianxia.sect.core.engine.domain.battle.BattleSystem.SkillActionContext
import com.xianxia.sect.core.engine.domain.battle.BattleSystem.TurnContext
import com.xianxia.sect.core.engine.domain.battle.BattleSystem.TurnSides

/** 回合双方阵营解析（executeCombatantTurn 提取） */
// ── BattleSystem 拆分域 2/4（行为零变更） ──
internal fun BattleSystem.resolveTurnSides(ctx: TurnContext, isTeamMember: Boolean): TurnSides {
    return if (isTeamMember) {
        TurnSides(ctx.team, ctx.beasts, ctx.teamIndexMap, ctx.beastsIndexMap)
    } else {
        TurnSides(ctx.beasts, ctx.team, ctx.beastsIndexMap, ctx.teamIndexMap)
    }
}

/**
 * 记录参战者本次行动到战斗动作列表。
 */

internal fun BattleSystem.recordTurnAction(ctx: TurnContext, data: ActionRecordData) {
    val isKill = data.turnMessage.isKill
    ctx.actions.add(BattleActionData(
        type = when {
            data.isSupportSkill -> "support"
            data.availableSkill != null -> "skill"
            else -> "attack"
        },
        attacker = data.currentCombatant.name,
        attackerType = if (data.isTeamMember) "disciple" else
            if (data.currentCombatant.isBeast) "beast" else "disciple",
        target = if (data.isSupportSkill) "ctx.team" else
            if (data.isAoeSkill) "全体敌人" else data.result.target.name,
        damage = if (data.isAoeSkill) data.turnMessage.totalDamage else data.result.damage,
        damageType = if (data.isInstantKill) "必杀" else if (data.result.isSupport) "support" else
            if (data.result.isDodged) "闪避" else if (data.result.isPhysical) "物理" else "法术",
        isCrit = data.isCrit,
        isKill = isKill,
        isInstantKill = data.isInstantKill,
        message = data.turnMessage.text,
        skillName = data.result.skillName
    ))
}

/**
 * 技能冷却更新写回。
 */

internal fun BattleSystem.applyCooldownUpdate(
    ctx: TurnContext,
    alliesIndexMap: Map<String, Int>,
    currentCombatant: Combatant,
    availableSkill: CombatSkill,
    isTeamMember: Boolean
) {
    val combatantIndex = alliesIndexMap[currentCombatant.id] ?: -1
    if (combatantIndex >= 0) {
        val updatedCombatant = BattleCalculator.updateCombatantCooldowns(currentCombatant, availableSkill)
        if (isTeamMember) {
            ctx.team[combatantIndex] = updatedCombatant
        } else {
            ctx.beasts[combatantIndex] = updatedCombatant
        }
    }
}

/**
 * 支援技能效果应用：治疗写回 + 团队 BUFF + 拉条。
 */

internal fun BattleSystem.applySupportEffects(
    ctx: TurnContext,
    result: AttackResult,
    allies: MutableList<Combatant>,
    isTeamMember: Boolean,
    currentCombatant: Combatant,
    playerDamageModifier: Double
) {
    applySupportHealing(ctx, result, allies, isTeamMember)
    applySupportTeamBuffs(ctx, result, allies, isTeamMember)

    // Turn advance: target ally acts immediately after current combatant
    if (result.turnAdvancePercent > 0) {
        val enemiesIndexMap = if (isTeamMember) ctx.beastsIndexMap else ctx.teamIndexMap
        processTurnAdvance(
            ctx, result, allies, currentCombatant, isTeamMember, enemiesIndexMap,
            playerDamageModifier
        )
    }
}

/** 支援治疗写回（applySupportEffects 提取，按 allies 定位、按 isTeamMember 分写） */

internal fun BattleSystem.applySupportHealing(
    ctx: TurnContext,
    result: AttackResult,
    allies: MutableList<Combatant>,
    isTeamMember: Boolean
) {
    if (result.healedIds.isEmpty()) return
    // 治疗按 allies 定位、按 isTeamMember 分写（修复：原硬编码 ctx.team 致敌方治疗无效）
    result.healedIds.forEach { healedId ->
        val healedIndex = allies.indexOfFirst { it.id == healedId }
        if (healedIndex >= 0) {
            val healed = allies[healedIndex]
            val updated = if (result.healType == HealType.MP) {
                healed.copy(mp = minOf(healed.mp + result.healAmount, healed.maxMp))
            } else {
                healed.copy(hp = minOf(healed.hp + result.healAmount, healed.maxHp))
            }
            if (isTeamMember) {
                ctx.team[healedIndex] = updated
            } else {
                ctx.beasts[healedIndex] = updated
            }
        }
    }
}

/** 团队 BUFF 写回（applySupportEffects 提取） */

internal fun BattleSystem.applySupportTeamBuffs(
    ctx: TurnContext,
    result: AttackResult,
    allies: MutableList<Combatant>,
    isTeamMember: Boolean
) {
    if (result.teamBuffs.isEmpty()) return
    result.teamBuffs.forEach { (memberId, buffs) ->
        val memberIndex = allies.indexOfFirst { it.id == memberId }
        if (memberIndex >= 0) {
            val member = allies[memberIndex]
            val existingBuffs = member.buffs.filter { it.remainingDuration > 0 }
            val updated = member.copy(buffs = existingBuffs + buffs)
            if (isTeamMember) {
                ctx.team[memberIndex] = updated
            } else {
                ctx.beasts[memberIndex] = updated
            }
        }
    }
}


internal fun BattleSystem.executeSkillAction(
    ctx: SkillActionContext,
    availableSkill: CombatSkill?,
    isSupportSkill: Boolean,
    isAoeSkill: Boolean,
    aiAction: BattleAI.AIAction?
): List<AttackResult> = when {
    availableSkill == null -> executeBasicAttackAction(ctx, aiAction)
    isSupportSkill -> if (availableSkill.targetScope == "ally") executeAllyScopeSupport(ctx, availableSkill)
    else executeTeamSupport(ctx, availableSkill)
    isAoeSkill -> executeAoeSkillAction(ctx, availableSkill)
    else -> executeSingleSkillAction(ctx, availableSkill, aiAction)
}

/** 友方单体支援（executeSkillAction 提取）：随机选一名存活友方施放，保留 RNG 抽数位置 */

internal fun BattleSystem.executeAllyScopeSupport(ctx: SkillActionContext, skill: CombatSkill): List<AttackResult> {
    val validAllies = ctx.allies.filter { !it.isDead && it.id != ctx.currentCombatant.id }
    if (validAllies.isNotEmpty()) {
        val selectedAlly = validAllies[rng.nextInt(validAllies.size)]
        val supResult = executeSupportSkill(ctx.currentCombatant, listOf(selectedAlly), skill)
        // Mark the single ally as the target for turn advance
        return listOf(supResult.copy(
            healedIds = if (supResult.healAmount > 0) listOf(selectedAlly.id) else supResult.healedIds,
            teamBuffs = if (supResult.teamBuffs.isNotEmpty()) {
                mapOf(selectedAlly.id to (supResult.teamBuffs.values.firstOrNull() ?: emptyList()))
            } else {
                emptyMap()
            }
        ))
    }
    return listOf(executeSupportSkill(ctx.currentCombatant, listOf(ctx.currentCombatant), skill))
}

/** 团队支援（executeSkillAction 提取） */

internal fun BattleSystem.executeTeamSupport(ctx: SkillActionContext, skill: CombatSkill): List<AttackResult> =
    listOf(executeSupportSkill(ctx.currentCombatant, ctx.allies.filter { !it.isDead }, skill))

/** AOE 技能（executeSkillAction 提取） */

internal fun BattleSystem.executeAoeSkillAction(ctx: SkillActionContext, skill: CombatSkill): List<AttackResult> {
    val dmgMod = if (ctx.isTeamMember) ctx.playerDamageModifier else 1.0
    return ctx.aliveEnemies.map { target -> executeSkill(ctx.currentCombatant, target, skill, dmgMod) }
}

/** 单体技能（executeSkillAction 提取） */

internal fun BattleSystem.executeSingleSkillAction(
    ctx: SkillActionContext,
    skill: CombatSkill,
    aiAction: BattleAI.AIAction?
): List<AttackResult> {
    val target = selectTarget(ctx.currentCombatant, ctx.aliveEnemies, aiAction)
    val dmgMod = if (ctx.isTeamMember) ctx.playerDamageModifier else 1.0
    return listOf(executeSkill(ctx.currentCombatant, target, skill, dmgMod))
}

/** 普攻（executeSkillAction 提取） */

internal fun BattleSystem.executeBasicAttackAction(
    ctx: SkillActionContext, aiAction: BattleAI.AIAction?
): List<AttackResult> {
    val target = selectTarget(ctx.currentCombatant, ctx.aliveEnemies, aiAction)
    val dmgMod = if (ctx.isTeamMember) ctx.playerDamageModifier else 1.0
    return listOf(executeAttack(ctx.currentCombatant, target, dmgMod))
}

/**
 * 应用非支援行动的全部伤害效果：护盾吸收、伤害链接、伤害分摊、
 * 单体/AOE debuff 附加。原地修改 ctx 中 combatant 的 hp/buffs。
 *
 * 护盾/链接/分摊经共享应用层 [BattleDamageApplier]（与宗门战引擎语义一致）。
 */
