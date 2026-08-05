package com.xianxia.sect.core.model

import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.HealType
import com.xianxia.sect.core.SkillType
import com.xianxia.sect.core.util.StackableItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.math.roundToInt

sealed class GameItem : HasId {
    override abstract val id: String
    abstract val name: String
    abstract val rarity: Int
    abstract val description: String

    val rarityColor: String get() = GameConfig.Rarity.getColor(rarity)
    val rarityName: String get() = GameConfig.Rarity.getName(rarity)
}

@Keep
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
@Immutable
data class EquipmentStack(
    @ColumnInfo(name = "id")
    @ProtoNumber(1)
    override val id: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(name = "slot_id")
    @ProtoNumber(100)
    var slotId: Int = 0,

    @ProtoNumber(2)
    override val name: String = "",
    @ProtoNumber(4)
    override val rarity: Int = 1,
    @ProtoNumber(7)
    override val description: String = "",

    @ProtoNumber(3)
    val slot: EquipmentSlot = EquipmentSlot.WEAPON,
    @ProtoNumber(50)
    val physicalAttack: Int = 0,
    @ProtoNumber(51)
    val magicAttack: Int = 0,
    @ProtoNumber(52)
    val physicalDefense: Int = 0,
    @ProtoNumber(53)
    val magicDefense: Int = 0,
    @ProtoNumber(54)
    val speed: Int = 0,
    @ProtoNumber(55)
    val hp: Int = 0,
    @ProtoNumber(56)
    val mp: Int = 0,
    @ProtoNumber(10)
    val critChance: Double = 0.0,

    @ProtoNumber(15)
    val minRealm: Int = 9,

    @ProtoNumber(17)
    override var quantity: Int = 1,
    @ProtoNumber(101)
    override val isLocked: Boolean = false
) : GameItem(), StackableItem {

    override fun withQuantity(newQuantity: Int): EquipmentStack = copy(quantity = newQuantity)

    val basePrice: Int get() = EquipmentDatabase.getTemplateByName(name)?.price
        ?: GameConfig.Rarity.get(rarity).basePrice

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

@Keep
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
    @ProtoNumber(1)
    override val id: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(name = "slot_id")
    @ProtoNumber(100)
    var slotId: Int = 0,

    @ProtoNumber(2)
    override val name: String = "",
    @ProtoNumber(4)
    override val rarity: Int = 1,
    @ProtoNumber(7)
    override val description: String = "",

    @ProtoNumber(3)
    val slot: EquipmentSlot = EquipmentSlot.WEAPON,
    @ProtoNumber(50)
    val physicalAttack: Int = 0,
    @ProtoNumber(51)
    val magicAttack: Int = 0,
    @ProtoNumber(52)
    val physicalDefense: Int = 0,
    @ProtoNumber(53)
    val magicDefense: Int = 0,
    @ProtoNumber(54)
    val speed: Int = 0,
    @ProtoNumber(55)
    val hp: Int = 0,
    @ProtoNumber(56)
    val mp: Int = 0,
    @ProtoNumber(10)
    val critChance: Double = 0.0,

    @ProtoNumber(13)
    val nurtureLevel: Int = 0,
    @ProtoNumber(14)
    val nurtureProgress: Double = 0.0,

    @ProtoNumber(15)
    val minRealm: Int = 9,

    @ProtoNumber(16)
    val ownerId: String? = null,
    @ProtoNumber(11)
    val isEquipped: Boolean = false
) : GameItem() {

    val basePrice: Int get() = EquipmentDatabase.getTemplateByName(name)?.price
        ?: GameConfig.Rarity.get(rarity).basePrice

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

    fun getFinalStats(): EquipmentStats = cachedFinalStats(this)

    private fun computeFinalStats(): EquipmentStats {
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

    companion object {
        /**
         * C2（P1-C）：装备最终属性缓存。
         *
         * 键语义：EquipmentInstance 是不可变 COW data class（全字段不可变，
         * 唯一 var slotId 实际均经 copy 创建新实例）——内容即版本，值语义键
         * （data class equals/hashCode）使"同内容不同实例"共享缓存且永不需要
         * 失效逻辑（引用即指纹，对标 COW 架构的既有 disciplePowerCache 模式）。
         * 纯函数结果缓存：幂等，跨测试/跨线程（ConcurrentHashMap）安全。
         * 容量护栏：装备实例变更次数无界增长时清空重建（触发罕见，摊还 O(1)）。
         */
        private const val FINAL_STATS_CACHE_LIMIT = 4096
        private val finalStatsCache =
            java.util.concurrent.ConcurrentHashMap<EquipmentInstance, EquipmentStats>()

        private fun cachedFinalStats(instance: EquipmentInstance): EquipmentStats {
            finalStatsCache[instance]?.let { return it }
            val stats = instance.computeFinalStats()
            if (finalStatsCache.size >= FINAL_STATS_CACHE_LIMIT) {
                finalStatsCache.clear()
            }
            finalStatsCache[instance] = stats
            return stats
        }
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

@Keep
@Serializable
enum class EquipmentSlot {
    @ProtoNumber(0) WEAPON,
    @ProtoNumber(1) ARMOR,
    @ProtoNumber(2) BOOTS,
    @ProtoNumber(3) ACCESSORY;

    val displayName: String get() = when (this) {
        WEAPON -> "武器"
        ARMOR -> "护甲"
        BOOTS -> "靴子"
        ACCESSORY -> "饰品"
    }
}

@Keep
@Serializable
data class EquipmentStats(
    @ProtoNumber(1) val physicalAttack: Int = 0,
    @ProtoNumber(2) val magicAttack: Int = 0,
    @ProtoNumber(3) val physicalDefense: Int = 0,
    @ProtoNumber(4) val magicDefense: Int = 0,
    @ProtoNumber(5) val speed: Int = 0,
    @ProtoNumber(6) val hp: Int = 0,
    @ProtoNumber(7) val mp: Int = 0
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

@Keep
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
@Immutable
data class ManualStack(
    @ColumnInfo(name = "id")
    @ProtoNumber(1)
    override val id: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(name = "slot_id")
    @ProtoNumber(100)
    var slotId: Int = 0,

    @ProtoNumber(2)
    override val name: String = "",
    @ProtoNumber(4)
    override val rarity: Int = 1,
    @ProtoNumber(6)
    override val description: String = "",

    @ProtoNumber(3)
    val type: ManualType = ManualType.MIND,
    @ProtoNumber(5)
    val stats: Map<String, Int> = emptyMap(),

    @ProtoNumber(10)
    val skillName: String? = null,
    @ProtoNumber(11)
    val skillDescription: String? = null,
    @ProtoNumber(12)
    val skillType: String = "attack",
    @ProtoNumber(13)
    val skillDamageType: String = "physical",
    @ProtoNumber(14)
    val skillHits: Int = 1,
    @ProtoNumber(15)
    val skillDamageMultiplier: Double = 1.0,
    @ProtoNumber(16)
    val skillCooldown: Int = 3,
    @ProtoNumber(17)
    val skillMpCost: Int = 10,
    @ProtoNumber(18)
    val skillHealPercent: Double = 0.0,
    @ProtoNumber(19)
    val skillHealFixed: Int = 0,
    @ProtoNumber(20)
    val skillHealType: String = "hp",
    @ProtoNumber(21)
    val skillBuffType: String? = null,
    @ProtoNumber(22)
    val skillBuffValue: Double = 0.0,
    @ProtoNumber(23)
    val skillBuffDuration: Int = 0,
    @ProtoNumber(24)
    val skillBuffsJson: String = "",
    @ProtoNumber(25)
    val skillIsAoe: Boolean = false,
    @ProtoNumber(26)
    val skillTargetScope: String = "self",
    @ProtoNumber(27)
    val skillShieldPercent: Double = 0.0,
    @ProtoNumber(28)
    val skillTurnAdvancePercent: Double = 0.0,
    @ProtoNumber(29)
    val skillDamageSharePercent: Double = 0.0,
    @ProtoNumber(30)
    val skillDamageLinkPercent: Double = 0.0,

    @ProtoNumber(31)
    val minRealm: Int = 9,

    @ProtoNumber(101)
    override var quantity: Int = 1,
    @ProtoNumber(102)
    override val isLocked: Boolean = false
) : GameItem(), StackableItem {

    override fun withQuantity(newQuantity: Int): ManualStack = copy(quantity = newQuantity)

    val basePrice: Int get() = GameConfig.Rarity.get(rarity).basePrice

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
        skillHealFixed = skillHealFixed,
        skillHealType = skillHealType,
        skillBuffType = skillBuffType,
        skillBuffValue = skillBuffValue,
        skillBuffDuration = skillBuffDuration,
        skillBuffsJson = skillBuffsJson,
        skillIsAoe = skillIsAoe,
        skillTargetScope = skillTargetScope,
        skillShieldPercent = skillShieldPercent,
        skillTurnAdvancePercent = skillTurnAdvancePercent,
        skillDamageSharePercent = skillDamageSharePercent,
        skillDamageLinkPercent = skillDamageLinkPercent,
        minRealm = minRealm,
        ownerId = ownerId,
        isLearned = isLearned
    )
}

@Keep
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
    @ProtoNumber(1)
    override val id: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(name = "slot_id")
    @ProtoNumber(100)
    var slotId: Int = 0,

    @ProtoNumber(2)
    override val name: String = "",
    @ProtoNumber(4)
    override val rarity: Int = 1,
    @ProtoNumber(6)
    override val description: String = "",

    @ProtoNumber(3)
    val type: ManualType = ManualType.MIND,
    @ProtoNumber(5)
    val stats: Map<String, Int> = emptyMap(),

    @ProtoNumber(10)
    val skillName: String? = null,
    @ProtoNumber(11)
    val skillDescription: String? = null,
    @ProtoNumber(12)
    val skillType: String = "attack",
    @ProtoNumber(13)
    val skillDamageType: String = "physical",
    @ProtoNumber(14)
    val skillHits: Int = 1,
    @ProtoNumber(15)
    val skillDamageMultiplier: Double = 1.0,
    @ProtoNumber(16)
    val skillCooldown: Int = 3,
    @ProtoNumber(17)
    val skillMpCost: Int = 10,
    @ProtoNumber(18)
    val skillHealPercent: Double = 0.0,
    @ProtoNumber(19)
    val skillHealFixed: Int = 0,
    @ProtoNumber(20)
    val skillHealType: String = "hp",
    @ProtoNumber(21)
    val skillBuffType: String? = null,
    @ProtoNumber(22)
    val skillBuffValue: Double = 0.0,
    @ProtoNumber(23)
    val skillBuffDuration: Int = 0,
    @ProtoNumber(24)
    val skillBuffsJson: String = "",
    @ProtoNumber(25)
    val skillIsAoe: Boolean = false,
    @ProtoNumber(26)
    val skillTargetScope: String = "self",
    @ProtoNumber(27)
    val skillShieldPercent: Double = 0.0,
    @ProtoNumber(28)
    val skillTurnAdvancePercent: Double = 0.0,
    @ProtoNumber(29)
    val skillDamageSharePercent: Double = 0.0,
    @ProtoNumber(30)
    val skillDamageLinkPercent: Double = 0.0,

    @ProtoNumber(31)
    val minRealm: Int = 9,

    @ProtoNumber(32)
    val ownerId: String? = null,
    @ProtoNumber(33)
    val isLearned: Boolean = false
) : GameItem() {

    val basePrice: Int get() = GameConfig.Rarity.get(rarity).basePrice

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
        "damage_boost" -> BuffType.DAMAGE_BOOST
        "damage_reduction" -> BuffType.DAMAGE_REDUCTION
        "shield" -> BuffType.SHIELD
        "damage_share" -> BuffType.DAMAGE_SHARE
        "damage_link" -> BuffType.DAMAGE_LINK
        "turn_advance" -> BuffType.TURN_ADVANCE
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
            healFixed = skillHealFixed,
            healType = if (skillHealType == "mp") HealType.MP else HealType.HP,
            buffType = skillBuffType?.let { bt -> parseBuffType(bt) },
            buffValue = skillBuffValue,
            buffDuration = skillBuffDuration,
            buffs = buffs,
            isAoe = skillIsAoe,
            targetScope = skillTargetScope,
            shieldPercent = skillShieldPercent,
            turnAdvancePercent = skillTurnAdvancePercent,
            damageSharePercent = skillDamageSharePercent,
            damageLinkPercent = skillDamageLinkPercent
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
        skillHealFixed = skillHealFixed,
        skillHealType = skillHealType,
        skillBuffType = skillBuffType,
        skillBuffValue = skillBuffValue,
        skillBuffDuration = skillBuffDuration,
        skillBuffsJson = skillBuffsJson,
        skillIsAoe = skillIsAoe,
        skillTargetScope = skillTargetScope,
        skillShieldPercent = skillShieldPercent,
        skillTurnAdvancePercent = skillTurnAdvancePercent,
        skillDamageSharePercent = skillDamageSharePercent,
        skillDamageLinkPercent = skillDamageLinkPercent,
        minRealm = minRealm,
        quantity = quantity
    )
}

@Keep
@Serializable
enum class ManualType {
    @ProtoNumber(0) ATTACK,
    @ProtoNumber(1) DEFENSE,
    @ProtoNumber(2) SUPPORT,
    @ProtoNumber(3) MIND;

    val displayName: String get() = when (this) {
        ATTACK -> "攻击型"
        DEFENSE -> "防御型"
        SUPPORT -> "辅助型"
        MIND -> "心法型"
    }
}

@Keep
@Serializable
data class ManualSkill(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val description: String,
    @ProtoNumber(3) val skillType: SkillType = SkillType.ATTACK,
    @ProtoNumber(4) val damageType: DamageType = DamageType.PHYSICAL,
    @ProtoNumber(5) val hits: Int = 1,
    @ProtoNumber(6) val damageMultiplier: Double = 1.0,
    @ProtoNumber(7) val cooldown: Int = 3,
    @ProtoNumber(8) val mpCost: Int = 10,
    @ProtoNumber(9) val healPercent: Double = 0.0,
    @ProtoNumber(10) val healFixed: Int = 0,
    @ProtoNumber(11) val healType: HealType = HealType.HP,
    @ProtoNumber(12) val buffType: BuffType? = null,
    @ProtoNumber(13) val buffValue: Double = 0.0,
    @ProtoNumber(14) val buffDuration: Int = 0,
    @ProtoNumber(15) val buffs: List<Triple<BuffType, Double, Int>> = emptyList(),
    @ProtoNumber(16) val isAoe: Boolean = false,
    @ProtoNumber(17) val targetScope: String = "self",
    @ProtoNumber(18) val shieldPercent: Double = 0.0,
    @ProtoNumber(19) val turnAdvancePercent: Double = 0.0,
    @ProtoNumber(20) val damageSharePercent: Double = 0.0,
    @ProtoNumber(21) val damageLinkPercent: Double = 0.0
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
        healFixed = healFixed,
        healType = healType,
        buffType = buffType,
        buffValue = buffValue,
        buffDuration = buffDuration,
        buffs = buffs,
        isAoe = isAoe,
        targetScope = targetScope,
        skillDescription = description,
        manualName = manualName,
        shieldPercent = shieldPercent,
        turnAdvancePercent = turnAdvancePercent,
        damageSharePercent = damageSharePercent,
        damageLinkPercent = damageLinkPercent
    )
}

