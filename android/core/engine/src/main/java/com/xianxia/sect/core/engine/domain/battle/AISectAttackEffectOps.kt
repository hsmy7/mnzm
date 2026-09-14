package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.HealType
import com.xianxia.sect.core.model.CombatSkill
import com.xianxia.sect.core.model.BattleLogAction
import com.xianxia.sect.core.util.BattleCalculator
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.BattleCalculator.SupportResult
import com.xianxia.sect.core.engine.domain.battle.AISectAttackManager.AiSkillDecision
import com.xianxia.sect.core.util.updateCombatantCooldowns
import com.xianxia.sect.core.util.processDotEffects

/** 支援治疗写回（executeSupportAction 提取） */
// ── AI 战斗效果与应用域（自 AISectAttackManager 拆出，行为零变更） ─────────────
internal fun AISectAttackManager.applySupportHealing(
    supportResult: SupportResult,
    alliesList: MutableList<Combatant>,
    alliesIndexMap: Map<String, Int>,
    skill: CombatSkill
) {
    if (supportResult.healAmount <= 0) return
    supportResult.healedIds.forEach { healedId ->
        val idx = alliesIndexMap[healedId]
        if (idx != null && idx < alliesList.size) {
            if (skill.healType == HealType.MP) {
                alliesList[idx] = alliesList[idx].copy(mp = minOf(alliesList[idx].mp + supportResult.healAmount,
                    alliesList[idx].maxMp))
            } else {
                alliesList[idx] = alliesList[idx].copy(hp = minOf(alliesList[idx].hp + supportResult.healAmount,
                    alliesList[idx].maxHp))
            }
        }
    }
}

/** 支援团队 BUFF 写回（executeSupportAction 提取） */

internal fun AISectAttackManager.applySupportTeamBuffs(
    supportResult: SupportResult,
    alliesList: MutableList<Combatant>,
    alliesIndexMap: Map<String, Int>
) {
    supportResult.teamBuffs.forEach { (memberId, buffs) ->
        val idx = alliesIndexMap[memberId]
        if (idx != null && idx < alliesList.size) {
            alliesList[idx] = alliesList[idx].copy(buffs = alliesList[idx].buffs + buffs)
        }
    }
}

/** 支援施放者冷却更新（executeSupportAction 提取） */

internal fun AISectAttackManager.updateSupportCooldown(
    caster: Combatant,
    alliesList: MutableList<Combatant>,
    alliesIndexMap: Map<String, Int>,
    skill: CombatSkill
) {
    val combatantIdx = alliesIndexMap[caster.id]
    if (combatantIdx != null && combatantIdx < alliesList.size) {
        alliesList[combatantIdx] = BattleCalculator.updateCombatantCooldowns(caster, skill)
    }
}

/** 支援行动日志（executeSupportAction 提取） */

internal fun AISectAttackManager.buildSupportActionLog(
    caster: Combatant,
    allies: List<Combatant>,
    supportResult: SupportResult,
    skill: CombatSkill
): BattleLogAction = BattleLogAction(
    type = "support", attacker = caster.name,
    attackerType = if (caster.side == CombatantSide.ATTACKER) "attacker" else "defender",
    target = allies.joinToString("、") { it.name }, damage = supportResult.healAmount,
    skillName = skill.name,
    message = "${caster.name} 施展 ${skill.name}" +
        if (supportResult.healAmount > 0) "，恢复 ${supportResult.healedIds.size} 名友方 ${supportResult.healAmount}"
        else if (supportResult.teamBuffs.isNotEmpty()) "，强化 ${supportResult.teamBuffs.size} 名友方"
        else ""
)

/**
 * 伤害链接 debuff 附加（与主引擎 applyDamageLinkDebuff 语义一致）：
 * 清掉旧的链接标记再附加新链接（同时仅一个链接）。
 */

internal fun AISectAttackManager.applyLinkDebuff(
    attacker: Combatant,
    target: Combatant,
    skill: CombatSkill
): Combatant {
    val linkPercent = skill.damageLinkPercent
    if (linkPercent <= 0 || skill.buffDuration <= 0) return target
    val cleaned = target.buffs.filter { it.type != BuffType.DAMAGE_LINK }
    return cleaned.let { buffs ->
        target.copy(
            buffs = buffs + CombatBuff(
                type = BuffType.DAMAGE_LINK,
                value = linkPercent,
                remainingDuration = skill.buffDuration,
                sourceRealm = attacker.realm,
                sourceRealmLayer = attacker.realmLayer
            )
        )
    }
}

/**
 * 伤害分摊/链接应用（共享应用层 [BattleDamageApplier]）。
 * attackers/defenders 映射为 BattleDamageApplier 的 team(DEFENDER)/beasts(ATTACKER) 语义。
 */

internal fun AISectAttackManager.applyShareAndLink(
    attacker: Combatant,
    target: Combatant,
    damage: Int,
    allies: MutableList<Combatant>,
    enemies: MutableList<Combatant>
) {
    val team = if (attacker.side == CombatantSide.DEFENDER) allies else enemies
    val beasts = if (attacker.side == CombatantSide.DEFENDER) enemies else allies
    BattleDamageApplier.applySharedDamage(target, damage, team, beasts)
        .forEach { (id, updated) -> writeBackToLists(id, updated, allies, enemies) }
    BattleDamageApplier.applyLinkedDamage(attacker, target, damage, team, beasts)
        .forEach { (id, updated) -> writeBackToLists(id, updated, allies, enemies) }
}

internal fun AISectAttackManager.writeBackToLists(
    id: String,
    updated: Combatant,
    allies: MutableList<Combatant>,
    enemies: MutableList<Combatant>
) {
    val idxA = allies.indexOfFirst { it.id == id }
    if (idxA >= 0) {
        allies[idxA] = updated
    } else {
        val idxE = enemies.indexOfFirst { it.id == id }
        if (idxE >= 0) enemies[idxE] = updated
    }
}

internal fun AISectAttackManager.processDotEffects(
    attackers: MutableList<Combatant>,
    defenders: MutableList<Combatant>
) {
    val allCombatants = (attackers + defenders).filter { !it.isDead }
    val dotResults = BattleCalculator.processDotEffects(allCombatants)
    for (result in dotResults) {
        val isAttacker = result.combatant.side == CombatantSide.ATTACKER
        val list = if (isAttacker) attackers else defenders
        val idx = list.indexOfFirst { it.id == result.combatant.id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(hp = result.newHp)
        }
    }
}


internal fun AISectAttackManager.selectAISkill(
    combatant: Combatant,
    enemies: List<Combatant>,
    allies: List<Combatant>,
    isSilenced: Boolean,
    rng: DeterministicRng
): AiSkillDecision {
    if (isSilenced) return AiSkillDecision(null, null)
    val action = BattleAI.decideAction(combatant, allies, enemies, rng)
    return AiSkillDecision(action.skill, action)
}

internal fun AISectAttackManager.selectAITarget(
    attacker: Combatant,
    targets: List<Combatant>,
    aiAction: BattleAI.AIAction?,
    rng: DeterministicRng
): Combatant {
    val aliveTargets = targets.filter { !it.isDead }
    if (aliveTargets.isEmpty()) return targets.first()
    return aiAction?.target
        ?: BattleAI.selectAttackTarget(attacker, aliveTargets, null, rng)
        ?: aliveTargets.first()
}
