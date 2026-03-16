package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.AIBattleTeam
import com.xianxia.sect.core.model.AISectDisciple
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.SectRelation
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.WorldSect
import kotlin.math.sqrt
import kotlin.random.Random

object AISectAttackManager {
    
    private const val MIN_FAVOR_FOR_ATTACK = 20
    private const val MIN_DISCIPLES_FOR_ATTACK = 10
    private const val BASE_ATTACK_PROBABILITY = 0.03
    private const val MAX_ATTACK_PROBABILITY = 0.20
    private const val RIGHTEOUS_EVIL_BONUS = 0.10
    private const val POWER_RATIO_THRESHOLD = 0.8
    const val TEAM_SIZE = 10
    
    fun decideAttacks(gameData: GameData): List<AIBattleTeam> {
        val newBattles = mutableListOf<AIBattleTeam>()
        val existingAttacks = gameData.aiBattleTeams.filter { it.status == "moving" || it.status == "battling" }
        val attackingSectIds = existingAttacks.map { it.attackerSectId }.toSet()
        val underAttackSectIds = existingAttacks.map { it.defenderSectId }.toSet()
        
        val aiSects = gameData.worldMapSects.filter { !it.isPlayerSect && !it.isPlayerOccupied }
        
        for (attacker in aiSects) {
            if (attacker.id in attackingSectIds) continue
            if (attacker.aiDisciples.filter { it.isAlive }.size < MIN_DISCIPLES_FOR_ATTACK) continue
            
            val connectedTargets = getConnectedTargets(attacker, gameData)
            
            for (defender in connectedTargets) {
                if (defender.id in underAttackSectIds) continue
                if (defender.id == attacker.id) continue
                
                if (!checkAttackConditions(attacker, defender, gameData)) continue
                
                val probability = calculateAttackProbability(attacker, defender, gameData)
                if (Random.nextDouble() < probability) {
                    val battleTeam = createAttackTeam(attacker, defender, gameData)
                    if (battleTeam != null) {
                        newBattles.add(battleTeam)
                        break
                    }
                }
            }
        }
        
        return newBattles
    }
    
    fun checkAttackConditions(attacker: WorldSect, defender: WorldSect, gameData: GameData): Boolean {
        if (attacker.id == defender.id) return false
        
        val attackerDisciples = attacker.aiDisciples.filter { it.isAlive }
        if (attackerDisciples.size < MIN_DISCIPLES_FOR_ATTACK) return false
        
        val relation = gameData.sectRelations.find { 
            (it.sectId1 == attacker.id && it.sectId2 == defender.id) ||
            (it.sectId1 == defender.id && it.sectId2 == attacker.id)
        }
        val favor = relation?.favor ?: 0
        if (favor >= MIN_FAVOR_FOR_ATTACK) return false
        
        if (attacker.allianceId != null && attacker.allianceId == defender.allianceId) return false
        
        if (!isRouteConnected(attacker, defender, gameData)) return false
        
        val attackerPower = calculatePowerScore(attacker)
        val defenderPower = calculatePowerScore(defender)
        if (attackerPower < defenderPower * POWER_RATIO_THRESHOLD) return false
        
        return true
    }
    
    fun isRouteConnected(attacker: WorldSect, target: WorldSect, gameData: GameData): Boolean {
        if (target.id in attacker.connectedSectIds) return true
        
        val occupiedByAttacker = gameData.worldMapSects.filter { sect ->
            sect.occupierSectId == attacker.id
        }
        
        for (occupied in occupiedByAttacker) {
            if (target.id in occupied.connectedSectIds) return true
        }
        
        return false
    }
    
    fun calculatePowerScore(sect: WorldSect): Double {
        val disciples = sect.aiDisciples.filter { it.isAlive }
        if (disciples.isEmpty()) return 0.0
        
        val avgRealm = disciples.map { it.realm }.average()
        val powerScore = disciples.size * (10.0 - avgRealm)
        
        return powerScore
    }
    