@Keep
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
@Immutable
data class Pill(
    @ColumnInfo(name = "id")
    @ProtoNumber(1)
    override val id: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(name = "slot_id")
    @ProtoNumber(100)
    var slotId: Int = 0,

    @ProtoNumber(2)
    override val name: String = "",
    @ProtoNumber(4)
    override val rarity: Int = 1,
    @ProtoNumber(6)
    override val description: String = "",

    @ProtoNumber(10)
    val category: PillCategory = PillCategory.CULTIVATION,
    @ProtoNumber(11)
    val grade: PillGrade = PillGrade.MEDIUM,
    @ProtoNumber(15)
    val pillType: String = "",

    @Embedded
    @ProtoNumber(14)
    val effects: PillEffect = PillEffect(),

    @ColumnInfo(name = "minRealm", defaultValue = "9")
    @ProtoNumber(12)
    val minRealm: Int = 9,

    @ProtoNumber(7)
    override var quantity: Int = 1,
    @ProtoNumber(13)
    override val isLocked: Boolean = false
) : GameItem(), StackableItem {

    override fun withQuantity(newQuantity: Int): Pill = copy(quantity = newQuantity)

    val basePrice: Int get() = (GameConfig.Rarity.get(rarity).pillBasePrice * grade.priceMultiplier).roundToInt()

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
    val miningAdd: Int get() = effects.miningAdd
    val healMaxHpPercent: Double get() = effects.healMaxHpPercent
    val mpRecoverMaxMpPercent: Double get() = effects.mpRecoverMaxMpPercent
    val revive: Boolean get() = effects.revive
    val clearAll: Boolean get() = effects.clearAll
}

