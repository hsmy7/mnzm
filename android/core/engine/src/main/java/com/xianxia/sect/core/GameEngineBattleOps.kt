package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.engine.domain.battle.AIBattleWinner
import com.xianxia.sect.core.engine.domain.battle.AISectAttackManager
import com.xianxia.sect.core.engine.domain.battle.Battle
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.Combatant
import com.xianxia.sect.core.engine.domain.battle.WarRewards
import com.xianxia.sect.core.engine.domain.exploration.LevelGenerator
import com.xianxia.sect.core.engine.domain.exploration.MissionSystem
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.util.DomainLog

// ── Battle facade delegates ─────────────────────────────────────────

suspend fun GameEngine.processBattleCasualties(deadMemberIds: Set<String>, survivorHpMap: Map<String, Int>, survivorMpMap: Map<String, Int> = emptyMap()) = battleFacade.processBattleCasualties(deadMemberIds, survivorHpMap, survivorMpMap)
fun GameEngine.getTotalBattlesCount(): Int = battleFacade.getTotalBattlesCount()
fun GameEngine.getRecentBattles(count: Int = 10): List<BattleLog> = battleFacade.getRecentBattles(count)
fun GameEngine.getWinRate(lastNBattles: Int = 50): Double = battleFacade.getWinRate(lastNBattles)
fun GameEngine.clearPendingBattleResult() = battleFacade.clearPendingBattleResult()

// ── Attack sect ─────────────────────────────────────────────────────

