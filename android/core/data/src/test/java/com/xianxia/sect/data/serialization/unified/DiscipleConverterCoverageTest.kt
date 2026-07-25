package com.xianxia.sect.data.serialization.unified

import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.core.model.PillEffects
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.model.SocialData
import com.xianxia.sect.core.model.UsageTracking
import kotlin.reflect.full.memberProperties
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守卫测试：确保 Disciple 所有组件字段都被 DiscipleConverter 覆盖。
 *
 * DiscipleConverter 将 Disciple 的子组件（CombatAttributes、PillEffects、EquipmentSet、
 * SocialData、SkillStats、UsageTracking）展平为 SerializableDisciple 的平铺字段。
 *
 * 当新增子组件字段时，此测试会失败，并提示开发者同步更新 DiscipleConverter。
 */
class DiscipleConverterCoverageTest {

    // ==================== Disciple 顶层字段 ====================

    private val DISCIPLE_COVERED: Set<String> = setOf(
        "id", "name", "surname", "realm", "realmLayer", "cultivation",
        "spiritRootType", "age", "lifespan", "isAlive", "gender",
        "portraitRes",
        "manualIds", "talentIds", "manualMasteries",
        "status", "statusData",
        "cultivationSpeedBonus", "cultivationSpeedDuration",
        "discipleType", "soulPower"
    )

    private val DISCIPLE_EXCLUDED: Set<String> = setOf(
        "slotId",                       // Room 复合主键
        "cultivationCheckpoint",        // 惰性结算运行时字段
        "cultivationCheckpointGameMonth", // 惰性结算运行时字段
        "autoLearnFromWarehouse",       // 运行时设置，不持久化
        "cultivationCompletionMonth",   // 惰性结算运行时字段
        "cultivationCompletionPhase",   // 惰性结算运行时字段
        "manualCompletionMonth",        // 惰性结算运行时字段
        "manualCompletionPhase",        // 惰性结算运行时字段
        "equipmentNurturingCompletionMonth", // 惰性结算运行时字段
        "equipmentNurturingCompletionPhase",  // 惰性结算运行时字段
        "lifeEvents",                   // @Ignore，不持久化
        "monthlyUsedPillIds"            // 弃用的委托属性，映射到 usage.usedFunctionalPillTypes
    )

    private val DISCIPLE_COMPUTED: Set<String> = setOf(
        "canCultivate", "realmName", "realmNameOnly", "maxCultivation",
        "cultivationProgress", "spiritRoot", "spiritRootName",
        "physicalAttack", "physicalDefense", "magicAttack", "magicDefense",
        "speed", "maxHp", "maxMp", "hpPercent", "mpPercent",
        "equippedItems", "learnedManuals",
        "genderName", "genderSymbol", "hasPartner",
        "comprehensionSpeedBonus",
        "combat", "pillEffects", "equipment", "social", "skills", "usage"
    )

    @Test
    fun `all Disciple fields are mapped in DiscipleConverter`() {
        val allFields = Disciple::class.memberProperties
            .map { it.name }
            .toSet()
        val allExcluded = DISCIPLE_EXCLUDED + DISCIPLE_COMPUTED
        val checkFields = allFields - allExcluded
        val missing = checkFields - DISCIPLE_COVERED
        val extra = DISCIPLE_COVERED - checkFields

        assertTrue(
            buildMissingMessage("Disciple", missing, extra, "DiscipleConverter"),
            missing.isEmpty()
        )
    }

    // ==================== CombatAttributes ====================

    private val COMBAT_COVERED: Set<String> = setOf(
        "baseHp", "baseMp", "basePhysicalAttack", "baseMagicAttack",
        "basePhysicalDefense", "baseMagicDefense", "baseSpeed",
        "hpVariance", "mpVariance", "physicalAttackVariance",
        "magicAttackVariance", "physicalDefenseVariance",
        "magicDefenseVariance", "speedVariance",
        "totalCultivation", "breakthroughCount", "breakthroughFailCount",
        "currentHp", "currentMp"
    )

    @Test
    fun `all CombatAttributes fields are mapped in DiscipleConverter`() {
        val allFields = CombatAttributes::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - COMBAT_COVERED
        assertTrue(
            buildMissingMessage("CombatAttributes", missing, emptySet(), "DiscipleConverter"),
            missing.isEmpty()
        )
    }

    // ==================== PillEffects ====================

    private val PILL_EFFECTS_COVERED: Set<String> = setOf(
        "pillPhysicalAttackBonus", "pillMagicAttackBonus",
        "pillPhysicalDefenseBonus", "pillMagicDefenseBonus",
        "pillHpBonus", "pillMpBonus", "pillSpeedBonus",
        "pillCritRateBonus", "pillCritEffectBonus",
        "pillCultivationSpeedBonus", "pillSkillExpSpeedBonus",
        "pillNurtureSpeedBonus", "pillEffectDuration",
        "activePillCategory", "activePillTypes"
    )

    /**
     * all fields covered — no excluded/computed
     */
    @Test
    fun `all PillEffects fields are mapped in DiscipleConverter`() {
        val allFields = PillEffects::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - PILL_EFFECTS_COVERED
        assertTrue(
            buildMissingMessage("PillEffects", missing, emptySet(), "DiscipleConverter"),
            missing.isEmpty()
        )
    }

