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
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.util.BattleCalculator
import kotlin.math.sqrt
import kotlin.random.Random

object AISectAttackManager {

    val MIN_DISCIPLES_FOR_ATTACK get() = GameConfig.AI.MIN_DISCIPLES_FOR_ATTACK
    private val POWER_RATIO_THRESHOLD get() = GameConfig.AI.POWER_RATIO_THRESHOLD
    val TEAM_SIZE get() = GameConfig.AI.TEAM_SIZE

    fun decideAttacks(gameData: GameData): List<AIBattleTeam> {
        val newBattles = mutableListOf<AIBattleTeam>()
        val existingAttacks = gameData.aiBattleTeams.filter { it.status == "moving" || it.status == "battling" }
        val attackingSectIds = existingAttacks.map { it.attackerSectId }.toSet()
        val underAttackSectIds = existingAttacks.map { it.defenderSectId }.toSet()
        val aiDisciplesMap = gameData.aiSectDisciples

        val existingTeamDiscipleIds = gameData.aiBattleTeams
            .filter { it.status != "completed" }
            .flatMap { it.disciples.map { d -> d.id } }
            .toSet()

        val aiSects = gameData.worldMapSects.filter { !it.isPlayerSect }

        for (attacker in aiSects) {
            if (attacker.id in attackingSectIds) continue
            val attackerDisciples = aiDisciplesMap[attacker.id] ?: emptyList()
            if (attackerDisciples.filter { it.isAlive && it.id !in existingTeamDiscipleIds }.size < MIN_DISCIPLES_FOR_ATTACK) continue

            val allTargets = gameData.worldMapSects.filter { sect ->
                sect.id != attacker.id && sect.occupierSectId != attacker.id
            }

            for (defender in allTargets) {
                if (defender.id in underAttackSectIds) continue

                if (!checkAttackConditions(attacker, defender, gameData, aiDisciplesMap)) continue

                val battleTeam = createAttackTeam(attacker, defender, gameData, attackerDisciples, existingTeamDiscipleIds)
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

        val weights = GameConfig.AI.PowerWeights
        var totalPower = 0.0

        for (disciple in aliveDisciples) {
            val realmPower = (10 - disciple.realm) * weights.REALM_BASE

            val maxRarity = GameConfig.Realm.getMaxRarity(disciple.realm)
            val minRarity = AISectDiscipleManager.getMinRarityByRealm(disciple.realm)
            val avgEquipmentRarity = (minRarity + maxRarity) / 2.0
            val avgManualRarity = (minRarity + maxRarity) / 2.0
            val maxManuals = AISectDiscipleManager.getMaxManualsByRealm(disciple.realm)

            val equipmentPower = avgEquipmentRarity * 2.0 * weights.EQUIPMENT_RARITY
            val manualPower = avgManualRarity * (maxManuals / 2.0) * weights.MANUAL_RARITY

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
        attackerDisciples: List<Disciple>,
        existingTeamDiscipleIds: Set<String> = emptySet()
    ): AIBattleTeam? {
        val availableDisciples = attackerDisciples
            .filter { it.isAlive && it.id !in existingTeamDiscipleIds }
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
            .filter { it.isAlive && it.status == DiscipleStatus.IDLE }
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

        val existingTeamDiscipleIds = gameData.aiBattleTeams
            .filter { it.status != "completed" }
            .flatMap { it.disciples.map { d -> d.id } }
            .toSet()

        val playerSect = gameData.worldMapSects.find { it.isPlayerSect } ?: return null

        val aiSects = gameData.worldMapSects.filter { !it.isPlayerSect }

        for (attacker in aiSects) {
            if (attacker.id in attackingSectIds) continue
            val attackerDisciples = aiDisciplesMap[attacker.id] ?: emptyList()
            if (attackerDisciples.filter { it.isAlive && it.id !in existingTeamDiscipleIds }.size < MIN_DISCIPLES_FOR_ATTACK) continue

            val relation = gameData.sectRelations.find {
                (it.sectId1 == attacker.id && it.sectId2 == playerSect.id) ||
                (it.sectId1 == playerSect.id && it.sectId2 == attacker.id)
            }
            val favor = relation?.favor ?: 0
            if (favor > 0) continue

            if (attacker.allianceId.isNotEmpty() && playerSect.allianceId == attacker.allianceId) continue

            return createAttackTeam(attacker, playerSect, gameData, attackerDisciples, existingTeamDiscipleIds)
        }

        return null
    }

    fun findSectsWithNoTargets(gameData: GameData): Set<String> {
        val aiSects = gameData.worldMapSects.filter { !it.isPlayerSect }
        val aiDisciplesMap = gameData.aiSectDisciples
        val sectsWithNoTargets = mutableSetOf<String>()

        for (sect in aiSects) {
            val sectDisciples = aiDisciplesMap[sect.id] ?: emptyList()
            if (sectDisciples.filter { it.isAlive }.size < MIN_DISCIPLES_FOR_ATTACK) {
                sectsWithNoTargets.add(sect.id)
                continue
            }

            val hasTarget = gameData.worldMapSects.any { target ->
                target.id != sect.id && target.occupierSectId != sect.id &&
                checkAttackConditions(sect, target, gameData, aiDisciplesMap)
            }

            if (!hasTarget) {
                sectsWithNoTargets.add(sect.id)
            }
        }

        return sectsWithNoTargets
    }

    fun updateAIBattleTeamMovement(team: AIBattleTeam, gameData: GameData): AIBattleTeam {
        if (team.moveProgress >= 1f) return team

        if (team.status == "moving") {
            val attackerSect = gameData.worldMapSects.find { it.id == team.attackerSectId } ?: return team
            val defenderSect = gameData.worldMapSects.find { it.id == team.defenderSectId } ?: return team

            val distance = sqrt(
                (defenderSect.x - attackerSect.x) * (defenderSect.x - attackerSect.x) +
                (defenderSect.y - attackerSect.y) * (defenderSect.y - attackerSect.y)
            )

            val duration = distance / 33.33f
            val progressIncrement = 1f / duration.coerceAtLeast(0.001f)
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

        if (team.status == "returning") {
            val attackerSect = gameData.worldMapSects.find { it.id == team.attackerSectId } ?: return team
            val defenderSect = gameData.worldMapSects.find { it.id == team.defenderSectId } ?: return team

            val distance = sqrt(
                (attackerSect.x - defenderSect.x) * (attackerSect.x - defenderSect.x) +
                (attackerSect.y - defenderSect.y) * (attackerSect.y - defenderSect.y)
            )

            val duration = distance / 33.33f
            val progressIncrement = 1f / duration.coerceAtLeast(0.001f)
            val newProgress = (team.moveProgress + progressIncrement).coerceAtMost(1f)

            val newX = defenderSect.x + (team.attackerStartX - defenderSect.x) * newProgress
            val newY = defenderSect.y + (team.attackerStartY - defenderSect.y) * newProgress

            return if (newProgress >= 1f) {
                team.copy(
                    status = "completed",
                    moveProgress = 1f,
                    currentX = team.attackerStartX,
                    currentY = team.attackerStartY
                )
            } else {
                team.copy(
                    moveProgress = newProgress,
                    currentX = newX,
                    currentY = newY
                )
            }
        }

        return team
    }

    fun isTeamArrived(team: AIBattleTeam): Boolean {
        return team.status == "battling" && team.moveProgress >= 1f
    }

    fun isTeamReturned(team: AIBattleTeam): Boolean {
        return team.status == "completed" && team.moveProgress >= 1f
    }

    private fun convertToCombatant(disciple: Disciple, side: CombatantSide): Combatant {
        if (!ManualDatabase.isInitialized) {
            throw IllegalStateException("ManualDatabase not initialized when converting disciple ${disciple.name} to combatant")
        }

        val battleItems = AISectDiscipleManager.generateBattleItems(disciple)

        val weaponId = battleItems.equipments.firstOrNull { it.second == EquipmentSlot.WEAPON }?.first ?: ""
        val armorId = battleItems.equipments.firstOrNull { it.second == EquipmentSlot.ARMOR }?.first ?: ""
        val bootsId = battleItems.equipments.firstOrNull { it.second == EquipmentSlot.BOOTS }?.first ?: ""
        val accessoryId = battleItems.equipments.firstOrNull { it.second == EquipmentSlot.ACCESSORY }?.first ?: ""

        val equipmentMap = buildMap {
            if (weaponId.isNotEmpty()) {
                EquipmentDatabase.getById(weaponId)?.let { template ->
                    val eq = EquipmentDatabase.createFromTemplate(template).toInstance(id = weaponId)
                    val nurture = battleItems.weaponNurture
                    put(weaponId, if (nurture.equipmentId == weaponId) eq.copy(nurtureLevel = nurture.nurtureLevel, nurtureProgress = nurture.nurtureProgress) else eq)
                }
            }
            if (armorId.isNotEmpty()) {
                EquipmentDatabase.getById(armorId)?.let { template ->
                    val eq = EquipmentDatabase.createFromTemplate(template).toInstance(id = armorId)
                    val nurture = battleItems.armorNurture
                    put(armorId, if (nurture.equipmentId == armorId) eq.copy(nurtureLevel = nurture.nurtureLevel, nurtureProgress = nurture.nurtureProgress) else eq)
                }
            }
            if (bootsId.isNotEmpty()) {
                EquipmentDatabase.getById(bootsId)?.let { template ->
                    val eq = EquipmentDatabase.createFromTemplate(template).toInstance(id = bootsId)
                    val nurture = battleItems.bootsNurture
                    put(bootsId, if (nurture.equipmentId == bootsId) eq.copy(nurtureLevel = nurture.nurtureLevel, nurtureProgress = nurture.nurtureProgress) else eq)
                }
            }
            if (accessoryId.isNotEmpty()) {
                EquipmentDatabase.getById(accessoryId)?.let { template ->
                    val eq = EquipmentDatabase.createFromTemplate(template).toInstance(id = accessoryId)
                    val nurture = battleItems.accessoryNurture
                    put(accessoryId, if (nurture.equipmentId == accessoryId) eq.copy(nurtureLevel = nurture.nurtureLevel, nurtureProgress = nurture.nurtureProgress) else eq)
                }
            }
        }

        val manualIds = battleItems.manuals.map { it.first }
        val manualMasteries = battleItems.manuals.toMap()

        val manualMap = manualIds.mapNotNull { mId ->
            ManualDatabase.getById(mId)?.let { template ->
                mId to ManualDatabase.createFromTemplate(template).toInstance(id = mId)
            }
        }.toMap()

        val manualProficiencies = manualIds.associateWith { mId ->
            val mastery = manualMasteries[mId] ?: 0
            val manual = ManualDatabase.getById(mId)
            val masteryLevel = if (manual != null) {
                ManualProficiencySystem.MasteryLevel.fromProficiency(mastery.toDouble(), manual.rarity).level
            } else 0
            ManualProficiencyData(
                manualId = mId,
                proficiency = mastery.toDouble(),
                masteryLevel = masteryLevel
            )
        }

        val battleDisciple = disciple.copyWith(
            manualIds = manualIds,
            manualMasteries = manualMasteries,
            weaponId = weaponId,
            armorId = armorId,
            bootsId = bootsId,
            accessoryId = accessoryId,
            weaponNurture = battleItems.weaponNurture,
            armorNurture = battleItems.armorNurture,
            bootsNurture = battleItems.bootsNurture,
            accessoryNurture = battleItems.accessoryNurture
        )

        val stats = battleDisciple.getFinalStats(equipmentMap, manualMap, manualProficiencies)

        val skills = manualIds.mapNotNull { mId ->
            val manual = manualMap[mId] ?: return@mapNotNull null
            val skill = manual.skill ?: return@mapNotNull null
            val proficiencyData = manualProficiencies[mId]
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
        val defenseTeam = createDefenseTeam(defenderDisciples)
        val attackers = attackTeam.disciples.map { convertToCombatant(it, CombatantSide.ATTACKER) }
        val defenders = defenseTeam.map { convertToCombatant(it, CombatantSide.DEFENDER) }

        val result = executeUnifiedAIBattle(attackers, defenders)

        val deadAttackerIds = attackTeam.disciples
            .filter { disciple ->
                result.attackers.find { it.id == disciple.id }?.isDead == true
            }
            .map { it.id }

        val deadDefenderIds = defenseTeam
            .filter { disciple ->
                result.defenders.find { it.id == disciple.id }?.isDead == true
            }
            .map { it.id }

        val allDefenderDisciples = defenderDisciples.filter { it.isAlive && it.id !in deadDefenderIds }
        val highRealmAllDead = allDefenderDisciples.filter { it.realm <= 5 }.isEmpty()

        val canOccupy = result.winner == AIBattleWinner.ATTACKER && highRealmAllDead

        return AIBattleResult(
            winner = result.winner,
            deadAttackerIds = deadAttackerIds,
            deadDefenderIds = deadDefenderIds,
            canOccupy = canOccupy
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

    fun createGarrisonTeam(
        attackerAliveDisciples: List<Disciple>,
        occupiedSectDisciples: List<Disciple>,
        attackerSectId: String,
        attackerSectName: String,
        occupiedSectId: String
    ): AIBattleTeam {
        val garrisonDisciples = mutableListOf<Disciple>()
        garrisonDisciples.addAll(attackerAliveDisciples.take(TEAM_SIZE))
        if (garrisonDisciples.size < TEAM_SIZE) {
            val remaining = occupiedSectDisciples
                .filter { it.isAlive && it.status == DiscipleStatus.IDLE && it.id !in garrisonDisciples.map { d -> d.id } }
                .sortedBy { it.realm }
                .take(TEAM_SIZE - garrisonDisciples.size)
            garrisonDisciples.addAll(remaining)
        }
        return AIBattleTeam(
            id = java.util.UUID.randomUUID().toString(),
            attackerSectId = attackerSectId,
            attackerSectName = attackerSectName,
            defenderSectId = occupiedSectId,
            defenderSectName = "",
            disciples = garrisonDisciples,
            currentX = 0f,
            currentY = 0f,
            targetX = 0f,
            targetY = 0f,
            attackerStartX = 0f,
            attackerStartY = 0f,
            moveProgress = 1f,
            status = "stationed",
            route = emptyList(),
            currentRouteIndex = 0,
            startYear = 0,
            startMonth = 0,
            isPlayerDefender = false,
            isGarrison = true,
            garrisonSectId = occupiedSectId,
            garrisonSectName = ""
        )
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
        val lostSpiritStones = (spiritStones * 0.4).toLong().coerceAtLeast(0)
        val lostMaterials = mutableMapOf<String, Int>()
        val allItems = materials + herbs + seeds + pills
        allItems.filter { it.quantity > 0 }.forEach { item ->
            val loss = (item.quantity * 0.4).toInt().coerceAtLeast(1)
            lostMaterials[item.name] = loss
        }
        return PlayerLootLossResult(lostSpiritStones, lostMaterials)
    }

    data class SectWarRewardConfig(
        val minRarity: Int,
        val maxRarity: Int,
        val spiritStoneValue: Long
    )

    fun getSectWarRewardConfig(sectLevel: Int): SectWarRewardConfig {
        return when (sectLevel) {
            0 -> SectWarRewardConfig(minRarity = 1, maxRarity = 2, spiritStoneValue = 2000)
            1 -> SectWarRewardConfig(minRarity = 2, maxRarity = 4, spiritStoneValue = 6000)
            2 -> SectWarRewardConfig(minRarity = 3, maxRarity = 5, spiritStoneValue = 30000)
            3 -> SectWarRewardConfig(minRarity = 4, maxRarity = 6, spiritStoneValue = 80000)
            else -> SectWarRewardConfig(minRarity = 1, maxRarity = 2, spiritStoneValue = 2000)
        }
    }

    fun generateWarRewards(sectLevel: Int, itemCount: Int): WarRewards {
        val config = getSectWarRewardConfig(sectLevel)
        val spiritStones = config.spiritStoneValue * itemCount

        val equipmentStacks = mutableListOf<com.xianxia.sect.core.model.EquipmentStack>()
        val manualStacks = mutableListOf<com.xianxia.sect.core.model.ManualStack>()
        val pills = mutableListOf<com.xianxia.sect.core.model.Pill>()
        val materials = mutableListOf<com.xianxia.sect.core.model.Material>()
        val herbs = mutableListOf<com.xianxia.sect.core.model.Herb>()
        val seeds = mutableListOf<com.xianxia.sect.core.model.Seed>()

        repeat(itemCount) {
            val itemType = Random.nextInt(6)
            when (itemType) {
                0 -> {
                    if (com.xianxia.sect.core.data.EquipmentDatabase.isInitialized) {
                        try {
                            equipmentStacks.add(
                                com.xianxia.sect.core.data.EquipmentDatabase.generateRandom(config.minRarity, config.maxRarity)
                            )
                        } catch (_: Exception) {}
                    }
                }
                1 -> {
                    if (com.xianxia.sect.core.data.ManualDatabase.isInitialized) {
                        try {
                            manualStacks.add(
                                com.xianxia.sect.core.data.ManualDatabase.generateRandom(config.minRarity, config.maxRarity)
                            )
                        } catch (_: Exception) {}
                    }
                }
                2 -> {
                    try {
                        pills.add(
                            com.xianxia.sect.core.data.ItemDatabase.generateRandomPill(config.minRarity, config.maxRarity)
                        )
                    } catch (_: Exception) {}
                }
                3 -> {
                    try {
                        materials.add(
                            com.xianxia.sect.core.data.ItemDatabase.generateRandomMaterial(config.minRarity, config.maxRarity)
                        )
                    } catch (_: Exception) {}
                }
                4 -> {
                    try {
                        val herbTemplate = com.xianxia.sect.core.data.HerbDatabase.generateRandomHerb(config.minRarity, config.maxRarity)
                        herbs.add(
                            com.xianxia.sect.core.model.Herb(
                                name = herbTemplate.name,
                                rarity = herbTemplate.rarity,
                                description = herbTemplate.description,
                                category = herbTemplate.category,
                                quantity = 1
                            )
                        )
                    } catch (_: Exception) {}
                }
                5 -> {
                    try {
                        val seedTemplate = com.xianxia.sect.core.data.HerbDatabase.generateRandomSeed(config.minRarity, config.maxRarity)
                        seeds.add(
                            com.xianxia.sect.core.model.Seed(
                                name = seedTemplate.name,
                                rarity = seedTemplate.rarity,
                                description = seedTemplate.description,
                                growTime = seedTemplate.growTime,
                                yield = seedTemplate.yield,
                                quantity = 1
                            )
                        )
                    } catch (_: Exception) {}
                }
            }
        }

        return WarRewards(
            spiritStones = spiritStones,
            equipmentStacks = equipmentStacks,
            manualStacks = manualStacks,
            pills = pills,
            materials = materials,
            herbs = herbs,
            seeds = seeds
        )
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

data class WarRewards(
    val spiritStones: Long,
    val equipmentStacks: List<com.xianxia.sect.core.model.EquipmentStack>,
    val manualStacks: List<com.xianxia.sect.core.model.ManualStack>,
    val pills: List<com.xianxia.sect.core.model.Pill>,
    val materials: List<com.xianxia.sect.core.model.Material>,
    val herbs: List<com.xianxia.sect.core.model.Herb>,
    val seeds: List<com.xianxia.sect.core.model.Seed>
)