suspend fun GameEngine.attackSect(sectId: String, attackSlots: List<Pair<Int, DiscipleAggregate>>) {
    ensureHeavyDataLoaded()
    val data = stateStore.gameDataSnapshot
    val targetSect = data.worldMapSects.find { it.id == sectId } ?: return
    val allDisciples = stateStore.discipleTables.assembleAll()
    val attackers = attackSlots.mapNotNull { (_, agg) -> allDisciples.find { it.id == agg.id && it.isAlive } }
    if (attackers.isEmpty()) return
    val playerSect = data.worldMapSects.find { it.isPlayerSect }
    val isAiOccupied = targetSect.occupierSectId.isNotEmpty() && targetSect.occupierSectId != playerSect?.id
    val defenderDisciples = if (isAiOccupied) {
        val occupierDisciples = data.aiSectDisciples[targetSect.occupierSectId] ?: emptyList()
        targetSect.garrisonSlots.filter { it.discipleId.isNotEmpty() }.mapNotNull { slot -> occupierDisciples.find { d -> d.id == slot.discipleId && d.isAlive } }
    } else {
        val sectDisciplePool = data.aiSectDisciples[sectId] ?: emptyList()
        sectDisciplePool.filter { it.isAlive }.sortedBy { it.realm }.take(AISectAttackManager.TEAM_SIZE)
    }
    val defenderPoolSectId = if (isAiOccupied) targetSect.occupierSectId else sectId
    val occupationPoolSectId = sectId
    val fullDefenderPool = data.aiSectDisciples[occupationPoolSectId] ?: emptyList()
    val battleResult = AISectAttackManager.executeSectBattle(attackers, targetSect, defenderDisciples, fullDefenderPool)
    val deadPlayerIds = battleResult.deadAttackerIds.toSet()
    combatService.processBattleCasualties(deadMemberIds = deadPlayerIds, survivorHpMap = battleResult.survivorHpMap, survivorMpMap = battleResult.survivorMpMap, isOutsideSect = true)
    val deadDefenderIds = battleResult.deadDefenderIds.toSet()
    stateStore.update {
        gameData = gameData.copy(
            aiSectDisciples = gameData.aiSectDisciples.mapValues { (sId, d) -> if (sId == defenderPoolSectId) d.filter { it.id !in deadDefenderIds } else d },
            worldMapSects = gameData.worldMapSects.map { sect ->
                if (sect.id == sectId) sect.copy(garrisonSlots = sect.garrisonSlots.map { slot -> if (slot.discipleId in deadDefenderIds) GarrisonSlot(index = slot.index) else slot }) else sect
            }
        )
    }
    val teamMembers = attackers.map { d -> BattleLogMember(id = d.id, name = d.name, realm = d.realm, realmName = d.realmName, hp = battleResult.survivorHpMap[d.id] ?: 0, maxHp = d.maxHp, mp = battleResult.survivorMpMap[d.id] ?: 0, maxMp = d.maxMp, isAlive = d.id !in deadPlayerIds, portraitRes = d.portraitRes) }
    val enemyMembers = defenderDisciples.map { d -> BattleLogEnemy(name = d.name, realm = d.realm, realmName = d.realmName, portraitRes = d.portraitRes) }
    val winResult = when (battleResult.winner) { AIBattleWinner.ATTACKER -> BattleResult.WIN; AIBattleWinner.DEFENDER -> BattleResult.LOSE; AIBattleWinner.DRAW -> BattleResult.DRAW }
    val log = BattleLog(year = data.gameYear, month = data.gameMonth, type = BattleType.SECT_WAR, attackerName = "玩家队伍", defenderName = targetSect.name, result = winResult, teamMembers = teamMembers, enemies = enemyMembers, turns = battleResult.turns, teamCasualties = deadPlayerIds.size, details = when (battleResult.winner) { AIBattleWinner.ATTACKER -> if (battleResult.canOccupy) "攻占了${targetSect.name}" else "击溃了${targetSect.name}的守军"; AIBattleWinner.DEFENDER -> "进攻${targetSect.name}失败"; AIBattleWinner.DRAW -> "与${targetSect.name}打成平手" })
    val existingLogs = stateStore.battleLogsSnapshot
    val updatedLogs = (existingLogs + log).takeLast(GameConfig.Logs.MAX_BATTLE_LOGS)
    stateStore.update { battleLogs = updatedLogs }
    if (battleResult.winner == AIBattleWinner.ATTACKER) {
        val sectSurvivorIds = attackers.filter { it.id !in deadPlayerIds }.map { it.id }.toSet()
        stateStore.update { discipleTables.ids.filter { it.toString() in sectSurvivorIds && discipleTables.isAlive[it] == 1 }.forEach { id -> discipleTables.soulPowers[id] = discipleTables.soulPowers[id] + 1 } }
        val warRewards: WarRewards
        if (battleResult.canOccupy) {
            val survivors = attackers.filter { it.id !in deadPlayerIds }
            val garrisonSlots = targetSect.garrisonSlots.mapIndexed { index, _ ->
                if (index < survivors.size) { val d = survivors[index]; GarrisonSlot(index = index, discipleId = d.id, discipleName = d.name, discipleRealm = d.realmName, discipleSpiritRootColor = d.spiritRoot.countColor, portraitRes = d.portraitRes) } else GarrisonSlot(index = index)
            }
            val rewardCount = (80..130).random()
            warRewards = AISectAttackManager.generateWarRewards(targetSect.level, rewardCount)
            val capturedDisciples = data.aiSectDisciples[sectId]?.filter { it.isAlive } ?: emptyList()
            stateStore.update {
                gameData = gameData.copy(
                    worldMapSects = gameData.worldMapSects.map { sect -> if (sect.id == sectId) sect.copy(isPlayerOccupied = true, occupierSectId = playerSect?.id ?: "", garrisonSlots = garrisonSlots) else sect },
                    recruitList = gameData.recruitList + capturedDisciples,
                    aiSectDisciples = gameData.aiSectDisciples.toMutableMap().apply { this[sectId] = emptyList() }
                )
                addSpiritStones(warRewards.spiritStones)
                warRewards.equipmentStacks.forEach { inventorySystem.addEquipmentStack(it) }; warRewards.manualStacks.forEach { inventorySystem.addManualStack(it) }
                warRewards.pills.forEach { inventorySystem.addPill(it) }; warRewards.materials.forEach { inventorySystem.addMaterial(it) }
                warRewards.herbs.forEach { inventorySystem.addHerb(it) }; warRewards.seeds.forEach { inventorySystem.addSeed(it) }
            }
        } else {
            val rewardCount = (20..60).random()
            warRewards = AISectAttackManager.generateWarRewards(targetSect.level, rewardCount)
            stateStore.update {
                addSpiritStones(warRewards.spiritStones)
                warRewards.equipmentStacks.forEach { inventorySystem.addEquipmentStack(it) }; warRewards.manualStacks.forEach { inventorySystem.addManualStack(it) }
                warRewards.pills.forEach { inventorySystem.addPill(it) }; warRewards.materials.forEach { inventorySystem.addMaterial(it) }
                warRewards.herbs.forEach { inventorySystem.addHerb(it) }; warRewards.seeds.forEach { inventorySystem.addSeed(it) }
            }
        }
        stateStore.setPendingBattleResult(BattleResultUIData(battleLogId = log.id, victory = true, teamMembers = teamMembers, rewards = warRewardsToBattleRewardItems(warRewards)))
        stateStore.setPendingBattleRewardCards(buildBattleRewardCards(warRewards))
    } else {
        stateStore.setPendingBattleResult(BattleResultUIData(battleLogId = log.id, victory = false, teamMembers = teamMembers, rewards = emptyList()))
    }
}

	
	private fun GameEngine.buildBattleRewardCards(rewards: WarRewards): List<RewardCardItem> {
	    val cards = mutableListOf<RewardCardItem>()
	    if (rewards.spiritStones > 0) {
	        cards.add(RewardCardItem(itemName = "灵石", itemType = "spiritStones", rarity = 1, quantity = rewards.spiritStones.toInt()))
	    }
	    rewards.equipmentStacks.forEach { cards.add(RewardCardItem(itemName = it.name, itemType = "equipment", rarity = it.rarity, quantity = it.quantity)) }
	    rewards.manualStacks.forEach { cards.add(RewardCardItem(itemName = it.name, itemType = "manual", rarity = it.rarity, quantity = it.quantity)) }
	    rewards.pills.forEach { cards.add(RewardCardItem(itemName = it.name, itemType = "pill", rarity = it.rarity, quantity = it.quantity)) }
	    rewards.materials.forEach { cards.add(RewardCardItem(itemName = it.name, itemType = "material", rarity = it.rarity, quantity = it.quantity)) }
	    rewards.herbs.forEach { cards.add(RewardCardItem(itemName = it.name, itemType = "herb", rarity = it.rarity, quantity = it.quantity)) }
	    rewards.seeds.forEach { cards.add(RewardCardItem(itemName = it.name, itemType = "seed", rarity = it.rarity, quantity = it.quantity)) }
	    return cards
	}

