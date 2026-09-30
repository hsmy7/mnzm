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
    abstract override val id: String
    abstract val name: String
    abstract val rarity: Int
    abstract val description: String

    val rarityColor: String get() = GameConfig.Rarity.getColor(rarity)
    val rarityName: String get() = GameConfig.Rarity.getName(rarity)
}

/**
 * 旧装备堆叠（B3 起退役——仅作旧档补偿的**只读载体**保留声明，禁参与任何新逻辑）。
 *
 * 保留原因（EQ-B1/B2「保留+归一化」先例）：旧 .sav/云档与 MIGRATION_63_64 影子表
 * （`legacy_equipment_stacks`/`legacy_equipment_instances`）中的堆叠/实例行需要
 * 可反序列化的载体，`LegacyEquipmentCompensationRule` 据此按 §5.4 折算补偿；
 * 补偿完成后该类型不再有写入点（R6：运行时装备一律一行一实例，无堆叠语义）。
 * `nurtureLevel(18)` 为 B3 搬运新增号（旧实例行 → 堆叠形搬运时携带孕养等级，
 * 供补偿公式 `basePrice × (1 + 0.5 × 已孕养等级/品阶满级)` 取值）。
 */
@Deprecated("装备堆叠已随 B3 退役（一行一实例）；保留声明仅供旧档补偿折算，禁新增写入点")
@Keep
@Serializable
data class EquipmentStack(
    @ProtoNumber(1)
    override val id: String = java.util.UUID.randomUUID().toString(),

    @ProtoNumber(100)
    val slotId: Int = 0,

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
    val quantity: Int = 1,
    @ProtoNumber(101)
    val isLocked: Boolean = false,

    /** 旧实例行搬运面：孕养等级（堆叠行恒 0）；补偿折算用 */
    @ProtoNumber(18)
    val nurtureLevel: Int = 0
) : GameItem() {

    /** 旧装备折算单价：旧模板价格快照（已删模板的静态快照表），缺失按品阶基准价 */
    val basePrice: Int get() = LegacyEquipmentPrices.priceOf(name, rarity)
}

/**
 * 旧堆叠载体 → 轻量实例（过渡桥：秘境背包/战利品等仍以 deprecated 载体流动的
 * 装备入实例轨时转换；按部件名反查新表——命中走新表元数据，未命中（旧装备名）
 * 用载体自身值构造占位实例，词条空面）。补偿折算面不走此函数（规则直接读载体）。
 */
fun EquipmentStack.toLegacyInstance(): EquipmentInstance {
    val entry = EquipmentDatabaseCompat.findEntryByName(name)
    return if (entry != null) {
        EquipmentInstance(
            name = entry.name,
            setId = entry.setId,
            part = entry.part,
            growth = EquipGrowth(
                affix = EquipAffixSet(mainStat = EquipStatValue(EquipStat.ATTACK, 0.0))
            ),
            meta = EquipInstanceMeta(
                rarity = entry.rarity,
                minRealm = entry.minRealm,
                description = entry.description
            )
        )
    } else {
        EquipmentInstance(
            name = name,
            part = EquipmentSlot.entries.find { it.name == slot.name } ?: EquipmentSlot.HEAD,
            growth = EquipGrowth(
                affix = EquipAffixSet(mainStat = EquipStatValue(EquipStat.ATTACK, 0.0))
            ),
            meta = EquipInstanceMeta(rarity = rarity, minRealm = minRealm, description = description)
        )
    }
}

/**
 * 兼容桥（domain 内部）：按部件名查新表展开条目。registry 包在 domain 内但
 * model 包先于 registry 初始化，此处用函数间接避免 object 初始化环。
 */
private object EquipmentDatabaseCompat {
    fun findEntryByName(name: String): com.xianxia.sect.core.registry.EquipmentDatabase.EquipPieceEntry? =
        com.xianxia.sect.core.registry.EquipmentDatabase.entries.values
            .filter { it.name == name }.minByOrNull { it.rarity }
}

