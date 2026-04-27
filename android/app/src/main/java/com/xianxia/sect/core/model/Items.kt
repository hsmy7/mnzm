package com.xianxia.sect.core.model

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.HealType
import com.xianxia.sect.core.SkillType
import com.xianxia.sect.core.engine.CombatSkill
import com.xianxia.sect.core.util.StackableItem
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

sealed class GameItem {
    abstract val id: String
    abstract val name: String
    abstract val rarity: Int
    abstract val description: String
    
    val rarityColor: String get() = GameConfig.Rarity.getColor(rarity)
    val rarityName: String get() = GameConfig.Rarity.getName(rarity)
}

@Serializable
@Entity(
    tableName = "equipment_stacks",
    primaryKeys = ["id", "slot_id"],
    indices = [
        Index(value = ["name"]),
        Index(value = ["rarity"]),
        Index(value = ["slot"]),
        Index(value = ["rarity", "slot"]),
        Index(value = ["minRealm"])
    ]
)
data class EquipmentStack(
    @ColumnInfo(name = "id")
    override val id: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(name = "slot_id")
    var slotId: Int = 0,

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

    val minRealm: Int = 9,

    override var quantity: Int = 1,
    override val isLocked: Boolean = false
) : GameItem(), StackableItem {

    override fun withQuantity(newQuantity: Int): EquipmentStack = copy(quantity = newQuantity)

    val basePrice: Int get() = (GameConfig.Rarity.get(rarity).basePrice * GameConfig.Rarity.PRICE_MULTIPLIER).roundToInt()

    val stats: EquipmentStats get() = EquipmentStats(
        physicalAttack = physicalAttack,
        magicAttack = magicAttack,
        physicalDefense = physicalDefense,
        magicDefense = magicDefense,
        speed = speed,
        hp = hp,
        mp = mp
    )

    fun toInstance(id: String = java.util.UUID.randomUUID().toString(), ownerId: String? = null, isEquipped: Boolean = true): EquipmentInstance = EquipmentInstance(
        id = id,
        slotId = slotId,
        name = name,
        rarity = rarity,
        description = description,
        slot = slot,
        physicalAttack = physicalAttack,
        magicAttack = magicAttack,
        physicalDefense = physicalDefense,
        magicDefense = magicDefense,
        speed = speed,
        hp = hp,
        mp = mp,
        critChance = critChance,
        minRealm = minRealm,
        ownerId = ownerId,
        isEquipped = isEquipped
    )
}

@Serializable
@Entity(
    tableName = "equipment_instances",
    primaryKeys = ["id", "slot_id"],
    indices = [
        Index(value = ["name"]),
        Index(value = ["rarity"]),
        Index(value = ["slot"]),
        Index(value = ["ownerId"]),
        Index(value = ["rarity", "slot"]),
        Index(value = ["minRealm"])
    ]
)
data class EquipmentInstance(
    @ColumnInfo(name = "id")
    override val id: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(name = "slot_id")
    var slotId: Int = 0,

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
    val nurtureProgress: Double = 0.0,

    val minRealm: Int = 9,

    val ownerId: String? = null,
    val isEquipped: Boolean = false
) : GameItem() {

    val basePrice: Int get() = (GameConfig.Rarity.get(rarity).basePrice * GameConfig.Rarity.PRICE_MULTIPLIER).roundToInt()

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
            val nurtureMult = getNurtureMultiplier(nurtureLevel)
            return nurtureMult
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

    fun toStack(quantity: Int = 1): EquipmentStack = EquipmentStack(
        id = java.util.UUID.randomUUID().toString(),
        slotId = slotId,
        name = name,
        rarity = rarity,
        description = description,
        slot = slot,
        physicalAttack = physicalAttack,
        magicAttack = magicAttack,
        physicalDefense = physicalDefense,
        magicDefense = magicDefense,
        speed = speed,
        hp = hp,
        mp = mp,
        critChance = critChance,
        minRealm = minRealm,
        quantity = quantity
    )
}