private fun warRewardsToBattleRewardItems(rewards: WarRewards): List<BattleRewardItem> {
    val items = mutableListOf<BattleRewardItem>()
    if (rewards.spiritStones > 0) items.add(BattleRewardItem(name = "灵石", quantity = rewards.spiritStones.toInt(), rarity = 1, type = "spiritStones"))
    rewards.equipmentStacks.forEach { items.add(BattleRewardItem(itemId = it.id, name = it.name, quantity = it.quantity, rarity = it.rarity, type = "equipment")) }
    rewards.manualStacks.forEach { items.add(BattleRewardItem(itemId = it.id, name = it.name, quantity = it.quantity, rarity = it.rarity, type = "manual")) }
    rewards.pills.forEach { items.add(BattleRewardItem(itemId = it.id, name = it.name, quantity = it.quantity, rarity = it.rarity, type = "pill")) }
    rewards.materials.forEach { items.add(BattleRewardItem(itemId = it.id, name = it.name, quantity = it.quantity, rarity = it.rarity, type = "material")) }
    rewards.herbs.forEach { items.add(BattleRewardItem(itemId = it.id, name = it.name, quantity = it.quantity, rarity = it.rarity, type = "herb")) }
    rewards.seeds.forEach { items.add(BattleRewardItem(itemId = it.id, name = it.name, quantity = it.quantity, rarity = it.rarity, type = "seed")) }
    return items
}

// ── Garrison ────────────────────────────────────────────────────────

