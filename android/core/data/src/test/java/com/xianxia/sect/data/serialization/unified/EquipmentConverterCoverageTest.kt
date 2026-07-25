package com.xianxia.sect.data.serialization.unified

import com.xianxia.sect.core.model.AICaveDisciple
import com.xianxia.sect.core.model.AICaveTeam
import com.xianxia.sect.core.model.AIRandomEquipment
import com.xianxia.sect.core.model.AIRandomManual
import com.xianxia.sect.core.model.EquipmentInstance
import kotlin.reflect.full.memberProperties
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守卫测试：确保 EquipmentConverter 覆盖的所有领域模型字段完整。
 *
 * EquipmentConverter 负责转换：
 * - EquipmentInstance ↔ SerializableEquipment
 * - AICaveTeam ↔ SerializableAICaveTeam
 * - AICaveDisciple ↔ SerializableAICaveDisciple
 * - AIRandomEquipment ↔ SerializableAIRandomEquipment
 * - AIRandomManual ↔ SerializableAIRandomManual
 *
 * 当新增字段时，此测试会失败，提示同步更新 EquipmentConverter。
 */
class EquipmentConverterCoverageTest {

    // ==================== EquipmentInstance ====================

    private val EQUIPMENT_COVERED: Set<String> = setOf(
        "id", "name", "rarity", "description",
        "slot", "physicalAttack", "magicAttack",
        "physicalDefense", "magicDefense", "speed", "hp", "mp",
        "critChance", "nurtureLevel", "nurtureProgress",
        "minRealm", "ownerId", "isEquipped"
    )

    private val EQUIPMENT_EXCLUDED: Set<String> = setOf(
        "slotId"    // Room 复合主键，不序列化到云存档
    )

    private val EQUIPMENT_COMPUTED: Set<String> = setOf(
        "basePrice", "stats", "totalMultiplier",
        "totalStatsDescription",
        "rarityColor", "rarityName"
    )

    @Test
    fun `all EquipmentInstance fields are mapped in EquipmentConverter`() {
        val allFields = EquipmentInstance::class.memberProperties
            .map { it.name }
            .toSet()
        val allExcluded = EQUIPMENT_EXCLUDED + EQUIPMENT_COMPUTED
        val checkFields = allFields - allExcluded
        val missing = checkFields - EQUIPMENT_COVERED
        val extra = EQUIPMENT_COVERED - checkFields

        assertTrue(
            buildMissingMessage("EquipmentInstance", missing, extra, "EquipmentConverter"),
            missing.isEmpty()
        )
    }

    // ==================== AICaveTeam ====================

    private val AI_CAVE_TEAM_COVERED: Set<String> = setOf(
        "id", "caveId", "sectId", "sectName",
        "memberCount", "avgRealm", "avgRealmName",
        "disciples", "status"
    )

    private val AI_CAVE_TEAM_COMPUTED: Set<String> = setOf(
        "isExploring", "isDefeated"
    )

    @Test
    fun `all AICaveTeam fields are mapped in EquipmentConverter`() {
        val allFields = AICaveTeam::class.memberProperties
            .map { it.name }
            .toSet()
        val checkFields = allFields - AI_CAVE_TEAM_COMPUTED
        val missing = checkFields - AI_CAVE_TEAM_COVERED

        assertTrue(
            buildMissingMessage("AICaveTeam", missing, emptySet(), "EquipmentConverter"),
            missing.isEmpty()
        )
    }

    // ==================== AICaveDisciple ====================

    private val AI_CAVE_DISCIPLE_COVERED: Set<String> = setOf(
        "id", "name", "realm", "realmName",
        "hp", "maxHp", "mp", "maxMp",
        "physicalAttack", "magicAttack",
        "physicalDefense", "magicDefense",
        "speed", "critRate",
        "equipments", "manuals"
    )

    private val AI_CAVE_DISCIPLE_COMPUTED: Set<String> = setOf(
        "isAlive", "hpPercent"
    )

    @Test
    fun `all AICaveDisciple fields are mapped in EquipmentConverter`() {
        val allFields = AICaveDisciple::class.memberProperties
            .map { it.name }
            .toSet()
        val checkFields = allFields - AI_CAVE_DISCIPLE_COMPUTED
        val missing = checkFields - AI_CAVE_DISCIPLE_COVERED

        assertTrue(
            buildMissingMessage("AICaveDisciple", missing, emptySet(), "EquipmentConverter"),
            missing.isEmpty()
        )
    }

    // ==================== AIRandomEquipment ====================

    private val AI_RANDOM_EQUIPMENT_COVERED: Set<String> = setOf(
        "slot", "name", "rarity", "nurtureLevel",
        "physicalAttack", "magicAttack",
        "physicalDefense", "magicDefense",
        "speed", "hp", "mp"
    )

    @Test
    fun `all AIRandomEquipment fields are mapped in EquipmentConverter`() {
        val allFields = AIRandomEquipment::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - AI_RANDOM_EQUIPMENT_COVERED

        assertTrue(
            buildMissingMessage("AIRandomEquipment", missing, emptySet(), "EquipmentConverter"),
            missing.isEmpty()
        )
    }

    // ==================== AIRandomManual ====================

    private val AI_RANDOM_MANUAL_COVERED: Set<String> = setOf(
        "name", "rarity", "mastery", "stats"
    )

    @Test
    fun `all AIRandomManual fields are mapped in EquipmentConverter`() {
        val allFields = AIRandomManual::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - AI_RANDOM_MANUAL_COVERED

        assertTrue(
            buildMissingMessage("AIRandomManual", missing, emptySet(), "EquipmentConverter"),
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
