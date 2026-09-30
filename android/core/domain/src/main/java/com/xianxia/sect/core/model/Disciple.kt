package com.xianxia.sect.core.model

import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import com.xianxia.sect.core.GameConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber


/**
 * 弟子数据模型（Room Entity）
 *
 * ## 推荐访问路径
 *
 * 为保持代码清晰和可维护性，推荐直接通过 @Embedded 子组件访问属性：
 *
 * **战斗属性** → `disciple.combat.baseHp`, `disciple.combat.baseAttack` 等
 * **丹药效果** → `disciple.pillEffects.pillHpBonus`, `disciple.pillEffects.pillEffectDuration` 等
 * **装备数据** → `disciple.equipment.weaponId`, `disciple.equipment.spiritStones` 等
 * **技能属性** → `disciple.skills.intelligence`, `disciple.skills.comprehension` 等
 * **使用追踪** → `disciple.usage.usedFunctionalPillTypes`, `disciple.usage.recruitedMonth` 等
 *
 * ## 委托属性
 *
 * 以下快捷访问属性用于简化访问，内部委托给子结构：
 * - `disciple.baseHp` → `disciple.combat.baseHp`
 * - `disciple.pillHpBonus` → `disciple.pillEffects.pillHpBonus`
 * - `disciple.weaponId` → `disciple.equipment.weaponId`
 * - `disciple.intelligence` → `disciple.skills.intelligence`
 *
 * ## 属性计算方法（晚绑定 DiscipleStatsProvider）
 *
 * 复杂的业务计算逻辑通过晚绑定的 DiscipleStatsProvider 接口实现，
 * 由 :core:engine 模块中的 DiscipleStatCalculator 注入：
 * - getBaseStats → DiscipleStatsProvider.getBaseStats
 * - getFinalStats → DiscipleStatsProvider.getFinalStats
 * - getStatsWithEquipment → DiscipleStatsProvider.getStatsWithEquipment
 * - calculateCultivationSpeed → DiscipleStatsProvider.calculateCultivationSpeed
 * - getBreakthroughChance → DiscipleStatsProvider.getBreakthroughChance
 */
