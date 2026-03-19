package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.data.BeastMaterialDatabase
import com.xianxia.sect.core.data.EquipmentDatabase
import com.xianxia.sect.core.data.HerbDatabase
import com.xianxia.sect.core.data.ItemDatabase
import com.xianxia.sect.core.data.ManualDatabase
import com.xianxia.sect.core.model.ActiveMission
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.Equipment
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.InvestigateOutcome
import com.xianxia.sect.core.model.Manual
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Mission
import com.xianxia.sect.core.model.MissionDifficulty
import com.xianxia.sect.core.model.MissionRewardConfig
import com.xianxia.sect.core.model.MissionType
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.RewardItemType
import com.xianxia.sect.core.model.Seed
import kotlin.random.Random

object MissionSystem {
    const val TEAM_SIZE = 5
    const val MIN_SLOTS = 2
    const val MAX_SLOTS = 5
    const val SLOT_EXPAND_COST = 5000

    private val spiritStoneRewards = mapOf(
        MissionDifficulty.YELLOW to 500,
        MissionDifficulty.MYSTERIOUS to 1200,
        MissionDifficulty.EARTH to 5000,
        MissionDifficulty.HEAVEN to 20000
    )

    private val extraItemChances = mapOf(
        MissionDifficulty.YELLOW to 0.75,
        MissionDifficulty.MYSTERIOUS to 0.60,
        MissionDifficulty.EARTH to 0.20,
        MissionDifficulty.HEAVEN to 0.20
    )

    private val extraItemCountRanges = mapOf(
        MissionDifficulty.YELLOW to (1..5),
        MissionDifficulty.MYSTERIOUS to (1..2),
        MissionDifficulty.EARTH to (1..1),
        MissionDifficulty.HEAVEN to (1..1)
    )

    private val extraItemMaxRarities = mapOf(
        MissionDifficulty.YELLOW to 2,
        MissionDifficulty.MYSTERIOUS to 3,
        MissionDifficulty.EARTH to 4,
        MissionDifficulty.HEAVEN to 6
    )

    private val materialCountRanges = mapOf(
        MissionDifficulty.YELLOW to (5..10),
        MissionDifficulty.MYSTERIOUS to (5..10),
        MissionDifficulty.EARTH to (1..5),
        MissionDifficulty.HEAVEN to (1..5)
    )

    private val materialMaxRarities = mapOf(
        MissionDifficulty.YELLOW to 2,
        MissionDifficulty.MYSTERIOUS to 3,
        MissionDifficulty.EARTH to 4,
        MissionDifficulty.HEAVEN to 6
    )

    data class MissionResult(
        val spiritStones: Int = 0,
        val pills: List<Pill> = emptyList(),
        val materials: List<Material> = emptyList(),
        val herbs: List<Herb> = emptyList(),
        val seeds: List<Seed> = emptyList(),
        val equipment: List<Equipment> = emptyList(),
        val manuals: List<Manual> = emptyList(),
        val investigateOutcome: InvestigateOutcome? = null
    )

    data class ValidationResult(
        val valid: Boolean,
        val errorMessage: String? = null
    )

    fun generateMissions(currentYear: Int, currentMonth: Int): List<Mission> {
        val missionCount = Random.nextInt(1, 8)
        val missions = mutableListOf<Mission>()
        
        val availableTypes = listOf(
            MissionType.ESCORT,
            MissionType.HUNT,
            MissionType.PATROL,
            MissionType.INVESTIGATE
        )
        
        val usedTypes = mutableSetOf<MissionType>()
        
        repeat(missionCount) {
            val difficulty = MissionDifficulty.fromSpawnChance(Random.nextDouble())
            val availableForDifficulty = availableTypes.filter { type ->
                isDifficultyAvailableForType(type, difficulty)
            }
            
            if (availableForDifficulty.isEmpty()) return@repeat
            
            val type = if (usedTypes.size < availableForDifficulty.size) {
                availableForDifficulty.filter { it !in usedTypes }.randomOrNull() 
                    ?: availableForDifficulty.random()
            } else {
                availableForDifficulty.random()
            }
            usedTypes.add(type)
            
            val mission = createMission(type, difficulty, currentYear, currentMonth)
            missions.add(mission)
        }
        
        return missions
    }

    private fun isDifficultyAvailableForType(type: MissionType, difficulty: MissionDifficulty): Boolean {
        return when (type) {
            MissionType.PATROL -> difficulty == MissionDifficulty.YELLOW || difficulty == MissionDifficulty.MYSTERIOUS
            MissionType.GUARD_CITY -> difficulty == MissionDifficulty.EARTH || difficulty == MissionDifficulty.HEAVEN
            else -> true
        }
    }