suspend fun GameEngine.assignGarrisonDisciple(sectId: String, slotIndex: Int, discipleId: String) {
    val data = stateStore.gameDataSnapshot
    val disciple = stateStore.discipleTables.assemble(discipleId.toInt()).takeIf { it.isAlive } ?: return
    val targetSect = data.worldMapSects.find { it.id == sectId } ?: return
    if (targetSect.garrisonSlots.any { it.discipleId == discipleId }) return
    stateStore.update {
        gameData = gameData.copy(worldMapSects = gameData.worldMapSects.map { sect ->
            if (sect.id == sectId) sect.copy(garrisonSlots = sect.garrisonSlots.map { slot ->
                if (slot.index == slotIndex) GarrisonSlot(index = slotIndex, discipleId = disciple.id, discipleName = disciple.name, discipleRealm = disciple.realmName, discipleSpiritRootColor = disciple.spiritRoot.countColor, portraitRes = disciple.portraitRes) else slot
            }) else sect
        })
    }
}

suspend fun GameEngine.removeGarrisonDisciple(sectId: String, slotIndex: Int) {
    stateStore.update {
        gameData = gameData.copy(worldMapSects = gameData.worldMapSects.map { sect ->
            if (sect.id == sectId) sect.copy(garrisonSlots = sect.garrisonSlots.map { slot -> if (slot.index == slotIndex) GarrisonSlot(index = slotIndex) else slot }) else sect
        })
    }
}

// ── Attack world level ──────────────────────────────────────────────

