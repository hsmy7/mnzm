package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.HealType
import com.xianxia.sect.core.SkillType
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.model.CombatSkill
import com.xianxia.sect.core.model.AISectPersonality
import com.xianxia.sect.core.model.BattleLogAction
import com.xianxia.sect.core.model.BattleLogEnemy
import com.xianxia.sect.core.model.BattleLogMember
import com.xianxia.sect.core.model.BattleLogRound
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager
import com.xianxia.sect.core.util.BattleCalculator
import android.util.Log
import kotlin.random.Random

object AISectAttackManager {
    private const val TAG = "AISectAttackManager"

    val MIN_DISCIPLES_FOR_ATTACK get() = GameConfig.AI.MIN_DISCIPLES_FOR_ATTACK
    private val POWER_RATIO_THRESHOLD get() = GameConfig.AI.POWER_RATIO_THRESHOLD
    val TEAM_SIZE get() = GameConfig.AI.TEAM_SIZE

    /**
     * 玩家占领宗门的驻军防御信息。
     * @param disciples 驻军弟子列表（用于战力评估和 canOccupy 判断）
     * @param combatants 已转换为 Combatant 的战斗单元（用于实际战斗）
     */
    data class PlayerOccupiedDefenseInfo(
        val disciples: List<Disciple>,
        val combatants: List<Combatant>
    )

    /**
     * Result of an AI attack decision with immediate execution.
     * The caller applies these changes to game state.
     */
    data class AIAttackResult(
        val attackerSectId: String,
        val defenderSectId: String,
        val attackerSectName: String,
        val defenderSectName: String,
        val winner: AIBattleWinner,
        val deadAttackerIds: List<String>,
        val deadDefenderIds: List<String>,
        val canOccupy: Boolean,
        val survivingAttackers: List<Disciple>,
        /** 防守方幸存者 HP/MP（用于玩家宗门被攻时回写弟子状态） */
        val defenderSurvivorHpMap: Map<String, Int> = emptyMap(),
        val defenderSurvivorMpMap: Map<String, Int> = emptyMap(),
        /** 战斗回合明细（用于写入 battleLogs） */
        val rounds: List<BattleLogRound> = emptyList(),
        /** 我方弟子快照（战斗终态，用于日志展示） */
        val teamMembers: List<BattleLogMember> = emptyList(),
        /** 敌方快照（战斗终态，用于日志展示） */
        val enemies: List<BattleLogEnemy> = emptyList()
    )

