package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.launch
import kotlin.random.Random
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.*
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.domain.exploration.CaveExplorationSystem
import com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager
import com.xianxia.sect.core.engine.domain.battle.AISectAttackManager
import com.xianxia.sect.core.engine.domain.battle.AISectGarrisonManager
import com.xianxia.sect.core.engine.domain.battle.AIBattleWinner
import com.xianxia.sect.core.util.AnalyticsTracker
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.engine.SectWarehouseManager
import com.xianxia.sect.core.util.DomainLog
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
    private val sectWarehouseManager: SectWarehouseManager
) {
    private val scope get() = scopeProvider.scope

    companion object {
        private const val TAG = "CaveExplorationProc"
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
            } catch (e: Exception) {
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

        val battleResult = if (aiTeamInCave != null) {
            val battle = CaveExplorationSystem.createAIBattle(
                playerDisciples = teamMembers,
                playerEquipmentMap = equipmentMap,
                playerManualMap = manualMap,
                playerManualProficiencies = allProficiencies,
                aiTeam = aiTeamInCave
            )
            battleSystem.executeBattle(battle)
        } else {
            val battle = CaveExplorationSystem.createGuardianBattle(
                playerDisciples = teamMembers,
                playerEquipmentMap = equipmentMap,
                playerManualMap = manualMap,
                playerManualProficiencies = allProficiencies,
                cave = cave
            )
            battleSystem.executeBattle(battle)
        }

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
                    id = member.id,
                    name = member.name,
                    realm = member.realm,
                    realmName = member.realmName,
                    hp = member.hp,
                    maxHp = member.maxHp,
                    mp = member.mp,
                    maxMp = member.maxMp,
                    isAlive = member.isAlive,
                    portraitRes = member.portraitRes
                )
            },
            enemies = battleResult.log.enemies.map { enemy ->
                BattleLogEnemy(
                    id = enemy.id,
                    name = enemy.name,
                    realm = enemy.realm,
                    realmName = enemy.realmName,
                    realmLayer = enemy.realmLayer,
                    hp = enemy.hp,
                    maxHp = enemy.maxHp,
                    isAlive = enemy.isAlive,
                    portraitRes = enemy.portraitRes
                )
            },
            rounds = battleResult.log.rounds.map { round ->
                BattleLogRound(
                    roundNumber = round.roundNumber,
                    actions = round.actions.map { action ->
                        BattleLogAction(
                            type = action.type,
                            attacker = action.attacker,
                            attackerType = action.attackerType,
                            target = action.target,
                            damage = action.damage,
                            damageType = action.damageType,
                            isCrit = action.isCrit,
                            isKill = action.isKill,
                            message = action.message,
                            skillName = action.skillName
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

    fun processAISectOperations(year: Int, month: Int) {
        val data = stateStore.gameData.value
        val aiDisciples = data.aiSectDisciples

        val cleanedSectDetails = data.sectDetails.mapValues { (sectId, detail) ->
            val sect = data.worldMapSects.find { it.id == sectId }
            if (sect != null && !sect.isPlayerSect && detail.warehouse.items.isNotEmpty()) {
                detail.copy(warehouse = SectWarehouse())
            } else {
                detail
            }
        }

        val updatedAiDisciples = aiDisciples.mapValues { (sectId, disciples) ->
            val sect = data.worldMapSects.find { it.id == sectId }
            if (sect == null || sect.isPlayerSect) return@mapValues disciples
            AISectDiscipleManager.processMonthlyCultivation(disciples)
        }

        scope.launch {
            stateStore.update {
                gameData = gameData.copy(
                    sectDetails = cleanedSectDetails,
                    aiSectDisciples = updatedAiDisciples
                )
            }
        }

        if (month == 1) {
            processSectDisciplesYearlyRecruitment(year)
        }

        processAISectAttackDecisions()

        scope.launch {
            stateStore.update {
                gameData = AISectGarrisonManager.fillEmptyGarrisonSlots(gameData)
            }
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
                gameData = data.copy(aiSectDisciples = updatedAiDisciples, recruitList = updatedRecruitList)
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
        scope.launch {
            stateStore.update { gameData = data.copy(aiSectDisciples = updatedAiDisciples) }
        }
    }

    fun processAISectAttackDecisions() {
        val data = stateStore.gameData.value

        val aiResults = AISectAttackManager.decideAttacks(data)
        for (result in aiResults) {
            applyAIAttackResult(result)
        }

        val playerAttack = AISectAttackManager.decidePlayerAttack(data)
        if (playerAttack != null) {
            applyPlayerDefenseResult(playerAttack)
        }
    }

    fun applyAIAttackResult(result: AISectAttackManager.AIAttackResult) {
        scope.launch {
            stateStore.update {
                val currentGameData = gameData
                val attackerDisciples = currentGameData.aiSectDisciples[result.attackerSectId] ?: emptyList()
                val updatedAttackerDisciples = attackerDisciples.filter { it.id !in result.deadAttackerIds }

                val defenderDisciples = currentGameData.aiSectDisciples[result.defenderSectId] ?: emptyList()
                val updatedDefenderDisciples = defenderDisciples.filter { it.id !in result.deadDefenderIds }

                var updatedData = gameData.copy(
                    aiSectDisciples = gameData.aiSectDisciples.toMutableMap().apply {
                        this[result.attackerSectId] = updatedAttackerDisciples
                        this[result.defenderSectId] = updatedDefenderDisciples
                    }
                )

                val updatedRelations = updatedData.sectRelations.map { relation ->
                    val isRelevantRelation = (relation.sectId1 == result.attackerSectId && relation.sectId2 == result.defenderSectId) ||
                            (relation.sectId1 == result.defenderSectId && relation.sectId2 == result.attackerSectId)
                    if (isRelevantRelation) {
                        relation.copy(favor = (relation.favor - 10).coerceIn(GameConfig.Diplomacy.MIN_FAVOR, GameConfig.Diplomacy.MAX_FAVOR))
                    } else {
                        relation
                    }
                }
                updatedData = updatedData.copy(sectRelations = updatedRelations)

                if (result.winner == AIBattleWinner.ATTACKER && result.canOccupy) {
                    val mergedAttackerDisciples = updatedAttackerDisciples + updatedDefenderDisciples
                    val garrisonSlots = (0 until 10).map { index ->
                        if (index < result.survivingAttackers.size) {
                            val d = result.survivingAttackers[index]
                            GarrisonSlot(
                                index = index,
                                discipleId = d.id,
                                discipleName = d.name,
                                discipleRealm = d.realmName,
                                discipleSpiritRootColor = d.spiritRoot.countColor,
                                portraitRes = d.portraitRes
                            )
                        } else {
                            GarrisonSlot(index = index)
                        }
                    }
                    updatedData = updatedData.copy(
                        worldMapSects = updatedData.worldMapSects.map { sect ->
                            if (sect.id == result.defenderSectId) {
                                sect.copy(
                                    occupierSectId = result.attackerSectId,
                                    garrisonSlots = garrisonSlots
                                )
                            } else {
                                sect
                            }
                        },
                        aiSectDisciples = updatedData.aiSectDisciples.toMutableMap().apply {
                            this[result.attackerSectId] = mergedAttackerDisciples
                            this[result.defenderSectId] = emptyList()
                        }
                    )
                }

                gameData = updatedData
            }
        }
    }

    fun applyPlayerDefenseResult(result: AISectAttackManager.AIAttackResult) {
        scope.launch {
            stateStore.update {
                val currentDisciples = discipleTables.assembleAll()
                val currentGameData = gameData

                val deadDefenders = currentDisciples.filter { it.id in result.deadDefenderIds }
                var newDisciples = currentDisciples
                if (deadDefenders.isNotEmpty()) {
                    newDisciples = DiscipleStatCalculator.applyGriefToRelatives(
                        newDisciples, deadDefenders, currentGameData.gameYear
                    )
                }

                newDisciples = newDisciples.map { d ->
                    if (d.id in result.deadDefenderIds) d.copy(isAlive = false, status = DiscipleStatus.DEAD) else d
                }

                discipleTables.clear()
                newDisciples.forEach { discipleTables.insert(it) }

                val attackerDisciples = currentGameData.aiSectDisciples[result.attackerSectId] ?: emptyList()
                val updatedAttackerDisciples = attackerDisciples.filter { it.id !in result.deadAttackerIds }

                var updatedData = gameData.copy(
                    aiSectDisciples = gameData.aiSectDisciples.toMutableMap().apply {
                        this[result.attackerSectId] = updatedAttackerDisciples
                    }
                )

                val playerSectId = updatedData.worldMapSects.find { it.isPlayerSect }?.id ?: return@update
                val updatedRelations = updatedData.sectRelations.map { relation ->
                    val isRelevantRelation = (relation.sectId1 == result.attackerSectId && relation.sectId2 == playerSectId) ||
                            (relation.sectId1 == playerSectId && relation.sectId2 == result.attackerSectId)
                    if (isRelevantRelation) {
                        relation.copy(favor = (relation.favor - 15).coerceIn(GameConfig.Diplomacy.MIN_FAVOR, GameConfig.Diplomacy.MAX_FAVOR))
                    } else {
                        relation
                    }
                }
                updatedData = updatedData.copy(sectRelations = updatedRelations)

                if (result.winner == AIBattleWinner.ATTACKER) {
                    val playerDetail = updatedData.sectDetails[playerSectId] ?: SectDetail(sectId = playerSectId)
                    val lootResult = sectWarehouseManager.calculateWarehouseLootLoss(playerDetail.warehouse)
                    val updatedWarehouse = sectWarehouseManager.applyLootLossToWarehouse(playerDetail.warehouse, lootResult)
                    updatedData = updatedData.copy(
                        sectDetails = updatedData.sectDetails.toMutableMap().apply {
                            this[playerSectId] = playerDetail.copy(warehouse = updatedWarehouse)
                        }
                    )
                }

                gameData = updatedData
            }
        }
    }
}