@Keep
@Serializable
enum class PillCategory {
    @ProtoNumber(0) CULTIVATION,
    @ProtoNumber(1) BATTLE,
    @ProtoNumber(2) FUNCTIONAL;

    val displayName: String get() = when (this) {
        CULTIVATION -> "修炼丹药"
        BATTLE -> "战斗丹药"
        FUNCTIONAL -> "功能丹药"
    }
}

@Keep
@Serializable
enum class PillGrade {
    @ProtoNumber(0) LOW,
    @ProtoNumber(1) MEDIUM,
    @ProtoNumber(2) HIGH;

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

        /** 使用指定 RNG 生成品阶（用于存档确定性场景） */
        fun random(rng: kotlin.random.Random): PillGrade {
            val roll = rng.nextDouble()
            return when {
                roll < 0.06 -> HIGH
                roll < 0.40 -> MEDIUM
                else -> LOW
            }
        }
    }
}

@Keep
@Serializable
data class PillEffect(
    @ProtoNumber(1) val breakthroughChance: Double = 0.0,
    @ProtoNumber(2) val targetRealm: Int = 0,
    @ProtoNumber(3) val isAscension: Boolean = false,
    @ProtoNumber(4) val cultivationSpeedPercent: Double = 0.0,
    @ProtoNumber(5) val skillExpSpeedPercent: Double = 0.0,
    @ProtoNumber(6) val nurtureSpeedPercent: Double = 0.0,
    @ProtoNumber(7) val cultivationAdd: Int = 0,
    @ProtoNumber(8) val skillExpAdd: Int = 0,
    @ProtoNumber(9) val nurtureAdd: Int = 0,
    @ProtoNumber(10) val duration: Int = 3,
    @ProtoNumber(11) val cannotStack: Boolean = true,
    @ProtoNumber(12) val physicalAttackAdd: Int = 0,
    @ProtoNumber(13) val magicAttackAdd: Int = 0,
    @ProtoNumber(14) val physicalDefenseAdd: Int = 0,
    @ProtoNumber(15) val magicDefenseAdd: Int = 0,
    @ProtoNumber(16) val hpAdd: Int = 0,
    @ProtoNumber(17) val mpAdd: Int = 0,
    @ProtoNumber(18) val speedAdd: Int = 0,
    @ProtoNumber(19) val critRateAdd: Double = 0.0,
    @ProtoNumber(20) val critEffectAdd: Double = 0.0,
    @ProtoNumber(21) val extendLife: Int = 0,
    @ProtoNumber(22) val intelligenceAdd: Int = 0,
    @ProtoNumber(23) val charmAdd: Int = 0,
    @ProtoNumber(24) val loyaltyAdd: Int = 0,
    @ProtoNumber(25) val comprehensionAdd: Int = 0,
    @ProtoNumber(26) val artifactRefiningAdd: Int = 0,
    @ProtoNumber(27) val pillRefiningAdd: Int = 0,
    @ProtoNumber(28) val spiritPlantingAdd: Int = 0,
    @ProtoNumber(29) val teachingAdd: Int = 0,
    @ProtoNumber(30) val moralityAdd: Int = 0,
    @ProtoNumber(31) val miningAdd: Int = 0,
    @ProtoNumber(32) val healMaxHpPercent: Double = 0.0,
    @ProtoNumber(33) val mpRecoverMaxMpPercent: Double = 0.0,
    @ProtoNumber(34) val revive: Boolean = false,
    @ProtoNumber(35) val clearAll: Boolean = false
)

