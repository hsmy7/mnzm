package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.SkillType
import com.xianxia.sect.core.model.CombatSkill
import com.xianxia.sect.core.engine.domain.battle.BattleSystem.ActionRecordData
import com.xianxia.sect.core.engine.domain.battle.BattleSystem.BeastCombatStats
import com.xianxia.sect.core.engine.domain.battle.BattleSystem.SkillActionContext
import com.xianxia.sect.core.engine.domain.battle.BattleSystem.TurnContext
import com.xianxia.sect.core.engine.domain.battle.BattleSystem.TurnOutcome
import com.xianxia.sect.core.engine.domain.battle.BattleSystem.BeastPreGenStats

internal fun BattleSystem.createBeast(
    beastRealm: Int,
    index: Int,
    beastType: String? = null,
    preGenStats: BeastPreGenStats? = null
): Combatant {
    val realmIndex = beastRealm.coerceIn(0, 9)

    val type = if (beastType != null) {
        GameConfig.Beast.TYPES.find { it.name == beastType } ?: GameConfig.Beast.getType(0)
    } else {
        GameConfig.Beast.getType(0)
    }

    val stats = resolveBeastStats(realmIndex, type, preGenStats)
    val beastSkills = buildBeastSkills(type)
    val typeIndex = GameConfig.Beast.TYPES.indexOf(type)

    return Combatant(
        id = "beast_$index",
        name = "${type.prefix}${type.name}",
        side = CombatantSide.ATTACKER,
        hp = stats.hp,
        maxHp = stats.hp,
        mp = stats.mp,
        maxMp = stats.mp,
        physicalAttack = stats.physicalAttack,
        magicAttack = stats.magicAttack,
        physicalDefense = stats.physicalDefense,
        magicDefense = stats.magicDefense,
        speed = stats.speed,
        critRate = 0.05 + realmIndex * 0.01,
        skills = beastSkills,
        realm = realmIndex,
        realmName = GameConfig.Realm.getName(realmIndex),
        realmLayer = stats.realmLayer,
        element = type.element,
        portraitRes = "beast_$typeIndex",
        isBeast = true
    )
}

/** 妖兽属性解析：预计算属性（含随机方差）或向后兼容基础值 */
internal fun BattleSystem.resolveBeastStats(
    realmIndex: Int,
    type: GameConfig.BeastTypeConfig,
    preGenStats: BeastPreGenStats?
): BeastCombatStats {
    if (preGenStats != null) {
        // 使用预计算属性（生成时已含随机方差，地图显示战力 = 战斗实际战力）
        val s = preGenStats
        // 钳制防止存档篡改或数据损坏导致异常值
        return BeastCombatStats(
            hp = s.maxHp.coerceIn(1, 10_000_000),
            mp = s.maxMp.coerceAtLeast(0),
            physicalAttack = s.physicalAttack.coerceAtLeast(0),
            magicAttack = s.magicAttack.coerceAtLeast(0),
            physicalDefense = s.physicalDefense.coerceAtLeast(0),
            magicDefense = s.magicDefense.coerceAtLeast(0),
            speed = s.speed.coerceAtLeast(0),
            realmLayer = s.realmLayer
        )
    }
    // 向后兼容：旧存档妖兽无预计算属性时，用基础值（不含随机方差）确保战斗不崩溃
    val rl = 5 // 默认中层
    val layerMult = 1.0 + (rl - 1) * 0.1
    val stats = GameConfig.Beast.getRealmStats(realmIndex)
    return BeastCombatStats(
        hp = (stats.hp * layerMult * type.hpMod).toInt(),
        mp = (stats.mp * layerMult * type.hpMod).toInt(),
        physicalAttack = (stats.attack * layerMult * type.atkMod).toInt(),
        magicAttack = (stats.attack * layerMult * type.atkMod).toInt(),
        physicalDefense = (stats.defense * layerMult * type.defMod).toInt(),
        magicDefense = (stats.defense * layerMult * type.defMod).toInt(),
        speed = (stats.speed * layerMult * type.speedMod).toInt(),
        realmLayer = rl
    )
}

/** 妖兽技能构建：模板技能配置 → CombatSkill 列表 */