@Serializable
enum class EquipmentSlot {
    WEAPON, ARMOR, BOOTS, ACCESSORY;
    
    val displayName: String get() = when (this) {
        WEAPON -> "武器"
        ARMOR -> "护甲"
        BOOTS -> "靴子"
        ACCESSORY -> "饰品"
    }
}

@Serializable
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

@Serializable
@Entity(
    tableName = "manual_stacks",
    primaryKeys = ["id", "slot_id"],
    indices = [
        Index(value = ["name"]),
        Index(value = ["rarity"]),
        Index(value = ["type"]),
        Index(value = ["rarity", "type"]),
        Index(value = ["minRealm"])
    ]
)
data class ManualStack(
    @ColumnInfo(name = "id")
    override val id: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(name = "slot_id")
    var slotId: Int = 0,

    override val name: String = "",
    override val rarity: Int = 1,
    override val description: String = "",

    val type: ManualType = ManualType.MIND,
    val stats: Map<String, Int> = emptyMap(),

    val skillName: String? = null,
    val skillDescription: String? = null,
    val skillType: String = "attack",
    val skillDamageType: String = "physical",
    val skillHits: Int = 1,
    val skillDamageMultiplier: Double = 1.0,
    val skillCooldown: Int = 3,
    val skillMpCost: Int = 10,
    val skillHealPercent: Double = 0.0,
    val skillHealType: String = "hp",
    val skillBuffType: String? = null,
    val skillBuffValue: Double = 0.0,
    val skillBuffDuration: Int = 0,
    val skillBuffsJson: String = "",
    val skillIsAoe: Boolean = false,
    val skillTargetScope: String = "self",

    val minRealm: Int = 9,

    override var quantity: Int = 1,
    override val isLocked: Boolean = false
) : GameItem(), StackableItem {

    override fun withQuantity(newQuantity: Int): ManualStack = copy(quantity = newQuantity)

    val basePrice: Int get() = (GameConfig.Rarity.get(rarity).basePrice * GameConfig.Rarity.PRICE_MULTIPLIER).roundToInt()

    fun toInstance(id: String = java.util.UUID.randomUUID().toString(), ownerId: String? = null, isLearned: Boolean = true): ManualInstance = ManualInstance(
        id = id,
        slotId = slotId,
        name = name,
        rarity = rarity,
        description = description,
        type = type,
        stats = stats,
        skillName = skillName,
        skillDescription = skillDescription,
        skillType = skillType,
        skillDamageType = skillDamageType,
        skillHits = skillHits,
        skillDamageMultiplier = skillDamageMultiplier,
        skillCooldown = skillCooldown,
        skillMpCost = skillMpCost,
        skillHealPercent = skillHealPercent,
        skillHealType = skillHealType,
        skillBuffType = skillBuffType,
        skillBuffValue = skillBuffValue,
        skillBuffDuration = skillBuffDuration,
        skillBuffsJson = skillBuffsJson,
        skillIsAoe = skillIsAoe,
        skillTargetScope = skillTargetScope,
        minRealm = minRealm,
        ownerId = ownerId,
        isLearned = isLearned
    )
}

