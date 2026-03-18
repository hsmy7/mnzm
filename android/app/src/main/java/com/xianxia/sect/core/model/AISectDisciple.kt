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
    var breakthroughFailCount: Int = 0,
    var baseHp: Int = 100,
    var baseMp: Int = 50,
    var basePhysicalAttack: Int = 10,
    var baseMagicAttack: Int = 5,
    var basePhysicalDefense: Int = 5,
    var baseMagicDefense: Int = 3,
    var baseSpeed: Int = 10
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
        
        val baseChance = GameConfig.Realm.getBreakthroughChance(realm)
        
        var totalBonus = 0.0
        totalBonus += -0.01
        
        val spiritRootCount = spiritRoot.types.size
        val targetRealm = realm - 1
        
        val spiritRootBonus = when (spiritRootCount) {
            1 -> if (targetRealm >= 5) 0.5 else 0.2
            2 -> if (targetRealm >= 5) 0.25 else 0.1
            else -> 0.0
        }
        totalBonus += spiritRootBonus
        
        val talentEffects = getTalentEffects()
        val breakthroughBonus = talentEffects["breakthroughChance"] ?: 0.0
        totalBonus += breakthroughBonus
        
        val comprehensionBonus = (comprehension - 50) / 5 * 0.005
        totalBonus += comprehensionBonus
        
        totalBonus += breakthroughFailCount * 0.03
        
        val finalChance = baseChance * (1.0 + totalBonus)
        return finalChance.coerceIn(0.01, 1.0)
    }
    
    fun calculateCultivationSpeed(): Double {
        val baseSpeed = GameConfig.Cultivation.BASE_SPEED
        var totalBonus = 0.0
        
        totalBonus += (spiritRoot.cultivationBonus - 1.0)
        
        totalBonus += (comprehensionSpeedBonus - 1.0)
        
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
                totalBonus += cultivationSpeedPercent * masteryBonus / 100.0
            }
        }
        
        val talentEffects = getTalentEffects()
        val cultivationSpeedBonus = talentEffects["cultivationSpeed"] ?: 0.0
        totalBonus += cultivationSpeedBonus
        
        return baseSpeed * (1.0 + totalBonus)
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
        val realmMultiplier = realmConfig.multiplier
        val layerBonus = 1.0 + (realmLayer - 1) * 0.1
        
        val talentEffects = getTalentEffects()
        val hpBonus = talentEffects["maxHp"] ?: 0.0
        val mpBonus = talentEffects["maxMp"] ?: 0.0
        val attackBonus = talentEffects["physicalAttack"] ?: 0.0
        val magicAttackBonus = talentEffects["magicAttack"] ?: 0.0
        val defenseBonus = talentEffects["physicalDefense"] ?: 0.0
        val magicDefenseBonus = talentEffects["magicDefense"] ?: 0.0
        val speedBonus = talentEffects["speed"] ?: 0.0
        val critBonus = talentEffects["critRate"] ?: 0.0

        val totalHpBonus = (realmMultiplier - 1.0) + (layerBonus - 1.0) + hpBonus
        val totalMpBonus = (realmMultiplier - 1.0) + (layerBonus - 1.0) + mpBonus
        val totalAttackBonus = (realmMultiplier - 1.0) + (layerBonus - 1.0) + attackBonus
        val totalMagicAttackBonus = (realmMultiplier - 1.0) + (layerBonus - 1.0) + magicAttackBonus
        val totalDefenseBonus = (realmMultiplier - 1.0) + (layerBonus - 1.0) + defenseBonus
        val totalMagicDefenseBonus = (realmMultiplier - 1.0) + (layerBonus - 1.0) + magicDefenseBonus
        val totalSpeedBonus = (realmMultiplier - 1.0) + (layerBonus - 1.0) + speedBonus
        
        return DiscipleStats(
            hp = (baseHp * (1.0 + totalHpBonus)).toInt(),
            maxHp = (baseHp * (1.0 + totalHpBonus)).toInt(),
            mp = (baseMp * (1.0 + totalMpBonus)).toInt(),
            maxMp = (baseMp * (1.0 + totalMpBonus)).toInt(),
            physicalAttack = (basePhysicalAttack * (1.0 + totalAttackBonus)).toInt(),
            magicAttack = (baseMagicAttack * (1.0 + totalMagicAttackBonus)).toInt(),
            physicalDefense = (basePhysicalDefense * (1.0 + totalDefenseBonus)).toInt(),
            magicDefense = (baseMagicDefense * (1.0 + totalMagicDefenseBonus)).toInt(),
            speed = (baseSpeed * (1.0 + totalSpeedBonus)).toInt(),
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