/**
 * 旧装备模板价格快照（补偿专用，方案 §5.4/§5.7 先例：模板已删，折算表由静态
 * 快照提供，不依赖运行时模板）。72 条旧模板名 → 价格；缺失名按品阶基准价兜底。
 */
object LegacyEquipmentPrices {
    private val BASE_BY_RARITY = listOf(4_000, 16_000, 80_000, 480_000, 3_360_000, 26_880_000)

    /** 旧 72 模板名 → 价格（静态快照；来源 = 退役时刻 EquipmentDatabase） */
    private val PRICES: Map<String, Int> = mapOf(
        // 武器（24）
        "精铁剑" to 4_000, "精铁刀" to 4_000, "桃木杖" to 4_000, "碧木扇" to 4_000,
        "灵锋剑" to 16_000, "凌华刀" to 16_000, "碧玉杖" to 16_000, "灵风扇" to 16_000,
        "青碧刃" to 80_000, "烈焰剑" to 80_000, "玄雷杖" to 80_000, "玄冰扇" to 80_000,
        "雷霆剑" to 480_000, "暗影刃" to 480_000, "虚华杖" to 480_000, "凰焰扇" to 480_000,
        "凤炎刃" to 3_360_000, "青莲剑" to 3_360_000, "阴阳扇" to 3_360_000, "天玄杖" to 3_360_000,
        "诛仙剑" to 26_880_000, "玄玉刃" to 26_880_000, "天星杖" to 26_880_000, "天玄扇" to 26_880_000,
        // 护甲（24）
        "皮甲" to 4_000, "锁子甲" to 4_000, "精铁甲" to 4_000, "灵竹衣" to 4_000,
        "碧叶甲" to 16_000, "丹羽衣" to 16_000, "灵丝袍" to 16_000, "云纹袍" to 16_000,
        "青鳞铠" to 80_000, "银板铠" to 80_000, "汐流衣" to 80_000, "星辰袍" to 80_000,
        "龙鳞铠" to 480_000, "渊岩铠" to 480_000, "瑶光袍" to 480_000, "月华袍" to 480_000,
        "玄幽袍" to 3_360_000, "墨幽铠" to 3_360_000, "凌星袍" to 3_360_000, "定海铠" to 3_360_000,
        "不朽铠" to 26_880_000, "苍罡铠" to 26_880_000, "曦光铠" to 26_880_000, "云影袍" to 26_880_000,
        // 靴（12）
        "青澜靴" to 4_000, "兽皮靴" to 4_000,
        "疾风靴" to 16_000, "轻羽靴" to 16_000,
        "追风靴" to 80_000, "云栖靴" to 80_000,
        "踏云履" to 480_000, "奔雷靴" to 480_000,
        "溯光靴" to 3_360_000, "赤煞靴" to 3_360_000,
        "鸾羽履" to 26_880_000, "鹤岚靴" to 26_880_000,
        // 饰品（12）
        "玉戒指" to 4_000, "铜项链" to 4_000,
        "灵玉佩" to 16_000, "蕴灵戒" to 16_000,
        "灵泉戒" to 80_000, "迅捷珠" to 80_000,
        "龙灵珠" to 480_000, "凤羽坠" to 480_000,
        "渡厄佩" to 3_360_000, "隐云佩" to 3_360_000,
        "幽朔珠" to 26_880_000, "长明坠" to 26_880_000
    )

    fun priceOf(name: String, rarity: Int): Int =
        PRICES[name] ?: BASE_BY_RARITY.getOrElse((rarity - 1).coerceIn(0, 5)) { 4_000 }
}
/**
 * 装备实例（装备重构 B3，方案 §3.5）：一行一实例、无堆叠、等级随实例单点。
 *
 * ## 存档编号（E1 冻结表，方案 §5.3 / `EquipmentProtoNumberFrozenTest`）
 * 保留 `id(1)/slotId(100)/name(2)/ownerId(16)/isEquipped(11)`；新增
 * `setId(60)/part(61)/growth(62)/meta(63)`；退役
 * `slot(3)/rarity(4)/description(7)/critChance(10)/nurtureLevel(13)/
 * nurtureProgress(14)/minRealm(15)/面板属性(50–56)`——旧档字节按未知字段
 * 忽略，禁复用；旧档实例在 MIGRATION_63_64 全部作废（R2 + §5.4 补偿）。
 */
