package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.model.CombatSkill
import com.xianxia.sect.core.util.BattleCalculator
import com.xianxia.sect.core.engine.domain.battle.BattleSystem.TurnContext
import com.xianxia.sect.core.engine.domain.battle.BattleSystem.TurnMessage
import com.xianxia.sect.core.util.selectSkill
import com.xianxia.sect.core.util.selectTarget

/**
 * 应用非支援行动的全部伤害效果：护盾吸收、伤害链接、伤害分摊、
 * 单体/AOE debuff 附加。原地修改 ctx 中 combatant 的 hp/buffs。
 *
 * 护盾/链接/分摊经共享应用层 [BattleDamageApplier]（与宗门战引擎语义一致）。
 */
// ── BattleSystem 拆分域 3/4（行为零变更） ──
internal fun BattleSystem.applyDamageEffects(
    ctx: TurnContext,
    results: List<AttackResult>,
    isTeamMember: Boolean,
    currentCombatant: Combatant,
    availableSkill: CombatSkill?,
    isAoeSkill: Boolean,
    aliveEnemies: List<Combatant>
) {
    // 敌方索引映射按 isTeamMember 从 ctx 派生（与 resolveTurnSides 同源）
    val enemiesIndexMap = if (isTeamMember) ctx.beastsIndexMap else ctx.teamIndexMap
    results.forEach { r ->
        if (r.isDodged) return@forEach
        val targetIndex = enemiesIndexMap[r.target.id] ?: return@forEach
        val currentTarget = if (isTeamMember) ctx.beasts[targetIndex] else ctx.team[targetIndex]

        if (r.isInstantKill) {
            // 斩杀（境界压制必杀）无视护盾直接击杀——
            // 与 AI 引擎（AISectAttackManager 斩杀分支直接 hp=0）语义一致，
            // 避免"战报显示必杀、实际护盾吸收后残血存活"的谎报矛盾
            if (isTeamMember) {
                ctx.beasts[targetIndex] = currentTarget.copy(hp = 0)
            } else {
                ctx.team[targetIndex] = currentTarget.copy(hp = 0)
            }
            return@forEach
        }

        // 护盾吸收 + 扣血 + 护盾余量写回
        if (isTeamMember) {
            ctx.beasts[targetIndex] = BattleDamageApplier.applyDamageToTarget(currentTarget, r.damage)
        } else {
            ctx.team[targetIndex] = BattleDamageApplier.applyDamageToTarget(currentTarget, r.damage)
        }
        // 伤害链接 / 伤害分摊（按更新映射写回）
        BattleDamageApplier.applyLinkedDamage(currentCombatant, currentTarget, r.damage, ctx.team, ctx.beasts)
            .forEach { (id, updated) -> writeBack(ctx, id, updated) }
        BattleDamageApplier.applySharedDamage(currentTarget, r.damage, ctx.team, ctx.beasts)
            .forEach { (id, updated) -> writeBack(ctx, id, updated) }

        applySkillDebuff(ctx, targetIndex, isTeamMember, currentCombatant, availableSkill, isAoeSkill)
        applyDamageLinkDebuff(ctx, targetIndex, isTeamMember, currentCombatant, availableSkill)
    }

    applyAoeDebuff(ctx, aliveEnemies, enemiesIndexMap, isTeamMember, currentCombatant, availableSkill, isAoeSkill)
}

/** 按 id 将更新后的 Combatant 写回 ctx 列表 */

internal fun BattleSystem.writeBack(ctx: TurnContext, id: String, updated: Combatant) {
    val idxInTeam = ctx.team.indexOfFirst { it.id == id }
    if (idxInTeam >= 0) {
        ctx.team[idxInTeam] = updated
    } else {
        val idxInBeasts = ctx.beasts.indexOfFirst { it.id == id }
        if (idxInBeasts >= 0) ctx.beasts[idxInBeasts] = updated
    }
}

/** 技能 debuff 附加（非 AOE 时对目标施加技能自带的减益 BUFF） */

