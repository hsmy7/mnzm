package com.xianxia.sect.core.model

import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.util.GameRandom
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber


/**
 * 弟子数据模型（Room Entity）
 *
 * ## 推荐访问路径
 *
 * 为保持代码清晰和可维护性，推荐直接通过 @Embedded 子组件访问属性：
 *
 * **战斗属性** → `disciple.combat.baseHp`, `disciple.combat.basePhysicalAttack` 等
 * **丹药效果** → `disciple.pillEffects.pillHpBonus`, `disciple.pillEffects.pillEffectDuration` 等
 * **装备数据** → `disciple.equipment.weaponId`, `disciple.equipment.spiritStones` 等
 * **社交关系** → `disciple.social.partnerId`, `disciple.social.parentId1` 等
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
 * - getTalentEffects → DiscipleStatsProvider.getTalentEffects
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
        Index(value = ["discipleType"]),
        Index(value = ["loyalty"]),
        Index(value = ["age"])
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

    var age: Int = 16,
    var lifespan: Int = 80,
    var isAlive: Boolean = true,

    var gender: String = "male",

    var portraitRes: String = "",

    var manualIds: List<String> = emptyList(),
    var talentIds: List<String> = emptyList(),
    var physiqueIds: List<String> = emptyList(),
    var affixIds: List<String> = emptyList(),

    var manualMasteries: Map<String, Int> = emptyMap(),

    var status: DiscipleStatus = DiscipleStatus.IDLE,
    var statusData: Map<String, String> = emptyMap(),

    var cultivationSpeedBonus: Double = 0.0,
    var cultivationSpeedDuration: Int = 0,

    var discipleType: String = "outer",

    var soulPower: Int = 0,

    @ColumnInfo(defaultValue = "0")
    var cultivationCompletionMonth: Int = 0,
    @ColumnInfo(defaultValue = "1")
    var cultivationCompletionPhase: Int = 1,
    @ColumnInfo(defaultValue = "0")
    var manualCompletionMonth: Int = 0,
    @ColumnInfo(defaultValue = "1")
    var manualCompletionPhase: Int = 1,
    @ColumnInfo(defaultValue = "0")
    var equipmentNurturingCompletionMonth: Int = 0,
    @ColumnInfo(defaultValue = "1")
    var equipmentNurturingCompletionPhase: Int = 1,

    // ========== @Embedded 组件 ==========
    // 委托扩展属性见 DiscipleDelegates.kt（66个） + 本文件（monthlyUsedPillIds）
    @Embedded
    var combat: CombatAttributes = CombatAttributes(),

    @Embedded
    var pillEffects: PillEffects = PillEffects(),

    @Embedded
    var equipment: EquipmentSet = EquipmentSet(),

    @Embedded(prefix = "social_")
    var social: SocialData = SocialData(),

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

    // ==================== 计算属性（保持不变）====================

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
        val nextBase = GameConfig.Realm.get(realm - 1).cultivationBase
        val maxLayers = GameConfig.Realm.get(realm).maxLayers
        return base + (realmLayer - 1) * (nextBase - base).toDouble() / maxLayers
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

    /** 当前生命百分比 */
    val hpPercent: Float get() = if (maxHp > 0) currentHp.toFloat() / maxHp else 0f
    val mpPercent: Float get() = if (maxMp > 0) currentMp.toFloat() / maxMp else 0f

    val equippedItems: Map<EquipmentSlot, EquipmentInstance?> get() = emptyMap()
    val learnedManuals: List<ManualInstance> get() = emptyList()

    val genderName: String get() = if (gender == "male") "男" else "女"
    val genderSymbol: String get() = if (gender == "male") "\u2642" else "\u2640"
    val hasPartner: Boolean get() = social.hasPartner

    val comprehensionSpeedBonus: Double get() = skills.comprehensionSpeedBonus

    // ==================== copyWith 已删除 ====================
    // 组件表架构下不再需要 copyWith，所有字段更新通过 DiscipleTables 直接操作。
    // 如需构造新 Disciple 对象，请使用 Disciple(...) 构造函数或 DiscipleTables.assemble()。

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
            return CombatAttributes.calculateBaseStatsWithVariance(
                hpVariance, mpVariance, physicalAttackVariance, magicAttackVariance,
                physicalDefenseVariance, magicDefenseVariance, speedVariance
            )
        }

        fun fixBaseStats(disciple: Disciple): Disciple {
            val needsFix = disciple.combat.hpVariance == 0 &&
                           disciple.combat.mpVariance == 0 &&
                           disciple.combat.physicalAttackVariance == 0 &&
                           disciple.combat.magicAttackVariance == 0 &&
                           disciple.combat.physicalDefenseVariance == 0 &&
                           disciple.combat.magicDefenseVariance == 0 &&
                           disciple.combat.speedVariance == 0 &&
                           disciple.combat.baseHp == 120

            if (!needsFix) return disciple

            val hpVariance = GameRandom.nextInt(-30, 31)
            val mpVariance = GameRandom.nextInt(-30, 31)
            val physicalAttackVariance = GameRandom.nextInt(-30, 31)
            val magicAttackVariance = GameRandom.nextInt(-30, 31)
            val physicalDefenseVariance = GameRandom.nextInt(-30, 31)
            val magicDefenseVariance = GameRandom.nextInt(-30, 31)
            val speedVariance = GameRandom.nextInt(-30, 31)

            val baseStats = calculateBaseStatsWithVariance(
                hpVariance, mpVariance, physicalAttackVariance, magicAttackVariance,
                physicalDefenseVariance, magicDefenseVariance, speedVariance
            )

            return disciple.copy(
                combat = disciple.combat.copy(
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
            )
        }
    }

    // ==================== 属性计算方法（晚绑定 DiscipleStatsProvider）====================

    fun getBaseStats(): DiscipleStats = DiscipleAggregate.statsProvider.getBaseStats(this)

    fun getTalentEffects(): Map<String, Double> = DiscipleAggregate.statsProvider.getTalentEffects(this)

    fun getStatsWithEquipment(equipments: Map<String, EquipmentInstance>): DiscipleStats = DiscipleAggregate.statsProvider.getStatsWithEquipment(this, equipments)

    fun getFinalStats(
        equipments: Map<String, EquipmentInstance>,
        manuals: Map<String, ManualInstance>,
        manualProficiencies: Map<String, ManualProficiencyData> = emptyMap(),
        bloodRefinementPct: BloodRefinementPctTotal? = null
    ): DiscipleStats = DiscipleAggregate.statsProvider.getFinalStats(
        this, equipments, manuals, manualProficiencies, bloodRefinementPct
    )

    fun calculateCultivationSpeed(manuals: Map<String, ManualInstance> = emptyMap(), manualProficiencies: Map<String, ManualProficiencyData> = emptyMap(), buildingBonus: Double = 1.0, additionalBonus: Double = 0.0, preachingElderBonus: Double = 0.0, preachingMastersBonus: Double = 0.0, cultivationSubsidyBonus: Double = 0.0, parentCultivationBonus: Double = 0.0, griefCultivationSpeedPenalty: Double = 0.0): Double = DiscipleAggregate.statsProvider.calculateCultivationSpeed(this, manuals, manualProficiencies, buildingBonus, additionalBonus, preachingElderBonus, preachingMastersBonus, cultivationSubsidyBonus, parentCultivationBonus, griefCultivationSpeedPenalty)

    /** 判断弟子是否可以突破 */
    fun canBreakthrough(): Boolean = cultivation >= maxCultivation

    fun getBreakthroughChance(innerElderComprehension: Int = 0, outerElderComprehension: Int = 0, pillBonus: Double = 0.0, adBonus: Double = 0.0, griefBreakthroughPenalty: Double = 0.0, masterDiscipleBonus: Double = 0.0): Double =
        DiscipleAggregate.statsProvider.getBreakthroughChance(this, innerElderComprehension, outerElderComprehension, pillBonus, adBonus, griefBreakthroughPenalty, masterDiscipleBonus)

    // ==================== 转换方法（用于迁移到 DiscipleAggregate）====================

    /**
     * 将此单表实体转换为 [DiscipleAggregate] 多表结构
     *
     * 此方法用于从遗留代码迁移到新的多表架构。
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
    IDLE, DEACONING, MINING, STUDYING, PREACHING, MANAGING, LAW_ENFORCING, ON_MISSION, REFLECTING, GARRISONING, IN_TEAM, PATROLLING, REFINING, ALCHEMY, FORGE, SPIRIT_PLANTING, DEAD,
    SECRET_REALM, WAREHOUSE_GARRISON;

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
        REFINING -> "血炼池中"
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
}

@Keep
@Serializable
data class Talent(
    val id: String,
    val name: String,
    val description: String,
    val rarity: Int,
    val effects: Map<String, Double>,
    val isNegative: Boolean = false,
    val positionBonus: PositionBonus? = null
) {
    val color: String get() = when {
        isNegative -> "#9E9E9E"
        rarity == 1 -> "#4CAF50"
        rarity == 2 -> "#2196F3"
        rarity == 3 -> "#E74C3C"
        else -> "#4CAF50"
    }
    val rarityName: String get() = when {
        isNegative -> "负面"
        rarity == 1 -> "下品"
        rarity == 2 -> "中品"
        rarity == 3 -> "上品"
        else -> "下品"
    }
}

/** 职务职能效果加成：拥有对应天赋/词条的弟子担任职务时，该职务职能效果获得额外百分比加成（乘算） */
@Keep
@Serializable
data class PositionBonus(
    val slotType: ElderSlotType,
    val effectBonus: Double
)

