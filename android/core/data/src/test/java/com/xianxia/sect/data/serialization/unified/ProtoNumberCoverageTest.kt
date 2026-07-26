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
 * 替代旧的 SerializationCoverageTest（验收来自对 Serializable* 包装类型的覆盖检查）。
 */
class ProtoNumberCoverageTest {

    /**
     * GameData 和 SaveData 中不序列化到云存档的字段及原因。
     */
    private val EXCLUDED_FIELDS = mapOf(
        // GameData Room 主键
        "id" to "Room 复合主键，非游戏字段",
        "slotId" to "Room 复合主键，非游戏字段",

        // GameData 不支持 Protobuf 直接序列化的字段
        "aiSectDisciples" to "Map<String, List<Disciple>> 无法直接 Protobuf 序列化（@Transient）",

        // GameData 旧兼容字段（逻辑层已废弃）
        "battleTeam" to "旧 Room schema 兼容字段，逻辑层已废弃",
        "aiBattleTeams" to "旧 AI 战斗队伍字段（已从序列化移除），仅 Room 兼容",

        // GameData @Ignore 运行时字段
        "aiSectBeastSkipCooldowns" to "运行时 AI 妖兽跳过冷却",
        "aiBeastEncounterTargets" to "运行时 AI 妖兽遭遇目标",
        "lockedBeastIds" to "运行时妖兽锁定",
        "aiSectBeastDirectTargets" to "运行时 AI 妖兽直攻目标",
        "battleTeams" to "运行时战斗队伍",
        "usedTeamNumbers" to "运行时队伍编号复用",

        // GameData 计算属性（仅 getter，无 backing field）
        "displayTime" to "格式化显示时间 getter",
        "worldMap" to "世界地图聚合 getter",
        "buildings" to "建筑状态聚合 getter",
        "economy" to "经济状态聚合 getter",
        "organization" to "组织架构聚合 getter",
        "exploration" to "探索状态聚合 getter",
        "isPlayerProtected" to "玩家保护状态计算 getter",
        "playerProtectionRemainingYears" to "玩家保护剩余年数计算 getter",

        // SaveData
        "equipmentStacks" to "本地 Room 专用，云存档从实例重建（@Transient）",
        "manualStacks" to "本地 Room 专用，云存档从实例重建（@Transient）",
    )

    @Test
    fun `all GameData fields have ProtoNumber annotation`() {
        val errors = mutableListOf<String>()
        for (prop in GameData::class.memberProperties) {
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
        sb.appendLine("如该字段不应参与云存档序列化，请将其加入 EXCLUDED_FIELDS 映射。")
        sb.appendLine("========================================")
        return sb.toString()
    }
}
