package com.xianxia.sect.core.model

import androidx.annotation.Keep
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

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
 * @param sequenceId 追加序号（P-9：消息列表稳定 key，头部 takeLast 移除时
 *   不引起其余条目 key 位移）。0 表示旧档未分配（加载后一次性回填）
 */
@Keep
@Serializable
data class GameEventRecord(
    @ProtoNumber(1) val timestamp: Long = System.currentTimeMillis(),
    @ProtoNumber(2) val year: Int = 1,
    @ProtoNumber(3) val month: Int = 1,
    @ProtoNumber(4) val phase: Int = 0,
    @ProtoNumber(5) val category: String = "SECT",
    @ProtoNumber(6) val eventType: String = "",
    @ProtoNumber(7) val summary: String = "",
    @ProtoNumber(8) val relatedEntityId: String = "",
    @ProtoNumber(9) val relatedEntityName: String = "",
    @ProtoNumber(10) val sequenceId: Long = 0
)

/**
 * 计算下一条事件序号（S5 修复：Long.MAX_VALUE + 1 溢出为负数会与已有序号
 * 碰撞导致 LazyColumn 重复 key 崩溃；溢出时从头（1）重新计数）。
 *
 * @param records 当前事件列表（含未回填的旧档条目）
 * @return 下一条序号（恒为正数，且不等于溢出前的 MAX_VALUE 本身）
 */
fun nextEventSequenceId(records: List<GameEventRecord>): Long {
    val max = records.maxOfOrNull { it.sequenceId } ?: 0L
    return if (max >= Long.MAX_VALUE - 1) 1L else max + 1
}

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
    const val SECRET_REALM = "secret_realm"
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