internal fun BattleSystem.applySkillDebuff(
    ctx: TurnContext,
    targetIndex: Int,
    isTeamMember: Boolean,
    currentCombatant: Combatant,
    availableSkill: CombatSkill?,
    isAoeSkill: Boolean
) {
    val localBuffType = availableSkill?.buffType
    if (localBuffType == null || availableSkill.buffDuration <= 0 || isAoeSkill) return
    val debuff = CombatBuff(
        type = localBuffType,
        value = availableSkill.buffValue,
        remainingDuration = availableSkill.buffDuration,
        sourceRealm = currentCombatant.realm,
        sourceRealmLayer = currentCombatant.realmLayer
    )
    if (isTeamMember && targetIndex < ctx.beasts.size) {
        ctx.beasts[targetIndex] = ctx.beasts[targetIndex].copy(
            buffs = ctx.beasts[targetIndex].buffs + debuff
        )
    } else if (targetIndex < ctx.team.size) {
        ctx.team[targetIndex] = ctx.team[targetIndex].copy(
            buffs = ctx.team[targetIndex].buffs + debuff
        )
    }
}

/** 伤害链接 debuff：清除旧的链接标记并给目标附加新链接（同时仅一个） */

internal fun BattleSystem.applyDamageLinkDebuff(
    ctx: TurnContext,
    targetIndex: Int,
    isTeamMember: Boolean,
    currentCombatant: Combatant,
    availableSkill: CombatSkill?
) {
    if (availableSkill?.damageLinkPercent == null || availableSkill.damageLinkPercent <= 0 ||
        availableSkill.buffDuration <= 0
    ) return
    val linkDebuff = CombatBuff(
        type = BuffType.DAMAGE_LINK,
        value = availableSkill.damageLinkPercent,
        remainingDuration = availableSkill.buffDuration,
        sourceRealm = currentCombatant.realm,
        sourceRealmLayer = currentCombatant.realmLayer
    )
    val enemies = if (isTeamMember) ctx.beasts else ctx.team
    enemies.forEachIndexed { idx, enemy ->
        val hasLink = enemy.buffs.any { it.type == BuffType.DAMAGE_LINK }
        if (hasLink) {
            val cleaned = enemy.buffs.filter { it.type != BuffType.DAMAGE_LINK }
            if (isTeamMember && idx < ctx.beasts.size) ctx.beasts[idx] = ctx.beasts[idx].copy(buffs = cleaned)
            else if (idx < ctx.team.size) ctx.team[idx] = ctx.team[idx].copy(buffs = cleaned)
        }
    }
    if (isTeamMember && targetIndex < ctx.beasts.size) {
        ctx.beasts[targetIndex] = ctx.beasts[targetIndex].copy(
            buffs = ctx.beasts[targetIndex].buffs + linkDebuff
        )
    } else if (targetIndex < ctx.team.size) {
        ctx.team[targetIndex] = ctx.team[targetIndex].copy(
            buffs = ctx.team[targetIndex].buffs + linkDebuff
        )
    }
}

/** AOE debuff：对全部存活敌人施加技能减益 BUFF */

internal fun BattleSystem.applyAoeDebuff(
    ctx: TurnContext,
    aliveEnemies: List<Combatant>,
    enemiesIndexMap: Map<String, Int>,
    isTeamMember: Boolean,
    currentCombatant: Combatant,
    availableSkill: CombatSkill?,
    isAoeSkill: Boolean
) {
    if (!isAoeSkill || availableSkill?.buffType == null || availableSkill.buffDuration <= 0) return
    val aoeBuffType = availableSkill.buffType ?: return
    val debuff = CombatBuff(
        type = aoeBuffType,
        value = availableSkill.buffValue,
        remainingDuration = availableSkill.buffDuration,
        sourceRealm = currentCombatant.realm,
        sourceRealmLayer = currentCombatant.realmLayer
    )
    aliveEnemies.filter { !it.isDead }.forEach { enemy ->
        val idx = enemiesIndexMap[enemy.id] ?: return@forEach
        if (isTeamMember && idx < ctx.beasts.size) {
            ctx.beasts[idx] = ctx.beasts[idx].copy(buffs = ctx.beasts[idx].buffs + debuff)
        } else if (idx < ctx.team.size) {
            ctx.team[idx] = ctx.team[idx].copy(buffs = ctx.team[idx].buffs + debuff)
        }
    }
}

