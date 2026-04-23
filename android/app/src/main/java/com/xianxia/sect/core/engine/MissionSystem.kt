@file:Suppress("DEPRECATION")

package com.xianxia.sect.core.engine

import com.xianxia.sect.core.data.BeastMaterialDatabase
import com.xianxia.sect.core.data.EquipmentDatabase
import com.xianxia.sect.core.data.ItemDatabase
import com.xianxia.sect.core.data.ManualDatabase
import com.xianxia.sect.core.model.ActiveMission
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EnemyType
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Mission
import com.xianxia.sect.core.model.MissionDifficulty
import com.xianxia.sect.core.model.MissionRewardConfig
import com.xianxia.sect.core.model.MissionTemplate
import com.xianxia.sect.core.model.MissionType
import com.xianxia.sect.core.model.Pill
import kotlin.random.Random

object MissionSystem {
    const val EXPIRY_MONTHS = 3
    const val REFRESH_INTERVAL_MONTHS = 3
    const val MAX_REFRESH_COUNT = 5

    data class MissionResult(
        val spiritStones: Int = 0,
        val materials: List<Material> = emptyList(),
        val pills: List<Pill> = emptyList(),
        val equipmentStacks: List<com.xianxia.sect.core.model.EquipmentStack> = emptyList(),
        val manualStacks: List<com.xianxia.sect.core.model.ManualStack> = emptyList(),
        val battleResult: BattleSystemResult? = null,
        val combatTriggered: Boolean = false,
        val victory: Boolean = true
    )

    data class ValidationResult(
        val valid: Boolean,
        val errorMessage: String? = null
    )

    data class MonthlyRefreshResult(
        val newMissions: List<Mission>,
        val cleanedMissions: List<Mission>
    )

    fun processMonthlyRefresh(
        existingMissions: List<Mission>,
        currentYear: Int,
        currentMonth: Int
    ): MonthlyRefreshResult {
        val newMissions = mutableListOf<Mission>()

        if (currentMonth % REFRESH_INTERVAL_MONTHS == 0) {
            val refreshCount = Random.nextInt(0, MAX_REFRESH_COUNT + 1)
            val pool = MissionTemplate.entries
            repeat(refreshCount) {
                val template = pool.random()
                newMissions.add(createMission(template, currentYear, currentMonth))
            }
        }

        val afterClean = cleanExpiredMissions(existingMissions, currentYear, currentMonth)

        return MonthlyRefreshResult(
            newMissions = newMissions,
            cleanedMissions = afterClean + newMissions
        )
    }

    private fun createMission(
        template: MissionTemplate,
        year: Int = 1,
        month: Int = 1
    ): Mission {
        val difficulty = template.difficulty
        val rewards = createRewardConfig(template)

        return Mission(
            template = template,
            name = "${difficulty.displayName}${template.displayName}",
            description = template.description,
            difficulty = difficulty,
            duration = template.duration,
            rewards = rewards,
            missionType = template.missionType,
            enemyType = template.enemyType,
            triggerChance = template.triggerChance,
            createdYear = year,
            createdMonth = month
        )
    }