@Serializable
@Entity(
    tableName = "manual_instances",
    primaryKeys = ["id", "slot_id"],
    indices = [
        Index(value = ["name"]),
        Index(value = ["rarity"]),
        Index(value = ["type"]),
        Index(value = ["ownerId"]),
        Index(value = ["minRealm"]),
        Index(value = ["rarity", "type"])
    ]
)
data class ManualInstance(
    @ColumnInfo(name = "id")
    override val id: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(name = "slot_id")
    var slotId: Int = 0,

    override val name: String = "",
    override val rarity: Int = 1,
    override val description: String = "",

    val type: ManualType = ManualType.MIND,
    val stats: Map<String, Int> = emptyMap(),

    val skillName: String? = null,
    val skillDescription: String? = null,
    val skillType: String = "attack",
    val skillDamageType: String = "physical",
    val skillHits: Int = 1,
    val skillDamageMultiplier: Double = 1.0,
    val skillCooldown: Int = 3,
    val skillMpCost: Int = 10,
    val skillHealPercent: Double = 0.0,
    val skillHealType: String = "hp",
    val skillBuffType: String? = null,
    val skillBuffValue: Double = 0.0,
    val skillBuffDuration: Int = 0,
    val skillBuffsJson: String = "",
    val skillIsAoe: Boolean = false,
    val skillTargetScope: String = "self",

    val minRealm: Int = 9,

    val ownerId: String? = null,
    val isLearned: Boolean = false
) : GameItem() {

    val basePrice: Int get() = (GameConfig.Rarity.get(rarity).basePrice * GameConfig.Rarity.PRICE_MULTIPLIER).roundToInt()

    private fun parseBuffType(bt: String): BuffType? = when (bt) {
        "physical_attack" -> BuffType.PHYSICAL_ATTACK_BOOST
        "magic_attack" -> BuffType.MAGIC_ATTACK_BOOST
        "physical_defense" -> BuffType.PHYSICAL_DEFENSE_BOOST
        "magic_defense" -> BuffType.MAGIC_DEFENSE_BOOST
        "hp" -> BuffType.HP_BOOST
        "mp" -> BuffType.MP_BOOST
        "speed" -> BuffType.SPEED_BOOST
        "crit_rate" -> BuffType.CRIT_RATE_BOOST
        "physical_attack_reduce" -> BuffType.PHYSICAL_ATTACK_REDUCE
        "magic_attack_reduce" -> BuffType.MAGIC_ATTACK_REDUCE
        "physical_defense_reduce" -> BuffType.PHYSICAL_DEFENSE_REDUCE
        "magic_defense_reduce" -> BuffType.MAGIC_DEFENSE_REDUCE
        "speed_reduce" -> BuffType.SPEED_REDUCE
        "crit_rate_reduce" -> BuffType.CRIT_RATE_REDUCE
        "poison" -> BuffType.POISON
        "burn" -> BuffType.BURN
        "stun" -> BuffType.STUN
        "freeze" -> BuffType.FREEZE
        "silence" -> BuffType.SILENCE
        "taunt" -> BuffType.TAUNT
        else -> null
    }

    private fun parseBuffsJson(json: String): List<Triple<BuffType, Double, Int>> {
        if (json.isBlank()) return emptyList()
        return json.split("|").mapNotNull { buffStr ->
            val parts = buffStr.split(",")
            if (parts.size == 3) {
                val type = parseBuffType(parts[0]) ?: return@mapNotNull null
                val value = parts[1].toDoubleOrNull() ?: return@mapNotNull null
                val duration = parts[2].toIntOrNull() ?: return@mapNotNull null
                Triple(type, value, duration)
            } else null
        }
    }

    val skill: ManualSkill? get() = skillName?.let {
        val buffs = parseBuffsJson(skillBuffsJson)
        ManualSkill(
            name = it,
            description = skillDescription ?: "",
            skillType = if (skillType == "support") SkillType.SUPPORT else SkillType.ATTACK,
            damageType = if (skillDamageType == "magic") DamageType.MAGIC else DamageType.PHYSICAL,
            hits = skillHits,
            damageMultiplier = skillDamageMultiplier,
            cooldown = skillCooldown,
            mpCost = skillMpCost,
            healPercent = skillHealPercent,
            healType = if (skillHealType == "mp") HealType.MP else HealType.HP,
            buffType = skillBuffType?.let { bt -> parseBuffType(bt) },
            buffValue = skillBuffValue,
            buffDuration = skillBuffDuration,
            buffs = buffs,
            isAoe = skillIsAoe,
            targetScope = skillTargetScope
        )
    }

    val cultivationSpeedPercent: Double
        get() = stats["cultivationSpeedPercent"]?.toDouble() ?: 0.0

    fun toStack(quantity: Int = 1): ManualStack = ManualStack(
        id = java.util.UUID.randomUUID().toString(),
        slotId = slotId,
        name = name,
        rarity = rarity,
        description = description,
        type = type,
        stats = stats,
        skillName = skillName,
        skillDescription = skillDescription,
        skillType = skillType,
        skillDamageType = skillDamageType,
        skillHits = skillHits,
        skillDamageMultiplier = skillDamageMultiplier,
        skillCooldown = skillCooldown,
        skillMpCost = skillMpCost,
        skillHealPercent = skillHealPercent,
        skillHealType = skillHealType,
        skillBuffType = skillBuffType,
        skillBuffValue = skillBuffValue,
        skillBuffDuration = skillBuffDuration,
        skillBuffsJson = skillBuffsJson,
        skillIsAoe = skillIsAoe,
        skillTargetScope = skillTargetScope,
        minRealm = minRealm,
        quantity = quantity
    )
}

