package com.xianxia.sect.core.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.CombatSkill
import com.xianxia.sect.core.engine.DamageType

sealed class GameItem {
    abstract val id: String
    abstract val name: String
    abstract val rarity: Int
    abstract val description: String
    
    val rarityColor: String get() = GameConfig.Rarity.getColor(rarity)
    val rarityName: String get() = GameConfig.Rarity.getName(rarity)
}

@Entity(tableName = "equipment")
data class Equipment(
    @PrimaryKey
    override val id: String = java.util.UUID.randomUUID().toString(),
    override val name: String = "",
    override val rarity: Int = 1,
    override val description: String = "",
    
    val slot: EquipmentSlot = EquipmentSlot.WEAPON,
    val physicalAttack: Int = 0,
    val magicAttack: Int = 0,
    val physicalDefense: Int = 0,
    val magicDefense: Int = 0,
    val speed: Int = 0,
    val hp: Int = 0,
    val mp: Int = 0,
    val critChance: Double = 0.0,
    
    val nurtureLevel: Int = 0,
    val nurtureProgress: Int = 0,
    
    var ownerId: String? = null,
    var isEquipped: Boolean = false
) : GameItem() {
    
    val basePrice: Int get() = GameConfig.Rarity.get(rarity).basePrice
    
    val stats: EquipmentStats get() = EquipmentStats(
        physicalAttack = physicalAttack,
        magicAttack = magicAttack,
        physicalDefense = physicalDefense,
        magicDefense = magicDefense,
        speed = speed,
        hp = hp,
        mp = mp
    )
    
    val totalMultiplier: Double
        get() {
            val rarityMult = GameConfig.Rarity.get(rarity).multiplier
            val nurtureMult = getNurtureMultiplier(nurtureLevel)
            return rarityMult * nurtureMult
        }
    
    private fun getNurtureMultiplier(level: Int): Double {
        if (level <= 0) return 1.0
        val maxLevel = 25
        val actualLevel = level.coerceAtMost(maxLevel)
        val totalBonus = actualLevel * (actualLevel + 1) / 2.0 * (3.0 / 325.0)
        return (1.0 + totalBonus).coerceAtMost(4.0)
    }
    
    fun getFinalStats(): EquipmentStats {
        val mult = totalMultiplier
        return EquipmentStats(
            physicalAttack = (physicalAttack * mult).toInt(),
            magicAttack = (magicAttack * mult).toInt(),
            physicalDefense = (physicalDefense * mult).toInt(),
            magicDefense = (magicDefense * mult).toInt(),
            speed = (speed * mult).toInt(),
            hp = (hp * mult).toInt(),
            mp = (mp * mult).toInt()
        )
    }
    
    // 总属性描述
    val totalStatsDescription: String
        get() {
            val finalStats = getFinalStats()
            val stats = mutableListOf<String>()
            if (finalStats.physicalAttack > 0) stats.add("物攻+${finalStats.physicalAttack}")
            if (finalStats.magicAttack > 0) stats.add("法攻+${finalStats.magicAttack}")
            if (finalStats.physicalDefense > 0) stats.add("物防+${finalStats.physicalDefense}")
            if (finalStats.magicDefense > 0) stats.add("法防+${finalStats.magicDefense}")
            if (finalStats.speed > 0) stats.add("速度+${finalStats.speed}")
            if (finalStats.hp > 0) stats.add("生命+${finalStats.hp}")
            if (finalStats.mp > 0) stats.add("灵力+${finalStats.mp}")
            return if (stats.isEmpty()) "无属性" else stats.joinToString(", ")
        }
}

enum class EquipmentSlot {
    WEAPON, ARMOR, BOOTS, ACCESSORY;
    
    val displayName: String get() = when (this) {
        WEAPON -> "武器"
        ARMOR -> "护甲"
        BOOTS -> "靴子"
        ACCESSORY -> "饰品"
    }
}

data class EquipmentStats(
    val physicalAttack: Int = 0,
    val magicAttack: Int = 0,
    val physicalDefense: Int = 0,
    val magicDefense: Int = 0,
    val speed: Int = 0,
    val hp: Int = 0,
    val mp: Int = 0
) {
    operator fun plus(other: EquipmentStats): EquipmentStats {
        return EquipmentStats(
            physicalAttack = physicalAttack + other.physicalAttack,
            magicAttack = magicAttack + other.magicAttack,
            physicalDefense = physicalDefense + other.physicalDefense,
            magicDefense = magicDefense + other.magicDefense,
            speed = speed + other.speed,
            hp = hp + other.hp,
            mp = mp + other.mp
        )
    }
    
    fun toDiscipleStats(): DiscipleStats = DiscipleStats(
        physicalAttack = physicalAttack,
        magicAttack = magicAttack,
        physicalDefense = physicalDefense,
        magicDefense = magicDefense,
        speed = speed,
        hp = hp,
        maxHp = hp,
        mp = mp,
        maxMp = mp
    )
}

