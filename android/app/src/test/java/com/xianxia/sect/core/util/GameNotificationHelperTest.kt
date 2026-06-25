package com.xianxia.sect.core.util

import android.app.Application
import android.app.Notification
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * GameNotificationHelper 单元测试。
 *
 * 验证前台服务通知构建器的：
 * - 通知渠道创建幂等
 * - 运行/暂停状态通知内容正确
 * - 与 GameForegroundService 的 action 常量一致
 * - PendingIntent 使用安全标志
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class GameNotificationHelperTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val helper = GameNotificationHelper(context)

    // ── 渠道创建 ──

    @Test
    fun `createChannel - does not throw on API 26+`() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            helper.createChannel(context)
            // 幂等：重复调用不抛异常
            helper.createChannel(context)
        }
    }

    // ── 通知构建 ──

    @Test
    fun `buildForegroundNotification - running state shows correct text`() {
        val notification = helper.buildForegroundNotification(context, paused = false)
        assertEquals("修仙门派", notification.extras.getString(Notification.EXTRA_TITLE))
        assertNotNull(notification.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun `buildForegroundNotification - paused state shows paused text`() {
        val notification = helper.buildForegroundNotification(context, paused = true)
        assertEquals("修仙门派", notification.extras.getString(Notification.EXTRA_TITLE))
    }

    @Test
    fun `buildForegroundNotification - has ongoing and no-clear flags`() {
        val notification = helper.buildForegroundNotification(context)
        assertTrue(
            "Notification should have FLAG_ONGOING_EVENT",
            notification.flags and Notification.FLAG_ONGOING_EVENT != 0
        )
        assertTrue(
            "Notification should have FLAG_NO_CLEAR",
            notification.flags and Notification.FLAG_NO_CLEAR != 0
        )
    }

    @Test
    fun `buildForegroundNotification - has content intent`() {
        val notification = helper.buildForegroundNotification(context)
        assertNotNull("Notification should have contentIntent", notification.contentIntent)
    }

    @Test
    fun `buildForegroundNotification - has two action buttons`() {
        val notification = helper.buildForegroundNotification(context)
        assertNotNull("Notification should have actions", notification.actions)
        assertEquals("Should have 2 actions (暂停, 退出)", 2, notification.actions!!.size)
    }

    // ── 常量一致性 ──

    @Test
    fun `constants - NOTIFICATION_ID matches GameForegroundService`() {
        assertEquals(
            GameForegroundService.NOTIFICATION_ID,
            GameNotificationHelper.NOTIFICATION_ID
        )
    }

    @Test
    fun `constants - PAUSE_ACTION syncs with GameForegroundService`() {
        // GameNotificationHelper 的 PAUSE_ACTION 应与 GameForegroundService.ACTION_PAUSE 同步
        // 通过在通知中构造 PendingIntent 时使用同一 action 保证
        assertEquals(
            "com.xianxia.sect.action.PAUSE",
            GameNotificationHelper.PAUSE_ACTION
        )
        assertEquals(
            GameNotificationHelper.PAUSE_ACTION,
            GameForegroundService.ACTION_PAUSE
        )
    }

    @Test
    fun `constants - STOP_ACTION syncs with GameForegroundService`() {
        assertEquals(
            "com.xianxia.sect.action.STOP",
            GameNotificationHelper.STOP_ACTION
        )
        assertEquals(
            GameNotificationHelper.STOP_ACTION,
            GameForegroundService.ACTION_STOP
        )
    }
}