@Keep
@Serializable
@Entity(
    tableName = "equipment_instances",
    primaryKeys = ["id", "slot_id"],
    indices = [
        Index(value = ["ownerId"]),
        Index(value = ["setId"]),
        Index(value = ["part"])
    ]
)
@Immutable
data class EquipmentInstance(
    @ColumnInfo(name = "id")
    @ProtoNumber(1)
    override val id: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(name = "slot_id")
    @ProtoNumber(100)
    var slotId: Int = 0,

    @ProtoNumber(2)
    override val name: String = "",
    // reserved 3,4,7,10,13,14,15;（slot/rarity/description/critChance/nurtureLevel/
    // nurtureProgress/minRealm 字段号已退役，禁复用）
    // reserved 50..56;（旧面板 7 属性 + 暴击率字段号已退役，禁复用）

    /** 套装 id（"lietian"/"zifu"） */
    @ProtoNumber(60)
    val setId: String = "",
    /** 六部位（HEAD/BODY/HANDS/FEET/WEAPON/LEGS） */
    @ProtoNumber(61)
    val part: EquipmentSlot = EquipmentSlot.HEAD,
    /** 成长面：等级 + 经验 + 词条（等级/词条只存实例单点，清偿 D2） */
    @ProtoNumber(62)
    val growth: EquipGrowth = EquipGrowth(
        affix = EquipAffixSet(mainStat = EquipStatValue(EquipStat.ATTACK, 0.0))
    ),
    /** 横切面：品阶/门槛/描述/锁 */
    @ProtoNumber(63)
    val meta: EquipInstanceMeta = EquipInstanceMeta(),

    @ProtoNumber(16)
    val ownerId: String? = null,
    @ProtoNumber(11)
    val isEquipped: Boolean = false
) : GameItem() {

    val level: Int get() = growth.level
    val exp: Int get() = growth.exp
    override val rarity: Int get() = meta.rarity
    override val description: String get() = meta.description
    val isLocked: Boolean get() = meta.isLocked
    val minRealm: Int get() = meta.minRealm
    val affix: EquipAffixSet get() = growth.affix

    /** 套装部件模板（setId 为空 = 散件 ⇒ null） */
    val pieceTemplate: EquipmentDatabase.SetPieceTemplate?
        get() = setId.takeIf { it.isNotEmpty() }?.let { EquipmentDatabase.getPieceById(it) }

    val basePrice: Int get() = pieceTemplate?.priceByRarity?.getOrElse(rarity - 1) { 0 }
        ?: GameConfig.Rarity.get(rarity).basePrice

    /** 完整加成（主词条按等级成长 + 3 副词条含强化收益） */
    fun totalBonus(): List<EquipStatValue> = growth.affix.totalBonus(growth.level)

    /** 词条摘要（UI 列表行展示） */
    val bonusDescription: String
        get() = totalBonus().joinToString("、") { it.toString() }.ifEmpty { "无属性" }

    /** 与 [other] 是否同一件装备（id 相等） */
    fun sameItem(other: EquipmentInstance): Boolean = id == other.id

    companion object {
        const val MIN_LEVEL = EquipLevelCurve.MIN_LEVEL
        const val MAX_LEVEL = EquipLevelCurve.MAX_LEVEL
    }
}

@Keep
@Serializable
enum class EquipmentSlot {
    // 编号 0..3 为已退役的四部位（原 WEAPON/ARMOR/BOOTS/ACCESSORY）：保留 reserved
    // 语义，禁复用（同名冲突仅 WEAPON 旧0/新14，由 MIGRATION_63_64 清空旧行兜底）。
    @ProtoNumber(10) HEAD,      // 头部
    @ProtoNumber(11) BODY,      // 身体
    @ProtoNumber(12) HANDS,     // 手部
    @ProtoNumber(13) FEET,      // 脚部
    @ProtoNumber(14) WEAPON,    // 武器
    @ProtoNumber(15) LEGS;      // 腿部

