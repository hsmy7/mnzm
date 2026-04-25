package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.data.EquipmentDatabase
import com.xianxia.sect.core.data.ManualDatabase
import com.xianxia.sect.core.data.TalentDatabase
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.util.NameService
import kotlin.random.Random

object AISectDiscipleManager {

    private val SECONDS_PER_MONTH = GameConfig.Time.SECONDS_PER_REAL_MONTH

    private val spiritRootTypes = listOf("metal", "wood", "water", "fire", "earth")

    data class BattleItems(
        val manuals: List<Pair<String, Int>>,
        val equipments: List<Pair<String, EquipmentSlot>>,
        val weaponNurture: EquipmentNurtureData,
        val armorNurture: EquipmentNurtureData,
        val bootsNurture: EquipmentNurtureData,
        val accessoryNurture: EquipmentNurtureData
    )

    fun generateRandomDisciple(sectName: String, maxRealm: Int = 9, existingNames: Set<String> = emptySet()): Disciple {
        val gender = if (Random.nextBoolean()) "male" else "female"
        val nameResult = NameService.generateName(gender, NameService.NameStyle.XIANXIA, existingNames)
        val spiritRoot = generateSpiritRoot()
        val spiritRootCount = spiritRoot.split(",").size
        val comprehension = when (spiritRootCount) {
            1 -> Random.nextInt(80, 101)
            2 -> Random.nextInt(60, 101)
            3 -> Random.nextInt(40, 101)
            4 -> Random.nextInt(20, 101)
            else -> Random.nextInt(1, 101)
        }
        val hpVariance = Random.nextInt(-50, 51)
        val mpVariance = Random.nextInt(-50, 51)
        val physicalAttackVariance = Random.nextInt(-50, 51)
        val magicAttackVariance = Random.nextInt(-50, 51)
        val physicalDefenseVariance = Random.nextInt(-50, 51)
        val magicDefenseVariance = Random.nextInt(-50, 51)
        val speedVariance = Random.nextInt(-50, 51)
        val talents = TalentDatabase.generateTalentsForDisciple().map { it.id }

        val lifespanBonus = talents.sumOf {
            TalentDatabase.getById(it)?.effects?.get("lifespan") ?: 0.0
        }
        val baseLifespan = GameConfig.Realm.get(9).maxAge
        val lifespan = (baseLifespan * (1 + lifespanBonus)).toInt().coerceAtLeast(1)

        return Disciple(
            id = java.util.UUID.randomUUID().toString(),
            name = nameResult.fullName,
            surname = nameResult.surname,
            realm = 9,
            realmLayer = 1,
            cultivation = 0.0,
            spiritRootType = spiritRoot,
            age = Random.nextInt(16, 26),
            lifespan = lifespan,
            isAlive = true,
            discipleType = "outer",
            talentIds = talents,
            manualIds = emptyList(),
            manualMasteries = emptyMap(),
            combat = CombatAttributes(
                hpVariance = hpVariance,
                mpVariance = mpVariance,
                physicalAttackVariance = physicalAttackVariance,
                magicAttackVariance = magicAttackVariance,
                physicalDefenseVariance = physicalDefenseVariance,
                magicDefenseVariance = magicDefenseVariance,
                speedVariance = speedVariance
            ),
            equipment = EquipmentSet(),
            skills = SkillStats(
                intelligence = Random.nextInt(1, 101),
                charm = Random.nextInt(1, 101),
                loyalty = Random.nextInt(1, 101),
                comprehension = comprehension,
                morality = Random.nextInt(1, 101),
                artifactRefining = Random.nextInt(1, 101),
                pillRefining = Random.nextInt(1, 101),
                spiritPlanting = Random.nextInt(1, 101),
                teaching = Random.nextInt(1, 101)
            )
        ).apply {
            val baseStats = Disciple.calculateBaseStatsWithVariance(
                hpVariance, mpVariance, physicalAttackVariance, magicAttackVariance,
                physicalDefenseVariance, magicDefenseVariance, speedVariance
            )
            combat.baseHp = baseStats.baseHp
            combat.baseMp = baseStats.baseMp
            combat.basePhysicalAttack = baseStats.basePhysicalAttack
            combat.baseMagicAttack = baseStats.baseMagicAttack
            combat.basePhysicalDefense = baseStats.basePhysicalDefense
            combat.baseMagicDefense = baseStats.baseMagicDefense
            combat.baseSpeed = baseStats.baseSpeed
        }
    }

    private fun generateSpiritRoot(): String {
        val count = when (Random.nextInt(100)) {
            in 0..4 -> 1
            in 5..24 -> 2
            in 25..54 -> 3
            in 55..84 -> 4
            else -> 5
        }

        val shuffled = spiritRootTypes.shuffled()
        return shuffled.take(count).joinToString(",")
    }

