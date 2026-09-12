package com.xianxia.sect.analytics

import com.xianxia.sect.taptap.TapDBManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 首次事件去重（FTUE 漏斗 "首次" 语义，见 rules/data-analytics.md）。
 *
 * 按用户（userId，即 TapDB setUser 的 openid）持久化标记：
 * 同一用户同一事件仅上报一次；登出/重登不丢；换账号不串。
 *
 * 存储：MMKV 统一偏好；旧 SharedPreferences
 * 一次性迁移（首次访问时懒执行，幂等）。
 */
@Singleton
class FirstEventTracker @Inject constructor(
    private val keyValueStore: com.xianxia.sect.data.prefs.KeyValueStore
) {

    @Volatile
    private var migrated = false

    private fun ensureMigrated() {
        if (migrated) return
        synchronized(this) {
            if (migrated) return
            keyValueStore.migrateFromSharedPreferences(PREFS_NAME)
            migrated = true
        }
    }

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
        ensureMigrated()
        val key = firstEventKey(userId, eventName)
        if (keyValueStore.getBoolean(key, false)) return false
        keyValueStore.putBoolean(key, true)
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