@Serializable
enum class ManualType {
    ATTACK, DEFENSE, SUPPORT, MIND;
    
    val displayName: String get() = when (this) {
        ATTACK -> "攻击型"
        DEFENSE -> "防御型"
        SUPPORT -> "辅助型"
        MIND -> "心法型"
    }
}

@Serializable
data class ManualSkill(
    val name: String,
    val description: String,
    val skillType: SkillType = SkillType.ATTACK,
    val damageType: DamageType = DamageType.PHYSICAL,
    val hits: Int = 1,
    val damageMultiplier: Double = 1.0,
    val cooldown: Int = 3,
    val mpCost: Int = 10,
    val healPercent: Double = 0.0,
    val healType: HealType = HealType.HP,
    val buffType: BuffType? = null,
    val buffValue: Double = 0.0,
    val buffDuration: Int = 0,
    val buffs: List<Triple<BuffType, Double, Int>> = emptyList(),
    val isAoe: Boolean = false,
    val targetScope: String = "self"
) {
    fun toCombatSkill(manualName: String = ""): CombatSkill = CombatSkill(
        name = name,
        skillType = skillType,
        damageType = damageType,
        damageMultiplier = damageMultiplier,
        mpCost = mpCost,
        cooldown = cooldown,
        hits = hits,
        healPercent = healPercent,
        healType = healType,
        buffType = buffType,
        buffValue = buffValue,
        buffDuration = buffDuration,
        buffs = buffs,
        isAoe = isAoe,
        targetScope = targetScope,
        skillDescription = description,
        manualName = manualName
    )
}