    fun getMaxRarityByRealm(realm: Int): Int = GameConfig.Realm.getMaxRarity(realm)

    fun getMinRarityByRealm(realm: Int): Int = when (realm) {
        9, 8 -> 1
        7 -> 1
        6 -> 1
        5 -> 2
        4 -> 2
        3 -> 3
        2 -> 3
        1 -> 4
        0 -> 5
        else -> 1
    }

    fun getMaxManualsByRealm(realm: Int): Int = when {
        realm >= 8 -> 3
        realm >= 6 -> 4
        realm >= 4 -> 5
        else -> 6
    }

    fun generateBattleItems(disciple: Disciple): BattleItems {
        val realm = disciple.realm
        val maxRarity = getMaxRarityByRealm(realm)
        val minRarity = getMinRarityByRealm(realm)
        val maxManuals = getMaxManualsByRealm(realm)

        val manualCount = Random.nextInt(1, maxManuals + 1)
        val manuals = generateBattleManuals(minRarity, maxRarity, manualCount)

        val equipmentSlots = EquipmentSlot.values().toList().shuffled()
        val equipmentCount = Random.nextInt(1, 5)
        val equipments = generateBattleEquipments(minRarity, maxRarity, equipmentSlots.take(equipmentCount))

        val weaponId = equipments.firstOrNull { it.second == EquipmentSlot.WEAPON }?.first ?: ""
        val armorId = equipments.firstOrNull { it.second == EquipmentSlot.ARMOR }?.first ?: ""
        val bootsId = equipments.firstOrNull { it.second == EquipmentSlot.BOOTS }?.first ?: ""
        val accessoryId = equipments.firstOrNull { it.second == EquipmentSlot.ACCESSORY }?.first ?: ""

        val weaponNurture = if (weaponId.isNotEmpty()) generateRandomNurture(weaponId) else EquipmentNurtureData("", 0)
        val armorNurture = if (armorId.isNotEmpty()) generateRandomNurture(armorId) else EquipmentNurtureData("", 0)
        val bootsNurture = if (bootsId.isNotEmpty()) generateRandomNurture(bootsId) else EquipmentNurtureData("", 0)
        val accessoryNurture = if (accessoryId.isNotEmpty()) generateRandomNurture(accessoryId) else EquipmentNurtureData("", 0)

        return BattleItems(
            manuals = manuals,
            equipments = equipments,
            weaponNurture = weaponNurture,
            armorNurture = armorNurture,
            bootsNurture = bootsNurture,
            accessoryNurture = accessoryNurture
        )
    }

    private fun generateBattleManuals(minRarity: Int, maxRarity: Int, count: Int): List<Pair<String, Int>> {
        val attackManuals = ManualDatabase.getByType(ManualType.ATTACK)
            .filter { it.rarity in minRarity..maxRarity }
        val defenseManuals = ManualDatabase.getByType(ManualType.DEFENSE)
            .filter { it.rarity in minRarity..maxRarity }
        val mindManuals = ManualDatabase.getByType(ManualType.MIND)
            .filter { it.rarity in minRarity..maxRarity }

        val nonMindManuals = (attackManuals + defenseManuals).shuffled()
        val selectedMind = if (mindManuals.isNotEmpty() && Random.nextBoolean()) {
            listOf(mindManuals.random())
        } else emptyList()

        val remainingCount = (count - selectedMind.size).coerceAtLeast(0)
        val selectedNonMind = nonMindManuals.take(remainingCount)
        val selected = selectedMind + selectedNonMind

        if (selected.isEmpty()) return emptyList()

        return selected.map { manual ->
            val maxMasteryLevel = ManualProficiencySystem.MasteryLevel.values().last().level
            val randomMasteryLevel = Random.nextInt(0, maxMasteryLevel + 1)
            val masteryLevel = ManualProficiencySystem.MasteryLevel.fromLevel(randomMasteryLevel)
            val proficiency = when (masteryLevel) {
                ManualProficiencySystem.MasteryLevel.NOVICE -> Random.nextDouble(0.0, 100.0)
                ManualProficiencySystem.MasteryLevel.SMALL_SUCCESS -> Random.nextDouble(100.0, 200.0)
                ManualProficiencySystem.MasteryLevel.GREAT_SUCCESS -> Random.nextDouble(200.0, 300.0)
                ManualProficiencySystem.MasteryLevel.PERFECTION -> 300.0 + Random.nextDouble(0.0, 100.0)
            }
            Pair(manual.id, proficiency.toInt())
        }
    }