suspend fun GameEngine.attackWorldLevel(levelId: String, discipleIds: List<String?>) {
    val data = stateStore.gameDataSnapshot
    val level = data.worldLevels.find { it.id == levelId } ?: return
    if (level.defeated) return
    val validIds = discipleIds.filterNotNull()
    if (validIds.isEmpty()) return
    val allDisciples = stateStore.discipleTables.assembleAll()
    val combatDisciples = validIds.mapNotNull { id -> allDisciples.find { it.id == id && it.isAlive } }
    if (combatDisciples.isEmpty()) return
    val equipmentMap = stateStore.equipmentInstancesSnapshot.associateBy { it.id }
    val manualMap = stateStore.manualInstancesSnapshot.associateBy { it.id }
    val beastTypeName = if (level.isBeast) GameConfig.Beast.getType(level.beastType ?: 0).name else null
    val allProficiencies = data.manualProficiencies.mapValues { (_, list) -> list.associateBy { it.manualId } }
    val battle = battleSystem.createBattle(disciples = combatDisciples, equipmentMap = equipmentMap, manualMap = manualMap, beastLevel = level.realm, beastCount = level.count, beastType = beastTypeName, manualProficiencies = allProficiencies)
    val result = battleSystem.executeBattle(battle)
    val hpMap = result.battle.team.associate { it.id to (it.hp to it.mp) }
    val survivorIds = result.battle.team.filter { !it.isDead }.map { it.id }.toSet()
    stateStore.update {
        for (id in discipleTables.ids) {
            val idStr = id.toString()
            val (hp, mp) = hpMap[idStr] ?: continue
            if (idStr !in survivorIds) {
                discipleTables.isAlive[id] = 0
                discipleTables.statuses[id] = DiscipleStatus.DEAD
            } else {
                val maxHp = discipleTables.baseHps[id]
                val maxMp = discipleTables.baseMps[id]
                discipleTables.currentHps[id] = hp.coerceIn(0, maxHp)
                discipleTables.currentMps[id] = mp.coerceIn(0, maxMp)
            }
        }
    }
    val combatDiscipleIds = combatDisciples.map { it.id }.toSet()
    val deadIds = stateStore.discipleTables.ids.filter { it.toString() in combatDiscipleIds && stateStore.discipleTables.isAlive[it] == 0 }.map { it.toString() }.toSet()
    if (deadIds.isNotEmpty()) { try { combatService.processBattleCasualties(deadIds, emptyMap(), emptyMap(), isOutsideSect = true) } catch (e: Exception) { DomainLog.e("GameEngine", "processBattleCasualties failed for deadIds=$deadIds, continuing", e) } }
    val teamMembers = result.battle.team.map { m -> BattleLogMember(id = m.id, name = m.name, realm = m.realm, realmName = m.realmName, hp = m.hp, maxHp = m.maxHp, mp = m.mp, maxMp = m.maxMp, isAlive = !m.isDead, portraitRes = m.portraitRes) }
    val enemies = result.battle.beasts.map { b -> BattleLogEnemy(name = b.name, realm = b.realm, realmName = b.realmName, portraitRes = b.portraitRes) }
    val rounds = result.log.rounds.map { r -> BattleLogRound(roundNumber = r.roundNumber, actions = r.actions.map { a -> BattleLogAction(type = a.type, attacker = a.attacker, attackerType = a.attackerType, target = a.target, damage = a.damage, damageType = a.damageType, isCrit = a.isCrit, isKill = a.isKill, message = a.message) }) }
    val log = BattleLog(year = data.gameYear, month = data.gameMonth, type = BattleType.PVE, attackerName = "玩家队伍", defenderName = if (level.isBeast) level.beastName else level.guardianName, result = if (result.victory) BattleResult.WIN else BattleResult.LOSE, teamMembers = teamMembers, enemies = enemies, rounds = rounds, turns = result.turnCount, teamCasualties = teamMembers.count { !survivorIds.contains(it.name) }, beastsDefeated = if (result.victory) level.count else result.battle.beasts.count { it.isDead }, details = if (result.victory) "击败了${if (level.isBeast) level.beastName else level.guardianName}" else "被${if (level.isBeast) level.beastName else level.guardianName}击败")
    val existingLogs = stateStore.battleLogsSnapshot
    val updatedLogs = (existingLogs + log).takeLast(GameConfig.Logs.MAX_BATTLE_LOGS)
    if (result.victory) {
        stateStore.update {
            for (id in discipleTables.ids) {
                val idStr = id.toString()
                if (idStr in survivorIds && discipleTables.isAlive[id] == 1) {
                    discipleTables.soulPowers[id] = discipleTables.soulPowers[id] + 1
                    if (discipleTables.talentIds[id].any { tid -> TalentDatabase.getById(tid)?.effects?.containsKey("winBattleRandomAttrPlus") == true }) {
                        val r = kotlin.random.Random.nextInt(17)
                        when (r) {
                            0 -> discipleTables.intelligences[id] = discipleTables.intelligences[id] + 1
                            1 -> discipleTables.comprehensions[id] = discipleTables.comprehensions[id] + 1
                            2 -> discipleTables.charms[id] = discipleTables.charms[id] + 1
                            3 -> discipleTables.loyalties[id] = discipleTables.loyalties[id] + 1
                            4 -> discipleTables.artifactRefinings[id] = discipleTables.artifactRefinings[id] + 1
                            5 -> discipleTables.pillRefinings[id] = discipleTables.pillRefinings[id] + 1
                            6 -> discipleTables.spiritPlantings[id] = discipleTables.spiritPlantings[id] + 1
                            7 -> discipleTables.minings[id] = discipleTables.minings[id] + 1
                            8 -> discipleTables.teachings[id] = discipleTables.teachings[id] + 1
                            9 -> discipleTables.moralities[id] = discipleTables.moralities[id] + 1
                            10 -> discipleTables.baseHps[id] = discipleTables.baseHps[id] + 1
                            11 -> discipleTables.baseMps[id] = discipleTables.baseMps[id] + 1
                            12 -> discipleTables.basePhysicalAttacks[id] = discipleTables.basePhysicalAttacks[id] + 1
                            13 -> discipleTables.baseMagicAttacks[id] = discipleTables.baseMagicAttacks[id] + 1
                            14 -> discipleTables.basePhysicalDefenses[id] = discipleTables.basePhysicalDefenses[id] + 1
                            15 -> discipleTables.baseMagicDefenses[id] = discipleTables.baseMagicDefenses[id] + 1
                            16 -> discipleTables.baseSpeeds[id] = discipleTables.baseSpeeds[id] + 1
                        }
                    }
                }
            }
        }
        val allRewards = mutableListOf<BattleRewardItem>()
        stateStore.update {
            if (level.isBeast) { allRewards.addAll(handleBeastLevelVictory(level)); val engineSsRewards = result.rewards["spiritStones"] ?: 0; if (engineSsRewards > 0) { addSpiritStones(engineSsRewards.toLong()); allRewards.add(BattleRewardItem(name = "灵石", quantity = engineSsRewards, rarity = 1, type = "spiritStones")) } } else allRewards.addAll(handleCaveLevelVictory(level))
            gameData = gameData.copy(worldLevels = gameData.worldLevels.map { l -> if (l.id == levelId) l.copy(defeated = true) else l }); battleLogs = updatedLogs
        }
        stateStore.setPendingBattleResult(BattleResultUIData(battleLogId = log.id, victory = true, teamMembers = teamMembers, rewards = allRewards))
    } else {
        stateStore.setPendingBattleResult(BattleResultUIData(battleLogId = log.id, victory = false, teamMembers = teamMembers, rewards = emptyList()))
        stateStore.update { battleLogs = updatedLogs }
    }
}