    private fun createRewardConfig(template: MissionTemplate): MissionRewardConfig {
        return when (template) {
            MissionTemplate.ESCORT_CARAVAN -> MissionRewardConfig(
                spiritStones = 600
            )
            MissionTemplate.PATROL_TERRITORY -> MissionRewardConfig(
                spiritStones = 300,
                materialCountMin = 5,
                materialCountMax = 10,
                materialMinRarity = 1,
                materialMaxRarity = 1
            )
            MissionTemplate.DELIVER_SUPPLIES -> MissionRewardConfig(
                spiritStones = 400,
                pillCountMin = 1,
                pillCountMax = 2,
                pillMinRarity = 1,
                pillMaxRarity = 1
            )
            MissionTemplate.SUPPRESS_LOW_BEASTS -> MissionRewardConfig(
                spiritStones = 400,
                materialCountMin = 10,
                materialCountMax = 15,
                materialMinRarity = 1,
                materialMaxRarity = 1
            )
            MissionTemplate.CLEAR_BANDITS -> MissionRewardConfig(
                spiritStones = 500,
                materialCountMin = 8,
                materialCountMax = 12,
                materialMinRarity = 1,
                materialMaxRarity = 1,
                equipmentChance = 0.3,
                equipmentMinRarity = 1,
                equipmentMaxRarity = 1
            )
            MissionTemplate.EXPLORE_ABANDONED_MINE -> MissionRewardConfig(
                baseSpiritStones = 200,
                spiritStones = 500,
                baseMaterialCountMin = 3,
                baseMaterialCountMax = 5,
                baseMaterialMinRarity = 1,
                baseMaterialMaxRarity = 1,
                materialCountMin = 10,
                materialCountMax = 15,
                materialMinRarity = 1,
                materialMaxRarity = 2
            )

            MissionTemplate.ESCORT_SPIRIT_CARAVAN -> MissionRewardConfig(
                spiritStones = 1500
            )
            MissionTemplate.INVESTIGATE_ANOMALY -> MissionRewardConfig(
                spiritStones = 800,
                materialCountMin = 8,
                materialCountMax = 15,
                materialMinRarity = 2,
                materialMaxRarity = 2
            )
            MissionTemplate.DELIVER_PILLS -> MissionRewardConfig(
                spiritStones = 1000,
                pillCountMin = 1,
                pillCountMax = 2,
                pillMinRarity = 2,
                pillMaxRarity = 2
            )
            MissionTemplate.SUPPRESS_JINDAN_BEASTS -> MissionRewardConfig(
                spiritStones = 1200,
                materialCountMin = 15,
                materialCountMax = 25,
                materialMinRarity = 2,
                materialMaxRarity = 3
            )
            MissionTemplate.DESTROY_MAGIC_OUTPOST -> MissionRewardConfig(
                spiritStones = 1500,
                materialCountMin = 12,
                materialCountMax = 20,
                materialMinRarity = 2,
                materialMaxRarity = 3,
                equipmentChance = 0.3,
                equipmentMinRarity = 2,
                equipmentMaxRarity = 3
            )
            MissionTemplate.EXPLORE_ANCIENT_CAVE -> MissionRewardConfig(
                baseSpiritStones = 600,
                spiritStones = 1800,
                baseMaterialCountMin = 5,
                baseMaterialCountMax = 10,
                baseMaterialMinRarity = 2,
                baseMaterialMaxRarity = 2,
                materialCountMin = 18,
                materialCountMax = 28,
                materialMinRarity = 2,
                materialMaxRarity = 3,
                manualChance = 0.3,
                manualMinRarity = 2,
                manualMaxRarity = 3
            )

            MissionTemplate.ESCORT_IMMORTAL_ENVOY -> MissionRewardConfig(
                spiritStones = 40000
            )
            MissionTemplate.REPAIR_ANCIENT_FORMATION -> MissionRewardConfig(
                spiritStones = 20000,
                materialCountMin = 10,
                materialCountMax = 20,
                materialMinRarity = 4,
                materialMaxRarity = 5
            )
            MissionTemplate.SEARCH_MISSING_ELDER -> MissionRewardConfig(
                spiritStones = 25000,
                pillCountMin = 1,
                pillCountMax = 2,
                pillMinRarity = 4,
                pillMaxRarity = 4
            )
            MissionTemplate.SUPPRESS_HUASHEN_BEAST_KING -> MissionRewardConfig(
                spiritStones = 30000,
                materialCountMin = 20,
                materialCountMax = 35,
                materialMinRarity = 4,
                materialMaxRarity = 5,
                equipmentChance = 0.3,
                equipmentMinRarity = 4,
                equipmentMaxRarity = 5
            )
            MissionTemplate.DESTROY_MAGIC_BRANCH -> MissionRewardConfig(
                spiritStones = 40000,
                materialCountMin = 18,
                materialCountMax = 30,
                materialMinRarity = 4,
                materialMaxRarity = 5,
                equipmentChance = 0.3,
                equipmentMinRarity = 4,
                equipmentMaxRarity = 5
            )
            MissionTemplate.EXPLORE_ANCIENT_BATTLEFIELD -> MissionRewardConfig(
                baseSpiritStones = 12500,
                spiritStones = 50000,
                baseMaterialCountMin = 8,
                baseMaterialCountMax = 15,
                baseMaterialMinRarity = 4,
                baseMaterialMaxRarity = 4,
                materialCountMin = 25,
                materialCountMax = 40,
                materialMinRarity = 4,
                materialMaxRarity = 5,
                manualChance = 0.3,
                manualMinRarity = 4,
                manualMaxRarity = 5
            )

            MissionTemplate.ESCORT_RELIC_ARTIFACT -> MissionRewardConfig(
                spiritStones = 200000
            )
            MissionTemplate.SEAL_SPATIAL_RIFT -> MissionRewardConfig(
                spiritStones = 100000,
                materialCountMin = 15,
                materialCountMax = 25,
                materialMinRarity = 5,
                materialMaxRarity = 6
            )
            MissionTemplate.SEARCH_SECRET_REALM_CLUE -> MissionRewardConfig(
                spiritStones = 150000,
                pillCountMin = 1,
                pillCountMax = 2,
                pillMinRarity = 5,
                pillMaxRarity = 5
            )
            MissionTemplate.SUPPRESS_ANCIENT_FIEND -> MissionRewardConfig(
                spiritStones = 150000,
                materialCountMin = 25,
                materialCountMax = 45,
                materialMinRarity = 5,
                materialMaxRarity = 6,
                equipmentChance = 0.3,
                equipmentMinRarity = 5,
                equipmentMaxRarity = 6
            )
            MissionTemplate.DESTROY_MAGIC_HEADQUARTERS -> MissionRewardConfig(
                spiritStones = 200000,
                materialCountMin = 22,
                materialCountMax = 38,
                materialMinRarity = 5,
                materialMaxRarity = 6,
                equipmentChance = 0.3,
                equipmentMinRarity = 5,
                equipmentMaxRarity = 6
            )
            MissionTemplate.EXPLORE_CORE_BATTLEFIELD -> MissionRewardConfig(
                baseSpiritStones = 60000,
                spiritStones = 250000,
                baseMaterialCountMin = 10,
                baseMaterialCountMax = 18,
                baseMaterialMinRarity = 5,
                baseMaterialMaxRarity = 5,
                materialCountMin = 30,
                materialCountMax = 50,
                materialMinRarity = 5,
                materialMaxRarity = 6,
                manualChance = 0.3,
                manualMinRarity = 5,
                manualMaxRarity = 6
            )
        }
    }

