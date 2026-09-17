package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.nativebridge.GameCoreBridge
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 战斗执行路由（AI 兽战/任务完成/遭遇战生产接线）。
 *
 * AUTHORITATIVE 模式下把 [BattleSystem.executeBattle] 路由到 C++ 战斗引擎
 * （gamecore::battle::executeBattle，经 [GameCoreBridge.nativeBattleExecute]
 * 生产通道）；降级契约：
 * - flag 非 AUTHORITATIVE / native 未加载 / native 返回 error → null，
 *   调用方回退 Kotlin 原实现
 * - C++ 侧消费 BATTLE 分区（kBattle）——AUTHORITATIVE 下委托式 RNG 单一
 *   真相源（Kotlin NativeBackedRng 委托同一分区），序列天然一致
 * - 战斗日志重建：teamMembers/enemies 从战斗终态重建 + rounds 从 C++
 *   动作序列重建（确定性字段，message 为确定性摘要——原 message 由 JVM
 *   全局 Random 生成随机措辞，评估报告"diff 排除 message"同源决策）
 *
 * 适配范围：系统内部战斗（AI 兽战/任务完成）+ 遭遇战两阶段
 * （PvP/PvE，战报回放由 rounds 重建满足——C++ 动作序列为确定性字段，
 * 仅 message 摘要口径与 Kotlin 随机措辞不同，diff 对拍同源排除）
 * + 探索/巡逻生产（R4.3：妖兽防守战/巡逻楼 PvE/冲突战 PvP+PvE）。
 * 洞府探索（CaveExplorationSystem）不在本路由范围：会话管理属平台域，
 * 且其生成随机为非分区随机域（System.nanoTime 种子，不进镜像协议）。
 */
internal object BattleExecutionRouter {

    private val json = Json

    /** 尝试经 C++ 执行战斗；成功返回重建的 [BattleSystemResult]，否则 null（调用方回退 Kotlin）。 */
    @Suppress("ReturnCount")  // 多 return 为降级契约（flag 关/native 不可用/失败信封逐级返回）
    internal fun tryExecuteNative(
        battle: Battle,
        playerDamageModifier: Double = 1.0
    ): BattleSystemResult? {
        if (!NativeEngineFlag.authoritative) return null
        if (!GameCoreBridge.isLoaded) return null

        val op = buildJsonObject {
            putJsonArray("team") { battle.team.forEach { add(BattleJsonCodec.combatantJson(it)) } }
            putJsonArray("beasts") { battle.beasts.forEach { add(BattleJsonCodec.combatantJson(it)) } }
            put("playerDamageModifier", playerDamageModifier)
            put("maxTurns", battle.maxTurns)
            put("timeoutMs", -1L)  // 生产不检查超时（调用方保证轻量战斗）
        }
        val out = json.parseToJsonElement(
            GameCoreBridge.nativeBattleExecute(op.toString().encodeToByteArray()).decodeToString()
        ).jsonObject
        if (out.containsKey("error")) return null

        return rebuildResult(out, battle)
    }

