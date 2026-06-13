package com.xianxia.sect.core.engine.domain.exploration

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator

import com.xianxia.sect.core.event.DeathEvent
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.state.BattleResultUIData
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.engine.system.AddResult
import com.xianxia.sect.core.engine.system.InventorySystem


import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExplorationService @Inject constructor(
    private val stateStore: GameStateStore,
    private val eventBus: EventBusPort,
    private val scopeProvider: CoroutineScopeProvider,
    private val battleSystem: BattleSystem,
    private val buildingConfigService: BuildingConfigService,
    private val inventorySystem: InventorySystem
) {
    private val scope get() = scopeProvider.scope

    private val _pendingPatrolResults = mutableListOf<BattleResultUIData>()

    // 关卡刷新冷却：每3个月刷新一次（绝对月 = year * 12 + month）
    private var lastWorldLevelRefreshMonth: Int = 0

    fun consumePendingPatrolResults(): List<BattleResultUIData> {
        val results = _pendingPatrolResults.toList()
        _pendingPatrolResults.clear()
        return results
    }

    suspend fun processMonthlyWorldLevels(state: MutableGameState) {
        val data = state.gameData
        val year = data.gameYear
        val month = data.gameMonth

        // 清理过期关卡（每月都做）
        val remainingLevels = data.worldLevels.filter { !it.checkExpired(year, month) }

        // 每3个月刷新一次新关卡（数量1~6，持续4个月）
        val absoluteMonth = year * 12 + month
        val shouldRefresh = lastWorldLevelRefreshMonth == 0 || (absoluteMonth - lastWorldLevelRefreshMonth) >= 3

        if (shouldRefresh) {
            lastWorldLevelRefreshMonth = absoluteMonth
            val playerSect = data.worldMapSects.find { it.isPlayerSect } ?: return

            val edges = LevelGenerator.buildConnectionEdges(data.worldMapSects)
            val newLevels = LevelGenerator.generateWorldLevels(
                existingSects = data.worldMapSects,
                connectionEdges = edges,
                currentYear = year,
                currentMonth = month,
                existingLevels = remainingLevels
            )

            if (newLevels.isNotEmpty()) {
                state.gameData = data.copy(worldLevels = remainingLevels + newLevels)
            } else if (remainingLevels.size != data.worldLevels.size) {
                state.gameData = data.copy(worldLevels = remainingLevels)
            }
        } else if (remainingLevels.size != data.worldLevels.size) {
            state.gameData = data.copy(worldLevels = remainingLevels)
        }

        // 巡视楼自动攻击
        processPatrolAttacks(state)
    }

    private fun processPatrolAttacks(state: MutableGameState) {
        var gd = state.gameData
        var disciples = state.discipleTables.assembleAll()
        val allSlots = gd.patrolSlots
        val configs = gd.patrolConfigs
        if (allSlots.isEmpty()) return

        val numTowers = gd.placedBuildings.count { it.displayName == "巡视楼" }
        val equipmentMap = state.equipmentInstances.associateBy { it.id }
        val manualMap = state.manualInstances.associateBy { it.id }
        val allProficiencies = gd.manualProficiencies.mapValues { (_, list) ->
            list.associateBy { it.manualId }
        }
        val year = gd.gameYear; val month = gd.gameMonth
        val claimedBeasts = mutableSetOf<String>()

        val slotsPerTower = buildingConfigService.getSlotCountByDisplayName("巡视楼")
        for (towerIndex in 0 until numTowers) {
            val config = configs.getOrElse(towerIndex) { PatrolConfig() }
            val start = towerIndex * slotsPerTower
            val end = (start + slotsPerTower).coerceAtMost(allSlots.size)
            val towerSlots = allSlots.subList(start, end).filter { it.discipleId.isNotEmpty() }
            if (towerSlots.isEmpty()) continue

            val towerDiscipleIds = towerSlots.map { it.discipleId }.toSet()
            val towerDisciples = disciples.filter { it.id in towerDiscipleIds && it.isAlive }
            if (towerDisciples.isEmpty()) continue

            // 满状态检查
            if (config.requireFullStatus) {
                val anyNotFull = towerDisciples.any {
                    it.combat.currentHp < it.maxHp || it.combat.currentMp < it.maxMp
                }
                if (anyNotFull) continue
            }

            // 找匹配的妖兽（排除已被其他塔选中的）
            val target = gd.worldLevels.firstOrNull {
                it.type == LevelType.BEAST &&
                !it.defeated &&
                !it.checkExpired(year, month) &&
                it.realm in config.targetRealms &&
                it.count <= config.maxBeastCount &&
                it.id !in claimedBeasts
            } ?: continue

            claimedBeasts.add(target.id)

            val battle = battleSystem.createBattle(
                disciples = towerDisciples,
                equipmentMap = equipmentMap,
                manualMap = manualMap,
                beastLevel = target.realm,
                beastCount = target.count,
                beastType = target.beastName,
                manualProficiencies = allProficiencies
            )
            val result = battleSystem.executeBattle(battle)

            // 更新弟子状态
            val hpMap = result.battle.team.associate { it.id to (it.hp to it.mp) }
            val survivorIds = result.battle.team.filter { !it.isDead }.map { it.id }.toSet()
            val deadDisciples = disciples.filter { it.id in towerDiscipleIds && it.id !in survivorIds }
            disciples = disciples.map { d ->
                val (hp, mp) = hpMap[d.id] ?: return@map d
                if (d.id !in survivorIds) {
                    d.copy(isAlive = false, status = DiscipleStatus.DEAD)
                } else {
                    d.copy(combat = d.combat.copy(
                        currentHp = hp.coerceIn(0, d.maxHp),
                        currentMp = mp.coerceIn(0, d.maxMp)
                    ))
                }
            }

            // 亲人逝世影响：为阵亡弟子的存活亲属设置悲痛期
            if (deadDisciples.isNotEmpty()) {
                disciples = DiscipleStatCalculator.applyGriefToRelatives(
                    disciples, deadDisciples, gd.gameYear
                )
            }

            // 构建 BattleLog
            val teamMembers = result.battle.team.map { m ->
                BattleLogMember(
                    id = m.id, name = m.name, realm = m.realm, realmName = m.realmName,
                    hp = m.hp, maxHp = m.maxHp, mp = m.mp, maxMp = m.maxMp,
                    isAlive = !m.isDead, portraitRes = m.portraitRes
                )
            }
            val enemies = result.battle.beasts.map { b ->
                BattleLogEnemy(name = b.name, realm = b.realm, realmName = b.realmName, portraitRes = b.portraitRes)
            }
            val rounds = result.log.rounds.map { r ->
                BattleLogRound(
                    roundNumber = r.roundNumber,
                    actions = r.actions.map { a ->
                        BattleLogAction(
                            type = a.type, attacker = a.attacker, attackerType = a.attackerType,
                            target = a.target, damage = a.damage, damageType = a.damageType,
                            isCrit = a.isCrit, isKill = a.isKill, message = a.message
                        )
                    }
                )
            }
            val log = BattleLog(
                year = gd.gameYear,
                month = gd.gameMonth,
                type = BattleType.PVE,
                attackerName = "巡视队伍",
                defenderName = target.beastName.ifEmpty { "妖兽" },
                result = if (result.victory) BattleResult.WIN else BattleResult.LOSE,
                teamMembers = teamMembers,
                enemies = enemies,
                rounds = rounds,
                turns = result.turnCount,
                teamCasualties = teamMembers.count { !survivorIds.contains(it.id) },
                beastsDefeated = if (result.victory) target.count else result.battle.beasts.count { it.isDead },
                details = if (result.victory) "巡视楼击败了${target.beastName}" else "巡视楼被${target.beastName}击败"
            )
            state.battleLogs = (state.battleLogs + log).takeLast(GameConfig.Logs.MAX_BATTLE_LOGS)

            // 奖励
            val allRewards = mutableListOf<BattleRewardItem>()

            // 标记妖兽已击败
            if (result.victory) {
                gd = gd.copy(
                    worldLevels = gd.worldLevels.map {
                        if (it.id == target.id) it.copy(defeated = true) else it
                    }
                )
                // 清理阵亡弟子槽位
                val deadIds = disciples.filter { !it.isAlive }.map { it.id }.toSet()
                if (deadIds.isNotEmpty()) {
                    gd = gd.copy(
                        patrolSlots = gd.patrolSlots.map { slot ->
                            if (slot.discipleId in deadIds) PatrolSlot(index = slot.index) else slot
                        }
                    )
                }

                // 幸存弟子神魂+1，有天赋的随机属性+1
                disciples = disciples.map { d ->
                    if (d.id in survivorIds && d.isAlive) {
                        var modified = d.copy(soulPower = d.soulPower + 1)
                        if (modified.talentIds.any { id ->
                            TalentDatabase.getById(id)?.effects?.containsKey("winBattleRandomAttrPlus") == true
                        }) {
                            val r = kotlin.random.Random.nextInt(17)
                            val s = modified.skills
                            val c = modified.combat
                            when (r) {
                                0 -> s.intelligence++
                                1 -> s.comprehension++
                                2 -> s.charm++
                                3 -> s.loyalty++
                                4 -> s.artifactRefining++
                                5 -> s.pillRefining++
                                6 -> s.spiritPlanting++
                                7 -> s.mining++
                                8 -> s.teaching++
                                9 -> s.morality++
                                10 -> c.baseHp++
                                11 -> c.baseMp++
                                12 -> c.basePhysicalAttack++
                                13 -> c.baseMagicAttack++
                                14 -> c.basePhysicalDefense++
                                15 -> c.baseMagicDefense++
                                16 -> c.baseSpeed++
                            }
                        }
                        modified
                    } else d
                }

                // 妖兽材料奖励
                val beastConfig = GameConfig.Beast.getType(target.beastType ?: 0)
                val tier = GameConfig.Realm.getMaxRarity(target.realm)
                for (i in 0 until target.count) {
                    val materialCount = kotlin.random.Random.nextInt(1, 4)
                    repeat(materialCount) {
                        val beastMaterial = BeastMaterialDatabase.getRandomMaterialByBeastType(beastConfig.name, tier)
                        if (beastMaterial != null) {
                            val material = Material(
                                id = UUID.randomUUID().toString(),
                                name = beastMaterial.name,
                                rarity = beastMaterial.rarity,
                                description = beastMaterial.description,
                                category = beastMaterial.materialCategory,
                                quantity = 1
                            )
                            val addResult = inventorySystem.addMaterial(material)
                            if (addResult == AddResult.SUCCESS || addResult == AddResult.PARTIAL_SUCCESS) {
                                allRewards.add(BattleRewardItem(
                                    itemId = material.id, name = material.name,
                                    quantity = 1, rarity = material.rarity, type = "material"
                                ))
                            }
                        }
                    }
                }

                // 灵石奖励
                val spiritStoneReward = result.rewards["spiritStones"] ?: 0
                if (spiritStoneReward > 0) {
                    gd = gd.copy(spiritStones = gd.spiritStones + spiritStoneReward.toLong())
                    allRewards.add(BattleRewardItem(name = "灵石", quantity = spiritStoneReward, rarity = 1, type = "spiritStones"))
                }
            }

            // 收集结算弹窗数据
            if (gd.patrolBattleResultPopup) {
                _pendingPatrolResults.add(BattleResultUIData(
                    battleLogId = log.id,
                    victory = result.victory,
                    teamMembers = teamMembers,
                    rewards = allRewards
                ))
            }
        }

        state.gameData = gd
        state.discipleTables.clear()
        disciples.forEach { state.discipleTables.insert(it) }
    }
    companion object {
        private const val TAG = "ExplorationService"
    }

    // ==================== StateFlow 暴露 ====================

    /**
     * Get exploration teams StateFlow
     */
    fun getTeams(): StateFlow<List<ExplorationTeam>> = stateStore.teams

    fun recallDiscipleFromTeam(teamId: String, discipleId: String): Boolean {
        val currentTeams = stateStore.teams.value
        val teamIndex = currentTeams.indexOfFirst { it.id == teamId }
        if (teamIndex < 0) return false

        val team = currentTeams[teamIndex]
        if (!team.memberIds.contains(discipleId)) return false

        val remainingMemberIds = team.memberIds.filter { it != discipleId }
        val remainingMemberNames = team.memberNames.toMutableList()
        val removedIndex = team.memberIds.indexOf(discipleId)
        if (removedIndex in remainingMemberNames.indices) {
            remainingMemberNames.removeAt(removedIndex)
        }

        if (remainingMemberIds.isEmpty()) {
            scope.launch { stateStore.update { this.teams = this.teams.filter { it.id != teamId } } }
        } else {
            val updatedTeam = team.copy(
                memberIds = remainingMemberIds,
                memberNames = remainingMemberNames
            )
            scope.launch { stateStore.update { this.teams = this.teams.toMutableList().also { it[teamIndex] = updatedTeam } } }
        }

        updateDiscipleStatus(discipleId, DiscipleStatus.IDLE)
        return true
    }

    /**
     * Complete exploration (success or failure)
     */
    fun completeExploration(teamId: String, success: Boolean, survivorIds: List<String>) {
        val currentTeams = stateStore.teams.value
        val teamIndex = currentTeams.indexOfFirst { it.id == teamId }
        if (teamIndex < 0) return

        val team = currentTeams[teamIndex]

        // Mark as completed
        val updatedTeam = team.copy(status = ExplorationStatus.COMPLETED)
        scope.launch { stateStore.update { this.teams = this.teams.toMutableList().also { it[teamIndex] = updatedTeam } } }

        // Reset survivor statuses, mark dead disciples
        team.memberIds.forEach { memberId ->
            if (survivorIds.contains(memberId)) {
                updateDiscipleStatus(memberId, DiscipleStatus.IDLE)
            } else {
                markDiscipleDead(memberId)
            }
        }
    }

    /**
     * Mark disciple as dead
     */
    private fun markDiscipleDead(discipleId: String) {
        val disciple = stateStore.disciples.value.find { it.id == discipleId }
        val gameYear = stateStore.gameData.value.gameYear
        scope.launch { stateStore.update {
            val currentList = discipleTables.assembleAll()
            val markedDead = currentList.map {
                if (it.id == discipleId) it.copy(isAlive = false, status = DiscipleStatus.DEAD) else it
            }
            val finalList = if (disciple != null) {
                DiscipleStatCalculator.applyGriefToRelatives(markedDead, listOf(disciple), gameYear)
            } else {
                markedDead
            }
            discipleTables.clear()
            finalList.forEach { discipleTables.insert(it) }
        } }
        disciple?.let { d ->
            eventBus.emitSync(DeathEvent(d.id, d.name, "探索阵亡"))
        }
    }

    private fun updateDiscipleStatus(discipleId: String, status: DiscipleStatus) {
        scope.launch { stateStore.update {
            val currentList = discipleTables.assembleAll()
            val updated = currentList.map {
                if (it.id == discipleId) it.copy(status = status) else it
            }
            discipleTables.clear()
            updated.forEach { discipleTables.insert(it) }
        } }
    }
}
