package com.xianxia.sect.core.model

import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Ignore
import kotlinx.serialization.Serializable

/**
 * 弟子战斗属性组件
 * 包含基础战斗属性、浮动系数、战斗统计等共15个字段
 *
 * 属性单列口径（装备重构 B1，方案 §15）：基础攻防各只有一列
 * [baseAttack]/[baseDefense]，物理/法术之分由三条通道承载——
 * 普攻 [innateDamageType]、技能 damageType、类型增伤/减伤分桶（战斗层）。
 */
@Keep
@Serializable
@Immutable
data class CombatAttributes(
    // 基础战斗属性（创建时根据浮动系数计算并存储）
    var baseHp: Int = 120,
    var baseMp: Int = 60,
    @ColumnInfo(defaultValue = "0")
    var baseAttack: Int = 24,
    @ColumnInfo(defaultValue = "0")
    var baseDefense: Int = 18,
    var baseSpeed: Int = 15,

    // 战斗属性独立浮动百分比（±30%，精确到1%）
    var hpVariance: Int = 0,
    var mpVariance: Int = 0,
    @ColumnInfo(defaultValue = "0")
    var attackVariance: Int = 0,
    @ColumnInfo(defaultValue = "0")
    var defenseVariance: Int = 0,
    var speedVariance: Int = 0,

    // 战斗统计
    var totalCultivation: Long = 0,
    var breakthroughCount: Int = 0,
    var breakthroughFailCount: Int = 0,

    // 当前血量/灵力（-1表示满血，用于向后兼容）
    var currentHp: Int = -1,
    var currentMp: Int = -1,

    /**
     * 固有伤害属性（[DamageType].name，"PHYSICAL"/"MAGIC"）。
     * 来源是角色模板（创建时继承、创建后不变）；空串 = 未派生
     * （存量旧弟子），读取时按 templateId → 模板、缺失按首灵根派生兜底
     * （金/土→物理，水/木/火→法术，见 [InnateDamageType.derive]）。
     */
    @ColumnInfo(defaultValue = "")
    var innateDamageType: String = ""
) {
    companion object {
        fun calculateBaseStatsWithVariance(
            hpVariance: Int,
            mpVariance: Int,
            attackVariance: Int,
            defenseVariance: Int,
            speedVariance: Int
        ): BaseCombatStats {
            return BaseCombatStats(
                baseHp = (120 * (1.0 + hpVariance / 100.0)).toInt(),
                baseMp = (60 * (1.0 + mpVariance / 100.0)).toInt(),
                baseAttack = (24 * (1.0 + attackVariance / 100.0)).toInt(),
                baseDefense = (18 * (1.0 + defenseVariance / 100.0)).toInt(),
                baseSpeed = (15 * (1.0 + speedVariance / 100.0)).toInt()
            )
        }
    }
}

/**
 * 丹药效果组件
 * 包含丹药临时属性加成和持续时间，共12个字段
 *
 * 攻防加成单列口径（B1）：[pillAttackBonus]/[pillDefenseBonus] 各一列；
 * 旧物法四列（36–39 号段）退役为只读归一化源（见 [DiscipleSerializer]）。
 */
@Keep
@Serializable
@Immutable
data class PillEffects(
    @ColumnInfo(defaultValue = "0")
    var pillAttackBonus: Int = 0,
    @ColumnInfo(defaultValue = "0")
    var pillDefenseBonus: Int = 0,
    var pillHpBonus: Int = 0,
    var pillMpBonus: Int = 0,
    var pillSpeedBonus: Int = 0,
    var pillCritRateBonus: Double = 0.0,
    var pillCritEffectBonus: Double = 0.0,
    var pillCultivationSpeedBonus: Double = 0.0,
    var pillSkillExpSpeedBonus: Double = 0.0,
    var pillNurtureSpeedBonus: Double = 0.0,
    var pillEffectDuration: Int = 0,
    // 当前生效中的临时/持续丹药效果，按 pillType 记录
    @Ignore
    var activePillTypes: Set<String> = emptySet(),
    // 旧字段，仅用于旧存档反序列化
    var activePillCategory: String = ""
)

/**
 * 装备套装组件
 * 包含装备ID、培养数据、储物袋资源等共14个字段
 */
@Serializable
@Immutable
data class EquipmentSet(
    var weaponId: String = "",
    var armorId: String = "",
    var bootsId: String = "",
    var accessoryId: String = "",

    // 武器孕育数据
    var weaponNurture: EquipmentNurtureData = EquipmentNurtureData("", 0),
    // 护甲孕育数据
    var armorNurture: EquipmentNurtureData = EquipmentNurtureData("", 0),
    // 鞋子孕育数据
    var bootsNurture: EquipmentNurtureData = EquipmentNurtureData("", 0),
    // 饰品孕育数据
    var accessoryNurture: EquipmentNurtureData = EquipmentNurtureData("", 0),

    var storageBagItems: List<StorageBagItem> = emptyList(),
    var storageBagSpiritStones: Long = 0,
    var spiritStones: Int = 0
) {
    val hasEquippedItems: Boolean get() = listOf(weaponId, armorId, bootsId, accessoryId).any { it.isNotEmpty() }
    val equippedItemIds: List<String> get() = listOf(weaponId, armorId, bootsId, accessoryId).filter { it.isNotEmpty() }
}

/**
 * 技能属性组件
 * 包含各项技能值和俸禄统计，共15个字段
 */
@Serializable
data class SkillStats(
    // 技能属性值
    var intelligence: Int = 50,
    var charm: Int = 50,
    var comprehension: Int = 50,
    var artifactRefining: Int = 50,
    var pillRefining: Int = 50,
    var spiritPlanting: Int = 50,
    var mining: Int = 50,
    var teaching: Int = 50,
    var morality: Int = 50,

    // 年俸累计次数
    var salaryPaidCount: Int = 0,
    var salaryMissedCount: Int = 0,

    // 炼丹师/锻造师职业（0=无职业，1~5=炼丹师~丹圣 / 炼器师~器圣）
    var alchemyLevel: Int = 0,
    // 当前解锁最高阶的成功炼制次数（晋升炼丹师等级用，低阶不计数）
    var alchemyPromotionCount: Int = 0,
    var forgeLevel: Int = 0,
    // 当前解锁最高阶的成功锻造次数（晋升炼器师等级用，低阶不计数）
    var forgePromotionCount: Int = 0
)

/**
 * 使用追踪组件
 * 包含丹药使用记录、招募时间、功能标记等，共5个字段
 */
@Serializable
@Immutable
data class UsageTracking(
    // 永久属性丹已服用的去重 key："tier#effectField"
    @Ignore
    var usedPermanentPillKeys: Set<String> = emptySet(),
    // 旧字段，仅用于旧存档反序列化和迁移
    var usedFunctionalPillTypes: List<String> = emptyList(),
    var recruitedMonth: Int = 0,
    var hasReviveEffect: Boolean = false,
    var hasClearAllEffect: Boolean = false,
)