    // ==================== EquipmentSet ====================

    private val EQUIPMENT_SET_COVERED: Set<String> = setOf(
        "weaponId", "armorId", "bootsId", "accessoryId",
        "weaponNurture", "armorNurture", "bootsNurture", "accessoryNurture",
        "storageBagItems", "storageBagSpiritStones", "spiritStones"
    )

    private val EQUIPMENT_SET_EXCLUDED: Set<String> = setOf(
        "autoEquipFromWarehouse"    // 运行时设置，不序列化到云存档
    )

    private val EQUIPMENT_SET_COMPUTED: Set<String> = setOf(
        "hasEquippedItems", "equippedItemIds"
    )

    @Test
    fun `all EquipmentSet fields are mapped in DiscipleConverter`() {
        val allFields = EquipmentSet::class.memberProperties
            .map { it.name }
            .toSet()
        val allExcluded = EQUIPMENT_SET_EXCLUDED + EQUIPMENT_SET_COMPUTED
        val checkFields = allFields - allExcluded
        val missing = checkFields - EQUIPMENT_SET_COVERED
        assertTrue(
            buildMissingMessage("EquipmentSet", missing, emptySet(), "DiscipleConverter"),
            missing.isEmpty()
        )
    }

    // ==================== SocialData ====================

    private val SOCIAL_DATA_COVERED: Set<String> = setOf(
        "partnerId", "partnerSectId", "parentId1", "parentId2",
        "lastChildYear", "griefEndYear"
    )

    private val SOCIAL_DATA_EXCLUDED: Set<String> = setOf(
        "childBirthMonth",   // 运行时计算字段，不序列化
        "masterId"           // 师徒关系，仅运行时使用
    )

    private val SOCIAL_DATA_COMPUTED: Set<String> = setOf(
        "hasPartner", "hasMaster"
    )

    @Test
    fun `all SocialData fields are mapped in DiscipleConverter`() {
        val allFields = SocialData::class.memberProperties
            .map { it.name }
            .toSet()
        val allExcluded = SOCIAL_DATA_EXCLUDED + SOCIAL_DATA_COMPUTED
        val checkFields = allFields - allExcluded
        val missing = checkFields - SOCIAL_DATA_COVERED
        assertTrue(
            buildMissingMessage("SocialData", missing, emptySet(), "DiscipleConverter"),
            missing.isEmpty()
        )
    }

    // ==================== SkillStats ====================

    private val SKILL_STATS_COVERED: Set<String> = setOf(
        "intelligence", "charm", "loyalty", "comprehension",
        "artifactRefining", "pillRefining", "spiritPlanting",
        "mining", "teaching", "morality",
        "salaryPaidCount", "salaryMissedCount"
    )

    private val SKILL_STATS_COMPUTED: Set<String> = setOf(
        "comprehensionSpeedBonus"
    )

    @Test
    fun `all SkillStats fields are mapped in DiscipleConverter`() {
        val allFields = SkillStats::class.memberProperties
            .map { it.name }
            .toSet()
        val checkFields = allFields - SKILL_STATS_COMPUTED
        val missing = checkFields - SKILL_STATS_COVERED
        assertTrue(
            buildMissingMessage("SkillStats", missing, emptySet(), "DiscipleConverter"),
            missing.isEmpty()
        )
    }

    // ==================== UsageTracking ====================

    private val USAGE_TRACKING_COVERED: Set<String> = setOf(
        "usedFunctionalPillTypes", "usedExtendLifePillIds",
        "usedPermanentPillKeys", "usedExtendLifePillTypes",
        "recruitedMonth", "hasReviveEffect", "hasClearAllEffect"
    )

    @Test
    fun `all UsageTracking fields are mapped in DiscipleConverter`() {
        val allFields = UsageTracking::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - USAGE_TRACKING_COVERED
        assertTrue(
            buildMissingMessage("UsageTracking", missing, emptySet(), "DiscipleConverter"),
            missing.isEmpty()
        )
    }

    // ==================== 工具方法 ====================

    private fun buildMissingMessage(
        className: String,
        missing: Set<String>,
        extra: Set<String>,
        converterName: String
    ): String {
        val sb = StringBuilder()
        sb.appendLine("========================================")
        sb.appendLine("$className 字段与 $converterName 序列化覆盖检查")
        sb.appendLine("========================================")

        if (missing.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("以下 $className 字段未在 $converterName 中覆盖：")
            missing.sorted().forEach { field ->
                sb.appendLine("  - $field")
            }
            sb.appendLine()
            sb.appendLine("请为上述每个字段在 $converterName 中：")
            sb.appendLine("  1. 在 convertXxx() 中添加正向映射")
            sb.appendLine("  2. 在 convertBackXxx() 中添加反向映射")
            sb.appendLine("  3. 将此测试的 COVERED_FIELDS 中添加字段名")
        }

        if (extra.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("警告：COVERED_FIELDS 中存在下列字段，")
            sb.appendLine("但在 $className 中未找到（可能已被移除或重命名）：")
            extra.sorted().forEach { field ->
                sb.appendLine("  - $field")
            }
        }

        sb.appendLine()
        sb.appendLine("========================================")
        return sb.toString()
    }
}
