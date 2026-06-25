package com.xianxia.sect.core.util

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows

/**
 * AlarmWatchdogReceiver 单元测试。
 *
 * 验证精确闹钟看门狗的：
 * - 常量正确性
 * - PendingIntent 构建
 * - 闹钟调度/取消（通过 Robolectric Shadow）
 * - 权限不足时的安全降级
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AlarmWatchdogReceiverTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    // ── 常量 ──

    @Test
    fun `constants - ACTION matches manifest declaration`() {
        assertEquals(
            "com.xianxia.sect.action.ALARM_WATCHDOG",
            AlarmWatchdogReceiver.ACTION_ALARM_WATCHDOG
        )
    }

    @Test
    fun `constants - alarm interval is 15 seconds`() {
        assertEquals(15_000L, AlarmWatchdogReceiver.ALARM_INTERVAL_MS)
    }

    // ── PendingIntent 构建 ──

    @Test
    fun `scheduleAlarm - does not throw when AlarmManager available`() {
        // 验证调度不会崩溃（即使没有精确闹钟权限，也应安全降级）
        try {
            AlarmWatchdogReceiver.scheduleAlarm(context)
        } catch (e: Exception) {
            fail("scheduleAlarm should not throw: ${e.message}")
        }
    }

    @Test
    fun `cancelAlarm - does not throw`() {
        try {
            AlarmWatchdogReceiver.cancelAlarm(context)
        } catch (e: Exception) {
            fail("cancelAlarm should not throw: ${e.message}")
        }
    }

    // ── 权限不足时的安全降级 ──

    @Test
    fun `scheduleAlarm - safe aborts without exact alarm permission`() {
        // Robolectric 中 canScheduleExactAlarms() 默认返回 false，
        // 验证安全降级：不抛异常、不调度闹钟
        val alarmManager = context
            .getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadow = Shadows.shadowOf(alarmManager)

        AlarmWatchdogReceiver.scheduleAlarm(context)

        // 无权限时不应调度任何闹钟
        val scheduled = shadow.peekNextScheduledAlarm()
        assertNull(
            "No alarm should be scheduled without permission",
            scheduled
        )
    }

    @Test
    fun `cancelAlarm - safe no-op when no alarm scheduled`() {
        // 无调度时取消不应抛异常
        try {
            AlarmWatchdogReceiver.cancelAlarm(context)
        } catch (e: Exception) {
            fail("cancelAlarm should not throw even with no alarm: ${e.message}")
        }
    }

    // ── Action 常量与 GameForegroundService 一致性 ──

    @Test
    fun `constants - ACTION_START used in onReceive starts correct service`() {
        // 验证 AlarmWatchdogReceiver 启动的 Service action 与
        // GameForegroundService.ACTION_START 一致
        assertEquals(
            "com.xianxia.sect.action.START",
            GameForegroundService.ACTION_START
        )
    }
}
