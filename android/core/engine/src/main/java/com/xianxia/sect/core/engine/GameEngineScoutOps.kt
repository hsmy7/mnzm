package com.xianxia.sect.core.engine


import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.BattleLogAction
import com.xianxia.sect.core.model.BattleLogEnemy
import com.xianxia.sect.core.model.BattleLogMember
import com.xianxia.sect.core.model.BattleLogRound
import com.xianxia.sect.core.model.BattleResult
import com.xianxia.sect.core.model.BattleType
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.SectDetail
import com.xianxia.sect.core.model.SectScoutInfo
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.state.BattleResultUIData
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.domain.battle.BattleSystemResult
import com.xianxia.sect.core.engine.domain.battle.AISectAttackManager
import com.xianxia.sect.core.engine.domain.battle.Battle
import com.xianxia.sect.core.engine.domain.battle.BattleExecutionRouter
import com.xianxia.sect.core.engine.domain.battle.Combatant
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.disciple.battleWritebackMaxHpMp
import com.xianxia.sect.core.nativebridge.ActionIds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add


// ── Scout sect ──────────────────────────────────────────────────────

suspend fun GameEngine.scoutSect(sectId: String, memberIds: List<String>) {
    return engineContextDispatcher.withEngineContext {
        ensureHeavyDataLoaded()
        val data = stateStore.gameDataSnapshot
        val targetSect = data.worldMapSects.find { it.id == sectId } ?: return@withEngineContext
        // ── Native 臂（AUTHORITATIVE）：AI 守卫选取/战斗执行（BATTLE 分区同序）/
        // 伤亡写回经 C++；战报/胜利情报留 Kotlin（S5/S6 口径）。降级 false →
        // Kotlin 原路径（双实现并行契约）
        if (scoutSectNative(sectId, data, targetSect, memberIds)) {
            // w3-13 通道关闭配套（§2.80）：胜利情报写面（sectDetails/scoutInfo 本批
            // 转关闭）发生后全量重建 native 基线回导 C++
            rebaselineNativeMirror("侦查宗门")
            return@withEngineContext
        }
        if (memberIds.isNotEmpty()) {
            stateStore.update {
                cultivationService.forceSettleDisciplesBeforeBattle(
                    this, memberIds
                )
            }
        }
        val allDisciples = stateStore.discipleTables.assembleAll()
        val combatDisciples = memberIds.mapNotNull { id -> allDisciples.find { it.id == id && it.isAlive } }
        if (combatDisciples.isEmpty()) return@withEngineContext
        val aiDefenders = (data.aiSectDisciples[sectId] ?: emptyList()).filter { it.isAlive && it.realm in 7..9 }
            .take(8)
        val equipmentMap = stateStore.equipmentInstancesSnapshot.associateBy { it.id }
        val manualMap = stateStore.manualInstancesSnapshot.associateBy { it.id }
        val allProficiencies = data.manualProficiencies.mapValues { (_, list) -> list.associateBy { it.manualId } }
        // 玩家 Combatant 统一走 BattleSystem.convertDiscipleToCombatant（实例表语义，
        // 与 attackSect/PlayerDefenseProcessor 同一入口）：修复原 buildScoutPlayerCombatants
        // 未传 realmLayer（默认 0，小层境界压制判定失效）、未带体质/词条因子与武器名的缺陷
        val playerCombatants = combatDisciples.map { d ->
            battleSystem.convertDiscipleToCombatant(
                d, equipmentMap, manualMap, allProficiencies,
                CombatantSide.DEFENDER,
                bloodRefinementPct = data.bloodRefinementPctTotals[d.id]
            )
        }
        val aiCombatants = aiDefenders.map { d -> AISectAttackManager.convertToCombatant(d, CombatantSide.ATTACKER) }
        val battle = Battle(team = playerCombatants, beasts = aiCombatants, turn = 0, isFinished = false, winner = null,
            maxTurns = Int.MAX_VALUE)
        // 严苛训练政策：玩家弟子伤害+5%（参数透传）
        val playerDamageModifier = if (data.sectPolicies.strictTraining) {
            1.0 + GameConfig.PolicyConfig.STRICT_TRAINING_DAMAGE
        } else 1.0
        val result = battleSystem.executeBattle(battle, playerDamageModifier)
        val hpMap = result.battle.team.associate { it.id to (it.hp to it.mp) }
        val survivorIds = result.battle.team.filter { !it.isDead }.map { it.id }.toSet()
        applyScoutCasualties(hpMap, survivorIds)
        val scoutDiscipleIds = combatDisciples.map { it.id }.toSet()
        val scoutDeadIds = stateStore.discipleTables.ids.filter { it.toString() in scoutDiscipleIds && stateStore
            .discipleTables.isAlive[it] == 0 }.map { it.toString() }.toSet()
        if (scoutDeadIds.isNotEmpty()) combatService.processBattleCasualties(scoutDeadIds, emptyMap(), emptyMap(),
            isOutsideSect = true)
        val (log, teamMembers) = buildScoutBattleLog(data, targetSect, result, aiCombatants, survivorIds)
        val victory = result.victory
        stateStore.setPendingBattleResult(BattleResultUIData(battleLogId = log.id, victory = victory,
            teamMembers = teamMembers, rewards = emptyList()))
        if (victory) {
            applyScoutVictoryInfo(sectId, data, targetSect)
        }
        // w3-13 通道关闭配套（§2.80）：回退臂写面（伤亡/战报/胜利情报）发生后全量
        // 重建 native 基线（native 未就绪时静默跳过）
        rebaselineNativeMirror("侦查宗门")
    }
}

