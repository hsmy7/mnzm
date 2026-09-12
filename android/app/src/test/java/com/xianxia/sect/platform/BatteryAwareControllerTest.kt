package com.xianxia.sect.platform

import android.app.Application
import android.content.Context
import com.xianxia.sect.core.thermal.BatteryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * BatteryAwareController 平台读取行为测试（Robolectric）。
 *
 * 降载策略判定矩阵由引擎层 [com.xianxia.sect.core.thermal.evaluatePowerPolicy]
 * 的 BatteryStatusProviderTest 覆盖；本文件只覆盖 Android 平台读取的安全回退：
 * - 无省电模式广播：isPowerSaveMode 初值 false（安全回退不降载）
 * - 无电池 sticky 广播（Robolectric 环境 registerReceiver 返回 null）：
 *   安全回退不降载、不崩溃
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)  // app 模块约定：禁启真实 XianxiaApplication（SDK/广告全量初始化）
class BatteryAwareControllerTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `power save - controller default false without broadcast`() {
        // Robolectric 无广播环境：isPowerSaveMode 初值 false（安全回退不降载）
        val controller = BatteryAwareController(context)
        assertFalse(controller.isPowerSaveMode)
    }

    @Test
    fun `no battery broadcast - safe fallback no degradation and no crash`() {
        // Robolectric 无 sticky 广播：registerReceiver 返回 null → 不降载不崩溃
        val controller = BatteryAwareController(context)
        assertFalse(controller.isLowBattery)
        assertFalse(controller.isPowerSaveMode)
        assertEquals(BatteryPolicy.MAX_FPS_CAP, controller.fpsCap)
        assertEquals(0f, controller.thermalThresholdOffsetC, 0.001f)
    }
}