@Keep
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
@Immutable
data class Material(
    @ColumnInfo(name = "id")
    @ProtoNumber(1)
    override val id: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(name = "slot_id")
    @ProtoNumber(100)
    var slotId: Int = 0,

    @ProtoNumber(2)
    override val name: String = "",
    @ProtoNumber(4)
    override val rarity: Int = 1,
    @ProtoNumber(6)
    override val description: String = "",

    @ProtoNumber(3)
    val category: MaterialCategory = MaterialCategory.BEAST_HIDE,
    @ProtoNumber(5)
    override var quantity: Int = 1,
    @ProtoNumber(101)
    override val isLocked: Boolean = false
) : GameItem(), StackableItem {

    override fun withQuantity(newQuantity: Int): Material = copy(quantity = newQuantity)

    val basePrice: Int get() = GameConfig.Rarity.get(rarity).materialBasePrice
}

@Keep
@Serializable
enum class MaterialCategory {
    @ProtoNumber(0) BEAST_HIDE,
    @ProtoNumber(1) BEAST_BONE,
    @ProtoNumber(2) BEAST_TOOTH,
    @ProtoNumber(3) BEAST_CORE,
    @ProtoNumber(4) BEAST_CLAW,
    @ProtoNumber(5) BEAST_FEATHER,
    @ProtoNumber(6) BEAST_TAIL,
    @ProtoNumber(7) BEAST_SCALE,
    @ProtoNumber(8) BEAST_HORN,
    @ProtoNumber(9) BEAST_SHELL,
    @ProtoNumber(10) BEAST_BLOOD,
    @ProtoNumber(11) BEAST_PLASTRON;

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
        BEAST_BLOOD -> "兽血"
        BEAST_PLASTRON -> "龟甲"
    }
}

