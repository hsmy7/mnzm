package com.xianxia.sect.core.engine.domain.diplomacy

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.EquipmentNurtureSystem
import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.util.NameService
import com.xianxia.sect.core.util.SpiritRootGenerator
import com.xianxia.sect.core.util.PortraitPool
import kotlin.random.Random

object AISectDiscipleManager {

    /** 每月真实秒数 = 3 旬 × MS_PER_PHASE_1X / 1000 = 6.0s */
    private val SECONDS_PER_MONTH = com.xianxia.sect.core.engine.system.GameTimeClock.MS_PER_PHASE_1X * 3 / 1000.0

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

        val talentEffects = TalentDatabase.calculateTalentEffects(talents)
        val lifespanBonus = talentEffects["lifespan"] ?: 0.0
        val baseLifespan = GameConfig.Realm.get(9).maxAge
        val lifespan = (baseLifespan * (1.0 + lifespanBonus)).toInt().coerceAtLeast(1)

        return Disciple(
            id = java.util.UUID.randomUUID().toString(),
            name = nameResult.fullName,
            surname = nameResult.surname,
            gender = gender,
            portraitRes = PortraitPool.getRandomPortrait(gender),
            realm = 9,
            realmLayer = 1,
            cultivation = 0.0,
            spiritRootType = spiritRoot,
            age = Random.nextInt(16, 30),
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
                mining = Random.nextInt(1, 101),
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

    private fun generateSpiritRoot(): String = SpiritRootGenerator.generate()

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
                ManualProficiencySystem.MasteryLevel.NOVICE -> Random.nextDouble(0.0, 1000.0)
                ManualProficiencySystem.MasteryLevel.SMALL_SUCCESS -> Random.nextDouble(1000.0, 10000.0)
                ManualProficiencySystem.MasteryLevel.GREAT_SUCCESS -> Random.nextDouble(10000.0, 30000.0)
                ManualProficiencySystem.MasteryLevel.PERFECTION -> 30000.0
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
        val newDisciples = generateYearlyRecruits(sectName, existingDisciples)
        val allDisciples = existingDisciples + newDisciples
        return if (allDisciples.size > PlantSlotData.MAX_AI_DISCIPLES_PER_SECT) {
            allDisciples.sortedByDescending { it.combat.basePhysicalAttack + it.combat.baseMagicAttack + it.combat.baseHp }.take(PlantSlotData.MAX_AI_DISCIPLES_PER_SECT)
        } else {
            allDisciples
        }
    }

    /**
     * 仅生成年度新弟子列表（不合并现有弟子），供占领路由使用
     */
    fun generateYearlyRecruits(
        sectName: String,
        existingDisciples: List<Disciple>
    ): List<Disciple> {
        val newDisciples = mutableListOf<Disciple>()
        val usedNames = existingDisciples.map { it.name }.toMutableSet()
        repeat(Random.nextInt(0, 7)) {
            val disciple = generateQiRefiningDisciple(sectName, usedNames)
            newDisciples.add(disciple)
            usedNames.add(disciple.name)
        }
        return newDisciples
    }

    private fun generateQiRefiningDisciple(sectName: String, existingNames: Set<String>): Disciple {
        return generateRandomDisciple(sectName, 9, existingNames)
    }

