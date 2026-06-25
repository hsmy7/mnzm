package com.xianxia.sect.core.model

import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import androidx.room.Ignore
import kotlinx.serialization.Serializable

/**
 * 弟子战斗属性组件
 * 包含基础战斗属性、浮动系数、战斗统计等共18个字段
 */
@Keep
@Serializable
@Immutable
data class CombatAttributes(
    // 基础战斗属性（创建时根据浮动系数计算并存储）
    var baseHp: Int = 120,
    var baseMp: Int = 60,
    var basePhysicalAttack: Int = 12,
    var baseMagicAttack: Int = 12,
    var basePhysicalDefense: Int = 10,
    var baseMagicDefense: Int = 8,
    var baseSpeed: Int = 15,

    // 战斗属性独立浮动百分比（±30%，精确到1%）
    var hpVariance: Int = 0,
    var mpVariance: Int = 0,
    var physicalAttackVariance: Int = 0,
    var magicAttackVariance: Int = 0,
    var physicalDefenseVariance: Int = 0,
    var magicDefenseVariance: Int = 0,
    var speedVariance: Int = 0,

    // 战斗统计
    var totalCultivation: Long = 0,
    var breakthroughCount: Int = 0,
    var breakthroughFailCount: Int = 0,

    // 当前血量/灵力（-1表示满血，用于向后兼容）
    var currentHp: Int = -1,
    var currentMp: Int = -1
) {
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
            return BaseCombatStats(
                baseHp = (120 * (1.0 + hpVariance / 100.0)).toInt(),
                baseMp = (60 * (1.0 + mpVariance / 100.0)).toInt(),
                basePhysicalAttack = (12 * (1.0 + physicalAttackVariance / 100.0)).toInt(),
                baseMagicAttack = (12 * (1.0 + magicAttackVariance / 100.0)).toInt(),
                basePhysicalDefense = (10 * (1.0 + physicalDefenseVariance / 100.0)).toInt(),
                baseMagicDefense = (8 * (1.0 + magicDefenseVariance / 100.0)).toInt(),
                baseSpeed = (15 * (1.0 + speedVariance / 100.0)).toInt()
            )
        }
    }
}

/**
 * 丹药效果组件
 * 包含丹药临时属性加成和持续时间，共8个字段
 */
@Keep
@Serializable
@Immutable
data class PillEffects(
    var pillPhysicalAttackBonus: Int = 0,
    var pillMagicAttackBonus: Int = 0,
    var pillPhysicalDefenseBonus: Int = 0,
    var pillMagicDefenseBonus: Int = 0,
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
    @Deprecated("使用 activePillTypes 替代")
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

    var autoEquipFromWarehouse: Boolean = false,
    var storageBagItems: List<StorageBagItem> = emptyList(),
    var storageBagSpiritStones: Long = 0,
    var spiritStones: Int = 0
) {
    val hasEquippedItems: Boolean get() = listOf(weaponId, armorId, bootsId, accessoryId).any { it.isNotEmpty() }
    val equippedItemIds: List<String> get() = listOf(weaponId, armorId, bootsId, accessoryId).filter { it.isNotEmpty() }
}

/**
 * 社交关系组件
 * 包含伴侣、父母、子女、师徒等关系数据，共7个字段
 */
@Serializable
data class SocialData(
    var partnerId: String? = null,
    var partnerSectId: String? = null,
    var parentId1: String? = null,
    var parentId2: String? = null,
    var lastChildYear: Int = 0,
    var childBirthMonth: Int? = null,
    var griefEndYear: Int? = null,
    /** 师父弟子ID（师徒关系永久绑定，仅一方死亡方可解绑；弟子最多1名师父） */
    var masterId: String? = null
) {
    val hasPartner: Boolean get() = partnerId != null
    val hasMaster: Boolean get() = masterId != null
}

/**
 * 技能属性组件
 * 包含各项技能值和俸禄统计，共11个字段
 */
@Serializable
data class SkillStats(
    // 技能属性值
    var intelligence: Int = 50,
    var charm: Int = 50,
    var loyalty: Int = 50,
    var comprehension: Int = 50,
    var artifactRefining: Int = 50,
    var pillRefining: Int = 50,
    var spiritPlanting: Int = 50,
    var mining: Int = 50,
    var teaching: Int = 50,
    var morality: Int = 50,

    // 年俸累计次数
    var salaryPaidCount: Int = 0,
    var salaryMissedCount: Int = 0
) {
    val comprehensionSpeedBonus: Double get() = 1.0 + (comprehension - 50) * 0.02
}

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
    // 寿命丹按 pillType 去重（所有品阶共享）
    @Ignore
    var usedExtendLifePillTypes: Set<String> = emptySet(),
    // 旧字段，仅用于旧存档反序列化和迁移
    var usedFunctionalPillTypes: List<String> = emptyList(),
    var usedExtendLifePillIds: List<String> = emptyList(),
    var recruitedMonth: Int = 0,
    var hasReviveEffect: Boolean = false,
    var hasClearAllEffect: Boolean = false,
    var lastTheftMonth: Int = 0  // 最近偷盗月份（绝对月），0=从未
)