    fun decideAttacks(
        gameData: GameData,
        playerOccupiedDefendersMap: Map<String, PlayerOccupiedDefenseInfo> = emptyMap()
    ): List<AIAttackResult> {
        val results = mutableListOf<AIAttackResult>()
        val aiDisciplesMap = gameData.aiSectDisciples

        val aiSects = gameData.worldMapSects.filter { !it.isPlayerSect }

        for (attacker in aiSects) {
            val attackerDisciples = aiDisciplesMap[attacker.id] ?: emptyList()
            val availableAttackers = attackerDisciples.filter { it.isAlive }
            if (availableAttackers.size < MIN_DISCIPLES_FOR_ATTACK) continue

            val allTargets = gameData.worldMapSects.filter { sect ->
                sect.id != attacker.id && sect.occupierSectId != attacker.id
            }

            for (defender in allTargets) {
                if (results.any { it.defenderSectId == defender.id || it.attackerSectId == attacker.id }) continue
                if (!checkAttackConditions(
                        attacker, defender, gameData, aiDisciplesMap,
                        playerGarrisonMap = playerOccupiedDefendersMap
                            .mapValues { it.value.disciples }
                    )) continue

                // Build attack team
                val selectedAttackers = availableAttackers
                    .sortedBy { it.realm }
                    .take(TEAM_SIZE)
                if (selectedAttackers.size < MIN_DISCIPLES_FOR_ATTACK) continue

                // Get defenders: garrison first, then sect disciples
                val defenderSect = gameData.worldMapSects.find {
                    it.id == defender.id
                }
                val isAiOccupied = defenderSect?.occupierSectId
                    ?.isNotEmpty() == true &&
                    defenderSect.occupierSectId != attacker.id
                val isPlayerOccupied = defenderSect?.isPlayerOccupied == true
                val garrisonDisciples = if (isAiOccupied) {
                    if (isPlayerOccupied) {
                        playerOccupiedDefendersMap[defender.id]
                            ?.disciples ?: emptyList()
                    } else {
                        val occupierDisciples = aiDisciplesMap[
                            defenderSect.occupierSectId] ?: emptyList()
                        defenderSect.garrisonSlots
                            .filter { it.discipleId.isNotEmpty() }
                            .mapNotNull { slot ->
                                occupierDisciples.find { d ->
                                    d.id == slot.discipleId && d.isAlive
                                }
                            }
                    }
                } else {
                    emptyList()
                }

                val defenderPool = aiDisciplesMap[defender.id] ?: emptyList()
                val defenderDisciples = if (garrisonDisciples.isNotEmpty()) {
                    garrisonDisciples
                } else {
                    defenderPool.filter { it.isAlive }
                        .sortedBy { it.realm }.take(TEAM_SIZE)
                }

                if (defenderDisciples.isEmpty()) continue

                // Full defender pool for occupation check
                val allDefenderPool = if (garrisonDisciples.isNotEmpty()) {
                    if (isPlayerOccupied) {
                        garrisonDisciples
                    } else {
                        aiDisciplesMap[
                            defenderSect?.occupierSectId ?: ""]
                            ?: emptyList()
                    }
                } else {
                    defenderPool
                }

                // Execute battle immediately
                val battleResult = if (isPlayerOccupied &&
                    garrisonDisciples.isNotEmpty()) {
                    val garrisonCombatants = playerOccupiedDefendersMap[
                        defender.id]?.combatants ?: emptyList()
                    executePlayerSectBattle(
                        selectedAttackers, garrisonCombatants)
                } else {
                    executeSectBattle(selectedAttackers,
                        defenderSect ?: defender,
                        defenderDisciples, allDefenderPool)
                }

                val survivingAttackers = selectedAttackers.filter { it.id !in battleResult.deadAttackerIds }

                results.add(
                    AIAttackResult(
                        attackerSectId = attacker.id,
                        defenderSectId = defender.id,
                        attackerSectName = attacker.name,
                        defenderSectName = defender.name,
                        winner = battleResult.winner,
                        deadAttackerIds = battleResult.deadAttackerIds,
                        deadDefenderIds = battleResult.deadDefenderIds,
                        canOccupy = battleResult.canOccupy,
                        survivingAttackers = survivingAttackers
                    )
                )

                // One attack per attacker per month
                break
            }
        }

        return results
    }

    /**
     * Execute a sect battle given raw disciple lists (no AIBattleTeam needed).
     */
    fun executeSectBattle(
        attackers: List<Disciple>,
        defenderSect: WorldSect,
        defenderDisciples: List<Disciple>,
        allSectDisciples: List<Disciple> = defenderDisciples
    ): AIBattleResult {
        val defenseTeam = createDefenseTeam(defenderDisciples)
        val combatAttackers = attackers.map { convertToCombatant(it, CombatantSide.ATTACKER) }
        val combatDefenders = defenseTeam.map { convertToCombatant(it, CombatantSide.DEFENDER) }

        val result = executeUnifiedAIBattle(combatAttackers, combatDefenders)

        val survivorAttackerIds = result.attackers.map { it.id }.toSet()
        val survivorDefenderIds = result.defenders.map { it.id }.toSet()

        val deadAttackerIds = attackers
            .filter { it.id !in survivorAttackerIds }
            .map { it.id }

        val deadDefenderIds = defenseTeam
            .filter { it.id !in survivorDefenderIds }
            .map { it.id }

        val allDefenderDisciples = allSectDisciples.filter { it.isAlive && it.id !in deadDefenderIds }
        val highRealmAllDead = allDefenderDisciples.filter { it.realm <= 5 }.isEmpty()

        val canOccupy = result.winner == AIBattleWinner.ATTACKER && highRealmAllDead

        val survivorHpMap = result.attackers.associate { it.id to it.hp }
        val survivorMpMap = result.attackers.associate { it.id to it.mp }

        return AIBattleResult(
            winner = result.winner,
            deadAttackerIds = deadAttackerIds,
            deadDefenderIds = deadDefenderIds,
            canOccupy = canOccupy,
            turns = result.turns,
            survivorHpMap = survivorHpMap,
            survivorMpMap = survivorMpMap,
            rounds = result.rounds
        )
    }

