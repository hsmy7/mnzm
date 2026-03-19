package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.data.EquipmentDatabase
import com.xianxia.sect.core.data.ManualDatabase
import com.xianxia.sect.core.data.TalentDatabase
import com.xianxia.sect.core.model.*
import kotlin.random.Random

object AISectDiscipleManager {
    
    private val SECONDS_PER_MONTH = GameConfig.Time.SECONDS_PER_REAL_MONTH
    
    private val spiritRootTypes = listOf("metal", "wood", "water", "fire", "earth")
    
    private val maleNames = listOf(
        "云", "风", "雷", "电", "剑", "刀", "枪", "棍", "拳", "掌",
        "明", "华", "天", "地", "玄", "黄", "宇", "宙", "洪", "荒",
        "龙", "虎", "鹤", "鹰", "豹", "狼", "熊", "狮", "象", "鲸"
    )
    
    private val femaleNames = listOf(
        "月", "雪", "花", "梅", "兰", "竹", "菊", "莲", "芸", "芳",
        "玉", "珠", "翠", "霞", "虹", "露", "霜", "雨", "云", "雾",
        "凤", "鸾", "燕", "莺", "鹃", "蝶", "蜂", "鹤", "鹭", "鸥"
    )
    
    private val surnames = listOf(
        "李", "王", "张", "刘", "陈", "杨", "赵", "黄", "周", "吴",
        "徐", "孙", "胡", "朱", "高", "林", "何", "郭", "马", "罗",
        "梁", "宋", "郑", "谢", "韩", "唐", "冯", "于", "董", "萧"
    )
    
    fun generateRandomDisciple(sectName: String, maxRealm: Int = 9): Disciple {
        val gender = if (Random.nextBoolean()) "male" else "female"
        val name = generateName(gender)
        val spiritRoot = generateSpiritRoot()
        val comprehension = Random.nextInt(30, 81)
        val combatStatsVariance = Random.nextInt(-30, 31)
        val varianceMultiplier = 1.0 + combatStatsVariance / 100.0
        val talents = generateTalents()
        val manuals = generateManuals(maxRealm)
        val equipments = generateEquipments(maxRealm)
        
        val lifespanBonus = talents.sumOf { 
            TalentDatabase.getById(it)?.effects?.get("lifespan") ?: 0.0 
        }
        val baseLifespan = GameConfig.Realm.get(9).maxAge
        val lifespan = (baseLifespan * (1 + lifespanBonus)).toInt().coerceAtLeast(1)
        
        return Disciple(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            realm = 9,
            realmLayer = 1,
            cultivation = 0.0,
            spiritRootType = spiritRoot,
            age = Random.nextInt(16, 26),
            lifespan = lifespan,
            isAlive = true,
            combatStatsVariance = combatStatsVariance,
            talentIds = talents,
            manualIds = manuals.map { it.first },
            manualMasteries = manuals.associate { it.first to it.second },
            weaponId = equipments.firstOrNull { it.second == EquipmentSlot.WEAPON }?.first,
            armorId = equipments.firstOrNull { it.second == EquipmentSlot.ARMOR }?.first,
            bootsId = equipments.firstOrNull { it.second == EquipmentSlot.BOOTS }?.first,
            accessoryId = equipments.firstOrNull { it.second == EquipmentSlot.ACCESSORY }?.first,
            comprehension = comprehension,
            baseHp = (100 * varianceMultiplier).toInt(),
            baseMp = (50 * varianceMultiplier).toInt(),
            basePhysicalAttack = (10 * varianceMultiplier).toInt(),
            baseMagicAttack = (5 * varianceMultiplier).toInt(),
            basePhysicalDefense = (5 * varianceMultiplier).toInt(),
            baseMagicDefense = (3 * varianceMultiplier).toInt(),
            baseSpeed = (10 * varianceMultiplier).toInt()
        )
    }
    
    private fun generateName(gender: String): String {
        val surname = surnames.random()
        val namePool = if (gender == "male") maleNames else femaleNames
        val firstName = namePool.random()
        val hasSecondChar = Random.nextBoolean()
        return if (hasSecondChar) {
            "$surname$firstName${namePool.random()}"
        } else {
            "$surname$firstName"
        }
    }
    
