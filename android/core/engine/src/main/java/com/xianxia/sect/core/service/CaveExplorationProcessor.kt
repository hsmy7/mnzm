package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.registry.*
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.domain.battle.AIBattleWinner
import com.xianxia.sect.core.engine.domain.battle.AISectAttackManager
import com.xianxia.sect.core.engine.domain.battle.AISectAttackManager.PlayerAttackDecision
import com.xianxia.sect.core.engine.domain.battle.AISectGarrisonManager
import com.xianxia.sect.core.engine.domain.battle.AttackWarningService
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.BattleSystemResult
import com.xianxia.sect.core.engine.domain.battle.Combatant
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.SectWarehouseManager
import com.xianxia.sect.core.engine.domain.exploration.CaveExplorationSystem
import com.xianxia.sect.core.engine.domain.exploration.CaveRewardItem
import com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager
import com.xianxia.sect.core.util.AnalyticsTracker
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.engine.LazyEvaluationDispatcher
import com.xianxia.sect.core.perf.ThermalMonitor
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 洞府探索 completion loop 中 mutable 累加器的容器。
 * 因 completion loop 和 error handler 均需修改 finalCaves/finalAITeams 的引用，
 * 使用 data class + var 字段替代闭包捕获，使提取的 private fun 可修改调用方状态。
 */
private data class CaveCompletionState(
    var finalCaves: List<CultivatorCave>,
    var finalAITeams: List<AICaveTeam>,
    val finalExplorationTeams: MutableList<CaveExplorationTeam>,
    val teamsWithMissingCave: MutableList<CaveExplorationTeam>,
    val teamsWithError: MutableList<CaveExplorationTeam>
)

/**
 * 防守战准备阶段返回的数据容器，支持解构。
 */
private data class DefensePreparation(
    val defenderIds: List<String>,
    val equipmentMap: Map<String, EquipmentInstance>,
    val manualMap: Map<String, ManualInstance>,
    val profMap: Map<String, Map<String, ManualProficiencyData>>,
    val data: GameData
)