/** 体质：修炼速度加成 + 战斗伤害特殊加成（独立乘算） */
@Keep
@Serializable
data class Physique(
    val id: String,
    val name: String,
    val description: String,
    val rarity: Int,
    val cultivationSpeedBonus: Double,
    val damageAmplification: Double,
    val damageReduction: Double,
    val critDamageBonus: Double,
    val defenseBonus: Double,
    val isNegative: Boolean = false
) {
    val color: String get() = when {
        isNegative -> "#9E9E9E"
        rarity == 1 -> "#4CAF50"
        rarity == 2 -> "#2196F3"
        rarity == 3 -> "#E74C3C"
        else -> "#4CAF50"
    }
    val rarityName: String get() = when {
        isNegative -> "负面"
        rarity == 1 -> "下品"
        rarity == 2 -> "中品"
        rarity == 3 -> "上品"
        else -> "下品"
    }
}

/** 词条：通用加成，覆盖基础属性/战斗属性/职务/战斗伤害特殊/修炼速度所有加成类型 */
@Keep
@Serializable
data class Affix(
    val id: String,
    val name: String,
    val description: String,
    val rarity: Int,
    val effects: Map<String, Double>,
    val isNegative: Boolean = false,
    val positionBonus: PositionBonus? = null
) {
    val color: String get() = when {
        isNegative -> "#9E9E9E"
        rarity == 1 -> "#4CAF50"
        rarity == 2 -> "#2196F3"
        rarity == 3 -> "#E74C3C"
        else -> "#4CAF50"
    }
    val rarityName: String get() = when {
        isNegative -> "负面"
        rarity == 1 -> "下品"
        rarity == 2 -> "中品"
        rarity == 3 -> "上品"
        else -> "下品"
    }
}

