package com.xianxia.sect.core.model

import androidx.annotation.Keep
import kotlinx.serialization.Serializable

/**
 * 游戏时间旬枚举
 * 每旬 = 1/3 月 ≈ 10 天（仙侠世界观中的上中下旬）
 */
@Keep
@Serializable
enum class GamePhase(val displayName: String, val value: Int) {
    EARLY("上旬", 0),
    MID("中旬", 1),
    LATE("下旬", 2);

    companion object {
        const val PHASES_PER_MONTH = 3

        fun fromValue(value: Int): GamePhase =
            entries.firstOrNull { it.value == value } ?: EARLY

        /** 从旧 gameDay 映射到旬（兼容旧存档加载） */
        fun fromOldGameDay(oldDay: Int): GamePhase {
            val normalized = (oldDay - 1).coerceIn(0, 29)
            return when {
                normalized < 10 -> EARLY
                normalized < 20 -> MID
                else -> LATE
            }
        }
    }
}