/**
 * Native 臂（scoutSect）：C++ 选取 AI 守卫（alive ∧ realm 7..9 取前 8）+ PvP
 * 战斗（BATTLE 分区同序）+ 伤亡写回；Kotlin 事务外收尾（哀伤 → 战报落库 →
 * 胜利情报）。成功接管返回 true；降级返回 false。
 */
private suspend fun GameEngine.scoutSectNative(
    sectId: String,
    data: GameData,
    targetSect: WorldSect,
    memberIds: List<String>
): Boolean {
    // 战前结算（与 Kotlin 回退臂同步序——镜像同步前置）
    if (memberIds.isNotEmpty()) {
        stateStore.update {
            cultivationService.forceSettleDisciplesBeforeBattle(this, memberIds)
        }
    }
    val playerDamageModifier = if (data.sectPolicies.strictTraining) {
        1.0 + GameConfig.PolicyConfig.STRICT_TRAINING_DAMAGE
    } else 1.0
    val native = ExplorationNativeForward.tryForward(this, ActionIds.EXPLORE_TX_SCOUT_SECT) {
        put("sectId", sectId)
        putJsonArray("memberIds") { memberIds.forEach { add(it) } }
        put("playerDamageModifier", playerDamageModifier)
    } as? JsonObject ?: return false
    ExplorationNativeForward.deliverOverflowDrafts(this, native)
    val victory = native["victory"]?.jsonPrimitive?.booleanOrNull == true
    val survivorIds = native.stringSet("survivorIds")
    val deadIds = native.stringSet("deadIds")
    val battleObj = native["battle"] as? JsonObject
    if (deadIds.isNotEmpty()) combatService.processBattleCasualties(deadIds, emptyMap(), emptyMap(),
        isOutsideSect = true)
    val (log, teamMembers) = buildScoutBattleLogFromNative(data, targetSect, battleObj,
        native["defenderViews"], survivorIds)
    stateStore.setPendingBattleResult(BattleResultUIData(battleLogId = log.id, victory = victory,
        teamMembers = teamMembers, rewards = emptyList()))
    if (victory) {
        applyScoutVictoryInfo(sectId, data, targetSect)
    }
    return true
}