    /**
     * C++ 秘境会话战斗信封 → 战报数据（展示通道非协议）。
     * 与 [rebuildResult] 同重建口径（确定性摘要 message / 终态成员 / rounds），
     * 但无 original Battle（战斗全程在 C++ 执行）。
     */
    internal fun rebuildBattleLogData(out: JsonObject): BattleLogData {
        val finalTeam = (out["team"] as? JsonArray)
            ?.map { BattleJsonCodec.combatantFromJson(it.jsonObject) } ?: emptyList()
        val finalBeasts = (out["beasts"] as? JsonArray)
            ?.map { BattleJsonCodec.combatantFromJson(it.jsonObject) } ?: emptyList()
        val teamMembers = finalTeam.map { c ->
            BattleMemberData(
                id = c.id, name = c.name, realm = c.realm,
                realmName = c.realmName, hp = c.hp, maxHp = c.maxHp,
                mp = c.mp, maxMp = c.maxMp, isAlive = !c.isDead,
                portraitRes = c.portraitRes
            )
        }
        val enemies = finalBeasts.map { c ->
            BattleEnemyData(
                id = c.id, name = c.name, realm = c.realm,
                realmName = c.realmName, realmLayer = c.realmLayer,
                hp = c.hp, maxHp = c.maxHp, isAlive = !c.isDead,
                portraitRes = c.portraitRes
            )
        }
        val rounds = (out["rounds"] as? JsonArray)?.mapNotNull { roundJson ->
            val r = roundJson.jsonObject
            val actions = (r["actions"] as? JsonArray)?.mapNotNull { actionJson ->
                rebuildAction(actionJson.jsonObject)
            } ?: emptyList()
            BattleRoundData(
                roundNumber = r["roundNumber"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                actions = actions
            )
        } ?: emptyList()
        return BattleLogData(rounds = rounds, teamMembers = teamMembers, enemies = enemies)
    }

    /** C++ 终态 JSON → Kotlin BattleSystemResult（battle/victory/rewards/log/turnCount）。 */
    private fun rebuildResult(out: JsonObject, original: Battle): BattleSystemResult {
        val winner = when (out["winner"]?.jsonPrimitive?.content) {
            "TEAM" -> BattleWinner.TEAM
            "BEASTS" -> BattleWinner.BEASTS
            else -> BattleWinner.DRAW
        }
        val turn = out["turn"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val rewards = (out["rewards"] as? kotlinx.serialization.json.JsonObject)
            ?.mapValues { it.value.jsonPrimitive.content.toIntOrNull() ?: 0 }
            ?: emptyMap()

        val finalTeam = (out["team"] as? JsonArray)
            ?.map { BattleJsonCodec.combatantFromJson(it.jsonObject) } ?: emptyList()
        val finalBeasts = (out["beasts"] as? JsonArray)
            ?.map { BattleJsonCodec.combatantFromJson(it.jsonObject) } ?: emptyList()

        val finalBattle = original.copy(
            team = finalTeam,
            beasts = finalBeasts,
            turn = turn,
            isFinished = true,
            winner = winner
        )

        // 日志重建：teamMembers/enemies 终态 + rounds 从 C++ 动作序列重建
        val teamMembers = finalTeam.map { c ->
            BattleMemberData(
                id = c.id, name = c.name, realm = c.realm,
                realmName = c.realmName, hp = c.hp, maxHp = c.maxHp,
                mp = c.mp, maxMp = c.maxMp, isAlive = !c.isDead,
                portraitRes = c.portraitRes
            )
        }
        val enemies = finalBeasts.map { c ->
            BattleEnemyData(
                id = c.id, name = c.name, realm = c.realm,
                realmName = c.realmName, realmLayer = c.realmLayer,
                hp = c.hp, maxHp = c.maxHp, isAlive = !c.isDead,
                portraitRes = c.portraitRes
            )
        }
        val rounds = (out["rounds"] as? JsonArray)?.mapNotNull { roundJson ->
            val r = roundJson.jsonObject
            val actions = (r["actions"] as? JsonArray)?.mapNotNull { actionJson ->
                rebuildAction(actionJson.jsonObject)
            } ?: emptyList()
            BattleRoundData(
                roundNumber = r["roundNumber"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                actions = actions
            )
        } ?: emptyList()

        return BattleSystemResult(
            battle = finalBattle,
            victory = winner == BattleWinner.TEAM,
            rewards = rewards,
            log = BattleLogData(rounds = rounds, teamMembers = teamMembers, enemies = enemies),
            timedOut = out["timedOut"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            durationMs = 0,
            turnCount = turn
        )
    }

    /** C++ 动作记录 → Kotlin BattleActionData（message 为确定性摘要）。 */
    private fun rebuildAction(o: JsonObject): BattleActionData? {
        val type = o["type"]?.jsonPrimitive?.content ?: return null
        val attacker = o["attacker"]?.jsonPrimitive?.content ?: ""
        val target = o["target"]?.jsonPrimitive?.content ?: ""
        val damage = o["damage"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val damageType = o["damageType"]?.jsonPrimitive?.content ?: ""
        val isCrit = o["isCrit"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val isKill = o["isKill"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val isInstantKill = o["isInstantKill"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val skillName = o["skillName"]?.jsonPrimitive?.content
        val message = buildSummaryMessage(type, attacker, target, damage, damageType, isCrit)
        return BattleActionData(
            type = type,
            attacker = attacker,
            attackerType = o["attackerType"]?.jsonPrimitive?.content ?: "",
            target = target,
            damage = damage,
            damageType = damageType,
            isCrit = isCrit,
            isKill = isKill,
            isInstantKill = isInstantKill,
            message = message,
            skillName = skillName
        )
    }

    /** 确定性摘要（原 BattleDescriptionGenerator 为 JVM Random 随机措辞，不进入 C++）。 */
    private fun buildSummaryMessage(
        type: String,
        attacker: String,
        target: String,
        damage: Int,
        damageType: String,
        isCrit: Boolean
    ): String = when (type) {
        "support" -> "$attacker 施展了支援技能"
        "control" -> "$attacker 受到控制效果，无法行动"
        "dot" -> "$target 受到 $damage 点持续伤害"
        else -> {
            val crit = if (isCrit) "，暴击" else ""
            "$attacker 对 $target 造成 $damage 点$damageType 伤害$crit"
        }
    }
}
