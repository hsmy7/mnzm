package com.xianxia.sect.core.domain.battle

import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.domain.FavorDomain
import com.xianxia.sect.core.engine.domain.battle.Battle
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.BattleSystemResult
import com.xianxia.sect.core.engine.domain.battle.Combatant
import com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager
import com.xianxia.sect.core.exploration.DiscipleDeathHandler
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.BattleLogAction
import com.xianxia.sect.core.model.BattleLogEnemy
import com.xianxia.sect.core.model.BattleLogMember
import com.xianxia.sect.core.model.BattleLogRound
import com.xianxia.sect.core.model.BattleResult
import com.xianxia.sect.core.model.BattleType
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.WorldLevel
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.DomainLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 遭遇战服务。
 *
 * 处理宗门弟子在探索过程中与敌对宗门发生遭遇并随后与妖兽战斗的两阶段流程。
 *
 * Phase 1 — 遭遇战（PvP）：两队弟子对战，胜者进入 Phase 2。
 * Phase 2 — 胜者 vs 妖兽（PvE）：胜者的幸存弟子与指定妖兽战斗。
 *
 * 所有修改在调用方提供的 [stateStore.update] 事务内完成，函数本身非挂起。
 */
@Singleton
class EncounterBattleService @Inject constructor(
    private val stateStore: GameStateStore,
    private val battleSystem: BattleSystem,
    private val deathHandler: DiscipleDeathHandler
) {

    companion object {
        private const val TAG = "EncounterBattleService"
        private const val ENCOUNTER_FAVOR_DELTA = -3
    }

    /**
     * 执行完整的遭遇战。
     *
     * @param state     当前可变游戏状态（调用方确保在 [stateStore.update] 事务内）
     * @param attackerA 遭遇战攻击方 A
     * @param attackerB 遭遇战攻击方 B
     * @param beast     目标妖兽关卡
     * @param year      当前游戏年份
     * @param month     当前游戏月份
     */
    /**
     * 执行遭遇战。
     *
     * @param state        游戏可变状态
     * @param attackerA    攻方A
     * @param attackerB    攻方B
     * @param beast        目标妖兽
     * @param year         当前年份
     * @param month        当前月份
     * @param favorDedup   [可选] 好感度月度去重 key（"aiSectId_absMonth"），
     *                     已存在时跳过好感度扣减，防止同 AI 每月多次 -3
     */
    fun encounter(
        state: MutableGameState,
        attackerA: EncounterAttacker,
        attackerB: EncounterAttacker,
        beast: WorldLevel,
        year: Int,
        month: Int,
        favorDedup: MutableSet<String>? = null
    ) {
        require(attackerA.sectId != attackerB.sectId) {
            "遭遇战双方 sectId 相同: ${attackerA.sectId}"
        }
        val isPlayerVsAI = attackerA.isPlayer != attackerB.isPlayer
        DomainLog.i(TAG, "遭遇战: ${attackerA.sectName} vs ${attackerB.sectName}, " +
            "playerVsAI=$isPlayerVsAI, beast=${beast.beastName}")

        // ── 按宗门类型构建装备/功法/熟练度映射 ──
        // 玩家方使用真实装备/功法；AI 方每次参战前按境界随机生成模拟装备/功法
        val preparedSides = mapOf(
            attackerA.sectId to prepareSide(attackerA, state),
            attackerB.sectId to prepareSide(attackerB, state)
        )

        // ═══════════════════════════════════════════════════════
        // Phase 1 — 遭遇战（PvP）
        // ═══════════════════════════════════════════════════════

        val sideA = preparedSides.getValue(attackerA.sectId)
        val sideB = preparedSides.getValue(attackerB.sectId)
        val teamACombatants = sideA.disciples.map { disciple ->
            battleSystem.convertDiscipleToCombatant(
                disciple = disciple,
                equipmentMap = sideA.equipmentMap,
                manualMap = sideA.manualMap,
                manualProficiencies = sideA.proficiencies,
                side = CombatantSide.DEFENDER,
                fullHeal = true
            )
        }
        val teamBCombatants = sideB.disciples.map { disciple ->
            battleSystem.convertDiscipleToCombatant(
                disciple = disciple,
                equipmentMap = sideB.equipmentMap,
                manualMap = sideB.manualMap,
                manualProficiencies = sideB.proficiencies,
                side = CombatantSide.ATTACKER,
                fullHeal = true
            )
        }

        // 构建双方均有 Combatant 的战斗（team=DEFENDER 方，beasts=ATTACKER 方）
        val pvpBattle = Battle(
            team = teamACombatants,
            beasts = teamBCombatants,
            maxTurns = GameConfig.Battle.MAX_TURNS
        )
        // Phase 1（PvP）：胜负判定/死亡/好感度/日志
        val p1 = executePhase1Pvp(
            state, pvpBattle, preparedSides, attackerA, attackerB, isPlayerVsAI, year, month, favorDedup
        )
        // Phase 1 胜方无幸存者则跳过 Phase 2
        if (p1.winnerSurvivors.isEmpty()) return

        // Phase 2（PvE）：胜方 vs 妖兽
        executePhase2Pve(state, p1.winnerP1, p1.winnerSide, p1.winnerSurvivors, beast, year, month)
    }

    /** Phase 1 结算结果（胜方 + 幸存弟子 + 胜方配置） */
    private data class Phase1Result(
        val winnerP1: EncounterAttacker,
        val winnerSurvivors: List<Disciple>,
        val winnerSide: PreparedSide
    )

    /**
     * Phase 1 — 遭遇战（PvP）结算：胜负判定、双方死亡处理、
     * 好感度变更（playerVsAI 每月每宗门去重）、战斗日志。
     */
    private fun executePhase1Pvp(
        state: MutableGameState,
        pvpBattle: Battle,
        preparedSides: Map<String, PreparedSide>,
        attackerA: EncounterAttacker,
        attackerB: EncounterAttacker,
        isPlayerVsAI: Boolean,
        year: Int,
        month: Int,
        favorDedup: MutableSet<String>?
    ): Phase1Result {
        val pvpResult = battleSystem.executeBattle(pvpBattle)

        // 判定 Phase 1 胜方/败方
        val winnerP1: EncounterAttacker
        val loserP1: EncounterAttacker
        val winnerCombatantsP1: List<Combatant>
        val loserCombatantsP1: List<Combatant>

        if (pvpResult.victory) {
            // team (DEFENDER) = attackerA 获胜
            winnerP1 = attackerA; loserP1 = attackerB
            winnerCombatantsP1 = pvpResult.battle.team
            loserCombatantsP1 = pvpResult.battle.beasts
        } else {
            // beasts (ATTACKER) = attackerB 获胜
            winnerP1 = attackerB; loserP1 = attackerA
            winnerCombatantsP1 = pvpResult.battle.beasts
            loserCombatantsP1 = pvpResult.battle.team
        }

        val winnerAliveIdsP1 = winnerCombatantsP1
            .filter { !it.isDead }.map { it.id }.toSet()
        val loserDeadIdsP1 = loserCombatantsP1
            .filter { it.isDead }.map { it.id }.toSet()
        val winnerDeadIdsP1 = winnerCombatantsP1
            .filter { it.isDead }.map { it.id }.toSet()

        DomainLog.i(TAG, "Phase 1 结果: ${winnerP1.sectName} 胜, " +
            "胜方存活=${winnerAliveIdsP1.size}, 败方阵亡=${loserDeadIdsP1.size}")

        // ── Phase 1 死亡处理 ──
        applyDeathsForSect(state, loserP1, loserDeadIdsP1, year)
        applyDeathsForSect(state, winnerP1, winnerDeadIdsP1, year)

        // ── 好感度变更（仅 playerVsAI，每月每个 AI 宗门最多扣 1 次） ──
        if (isPlayerVsAI) {
            val playerSectId = if (attackerA.isPlayer) attackerA.sectId else attackerB.sectId
            val aiSectId = if (attackerA.isPlayer) attackerB.sectId else attackerA.sectId
            val dedupKey = "${aiSectId}_${year * 12 + month}"
            if (favorDedup == null || dedupKey !in favorDedup) {
                favorDedup?.add(dedupKey)
                var relations = FavorDomain.setAcquainted(
                    state.gameData.sectRelations, playerSectId, aiSectId, year
                )
                relations = FavorDomain.modifyFavor(
                    relations, playerSectId, aiSectId, ENCOUNTER_FAVOR_DELTA, year
                )
                state.gameData = state.gameData.copy(sectRelations = relations)
            }
        }

        // ── 构建 Phase 1 战斗日志 ──
        val p1Log = buildPhase1Log(
            attackerA = attackerA,
            attackerB = attackerB,
            pvpResult = pvpResult,
            winnerP1 = winnerP1,
            year = year,
            month = month,
            loserDeadIds = loserDeadIdsP1
        )
        state.battleLogs = (state.battleLogs + p1Log)
            .takeLast(GameConfig.Logs.MAX_BATTLE_LOGS)

        val winnerSide = preparedSides.getValue(winnerP1.sectId)
        val winnerSurvivors = winnerSide.disciples
            .filter { it.id in winnerAliveIdsP1 }
        return Phase1Result(winnerP1, winnerSurvivors, winnerSide)
    }

    /**
     * Phase 2 — 胜方 vs 妖兽（PvE）：妖兽战斗、死亡处理、击败标记、战斗日志。
     */
    private fun executePhase2Pve(
        state: MutableGameState,
        winnerP1: EncounterAttacker,
        winnerSide: PreparedSide,
        winnerSurvivors: List<Disciple>,
        beast: WorldLevel,
        year: Int,
        month: Int
    ) {
        // 构建妖兽预计算属性
        val beastPreGenStats = if (beast.beastMaxHp > 0) {
            BattleSystem.BeastPreGenStats(
                maxHp = beast.beastMaxHp,
                maxMp = beast.beastMaxMp,
                physicalAttack = beast.beastPhysicalAttack,
                magicAttack = beast.beastMagicAttack,
                physicalDefense = beast.beastPhysicalDefense,
                magicDefense = beast.beastMagicDefense,
                speed = beast.beastSpeed,
                realmLayer = beast.realmLayer
            )
        } else null

        val beastTypeIndex = beast.beastType
        val beastTypeName = if (beastTypeIndex != null) {
            GameConfig.Beast.getType(beastTypeIndex.coerceIn(0, GameConfig.Beast.TYPES.size - 1)).name
        } else null

        val pveBattle = battleSystem.createBattle(
            disciples = winnerSurvivors,
            equipmentMap = winnerSide.equipmentMap,
            manualMap = winnerSide.manualMap,
            beastLevel = beast.realm,
            beastCount = beast.count,
            beastType = beastTypeName,
            manualProficiencies = winnerSide.proficiencies,
            beastPreGenStats = beastPreGenStats
        )
        val pveResult = battleSystem.executeBattle(pveBattle)

        DomainLog.i(TAG, "Phase 2 结果: ${winnerP1.sectName} " +
            if (pveResult.victory) "击败了" else "被" + "妖兽击败")

        // ── Phase 2 死亡处理 ──
        val p2DeadIds = pveResult.battle.team
            .filter { it.isDead }.map { it.id }.toSet()
        applyDeathsForSect(state, winnerP1, p2DeadIds, year)

        // ── 击败标记 ──
        if (pveResult.victory) {
            state.gameData = state.gameData.copy(
                worldLevels = state.gameData.worldLevels.map { wl ->
                    if (wl.id == beast.id) wl.copy(defeated = true) else wl
                }
            )
        }

        // ── 构建 Phase 2 战斗日志 ──
        val p2Log = buildPhase2Log(
            winner = winnerP1,
            beast = beast,
            pveResult = pveResult,
            year = year,
            month = month,
            p2DeadIds = p2DeadIds
        )
        state.battleLogs = (state.battleLogs + p2Log)
            .takeLast(GameConfig.Logs.MAX_BATTLE_LOGS)
    }

    // ═══════════════════════════════════════════════════════════
    // 死亡处理
    // ═══════════════════════════════════════════════════════════

    /**
     * 根据宗门类型应用弟子阵亡。
     *
     * 玩家弟子：通过 [DiscipleDeathHandler] 标记死亡 + deathYears。
     * AI 弟子：标记死亡但保留在列表中（与 [AISectBeastAttackProcessor.handleAIDeaths] 一致）。
     */
    private fun applyDeathsForSect(
        state: MutableGameState,
        attacker: EncounterAttacker,
        deadIds: Set<String>,
        year: Int
    ) {
        if (deadIds.isEmpty()) return

        if (attacker.isPlayer) {
            // 玩家弟子死亡 — 死战到底，装备不归还
            deathHandler.markAllDead(state.discipleTables, deadIds, year)
        } else {
            // AI 弟子死亡 — 标记死亡但保留在列表中
            state.gameData = state.gameData.copy(
                aiSectDisciples = state.gameData.aiSectDisciples.mapValues { (sectId, disciples) ->
                    if (sectId == attacker.sectId) {
                        disciples.map { d ->
                            if (d.id in deadIds) d.copy(
                                isAlive = false,
                                status = DiscipleStatus.DEAD
                            ) else d
                        }
                    } else {
                        disciples
                    }
                }
            )
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 战前准备（宗门方装备/功法映射构建）
    // ═══════════════════════════════════════════════════════════

    /**
     * 根据宗门类型准备战斗数据。
     *
     * 玩家方使用真实装备/功法实例；AI 方调用 [AISectDiscipleManager.prepareDisciplesForBattle]
     * 按境界范围随机生成模拟装备/功法（不含丹药/血炼）。
     *
     * @param attacker 攻击方宗门
     * @param state    当前可变游戏状态
     * @return [PreparedSide] 包含弟子副本和对应的装备/功法/熟练度映射
     */
    private fun prepareSide(
        attacker: EncounterAttacker,
        state: MutableGameState
    ): PreparedSide {
        return if (attacker.isPlayer) {
            PreparedSide(
                disciples = attacker.teamDisciples,
                equipmentMap = state.equipmentInstances.associateBy { it.id },
                manualMap = state.manualInstances.associateBy { it.id },
                proficiencies = state.gameData.manualProficiencies.mapValues { (_, list) ->
                    list.associateBy { it.manualId }
                }
            )
        } else {
            val prepared = AISectDiscipleManager.prepareDisciplesForBattle(attacker.teamDisciples)
            PreparedSide(
                disciples = prepared.disciples,
                equipmentMap = prepared.equipmentMap,
                manualMap = prepared.manualMap,
                proficiencies = prepared.proficiencies
            )
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 战斗日志构建
    // ═══════════════════════════════════════════════════════════

    /**
     * 构建 Phase 1 (PvP) 的战斗日志。
     */
    private fun buildPhase1Log(
        attackerA: EncounterAttacker,
        attackerB: EncounterAttacker,
        pvpResult: BattleSystemResult,
        winnerP1: EncounterAttacker,
        year: Int,
        month: Int,
        loserDeadIds: Set<String>
    ): BattleLog {
        val teamMembers = pvpResult.battle.team.map { m ->
            BattleLogMember(
                id = m.id,
                name = m.name,
                realm = m.realm,
                realmName = m.realmName,
                realmLayer = m.realmLayer,
                hp = m.hp,
                maxHp = m.maxHp,
                mp = m.mp,
                maxMp = m.maxMp,
                isAlive = !m.isDead,
                portraitRes = m.portraitRes
            )
        }
        val enemies = pvpResult.battle.beasts.map { b ->
            BattleLogEnemy(
                id = b.id,
                name = b.name,
                realm = b.realm,
                realmName = b.realmName,
                realmLayer = b.realmLayer,
                hp = b.hp,
                maxHp = b.maxHp,
                isAlive = !b.isDead,
                portraitRes = b.portraitRes
            )
        }
        val rounds = pvpResult.log.rounds.map { r ->
            BattleLogRound(
                roundNumber = r.roundNumber,
                actions = r.actions.map { a ->
                    BattleLogAction(
                        type = a.type,
                        attacker = a.attacker,
                        attackerType = a.attackerType,
                        target = a.target,
                        damage = a.damage,
                        damageType = a.damageType,
                        isCrit = a.isCrit,
                        isKill = a.isKill,
                        message = a.message,
                        skillName = a.skillName
                    )
                }
            )
        }

        val isWinnerA = winnerP1.sectId == attackerA.sectId
        val result = if (isWinnerA) BattleResult.WIN else BattleResult.LOSE
        val details = "${winnerP1.sectName}在遭遇战中战胜了${
            if (isWinnerA) attackerB.sectName else attackerA.sectName
        }"

        return BattleLog(
            year = year,
            month = month,
            type = BattleType.ENCOUNTER,
            attackerName = attackerA.sectName,
            defenderName = attackerB.sectName,
            result = result,
            teamMembers = teamMembers,
            enemies = enemies,
            rounds = rounds,
            turns = pvpResult.turnCount,
            teamCasualties = attackerA.teamDisciples.size -
                pvpResult.battle.team.count { !it.isDead },
            beastsDefeated = loserDeadIds.size,
            details = details
        )
    }

    /**
     * 构建 Phase 2 (PvE) 的战斗日志。
     */
    private fun buildPhase2Log(
        winner: EncounterAttacker,
        beast: WorldLevel,
        pveResult: BattleSystemResult,
        year: Int,
        month: Int,
        p2DeadIds: Set<String>
    ): BattleLog {
        val teamMembers = pveResult.battle.team.map { m ->
            BattleLogMember(
                id = m.id,
                name = m.name,
                realm = m.realm,
                realmName = m.realmName,
                realmLayer = m.realmLayer,
                hp = m.hp,
                maxHp = m.maxHp,
                mp = m.mp,
                maxMp = m.maxMp,
                isAlive = !m.isDead,
                portraitRes = m.portraitRes
            )
        }
        val enemies = pveResult.battle.beasts.map { b ->
            BattleLogEnemy(
                id = b.id,
                name = b.name,
                realm = b.realm,
                realmName = b.realmName,
                realmLayer = b.realmLayer,
                hp = b.hp,
                maxHp = b.maxHp,
                isAlive = !b.isDead,
                portraitRes = b.portraitRes
            )
        }
        val rounds = pveResult.log.rounds.map { r ->
            BattleLogRound(
                roundNumber = r.roundNumber,
                actions = r.actions.map { a ->
                    BattleLogAction(
                        type = a.type,
                        attacker = a.attacker,
                        attackerType = a.attackerType,
                        target = a.target,
                        damage = a.damage,
                        damageType = a.damageType,
                        isCrit = a.isCrit,
                        isKill = a.isKill,
                        message = a.message,
                        skillName = a.skillName
                    )
                }
            )
        }

        val result = if (pveResult.victory) BattleResult.WIN else BattleResult.LOSE
        val beastName = beast.beastName.ifEmpty { "妖兽" }
        val details = if (pveResult.victory) {
            "${winner.sectName}击败了${beastName}"
        } else {
            "${winner.sectName}被${beastName}击败"
        }

        return BattleLog(
            year = year,
            month = month,
            type = BattleType.PVE,
            attackerName = "${winner.sectName}队伍",
            defenderName = beastName,
            result = result,
            teamMembers = teamMembers,
            enemies = enemies,
            rounds = rounds,
            turns = pveResult.turnCount,
            teamCasualties = p2DeadIds.size,
            beastsDefeated = if (pveResult.victory) beast.count
                else pveResult.battle.beasts.count { it.isDead },
            details = details
        )
    }
}

/**
 * 遭遇战攻击方数据类。
 *
 * @param sectId        宗门 ID
 * @param sectName      宗门名称
 * @param isPlayer      是否为玩家宗门
 * @param teamDisciples 出战弟子列表（原班进攻妖兽的队伍）
 */
data class EncounterAttacker(
    val sectId: String,
    val sectName: String,
    val isPlayer: Boolean,
    val teamDisciples: List<Disciple>
)

/**
 * 战前准备结果：某方宗门（玩家或 AI）的战斗数据。
 *
 * 玩家方使用真实装备/功法实例；AI 方为随机生成的模拟装备/功法。
 * [disciples] 对 AI 方是修改后的副本（带 equipmentId/manualIds），对玩家方是原始弟子。
 */
data class PreparedSide(
    val disciples: List<Disciple>,
    val equipmentMap: Map<String, EquipmentInstance>,
    val manualMap: Map<String, ManualInstance>,
    val proficiencies: Map<String, Map<String, ManualProficiencyData>>
)