    private fun createMission(type: MissionType, difficulty: MissionDifficulty, year: Int, month: Int): Mission {
        val rewards = createRewardConfig(type, difficulty)
        
        return Mission(
            type = type,
            name = Mission.generateName(type, difficulty),
            description = type.description,
            difficulty = difficulty,
            minRealm = difficulty.minRealm,
            duration = difficulty.durationMonths,
            rewards = rewards,
            successRate = 0.7,
            createdYear = year,
            createdMonth = month
        )
    }

    private fun createRewardConfig(type: MissionType, difficulty: MissionDifficulty): MissionRewardConfig {
        val baseSpiritStones = spiritStoneRewards[difficulty] ?: 500
        
        return when (type) {
            MissionType.ESCORT -> MissionRewardConfig(
                spiritStones = baseSpiritStones
            )
            MissionType.HUNT -> MissionRewardConfig(
                spiritStones = baseSpiritStones,
                extraItemChance = extraItemChances[difficulty] ?: 0.0,
                extraItemCountRange = extraItemCountRanges[difficulty] ?: (0..0),
                extraItemMaxRarity = extraItemMaxRarities[difficulty] ?: 2,
                materialCountRange = materialCountRanges[difficulty] ?: (0..0),
                materialMaxRarity = materialMaxRarities[difficulty] ?: 2
            )
            MissionType.PATROL -> createPatrolRewardConfig(difficulty)
            MissionType.INVESTIGATE -> MissionRewardConfig(
                spiritStones = baseSpiritStones
            )
            MissionType.GUARD_CITY -> createGuardCityRewardConfig(difficulty)
        }
    }

    private fun createPatrolRewardConfig(difficulty: MissionDifficulty): MissionRewardConfig {
        return when (difficulty) {
            MissionDifficulty.YELLOW -> MissionRewardConfig(
                herbCountRange = (5..20),
                herbMinRarity = 1,
                herbMaxRarity = 1,
                seedCountRange = (5..20),
                seedMinRarity = 1,
                seedMaxRarity = 1
            )
            MissionDifficulty.MYSTERIOUS -> MissionRewardConfig(
                herbCountRange = (5..15),
                herbMinRarity = 1,
                herbMaxRarity = 2,
                seedCountRange = (5..15),
                seedMinRarity = 1,
                seedMaxRarity = 2
            )
            else -> MissionRewardConfig()
        }
    }

    private fun createGuardCityRewardConfig(difficulty: MissionDifficulty): MissionRewardConfig {
        return when (difficulty) {
            MissionDifficulty.EARTH -> MissionRewardConfig(
                extraItemChance = 1.0,
                extraItemCountRange = (2..5),
                extraItemMinRarity = 3,
                extraItemMaxRarity = 4
            )
            MissionDifficulty.HEAVEN -> MissionRewardConfig(
                extraItemChance = 1.0,
                extraItemCountRange = (1..2),
                extraItemMinRarity = 4,
                extraItemMaxRarity = 6
            )
            else -> MissionRewardConfig()
        }
    }

    fun validateDisciplesForMission(
        mission: Mission,
        disciples: List<Disciple>
    ): ValidationResult {
        if (disciples.size != TEAM_SIZE) {
            return ValidationResult(false, "队伍需要${TEAM_SIZE}名弟子")
        }

        for (disciple in disciples) {
            if (disciple.status != DiscipleStatus.IDLE) {
                return ValidationResult(false, "弟子${disciple.name}状态不允许")
            }

            val disciplePosition = if (disciple.discipleType == "outer") "外门弟子" else "内门弟子"
            if (disciplePosition !in mission.difficulty.allowedPositions) {
                return ValidationResult(false, "弟子${disciple.name}职务不符合")
            }

            if (disciple.realm > mission.minRealm) {
                return ValidationResult(false, "弟子${disciple.name}境界不足")
            }
        }

        return ValidationResult(true)
    }

    fun calculateSuccessRate(mission: Mission, disciples: List<Disciple>): Double {
        val baseRate = mission.successRate
        val avgRealm = disciples.map { it.realm }.average()
        val realmBonus = (avgRealm - mission.minRealm) * 0.05
        return (baseRate + realmBonus).coerceIn(0.0, 0.95)
    }

    fun createActiveMission(
        mission: Mission,
        disciples: List<Disciple>,
        currentYear: Int,
        currentMonth: Int
    ): ActiveMission {
        val successRate = calculateSuccessRate(mission, disciples)
        val investigateOutcome = if (mission.type == MissionType.INVESTIGATE) {
            InvestigateOutcome.random()
        } else null

        return ActiveMission(
            missionId = mission.id,
            missionName = mission.name,
            missionType = mission.type,
            difficulty = mission.difficulty,
            discipleIds = disciples.map { it.id },
            discipleNames = disciples.map { it.name },
            startYear = currentYear,
            startMonth = currentMonth,
            duration = mission.duration,
            rewards = mission.rewards,
            successRate = successRate,
            investigateOutcome = investigateOutcome
        )
    }