@Keep
@Serializable(with = DiscipleSerializer::class)
@Entity(
    tableName = "disciples",
    primaryKeys = ["id", "slot_id"],
    indices = [
        Index(value = ["name"]),
        Index(value = ["realm", "realmLayer"]),
        Index(value = ["isAlive", "realm"]),
        Index(value = ["isAlive", "status"]),
        Index(value = ["discipleType"])
    ]
)
@Immutable
data class Disciple(
    @ColumnInfo(name = "id")
    var id: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(name = "slot_id")
    var slotId: Int = 0,

    var name: String = "",
    @ColumnInfo(name = "surname")
    var surname: String = "",
    var realm: Int = 9,
    var realmLayer: Int = 1,
    var cultivation: Double = 0.0,
    var cultivationCheckpoint: Double = 0.0,
    var cultivationCheckpointGameMonth: Int = 0,

    val spiritRootType: String = "metal",

    var isAlive: Boolean = true,

    var gender: String = "male",

    var portraitRes: String = "",

    /** 角色模板 id（Q32 只读）；存量旧弟子空串、星级加成 0 */
    @ColumnInfo(name = "templateId", defaultValue = "")
    var templateId: String = "",

    var manualIds: List<String> = emptyList(),

    var manualMasteries: Map<String, Int> = emptyMap(),

    var status: DiscipleStatus = DiscipleStatus.IDLE,
    var statusData: Map<String, String> = emptyMap(),

    var cultivationSpeedBonus: Double = 0.0,
    var cultivationSpeedDuration: Int = 0,

    var discipleType: String = "outer",

    @ColumnInfo(defaultValue = "0")
    var cultivationCompletionMonth: Int = 0,
    @ColumnInfo(defaultValue = "0")
    var manualCompletionMonth: Int = 0,
    @ColumnInfo(defaultValue = "1")
    var manualCompletionPhase: Int = 1,
    // reserved 98,99;（equipmentNurturingCompletionMonth/Phase 装备孕养完成月/旬，
    // B3 装备体系替换批退役——孕养 checkpoint 随孕养体系整体删除（D4），禁复用）

    // ========== @Embedded 组件 ==========
    // 委托扩展属性见 DiscipleDelegates.kt + 本文件（monthlyUsedPillIds）
    @Embedded
    var combat: CombatAttributes = CombatAttributes(),

    @Embedded
    var pillEffects: PillEffects = PillEffects(),

    @Embedded
    var equipment: EquipmentSet = EquipmentSet(),

    @Embedded
    var skills: SkillStats = SkillStats(),

    @Embedded(prefix = "usage_")
    var usage: UsageTracking = UsageTracking()
) {
    /** 弟子日志事件列表，存储于 DiscipleTables.lifeEvents */
    @Ignore
    var lifeEvents: List<String> = emptyList()

    // ==================== 委托属性 ====================
    // 大部分委托扩展属性已提取到 DiscipleDelegates.kt，仅保留
    // 属性名与源字段名不同的例外（monthlyUsedPillIds → usedFunctionalPillTypes）
    /** @deprecated 请改用 [usage.usedFunctionalPillTypes] */
    var monthlyUsedPillIds: List<String>
        get() = usage.usedFunctionalPillTypes
        set(value) { usage.usedFunctionalPillTypes = value }

    // ==================== 计算属性 ====================

    val canCultivate: Boolean get() = realmLayer != 0
    val realmName: String get() {
        if (realmLayer == 0) return "无境界"
        // 仙人境界不显示层数
        if (realm == 0) return GameConfig.Realm.getName(realm)
        return "${GameConfig.Realm.getName(realm)}${realmLayer}层"
    }
    val realmNameOnly: String get() = GameConfig.Realm.getName(realm)
    val maxCultivation: Double get() {
        // 仙人境界修为直接显示满值
        if (realm == 0) return cultivation
        val base = GameConfig.Realm.get(realm).cultivationBase
        val nextBase = GameConfig.Realm.get(realm - 1).cultivationBase
        val maxLayers = GameConfig.Realm.get(realm).maxLayers
        return base + (realmLayer - 1) * (nextBase - base).toDouble() / maxLayers
    }
    val cultivationProgress: Double get() = if (maxCultivation > 0) cultivation / maxCultivation else 0.0

    val spiritRoot: SpiritRoot get() = SpiritRoot(spiritRootType)
    val spiritRootName: String get() = spiritRoot.name

    val attack: Int get() = getBaseStats().attack
    val defense: Int get() = getBaseStats().defense
    val speed: Int get() = getBaseStats().speed
    val maxHp: Int get() = getBaseStats().maxHp
    val maxMp: Int get() = getBaseStats().maxMp

    /** 当前生命百分比 */
    val hpPercent: Float get() = if (maxHp > 0) currentHp.toFloat() / maxHp else 0f
    val mpPercent: Float get() = if (maxMp > 0) currentMp.toFloat() / maxMp else 0f

    val equippedItems: Map<EquipmentSlot, EquipmentInstance?> get() = emptyMap()
    val learnedManuals: List<ManualInstance> get() = emptyList()

    val genderName: String get() = if (gender == "male") "男" else "女"
    val genderSymbol: String get() = if (gender == "male") "\u2642" else "\u2640"

    // 所有字段更新通过 DiscipleTables 直接操作；如需构造新 Disciple 对象，
    // 请使用 Disciple(...) 构造函数或 DiscipleTables.assemble()。

    companion object {
        fun calculateBaseStatsWithVariance(
            hpVariance: Int,
            mpVariance: Int,
            attackVariance: Int,
            defenseVariance: Int,
            speedVariance: Int
        ): BaseCombatStats {
            return CombatAttributes.calculateBaseStatsWithVariance(
                hpVariance, mpVariance, attackVariance, defenseVariance, speedVariance
            )
        }
    }

    // ==================== 属性计算方法（晚绑定 DiscipleStatsProvider）====================

    fun getBaseStats(): DiscipleStats = DiscipleAggregate.statsProvider.getBaseStats(this)

    fun getStatsWithEquipment(equipments: Map<String,
        EquipmentInstance>): DiscipleStats = DiscipleAggregate.statsProvider.getStatsWithEquipment(this, equipments)

    fun getFinalStats(
        equipments: Map<String, EquipmentInstance>,
        manuals: Map<String, ManualInstance>,
        manualProficiencies: Map<String, ManualProficiencyData> = emptyMap()
    ): DiscipleStats = DiscipleAggregate.statsProvider.getFinalStats(
        this, equipments, manuals, manualProficiencies
    )

    fun calculateCultivationSpeed(manuals: Map<String, ManualInstance> = emptyMap(), manualProficiencies: Map<String,
        ManualProficiencyData> = emptyMap(), buildingBonus: Double = 1.0, additionalBonus: Double = 0.0,
            preachingElderBonus: Double = 0.0, preachingMastersBonus: Double = 0.0,
                cultivationSubsidyBonus: Double = 0.0): Double = DiscipleAggregate.statsProvider
                    .calculateCultivationSpeed(this, manuals, manualProficiencies, buildingBonus, additionalBonus,
                        preachingElderBonus, preachingMastersBonus, cultivationSubsidyBonus)

    /** 判断弟子是否可以突破 */
    fun canBreakthrough(): Boolean = cultivation >= maxCultivation

    fun getBreakthroughChance(innerElderComprehension: Int = 0, outerElderComprehension: Int = 0, pillBonus: Double =
        0.0, adBonus: Double = 0.0): Double =
        DiscipleAggregate.statsProvider.getBreakthroughChance(this, innerElderComprehension, outerElderComprehension,
            pillBonus, adBonus)

    // ==================== 转换方法（Disciple → DiscipleAggregate）====================

    /**
     * 将此单表实体转换为 [DiscipleAggregate] 多表结构
     *
     * 转换后的 [DiscipleAggregate] 可直接用于业务逻辑处理。
     *
     * @return 完整的 DiscipleAggregate 实例，包含所有组件数据
     */
    fun toAggregate(): DiscipleAggregate {
        return DiscipleAggregate.fromDisciple(this)
    }
}