    fun validateDisciplesForMission(
        mission: Mission,
        disciples: List<Disciple>
    ): ValidationResult {
        if (disciples.size != mission.memberCount) {
            return ValidationResult(false, "队伍需要${mission.memberCount}名弟子")
        }

        val allowedTypes = mission.difficulty.allowedDiscipleTypes
        val minRealm = mission.difficulty.minRealm

        for (disciple in disciples) {
            if (disciple.status != DiscipleStatus.IDLE) {
                return ValidationResult(false, "弟子${disciple.name}状态不允许")
            }

            if (disciple.discipleType !in allowedTypes) {
                val required = allowedTypes.joinToString("/") { if (it == "outer") "外门" else "内门" }
                return ValidationResult(false, "弟子${disciple.name}职务不符合，需要${required}弟子")
            }

            if (disciple.realm > minRealm) {
                val realmName = com.xianxia.sect.core.GameConfig.Realm.getName(minRealm)
                return ValidationResult(false, "弟子${disciple.name}境界不足，需要${realmName}及以上")
            }
        }

        return ValidationResult(true)
    }

    fun createActiveMission(
        mission: Mission,
        disciples: List<Disciple>,
        currentYear: Int,
        currentMonth: Int
    ): ActiveMission {
        require(disciples.size == mission.memberCount) { "任务需要 ${mission.memberCount} 名弟子，实际传入 ${disciples.size}" }

        return ActiveMission(
            missionId = mission.id,
            missionName = mission.name,
            template = mission.template,
            difficulty = mission.difficulty,
            discipleIds = disciples.map { it.id },
            discipleNames = disciples.map { it.name },
            discipleRealms = disciples.map { it.realmNameOnly },
            startYear = currentYear,
            startMonth = currentMonth,
            duration = mission.duration,
            rewards = mission.rewards,
            missionType = mission.missionType,
            enemyType = mission.enemyType,
            triggerChance = mission.triggerChance
        )
    }

