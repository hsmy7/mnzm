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
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.engine.ManualProficiencySystem
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
        val aiDisciplesMap = gameData.aiSectDisciples
        
        val aiSects = gameData.worldMapSects.filter { !it.isPlayerSect && !it.isPlayerOccupied }
        
        for (attacker in aiSects) {
            if (attacker.id in attackingSectIds) continue
            val attackerDisciples = aiDisciplesMap[attacker.id] ?: emptyList()
            if (attackerDisciples.filter { it.isAlive }.size < MIN_DISCIPLES_FOR_ATTACK) continue
            
            val connectedTargets = getConnectedTargets(attacker, gameData)
            
            for (defender in connectedTargets) {
                if (defender.id in underAttackSectIds) continue
                if (defender.id == attacker.id) continue
                
                if (!checkAttackConditions(attacker, defender, gameData, aiDisciplesMap)) continue
                
                val battleTeam = createAttackTeam(attacker, defender, gameData, attackerDisciples)
                if (battleTeam != null) {
                    newBattles.add(battleTeam)
                    break
                }
            }
        }
        
        return newBattles
    }
    
    fun checkAttackConditions(
        attacker: WorldSect,
        defender: WorldSect,
        gameData: GameData,
        aiDisciplesMap: Map<String, List<Disciple>> = emptyMap()
    ): Boolean {
        if (attacker.id == defender.id) return false
        
        val attackerDisciples = (aiDisciplesMap[attacker.id] ?: emptyList()).filter { it.isAlive }
        if (attackerDisciples.size < MIN_DISCIPLES_FOR_ATTACK) return false
        
        val relation = gameData.sectRelations.find { 
            (it.sectId1 == attacker.id && it.sectId2 == defender.id) ||
            (it.sectId1 == defender.id && it.sectId2 == attacker.id)
        }
        val favor = relation?.favor ?: 0
        if (favor > 0) return false
        
        if (attacker.allianceId.isNotEmpty() && attacker.allianceId == defender.allianceId) return false
        
        if (!isRouteConnected(attacker, defender, gameData)) return false
        
        val attackerPower = calculatePowerScore(attackerDisciples)
        val defenderDisciples = (aiDisciplesMap[defender.id] ?: emptyList()).filter { it.isAlive }
        val defenderPower = calculatePowerScore(defenderDisciples)
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
    
    fun calculatePowerScore(disciples: List<Disciple>): Double {
        val aliveDisciples = disciples.filter { it.isAlive }
        if (aliveDisciples.isEmpty()) return 0.0
        
        if (!ManualDatabase.isInitialized || !EquipmentDatabase.isInitialized || !TalentDatabase.isInitialized) {
            var totalPower = 0.0
            for (disciple in aliveDisciples) {
                totalPower += (10 - disciple.realm) * GameConfig.AI.PowerWeights.REALM_BASE
            }
            return totalPower
        }
        
        val weights = GameConfig.AI.PowerWeights
        var totalPower = 0.0
        
        for (disciple in aliveDisciples) {
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
    
    fun createAttackTeam(
        attacker: WorldSect,
        defender: WorldSect,
        gameData: GameData,
        attackerDisciples: List<Disciple>
    ): AIBattleTeam? {
        val availableDisciples = attackerDisciples
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
    
    fun createDefenseTeam(defenderDisciples: List<Disciple>): List<Disciple> {
        return defenderDisciples
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
        val aiDisciplesMap = gameData.aiSectDisciples
        
        val playerSect = gameData.worldMapSects.find { it.isPlayerSect } ?: return null
        
        val aiSects = gameData.worldMapSects.filter { !it.isPlayerSect && !it.isPlayerOccupied }
        
        for (attacker in aiSects) {
            if (attacker.id in attackingSectIds) continue
            val attackerDisciples = aiDisciplesMap[attacker.id] ?: emptyList()
            if (attackerDisciples.filter { it.isAlive }.size < MIN_DISCIPLES_FOR_ATTACK) continue
            
            if (!isRouteConnected(attacker, playerSect, gameData)) continue
            
            val relation = gameData.sectRelations.find { 
                (it.sectId1 == attacker.id && it.sectId2 == playerSect.id) ||
                (it.sectId1 == playerSect.id && it.sectId2 == attacker.id)
            }
            val favor = relation?.favor ?: 0
            if (favor > 0) continue
            
            if (attacker.allianceId.isNotEmpty() && playerSect.allianceId == attacker.allianceId) continue
            
            return createAttackTeam(attacker, playerSect, gameData, attackerDisciples)
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
        defenderSect: WorldSect,
        defenderDisciples: List<Disciple>
    ): AIBattleResult {
        val attackers = attackTeam.disciples.map { convertToCombatant(it, true) }
        val defenders = createDefenseTeam(defenderDisciples).map { convertToCombatant(it, false) }
        
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
        
        val deadDefenderIds = defenderDisciples
            .filter { disciple ->
                finalBattle.defenders.find { it.id == disciple.id }?.isDead == true
            }
            .map { it.id }
        
        val highRealmDefendersAlive = defenderDisciples
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
                    val eq = EquipmentDatabase.createFromTemplate(template)
                    val nurture = disciple.weaponNurture
                    put(weaponId, if (nurture.equipmentId == weaponId) eq.copy(nurtureLevel = nurture.nurtureLevel, nurtureProgress = nurture.nurtureProgress) else eq)
                }
            }
            disciple.armorId?.let { armorId ->
                EquipmentDatabase.getById(armorId)?.let { template ->
                    val eq = EquipmentDatabase.createFromTemplate(template)
                    val nurture = disciple.armorNurture
                    put(armorId, if (nurture.equipmentId == armorId) eq.copy(nurtureLevel = nurture.nurtureLevel, nurtureProgress = nurture.nurtureProgress) else eq)
                }
            }
            disciple.bootsId?.let { bootsId ->
                EquipmentDatabase.getById(bootsId)?.let { template ->
                    val eq = EquipmentDatabase.createFromTemplate(template)
                    val nurture = disciple.bootsNurture
                    put(bootsId, if (nurture.equipmentId == bootsId) eq.copy(nurtureLevel = nurture.nurtureLevel, nurtureProgress = nurture.nurtureProgress) else eq)
                }
            }
            disciple.accessoryId?.let { accessoryId ->
                EquipmentDatabase.getById(accessoryId)?.let { template ->
                    val eq = EquipmentDatabase.createFromTemplate(template)
                    val nurture = disciple.accessoryNurture
                    put(accessoryId, if (nurture.equipmentId == accessoryId) eq.copy(nurtureLevel = nurture.nurtureLevel, nurtureProgress = nurture.nurtureProgress) else eq)
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
            val manual = ManualDatabase.getById(manualId)
            val masteryLevel = if (manual != null) {
                ManualProficiencySystem.MasteryLevel.fromProficiency(mastery.toDouble(), manual.rarity).level
            } else 0
            ManualProficiencyData(
                manualId = manualId,
                proficiency = mastery.toDouble(),
                masteryLevel = masteryLevel
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
                    defenders[targetIndex] = defenders[targetIndex].copy(hp = newHp)
                }
            } else {
                val targetIndex = attackers.indexOfFirst { it.id == target.id }
                if (targetIndex >= 0) {
                    attackers[targetIndex] = attackers[targetIndex].copy(hp = newHp)
                }
            }
            
            availableSkill?.let { skill ->
                val skillIndex = currentCombatant.skills.indexOfFirst { it.name == skill.name }
                if (skillIndex >= 0) {
                    val updatedSkill = currentCombatant.skills[skillIndex].copy(
                        currentCooldown = skill.cooldown
                    )
                    val updatedSkills = currentCombatant.skills.toMutableList()
                    updatedSkills[skillIndex] = updatedSkill
                    
                    if (currentCombatant.isAttacker) {
                        val idx = attackers.indexOfFirst { it.id == currentCombatant.id }
                        if (idx >= 0) {
                            attackers[idx] = attackers[idx].copy(
                                skills = updatedSkills,
                                mp = currentCombatant.mp - skill.mpCost
                            )
                        }
                    } else {
                        val idx = defenders.indexOfFirst { it.id == currentCombatant.id }
                        if (idx >= 0) {
                            defenders[idx] = defenders[idx].copy(
                                skills = updatedSkills,
                                mp = currentCombatant.mp - skill.mpCost
                            )
                        }
                    }
                }
            }
            
            attackers = attackers.map { it.copy(skills = it.skills.map { s -> s.copy(currentCooldown = maxOf(0, s.currentCooldown - 1)) }) }.toMutableList()
            defenders = defenders.map { it.copy(skills = it.skills.map { s -> s.copy(currentCooldown = maxOf(0, s.currentCooldown - 1)) }) }.toMutableList()
        }
        
        val aliveAttackers = attackers.count { !it.isDead }
        val aliveDefenders = defenders.count { !it.isDead }
        
        return battle.copy(
            attackers = attackers,
            defenders = defenders,
            turn = battle.turn + 1,
            isFinished = aliveAttackers == 0 || aliveDefenders == 0
        )
    }
    
    private fun selectTarget(attacker: AICombatant, targets: List<AICombatant>): AICombatant {
        val aliveTargets = targets.filter { !it.isDead }
        if (aliveTargets.isEmpty()) return targets.first()
        
        val lowHpTargets = aliveTargets.filter { it.hp < it.maxHp * 0.3 }
        if (lowHpTargets.isNotEmpty() && Random.nextDouble() < 0.7) {
            return lowHpTargets.random()
        }
        
        return aliveTargets.random()
    }
    
    fun executePlayerSectBattle(
        attackTeam: AIBattleTeam,
        playerDefenseTeam: List<Disciple>
    ): AIBattleResult {
        val attackers = attackTeam.disciples.map { convertToCombatant(it, true) }
        val defenders = playerDefenseTeam.map { convertToCombatant(it, false) }
        
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
        
        val deadDefenderIds = playerDefenseTeam
            .filter { disciple ->
                finalBattle.defenders.find { it.id == disciple.id }?.isDead == true
            }
            .map { it.id }
        
        return AIBattleResult(
            battle = finalBattle,
            winner = winner,
            deadAttackerIds = deadAttackerIds,
            deadDefenderIds = deadDefenderIds,
            canOccupy = winner == AIBattleWinner.ATTACKER
        )
    }
    
    fun createPlayerDefenseTeam(
        disciples: List<Disciple>,
        equipmentMap: Map<String, com.xianxia.sect.core.model.Equipment>,
        manualMap: Map<String, com.xianxia.sect.core.model.Manual>,
        manualProficiencies: Map<String, Map<String, ManualProficiencyData>>
    ): List<Disciple> {
        return disciples
            .filter { it.isAlive && it.status == DiscipleStatus.IDLE }
            .sortedBy { it.realm }
            .take(TEAM_SIZE)
    }
    
    fun generateSectDestroyedEvent(attackerName: String, defenderName: String): String {
        return "⚔️ $attackerName 攻破了 $defenderName！"
    }

    fun calculatePlayerLootLoss(
        spiritStones: Long,
        materials: List<com.xianxia.sect.core.model.Material>,
        herbs: List<com.xianxia.sect.core.model.Herb>,
        seeds: List<com.xianxia.sect.core.model.Seed>,
        pills: List<com.xianxia.sect.core.model.Pill>
    ): PlayerLootLossResult {
        val lostSpiritStones = (spiritStones * 0.1).toLong().coerceAtLeast(0)
        val lostMaterials = mutableMapOf<String, Int>()
        val allItems = materials + herbs + seeds + pills
        allItems.filter { it.quantity > 0 }.shuffled().take(3).forEach { item ->
            val loss = (item.quantity * 0.2).toInt().coerceAtLeast(1)
            lostMaterials[item.name] = loss
        }
        return PlayerLootLossResult(lostSpiritStones, lostMaterials)
    }
}

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

data class AIBattle(
    val attackers: List<AICombatant>,
    val defenders: List<AICombatant>,
    val turn: Int,
    val isFinished: Boolean,
    val winner: AIBattleWinner?
)

enum class AIDamageType {
    PHYSICAL, MAGIC
}

data class AICombatSkill(
    val name: String,
    val damageType: AIDamageType,
    val damageMultiplier: Double,
    val mpCost: Int,
    val cooldown: Int,
    val currentCooldown: Int,
    val hits: Int = 1
)

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
    val skills: List<AICombatSkill>,
    val buffs: List<AICombatBuff>
) {
    val isDead: Boolean get() = hp <= 0
    val effectiveSpeed: Int get() = speed + buffs.sumOf { it.speedBonus }
    val effectivePhysicalAttack: Int get() = physicalAttack + buffs.sumOf { it.attackBonus }
    val effectiveMagicAttack: Int get() = magicAttack + buffs.sumOf { it.attackBonus }
    val effectivePhysicalDefense: Int get() = physicalDefense + buffs.sumOf { it.defenseBonus }
    val effectiveMagicDefense: Int get() = magicDefense + buffs.sumOf { it.defenseBonus }
    val effectiveCritRate: Double get() = critRate + buffs.sumOf { it.speedBonus } * 0.01
}

data class AICombatBuff(
    val name: String,
    val turnsRemaining: Int,
    val attackBonus: Int = 0,
    val defenseBonus: Int = 0,
    val speedBonus: Int = 0
)

data class PlayerLootLossResult(
    val lostSpiritStones: Long,
    val lostMaterials: Map<String, Int>
)