@Keep
@Serializable
enum class DiscipleStatus {
    IDLE, DEACONING, MINING, STUDYING, PREACHING, MANAGING, LAW_ENFORCING, ON_MISSION, REFLECTING, GARRISONING, IN_TEAM,
        PATROLLING, ALCHEMY, FORGE, SPIRIT_PLANTING, DEAD,
    SECRET_REALM,
    /** 旧档兼容保留：仓库驻守玩法已下线，存量存档可能仍写入该状态 */
    WAREHOUSE_GARRISON;

    val displayName: String get() = when (this) {
        IDLE -> "空闲中"
        DEACONING -> "灵矿执事"
        MINING -> "灵矿场矿工"
        STUDYING -> "藏经阁弟子"
        PREACHING -> "传道弟子"
        MANAGING -> "管理中"
        LAW_ENFORCING -> "执法弟子"
        ON_MISSION -> "执行任务中"
        REFLECTING -> "监牢中"
        GARRISONING -> "驻守中"
        IN_TEAM -> "队伍中"
        PATROLLING -> "巡视塔中"
        ALCHEMY -> "炼丹弟子"
        FORGE -> "锻造弟子"
        SPIRIT_PLANTING -> "灵植弟子"
        DEAD -> "已死亡"
        SECRET_REALM -> "远古秘境中"
        WAREHOUSE_GARRISON -> "仓库驻守中"
    }
}