/**
 * 生成回合行动描述消息：必杀/支援/技能（AOE 与单体）/普攻四分支。
 * 纯函数：仅依赖入参生成消息与击杀判定，不修改战斗状态。
 */
internal fun BattleSystem.buildTurnMessage(
    isInstantKill: Boolean,
    result: AttackResult,
    availableSkill: CombatSkill?,
    isAoeSkill: Boolean,
    results: List<AttackResult>,
    currentCombatant: Combatant
): TurnMessage {
    return when {
        isInstantKill -> TurnMessage(
            "境界碾压，${result.target.name}被一击必杀！", isKill = true, totalDamage = 0
        )
        result.isSupport && availableSkill != null -> TurnMessage(
            BattleDescriptionGenerator.generateSupportSkillDescription(
                caster = currentCombatant,
                skill = availableSkill,
                healAmount = result.healAmount,
                healType = result.healType,
                buffs = availableSkill.buffs
            ),
            isKill = false,
            totalDamage = 0
        )
        availableSkill != null -> {
            // Long 求和防多段×多目标溢出为负
            val totalDamage = results.sumOf { it.damage.toLong() }
                .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            val isKill = results.any { r -> r.target.hp - r.damage <= 0 }
            val text = if (isAoeSkill) {
                BattleDescriptionGenerator.generateAoeSkillDescription(
                    attacker = currentCombatant,
                    skill = availableSkill,
                    results = results,
                    isKill = isKill
                )
            } else {
                val singleTarget = result.target
                BattleDescriptionGenerator.generateSkillDescription(
                    attacker = currentCombatant,
                    target = singleTarget,
                    skill = availableSkill,
                    result = result,
                    isKill = singleTarget.hp - result.damage <= 0
                )
            }
            TurnMessage(text, isKill = isKill, totalDamage = totalDamage)
        }
        else -> TurnMessage(
            BattleDescriptionGenerator.generateAttackDescription(
                attacker = currentCombatant,
                target = result.target,
                result = result,
                isKill = result.target.hp - result.damage <= 0
            ),
            isKill = result.target.hp - result.damage <= 0,
            totalDamage = 0
        )
    }
}

/**
 * 拉条立即行动：被拉条的友方跳过等待立即执行一次行动（技能或普攻）。
 * 含伤害结算（护盾吸收）与冷却更新。
 */

internal fun BattleSystem.processTurnAdvance(
    ctx: TurnContext,
    result: AttackResult,
    allies: MutableList<Combatant>,
    currentCombatant: Combatant,
    isTeamMember: Boolean,
    enemiesIndexMap: Map<String, Int>,
    playerDamageModifier: Double
) {
    val advancedAlly = resolveAdvancedAlly(result, allies, currentCombatant) ?: return
    val advAllies = if (isTeamMember) ctx.team else ctx.beasts
    val advEnemies = if (isTeamMember) ctx.beasts else ctx.team
    val advAliveEnemies = advEnemies.filter { !it.isDead }
    val advIdx = advAllies.indexOfFirst { it.id == advancedAlly.id }
    if (advIdx < 0 || advAliveEnemies.isEmpty()) return

    val (advSkill, advTarget) = decideAdvancedAction(advancedAlly, advAllies, advAliveEnemies)
    val advDmgMod = if (advancedAlly.side == CombatantSide.DEFENDER) playerDamageModifier else 1.0
    val advResult = executeAdvancedAction(advancedAlly, advTarget, advSkill, advDmgMod)
    val advDmg = if (advResult.isSupport) 0 else advResult.damage
    ctx.actions.add(buildAdvancedActionLog(advSkill, advResult, advancedAlly, isTeamMember, advDmg))
    if (!advResult.isSupport && !advResult.isDodged) {
        applyAdvancedDamage(ctx, advResult, advDmg, isTeamMember, enemiesIndexMap)
    }
    if (advSkill != null && advIdx >= 0) {
        updateAdvancedCooldown(ctx, advSkill, advancedAlly, isTeamMember, advIdx)
    }
}

