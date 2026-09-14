package com.xianxia.sect.core.util

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.HealType
import com.xianxia.sect.core.SkillType
import com.xianxia.sect.core.engine.domain.battle.CombatBuff
import com.xianxia.sect.core.model.CombatSkill
import com.xianxia.sect.core.engine.domain.battle.Combatant
import com.xianxia.sect.core.util.BattleCalculator.DamageResult
import com.xianxia.sect.core.util.BattleCalculator.DotResult
import com.xianxia.sect.core.util.BattleCalculator.SupportResult

// ── 战斗技能/DOT/支援结算域（自 BattleCalculator 拆出，行为零变更） ────────────
fun BattleCalculator.selectSkill(
    combatant: Combatant,
    enemies: List<Combatant>,
    allies: List<Combatant>,
    isSilenced: Boolean,
    rng: DeterministicRng
): CombatSkill? {
    val availableSkills = usableSkills(combatant, isSilenced) ?: return null

    val supportSkills = availableSkills.filter { it.skillType == SkillType.SUPPORT }
    val attackSkills = availableSkills.filter { it.skillType == SkillType.ATTACK }

    tryTacticalSkill(enemies, allies, rng, supportSkills, attackSkills)?.let { return it }

    return selectEconomySkill(combatant, attackSkills, availableSkills)
}

/** 入口守卫 + 可用技能过滤：被沉默 / 无技能 / 全部冷却或蓝不足返回 null */

internal fun BattleCalculator.usableSkills(combatant: Combatant, isSilenced: Boolean): List<CombatSkill>? {
    if (isSilenced) return null
    if (combatant.skills.isEmpty()) return null

    val availableSkills = combatant.skills.filter {
        it.currentCooldown == 0 && combatant.mp >= it.mpCost
    }
    if (availableSkills.isEmpty()) return null
    return availableSkills
}

/**
 * 战术机会臂：支援低血盟友 → 控制未受控敌人 → 多敌 AOE。
 * RNG 消费顺序与次数与拆分前完全一致。
 */

internal fun BattleCalculator.tryTacticalSkill(
    enemies: List<Combatant>,
    allies: List<Combatant>,
    rng: DeterministicRng,
    supportSkills: List<CombatSkill>,
    attackSkills: List<CombatSkill>
): CombatSkill? {
    val lowHpAllies = allies.filter { it.hpPercent < LOW_HP_THRESHOLD }
    if (lowHpAllies.isNotEmpty() && supportSkills.isNotEmpty() && rng.nextDouble() < PROB_SUPPORT_LOW_HP) {
        return supportSkills.first()
    }

    val controlSkills = attackSkills.filter { skill ->
        val localBuffType = skill.buffType
        localBuffType != null && skill.buffDuration > 0 && localBuffType.isDebuff &&
            localBuffType in setOf(BuffType.STUN, BuffType.FREEZE, BuffType.SILENCE, BuffType.TAUNT)
    }
    val uncontrolledEnemies = enemies.filter { enemy -> !enemy.hasControlEffect }
    if (uncontrolledEnemies.isNotEmpty() && controlSkills.isNotEmpty() && rng
        .nextDouble() < PROB_CONTROL_UNCONTROLLED) {
        return controlSkills.first()
    }

    val aoeSkills = attackSkills.filter { it.isAoe }
    if (enemies.size >= AOE_MIN_ENEMIES && aoeSkills.isNotEmpty() && rng.nextDouble() < PROB_AOE_MANY_ENEMIES) {
        return aoeSkills.maxByOrNull { it.damageMultiplier }
    }
    return null
}

/**
 * 低蓝 / 性价比兜底：低蓝时只选可负担的省费攻击技能
 * （否则不用技能，终态 null 不回退）；否则选性价比最优攻击技能；
 * 无攻击技能时回退首个可用技能。
 */

