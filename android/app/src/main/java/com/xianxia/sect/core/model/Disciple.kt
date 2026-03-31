package com.xianxia.sect.core.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.data.ManualDatabase
import com.xianxia.sect.core.data.TalentDatabase
import com.xianxia.sect.core.engine.ManualProficiencySystem
import kotlin.math.roundToInt

@Entity(
    tableName = "disciples",
    indices = [
        Index(value = ["name"]),
        Index(value = ["realm", "realmLayer"]),
        Index(value = ["isAlive", "realm"]),
        Index(value = ["isAlive", "status"]),
        Index(value = ["discipleType"]),
        Index(value = ["loyalty"]),
        Index(value = ["age"])
    ]
)
data class Disciple(
    @PrimaryKey
    var id: String = java.util.UUID.randomUUID().toString(),
    var name: String = "",
    var realm: Int = 9,
    var realmLayer: Int = 1,
    var cultivation: Double = 0.0,
    
    val spiritRootType: String = "metal",
    
    var age: Int = 16,
    var lifespan: Int = 80,
    var isAlive: Boolean = true,
    
    var gender: String = "male",
    var partnerId: String? = null,
    var partnerSectId: String? = null,
    var parentId1: String? = null,
    var parentId2: String? = null,
    var lastChildYear: Int = 0,
    var griefEndYear: Int? = null,
    
    var weaponId: String? = null,
    var armorId: String? = null,
    var bootsId: String? = null,
    var accessoryId: String? = null,
    
    var manualIds: List<String> = emptyList(),
    var talentIds: List<String> = emptyList(),
    var manualMasteries: Map<String, Int> = emptyMap(),
    
    var weaponNurture: EquipmentNurtureData? = null,
    var armorNurture: EquipmentNurtureData? = null,
    var bootsNurture: EquipmentNurtureData? = null,
    var accessoryNurture: EquipmentNurtureData? = null,
    
    var spiritStones: Int = 0,
    var soulPower: Int = 10,
    
    var storageBagItems: List<StorageBagItem> = emptyList(),
    var storageBagSpiritStones: Long = 0,
    
    var status: DiscipleStatus = DiscipleStatus.IDLE,
    var statusData: Map<String, String> = emptyMap(),
    
    var cultivationSpeedBonus: Double = 1.0,
    var cultivationSpeedDuration: Int = 0,
    
    // 丹药临时属性加成（百分比，如0.1表示+10%）
    var pillPhysicalAttackBonus: Double = 0.0,
    var pillMagicAttackBonus: Double = 0.0,
    var pillPhysicalDefenseBonus: Double = 0.0,
    var pillMagicDefenseBonus: Double = 0.0,
    var pillHpBonus: Double = 0.0,
    var pillMpBonus: Double = 0.0,
    var pillSpeedBonus: Double = 0.0,
    var pillEffectDuration: Int = 0,  // 剩余回合/月数
    
    var totalCultivation: Long = 0,
    var breakthroughCount: Int = 0,
    var breakthroughFailCount: Int = 0,
    var battlesWon: Int = 0,
    
    // 新增加的属性
    var intelligence: Int = 50,
    var charm: Int = 50,
    var loyalty: Int = 50,
    var comprehension: Int = 50,
    var artifactRefining: Int = 50,
    var pillRefining: Int = 50,
    var spiritPlanting: Int = 50,
    var teaching: Int = 50,
    var morality: Int = 50,
    
    // 月俸累计次数（累计发放3次忠诚+1，累计少发3次忠诚-1）
    var salaryPaidCount: Int = 0,
    var salaryMissedCount: Int = 0,

    // 招募时间（游戏月），用于新弟子保护期
    var recruitedMonth: Int = 0,

    // 战斗属性独立浮动百分比（±30%，精确到1%，如15表示+15%，-20表示-20%）
    var hpVariance: Int = 0,
    var mpVariance: Int = 0,
    var physicalAttackVariance: Int = 0,
    var magicAttackVariance: Int = 0,
    var physicalDefenseVariance: Int = 0,
    var magicDefenseVariance: Int = 0,
    var speedVariance: Int = 0,

    // 基础战斗属性（创建时根据浮动系数计算并存储，后续境界提升不再受浮动影响）
    var baseHp: Int = 120,
    var baseMp: Int = 60,
    var basePhysicalAttack: Int = 12,
    var baseMagicAttack: Int = 12,
    var basePhysicalDefense: Int = 10,
    var baseMagicDefense: Int = 8,
    var baseSpeed: Int = 15,

    // 弟子类型：outer=外门弟子，inner=内门弟子
    var discipleType: String = "outer",

    // 本月已使用的功能型丹药ID列表（每月重置）
    var monthlyUsedPillIds: List<String> = emptyList(),

    // 已使用过的增寿丹药ID列表（永久记录，不会重置）
    var usedExtendLifePillIds: List<String> = emptyList(),

    // 功能型丹药效果标记
    var hasReviveEffect: Boolean = false,
    var hasClearAllEffect: Boolean = false
) {
    val canCultivate: Boolean get() = age >= 5
    val realmName: String get() {
        if (age < 5 || realmLayer == 0) return "无境界"
        // 仙人境界不显示层数
        if (realm == 0) return GameConfig.Realm.getName(realm)
        return "${GameConfig.Realm.getName(realm)}${realmLayer}层"
    }
    val realmNameOnly: String get() = GameConfig.Realm.getName(realm)
    val maxCultivation: Double get() {
        // 仙人境界修为直接显示满值
        if (realm == 0) return cultivation
        val base = GameConfig.Realm.get(realm).cultivationBase
        // 每层修为要求递增 20%
        return base * (1.0 + (realmLayer - 1) * 0.2)
    }
    val cultivationProgress: Double get() = if (maxCultivation > 0) cultivation / maxCultivation else 0.0
    
    val spiritRoot: SpiritRoot get() = SpiritRoot(spiritRootType)
    val spiritRootName: String get() = spiritRoot.name
    
    val physicalAttack: Int get() = getBaseStats().physicalAttack
    val physicalDefense: Int get() = getBaseStats().physicalDefense
    val magicAttack: Int get() = getBaseStats().magicAttack
    val magicDefense: Int get() = getBaseStats().magicDefense
    val speed: Int get() = getBaseStats().speed
    val maxHp: Int get() = getBaseStats().maxHp
    val maxMp: Int get() = getBaseStats().maxMp
    
    val hpPercent: Double get() = if (maxHp > 0) baseHp.toDouble() / maxHp * 100 else 100.0
    val mpPercent: Double get() = if (maxMp > 0) baseMp.toDouble() / maxMp * 100 else 100.0
    
    val equippedItems: Map<EquipmentSlot, Equipment?> get() = emptyMap()
    val learnedManuals: List<Manual> get() = emptyList()
    
    val genderName: String get() = if (gender == "male") "男" else "女"
    val genderSymbol: String get() = if (gender == "male") "♂" else "♀"
    val hasPartner: Boolean get() = partnerId != null
    
    val comprehensionSpeedBonus: Double get() = 1.0 + (comprehension - 50) * 0.02

    companion object {
        fun calculateBaseStatsWithVariance(
            hpVariance: Int,
            mpVariance: Int,
            physicalAttackVariance: Int,
            magicAttackVariance: Int,
            physicalDefenseVariance: Int,
            magicDefenseVariance: Int,
            speedVariance: Int
        ): BaseCombatStats {
            return BaseCombatStats(
                baseHp = (120 * (1.0 + hpVariance / 100.0)).toInt(),
                baseMp = (60 * (1.0 + mpVariance / 100.0)).toInt(),
                basePhysicalAttack = (12 * (1.0 + physicalAttackVariance / 100.0)).toInt(),
                baseMagicAttack = (12 * (1.0 + magicAttackVariance / 100.0)).toInt(),
                basePhysicalDefense = (10 * (1.0 + physicalDefenseVariance / 100.0)).toInt(),
                baseMagicDefense = (8 * (1.0 + magicDefenseVariance / 100.0)).toInt(),
                baseSpeed = (15 * (1.0 + speedVariance / 100.0)).toInt()
            )
        }

        fun fixBaseStats(disciple: Disciple): Disciple {
            val needsFix = disciple.hpVariance == 0 &&
                           disciple.mpVariance == 0 &&
                           disciple.physicalAttackVariance == 0 &&
                           disciple.magicAttackVariance == 0 &&
                           disciple.physicalDefenseVariance == 0 &&
                           disciple.magicDefenseVariance == 0 &&
                           disciple.speedVariance == 0 &&
                           disciple.baseHp == 120
            
            if (!needsFix) return disciple
            
            val hpVariance = kotlin.random.Random.nextInt(-30, 31)
            val mpVariance = kotlin.random.Random.nextInt(-30, 31)
            val physicalAttackVariance = kotlin.random.Random.nextInt(-30, 31)
            val magicAttackVariance = kotlin.random.Random.nextInt(-30, 31)
            val physicalDefenseVariance = kotlin.random.Random.nextInt(-30, 31)
            val magicDefenseVariance = kotlin.random.Random.nextInt(-30, 31)
            val speedVariance = kotlin.random.Random.nextInt(-30, 31)
            
            val baseStats = calculateBaseStatsWithVariance(
                hpVariance, mpVariance, physicalAttackVariance, magicAttackVariance,
                physicalDefenseVariance, magicDefenseVariance, speedVariance
            )
            
            return disciple.copy(
                hpVariance = hpVariance,
                mpVariance = mpVariance,
                physicalAttackVariance = physicalAttackVariance,
                magicAttackVariance = magicAttackVariance,
                physicalDefenseVariance = physicalDefenseVariance,
                magicDefenseVariance = magicDefenseVariance,
                speedVariance = speedVariance,
                baseHp = baseStats.baseHp,
                baseMp = baseStats.baseMp,
                basePhysicalAttack = baseStats.basePhysicalAttack,
                baseMagicAttack = baseStats.baseMagicAttack,
                basePhysicalDefense = baseStats.basePhysicalDefense,
                baseMagicDefense = baseStats.baseMagicDefense,
                baseSpeed = baseStats.baseSpeed
            )
        }
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

        val maxHpGrowth = statusData["winGrowth.maxHp"]?.toIntOrNull() ?: 0
        val maxMpGrowth = statusData["winGrowth.maxMp"]?.toIntOrNull() ?: 0
        val physicalAttackGrowth = statusData["winGrowth.physicalAttack"]?.toIntOrNull() ?: 0
        val magicAttackGrowth = statusData["winGrowth.magicAttack"]?.toIntOrNull() ?: 0
        val physicalDefenseGrowth = statusData["winGrowth.physicalDefense"]?.toIntOrNull() ?: 0
        val magicDefenseGrowth = statusData["winGrowth.magicDefense"]?.toIntOrNull() ?: 0
        val speedGrowth = statusData["winGrowth.speed"]?.toIntOrNull() ?: 0

        val totalHpBonus = (realmMultiplier - 1.0) + (layerBonus - 1.0) + hpBonus
        val totalMpBonus = (realmMultiplier - 1.0) + (layerBonus - 1.0) + mpBonus
        val totalAttackBonus = (realmMultiplier - 1.0) + (layerBonus - 1.0) + attackBonus
        val totalMagicAttackBonus = (realmMultiplier - 1.0) + (layerBonus - 1.0) + magicAttackBonus
        val totalDefenseBonus = (realmMultiplier - 1.0) + (layerBonus - 1.0) + defenseBonus
        val totalMagicDefenseBonus = (realmMultiplier - 1.0) + (layerBonus - 1.0) + magicDefenseBonus
        val totalSpeedBonus = (realmMultiplier - 1.0) + (layerBonus - 1.0) + speedBonus

        return DiscipleStats(
            hp = (baseHp * (1.0 + totalHpBonus)).roundToInt() + maxHpGrowth,
            maxHp = (baseHp * (1.0 + totalHpBonus)).roundToInt() + maxHpGrowth,
            mp = (baseMp * (1.0 + totalMpBonus)).roundToInt() + maxMpGrowth,
            maxMp = (baseMp * (1.0 + totalMpBonus)).roundToInt() + maxMpGrowth,
            physicalAttack = (basePhysicalAttack * (1.0 + totalAttackBonus)).roundToInt() + physicalAttackGrowth,
            magicAttack = (baseMagicAttack * (1.0 + totalMagicAttackBonus)).roundToInt() + magicAttackGrowth,
            physicalDefense = (basePhysicalDefense * (1.0 + totalDefenseBonus)).roundToInt() + physicalDefenseGrowth,
            magicDefense = (baseMagicDefense * (1.0 + totalMagicDefenseBonus)).roundToInt() + magicDefenseGrowth,
            speed = (baseSpeed * (1.0 + totalSpeedBonus)).roundToInt() + speedGrowth,
            critRate = 0.05 + critBonus,
            intelligence = intelligence,
            charm = charm,
            loyalty = loyalty
        )
    }

    // 获取天赋效果汇总
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
    
    fun getStatsWithEquipment(equipments: Map<String, Equipment>): DiscipleStats {
        val base = getBaseStats()
        var total = base
        var totalCritChance = 0.0
        
        listOfNotNull(weaponId, armorId, bootsId, accessoryId).forEach { equipId ->
            val equipment = equipments[equipId]
            if (equipment != null) {
                equipment.getFinalStats().toDiscipleStats().let { total = total + it }
                totalCritChance += equipment.critChance
            }
        }
        
        return total.copy(critRate = total.critRate + totalCritChance)
    }

    fun getFinalStats(
        equipments: Map<String, Equipment>, 
        manuals: Map<String, Manual>,
        manualProficiencies: Map<String, ManualProficiencyData> = emptyMap()
    ): DiscipleStats {
        val baseStats = getBaseStats()
        var total = baseStats
        var totalCritRate = total.critRate

        listOfNotNull(weaponId, armorId, bootsId, accessoryId).forEach { equipId ->
            val equipment = equipments[equipId]
            if (equipment != null) {
                equipment.getFinalStats().toDiscipleStats().let { total = total + it }
                totalCritRate += equipment.critChance
            }
        }

        manualIds.forEach { manualId ->
            val manual = manuals[manualId]
            if (manual != null) {
                val proficiencyData = manualProficiencies[manualId]
                val masteryLevel = proficiencyData?.masteryLevel ?: 0
                val masteryBonus = ManualProficiencySystem.MasteryLevel.fromLevel(masteryLevel).bonus
                
                val hpValue = manual.stats["hp"] ?: manual.stats["maxHp"] ?: 0
                val mpValue = manual.stats["mp"] ?: manual.stats["maxMp"] ?: 0
                val manualStats = DiscipleStats(
                    hp = (hpValue * masteryBonus).toInt(),
                    maxHp = (hpValue * masteryBonus).toInt(),
                    mp = (mpValue * masteryBonus).toInt(),
                    maxMp = (mpValue * masteryBonus).toInt(),
                    physicalAttack = ((manual.stats["physicalAttack"] ?: 0) * masteryBonus).toInt(),
                    magicAttack = ((manual.stats["magicAttack"] ?: 0) * masteryBonus).toInt(),
                    physicalDefense = ((manual.stats["physicalDefense"] ?: 0) * masteryBonus).toInt(),
                    magicDefense = ((manual.stats["magicDefense"] ?: 0) * masteryBonus).toInt(),
                    speed = ((manual.stats["speed"] ?: 0) * masteryBonus).toInt(),
                    critRate = 1.0
                )
                total = total + manualStats
                totalCritRate += ((manual.stats["critRate"] ?: 0) * masteryBonus) / 100.0
            }
        }

        if (pillEffectDuration > 0) {
            val pillBonus = DiscipleStats(
                hp = (baseStats.maxHp * pillHpBonus).toInt(),
                maxHp = (baseStats.maxHp * pillHpBonus).toInt(),
                mp = (baseStats.maxMp * pillMpBonus).toInt(),
                maxMp = (baseStats.maxMp * pillMpBonus).toInt(),
                physicalAttack = (baseStats.physicalAttack * pillPhysicalAttackBonus).toInt(),
                magicAttack = (baseStats.magicAttack * pillMagicAttackBonus).toInt(),
                physicalDefense = (baseStats.physicalDefense * pillPhysicalDefenseBonus).toInt(),
                magicDefense = (baseStats.magicDefense * pillMagicDefenseBonus).toInt(),
                speed = (baseStats.speed * pillSpeedBonus).toInt(),
                critRate = 0.0
            )
            total = total + pillBonus
        }

        return total.copy(critRate = totalCritRate)
    }

    fun calculateCultivationSpeedPerSecond(
        manuals: Map<String, Manual> = emptyMap(),
        manualProficiencies: Map<String, ManualProficiencyData> = emptyMap(),
        additionalBonus: Double = 0.0
    ): Double {
        val baseSpeed = GameConfig.Cultivation.BASE_SPEED
        
        var totalBonus = 0.0
        
        totalBonus += (spiritRoot.cultivationBonus - 1.0)
        
        totalBonus += (comprehensionSpeedBonus - 1.0)
        
        totalBonus += (cultivationSpeedBonus - 1.0)
        
        manualIds.forEach { manualId ->
            val manual = manuals[manualId]
            if (manual != null) {
                val proficiencyData = manualProficiencies[manualId]
                val masteryLevel = proficiencyData?.masteryLevel ?: 0
                val masteryBonus = ManualProficiencySystem.MasteryLevel.fromLevel(masteryLevel).bonus
                totalBonus += manual.cultivationSpeedPercent * masteryBonus / 100.0
            }
        }
        
        val talents = TalentDatabase.getTalentsByIds(talentIds)
        talents.forEach { talent ->
            totalBonus += (talent.effects["cultivationSpeed"] ?: 0.0)
        }
        
        totalBonus += additionalBonus
        
        return baseSpeed * (1.0 + totalBonus)
    }
    
    fun calculateCultivationSpeed(): Double {
        val baseSpeed = GameConfig.Cultivation.BASE_SPEED
        var totalBonus = 0.0
        
        totalBonus += (spiritRoot.cultivationBonus - 1.0)
        totalBonus += (comprehensionSpeedBonus - 1.0)
        totalBonus += (cultivationSpeedBonus - 1.0)
        
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
    
    fun canBreakthrough(): Boolean = cultivation >= maxCultivation
    
    fun getBreakthroughChance(pillBonus: Double = 0.0, innerElderComprehension: Int = 0, outerElderComprehensionBonus: Double = 0.0): Double {
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
        
        totalBonus += pillBonus

        val talentEffects = getTalentEffects()
        val breakthroughBonus = talentEffects["breakthroughChance"] ?: 0.0
        totalBonus += breakthroughBonus

        val comprehensionBonus = (comprehension - 50) / 5 * 0.005
        totalBonus += comprehensionBonus

        if (innerElderComprehension > 0) {
            val innerElderBonus = (innerElderComprehension - 50) * 0.001
            totalBonus += innerElderBonus
        }

        totalBonus += outerElderComprehensionBonus

        totalBonus += breakthroughFailCount * 0.03

        val finalChance = baseChance * (1.0 + totalBonus)
        return finalChance.coerceIn(0.01, 1.0)
    }
}

enum class DiscipleStatus {
    IDLE, CULTIVATING, EXPLORING, ALCHEMY, FORGING, FARMING, STUDYING, BATTLE, WORKING, SCOUTING, MINING, REFLECTING, LAW_ENFORCING, PREACHING, MANAGING, GROWING, ON_MISSION;

    val displayName: String get() = when (this) {
        IDLE -> "空闲中"
        CULTIVATING -> "修炼中"
        EXPLORING -> "探索中"
        ALCHEMY -> "炼丹中"
        FORGING -> "炼器中"
        FARMING -> "种植中"
        STUDYING -> "研习中"
        BATTLE -> "战斗中"
        WORKING -> "工作中"
        SCOUTING -> "探查中"
        MINING -> "采矿中"
        REFLECTING -> "思过中"
        LAW_ENFORCING -> "执法中"
        PREACHING -> "传道中"
        MANAGING -> "管理中"
        GROWING -> "成长中"
        ON_MISSION -> "执行任务中"
    }
}

data class SpiritRoot(
    val type: String
) {
    val types: List<String> get() = type.split(",")

    val name: String get() {
        val rootNames = types.map { GameConfig.SpiritRoot.get(it.trim()).name }
        return when (rootNames.size) {
            1 -> "单灵根(${rootNames[0]})"
            2 -> "双灵根(${rootNames[0]}${rootNames[1]})"
            3 -> "三灵根(${rootNames.joinToString("")})"
            4 -> "四灵根(${rootNames.joinToString("")})"
            5 -> "五灵根(全灵根)"
            else -> rootNames[0]
        }
    }

    val elementColor: String get() = GameConfig.SpiritRoot.get(types.first().trim()).color

    val countColor: String get() = when (types.size) {
        1 -> "#E74C3C"
        2 -> "#F39C12"
        3 -> "#9B59B6"
        4 -> "#27AE60"
        5 -> "#95A5A6"
        else -> "#95A5A6"
    }

    val cultivationBonus: Double
        get() {
            return when (types.size) {
                1 -> 5.0
                2 -> 3.5
                3 -> 2.0
                4 -> 1.2
                5 -> 1.0
                else -> 1.0
            }
        }
}

data class Talent(
    val id: String,
    val name: String,
    val description: String,
    val rarity: Int,
    val effects: Map<String, Double>,
    val isNegative: Boolean = false
) {
    val color: String get() = GameConfig.Rarity.getColor(rarity)
    val rarityName: String get() = GameConfig.Rarity.getName(rarity)
}

data class DiscipleStats(
    val hp: Int = 0,
    val maxHp: Int = 0,
    val mp: Int = 0,
    val maxMp: Int = 0,
    val physicalAttack: Int = 0,
    val magicAttack: Int = 0,
    val physicalDefense: Int = 0,
    val magicDefense: Int = 0,
    val speed: Int = 0,
    val critRate: Double = 0.0,
    val intelligence: Int = 0,
    val charm: Int = 0,
    val loyalty: Int = 0
) {
    operator fun plus(other: DiscipleStats): DiscipleStats {
        return DiscipleStats(
            hp = hp + other.hp,
            maxHp = maxHp + other.maxHp,
            mp = mp + other.mp,
            maxMp = maxMp + other.maxMp,
            physicalAttack = physicalAttack + other.physicalAttack,
            magicAttack = magicAttack + other.magicAttack,
            physicalDefense = physicalDefense + other.physicalDefense,
            magicDefense = magicDefense + other.magicDefense,
            speed = speed + other.speed,
            critRate = critRate + other.critRate,
            intelligence = intelligence + other.intelligence,
            charm = charm + other.charm,
            loyalty = loyalty + other.loyalty
        )
    }
}

data class BaseCombatStats(
    val baseHp: Int = 120,
    val baseMp: Int = 60,
    val basePhysicalAttack: Int = 12,
    val baseMagicAttack: Int = 12,
    val basePhysicalDefense: Int = 10,
    val baseMagicDefense: Int = 8,
    val baseSpeed: Int = 15
)

data class StorageBagItem(
    val itemId: String,
    val itemType: String,
    val name: String,
    val rarity: Int,
    val quantity: Int = 1,
    val obtainedYear: Int = 1,
    val obtainedMonth: Int = 1,
    val effect: ItemEffect? = null
) {
    val color: String get() = GameConfig.Rarity.getColor(rarity)
    val rarityName: String get() = GameConfig.Rarity.getName(rarity)
}

data class ItemEffect(
    val cultivationSpeed: Double = 1.0,
    val cultivationPercent: Double = 0.0,
    val skillExpPercent: Double = 0.0,
    val breakthroughChance: Double = 0.0,
    val targetRealm: Int = 0,
    val heal: Int = 0,
    val healPercent: Double = 0.0,
    val healMaxHpPercent: Double = 0.0,
    val hpPercent: Double = 0.0,
    val mpPercent: Double = 0.0,
    val mpRecoverPercent: Double = 0.0,
    val extendLife: Int = 0,
    val battleCount: Int = 0,
    val physicalAttackPercent: Double = 0.0,
    val magicAttackPercent: Double = 0.0,
    val physicalDefensePercent: Double = 0.0,
    val magicDefensePercent: Double = 0.0,
    val speedPercent: Double = 0.0,
    val revive: Boolean = false,
    val clearAll: Boolean = false,
    val duration: Int = 0
)

data class RewardSelectedItem(
    val id: String,
    val type: String,
    val name: String,
    val rarity: Int,
    val quantity: Int
)

data class EquipmentNurtureData(
    val equipmentId: String,
    val rarity: Int,
    val nurtureLevel: Int = 0,
    val nurtureProgress: Double = 0.0
)
