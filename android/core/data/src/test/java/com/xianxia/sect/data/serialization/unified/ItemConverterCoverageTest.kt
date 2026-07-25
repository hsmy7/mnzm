package com.xianxia.sect.data.serialization.unified

import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PillEffect
import com.xianxia.sect.core.model.Seed
import kotlin.reflect.full.memberProperties
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守卫测试：确保 ItemConverter 覆盖的所有领域模型字段完整。
 *
 * ItemConverter 负责转换：
 * - Pill ↔ SerializablePill
 * - Material ↔ SerializableMaterial
 * - Herb ↔ SerializableHerb
 * - Seed ↔ SerializableSeed
 *
 * 当新增字段时，此测试会失败，提示同步更新 ItemConverter。
 */
class ItemConverterCoverageTest {

    // ==================== Pill ====================

    /**
     * Pill 的 effects 字段是 PillEffect 嵌入组件，由 converter 展平为 SerializablePillEffect。
     * Pill 上的 breakthroughChance、targetRealm 等均为 delegates getter → effects.xxx。
     */
    private val PILL_COVERED: Set<String> = setOf(
        "id", "name", "rarity", "description",
        "category", "grade", "pillType",
        "effects", "minRealm", "quantity", "isLocked"
    )

    private val PILL_EXCLUDED: Set<String> = setOf(
        "slotId"  // Room 复合主键
    )

    private val PILL_COMPUTED: Set<String> = setOf(
        "basePrice", "rarityColor", "rarityName",
        // delegates to effects.*
        "breakthroughChance", "targetRealm", "isAscension",
        "cultivationSpeedPercent", "skillExpSpeedPercent", "nurtureSpeedPercent",
        "cultivationAdd", "skillExpAdd", "nurtureAdd",
        "duration", "cannotStack",
        "physicalAttackAdd", "magicAttackAdd", "physicalDefenseAdd", "magicDefenseAdd",
        "hpAdd", "mpAdd", "speedAdd",
        "critRateAdd", "critEffectAdd",
        "extendLife",
        "intelligenceAdd", "charmAdd", "loyaltyAdd", "comprehensionAdd",
        "artifactRefiningAdd", "pillRefiningAdd", "spiritPlantingAdd",
        "teachingAdd", "moralityAdd", "miningAdd",
        "healMaxHpPercent", "mpRecoverMaxMpPercent",
        "revive", "clearAll"
    )

    @Test
    fun `all Pill fields are mapped in ItemConverter`() {
        val allFields = Pill::class.memberProperties
            .map { it.name }
            .toSet()
        val allExcluded = PILL_EXCLUDED + PILL_COMPUTED
        val checkFields = allFields - allExcluded
        val missing = checkFields - PILL_COVERED
        val extra = PILL_COVERED - checkFields
        assertTrue(
            buildMissingMessage("Pill", missing, extra),
            missing.isEmpty()
        )
    }

    // ==================== PillEffect ====================

    private val PILL_EFFECT_COVERED: Set<String> = setOf(
        "breakthroughChance", "targetRealm", "isAscension",
        "cultivationSpeedPercent", "skillExpSpeedPercent", "nurtureSpeedPercent",
        "cultivationAdd", "skillExpAdd", "nurtureAdd",
        "duration", "cannotStack",
        "physicalAttackAdd", "magicAttackAdd",
        "physicalDefenseAdd", "magicDefenseAdd",
        "hpAdd", "mpAdd", "speedAdd",
        "critRateAdd", "critEffectAdd",
        "extendLife",
        "intelligenceAdd", "charmAdd", "loyaltyAdd", "comprehensionAdd",
        "artifactRefiningAdd", "pillRefiningAdd", "spiritPlantingAdd",
        "teachingAdd", "moralityAdd", "miningAdd",
        "healMaxHpPercent", "mpRecoverMaxMpPercent",
        "revive", "clearAll"
    )