@Keep
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
@Immutable
data class Herb(
    @ColumnInfo(name = "id")
    @ProtoNumber(1)
    override val id: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(name = "slot_id")
    @ProtoNumber(100)
    var slotId: Int = 0,

    @ProtoNumber(2)
    override val name: String = "",
    @ProtoNumber(3)
    override val rarity: Int = 1,
    @ProtoNumber(6)
    override val description: String = "",

    @ProtoNumber(50)
    val category: String = "",
    @ProtoNumber(4)
    override var quantity: Int = 1,
    @ProtoNumber(101)
    override val isLocked: Boolean = false
) : GameItem(), StackableItem {

    override fun withQuantity(newQuantity: Int): Herb = copy(quantity = newQuantity)

    val basePrice: Int get() = GameConfig.Rarity.get(rarity).herbPrice
}

@Keep
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
@Immutable
data class Seed(
    @ColumnInfo(name = "id")
    @ProtoNumber(1)
    override val id: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(name = "slot_id")
    @ProtoNumber(100)
    var slotId: Int = 0,

    @ProtoNumber(2)
    override val name: String = "",
    @ProtoNumber(3)
    override val rarity: Int = 1,
    @ProtoNumber(8)
    override val description: String = "",

    @ProtoNumber(4)
    val growTime: Int = 3,
    @ProtoNumber(5)
    val yield: Int = 1,
    @ProtoNumber(7)
    override var quantity: Int = 1,
    @ProtoNumber(101)
    override val isLocked: Boolean = false
) : GameItem(), StackableItem {

    override fun withQuantity(newQuantity: Int): Seed = copy(quantity = newQuantity)

    val basePrice: Int get() = GameConfig.Rarity.get(rarity).seedPrice
}

