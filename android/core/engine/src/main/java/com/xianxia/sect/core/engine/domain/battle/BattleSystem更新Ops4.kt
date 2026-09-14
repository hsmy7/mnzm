package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.model.CombatSkill
import com.xianxia.sect.core.util.BattleCalculator
import com.xianxia.sect.core.util.updateCombatantCooldowns
import com.xianxia.sect.core.util.updateCombatantBuffsOnly
import com.xianxia.sect.core.engine.domain.battle.BattleSystem.SkillDecision
import com.xianxia.sect.core.engine.domain.battle.BattleSystem.TurnContext
import com.xianxia.sect.core.engine.domain.battle.BattleSystem.TurnOutcome
import com.xianxia.sect.core.util.processDotEffects
import com.xianxia.sect.core.util.executeSupportSkill
import com.xianxia.sect.core.util.calculateRealmGapFactors

/** 拉条行动冷却更新（processTurnAdvance 提取） */
// ── BattleSystem 拆分域 4/4（行为零变更） ──
internal fun BattleSystem.updateAdvancedCooldown(
    ctx: TurnContext,
    advSkill: CombatSkill,
    advancedAlly: Combatant,
    isTeamMember: Boolean,
    advIdx: Int
) {
    val updatedAdv = BattleCalculator.updateCombatantCooldowns(advancedAlly, advSkill)
    if (isTeamMember) ctx.team[advIdx] = updatedAdv else ctx.beasts[advIdx] = updatedAdv
}

/**
 * 控制效果处理：眩晕/冰冻时记录控制日志、结算 BUFF，回合提前结束。
 *
 * @return 被控制时返回 Continue（本回合结束）；否则 null 表示继续正常行动
 */

internal fun BattleSystem.applyControlEffects(
    ctx: TurnContext,
    currentCombatant: Combatant,
    isTeamMember: Boolean,
    allies: MutableList<Combatant>,
    alliesIndexMap: Map<String, Int>
): TurnOutcome? {
    if (!currentCombatant.hasControlEffect) return null
    val stunBuff = currentCombatant.buffs.find { it.type == BuffType.STUN || it.type == BuffType.FREEZE }
        ?: return null
    ctx.actions.add(BattleActionData(
        type = "control",
        attacker = currentCombatant.name,
        attackerType = if (isTeamMember) "disciple" else if (currentCombatant.isBeast) "beast" else "disciple",
        target = currentCombatant.name,
        damage = 0,
        damageType = if (stunBuff.type == BuffType.STUN) "眩晕" else "冰冻",
        message = "${currentCombatant.name}因${stunBuff.type.displayName}无法行动！"
    ))
    updateCombatantBuffs(currentCombatant, allies, alliesIndexMap)
    return TurnOutcome.Continue
}

internal fun BattleSystem.updateCombatantBuffs(
    combatant: Combatant,
    list: MutableList<Combatant>,
    indexMap: Map<String,
    Int>
) {
    val idx = indexMap[combatant.id] ?: return
    if (idx >= list.size) return
    val updated = BattleCalculator.updateCombatantBuffsOnly(combatant)
    list[idx] = updated
}

internal fun BattleSystem.processDotEffects(team: MutableList<Combatant>, beasts: MutableList<Combatant>,
    actions: MutableList<BattleActionData>) {
    val allCombatants = (team + beasts).filter { !it.isDead }
    val dotResults = BattleCalculator.processDotEffects(allCombatants)
    for (result in dotResults) {
        val isTeamMember = result.combatant.side == CombatantSide.DEFENDER
        if (isTeamMember) {
            val idx = team.indexOfFirst { it.id == result.combatant.id }
            if (idx >= 0) team[idx] = team[idx].copy(hp = result.newHp)
        } else {
            val idx = beasts.indexOfFirst { it.id == result.combatant.id }
            if (idx >= 0) beasts[idx] = beasts[idx].copy(hp = result.newHp)
        }
        actions.add(BattleActionData(
            type = "dot",
            attacker = "",
            attackerType = "",
            target = result.combatant.name,
            damage = result.damage,
            damageType = "持续伤害",
            isKill = result.newHp <= 0,
            message = "${result.combatant.name}受到${result.damage}点持续伤害"
        ))
    }
}

internal fun BattleSystem.executeAttack(
    attacker: Combatant, defender: Combatant, damageModifier: Double = 1.0
): AttackResult {
    val result = BattleCalculator.calculateCombatantDamage(
        attacker, defender, null, damageModifier = damageModifier, rng = rng, enableInstantKill = true
    )
    return AttackResult(
        attacker = attacker,
        target = defender,
        damage = result.damage,
        isCrit = result.isCrit,
        isPhysical = result.isPhysical,
        isDodged = result.isDodged,
        isInstantKill = result.isInstantKill
    )
}