    @Test
    fun `all PillEffect fields are mapped in ItemConverter`() {
        val allFields = PillEffect::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - PILL_EFFECT_COVERED
        assertTrue(
            buildMissingMessage("PillEffect", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== Material ====================

    /**
     * Material 的 isLocked 字段和 Seed 的 isLocked 字段不由 ItemConverter 序列化，
     * 由 MaterialStack/SeedStack 的独立序列化路径覆盖。
     */
    private val MATERIAL_COVERED: Set<String> = setOf(
        "id", "name", "rarity", "description",
        "category", "quantity"
    )

    private val MATERIAL_EXCLUDED: Set<String> = setOf(
        "slotId",    // Room 复合主键
        "isLocked"   // 不由此序列化路径覆盖（由 Stack 序列化）
    )

    private val MATERIAL_COMPUTED: Set<String> = setOf(
        "basePrice", "rarityColor", "rarityName"
    )

    @Test
    fun `all Material fields are mapped in ItemConverter`() {
        val allFields = Material::class.memberProperties
            .map { it.name }
            .toSet()
        val allExcluded = MATERIAL_EXCLUDED + MATERIAL_COMPUTED
        val checkFields = allFields - allExcluded
        val missing = checkFields - MATERIAL_COVERED
        val extra = MATERIAL_COVERED - checkFields
        assertTrue(
            buildMissingMessage("Material", missing, extra),
            missing.isEmpty()
        )
    }

    // ==================== Herb ====================

    /**
     * Herb 的 category 和 isLocked 字段不由 ItemConverter 序列化。
     * category 在旧存档中没有可靠的反向映射（转换器中做了名称兼容处理）。
     */
    private val HERB_COVERED: Set<String> = setOf(
        "id", "name", "rarity", "quantity", "description"
    )

    private val HERB_EXCLUDED: Set<String> = setOf(
        "slotId",    // Room 复合主键
        "category",  // 不由此序列化路径覆盖
        "isLocked"   // 不由此序列化路径覆盖（由 Stack 序列化）
    )

    private val HERB_COMPUTED: Set<String> = setOf(
        "basePrice", "rarityColor", "rarityName"
    )

    @Test
    fun `all Herb fields are mapped in ItemConverter`() {
        val allFields = Herb::class.memberProperties
            .map { it.name }
            .toSet()
        val allExcluded = HERB_EXCLUDED + HERB_COMPUTED
        val checkFields = allFields - allExcluded
        val missing = checkFields - HERB_COVERED
        val extra = HERB_COVERED - checkFields
        assertTrue(
            buildMissingMessage("Herb", missing, extra),
            missing.isEmpty()
        )
    }

    // ==================== Seed ====================

    private val SEED_COVERED: Set<String> = setOf(
        "id", "name", "rarity", "description",
        "growTime", "yield", "quantity"
    )

    private val SEED_EXCLUDED: Set<String> = setOf(
        "slotId",    // Room 复合主键
        "isLocked"   // 不由此序列化路径覆盖（由 Stack 序列化）
    )

    private val SEED_COMPUTED: Set<String> = setOf(
        "basePrice", "rarityColor", "rarityName"
    )

    @Test
    fun `all Seed fields are mapped in ItemConverter`() {
        val allFields = Seed::class.memberProperties
            .map { it.name }
            .toSet()
        val allExcluded = SEED_EXCLUDED + SEED_COMPUTED
        val checkFields = allFields - allExcluded
        val missing = checkFields - SEED_COVERED
        val extra = SEED_COVERED - checkFields
        assertTrue(
            buildMissingMessage("Seed", missing, extra),
            missing.isEmpty()
        )
    }

    // ==================== 工具方法 ====================

    private fun buildMissingMessage(
        className: String,
        missing: Set<String>,
        extra: Set<String>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("========================================")
        sb.appendLine("$className 字段与 ItemConverter 序列化覆盖检查")
        sb.appendLine("========================================")

        if (missing.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("以下 $className 字段未在 ItemConverter 中覆盖：")
            missing.sorted().forEach { field ->
                sb.appendLine("  - $field")
            }
            sb.appendLine()
            sb.appendLine("请为上述每个字段在 ItemConverter 中：")
            sb.appendLine("  1. 在 convertXxx() 中添加正向映射")
            sb.appendLine("  2. 在 convertBackXxx() 中添加反向映射")
            sb.appendLine("  3. 将此测试的 COVERED 列表中添加字段名")
        }

        if (extra.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("警告：COVERED 中存在下列字段，")
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