/**
 * 从装备实例重建堆叠（旧存档兜底，2026-08-01 堆叠序列化缺陷修复）。
 *
 * 历史缺陷：SaveData 中 equipmentStacks 曾被标记 @Transient，备份文件/云存档不含堆叠，
 * 恢复路径会永久清空仓库堆叠。本函数仅对"未装备且无归属"的实例按 (name, rarity, slot)
 * 分组重建——仓库物品物理上从未被序列化过，无法无损恢复，仅能恢复已装备之外的游离实例。
 *
 * @param instances 装备实例列表
 * @return 按 (name, rarity, slot) 分组聚合的重建堆叠；无游离实例时返回空列表
 */
fun rebuildEquipmentStacks(instances: List<EquipmentInstance>): List<EquipmentStack> {
    val unowned = instances.filter { it.ownerId == null && !it.isEquipped }
    if (unowned.isEmpty()) return emptyList()
    return unowned
        .groupBy { Triple(it.name, it.rarity, it.slot) }
        .map { (_, group) -> group.first().toStack(quantity = group.size) }
}

/**
 * 从功法实例重建堆叠（旧存档兜底，2026-08-01 堆叠序列化缺陷修复）。
 * 语义同 [rebuildEquipmentStacks]，仅重建未学习（ownerId == null && !isLearned）的实例。
 *
 * @param instances 功法实例列表
 * @return 按 (name, rarity, type) 分组聚合的重建堆叠；无游离实例时返回空列表
 */