@Keep
@Serializable
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
    val loyalty: Int = 0,
    val comprehension: Int = 0,
    val teaching: Int = 0,
    val morality: Int = 0,
    val mining: Int = 0,
    val spiritPlanting: Int = 0,
    val artifactRefining: Int = 0,
    val pillRefining: Int = 0
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
            loyalty = loyalty + other.loyalty,
            comprehension = comprehension + other.comprehension,
            teaching = teaching + other.teaching,
            morality = morality + other.morality,
            mining = mining + other.mining,
            spiritPlanting = spiritPlanting + other.spiritPlanting,
            artifactRefining = artifactRefining + other.artifactRefining,
            pillRefining = pillRefining + other.pillRefining
        )
    }
}

@Keep
@Serializable
data class BaseCombatStats(
    val baseHp: Int = 120,
    val baseMp: Int = 60,
    val basePhysicalAttack: Int = 12,
    val baseMagicAttack: Int = 12,
    val basePhysicalDefense: Int = 10,
    val baseMagicDefense: Int = 8,
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
    // 2026-08-04 修复：此前 5 个字段 @Transient 不持久化，读档后储物袋丹药效果/品级/遗忘冷却全部丢失，
    // 导致自动服药失效、丹药详情空显示。编号 8-12 与旧格式 SerializableStorageBagItem 完全一致。
    @ProtoNumber(8) val effect: ItemEffect? = null,
    @ProtoNumber(9) val grade: String? = null,
    @ProtoNumber(10) val forgetYear: Int? = null,
    @ProtoNumber(11) val forgetMonth: Int? = null,
    @ProtoNumber(12) val forgetPhase: Int? = null,
    // 2026-08-08 D-03 储物袋独立存储重构：袋条目自带数据，不再引用仓库堆叠。
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
    @ProtoNumber(3) val nurtureSpeedPercent: Double = 0.0,
    @ProtoNumber(4) val breakthroughChance: Double = 0.0,
    @ProtoNumber(5) val targetRealm: Int = 0,
    @ProtoNumber(6) val cultivationAdd: Int = 0,
    @ProtoNumber(7) val skillExpAdd: Int = 0,
    @ProtoNumber(8) val nurtureAdd: Int = 0,
    @ProtoNumber(9) val healMaxHpPercent: Double = 0.0,
    @ProtoNumber(10) val mpRecoverMaxMpPercent: Double = 0.0,
    @ProtoNumber(11) val hpAdd: Int = 0,
    @ProtoNumber(12) val mpAdd: Int = 0,
    @ProtoNumber(13) val extendLife: Int = 0,
    @ProtoNumber(14) val physicalAttackAdd: Int = 0,
    @ProtoNumber(15) val magicAttackAdd: Int = 0,
    @ProtoNumber(16) val physicalDefenseAdd: Int = 0,
    @ProtoNumber(17) val magicDefenseAdd: Int = 0,
    @ProtoNumber(18) val speedAdd: Int = 0,
    @ProtoNumber(19) val critRateAdd: Double = 0.0,
    @ProtoNumber(20) val critEffectAdd: Double = 0.0,
    @ProtoNumber(21) val intelligenceAdd: Int = 0,
    @ProtoNumber(22) val charmAdd: Int = 0,
    @ProtoNumber(23) val loyaltyAdd: Int = 0,
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
)

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
@Serializable
data class EquipmentNurtureData(
    @ProtoNumber(1) val equipmentId: String,
    @ProtoNumber(2) val rarity: Int,
    @ProtoNumber(3) val nurtureLevel: Int = 0,
    @ProtoNumber(4) val nurtureProgress: Double = 0.0
)