// ── Scout sect ──────────────────────────────────────────────────────

suspend fun GameEngine.scoutSect(sectId: String, memberIds: List<String>) {
    ensureHeavyDataLoaded()
    val data = stateStore.gameDataSnapshot
    val targetSect = data.worldMapSects.find { it.id == sectId } ?: return
    val allDisciples = stateStore.discipleTables.assembleAll()
    val combatDisciples = memberIds.mapNotNull { id -> allDisciples.find { it.id == id && it.isAlive } }
    if (combatDisciples.isEmpty()) return
    val aiDefenders = (data.aiSectDisciples[sectId] ?: emptyList()).filter { it.isAlive && it.realm in 7..9 }.shuffled().take(kotlin.random.Random.nextInt(5, 11))
    val equipmentMap = stateStore.equipmentInstancesSnapshot.associateBy { it.id }
    val manualMap = stateStore.manualInstancesSnapshot.associateBy { it.id }
    val allProficiencies = data.manualProficiencies.mapValues { (_, list) -> list.associateBy { it.manualId } }
    val playerCombatants = combatDisciples.map { disciple ->
        val discipleEquipment = buildMap { disciple.equipment.weaponId?.let { id -> equipmentMap[id]?.let { put(id, it) } }; disciple.equipment.armorId?.let { id -> equipmentMap[id]?.let { put(id, it) } }; disciple.equipment.bootsId?.let { id -> equipmentMap[id]?.let { put(id, it) } }; disciple.equipment.accessoryId?.let { id -> equipmentMap[id]?.let { put(id, it) } } }
        val discipleManuals = disciple.manualIds.mapNotNull { id -> manualMap[id]?.let { id to it } }.toMap()
        val discipleProficiencies = allProficiencies[disciple.id] ?: emptyMap()
        val stats = disciple.getFinalStats(discipleEquipment, discipleManuals, discipleProficiencies)
        val effectiveHp = if (disciple.combat.currentHp < 0) stats.maxHp else disciple.combat.currentHp.coerceAtMost(stats.maxHp)
        val effectiveMp = if (disciple.combat.currentMp < 0) stats.maxMp else disciple.combat.currentMp.coerceAtMost(stats.maxMp)
        val skills = disciple.manualIds.mapNotNull { manualId ->
            val manual = discipleManuals[manualId] ?: return@mapNotNull null
            val proficiencyData = discipleProficiencies[manualId]
            val masteryLevel = proficiencyData?.masteryLevel ?: 0
            val baseSkill = manual.skill ?: return@mapNotNull null
            val multiplier = ManualProficiencySystem.calculateSkillDamageMultiplier(baseSkill.damageMultiplier, masteryLevel)
            baseSkill.copy(damageMultiplier = multiplier).toCombatSkill(manualName = manual.name)
        }
        Combatant(id = disciple.id, name = disciple.name, side = CombatantSide.DEFENDER, hp = effectiveHp, maxHp = stats.maxHp, mp = effectiveMp, maxMp = stats.maxMp, physicalAttack = stats.physicalAttack, magicAttack = stats.magicAttack, physicalDefense = stats.physicalDefense, magicDefense = stats.magicDefense, speed = stats.speed, critRate = stats.critRate, skills = skills, realm = disciple.realm, realmName = disciple.realmName, element = disciple.spiritRoot.types.firstOrNull()?.trim() ?: "metal", portraitRes = disciple.portraitRes)
    }
    val aiCombatants = aiDefenders.map { d -> AISectAttackManager.convertToCombatant(d, CombatantSide.ATTACKER) }
    val battle = Battle(team = playerCombatants, beasts = aiCombatants, turn = 0, isFinished = false, winner = null, maxTurns = Int.MAX_VALUE)
    val result = battleSystem.executeBattle(battle)
    val hpMap = result.battle.team.associate { it.id to (it.hp to it.mp) }
    val survivorIds = result.battle.team.filter { !it.isDead }.map { it.id }.toSet()
    stateStore.update {
        for (id in discipleTables.ids) {
            val idStr = id.toString()
            val (hp, mp) = hpMap[idStr] ?: continue
            if (idStr !in survivorIds) {
                discipleTables.isAlive[id] = 0
                discipleTables.statuses[id] = DiscipleStatus.DEAD
            } else {
                val maxHp = discipleTables.baseHps[id]
                val maxMp = discipleTables.baseMps[id]
                discipleTables.currentHps[id] = hp.coerceIn(0, maxHp)
                discipleTables.currentMps[id] = mp.coerceIn(0, maxMp)
            }
        }
    }
    val scoutIds = memberIds.toSet()
    stateStore.update {
        for (id in discipleTables.ids) {
            val idStr = id.toString()
            if (idStr in scoutIds && discipleTables.isAlive[id] == 1 && discipleTables.statuses[id] != DiscipleStatus.IDLE) {
                discipleTables.statuses[id] = DiscipleStatus.IDLE
            }
        }
    }
    val scoutDiscipleIds = combatDisciples.map { it.id }.toSet()
    val scoutDeadIds = stateStore.discipleTables.ids.filter { it.toString() in scoutDiscipleIds && stateStore.discipleTables.isAlive[it] == 0 }.map { it.toString() }.toSet()
    if (scoutDeadIds.isNotEmpty()) combatService.processBattleCasualties(scoutDeadIds, emptyMap(), emptyMap(), isOutsideSect = true)
    val teamMembers = result.battle.team.map { m -> BattleLogMember(id = m.id, name = m.name, realm = m.realm, realmName = m.realmName, hp = m.hp, maxHp = m.maxHp, mp = m.mp, maxMp = m.maxMp, isAlive = !m.isDead, portraitRes = m.portraitRes) }
    val enemies = aiCombatants.map { b -> BattleLogEnemy(name = b.name, realm = b.realm, realmName = b.realmName, portraitRes = b.portraitRes) }
    val rounds = result.log.rounds.map { r -> BattleLogRound(roundNumber = r.roundNumber, actions = r.actions.map { a -> BattleLogAction(type = a.type, attacker = a.attacker, attackerType = a.attackerType, target = a.target, damage = a.damage, damageType = a.damageType, isCrit = a.isCrit, isKill = a.isKill, message = a.message) }) }
    val victory = result.victory
    val log = BattleLog(year = data.gameYear, month = data.gameMonth, type = BattleType.SCOUT, attackerName = "探查队伍", defenderName = targetSect.name, result = if (victory) BattleResult.WIN else BattleResult.LOSE, teamMembers = teamMembers, enemies = enemies, rounds = rounds, turns = result.turnCount, teamCasualties = teamMembers.size - survivorIds.size, beastsDefeated = if (victory) aiCombatants.count { result.battle.beasts.any { b -> b.id == it.id && b.isDead } } else 0, details = if (victory) "成功探查了${targetSect.name}" else "探查${targetSect.name}失败")
    val existingLogs = stateStore.battleLogsSnapshot
    val updatedLogs = (existingLogs + log).takeLast(GameConfig.Logs.MAX_BATTLE_LOGS)
    stateStore.update { battleLogs = updatedLogs }
    stateStore.setPendingBattleResult(BattleResultUIData(battleLogId = log.id, victory = victory, teamMembers = teamMembers, rewards = emptyList()))
    if (victory) {
        val allSectDisciples = data.aiSectDisciples[sectId] ?: emptyList()
        val realmDistribution = allSectDisciples.groupingBy { it.realm }.eachCount()
        val scoutInfo = SectScoutInfo(sectId = sectId, sectName = targetSect.name, scoutYear = data.gameYear, scoutMonth = data.gameMonth, discipleCount = allSectDisciples.size, maxRealm = allSectDisciples.maxOfOrNull { it.realm } ?: 9, disciples = realmDistribution, isKnown = true, expiryYear = data.gameYear + 1, expiryMonth = data.gameMonth)
        updateGameDataSync { val existingDetail = it.sectDetails[sectId] ?: SectDetail(sectId = sectId); it.copy(sectDetails = it.sectDetails + (sectId to existingDetail.copy(scoutInfo = scoutInfo))) }
    }
}

