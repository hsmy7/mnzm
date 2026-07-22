package com.xianxia.sect.core.exploration

import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.engine.domain.battle.Battle
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.BattleSystemResult
import com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.BattleLogAction
import com.xianxia.sect.core.model.BattleLogEnemy
import com.xianxia.sect.core.model.BattleLogMember
import com.xianxia.sect.core.model.BattleLogRound
import com.xianxia.sect.core.model.BattleResult
import com.xianxia.sect.core.model.BattleRewardItem
import com.xianxia.sect.core.model.BattleType
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.LevelType
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.PatrolConfig
import com.xianxia.sect.core.model.PatrolSlot
import com.xianxia.sect.core.model.WorldLevel
import com.xianxia.sect.core.model.guide.GuideCounterKeys
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.state.BattleResultUIData
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.exploration.DiscipleDeathHandler
import com.xianxia.sect.core.util.RngPartition
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// ═══════════════════════════════════════════════════════════════════════════════
// 数据类
// ═══════════════════════════════════════════════════════════════════════════════

data class TowerTeam(
    val towerIndex: Int,
    val disciples: List<Disciple>,
    val slots: List<PatrolSlot>
)

data class TowerBattleResult(
    val towerIndex: Int,
    val target: WorldLevel,
    val victory: Boolean,
    val result: BattleSystemResult,
    val survivors: Set<String>,
    val deadIds: Set<String>
)

data class BattleDisciplesUpdate(
    val disciples: List<Disciple>,
    val deadIds: Set<String>
)

// ═══════════════════════════════════════════════════════════════════════════════
// 巡逻战斗系统 — 从 ExplorationService.processPatrolAttacks 提取，拆为 4 步
// ═══════════════════════════════════════════════════════════════════════════════