    fun processMissionCompletion(
        activeMission: ActiveMission,
        disciples: List<Disciple>,
        equipmentMap: Map<String, com.xianxia.sect.core.model.EquipmentInstance> = emptyMap(),
        manualMap: Map<String, com.xianxia.sect.core.model.ManualInstance> = emptyMap(),
        manualProficiencies: Map<String, Map<String, com.xianxia.sect.core.model.ManualProficiencyData>> = emptyMap(),
        battleSystem: BattleSystem? = null
    ): MissionResult {
        return when (activeMission.missionType) {
            MissionType.NO_COMBAT -> processNoCombatMission(activeMission)
            MissionType.COMBAT_REQUIRED -> processCombatRequiredMission(
                activeMission, disciples, equipmentMap, manualMap, manualProficiencies, battleSystem
            )
            MissionType.COMBAT_RANDOM -> processCombatRandomMission(
                activeMission, disciples, equipmentMap, manualMap, manualProficiencies, battleSystem
            )
        }
    }

    private fun processNoCombatMission(activeMission: ActiveMission): MissionResult {
        val rewards = activeMission.rewards
        val spiritStones = rollSpiritStones(rewards)
        val materials = generateMaterials(rewards)
        val pills = generatePills(rewards)

        return MissionResult(
            spiritStones = spiritStones,
            materials = materials,
            pills = pills,
            victory = true
        )
    }

    private fun processCombatRequiredMission(
        activeMission: ActiveMission,
        disciples: List<Disciple>,
        equipmentMap: Map<String, com.xianxia.sect.core.model.EquipmentInstance>,
        manualMap: Map<String, com.xianxia.sect.core.model.ManualInstance>,
        manualProficiencies: Map<String, Map<String, com.xianxia.sect.core.model.ManualProficiencyData>>,
        battleSystem: BattleSystem?
    ): MissionResult {
        val battleResult = executeMissionBattle(
            activeMission, disciples, equipmentMap, manualMap, manualProficiencies, battleSystem
        ) ?: return MissionResult(victory = false)

        if (!battleResult.victory) {
            return MissionResult(
                battleResult = battleResult,
                combatTriggered = true,
                victory = false
            )
        }

        val rewards = activeMission.rewards
        val spiritStones = rollSpiritStones(rewards)
        val materials = generateMaterials(rewards)
        val pills = generatePills(rewards)
        val equipmentStacks = generateEquipment(rewards)
        val manualStacks = generateManuals(rewards)

        return MissionResult(
            spiritStones = spiritStones,
            materials = materials,
            pills = pills,
            equipmentStacks = equipmentStacks,
            manualStacks = manualStacks,
            battleResult = battleResult,
            combatTriggered = true,
            victory = true
        )
    }

