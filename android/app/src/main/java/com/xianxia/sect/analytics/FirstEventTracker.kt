package com.xianxia.sect.analytics

import android.content.Context
import android.content.SharedPreferences
import com.xianxia.sect.taptap.TapDBManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 首次事件去重（FTUE 漏斗 "首次" 语义，见 rules/data-analytics.md）。
 *
 * 按用户（userId，即 TapDB setUser 的 openid）持久化标记：
 * 同一用户同一事件仅上报一次；登出/重登不丢；换账号不串。
 */
@Singleton
class FirstEventTracker @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 上报首次事件：本次为首发则上报并置位，返回 true；已上报过则静默跳过，返回 false。
     *
     * @param userId 当前登录用户 ID（TapDB setUser 的 userId）；未登录按设备维度去重
     * @param eventName 首次事件名（如 #battle_first_win）
     * @param properties 事件属性
     */
    @Synchronized
    fun trackFirst(
        userId: String?,
        eventName: String,
        properties: Map<String, Any> = emptyMap()
    ): Boolean {
        val key = firstEventKey(userId, eventName)
        val prefs = prefs
        if (prefs.getBoolean(key, false)) return false
        prefs.edit().putBoolean(key, true).apply()
        TapDBManager.trackEvent(eventName, properties)
        return true
    }

    /** 首次事件持久化 key（测试可观察） */
    internal fun firstEventKey(userId: String?, eventName: String): String =
        "first_${userId ?: "device"}_$eventName"

    companion object {
        internal const val PREFS_NAME = "tapdb_first_events"
    }
}
