package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.data.EquipmentDatabase
import com.xianxia.sect.core.data.ManualDatabase
import com.xianxia.sect.core.model.*
import kotlin.math.sqrt
import kotlin.random.Random

object AICaveTeamGenerator {
    
    fun generateAITeam(
        cave: CultivatorCave,
        nearbySects: List<WorldSect>,
        existingTeams: List<AICaveTeam>
    ): AICaveTeam? {
        val usedSectIds = existingTeams.map { it.sectId }.toSet()
        
        val eligibleSects = nearbySects.filter { sect ->
            sect.id !in usedSectIds &&
            sect.disciples.any { (realm, count) -> 
                realm <= cave.ownerRealm && count > 0 
            }
        }
        
        if (eligibleSects.isEmpty()) return null
        
        val targetSect = eligibleSects.minByOrNull { sect ->
            sqrt(
                (cave.x - sect.x).toDouble() * (cave.x - sect.x).toDouble() +
                (cave.y - sect.y).toDouble() * (cave.y - sect.y).toDouble()
            )
        } ?: return null
        
        val memberCount = Random.nextInt(5, 11)
        val aiDisciples = mutableListOf<AICaveDisciple>()
        
        val eligibleRealms = targetSect.disciples
            .filter { (realm, count) -> realm <= cave.ownerRealm && count > 0 }
            .keys
            .toList()
        
        if (eligibleRealms.isEmpty()) return null
        
        repeat(memberCount) { index ->
            val realm = eligibleRealms.random()
            val aiDisciple = generateRandomEquippedDisciple(
                sectName = targetSect.name,
                realm = realm,
                index = index
            )
            aiDisciples.add(aiDisciple)
        }
        
        if (aiDisciples.isEmpty()) return null
        
        val avgRealm = aiDisciples.map { it.realm }.average().toInt()
        
        return AICaveTeam(
            id = "ai_team_${cave.id}_${System.currentTimeMillis()}_${Random.nextInt(1000)}",
            caveId = cave.id,
            sectId = targetSect.id,
            sectName = targetSect.name,
            memberCount = aiDisciples.size,
            avgRealm = avgRealm,
            avgRealmName = GameConfig.Realm.getName(avgRealm),
            disciples = aiDisciples,
            status = AITeamStatus.EXPLORING
        )
    }
    
    private fun generateRandomEquippedDisciple(
        sectName: String,
        realm: Int,
        index: Int
    ): AICaveDisciple {
        val maxRarity = getMaxRarityByRealm(realm)
        
        val equipments = generateRandomEquipments(maxRarity)
        val manuals = generateRandomManuals(maxRarity)
        
        val baseStats = calculateBaseStats(realm)
        val equipStats = calculateEquipmentStats(equipments)
        val manualStats = calculateManualStats(manuals)
        
        val finalHp = baseStats.hp + equipStats.hp + manualStats.hp
        val finalAttack = baseStats.physicalAttack + equipStats.physicalAttack + manualStats.physicalAttack
        val finalDefense = baseStats.physicalDefense + equipStats.physicalDefense + manualStats.physicalDefense
        val finalSpeed = baseStats.speed + equipStats.speed + manualStats.speed
        
        return AICaveDisciple(
            id = "${sectName}_disciple_${index}_${System.currentTimeMillis()}",
            name = "${sectName}弟子${index + 1}",
            realm = realm,
            realmName = GameConfig.Realm.getName(realm),
            hp = finalHp,
            maxHp = finalHp,
            physicalAttack = finalAttack,
            magicAttack = (finalAttack * 0.5).toInt(),
            physicalDefense = finalDefense,
            magicDefense = (finalDefense * 0.8).toInt(),
            speed = finalSpeed,
            equipments = equipments,
            manuals = manuals
        )
    }
    
    private fun getMaxRarityByRealm(realm: Int): Int {
        return when (realm) {
            9, 8 -> 1
            7, 6 -> 2
            5 -> 3
            4 -> 4
            3 -> 5
            2, 1, 0 -> 6
            else -> 1
        }
    }
    
    private fun generateRandomEquipments(maxRarity: Int): List<AIRandomEquipment> {
        val equipments = mutableListOf<AIRandomEquipment>()
        
        for (slot in EquipmentSlot.values()) {
            if (Random.nextDouble() < 0.7) {
                val equipment = generateRandomEquipment(slot, maxRarity)
                equipments.add(equipment)
            }
        }
        
        return equipments
    }
    
