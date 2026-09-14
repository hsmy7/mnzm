package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.domain.FavorDomain
import com.xianxia.sect.core.engine.SectCombatPowerCalculator
import com.xianxia.sect.core.engine.domain.diplomacy.IntelligentSectDecisionEngine
import com.xianxia.sect.core.model.AISectPersonality
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.SectBattleType
import com.xianxia.sect.core.model.SectRelationLevel
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.nativebridge.GameCoreBridge
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.engine.domain.battle.AISectAttackManager.PlayerAttackDecision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
// ── AISectAttackManager 决策/编队域（自 AISectAttackManager 拆出，行为零变更） ──

/**
 * 决定AI宗门是否应攻击玩家。
 *
 * 保留的二进制硬约束（按序检查）：
 * 1. 保护期
 * 2. 附庸关系（主宗不攻击附庸）
 * 3. 已有活跃预警
 * 4. 攻击冷却期
 * 5. 最低弟子数
 * 6. 同联盟不攻击
 *
 * 综合评估委托 [IntelligentSectDecisionEngine] 的四因素加权模型；
 * 守军战力面 = aiDisciplesMap[playerSectId]（世界生成不含玩家宗门条目 →
 * 恒空 → 恒 Skip；C++ AUTHORITATIVE 面已改读玩家弟子权威存储——
 * 差异登记于 sect_attack_decision.h playerDefenders，对拍快照经
 * 双侧同池注入保持逐位一致）。
 */
fun AISectAttackManager.decidePlayerAttack(gameData: GameData, rngManager: GameRngManager): PlayerAttackDecision {
    // AUTHORITATIVE 下经 C++ 决策（sect_attack_decision.h decidePlayerAttack——
    // 消费 BATTLE 分区；原生失败/未加载回退 Kotlin）
    tryNativeDecidePlayerAttack()?.let { return it }

    if (gameData.isPlayerProtected) return PlayerAttackDecision.Skip

    val playerSect = gameData.worldMapSects.find { it.isPlayerSect }
        ?: return PlayerAttackDecision.Skip
    val playerSectId = playerSect.id
    val nowMonth = gameData.gameYear * 12 + gameData.gameMonth

    val aiDisciplesMap = gameData.aiSectDisciples

    for (attacker in gameData.worldMapSects.filter { !it.isPlayerSect }) {
        val aliveAttackers = passesAttackerGates(gameData, attacker, nowMonth, aiDisciplesMap)
        val powerRatio = aliveAttackers?.let { computePowerRatio(it, aiDisciplesMap, playerSectId) }
        if (aliveAttackers == null || powerRatio == null) continue
        val attackChance = computeAttackChance(gameData, attacker, playerSectId, powerRatio)

        // W4-C 随机源收敛：BATTLE 分区抽取经形参传入（原顶层 aisRng 已摘除）
        if (rngManager.getRng(RngPartition.BATTLE).nextDouble() < attackChance) {
            return PlayerAttackDecision.GenerateWarning(
                attackerSectId = attacker.id,
                attackerSectName = attacker.name
            )
        }
    }

    return PlayerAttackDecision.Skip
}

