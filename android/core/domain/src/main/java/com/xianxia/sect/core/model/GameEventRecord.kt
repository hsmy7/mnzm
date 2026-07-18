package com.xianxia.sect.core.model

import androidx.annotation.Keep
import kotlinx.serialization.Serializable

/**
 * 游戏事件记录——消息栏系统的数据单位。
 * 记录游戏中发生的各类事件，持久化到 GameData，随存档保存。
 *
 * @param timestamp 系统时间戳，用于排序
 * @param year 游戏年
 * @param month 游戏月（1-12）
 * @param phase 游戏旬（0=上旬, 1=中旬, 2=下旬）
 * @param category 事件分类："WORLD"（世界/AI宗门）或"SECT"（玩家宗门）
 * @param eventType 事件类型标识，见 [GameEventType] 常量定义
 * @param summary 显示文本
 * @param relatedEntityId 关联实体 ID（弟子 ID、宗门 ID 等）
 * @param relatedEntityName 关联实体名称
 */
@Keep
@Serializable
data class GameEventRecord(
    val timestamp: Long = System.currentTimeMillis(),
    val year: Int = 1,
    val month: Int = 1,
    val phase: Int = 0,
    val category: String = "SECT",
    val eventType: String = "",
    val summary: String = "",
    val relatedEntityId: String = "",
    val relatedEntityName: String = ""
)

/** 游戏事件类型常量——统一管理，避免散落的字符串字面量 */
object GameEventType {
    const val DESERTION = "desertion"
    const val THEFT_CAUGHT = "theft_caught"
    const val WAREHOUSE_THEFT = "warehouse_theft"
    const val THEFT_DESERTION = "theft_desertion"
    const val DEATH = "death"
    const val BREAKTHROUGH = "breakthrough"
    const val MARRIAGE = "marriage"
    const val BLOOD_REFINEMENT = "blood_refinement"
    const val ALLIANCE = "alliance"
    const val ALLIANCE_BREAK = "alliance_break"
    const val VASSAL_BREAKAWAY = "vassal_breakaway"
    const val BEAST_HUNT = "ai_beast_hunt"
    const val BEAST_FAIL = "ai_beast_fail"
    const val ENCOUNTER_HUNT = "ai_encounter_hunt"
    const val ENCOUNTER_FAIL = "ai_encounter_fail"
    const val SECT_OCCUPY = "sect_occupy"
}

/**
 * 事件分类枚举——UI 层标签切换使用。
 * 存储到 GameData 时使用 [category] 字段的字符串值（"WORLD"/"SECT"）。
 */
enum class GameEventCategory(val label: String) {
    WORLD("世界"),
    SECT("宗门");

    companion object {
        fun fromValue(value: String): GameEventCategory =
            try { valueOf(value) } catch (_: Exception) { SECT }
    }
}
