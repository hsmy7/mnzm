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
 * 包含丹药临时属性加成和持续时间，共11个字段
 *
 * 攻防加成单列口径（B1）：[pillAttackBonus]/[pillDefenseBonus] 各一列；
 * 旧物法四列（36–39 号段）退役为只读归一化源（见 [DiscipleSerializer]）。
 * 孕养速度加成（pillNurtureSpeedBonus，47 号）已随 R11 孕养丹退役删除。
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
    var pillEffectDuration: Int = 0,
    // 当前生效中的临时/持续丹药效果，按 pillType 记录
    @Ignore
    var activePillTypes: Set<String> = emptySet(),
    // 旧字段，仅用于旧存档反序列化
    var activePillCategory: String = ""
)

/**
 * 装备套装组件：四部位（头/身/手/脚，按显示序）+ 储物袋资源。
 * 等级/词条只存 [EquipmentInstance.growth] 单点。
 */
@Serializable
@Immutable
data class EquipmentSet(
    var headId: String = "",
    var bodyId: String = "",
    var handsId: String = "",
    var feetId: String = "",
    var weaponId: String = "",
    var legsId: String = "",

    var storageBagItems: List<StorageBagItem> = emptyList(),
    var storageBagSpiritStones: Long = 0,
    var spiritStones: Int = 0
) {
    val hasEquippedItems: Boolean
        get() = EquipmentSlot.displayOrder.any { slotName(it).isNotEmpty() }

    /** 已装备的部位 id（非空项，顺序与 [EquipmentSlot.displayOrder] 一致） */
    val equippedItemIds: List<String>
        get() = EquipmentSlot.displayOrder.mapNotNull { slotName(it).takeIf(String::isNotEmpty) }

    /** 按部位取装备 id */
    fun slotId(part: EquipmentSlot): String = slotName(part)

    /** 按部位写装备 id */
    fun setSlotId(part: EquipmentSlot, id: String) = setSlotName(part, id)

    /** 清空全部部位（迁移/规则清理用） */
    fun clearedSlots(): EquipmentSet = copy(
        headId = "", bodyId = "", handsId = "", feetId = "", weaponId = "", legsId = ""
    )

    private fun slotName(part: EquipmentSlot): String = when (part) {
        EquipmentSlot.HEAD -> headId
        EquipmentSlot.BODY -> bodyId
        EquipmentSlot.HANDS -> handsId
        EquipmentSlot.FEET -> feetId
    }

    private fun setSlotName(part: EquipmentSlot, id: String) {
        when (part) {
            EquipmentSlot.HEAD -> headId = id
            EquipmentSlot.BODY -> bodyId = id
            EquipmentSlot.HANDS -> handsId = id
            EquipmentSlot.FEET -> feetId = id
        }
    }
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
