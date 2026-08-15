package com.xianxia.sect.analytics

import com.xianxia.sect.core.util.AnalyticsEvents
import com.xianxia.sect.data.SessionManager
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [TapDBAnalyticsTracker] 埋点派生测试：FTUE 漏斗 "首次" 事件转换逻辑。
 * FirstEventTracker/SessionManager 用 mockito-kotlin mock（类型安全 matcher）；
 * TapDBManager 真实调用在 Robolectric 下被 Throwable 兜底吞掉，不影响断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TapDBAnalyticsTrackerTest {

    private lateinit var firstEventTracker: FirstEventTracker
    private lateinit var sessionManager: SessionManager
    private lateinit var tracker: TapDBAnalyticsTracker

    @Before
    fun setUp() {
        firstEventTracker = mock()
        sessionManager = mock()
        whenever(sessionManager.userId).thenReturn("user-1")
        tracker = TapDBAnalyticsTracker(firstEventTracker, sessionManager)
    }

    @Test
    fun `trackEvent - battle_end 胜利派生首次战斗胜利`() {
        tracker.trackEvent(
            AnalyticsEvents.BATTLE_END,
            mapOf(AnalyticsEvents.PROP_OUTCOME to "win")
        )
        verify(firstEventTracker).trackFirst(eq("user-1"), eq(AnalyticsEvents.BATTLE_FIRST_WIN), any())
    }

    @Test
    fun `trackEvent - battle_end 失败不派生首次战斗胜利`() {
        tracker.trackEvent(
            AnalyticsEvents.BATTLE_END,
            mapOf(AnalyticsEvents.PROP_OUTCOME to "lose")
        )
        verify(firstEventTracker, never()).trackFirst(any(), any(), any())
    }

    @Test
    fun `trackEvent - breakthrough_success 派生首次突破`() {
        tracker.trackEvent(
            AnalyticsEvents.BREAKTHROUGH_SUCCESS,
            mapOf(AnalyticsEvents.PROP_REALM to 8)
        )
        verify(firstEventTracker).trackFirst(eq("user-1"), eq(AnalyticsEvents.BREAKTHROUGH_FIRST), any())
    }

    @Test
    fun `trackEvent - 普通事件不触发首次派生`() {
        tracker.trackEvent(AnalyticsEvents.GAME_START, emptyMap())
        verify(firstEventTracker, never()).trackFirst(any(), any(), any())
    }
}