    fun checkAttackConditions(
        attacker: WorldSect,
        defender: WorldSect,
        gameData: GameData,
        aiDisciplesMap: Map<String, List<Disciple>> = emptyMap(),
        playerGarrisonMap: Map<String, List<Disciple>> = emptyMap()
    ): Boolean {
        if (attacker.id == defender.id) return false

        val attackerDisciples = (aiDisciplesMap[attacker.id] ?: emptyList())
            .filter { it.isAlive }
        if (attackerDisciples.size < MIN_DISCIPLES_FOR_ATTACK) return false

        val relation = gameData.sectRelations.find {
            (it.sectId1 == attacker.id && it.sectId2 == defender.id) ||
            (it.sectId1 == defender.id && it.sectId2 == attacker.id)
        }
        val favor = relation?.favor ?: 0
        if (favor > 0) return false

        if (attacker.allianceId.isNotEmpty() &&
            attacker.allianceId == defender.allianceId) return false

        val attackerPower = calculatePowerScore(attackerDisciples)
        val defenderDisciples = if (defender.isPlayerOccupied) {
            playerGarrisonMap[defender.id] ?: emptyList()
        } else {
            (aiDisciplesMap[defender.id] ?: emptyList()).filter { it.isAlive }
        }
        val defenderPower = calculatePowerScore(defenderDisciples)
        if (attackerPower < defenderPower * POWER_RATIO_THRESHOLD) return false

        return true
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
        attackerDisciples: List<Disciple>,
        existingBusyIds: Set<String> = emptySet()
    ): List<Disciple> {
        val availableDisciples = attackerDisciples
            .filter { it.isAlive && it.id !in existingBusyIds }
            .sortedBy { it.realm }

        if (availableDisciples.size < MIN_DISCIPLES_FOR_ATTACK) return emptyList()

        return availableDisciples.take(TEAM_SIZE)
    }

    fun createDefenseTeam(defenderDisciples: List<Disciple>): List<Disciple> {
        return defenderDisciples
            .filter { it.isAlive && it.status == DiscipleStatus.IDLE }
            .sortedBy { it.realm }
            .take(TEAM_SIZE)
    }


    /**
     * AI决定攻击玩家的结果——不再是立即执行战斗，
     * 而是返回【是否应生成预警】或【是否应跳过】。
     *
     * 预警生成后进入谴责→战书二级生命周期，
     * 到期后才执行实际战斗。
     */
    sealed interface PlayerAttackDecision {
        /** 不攻击（保护期/附庸/冷却/好感度>0 等） */
        data object Skip : PlayerAttackDecision
        /** 生成预警，进入谴责阶段 */
        data class GenerateWarning(
            val attackerSectId: String,
            val attackerSectName: String
        ) : PlayerAttackDecision
    }

    /**
     * 决定AI宗门是否应攻击玩家。
     *
     * 检查顺序：保护期 → 附庸关系 → 已有预警 → 个性冷却 →
     * 好感度 → 联盟 → 个性宣战概率。
     */
    fun decidePlayerAttack(gameData: GameData): PlayerAttackDecision {
        if (gameData.isPlayerProtected) return PlayerAttackDecision.Skip

        val playerSect = gameData.worldMapSects.find { it.isPlayerSect }
            ?: return PlayerAttackDecision.Skip
        val playerSectId = playerSect.id
        val nowMonth = gameData.gameYear * 12 + gameData.gameMonth

        val aiDisciplesMap = gameData.aiSectDisciples

        for (attacker in gameData.worldMapSects.filter { !it.isPlayerSect }) {
            // ---- 附庸关系：主宗不攻击附庸 ----
            if (gameData.suzerainSectId == attacker.id) continue

            // ---- 该宗门已有活跃预警 → 跳过 ----
            if (gameData.activeAttackWarnings.any {
                it.attackerSectId == attacker.id
            }) continue

            // ---- 冷却期检查 ----
            val cooldownUntil = gameData.sectAttackCooldowns[attacker.id]
            if (cooldownUntil != null && nowMonth < cooldownUntil) continue

            // ---- 个性参数获取 ----
            val personality = gameData.aiSectPersonalities[attacker.id]
                ?: AISectPersonality.BALANCED
            val denounceInterval = AISectPersonality.randomDenounceInterval(personality)

            // ---- 最低弟子数 ----
            val attackerDisciples = aiDisciplesMap[attacker.id] ?: emptyList()
            val aliveAttackers = attackerDisciples.filter { it.isAlive }
            if (aliveAttackers.size < MIN_DISCIPLES_FOR_ATTACK) continue

            // ---- 好感度 ----
            val relation = gameData.sectRelations.find {
                (it.sectId1 == attacker.id && it.sectId2 == playerSectId) ||
                (it.sectId1 == playerSectId && it.sectId2 == attacker.id)
            }
            val favor = relation?.favor ?: 0
            if (favor > 0) continue

            // ---- 联盟 ----
            if (attacker.allianceId.isNotEmpty() &&
                playerSect.allianceId == attacker.allianceId) continue

            // ---- 战力比门槛（按个性） ----
            val attackerPower = calculatePowerScore(aliveAttackers)
            val defenderDisciples = aiDisciplesMap[playerSectId] ?: emptyList()
            val defenderPower = calculatePowerScore(
                defenderDisciples.filter { it.isAlive }
            )
            if (defenderPower > 0 &&
                attackerPower < defenderPower * personality.powerRatioThreshold
            ) continue

            // ---- 宣战概率（按个性） ----
            if (Random.nextDouble() > personality.warProbability) continue

            // 通过所有检查 → 生成谴责预警
            return PlayerAttackDecision.GenerateWarning(
                attackerSectId = attacker.id,
                attackerSectName = attacker.name
            )
        }

        return PlayerAttackDecision.Skip
    }

