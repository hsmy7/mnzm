package com.xianxia.sect.data.serialization.unified

import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.data.model.SaveData
import kotlinx.serialization.Transient
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.full.memberProperties

/**
 * 守卫测试：确保所有需要云存档序列化的域类型字段都有 [ProtoNumber] 注解。
 *
 * 当新增 GameData/SaveData 字段时，此测试会失败，并提示开发者添加 @ProtoNumber。
 *
 * ## 设计原则
 * - 运行时字段应直接标注 @kotlinx.serialization.Transient 排除，不应加入 EXCLUDED_FIELDS
 * - EXCLUDED_FIELDS 仅为无法标注 @Transient 的 computed property 提供逃生门
 * - 新增字段时优先加 @ProtoNumber，其次 @Transient，最后才考虑 EXCLUDED_FIELDS
 */
class ProtoNumberCoverageTest {

    /**
     * 不可用 @Transient 排除的字段（如 computed property），
     * 仍需要手动加入此列表，并注明原因。
     *
     * 所有可添加 @Transient 的运行时字段应直接标注 @kotlinx.serialization.Transient，
     * 不应写入此表。此表仅为无法标注 @Transient 的字段提供逃生门。
     */
    private val EXCLUDED_FIELDS = mapOf(
        // GameData 计算属性（仅 getter，无 backing field → 无 @Transient）
        "displayTime" to "格式化显示时间 getter",
        "worldMap" to "世界地图聚合 getter",
        "buildings" to "建筑状态聚合 getter",
        "economy" to "经济状态聚合 getter",
        "organization" to "组织架构聚合 getter",
        "exploration" to "探索状态聚合 getter",
        "isPlayerProtected" to "玩家保护状态计算 getter",
        "playerProtectionRemainingYears" to "玩家保护剩余年数计算 getter",
    )

    @Test
    fun `all GameData fields have ProtoNumber annotation`() {
        val errors = mutableListOf<String>()
        for (prop in GameData::class.memberProperties) {
            // @Transient 字段不需要 @ProtoNumber
            if (prop.annotations.any { it is Transient }) continue
            if (prop.name in EXCLUDED_FIELDS) continue
            val hasProtoNumber = prop.annotations.any { it is ProtoNumber }
            if (!hasProtoNumber) {
                errors.add("GameData.${prop.name}: ${prop.returnType}")
            }
        }
        assertTrue(
            buildErrorMessage("GameData", errors),
            errors.isEmpty()
        )
    }

    @Test
    fun `all SaveData fields have ProtoNumber annotation`() {
        val errors = mutableListOf<String>()
        for (prop in SaveData::class.memberProperties) {
            if (prop.name in EXCLUDED_FIELDS) continue
            // @Transient 字段不需要 @ProtoNumber
            if (prop.annotations.any { it is Transient }) continue
            val hasProtoNumber = prop.annotations.any { it is ProtoNumber }
            if (!hasProtoNumber) {
                errors.add("SaveData.${prop.name}: ${prop.returnType}")
            }
        }
        assertTrue(
            buildErrorMessage("SaveData", errors),
            errors.isEmpty()
        )
    }

    private fun buildErrorMessage(className: String, errors: List<String>): String {
        if (errors.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("========================================")
        sb.appendLine("$className 中以下字段缺少 @ProtoNumber 注解：")
        sb.appendLine("========================================")
        sb.appendLine()
        errors.forEach { sb.appendLine("  - $it") }
        sb.appendLine()
        sb.appendLine("请为每个字段添加 @ProtoNumber(n)，其中 n 为全局唯一的编号。")
        sb.appendLine("如该字段不应参与云存档序列化，请标注 @kotlinx.serialization.Transient。")
        sb.appendLine("仅对无法标注 @Transient 的 computed property，才加入 EXCLUDED_FIELDS。")
        sb.appendLine("========================================")
        return sb.toString()
    }
}
