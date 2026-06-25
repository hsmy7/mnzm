@file:Suppress("DEPRECATION")

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
@Serializable
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

    val spiritRootType: String = "metal",

    var age: Int = 16,
    var lifespan: Int = 80,
    var isAlive: Boolean = true,

    var gender: String = "male",

    var portraitRes: String = "",

    var manualIds: List<String> = emptyList(),
    var talentIds: List<String> = emptyList(),
    var manualMasteries: Map<String, Int> = emptyMap(),

    var status: DiscipleStatus = DiscipleStatus.IDLE,
    var statusData: Map<String, String> = emptyMap(),

    var cultivationSpeedBonus: Double = 0.0,
    var cultivationSpeedDuration: Int = 0,

    var discipleType: String = "outer",

    var autoLearnFromWarehouse: Boolean = false,

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

    fun getFinalStats(equipments: Map<String, EquipmentInstance>, manuals: Map<String, ManualInstance>, manualProficiencies: Map<String, ManualProficiencyData> = emptyMap()): DiscipleStats = DiscipleAggregate.statsProvider.getFinalStats(this, equipments, manuals, manualProficiencies)

    fun calculateCultivationSpeed(manuals: Map<String, ManualInstance> = emptyMap(), manualProficiencies: Map<String, ManualProficiencyData> = emptyMap(), buildingBonus: Double = 1.0, additionalBonus: Double = 0.0, preachingElderBonus: Double = 0.0, preachingMastersBonus: Double = 0.0, cultivationSubsidyBonus: Double = 0.0, parentCultivationBonus: Double = 0.0, griefCultivationSpeedPenalty: Double = 0.0): Double = DiscipleAggregate.statsProvider.calculateCultivationSpeed(this, manuals, manualProficiencies, buildingBonus, additionalBonus, preachingElderBonus, preachingMastersBonus, cultivationSubsidyBonus, parentCultivationBonus, griefCultivationSpeedPenalty)

    /** 判断弟子是否可以突破 */
    fun canBreakthrough(): Boolean = cultivation >= maxCultivation

    fun getBreakthroughChance(innerElderComprehension: Int = 0, outerElderComprehensionBonus: Double = 0.0, pillBonus: Double = 0.0, adBonus: Double = 0.0, griefBreakthroughPenalty: Double = 0.0, masterDiscipleBonus: Double = 0.0): Double =
        DiscipleAggregate.statsProvider.getBreakthroughChance(this, innerElderComprehension, outerElderComprehensionBonus, pillBonus, adBonus, griefBreakthroughPenalty, masterDiscipleBonus)

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
    IDLE, DEACONING, MINING, STUDYING, PREACHING, MANAGING, LAW_ENFORCING, ON_MISSION, REFLECTING, GARRISONING, IN_TEAM, PATROLLING, DEAD;

    val displayName: String get() = when (this) {
        IDLE -> "空闲中"
        DEACONING -> "执事中"
        MINING -> "采矿中"
        STUDYING -> "学习中"
        PREACHING -> "传道中"
        MANAGING -> "管理中"
        LAW_ENFORCING -> "执法中"
        ON_MISSION -> "任务中"
        REFLECTING -> "思过中"
        GARRISONING -> "驻守中"
        IN_TEAM -> "队伍中"
        PATROLLING -> "巡视中"
        DEAD -> "已死亡"
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

    /** 灵根修炼速度系数（1/灵根数）。仅用于 UI 显示兼容。 */
    val cultivationBonus: Double
        get() = 1.0 / types.size.coerceAtLeast(1)
}

@Keep
@Serializable
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
    val mining: Int = 0
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
            mining = mining + other.mining
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
    val itemId: String,
    val itemType: String,
    val name: String,
    val rarity: Int,
    val quantity: Int = 1,
    val obtainedYear: Int = 1,
    val obtainedMonth: Int = 1,
    val effect: ItemEffect? = null,
    val grade: String? = null,
    val forgetYear: Int? = null,
    val forgetMonth: Int? = null,
    val forgetPhase: Int? = null
) {
    val color: String get() = GameConfig.Rarity.getColor(rarity)
    val rarityName: String get() = GameConfig.Rarity.getName(rarity)
}

@Keep
@Serializable
data class ItemEffect(
    val tier: Int = 0,  // 丹药品阶，用于永久属性丹去重
    val cultivationSpeedPercent: Double = 0.0,
    val skillExpSpeedPercent: Double = 0.0,
    val nurtureSpeedPercent: Double = 0.0,
    val breakthroughChance: Double = 0.0,
    val targetRealm: Int = 0,
    val cultivationAdd: Int = 0,
    val skillExpAdd: Int = 0,
    val nurtureAdd: Int = 0,
    val healMaxHpPercent: Double = 0.0,
    val mpRecoverMaxMpPercent: Double = 0.0,
    val hpAdd: Int = 0,
    val mpAdd: Int = 0,
    val extendLife: Int = 0,
    val physicalAttackAdd: Int = 0,
    val magicAttackAdd: Int = 0,
    val physicalDefenseAdd: Int = 0,
    val magicDefenseAdd: Int = 0,
    val speedAdd: Int = 0,
    val critRateAdd: Double = 0.0,
    val critEffectAdd: Double = 0.0,
    val intelligenceAdd: Int = 0,
    val charmAdd: Int = 0,
    val loyaltyAdd: Int = 0,
    val comprehensionAdd: Int = 0,
    val artifactRefiningAdd: Int = 0,
    val pillRefiningAdd: Int = 0,
    val spiritPlantingAdd: Int = 0,
    val teachingAdd: Int = 0,
    val moralityAdd: Int = 0,
    val miningAdd: Int = 0,
    val revive: Boolean = false,
    val clearAll: Boolean = false,
    val isAscension: Boolean = false,
    val duration: Int = 0,
    val cannotStack: Boolean = true,
    val minRealm: Int = 9,
    val pillCategory: String = "",
    val pillType: String = ""
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
    val equipmentId: String,
    val rarity: Int,
    val nurtureLevel: Int = 0,
    val nurtureProgress: Double = 0.0
)
