@file:Suppress("DEPRECATION")

package com.xianxia.sect.core.engine

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.HealType
import com.xianxia.sect.core.SkillType
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
import com.xianxia.sect.core.util.BattleCalculator
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

    private fun convertToCombatant(disciple: Disciple, side: CombatantSide): Combatant {
        if (!ManualDatabase.isInitialized) {
            throw IllegalStateException("ManualDatabase not initialized when converting disciple ${disciple.name} to combatant")
        }

        val equipmentMap = buildMap {
            disciple.weaponId?.let { weaponId ->
                EquipmentDatabase.getById(weaponId)?.let { template ->
                    val eq = EquipmentDatabase.createFromTemplate(template).toInstance(id = weaponId)
                    val nurture = disciple.weaponNurture
                    put(weaponId, if (nurture.equipmentId == weaponId) eq.copy(nurtureLevel = nurture.nurtureLevel, nurtureProgress = nurture.nurtureProgress) else eq)
                }
            }
            disciple.armorId?.let { armorId ->
                EquipmentDatabase.getById(armorId)?.let { template ->
                    val eq = EquipmentDatabase.createFromTemplate(template).toInstance(id = armorId)
                    val nurture = disciple.armorNurture
                    put(armorId, if (nurture.equipmentId == armorId) eq.copy(nurtureLevel = nurture.nurtureLevel, nurtureProgress = nurture.nurtureProgress) else eq)
                }
            }
            disciple.bootsId?.let { bootsId ->
                EquipmentDatabase.getById(bootsId)?.let { template ->
                    val eq = EquipmentDatabase.createFromTemplate(template).toInstance(id = bootsId)
                    val nurture = disciple.bootsNurture
                    put(bootsId, if (nurture.equipmentId == bootsId) eq.copy(nurtureLevel = nurture.nurtureLevel, nurtureProgress = nurture.nurtureProgress) else eq)
                }
            }
            disciple.accessoryId?.let { accessoryId ->
                EquipmentDatabase.getById(accessoryId)?.let { template ->
                    val eq = EquipmentDatabase.createFromTemplate(template).toInstance(id = accessoryId)
                    val nurture = disciple.accessoryNurture
                    put(accessoryId, if (nurture.equipmentId == accessoryId) eq.copy(nurtureLevel = nurture.nurtureLevel, nurtureProgress = nurture.nurtureProgress) else eq)
                }
            }
        }

        val manualMap = disciple.manualIds.mapNotNull { manualId ->
            ManualDatabase.getById(manualId)?.let { template ->
                manualId to ManualDatabase.createFromTemplate(template).toInstance(id = manualId)
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
            CombatSkill(
                name = skill.name,
                damageType = if (skill.damageType == DamageType.PHYSICAL) DamageType.PHYSICAL else DamageType.MAGIC,
                damageMultiplier = adjustedMultiplier,
                mpCost = skill.mpCost,
                cooldown = skill.cooldown,
                currentCooldown = 0,
                hits = skill.hits
            )
        }

        val spiritRootTypes = disciple.spiritRoot.types
        val primaryElement = spiritRootTypes.firstOrNull()?.trim() ?: "metal"

        return Combatant(
            id = disciple.id,
            name = disciple.name,
            side = side,
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
            skills = skills,
            buffs = emptyList(),
            element = primaryElement
        )
    }

    fun executeAISectBattle(
        attackTeam: AIBattleTeam,
        defenderSect: WorldSect,
        defenderDisciples: List<Disciple>
    ): AIBattleResult {
        val attackers = attackTeam.disciples.map { convertToCombatant(it, CombatantSide.ATTACKER) }
        val defenders = createDefenseTeam(defenderDisciples).map { convertToCombatant(it, CombatantSide.DEFENDER) }

        val result = executeUnifiedAIBattle(attackers, defenders)

        val deadAttackerIds = attackTeam.disciples
            .filter { disciple ->
                result.attackers.find { it.id == disciple.id }?.isDead == true
            }
            .map { it.id }

        val deadDefenderIds = defenderDisciples
            .filter { disciple ->
                result.defenders.find { it.id == disciple.id }?.isDead == true
            }
            .map { it.id }

        val highRealmDefendersAlive = defenderDisciples
            .filter { it.isAlive && it.realm <= 5 }
            .none { it.id !in deadDefenderIds }

        return AIBattleResult(
            winner = result.winner,
            deadAttackerIds = deadAttackerIds,
            deadDefenderIds = deadDefenderIds,
            canOccupy = result.winner == AIBattleWinner.ATTACKER && highRealmDefendersAlive
        )
    }

    fun executePlayerSectBattle(
        attackTeam: AIBattleTeam,
        playerDefenseTeam: List<Disciple>
    ): AIBattleResult {
        val attackers = attackTeam.disciples.map { convertToCombatant(it, CombatantSide.ATTACKER) }
        val defenders = playerDefenseTeam.map { convertToCombatant(it, CombatantSide.DEFENDER) }

        val result = executeUnifiedAIBattle(attackers, defenders)

        val deadAttackerIds = attackTeam.disciples
            .filter { disciple ->
                result.attackers.find { it.id == disciple.id }?.isDead == true
            }
            .map { it.id }

        val deadDefenderIds = playerDefenseTeam
            .filter { disciple ->
                result.defenders.find { it.id == disciple.id }?.isDead == true
            }
            .map { it.id }

        return AIBattleResult(
            winner = result.winner,
            deadAttackerIds = deadAttackerIds,
            deadDefenderIds = deadDefenderIds,
            canOccupy = result.winner == AIBattleWinner.ATTACKER
        )
    }

    private data class UnifiedAIBattleResult(
        val attackers: List<Combatant>,
        val defenders: List<Combatant>,
        val winner: AIBattleWinner
    )

    private fun executeUnifiedAIBattle(
        attackers: List<Combatant>,
        defenders: List<Combatant>
    ): UnifiedAIBattleResult {
        var currentAttackers = attackers.toMutableList()
        var currentDefenders = defenders.toMutableList()
        var turn = 0

        while (turn < GameConfig.AI.MAX_BATTLE_TURNS) {
            val allCombatants = (currentAttackers + currentDefenders)
                .filter { !it.isDead }
                .sortedByDescending { it.effectiveSpeed }

            for (combatant in allCombatants) {
                if (combatant.isDead) continue

                val isAttacker = combatant.side == CombatantSide.ATTACKER
                val allies = if (isAttacker) currentAttackers else currentDefenders
                val enemies = if (isAttacker) currentDefenders else currentAttackers
                val alliesIndexMap = allies.withIndex().associate { it.value.id to it.index }
                val enemiesIndexMap = enemies.withIndex().associate { it.value.id to it.index }

                val aliveEnemies = enemies.filter { !it.isDead }
                if (aliveEnemies.isEmpty()) break

                val combatantIdx = alliesIndexMap[combatant.id] ?: continue
                val currentCombatant = allies[combatantIdx]

                if (currentCombatant.hasControlEffect) {
                    allies[combatantIdx] = BattleCalculator.updateCombatantBuffsOnly(currentCombatant)
                    continue
                }

                val silenceBuff = currentCombatant.buffs.find { it.type == BuffType.SILENCE && it.remainingDuration > 0 }
                val availableSkill = selectAISkill(currentCombatant, aliveEnemies, allies.filter { !it.isDead }, silenceBuff != null)

                val isSupportSkill = availableSkill?.skillType == SkillType.SUPPORT
                val isAoeSkill = availableSkill?.isAoe == true && !isSupportSkill

                if (availableSkill != null && isSupportSkill) {
                    executeSupportAction(currentCombatant, allies.filter { !it.isDead }, availableSkill, allies, alliesIndexMap)
                } else if (availableSkill != null && isAoeSkill) {
                    executeAoeAttackAction(currentCombatant, aliveEnemies, availableSkill, allies, enemies, alliesIndexMap, enemiesIndexMap)
                } else if (availableSkill != null) {
                    val target = selectAITarget(currentCombatant, aliveEnemies)
                    executeSingleAttackAction(currentCombatant, target, availableSkill, allies, enemies, alliesIndexMap, enemiesIndexMap)
                } else {
                    val target = selectAITarget(currentCombatant, aliveEnemies)
                    executeNormalAttackAction(currentCombatant, target, allies, enemies, alliesIndexMap, enemiesIndexMap)
                }

                currentAttackers = currentAttackers.filter { !it.isDead }.toMutableList()
                currentDefenders = currentDefenders.filter { !it.isDead }.toMutableList()
            }

            processDotEffects(currentAttackers, currentDefenders)

            turn++

            if (currentAttackers.isEmpty() || currentDefenders.isEmpty()) break
        }

        val winner = when {
            currentDefenders.isEmpty() -> AIBattleWinner.ATTACKER
            currentAttackers.isEmpty() -> AIBattleWinner.DEFENDER
            else -> AIBattleWinner.DRAW
        }

        return UnifiedAIBattleResult(
            attackers = currentAttackers,
            defenders = currentDefenders,
            winner = winner
        )
    }

    private fun executeNormalAttackAction(
        attacker: Combatant,
        target: Combatant,
        allies: MutableList<Combatant>,
        enemies: MutableList<Combatant>,
        alliesIndexMap: Map<String, Int>,
        enemiesIndexMap: Map<String, Int>
    ) {
        val result = BattleCalculator.calculateCombatantDamage(attacker, target, null)

        if (result.isDodged) {
            val combatantIdx = alliesIndexMap[attacker.id]
            if (combatantIdx != null && combatantIdx < allies.size) {
                allies[combatantIdx] = BattleCalculator.updateCombatantBuffsOnly(attacker)
            }
            return
        }

        val newHp = maxOf(0, target.hp - result.damage)
        val targetIdx = enemiesIndexMap[target.id]
        if (targetIdx != null && targetIdx < enemies.size) {
            enemies[targetIdx] = enemies[targetIdx].copy(hp = newHp)
        }

        val combatantIdx = alliesIndexMap[attacker.id]
        if (combatantIdx != null && combatantIdx < allies.size) {
            allies[combatantIdx] = BattleCalculator.updateCombatantBuffsOnly(attacker)
        }
    }

    private fun executeSingleAttackAction(
        attacker: Combatant,
        target: Combatant,
        skill: CombatSkill,
        allies: MutableList<Combatant>,
        enemies: MutableList<Combatant>,
        alliesIndexMap: Map<String, Int>,
        enemiesIndexMap: Map<String, Int>
    ) {
        val result = BattleCalculator.calculateCombatantDamage(attacker, target, skill)

        if (result.isDodged) {
            val combatantIdx = alliesIndexMap[attacker.id]
            if (combatantIdx != null && combatantIdx < allies.size) {
                allies[combatantIdx] = BattleCalculator.updateCombatantCooldowns(attacker, skill)
            }
            return
        }

        val newHp = maxOf(0, target.hp - result.damage)
        val targetIdx = enemiesIndexMap[target.id]
        if (targetIdx != null && targetIdx < enemies.size) {
            var updatedTarget = enemies[targetIdx].copy(hp = newHp)

            if (skill.buffType != null && skill.buffDuration > 0) {
                val debuff = CombatBuff(type = skill.buffType, value = skill.buffValue, remainingDuration = skill.buffDuration)
                updatedTarget = updatedTarget.copy(buffs = updatedTarget.buffs + debuff)
            }

            enemies[targetIdx] = updatedTarget
        }

        val combatantIdx = alliesIndexMap[attacker.id]
        if (combatantIdx != null && combatantIdx < allies.size) {
            allies[combatantIdx] = BattleCalculator.updateCombatantCooldowns(attacker, skill)
        }
    }

    private fun executeAoeAttackAction(
        attacker: Combatant,
        targets: List<Combatant>,
        skill: CombatSkill,
        allies: MutableList<Combatant>,
        enemies: MutableList<Combatant>,
        alliesIndexMap: Map<String, Int>,
        enemiesIndexMap: Map<String, Int>
    ) {
        for (target in targets) {
            if (target.isDead) continue

            val result = BattleCalculator.calculateCombatantDamage(attacker, target, skill)

            if (result.isDodged) continue

            val newHp = maxOf(0, target.hp - result.damage)
            val targetIdx = enemiesIndexMap[target.id]
            if (targetIdx != null && targetIdx < enemies.size) {
                var updatedTarget = enemies[targetIdx].copy(hp = newHp)

                if (skill.buffType != null && skill.buffDuration > 0) {
                    val debuff = CombatBuff(type = skill.buffType, value = skill.buffValue, remainingDuration = skill.buffDuration)
                    updatedTarget = updatedTarget.copy(buffs = updatedTarget.buffs + debuff)
                }

                enemies[targetIdx] = updatedTarget
            }
        }

        val combatantIdx = alliesIndexMap[attacker.id]
        if (combatantIdx != null && combatantIdx < allies.size) {
            allies[combatantIdx] = BattleCalculator.updateCombatantCooldowns(attacker, skill)
        }
    }

    private fun executeSupportAction(
        caster: Combatant,
        allies: List<Combatant>,
        skill: CombatSkill,
        alliesList: MutableList<Combatant>,
        alliesIndexMap: Map<String, Int>
    ) {
        val supportResult = BattleCalculator.executeSupportSkill(caster, allies, skill)

        if (supportResult.healAmount > 0) {
            supportResult.healedIds.forEach { healedId ->
                val idx = alliesIndexMap[healedId]
                if (idx != null && idx < alliesList.size) {
                    if (skill.healType == HealType.MP) {
                        alliesList[idx] = alliesList[idx].copy(mp = minOf(alliesList[idx].mp + supportResult.healAmount, alliesList[idx].maxMp))
                    } else {
                        alliesList[idx] = alliesList[idx].copy(hp = minOf(alliesList[idx].hp + supportResult.healAmount, alliesList[idx].maxHp))
                    }
                }
            }
        }

        supportResult.teamBuffs.forEach { (memberId, buffs) ->
            val idx = alliesIndexMap[memberId]
            if (idx != null && idx < alliesList.size) {
                alliesList[idx] = alliesList[idx].copy(buffs = alliesList[idx].buffs + buffs)
            }
        }

        val combatantIdx = alliesIndexMap[caster.id]
        if (combatantIdx != null && combatantIdx < alliesList.size) {
            alliesList[combatantIdx] = BattleCalculator.updateCombatantCooldowns(caster, skill)
        }
    }

    private fun processDotEffects(attackers: MutableList<Combatant>, defenders: MutableList<Combatant>) {
        val allCombatants = (attackers + defenders).filter { !it.isDead }
        val dotResults = BattleCalculator.processDotEffects(allCombatants)
        for (result in dotResults) {
            val isAttacker = result.combatant.side == CombatantSide.ATTACKER
            val list = if (isAttacker) attackers else defenders
            val idx = list.indexOfFirst { it.id == result.combatant.id }
            if (idx >= 0) {
                list[idx] = list[idx].copy(hp = result.newHp)
            }
        }
    }

    private fun selectAISkill(
        combatant: Combatant,
        enemies: List<Combatant>,
        allies: List<Combatant>,
        isSilenced: Boolean
    ): CombatSkill? {
        return BattleCalculator.selectSkill(combatant, enemies, allies, isSilenced)
    }

    private fun selectAITarget(attacker: Combatant, targets: List<Combatant>): Combatant {
        val aliveTargets = targets.filter { !it.isDead }
        if (aliveTargets.isEmpty()) return targets.first()
        return BattleCalculator.selectTarget(attacker, aliveTargets)
    }

    fun createPlayerDefenseTeam(
        disciples: List<Disciple>,
        equipmentMap: Map<String, com.xianxia.sect.core.model.EquipmentInstance>,
        manualMap: Map<String, com.xianxia.sect.core.model.ManualInstance>,
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
    val winner: AIBattleWinner,
    val deadAttackerIds: List<String>,
    val deadDefenderIds: List<String>,
    val canOccupy: Boolean
)

data class PlayerLootLossResult(
    val lostSpiritStones: Long,
    val lostMaterials: Map<String, Int>
)