    /**
     * 执行AI宗门对玩家的实际战斗（预警到期后调用）。
     *
     * @param playerDefenseTeam 玩家方出战弟子（已转换为 Combatant，使用真实装备/功法）
     */
    fun executePlayerAttack(
        gameData: GameData,
        attackerSectId: String,
        playerDefenseTeam: List<Combatant>
    ): AIAttackResult? {
        val aiDisciplesMap = gameData.aiSectDisciples
        val playerSect = gameData.worldMapSects.find { it.isPlayerSect } ?: return null
        val playerSectId = playerSect.id
        val attacker = gameData.worldMapSects.find { it.id == attackerSectId } ?: return null

        val attackerDisciples = aiDisciplesMap[attacker.id] ?: emptyList()
        val selectedAttackers = attackerDisciples.filter { it.isAlive }
            .sortedBy { it.realm }
            .take(TEAM_SIZE)
        if (selectedAttackers.size < MIN_DISCIPLES_FOR_ATTACK) return null

        val battleResult = executePlayerSectBattle(
            selectedAttackers, playerDefenseTeam
        )
        val survivingAttackers = selectedAttackers.filter {
            it.id !in battleResult.deadAttackerIds
        }

        return AIAttackResult(
            attackerSectId = attacker.id,
            defenderSectId = playerSectId,
            attackerSectName = attacker.name,
            defenderSectName = playerSect.name,
            winner = battleResult.winner,
            deadAttackerIds = battleResult.deadAttackerIds,
            deadDefenderIds = battleResult.deadDefenderIds,
            canOccupy = battleResult.canOccupy,
            survivingAttackers = survivingAttackers,
            defenderSurvivorHpMap = battleResult.survivorHpMap,
            defenderSurvivorMpMap = battleResult.survivorMpMap,
            rounds = battleResult.rounds
        )
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

    internal fun convertToCombatant(disciple: Disciple, side: CombatantSide): Combatant {
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
                ManualProficiencySystem.MasteryLevel.fromProficiency(mastery.toDouble()).level
            } else 0
            val maxProf = ManualProficiencySystem.MAX_PROFICIENCY.toInt()
            ManualProficiencyData(
                manualId = mId,
                proficiency = mastery.toDouble().coerceAtMost(maxProf.toDouble()),
                maxProficiency = maxProf,
                masteryLevel = masteryLevel
            )
        }