/** 战报重建 + 落库（native 臂——buildScoutBattleLog 的 C++ 终态口径）。 */
private fun GameEngine.buildScoutBattleLogFromNative(
    data: GameData,
    targetSect: WorldSect,
    battleObj: JsonObject?,
    defenderViews: JsonElement?,
    survivorIds: Set<String>
): Pair<BattleLog, List<BattleLogMember>> {
    val combatLog = BattleExecutionRouter.rebuildBattleLogData(battleObj ?: JsonObject(emptyMap()))
    val victory = battleObj?.get("winner")?.jsonPrimitive?.contentOrNull == "TEAM"
    val teamMembers = buildNativeBattleTeamMembers(combatLog)
    // AI 守卫展示段（战前 realm/portrait/maxHp——C++ defenderViews；终态 hp/存活
    // 取 C++ 战斗终态；显示名 = "宗门名弟子"，Kotlin 原口径）
    val views = (defenderViews as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    val postBattleBeasts = combatLog.enemies.associateBy { it.id }
    val enemies = views.map { v ->
        val id = v["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val postState = postBattleBeasts[id]
        BattleLogEnemy(
            id = id,
            name = "${targetSect.name}弟子",
            realm = v["realm"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            realmName = v["realmName"]?.jsonPrimitive?.contentOrNull ?: "",
            hp = postState?.hp ?: 0,
            maxHp = v["maxHp"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            isAlive = postState?.isAlive == true,
            portraitRes = v["portraitRes"]?.jsonPrimitive?.contentOrNull ?: ""
        )
    }
    val rounds = buildNativeBattleRounds(combatLog)
    val log = BattleLog(year = data.gameYear, month = data.gameMonth, type = BattleType.SCOUT, attackerName = "探查队伍",
        defenderName = targetSect.name, result = if (victory) BattleResult.WIN else BattleResult.LOSE,
            teamMembers = teamMembers, enemies = enemies, rounds = rounds,
                turns = battleObj?.get("turn")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                    teamCasualties = teamMembers.size - survivorIds.size,
                        beastsDefeated = if (victory) views.count { postBattleBeasts[it["id"]
                            ?.jsonPrimitive?.contentOrNull]?.isAlive == false } else 0,
                            details = if (victory) "成功探查了${targetSect.name}" else "探查${targetSect.name}失败")
    val existingLogs = stateStore.battleLogsSnapshot
    val updatedLogs = (existingLogs + log).takeLast(GameConfig.Logs.MAX_BATTLE_LOGS)
    stateStore.update { battleLogs = updatedLogs }
    return log to teamMembers
}

/** 探查战后 HP/MP 回写 + 死亡标记（scoutSect 提取） */
private fun GameEngine.applyScoutCasualties(hpMap: Map<String, Pair<Int, Int>>, survivorIds: Set<String>) {
    stateStore.update {
        for (id in discipleTables.ids) {
            val idStr = id.toString()
            val (hp, mp) = hpMap[idStr] ?: continue
            if (idStr !in survivorIds) {
                // 死亡统一入口——袋物品物化回仓库（玩家保留）+ 清袋 + 标记死亡
                inventorySystem.materializeDiscipleBagAndMarkDead(this, id, gameData.gameYear, "scout")
            } else {
                val (finalMaxHp, finalMaxMp) = DiscipleStatCalculator.battleWritebackMaxHpMp(
                    this, discipleTables.assemble(id)
                )
                discipleTables.currentHps[id] = hp.coerceIn(0, finalMaxHp)
                discipleTables.currentMps[id] = mp.coerceIn(0, finalMaxMp)
            }
        }
    }
}

/** 探查战报组装 + 落库（scoutSect 提取） */
private fun GameEngine.buildScoutBattleLog(
    data: GameData,
    targetSect: WorldSect,
    result: BattleSystemResult,
    aiCombatants: List<Combatant>,
    survivorIds: Set<String>
): Pair<BattleLog, List<BattleLogMember>> {
    val teamMembers = result.battle.team.map { m -> BattleLogMember(id = m.id, name = m.name, realm = m.realm,
        realmName = m.realmName, hp = m.hp, maxHp = m.maxHp, mp = m.mp, maxMp = m.maxMp, isAlive = !m.isDead,
            portraitRes = m.portraitRes) }
    val postBattleBeasts = result.battle.beasts.associateBy { it.id }
    val enemies = aiCombatants.map { b ->
        val postState = postBattleBeasts[b.id]
        BattleLogEnemy(
            id = b.id,
            name = "${targetSect.name}弟子",
            realm = b.realm,
            realmName = b.realmName,
            hp = postState?.hp ?: 0,
            maxHp = b.maxHp,
            isAlive = postState?.isDead != true,
            portraitRes = b.portraitRes
        )
    }
    val rounds = result.log.rounds.map { r -> BattleLogRound(roundNumber = r.roundNumber,
        actions = r.actions.map { a -> BattleLogAction(type = a.type, attacker = a.attacker,
            attackerType = a.attackerType, target = a.target, damage = a.damage, damageType = a.damageType,
                isCrit = a.isCrit, isKill = a.isKill, message = a.message) }) }
    val victory = result.victory
    val log = BattleLog(year = data.gameYear, month = data.gameMonth, type = BattleType.SCOUT, attackerName = "探查队伍",
        defenderName = targetSect.name, result = if (victory) BattleResult.WIN else BattleResult.LOSE,
            teamMembers = teamMembers, enemies = enemies, rounds = rounds, turns = result.turnCount,
                teamCasualties = teamMembers.size - survivorIds.size,
                    beastsDefeated = if (victory) aiCombatants.count { result.battle.beasts.any { b -> b.id == it
                        .id && b.isDead } } else 0,
                            details = if (victory) "成功探查了${targetSect.name}" else "探查${targetSect.name}失败")
    val existingLogs = stateStore.battleLogsSnapshot
    val updatedLogs = (existingLogs + log).takeLast(GameConfig.Logs.MAX_BATTLE_LOGS)
    stateStore.update { battleLogs = updatedLogs }
    return log to teamMembers
}

/** 探查胜利情报写入（scoutSect 提取） */
private fun GameEngine.applyScoutVictoryInfo(sectId: String, data: GameData, targetSect: WorldSect) {
    val allSectDisciples = data.aiSectDisciples[sectId] ?: emptyList()
    val aliveSectDisciples = allSectDisciples.filter { it.isAlive }
    val realmDistribution = aliveSectDisciples.groupingBy { it.realm }.eachCount()
    val scoutInfo = SectScoutInfo(sectId = sectId, sectName = targetSect.name, scoutYear = data.gameYear,
        scoutMonth = data.gameMonth, discipleCount = aliveSectDisciples.size,
            maxRealm = aliveSectDisciples.minOfOrNull { it.realm } ?: 9, disciples = realmDistribution, isKnown = true,
                expiryYear = data.gameYear + 1, expiryMonth = data.gameMonth)
    updateGameDataSync { val existingDetail = it.sectDetails[sectId] ?: SectDetail(sectId = sectId); it
        .copy(sectDetails = it.sectDetails + (sectId to existingDetail.copy(scoutInfo = scoutInfo))) }
}