@Keep
@Serializable
data class SpiritRoot(
    val type: String
) {
    val types: List<String> get() = type.split(",")

    /**
     * 五行伤害加成的灵根 gate（唯一实现入口，五行属性伤害系统方案 §3.3）：
     * 灵根集合**含该元素 → 全额生效（1.0）；不含 → 完全不生效（0.0）**。
     * 纯乘性开关，不做按灵根数量的线性折算；[element] 为 null（物理）恒 1.0——
     * 物理伤害加成不受灵根 gate（普攻人人物理）。
     * 空串/未知元素按"不含"处理（0.0）。
     */
    fun elementGate(element: String?): Double {
        if (element == null) return 1.0
        return if (types.any { it.trim() == element }) 1.0 else 0.0
    }

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

    /**
     * 灵根**数量**徽章色（单金双红三紫四蓝五灰）。
     *
     * 取值委托 [GameConfig.Gacha.spiritRootCountColor]——Q31 是本仓灵根数色的唯一真源，
     * 与寻访结果页的角色碎片框同表（[elementColor] 是另一维度：灵根**元素**色，不在此表内）。
     * C++ 侧三份同口径副本（`exploration_tx.h` / `year_settlement.h` /
     * `sect_defense_battle.h`）由 `GachaColorSingleSourceGuardTest` 钉住，改表必须四处同改。
     */
    val countColor: String get() = GameConfig.Gacha.spiritRootCountColor(types.size)
}

@Keep
@Serializable
data class DiscipleStats(
    val hp: Int = 0,
    val maxHp: Int = 0,
    val mp: Int = 0,
    val maxMp: Int = 0,
    val attack: Int = 0,
    val defense: Int = 0,
    val speed: Int = 0,
    val critRate: Double = 0.0,
    val intelligence: Int = 0,
    val charm: Int = 0,
    val comprehension: Int = 0,
    val teaching: Int = 0,
    val morality: Int = 0,
    val mining: Int = 0,
    val spiritPlanting: Int = 0,
    val artifactRefining: Int = 0,
    val pillRefining: Int = 0,
    /**
     * 暴击伤害加成：暴击时伤害倍率 = `1 + GameConfig.Battle.CRIT_BASE_MULTIPLIER + 本字段`。
     * 来源 = 装备（含套装）critDamage 词条 + 丹药暴击效果（pillCritEffectBonus），
     * 由属性结算管线派生累加；面板展示与 Combatant 装配直读本字段。
     * 追加在字段末尾（kotlinx-proto 隐式字段号按声明序，插入中部会漂移旧档解码）。
     */
    val critDamageBonus: Double = 0.0
) {
    operator fun plus(other: DiscipleStats): DiscipleStats {
        return DiscipleStats(
            hp = hp + other.hp,
            maxHp = maxHp + other.maxHp,
            mp = mp + other.mp,
            maxMp = maxMp + other.maxMp,
            attack = attack + other.attack,
            defense = defense + other.defense,
            speed = speed + other.speed,
            critRate = critRate + other.critRate,
            intelligence = intelligence + other.intelligence,
            charm = charm + other.charm,
            comprehension = comprehension + other.comprehension,
            teaching = teaching + other.teaching,
            morality = morality + other.morality,
            mining = mining + other.mining,
            spiritPlanting = spiritPlanting + other.spiritPlanting,
            artifactRefining = artifactRefining + other.artifactRefining,
            pillRefining = pillRefining + other.pillRefining,
            critDamageBonus = critDamageBonus + other.critDamageBonus
        )
    }
}

@Keep
@Serializable
data class BaseCombatStats(
    val baseHp: Int = 120,
    val baseMp: Int = 60,
    val baseAttack: Int = 24,
    val baseDefense: Int = 18,
    val baseSpeed: Int = 15
)