@Serializable
@Entity(
    tableName = "pills",
    primaryKeys = ["id", "slot_id"],
    indices = [
        Index(value = ["name"]),
        Index(value = ["rarity"]),
        Index(value = ["category"]),
        Index(value = ["targetRealm"]),
        Index(value = ["rarity", "category"])
    ]
)
data class Pill(
    @ColumnInfo(name = "id")
    override val id: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(name = "slot_id")
    var slotId: Int = 0,

    override val name: String = "",
    override val rarity: Int = 1,
    override val description: String = "",
    
    val category: PillCategory = PillCategory.CULTIVATION,
    val grade: PillGrade = PillGrade.MEDIUM,
    val pillType: String = "",

    @Embedded
    val effects: PillEffect = PillEffect(),

    @ColumnInfo(name = "minRealm", defaultValue = "9")
    val minRealm: Int = 9,
    
    override var quantity: Int = 1,
    override val isLocked: Boolean = false
) : GameItem(), StackableItem {
    
    override fun withQuantity(newQuantity: Int): Pill = copy(quantity = newQuantity)
    
    val basePrice: Int get() = (GameConfig.Rarity.get(rarity).pillBasePrice * grade.priceMultiplier * GameConfig.Rarity.PRICE_MULTIPLIER).roundToInt()
    
    val breakthroughChance: Double get() = effects.breakthroughChance
    val targetRealm: Int get() = effects.targetRealm
    val isAscension: Boolean get() = effects.isAscension
    val cultivationSpeedPercent: Double get() = effects.cultivationSpeedPercent
    val skillExpSpeedPercent: Double get() = effects.skillExpSpeedPercent
    val nurtureSpeedPercent: Double get() = effects.nurtureSpeedPercent
    val cultivationAdd: Int get() = effects.cultivationAdd
    val skillExpAdd: Int get() = effects.skillExpAdd
    val nurtureAdd: Int get() = effects.nurtureAdd
    val duration: Int get() = effects.duration
    val cannotStack: Boolean get() = effects.cannotStack
    val physicalAttackAdd: Int get() = effects.physicalAttackAdd
    val magicAttackAdd: Int get() = effects.magicAttackAdd
    val physicalDefenseAdd: Int get() = effects.physicalDefenseAdd
    val magicDefenseAdd: Int get() = effects.magicDefenseAdd
    val hpAdd: Int get() = effects.hpAdd
    val mpAdd: Int get() = effects.mpAdd
    val speedAdd: Int get() = effects.speedAdd
    val critRateAdd: Double get() = effects.critRateAdd
    val critEffectAdd: Double get() = effects.critEffectAdd
    val extendLife: Int get() = effects.extendLife
    val intelligenceAdd: Int get() = effects.intelligenceAdd
    val charmAdd: Int get() = effects.charmAdd
    val loyaltyAdd: Int get() = effects.loyaltyAdd
    val comprehensionAdd: Int get() = effects.comprehensionAdd
    val artifactRefiningAdd: Int get() = effects.artifactRefiningAdd
    val pillRefiningAdd: Int get() = effects.pillRefiningAdd
    val spiritPlantingAdd: Int get() = effects.spiritPlantingAdd
    val teachingAdd: Int get() = effects.teachingAdd
    val moralityAdd: Int get() = effects.moralityAdd
    val healMaxHpPercent: Double get() = effects.healMaxHpPercent
    val mpRecoverMaxMpPercent: Double get() = effects.mpRecoverMaxMpPercent
    val revive: Boolean get() = effects.revive
    val clearAll: Boolean get() = effects.clearAll
}

@Serializable
enum class PillCategory {
    CULTIVATION, BATTLE, FUNCTIONAL;

    val displayName: String get() = when (this) {
        CULTIVATION -> "修炼丹药"
        BATTLE -> "战斗丹药"
        FUNCTIONAL -> "功能丹药"
    }
}

@Serializable
enum class PillGrade {
    LOW, MEDIUM, HIGH;

    val displayName: String get() = when (this) {
        LOW -> "下品"
        MEDIUM -> "中品"
        HIGH -> "上品"
    }

    val multiplier: Double get() = when (this) {
        LOW -> 0.5
        MEDIUM -> 1.0
        HIGH -> 2.0
    }

    val priceMultiplier: Double get() = when (this) {
        LOW -> 0.5
        MEDIUM -> 1.0
        HIGH -> 2.0
    }

    companion object {
        fun random(): PillGrade {
            val roll = kotlin.random.Random.nextDouble()
            return when {
                roll < 0.06 -> HIGH
                roll < 0.40 -> MEDIUM
                else -> LOW
            }
        }
    }
}