internal fun BattleCalculator.selectEconomySkill(
    combatant: Combatant,
    attackSkills: List<CombatSkill>,
    availableSkills: List<CombatSkill>
): CombatSkill? {
    if (combatant.mpPercent < LOW_MP_THRESHOLD && attackSkills.isNotEmpty()) {
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

fun BattleCalculator.selectTarget(
    attacker: Combatant, targets: List<Combatant>, rng: DeterministicRng
): Combatant {
    val lowHpTargets = targets.filter { it.hpPercent < LOW_HP_THRESHOLD }
    if (lowHpTargets.isNotEmpty() && rng.nextDouble() < PROB_TARGET_LOW_HP) {
        return lowHpTargets[rng.nextInt(lowHpTargets.size)]
    }

    val highThreatTargets = targets.filter { target ->
        target.skills.isNotEmpty() && target.effectivePhysicalAttack > attacker.effectivePhysicalDefense
    }
    if (highThreatTargets.isNotEmpty() && rng.nextDouble() < PROB_TARGET_HIGH_THREAT) {
        return highThreatTargets[rng.nextInt(highThreatTargets.size)]
    }

    val lowDefenseTargets = targets.filter { target ->
        val avgDefense = (target.effectivePhysicalDefense + target.effectiveMagicDefense) / 2.0
        avgDefense < attacker.effectivePhysicalAttack * 0.5
    }
    if (lowDefenseTargets.isNotEmpty() && rng.nextDouble() < PROB_TARGET_LOW_DEFENSE) {
        return lowDefenseTargets[rng.nextInt(lowDefenseTargets.size)]
    }

    return targets[rng.nextInt(targets.size)]
}

fun BattleCalculator.processDotEffects(combatants: List<Combatant>): List<DotResult> {
    val results = mutableListOf<DotResult>()

    for (combatant in combatants) {
        val poisonBuffs = combatant.buffs.filter { it.type == BuffType.POISON && it.remainingDuration > 0 }
        val burnBuffs = combatant.buffs.filter { it.type == BuffType.BURN && it.remainingDuration > 0 }

        // Long 累加防多段 DoT Int 溢出回绕（两段 Int.MAX 累加成负数 → 伤害失真兜底 1），
        // 最后钳制到 [MIN_DAMAGE, Int.MAX]，与 computeDamagePipeline 多段伤害同款模式
        var dotDamage = 0L
        poisonBuffs.forEach { buff ->
            dotDamage += (combatant.maxHp * buff.value * dotRealmFactor(buff, combatant)).toLong()
        }
        burnBuffs.forEach { buff ->
            dotDamage += (combatant.maxHp * buff.value * dotRealmFactor(buff, combatant)).toLong()
        }
        val dotDamageFinal = dotDamage
            .coerceIn(GameConfig.Battle.MIN_DAMAGE.toLong(), Int.MAX_VALUE.toLong())
            .toInt()

        if (dotDamageFinal > 0) {
            val newHp = maxOf(0, combatant.hp - dotDamageFinal)
            results.add(DotResult(combatant, dotDamageFinal, newHp))
        }
    }

    return results
}

/** DoT 境界压制倍率 = (1 + 小层增伤) × (1 + 大境界增伤) × (1 - 减伤)（与普攻/技能伤害同公式，独立乘算不进乘区） */

internal fun BattleCalculator.dotRealmFactor(buff: CombatBuff, defender: Combatant): Double {
    val factors = calculateRealmGapFactors(
        buff.sourceRealm, buff.sourceRealmLayer, defender.realm, defender.realmLayer
    )
    return (1.0 + factors.damageAmplification) *
        (1.0 + factors.majorRealmDamageAmplification) *
        (1.0 - factors.damageReduction)
}

@Suppress("UnusedParameter") // allCombatants: 语义形参：签名表达 API 决策域（调用点可读性与协议完整性优先），当前策略不消费
fun BattleCalculator.executeSupportSkill(
    caster: Combatant,
    allies: List<Combatant>,
    skill: CombatSkill,
    allCombatants: List<Combatant> = emptyList()
): SupportResult {
    val targets = when (skill.targetScope) {
        "team" -> allies
        "ally" -> emptyList() // Single ally resolved by caller
        else -> listOf(caster)
    }

    val (healAmount, healFixedAmount) = computeHealAmounts(caster, skill)
    val totalHeal = healAmount + healFixedAmount
    val teamBuffs = buildSkillBuffs(skill, targets, caster.realm, caster.realmLayer)

    return SupportResult(
        healAmount = totalHeal,
        healedIds = if (totalHeal > 0) targets.map { it.id } else emptyList(),
        teamBuffs = teamBuffs,
        turnAdvancePercent = skill.turnAdvancePercent,
        healType = skill.healType
    )
}

/** 治疗量计算（executeSupportSkill 提取）：百分比 + 固定值 */

internal fun BattleCalculator.computeHealAmounts(caster: Combatant, skill: CombatSkill): Pair<Int, Int> {
    var healAmount = 0
    var healFixedAmount = 0

    // Percentage healing
    if (skill.healPercent > 0) {
        healAmount = if (skill.healType == HealType.MP) {
            (caster.maxMp * skill.healPercent).toInt()
        } else {
            (caster.maxHp * skill.healPercent).toInt()
        }
    }

    // Fixed-value healing
    if (skill.healFixed > 0) {
        healFixedAmount = skill.healFixed
    }
    return healAmount to healFixedAmount
}

/** 团队 BUFF 构建（executeSupportSkill 提取）：护盾/伤害分担/旧单 BUFF/多 BUFF 列表 */

internal fun BattleCalculator.buildSkillBuffs(
    skill: CombatSkill,
    targets: List<Combatant>,
    sourceRealm: Int,
    sourceRealmLayer: Int
): Map<String, List<CombatBuff>> {
    val teamBuffs = mutableMapOf<String, List<CombatBuff>>()

    // Shield buff
    if (skill.shieldPercent > 0 && skill.buffDuration > 0) {
        val shieldBuff = CombatBuff(
            type = BuffType.SHIELD,
            value = skill.shieldPercent,
            remainingDuration = skill.buffDuration,
            sourceRealm = sourceRealm,
            sourceRealmLayer = sourceRealmLayer
        )
        for (member in targets) {
            teamBuffs[member.id] = listOf(shieldBuff)
        }
    }

    // Damage share buff
    if (skill.damageSharePercent > 0 && skill.buffDuration > 0) {
        val shareBuff = CombatBuff(
            type = BuffType.DAMAGE_SHARE,
            value = skill.damageSharePercent,
            remainingDuration = skill.buffDuration,
            sourceRealm = sourceRealm,
            sourceRealmLayer = sourceRealmLayer
        )
        for (member in targets) {
            teamBuffs[member.id] = listOf(shareBuff)
        }
    }

    // Legacy single buffType
    val skillBuffType = skill.buffType
    if (skillBuffType != null && skill.buffDuration > 0) {
        val buff = CombatBuff(
            type = skillBuffType,
            value = skill.buffValue,
            remainingDuration = skill.buffDuration,
            sourceRealm = sourceRealm,
            sourceRealmLayer = sourceRealmLayer
        )
        for (member in targets) {
            teamBuffs[member.id] = listOf(buff)
        }
    }

    // Multi-buff list
    for ((buffType, buffValue, buffDuration) in skill.buffs) {
        val buff = CombatBuff(
            type = buffType,
            value = buffValue,
            remainingDuration = buffDuration,
            sourceRealm = sourceRealm,
            sourceRealmLayer = sourceRealmLayer
        )
        for (member in targets) {
            val existing = teamBuffs[member.id] ?: emptyList()
            teamBuffs[member.id] = existing + buff
        }
    }
    return teamBuffs
}

fun BattleCalculator.updateCombatantCooldowns(combatant: Combatant, usedSkill: CombatSkill): Combatant {
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

fun BattleCalculator.updateCombatantBuffsOnly(combatant: Combatant): Combatant {
    val newBuffs = combatant.buffs
        .map { it.copy(remainingDuration = maxOf(0, it.remainingDuration - 1)) }
        .filter { it.remainingDuration > 0 }
    return combatant.copy(buffs = newBuffs)
}

/**
 * 跨境界斩杀判定（境界压制必杀）。
 *
 * realm 数值越小境界越高（0=仙人，9=炼气）。总小层差距 = 大境界差×每境界层数 + 层数差
 * （攻击方层数越高越强，差距增大；防御方层数越高越强，差距缩小）。
 * 攻击方比防御方高 [GameConfig.Battle.RealmGap.INSTANT_KILL_GAP] 个以上大境界（层数微调）时触发斩杀。
 *
 * realm/realmLayer 经存档篡改可越界，Int 运算会溢出回绕
 * （realmLayer=Int.MAX_VALUE 误斩秒杀任意目标、巨大 realm 漏斩），
 * 故使用 Long 中间运算 + safeRealm/safeLayer 钳制。
 *
 * @param attackerRealm 攻击方境界（数值越小境界越高）
 * @param defenderRealm 防御方境界（数值越小境界越高）
 */

internal fun BattleCalculator.calculateCombatantDodgeChance(
    attacker: Combatant,
    defender: Combatant,
    modifier: Double,
    maxDodgeChance: Double = GameConfig.Battle.MAX_DODGE_CHANCE
): Double {
    val speedDiff = attacker.effectiveSpeed - defender.effectiveSpeed
    val totalSpeed = (attacker.effectiveSpeed + defender.effectiveSpeed).coerceAtLeast(1)
    return (speedDiff.toDouble() / totalSpeed * modifier).coerceIn(0.0, maxDodgeChance)
}

internal fun BattleCalculator.tryDodge(
    attacker: Combatant,
    defender: Combatant,
    skill: CombatSkill?,
    isSkillAttack: Boolean,
    rng: DeterministicRng
): DamageResult? {
    val dodgeModifier = if (isSkillAttack) 0.3 else 0.5
    val maxDodgeChance = if (isSkillAttack) GameConfig.Battle.MAX_SKILL_DODGE_CHANCE
    else GameConfig.Battle.MAX_DODGE_CHANCE
    val dodgeChance = calculateCombatantDodgeChance(attacker, defender, dodgeModifier, maxDodgeChance)

    if (rng.nextDouble() >= dodgeChance) return null
    return DamageResult(
        damage = 0,
        isCrit = false,
        isPhysical = if (isSkillAttack) skill?.damageType == DamageType.PHYSICAL ?: true
        else attacker.physicalAttack >= attacker.magicAttack,
        isDodged = true,
        skillName = skill?.name,
        hits = skill?.hits ?: 1
    )
}

/** 正常伤害管线（calculateCombatantDamage 提取）：暴击抽数 → 波动抽数 → 分桶注入 → 段数钳制 */