@Entity(tableName = "manuals")
data class Manual(
    @PrimaryKey
    override val id: String = java.util.UUID.randomUUID().toString(),
    override val name: String = "",
    override val rarity: Int = 1,
    override val description: String = "",
    
    val type: ManualType = ManualType.MIND,
    val stats: Map<String, Int> = emptyMap(),
    
    val skillName: String? = null,
    val skillDescription: String? = null,
    val skillDamageType: String = "physical",
    val skillHits: Int = 1,
    val skillDamageMultiplier: Double = 1.0,
    val skillCooldown: Int = 3,
    val skillMpCost: Int = 10,
    
    val minRealm: Int = 9,
    
    var ownerId: String? = null,
    var isLearned: Boolean = false
) : GameItem() {
    
    val basePrice: Int get() = GameConfig.Rarity.get(rarity).basePrice
    
    val skill: ManualSkill? get() = skillName?.let {
        ManualSkill(
            name = it,
            description = skillDescription ?: "",
            damageType = if (skillDamageType == "magic") DamageType.MAGIC else DamageType.PHYSICAL,
            hits = skillHits,
            damageMultiplier = skillDamageMultiplier,
            cooldown = skillCooldown,
            mpCost = skillMpCost
        )
    }
    
    val cultivationSpeedPercent: Double
        get() = stats["cultivationSpeedPercent"]?.toDouble() ?: 0.0
}

enum class ManualType {
    ATTACK, DEFENSE, SUPPORT, MOVEMENT, MIND, PRODUCTION;
    
    val displayName: String get() = when (this) {
        ATTACK -> "攻击型"
        DEFENSE -> "防御型"
        SUPPORT -> "辅助型"
        MOVEMENT -> "身法型"
        MIND -> "心法型"
        PRODUCTION -> "功能型"
    }
}

data class ManualSkill(
    val name: String,
    val description: String,
    val damageType: DamageType = DamageType.PHYSICAL,
    val hits: Int = 1,
    val damageMultiplier: Double = 1.0,
    val cooldown: Int = 3,
    val mpCost: Int = 10
) {
    fun toCombatSkill(): CombatSkill = CombatSkill(
        name = name,
        damageType = damageType,
        damageMultiplier = damageMultiplier,
        mpCost = mpCost,
        cooldown = cooldown,
        hits = hits
    )
}

@Entity(tableName = "pills")
data class Pill(
    @PrimaryKey
    override val id: String = java.util.UUID.randomUUID().toString(),
    override val name: String = "",
    override val rarity: Int = 1,
    override val description: String = "",
    
    val category: PillCategory = PillCategory.CULTIVATION,
    val breakthroughChance: Double = 0.0,
    val targetRealm: Int = 0,
    val isAscension: Boolean = false,
    val cultivationSpeed: Double = 1.0,
    val duration: Int = 0,
    val cannotStack: Boolean = false,
    val cultivationPercent: Double = 0.0,
    val skillExpPercent: Double = 0.0,
    val extendLife: Int = 0,
    val physicalAttackPercent: Double = 0.0,
    val magicAttackPercent: Double = 0.0,
    val physicalDefensePercent: Double = 0.0,
    val magicDefensePercent: Double = 0.0,
    val hpPercent: Double = 0.0,
    val mpPercent: Double = 0.0,
    val speedPercent: Double = 0.0,
    val healMaxHpPercent: Double = 0.0,
    val healPercent: Double = 0.0,
    val heal: Int = 0,
    val battleCount: Int = 0,
    val revive: Boolean = false,
    val clearAll: Boolean = false,
    val mpRecoverMaxMpPercent: Double = 0.0,
    
    var quantity: Int = 1
) : GameItem() {
    
    val basePrice: Int get() = (GameConfig.Rarity.get(rarity).basePrice * 0.8).toInt() // 丹药基础价格是功法/装备的80%
    
    val effect: PillEffect get() = PillEffect(
        breakthroughChance = breakthroughChance,
        targetRealm = targetRealm,
        isAscension = isAscension,
        cultivationSpeed = cultivationSpeed,
        duration = duration,
        cannotStack = cannotStack,
        cultivationPercent = cultivationPercent,
        skillExpPercent = skillExpPercent,
        extendLife = extendLife,
        physicalAttackPercent = physicalAttackPercent,
        magicAttackPercent = magicAttackPercent,
        physicalDefensePercent = physicalDefensePercent,
        magicDefensePercent = magicDefensePercent,
        hpPercent = hpPercent,
        mpPercent = mpPercent,
        speedPercent = speedPercent,
        healMaxHpPercent = healMaxHpPercent,
        healPercent = healPercent,
        heal = heal,
        battleCount = battleCount,
        revive = revive,
        clearAll = clearAll,
        mpRecoverMaxMpPercent = mpRecoverMaxMpPercent
    )
}

