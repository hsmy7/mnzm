package com.xianxia.sect.core.thermal

import android.content.Context
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 电量感知控制器测试（低电量 ≤20% 未充电 → 降帧/提前降载）。
 *
 * 覆盖维度：
 * - [BatteryAwareController.evaluateBatteryPolicy] 判定矩阵（阈值/边界/充电豁免）
 * - 无电池广播（Robolectric 环境 registerReceiver 返回 null）时安全回退
 * - NoopBatteryStatus 永不降载
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
class BatteryAwareControllerTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `policy - low battery not charging degrades`() {
        val (low, cap, offset) = BatteryAwareController(context)
            .evaluateBatteryPolicy(levelPercent = 15, charging = false)
        assertTrue(low)
        assertEquals(BatteryAwareController.LOW_BATTERY_FPS_CAP, cap)
        assertEquals(BatteryAwareController.LOW_BATTERY_THRESHOLD_OFFSET_C, offset, 0.001f)
    }

    @Test
    fun `policy - boundary at exactly 20 percent degrades`() {
        val (low, cap, _) = BatteryAwareController(context)
            .evaluateBatteryPolicy(levelPercent = 20, charging = false)
        assertTrue(low)
        assertEquals(BatteryAwareController.LOW_BATTERY_FPS_CAP, cap)
    }

    @Test
    fun `policy - above 20 percent no degradation`() {
        val (low, cap, offset) = BatteryAwareController(context)
            .evaluateBatteryPolicy(levelPercent = 21, charging = false)
        assertFalse(low)
        assertEquals(BatteryAwareController.MAX_FPS_CAP, cap)
        assertEquals(0f, offset, 0.001f)
    }

    @Test
    fun `policy - zero percent degrades`() {
        val (low, _, _) = BatteryAwareController(context)
            .evaluateBatteryPolicy(levelPercent = 0, charging = false)
        assertTrue(low)
    }

    @Test
    fun `policy - charging exempts even at very low level`() {
        val (low, cap, offset) = BatteryAwareController(context)
            .evaluateBatteryPolicy(levelPercent = 10, charging = true)
        assertFalse(low)
        assertEquals(BatteryAwareController.MAX_FPS_CAP, cap)
        assertEquals(0f, offset, 0.001f)
    }

    @Test
    fun `policy - full battery no degradation`() {
        val (low, cap, _) = BatteryAwareController(context)
            .evaluateBatteryPolicy(levelPercent = 100, charging = false)
        assertFalse(low)
        assertEquals(BatteryAwareController.MAX_FPS_CAP, cap)
    }

    @Test
    fun `policy - unknown level treated as not low`() {
        val (low, cap, _) = BatteryAwareController(context)
            .evaluateBatteryPolicy(levelPercent = -1, charging = false)
        assertFalse(low)
        assertEquals(BatteryAwareController.MAX_FPS_CAP, cap)
    }

    @Test
    fun `no battery broadcast - safe fallback no degradation and no crash`() {
        // Robolectric 无 sticky 广播：registerReceiver 返回 null → 不降载不崩溃
        val controller = BatteryAwareController(context)
        assertFalse(controller.isLowBattery)
        assertEquals(BatteryAwareController.MAX_FPS_CAP, controller.fpsCap)
        assertEquals(0f, controller.thermalThresholdOffsetC, 0.001f)
    }

    @Test
    fun `noop battery status - never degrades`() {
        assertFalse(NoopBatteryStatus.isLowBattery)
        assertEquals(BatteryAwareController.MAX_FPS_CAP, NoopBatteryStatus.fpsCap)
        assertEquals(0f, NoopBatteryStatus.thermalThresholdOffsetC, 0.001f)
    }
}