    fun processMonthlyCultivation(
        disciples: List<Disciple>,
        batchMonths: Int = 1,
        manualProficienciesMap: Map<String, Map<String, ManualProficiencyData>> = emptyMap()
    ): List<Disciple> {
        if (batchMonths <= 0 || disciples.isEmpty()) return disciples

        return disciples.map { disciple ->
            if (!disciple.isAlive) return@map disciple

            var workingDisciple = disciple

            repeat(batchMonths) {
                // 每月重新计算修炼速度（突破后境界可能改变）
                val cultivationSpeed = DiscipleStatCalculator.calculateCultivationSpeed(
                    workingDisciple,
                    manuals = emptyMap(),
                    manualProficiencies = emptyMap(),
                    buildingBonus = 1.0,
                    additionalBonus = 0.0,
                    preachingElderBonus = 0.0,
                    preachingMastersBonus = 0.0,
                    cultivationSubsidyBonus = 0.0
                )
                val monthlyGain = cultivationSpeed * SECONDS_PER_MONTH

                var newCultivation = workingDisciple.cultivation + monthlyGain
                var newRealm = workingDisciple.realm
                var newRealmLayer = workingDisciple.realmLayer

                while (newCultivation >= workingDisciple.maxCultivation && newRealm > 0) {
                    val isMajorBreakthrough = newRealmLayer >= GameConfig.Realm.get(newRealm).maxLayers
                    val rootCount = workingDisciple.spiritRoot.types.size
                    val breakthroughChance = GameConfig.Realm.getBreakthroughChance(
                        newRealm, rootCount, newRealmLayer
                    )
                    if (Random.nextDouble() < breakthroughChance) {
                        newCultivation = 0.0

                        if (!isMajorBreakthrough) {
                            newRealmLayer++
                        } else {
                            newRealm--
                            newRealmLayer = 1
                        }
                    } else {
                        newCultivation = 0.0
                        break
                    }
                }

                workingDisciple = workingDisciple.copy(
                    cultivation = newCultivation,
                    realm = newRealm,
                    realmLayer = newRealmLayer,
                    lifespan = workingDisciple.lifespan
                )
            }

            workingDisciple
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
            fun forLevel(level: Int): SectLevelConfig {
                val maxRealm = SectLevel.maxRealmForLevel(level)
                return SectLevelConfig(
                    normalMin = 50, normalMax = 50,
                    normalMaxRealm = maxRealm,
                    eliteCount = 0, eliteRealm = maxRealm,
                    sectMaxRealm = maxRealm
                )
            }
        }
    }

    private fun generateRealmDistribution(total: Int, maxRealm: Int): Map<Int, Int> {
        val distribution = mutableMapOf<Int, Int>()

        val realmRange = (maxRealm + 1)..9
        if (realmRange.isEmpty()) return distribution

        val weights = realmRange.associateWith { realm ->
            when (realm) {
                9 -> 3
                8 -> 2
                7 -> 2
                else -> 1
            }
        }
        val totalWeight = weights.values.sum()

        var assigned = 0
        for (realm in realmRange) {
            val weight = weights[realm] ?: 1
            val count = (total * weight / totalWeight)
            distribution[realm] = count
            assigned += count
        }

        var remaining = total - assigned
        if (remaining > 0) {
            val sortedRealms = realmRange.sortedByDescending { weights[it] ?: 1 }
            for (realm in sortedRealms) {
                if (remaining <= 0) break
                distribution[realm] = (distribution[realm] ?: 0) + 1
                remaining--
            }
        }

        return distribution
    }

    /**
     * 旧存档兼容：将 AI 宗门弟子补充至目标数量。
     * 新增弟子境界在宗门等级允许范围内随机分配。
     *
     * @param sectName 宗门名称
     * @param existingDisciples 现有弟子列表
     * @param targetCount 目标弟子总数（如 50）
     * @param sectLevel 宗门等级（用于境界上限）
     * @return 补满后的弟子列表
     */
    fun fillDisciplesToTarget(
        sectName: String,
        existingDisciples: List<Disciple>,
        targetCount: Int,
        sectLevel: Int
    ): List<Disciple> {
        if (existingDisciples.size >= targetCount) return existingDisciples

        val maxRealm = SectLevel.maxRealmForLevel(sectLevel)
        val usedNames = existingDisciples.map { it.name }.toMutableSet()
        val newDisciples = mutableListOf<Disciple>()

        val fillCount = targetCount - existingDisciples.size
        val realmDistribution = generateRealmDistribution(fillCount, maxRealm)

        realmDistribution.forEach { (realm, count) ->
            repeat(count) {
                val disciple = generateRandomDisciple(sectName, maxRealm, usedNames)
                val adjusted = adjustDiscipleRealm(disciple, realm)
                newDisciples.add(adjusted)
                usedNames.add(adjusted.name)
            }
        }

        return existingDisciples + newDisciples
    }

    private fun adjustDiscipleRealm(disciple: Disciple, targetRealm: Int): Disciple {
        if (targetRealm == 9) return disciple

        val baseLifespan = GameConfig.Realm.get(targetRealm).maxAge
        val talentEffects = TalentDatabase.calculateTalentEffects(disciple.talentIds)
        val lifespanBonus = talentEffects["lifespan"] ?: 0.0
        val newLifespan = (baseLifespan * (1.0 + lifespanBonus)).toInt().coerceAtLeast(1)
        val maxLayer = GameConfig.Realm.get(targetRealm).maxLayers

        return disciple.copy(
            realm = targetRealm,
            realmLayer = Random.nextInt(1, maxLayer + 1),
            cultivation = Random.nextDouble() * 0.8 * GameConfig.Realm.get(targetRealm).cultivationBase,
            lifespan = newLifespan
        )
    }
}