    private fun generateBattleEquipments(minRarity: Int, maxRarity: Int, slots: List<EquipmentSlot>): List<Pair<String, EquipmentSlot>> {
        return slots.mapNotNull { slot ->
            val allSlotTemplates = EquipmentDatabase.getBySlot(slot)
            if (allSlotTemplates.isEmpty()) return@mapNotNull null

            val templates = allSlotTemplates.filter { it.rarity in minRarity..maxRarity }
            val template = if (templates.isNotEmpty()) templates.random() else allSlotTemplates.random()
            Pair(template.id, slot)
        }
    }

    private fun generateRandomNurture(equipmentId: String): EquipmentNurtureData {
        val template = EquipmentDatabase.getById(equipmentId) ?: return EquipmentNurtureData("", 0)
        val maxLevel = EquipmentNurtureSystem.getMaxNurtureLevel(template.rarity)
        val nurtureLevel = Random.nextInt(0, maxLevel + 1)
        return EquipmentNurtureData(
            equipmentId = equipmentId,
            rarity = template.rarity,
            nurtureLevel = nurtureLevel,
            nurtureProgress = if (nurtureLevel >= maxLevel) 0.0 else Random.nextDouble(0.0, EquipmentNurtureSystem.getExpRequiredForLevelUp(nurtureLevel, template.rarity))
        )
    }

    fun recruitYearlyDisciples(
        sectName: String,
        existingDisciples: List<Disciple>
    ): List<Disciple> {
        val newDisciples = mutableListOf<Disciple>()
        val usedNames = existingDisciples.map { it.name }.toMutableSet()

        repeat(5) {
            val disciple = generateQiRefiningDisciple(sectName, usedNames)
            newDisciples.add(disciple)
            usedNames.add(disciple.name)
        }

        val allDisciples = existingDisciples + newDisciples
        return if (allDisciples.size > PlantSlotData.MAX_AI_DISCIPLES_PER_SECT) {
            allDisciples.sortedByDescending { it.combat.basePhysicalAttack + it.combat.baseMagicAttack + it.combat.baseHp }.take(PlantSlotData.MAX_AI_DISCIPLES_PER_SECT)
        } else {
            allDisciples
        }
    }

    private fun generateQiRefiningDisciple(sectName: String, existingNames: Set<String>): Disciple {
        return generateRandomDisciple(sectName, 9, existingNames)
    }

    fun processMonthlyCultivation(disciples: List<Disciple>, manualProficienciesMap: Map<String, Map<String, ManualProficiencyData>> = emptyMap()): List<Disciple> {
        return disciples.map { disciple ->
            if (!disciple.isAlive) return@map disciple

            val cultivationSpeed = DiscipleStatCalculator.calculateCultivationSpeed(
                disciple,
                manuals = emptyMap(),
                manualProficiencies = emptyMap(),
                buildingBonus = 1.0,
                additionalBonus = 0.0,
                preachingElderBonus = 0.0,
                preachingMastersBonus = 0.0,
                cultivationSubsidyBonus = 0.0
            )
            val monthlyGain = cultivationSpeed * SECONDS_PER_MONTH

            var newCultivation = disciple.cultivation + monthlyGain
            var newRealm = disciple.realm
            var newRealmLayer = disciple.realmLayer
            var newBreakthroughFailCount = disciple.combat.breakthroughFailCount

            while (newCultivation >= disciple.maxCultivation && newRealm > 0) {
                val isMajorBreakthrough = newRealmLayer >= GameConfig.Realm.get(newRealm).maxLayers
                if (isMajorBreakthrough && !DiscipleStatCalculator.meetsSoulPowerRequirement(newRealm, newRealmLayer, disciple.equipment.soulPower)) {
                    break
                }

                val breakthroughChance = disciple.getBreakthroughChance()
                if (Random.nextDouble() < breakthroughChance) {
                    newCultivation = 0.0
                    newBreakthroughFailCount = 0

                    if (newRealmLayer < 9) {
                        newRealmLayer++
                    } else {
                        newRealm--
                        newRealmLayer = 1
                    }
                } else {
                    newCultivation = 0.0
                    newBreakthroughFailCount++
                    break
                }
            }

            val newLifespan = if (newRealm != disciple.realm) {
                GameConfig.Realm.get(newRealm).maxAge
            } else {
                disciple.lifespan
            }

            disciple.copyWith(
                cultivation = newCultivation,
                realm = newRealm,
                realmLayer = newRealmLayer,
                lifespan = newLifespan,
                breakthroughFailCount = newBreakthroughFailCount
            )
        }
    }

    fun processAging(disciples: List<Disciple>): List<Disciple> {
        return disciples.map { disciple ->
            val newAge = disciple.age + 1
            val isAlive = newAge <= disciple.lifespan

            disciple.copy(
                age = newAge,
                isAlive = isAlive
            )
        }.filter { it.isAlive }
    }

