package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.data.EquipmentDatabase
import com.xianxia.sect.core.data.ManualDatabase
import com.xianxia.sect.core.data.TalentDatabase
import com.xianxia.sect.core.engine.DiscipleStatCalculator
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.util.NameService
import kotlin.random.Random

object AISectDiscipleManager {
    
    private val SECONDS_PER_MONTH = GameConfig.Time.SECONDS_PER_REAL_MONTH
    
    private val spiritRootTypes = listOf("metal", "wood", "water", "fire", "earth")
    
    fun generateRandomDisciple(sectName: String, maxRealm: Int = 9): Disciple {
        val gender = if (Random.nextBoolean()) "male" else "female"
        val nameResult = NameService.generateName(gender, NameService.NameStyle.XIANXIA)
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
        val manuals = generateManuals(maxRealm)
        val equipments = generateEquipments(maxRealm)
        
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
            manualIds = manuals.map { it.first },
            manualMasteries = manuals.associate { it.first to it.second },
            combat = CombatAttributes(
                hpVariance = hpVariance,
                mpVariance = mpVariance,
                physicalAttackVariance = physicalAttackVariance,
                magicAttackVariance = magicAttackVariance,
                physicalDefenseVariance = physicalDefenseVariance,
                magicDefenseVariance = magicDefenseVariance,
                speedVariance = speedVariance
            ),
            equipment = EquipmentSet(
                weaponId = equipments.firstOrNull { it.second == EquipmentSlot.WEAPON }?.first ?: "",
                armorId = equipments.firstOrNull { it.second == EquipmentSlot.ARMOR }?.first ?: "",
                bootsId = equipments.firstOrNull { it.second == EquipmentSlot.BOOTS }?.first ?: "",
                accessoryId = equipments.firstOrNull { it.second == EquipmentSlot.ACCESSORY }?.first ?: ""
            ),
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
    
    private fun generateManuals(maxRealm: Int): List<Pair<String, Int>> {
        val count = Random.nextInt(1, 6)
        val maxRarity = getMaxRarityByRealm(maxRealm)
        
        val attackManuals = ManualDatabase.getByType(ManualType.ATTACK).filter { it.rarity <= maxRarity }
        val defenseManuals = ManualDatabase.getByType(ManualType.DEFENSE).filter { it.rarity <= maxRarity }
        val mindManuals = ManualDatabase.getByType(ManualType.MIND).filter { it.rarity <= maxRarity }
        
        val nonMindManuals = (attackManuals + defenseManuals).shuffled()
        val selectedMind = if (mindManuals.isNotEmpty() && Random.nextBoolean()) {
            listOf(mindManuals.random())
        } else emptyList()
        
        val remainingCount = (count - selectedMind.size).coerceAtLeast(0)
        val selectedNonMind = nonMindManuals.take(remainingCount)
        val selected = selectedMind + selectedNonMind
        
        if (selected.isEmpty()) return emptyList()
        
        return selected.map { manual ->
            val mastery = Random.nextInt(0, 101)
            Pair(manual.id, mastery)
        }
    }
    
    private fun generateEquipments(maxRealm: Int): List<Pair<String, EquipmentSlot>> {
        val count = Random.nextInt(1, 5)
        val maxRarity = getMaxRarityByRealm(maxRealm)
        
        val slots = EquipmentSlot.values().toList().shuffled().take(count)
        
        return slots.mapNotNull { slot ->
            val allSlotTemplates = EquipmentDatabase.getBySlot(slot)
            if (allSlotTemplates.isEmpty()) return@mapNotNull null
            
            val templates = allSlotTemplates.filter { it.rarity <= maxRarity }
            val template = if (templates.isNotEmpty()) templates.random() else allSlotTemplates.random()
            Pair(template.id, slot)
        }
    }
    
    fun getMaxRarityByRealm(realm: Int): Int = GameConfig.Realm.getMaxRarity(realm)
    
    fun recruitDisciples(
        sectName: String,
        maxRealm: Int,
        existingDisciples: List<Disciple>
    ): List<Disciple> {
        val recruitCount = Random.nextInt(1, 11)
        val newDisciples = mutableListOf<Disciple>()
        
        repeat(recruitCount) {
            val disciple = generateRandomDisciple(sectName, maxRealm)
            newDisciples.add(disciple)
        }
        
        val allDisciples = existingDisciples + newDisciples
        return if (allDisciples.size > PlantSlotData.MAX_AI_DISCIPLES_PER_SECT) {
            allDisciples.sortedByDescending { it.combat.basePhysicalAttack + it.combat.baseMagicAttack + it.combat.baseHp }.take(PlantSlotData.MAX_AI_DISCIPLES_PER_SECT)
        } else {
            allDisciples
        }
    }
    
    fun processMonthlyCultivation(disciples: List<Disciple>, manualProficienciesMap: Map<String, Map<String, ManualProficiencyData>> = emptyMap()): List<Disciple> {
        return disciples.map { disciple ->
            if (!disciple.isAlive) return@map disciple
            
            val discipleProficiencies = manualProficienciesMap[disciple.id] ?: emptyMap()
            val cultivationSpeed = disciple.calculateCultivationSpeed(manualProficiencies = discipleProficiencies)
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
    
    fun processManualMasteryGrowth(disciples: List<Disciple>): List<Disciple> {
        return disciples.map { disciple ->
            if (!disciple.isAlive) return@map disciple
            
            val updatedMasteries = disciple.manualMasteries.toMutableMap()
            
            disciple.manualIds.forEach { manualId ->
                val manual = ManualDatabase.getById(manualId) ?: return@forEach
                val currentMastery = updatedMasteries[manualId] ?: 0
                
                val gain = ManualProficiencySystem.calculateProficiencyGain(
                    baseGain = ManualProficiencySystem.BASE_PROFICIENCY_GAIN,
                    discipleRealm = disciple.realm,
                    manualRarity = manual.rarity
                )
                
                val monthlyGain = gain * SECONDS_PER_MONTH
                val maxProficiency = ManualProficiencySystem.getMaxProficiency(manual.rarity)
                val newMastery = (currentMastery + monthlyGain).toInt().coerceAtMost(maxProficiency.toInt())
                updatedMasteries[manualId] = newMastery
            }
            
            disciple.copy(manualMasteries = updatedMasteries)
        }
    }
    
    fun processEquipmentNurture(disciples: List<Disciple>): List<Disciple> {
        return disciples.map { disciple ->
            if (!disciple.isAlive) return@map disciple
            
            var updatedDisciple = disciple
            
            updatedDisciple = updateEquipmentNurture(
                disciple = updatedDisciple,
                equipmentId = disciple.equipment.weaponId,
                currentNurture = disciple.equipment.weaponNurture,
                nurtureSetter = { d, n -> d.copyWith(weaponNurture = n) }
            )

            updatedDisciple = updateEquipmentNurture(
                disciple = updatedDisciple,
                equipmentId = disciple.equipment.armorId,
                currentNurture = disciple.equipment.armorNurture,
                nurtureSetter = { d, n -> d.copyWith(armorNurture = n) }
            )

            updatedDisciple = updateEquipmentNurture(
                disciple = updatedDisciple,
                equipmentId = disciple.equipment.bootsId,
                currentNurture = disciple.equipment.bootsNurture,
                nurtureSetter = { d, n -> d.copyWith(bootsNurture = n) }
            )

            updatedDisciple = updateEquipmentNurture(
                disciple = updatedDisciple,
                equipmentId = disciple.equipment.accessoryId,
                currentNurture = disciple.equipment.accessoryNurture,
                nurtureSetter = { d, n -> d.copyWith(accessoryNurture = n) }
            )
            
            updatedDisciple
        }
    }
    
    private fun updateEquipmentNurture(
        disciple: Disciple,
        equipmentId: String,
        currentNurture: EquipmentNurtureData,
        nurtureSetter: (Disciple, EquipmentNurtureData) -> Disciple
    ): Disciple {
        if (equipmentId.isEmpty()) return disciple

        val template = EquipmentDatabase.getById(equipmentId) ?: return disciple
        val rarity = template.rarity

        val nurture = if (currentNurture.equipmentId != equipmentId) {
            EquipmentNurtureData(
                equipmentId = equipmentId,
                rarity = rarity,
                nurtureLevel = 0,
                nurtureProgress = 0.0
            )
        } else {
            currentNurture
        }
        
        val maxLevel = EquipmentNurtureSystem.getMaxNurtureLevel(rarity)
        if (nurture.nurtureLevel >= maxLevel) return disciple
        
        val autoExpGain = EquipmentNurtureSystem.calculateAutoExpGain(rarity)
        val monthlyGain = autoExpGain * SECONDS_PER_MONTH
        val expRequired = EquipmentNurtureSystem.getExpRequiredForLevelUp(nurture.nurtureLevel, rarity)
        
        val newProgress = nurture.nurtureProgress + monthlyGain
        
        return if (newProgress >= expRequired) {
            val newLevel = (nurture.nurtureLevel + 1).coerceAtMost(maxLevel)
            val remainingExp = newProgress - expRequired
            nurtureSetter(disciple, nurture.copy(
                nurtureLevel = newLevel,
                nurtureProgress = if (newLevel >= maxLevel) 0.0 else remainingExp
            ))
        } else {
            nurtureSetter(disciple, nurture.copy(nurtureProgress = newProgress))
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
        val totalDisciples = when (sectLevel) {
            0 -> Random.nextInt(15, 26)
            1 -> Random.nextInt(25, 41)
            2 -> Random.nextInt(40, 61)
            3 -> Random.nextInt(60, 101)
            else -> Random.nextInt(15, 26)
        }
        
        val disciples = mutableListOf<Disciple>()
        
        val maxRealm = when (sectLevel) {
            0 -> 6
            1 -> 4
            2 -> 3
            3 -> 1
            else -> 6
        }
        
        val realmDistribution = generateRealmDistribution(totalDisciples, maxRealm)
        
        realmDistribution.forEach { (realm, count) ->
            repeat(count) {
                val disciple = generateRandomDisciple(sectName, maxRealm)
                val adjustedDisciple = adjustDiscipleRealm(disciple, realm)
                disciples.add(adjustedDisciple)
            }
        }
        
        val trimmed = if (disciples.size > PlantSlotData.MAX_AI_DISCIPLES_PER_SECT) {
            disciples.sortedByDescending { it.combat.basePhysicalAttack + it.combat.baseMagicAttack + it.combat.baseHp }.take(PlantSlotData.MAX_AI_DISCIPLES_PER_SECT)
        } else {
            disciples
        }
        
        return Pair(trimmed, maxRealm)
    }
    
    private fun generateRealmDistribution(total: Int, maxRealm: Int): Map<Int, Int> {
        val distribution = mutableMapOf<Int, Int>()
        var remaining = total
        
        val topCount = if (maxRealm == 1) Random.nextInt(1, 4) else Random.nextInt(1, 6)
        distribution[maxRealm] = topCount.coerceAtMost(remaining)
        remaining -= topCount
        
        for (realm in (maxRealm + 1)..9) {
            if (remaining <= 0) break
            val count = Random.nextInt(5, 21).coerceAtMost(remaining)
            distribution[realm] = count
            remaining -= count
        }
        
        return distribution
    }
    
    private fun adjustDiscipleRealm(disciple: Disciple, targetRealm: Int): Disciple {
        if (targetRealm == 9) return disciple
        
        val newLifespan = GameConfig.Realm.get(targetRealm).maxAge
        var newDisciple = disciple.copy(
            realm = targetRealm,
            realmLayer = Random.nextInt(1, 10),
            cultivation = Random.nextDouble() * 0.8 * GameConfig.Realm.get(targetRealm).cultivationBase,
            lifespan = newLifespan
        )
        
        val maxRarity = getMaxRarityByRealm(targetRealm)
        val manuals = generateManuals(targetRealm)
        val equipments = generateEquipments(targetRealm)
        
        newDisciple = newDisciple.copyWith(
            manualIds = manuals.map { it.first },
            manualMasteries = manuals.associate { it.first to it.second },
            weaponId = equipments.firstOrNull { it.second == EquipmentSlot.WEAPON }?.first ?: "",
            armorId = equipments.firstOrNull { it.second == EquipmentSlot.ARMOR }?.first ?: "",
            bootsId = equipments.firstOrNull { it.second == EquipmentSlot.BOOTS }?.first ?: "",
            accessoryId = equipments.firstOrNull { it.second == EquipmentSlot.ACCESSORY }?.first ?: "",
            weaponNurture = EquipmentNurtureData("", 0),
            armorNurture = EquipmentNurtureData("", 0),
            bootsNurture = EquipmentNurtureData("", 0),
            accessoryNurture = EquipmentNurtureData("", 0)
        )
        
        return newDisciple
    }
}
