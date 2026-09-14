@file:Suppress("MatchingDeclarationName")  // 文件持探索域 native 转发器对象 + 其 native 臂扩展（batch-13 聚合，S6 native 域同构）

package com.xianxia.sect.core.engine


import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.battle.BattleExecutionRouter
import com.xianxia.sect.core.engine.domain.battle.BattleLogData
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.BattleLogAction
import com.xianxia.sect.core.model.BattleLogEnemy
import com.xianxia.sect.core.model.BattleLogMember
import com.xianxia.sect.core.model.BattleLogRound
import com.xianxia.sect.core.model.BattleResult
import com.xianxia.sect.core.model.BattleType
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.WorldLevel
import com.xianxia.sect.core.nativebridge.ActionIds
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.params
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.nativebridge.StateSyncService
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import com.xianxia.sect.core.util.DomainLog


// GameEngineExplorationNativeOps.kt — 探索域 native 转发域（batch-13）
// ExplorationNativeForward 转发器 + native 臂战报公共重建助手 +
// attackWorldLevel 的 native 臂与遭遇战分支。主入口见
// GameEngineWorldBattleOps.kt / GameEngineScoutOps.kt /
// GameEngineGarrisonOps.kt；Kotlin 回退路径语义不变。

// ── Native 转发器（batch-13 探索域共用——世界关卡/侦察/分舵驻守）──────

/**
 * 探索域 native 转发器（AUTHORITATIVE 稳态写者归 C++——校验链/战斗执行/
 * 伤亡写回/槽位写；奖励生成/胜利事务/哀伤/gate/战报留 Kotlin，S5/S6 口径）。
 * 降级契约：flag 非 AUTHORITATIVE / 镜像服务缺失 / native 失败信封 → null，
 * 调用方回退 Kotlin 原实现（双实现并行契约）。
 */
internal object ExplorationNativeForward {

    /** 尝试经 C++ 执行探索域事务；成功返回 data，降级返回 null。 */
    @Suppress("ReturnCount")  // 多 return 为降级契约（flag 关/镜像不可用/失败信封逐级返回）
    internal fun tryForward(
        gameEngine: GameEngine,
        actionId: Int,
        paramsBuilder: JsonObjectBuilder.() -> Unit
    ): JsonElement? {
        if (!NativeEngineFlag.authoritative) return null
        // 防御性空安全：测试 mock（未 stub stateSyncServiceRef）返回 null——
        // 先赋可空局部再判空（handover findings 13，与 InventoryNativeForward 同守卫）
        val sync: StateSyncService? = gameEngine.stateSyncService
        if (sync == null) return null
        return GameEngineNativeOps.tryExecuteNative(
            stateSyncService = sync,
            actionId = actionId,
            paramsJson = params(paramsBuilder)
        )
    }

    /** 溢出邮件投递（战斗死亡袋物化/奖励入仓草稿 → Kotlin 同一投递通道）。 */
    internal fun deliverOverflowDrafts(gameEngine: GameEngine, data: JsonElement) {
        val drafts = (data as? JsonObject)?.get("overflowDrafts") as? JsonArray ?: return
        val system = gameEngine.inventorySystem
        for (draft in drafts) {
            (draft as? JsonObject)?.let { InventoryNativeForward.deliverDraft(system, it) }
        }
    }
}

// ── native 臂战报公共重建助手（世界关卡/侦察共用——展示通道非协议）──

/** native 臂战报成员展示段（name/realmName/portraitRes 经弟子表重建——C++ 战斗态不承载展示域） */
internal fun GameEngine.buildNativeBattleTeamMembers(
    combatLog: BattleLogData
): List<BattleLogMember> = combatLog.teamMembers.map { m ->
    val d = stateStore.discipleTables.assemble(m.id.toIntOrNull() ?: -1)
    BattleLogMember(
        id = m.id, name = d.name, realm = d.realm, realmName = d.realmName,
        hp = m.hp, maxHp = m.maxHp, mp = m.mp, maxMp = m.maxMp,
        isAlive = m.isAlive, portraitRes = d.portraitRes
    )
}

/** native 臂战报回合段（确定性字段直映射；message 为 Kotlin 确定性摘要口径）。 */
internal fun buildNativeBattleRounds(combatLog: BattleLogData): List<BattleLogRound> =
    combatLog.rounds.map { r -> BattleLogRound(roundNumber = r.roundNumber,
        actions = r.actions.map { a -> BattleLogAction(type = a.type, attacker = a.attacker,
            attackerType = a.attackerType, target = a.target, damage = a.damage,
                damageType = a.damageType, isCrit = a.isCrit, isKill = a.isKill,
                    message = a.message) }) }

// ── attackWorldLevel native 臂（主入口见 GameEngineWorldBattleOps.kt）──

/**
 * 遭遇战分支（attackWorldLevel 提取）：妖兽被 AI 宗门盯上 → 与 AI 打遭遇战
 * （aiBeastEncounterTargets 为 @Transient Kotlin 域字段——本分支整臂留 Kotlin）。
 * 目标存在返回 true（含无可用弟子的静默返回原语义）；不存在返回 false 继续。
 */
internal suspend fun GameEngine.resolveBeastEncounterIfAny(
    levelId: String,
    validIds: List<String>
): Boolean {
    val data = stateStore.gameDataSnapshot
    if (!data.aiBeastEncounterTargets.containsKey(levelId)) return false
    val allDisciples = stateStore.discipleTables.assembleAll()
    val combatDisciples = validIds.mapNotNull { id -> allDisciples.find { it.id == id && it.isAlive } }
    if (combatDisciples.isEmpty()) return true
    resolveBeastAttackFight(levelId, manualDefenders = combatDisciples)
    return true
}