enum class PillCategory {
    BREAKTHROUGH, CULTIVATION, BATTLE, HEALING;
    
    val displayName: String get() = when (this) {
        BREAKTHROUGH -> "突破丹"
        CULTIVATION -> "修炼丹"
        BATTLE -> "战斗丹"
        HEALING -> "治疗丹"
    }
}

data class PillEffect(
    val breakthroughChance: Double = 0.0,
    val targetRealm: Int = 0,
    val isAscension: Boolean = false,
    val cultivationSpeed: Double = 1.0,
    val duration: Int = 0,
    val cannotStack: Boolean = false,
    val cultivationPercent: Double = 0.0,
    val skillExpPercent: Double = 0.0,
    val extendLife: Int = 0,
    val physicalAttackPercent: Double = 0.0,
    val magicAttackPercent: Double = 0.0,
    val physicalDefensePercent: Double = 0.0,
    val magicDefensePercent: Double = 0.0,
    val hpPercent: Double = 0.0,
    val mpPercent: Double = 0.0,
    val speedPercent: Double = 0.0,
    val healMaxHpPercent: Double = 0.0,
    val healPercent: Double = 0.0,
    val heal: Int = 0,
    val battleCount: Int = 0,
    val revive: Boolean = false,
    val clearAll: Boolean = false,
    val mpRecoverMaxMpPercent: Double = 0.0
)

@Entity(tableName = "materials")
data class Material(
    @PrimaryKey
    override val id: String = java.util.UUID.randomUUID().toString(),
    override val name: String = "",
    override val rarity: Int = 1,
    override val description: String = "",
    
    val category: MaterialCategory = MaterialCategory.BEAST_HIDE,
    var quantity: Int = 1
) : GameItem() {
    
    val basePrice: Int get() = (GameConfig.Rarity.get(rarity).basePrice * 0.05).toInt()
}

enum class MaterialCategory {
    BEAST_HIDE,
    BEAST_BONE,
    BEAST_TOOTH,
    BEAST_CORE,
    BEAST_CLAW,
    BEAST_FEATHER,
    BEAST_TAIL,
    BEAST_SCALE,
    BEAST_HORN,
    BEAST_SHELL,
    BEAST_PLASTRON;

    val displayName: String get() = when (this) {
        BEAST_HIDE -> "兽皮"
        BEAST_BONE -> "兽骨"
        BEAST_TOOTH -> "兽牙"
        BEAST_CORE -> "内丹"
        BEAST_CLAW -> "兽爪"
        BEAST_FEATHER -> "兽羽"
        BEAST_TAIL -> "兽尾"
        BEAST_SCALE -> "鳞片"
        BEAST_HORN -> "兽角"
        BEAST_SHELL -> "龟壳"
        BEAST_PLASTRON -> "龟甲"
    }
}

@Entity(tableName = "herbs")
data class Herb(
    @PrimaryKey
    override val id: String = java.util.UUID.randomUUID().toString(),
    override val name: String = "",
    override val rarity: Int = 1,
    override val description: String = "",

    val category: String = "",
    var quantity: Int = 1
) : GameItem() {
    
    val basePrice: Int get() = (GameConfig.Rarity.get(rarity).basePrice * 0.05).toInt()
}

@Entity(tableName = "seeds")
data class Seed(
    @PrimaryKey
    override val id: String = java.util.UUID.randomUUID().toString(),
    override val name: String = "",
    override val rarity: Int = 1,
    override val description: String = "",
    
    val growTime: Int = 3,
    val yield: Int = 1,
    var quantity: Int = 1
) : GameItem() {
    
    val basePrice: Int get() = (GameConfig.Rarity.get(rarity).basePrice * 0.05).toInt()
}