@Singleton
@GameService("CaveExplorationProcessor")
class CaveExplorationProcessor @Inject constructor(
    private val stateStore: GameStateStore,
    private val inventorySystem: InventorySystem,
    private val scopeProvider: CoroutineScopeProvider,
    private val battleSystem: BattleSystem,
    private val eventProcessor: CultivationEventProcessor,
    private val analyticsTracker: AnalyticsTracker,
    private val thermalMonitor: ThermalMonitor,
    private val attackWarningService: AttackWarningService,
    private val sectWarehouseManager: SectWarehouseManager,
    private val cultivationService: CultivationService,
    private val spiritStoneWallet: SpiritStoneWallet,
    private val rngManager: GameRngManager
) {
    private val scope get() = scopeProvider.scope
    private val rng get() = rngManager.getRng(RngPartition.EXPLORATION)

    // AI 非焦点域热控分批状态
    private var aiNonFocusedLastSettleMonth: Int = 0
    private var aiNonFocusedBatchMonths: Int = 1

    companion object {
        private const val TAG = "CaveExplorationProc"
        private const val THERMAL_EMERGENCY_BATCH = 12
        private const val THERMAL_REDUCE_BATCH = 6
        private const val THERMAL_NORMAL_BATCH = 1

        /**
         * 从实际参战弟子构建防守战日志的敌人快照列表（纯函数）。
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

    // ── 洞府探索 ──────────────────────────────────────────────────────

    fun processCaveLifecycle(year: Int, month: Int) {
        // Phase 1: 重置过期洞府的探索队伍（在 stateStore.update 外部进行）
        val initialExpiredCaveIds = resetExpiredCaveTeams(year, month)

        // Phase 2: 剩余逻辑在单个 stateStore.update 事务内完成
        stateStore.update {
            val caves = gameData.cultivatorCaves
            val explorationTeams = gameData.caveExplorationTeams
            val sects = gameData.worldMapSects
            val details = gameData.sectDetails

            val activeCaves = caves.filter { cave ->
                !cave.isExpired(year, month) && cave.status != CaveStatus.EXPLORED
            }

            var updatedSectsForAI = sects.toMutableList()
            val updatedSectDetails = details.toMutableMap()

            val teamsToComplete = explorationTeams.filter {
                it.status == CaveExplorationStatus.EXPLORING
            }

            val completionState = CaveCompletionState(
                finalCaves = activeCaves.toList(),
                finalAITeams = gameData.aiCaveTeams,
                finalExplorationTeams = explorationTeams.filter {
                    it.caveId !in initialExpiredCaveIds
                }.toMutableList(),
                teamsWithMissingCave = mutableListOf(),
                teamsWithError = mutableListOf()
            )
            teamsToComplete.forEach { processSingleTeamCompletion(it, completionState) }

            handleExplorationErrors(completionState)

            gameData = gameData.copy(
                cultivatorCaves = completionState.finalCaves,
                caveExplorationTeams = completionState.finalExplorationTeams,
                worldMapSects = updatedSectsForAI,
                sectDetails = updatedSectDetails
            )
        }
    }

    private fun executeCaveExploration(
        team: CaveExplorationTeam,
        cave: CultivatorCave,
        currentAITeams: List<AICaveTeam>
    ): Triple<List<CultivatorCave>, List<AICaveTeam>, Boolean> {
        val teamMembers = assembleTeamMembers(team)
        if (teamMembers.isEmpty()) {
            return handleEmptyTeam(cave, currentAITeams)
        }

        val data = stateStore.gameData.value
        val equipmentMap = stateStore.equipmentInstances.value.associateBy { it.id }
        val manualMap = stateStore.manualInstances.value.associateBy { it.id }
        val allProficiencies = data.manualProficiencies.mapValues { (_, list) ->
            list.associateBy { it.manualId }
        }
        // 移除 AI 队伍战斗 — 玩家团队直接对战守护兽
        val battleResult = executeBattleForTeam(
            teamMembers, equipmentMap, manualMap, allProficiencies,
            cave
        )

        val deadDisciples = processBattleCasualties(team, battleResult)
        deadDisciples.forEach { eventProcessor.handleDiscipleDeath(
            it, isOutsideSect = true
        ) }

        if (!battleResult.victory) {
            val updatedAITeams = currentAITeams.filter { it.caveId != cave.id }
            return Triple(stateStore.gameData.value.cultivatorCaves, updatedAITeams, true)
        }

        val victorMembers = battleResult.log.teamMembers.filter { it.isAlive }
        val survivorIds = victorMembers.map { it.id }.toSet()
        awardVictorySoulPower(survivorIds)

        val battleRewardItems = grantBattleRewards(cave)
        val battleLog = buildAndStoreBattleLog(
            data, team, cave, battleResult, battleRewardItems
        )
        stateStore.setPendingBattleResult(BattleResultUIData(
            battleLogId = battleLog.id,
            victory = battleResult.victory,
            teamMembers = battleLog.teamMembers,
            rewards = battleRewardItems
        ))
        trackBattleAnalytics(battleResult, cave)

        return cleanupAfterCaveExploration(cave, currentAITeams)
    }

    /** 执行洞府战斗：玩家团队 vs 守护妖兽 */
    private fun executeBattleForTeam(
        teamMembers: List<Disciple>,
        equipmentMap: Map<String, EquipmentInstance>,
        manualMap: Map<String, ManualInstance>,
        allProficiencies: Map<String, Map<String, ManualProficiencyData>>,
        cave: CultivatorCave
    ): BattleSystemResult {
        return battleSystem.executeBattle(
            CaveExplorationSystem.createGuardianBattle(
                playerDisciples = teamMembers,
                playerEquipmentMap = equipmentMap,
                playerManualMap = manualMap,
                playerManualProficiencies = allProficiencies,
                cave = cave
            )
        )
    }

    fun findNearbySects(cave: CultivatorCave, range: Float): List<WorldSect> {
        val data = stateStore.gameData.value
        return data.worldMapSects.filter { sect ->
            !sect.isPlayerSect &&
            kotlin.math.sqrt(
                (cave.x - sect.x) * (cave.x - sect.x) +
                (cave.y - sect.y) * (cave.y - sect.y)
            ) <= range
        }
    }

    fun resetCaveExplorationTeamMembersStatus(team: CaveExplorationTeam) {
        val memberIds = team.memberIds.toList()
        stateStore.update {
            val idsToReset = memberIds.filter { memberId ->
                val d = discipleTables.assembleAll().find { it.id == memberId }
                d != null && d.status == DiscipleStatus.IN_TEAM
            }
            if (idsToReset.isNotEmpty()) {
                val newList = discipleTables.assembleAll().map {
                    if (it.id in idsToReset) it.copy(status = DiscipleStatus.IDLE) else it
                }
                discipleTables.replaceAll(newList)
            }
        }
    }

    // ── AI 宗门 ──────────────────────────────────────────────────────

    /**
     * AI 弟子热控分批：根据手机发热程度决定结算间隔。
     * - 常温 → 每月结算
     * - 发热(shouldReduceWorkload) → 每 6 月结算一次
     * - 发热严重(shouldEmergencySave) → 每 12 月结算一次
     */
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

        // AI 弟子修炼（热控分批：跳过时保留原数据）
        val updatedAiDisciples = if (aiNonFocusedBatchMonths > 0) {
            aiDisciples.mapValues { (sectId, disciples) ->
                val sect = data.worldMapSects.find { it.id == sectId }
                if (sect == null || sect.isPlayerSect) return@mapValues disciples
                AISectDiscipleManager.processMonthlyCultivation(
                    disciples, aiNonFocusedBatchMonths
                )
            }
        } else {
            aiDisciples
        }

        // 同步 AI 宗门等级 — 月度修炼弟子只会变强，仅用 any{} 短路检查升级（只升不降）
        // 玩家宗门等级由玩家手动升级（通过 SectLevelDetailDialog），此处跳过
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
                    sect.copy(level = newLevel, levelName = SectLevel.levelName(newLevel))
                } else {
                    sect
                }
            }
        }

        state.gameData = state.gameData.copy(
            sectDetails = cleanedSectDetails,
            aiSectDisciples = state.gameData.aiSectDisciples.mapValues { (sId, current) ->
                val calculated = updatedAiDisciples[sId] ?: return@mapValues current
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

        // 构建玩家占领宗门防御信息
        val allDisciples = stateStore.discipleTables.assembleAll()
        val equipmentMap = stateStore.equipmentInstancesSnapshot
            .associateBy { it.id }
        val manualMap = stateStore.manualInstancesSnapshot
            .associateBy { it.id }
        val profMap = data.manualProficiencies.mapValues { (_, list) ->
            list.associateBy { it.manualId }
        }

        val playerDefenders = if (playerSectId != null) {
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
                            CombatantSide.DEFENDER
                        )
                    }
                    sect.id to AISectAttackManager.PlayerOccupiedDefenseInfo(
                        disciples = garrisoned,
                        combatants = combatants
                    )
                }
        } else emptyMap()

        val results = AISectAttackManager.decideAttacks(data, playerDefenders)
        if (results.isEmpty()) return

        for (result in results) {
            stateStore.update {
                val currentGameData = gameData
                val defenderSect = currentGameData.worldMapSects
                    .find { it.id == result.defenderSectId }
                val isPlayerOccupied = defenderSect
                    ?.isPlayerOccupied == true

                // 玩家占领宗门防御：更新驻军弟子状态
                if (isPlayerOccupied) {
                    updatePlayerGarrisonState(
                        result, discipleTables, data.gameYear
                    )
                }

                // 过滤阵亡弟子
                val attackerDisc = currentGameData
                    .aiSectDisciples[result.attackerSectId]
                    ?: emptyList()
                val updatedAttacker = attackerDisc
                    .filter { it.id !in result.deadAttackerIds }

                // 被AI占领宗门：防守方驻军来自占领者池，死亡应从占领者池移除
                val isAiOccupied = defenderSect != null &&
                    defenderSect.occupierSectId.isNotEmpty() &&
                    !isPlayerOccupied
                val occupierId = defenderSect?.occupierSectId ?: ""
                val (updatedDefender, updatedOccupier, updatedSects) =
                    if (isAiOccupied && result.deadDefenderIds.isNotEmpty()) {
                        val occupierDisc = currentGameData
                            .aiSectDisciples[occupierId]
                            ?: emptyList()
                        val filteredOccupier = occupierDisc
                            .filter { it.id !in result.deadDefenderIds }
                        val clearedGarrisonSects = gameData.worldMapSects.map { s ->
                            if (s.id == result.defenderSectId) s.copy(
                                garrisonSlots = s.garrisonSlots.map { slot ->
                                    if (slot.discipleId in result.deadDefenderIds)
                                        GarrisonSlot(index = slot.index) else slot
                                }
                            ) else s
                        }
                        Triple(
                            currentGameData.aiSectDisciples[result.defenderSectId] ?: emptyList(),
                            filteredOccupier,
                            clearedGarrisonSects
                        )
                    } else {
                        val defenderDisc = currentGameData
                            .aiSectDisciples[result.defenderSectId]
                            ?: emptyList()
                        Triple(
                            defenderDisc.filter { it.id !in result.deadDefenderIds },
                            null,
                            gameData.worldMapSects
                        )
                    }

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
                    sectRelations = gameData.sectRelations.map { r ->
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
                )

                // 占领处理
                if (result.winner == AIBattleWinner.ATTACKER &&
                    result.canOccupy
                ) {
                    updatedData = if (isPlayerOccupied) {
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

                gameData = updatedData
            }
        }
    }

    private fun updatePlayerGarrisonState(
        result: AISectAttackManager.AIAttackResult,
        tables: DiscipleTables,
        gameYear: Int
    ) {
        if (result.deadDefenderIds.isEmpty() &&
            result.defenderSurvivorHpMap.isEmpty()
        ) return
        val current = tables.assembleAll()
        val updated = current.map { d ->
            when {
                d.id in result.deadDefenderIds -> d.copy(
                    isAlive = false, status = DiscipleStatus.DEAD
                )
                else -> {
                    val hp = result.defenderSurvivorHpMap[d.id]
                    val mp = result.defenderSurvivorMpMap[d.id]
                    if (hp != null && mp != null) d.copy(
                        combat = d.combat.copy(
                            currentHp = hp.coerceIn(0, d.maxHp),
                            currentMp = mp.coerceIn(0, d.maxMp)
                        )
                    ) else d
                }
            }
        }
        tables.replaceAll(updated)
        // 为阵亡弟子补充 deathYears
        updated.filter { !it.isAlive }.forEach {
            val idInt = it.id.toIntOrNull()
            if (idInt != null && !tables.deathYears.contains(idInt)) {
                tables.deathYears[idInt] = gameYear
            }
        }
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

    fun processSectDisciplesYearlyRecruitment(year: Int, state: MutableGameState) {
        val data = state.gameData
        var updatedAiDisciples = data.aiSectDisciples.toMutableMap()
        var updatedRecruitList = data.recruitList

        for ((sectId, disciples) in data.aiSectDisciples) {
            val sect = data.worldMapSects.find { it.id == sectId } ?: continue
            if (sect.isPlayerSect) continue

            val newRecruits = AISectDiscipleManager.generateYearlyRecruits(sect.name, disciples)
            when {
                sect.isPlayerOccupied -> {
                    updatedRecruitList = updatedRecruitList + newRecruits
                }
                sect.occupierSectId.isNotEmpty() -> {
                    val occupierDisciples = updatedAiDisciples[sect.occupierSectId] ?: emptyList()
                    updatedAiDisciples[sect.occupierSectId] =
                        AISectDiscipleManager.truncateToLimit(occupierDisciples + newRecruits)
                }
                else -> {
                    updatedAiDisciples[sectId] =
                        AISectDiscipleManager.truncateToLimit(disciples + newRecruits)
                }
            }
        }
        // 直接基于事务 buffer 写回：年变单事务内前序事件（如 refreshRecruitList）
        // 对 buffer 的修改必须保留，禁止读已提交快照覆盖（招募列表不刷新 MNG 修复）。
        state.gameData = state.gameData.copy(
            aiSectDisciples = updatedAiDisciples,
            recruitList = updatedRecruitList
        )
        // 被占领AI宗门产生新弟子后立即执行自动招募检查 + 重置惰性
        RecruitService.RecruitLazyState.autoRecruitIdle = false
        RecruitService.RecruitLazyState.autoRejectIdle = false
        RecruitService.processAutoRecruit(state)
    }

    fun processSectDisciplesAging(year: Int, state: MutableGameState) {
        val data = state.gameData
        val updatedAiDisciples = data.aiSectDisciples.mapValues { (sectId, disciples) ->
            val sect = data.worldMapSects.find { it.id == sectId }
            if (sect == null || sect.isPlayerSect) return@mapValues disciples
            AISectDiscipleManager.processAging(disciples)
        }
        // 年度老化仅修改年龄，不改变境界，无需同步宗门等级。
        // 基于事务 buffer 写回，保留同事务前序事件对 aiSectDisciples 的修改
        // （禁止读已提交快照覆盖，招募列表不刷新 MNG 修复）。
        state.gameData = state.gameData.copy(aiSectDisciples = updatedAiDisciples)
    }

    // ── processCaveLifecycle 辅助方法 ──────────────────────────────────

    private fun resetExpiredCaveTeams(year: Int, month: Int): Set<String> {
        val initialExpiredCaveIds = stateStore.gameData.value.cultivatorCaves.filter { cave ->
            cave.isExpired(year, month) || cave.status == CaveStatus.EXPLORED
        }.map { it.id }.toSet()
        initialExpiredCaveIds.forEach { caveId ->
            val affectedTeams = stateStore.gameData.value.caveExplorationTeams.filter {
                it.caveId == caveId && it.status == CaveExplorationStatus.TRAVELING
            }
            affectedTeams.forEach { team ->
                resetCaveExplorationTeamMembersStatus(team)
            }
        }
        return initialExpiredCaveIds
    }

    private fun processSingleTeamCompletion(
        team: CaveExplorationTeam,
        state: CaveCompletionState
    ) {
        val cave = state.finalCaves.find { it.id == team.caveId }
        if (cave == null) {
            state.teamsWithMissingCave.add(team)
            return
        }
        try {
            val result = executeCaveExploration(team, cave, state.finalAITeams)
            state.finalCaves = result.first
            state.finalAITeams = result.second
            if (result.third) {
                state.finalExplorationTeams.removeAll { it.id == team.id }
            }
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) {
            DomainLog.e(TAG, "Error processing cave exploration for team ${team.id}", e)
            state.teamsWithError.add(team)
        }
    }

    private fun handleExplorationErrors(state: CaveCompletionState) {
        state.teamsWithMissingCave.forEach { team ->
            resetCaveExplorationTeamMembersStatus(team)
            state.finalExplorationTeams.removeAll { it.id == team.id }
        }
        state.teamsWithError.forEach { team ->
            resetCaveExplorationTeamMembersStatus(team)
            state.finalCaves = state.finalCaves.map { cave ->
                if (cave.id == team.caveId && cave.status == CaveStatus.EXPLORING) {
                    cave.copy(status = CaveStatus.AVAILABLE)
                } else {
                    cave
                }
            }
            state.finalExplorationTeams.removeAll { it.id == team.id }
        }
    }

    // ── executeCaveExploration 辅助方法 ─────────────────────────────────

    private fun assembleTeamMembers(team: CaveExplorationTeam): List<Disciple> {
        return team.memberIds.mapNotNull { id ->
            stateStore.disciples.value.find { it.id == id }
        }.filter { it.isAlive }
    }

    private fun handleEmptyTeam(
        cave: CultivatorCave,
        currentAITeams: List<AICaveTeam>
    ): Triple<List<CultivatorCave>, List<AICaveTeam>, Boolean> {
        return Triple(
            stateStore.gameData.value.cultivatorCaves,
            currentAITeams.filter { it.caveId != cave.id },
            true
        )
    }

    private fun processBattleCasualties(
        team: CaveExplorationTeam,
        battleResult: com.xianxia.sect.core.engine.domain.battle.BattleSystemResult
    ): List<Disciple> {
        val survivorIds = battleResult.log.teamMembers.filter { it.isAlive }.map { it.id }.toSet()
        val survivorHpMap = battleResult.log.teamMembers.filter { it.isAlive }
            .associate { it.id to it.hp }
        val survivorMpMap = battleResult.log.teamMembers.filter { it.isAlive }
            .associate { it.id to it.mp }
        val deadDisciples = mutableListOf<Disciple>()
        stateStore.update {
            val newList = discipleTables.assembleAll().map { disciple ->
                if (disciple.id in team.memberIds) {
                    if (disciple.id in survivorIds) {
                        val hp = survivorHpMap[disciple.id] ?: disciple.combat.currentHp
                        val mp = survivorMpMap[disciple.id] ?: disciple.combat.currentMp
                        disciple.copy(
                            status = DiscipleStatus.IDLE,
                            combat = disciple.combat.copy(currentHp = hp, currentMp = mp)
                        )
                    } else {
                        deadDisciples.add(disciple)
                        disciple.copy(isAlive = false, status = DiscipleStatus.DEAD)
                    }
                } else disciple
            }
            discipleTables.replaceAll(newList)
            val caveYear = stateStore.gameData.value.gameYear
            newList.filter { !it.isAlive }.forEach {
                val idInt = it.id.toIntOrNull()
                if (idInt != null && !discipleTables.deathYears.contains(idInt)) {
                    discipleTables.deathYears[idInt] = caveYear
                }
            }
        }
        return deadDisciples
    }

    private fun awardVictorySoulPower(survivorIds: Set<String>) {
        stateStore.update {
            val newList = discipleTables.assembleAll().map { disciple ->
                if (disciple.id in survivorIds && disciple.isAlive) {
                    disciple.copy(soulPower = disciple.soulPower + 1)
                } else {
                    disciple
                }
            }
            discipleTables.replaceAll(newList)
        }
    }

    private fun grantBattleRewards(cave: CultivatorCave): List<BattleRewardItem> {
        val rewards = CaveExplorationSystem.generateVictoryRewards(cave)
        val battleRewardItems = mutableListOf<BattleRewardItem>()
        rewards.items.forEach { reward ->
            when (reward.type) {
                "spiritStones" -> grantSpiritStoneReward(reward, battleRewardItems)
                "equipment" -> grantEquipmentReward(reward, battleRewardItems)
                "manual" -> grantManualReward(reward, battleRewardItems)
                "pill" -> grantPillReward(reward, battleRewardItems)
            }
        }
        return battleRewardItems
    }

    private fun grantSpiritStoneReward(
        reward: CaveRewardItem,
        battleRewardItems: MutableList<BattleRewardItem>
    ) {
        stateStore.update { spiritStoneWallet.add(this,
            amount = reward.quantity.toLong(),
            grade = SpiritStoneGrade.LOW,
            source = SpiritStoneSource.Cave
        ) }
        battleRewardItems.add(BattleRewardItem(
            itemId = reward.itemId,
            name = reward.name,
            quantity = reward.quantity,
            rarity = reward.rarity,
            type = reward.type
        ))
    }

    private fun grantEquipmentReward(
        reward: CaveRewardItem,
        battleRewardItems: MutableList<BattleRewardItem>
    ) {
        val template = EquipmentDatabase.getById(reward.itemId)
        if (template != null) {
            val equipment = EquipmentDatabase.createFromTemplate(template).copy(
                rarity = reward.rarity,
                quantity = reward.quantity
            )
            val result = inventorySystem.withTrackingSource("cave") { inventorySystem.addEquipmentStack(equipment) }
            when (val r = result) {
                is DomainResult.Success -> battleRewardItems.add(BattleRewardItem(
                    itemId = reward.itemId,
                    name = reward.name,
                    quantity = reward.quantity,
                    rarity = reward.rarity,
                    type = reward.type
                ))
                is DomainResult.Partial -> {
                    battleRewardItems.add(BattleRewardItem(
                        itemId = reward.itemId,
                        name = reward.name,
                        quantity = reward.quantity,
                        rarity = reward.rarity,
                        type = reward.type
                    ))
                    DomainLog.w(TAG, "${reward.name} 溢出 ${r.overflow} 个")
                }
                is DomainResult.Failure -> DomainLog.w(TAG, "装备添加失败: ${r.error}")
            }
        }
    }

    private fun grantManualReward(
        reward: CaveRewardItem,
        battleRewardItems: MutableList<BattleRewardItem>
    ) {
        val template = ManualDatabase.getById(reward.itemId)
        if (template != null) {
            val manual = ManualDatabase.createFromTemplate(template).copy(
                rarity = reward.rarity,
                quantity = reward.quantity
            )
            val result = inventorySystem.addManualStack(manual)
            if (result.isSuccess) {
                battleRewardItems.add(BattleRewardItem(
                    itemId = reward.itemId,
                    name = reward.name,
                    quantity = reward.quantity,
                    rarity = reward.rarity,
                    type = reward.type
                ))
            }
        }
    }

    private fun grantPillReward(
        reward: CaveRewardItem,
        battleRewardItems: MutableList<BattleRewardItem>
    ) {
        val template = PillRecipeDatabase.getRecipeById(reward.itemId)
        if (template != null) {
            val pill = Pill(
                id = java.util.UUID.randomUUID().toString(),
                name = template.name,
                rarity = template.rarity,
                quantity = reward.quantity,
                description = template.description,
                category = template.category,
                effects = PillEffect(
                    breakthroughChance = template.breakthroughChance,
                    targetRealm = template.targetRealm,
                    cultivationSpeedPercent = template.cultivationSpeedPercent,
                    duration = template.duration,
                    cultivationAdd = template.cultivationAdd,
                    skillExpAdd = template.skillExpAdd,
                    nurtureAdd = template.nurtureAdd,
                    extendLife = template.extendLife,
                    physicalAttackAdd = template.physicalAttackAdd,
                    magicAttackAdd = template.magicAttackAdd,
                    physicalDefenseAdd = template.physicalDefenseAdd,
                    magicDefenseAdd = template.magicDefenseAdd,
                    hpAdd = template.hpAdd,
                    mpAdd = template.mpAdd,
                    speedAdd = template.speedAdd,
                    critRateAdd = template.critRateAdd,
                    critEffectAdd = template.critEffectAdd,
                    intelligenceAdd = template.intelligenceAdd,
                    charmAdd = template.charmAdd,
                    loyaltyAdd = template.loyaltyAdd,
                    comprehensionAdd = template.comprehensionAdd,
                    artifactRefiningAdd = template.artifactRefiningAdd,
                    pillRefiningAdd = template.pillRefiningAdd,
                    spiritPlantingAdd = template.spiritPlantingAdd,
                    teachingAdd = template.teachingAdd,
                    moralityAdd = template.moralityAdd
                ),
                minRealm = GameConfig.Realm.getMinRealmForRarity(template.rarity)
            )
            val result = inventorySystem.withTrackingSource("cave") { inventorySystem.addPill(pill) }
            when (val r = result) {
                is DomainResult.Success -> battleRewardItems.add(BattleRewardItem(
                    itemId = reward.itemId,
                    name = reward.name,
                    quantity = reward.quantity,
                    rarity = reward.rarity,
                    type = reward.type
                ))
                is DomainResult.Partial -> {
                    battleRewardItems.add(BattleRewardItem(
                        itemId = reward.itemId,
                        name = reward.name,
                        quantity = reward.quantity,
                        rarity = reward.rarity,
                        type = reward.type
                    ))
                    DomainLog.w(TAG, "${reward.name} 溢出 ${r.overflow} 个")
                }
                is DomainResult.Failure -> DomainLog.w(TAG, "丹药添加失败: ${r.error}")
            }
        }
    }

    private fun buildAndStoreBattleLog(
        data: GameData,
        team: CaveExplorationTeam,
        cave: CultivatorCave,
        battleResult: com.xianxia.sect.core.engine.domain.battle.BattleSystemResult,
        battleRewardItems: List<BattleRewardItem>
    ): BattleLog {
        val battleLog = BattleLog(
            timestamp = System.currentTimeMillis(),
            year = data.gameYear,
            month = data.gameMonth,
            type = BattleType.CAVE_EXPLORATION,
            attackerName = team.caveName,
            defenderName = cave.name,
            result = BattleResult.WIN,
            details = "洞府探索",
            dungeonName = cave.name,
            teamId = team.id,
            teamMembers = battleResult.log.teamMembers.map { member ->
                BattleLogMember(
                    id = member.id, name = member.name,
                    realm = member.realm, realmName = member.realmName,
                    hp = member.hp, maxHp = member.maxHp,
                    mp = member.mp, maxMp = member.maxMp,
                    isAlive = member.isAlive, portraitRes = member.portraitRes
                )
            },
            enemies = battleResult.log.enemies.map { enemy ->
                BattleLogEnemy(
                    id = enemy.id, name = "守护兽",
                    realm = enemy.realm, realmName = enemy.realmName,
                    realmLayer = enemy.realmLayer,
                    hp = enemy.hp, maxHp = enemy.maxHp,
                    isAlive = enemy.isAlive, portraitRes = enemy.portraitRes
                )
            },
            rounds = battleResult.log.rounds.map { round ->
                BattleLogRound(
                    roundNumber = round.roundNumber,
                    actions = round.actions.map { action ->
                        BattleLogAction(
                            type = action.type, attacker = action.attacker,
                            attackerType = action.attackerType, target = action.target,
                            damage = action.damage, damageType = action.damageType,
                            isCrit = action.isCrit, isKill = action.isKill,
                            message = action.message, skillName = action.skillName
                        )
                    }
                )
            },
            turns = battleResult.turnCount,
            battleResult = BattleLogResult(
                winner = if (battleResult.victory) "team" else "beasts",
                isPlayerWin = battleResult.victory,
                turns = battleResult.turnCount,
                rounds = battleResult.log.rounds.size,
                teamCasualties = battleResult.log.teamMembers.count { !it.isAlive },
                beastsDefeated = battleResult.log.enemies.count { !it.isAlive }
            )
        )
        stateStore.update { battleLogs = listOf(battleLog) + battleLogs.take(49) }
        return battleLog
    }

    private fun trackBattleAnalytics(
        battleResult: com.xianxia.sect.core.engine.domain.battle.BattleSystemResult,
        cave: CultivatorCave
    ) {
        analyticsTracker.trackEvent(
            "battle_end",
            mapOf(
                "outcome" to if (battleResult.victory) "win" else "lose",
                "enemy_type" to cave.name,
                "turns" to battleResult.turnCount,
                "team_size" to battleResult.log.teamMembers.size
            )
        )
    }

    private fun cleanupAfterCaveExploration(
        cave: CultivatorCave,
        currentAITeams: List<AICaveTeam>
    ): Triple<List<CultivatorCave>, List<AICaveTeam>, Boolean> {
        val updatedCaves = stateStore.gameData.value.cultivatorCaves.map { c ->
            if (c.id == cave.id) c.copy(status = CaveStatus.EXPLORED) else c
        }
        val updatedAITeams = currentAITeams.filter { it.caveId != cave.id }
        return Triple(updatedCaves, updatedAITeams, true)
    }

    // ── executePlayerDefenseBattle 辅助方法 ─────────────────────────────

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
                preparation.profMap, CombatantSide.DEFENDER
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
                if (hp != null && mp != null) d.copy(
                    combat = d.combat.copy(
                        currentHp = hp.coerceIn(0, d.maxHp),
                        currentMp = mp.coerceIn(0, d.maxMp)
                    )
                ) else d
            }
        }
        // 为阵亡弟子补充 deathYears
        newDisciples.filter { !it.isAlive }.forEach {
            val idInt = it.id.toIntOrNull()
            if (idInt != null && !state.discipleTables.deathYears.contains(idInt)) {
                state.discipleTables.deathYears[idInt] = state.gameData.gameYear
            }
        }
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
