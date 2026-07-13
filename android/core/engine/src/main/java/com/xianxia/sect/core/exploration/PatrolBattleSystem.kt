package com.xianxia.sect.core.exploration

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.BattleSystemResult
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
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.state.BattleResultUIData
import com.xianxia.sect.core.state.MutableGameState
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

// ═══════════════════════════════════════════════════════════════════════════════
// 巡逻战斗系统 — 从 ExplorationService.processPatrolAttacks 提取，拆为 4 步
// ═══════════════════════════════════════════════════════════════════════════════

@Singleton
class PatrolBattleSystem @Inject constructor(
    private val battleSystem: BattleSystem,
    private val rngManager: GameRngManager,
    private val inventorySystem: InventorySystem,
    private val buildingConfigService: BuildingConfigService,
    private val deathHandler: DiscipleDeathHandler
) {
    companion object {
        private const val TAG = "PatrolBattleSystem"
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

        val results = executeBattles(
            teams, targets, equipmentMap, manualMap, allProficiencies
        )
        if (results.isEmpty()) return

        applyResults(results, state, gd, disciples, equipmentMap, manualMap, allProficiencies)
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
            val target = gd.worldLevels.firstOrNull {
                it.type == LevelType.BEAST &&
                    !it.defeated &&
                    !it.checkExpired(year, month) &&
                    it.realm in config.targetRealms &&
                    it.count <= config.maxBeastCount &&
                    it.id !in claimedBeasts
            } ?: continue

            claimedBeasts.add(target.id)
            result[team.towerIndex] = target
        }
        return result
    }

    // ── 步骤 3: 执行战斗 ──────────────────────────────────────────────────

    private fun executeBattles(
        teams: List<TowerTeam>,
        targets: Map<Int, WorldLevel>,
        equipmentMap: Map<String, EquipmentInstance>,
        manualMap: Map<String, ManualInstance>,
        allProficiencies: Map<String, Map<String, ManualProficiencyData>>
    ): List<TowerBattleResult> {
        return teams.mapNotNull { team ->
            val target = targets[team.towerIndex] ?: return@mapNotNull null

            val battle = battleSystem.createBattle(
                disciples = team.disciples,
                equipmentMap = equipmentMap,
                manualMap = manualMap,
                beastLevel = target.realm,
                beastCount = target.count,
                beastType = target.beastName,
                manualProficiencies = allProficiencies
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
        disciples: List<Disciple>,
        equipmentMap: Map<String, EquipmentInstance>,
        manualMap: Map<String, ManualInstance>,
        allProficiencies: Map<String, Map<String, ManualProficiencyData>>
    ) {
        var updatedDisciples = disciples
        var updatedGd = gd
        val allDeadIds = mutableSetOf<String>()

        for (result in results) {
            val allRewards = mutableListOf<BattleRewardItem>()

            // 弟子 HP/MP 更新 + 死亡标记
            val hpMap = result.result.battle.team.associate {
                it.id to (it.hp to it.mp)
            }
            updatedDisciples = updatedDisciples.map { d ->
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
            allDeadIds.addAll(result.deadIds)

            // 胜利奖励
            if (result.victory) {
                updatedDisciples = applyVictoryRewards(
                    state, result.target, result.survivors, updatedDisciples, allRewards, result.result
                )
                updatedGd = applyVictoryGdChanges(result, updatedGd)
            }

            // BattleLog 追加
            val log = buildBattleLog(result, updatedGd)
            state.battleLogs = (state.battleLogs + log)
                .takeLast(GameConfig.Logs.MAX_BATTLE_LOGS)

            // 弹窗数据
            if (updatedGd.patrolBattleResultPopup) {
                _pendingPatrolResults += BattleResultUIData(
                    battleLogId = log.id,
                    victory = result.victory,
                    teamMembers = log.teamMembers,
                    rewards = allRewards
                )
            }
        }

        // 悲痛期
        val deadList = disciples.filter { it.id in allDeadIds }
        if (deadList.isNotEmpty()) {
            updatedDisciples = DiscipleStatCalculator.applyGriefToRelatives(
                updatedDisciples, deadList, updatedGd.gameYear
            )
        }

        // 清理阵亡弟子槽位 + 写回 state
        if (allDeadIds.isNotEmpty()) {
            updatedGd = updatedGd.copy(
                patrolSlots = updatedGd.patrolSlots.map { slot ->
                    if (slot.discipleId in allDeadIds) PatrolSlot(index = slot.index) else slot
                }
            )
        }
        state.gameData = updatedGd
        state.discipleTables.clear()
        updatedDisciples.forEach { state.discipleTables.insert(it) }
        // 通过 DiscipleDeathHandler 统一标记死亡（含装备断言守卫）
        deathHandler.markAllDead(
            state.discipleTables, allDeadIds, updatedGd.gameYear
        )
    }

    // ── 胜利奖励：击败标记 + 神魂/属性 + 材料 + 灵石 ──────────────────────

    /** 应用胜利后的 GameData 变更：击败妖兽标记 */
    private fun applyVictoryGdChanges(
        result: TowerBattleResult, gd: GameData
    ): GameData {
        return gd.copy(
            worldLevels = gd.worldLevels.map {
                if (it.id == result.target.id) it.copy(defeated = true) else it
            }
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

        // 幸存弟子：神魂 +1，有天赋者随机属性 +1
        val soulUpdated = disciples.map { d ->
            if (d.id in survivors && d.isAlive) {
                var m = d.copy(soulPower = d.soulPower + 1)
                if (m.talentIds.any { id ->
                    TalentDatabase.getById(id)?.effects
                        ?.containsKey("winBattleRandomAttrPlus") == true
                }) {
                    val attr = rng.nextInt(17)
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

        // 妖兽材料（每只妖兽随机 1~3 个材料）
        val beastConfig = GameConfig.Beast.getType(target.beastType ?: 0)
        val tier = GameConfig.Realm.getMaxRarity(target.realm)
        for (i in 0 until target.count) {
            val materialCount = rng.nextInt(3) + 1
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

        // 灵石奖励
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

        return soulUpdated
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
            teamCasualties = teamMembers.count { !result.survivors.contains(it.id) },
            beastsDefeated = if (result.victory) result.target.count
                else result.result.battle.beasts.count { it.isDead },
            details = if (result.victory) "巡视楼击败了${result.target.beastName}"
                else "巡视楼被${result.target.beastName}击败"
        )
    }
}
