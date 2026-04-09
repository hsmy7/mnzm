@file:Suppress("DEPRECATION")

package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.data.EquipmentDatabase
import com.xianxia.sect.core.data.ManualDatabase
import com.xianxia.sect.core.data.TalentDatabase
import com.xianxia.sect.core.model.AIBattleTeam
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.SectRelation
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.WorldSect
import kotlin.math.sqrt
import kotlin.random.Random

object AISectAttackManager {
    
    private val MIN_DISCIPLES_FOR_ATTACK get() = GameConfig.AI.MIN_DISCIPLES_FOR_ATTACK
    private val POWER_RATIO_THRESHOLD get() = GameConfig.AI.POWER_RATIO_THRESHOLD
    val TEAM_SIZE get() = GameConfig.AI.TEAM_SIZE
    
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
                
                val battleTeam = createAttackTeam(attacker, defender, gameData)
                if (battleTeam != null) {
                    newBattles.add(battleTeam)
                    break
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
        if (favor > 0) return false
        
        if (attacker.allianceId.isNotEmpty() && attacker.allianceId == defender.allianceId) return false
        
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
        
        if (!ManualDatabase.isInitialized || !EquipmentDatabase.isInitialized || !TalentDatabase.isInitialized) {
            var totalPower = 0.0
            for (disciple in disciples) {
                totalPower += (10 - disciple.realm) * GameConfig.AI.PowerWeights.REALM_BASE
            }
            return totalPower
        }
        
        val weights = GameConfig.AI.PowerWeights
        var totalPower = 0.0
        
        for (disciple in disciples) {
            val realmPower = (10 - disciple.realm) * weights.REALM_BASE
            
            val equipmentPower = buildMap {
                disciple.weaponId?.let { put(it, 1.0) }
                disciple.armorId?.let { put(it, 1.0) }
                disciple.bootsId?.let { put(it, 1.0) }
                disciple.accessoryId?.let { put(it, 1.0) }
            }.entries.sumOf { (id, _) ->
                EquipmentDatabase.getById(id)?.let { template ->
                    template.rarity * weights.EQUIPMENT_RARITY
                } ?: 0.0
            }
            
            val manualPower = disciple.manualIds.sumOf { manualId ->
                ManualDatabase.getById(manualId)?.let { template ->
                    val mastery = disciple.manualMasteries[manualId] ?: 0
                    template.rarity * weights.MANUAL_RARITY + mastery * weights.MANUAL_MASTERY
                } ?: 0.0
            }
            
            val talentPower = disciple.talentIds.sumOf { talentId ->
                TalentDatabase.getById(talentId)?.rarity?.times(weights.TALENT_RARITY) ?: 0.0
            }
            
            val individualPower = realmPower + equipmentPower + manualPower + talentPower
            totalPower += individualPower
        }
        
        return totalPower
    }
    
