package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.engine.SectWarehouseManager
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.engine.domain.battle.AIBattleWinner
import com.xianxia.sect.core.engine.domain.battle.AISectAttackManager
import com.xianxia.sect.core.engine.domain.battle.AISectGarrisonManager
import com.xianxia.sect.core.engine.domain.battle.AttackWarningService
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.Combatant
import com.xianxia.sect.core.engine.domain.disciple.DiscipleSlotCleanup
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.exploration.DiscipleDeathHandler
import com.xianxia.sect.core.model.AttackWarning
import com.xianxia.sect.core.model.BattleLogEnemy
import com.xianxia.sect.core.model.BattleLogMember
import com.xianxia.sect.core.model.BattleResult
import com.xianxia.sect.core.model.BattleType
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GarrisonSlot
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.SectDetail
import com.xianxia.sect.core.model.WarningStage
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.recordPlayerBattle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 防守战准备阶段返回的数据容器（D15 迁移自 AISectBattleProcessor），支持解构。
 */
private data class DefensePreparation(
    val defenderIds: List<String>,
    val equipmentMap: Map<String, EquipmentInstance>,
    val manualMap: Map<String, ManualInstance>,
    val profMap: Map<String, Map<String, ManualProficiencyData>>,
    val data: GameData
)

/**
 * 玩家防守战结算处理器（D15 拆分自 AISectBattleProcessor，2026-08-08）。
 *
 * 职责：AI 攻打玩家的完整防守链路——预警生命周期推进、到期战书内联结算
 * （防守方选择/组队/战斗/结果应用）、驻军填充。
 * 与 [AISectOccupationResolver]（占领结算）解耦，编排由 [AISectBattleProcessor] 承担。
 */