@Suppress("TooGenericExceptionCaught") // 防御兜底: native 降级路径异常源跨 JNI/IO/SDK 不可枚举, 降级继续+日志留痕
internal fun AISectAttackManager.tryNativeDecidePlayerAttack(): PlayerAttackDecision? {
    if (!NativeEngineFlag.authoritative) return null
    if (!GameCoreBridge.isLoaded) return null
    return try {
        val out = Json.parseToJsonElement(
            GameCoreBridge.nativeDecidePlayerAttack().decodeToString()
        ).jsonObject
        if (out.containsKey("error")) return null
        if (out["type"]?.jsonPrimitive?.content == "GENERATE_WARNING") {
            PlayerAttackDecision.GenerateWarning(
                attackerSectId = out["attackerSectId"]?.jsonPrimitive?.content ?: "",
                attackerSectName = out["attackerSectName"]?.jsonPrimitive?.content ?: ""
            )
        } else {
            PlayerAttackDecision.Skip
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainLog.w(TAG, "nativeDecidePlayerAttack degraded to Kotlin: $e")
        null
    }
}

/** 攻击前置六道闸（decidePlayerAttack 提取）：附庸/预警/冷却/弟子数/联盟；未通过返回 null */
internal fun AISectAttackManager.passesAttackerGates(
    gameData: GameData,
    attacker: WorldSect,
    nowMonth: Int,
    aiDisciplesMap: Map<String, List<Disciple>>
): List<Disciple>? {
    // ---- 最低弟子数 ----
    val aliveAttackers = (aiDisciplesMap[attacker.id] ?: emptyList()).filter { it.isAlive }
    val playerSect = gameData.worldMapSects.find { it.isPlayerSect }
    val cooldownUntil = gameData.sectAttackCooldowns[attacker.id]

    // ---- 六道闸：附庸 / 活跃预警 / 冷却期 / 最低弟子数 / 联盟 ----
    val passes = gameData.suzerainSectId != attacker.id &&
        gameData.activeAttackWarnings.none { it.attackerSectId == attacker.id } &&
        (cooldownUntil == null || nowMonth >= cooldownUntil) &&
        aliveAttackers.size >= MIN_DISCIPLES_FOR_ATTACK &&
        (attacker.allianceId.isEmpty() || playerSect?.allianceId != attacker.allianceId)
    return if (passes) aliveAttackers else null
}

/** 战力比计算（decidePlayerAttack 提取）；防守战力 <=0 返回 null 跳过 */
internal fun AISectAttackManager.computePowerRatio(
    aliveAttackers: List<Disciple>,
    aiDisciplesMap: Map<String, List<Disciple>>,
    playerSectId: String
): Double? {
    // ---- 战力计算 ----
    val attackerPower = SectCombatPowerCalculator.calculateSectPower(aliveAttackers)
    val defenderDisciples = aiDisciplesMap[playerSectId] ?: emptyList()
    val defenderPower = SectCombatPowerCalculator.calculateSectPower(
        defenderDisciples.filter { it.isAlive }
    )
    if (defenderPower <= 0) return null
    return attackerPower.toDouble() / defenderPower.toDouble()
}

/** 多因素智能综合评估（decidePlayerAttack 提取）：好感/战绩统计 + 个性修正 */
internal fun AISectAttackManager.computeAttackChance(
    gameData: GameData,
    attacker: WorldSect,
    playerSectId: String,
    powerRatio: Double
): Double {
    // ---- 个性参数 ----
    val personality = gameData.aiSectPersonalities[attacker.id]
        ?: AISectPersonality.BALANCED

    // ---- 多因素智能综合评估 ----
    val favor = FavorDomain.findFavor(gameData.sectRelations, attacker.id, playerSectId)
    val favorLevel = SectRelationLevel.fromFavor(favor)
    val recentRecords = gameData.sectBattleRecords.filter {
        it.year >= gameData.gameYear - 3
    }
    val conquestCount = recentRecords.count { it.type == SectBattleType.CONQUEST }
    val lostSectCount = recentRecords.count { it.type == SectBattleType.LOST_SECT }
    val battleWinCount = recentRecords.count { it.type == SectBattleType.BATTLE_WIN }
    val battleLossCount = recentRecords.count { it.type == SectBattleType.BATTLE_LOSS }

    return IntelligentSectDecisionEngine.calculateChance(
        profile = IntelligentSectDecisionEngine.ATTACK_PROFILE,
        powerRatio = powerRatio,
        conquestCount = conquestCount,
        lostSectCount = lostSectCount,
        battleWinCount = battleWinCount,
        battleLossCount = battleLossCount,
        favorLevel = favorLevel,
        personality = personality
    )
}

/**
 * 创建进攻队伍 — 按境界排序，选取战斗力最低的 N 个弟子。
 */
fun AISectAttackManager.createAttackTeam(
    attackerDisciples: List<Disciple>,
    existingBusyIds: Set<String> = emptySet()
): List<Disciple> {
    val minCount = GameConfig.AI.MIN_DISCIPLES_FOR_ATTACK
    val teamSize = GameConfig.AI.TEAM_SIZE
    val availableDisciples = attackerDisciples
        .filter { it.isAlive && it.id !in existingBusyIds }
        .sortedBy { it.realm }
    if (availableDisciples.size < minCount) return emptyList()
    return availableDisciples.take(teamSize)
}

/**
 * 创建防守队伍 — 按境界排序，选取最强的 N 个弟子。
 */
fun AISectAttackManager.createDefenseTeam(defenderDisciples: List<Disciple>): List<Disciple> {
    val teamSize = GameConfig.AI.TEAM_SIZE
    return defenderDisciples.filter { it.isAlive }.sortedBy { it.realm }.take(teamSize)
}

/**
 * 获取宗门驻军弟子列表。
 */
fun AISectAttackManager.getGarrisonDisciples(sect: WorldSect, allDisciples: List<Disciple>): List<Disciple> {
    return sect.garrisonSlots
        .filter { it.discipleId.isNotEmpty() }
        .mapNotNull { slot -> allDisciples.find { it.id == slot.discipleId } }
        .filter { it.isAlive }
}