internal fun BattleSystem.buildBeastSkills(type: GameConfig.BeastTypeConfig): List<CombatSkill> {
    return type.skills.map { skillConfig ->
        CombatSkill(
            name = skillConfig.name,
            skillType = skillConfig.skillType,
            damageType = skillConfig.damageType,
            damageMultiplier = skillConfig.damageMultiplier,
            mpCost = skillConfig.mpCost,
            cooldown = skillConfig.cooldown,
            hits = skillConfig.hits,
            healPercent = skillConfig.healPercent,
            healFixed = skillConfig.healFixed,
            healType = skillConfig.healType,
            buffType = skillConfig.buffType,
            buffValue = skillConfig.buffValue,
            buffDuration = skillConfig.buffDuration,
            buffs = skillConfig.buffs,
            isAoe = skillConfig.isAoe,
            targetScope = skillConfig.targetScope,
            shieldPercent = skillConfig.shieldPercent,
            turnAdvancePercent = skillConfig.turnAdvancePercent,
            damageSharePercent = skillConfig.damageSharePercent,
            damageLinkPercent = skillConfig.damageLinkPercent,
            skillDescription = skillConfig.skillDescription
        )
    }
}

/**
 * @param playerDamageModifier 玩家阵营伤害倍率（如严苛训练政策 +5%；默认 1.0）。
 * 参数透传替代原 @Volatile 单例字段（设置-执行-重置模式在异常中断时会污染后续战斗）。
 */

/** 战斗初始快照构建（executeBattleWithTimeout 提取） */

internal fun BattleSystem.buildBattleSnapshots(battle: Battle): Pair<MutableList<BattleMemberData>,
    MutableList<BattleEnemyData>> {
    val teamMembers = battle.team.map { combatant ->
        BattleMemberData(
            id = combatant.id,
            name = combatant.name,
            realm = combatant.realm,
            realmName = combatant.realmName,
            hp = combatant.hp,
            maxHp = combatant.maxHp,
            mp = combatant.mp,
            maxMp = combatant.maxMp,
            isAlive = true,
            portraitRes = combatant.portraitRes
        )
    }.toMutableList()
    val enemies = battle.beasts.map { combatant ->
        BattleEnemyData(
            id = combatant.id,
            name = combatant.name,
            realm = combatant.realm,
            realmName = combatant.realmName,
            realmLayer = combatant.realmLayer,
            hp = combatant.hp,
            maxHp = combatant.maxHp,
            isAlive = true,
            portraitRes = combatant.portraitRes
        )
    }.toMutableList()
    return teamMembers to enemies
}

/** 战斗快照逐回合刷新（executeBattleWithTimeout 提取） */

internal fun BattleSystem.updateBattleSnapshots(
    teamMembers: MutableList<BattleMemberData>,
    enemies: MutableList<BattleEnemyData>,
    currentBattle: Battle
) {
    teamMembers.forEachIndexed { index, member ->
        val combatant = currentBattle.team.find { it.id == member.id }
        if (combatant != null) {
            teamMembers[index] = member.copy(
                isAlive = !combatant.isDead,
                hp = combatant.hp,
                mp = combatant.mp
            )
        } else {
            teamMembers[index] = member.copy(
                isAlive = false,
                hp = 0,
                mp = 0
            )
        }
    }

    enemies.forEachIndexed { index, enemy ->
        val beast = currentBattle.beasts.find { it.id == enemy.id }
        if (beast != null) {
            enemies[index] = enemy.copy(
                isAlive = !beast.isDead,
                hp = beast.hp
            )
        } else {
            enemies[index] = enemy.copy(
                isAlive = false,
                hp = 0
            )
        }
    }
}

/** 战斗胜者判定（executeBattleWithTimeout 提取） */

internal fun BattleSystem.resolveBattleWinner(
    timedOut: Boolean, aliveTeam: Int, aliveBeasts: Int
): BattleWinner = when {
    timedOut -> if (aliveTeam > aliveBeasts) BattleWinner.TEAM
    else if (aliveBeasts > aliveTeam) BattleWinner.BEASTS else BattleWinner.DRAW
    aliveTeam == 0 -> BattleWinner.BEASTS
    aliveBeasts == 0 -> BattleWinner.TEAM
    else -> BattleWinner.DRAW
}