    fun calculateAttackProbability(attacker: WorldSect, defender: WorldSect, gameData: GameData): Double {
        var probability = BASE_ATTACK_PROBABILITY
        
        val relation = gameData.sectRelations.find { 
            (it.sectId1 == attacker.id && it.sectId2 == defender.id) ||
            (it.sectId1 == defender.id && it.sectId2 == attacker.id)
        }
        val favor = relation?.favor ?: 0
        val favorPenalty = (MIN_FAVOR_FOR_ATTACK - favor) * 0.005
        probability += favorPenalty
        
        if (attacker.isRighteous != defender.isRighteous) {
            probability += RIGHTEOUS_EVIL_BONUS
        }
        
        return minOf(probability, MAX_ATTACK_PROBABILITY)
    }
    
    fun createAttackTeam(attacker: WorldSect, defender: WorldSect, gameData: GameData): AIBattleTeam? {
        val availableDisciples = attacker.aiDisciples
            .filter { it.isAlive }
            .sortedByDescending { it.realm }
        
        if (availableDisciples.size < MIN_DISCIPLES_FOR_ATTACK) return null
        
        val selectedDisciples = availableDisciples.take(TEAM_SIZE)
        
        return AIBattleTeam(
            id = java.util.UUID.randomUUID().toString(),
            attackerSectId = attacker.id,
            attackerSectName = attacker.name,
            defenderSectId = defender.id,
            defenderSectName = defender.name,
            disciples = selectedDisciples,
            currentX = attacker.x,
            currentY = attacker.y,
            targetX = defender.x,
            targetY = defender.y,
            attackerStartX = attacker.x,
            attackerStartY = attacker.y,
            moveProgress = 0f,
            status = "moving",
            route = emptyList(),
            currentRouteIndex = 0,
            startYear = gameData.gameYear,
            startMonth = gameData.gameMonth,
            isPlayerDefender = defender.isPlayerSect
        )
    }
    
    fun createDefenseTeam(defender: WorldSect): List<AISectDisciple> {
        return defender.aiDisciples
            .filter { it.isAlive }
            .sortedByDescending { it.realm }
            .take(TEAM_SIZE)
    }
    
    fun getConnectedTargets(sect: WorldSect, gameData: GameData): List<WorldSect> {
        val targets = mutableSetOf<WorldSect>()
        
        for (connectedId in sect.connectedSectIds) {
            val connectedSect = gameData.worldMapSects.find { it.id == connectedId }
            if (connectedSect != null && !connectedSect.isPlayerSect) {
                targets.add(connectedSect)
            }
        }
        
        val occupiedBySect = gameData.worldMapSects.filter { it.occupierSectId == sect.id }
        for (occupied in occupiedBySect) {
            for (connectedId in occupied.connectedSectIds) {
                val connectedSect = gameData.worldMapSects.find { it.id == connectedId }
                if (connectedSect != null && !connectedSect.isPlayerSect && connectedSect.id != sect.id) {
                    targets.add(connectedSect)
                }
            }
        }
        
        return targets.toList()
    }
    
    fun checkAllianceSupport(gameData: GameData): List<AIBattleTeam> {
        val supportBattles = mutableListOf<AIBattleTeam>()
        val existingAttacks = gameData.aiBattleTeams.filter { it.status == "moving" || it.status == "battling" }
        
        for (attack in existingAttacks) {
            val defender = gameData.worldMapSects.find { it.id == attack.defenderSectId } ?: continue
            val attacker = gameData.worldMapSects.find { it.id == attack.attackerSectId } ?: continue
            
            if (defender.allianceId == null) continue
            
            val allies = gameData.worldMapSects.filter { sect ->
                sect.allianceId == defender.allianceId && sect.id != defender.id
            }
            
            for (ally in allies) {
                val relationToDefender = gameData.sectRelations.find {
                    (it.sectId1 == ally.id && it.sectId2 == defender.id) ||
                    (it.sectId1 == defender.id && it.sectId2 == ally.id)
                }
                
                if ((relationToDefender?.favor ?: 0) <= 90) continue
                
                if (!isRouteConnected(ally, attacker, gameData)) continue
                
                if (Random.nextDouble() < 0.032) {
                    val battleTeam = createAttackTeam(ally, attacker, gameData)
                    if (battleTeam != null) {
                        supportBattles.add(battleTeam)
                    }
                }
            }
        }
        
        return supportBattles
    }
    
