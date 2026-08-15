package com.xianxia.sect.analytics

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [FirstEventTracker] 首次事件去重测试。
 *
 * 真实 SharedPreferences + 真实 TapDBManager（Robolectric 下 SDK 调用被 Throwable
 * 兜底吞掉，不影响去重语义验证——标记在 SDK 调用前置位）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FirstEventTrackerTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var tracker: FirstEventTracker

    @Before
    fun setUp() {
        tracker = FirstEventTracker(app)
        app.getSharedPreferences(FirstEventTracker.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `trackFirst - 首次上报返回 true 且重复调用跳过`() {
        val first = tracker.trackFirst("user-1", "#battle_first_win", mapOf("outcome" to "win"))
        assertTrue("首次应上报并返回 true", first)
        val second = tracker.trackFirst("user-1", "#battle_first_win")
        assertFalse("重复调用应跳过并返回 false", second)
    }

    @Test
    fun `trackFirst - 不同用户互不影响`() {
        tracker.trackFirst("user-1", "#breakthrough_first")
        val otherFirst = tracker.trackFirst("user-2", "#breakthrough_first")
        assertTrue("换账号应视为首次", otherFirst)
    }

    @Test
    fun `trackFirst - 未登录按设备维度去重`() {
        tracker.trackFirst(null, "#battle_first_win")
        assertFalse("同设备重复调用应跳过", tracker.trackFirst(null, "#battle_first_win"))
    }

    @Test
    fun `trackFirst - 首次标记跨实例持久化（登出重登不重报）`() {
        tracker.trackFirst("user-1", "#battle_first_win")
        val newTracker = FirstEventTracker(app)
        assertFalse("新实例应读取到已置位标记", newTracker.trackFirst("user-1", "#battle_first_win"))
    }
}