@Keep
@Serializable
data class StorageBagItem(
    @ProtoNumber(1) val itemId: String,
    @ProtoNumber(2) val itemType: String,
    @ProtoNumber(3) val name: String,
    @ProtoNumber(4) val rarity: Int,
    @ProtoNumber(5) val quantity: Int = 1,
    @ProtoNumber(6) val obtainedYear: Int = 1,
    @ProtoNumber(7) val obtainedMonth: Int = 1,
    // 编号 8-12 与旧格式 SerializableStorageBagItem 完全一致（protobuf 字段号
    // 与旧档兼容，不得变动）。
    @ProtoNumber(8) val effect: ItemEffect? = null,
    @ProtoNumber(9) val grade: String? = null,
    @ProtoNumber(10) val forgetYear: Int? = null,
    @ProtoNumber(11) val forgetMonth: Int? = null,
    @ProtoNumber(12) val forgetPhase: Int? = null,
    // 储物袋独立存储：袋条目自带数据，不引用仓库堆叠。
    // equipmentInstance = 卸装装备实例（完整保真，含 nurtureLevel/Progress）；
    // manualInstance = 忘功法实例（完整保真）；stackedData = 堆叠类物品的
    // 取回/物化重建补充字段（minRealm/slot/manualType）。
    // 三者任一非空 = 已物化（老存档条目经 materializeDiscipleBagItems 迁移）。
    // 容量无上限：袋不设容量检查，所有写入路径（赏赐/购买/偷盗/赠礼/卸装）永不因袋满失败。
    @ProtoNumber(13) val equipmentInstance: EquipmentInstance? = null,
    @ProtoNumber(14) val stackedData: BagStackedData? = null,
    @ProtoNumber(15) val manualInstance: ManualInstance? = null
) {
    val color: String get() = GameConfig.Rarity.getColor(rarity)
    val rarityName: String get() = GameConfig.Rarity.getName(rarity)

    /** 是否已物化（独立存储）；false = 老存档引用式条目，等待物化迁移 */
    val isMaterialized: Boolean get() = equipmentInstance != null || stackedData != null || manualInstance != null
}

/**
 * 储物袋堆叠类物品的取回/物化重建补充数据（名称/品级/数量/效果均在 [StorageBagItem] 顶层）。
 * 独立存储后仅补装备/功法的重建属性——取回（没收）与死亡/逐出物化时无需依赖物品模板。
 */
@Keep
@Serializable
data class BagStackedData(
    /** 装备/功法境界要求（取回重建 EquipmentStack/ManualStack 用） */
    @ProtoNumber(1) val minRealm: Int = 0,
    /** 装备槽位名（EquipmentSlot.name，取回重建用） */
    @ProtoNumber(2) val slot: String = "",
    /** 功法类型（ManualType.name，取回重建用） */
    @ProtoNumber(3) val manualType: String = ""
)