    fun decidePlayerAttack(gameData: GameData): AIBattleTeam? {
        if (gameData.isPlayerProtected) return null
        
        val existingAttacks = gameData.aiBattleTeams.filter { it.status == "moving" || it.status == "battling" }
        val attackingSectIds = existingAttacks.map { it.attackerSectId }.toSet()
        
        val playerSect = gameData.worldMapSects.find { it.isPlayerSect } ?: return null
        
        val aiSects = gameData.worldMapSects.filter { !it.isPlayerSect && !it.isPlayerOccupied }
        
        for (attacker in aiSects) {
            if (attacker.id in attackingSectIds) continue
            if (attacker.aiDisciples.filter { it.isAlive }.size < MIN_DISCIPLES_FOR_ATTACK) continue
            
            if (!isRouteConnected(attacker, playerSect, gameData)) continue
            
            val relation = gameData.sectRelations.find { 
                (it.sectId1 == attacker.id && it.sectId2 == playerSect.id) ||
                (it.sectId1 == playerSect.id && it.sectId2 == attacker.id)
            }
            val favor = relation?.favor ?: 0
            if (favor >= MIN_FAVOR_FOR_ATTACK) continue
            
            if (attacker.allianceId != null && playerSect.allianceId == attacker.allianceId) continue
            
            val probability = calculateAttackProbability(attacker, playerSect, gameData)
            if (Random.nextDouble() < probability) {
                return createAttackTeam(attacker, playerSect, gameData)
            }
        }
        
        return null
    }
    
    fun updateAIBattleTeamMovement(team: AIBattleTeam, gameData: GameData): AIBattleTeam {
        if (team.status != "moving" || team.moveProgress >= 1f) return team
        
        val attackerSect = gameData.worldMapSects.find { it.id == team.attackerSectId } ?: return team
        val defenderSect = gameData.worldMapSects.find { it.id == team.defenderSectId } ?: return team
        
        val distance = sqrt(
            (defenderSect.x - attackerSect.x) * (defenderSect.x - attackerSect.x) +
            (defenderSect.y - attackerSect.y) * (defenderSect.y - attackerSect.y)
        )
        
        val duration = (distance / 100f).coerceAtLeast(1f).toInt()
        val progressIncrement = 1f / duration.coerceAtLeast(1) * 1.5f
        val newProgress = (team.moveProgress + progressIncrement).coerceAtMost(1f)
        
        val newX = attackerSect.x + (defenderSect.x - attackerSect.x) * newProgress
        val newY = attackerSect.y + (defenderSect.y - attackerSect.y) * newProgress
        
        return if (newProgress >= 1f) {
            team.copy(
                status = "battling",
                moveProgress = 1f,
                currentX = defenderSect.x,
                currentY = defenderSect.y
            )
        } else {
            team.copy(
                moveProgress = newProgress,
                currentX = newX,
                currentY = newY
            )
        }
    }
    
    fun isTeamArrived(team: AIBattleTeam): Boolean {
        return team.status == "battling" && team.moveProgress >= 1f
    }
    
    fun executeAISectBattle(
        attackTeam: AIBattleTeam,
        defenderSect: WorldSect
    ): AIBattleResult {
        val attackers = attackTeam.disciples.map { convertToCombatant(it, true) }
        val defenders = createDefenseTeam(defenderSect).map { convertToCombatant(it, false) }
        
        var currentBattle = AIBattle(
            attackers = attackers,
            defenders = defenders,
            turn = 0,
            isFinished = false,
            winner = null
        )
        
        while (!currentBattle.isFinished && currentBattle.turn < 25) {
            currentBattle = executeAIBattleTurn(currentBattle)
        }
        
        val aliveAttackers = currentBattle.attackers.count { !it.isDead }
        val aliveDefenders = currentBattle.defenders.count { !it.isDead }
        
        val winner = when {
            aliveDefenders == 0 -> AIBattleWinner.ATTACKER
            aliveAttackers == 0 -> AIBattleWinner.DEFENDER
            else -> AIBattleWinner.DRAW
        }
        
        val finalBattle = currentBattle.copy(
            isFinished = true,
            winner = winner
        )
        
        val deadAttackerIds = attackTeam.disciples
            .filter { disciple -> 
                finalBattle.attackers.find { it.id == disciple.id }?.isDead == true 
            }
            .map { it.id }
        
        val deadDefenderIds = defenderSect.aiDisciples
            .filter { disciple ->
                finalBattle.defenders.find { it.id == disciple.id }?.isDead == true
            }
            .map { it.id }
        
        val highRealmDefendersAlive = defenderSect.aiDisciples
            .filter { it.isAlive && it.realm <= 5 }
            .none { it.id !in deadDefenderIds }
        
        return AIBattleResult(
            battle = finalBattle,
            winner = winner,
            deadAttackerIds = deadAttackerIds,
            deadDefenderIds = deadDefenderIds,
            canOccupy = winner == AIBattleWinner.ATTACKER && highRealmDefendersAlive
        )
    }
    
