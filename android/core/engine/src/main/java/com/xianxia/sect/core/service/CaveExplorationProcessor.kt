package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.random.Random
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
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.SectWarehouseManager
import com.xianxia.sect.core.engine.domain.exploration.CaveExplorationSystem
import com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager
import com.xianxia.sect.core.util.AnalyticsTracker
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.engine.LazyEvaluationDispatcher
import com.xianxia.sect.core.perf.ThermalMonitor
import com.xianxia.sect.core.engine.annotation.GameService
import javax.inject.Inject
import javax.inject.Singleton

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
    private val cultivationService: CultivationService
) {
    private val scope get() = scopeProvider.scope

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

    suspend fun processCaveLifecycle(year: Int, month: Int) {
        val data = stateStore.gameData.value

        val expiredCaveIds = data.cultivatorCaves.filter { cave ->
            cave.isExpired(year, month) || cave.status == CaveStatus.EXPLORED
        }.map { it.id }.toSet()

        val activeCaves = data.cultivatorCaves.filter { cave ->
            !cave.isExpired(year, month) && cave.status != CaveStatus.EXPLORED
        }

        expiredCaveIds.forEach { caveId ->
            val affectedTeams = data.caveExplorationTeams.filter {
                it.caveId == caveId && it.status == CaveExplorationStatus.TRAVELING
            }
            affectedTeams.forEach { team ->
                resetCaveExplorationTeamMembersStatus(team)
            }
        }

        val allAITeams = data.aiCaveTeams.toMutableList()
        activeCaves.forEach { cave ->
            val currentTeams = allAITeams.count {
                it.caveId == cave.id && it.status == AITeamStatus.EXPLORING
            }

            if (currentTeams < 3 && Random.nextDouble() < 0.7) {
                val nearbySects = findNearbySects(cave, 400f)
                val existingTeamForCave = allAITeams.filter { it.caveId == cave.id }

                val aiTeam = generateAITeamInline(cave, nearbySects, existingTeamForCave)
                if (aiTeam != null) {
                    allAITeams.add(aiTeam)
                }
            }
        }

        var updatedSectsForAI = stateStore.gameData.value.worldMapSects.toMutableList()
        val updatedSectDetails = stateStore.gameData.value.sectDetails.toMutableMap()
        val aiTeamsToRemove = mutableListOf<String>()

        allAITeams.filter { it.status == AITeamStatus.EXPLORING }.forEach { aiTeam ->
            val cave = activeCaves.find { it.id == aiTeam.caveId } ?: return@forEach

            if (Random.nextDouble() < 0.3) {
                aiTeamsToRemove.add(aiTeam.id)
            }
        }

        val filteredAITeams = allAITeams.filter { it.id !in aiTeamsToRemove }

        val teamsToComplete = data.caveExplorationTeams.filter {
            it.status == CaveExplorationStatus.EXPLORING
        }

        var finalCaves = activeCaves.toMutableList()
        var finalAITeams = filteredAITeams.toList()
        var finalExplorationTeams = data.caveExplorationTeams.filter {
            it.caveId !in expiredCaveIds
        }.toMutableList()
        val teamsWithMissingCave = mutableListOf<CaveExplorationTeam>()
        val teamsWithError = mutableListOf<CaveExplorationTeam>()

        teamsToComplete.forEach { team ->
            val cave = finalCaves.find { it.id == team.caveId }
            if (cave == null) {
                teamsWithMissingCave.add(team)
                return@forEach
            }
            try {
                val result = executeCaveExploration(team, cave, finalAITeams)

                finalCaves = result.first.toMutableList()
                finalAITeams = result.second
                if (result.third) {
                    finalExplorationTeams.removeAll { it.id == team.id }
                }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                DomainLog.e(TAG, "Error processing cave exploration for team ${team.id}", e)
                teamsWithError.add(team)
            }
        }

        teamsWithMissingCave.forEach { team ->
            resetCaveExplorationTeamMembersStatus(team)
            finalExplorationTeams.removeAll { it.id == team.id }
        }

        teamsWithError.forEach { team ->
            resetCaveExplorationTeamMembersStatus(team)
            finalCaves = finalCaves.map { cave ->
                if (cave.id == team.caveId && cave.status == CaveStatus.EXPLORING) {
                    cave.copy(status = CaveStatus.AVAILABLE)
                } else {
                    cave
                }
            }.toMutableList()
            finalExplorationTeams.removeAll { it.id == team.id }
        }

        val currentData = stateStore.gameData.value
        stateStore.update { gameData = currentData.copy(
            cultivatorCaves = finalCaves,
            aiCaveTeams = finalAITeams,
            caveExplorationTeams = finalExplorationTeams,
            worldMapSects = updatedSectsForAI,
            sectDetails = updatedSectDetails
        ) }
    }

    fun generateAITeamInline(
        cave: CultivatorCave,
        nearbySects: List<WorldSect>,
        existingTeams: List<AICaveTeam>
    ): AICaveTeam? {
        val availableSects = nearbySects.filter { sect ->
            existingTeams.none { it.sectId == sect.id }
        }
        if (availableSects.isEmpty()) return null
        val sect = availableSects.random()
        return AICaveTeam(
            caveId = cave.id,
            sectId = sect.id,
            sectName = sect.name,
            memberCount = (3..5).random(),
            avgRealm = cave.ownerRealm,
            avgRealmName = cave.ownerRealmName
        )
    }

    private suspend fun executeCaveExploration(
        team: CaveExplorationTeam,
        cave: CultivatorCave,
        currentAITeams: List<AICaveTeam>
    ): Triple<List<CultivatorCave>, List<AICaveTeam>, Boolean> {
        val teamMembers = team.memberIds.mapNotNull { id ->
            stateStore.disciples.value.find { it.id == id }
        }.filter { it.isAlive }

        if (teamMembers.isEmpty()) {
            return Triple(
                stateStore.gameData.value.cultivatorCaves,
                currentAITeams.filter { it.caveId != cave.id },
                true
            )
        }

        val data = stateStore.gameData.value
        val equipmentMap = stateStore.equipmentInstances.value.associateBy { it.id }
        val manualMap = stateStore.manualInstances.value.associateBy { it.id }
        val allProficiencies = data.manualProficiencies.mapValues { (_, list) ->
            list.associateBy { it.manualId }
        }

        val aiTeamInCave = currentAITeams.find { it.caveId == cave.id && it.status == AITeamStatus.EXPLORING }
        val battleResult = executeBattleForTeam(
            teamMembers, equipmentMap, manualMap, allProficiencies,
            aiTeamInCave, cave
        )

        val survivorIds = battleResult.log.teamMembers.filter { it.isAlive }.map { it.id }.toSet()
        val survivorHpMap = battleResult.log.teamMembers.filter { it.isAlive }.associate { it.id to it.hp }
        val survivorMpMap = battleResult.log.teamMembers.filter { it.isAlive }.associate { it.id to it.mp }
        stateStore.update {
            val newList = discipleTables.assembleAll().map { disciple ->
                if (disciple.id in team.memberIds) {
                    if (disciple.id in survivorIds) {
                        val hp = survivorHpMap[disciple.id] ?: disciple.combat.currentHp
                        val mp = survivorMpMap[disciple.id] ?: disciple.combat.currentMp
                        disciple.copy(status = DiscipleStatus.IDLE, combat = disciple.combat.copy(currentHp = hp, currentMp = mp))
                    } else {
                        eventProcessor.handleDiscipleDeath(disciple, isOutsideSect = true)
                        disciple.copy(isAlive = false, status = DiscipleStatus.DEAD)
                    }
                } else disciple
            }
            discipleTables.clear()
            newList.forEach { discipleTables.insert(it) }
        }

        if (!battleResult.victory) {
            var updatedAITeams = currentAITeams.filter { it.caveId != cave.id }
            if (aiTeamInCave != null) {
                updatedAITeams = updatedAITeams.toMutableList()
            }
            return Triple(
                stateStore.gameData.value.cultivatorCaves,
                updatedAITeams,
                true
            )
        }

        stateStore.update {
            val newList = discipleTables.assembleAll().map { disciple ->
                if (disciple.id in survivorIds && disciple.isAlive) {
                    disciple.copy(soulPower = disciple.soulPower + 1)
                } else {
                    disciple
                }
            }
            discipleTables.clear()
            newList.forEach { discipleTables.insert(it) }
        }

        val rewards = CaveExplorationSystem.generateVictoryRewards(cave)

        val battleRewardItems = mutableListOf<BattleRewardItem>()
        rewards.items.forEach { reward ->
            when (reward.type) {
                "spiritStones" -> {
                    stateStore.update {
                        gameData = gameData.copy(
                            spiritStones = gameData.spiritStones + reward.quantity.toLong()
                        )
                    }
                    battleRewardItems.add(BattleRewardItem(
                        itemId = reward.itemId,
                        name = reward.name,
                        quantity = reward.quantity,
                        rarity = reward.rarity,
                        type = reward.type
                    ))
                }
                "equipment" -> {
                    val template = EquipmentDatabase.getById(reward.itemId)
                    if (template != null) {
                        val equipment = EquipmentDatabase.createFromTemplate(template).copy(
                            rarity = reward.rarity,
                            quantity = reward.quantity
                        )
                        val result = inventorySystem.addEquipmentStack(equipment)
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
                "manual" -> {
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
                "pill" -> {
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
                        val result = inventorySystem.addPill(pill)
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
            }
        }

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

        stateStore.setPendingBattleResult(BattleResultUIData(
            battleLogId = battleLog.id,
            victory = battleResult.victory,
            teamMembers = battleLog.teamMembers,
            rewards = battleRewardItems
        ))

        analyticsTracker.trackEvent(
            "battle_end",
            mapOf(
                "outcome" to if (battleResult.victory) "win" else "lose",
                "enemy_type" to cave.name,
                "turns" to battleResult.turnCount,
                "team_size" to battleResult.log.teamMembers.size
            )
        )

        val updatedCaves = stateStore.gameData.value.cultivatorCaves.map { c ->
            if (c.id == cave.id) c.copy(status = CaveStatus.EXPLORED) else c
        }
        val updatedAITeams = currentAITeams.filter { it.caveId != cave.id }

        return Triple(updatedCaves, updatedAITeams, true)
    }

    /** 执行洞府战斗：玩家团队 vs AI团队或守护妖兽 */
    private fun executeBattleForTeam(
        teamMembers: List<Disciple>,
        equipmentMap: Map<String, EquipmentInstance>,
        manualMap: Map<String, ManualInstance>,
        allProficiencies: Map<String, Map<String, ManualProficiencyData>>,
        aiTeamInCave: AICaveTeam?,
        cave: CultivatorCave
    ): com.xianxia.sect.core.engine.domain.battle.BattleSystemResult = if (aiTeamInCave != null) {
        battleSystem.executeBattle(
            CaveExplorationSystem.createAIBattle(
                playerDisciples = teamMembers,
                playerEquipmentMap = equipmentMap,
                playerManualMap = manualMap,
                playerManualProficiencies = allProficiencies,
                aiTeam = aiTeamInCave
            )
        )
    } else {
        battleSystem.executeBattle(
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
        scope.launch {
            stateStore.update {
                val idsToReset = team.memberIds.filter { memberId ->
                    val d = discipleTables.assembleAll().find { it.id == memberId }
                    d != null && d.status == DiscipleStatus.IN_TEAM
                }
                if (idsToReset.isNotEmpty()) {
                    val newList = discipleTables.assembleAll().map {
                        if (it.id in idsToReset) it.copy(status = DiscipleStatus.IDLE) else it
                    }
                    discipleTables.clear()
                    newList.forEach { discipleTables.insert(it) }
                }
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

    suspend fun processAISectOperations(year: Int, month: Int) {
        val data = stateStore.gameData.value
        val aiDisciples = data.aiSectDisciples

        // 热控分批
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

        scope.launch {
            stateStore.update {
                gameData = gameData.copy(
                    sectDetails = cleanedSectDetails,
                    aiSectDisciples = gameData.aiSectDisciples.mapValues { (sId, current) ->
                        val calculated = updatedAiDisciples[sId] ?: return@mapValues current
                        val currentIds = current.map { it.id }.toSet()
                        calculated.filter { it.id in currentIds }
                    },
                    worldMapSects = syncedWorldSects
                )
            }
        }

        processAIVsAIBattles()
        processPlayerDefenseBattles()
    }

    /**
     * AI 攻打玩家：预警生命周期 + 战斗结算。
     */
    private suspend fun processPlayerDefenseBattles() {
        val data = stateStore.gameData.value

        // 1. 推进预警阶段（谴责 → 战书）
        stateStore.update {
            attackWarningService.advanceWarningsIfNeededSync(this)
        }

        // 2. 检查到期战书 → 执行内联结算（战斗前结算 + 战斗 + 结果）
        val expiredWarnings = data.activeAttackWarnings.filter {
            it.stage == WarningStage.WAR_DECLARATION &&
                data.gameYear * 12 + data.gameMonth >= it.attackMonth
        }
        for (expired in expiredWarnings) {
            executePlayerDefenseBattle(expired)
        }

        // 3. 新攻击决策 → 生成谴责
        val decision = AISectAttackManager.decidePlayerAttack(data)
        if (decision is PlayerAttackDecision.GenerateWarning) {
            scope.launch {
                stateStore.update {
                    attackWarningService.addWarningSync(
                        this,
                        attackWarningService.createDenunciationWarning(
                            decision.attackerSectId, decision.attackerSectName
                        )
                    )
                }
            }
        }

        // 4. 驻军填充
        scope.launch {
            stateStore.update {
                gameData = AISectGarrisonManager.fillEmptyGarrisonSlots(gameData)
            }
        }
    }

    private suspend fun executePlayerDefenseBattle(expired: AttackWarning) {
        val data = stateStore.gameData.value
        val allDisciples = stateStore.discipleTables.assembleAll()
        val selectedDefenders = allDisciples
            .filter {
                it.isAlive &&
                    it.status !in setOf(DiscipleStatus.ON_MISSION,
                        DiscipleStatus.IN_TEAM, DiscipleStatus.REFLECTING,
                        DiscipleStatus.GARRISONING) &&
                    it.statusData["bloodRefining"] != "true"
            }
            .sortedBy { it.realm }
            .take(AISectAttackManager.TEAM_SIZE)

        val defenderIds = selectedDefenders.map { it.id }
        if (defenderIds.isEmpty()) return

        // 战斗前全量结算 + 转换 Combatant
        val equipmentMap = stateStore.equipmentInstancesSnapshot
            .associateBy { it.id }
        val manualMap = stateStore.manualInstancesSnapshot
            .associateBy { it.id }
        val profMap = data.manualProficiencies.mapValues { (_, list) ->
            list.associateBy { it.manualId }
        }

        stateStore.update {
            cultivationService.forceSettleDisciplesBeforeBattle(this, defenderIds)
        }
        val tables = stateStore.discipleTables
        val refreshedDefenders = defenderIds.mapNotNull { id ->
            val idInt = id.toIntOrNull() ?: return@mapNotNull null
            if (tables.isAlive[idInt] == 1) tables.assemble(idInt) else null
        }

        val defenseTeam = refreshedDefenders.map { d ->
            battleSystem.convertDiscipleToCombatant(
                d, equipmentMap, manualMap, profMap, CombatantSide.DEFENDER
            )
        }

        val result = AISectAttackManager.executePlayerAttack(
            data, expired.attackerSectId, defenseTeam
        ) ?: return

        // 删除到期预警 + 应用战斗结果
        scope.launch {
            stateStore.update {
                gameData = gameData.copy(
                    activeAttackWarnings = gameData.activeAttackWarnings.filter {
                        it.warningId != expired.warningId
                    }
                )

                val currentDisciples = discipleTables.assembleAll()
                val deadDefenders = currentDisciples.filter {
                    it.id in result.deadDefenderIds
                }
                var newDisciples = currentDisciples
                if (deadDefenders.isNotEmpty()) {
                    newDisciples = DiscipleStatCalculator
                        .applyGriefToRelatives(
                            newDisciples, deadDefenders, gameData.gameYear
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
                discipleTables.clear()
                newDisciples.forEach { discipleTables.insert(it) }

                val attackerDisc = gameData.aiSectDisciples[
                    result.attackerSectId] ?: emptyList()
                val playerSectId = gameData.worldMapSects
                    .find { it.isPlayerSect }?.id ?: return@update
                val playerSectName = gameData.worldMapSects
                    .find { it.isPlayerSect }?.name ?: "玩家宗门"

                var updated = gameData.copy(
                    aiSectDisciples = gameData.aiSectDisciples.toMutableMap().apply {
                        this[result.attackerSectId] = attackerDisc.filter {
                            it.id !in result.deadAttackerIds
                        }
                    },
                    worldMapSects = gameData.worldMapSects.map { sect ->
                        if (sect.id == playerSectId) sect.copy(
                            garrisonSlots = sect.garrisonSlots.map { slot ->
                                if (slot.discipleId in result.deadDefenderIds)
                                    GarrisonSlot(index = slot.index) else slot
                            }
                        ) else sect
                    },
                    sectRelations = gameData.sectRelations.map { r ->
                        val relevant = (r.sectId1 == result.attackerSectId &&
                            r.sectId2 == playerSectId) ||
                            (r.sectId1 == playerSectId &&
                                r.sectId2 == result.attackerSectId)
                        if (relevant) r.copy(
                            favor = (r.favor - 15).coerceIn(
                                GameConfig.Diplomacy.MIN_FAVOR,
                                GameConfig.Diplomacy.MAX_FAVOR)
                        ) else r
                    }
                )

                if (result.winner == AIBattleWinner.ATTACKER) {
                    val detail = updated.sectDetails[playerSectId]
                        ?: SectDetail(sectId = playerSectId)
                    val loot = sectWarehouseManager
                        .calculateWarehouseLootLoss(detail.warehouse)
                    val newWarehouse = sectWarehouseManager
                        .applyLootLossToWarehouse(detail.warehouse, loot)
                    updated = updated.copy(
                        sectDetails = updated.sectDetails.toMutableMap().apply {
                            this[playerSectId] = detail.copy(warehouse = newWarehouse)
                        }
                    )
                }

                // 战斗日志
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
                val deadAttackerData = attackerDisc.filter {
                    it.id in result.deadAttackerIds
                }
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
                recordPlayerBattle(
                    year = gameData.gameYear,
                    month = gameData.gameMonth,
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

                gameData = updated
            }
        }
    }

    /**
     * AI-vs-AI 战斗月度结算（含玩家占领宗门防御）。
     * 同步执行，不通过 scope.launch 异步写入。
     */
    private suspend fun processAIVsAIBattles() {
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
            scope.launch {
                stateStore.update {
                    val currentGameData = gameData
                    val defenderSect = currentGameData.worldMapSects
                        .find { it.id == result.defenderSectId }
                    val isPlayerOccupied = defenderSect
                        ?.isPlayerOccupied == true

                    // 玩家占领宗门防御：更新驻军弟子状态
                    if (isPlayerOccupied) {
                        updatePlayerGarrisonState(
                            result, discipleTables
                        )
                    }

                    // 过滤阵亡弟子
                    val attackerDisc = currentGameData
                        .aiSectDisciples[result.attackerSectId]
                        ?: emptyList()
                    val updatedAttacker = attackerDisc
                        .filter { it.id !in result.deadAttackerIds }
                    val defenderDisc = currentGameData
                        .aiSectDisciples[result.defenderSectId]
                        ?: emptyList()
                    val updatedDefender = defenderDisc
                        .filter { it.id !in result.deadDefenderIds }

                    var updatedData = gameData.copy(
                        aiSectDisciples = gameData.aiSectDisciples
                            .toMutableMap().apply {
                                this[result.attackerSectId] = updatedAttacker
                                this[result.defenderSectId] = updatedDefender
                            },
                        sectRelations = gameData.sectRelations.map { r ->
                            val relevant =
                                (r.sectId1 == result.attackerSectId &&
                                    r.sectId2 == result.defenderSectId) ||
                                    (r.sectId1 == result.defenderSectId &&
                                        r.sectId2 == result.attackerSectId)
                            if (relevant) r.copy(
                                favor = (r.favor - 10).coerceIn(
                                    GameConfig.Diplomacy.MIN_FAVOR,
                                    GameConfig.Diplomacy.MAX_FAVOR
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
    }

    private fun updatePlayerGarrisonState(
        result: AISectAttackManager.AIAttackResult,
        tables: DiscipleTables
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
        tables.clear()
        updated.forEach { tables.insert(it) }
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

    fun processSectDisciplesYearlyRecruitment(year: Int) {
        val data = stateStore.gameData.value
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
                    updatedAiDisciples[sect.occupierSectId] = occupierDisciples + newRecruits
                }
                else -> {
                    updatedAiDisciples[sectId] = disciples + newRecruits
                }
            }
        }
        scope.launch {
            stateStore.update {
                gameData = gameData.copy(
                    aiSectDisciples = gameData.aiSectDisciples.mapValues { (sId, current) ->
                        val calculated = updatedAiDisciples[sId] ?: return@mapValues current
                        val currentIds = current.map { it.id }.toSet()
                        current + calculated.filter { it.id !in currentIds }
                    },
                    recruitList = updatedRecruitList
                )
            }
        }
    }

    fun processSectDisciplesAging(year: Int) {
        val data = stateStore.gameData.value
        val updatedAiDisciples = data.aiSectDisciples.mapValues { (sectId, disciples) ->
            val sect = data.worldMapSects.find { it.id == sectId }
            if (sect == null || sect.isPlayerSect) return@mapValues disciples
            AISectDiscipleManager.processAging(disciples)
        }
        // 年度老化仅修改年龄，不改变境界，无需同步宗门等级
        scope.launch {
            stateStore.update {
                gameData = gameData.copy(
                    aiSectDisciples = gameData.aiSectDisciples.mapValues { (sId, current) ->
                        val calculated = updatedAiDisciples[sId] ?: return@mapValues current
                        val currentIds = current.map { it.id }.toSet()
                        calculated.filter { it.id in currentIds }
                    }
                )
            }
        }
    }

}
