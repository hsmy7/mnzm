package com.xianxia.sect.core.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.data.TalentDatabase
import com.xianxia.sect.core.engine.ManualProficiencySystem

@Entity(tableName = "disciples")
data class Disciple(
    @PrimaryKey
    var id: String = java.util.UUID.randomUUID().toString(),
    var name: String = "",
    var realm: Int = 9,
    var realmLayer: Int = 1,
    var cultivation: Double = 0.0,
    
    val spiritRootType: String = "metal",
    
    var age: Int = 16,
    var lifespan: Int = 100,
    var isAlive: Boolean = true,
    
    var gender: String = if (kotlin.random.Random.nextBoolean()) "male" else "female",
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

    // 战斗属性浮动百分比（±30%，精确到1%，如15表示+15%，-20表示-20%）
    var combatStatsVariance: Int = 0,

    // 弟子类型：outer=外门弟子，inner=内门弟子
    var discipleType: String = "outer",

    // 本月已使用的功能型丹药ID列表（每月重置）
    var monthlyUsedPillIds: List<String> = emptyList(),

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
    
    val equippedItems: Map<EquipmentSlot, Equipment?> get() = emptyMap()
    val learnedManuals: List<Manual> get() = emptyList()
    
    val genderName: String get() = if (gender == "male") "男" else "女"
    val genderSymbol: String get() = if (gender == "male") "♂" else "♀"
    val hasPartner: Boolean get() = partnerId != null
    
    // 计算悟性对修炼速度的加成（正面每点4%，负面每点2%）
    val comprehensionSpeedBonus: Double get() {
        val diff = comprehension - 50
        return if (diff >= 0) {
            1.0 + (diff * 0.04)
        } else {
            1.0 + (diff * 0.02)
        }
    }
    
    // 获取无任何加成的原始属性（境界加成 only）
    fun getRawBaseStats(): DiscipleStats {
        val realmConfig = GameConfig.Realm.get(realm)
        val realmMultiplier = realmConfig.multiplier
        val layerBonus = 1.0 + (realmLayer - 1) * 0.1

        return DiscipleStats(
            hp = (100 * realmMultiplier * layerBonus).toInt(),
            maxHp = (100 * realmMultiplier * layerBonus).toInt(),
            mp = (50 * realmMultiplier * layerBonus).toInt(),
            maxMp = (50 * realmMultiplier * layerBonus).toInt(),
            physicalAttack = (10 * realmMultiplier * layerBonus).toInt(),
            magicAttack = (5 * realmMultiplier * layerBonus).toInt(),
            physicalDefense = (5 * realmMultiplier * layerBonus).toInt(),
            magicDefense = (3 * realmMultiplier * layerBonus).toInt(),
            speed = (10 * realmMultiplier * layerBonus).toInt(),
            critRate = 0.05,
            intelligence = 0,
            charm = 0,
            loyalty = 0
        )
    }

    // 获取灵根加成值
    fun getSpiritRootBonus(): DiscipleStats {
        val raw = getRawBaseStats()
        val withSpiritRoot = getBaseStats()
        return DiscipleStats(
            hp = withSpiritRoot.hp - raw.hp,
            maxHp = withSpiritRoot.maxHp - raw.maxHp,
            mp = withSpiritRoot.mp - raw.mp,
            maxMp = withSpiritRoot.maxMp - raw.maxMp,
            physicalAttack = withSpiritRoot.physicalAttack - raw.physicalAttack,
            magicAttack = withSpiritRoot.magicAttack - raw.magicAttack,
            physicalDefense = withSpiritRoot.physicalDefense - raw.physicalDefense,
            magicDefense = withSpiritRoot.magicDefense - raw.magicDefense,
            speed = withSpiritRoot.speed - raw.speed,
            critRate = 0.0,
            intelligence = withSpiritRoot.intelligence - raw.intelligence,
            charm = withSpiritRoot.charm - raw.charm,
            loyalty = withSpiritRoot.loyalty - raw.loyalty
        )
    }

    fun getBaseStats(): DiscipleStats {
        val realmConfig = GameConfig.Realm.get(realm)
        val spiritRootMultiplier = spiritRoot.cultivationBonus
        val realmMultiplier = realmConfig.multiplier
        val layerBonus = 1.0 + (realmLayer - 1) * 0.1

        // 计算天赋加成
        val talentEffects = getTalentEffects()
        val hpBonus = 1.0 + (talentEffects["maxHp"] ?: 0.0)
        val mpBonus = 1.0 + (talentEffects["maxMp"] ?: 0.0)
        val attackBonus = 1.0 + (talentEffects["physicalAttack"] ?: 0.0)
        val magicAttackBonus = 1.0 + (talentEffects["magicAttack"] ?: 0.0)
        val defenseBonus = 1.0 + (talentEffects["physicalDefense"] ?: 0.0)
        val magicDefenseBonus = 1.0 + (talentEffects["magicDefense"] ?: 0.0)
        val speedBonus = 1.0 + (talentEffects["speed"] ?: 0.0)
        val critBonus = talentEffects["critRate"] ?: 0.0

        // 战斗属性浮动百分比（combatStatsVariance 存储 -30 到 +30，表示 -30% 到 +30%）
        val varianceMultiplier = 1.0 + combatStatsVariance / 100.0

        // 胜场成长天赋通过 statusData 持久化战斗属性的固定成长值（无上限）
        val maxHpGrowth = statusData["winGrowth.maxHp"]?.toIntOrNull() ?: 0
        val maxMpGrowth = statusData["winGrowth.maxMp"]?.toIntOrNull() ?: 0
        val physicalAttackGrowth = statusData["winGrowth.physicalAttack"]?.toIntOrNull() ?: 0
        val magicAttackGrowth = statusData["winGrowth.magicAttack"]?.toIntOrNull() ?: 0
        val physicalDefenseGrowth = statusData["winGrowth.physicalDefense"]?.toIntOrNull() ?: 0
        val magicDefenseGrowth = statusData["winGrowth.magicDefense"]?.toIntOrNull() ?: 0
        val speedGrowth = statusData["winGrowth.speed"]?.toIntOrNull() ?: 0

        return DiscipleStats(
            hp = ((100 * spiritRootMultiplier * realmMultiplier * layerBonus * hpBonus * varianceMultiplier).toInt() + maxHpGrowth),
            maxHp = ((100 * spiritRootMultiplier * realmMultiplier * layerBonus * hpBonus * varianceMultiplier).toInt() + maxHpGrowth),
            mp = ((50 * spiritRootMultiplier * realmMultiplier * layerBonus * mpBonus * varianceMultiplier).toInt() + maxMpGrowth),
            maxMp = ((50 * spiritRootMultiplier * realmMultiplier * layerBonus * mpBonus * varianceMultiplier).toInt() + maxMpGrowth),
            physicalAttack = ((10 * spiritRootMultiplier * realmMultiplier * layerBonus * attackBonus * varianceMultiplier).toInt() + physicalAttackGrowth),
            magicAttack = ((5 * spiritRootMultiplier * realmMultiplier * layerBonus * magicAttackBonus * varianceMultiplier).toInt() + magicAttackGrowth),
            physicalDefense = ((5 * spiritRootMultiplier * realmMultiplier * layerBonus * defenseBonus * varianceMultiplier).toInt() + physicalDefenseGrowth),
            magicDefense = ((3 * spiritRootMultiplier * realmMultiplier * layerBonus * magicDefenseBonus * varianceMultiplier).toInt() + magicDefenseGrowth),
            speed = ((10 * spiritRootMultiplier * realmMultiplier * layerBonus * speedBonus * varianceMultiplier).toInt() + speedGrowth),
            critRate = 0.05 + critBonus,
            intelligence = intelligence,
            charm = charm,
            loyalty = loyalty
        )
    }

    // 获取天赋效果汇总
    private fun getTalentEffects(): Map<String, Double> {
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

    // 获取完整属性（包含装备、功法和丹药加成）
    fun getFinalStats(
        equipments: Map<String, Equipment>, 
        manuals: Map<String, Manual>,
        manualProficiencies: Map<String, ManualProficiencyData> = emptyMap()
    ): DiscipleStats {
        var total = getStatsWithEquipment(equipments)
        var totalCritRate = total.critRate

        manualIds.forEach { manualId ->
            val manual = manuals[manualId]
            if (manual != null) {
                val proficiencyData = manualProficiencies[manualId]
                val masteryLevel = proficiencyData?.masteryLevel ?: 0
                val masteryBonus = ManualProficiencySystem.MasteryLevel.fromLevel(masteryLevel).bonus
                
                val manualStats = DiscipleStats(
                    hp = ((manual.stats["maxHp"] ?: 0) * masteryBonus).toInt(),
                    maxHp = ((manual.stats["maxHp"] ?: 0) * masteryBonus).toInt(),
                    mp = ((manual.stats["maxMp"] ?: 0) * masteryBonus).toInt(),
                    maxMp = ((manual.stats["maxMp"] ?: 0) * masteryBonus).toInt(),
                    physicalAttack = ((manual.stats["physicalAttack"] ?: 0) * masteryBonus).toInt(),
                    magicAttack = ((manual.stats["magicAttack"] ?: 0) * masteryBonus).toInt(),
                    physicalDefense = ((manual.stats["physicalDefense"] ?: 0) * masteryBonus).toInt(),
                    magicDefense = ((manual.stats["magicDefense"] ?: 0) * masteryBonus).toInt(),
                    speed = ((manual.stats["speed"] ?: 0) * masteryBonus).toInt(),
                    critRate = 0.0
                )
                total = total + manualStats
                totalCritRate += ((manual.stats["critRate"] ?: 0) * masteryBonus) / 100.0
            }
        }

        if (pillEffectDuration > 0) {
            val pillBonus = DiscipleStats(
                hp = (total.maxHp * pillHpBonus).toInt(),
                maxHp = (total.maxHp * pillHpBonus).toInt(),
                mp = (total.maxMp * pillMpBonus).toInt(),
                maxMp = (total.maxMp * pillMpBonus).toInt(),
                physicalAttack = (total.physicalAttack * pillPhysicalAttackBonus).toInt(),
                magicAttack = (total.magicAttack * pillMagicAttackBonus).toInt(),
                physicalDefense = (total.physicalDefense * pillPhysicalDefenseBonus).toInt(),
                magicDefense = (total.magicDefense * pillMagicDefenseBonus).toInt(),
                speed = (total.speed * pillSpeedBonus).toInt(),
                critRate = 0.0
            )
            total = total + pillBonus
        }

        return total.copy(critRate = totalCritRate)
    }

    fun calculateCultivationSpeedPerSecond(
        manuals: Map<String, Manual> = emptyMap(),
        manualProficiencies: Map<String, ManualProficiencyData> = emptyMap()
    ): Double {
        var speed = GameConfig.Cultivation.BASE_SPEED
        
        speed *= spiritRoot.cultivationBonus
        
        speed *= comprehensionSpeedBonus
        
        speed *= cultivationSpeedBonus
        
        manualIds.forEach { manualId ->
            val manual = manuals[manualId]
            if (manual != null) {
                val proficiencyData = manualProficiencies[manualId]
                val masteryLevel = proficiencyData?.masteryLevel ?: 0
                val masteryBonus = ManualProficiencySystem.MasteryLevel.fromLevel(masteryLevel).bonus
                speed *= (1 + manual.cultivationSpeedPercent * masteryBonus / 100.0)
            }
        }
        
        val talents = TalentDatabase.getTalentsByIds(talentIds)
        talents.forEach { talent ->
            speed *= (1 + (talent.effects["cultivationSpeed"] ?: 0.0))
        }
        
        return speed
    }
    
    fun canBreakthrough(): Boolean = cultivation >= maxCultivation
    
    fun getBreakthroughChance(pillBonus: Double = 0.0): Double {
        // 仙人境界无法突破
        if (realm <= 0) return 0.0
        
        // 基础突破率
        var chance = GameConfig.Realm.getBreakthroughChance(realm)
        
        // 所有小境界突破率降低1%
        chance *= 0.99
        
        // 灵根加成 - 根据灵根品质和目标境界调整
        val spiritRootCount = spiritRoot.types.size
        val targetRealm = realm - 1 // 要突破到的境界
        
        if (spiritRootCount >= 4 && targetRealm <= 5) {
            // 四灵根及以下突破到化神境及以上：概率降低40%（仍有60%）
            chance *= 0.6
        } else if (spiritRootCount == 3 && targetRealm <= 2) {
            // 三灵根突破到合体境及以上：概率降低25%（仍有75%）
            chance *= 0.75
        } else {
            // 灵根加成：根据灵根数量和目标境界给予加成
            // 化神及以下（targetRealm >= 5）保持原加成，化神以上（targetRealm < 5）降低加成
            val multiplier = when (spiritRootCount) {
                1 -> if (targetRealm >= 5) 1.5 else 1.2  // 单灵根：化神及以下+50%，化神以上+20%
                2 -> if (targetRealm >= 5) 1.25 else 1.1 // 双灵根：化神及以下+25%，化神以上+10%
                else -> 1.0 // 三灵根及以上无额外加成
            }
            chance *= multiplier
        }
        
        // 丹药加成
        chance += pillBonus

        // 天赋加成
        val talentEffects = getTalentEffects()
        val breakthroughBonus = talentEffects["breakthroughChance"] ?: 0.0
        chance += breakthroughBonus

        // 悟性加成（50为基准，每多5点增加0.5%突破率，每少5点减少0.5%突破率）
        val comprehensionBonus = (comprehension - 50) / 5 * 0.005
        chance += comprehensionBonus

        // 突破失败次数加成（每次失败增加3%）
        chance += breakthroughFailCount * 0.03

        // 限制在1%-100%之间
        return chance.coerceIn(0.01, 1.0)
    }
}

enum class DiscipleStatus {
    IDLE, CULTIVATING, EXPLORING, ALCHEMY, FORGING, FARMING, STUDYING, BATTLE, WORKING, SCOUTING, MINING, REFLECTING;

    val displayName: String get() = when (this) {
        IDLE -> "空闲"
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
                1 -> 4.0
                2 -> 3.0
                3 -> 1.5
                4 -> 1.0
                5 -> 0.8
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
    val breakthroughChance: Double = 0.0,
    val targetRealm: Int = 0,
    val heal: Int = 0,
    val healPercent: Double = 0.0,
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