    private fun processCombatRandomMission(
        activeMission: ActiveMission,
        disciples: List<Disciple>,
        equipmentMap: Map<String, com.xianxia.sect.core.model.EquipmentInstance>,
        manualMap: Map<String, com.xianxia.sect.core.model.ManualInstance>,
        manualProficiencies: Map<String, Map<String, com.xianxia.sect.core.model.ManualProficiencyData>>,
        battleSystem: BattleSystem?
    ): MissionResult {
        val triggered = Random.nextDouble() < activeMission.triggerChance

        if (!triggered) {
            val rewards = activeMission.rewards
            val baseSpiritStones = rewards.baseSpiritStones
            val baseMaterials = generateBaseMaterials(rewards)

            return MissionResult(
                spiritStones = baseSpiritStones,
                materials = baseMaterials,
                combatTriggered = false,
                victory = true
            )
        }

        val battleResult = executeMissionBattle(
            activeMission, disciples, equipmentMap, manualMap, manualProficiencies, battleSystem
        ) ?: return MissionResult(combatTriggered = true, victory = false)

        if (!battleResult.victory) {
            return MissionResult(
                battleResult = battleResult,
                combatTriggered = true,
                victory = false
            )
        }

        val rewards = activeMission.rewards
        val spiritStones = rollSpiritStones(rewards)
        val materials = generateMaterials(rewards)
        val pills = generatePills(rewards)
        val equipmentStacks = generateEquipment(rewards)
        val manualStacks = generateManuals(rewards)

        return MissionResult(
            spiritStones = spiritStones,
            materials = materials,
            pills = pills,
            equipmentStacks = equipmentStacks,
            manualStacks = manualStacks,
            battleResult = battleResult,
            combatTriggered = true,
            victory = true
        )
    }

    private fun executeMissionBattle(
        activeMission: ActiveMission,
        disciples: List<Disciple>,
        equipmentMap: Map<String, com.xianxia.sect.core.model.EquipmentInstance>,
        manualMap: Map<String, com.xianxia.sect.core.model.ManualInstance>,
        manualProficiencies: Map<String, Map<String, com.xianxia.sect.core.model.ManualProficiencyData>>,
        battleSystem: BattleSystem?
    ): BattleSystemResult? {
        if (battleSystem == null) return null

        val difficulty = activeMission.difficulty
        val realmMin = difficulty.enemyRealmMin
        val realmMax = difficulty.enemyRealmMax

        return when (activeMission.enemyType) {
            EnemyType.BEAST -> {
                val beastCount = Random.nextInt(
                    activeMission.template.beastCountRange.first,
                    activeMission.template.beastCountRange.last + 1
                )
                val beastRealm = Random.nextInt(realmMin, realmMax + 1)
                val battle = battleSystem.createBattle(
                    disciples = disciples,
                    equipmentMap = equipmentMap,
                    manualMap = manualMap,
                    beastLevel = beastRealm,
                    beastCount = beastCount,
                    manualProficiencies = manualProficiencies
                )
                battleSystem.executeBattle(battle)
            }
            EnemyType.HUMAN -> {
                val humanCount = Random.nextInt(
                    activeMission.template.humanCountRange.first,
                    activeMission.template.humanCountRange.last + 1
                )
                val enemies = EnemyGenerator.generateHumanEnemies(realmMin, realmMax, humanCount)
                val team = disciples.map { disciple ->
                    battleSystem.convertDiscipleToCombatant(
                        disciple, equipmentMap, manualMap, manualProficiencies,
                        com.xianxia.sect.core.CombatantSide.DEFENDER
                    )
                }
                val battle = Battle(
                    team = team,
                    beasts = enemies.map { it.combatant },
                    turn = 0,
                    isFinished = false,
                    winner = null
                )
                battleSystem.executeBattle(battle)
            }
        }
    }