// ── Private: Victory rewards ────────────────────────────────────────

private fun GameEngine.handleBeastLevelVictory(level: WorldLevel): List<BattleRewardItem> {
    val rewards = mutableListOf<BattleRewardItem>()
    val beastConfig = GameConfig.Beast.getType(level.beastType ?: 0)
    val tier = GameConfig.Realm.getMaxRarity(level.realm)
    for (i in 0 until level.count) {
        repeat(kotlin.random.Random.nextInt(1, 4)) {
            val beastMaterial = com.xianxia.sect.core.registry.BeastMaterialDatabase.getRandomMaterialByBeastType(beastConfig.name, tier)
            if (beastMaterial != null) {
                val material = Material(id = java.util.UUID.randomUUID().toString(), name = beastMaterial.name, rarity = beastMaterial.rarity, description = beastMaterial.description, category = beastMaterial.materialCategory, quantity = 1)
                val result = inventorySystem.addMaterial(material)
                if (result.isSuccess) rewards.add(BattleRewardItem(itemId = material.id, name = material.name, quantity = 1, rarity = material.rarity, type = "material"))
            }
        }
    }
    return rewards
}

private fun GameEngine.handleCaveLevelVictory(level: WorldLevel): List<BattleRewardItem> {
    val rewards = mutableListOf<BattleRewardItem>()
    val config = LevelGenerator.getCaveReward(level.realm)
    val spiritStones = (config.baseSpiritStones * (0.8 + kotlin.random.Random.nextDouble() * 0.4)).toLong()
    addSpiritStones(spiritStones)
    if (spiritStones > 0) rewards.add(BattleRewardItem(name = "灵石", quantity = spiritStones.toInt(), rarity = 1, type = "spiritStones"))
    val itemCount = kotlin.random.Random.nextInt(1, 7)
    val (minRarity, maxRarity) = config.rarityRange
    repeat(itemCount) {
        val rarity = kotlin.random.Random.nextInt(minRarity, maxRarity + 1)
        val typeRoll = kotlin.random.Random.nextDouble()
        when {
            typeRoll < 0.33 -> {
                val manual = com.xianxia.sect.core.registry.ManualDatabase.generateRandom(rarity)
                if (manual != null) { val result = inventorySystem.addManualStack(manual); if (result.isSuccess) rewards.add(BattleRewardItem(itemId = manual.id, name = manual.name, quantity = 1, rarity = manual.rarity, type = "manual")) }
            }
            typeRoll < 0.66 -> {
                val equip = com.xianxia.sect.core.registry.EquipmentDatabase.generateRandom(rarity)
                if (equip != null) { val result = inventorySystem.addEquipmentStack(equip); if (result.isSuccess) rewards.add(BattleRewardItem(itemId = equip.id, name = equip.name, quantity = 1, rarity = equip.rarity, type = "equipment")) }
            }
            else -> {
                val pill = com.xianxia.sect.core.registry.ItemDatabase.generateRandomPill(rarity)
                if (pill != null) { val result = inventorySystem.addPill(pill); if (result.isSuccess) rewards.add(BattleRewardItem(itemId = pill.id, name = pill.name, quantity = 1, rarity = pill.rarity, type = "pill")) }
            }
        }
    }
    return rewards
}