    fun initializeSectDisciples(sectName: String, sectLevel: Int): Pair<List<Disciple>, Int> {
        val config = SectLevelConfig.forLevel(sectLevel)

        val disciples = mutableListOf<Disciple>()
        val usedNames = mutableSetOf<String>()

        val normalCount = Random.nextInt(config.normalMin, config.normalMax + 1)
        val realmDistribution = generateRealmDistribution(normalCount, config.normalMaxRealm)

        realmDistribution.forEach { (realm, count) ->
            repeat(count) {
                val disciple = generateRandomDisciple(sectName, config.normalMaxRealm, usedNames)
                val adjustedDisciple = adjustDiscipleRealm(disciple, realm)
                disciples.add(adjustedDisciple)
                usedNames.add(adjustedDisciple.name)
            }
        }

        repeat(config.eliteCount) {
            val disciple = generateRandomDisciple(sectName, config.eliteRealm, usedNames)
            val adjustedDisciple = adjustDiscipleRealm(disciple, config.eliteRealm)
            disciples.add(adjustedDisciple)
            usedNames.add(adjustedDisciple.name)
        }

        val trimmed = if (disciples.size > PlantSlotData.MAX_AI_DISCIPLES_PER_SECT) {
            disciples.sortedByDescending { it.combat.basePhysicalAttack + it.combat.baseMagicAttack + it.combat.baseHp }.take(PlantSlotData.MAX_AI_DISCIPLES_PER_SECT)
        } else {
            disciples
        }

        return Pair(trimmed, config.sectMaxRealm)
    }

    private data class SectLevelConfig(
        val normalMin: Int,
        val normalMax: Int,
        val normalMaxRealm: Int,
        val eliteCount: Int,
        val eliteRealm: Int,
        val sectMaxRealm: Int
    ) {
        companion object {
            fun forLevel(level: Int): SectLevelConfig = when (level) {
                0 -> SectLevelConfig(
                    normalMin = 20, normalMax = 60,
                    normalMaxRealm = 6,
                    eliteCount = 5, eliteRealm = 5,
                    sectMaxRealm = 5
                )
                1 -> SectLevelConfig(
                    normalMin = 40, normalMax = 80,
                    normalMaxRealm = 5,
                    eliteCount = 5, eliteRealm = 3,
                    sectMaxRealm = 3
                )
                2 -> SectLevelConfig(
                    normalMin = 40, normalMax = 120,
                    normalMaxRealm = 4,
                    eliteCount = 5, eliteRealm = 2,
                    sectMaxRealm = 2
                )
                3 -> SectLevelConfig(
                    normalMin = 50, normalMax = 120,
                    normalMaxRealm = 3,
                    eliteCount = 5, eliteRealm = 1,
                    sectMaxRealm = 1
                )
                else -> SectLevelConfig(
                    normalMin = 20, normalMax = 60,
                    normalMaxRealm = 6,
                    eliteCount = 5, eliteRealm = 5,
                    sectMaxRealm = 5
                )
            }
        }
    }

    private fun generateRealmDistribution(total: Int, maxRealm: Int): Map<Int, Int> {
        val distribution = mutableMapOf<Int, Int>()
        var remaining = total

        val realmRange = (maxRealm + 1)..9
        if (realmRange.isEmpty()) return distribution

        val realmCount = realmRange.count()
        val baseCount = total / realmCount
        var extra = total % realmCount

        for (realm in realmRange) {
            if (remaining <= 0) break
            val weight = when (realm) {
                9 -> 3
                8 -> 2
                7 -> 2
                else -> 1
            }
            val count = if (extra > 0) {
                extra--
                baseCount + weight
            } else {
                baseCount
            }.coerceAtMost(remaining)
            distribution[realm] = count
            remaining -= count
        }

        if (remaining > 0) {
            for (realm in realmRange.reversed()) {
                if (remaining <= 0) break
                distribution[realm] = (distribution[realm] ?: 0) + 1
                remaining--
            }
        }

        return distribution
    }

    private fun adjustDiscipleRealm(disciple: Disciple, targetRealm: Int): Disciple {
        if (targetRealm == 9) return disciple

        val newLifespan = GameConfig.Realm.get(targetRealm).maxAge
        val maxLayer = GameConfig.Realm.get(targetRealm).maxLayers

        return disciple.copy(
            realm = targetRealm,
            realmLayer = Random.nextInt(1, maxLayer + 1),
            cultivation = Random.nextDouble() * 0.8 * GameConfig.Realm.get(targetRealm).cultivationBase,
            lifespan = newLifespan
        )
    }
}
