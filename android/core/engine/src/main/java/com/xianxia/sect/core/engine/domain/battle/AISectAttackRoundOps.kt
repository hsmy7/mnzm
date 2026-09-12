package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.SkillType
import com.xianxia.sect.core.model.CombatSkill
import com.xianxia.sect.core.model.BattleLogAction
import com.xianxia.sect.core.model.BattleLogRound
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.nativebridge.GameCoreBridge
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonArray
import com.xianxia.sect.core.util.BattleCalculator
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.engine.domain.battle.AISectAttackManager.AiBattleRoundOutcome
import com.xianxia.sect.core.engine.domain.battle.AISectAttackManager.UnifiedAIBattleResult
import com.xianxia.sect.core.engine.domain.battle.AISectAttackManager.BattleWriteBackContext
import com.xianxia.sect.core.util.updateCombatantCooldowns
import com.xianxia.sect.core.util.updateCombatantBuffsOnly
import com.xianxia.sect.core.util.executeSupportSkill
import com.xianxia.sect.core.util.processDotEffects

/**
 * 单用户定向补偿邮件（MailService 扩展，独立文件）。
 *
 * 拆分原因：MailService 类主体接近 detekt LargeClass（800 行）阈值，
 * 补偿邮件属独立运营配置，放独立文件保持 MailService 规模稳定；
 * stateStore/mailRepo 已放宽为 internal 供本扩展读取（三重防护）。
 */
// ── AI 战斗回合执行域（自 AISectAttackManager 拆出，行为零变更） ───────────────

private val TAG = AISectAttackManager.TAG
/**
 * 单个参战者回合行动：控制效果跳过 / 支援 / AOE / 单体技能 / 普攻四分支。
 * 原地修改 [currentAttackers]/[currentDefenders] 中对应 combatant。
 */
internal fun AISectAttackManager.executeAiCombatantTurn(
    currentAttackers: MutableList<Combatant>,
    currentDefenders: MutableList<Combatant>,
    combatant: Combatant,
    roundActions: MutableList<BattleLogAction>
) {
    val isAttacker = combatant.side == CombatantSide.ATTACKER
    val allies = if (isAttacker) currentAttackers else currentDefenders
    val enemies = if (isAttacker) currentDefenders else currentAttackers
    val alliesIndexMap = allies.withIndex().associate { it.value.id to it.index }
    val enemiesIndexMap = enemies.withIndex().associate { it.value.id to it.index }

    val aliveEnemies = enemies.filter { !it.isDead }
    if (aliveEnemies.isEmpty()) return

    val combatantIdx = alliesIndexMap[combatant.id] ?: return
    val currentCombatant = allies[combatantIdx]

    if (currentCombatant.hasControlEffect) {
        allies[combatantIdx] = BattleCalculator.updateCombatantBuffsOnly(currentCombatant)
        return
    }

    val silenceBuff = currentCombatant.buffs.find { it.type == BuffType.SILENCE && it.remainingDuration > 0 }
    val skillDecision = selectAISkill(
        currentCombatant, aliveEnemies, allies.filter { !it.isDead }, silenceBuff != null
    )
    val availableSkill = skillDecision.skill

    val isSupportSkill = availableSkill?.skillType == SkillType.SUPPORT
    val isAoeSkill = availableSkill?.isAoe == true && !isSupportSkill

    val writeBack = BattleWriteBackContext(allies, enemies, alliesIndexMap, enemiesIndexMap, roundActions)

    if (availableSkill != null && isSupportSkill) {
        executeSupportAction(currentCombatant, allies.filter { !it.isDead }, availableSkill, allies, alliesIndexMap,
            roundActions)
    } else if (availableSkill != null && isAoeSkill) {
        executeAoeAttackAction(currentCombatant, aliveEnemies, availableSkill, writeBack)
    } else if (availableSkill != null) {
        val target = selectAITarget(currentCombatant, aliveEnemies, skillDecision.action)
        executeSingleAttackAction(currentCombatant, target, availableSkill, writeBack)
    } else {
        val target = selectAITarget(currentCombatant, aliveEnemies, skillDecision.action)
        executeNormalAttackAction(currentCombatant, target, writeBack)
    }
}

