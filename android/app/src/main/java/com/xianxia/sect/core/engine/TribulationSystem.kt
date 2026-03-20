package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.Equipment
import com.xianxia.sect.core.model.Manual
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.GameConfig
import kotlin.random.Random

data class TribulationResult(
    val success: Boolean,
    val type: String,
    val message: String,
    val damageDealt: Int = 0
)

object TribulationSystem {
    
    val soulRequirements = mapOf(
        "筑基" to 20,
        "金丹" to 30,
        "元婴" to 40,
        "化神" to 50,
        "炼虚" to 70,
        "合体" to 100,
        "大乘" to 130,
        "渡劫" to 160,
        "仙人" to 200
    )
    
    fun trialHeartDemon(disciple: Disciple): TribulationResult {
        val newRealmIndex = disciple.realm - 1
        val newRealmName = GameConfig.Realm.getName(newRealmIndex)
        val requiredSoul = soulRequirements[newRealmName] ?: 20
        val currentSoul = disciple.soulPower
        
        return if (currentSoul >= requiredSoul) {
            TribulationResult(
                success = true,
                type = "heartDemon",
                message = "${disciple.name} 神魂达标（$currentSoul/$requiredSoul），心魔考验通过"
            )
        } else {
            TribulationResult(
                success = false,
                type = "heartDemon",
                message = "${disciple.name} 神魂不足（$currentSoul/$requiredSoul），心魔考验失败"
            )
        }
    }
    
    fun needsHeartDemon(disciple: Disciple): Boolean {
        return disciple.realm <= 6
    }
    
    fun trialThunderTribulation(
        disciple: Disciple,
        equipmentMap: Map<String, Equipment> = emptyMap(),
        manualMap: Map<String, Manual> = emptyMap(),
        manualProficiencies: Map<String, ManualProficiencyData> = emptyMap()
    ): TribulationResult {
        val newRealmIndex = disciple.realm - 1
        val thunderTribulation = createThunderTribulation(newRealmIndex)
        val newRealmName = GameConfig.Realm.getName(newRealmIndex)
        
        val finalStats = disciple.getFinalStats(equipmentMap, manualMap, manualProficiencies)
        
        var discipleHp = finalStats.maxHp
        var totalDamage = 0
        
        for (i in 1..3) {
            val physicalDamage = maxOf(1, thunderTribulation.physicalAttack - finalStats.physicalDefense)
            val magicDamage = maxOf(1, thunderTribulation.magicAttack - finalStats.magicDefense)
            val roundDamage = physicalDamage + magicDamage
            
            discipleHp -= roundDamage
            totalDamage += roundDamage
            
            if (discipleHp <= 0) {
                return TribulationResult(
                    success = false,
                    type = "thunder",
                    message = "${disciple.name} 未能渡过${newRealmName}雷劫！",
                    damageDealt = totalDamage
                )
            }
        }
        
        return TribulationResult(
            success = true,
            type = "thunder",
            message = "${disciple.name} 成功渡过${newRealmName}雷劫！",
            damageDealt = totalDamage
        )
    }
    
    private fun createThunderTribulation(realmIndex: Int): ThunderTribulation {
        val realmConfig = GameConfig.Realm.get(realmIndex)
        val multiplier = realmConfig.multiplier
        
        val basePhysicalAttack = (10 * multiplier).toInt()
        val baseMagicAttack = (5 * multiplier).toInt()
        val basePhysicalDefense = (5 * multiplier).toInt()
        val baseMagicDefense = (3 * multiplier).toInt()
        val baseMaxHp = (100 * multiplier).toInt()
        val baseMaxMp = (50 * multiplier).toInt()
        
        return ThunderTribulation(
            name = "${GameConfig.Realm.getName(realmIndex)}雷劫",
            physicalAttack = basePhysicalAttack,
            magicAttack = baseMagicAttack,
            physicalDefense = basePhysicalDefense,
            magicDefense = baseMagicDefense,
            maxHp = baseMaxHp,
            maxMp = baseMaxMp
        )
    }
    
    fun isBigBreakthrough(disciple: Disciple): Boolean {
        val realmConfig = GameConfig.Realm.get(disciple.realm)
        return disciple.realmLayer >= realmConfig.maxLayers && disciple.realm > 0
    }
    
    fun needsTribulation(disciple: Disciple): Boolean {
        return isBigBreakthrough(disciple) && disciple.realm > 0
    }
}

data class ThunderTribulation(
    val name: String,
    val physicalAttack: Int,
    val magicAttack: Int,
    val physicalDefense: Int,
    val magicDefense: Int,
    val maxHp: Int,
    val maxMp: Int
)
