package com.xianxia.sect.core.model

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.data.ManualDatabase
import com.xianxia.sect.core.data.TalentDatabase
import kotlin.random.Random

data class AISectDisciple(
    val id: String = java.util.UUID.randomUUID().toString(),
    var name: String = "",
    var realm: Int = 9,
    var realmLayer: Int = 1,
    var cultivation: Double = 0.0,
    val spiritRootType: String = "metal",
    var age: Int = 16,
    var lifespan: Int = 80,
    var isAlive: Boolean = true,
    var combatStatsVariance: Int = 0,
    var talentIds: List<String> = emptyList(),
    var manualIds: List<String> = emptyList(),
    var manualMasteries: Map<String, Int> = emptyMap(),
    var weaponId: String? = null,
    var armorId: String? = null,
    var bootsId: String? = null,
    var accessoryId: String? = null,
    var weaponNurture: EquipmentNurtureData? = null,
    var armorNurture: EquipmentNurtureData? = null,
    var bootsNurture: EquipmentNurtureData? = null,
    var accessoryNurture: EquipmentNurtureData? = null,
    var comprehension: Int = 50,
    var breakthroughFailCount: Int = 0
) {
    val spiritRoot: SpiritRoot get() = SpiritRoot(spiritRootType)
    val spiritRootName: String get() = spiritRoot.name
    
    val realmName: String get() {
        if (age < 5 || realmLayer == 0) return "无境界"
        if (realm == 0) return GameConfig.Realm.getName(realm)
        return "${GameConfig.Realm.getName(realm)}${realmLayer}层"
    }
    
    val realmNameOnly: String get() = GameConfig.Realm.getName(realm)
    
    val maxCultivation: Double get() {
        if (realm == 0) return cultivation
        val base = GameConfig.Realm.get(realm).cultivationBase
        return base * (1.0 + (realmLayer - 1) * 0.2)
    }
    
    val cultivationProgress: Double get() = if (maxCultivation > 0) cultivation / maxCultivation else 0.0
    
    val comprehensionSpeedBonus: Double get() = 1.0 + (comprehension - 50) * 0.02
    
    fun canBreakthrough(): Boolean = cultivation >= maxCultivation
    
    fun getBreakthroughChance(): Double {
        if (realm <= 0) return 0.0
        
        var chance = GameConfig.Realm.getBreakthroughChance(realm)
        chance *= 0.99
        
        val spiritRootCount = spiritRoot.types.size
        val targetRealm = realm - 1
        
        val multiplier = when (spiritRootCount) {
            1 -> if (targetRealm >= 5) 1.5 else 1.2
            2 -> if (targetRealm >= 5) 1.25 else 1.1
            else -> 1.0
        }
        chance *= multiplier
        
        val talentEffects = getTalentEffects()
        val breakthroughBonus = talentEffects["breakthroughChance"] ?: 0.0
        chance += breakthroughBonus
        
        val comprehensionBonus = (comprehension - 50) / 5 * 0.005
        chance += comprehensionBonus
        
        chance += breakthroughFailCount * 0.03
        
        return chance.coerceIn(0.01, 1.0)
    }
    
    fun calculateCultivationSpeed(): Double {
        var speed = GameConfig.Cultivation.BASE_SPEED
        
        speed *= spiritRoot.cultivationBonus
        
        speed *= comprehensionSpeedBonus
        
        manualIds.forEach { manualId ->
            val manual = ManualDatabase.getById(manualId)
            if (manual != null) {
                val mastery = manualMasteries[manualId] ?: 0
                val masteryBonus = when {
                    mastery >= 100 -> 1.5
                    mastery >= 80 -> 1.3
                    mastery >= 60 -> 1.2
                    mastery >= 40 -> 1.1
                    mastery >= 20 -> 1.05
                    else -> 1.0
                }
                val cultivationSpeedPercent = manual.stats["cultivationSpeedPercent"] ?: 0
                speed *= (1 + cultivationSpeedPercent * masteryBonus / 100.0)
            }
        }
        
        val talentEffects = getTalentEffects()
        val cultivationSpeedBonus = talentEffects["cultivationSpeed"] ?: 0.0
        speed *= (1 + cultivationSpeedBonus)
        
        return speed
    }
    
    fun getTalentEffects(): Map<String, Double> {
        val effects = mutableMapOf<String, Double>()
        val talents = TalentDatabase.getTalentsByIds(talentIds)
        talents.forEach { talent ->
            talent.effects.forEach { (key, value) ->
                effects[key] = (effects[key] ?: 0.0) + value
            }
        }
        return effects
    }
    
    fun getBaseStats(): DiscipleStats {
        val realmConfig = GameConfig.Realm.get(realm)
        val spiritRootMultiplier = spiritRoot.cultivationBonus
        val realmMultiplier = realmConfig.multiplier
        val layerBonus = 1.0 + (realmLayer - 1) * 0.1
        
        val talentEffects = getTalentEffects()
        val hpBonus = 1.0 + (talentEffects["maxHp"] ?: 0.0)
        val mpBonus = 1.0 + (talentEffects["maxMp"] ?: 0.0)
        val attackBonus = 1.0 + (talentEffects["physicalAttack"] ?: 0.0)
        val magicAttackBonus = 1.0 + (talentEffects["magicAttack"] ?: 0.0)
        val defenseBonus = 1.0 + (talentEffects["physicalDefense"] ?: 0.0)
        val magicDefenseBonus = 1.0 + (talentEffects["magicDefense"] ?: 0.0)
        val speedBonus = 1.0 + (talentEffects["speed"] ?: 0.0)
        val critBonus = talentEffects["critRate"] ?: 0.0
        
        val varianceMultiplier = 1.0 + combatStatsVariance / 100.0
        
        return DiscipleStats(
            hp = (100 * spiritRootMultiplier * realmMultiplier * layerBonus * hpBonus * varianceMultiplier).toInt(),
            maxHp = (100 * spiritRootMultiplier * realmMultiplier * layerBonus * hpBonus * varianceMultiplier).toInt(),
            mp = (50 * spiritRootMultiplier * realmMultiplier * layerBonus * mpBonus * varianceMultiplier).toInt(),
            maxMp = (50 * spiritRootMultiplier * realmMultiplier * layerBonus * mpBonus * varianceMultiplier).toInt(),
            physicalAttack = (10 * spiritRootMultiplier * realmMultiplier * layerBonus * attackBonus * varianceMultiplier).toInt(),
            magicAttack = (5 * spiritRootMultiplier * realmMultiplier * layerBonus * magicAttackBonus * varianceMultiplier).toInt(),
            physicalDefense = (5 * spiritRootMultiplier * realmMultiplier * layerBonus * defenseBonus * varianceMultiplier).toInt(),
            magicDefense = (3 * spiritRootMultiplier * realmMultiplier * layerBonus * magicDefenseBonus * varianceMultiplier).toInt(),
            speed = (10 * spiritRootMultiplier * realmMultiplier * layerBonus * speedBonus * varianceMultiplier).toInt(),
            critRate = 0.05 + critBonus,
            intelligence = 0,
            charm = 0,
            loyalty = 0
        )
    }
}

data class AISectDiscipleManual(
    val manualId: String,
    val name: String,
    val rarity: Int,
    val mastery: Int,
    val stats: Map<String, Int> = emptyMap()
)

data class AISectDiscipleEquipment(
    val equipmentId: String,
    val name: String,
    val slot: EquipmentSlot,
    val rarity: Int,
    val nurtureLevel: Int = 1,
    val physicalAttack: Int = 0,
    val magicAttack: Int = 0,
    val physicalDefense: Int = 0,
    val magicDefense: Int = 0,
    val speed: Int = 0,
    val hp: Int = 0,
    val mp: Int = 0
)

data class EquipmentNurtureData(
    val equipmentId: String,
    val rarity: Int,
    val nurtureLevel: Int = 0,
    val nurtureProgress: Int = 0
)