        val battleDisciple = disciple.copy(
            manualIds = manualIds,
            manualMasteries = manualMasteries,
            equipment = disciple.equipment.copy(
                weaponId = weaponId,
                armorId = armorId,
                bootsId = bootsId,
                accessoryId = accessoryId,
                weaponNurture = battleItems.weaponNurture,
                armorNurture = battleItems.armorNurture,
                bootsNurture = battleItems.bootsNurture,
                accessoryNurture = battleItems.accessoryNurture
            )
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
        val weaponName = battleItems.equipments
            .firstOrNull { it.second == EquipmentSlot.WEAPON }
            ?.first
            ?.let { EquipmentDatabase.getById(it)?.name }

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
            element = primaryElement,
            weaponName = weaponName,
            portraitRes = disciple.portraitRes
        )
    }

    fun executeAISectBattle(
        attackers: List<Disciple>,
        defenderSect: WorldSect,
        defenderDisciples: List<Disciple>,
        allSectDisciples: List<Disciple> = defenderDisciples
    ): AIBattleResult {
        return executeSectBattle(attackers, defenderSect, defenderDisciples, allSectDisciples)
    }

    fun executePlayerSectBattle(
        attackers: List<Disciple>,
        playerDefenseTeam: List<Combatant>
    ): AIBattleResult {
        val combatAttackers = attackers.map { convertToCombatant(it, CombatantSide.ATTACKER) }
        val combatDefenders = playerDefenseTeam
            .filter { it.side == CombatantSide.DEFENDER }
            .take(TEAM_SIZE)

        val result = executeUnifiedAIBattle(combatAttackers, combatDefenders)

        val deadAttackerIds = attackers
            .filter { disciple ->
                result.attackers.find { it.id == disciple.id } == null
            }
            .map { it.id }

        val survivorDefenderIds = result.defenders.map { it.id }.toSet()
        val deadDefenderIds = combatDefenders
            .filter { it.id !in survivorDefenderIds }
            .map { it.id }

        val survivorHpMap = result.defenders.associate { it.id to it.hp }
        val survivorMpMap = result.defenders.associate { it.id to it.mp }

        return AIBattleResult(
            winner = result.winner,
            deadAttackerIds = deadAttackerIds,
            deadDefenderIds = deadDefenderIds,
            canOccupy = result.winner == AIBattleWinner.ATTACKER,
            turns = result.turns,
            survivorHpMap = survivorHpMap,
            survivorMpMap = survivorMpMap,
            rounds = result.rounds
        )
    }

    private data class UnifiedAIBattleResult(
        val attackers: List<Combatant>,
        val defenders: List<Combatant>,
        val winner: AIBattleWinner,
        val turns: Int,
        val rounds: List<BattleLogRound> = emptyList()
    )

    private fun executeUnifiedAIBattle(
        attackers: List<Combatant>,
        defenders: List<Combatant>
    ): UnifiedAIBattleResult {
        var currentAttackers = attackers.toMutableList()
        var currentDefenders = defenders.toMutableList()
        var turn = 0
        val rounds = mutableListOf<BattleLogRound>()

        while (turn < GameConfig.AI.MAX_BATTLE_TURNS) {
            val roundActions = mutableListOf<BattleLogAction>()
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
                    executeSupportAction(currentCombatant, allies.filter { !it.isDead }, availableSkill, allies, alliesIndexMap, roundActions)
                } else if (availableSkill != null && isAoeSkill) {
                    executeAoeAttackAction(currentCombatant, aliveEnemies, availableSkill, allies, enemies, alliesIndexMap, enemiesIndexMap, roundActions)
                } else if (availableSkill != null) {
                    val target = selectAITarget(currentCombatant, aliveEnemies)
                    executeSingleAttackAction(currentCombatant, target, availableSkill, allies, enemies, alliesIndexMap, enemiesIndexMap, roundActions)
                } else {
                    val target = selectAITarget(currentCombatant, aliveEnemies)
                    executeNormalAttackAction(currentCombatant, target, allies, enemies, alliesIndexMap, enemiesIndexMap, roundActions)
                }

                currentAttackers = currentAttackers.filter { !it.isDead }.toMutableList()
                currentDefenders = currentDefenders.filter { !it.isDead }.toMutableList()
            }

            processDotEffects(currentAttackers, currentDefenders)

            rounds.add(BattleLogRound(roundNumber = turn + 1, actions = roundActions.toList()))
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
            winner = winner,
            turns = turn,
            rounds = rounds
        )
    }

    private fun executeNormalAttackAction(
        attacker: Combatant,
        target: Combatant,
        allies: MutableList<Combatant>,
        enemies: MutableList<Combatant>,
        alliesIndexMap: Map<String, Int>,
        enemiesIndexMap: Map<String, Int>,
        roundActions: MutableList<BattleLogAction>
    ) {
        if (BattleCalculator.checkInstantKill(attacker.realm, target.realm)) {
            val targetIdx = enemiesIndexMap[target.id]
            if (targetIdx != null && targetIdx < enemies.size) {
                enemies[targetIdx] = enemies[targetIdx].copy(hp = 0)
            }
            val combatantIdx = alliesIndexMap[attacker.id]
            if (combatantIdx != null && combatantIdx < allies.size) {
                allies[combatantIdx] = BattleCalculator.updateCombatantBuffsOnly(attacker)
            }
            roundActions.add(BattleLogAction(
                type = "normal", attacker = attacker.name,
                attackerType = if (attacker.side == CombatantSide.ATTACKER) "attacker" else "defender",
                target = target.name, damage = target.maxHp,
                isKill = true, message = "${attacker.name} 境界压制斩杀 ${target.name}"
            ))
            return
        }

        val result = BattleCalculator.calculateCombatantDamage(attacker, target, null)

        if (result.isDodged) {
            val combatantIdx = alliesIndexMap[attacker.id]
            if (combatantIdx != null && combatantIdx < allies.size) {
                allies[combatantIdx] = BattleCalculator.updateCombatantBuffsOnly(attacker)
            }
            roundActions.add(BattleLogAction(
                type = "normal", attacker = attacker.name,
                attackerType = if (attacker.side == CombatantSide.ATTACKER) "attacker" else "defender",
                target = target.name, damage = 0,
                message = "${target.name} 闪避了 ${attacker.name} 的攻击"
            ))
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
        roundActions.add(BattleLogAction(
            type = "normal", attacker = attacker.name,
            attackerType = if (attacker.side == CombatantSide.ATTACKER) "attacker" else "defender",
            target = target.name, damage = result.damage,
            isCrit = result.isCrit, isKill = newHp == 0
        ))
    }

    private fun executeSingleAttackAction(
        attacker: Combatant,
        target: Combatant,
        skill: CombatSkill,
        allies: MutableList<Combatant>,
        enemies: MutableList<Combatant>,
        alliesIndexMap: Map<String, Int>,
        enemiesIndexMap: Map<String, Int>,
        roundActions: MutableList<BattleLogAction>
    ) {
        if (BattleCalculator.checkInstantKill(attacker.realm, target.realm)) {
            val targetIdx = enemiesIndexMap[target.id]
            if (targetIdx != null && targetIdx < enemies.size) {
                enemies[targetIdx] = enemies[targetIdx].copy(hp = 0)
            }
            val combatantIdx = alliesIndexMap[attacker.id]
            if (combatantIdx != null && combatantIdx < allies.size) {
                allies[combatantIdx] = BattleCalculator.updateCombatantCooldowns(attacker, skill)
            }
            roundActions.add(BattleLogAction(
                type = "skill", attacker = attacker.name,
                attackerType = if (attacker.side == CombatantSide.ATTACKER) "attacker" else "defender",
                target = target.name, damage = target.maxHp, skillName = skill.name,
                isKill = true, message = "${attacker.name} 以 ${skill.name} 境界压制斩杀 ${target.name}"
            ))
            return
        }

        val result = BattleCalculator.calculateCombatantDamage(attacker, target, skill)

        if (result.isDodged) {
            val combatantIdx = alliesIndexMap[attacker.id]
            if (combatantIdx != null && combatantIdx < allies.size) {
                allies[combatantIdx] = BattleCalculator.updateCombatantCooldowns(attacker, skill)
            }
            roundActions.add(BattleLogAction(
                type = "skill", attacker = attacker.name,
                attackerType = if (attacker.side == CombatantSide.ATTACKER) "attacker" else "defender",
                target = target.name, damage = 0, skillName = skill.name,
                message = "${target.name} 闪避了 ${attacker.name} 的 ${skill.name}"
            ))
            return
        }

        val newHp = maxOf(0, target.hp - result.damage)
        val targetIdx = enemiesIndexMap[target.id]
        if (targetIdx != null && targetIdx < enemies.size) {
            var updatedTarget = enemies[targetIdx].copy(hp = newHp)

            val localBuffType = skill.buffType
            if (localBuffType != null && skill.buffDuration > 0) {
                val debuff = CombatBuff(type = localBuffType, value = skill.buffValue, remainingDuration = skill.buffDuration, sourceRealm = attacker.realm)
                updatedTarget = updatedTarget.copy(buffs = updatedTarget.buffs + debuff)
            }

            enemies[targetIdx] = updatedTarget
        }

        val combatantIdx = alliesIndexMap[attacker.id]
        if (combatantIdx != null && combatantIdx < allies.size) {
            allies[combatantIdx] = BattleCalculator.updateCombatantCooldowns(attacker, skill)
        }
        roundActions.add(BattleLogAction(
            type = "skill", attacker = attacker.name,
            attackerType = if (attacker.side == CombatantSide.ATTACKER) "attacker" else "defender",
            target = target.name, damage = result.damage, skillName = skill.name,
            isCrit = result.isCrit, isKill = newHp == 0
        ))
    }

    private fun executeAoeAttackAction(
        attacker: Combatant,
        targets: List<Combatant>,
        skill: CombatSkill,
        allies: MutableList<Combatant>,
        enemies: MutableList<Combatant>,
        alliesIndexMap: Map<String, Int>,
        enemiesIndexMap: Map<String, Int>,
        roundActions: MutableList<BattleLogAction>
    ) {
        val attackerType = if (attacker.side == CombatantSide.ATTACKER) "attacker" else "defender"
        for (target in targets) {
            if (target.isDead) continue

            if (BattleCalculator.checkInstantKill(attacker.realm, target.realm)) {
                val targetIdx = enemiesIndexMap[target.id]
                if (targetIdx != null && targetIdx < enemies.size) {
                    enemies[targetIdx] = enemies[targetIdx].copy(hp = 0)
                }
                roundActions.add(BattleLogAction(
                    type = "skill", attacker = attacker.name, attackerType = attackerType,
                    target = target.name, damage = target.maxHp, skillName = skill.name,
                    isKill = true, message = "${attacker.name} 以 ${skill.name} 境界压制斩杀 ${target.name}"
                ))
                continue
            }

            val result = BattleCalculator.calculateCombatantDamage(attacker, target, skill)

            if (result.isDodged) {
                roundActions.add(BattleLogAction(
                    type = "skill", attacker = attacker.name, attackerType = attackerType,
                    target = target.name, damage = 0, skillName = skill.name,
                    message = "${target.name} 闪避了 ${attacker.name} 的 ${skill.name}"
                ))
                continue
            }

            val newHp = maxOf(0, target.hp - result.damage)
            val targetIdx = enemiesIndexMap[target.id]
            if (targetIdx != null && targetIdx < enemies.size) {
                var updatedTarget = enemies[targetIdx].copy(hp = newHp)

                val localBuffType = skill.buffType
                if (localBuffType != null && skill.buffDuration > 0) {
                    val debuff = CombatBuff(type = localBuffType, value = skill.buffValue, remainingDuration = skill.buffDuration, sourceRealm = attacker.realm)
                    updatedTarget = updatedTarget.copy(buffs = updatedTarget.buffs + debuff)
                }

                enemies[targetIdx] = updatedTarget
            }
            roundActions.add(BattleLogAction(
                type = "skill", attacker = attacker.name, attackerType = attackerType,
                target = target.name, damage = result.damage, skillName = skill.name,
                isCrit = result.isCrit, isKill = newHp == 0
            ))
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
        alliesIndexMap: Map<String, Int>,
        roundActions: MutableList<BattleLogAction>
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
        roundActions.add(BattleLogAction(
            type = "support", attacker = caster.name,
            attackerType = if (caster.side == CombatantSide.ATTACKER) "attacker" else "defender",
            target = allies.joinToString("、") { it.name }, damage = supportResult.healAmount,
            skillName = skill.name,
            message = "${caster.name} 施展 ${skill.name}" +
                if (supportResult.healAmount > 0) "，恢复 ${supportResult.healedIds.size} 名友方 ${supportResult.healAmount}"
                else if (supportResult.teamBuffs.isNotEmpty()) "，强化 ${supportResult.teamBuffs.size} 名友方"
                else ""
        ))
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

    // 临时保存 BattleAI 决策结果，供 selectAISkill → selectAITarget 配对使用
    private var pendingAiAction: BattleAI.AIAction? = null

    private fun selectAISkill(
        combatant: Combatant,
        enemies: List<Combatant>,
        allies: List<Combatant>,
        isSilenced: Boolean
    ): CombatSkill? {
        if (isSilenced) {
            pendingAiAction = null
            return null
        }
        val action = BattleAI.decideAction(combatant, allies, enemies)
        pendingAiAction = action
        return action.skill
    }

    private fun selectAITarget(attacker: Combatant, targets: List<Combatant>): Combatant {
        val aliveTargets = targets.filter { !it.isDead }
        if (aliveTargets.isEmpty()) return targets.first()
        val action = pendingAiAction
        if (action != null && action.target != null) {
            pendingAiAction = null
            return action.target
        }
        pendingAiAction = null
        return BattleAI.selectAttackTarget(attacker, aliveTargets, null)
            ?: aliveTargets.first()
    }

    fun getGarrisonDisciples(sect: WorldSect, allDisciples: List<Disciple>): List<Disciple> {
        return sect.garrisonSlots
            .filter { it.discipleId.isNotEmpty() }
            .mapNotNull { slot -> allDisciples.find { it.id == slot.discipleId } }
            .filter { it.isAlive }
    }

    fun supplementDisciples(
        coreDisciples: List<Disciple>,
        availableDisciples: List<Disciple>
    ): List<Disciple> {
        val core = coreDisciples.take(TEAM_SIZE)
        if (core.size >= TEAM_SIZE) return core
        val coreIds = core.map { it.id }.toSet()
        val supplements = availableDisciples
            .filter { it.isAlive && it.id !in coreIds }
            .sortedBy { it.realm }
            .take(TEAM_SIZE - core.size)
        return core + supplements
    }

    fun createPlayerDefenseTeam(
        disciples: List<Disciple>
    ): List<Disciple> {
        return disciples
            .filter { it.isAlive && it.status == DiscipleStatus.IDLE }
            .sortedBy { it.realm }
            .take(TEAM_SIZE)
    }

    fun generateSectDestroyedEvent(attackerName: String, defenderName: String): String {
        return "⚔️ $attackerName 攻破了 $defenderName！"
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
        var spiritStones = 0L

        val equipmentStacks = mutableListOf<com.xianxia.sect.core.model.EquipmentStack>()
        val manualStacks = mutableListOf<com.xianxia.sect.core.model.ManualStack>()
        val pills = mutableListOf<com.xianxia.sect.core.model.Pill>()
        val materials = mutableListOf<com.xianxia.sect.core.model.Material>()
        val herbs = mutableListOf<com.xianxia.sect.core.model.Herb>()
        val seeds = mutableListOf<com.xianxia.sect.core.model.Seed>()

        repeat(itemCount) {
            val itemType = Random.nextInt(7)
            when (itemType) {
                0 -> spiritStones += config.spiritStoneValue
                1 -> {
                    if (com.xianxia.sect.core.registry.EquipmentDatabase.isInitialized) {
                        try {
                            equipmentStacks.add(
                                com.xianxia.sect.core.registry.EquipmentDatabase.generateRandom(config.minRarity, config.maxRarity)
                            )
                        } catch (e: Exception) { Log.w(TAG, "随机物品生成失败", e) }
                    }
                }
                2 -> {
                    if (com.xianxia.sect.core.registry.ManualDatabase.isInitialized) {
                        try {
                            manualStacks.add(
                                com.xianxia.sect.core.registry.ManualDatabase.generateRandom(config.minRarity, config.maxRarity)
                            )
                        } catch (e: Exception) { Log.w(TAG, "随机物品生成失败", e) }
                    }
                }
                3 -> {
                    try {
                        pills.add(
                            com.xianxia.sect.core.registry.ItemDatabase.generateRandomPill(config.minRarity, config.maxRarity)
                        )
                    } catch (e: Exception) { Log.w(TAG, "随机物品生成失败", e) }
                }
                4 -> {
                    try {
                        materials.add(
                            com.xianxia.sect.core.registry.ItemDatabase.generateRandomMaterial(config.minRarity, config.maxRarity)
                        )
                    } catch (e: Exception) { Log.w(TAG, "随机物品生成失败", e) }
                }
                5 -> {
                    try {
                        val herbTemplate = com.xianxia.sect.core.registry.HerbDatabase.generateRandomHerb(config.minRarity, config.maxRarity)
                        herbs.add(
                            com.xianxia.sect.core.model.Herb(
                                name = herbTemplate.name,
                                rarity = herbTemplate.rarity,
                                description = herbTemplate.description,
                                category = herbTemplate.category,
                                quantity = 1
                            )
                        )
                    } catch (e: Exception) { Log.w(TAG, "随机物品生成失败", e) }
                }
                6 -> {
                    try {
                        val seedTemplate = com.xianxia.sect.core.registry.HerbDatabase.generateRandomSeed(config.minRarity, config.maxRarity)
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
                    } catch (e: Exception) { Log.w(TAG, "随机物品生成失败", e) }
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
    val canOccupy: Boolean,
    val turns: Int = 0,
    val survivorHpMap: Map<String, Int> = emptyMap(),
    val survivorMpMap: Map<String, Int> = emptyMap(),
    val rounds: List<BattleLogRound> = emptyList(),
    val teamMembers: List<BattleLogMember> = emptyList(),
    val enemies: List<BattleLogEnemy> = emptyList()
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