    private fun rollSpiritStones(rewards: MissionRewardConfig): Int {
        return if (rewards.spiritStonesMax > 0) {
            Random.nextInt(rewards.spiritStones, rewards.spiritStonesMax + 1)
        } else {
            rewards.spiritStones
        }
    }

    private fun generateMaterials(rewards: MissionRewardConfig): List<Material> {
        if (rewards.materialCountMin <= 0) return emptyList()

        val count = Random.nextInt(rewards.materialCountMin, rewards.materialCountMax + 1)
        val materials = mutableListOf<Material>()

        repeat(count) {
            val eligibleMaterials = BeastMaterialDatabase.getAllMaterials()
                .filter { it.rarity in rewards.materialMinRarity..rewards.materialMaxRarity }
            if (eligibleMaterials.isNotEmpty()) {
                val template = eligibleMaterials.random()
                materials.add(ItemDatabase.createMaterialFromTemplate(
                    ItemDatabase.MaterialTemplate(
                        id = template.id,
                        name = template.name,
                        category = template.materialCategory,
                        rarity = template.rarity,
                        description = template.description,
                        price = template.price
                    )
                ))
            }
        }

        return materials
    }

    private fun generateBaseMaterials(rewards: MissionRewardConfig): List<Material> {
        if (rewards.baseMaterialCountMin <= 0) return emptyList()

        val count = Random.nextInt(rewards.baseMaterialCountMin, rewards.baseMaterialCountMax + 1)
        val materials = mutableListOf<Material>()

        repeat(count) {
            val eligibleMaterials = BeastMaterialDatabase.getAllMaterials()
                .filter { it.rarity in rewards.baseMaterialMinRarity..rewards.baseMaterialMaxRarity }
            if (eligibleMaterials.isNotEmpty()) {
                val template = eligibleMaterials.random()
                materials.add(ItemDatabase.createMaterialFromTemplate(
                    ItemDatabase.MaterialTemplate(
                        id = template.id,
                        name = template.name,
                        category = template.materialCategory,
                        rarity = template.rarity,
                        description = template.description,
                        price = template.price
                    )
                ))
            }
        }

        return materials
    }

    private fun generatePills(rewards: MissionRewardConfig): List<Pill> {
        if (rewards.pillCountMin <= 0) return emptyList()

        val count = Random.nextInt(rewards.pillCountMin, rewards.pillCountMax + 1)
        val pills = mutableListOf<Pill>()

        repeat(count) {
            pills.add(ItemDatabase.generateRandomPill(rewards.pillMinRarity, rewards.pillMaxRarity))
        }

        return pills
    }

    private fun generateEquipment(rewards: MissionRewardConfig): List<com.xianxia.sect.core.model.EquipmentStack> {
        if (rewards.equipmentChance <= 0.0) return emptyList()
        if (Random.nextDouble() >= rewards.equipmentChance) return emptyList()

        return listOf(EquipmentDatabase.generateRandom(rewards.equipmentMinRarity, rewards.equipmentMaxRarity))
    }

    private fun generateManuals(rewards: MissionRewardConfig): List<com.xianxia.sect.core.model.ManualStack> {
        if (rewards.manualChance <= 0.0) return emptyList()
        if (Random.nextDouble() >= rewards.manualChance) return emptyList()

        return try {
            listOf(ManualDatabase.generateRandom(rewards.manualMinRarity, rewards.manualMaxRarity))
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun cleanExpiredMissions(
        missions: List<Mission>,
        currentYear: Int,
        currentMonth: Int
    ): List<Mission> {
        return missions.filter { mission ->
            !isExpired(mission, currentYear, currentMonth)
        }
    }

    fun isExpired(mission: Mission, currentYear: Int, currentMonth: Int): Boolean {
        val yearDiff = currentYear - mission.createdYear
        val monthDiff = currentMonth - mission.createdMonth
        val totalMonths = yearDiff * 12 + monthDiff
        return totalMonths >= EXPIRY_MONTHS
    }
}