/** 拉条目标解析（processTurnAdvance 提取）：3 个守卫收敛为单点返回 */

internal fun BattleSystem.resolveAdvancedAlly(
    result: AttackResult,
    allies: MutableList<Combatant>,
    currentCombatant: Combatant
): Combatant? {
    val advancedId = result.healedIds.firstOrNull()
        ?: result.teamBuffs.keys.firstOrNull() ?: return null
    return allies.find { it.id == advancedId && !it.isDead && it.id != currentCombatant.id }
}

/** 拉条行动决策（processTurnAdvance 提取）：RNG 顺序保持 selectSkill → selectTarget */

internal fun BattleSystem.decideAdvancedAction(
    advancedAlly: Combatant,
    advAllies: MutableList<Combatant>,
    advAliveEnemies: List<Combatant>
): Pair<CombatSkill?, Combatant> {
    val advSkill = BattleCalculator.selectSkill(
        advancedAlly, advAliveEnemies, advAllies.filter { !it.isDead }, false, rng
    )
    val advTarget = BattleCalculator.selectTarget(advancedAlly, advAliveEnemies, rng)
    return advSkill to advTarget
}

/** 拉条行动执行（processTurnAdvance 提取） */

internal fun BattleSystem.executeAdvancedAction(
    advancedAlly: Combatant,
    advTarget: Combatant,
    advSkill: CombatSkill?,
    advDmgMod: Double
): AttackResult = if (advSkill != null) {
    executeSkill(advancedAlly, advTarget, advSkill, advDmgMod)
} else {
    executeAttack(advancedAlly, advTarget, advDmgMod)
}

/** 拉条行动日志（processTurnAdvance 提取） */

internal fun BattleSystem.buildAdvancedActionLog(
    advSkill: CombatSkill?,
    advResult: AttackResult,
    advancedAlly: Combatant,
    isTeamMember: Boolean,
    advDmg: Int
): BattleActionData = BattleActionData(
    type = if (advSkill != null) "skill" else "attack",
    attacker = advancedAlly.name,
    attackerType = if (isTeamMember) "disciple" else "beast",
    target = advResult.target.name,
    damage = advDmg,
    damageType = if (advResult.isPhysical) "物理" else "法术",
    isCrit = advResult.isCrit,
    isKill = advResult.target.hp - advDmg <= 0,
    message = "${advancedAlly.name}被拉条立即行动！",
    skillName = advResult.skillName
)

/** 拉条伤害结算（processTurnAdvance 提取）：护盾吸收经共享 BattleDamageApplier */

internal fun BattleSystem.applyAdvancedDamage(
    ctx: TurnContext,
    advResult: AttackResult,
    advDmg: Int,
    isTeamMember: Boolean,
    enemiesIndexMap: Map<String, Int>
) {
    val advTargetIdx = enemiesIndexMap[advResult.target.id] ?: return
    val currentTarget = if (isTeamMember) ctx.beasts[advTargetIdx] else ctx.team[advTargetIdx]
    val updated = BattleDamageApplier.applyDamageToTarget(currentTarget, advDmg)
    if (isTeamMember && advTargetIdx < ctx.beasts.size) {
        ctx.beasts[advTargetIdx] = updated
    } else if (advTargetIdx < ctx.team.size) {
        ctx.team[advTargetIdx] = updated
    }
}

/** 拉条行动冷却更新（processTurnAdvance 提取） */