@Serializable
data class PillEffect(
    val breakthroughChance: Double = 0.0,
    val targetRealm: Int = 0,
    val isAscension: Boolean = false,
    val cultivationSpeedPercent: Double = 0.0,
    val skillExpSpeedPercent: Double = 0.0,
    val nurtureSpeedPercent: Double = 0.0,
    val cultivationAdd: Int = 0,
    val skillExpAdd: Int = 0,
    val nurtureAdd: Int = 0,
    val duration: Int = 3,
    val cannotStack: Boolean = true,
    val physicalAttackAdd: Int = 0,
    val magicAttackAdd: Int = 0,
    val physicalDefenseAdd: Int = 0,
    val magicDefenseAdd: Int = 0,
    val hpAdd: Int = 0,
    val mpAdd: Int = 0,
    val speedAdd: Int = 0,
    val critRateAdd: Double = 0.0,
    val critEffectAdd: Double = 0.0,
    val extendLife: Int = 0,
    val intelligenceAdd: Int = 0,
    val charmAdd: Int = 0,
    val loyaltyAdd: Int = 0,
    val comprehensionAdd: Int = 0,
    val artifactRefiningAdd: Int = 0,
    val pillRefiningAdd: Int = 0,
    val spiritPlantingAdd: Int = 0,
    val teachingAdd: Int = 0,
    val moralityAdd: Int = 0,
    val healMaxHpPercent: Double = 0.0,
    val mpRecoverMaxMpPercent: Double = 0.0,
    val revive: Boolean = false,
    val clearAll: Boolean = false
)

@Serializable
@Entity(
    tableName = "materials",
    primaryKeys = ["id", "slot_id"],
    indices = [
        Index(value = ["name"]),
        Index(value = ["rarity"]),
        Index(value = ["category"]),
        Index(value = ["rarity", "category"])
    ]
)
data class Material(
    @ColumnInfo(name = "id")
    override val id: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(name = "slot_id")
    var slotId: Int = 0,

    override val name: String = "",
    override val rarity: Int = 1,
    override val description: String = "",
    
    val category: MaterialCategory = MaterialCategory.BEAST_HIDE,
    override var quantity: Int = 1,
    override val isLocked: Boolean = false
) : GameItem(), StackableItem {
    
    override fun withQuantity(newQuantity: Int): Material = copy(quantity = newQuantity)
    
    val basePrice: Int get() = (GameConfig.Rarity.get(rarity).materialBasePrice * GameConfig.Rarity.PRICE_MULTIPLIER).roundToInt()
}

@Serializable
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

@Serializable
@Entity(
    tableName = "herbs",
    primaryKeys = ["id", "slot_id"],
    indices = [
        Index(value = ["name"]),
        Index(value = ["rarity"]),
        Index(value = ["category"]),
        Index(value = ["rarity", "category"])
    ]
)
data class Herb(
    @ColumnInfo(name = "id")
    override val id: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(name = "slot_id")
    var slotId: Int = 0,

    override val name: String = "",
    override val rarity: Int = 1,
    override val description: String = "",

    val category: String = "",
    override var quantity: Int = 1,
    override val isLocked: Boolean = false
) : GameItem(), StackableItem {
    
    override fun withQuantity(newQuantity: Int): Herb = copy(quantity = newQuantity)
    
    val basePrice: Int get() = (GameConfig.Rarity.get(rarity).materialBasePrice * GameConfig.Rarity.PRICE_MULTIPLIER).roundToInt()
}

@Serializable
@Entity(
    tableName = "seeds",
    primaryKeys = ["id", "slot_id"],
    indices = [
        Index(value = ["name"]),
        Index(value = ["rarity"]),
        Index(value = ["growTime"])
    ]
)
data class Seed(
    @ColumnInfo(name = "id")
    override val id: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(name = "slot_id")
    var slotId: Int = 0,

    override val name: String = "",
    override val rarity: Int = 1,
    override val description: String = "",
    
    val growTime: Int = 3,
    val yield: Int = 1,
    override var quantity: Int = 1,
    override val isLocked: Boolean = false
) : GameItem(), StackableItem {
    
    override fun withQuantity(newQuantity: Int): Seed = copy(quantity = newQuantity)
    
    val basePrice: Int get() = (GameConfig.Rarity.get(rarity).materialBasePrice * GameConfig.Rarity.PRICE_MULTIPLIER).roundToInt()
}
