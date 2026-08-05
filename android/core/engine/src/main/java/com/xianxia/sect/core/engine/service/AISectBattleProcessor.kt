package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.BattleLogEnemy
import com.xianxia.sect.core.model.WarningStage
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.AttackWarning
import com.xianxia.sect.core.model.SectRelation
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.model.GarrisonSlot
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.SectDetail
import com.xianxia.sect.core.model.BattleResult
import com.xianxia.sect.core.model.BattleLogMember
import com.xianxia.sect.core.model.BattleType
import com.xianxia.sect.core.model.SectWarehouse
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.recordPlayerBattle
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.engine.domain.battle.AIBattleWinner
import com.xianxia.sect.core.engine.domain.battle.AISectAttackManager
import com.xianxia.sect.core.engine.domain.battle.AISectAttackManager.PlayerAttackDecision
import com.xianxia.sect.core.engine.domain.battle.AISectGarrisonManager
import com.xianxia.sect.core.engine.domain.battle.AttackWarningService
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.Combatant
import com.xianxia.sect.core.engine.SectWarehouseManager
import com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.LazyEvaluationDispatcher
import com.xianxia.sect.core.perf.ThermalMonitor
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.exploration.DiscipleDeathHandler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 防守战准备阶段返回的数据容器（D3 迁移自 CaveExplorationProcessor），支持解构。
 */
private data class DefensePreparation(
    val defenderIds: List<String>,
    val equipmentMap: Map<String, EquipmentInstance>,
    val manualMap: Map<String, ManualInstance>,
    val profMap: Map<String, Map<String, ManualProficiencyData>>,
    val data: GameData
)

/**
 * AI 宗门攻防结算处理器（D3 拆分自 CaveExplorationProcessor，2026-08-05）。
 *
 * 职责：AI 非焦点域热控分批修炼、宗门等级同步、AI 攻打玩家（预警生命周期 +
 * 战斗结算）、AI-vs-AI 战斗、玩家占领宗门防守战。
 * 洞府探索域保留在 [CaveExplorationProcessor]。
 */
