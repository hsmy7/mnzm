package com.xianxia.sect.data.unified

/**
 * 本地单档摘要投影（三态：有存档 / 空档 / 读取失败）。
 *
 * [isEmpty] 与 [isLoadError] 互斥语义：空档 = 本地无数据（可创建新游戏）；
 * 读取失败 = 本地有数据但查询异常（数据库不可达/schema 不匹配等），
 * **不得**当作空档提供"点击创建"入口——损坏存档被空档伪装覆盖是数据丢失事故。
 */
data class SaveInfo(
    val timestamp: Long = 0L,
    val gameYear: Int = 1,
    val gameMonth: Int = 1,
    val sectName: String = "",
    val discipleCount: Int = 0,
    val spiritStones: Long = 0L,
    val isEmpty: Boolean = false,
    val isLoadError: Boolean = false
) {
    val displayTime: String get() = "第${gameYear}年${gameMonth}月"
    val saveTime: String
        get() = if (timestamp > 0) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
            .format(java.util.Date(timestamp)) else "--"
}