internal fun BattleSystem.executeTurnWithLog(
    battle: Battle, playerDamageModifier: Double
): Pair<Battle, BattleRoundData> {
    val allCombatants = (battle.team + battle.beasts)
        .filter { !it.isDead }
        .sortedByDescending { it.effectiveSpeed }

    val ctx = TurnContext(
        team = battle.team.toMutableList(),
        beasts = battle.beasts.toMutableList(),
        teamIndexMap = battle.team.withIndex().associate { it.value.id to it.index },
        beastsIndexMap = battle.beasts.withIndex().associate { it.value.id to it.index },
        actions = mutableListOf()
    )

    for (combatant in allCombatants) {
        if (combatant.isDead) continue
        val outcome = executeCombatantTurn(ctx, combatant, playerDamageModifier)
        if (outcome is TurnOutcome.EndBattle) {
            return Pair(
                battle.copy(team = ctx.team, beasts = ctx.beasts, isFinished = true),
                BattleRoundData(battle.turn + 1, ctx.actions)
            )
        }
    }

    processDotEffects(ctx.team, ctx.beasts, ctx.actions)

    return Pair(
        battle.copy(
            team = ctx.team,
            beasts = ctx.beasts,
            turn = battle.turn + 1
        ),
        BattleRoundData(battle.turn + 1, ctx.actions)
    )
}

/**
 * 执行单参战者回合。
 *
 * 控制效果检查 → 技能/攻击选择执行 → 行动记录 → 伤害结算 → 冷却/治疗/拉条。
 * RNG 调用顺序固定
 * （rng 经类属性访问，GameRngManager 分区调用序不变）。
 *
 * @param ctx 回合上下文（team/beasts 原地修改）
 * @param combatant 当前行动的参战者（按速度排序遍历）
 * @return Continue 继续回合；EndBattle 敌方全灭提前结束
 */

@Suppress("ReturnCount") // 卫语句密集的回合控制函数（判死/全灭/控制效果 4 处提前退出）
internal fun BattleSystem.executeCombatantTurn(
    ctx: TurnContext,
    combatant: Combatant,
    playerDamageModifier: Double
): TurnOutcome {
    if (combatant.isDead) return TurnOutcome.Continue

    val isTeamMember = combatant.side == CombatantSide.DEFENDER
    val sides = resolveTurnSides(ctx, isTeamMember)
    val allies = sides.allies
    val enemies = sides.enemies

    val aliveEnemies = enemies.filter { !it.isDead }
    if (aliveEnemies.isEmpty()) return TurnOutcome.EndBattle

    // 以 ctx 当前状态判死：回合内被击杀的单位（快照仍存活）不得继续出手
    val currentCombatant = allies.firstOrNull { it.id == combatant.id } ?: combatant
    if (currentCombatant.isDead) return TurnOutcome.Continue

    // 控制效果（眩晕/冰冻）：跳过行动并结算 BUFF，回合提前结束
    applyControlEffects(ctx, currentCombatant, isTeamMember, allies, sides.alliesIndexMap)
        ?.let { return it }

    val silenceBuff = currentCombatant.buffs.find { it.type == BuffType.SILENCE && it.remainingDuration > 0 }
    val skillDecision = selectSkill(
        currentCombatant, aliveEnemies, allies, silenceBuff != null, playerDamageModifier
    )
    val availableSkill = skillDecision.skill

    val isSupportSkill = availableSkill?.skillType == SkillType.SUPPORT
    val isAoeSkill = availableSkill?.isAoe == true && !isSupportSkill
    val results = executeSkillAction(
        SkillActionContext(currentCombatant, aliveEnemies, allies, isTeamMember, playerDamageModifier),
        availableSkill, isSupportSkill, isAoeSkill, skillDecision.action
    )

    val result = results.first(); val isInstantKill = results.any { it.isInstantKill }

    val turnMessage = buildTurnMessage(
        isInstantKill = isInstantKill,
        result = result,
        availableSkill = availableSkill,
        isAoeSkill = isAoeSkill,
        results = results,
        currentCombatant = currentCombatant
    )

    recordTurnAction(ctx, ActionRecordData(
        availableSkill = availableSkill,
        isSupportSkill = isSupportSkill,
        isAoeSkill = isAoeSkill,
        isInstantKill = isInstantKill,
        isTeamMember = isTeamMember,
        result = result,
        turnMessage = turnMessage,
        currentCombatant = currentCombatant,
        isCrit = results.any { it.isCrit }
    ))

    if (!result.isSupport) {
        applyDamageEffects(
            ctx, results, isTeamMember,
            currentCombatant, availableSkill, isAoeSkill, aliveEnemies
        )
    }

    if (availableSkill != null) {
        applyCooldownUpdate(ctx, sides.alliesIndexMap, currentCombatant, availableSkill, isTeamMember)
        if (isSupportSkill) {
            applySupportEffects(ctx, result, allies, isTeamMember, currentCombatant, playerDamageModifier)
        }
    } else {
        updateCombatantBuffs(currentCombatant, allies, sides.alliesIndexMap)
    }
    return TurnOutcome.Continue
}

/** 回合双方阵营解析打包（executeCombatantTurn 提取，消除 4 个三元 if） */