@Singleton
@GameService("PlayerDefenseProcessor")
class PlayerDefenseProcessor @Inject constructor(
    private val stateStore: GameStateStore,
    private val battleSystem: BattleSystem,
    private val attackWarningService: AttackWarningService,
    private val cultivationService: CultivationService,
    private val sectWarehouseManager: SectWarehouseManager,
    private val deathHandler: DiscipleDeathHandler,
    private val discipleSlotCleanup: DiscipleSlotCleanup
) {

    /**
     * AI 攻打玩家：预警收敛 + 到期结算 + 新预警生成。
     * 由 [AISectBattleProcessor.processAISectOperations] 编排调用。
     */
    internal fun processPlayerDefenseBattles() {
        // 1. 旧档收敛：历史预警统一为"战书阶段、下月进攻"（幂等；新档无变化）
        stateStore.update {
            attackWarningService.normalizeImminentWarningsSync(this)
        }

        // 2. 推进预警可能已修改 activeAttackWarnings / gameMonth，重新读取最新状态
        val data = stateStore.gameData.value

        // 3. 检查到期预警 → 执行内联结算（战斗前结算 + 战斗 + 结果）
        val expiredWarnings = data.activeAttackWarnings.filter {
            it.stage == WarningStage.WAR_DECLARATION &&
                data.gameYear * 12 + data.gameMonth >= it.attackMonth
        }
        for (expired in expiredWarnings) {
            executePlayerDefenseBattle(expired)
        }

        // 4. 新攻击决策 → 生成"即将进攻"预警（下月直接进攻）
        val decision = AISectAttackManager.decidePlayerAttack(data)
        if (decision is AISectAttackManager.PlayerAttackDecision.GenerateWarning) {
            stateStore.update {
                attackWarningService.addWarningSync(
                    this,
                    attackWarningService.createImminentAttackWarning(
                        decision.attackerSectId, decision.attackerSectName
                    )
                )
            }
        }

        // 5. 驻军填充
        stateStore.update {
            gameData = AISectGarrisonManager.fillEmptyGarrisonSlots(gameData)
        }
    }

    @Suppress("UnusedParameter") // expired 保留签名兼容（搬移自原文件）
    private fun executePlayerDefenseBattle(expired: AttackWarning) {
        val attackerSectId = expired.attackerSectId

        // 单事务原子执行：防守方选择、战前结算、组队、战斗、结果应用全部在锁内完成
        stateStore.update {
            // 1. 防守方选择和准备（从锁内最新数据读取）
            val preparation = selectAndPrepareDefenders(this, expired)
            if (preparation == null) {
                return@update
            }
            val defenderIds = preparation.defenderIds

            // 2. 战斗前结算 + 刷新防守方状态
            cultivationService.forceSettleDisciplesBeforeBattle(this, defenderIds)

            // 3. 组防守队（从锁内最新 Tables 构建）
            val defenseTeam = buildDefenseTeam(this, preparation)

            // 4. 执行战斗
            val result = AISectAttackManager.executePlayerAttack(
                gameData, attackerSectId, defenseTeam
            ) ?: return@update

            // 5. 应用战斗结果
            applyDefenseBattleResult(this, expired, result)
        }
    }

    @Suppress("UnusedParameter") // expired 保留签名兼容（搬移自原文件）
    private fun selectAndPrepareDefenders(
        state: MutableGameState,
        expired: AttackWarning
    ): DefensePreparation? {
        val data = state.gameData
        val allDisciples = state.discipleTables.assembleAll()
        val allAlive = allDisciples.filter {
            it.isAlive && it.status !in setOf(
                DiscipleStatus.ON_MISSION,
                DiscipleStatus.IN_TEAM,
                DiscipleStatus.REFLECTING,
                DiscipleStatus.GARRISONING,
                DiscipleStatus.REFINING
            )
        }
        val pids = data.patrolSlots
            .filter { it.discipleId.isNotEmpty() }
            .map { it.discipleId }.toSet()
        val patrol = allAlive.filter { it.id in pids }
            .sortedByDescending { it.realmLayer }
        val remaining = allAlive.filter { it.id !in pids }
            .sortedByDescending { it.realmLayer }
        val selectedDefenders = (patrol + remaining)
            .take(AISectAttackManager.TEAM_SIZE)

        val defenderIds = selectedDefenders.map { it.id }
        if (defenderIds.isEmpty()) return null

        val equipmentMap = state.equipmentInstances.all().associateBy { it.id }
        val manualMap = state.manualInstances.all().associateBy { it.id }
        val profMap = data.manualProficiencies.mapValues { (_, list) ->
            list.associateBy { it.manualId }
        }
        return DefensePreparation(defenderIds, equipmentMap, manualMap, profMap, data)
    }

    private fun buildDefenseTeam(
        state: MutableGameState,
        preparation: DefensePreparation
    ): List<Combatant> {
        val tables = state.discipleTables
        val refreshedDefenders = preparation.defenderIds.mapNotNull { id ->
            val idInt = id.toIntOrNull() ?: return@mapNotNull null
            if (tables.isAlive[idInt] == 1) tables.assemble(idInt) else null
        }
        return refreshedDefenders.map { d ->
            battleSystem.convertDiscipleToCombatant(
                d, preparation.equipmentMap, preparation.manualMap,
                preparation.profMap, CombatantSide.DEFENDER,
                bloodRefinementPct = state.gameData.bloodRefinementPctTotals[d.id]
            )
        }
    }

    private fun applyDefenseBattleResult(
        state: MutableGameState,
        expired: AttackWarning,
        result: AISectAttackManager.AIAttackResult
    ) {
        // 1. 删除到期预警
        state.gameData = state.gameData.copy(
            activeAttackWarnings = state.gameData.activeAttackWarnings.filter {
                it.warningId != expired.warningId
            }
        )

        // 2. 伤亡结算
        val newDisciples = applyDefenseCasualties(state, result)
        state.discipleTables.replaceAll(newDisciples)
        // 2b. 阵亡弟子从所有槽位清理（2026-08-10：原实现补偿式死亡标记但不清槽——
        // 被征召的巡逻弟子阵亡后永久残留巡逻槽/生产槽，界面继续显示"在岗"；
        // replaceAll 只影响 disciple 列，与 GameData 槽位清理互不冲突）
        result.deadDefenderIds.forEach { id ->
            discipleSlotCleanup.clearAllSlotsState(state, id, includeResidence = true)
        }

        // 3. 查找玩家宗门信息
        val playerSectId = state.gameData.worldMapSects
            .find { it.isPlayerSect }?.id ?: return
        val playerSectName = state.gameData.worldMapSects
            .find { it.isPlayerSect }?.name ?: "玩家宗门"

        // 4. 构建战后数据
        val updated = buildPostBattleGameData(state, result, playerSectId)

        // 5. 战斗日志
        recordDefenseBattleLog(state, result, newDisciples, playerSectId, playerSectName)

        state.gameData = updated
    }

    private fun applyDefenseCasualties(
        state: MutableGameState,
        result: AISectAttackManager.AIAttackResult
    ): List<Disciple> {
        val currentDisciples = state.discipleTables.assembleAll()
        val deadDefenders = currentDisciples.filter { it.id in result.deadDefenderIds }
        // 年报死亡计数：防守战死亡仅 map 标记 + backfillDeathYears（无统一入口），
        // 本函数每场战斗恰好调用一次，按阵亡数直接计数
        if (deadDefenders.isNotEmpty()) {
            state.gameData = state.gameData.copy(
                annualDeceasedDisciples = state.gameData.annualDeceasedDisciples + deadDefenders.size
            )
        }
        var newDisciples = currentDisciples
        if (deadDefenders.isNotEmpty()) {
            newDisciples = DiscipleStatCalculator.applyGriefToRelatives(
                newDisciples, deadDefenders, state.gameData.gameYear
            )
        }
        newDisciples = newDisciples.map { d ->
            if (d.id in result.deadDefenderIds) {
                d.copy(isAlive = false, status = DiscipleStatus.DEAD)
            } else {
                val hp = result.defenderSurvivorHpMap[d.id]
                val mp = result.defenderSurvivorMpMap[d.id]
                if (hp != null && mp != null) {
                    // clamp 上限用含血炼口径（P2 对抗性审查修复），防削血
                    val (finalMaxHp, finalMaxMp) = DiscipleStatCalculator.battleWritebackMaxHpMp(state, d)
                    d.copy(
                        combat = d.combat.copy(
                            currentHp = hp.coerceIn(0, finalMaxHp),
                            currentMp = mp.coerceIn(0, finalMaxMp)
                        )
                    )
                } else d
            }
        }
        // 死亡年份由 DiscipleDeathHandler 统一补写（replaceAll 已清空列写入）
        deathHandler.backfillDeathYears(
            state.discipleTables, newDisciples, state.gameData.gameYear
        )
        return newDisciples
    }

    private fun buildPostBattleGameData(
        state: MutableGameState,
        result: AISectAttackManager.AIAttackResult,
        playerSectId: String
    ): GameData {
        val attackerDisc = state.gameData.aiSectDisciples[
            result.attackerSectId] ?: emptyList()

        var updated = state.gameData.copy(
            aiSectDisciples = state.gameData.aiSectDisciples.toMutableMap().apply {
                this[result.attackerSectId] = attackerDisc.filter {
                    it.id !in result.deadAttackerIds
                }
            },
            worldMapSects = state.gameData.worldMapSects.map { sect ->
                if (sect.id == playerSectId) sect.copy(
                    garrisonSlots = sect.garrisonSlots.map { slot ->
                        if (slot.discipleId in result.deadDefenderIds)
                            GarrisonSlot(index = slot.index) else slot
                    }
                ) else sect
            },
            sectRelations = state.gameData.sectRelations.map { r ->
                val relevant = (r.sectId1 == result.attackerSectId &&
                    r.sectId2 == playerSectId) ||
                    (r.sectId1 == playerSectId &&
                        r.sectId2 == result.attackerSectId)
                if (relevant) r.copy(
                    favor = (r.favor - 15).coerceIn(
                        com.xianxia.sect.core.config.FavorConfig.MIN_FAVOR,
                        com.xianxia.sect.core.config.FavorConfig.MAX_FAVOR)
                ) else r
            }
        )

        if (result.winner == AIBattleWinner.ATTACKER) {
            val detail = updated.sectDetails[playerSectId]
                ?: SectDetail(sectId = playerSectId)
            val loot = sectWarehouseManager.calculateWarehouseLootLoss(detail.warehouse)
            val newWarehouse = sectWarehouseManager.applyLootLossToWarehouse(
                detail.warehouse, loot
            )
            updated = updated.copy(
                sectDetails = updated.sectDetails.toMutableMap().apply {
                    this[playerSectId] = detail.copy(warehouse = newWarehouse)
                }
            )
        }
        return updated
    }

    @Suppress("UnusedParameter") // playerSectId 保留签名兼容（搬移自原文件）
    private fun recordDefenseBattleLog(
        state: MutableGameState,
        result: AISectAttackManager.AIAttackResult,
        newDisciples: List<Disciple>,
        playerSectId: String,
        playerSectName: String
    ) {
        val attackerDisc = state.gameData.aiSectDisciples[
            result.attackerSectId] ?: emptyList()

        val winResult = when (result.winner) {
            AIBattleWinner.ATTACKER -> BattleResult.LOSE
            AIBattleWinner.DEFENDER -> BattleResult.WIN
            AIBattleWinner.DRAW -> BattleResult.DRAW
        }
        val participantIds = result.deadDefenderIds.toSet() +
            result.defenderSurvivorHpMap.keys
        val teamMembers = newDisciples
            .filter { it.id in participantIds }
            .map { d ->
                BattleLogMember(
                    id = d.id, name = d.name,
                    realm = d.realm, realmName = d.realmName,
                    hp = result.defenderSurvivorHpMap[d.id] ?: 0,
                    maxHp = d.maxHp,
                    mp = result.defenderSurvivorMpMap[d.id] ?: 0,
                    maxMp = d.maxMp,
                    isAlive = d.id !in result.deadDefenderIds,
                    portraitRes = d.portraitRes
                )
            }
        val survivorIds = result.survivingAttackers.map { it.id }.toSet()
        val deadAttackerData = attackerDisc.filter { it.id in result.deadAttackerIds }
        val enemies = (result.survivingAttackers + deadAttackerData).map { d ->
            BattleLogEnemy(
                id = d.id,
                name = "${result.attackerSectName}弟子",
                realm = d.realm, realmName = d.realmName,
                hp = if (d.id in survivorIds) d.combat.currentHp else 0,
                maxHp = d.maxHp,
                isAlive = d.id in survivorIds,
                portraitRes = d.portraitRes
            )
        }
        state.recordPlayerBattle(
            year = state.gameData.gameYear,
            month = state.gameData.gameMonth,
            type = BattleType.SECT_WAR,
            attackerName = result.attackerSectName,
            defenderName = playerSectName,
            result = winResult,
            teamMembers = teamMembers,
            enemies = enemies,
            rounds = result.rounds,
            turns = result.rounds.size,
            details = "${result.attackerSectName} 进犯${playerSectName}，" +
                when (result.winner) {
                    AIBattleWinner.ATTACKER -> "防守失利"
                    AIBattleWinner.DEFENDER -> "防守成功"
                    else -> "不分胜负"
                },
            beastsDefeated = result.deadAttackerIds.size,
            teamCasualties = result.deadDefenderIds.size
        )
    }
}