/**
 * Native 臂（attackWorldLevel）：C++ 执行关卡战斗 + 伤亡写回，Kotlin 事务外
 * 收尾（哀伤 → 战报 → 胜利事务/奖励）。成功接管返回 true；降级返回 false。
 */
@Suppress("TooGenericExceptionCaught")  // 防御兜底: 哀伤处理异常源跨 IO/SDK 不可枚举, log-and-continue 不静默
internal suspend fun GameEngine.attackWorldLevelNative(
    level: WorldLevel,
    validIds: List<String>
): Boolean {
    // 战前结算（与 Kotlin 回退臂 buildWorldLevelBattle 同步序——镜像同步前置）
    stateStore.update {
        cultivationService.forceSettleDisciplesBeforeBattle(this, validIds)
    }
    val playerDamageModifier = if (stateStore.gameDataSnapshot.sectPolicies.strictTraining) {
        1.0 + GameConfig.PolicyConfig.STRICT_TRAINING_DAMAGE
    } else 1.0
    val native = ExplorationNativeForward.tryForward(this, ActionIds.EXPLORE_TX_ATTACK_WORLD_LEVEL) {
        put("levelId", level.id)
        putJsonArray("discipleIds") { validIds.forEach { add(it) } }
        put("playerDamageModifier", playerDamageModifier)
    } as? JsonObject ?: return false
    // 袋物化溢出草稿投递（W2-a/S6 同通道——邮件发送为平台效应留 Kotlin）
    ExplorationNativeForward.deliverOverflowDrafts(this, native)
    val victory = native["victory"]?.jsonPrimitive?.booleanOrNull == true
    val survivorIds = native.stringSet("survivorIds")
    val deadIds = native.stringSet("deadIds")
    val spiritStones = (native["rewards"] as? JsonObject)
        ?.get("spiritStones")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
    val battleObj = native["battle"] as? JsonObject
    // 伤亡处理（事务外 log-and-continue——袋已清空幂等 + wasAlive 双计防线，S6 口径）
    if (deadIds.isNotEmpty()) {
        try {
            combatService.processBattleCasualties(deadIds, emptyMap(), emptyMap(), isOutsideSect = true)
        } catch (e: CancellationException) {
            throw e // 取消穿透: 引擎重启/退出时中止剩余世界关卡结算, 不吞取消继续发奖
        } catch (e: Exception) {
            DomainLog.e("GameEngine",
                "native attackWorldLevel processBattleCasualties failed for deadIds=$deadIds, continuing", e)
        }
    }
    // 战报重建（展示通道：终态/rounds 来自 C++，展示字段经弟子表/关卡重建）
    val snapshot = stateStore.gameDataSnapshot
    val (log, teamMembers) = buildWorldLevelBattleLogFromNative(snapshot, level, battleObj, survivorIds)
    val updatedLogs = (stateStore.battleLogsSnapshot + log).takeLast(GameConfig.Logs.MAX_BATTLE_LOGS)
    if (victory) {
        // 胜利事务原函数（TOCTOU 重查 defeated + soulPowers/winAttr/defeated 原子块——C++ 不写 defeated）
        applyWorldLevelVictoryTransaction(level.id, survivorIds, updatedLogs)
        applyVictoryRewards(level, spiritStones, log, teamMembers)
    } else {
        applyWorldLevelDefeat(log, teamMembers, updatedLogs)
    }
    return true
}

/** 战报重建（native 臂——buildWorldLevelBattleLog 的 C++ 终态口径）。 */
private fun GameEngine.buildWorldLevelBattleLogFromNative(
    data: GameData,
    level: WorldLevel,
    battleObj: JsonObject?,
    survivorIds: Set<String>
): Pair<BattleLog, List<BattleLogMember>> {
    val combatLog = BattleExecutionRouter.rebuildBattleLogData(battleObj ?: JsonObject(emptyMap()))
    val victory = battleObj?.get("winner")?.jsonPrimitive?.contentOrNull == "TEAM"
    val teamMembers = buildNativeBattleTeamMembers(combatLog)
    // 妖兽展示字段：portraitRes = "beast_$typeIndex"（Kotlin createBeast 同口径；
    // 关卡 beastType 越界回退 type 0 与 GameConfig.Beast.getType 一致）
    val beastTypeIndex = if (level.isBeast) {
        (level.beastType ?: 0).let { if (it in GameConfig.Beast.TYPES.indices) it else 0 }
    } else 0
    val enemies = combatLog.enemies.map { b ->
        BattleLogEnemy(
            id = b.id, name = b.name, realm = b.realm,
            realmName = GameConfig.Realm.getName(b.realm),
            hp = b.hp, maxHp = b.maxHp, isAlive = b.isAlive,
            portraitRes = "beast_$beastTypeIndex"
        )
    }
    val rounds = buildNativeBattleRounds(combatLog)
    val defenderName = if (level.isBeast) level.beastName else level.guardianName
    val log = BattleLog(year = data.gameYear, month = data.gameMonth, type = BattleType.PVE, attackerName = "玩家队伍",
        defenderName = defenderName,
            result = if (victory) BattleResult.WIN else BattleResult.LOSE, teamMembers = teamMembers,
                enemies = enemies, rounds = rounds,
                    turns = battleObj?.get("turn")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                        // 原 Kotlin 口径逐字保留（survivorIds 为 id 集、按 member.name 判定）
                        teamCasualties = teamMembers.count { !survivorIds.contains(it.name) },
                            beastsDefeated = if (victory) level.count else combatLog.enemies.count { !it.isAlive },
                                details = if (victory) "击败了$defenderName" else "被${defenderName}击败")
    return log to teamMembers
}
