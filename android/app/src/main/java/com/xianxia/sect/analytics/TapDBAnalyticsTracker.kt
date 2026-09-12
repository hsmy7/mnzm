package com.xianxia.sect.analytics

import com.xianxia.sect.core.util.AnalyticsEvents
import com.xianxia.sect.core.util.AnalyticsTracker
import com.xianxia.sect.data.SessionManager
import com.xianxia.sect.taptap.TapDBManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 引擎 [AnalyticsTracker] 的 app 层实现（TapDB）。
 *
 * 除原样转发外，负责 FTUE 漏斗 "首次" 事件的派生（首次语义由 [FirstEventTracker] 去重）：
 * - `battle_end`(win) → `#battle_first_win`
 * - `breakthrough_success` → `#breakthrough_first`
 */
@Singleton
class TapDBAnalyticsTracker @Inject constructor(
    private val firstEventTracker: FirstEventTracker,
    private val sessionManager: SessionManager
) : AnalyticsTracker {

    override fun trackEvent(eventName: String, properties: Map<String, Any>) {
        TapDBManager.trackEvent(eventName, properties)
        when (eventName) {
            AnalyticsEvents.BATTLE_END -> {
                if (properties[AnalyticsEvents.PROP_OUTCOME] == OUTCOME_WIN) {
                    firstEventTracker.trackFirst(
                        sessionManager.userId,
                        AnalyticsEvents.BATTLE_FIRST_WIN,
                        properties
                    )
                }
            }
            AnalyticsEvents.BREAKTHROUGH_SUCCESS -> {
                firstEventTracker.trackFirst(
                    sessionManager.userId,
                    AnalyticsEvents.BREAKTHROUGH_FIRST,
                    properties
                )
            }
        }
    }

    companion object {
        private const val OUTCOME_WIN = "win"
    }
}
