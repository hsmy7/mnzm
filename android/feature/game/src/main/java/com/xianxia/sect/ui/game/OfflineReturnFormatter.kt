package com.xianxia.sect.ui.game

/**
 * 离线回归提示的现实时长通俗格式化（结算改造 2026-09-27 B7）。
 *
 * 展示纪律（双 changelog 同口径）：给玩家看的离线时长只做粗略描述，
 * 不出现折算速率/上限数值细节。
 */

private const val MINUTE_MS: Long = 60L * 1000L
private const val HOUR_MS: Long = 60L * MINUTE_MS
private const val DAY_MS: Long = 24L * HOUR_MS

/** 离线时长 → 通俗文案（"片刻" / "N 分钟" / "N 小时 M 分钟" / "N 天 M 小时"） */
fun formatOfflineDuration(offlineWallMs: Long): String {
    if (offlineWallMs <= 0L) return "片刻"
    if (offlineWallMs < MINUTE_MS) return "片刻"
    if (offlineWallMs < HOUR_MS) {
        val minutes = offlineWallMs / MINUTE_MS
        return "$minutes 分钟"
    }
    if (offlineWallMs < DAY_MS) {
        val hours = offlineWallMs / HOUR_MS
        val minutes = (offlineWallMs % HOUR_MS) / MINUTE_MS
        return if (minutes == 0L) "$hours 小时" else "$hours 小时 $minutes 分钟"
    }
    val days = offlineWallMs / DAY_MS
    val hours = (offlineWallMs % DAY_MS) / HOUR_MS
    return if (hours == 0L) "$days 天" else "$days 天 $hours 小时"
}