    fun processMissionCompletion(
        activeMission: ActiveMission,
        disciples: List<Disciple>
    ): MissionResult {
        return when (activeMission.missionType) {
            MissionType.ESCORT -> handleEscortSuccess(activeMission)
            MissionType.HUNT -> handleHuntSuccess(activeMission)
            MissionType.PATROL -> handlePatrolSuccess(activeMission)
            MissionType.INVESTIGATE -> handleInvestigateSuccess(activeMission)
            MissionType.GUARD_CITY -> handleGuardCitySuccess(activeMission)
        }
    }

    private fun handleEscortSuccess(activeMission: ActiveMission): MissionResult {
        return MissionResult(
            spiritStones = activeMission.rewards.spiritStones
        )
    }

    private fun handleHuntSuccess(activeMission: ActiveMission): MissionResult {
        val rewards = activeMission.rewards
        val materials = generateMaterials(rewards)
        val extraItems = generateExtraItems(rewards)

        return MissionResult(
            spiritStones = rewards.spiritStones,
            materials = materials,
            pills = extraItems.pills,
            equipment = extraItems.equipment,
            manuals = extraItems.manuals
        )
    }

    private fun handlePatrolSuccess(activeMission: ActiveMission): MissionResult {
        val rewards = activeMission.rewards
        val herbs = generateHerbs(rewards)
        val seeds = generateSeeds(rewards)

        return MissionResult(
            herbs = herbs,
            seeds = seeds
        )
    }

    private fun handleInvestigateSuccess(activeMission: ActiveMission): MissionResult {
        val outcome = activeMission.investigateOutcome ?: InvestigateOutcome.random()
        val difficulty = activeMission.difficulty

        return when (outcome) {
            InvestigateOutcome.BEAST_RIOT -> handleBeastRiotOutcome(activeMission, difficulty)
            InvestigateOutcome.SECT_CONFLICT -> handleSectConflictOutcome(activeMission, difficulty)
            InvestigateOutcome.DESTINED_CHILD -> handleDestinedChildOutcome(activeMission, difficulty)
            InvestigateOutcome.NOTHING_FOUND -> MissionResult(
                investigateOutcome = outcome
            )
        }
    }