    private fun convertToCombatant(disciple: AISectDisciple, isAttacker: Boolean): AICombatant {
        val stats = disciple.getBaseStats()
        val talents = disciple.getTalentEffects()
        
        return AICombatant(
            id = disciple.id,
            name = disciple.name,
            hp = stats.maxHp,
            maxHp = stats.maxHp,
            mp = stats.maxMp,
            maxMp = stats.maxMp,
            physicalAttack = stats.physicalAttack,
            magicAttack = stats.magicAttack,
            physicalDefense = stats.physicalDefense,
            magicDefense = stats.magicDefense,
            speed = stats.speed,
            critRate = stats.critRate,
            realm = disciple.realm,
            realmName = disciple.realmName,
            isAttacker = isAttacker
        )
    }
    
    private fun executeAIBattleTurn(battle: AIBattle): AIBattle {
        val allCombatants = (battle.attackers + battle.defenders)
            .filter { !it.isDead }
            .sortedByDescending { it.speed }
        
        var attackers = battle.attackers.toMutableList()
        var defenders = battle.defenders.toMutableList()
        
        allCombatants.forEach { combatant ->
            if (combatant.isDead) return@forEach
            
            val targets = if (combatant.isAttacker) {
                defenders.filter { !it.isDead }
            } else {
                attackers.filter { !it.isDead }
            }
            
            if (targets.isEmpty()) {
                return battle.copy(
                    attackers = attackers,
                    defenders = defenders,
                    isFinished = true
                )
            }
            
            val target = targets.random()
            
            val isPhysical = combatant.physicalAttack >= combatant.magicAttack
            val attack = if (isPhysical) combatant.physicalAttack else combatant.magicAttack
            val defense = if (isPhysical) target.physicalDefense else target.magicDefense
            
            val isCrit = Random.nextDouble() < combatant.critRate
            val critMultiplier = if (isCrit) 1.5 else 1.0
            
            val baseDamage = (attack * critMultiplier - defense).coerceAtLeast(1.0)
            val variance = Random.nextDouble(0.9, 1.1)
            val finalDamage = (baseDamage * variance).toInt()
            
            val newHp = maxOf(0, target.hp - finalDamage)
            
            if (combatant.isAttacker) {
                val targetIndex = defenders.indexOfFirst { it.id == target.id }
                if (targetIndex >= 0) {
                    defenders[targetIndex] = target.copy(hp = newHp)
                }
            } else {
                val targetIndex = attackers.indexOfFirst { it.id == target.id }
                if (targetIndex >= 0) {
                    attackers[targetIndex] = target.copy(hp = newHp)
                }
            }
        }
        
        return battle.copy(
            attackers = attackers,
            defenders = defenders,
            turn = battle.turn + 1
        )
    }
    
    fun generateSectDestroyedEvent(attackerName: String, defenderName: String): String {
        return "【宗门灭亡】${defenderName}被${attackerName}吞并！"
    }
    
    fun createPlayerDefenseTeam(disciples: List<Disciple>): List<AICombatant> {
        return disciples
            .filter { it.status == DiscipleStatus.IDLE }
            .sortedByDescending { it.realm }
            .take(TEAM_SIZE)
            .map { disciple ->
                val stats = disciple.getBaseStats()
                AICombatant(
                    id = disciple.id,
                    name = disciple.name,
                    hp = stats.maxHp,
                    maxHp = stats.maxHp,
                    mp = stats.maxMp,
                    maxMp = stats.maxMp,
                    physicalAttack = stats.physicalAttack,
                    magicAttack = stats.magicAttack,
                    physicalDefense = stats.physicalDefense,
                    magicDefense = stats.magicDefense,
                    speed = stats.speed,
                    critRate = stats.critRate,
                    realm = disciple.realm,
                    realmName = disciple.realmName,
                    isAttacker = false
                )
            }
    }
    