    private fun generateSpiritRoot(): String {
        val count = when (Random.nextDouble()) {
            in 0.0..0.05 -> 1
            in 0.05..0.20 -> 2
            in 0.20..0.45 -> 3
            in 0.45..0.75 -> 4
            else -> 5
        }
        
        val shuffled = spiritRootTypes.shuffled()
        return shuffled.take(count).joinToString(",")
    }
    
    private fun generateTalents(): List<String> {
        val count = Random.nextInt(1, 4)
        val talents = TalentDatabase.generateRandomTalents(count)
        return talents.map { it.id }
    }
    
    private fun generateManuals(maxRealm: Int): List<Pair<String, Int>> {
        val count = Random.nextInt(1, 6)
        val maxRarity = getMaxRarityByRealm(maxRealm)
        
        val allManuals = ManualDatabase.attackManuals.values +
                         ManualDatabase.defenseManuals.values +
                         ManualDatabase.mindManuals.values
        
        val availableManuals = allManuals.filter { it.rarity <= maxRarity }
        if (availableManuals.isEmpty()) return emptyList()
        
        val selected = availableManuals.shuffled().take(count)
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
    
    fun getMaxRarityByRealm(realm: Int): Int {
        return when (realm) {
            9, 8 -> 1
            7 -> 2
            6 -> 3
            5 -> 4
            4, 3 -> 5
            2, 1, 0 -> 6
            else -> 1
        }
    }
    
    fun recruitDisciplesForSect(sect: WorldSect, year: Int): WorldSect {
        if (sect.isPlayerSect) return sect
        
        val recruitCount = Random.nextInt(1, 11)
        val newDisciples = mutableListOf<Disciple>()
        
        repeat(recruitCount) {
            val disciple = generateRandomDisciple(sect.name, sect.maxRealm)
            newDisciples.add(disciple)
        }
        
        val allDisciples = sect.aiDisciples + newDisciples
        return sect.copy(aiDisciples = allDisciples)
    }
    
    fun processMonthlyCultivation(sect: WorldSect): WorldSect {
        if (sect.isPlayerSect) return sect
        
        val updatedDisciples = sect.aiDisciples.map { disciple ->
            if (!disciple.isAlive) return@map disciple
            
            val cultivationSpeed = disciple.calculateCultivationSpeed()
            val monthlyGain = cultivationSpeed * SECONDS_PER_MONTH
            
            var newCultivation = disciple.cultivation + monthlyGain
            var newRealm = disciple.realm
            var newRealmLayer = disciple.realmLayer
            var newBreakthroughFailCount = disciple.breakthroughFailCount
            
            while (newCultivation >= disciple.maxCultivation && newRealm > 0) {
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
            
            disciple.copy(
                cultivation = newCultivation,
                realm = newRealm,
                realmLayer = newRealmLayer,
                lifespan = newLifespan,
                breakthroughFailCount = newBreakthroughFailCount
            )
        }
        
        return sect.copy(aiDisciples = updatedDisciples)
    }
    
    fun processManualMasteryGrowth(sect: WorldSect): WorldSect {
        if (sect.isPlayerSect) return sect
        
        val updatedDisciples = sect.aiDisciples.map { disciple ->
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
        
        return sect.copy(aiDisciples = updatedDisciples)
    }
    
    fun processEquipmentNurture(sect: WorldSect): WorldSect {
        if (sect.isPlayerSect) return sect
        
        val updatedDisciples = sect.aiDisciples.map { disciple ->
            if (!disciple.isAlive) return@map disciple
            
            var updatedDisciple = disciple
            
            updatedDisciple = updateEquipmentNurture(
                disciple = updatedDisciple,
                equipmentId = disciple.weaponId,
                currentNurture = disciple.weaponNurture,
                nurtureSetter = { d, n -> d.copy(weaponNurture = n) }
            )
            
            updatedDisciple = updateEquipmentNurture(
                disciple = updatedDisciple,
                equipmentId = disciple.armorId,
                currentNurture = disciple.armorNurture,
                nurtureSetter = { d, n -> d.copy(armorNurture = n) }
            )
            
            updatedDisciple = updateEquipmentNurture(
                disciple = updatedDisciple,
                equipmentId = disciple.bootsId,
                currentNurture = disciple.bootsNurture,
                nurtureSetter = { d, n -> d.copy(bootsNurture = n) }
            )
            
            updatedDisciple = updateEquipmentNurture(
                disciple = updatedDisciple,
                equipmentId = disciple.accessoryId,
                currentNurture = disciple.accessoryNurture,
                nurtureSetter = { d, n -> d.copy(accessoryNurture = n) }
            )
            
            updatedDisciple
        }
        
        return sect.copy(aiDisciples = updatedDisciples)
    }
    
    private fun updateEquipmentNurture(
        disciple: Disciple,
        equipmentId: String?,
        currentNurture: EquipmentNurtureData?,
        nurtureSetter: (Disciple, EquipmentNurtureData?) -> Disciple
    ): Disciple {
        if (equipmentId == null) return disciple
        
        val template = EquipmentDatabase.getById(equipmentId) ?: return disciple
        val rarity = template.rarity
        
        val nurture = if (currentNurture == null || currentNurture.equipmentId != equipmentId) {
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
    
    fun processAging(sect: WorldSect): WorldSect {
        if (sect.isPlayerSect) return sect
        
        val updatedDisciples = sect.aiDisciples.map { disciple ->
            val newAge = disciple.age + 1
            val isAlive = newAge <= disciple.lifespan
            
            disciple.copy(
                age = newAge,
                isAlive = isAlive
            )
        }.filter { it.isAlive }
        
        return sect.copy(aiDisciples = updatedDisciples)
    }
    
    fun initializeSectDisciples(sect: WorldSect): WorldSect {
        if (sect.isPlayerSect) return sect
        
        val totalDisciples = when (sect.level) {
            0 -> Random.nextInt(15, 26)
            1 -> Random.nextInt(25, 41)
            2 -> Random.nextInt(40, 61)
            3 -> Random.nextInt(60, 101)
            else -> Random.nextInt(15, 26)
        }
        
        val disciples = mutableListOf<Disciple>()
        
        val maxRealm = when (sect.level) {
            0 -> 5
            1 -> 3
            2 -> 1
            3 -> 0
            else -> 5
        }
        
        val realmDistribution = generateRealmDistribution(totalDisciples, maxRealm)
        
        realmDistribution.forEach { (realm, count) ->
            repeat(count) {
                val disciple = generateRandomDisciple(sect.name, maxRealm)
                val adjustedDisciple = adjustDiscipleRealm(disciple, realm)
                disciples.add(adjustedDisciple)
            }
        }
        
        return sect.copy(
            aiDisciples = disciples,
            maxRealm = maxRealm
        )
    }
    
    private fun generateRealmDistribution(total: Int, maxRealm: Int): Map<Int, Int> {
        val distribution = mutableMapOf<Int, Int>()
        var remaining = total
        
        for (realm in 0..maxRealm) {
            if (remaining <= 0) break
            
            val count = when (realm) {
                0 -> if (maxRealm == 0) Random.nextInt(1, 4) else 0
                1 -> if (maxRealm <= 1) Random.nextInt(1, 4) else 0
                2 -> if (maxRealm <= 2) Random.nextInt(2, 6) else 0
                3 -> if (maxRealm <= 3) Random.nextInt(3, 8) else 0
                4 -> Random.nextInt(5, 11)
                5 -> Random.nextInt(8, 16)
                else -> 0
            }
            
            if (count > 0) {
                distribution[realm] = count.coerceAtMost(remaining)
                remaining -= count
            }
        }
        
        val midRealmCount = (remaining * 0.4).toInt()
        if (midRealmCount > 0) {
            distribution[6] = midRealmCount
            remaining -= midRealmCount
        }
        
        val lowMidCount = (remaining * 0.5).toInt()
        if (lowMidCount > 0) {
            distribution[7] = lowMidCount
            remaining -= lowMidCount
        }
        
        if (remaining > 0) {
            distribution[9] = remaining
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
        
        newDisciple = newDisciple.copy(
            manualIds = manuals.map { it.first },
            manualMasteries = manuals.associate { it.first to it.second },
            weaponId = equipments.firstOrNull { it.second == EquipmentSlot.WEAPON }?.first,
            armorId = equipments.firstOrNull { it.second == EquipmentSlot.ARMOR }?.first,
            bootsId = equipments.firstOrNull { it.second == EquipmentSlot.BOOTS }?.first,
            accessoryId = equipments.firstOrNull { it.second == EquipmentSlot.ACCESSORY }?.first,
            weaponNurture = null,
            armorNurture = null,
            bootsNurture = null,
            accessoryNurture = null
        )
        
        return newDisciple
    }
}