    private fun handleBeastRiotOutcome(activeMission: ActiveMission, difficulty: MissionDifficulty): MissionResult {
        val (minRarity, maxRarity, countRange) = when (difficulty) {
            MissionDifficulty.YELLOW -> Triple(1, 1, 5..20)
            MissionDifficulty.MYSTERIOUS -> Triple(2, 3, 5..15)
            MissionDifficulty.EARTH -> Triple(3, 4, 1..10)
            MissionDifficulty.HEAVEN -> Triple(4, 6, 1..5)
        }

        val count = Random.nextInt(countRange.first, countRange.last + 1)
        val materials = mutableListOf<Material>()
        
        repeat(count) {
            val eligibleMaterials = BeastMaterialDatabase.getAllMaterials()
                .filter { it.rarity in minRarity..maxRarity }
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

        return MissionResult(
            spiritStones = activeMission.rewards.spiritStones,
            materials = materials,
            investigateOutcome = InvestigateOutcome.BEAST_RIOT
        )
    }

    private fun handleSectConflictOutcome(activeMission: ActiveMission, difficulty: MissionDifficulty): MissionResult {
        val (minRarity, maxRarity, countRange) = when (difficulty) {
            MissionDifficulty.YELLOW -> Triple(1, 2, 1..10)
            MissionDifficulty.MYSTERIOUS -> Triple(2, 3, 1..5)
            MissionDifficulty.EARTH -> Triple(3, 4, 1..3)
            MissionDifficulty.HEAVEN -> Triple(4, 6, 1..2)
        }

        val count = Random.nextInt(countRange.first, countRange.last + 1)
        val extraItems = generateItemsByRarity(minRarity, maxRarity, count)

        return MissionResult(
            spiritStones = activeMission.rewards.spiritStones,
            pills = extraItems.pills,
            equipment = extraItems.equipment,
            manuals = extraItems.manuals,
            investigateOutcome = InvestigateOutcome.SECT_CONFLICT
        )
    }

    private fun handleDestinedChildOutcome(activeMission: ActiveMission, difficulty: MissionDifficulty): MissionResult {
        return MissionResult(
            spiritStones = activeMission.rewards.spiritStones,
            investigateOutcome = InvestigateOutcome.DESTINED_CHILD
        )
    }

    private fun handleGuardCitySuccess(activeMission: ActiveMission): MissionResult {
        val rewards = activeMission.rewards
        val extraItems = generateExtraItems(rewards)

        return MissionResult(
            pills = extraItems.pills,
            equipment = extraItems.equipment,
            manuals = extraItems.manuals
        )
    }

    private fun generateMaterials(rewards: MissionRewardConfig): List<Material> {
        if (rewards.materialCountRange.first == 0) return emptyList()
        
        val count = Random.nextInt(rewards.materialCountRange.first, rewards.materialCountRange.last + 1)
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

    private fun generateHerbs(rewards: MissionRewardConfig): List<Herb> {
        if (rewards.herbCountRange.first == 0) return emptyList()
        
        val count = Random.nextInt(rewards.herbCountRange.first, rewards.herbCountRange.last + 1)
        val herbs = mutableListOf<Herb>()
        
        repeat(count) {
            val template = HerbDatabase.generateRandomHerb(rewards.herbMinRarity, rewards.herbMaxRarity)
            herbs.add(Herb(
                id = java.util.UUID.randomUUID().toString(),
                name = template.name,
                rarity = template.rarity,
                description = template.description,
                category = template.category
            ))
        }
        
        return herbs
    }

    private fun generateSeeds(rewards: MissionRewardConfig): List<Seed> {
        if (rewards.seedCountRange.first == 0) return emptyList()
        
        val count = Random.nextInt(rewards.seedCountRange.first, rewards.seedCountRange.last + 1)
        val seeds = mutableListOf<Seed>()
        
        repeat(count) {
            val template = HerbDatabase.generateRandomSeed(rewards.seedMinRarity, rewards.seedMaxRarity)
            seeds.add(Seed(
                id = java.util.UUID.randomUUID().toString(),
                name = template.name,
                rarity = template.rarity,
                description = template.description,
                growTime = template.growTime,
                yield = template.yield
            ))
        }
        
        return seeds
    }

    private data class ExtraItems(
        val pills: List<Pill> = emptyList(),
        val equipment: List<Equipment> = emptyList(),
        val manuals: List<Manual> = emptyList()
    )

    private fun generateExtraItems(rewards: MissionRewardConfig): ExtraItems {
        if (rewards.extraItemChance <= 0 || Random.nextDouble() >= rewards.extraItemChance) {
            return ExtraItems()
        }

        val count = Random.nextInt(rewards.extraItemCountRange.first, rewards.extraItemCountRange.last + 1)
        return generateItemsByRarity(rewards.extraItemMinRarity, rewards.extraItemMaxRarity, count)
    }

    private fun generateItemsByRarity(minRarity: Int, maxRarity: Int, count: Int): ExtraItems {
        val pills = mutableListOf<Pill>()
        val equipment = mutableListOf<Equipment>()
        val manuals = mutableListOf<Manual>()

        repeat(count) {
            when (RewardItemType.entries.random()) {
                RewardItemType.PILL -> {
                    val eligiblePills = ItemDatabase.allPills.values
                        .filter { it.rarity in minRarity..maxRarity }
                    if (eligiblePills.isNotEmpty()) {
                        pills.add(ItemDatabase.createPillFromTemplate(eligiblePills.random()))
                    }
                }
                RewardItemType.EQUIPMENT -> {
                    equipment.add(EquipmentDatabase.generateRandom(minRarity, maxRarity))
                }
                RewardItemType.MANUAL -> {
                    manuals.add(ManualDatabase.generateRandom(minRarity, maxRarity))
                }
            }
        }

        return ExtraItems(pills, equipment, manuals)
    }

    fun calculateExpandCost(currentSlots: Int): Int {
        return SLOT_EXPAND_COST * currentSlots
    }

    fun canExpandSlots(currentSlots: Int): Boolean {
        return currentSlots < MAX_SLOTS
    }

    fun getAvailableSlotsCount(activeMissions: Int, totalSlots: Int): Int {
        return totalSlots - activeMissions
    }

    fun shouldRefreshMissions(lastRefreshYear: Int, currentYear: Int): Boolean {
        return currentYear > lastRefreshYear
    }

    fun cleanExpiredMissions(
        missions: List<Mission>,
        currentYear: Int,
        currentMonth: Int
    ): List<Mission> {
        return missions.filter { mission ->
            val yearDiff = currentYear - mission.createdYear
            val monthDiff = currentMonth - mission.createdMonth
            val totalMonths = yearDiff * 12 + monthDiff
            totalMonths < 12
        }
    }
}