    fun executePlayerSectBattle(attackTeam: AIBattleTeam, playerDefenders: List<AICombatant>): AIBattleResult {
        val attackers = attackTeam.disciples.map { convertToCombatant(it, true) }
        val defenders = playerDefenders
        
        var currentBattle = AIBattle(
            attackers = attackers,
            defenders = defenders,
            turn = 0,
            isFinished = false,
            winner = null
        )
        
        while (!currentBattle.isFinished && currentBattle.turn < 25) {
            currentBattle = executeAIBattleTurn(currentBattle)
        }
        
        val aliveAttackers = currentBattle.attackers.count { !it.isDead }
        val aliveDefenders = currentBattle.defenders.count { !it.isDead }
        
        val winner = when {
            aliveDefenders == 0 -> AIBattleWinner.ATTACKER
            aliveAttackers == 0 -> AIBattleWinner.DEFENDER
            else -> AIBattleWinner.DRAW
        }
        
        val finalBattle = currentBattle.copy(
            isFinished = true,
            winner = winner
        )
        
        val deadAttackerIds = attackTeam.disciples
            .filter { disciple -> 
                finalBattle.attackers.find { it.id == disciple.id }?.isDead == true 
            }
            .map { it.id }
        
        val deadDefenderIds = playerDefenders
            .filter { defender ->
                finalBattle.defenders.find { it.id == defender.id }?.isDead == true
            }
            .map { it.id }
        
        return AIBattleResult(
            battle = finalBattle,
            winner = winner,
            deadAttackerIds = deadAttackerIds,
            deadDefenderIds = deadDefenderIds,
            canOccupy = false
        )
    }
    
    fun calculatePlayerLootLoss(
        spiritStones: Long,
        materials: List<Material>,
        herbs: List<Herb>,
        seeds: List<Seed>,
        pills: List<Pill>
    ): LootLossResult {
        val lootItems = mutableListOf<String>()
        var remainingSpiritStones = spiritStones
        
        val spiritStoneCount = (spiritStones / 10000).toInt()
        repeat(spiritStoneCount) { lootItems.add("灵石×10000") }
        remainingSpiritStones = spiritStones % 10000
        
        materials.forEach { material ->
            repeat(material.quantity) { lootItems.add(material.name) }
        }
        herbs.forEach { herb ->
            repeat(herb.quantity) { lootItems.add(herb.name) }
        }
        seeds.forEach { seed ->
            repeat(seed.quantity) { lootItems.add(seed.name) }
        }
        pills.forEach { pill ->
            repeat(pill.quantity) { lootItems.add(pill.name) }
        }
        
        if (lootItems.size < 30 && remainingSpiritStones > 0) {
            lootItems.add("灵石×$remainingSpiritStones")
        }
        
        val lootCount = if (lootItems.isEmpty()) 0 else minOf((30..50).random(), lootItems.size)
        val selectedItems = lootItems.shuffled().take(lootCount)
        
        val lostSpiritStones = selectedItems.mapNotNull { item ->
            if (item.startsWith("灵石×")) {
                item.removePrefix("灵石×").toLongOrNull()
            } else null
        }.sum()
        
        return LootLossResult(
            lostSpiritStones = lostSpiritStones,
            lostMaterials = selectedItems.filter { !it.startsWith("灵石") }.groupBy { it }.mapValues { it.value.size }
        )
    }
}

data class AICombatant(
    val id: String,
    val name: String,
    val hp: Int,
    val maxHp: Int,
    val mp: Int,
    val maxMp: Int,
    val physicalAttack: Int,
    val magicAttack: Int,
    val physicalDefense: Int,
    val magicDefense: Int,
    val speed: Int,
    val critRate: Double,
    val realm: Int,
    val realmName: String,
    val isAttacker: Boolean
) {
    val isDead: Boolean get() = hp <= 0
}

data class AIBattle(
    val attackers: List<AICombatant>,
    val defenders: List<AICombatant>,
    val turn: Int = 0,
    val isFinished: Boolean = false,
    val winner: AIBattleWinner? = null
)

enum class AIBattleWinner {
    ATTACKER, DEFENDER, DRAW
}

data class AIBattleResult(
    val battle: AIBattle,
    val winner: AIBattleWinner,
    val deadAttackerIds: List<String>,
    val deadDefenderIds: List<String>,
    val canOccupy: Boolean
)

data class LootLossResult(
    val lostSpiritStones: Long,
    val lostMaterials: Map<String, Int>
)