internal fun BattleSystem.executeSkill(attacker: Combatant, defender: Combatant, skill: CombatSkill,
    damageModifier: Double = 1.0): AttackResult {
    val result = BattleCalculator.calculateCombatantDamage(
        attacker, defender, skill, damageModifier = damageModifier, rng = rng, enableInstantKill = true
    )
    return AttackResult(
        attacker = attacker,
        target = defender,
        damage = result.damage,
        isCrit = result.isCrit,
        isPhysical = result.isPhysical,
        isDodged = result.isDodged,
        skillName = result.skillName,
        hits = result.hits
    )
}

internal fun BattleSystem.executeSupportSkill(
    caster: Combatant,
    allies: List<Combatant>,
    skill: CombatSkill
): AttackResult {
    val supportResult = BattleCalculator.executeSupportSkill(caster, allies, skill)
    return AttackResult(
        attacker = caster,
        target = caster,
        damage = 0,
        isCrit = false,
        isPhysical = false,
        isDodged = false,
        skillName = skill.name,
        hits = 1,
        isSupport = true,
        message = "",
        healPercent = skill.healPercent,
        healType = skill.healType,
        healAmount = supportResult.healAmount,
        healedIds = supportResult.healedIds,
        newBuffs = emptyList(),
        teamBuffs = supportResult.teamBuffs,
        turnAdvancePercent = supportResult.turnAdvancePercent
    )
}

/**
 * 跨境界压制因子（独立乘算，不进乘区，不会被同一乘区加算稀释）。
 *
 * realm 数值越小境界越高（0=仙人，9=炼气），realmLayer 1~9（1=初层）。
 * 小层差距沿用 [checkInstantKill] 的归一化公式：
 *   layerGap = (defenderRealm - attackerRealm) × LAYERS_PER_REALM + (attackerLayer - defenderLayer)
 * layerGap > 0 表示攻击方境界更高：增伤因子 = 每层加成 × layerGap（不封顶）
 * layerGap < 0 表示防守方境界更高：减伤 = min(1.0, 每层减伤 × (-layerGap))（封顶 100%）
 * 大境界差 = defenderRealm - attackerRealm，> 0 表示攻击方高 N 个大境界：
 *   大境界增伤因子 = 每大境界加成 × 大境界差（仅增伤方向，反向无对称减伤）。
 * 三因子各自独立乘算，可同时生效（大境界加成与小层加成叠加）。
 *
 * 注意：直伤路径上高 2 个大境界及以上时 [checkInstantKill] 必杀优先，
 * 大境界加成仅在"高 1 个大境界"及 DoT 路径完整生效；因子仍按大境界差累加计算（DoT 全档生效）。
 *
 * @param attackerRealm 攻击方境界（数值越小境界越高）
 * @param attackerLayer 攻击方小层（1~9，0/越界按初层 1 回退）
 * @param defenderRealm 防守方境界
 * @param defenderLayer 防守方小层
 */
fun BattleSystem.calculateRealmGapFactors(
    attackerRealm: Int,
    attackerLayer: Int,
    defenderRealm: Int,
    defenderLayer: Int
): BattleCalculator.RealmGapFactors {
    return BattleCalculator.calculateRealmGapFactors(
        attackerRealm, attackerLayer, defenderRealm, defenderLayer
    )
}


internal fun BattleSystem.selectSkill(
    combatant: Combatant,
    enemies: List<Combatant>,
    allies: List<Combatant>,
    isSilenced: Boolean,
    playerDamageModifier: Double
): SkillDecision {
    if (isSilenced) return SkillDecision(null, null)
    // 玩家侧 AI 决策透传伤害倍率（与严苛训练 +5% 估算一致）
    val dmgMod = if (combatant.side == CombatantSide.DEFENDER) playerDamageModifier else 1.0
    val action = BattleAI.decideAction(combatant, allies, enemies, rng, dmgMod)
    return SkillDecision(action.skill, action)
}

internal fun BattleSystem.selectTarget(
    attacker: Combatant, targets: List<Combatant>, aiAction: BattleAI.AIAction?
): Combatant {
    if (aiAction?.target != null) return aiAction.target
    return BattleAI.selectAttackTarget(attacker, targets, null, rng)
        ?: targets.first()
}

internal fun BattleSystem.generateRewards(beastCount: Int): Map<String, Int> {
    val rewards = mutableMapOf<String, Int>()
    rewards["spiritStones"] = 100 * beastCount
    return rewards
}
