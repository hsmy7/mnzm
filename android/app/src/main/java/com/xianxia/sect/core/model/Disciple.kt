@file:Suppress("DEPRECATION")

package com.xianxia.sect.core.model

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.DiscipleStatCalculator
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
 * ## 属性计算方法（已提取到 DiscipleStatCalculator）
 *
 * 复杂的业务计算逻辑已提取到 [DiscipleStatCalculator]，
 * 本类保留转发方法：
 * - [getBaseStats] → 委托给 [DiscipleStatCalculator.getBaseStats]
 * - [getFinalStats] → 委托给 [DiscipleStatCalculator.getFinalStats]
 * - [getStatsWithEquipment] → 委托给 [DiscipleStatCalculator.getStatsWithEquipment]
 * - [calculateCultivationSpeed] → 委托给 [DiscipleStatCalculator.calculateCultivationSpeed]
 * - [getBreakthroughChance] → 委托给 [DiscipleStatCalculator.getBreakthroughChance]
 * - [getTalentEffects] → 委托给 [DiscipleStatCalculator.getTalentEffects]
 */
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

    var manualIds: List<String> = emptyList(),
    var talentIds: List<String> = emptyList(),
    var manualMasteries: Map<String, Int> = emptyMap(),

    var status: DiscipleStatus = DiscipleStatus.IDLE,
    var statusData: Map<String, String> = emptyMap(),

    var cultivationSpeedBonus: Double = 0.0,
    var cultivationSpeedDuration: Int = 0,

    var discipleType: String = "outer",

    var autoLearnFromWarehouse: Boolean = false,

    // ========== @Embedded 组件 ==========
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
    // ==================== 委托属性：保持 API 向后兼容 ====================
    // 这些属性不在构造函数中，Room 不会将它们作为数据库列处理
    // （Room 只持久化主构造函数中声明的属性 + @Embedded 展开的字段）

    // --- CombatAttributes 委托 ---
    /** @deprecated 请改用 [combat.baseHp] */
    var baseHp: Int get() = combat.baseHp; set(value) { combat.baseHp = value }
    /** @deprecated 请改用 [combat.baseMp] */
    var baseMp: Int get() = combat.baseMp; set(value) { combat.baseMp = value }
    /** @deprecated 请改用 [combat.basePhysicalAttack] */
    var basePhysicalAttack: Int get() = combat.basePhysicalAttack; set(value) { combat.basePhysicalAttack = value }
    /** @deprecated 请改用 [combat.baseMagicAttack] */
    var baseMagicAttack: Int get() = combat.baseMagicAttack; set(value) { combat.baseMagicAttack = value }
    /** @deprecated 请改用 [combat.basePhysicalDefense] */
    var basePhysicalDefense: Int get() = combat.basePhysicalDefense; set(value) { combat.basePhysicalDefense = value }
    /** @deprecated 请改用 [combat.baseMagicDefense] */
    var baseMagicDefense: Int get() = combat.baseMagicDefense; set(value) { combat.baseMagicDefense = value }
    /** @deprecated 请改用 [combat.baseSpeed] */
    var baseSpeed: Int get() = combat.baseSpeed; set(value) { combat.baseSpeed = value }

    /** @deprecated 请改用 [combat.hpVariance] */
    var hpVariance: Int get() = combat.hpVariance; set(value) { combat.hpVariance = value }
    /** @deprecated 请改用 [combat.mpVariance] */
    var mpVariance: Int get() = combat.mpVariance; set(value) { combat.mpVariance = value }
    /** @deprecated 请改用 [combat.physicalAttackVariance] */
    var physicalAttackVariance: Int get() = combat.physicalAttackVariance; set(value) { combat.physicalAttackVariance = value }
    /** @deprecated 请改用 [combat.magicAttackVariance] */
    var magicAttackVariance: Int get() = combat.magicAttackVariance; set(value) { combat.magicAttackVariance = value }
    /** @deprecated 请改用 [combat.physicalDefenseVariance] */
    var physicalDefenseVariance: Int get() = combat.physicalDefenseVariance; set(value) { combat.physicalDefenseVariance = value }
    /** @deprecated 请改用 [combat.magicDefenseVariance] */
    var magicDefenseVariance: Int get() = combat.magicDefenseVariance; set(value) { combat.magicDefenseVariance = value }
    /** @deprecated 请改用 [combat.speedVariance] */
    var speedVariance: Int get() = combat.speedVariance; set(value) { combat.speedVariance = value }

    /** @deprecated 请改用 [combat.totalCultivation] */
    var totalCultivation: Long get() = combat.totalCultivation; set(value) { combat.totalCultivation = value }
    /** @deprecated 请改用 [combat.breakthroughCount] */
    var breakthroughCount: Int get() = combat.breakthroughCount; set(value) { combat.breakthroughCount = value }
    /** @deprecated 请改用 [combat.breakthroughFailCount] */
    var breakthroughFailCount: Int get() = combat.breakthroughFailCount; set(value) { combat.breakthroughFailCount = value }

    /** @deprecated 请改用 [combat.currentHp] */
    var currentHp: Int get() = combat.currentHp; set(value) { combat.currentHp = value }
    /** @deprecated 请改用 [combat.currentMp] */
    var currentMp: Int get() = combat.currentMp; set(value) { combat.currentMp = value }

    // --- PillEffects 委托 ---
    /** @deprecated 请改用 [pillEffects.pillPhysicalAttackBonus] */
    var pillPhysicalAttackBonus: Int get() = pillEffects.pillPhysicalAttackBonus; set(value) { pillEffects.pillPhysicalAttackBonus = value }
    /** @deprecated 请改用 [pillEffects.pillMagicAttackBonus] */
    var pillMagicAttackBonus: Int get() = pillEffects.pillMagicAttackBonus; set(value) { pillEffects.pillMagicAttackBonus = value }
    /** @deprecated 请改用 [pillEffects.pillPhysicalDefenseBonus] */
    var pillPhysicalDefenseBonus: Int get() = pillEffects.pillPhysicalDefenseBonus; set(value) { pillEffects.pillPhysicalDefenseBonus = value }
    /** @deprecated 请改用 [pillEffects.pillMagicDefenseBonus] */
    var pillMagicDefenseBonus: Int get() = pillEffects.pillMagicDefenseBonus; set(value) { pillEffects.pillMagicDefenseBonus = value }
    /** @deprecated 请改用 [pillEffects.pillHpBonus] */
    var pillHpBonus: Int get() = pillEffects.pillHpBonus; set(value) { pillEffects.pillHpBonus = value }
    /** @deprecated 请改用 [pillEffects.pillMpBonus] */
    var pillMpBonus: Int get() = pillEffects.pillMpBonus; set(value) { pillEffects.pillMpBonus = value }
    /** @deprecated 请改用 [pillEffects.pillSpeedBonus] */
    var pillSpeedBonus: Int get() = pillEffects.pillSpeedBonus; set(value) { pillEffects.pillSpeedBonus = value }
    /** @deprecated 请改用 [pillEffects.pillEffectDuration] */
    var pillEffectDuration: Int get() = pillEffects.pillEffectDuration; set(value) { pillEffects.pillEffectDuration = value }
    /** @deprecated 请改用 [pillEffects.pillCritRateBonus] */
    var pillCritRateBonus: Double get() = pillEffects.pillCritRateBonus; set(value) { pillEffects.pillCritRateBonus = value }
    /** @deprecated 请改用 [pillEffects.pillCritEffectBonus] */
    var pillCritEffectBonus: Double get() = pillEffects.pillCritEffectBonus; set(value) { pillEffects.pillCritEffectBonus = value }
    /** @deprecated 请改用 [pillEffects.pillCultivationSpeedBonus] */
    var pillCultivationSpeedBonus: Double get() = pillEffects.pillCultivationSpeedBonus; set(value) { pillEffects.pillCultivationSpeedBonus = value }
    /** @deprecated 请改用 [pillEffects.pillSkillExpSpeedBonus] */
    var pillSkillExpSpeedBonus: Double get() = pillEffects.pillSkillExpSpeedBonus; set(value) { pillEffects.pillSkillExpSpeedBonus = value }
    /** @deprecated 请改用 [pillEffects.pillNurtureSpeedBonus] */
    var pillNurtureSpeedBonus: Double get() = pillEffects.pillNurtureSpeedBonus; set(value) { pillEffects.pillNurtureSpeedBonus = value }
    /** @deprecated 请改用 [pillEffects.activePillCategory] */
    var activePillCategory: String get() = pillEffects.activePillCategory; set(value) { pillEffects.activePillCategory = value }

    // --- EquipmentSet 委托 ---
    /** @deprecated 请改用 [equipment.weaponId] */
    var weaponId: String get() = equipment.weaponId; set(value) { equipment.weaponId = value }
    /** @deprecated 请改用 [equipment.armorId] */
    var armorId: String get() = equipment.armorId; set(value) { equipment.armorId = value }
    /** @deprecated 请改用 [equipment.bootsId] */
    var bootsId: String get() = equipment.bootsId; set(value) { equipment.bootsId = value }
    /** @deprecated 请改用 [equipment.accessoryId] */
    var accessoryId: String get() = equipment.accessoryId; set(value) { equipment.accessoryId = value }

    /** @deprecated 请改用 [equipment.weaponNurture] */
    var weaponNurture: EquipmentNurtureData get() = equipment.weaponNurture; set(value) { equipment.weaponNurture = value }
    /** @deprecated 请改用 [equipment.armorNurture] */
    var armorNurture: EquipmentNurtureData get() = equipment.armorNurture; set(value) { equipment.armorNurture = value }
    /** @deprecated 请改用 [equipment.bootsNurture] */
    var bootsNurture: EquipmentNurtureData get() = equipment.bootsNurture; set(value) { equipment.bootsNurture = value }
    /** @deprecated 请改用 [equipment.accessoryNurture] */
    var accessoryNurture: EquipmentNurtureData get() = equipment.accessoryNurture; set(value) { equipment.accessoryNurture = value }

    /** @deprecated 请改用 [equipment.storageBagItems] */
    var storageBagItems: List<StorageBagItem> get() = equipment.storageBagItems; set(value) { equipment.storageBagItems = value }
    /** @deprecated 请改用 [equipment.storageBagSpiritStones] */
    var storageBagSpiritStones: Long get() = equipment.storageBagSpiritStones; set(value) { equipment.storageBagSpiritStones = value }
    /** @deprecated 请改用 [equipment.spiritStones] */
    var spiritStones: Int get() = equipment.spiritStones; set(value) { equipment.spiritStones = value }
    /** @deprecated 请改用 [equipment.soulPower] */
    var soulPower: Int get() = equipment.soulPower; set(value) { equipment.soulPower = value }

    // --- SocialData 委托 ---
    /** @deprecated 请改用 [social.partnerId] */
    var partnerId: String? get() = social.partnerId; set(value) { social.partnerId = value }
    /** @deprecated 请改用 [social.partnerSectId] */
    var partnerSectId: String? get() = social.partnerSectId; set(value) { social.partnerSectId = value }
    /** @deprecated 请改用 [social.parentId1] */
    var parentId1: String? get() = social.parentId1; set(value) { social.parentId1 = value }
    /** @deprecated 请改用 [social.parentId2] */
    var parentId2: String? get() = social.parentId2; set(value) { social.parentId2 = value }
    /** @deprecated 请改用 [social.lastChildYear] */
    var lastChildYear: Int get() = social.lastChildYear; set(value) { social.lastChildYear = value }
    /** @deprecated 请改用 [social.griefEndYear] */
    var griefEndYear: Int? get() = social.griefEndYear; set(value) { social.griefEndYear = value }

    // --- SkillStats 委托 ---
    /** @deprecated 请改用 [skills.intelligence] */
    var intelligence: Int get() = skills.intelligence; set(value) { skills.intelligence = value }
    /** @deprecated 请改用 [skills.charm] */
    var charm: Int get() = skills.charm; set(value) { skills.charm = value }
    /** @deprecated 请改用 [skills.loyalty] */
    var loyalty: Int get() = skills.loyalty; set(value) { skills.loyalty = value }
    /** @deprecated 请改用 [skills.comprehension] */
    var comprehension: Int get() = skills.comprehension; set(value) { skills.comprehension = value }
    /** @deprecated 请改用 [skills.artifactRefining] */
    var artifactRefining: Int get() = skills.artifactRefining; set(value) { skills.artifactRefining = value }
    /** @deprecated 请改用 [skills.pillRefining] */
    var pillRefining: Int get() = skills.pillRefining; set(value) { skills.pillRefining = value }
    /** @deprecated 请改用 [skills.spiritPlanting] */
    var spiritPlanting: Int get() = skills.spiritPlanting; set(value) { skills.spiritPlanting = value }
    /** @deprecated 请改用 [skills.teaching] */
    var teaching: Int get() = skills.teaching; set(value) { skills.teaching = value }
    /** @deprecated 请改用 [skills.mining] */
    var mining: Int get() = skills.mining; set(value) { skills.mining = value }
    /** @deprecated 请改用 [skills.morality] */
    var morality: Int get() = skills.morality; set(value) { skills.morality = value }

    /** @deprecated 请改用 [skills.salaryPaidCount] */
    var salaryPaidCount: Int get() = skills.salaryPaidCount; set(value) { skills.salaryPaidCount = value }
    /** @deprecated 请改用 [skills.salaryMissedCount] */
    var salaryMissedCount: Int get() = skills.salaryMissedCount; set(value) { skills.salaryMissedCount = value }

    // --- UsageTracking 委托 ---
    /** @deprecated 请改用 [usage.usedFunctionalPillTypes] */
    var monthlyUsedPillIds: List<String> get() = usage.usedFunctionalPillTypes; set(value) { usage.usedFunctionalPillTypes = value }
    /** @deprecated 请改用 [usage.usedExtendLifePillIds] */
    var usedExtendLifePillIds: List<String> get() = usage.usedExtendLifePillIds; set(value) { usage.usedExtendLifePillIds = value }
    /** @deprecated 请改用 [usage.recruitedMonth] */
    var recruitedMonth: Int get() = usage.recruitedMonth; set(value) { usage.recruitedMonth = value }
    /** @deprecated 请改用 [usage.hasReviveEffect] */
    var hasReviveEffect: Boolean get() = usage.hasReviveEffect; set(value) { usage.hasReviveEffect = value }
    /** @deprecated 请改用 [usage.hasClearAllEffect] */
    var hasClearAllEffect: Boolean get() = usage.hasClearAllEffect; set(value) { usage.hasClearAllEffect = value }

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
        // 每层修为要求递增 20%
        return base * (1.0 + (realmLayer - 1) * 0.2)
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

    val hpPercent: Double get() {
        val max = maxHp
        if (max <= 0) return 100.0
        val current = if (currentHp < 0) max else currentHp
        return current.toDouble() / max * 100.0
    }
    val mpPercent: Double get() {
        val max = maxMp
        if (max <= 0) return 100.0
        val current = if (currentMp < 0) max else currentMp
        return current.toDouble() / max * 100.0
    }

    val equippedItems: Map<EquipmentSlot, EquipmentInstance?> get() = emptyMap()
    val learnedManuals: List<ManualInstance> get() = emptyList()

    val genderName: String get() = if (gender == "male") "男" else "女"
    val genderSymbol: String get() = if (gender == "male") "\u2642" else "\u2640"
    val hasPartner: Boolean get() = social.hasPartner

    val comprehensionSpeedBonus: Double get() = skills.comprehensionSpeedBonus

    // ==================== 扩展 copy 方法 ====================
    
    /**
     * 完整的 copy 方法，支持所有原始字段的命名参数。
     * 这是向后兼容的关键：所有原有的 disciple.copy(baseHp = 100, pillHpBonus = 0.1, ...)
     * 调用方式都可以继续工作。
     */
    fun copyWith(
        id: String = this.id,
        name: String = this.name,
        surname: String = this.surname,
        realm: Int = this.realm,
        realmLayer: Int = this.realmLayer,
        cultivation: Double = this.cultivation,
        spiritRootType: String = this.spiritRootType,
        age: Int = this.age,
        lifespan: Int = this.lifespan,
        isAlive: Boolean = this.isAlive,
        gender: String = this.gender,
        manualIds: List<String> = this.manualIds,
        talentIds: List<String> = this.talentIds,
        manualMasteries: Map<String, Int> = this.manualMasteries,
        status: DiscipleStatus = this.status,
        statusData: Map<String, String> = this.statusData,
        cultivationSpeedBonus: Double = this.cultivationSpeedBonus,
        cultivationSpeedDuration: Int = this.cultivationSpeedDuration,
        discipleType: String = this.discipleType,
        autoLearnFromWarehouse: Boolean = this.autoLearnFromWarehouse,

        // CombatAttributes
        baseHp: Int = this.baseHp,
        baseMp: Int = this.baseMp,
        basePhysicalAttack: Int = this.basePhysicalAttack,
        baseMagicAttack: Int = this.baseMagicAttack,
        basePhysicalDefense: Int = this.basePhysicalDefense,
        baseMagicDefense: Int = this.baseMagicDefense,
        baseSpeed: Int = this.baseSpeed,
        hpVariance: Int = this.hpVariance,
        mpVariance: Int = this.mpVariance,
        physicalAttackVariance: Int = this.physicalAttackVariance,
        magicAttackVariance: Int = this.magicAttackVariance,
        physicalDefenseVariance: Int = this.physicalDefenseVariance,
        magicDefenseVariance: Int = this.magicDefenseVariance,
        speedVariance: Int = this.speedVariance,
        totalCultivation: Long = this.totalCultivation,
        breakthroughCount: Int = this.breakthroughCount,
        breakthroughFailCount: Int = this.breakthroughFailCount,
        currentHp: Int = this.currentHp,
        currentMp: Int = this.currentMp,

        // PillEffects
        pillPhysicalAttackBonus: Int = this.pillPhysicalAttackBonus,
        pillMagicAttackBonus: Int = this.pillMagicAttackBonus,
        pillPhysicalDefenseBonus: Int = this.pillPhysicalDefenseBonus,
        pillMagicDefenseBonus: Int = this.pillMagicDefenseBonus,
        pillHpBonus: Int = this.pillHpBonus,
        pillMpBonus: Int = this.pillMpBonus,
        pillSpeedBonus: Int = this.pillSpeedBonus,
        pillEffectDuration: Int = this.pillEffectDuration,
        pillCritRateBonus: Double = this.pillCritRateBonus,
        pillCritEffectBonus: Double = this.pillCritEffectBonus,
        pillCultivationSpeedBonus: Double = this.pillCultivationSpeedBonus,
        pillSkillExpSpeedBonus: Double = this.pillSkillExpSpeedBonus,
        pillNurtureSpeedBonus: Double = this.pillNurtureSpeedBonus,
        activePillCategory: String = this.activePillCategory,

        // EquipmentSet
        weaponId: String = this.weaponId,
        armorId: String = this.armorId,
        bootsId: String = this.bootsId,
        accessoryId: String = this.accessoryId,
        weaponNurture: EquipmentNurtureData = this.weaponNurture,
        armorNurture: EquipmentNurtureData = this.armorNurture,
        bootsNurture: EquipmentNurtureData = this.bootsNurture,
        accessoryNurture: EquipmentNurtureData = this.accessoryNurture,
        storageBagItems: List<StorageBagItem> = this.storageBagItems,
        storageBagSpiritStones: Long = this.storageBagSpiritStones,
        spiritStones: Int = this.spiritStones,
        soulPower: Int = this.soulPower,
        autoEquipFromWarehouse: Boolean = this.equipment.autoEquipFromWarehouse,

        // SocialData
        partnerId: String? = this.partnerId,
        partnerSectId: String? = this.partnerSectId,
        parentId1: String? = this.parentId1,
        parentId2: String? = this.parentId2,
        lastChildYear: Int = this.lastChildYear,
        griefEndYear: Int? = this.griefEndYear,

        // SkillStats
        intelligence: Int = this.intelligence,
        charm: Int = this.charm,
        loyalty: Int = this.loyalty,
        comprehension: Int = this.comprehension,
        artifactRefining: Int = this.artifactRefining,
        pillRefining: Int = this.pillRefining,
        spiritPlanting: Int = this.spiritPlanting,
        mining: Int = this.mining,
        teaching: Int = this.teaching,
        morality: Int = this.morality,
        salaryPaidCount: Int = this.salaryPaidCount,
        salaryMissedCount: Int = this.salaryMissedCount,

        // UsageTracking
        usedFunctionalPillTypes: List<String> = this.monthlyUsedPillIds,
        usedExtendLifePillIds: List<String> = this.usedExtendLifePillIds,
        recruitedMonth: Int = this.recruitedMonth,
        hasReviveEffect: Boolean = this.hasReviveEffect,
        hasClearAllEffect: Boolean = this.hasClearAllEffect
    ): Disciple {
        return Disciple(
            id = id,
            name = name,
            surname = surname,
            realm = realm,
            realmLayer = realmLayer,
            cultivation = cultivation,
            spiritRootType = spiritRootType,
            age = age,
            lifespan = lifespan,
            isAlive = isAlive,
            gender = gender,
            manualIds = manualIds,
            talentIds = talentIds,
            manualMasteries = manualMasteries,
            status = status,
            statusData = statusData,
            cultivationSpeedBonus = cultivationSpeedBonus,
            cultivationSpeedDuration = cultivationSpeedDuration,
            discipleType = discipleType,
            autoLearnFromWarehouse = autoLearnFromWarehouse,
            combat = CombatAttributes(
                baseHp = baseHp,
                baseMp = baseMp,
                basePhysicalAttack = basePhysicalAttack,
                baseMagicAttack = baseMagicAttack,
                basePhysicalDefense = basePhysicalDefense,
                baseMagicDefense = baseMagicDefense,
                baseSpeed = baseSpeed,
                hpVariance = hpVariance,
                mpVariance = mpVariance,
                physicalAttackVariance = physicalAttackVariance,
                magicAttackVariance = magicAttackVariance,
                physicalDefenseVariance = physicalDefenseVariance,
                magicDefenseVariance = magicDefenseVariance,
                speedVariance = speedVariance,
                totalCultivation = totalCultivation,
                breakthroughCount = breakthroughCount,
                breakthroughFailCount = breakthroughFailCount,
                currentHp = currentHp,
                currentMp = currentMp
            ),
            pillEffects = PillEffects(
                pillPhysicalAttackBonus = pillPhysicalAttackBonus,
                pillMagicAttackBonus = pillMagicAttackBonus,
                pillPhysicalDefenseBonus = pillPhysicalDefenseBonus,
                pillMagicDefenseBonus = pillMagicDefenseBonus,
                pillHpBonus = pillHpBonus,
                pillMpBonus = pillMpBonus,
                pillSpeedBonus = pillSpeedBonus,
                pillCritRateBonus = pillCritRateBonus,
                pillCritEffectBonus = pillCritEffectBonus,
                pillCultivationSpeedBonus = pillCultivationSpeedBonus,
                pillSkillExpSpeedBonus = pillSkillExpSpeedBonus,
                pillNurtureSpeedBonus = pillNurtureSpeedBonus,
                pillEffectDuration = pillEffectDuration,
                activePillCategory = activePillCategory
            ),
            equipment = EquipmentSet(
                weaponId = weaponId,
                armorId = armorId,
                bootsId = bootsId,
                accessoryId = accessoryId,
                weaponNurture = weaponNurture,
                armorNurture = armorNurture,
                bootsNurture = bootsNurture,
                accessoryNurture = accessoryNurture,
                autoEquipFromWarehouse = autoEquipFromWarehouse,
                storageBagItems = storageBagItems,
                storageBagSpiritStones = storageBagSpiritStones,
                spiritStones = spiritStones,
                soulPower = soulPower
            ),
            social = SocialData(
                partnerId = partnerId,
                partnerSectId = partnerSectId,
                parentId1 = parentId1,
                parentId2 = parentId2,
                lastChildYear = lastChildYear,
                griefEndYear = griefEndYear
            ),
            skills = SkillStats(
                intelligence = intelligence,
                charm = charm,
                loyalty = loyalty,
                comprehension = comprehension,
                artifactRefining = artifactRefining,
                pillRefining = pillRefining,
                spiritPlanting = spiritPlanting,
                mining = mining,
                teaching = teaching,
                morality = morality,
                salaryPaidCount = salaryPaidCount,
                salaryMissedCount = salaryMissedCount
            ),
            usage = UsageTracking(
                usedFunctionalPillTypes = usedFunctionalPillTypes,
                usedExtendLifePillIds = usedExtendLifePillIds,
                recruitedMonth = recruitedMonth,
                hasReviveEffect = hasReviveEffect,
                hasClearAllEffect = hasClearAllEffect
            )
        )
    }

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

            return disciple.copyWith(
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
        }
    }

    // ==================== 属性计算方法（委托给 DiscipleStatCalculator）====================

    /**
     * @deprecated 建议直接使用 [DiscipleStatCalculator.getBaseStats]
     * 计算弟子的基础属性（不含装备和功法加成）
     */
    fun getBaseStats(): DiscipleStats = DiscipleStatCalculator.getBaseStats(this)

    /**
     * @deprecated 建议直接使用 [DiscipleStatCalculator.getTalentEffects]
     * 获取弟子所有天赋的效果汇总
     */
    fun getTalentEffects(): Map<String, Double> = DiscipleStatCalculator.getTalentEffects(this)

    /**
     * @deprecated 建议直接使用 [DiscipleStatCalculator.getStatsWithEquipment]
     * 计算弟子穿戴装备后的属性（不含功法和丹药）
     */
    fun getStatsWithEquipment(equipments: Map<String, EquipmentInstance>): DiscipleStats =
        DiscipleStatCalculator.getStatsWithEquipment(this, equipments)

    /**
     * @deprecated 建议直接使用 [DiscipleStatCalculator.getFinalStats]
     * 计算弟子的最终完整属性
     */
    fun getFinalStats(
        equipments: Map<String, EquipmentInstance>,
        manuals: Map<String, ManualInstance>,
        manualProficiencies: Map<String, ManualProficiencyData> = emptyMap()
    ): DiscipleStats = DiscipleStatCalculator.getFinalStats(this, equipments, manuals, manualProficiencies)

    /**
     * 计算修炼速度（支持外部传入功法和熟练度数据）
     */
    fun calculateCultivationSpeed(
        manuals: Map<String, ManualInstance> = emptyMap(),
        manualProficiencies: Map<String, ManualProficiencyData> = emptyMap(),
        additionalBonus: Double = 0.0,
        buildingBonus: Double = 1.0,
        preachingElderBonus: Double = 0.0,
        preachingMastersBonus: Double = 0.0,
        cultivationSubsidyBonus: Double = 0.0
    ): Double = DiscipleStatCalculator.calculateCultivationSpeed(
        this, manuals, manualProficiencies,
        buildingBonus = buildingBonus,
        additionalBonus = additionalBonus,
        preachingElderBonus = preachingElderBonus,
        preachingMastersBonus = preachingMastersBonus,
        cultivationSubsidyBonus = cultivationSubsidyBonus
    )

    /** 判断弟子是否可以突破 */
    fun canBreakthrough(): Boolean = cultivation >= maxCultivation

    fun getBreakthroughChance(): Double =
        DiscipleStatCalculator.getBreakthroughChance(this)

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

@Serializable
enum class DiscipleStatus {
    IDLE, DEACONING, MINING, STUDYING, PREACHING, MANAGING, LAW_ENFORCING, ON_MISSION, REFLECTING, IN_TEAM, DEAD;

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
        IN_TEAM -> "队伍中"
        DEAD -> "已死亡"
    }
}

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

    val cultivationBonus: Double
        get() {
            return when (types.size) {
                1 -> 3.0
                2 -> 2.0
                3 -> 1.5
                4 -> 1.0
                5 -> 0.7
                else -> 1.0
            }
        }
}

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
    val forgetDay: Int? = null
) {
    val color: String get() = GameConfig.Rarity.getColor(rarity)
    val rarityName: String get() = GameConfig.Rarity.getName(rarity)
}

@Serializable
data class ItemEffect(
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

@Serializable
data class RewardSelectedItem(
    val id: String,
    val type: String,
    val name: String,
    val rarity: Int,
    val quantity: Int,
    val grade: String? = null
)

@Serializable
data class EquipmentNurtureData(
    val equipmentId: String,
    val rarity: Int,
    val nurtureLevel: Int = 0,
    val nurtureProgress: Double = 0.0
)