    val displayName: String get() = when (this) {
        HEAD -> "头部"
        BODY -> "身体"
        HANDS -> "手部"
        FEET -> "脚部"
        WEAPON -> "武器"
        LEGS -> "腿部"
    }

    /**
     * UI 六宫格顺序（单一真源；声明序 = 显示序 = R1 指定序 头/身/手/脚/武/腿；
     * 3×2 宫格：上行 头·身·手，下行 脚·武·腿）。
     * `EquipmentSlotOrderGuardTest` 同时断言它与枚举声明序一致。
     */
    companion object {
        val displayOrder: List<EquipmentSlot> =
            listOf(HEAD, BODY, HANDS, FEET, WEAPON, LEGS)
    }
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

    override fun withNewId(newId: String): StackableItem = copy(id = newId)

    val basePrice: Int get() = GameConfig.Rarity.get(rarity).basePrice

    fun toInstance(id: String = java.util.UUID.randomUUID().toString(), ownerId: String? = null,
        isLearned: Boolean = true): ManualInstance = ManualInstance(
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

    private fun parseBuffType(bt: String): BuffType? = BUFF_TYPE_BY_KEY[bt]

    companion object {
        /** buffType 键 → 枚举映射：未知键为 null */
        private val BUFF_TYPE_BY_KEY: Map<String, BuffType> = mapOf(
            "physical_attack" to BuffType.PHYSICAL_ATTACK_BOOST,
            "magic_attack" to BuffType.MAGIC_ATTACK_BOOST,
            "physical_defense" to BuffType.PHYSICAL_DEFENSE_BOOST,
            "magic_defense" to BuffType.MAGIC_DEFENSE_BOOST,
            "hp" to BuffType.HP_BOOST,
            "mp" to BuffType.MP_BOOST,
            "speed" to BuffType.SPEED_BOOST,
            "crit_rate" to BuffType.CRIT_RATE_BOOST,
            "physical_attack_reduce" to BuffType.PHYSICAL_ATTACK_REDUCE,
            "magic_attack_reduce" to BuffType.MAGIC_ATTACK_REDUCE,
            "physical_defense_reduce" to BuffType.PHYSICAL_DEFENSE_REDUCE,
            "magic_defense_reduce" to BuffType.MAGIC_DEFENSE_REDUCE,
            "speed_reduce" to BuffType.SPEED_REDUCE,
            "crit_rate_reduce" to BuffType.CRIT_RATE_REDUCE,
            "poison" to BuffType.POISON,
            "burn" to BuffType.BURN,
            "stun" to BuffType.STUN,
            "freeze" to BuffType.FREEZE,
            "silence" to BuffType.SILENCE,
            "taunt" to BuffType.TAUNT,
            "damage_boost" to BuffType.DAMAGE_BOOST,
            "damage_reduction" to BuffType.DAMAGE_REDUCTION,
            "shield" to BuffType.SHIELD,
            "damage_share" to BuffType.DAMAGE_SHARE,
            "damage_link" to BuffType.DAMAGE_LINK,
            "turn_advance" to BuffType.TURN_ADVANCE
        )
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

    override fun withNewId(newId: String): StackableItem = copy(id = newId)

    val basePrice: Int get() = (GameConfig.Rarity.get(rarity).pillBasePrice * grade.priceMultiplier).roundToInt()

    val breakthroughChance: Double get() = effects.breakthroughChance
    val targetRealm: Int get() = effects.targetRealm
    val isAscension: Boolean get() = effects.isAscension
    val cultivationSpeedPercent: Double get() = effects.cultivationSpeedPercent
    val skillExpSpeedPercent: Double get() = effects.skillExpSpeedPercent
    val cultivationAdd: Int get() = effects.cultivationAdd
    val skillExpAdd: Int get() = effects.skillExpAdd
    val duration: Int get() = effects.duration
    val cannotStack: Boolean get() = effects.cannotStack
    val attackAdd: Int get() = effects.attackAddTotal
    val defenseAdd: Int get() = effects.defenseAddTotal
    val hpAdd: Int get() = effects.hpAdd
    val mpAdd: Int get() = effects.mpAdd
    val speedAdd: Int get() = effects.speedAdd
    val critRateAdd: Double get() = effects.critRateAdd
    val critEffectAdd: Double get() = effects.critEffectAdd
    val extendLife: Int get() = effects.extendLife
    val intelligenceAdd: Int get() = effects.intelligenceAdd
    val charmAdd: Int get() = effects.charmAdd
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
    // reserved 6,9;（nurtureSpeedPercent/nurtureAdd 孕养类加成丹效果，R11 退役——
    // 旧档字节按未知字段忽略，禁复用）
    @ProtoNumber(7) val cultivationAdd: Int = 0,
    @ProtoNumber(8) val skillExpAdd: Int = 0,
    @ProtoNumber(10) val duration: Int = 3,
    @ProtoNumber(11) val cannotStack: Boolean = true,
    // ── 攻防加成单列口径（B1，方案 §15.4）──
    // 新写入 attackAdd(36)/defenseAdd(37)；旧物法四列（12–15）保留声明仅作旧档
    // 归一化读取（不再写入），消费点一律读 [attackAddTotal]/[defenseAddTotal]。
    @Deprecated("旧物攻加成，仅旧档归一化读取；改用 attackAddTotal")
    @ProtoNumber(12) val physicalAttackAdd: Int = 0,
    @Deprecated("旧法攻加成，仅旧档归一化读取")
    @ProtoNumber(13) val magicAttackAdd: Int = 0,
    @Deprecated("旧物防加成，仅旧档归一化读取")
    @ProtoNumber(14) val physicalDefenseAdd: Int = 0,
    @Deprecated("旧法防加成，仅旧档归一化读取")
    @ProtoNumber(15) val magicDefenseAdd: Int = 0,
    @ColumnInfo(defaultValue = "0")
    @ProtoNumber(36) val attackAdd: Int = 0,
    @ColumnInfo(defaultValue = "0")
    @ProtoNumber(37) val defenseAdd: Int = 0,
    @ProtoNumber(16) val hpAdd: Int = 0,
    @ProtoNumber(17) val mpAdd: Int = 0,
    @ProtoNumber(18) val speedAdd: Int = 0,
    @ProtoNumber(19) val critRateAdd: Double = 0.0,
    @ProtoNumber(20) val critEffectAdd: Double = 0.0,
    @ProtoNumber(21) val extendLife: Int = 0,
    @ProtoNumber(22) val intelligenceAdd: Int = 0,
    @ProtoNumber(23) val charmAdd: Int = 0,
    // reserved 24;（loyaltyAdd 字段号已退役，禁止复用）
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
) {
    /** 有效攻击加成：新单列值 + 旧物法两列归一化（旧档 12/13 有值、新档恒 0） */
    val attackAddTotal: Int get() = attackAdd + physicalAttackAdd + magicAttackAdd

    /** 有效防御加成：口径同 [attackAddTotal] */
    val defenseAddTotal: Int get() = defenseAdd + physicalDefenseAdd + magicDefenseAdd
}

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

    override fun withNewId(newId: String): StackableItem = copy(id = newId)

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

    override fun withNewId(newId: String): StackableItem = copy(id = newId)

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

    override fun withNewId(newId: String): StackableItem = copy(id = newId)

    val basePrice: Int get() = GameConfig.Rarity.get(rarity).seedPrice
}

/**
 * 从功法实例重建堆叠（旧存档兜底）。
 * 语义同旧装备堆叠重建（B3 起装备无堆叠语义，仅剩功法），
 * 仅重建未学习（ownerId == null && !isLearned）的实例。
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

    override fun withNewId(newId: String): StackableItem = copy(id = newId)

    companion object {
        val TIER_NAMES = listOf("凡品储物袋", "灵品储物袋", "宝品储物袋", "玄品储物袋", "地品储物袋", "天品储物袋")
        val SPIRIT_STONE_AMOUNTS = listOf(500L, 2000L, 10000L, 50000L, 200000L, 500000L)
    }
}