@Singleton
@GameService("AISectBattleProcessor")
class AISectBattleProcessor @Inject constructor(
    private val stateStore: GameStateStore,
    private val thermalMonitor: ThermalMonitor,
    private val battleSystem: BattleSystem,
    private val attackWarningService: AttackWarningService,
    private val cultivationService: CultivationService,
    private val sectWarehouseManager: SectWarehouseManager,
    private val deathHandler: DiscipleDeathHandler
) {
    // AI 非焦点域热控分批状态
    private var aiNonFocusedLastSettleMonth: Int = 0
    private var aiNonFocusedBatchMonths: Int = 1

    companion object {
        private const val THERMAL_EMERGENCY_BATCH = 12
        private const val THERMAL_REDUCE_BATCH = 6
        private const val THERMAL_NORMAL_BATCH = 1

        /**
         * 从实际参战弟子构建防守战日志的敌人快照列表（纯函数，D3 迁移自 CaveExplorationProcessor）。
         * 供 BattleTickSystem 和测试使用。
         */
        internal fun buildDefenseBattleEnemies(
            survivingAttackers: List<Disciple>,
            deadAttackerIds: List<String>,
            sectDisciplePool: List<Disciple>,
            attackerSectName: String
        ): List<BattleLogEnemy> {
            val survivorIds = survivingAttackers.map { it.id }.toSet()
            val deadAttackerData = sectDisciplePool.filter {
                it.id in deadAttackerIds
            }
            val participants = survivingAttackers + deadAttackerData
            return participants.map { d ->
                val survived = d.id in survivorIds
                BattleLogEnemy(
                    id = d.id,
                    name = "${attackerSectName}弟子",
                    realm = d.realm,
                    realmName = d.realmName,
                    hp = if (survived) d.combat.currentHp else 0,
                    maxHp = d.maxHp,
                    isAlive = survived,
                    portraitRes = d.portraitRes
                )
            }
        }
    }
    private fun computeAIBatch(currentAbsoluteMonth: Int) {
        if (aiNonFocusedLastSettleMonth == 0) {
            aiNonFocusedLastSettleMonth = currentAbsoluteMonth
            aiNonFocusedBatchMonths = 1
            return
        }
        val monthsSince = currentAbsoluteMonth - aiNonFocusedLastSettleMonth
        if (monthsSince <= 0) {
            aiNonFocusedBatchMonths = 1
            return
        }
        val batchSize = when {
            thermalMonitor.shouldEmergencySave() -> THERMAL_EMERGENCY_BATCH
            thermalMonitor.shouldReduceWorkload() -> THERMAL_REDUCE_BATCH
            else -> THERMAL_NORMAL_BATCH
        }
        aiNonFocusedBatchMonths = if (monthsSince >= batchSize) {
            aiNonFocusedLastSettleMonth = currentAbsoluteMonth
            monthsSince
        } else {
            0
        }
    }

    fun processAISectOperations(year: Int, month: Int) {
        stateStore.update { processAISectOperations(year, month, this) }
        processAIVsAIBattles()
        processPlayerDefenseBattles()
    }

    @Suppress("CyclomaticComplexMethod", "MaxLineLength") // 宗门等级同步 when 链（搬移自原文件）
    fun processAISectOperations(year: Int, month: Int, state: MutableGameState) {
        val data = state.gameData
        val aiDisciples = data.aiSectDisciples

        val currentAbsMonth = LazyEvaluationDispatcher.toAbsoluteMonth(year, month)
        computeAIBatch(currentAbsMonth)

        val cleanedSectDetails = data.sectDetails.mapValues { (sectId, detail) ->
            val sect = data.worldMapSects.find { it.id == sectId }
            if (sect != null && !sect.isPlayerSect && detail.warehouse.items.isNotEmpty()) {
                detail.copy(warehouse = SectWarehouse())
            } else {
                detail
            }
        }

        // AI 弟子修炼（热控分批：跳过时保留原数据；传宗门等级供突破刷新装备/功法数量）
        val updatedAiDisciples = if (aiNonFocusedBatchMonths > 0) {
            aiDisciples.mapValues { (sectId, disciples) ->
                val sect = data.worldMapSects.find { it.id == sectId }
                if (sect == null || sect.isPlayerSect) return@mapValues disciples
                AISectDiscipleManager.processMonthlyCultivation(
                    disciples, aiNonFocusedBatchMonths, sect.level
                )
            }
        } else {
            aiDisciples
        }

        // 同步 AI 宗门等级 — 月度修炼弟子只会变强，仅用 any{} 短路检查升级（只升不降）
        // 玩家宗门等级由玩家手动升级（通过 SectLevelDetailDialog），此处跳过
        var finalAiDisciples = updatedAiDisciples
        val syncedWorldSects = data.worldMapSects.map { sect ->
            if (sect.isPlayerSect) {
                sect  // 玩家宗门手动升级，月度 tick 不再自动升级
            } else if (sect.level >= SectLevel.TOP) {
                sect  // 已是顶级 → 跳过
            } else {
                val disciples = updatedAiDisciples[sect.id] ?: return@map sect
                val newLevel = when (sect.level) {
                    SectLevel.SMALL -> if (disciples.any { it.isAlive && it.realm <= 5 }) SectLevel.MEDIUM else sect.level
                    SectLevel.MEDIUM -> if (disciples.any { it.isAlive && it.realm <= 4 }) SectLevel.LARGE else sect.level
                    SectLevel.LARGE -> if (disciples.any { it.isAlive && it.realm <= 2 }) SectLevel.TOP else sect.level
                    else -> sect.level
                }
                if (sect.level != newLevel) {
                    // 等级升级：全宗门补装备/功法数量至新等级标准（只补缺，不动已有；
                    // 同时修正"突破当月按旧等级计数"的数量滞后）
                    val upgraded = disciples.map { AISectDiscipleManager.ensureDiscipleGear(it, newLevel) }
                    finalAiDisciples = finalAiDisciples + (sect.id to upgraded)
                    sect.copy(level = newLevel, levelName = SectLevel.levelName(newLevel))
                } else {
                    sect
                }
            }
        }

        state.gameData = state.gameData.copy(
            sectDetails = cleanedSectDetails,
            aiSectDisciples = state.gameData.aiSectDisciples.mapValues { (sId, current) ->
                val calculated = finalAiDisciples[sId] ?: return@mapValues current
                val currentIds = current.map { it.id }.toSet()
                calculated.filter { it.id in currentIds }
            },
            worldMapSects = syncedWorldSects
        )
    }

    /**
     * AI 攻打玩家：预警生命周期 + 战斗结算。
     */
    private fun processPlayerDefenseBattles() {
        // 1. 推进预警阶段（谴责 → 战书）
        stateStore.update {
            attackWarningService.advanceWarningsIfNeededSync(this)
        }

        // 2. 推进预警可能已修改 activeAttackWarnings / gameMonth，重新读取最新状态
        val data = stateStore.gameData.value

        // 3. 检查到期战书 → 执行内联结算（战斗前结算 + 战斗 + 结果）
        val expiredWarnings = data.activeAttackWarnings.filter {
            it.stage == WarningStage.WAR_DECLARATION &&
                data.gameYear * 12 + data.gameMonth >= it.attackMonth
        }
        for (expired in expiredWarnings) {
            executePlayerDefenseBattle(expired)
        }

        // 4. 新攻击决策 → 生成谴责
        val decision = AISectAttackManager.decidePlayerAttack(data)
        if (decision is PlayerAttackDecision.GenerateWarning) {
            stateStore.update {
                attackWarningService.addWarningSync(
                    this,
                    attackWarningService.createDenunciationWarning(
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
            if (preparation == null) return@update
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

    /**
     * AI-vs-AI 战斗月度结算（含玩家占领宗门防御）。
     * 同步执行，不通过 scope.launch 异步写入。
     */
    private fun processAIVsAIBattles() {
        val data = stateStore.gameData.value
        val playerSectId = data.worldMapSects
            .find { it.isPlayerSect }?.id

        // 驻军弟子由 BattleTickSystem 每 tick 实时结算，此处无需重复

        // 构建玩家占领宗门防御信息（P-2 拆分：防御构建提取）
        val allDisciples = stateStore.discipleTables.assembleAll()
        val equipmentMap = stateStore.equipmentInstancesSnapshot
            .associateBy { it.id }
        val manualMap = stateStore.manualInstancesSnapshot
            .associateBy { it.id }
        val profMap = data.manualProficiencies.mapValues { (_, list) ->
            list.associateBy { it.manualId }
        }
        val playerDefenders = buildPlayerDefenseInfo(
            data, allDisciples, equipmentMap, manualMap, profMap, playerSectId
        )

        val results = AISectAttackManager.decideAttacks(data, playerDefenders)
        if (results.isEmpty()) return

        // P-2 拆分：单次攻击结果应用提取（含占领/关系变更）
        for (result in results) {
            applyAIAttackResult(result, data.gameYear)
        }
    }

    /** P-2：构建玩家占领宗门的防御信息（驻军弟子 + 战斗参战者）。 */
    @Suppress("UnusedParameter") // playerSectId 保留签名兼容（搬移自原文件）
    private fun buildPlayerDefenseInfo(
        data: com.xianxia.sect.core.model.GameData,
        allDisciples: List<com.xianxia.sect.core.model.Disciple>,
        equipmentMap: Map<String, com.xianxia.sect.core.model.EquipmentInstance>,
        manualMap: Map<String, com.xianxia.sect.core.model.ManualInstance>,
        profMap: Map<String, Map<String, com.xianxia.sect.core.model.ManualProficiencyData>>,
        playerSectId: String?
    ): Map<String, AISectAttackManager.PlayerOccupiedDefenseInfo> = if (playerSectId != null) {
        data.worldMapSects
            .filter { it.isPlayerOccupied && it.occupierSectId == playerSectId }
            .associate { sect ->
                val garrisoned = sect.garrisonSlots
                    .filter { it.discipleId.isNotEmpty() }
                    .mapNotNull { slot ->
                        allDisciples.find { d ->
                            d.id == slot.discipleId && d.isAlive
                        }
                    }
                val combatants = garrisoned.map { d ->
                    battleSystem.convertDiscipleToCombatant(
                        d, equipmentMap, manualMap, profMap,
                        CombatantSide.DEFENDER,
                        bloodRefinementPct = data.bloodRefinementPctTotals[d.id]
                    )
                }
                sect.id to AISectAttackManager.PlayerOccupiedDefenseInfo(
                    disciples = garrisoned,
                    combatants = combatants
                )
            }
    } else emptyMap()

    /** P-2：应用单次 AI 进攻结果（死亡过滤/占领/关系变更，单事务原子提交）。 */
    private fun applyAIAttackResult(
        result: AISectAttackManager.AIAttackResult,
        gameYear: Int
    ) {
        stateStore.update {
                val currentGameData = gameData
                val defenderSect = currentGameData.worldMapSects
                    .find { it.id == result.defenderSectId }
                val isPlayerOccupied = defenderSect
                    ?.isPlayerOccupied == true

                // 玩家占领宗门防御：更新驻军弟子状态
                if (isPlayerOccupied) {
                    updatePlayerGarrisonState(
                        this, result, discipleTables, gameYear
                    )
                }

                // 过滤阵亡弟子（攻击者/防守者/占领者 + 驻军清理）
                val updatedAttacker = (currentGameData.aiSectDisciples[result.attackerSectId] ?: emptyList())
                    .filter { it.id !in result.deadAttackerIds }
                val isAiOccupied = defenderSect != null &&
                    defenderSect.occupierSectId.isNotEmpty() &&
                    !isPlayerOccupied
                val occupierId = defenderSect?.occupierSectId ?: ""
                val (updatedDefender, updatedOccupier, updatedSects) = computeCasualtyUpdates(
                    currentGameData, result, isAiOccupied, occupierId
                )

                var updatedData = gameData.copy(
                    aiSectDisciples = gameData.aiSectDisciples
                        .toMutableMap().apply {
                            this[result.attackerSectId] = updatedAttacker
                            this[result.defenderSectId] = updatedDefender
                            if (updatedOccupier != null &&
                                occupierId.isNotEmpty()) {
                                this[occupierId] = updatedOccupier
                            }
                        },
                    worldMapSects = updatedSects,
                    sectRelations = applyAIAttackFavorPenalty(
                        gameData.sectRelations, result
                    )
                )

                // 占领处理
                updatedData = applyAIOccupation(
                    updatedData, result, isPlayerOccupied, updatedAttacker, updatedDefender
                )

                gameData = updatedData
            }
    }

    /** AI 攻防双方好感惩罚（-10，夹取在允许范围） */
    private fun applyAIAttackFavorPenalty(
        sectRelations: List<SectRelation>,
        result: AISectAttackManager.AIAttackResult
    ): List<SectRelation> {
        return sectRelations.map { r ->
            val relevant =
                (r.sectId1 == result.attackerSectId &&
                    r.sectId2 == result.defenderSectId) ||
                    (r.sectId1 == result.defenderSectId &&
                        r.sectId2 == result.attackerSectId)
            if (relevant) r.copy(
                favor = (r.favor - 10).coerceIn(
                    com.xianxia.sect.core.config.FavorConfig.MIN_FAVOR,
                    com.xianxia.sect.core.config.FavorConfig.MAX_FAVOR
                )
            ) else r
        }
    }

    /**
     * 阵亡过滤：按防守方是否被 AI/玩家占领分流——被 AI 占领时死亡从占领者池移除
     * 并清理驻军槽位；否则仅从防守方弟子池过滤。
     * 返回 (更新后防守者, 更新后占领者或 null, 更新后宗门列表)。
     */
    private fun computeCasualtyUpdates(
        currentGameData: GameData,
        result: AISectAttackManager.AIAttackResult,
        isAiOccupied: Boolean,
        occupierId: String
    ): Triple<List<Disciple>, List<Disciple>?, List<WorldSect>> {
        if (isAiOccupied && result.deadDefenderIds.isNotEmpty()) {
            val occupierDisc = currentGameData
                .aiSectDisciples[occupierId]
                ?: emptyList()
            val filteredOccupier = occupierDisc
                .filter { it.id !in result.deadDefenderIds }
            val clearedGarrisonSects = currentGameData.worldMapSects.map { s ->
                if (s.id == result.defenderSectId) s.copy(
                    garrisonSlots = s.garrisonSlots.map { slot ->
                        if (slot.discipleId in result.deadDefenderIds)
                            GarrisonSlot(index = slot.index) else slot
                    }
                ) else s
            }
            return Triple(
                currentGameData.aiSectDisciples[result.defenderSectId] ?: emptyList(),
                filteredOccupier,
                clearedGarrisonSects
            )
        }
        val defenderDisc = currentGameData
            .aiSectDisciples[result.defenderSectId]
            ?: emptyList()
        return Triple(
            defenderDisc.filter { it.id !in result.deadDefenderIds },
            null,
            currentGameData.worldMapSects
        )
    }

    /**
     * AI 攻占处理：占领成功时更新宗门归属（玩家占领被夺回 / AI 占领者接管）、
     * 驻军槽位与宗门弟子池合并。
     */
    private fun applyAIOccupation(
        updatedData: GameData,
        result: AISectAttackManager.AIAttackResult,
        isPlayerOccupied: Boolean,
        updatedAttacker: List<Disciple>,
        updatedDefender: List<Disciple>
    ): GameData {
        if (result.winner != AIBattleWinner.ATTACKER || !result.canOccupy) return updatedData
        return if (isPlayerOccupied) {
            updatedData.copy(
                worldMapSects = updatedData.worldMapSects.map { s ->
                    if (s.id == result.defenderSectId) s.copy(
                        isPlayerOccupied = false,
                        occupierSectId = result.attackerSectId,
                        garrisonSlots = buildGarrSlots(
                            result.survivingAttackers
                        )
                    ) else s
                }
            )
        } else {
            updatedData.copy(
                worldMapSects = updatedData.worldMapSects.map { s ->
                    if (s.id == result.defenderSectId) s.copy(
                        occupierSectId = result.attackerSectId,
                        garrisonSlots = buildGarrSlots(
                            result.survivingAttackers
                        )
                    ) else s
                },
                aiSectDisciples = updatedData.aiSectDisciples
                    .toMutableMap().apply {
                        this[result.attackerSectId] =
                            updatedAttacker + updatedDefender
                        this[result.defenderSectId] = emptyList()
                    }
            )
        }
    }

    private fun updatePlayerGarrisonState(
        state: MutableGameState,
        result: AISectAttackManager.AIAttackResult,
        tables: DiscipleTables,
        gameYear: Int
    ) {
        if (result.deadDefenderIds.isEmpty() &&
            result.defenderSurvivorHpMap.isEmpty()
        ) return
        val current = tables.assembleAll()
        val updated = current.map { d ->
            if (d.id in result.deadDefenderIds) {
                // 死亡标记由 DiscipleDeathHandler 统一写入列（见下方 markAllDead）
                d
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
        tables.replaceAll(updated)
        // 死亡标记 + deathYears 统一由 DiscipleDeathHandler 写入列
        deathHandler.markAllDead(tables, result.deadDefenderIds.toSet(), gameYear)
    }

    private fun buildGarrSlots(
        survivors: List<Disciple>
    ): List<GarrisonSlot> {
        return (0 until 10).map { i ->
            if (i < survivors.size) {
                val d = survivors[i]
                GarrisonSlot(
                    index = i, discipleId = d.id,
                    discipleName = d.name,
                    discipleRealm = d.realmName,
                    discipleSpiritRootColor = d.spiritRoot.countColor,
                    portraitRes = d.portraitRes
                )
            } else GarrisonSlot(index = i)
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