@Keep
@Serializable
data class ItemEffect(
    @ProtoNumber(38) val tier: Int = 0,  // 丹药品阶，用于永久属性丹去重
    @ProtoNumber(1) val cultivationSpeedPercent: Double = 0.0,
    @ProtoNumber(2) val skillExpSpeedPercent: Double = 0.0,
    // reserved 3,8;（nurtureSpeedPercent/nurtureAdd 孕养类加成丹效果，R11 退役——
    // 旧档字节按未知字段忽略，禁复用）
    @ProtoNumber(4) val breakthroughChance: Double = 0.0,
    @ProtoNumber(5) val targetRealm: Int = 0,
    @ProtoNumber(6) val cultivationAdd: Int = 0,
    @ProtoNumber(7) val skillExpAdd: Int = 0,
    @ProtoNumber(9) val healMaxHpPercent: Double = 0.0,
    @ProtoNumber(10) val mpRecoverMaxMpPercent: Double = 0.0,
    @ProtoNumber(11) val hpAdd: Int = 0,
    @ProtoNumber(12) val mpAdd: Int = 0,
    @ProtoNumber(13) val extendLife: Int = 0,
    // ── 攻防加成单列口径（B1，方案 §15.4）──
    // 新写入字段为 attackAdd(39)/defenseAdd(40)；旧物法四列（14–17）保留声明
    // 仅作旧档归一化读取（不再写入；新档恒 0），消费点一律读 [attackAddTotal]/
    // [defenseAddTotal]。退役编号禁复用。
    @Deprecated("旧物攻加成，仅旧档归一化读取；改用 attackAddTotal")
    @ProtoNumber(14) val physicalAttackAdd: Int = 0,
    @Deprecated("旧法攻加成，仅旧档归一化读取；改用 attackAddTotal")
    @ProtoNumber(15) val magicAttackAdd: Int = 0,
    @Deprecated("旧物防加成，仅旧档归一化读取；改用 defenseAddTotal")
    @ProtoNumber(16) val physicalDefenseAdd: Int = 0,
    @Deprecated("旧法防加成，仅旧档归一化读取；改用 defenseAddTotal")
    @ProtoNumber(17) val magicDefenseAdd: Int = 0,
    @ProtoNumber(39) val attackAdd: Int = 0,
    @ProtoNumber(40) val defenseAdd: Int = 0,
    @ProtoNumber(18) val speedAdd: Int = 0,
    @ProtoNumber(19) val critRateAdd: Double = 0.0,
    @ProtoNumber(20) val critEffectAdd: Double = 0.0,
    @ProtoNumber(21) val intelligenceAdd: Int = 0,
    @ProtoNumber(22) val charmAdd: Int = 0,
    // reserved 23;（loyaltyAdd 字段号已退役，禁止复用）
    @ProtoNumber(24) val comprehensionAdd: Int = 0,
    @ProtoNumber(25) val artifactRefiningAdd: Int = 0,
    @ProtoNumber(26) val pillRefiningAdd: Int = 0,
    @ProtoNumber(27) val spiritPlantingAdd: Int = 0,
    @ProtoNumber(28) val teachingAdd: Int = 0,
    @ProtoNumber(29) val moralityAdd: Int = 0,
    @ProtoNumber(88) val miningAdd: Int = 0,
    @ProtoNumber(30) val revive: Boolean = false,
    @ProtoNumber(31) val clearAll: Boolean = false,
    @ProtoNumber(32) val isAscension: Boolean = false,
    @ProtoNumber(33) val duration: Int = 0,
    @ProtoNumber(34) val cannotStack: Boolean = true,
    @ProtoNumber(35) val minRealm: Int = 9,
    @ProtoNumber(36) val pillCategory: String = "",
    @ProtoNumber(37) val pillType: String = ""
) {
    /**
     * 有效攻击加成：新单列值 + 旧物法两列归一化（旧档 14/15 有值、新档恒 0，
     * 线性相加对两代存档皆正确）。
     */
    val attackAddTotal: Int get() = attackAdd + physicalAttackAdd + magicAttackAdd

    /** 有效防御加成：口径同 [attackAddTotal] */
    val defenseAddTotal: Int get() = defenseAdd + physicalDefenseAdd + magicDefenseAdd
}

@Keep
@Serializable
data class RewardSelectedItem(
    val id: String,
    val type: String,
    val name: String,
    val rarity: Int,
    val quantity: Int,
    val grade: String? = null
)

@Keep
@Deprecated("装备孕养已随 B3 升级体系退役（等级随 EquipmentInstance.growth 单点）；保留声明仅供旧档 DiscipleSurrogate 反序列化，禁参与任何新逻辑")
@Serializable
data class EquipmentNurtureData(
    @ProtoNumber(1) val equipmentId: String,
    @ProtoNumber(2) val rarity: Int,
    @ProtoNumber(3) val nurtureLevel: Int = 0,
    @ProtoNumber(4) val nurtureProgress: Double = 0.0
)