    private fun generateRandomEquipment(slot: EquipmentSlot, maxRarity: Int): AIRandomEquipment {
        val rarity = Random.nextInt(1, maxRarity + 1)
        
        val templates = when (slot) {
            EquipmentSlot.WEAPON -> EquipmentDatabase.weapons.values.filter { it.rarity == rarity }
            EquipmentSlot.ARMOR -> EquipmentDatabase.armors.values.filter { it.rarity == rarity }
            EquipmentSlot.BOOTS -> EquipmentDatabase.boots.values.filter { it.rarity == rarity }
            EquipmentSlot.ACCESSORY -> EquipmentDatabase.accessories.values.filter { it.rarity == rarity }
        }
        
        if (templates.isEmpty()) {
            return AIRandomEquipment(
                slot = slot,
                name = "未知装备",
                rarity = rarity,
                nurtureLevel = 1
            )
        }
        
        val template = templates.random()
        val maxNurtureLevel = EquipmentNurtureSystem.getMaxNurtureLevel(rarity)
        val nurtureLevel = Random.nextInt(1, maxNurtureLevel + 1)
        val nurtureMult = 1.0 + nurtureLevel * 0.05
        
        return AIRandomEquipment(
            slot = slot,
            name = template.name,
            rarity = rarity,
            nurtureLevel = nurtureLevel,
            physicalAttack = (template.physicalAttack * nurtureMult).toInt(),
            magicAttack = (template.magicAttack * nurtureMult).toInt(),
            physicalDefense = (template.physicalDefense * nurtureMult).toInt(),
            magicDefense = (template.magicDefense * nurtureMult).toInt(),
            speed = (template.speed * nurtureMult).toInt(),
            hp = (template.hp * nurtureMult).toInt(),
            mp = (template.mp * nurtureMult).toInt()
        )
    }
    
    private fun generateRandomManuals(maxRarity: Int): List<AIRandomManual> {
        val manuals = mutableListOf<AIRandomManual>()
        val manualCount = Random.nextInt(0, 6)
        
        repeat(manualCount) {
            if (Random.nextDouble() < 0.7) {
                val manual = generateRandomManual(maxRarity)
                manuals.add(manual)
            }
        }
        
        return manuals
    }
    
    private fun generateRandomManual(maxRarity: Int): AIRandomManual {
        val rarity = Random.nextInt(1, maxRarity + 1)
        
        val templates = ManualDatabase.attackManuals.values.filter { it.rarity == rarity } +
                        ManualDatabase.defenseManuals.values.filter { it.rarity == rarity } +
                        ManualDatabase.mindManuals.values.filter { it.rarity == rarity }
        
        if (templates.isEmpty()) {
            return AIRandomManual(
                name = "未知功法",
                rarity = rarity,
                mastery = Random.nextInt(0, 101)
            )
        }
        
        val template = templates.random()
        val mastery = Random.nextInt(0, 101)
        
        val masteryBonus = when {
            mastery >= 100 -> 1.5
            mastery >= 80 -> 1.3
            mastery >= 60 -> 1.2
            mastery >= 40 -> 1.1
            mastery >= 20 -> 1.05
            else -> 1.0
        }
        
        val boostedStats = template.stats.mapValues { (_, value) ->
            (value * masteryBonus).toInt()
        }
        
        return AIRandomManual(
            name = template.name,
            rarity = rarity,
            mastery = mastery,
            stats = boostedStats
        )
    }
    
    private fun calculateBaseStats(realm: Int): BaseDiscipleStats {
        val realmConfig = GameConfig.Realm.get(realm)
        val baseHp = realmConfig.cultivationBase * 10
        val baseAttack = realmConfig.cultivationBase / 2
        val baseDefense = realmConfig.cultivationBase / 4
        val baseSpeed = 50 + realm * 10
        
        return BaseDiscipleStats(
            hp = baseHp,
            physicalAttack = baseAttack,
            physicalDefense = baseDefense,
            speed = baseSpeed
        )
    }
    
    private fun calculateEquipmentStats(equipments: List<AIRandomEquipment>): EquipmentStats {
        return EquipmentStats(
            physicalAttack = equipments.sumOf { it.physicalAttack },
            magicAttack = equipments.sumOf { it.magicAttack },
            physicalDefense = equipments.sumOf { it.physicalDefense },
            magicDefense = equipments.sumOf { it.magicDefense },
            speed = equipments.sumOf { it.speed },
            hp = equipments.sumOf { it.hp },
            mp = equipments.sumOf { it.mp }
        )
    }
    
    private fun calculateManualStats(manuals: List<AIRandomManual>): ManualStats {
        val stats = mutableMapOf<String, Int>()
        
        manuals.forEach { manual ->
            manual.stats.forEach { (key, value) ->
                stats[key] = (stats[key] ?: 0) + value
            }
        }
        
        return ManualStats(
            hp = stats["hp"] ?: 0,
            physicalAttack = stats["physicalAttack"] ?: 0,
            magicAttack = stats["magicAttack"] ?: 0,
            physicalDefense = stats["physicalDefense"] ?: 0,
            magicDefense = stats["magicDefense"] ?: 0,
            speed = stats["speed"] ?: 0
        )
    }
    
    private data class BaseDiscipleStats(
        val hp: Int,
        val physicalAttack: Int,
        val physicalDefense: Int,
        val speed: Int
    )
    
    private data class EquipmentStats(
        val physicalAttack: Int,
        val magicAttack: Int,
        val physicalDefense: Int,
        val magicDefense: Int,
        val speed: Int,
        val hp: Int,
        val mp: Int
    )
    
    private data class ManualStats(
        val hp: Int,
        val physicalAttack: Int,
        val magicAttack: Int,
        val physicalDefense: Int,
        val magicDefense: Int,
        val speed: Int
    )
}