@Singleton
class PatrolBattleSystem @Inject constructor(
    private val battleSystem: BattleSystem,
    private val rngManager: GameRngManager,
    private val inventorySystem: InventorySystem,
    private val buildingConfigService: BuildingConfigService,
    private val deathHandler: DiscipleDeathHandler,
    private val encounterBattleService: com.xianxia.sect.core.domain.battle.EncounterBattleService
) {
    companion object {
        private const val TAG = "PatrolBattleSystem"
        private const val WIN_BATTLE_ATTR_COUNT = 17
        private const val MAX_RANDOM_MATERIAL_DROPS = 3
    }

    private val _pendingPatrolResults = mutableListOf<BattleResultUIData>()

    fun consumePendingPatrolResults(): List<BattleResultUIData> {
        val results = _pendingPatrolResults.toList()
        _pendingPatrolResults.clear()
        return results
    }

    // ── 入口：四步流程 ─────────────────────────────────────────────────────

    /**
     * 执行一轮巡逻战斗。调用方确保 [state] 已在 [stateStore.update] 事务内。
     *
     * 1. buildTowerTeams — 从巡视楼 + 巡逻槽位构建队伍
     * 2. assignTargets  — 为每支队伍分配妖兽目标
     * 3. executeBattles — 创建并执行战斗
     * 4. applyResults   — 结算战斗结果（弟子状态/奖励/日志/槽位清理）
     */
    fun executePatrolRound(state: MutableGameState) {
        val gd = state.gameData
        if (gd.patrolSlots.isEmpty()) return

        val disciples = state.discipleTables.assembleAll()
        val teams = buildTowerTeams(gd, disciples)
        if (teams.isEmpty()) return

        val claimedBeasts = mutableSetOf<String>()
        val targets = assignTargets(teams, gd, claimedBeasts)
        if (targets.isEmpty()) return

        val equipmentMap = state.equipmentInstances.associateBy { it.id }
        val manualMap = state.manualInstances.associateBy { it.id }
        val allProficiencies = gd.manualProficiencies.mapValues { (_, list) ->
            list.associateBy { it.manualId }
        }

        // 分离被 AI 同时瞄准的妖兽（冲突目标）和纯巡逻目标
        val aiDirectTargets = gd.aiSectBeastDirectTargets
        val conflictTargets = targets.filterValues { it.id in aiDirectTargets }
        val normalTargets = targets.filterValues { it.id !in aiDirectTargets }

        val allResults = mutableListOf<TowerBattleResult>()

        // 处理无冲突的巡逻目标（正常 PvE）
        if (normalTargets.isNotEmpty()) {
            val normalTeams = teams.filter { it.towerIndex in normalTargets.keys }
            allResults += executeBattles(
                normalTeams, normalTargets, equipmentMap, manualMap, allProficiencies
            )
        }

        // 处理冲突目标：巡逻队 vs AI → 胜者 vs 妖兽
        if (conflictTargets.isNotEmpty()) {
            val conflictTeams = teams.filter { it.towerIndex in conflictTargets.keys }
            allResults += executeConflictBattles(
                conflictTeams, conflictTargets, aiDirectTargets,
                equipmentMap, manualMap, allProficiencies, state
            )
            // 从 AI 目标中移除已处理的冲突妖兽
            val handledIds = conflictTargets.values.map { it.id }.toSet()
            val remaining = gd.aiSectBeastDirectTargets - handledIds
            if (remaining.size != gd.aiSectBeastDirectTargets.size) {
                state.gameData = state.gameData.copy(aiSectBeastDirectTargets = remaining)
            }
        }

        if (allResults.isEmpty()) return
        applyResults(allResults, state, gd, disciples)
    }

    // ── 步骤 1: 构建巡逻队伍 ──────────────────────────────────────────────

    private fun buildTowerTeams(gd: GameData, disciples: List<Disciple>): List<TowerTeam> {
        val allSlots = gd.patrolSlots
        val configs = gd.patrolConfigs
        val towerBuildings = gd.placedBuildings.filter { it.displayName == "巡视楼" }
        val slotsPerTower = buildingConfigService.getSlotCountByDisplayName("巡视楼")

        return towerBuildings.mapIndexedNotNull { towerIndex, tower ->
            val config = configs.getOrElse(towerIndex) { PatrolConfig() }

            val towerSlots = if (tower.instanceId.isNotEmpty()) {
                allSlots.filter {
                    it.buildingInstanceId == tower.instanceId && it.discipleId.isNotEmpty()
                }
            } else {
                val start = towerIndex * slotsPerTower
                if (start >= allSlots.size) return@mapIndexedNotNull null
                val end = (start + slotsPerTower).coerceAtMost(allSlots.size)
                allSlots.subList(start, end).filter { it.discipleId.isNotEmpty() }
            }
            if (towerSlots.isEmpty()) return@mapIndexedNotNull null

            val towerDiscipleIds = towerSlots.map { it.discipleId }.toSet()
            val towerDisciples = disciples.filter {
                it.id in towerDiscipleIds && it.isAlive
            }
            if (towerDisciples.isEmpty()) return@mapIndexedNotNull null

            if (config.requireFullStatus) {
                val anyNotFull = towerDisciples.any {
                    it.combat.currentHp < it.maxHp || it.combat.currentMp < it.maxMp
                }
                if (anyNotFull) return@mapIndexedNotNull null
            }

            TowerTeam(towerIndex, towerDisciples, towerSlots)
        }
    }

    // ── 步骤 2: 分配妖兽目标 ──────────────────────────────────────────────

    private fun assignTargets(
        teams: List<TowerTeam>,
        gd: GameData,
        claimedBeasts: MutableSet<String>
    ): Map<Int, WorldLevel> {
        val year = gd.gameYear; val month = gd.gameMonth
        val result = mutableMapOf<Int, WorldLevel>()

        for (team in teams) {
            val config = gd.patrolConfigs.getOrElse(team.towerIndex) { PatrolConfig() }
            val teamAvgRealm = if (team.disciples.isNotEmpty()) {
                team.disciples.map { it.realm }.average()
            } else continue

            // 当 targetRealms 仍为默认 {9}（炼气）时，动态扩展为 [队伍平均境界, 9]（不限制上限）
            val effectiveTargetRealms = if (config.targetRealms == setOf(9)) {
                (teamAvgRealm.toInt().coerceAtMost(9)..9).toSet()
            } else {
                config.targetRealms
            }

            val target = gd.worldLevels.firstOrNull {
                it.type == LevelType.BEAST &&
                    !it.defeated &&
                    !it.checkExpired(year, month) &&
                    it.realm in effectiveTargetRealms &&
                    it.count <= config.maxBeastCount &&
                    it.id !in claimedBeasts &&
                    // 安全兜底：妖兽境界不超过队伍平均 +1 级
                    it.realm <= (teamAvgRealm.toInt() + 1).coerceAtMost(9)
            } ?: continue

            claimedBeasts.add(target.id)
            result[team.towerIndex] = target
        }
        return result
    }

    // ── 步骤 3b: 执行冲突战斗（巡逻队 vs AI → 胜者 vs 妖兽） ──────────

    /**
     * 处理巡逻队和 AI 同时瞄准同一妖兽的冲突。
     * 巡逻队 vs AI PvP → 胜者的幸存弟子 vs 妖兽 PvE。
     * AI 弟子使用 [com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager.generateBattleItems] 生成模拟装备/功法。
     */
    private fun executeConflictBattles(
        teams: List<TowerTeam>,
        targets: Map<Int, WorldLevel>,
        aiDirectTargets: Map<String, List<String>>,
        equipmentMap: Map<String, EquipmentInstance>,
        manualMap: Map<String, ManualInstance>,
        allProficiencies: Map<String, Map<String, ManualProficiencyData>>,
        state: MutableGameState
    ): List<TowerBattleResult> {
        return teams.mapNotNull { team ->
            val target = targets[team.towerIndex] ?: return@mapNotNull null
            // 冲突时只取最近的 1 个 AI（第 2 个直接清理）
            val aiSectIds = aiDirectTargets[target.id] ?: return@mapNotNull null
            if (aiSectIds.isEmpty()) return@mapNotNull null
            val aiSectId = aiSectIds[0]
            val aiSect = state.gameData.worldMapSects.find { it.id == aiSectId } ?: return@mapNotNull null
            val aiDisciples = state.gameData.aiSectDisciples[aiSectId]
                ?.filter { it.isAlive }
                ?.take(GameConfig.AI.TEAM_SIZE) ?: return@mapNotNull null
            if (aiDisciples.isEmpty()) return@mapNotNull null

            // Phase 1: 巡逻队 vs AI（PvP）
            // 双方分别构建 Combatant → Battle(team=巡逻队, beasts=AI队伍)
            val aiPrepared = AISectDiscipleManager.prepareDisciplesForBattle(aiDisciples)
            val patrolCombatants = team.disciples.map { disciple ->
                battleSystem.convertDiscipleToCombatant(
                    disciple = disciple,
                    equipmentMap = equipmentMap,
                    manualMap = manualMap,
                    manualProficiencies = allProficiencies,
                    side = CombatantSide.DEFENDER,
                    fullHeal = true
                )
            }
            val aiCombatants = aiPrepared.disciples.map { disciple ->
                battleSystem.convertDiscipleToCombatant(
                    disciple = disciple,
                    equipmentMap = aiPrepared.equipmentMap,
                    manualMap = aiPrepared.manualMap,
                    manualProficiencies = aiPrepared.proficiencies,
                    side = CombatantSide.ATTACKER,
                    fullHeal = true
                )
            }
            val pvpBattle = Battle(
                team = patrolCombatants,
                beasts = aiCombatants,
                maxTurns = GameConfig.Battle.MAX_TURNS
            )
            val pvpResult = battleSystem.executeBattle(pvpBattle)

            // 收集 Phase 1 幸存者
            val patrolSurvivors = pvpResult.battle.team.filter { !it.isDead }
            val aiSurvivors = pvpResult.battle.beasts.filter { !it.isDead }
            val patrolDead = pvpResult.battle.team.filter { it.isDead }.map { it.id }.toSet()
            val aiDead = pvpResult.battle.beasts.filter { it.isDead }.map { it.id }.toSet()

            // 标记 AI 阵亡
            if (aiDead.isNotEmpty()) {
                val updatedAi = state.gameData.aiSectDisciples[aiSectId]?.map { d ->
                    if (d.id in aiDead) d.copy(isAlive = false, status = DiscipleStatus.DEAD) else d
                } ?: state.gameData.aiSectDisciples[aiSectId] ?: emptyList()
                state.gameData = state.gameData.copy(
                    aiSectDisciples = state.gameData.aiSectDisciples + (aiSectId to updatedAi)
                )
            }

            // Phase 2: 胜者 vs 妖兽
            val winnerDisciples: List<com.xianxia.sect.core.model.Disciple>
            val victory = if (pvpResult.victory) {
                // 巡逻队胜 → 巡逻队幸存者打妖兽
                winnerDisciples = team.disciples.filter { it.id !in patrolDead }
                true
            } else {
                // AI 胜 → AI 幸存者打妖兽
                winnerDisciples = aiDisciples.filter { it.id !in aiDead }
                false
            }

            if (winnerDisciples.isEmpty()) {
                // 双方全灭：记录巡逻弟子死亡 + AI 死亡（AI 已在上面记录），
                // 妖兽未击败，返回带死亡信息的空结果让 applyResults 处理。
                return@mapNotNull TowerBattleResult(
                    towerIndex = team.towerIndex, target = target,
                    victory = false, result = pvpResult,
                    survivors = emptySet(), deadIds = patrolDead
                )
            }

            // 构建妖兽预计算属性
            val beastPreGenStats = if (target.beastMaxHp > 0) BattleSystem.BeastPreGenStats(
                maxHp = target.beastMaxHp, maxMp = target.beastMaxMp,
                physicalAttack = target.beastPhysicalAttack, magicAttack = target.beastMagicAttack,
                physicalDefense = target.beastPhysicalDefense, magicDefense = target.beastMagicDefense,
                speed = target.beastSpeed, realmLayer = target.realmLayer
            ) else null
            val beastTypeName = GameConfig.Beast.getType(
                (target.beastType ?: 0).coerceIn(0, GameConfig.Beast.TYPES.size - 1)
            ).name

            // 构建 Phase 2 (PvE): 胜者 vs 妖兽
            // 巡逻队胜 → 已有 equipmentMap/manualMap；AI 胜 → 需要生成模拟装备
            val pveBattle = if (pvpResult.victory) {
                battleSystem.createBattle(
                    disciples = winnerDisciples,
                    equipmentMap = equipmentMap,
                    manualMap = manualMap,
                    beastLevel = target.realm,
                    beastCount = target.count,
                    beastType = beastTypeName,
                    manualProficiencies = allProficiencies,
                    beastPreGenStats = beastPreGenStats
                )
            } else {
                // AI 胜 → 生成 AI 弟子的模拟装备/功法
                createAIBattle(winnerDisciples, target)
            }
            val pveResult = battleSystem.executeBattle(pveBattle)

            val pveSurvivorIds = pveResult.battle.team.filter { !it.isDead }.map { it.id }.toSet()
            val pveDeadIds = winnerDisciples.filter { it.id !in pveSurvivorIds }.map { it.id }.toSet()
            val allDeadIds = patrolDead + pveDeadIds

            TowerBattleResult(
                towerIndex = team.towerIndex,
                target = target,
                victory = pveResult.victory,
                result = pveResult,
                survivors = pveSurvivorIds,
                deadIds = allDeadIds
            )
        }
    }

    /**
     * 为 AI 弟子创建战斗（生成模拟装备/功法）。
     * 使用共享函数 [AISectDiscipleManager.prepareDisciplesForBattle] 生成装备/功法。
     */
    private fun createAIBattle(
        disciples: List<com.xianxia.sect.core.model.Disciple>,
        target: WorldLevel
    ): Battle {
        val prepared = AISectDiscipleManager.prepareDisciplesForBattle(disciples)
        val beastPreGenStats = if (target.beastMaxHp > 0) BattleSystem.BeastPreGenStats(
            maxHp = target.beastMaxHp, maxMp = target.beastMaxMp,
            physicalAttack = target.beastPhysicalAttack, magicAttack = target.beastMagicAttack,
            physicalDefense = target.beastPhysicalDefense, magicDefense = target.beastMagicDefense,
            speed = target.beastSpeed, realmLayer = target.realmLayer
        ) else null
        val beastTypeName = GameConfig.Beast.getType(
            (target.beastType ?: 0).coerceIn(0, GameConfig.Beast.TYPES.size - 1)
        ).name
        return battleSystem.createBattle(
            disciples = prepared.disciples,
            equipmentMap = prepared.equipmentMap,
            manualMap = prepared.manualMap,
            beastLevel = target.realm,
            beastCount = target.count,
            beastType = beastTypeName,
            manualProficiencies = prepared.proficiencies,
            beastPreGenStats = beastPreGenStats
        )
    }

    private fun executeBattles(
        teams: List<TowerTeam>,
        targets: Map<Int, WorldLevel>,
        equipmentMap: Map<String, EquipmentInstance>,
        manualMap: Map<String, ManualInstance>,
        allProficiencies: Map<String, Map<String, ManualProficiencyData>>
    ): List<TowerBattleResult> {
        return teams.mapNotNull { team ->
            val target = targets[team.towerIndex] ?: return@mapNotNull null
            require(target.type == LevelType.BEAST) { "PatrolBattleSystem 只支持 BEAST 类型, got ${target.type}" }

            val beastTypeName = GameConfig.Beast.getType((target.beastType ?: 0).coerceIn(0, GameConfig.Beast.TYPES.size - 1)).name
            val beastPreGenStats = if (target.beastMaxHp > 0) BattleSystem.BeastPreGenStats(
                maxHp = target.beastMaxHp,
                maxMp = target.beastMaxMp,
                physicalAttack = target.beastPhysicalAttack,
                magicAttack = target.beastMagicAttack,
                physicalDefense = target.beastPhysicalDefense,
                magicDefense = target.beastMagicDefense,
                speed = target.beastSpeed,
                realmLayer = target.realmLayer
            ) else null
            val battle = battleSystem.createBattle(
                disciples = team.disciples,
                equipmentMap = equipmentMap,
                manualMap = manualMap,
                beastLevel = target.realm,
                beastCount = target.count,
                beastType = beastTypeName,
                manualProficiencies = allProficiencies,
                beastPreGenStats = beastPreGenStats
            )
            val result = battleSystem.executeBattle(battle)

            val survivorIds = result.battle.team
                .filter { !it.isDead }.map { it.id }.toSet()
            val deadIds = team.disciples
                .filter { it.id !in survivorIds }.map { it.id }.toSet()

            TowerBattleResult(
                towerIndex = team.towerIndex,
                target = target,
                victory = result.victory,
                result = result,
                survivors = survivorIds,
                deadIds = deadIds
            )
        }
    }

    // ── 步骤 4: 结算战斗结果 ──────────────────────────────────────────────

    private fun applyResults(
        results: List<TowerBattleResult>,
        state: MutableGameState,
        gd: GameData,
        disciples: List<Disciple>
    ) {
        var updatedDisciples = disciples
        var updatedGd = gd
        val allDeadIds = mutableSetOf<String>()

        for (result in results) {
            val allRewards = mutableListOf<BattleRewardItem>()

            // 弟子 HP/MP 更新 + 死亡标记
            val resultState = updateDisciplesForBattleResult(
                result, updatedDisciples
            )
            updatedDisciples = resultState.disciples
            allDeadIds.addAll(resultState.deadIds)

            // 胜利奖励
            if (result.victory) {
                updatedDisciples = applyVictoryRewards(
                    state, result.target, result.survivors,
                    updatedDisciples, allRewards, result.result
                )
                updatedGd = applyVictoryGdChanges(result, updatedGd)
            }

            // BattleLog + 弹窗
            recordBattleLogAndPopup(result, updatedGd, state, allRewards)
        }

        // 悲痛期 + 清理 + 写回
        finalizeBattleOutcome(
            allDeadIds, disciples, updatedDisciples, updatedGd, state
        )
    }

    /** 更新单场战斗结果的弟子 HP/MP 和阵亡状态 */
    private fun updateDisciplesForBattleResult(
        result: TowerBattleResult,
        disciples: List<Disciple>
    ): BattleDisciplesUpdate {
        val hpMap = result.result.battle.team.associate {
            it.id to (it.hp to it.mp)
        }
        val updated = disciples.map { d ->
            val (hp, mp) = hpMap[d.id] ?: return@map d
            if (d.id !in result.survivors) {
                d.copy(isAlive = false, status = DiscipleStatus.DEAD)
            } else {
                d.copy(combat = d.combat.copy(
                    currentHp = hp.coerceIn(0, d.maxHp),
                    currentMp = mp.coerceIn(0, d.maxMp)
                ))
            }
        }
        return BattleDisciplesUpdate(updated, result.deadIds)
    }

    /** 记录战斗日志并加入弹窗队列 */
    private fun recordBattleLogAndPopup(
        result: TowerBattleResult,
        gd: GameData,
        state: MutableGameState,
        allRewards: List<BattleRewardItem>
    ) {
        val log = buildBattleLog(result, gd)
        state.battleLogs = (state.battleLogs + log)
            .takeLast(GameConfig.Logs.MAX_BATTLE_LOGS)
        if (gd.patrolBattleResultPopup) {
            _pendingPatrolResults += BattleResultUIData(
                battleLogId = log.id,
                victory = result.victory,
                teamMembers = log.teamMembers,
                rewards = allRewards
            )
        }
    }

    /** 悲痛期处理 + 槽位清理 + 状态写回 */
    private fun finalizeBattleOutcome(
        allDeadIds: Set<String>,
        originalDisciples: List<Disciple>,
        updatedDisciples: List<Disciple>,
        updatedGd: GameData,
        state: MutableGameState
    ) {
        val deadList = originalDisciples.filter { it.id in allDeadIds }
        val finalDisciples = if (deadList.isNotEmpty()) {
            DiscipleStatCalculator.applyGriefToRelatives(
                updatedDisciples, deadList, updatedGd.gameYear
            )
        } else {
            updatedDisciples
        }
        val finalGd = if (allDeadIds.isNotEmpty()) {
            updatedGd.copy(
                patrolSlots = updatedGd.patrolSlots.map { slot ->
                    if (slot.discipleId in allDeadIds) {
                        PatrolSlot(index = slot.index)
                    } else slot
                }
            )
        } else {
            updatedGd
        }
        state.gameData = finalGd
        state.discipleTables.replaceAll(finalDisciples)
        deathHandler.markAllDead(
            state.discipleTables, allDeadIds, updatedGd.gameYear
        )
    }

    // ── 胜利奖励：击败标记 + 神魂/属性 + 材料 + 灵石 ──────────────────────

    /** 应用胜利后的 GameData 变更：击败妖兽标记 */
    private fun applyVictoryGdChanges(
        result: TowerBattleResult, gd: GameData
    ): GameData {
        val prevCount = gd.guideCounters[GuideCounterKeys.PATROL_BEAST_DEFEATED] ?: 0L
        return gd.copy(
            worldLevels = gd.worldLevels.map {
                if (it.id == result.target.id) it.copy(defeated = true) else it
            },
            guideCounters = gd.guideCounters + (GuideCounterKeys.PATROL_BEAST_DEFEATED to prevCount + 1)
        )
    }

    /** 应用胜利奖励：神魂/属性 + 妖兽材料 + 灵石 */
    private fun applyVictoryRewards(
        state: MutableGameState,
        target: WorldLevel,
        survivors: Set<String>,
        disciples: List<Disciple>,
        allRewards: MutableList<BattleRewardItem>,
        battleResult: BattleSystemResult
    ): List<Disciple> {
        val rng = rngManager.getRng(RngPartition.EXPLORATION)

        val soulUpdated = applySurvivorSoulAndAttribute(
            target, survivors, disciples, rng
        )
        generateBeastMaterialRewards(target, rng, allRewards)
        applySpiritStoneReward(state, battleResult, allRewards)

        return soulUpdated
    }

    /** 幸存弟子：神魂 +1，有天赋者随机属性 +1 */
    private fun applySurvivorSoulAndAttribute(
        target: WorldLevel,
        survivors: Set<String>,
        disciples: List<Disciple>,
        rng: DeterministicRng
    ): List<Disciple> {
        return disciples.map { d ->
            if (d.id in survivors && d.isAlive) {
                var m = d.copy(soulPower = d.soulPower + 1)
                if (m.talentIds.any { id ->
                    TalentDatabase.getById(id)?.effects
                        ?.containsKey("winBattleRandomAttrPlus") == true
                }) {
                    val attr = rng.nextInt(WIN_BATTLE_ATTR_COUNT)
                    val s = m.skills; val c = m.combat
                    when (attr) {
                        0 -> s.intelligence++; 1 -> s.comprehension++
                        2 -> s.charm++; 3 -> s.loyalty++
                        4 -> s.artifactRefining++; 5 -> s.pillRefining++
                        6 -> s.spiritPlanting++; 7 -> s.mining++
                        8 -> s.teaching++; 9 -> s.morality++
                        10 -> c.baseHp++; 11 -> c.baseMp++
                        12 -> c.basePhysicalAttack++; 13 -> c.baseMagicAttack++
                        14 -> c.basePhysicalDefense++; 15 -> c.baseMagicDefense++
                        16 -> c.baseSpeed++
                    }
                }
                m
            } else d
        }
    }

    /** 妖兽材料掉落（每只妖兽随机 1~3 个材料） */
    private fun generateBeastMaterialRewards(
        target: WorldLevel,
        rng: DeterministicRng,
        allRewards: MutableList<BattleRewardItem>
    ) {
        val beastConfig = GameConfig.Beast.getType((target.beastType ?: 0).coerceIn(0, GameConfig.Beast.TYPES.size - 1))
        val tier = GameConfig.Realm.getMaxRarity(target.realm)
        for (i in 0 until target.count) {
            val materialCount = rng.nextInt(MAX_RANDOM_MATERIAL_DROPS) + 1
            repeat(materialCount) {
                val beastMat = BeastMaterialDatabase.getRandomMaterialByBeastType(
                    beastConfig.name, tier
                )
                if (beastMat != null) {
                    val material = Material(
                        id = UUID.randomUUID().toString(),
                        name = beastMat.name,
                        rarity = beastMat.rarity,
                        description = beastMat.description,
                        category = beastMat.materialCategory,
                        quantity = 1
                    )
                    val addResult = inventorySystem.addMaterial(material)
                    if (addResult.isSuccess) {
                        allRewards += BattleRewardItem(
                            itemId = material.id, name = material.name,
                            quantity = 1, rarity = material.rarity,
                            type = "material"
                        )
                    }
                }
            }
        }
    }

    /** 灵石奖励 */
    private fun applySpiritStoneReward(
        state: MutableGameState,
        battleResult: BattleSystemResult,
        allRewards: MutableList<BattleRewardItem>
    ) {
        val spiritStoneReward = battleResult.rewards["spiritStones"] ?: 0
        if (spiritStoneReward > 0) {
            state.gameData = state.gameData.copy(
                spiritStones = state.gameData.spiritStones + spiritStoneReward
            )
            allRewards += BattleRewardItem(
                name = "灵石", quantity = spiritStoneReward,
                rarity = 1, type = "spiritStones"
            )
        }
    }

    // ── 战斗日志构建 ───────────────────────────────────────────────────────

    private fun buildBattleLog(
        result: TowerBattleResult, gd: GameData
    ): BattleLog {
        val teamMembers = result.result.battle.team.map { m ->
            BattleLogMember(
                id = m.id, name = m.name, realm = m.realm,
                realmName = m.realmName, hp = m.hp, maxHp = m.maxHp,
                mp = m.mp, maxMp = m.maxMp, isAlive = !m.isDead,
                portraitRes = m.portraitRes
            )
        }
        val enemies = result.result.battle.beasts.map { b ->
            BattleLogEnemy(
                name = b.name, realm = b.realm,
                realmName = b.realmName, portraitRes = b.portraitRes
            )
        }
        val rounds = result.result.log.rounds.map { r ->
            BattleLogRound(
                roundNumber = r.roundNumber,
                actions = r.actions.map { a ->
                    BattleLogAction(
                        type = a.type, attacker = a.attacker,
                        attackerType = a.attackerType, target = a.target,
                        damage = a.damage, damageType = a.damageType,
                        isCrit = a.isCrit, isKill = a.isKill,
                        message = a.message
                    )
                }
            )
        }
        return BattleLog(
            year = gd.gameYear, month = gd.gameMonth,
            type = BattleType.PVE,
            attackerName = "巡视队伍",
            defenderName = result.target.beastName.ifEmpty { "妖兽" },
            result = if (result.victory) BattleResult.WIN else BattleResult.LOSE,
            teamMembers = teamMembers, enemies = enemies,
            rounds = rounds, turns = result.result.turnCount,
            teamCasualties = teamMembers.count {
                !result.survivors.contains(it.id)
            },
            beastsDefeated = if (result.victory) result.target.count
                else result.result.battle.beasts.count { it.isDead },
            details = if (result.victory) "巡视楼击败了${result.target.beastName}"
                else "巡视楼被${result.target.beastName}击败"
        )
    }
}
