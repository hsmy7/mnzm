package com.xianxia.sect.taptap

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 排行榜上报节流纯策略（可单测）。
 *
 * 战力为单调增数值且服务端保留更高分，节流仅防频繁请求：
 * 从未上报 / 跨天 / 战力变化 任一条件满足即上报。
 */
object LeaderboardUploadPolicy {

    /** 日期格式器（线程安全：SimpleDateFormat 非线程安全，但静态常量仅供单线程按需调用） */
    private val dateFormatter = SimpleDateFormat(LeaderboardConstants.DATE_PATTERN, Locale.US)

    /**
     * 是否应上报。
     * @param power 当前宗门战力
     * @param lastUploadedPower 上次成功上报的战力（从未上报为 null）
     * @param lastUploadDate 上次成功上报日期（yyyy-MM-dd，从未上报为 null）
     * @param today 今天日期（yyyy-MM-dd）
     */
    fun shouldUpload(
        power: Long,
        lastUploadedPower: Long?,
        lastUploadDate: String?,
        today: String
    ): Boolean = when {
        power <= 0L -> false
        lastUploadDate == null || lastUploadedPower == null -> true
        else -> lastUploadDate != today || lastUploadedPower != power
    }

    /** 格式化时间戳为节流日期（yyyy-MM-dd） */
    fun formatDate(timestamp: Long): String = dateFormatter.format(Date(timestamp))
}