    fun createAttackTeam(attacker: WorldSect, defender: WorldSect, gameData: GameData): AIBattleTeam? {
        val availableDisciples = attacker.aiDisciples
            .filter { it.isAlive }
            .sortedBy { it.realm }
        
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
    
    fun createDefenseTeam(defender: WorldSect): List<Disciple> {
        return defender.aiDisciples
            .filter { it.isAlive }
            .sortedBy { it.realm }
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
            if (favor > 0) continue
            
            if (attacker.allianceId.isNotEmpty() && playerSect.allianceId == attacker.allianceId) continue
            
            return createAttackTeam(attacker, playerSect, gameData)
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
        
        while (!currentBattle.isFinished && currentBattle.turn < GameConfig.AI.MAX_BATTLE_TURNS) {
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
    
    private fun convertToCombatant(disciple: Disciple, isAttacker: Boolean): AICombatant {
        if (!ManualDatabase.isInitialized) {
            throw IllegalStateException("ManualDatabase not initialized when converting disciple ${disciple.name} to combatant")
        }
        
        val equipmentMap = buildMap {
            disciple.weaponId?.let { weaponId ->
                EquipmentDatabase.getById(weaponId)?.let { template ->
                    put(weaponId, EquipmentDatabase.createFromTemplate(template))
                }
            }
            disciple.armorId?.let { armorId ->
                EquipmentDatabase.getById(armorId)?.let { template ->
                    put(armorId, EquipmentDatabase.createFromTemplate(template))
                }
            }
            disciple.bootsId?.let { bootsId ->
                EquipmentDatabase.getById(bootsId)?.let { template ->
                    put(bootsId, EquipmentDatabase.createFromTemplate(template))
                }
            }
            disciple.accessoryId?.let { accessoryId ->
                EquipmentDatabase.getById(accessoryId)?.let { template ->
                    put(accessoryId, EquipmentDatabase.createFromTemplate(template))
                }
            }
        }
        
        val manualMap = disciple.manualIds.mapNotNull { manualId ->
            ManualDatabase.getById(manualId)?.let { template ->
                manualId to ManualDatabase.createFromTemplate(template)
            }
        }.toMap()
        
        val manualProficiencies = disciple.manualIds.associateWith { manualId ->
            val mastery = disciple.manualMasteries[manualId] ?: 0
            ManualProficiencyData(
                manualId = manualId,
                proficiency = mastery.toDouble(),
                masteryLevel = when {
                    mastery >= 100 -> 3
                    mastery >= 80 -> 2
                    mastery >= 60 -> 1
                    mastery >= 40 -> 1
                    mastery >= 20 -> 0
                    else -> 0
                }
            )
        }
        
        val stats = disciple.getFinalStats(equipmentMap, manualMap, manualProficiencies)
        
        val skills = disciple.manualIds.mapNotNull { manualId ->
            val manual = manualMap[manualId] ?: return@mapNotNull null
            val skill = manual.skill ?: return@mapNotNull null
            val proficiencyData = manualProficiencies[manualId]
            val masteryLevel = proficiencyData?.masteryLevel ?: 0
            val adjustedMultiplier = ManualProficiencySystem.calculateSkillDamageMultiplier(
                skill.damageMultiplier,
                masteryLevel
            )
            AICombatSkill(
                name = skill.name,
                damageType = if (skill.damageType == com.xianxia.sect.core.engine.DamageType.PHYSICAL) AIDamageType.PHYSICAL else AIDamageType.MAGIC,
                damageMultiplier = adjustedMultiplier,
                mpCost = skill.mpCost,
                cooldown = skill.cooldown,
                currentCooldown = 0,
                hits = skill.hits
            )
        }
        
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
            isAttacker = isAttacker,
            skills = skills,
            buffs = emptyList()
        )
    }
    
    private fun executeAIBattleTurn(battle: AIBattle): AIBattle {
        val allCombatants = (battle.attackers + battle.defenders)
            .filter { !it.isDead }
            .sortedByDescending { it.effectiveSpeed }
        
        var attackers = battle.attackers.toMutableList()
        var defenders = battle.defenders.toMutableList()
        
        allCombatants.forEach { combatant ->
            var currentCombatant = if (combatant.isAttacker) {
                attackers.find { it.id == combatant.id } ?: combatant
            } else {
                defenders.find { it.id == combatant.id } ?: combatant
            }
            
            if (currentCombatant.isDead) return@forEach
            
            val targets = if (currentCombatant.isAttacker) {
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
            
            val target = selectTarget(currentCombatant, targets)
            
            val availableSkill = currentCombatant.skills.firstOrNull { 
                it.currentCooldown == 0 && currentCombatant.mp >= it.mpCost 
            }
            
            val (finalDamage, isCrit, isPhysical) = if (availableSkill != null) {
                val isPhys = availableSkill.damageType == AIDamageType.PHYSICAL
                val attack = if (isPhys) currentCombatant.effectivePhysicalAttack else currentCombatant.effectiveMagicAttack
                val defense = if (isPhys) target.effectivePhysicalDefense else target.effectiveMagicDefense
                
                val crit = Random.nextDouble() < currentCombatant.effectiveCritRate
                val critMultiplier = if (crit) GameConfig.Battle.CRIT_MULTIPLIER else 1.0
                
                val baseDamage = (attack * availableSkill.damageMultiplier * critMultiplier - defense).coerceAtLeast(1.0)
                val variance = Random.nextDouble(0.9, 1.1)
                Triple((baseDamage * variance).toInt(), crit, isPhys)
            } else {
                val isPhys = currentCombatant.physicalAttack >= currentCombatant.magicAttack
                val attack = if (isPhys) currentCombatant.effectivePhysicalAttack else currentCombatant.effectiveMagicAttack
                val defense = if (isPhys) target.effectivePhysicalDefense else target.effectiveMagicDefense
                
                val crit = Random.nextDouble() < currentCombatant.effectiveCritRate
                val critMultiplier = if (crit) GameConfig.Battle.CRIT_MULTIPLIER else 1.0
                
                val baseDamage = (attack * critMultiplier - defense).coerceAtLeast(1.0)
                val variance = Random.nextDouble(0.9, 1.1)
                Triple((baseDamage * variance).toInt(), crit, isPhys)
            }
            
            val newHp = maxOf(0, target.hp - finalDamage)
            
            if (currentCombatant.isAttacker) {
                val targetIndex = defenders.indexOfFirst { it.id == target.id }
                if (targetIndex >= 0) {
                    defenders[targetIndex] = target.copy(hp = newHp)
                }
                
                val combatantIndex = attackers.indexOfFirst { it.id == currentCombatant.id }
                if (combatantIndex >= 0) {
                    val updatedSkills = if (availableSkill != null) {
                        currentCombatant.skills.map { skill ->
                            if (skill.name == availableSkill.name) {
                                skill.copy(currentCooldown = skill.cooldown)
                            } else {
                                skill.copy(currentCooldown = maxOf(0, skill.currentCooldown - 1))
                            }
                        }
                    } else {
                        currentCombatant.skills.map { it.copy(currentCooldown = maxOf(0, it.currentCooldown - 1)) }
                    }
                    
                    val updatedBuffs = currentCombatant.buffs.map { it.copy(remainingDuration = maxOf(0, it.remainingDuration - 1)) }.filter { it.remainingDuration > 0 }
                    
                    attackers[combatantIndex] = currentCombatant.copy(
                        mp = currentCombatant.mp - (availableSkill?.mpCost ?: 0),
                        skills = updatedSkills,
                        buffs = updatedBuffs
                    )
                    currentCombatant = attackers[combatantIndex]
                }
            } else {
                val targetIndex = attackers.indexOfFirst { it.id == target.id }
                if (targetIndex >= 0) {
                    attackers[targetIndex] = target.copy(hp = newHp)
                }
                
                val combatantIndex = defenders.indexOfFirst { it.id == currentCombatant.id }
                if (combatantIndex >= 0) {
                    val updatedSkills = if (availableSkill != null) {
                        currentCombatant.skills.map { skill ->
                            if (skill.name == availableSkill.name) {
                                skill.copy(currentCooldown = skill.cooldown)
                            } else {
                                skill.copy(currentCooldown = maxOf(0, skill.currentCooldown - 1))
                            }
                        }
                    } else {
                        currentCombatant.skills.map { it.copy(currentCooldown = maxOf(0, it.currentCooldown - 1)) }
                    }
                    
                    val updatedBuffs = currentCombatant.buffs.map { it.copy(remainingDuration = maxOf(0, it.remainingDuration - 1)) }.filter { it.remainingDuration > 0 }
                    
                    defenders[combatantIndex] = currentCombatant.copy(
                        mp = currentCombatant.mp - (availableSkill?.mpCost ?: 0),
                        skills = updatedSkills,
                        buffs = updatedBuffs
                    )
                }
            }
        }
        
        return battle.copy(
            attackers = attackers,
            defenders = defenders,
            turn = battle.turn + 1
        )
    }
    
    private fun selectTarget(attacker: AICombatant, targets: List<AICombatant>): AICombatant {
        val lowHpTargets = targets.filter { it.hp.toDouble() / it.maxHp < 0.3 }
        if (lowHpTargets.isNotEmpty() && Random.nextDouble() < 0.7) {
            return lowHpTargets.random()
        }
        
        val highThreatTargets = targets.filter { 
            it.skills.isNotEmpty() && it.effectivePhysicalAttack > attacker.effectivePhysicalDefense
        }
        if (highThreatTargets.isNotEmpty() && Random.nextDouble() < 0.5) {
            return highThreatTargets.random()
        }
        
        return targets.random()
    }
    
    fun generateSectDestroyedEvent(attackerName: String, defenderName: String): String {
        return "【宗门灭亡】${defenderName}被${attackerName}吞并！"
    }
    
    fun createPlayerDefenseTeam(
        disciples: List<Disciple>,
        equipmentMap: Map<String, com.xianxia.sect.core.model.Equipment>,
        manualMap: Map<String, com.xianxia.sect.core.model.Manual>,
        manualProficiencies: Map<String, Map<String, ManualProficiencyData>>
    ): List<AICombatant> {
        return disciples
            .filter { it.status == DiscipleStatus.IDLE }
            .sortedBy { it.realm }
            .take(TEAM_SIZE)
            .map { disciple ->
                val discipleEquipment = buildMap {
                    disciple.weaponId?.let { id -> equipmentMap[id]?.let { put(id, it) } }
                    disciple.armorId?.let { id -> equipmentMap[id]?.let { put(id, it) } }
                    disciple.bootsId?.let { id -> equipmentMap[id]?.let { put(id, it) } }
                    disciple.accessoryId?.let { id -> equipmentMap[id]?.let { put(id, it) } }
                }
                val discipleManuals = disciple.manualIds.mapNotNull { id -> manualMap[id]?.let { id to it } }.toMap()
                val discipleProficiencies = manualProficiencies[disciple.id] ?: emptyMap()
                val stats = disciple.getFinalStats(discipleEquipment, discipleManuals, discipleProficiencies)
                
                val skills = disciple.manualIds.mapNotNull { manualId ->
                    val manual = discipleManuals[manualId] ?: return@mapNotNull null
                    val skill = manual.skill ?: return@mapNotNull null
                    val proficiencyData = discipleProficiencies[manualId]
                    val masteryLevel = proficiencyData?.masteryLevel ?: 0
                    val adjustedMultiplier = ManualProficiencySystem.calculateSkillDamageMultiplier(
                        skill.damageMultiplier,
                        masteryLevel
                    )
                    AICombatSkill(
                        name = skill.name,
                        damageType = if (skill.damageType == com.xianxia.sect.core.engine.DamageType.PHYSICAL) AIDamageType.PHYSICAL else AIDamageType.MAGIC,
                        damageMultiplier = adjustedMultiplier,
                        mpCost = skill.mpCost,
                        cooldown = skill.cooldown,
                        currentCooldown = 0,
                        hits = skill.hits
                    )
                }
                
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
                    isAttacker = false,
                    skills = skills,
                    buffs = emptyList()
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
        
        while (!currentBattle.isFinished && currentBattle.turn < GameConfig.AI.MAX_BATTLE_TURNS) {
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
    val isAttacker: Boolean,
    val skills: List<AICombatSkill> = emptyList(),
    val buffs: List<AICombatBuff> = emptyList()
) {
    val isDead: Boolean get() = hp <= 0
    
    val effectivePhysicalAttack: Int get() {
        val attackBoost = buffs.filter { it.type == AIBuffType.PHYSICAL_ATTACK_BOOST }.sumOf { it.value }
        return (physicalAttack * (1 + attackBoost)).toInt()
    }
    
    val effectiveMagicAttack: Int get() {
        val attackBoost = buffs.filter { it.type == AIBuffType.MAGIC_ATTACK_BOOST }.sumOf { it.value }
        return (magicAttack * (1 + attackBoost)).toInt()
    }
    
    val effectivePhysicalDefense: Int get() {
        val defenseBoost = buffs.filter { it.type == AIBuffType.PHYSICAL_DEFENSE_BOOST }.sumOf { it.value }
        return (physicalDefense * (1 + defenseBoost)).toInt()
    }
    
    val effectiveMagicDefense: Int get() {
        val defenseBoost = buffs.filter { it.type == AIBuffType.MAGIC_DEFENSE_BOOST }.sumOf { it.value }
        return (magicDefense * (1 + defenseBoost)).toInt()
    }
    
    val effectiveSpeed: Int get() {
        val speedBoost = buffs.filter { it.type == AIBuffType.SPEED_BOOST }.sumOf { it.value }
        return (speed * (1 + speedBoost)).toInt()
    }
    
    val effectiveCritRate: Double get() {
        val critBoost = buffs.filter { it.type == AIBuffType.CRIT_RATE_BOOST }.sumOf { it.value }
        return critRate + critBoost
    }
}

data class AICombatSkill(
    val name: String,
    val damageType: AIDamageType,
    val damageMultiplier: Double,
    val mpCost: Int,
    val cooldown: Int,
    val currentCooldown: Int = 0,
    val hits: Int = 1
)

enum class AIDamageType {
    PHYSICAL, MAGIC
}

enum class AIBuffType {
    PHYSICAL_ATTACK_BOOST, MAGIC_ATTACK_BOOST, PHYSICAL_DEFENSE_BOOST, MAGIC_DEFENSE_BOOST,
    SPEED_BOOST, CRIT_RATE_BOOST
}

data class AICombatBuff(
    val type: AIBuffType,
    val value: Double,
    var remainingDuration: Int
)

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