/**
 * AUTHORITATIVE 下经 C++ 第三战斗引擎执行 AI 宗门战
 * （sect_battle.h executeUnifiedAIBattle 等价）。降级契约：flag 关 /
 * native 未加载 / 失败信封 → null，调用方回退 Kotlin。
 */

@Suppress("ReturnCount")  // 多 return 为降级契约（flag 关/native 不可用/失败信封逐级返回）
internal fun AISectAttackManager.tryExecuteUnifiedNative(
    combatAttackers: List<Combatant>,
    combatDefenders: List<Combatant>
): UnifiedAIBattleResult? {
    if (!NativeEngineFlag.authoritative) return null
    if (!GameCoreBridge.isLoaded) return null
    val op = buildJsonObject {
        putJsonArray("attackers") { combatAttackers.forEach { add(BattleJsonCodec.combatantJson(it)) } }
        putJsonArray("defenders") { combatDefenders.forEach { add(BattleJsonCodec.combatantJson(it)) } }
    }
    val out = Json.parseToJsonElement(
        GameCoreBridge.nativeAiBattleExecute(op.toString().encodeToByteArray()).decodeToString()
    ).jsonObject
    if (out.containsKey("error")) return null

    val winner = when (out["winner"]?.jsonPrimitive?.content) {
        "ATTACKER" -> AIBattleWinner.ATTACKER
        "DEFENDER" -> AIBattleWinner.DEFENDER
        else -> AIBattleWinner.DRAW
    }
    val turns = out["turns"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
    val attackers = (out["attackers"] as? kotlinx.serialization.json.JsonArray)
        ?.map { BattleJsonCodec.combatantFromJson(it.jsonObject) } ?: emptyList()
    val defenders = (out["defenders"] as? kotlinx.serialization.json.JsonArray)
        ?.map { BattleJsonCodec.combatantFromJson(it.jsonObject) } ?: emptyList()
    return UnifiedAIBattleResult(
        attackers = attackers,
        defenders = defenders,
        winner = winner,
        turns = turns,
        rounds = rebuildAiRounds(out)
    )
}

/**
 * AUTHORITATIVE 下经 C++ 判定战胜后占领（sect_attack_decision.h
 * computeCanOccupy，纯确定性零 RNG）。降级：flag 关/native 未加载/异常 → null
 * （调用方在 executeSectBattleCore 回退 Kotlin 原判定）。
 */

@Suppress("TooGenericExceptionCaught")
internal fun AISectAttackManager.tryNativeComputeCanOccupy(
    winnerIsAttacker: Boolean,
    allSectDisciples: List<Disciple>,
    deadDefenderIds: List<String>
): Boolean? {
    if (!NativeEngineFlag.authoritative || !GameCoreBridge.isLoaded) return null
    val payload = buildJsonObject {
        put("winnerIsAttacker", JsonPrimitive(winnerIsAttacker))
        putJsonArray("defenders") {
            allSectDisciples.forEach { add(Json.encodeToJsonElement(Disciple.serializer(), it)) }
        }
        putJsonArray("deadDefenderIds") { deadDefenderIds.forEach { add(JsonPrimitive(it)) } }
    }
    return try {
        GameCoreBridge.nativeComputeCanOccupy(payload.toString().encodeToByteArray())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainLog.w(TAG, "nativeComputeCanOccupy degraded to Kotlin: $e")
        null
    }
}

/** C++ rounds JSON → Kotlin BattleLogRound 列表（确定性动作重建）。 */

internal fun AISectAttackManager.rebuildAiRounds(out: kotlinx.serialization.json.JsonObject): List<BattleLogRound> =
    (out["rounds"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { rj ->
        val r = rj.jsonObject
        val actions = (r["actions"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { aj ->
                val o = aj.jsonObject
                BattleLogAction(
                    type = o["type"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    attacker = o["attacker"]?.jsonPrimitive?.content ?: "",
                    attackerType = o["attackerType"]?.jsonPrimitive?.content ?: "",
                    target = o["target"]?.jsonPrimitive?.content ?: "",
                    damage = o["damage"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    isCrit = o["isCrit"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    isKill = o["isKill"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    message = "",
                    skillName = o["skillName"]?.jsonPrimitive?.content
                )
            } ?: emptyList()
        BattleLogRound(
            roundNumber = r["roundNumber"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            actions = actions
        )
    } ?: emptyList()

/** AI 宗门战单回合（executeUnifiedAIBattle 提取）：保留快照后击杀的 isDead 运行时守卫 */

internal fun AISectAttackManager.executeAiRound(
    currentAttackers: MutableList<Combatant>,
    currentDefenders: MutableList<Combatant>,
    startTime: Long,
    roundNumber: Int
): AiBattleRoundOutcome {
    // 超时保护（对齐 BattleSystem 5000ms）：每旬大量 AI 宗门战在游戏线程执行，
    // 拉锯战（高防低攻）不得无限占用主线程
    if (System.currentTimeMillis() - startTime > GameConfig.AI.MAX_AI_BATTLE_DURATION_MS) {
        return AiBattleRoundOutcome(
            timedOut = true, ended = false, attackers = currentAttackers,
            defenders = currentDefenders, round = BattleLogRound(roundNumber, emptyList())
        )
    }
    var attackers = currentAttackers
    var defenders = currentDefenders
    val roundActions = mutableListOf<BattleLogAction>()
    val allCombatants = (attackers + defenders)
        .filter { !it.isDead }
        .sortedByDescending { it.effectiveSpeed }

    for (combatant in allCombatants) {
        if (combatant.isDead) continue
        executeAiCombatantTurn(
            attackers, defenders, combatant, roundActions
        )
        attackers = attackers.filter { !it.isDead }.toMutableList()
        defenders = defenders.filter { !it.isDead }.toMutableList()
    }

    processDotEffects(attackers, defenders)

    return AiBattleRoundOutcome(
        timedOut = false,
        ended = attackers.isEmpty() || defenders.isEmpty(),
        attackers = attackers,
        defenders = defenders,
        round = BattleLogRound(roundNumber = roundNumber, actions = roundActions.toList())
    )
}

/** AI 宗门战胜者判定（executeUnifiedAIBattle 提取） */

internal fun AISectAttackManager.resolveAiWinner(
    currentAttackers: List<Combatant>,
    currentDefenders: List<Combatant>,
    timedOut: Boolean
): AIBattleWinner = when {
    currentDefenders.isEmpty() -> AIBattleWinner.ATTACKER
    currentAttackers.isEmpty() -> AIBattleWinner.DEFENDER
    // 超时后按存活数多者胜（与 BattleSystem 超时语义对齐），
    // 避免僵局战一律 DRAW 使攻击方无损失（玩家高防驻军=免伤屏障）
    timedOut && currentAttackers.size != currentDefenders.size ->
        if (currentAttackers.size > currentDefenders.size) AIBattleWinner.ATTACKER
        else AIBattleWinner.DEFENDER
    else -> AIBattleWinner.DRAW
}

internal fun AISectAttackManager.executeNormalAttackAction(
    attacker: Combatant,
    target: Combatant,
    ctx: BattleWriteBackContext
) {
    val result = BattleCalculator.calculateCombatantDamage(
        attacker, target, null, rng = aisRng, enableInstantKill = true
    )
    if (result.isInstantKill) {
        val targetIdx = ctx.enemiesIndexMap[target.id]
        if (targetIdx != null && targetIdx < ctx.enemies.size) {
            ctx.enemies[targetIdx] = ctx.enemies[targetIdx].copy(hp = 0)
        }
        val combatantIdx = ctx.alliesIndexMap[attacker.id]
        if (combatantIdx != null && combatantIdx < ctx.allies.size) {
            ctx.allies[combatantIdx] = BattleCalculator.updateCombatantBuffsOnly(attacker)
        }
        ctx.roundActions.add(BattleLogAction(
            type = "normal", attacker = attacker.name,
            attackerType = if (attacker.side == CombatantSide.ATTACKER) "attacker" else "defender",
            target = target.name, damage = target.maxHp,
            isKill = true, message = "${attacker.name} 境界压制斩杀 ${target.name}"
        ))
        return
    }

    if (result.isDodged) {
        val combatantIdx = ctx.alliesIndexMap[attacker.id]
        if (combatantIdx != null && combatantIdx < ctx.allies.size) {
            ctx.allies[combatantIdx] = BattleCalculator.updateCombatantBuffsOnly(attacker)
        }
        ctx.roundActions.add(BattleLogAction(
            type = "normal", attacker = attacker.name,
            attackerType = if (attacker.side == CombatantSide.ATTACKER) "attacker" else "defender",
            target = target.name, damage = 0,
            message = "${target.name} 闪避了 ${attacker.name} 的攻击"
        ))
        return
    }

    applyNormalAttackDamage(
        attacker, target, result, ctx
    )
}

/**
 * 普攻伤害应用：扣除目标 HP、刷新攻击者 BUFF、记录行动日志。
 * 从 executeNormalAttackAction 提取（正常伤害分支）。
 */

internal fun AISectAttackManager.applyNormalAttackDamage(
    attacker: Combatant,
    target: Combatant,
    result: BattleCalculator.DamageResult,
    ctx: BattleWriteBackContext
) {
    var newHp = target.hp
    val targetIdx = ctx.enemiesIndexMap[target.id]
    if (targetIdx != null && targetIdx < ctx.enemies.size) {
        // 护盾吸收 + 扣血（共享应用层，与主战斗引擎一致）
        val updated = BattleDamageApplier.applyDamageToTarget(ctx.enemies[targetIdx], result.damage)
        ctx.enemies[targetIdx] = updated
        newHp = updated.hp
        // 伤害分摊/链接（AI 弟子技能可能带 damageShare/damageLink）
        applyShareAndLink(attacker, updated, result.damage, ctx.allies, ctx.enemies)
    }

    val combatantIdx = ctx.alliesIndexMap[attacker.id]
    if (combatantIdx != null && combatantIdx < ctx.allies.size) {
        ctx.allies[combatantIdx] = BattleCalculator.updateCombatantBuffsOnly(attacker)
    }
    ctx.roundActions.add(BattleLogAction(
        type = "normal", attacker = attacker.name,
        attackerType = if (attacker.side == CombatantSide.ATTACKER) "attacker" else "defender",
        target = target.name, damage = result.damage,
        isCrit = result.isCrit, isKill = newHp == 0
    ))
}

internal fun AISectAttackManager.executeSingleAttackAction(
    attacker: Combatant,
    target: Combatant,
    skill: CombatSkill,
    ctx: BattleWriteBackContext
) {
    val result = BattleCalculator.calculateCombatantDamage(
        attacker, target, skill, rng = aisRng, enableInstantKill = true
    )
    if (result.isInstantKill) {
        val targetIdx = ctx.enemiesIndexMap[target.id]
        if (targetIdx != null && targetIdx < ctx.enemies.size) {
            ctx.enemies[targetIdx] = ctx.enemies[targetIdx].copy(hp = 0)
        }
        val combatantIdx = ctx.alliesIndexMap[attacker.id]
        if (combatantIdx != null && combatantIdx < ctx.allies.size) {
            ctx.allies[combatantIdx] = BattleCalculator.updateCombatantCooldowns(attacker, skill)
        }
        ctx.roundActions.add(BattleLogAction(
            type = "skill", attacker = attacker.name,
            attackerType = if (attacker.side == CombatantSide.ATTACKER) "attacker" else "defender",
            target = target.name, damage = target.maxHp, skillName = skill.name,
            isKill = true, message = "${attacker.name} 以 ${skill.name} 境界压制斩杀 ${target.name}"
        ))
        return
    }

    if (result.isDodged) {
        val combatantIdx = ctx.alliesIndexMap[attacker.id]
        if (combatantIdx != null && combatantIdx < ctx.allies.size) {
            ctx.allies[combatantIdx] = BattleCalculator.updateCombatantCooldowns(attacker, skill)
        }
        ctx.roundActions.add(BattleLogAction(
            type = "skill", attacker = attacker.name,
            attackerType = if (attacker.side == CombatantSide.ATTACKER) "attacker" else "defender",
            target = target.name, damage = 0, skillName = skill.name,
            message = "${target.name} 闪避了 ${attacker.name} 的 ${skill.name}"
        ))
        return
    }

    applySingleSkillDamage(
        attacker, target, skill, result, ctx
    )
}

/**
 * 单体技能伤害应用：扣除目标 HP、附加技能 debuff、刷新攻击者冷却、记录日志。
 * 从 executeSingleAttackAction 提取（正常伤害分支）。
 */

internal fun AISectAttackManager.applySingleSkillDamage(
    attacker: Combatant,
    target: Combatant,
    skill: CombatSkill,
    result: BattleCalculator.DamageResult,
    ctx: BattleWriteBackContext
) {
    var newHp = target.hp
    val targetIdx = ctx.enemiesIndexMap[target.id]
    if (targetIdx != null && targetIdx < ctx.enemies.size) {
        // 护盾吸收 + 扣血（共享应用层）
        var updatedTarget = BattleDamageApplier.applyDamageToTarget(ctx.enemies[targetIdx], result.damage)
        newHp = updatedTarget.hp

        val localBuffType = skill.buffType
        if (localBuffType != null && skill.buffDuration > 0) {
            val debuff = CombatBuff(
                type = localBuffType,
                value = skill.buffValue,
                remainingDuration = skill.buffDuration,
                sourceRealm = attacker.realm,
                sourceRealmLayer = attacker.realmLayer
            )
            updatedTarget = updatedTarget.copy(buffs = updatedTarget.buffs + debuff)
        }
        // 伤害链接 debuff（AI 战需与主引擎一致——
        // 清旧链接再附加，否则链接效果在宗门战恒为零）
        updatedTarget = applyLinkDebuff(attacker, updatedTarget, skill)

        ctx.enemies[targetIdx] = updatedTarget
        // 伤害分摊/链接
        applyShareAndLink(attacker, updatedTarget, result.damage, ctx.allies, ctx.enemies)
    }

    val combatantIdx = ctx.alliesIndexMap[attacker.id]
    if (combatantIdx != null && combatantIdx < ctx.allies.size) {
        ctx.allies[combatantIdx] = BattleCalculator.updateCombatantCooldowns(attacker, skill)
    }
    ctx.roundActions.add(BattleLogAction(
        type = "skill", attacker = attacker.name,
        attackerType = if (attacker.side == CombatantSide.ATTACKER) "attacker" else "defender",
        target = target.name, damage = result.damage, skillName = skill.name,
        isCrit = result.isCrit, isKill = newHp == 0
    ))
}

internal fun AISectAttackManager.executeAoeAttackAction(
    attacker: Combatant,
    targets: List<Combatant>,
    skill: CombatSkill,
    ctx: BattleWriteBackContext
) {
    val attackerType = if (attacker.side == CombatantSide.ATTACKER) "attacker" else "defender"
    for (target in targets) {
        if (target.isDead) continue
        applyAoeSingleTarget(
            attacker, target, skill,
            ctx
        )
    }
    // 攻击者冷却/MP 结算：每次技能执行一次（无论目标走必杀/闪避/正常分支）
    val combatantIdx = ctx.alliesIndexMap[attacker.id]
    if (combatantIdx != null && combatantIdx < ctx.allies.size) {
        ctx.allies[combatantIdx] = BattleCalculator.updateCombatantCooldowns(attacker, skill)
    }
}

/**
 * AOE 单目标伤害应用：必杀/闪避/正常三分支（含技能 debuff 附加）。
 * 从 executeAoeAttackAction 循环体提取。
 */

internal fun AISectAttackManager.applyAoeSingleTarget(
    attacker: Combatant,
    target: Combatant,
    skill: CombatSkill,
    ctx: BattleWriteBackContext
) {
    val result = BattleCalculator.calculateCombatantDamage(
        attacker, target, skill, rng = aisRng, enableInstantKill = true
    )
    val attackerType = if (attacker.side == CombatantSide.ATTACKER) "attacker" else "defender"
    if (result.isInstantKill) {
            val targetIdx = ctx.enemiesIndexMap[target.id]
            if (targetIdx != null && targetIdx < ctx.enemies.size) {
                ctx.enemies[targetIdx] = ctx.enemies[targetIdx].copy(hp = 0)
            }
            ctx.roundActions.add(BattleLogAction(
                type = "skill", attacker = attacker.name, attackerType = attackerType,
                target = target.name, damage = target.maxHp, skillName = skill.name,
                isKill = true, message = "${attacker.name} 以 ${skill.name} 境界压制斩杀 ${target.name}"
            ))
            return
        }

        if (result.isDodged) {
            ctx.roundActions.add(BattleLogAction(
                type = "skill", attacker = attacker.name, attackerType = attackerType,
                target = target.name, damage = 0, skillName = skill.name,
                message = "${target.name} 闪避了 ${attacker.name} 的 ${skill.name}"
            ))
            return
        }

        var newHp = target.hp
        val targetIdx = ctx.enemiesIndexMap[target.id]
        if (targetIdx != null && targetIdx < ctx.enemies.size) {
            // 护盾吸收 + 扣血（共享应用层）
            var updatedTarget = BattleDamageApplier.applyDamageToTarget(ctx.enemies[targetIdx], result.damage)
            newHp = updatedTarget.hp

            val localBuffType = skill.buffType
            if (localBuffType != null && skill.buffDuration > 0) {
                val debuff = CombatBuff(
                    type = localBuffType,
                    value = skill.buffValue,
                    remainingDuration = skill.buffDuration,
                    sourceRealm = attacker.realm,
                    sourceRealmLayer = attacker.realmLayer
                )
                updatedTarget = updatedTarget.copy(buffs = updatedTarget.buffs + debuff)
            }
            // 伤害链接 debuff（与主引擎一致）
            updatedTarget = applyLinkDebuff(attacker, updatedTarget, skill)

            ctx.enemies[targetIdx] = updatedTarget
            // 伤害分摊/链接
            applyShareAndLink(attacker, updatedTarget, result.damage, ctx.allies, ctx.enemies)
        }
        ctx.roundActions.add(BattleLogAction(
            type = "skill", attacker = attacker.name, attackerType = attackerType,
            target = target.name, damage = result.damage, skillName = skill.name,
            isCrit = result.isCrit, isKill = newHp == 0
        ))
}

internal fun AISectAttackManager.executeSupportAction(
    caster: Combatant,
    allies: List<Combatant>,
    skill: CombatSkill,
    alliesList: MutableList<Combatant>,
    alliesIndexMap: Map<String, Int>,
    roundActions: MutableList<BattleLogAction>
) {
    val supportAllies = resolveSupportTargets(caster, allies, skill)
    val supportResult = BattleCalculator.executeSupportSkill(caster, supportAllies, skill)
    applySupportHealing(supportResult, alliesList, alliesIndexMap, skill)
    applySupportTeamBuffs(supportResult, alliesList, alliesIndexMap)
    updateSupportCooldown(caster, alliesList, alliesIndexMap, skill)
    roundActions.add(buildSupportActionLog(caster, allies, supportResult, skill))
}

/** 支援目标解析（executeSupportAction 提取）：保留 aisRng 抽数位置 */

internal fun AISectAttackManager.resolveSupportTargets(
    caster: Combatant,
    allies: List<Combatant>,
    skill: CombatSkill
): List<Combatant> {
    // ally 作用域由本函数解析（BattleCalculator 对 "ally" 返回空列表）：
    // 仅存活且非施法者的盟友为合法目标
    if (skill.targetScope != "ally") return allies
    val valid = allies.filter { !it.isDead && it.id != caster.id }
    return if (valid.isNotEmpty()) listOf(valid[aisRng.nextInt(valid.size)]) else emptyList()
}

/** 支援治疗写回（executeSupportAction 提取） */