fun rebuildManualStacks(instances: List<ManualInstance>): List<ManualStack> {
    val unlearned = instances.filter { it.ownerId == null && !it.isLearned }
    if (unlearned.isEmpty()) return emptyList()
    return unlearned
        .groupBy { Triple(it.name, it.rarity, it.type) }
        .map { (_, group) -> group.first().toStack(quantity = group.size) }
}

@Entity(
    tableName = "storage_bags",
    primaryKeys = ["id", "slot_id"],
    indices = [androidx.room.Index(value = ["slot_id"])]
)
@Keep
@Serializable
@Immutable
data class StorageBag(
    @ColumnInfo(name = "id")
    @ProtoNumber(1)
    override val id: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(name = "slot_id")
    @ProtoNumber(100)
    var slotId: Int = 0,

    @ProtoNumber(2)
    override val name: String = "",
    @ProtoNumber(3)
    override val rarity: Int = 1,
    @ProtoNumber(4)
    val description: String = "可随机获得5-20件同品阶物品",
    @ProtoNumber(5)
    override var quantity: Int = 1,
    @ProtoNumber(6)
    override val isLocked: Boolean = false
) : HasId, StackableItem {

    override fun withQuantity(newQuantity: Int): StorageBag = copy(quantity = newQuantity)

    companion object {
        val TIER_NAMES = listOf("凡品储物袋", "灵品储物袋", "宝品储物袋", "玄品储物袋", "地品储物袋", "天品储物袋")
        val SPIRIT_STONE_AMOUNTS = listOf(500L, 2000L, 10000L, 50000L, 200000L, 500000L)
    }
}
