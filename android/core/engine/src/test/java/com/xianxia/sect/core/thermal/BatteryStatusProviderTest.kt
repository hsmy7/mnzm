package com.xianxia.sect.core.thermal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 电量/省电模式降载策略测试（低电量 ≤20% 未充电 → 降帧/提前降载；
 * 系统省电模式 → 30fps 上限；两级独立生效取 min，2026-08-14 扩展）。
 *
 * 覆盖维度：
 * - [evaluatePowerPolicy] 判定矩阵（阈值/边界/充电豁免/省电组合）
 * - NoopBatteryStatus 永不降载
 *
 * Android 平台读取行为（sticky 广播回退等）由 app 层
 * `com.xianxia.sect.platform.BatteryAwareControllerTest` 覆盖（2026-08-28
 * 平台能力接口化：策略纯函数留引擎，平台实现移 app）。
 */
class BatteryStatusProviderTest {

    @Test
    fun `policy - low battery not charging degrades`() {
        val policy = evaluatePowerPolicy(powerSaveMode = false, levelPercent = 15, charging = false)
        assertTrue(policy.isLowBattery)
        assertEquals(BatteryPolicy.LOW_BATTERY_FPS_CAP, policy.fpsCap)
        assertEquals(BatteryPolicy.LOW_BATTERY_THRESHOLD_OFFSET_C, policy.thermalThresholdOffsetC, 0.001f)
    }

    @Test
    fun `policy - boundary at exactly 20 percent degrades`() {
        val policy = evaluatePowerPolicy(powerSaveMode = false, levelPercent = 20, charging = false)
        assertTrue(policy.isLowBattery)
        assertEquals(BatteryPolicy.LOW_BATTERY_FPS_CAP, policy.fpsCap)
    }

    @Test
    fun `policy - above 20 percent no degradation`() {
        val policy = evaluatePowerPolicy(powerSaveMode = false, levelPercent = 21, charging = false)
        assertFalse(policy.isLowBattery)
        assertEquals(BatteryPolicy.MAX_FPS_CAP, policy.fpsCap)
        assertEquals(0f, policy.thermalThresholdOffsetC, 0.001f)
    }

    @Test
    fun `policy - zero percent degrades`() {
        val policy = evaluatePowerPolicy(powerSaveMode = false, levelPercent = 0, charging = false)
        assertTrue(policy.isLowBattery)
    }

    @Test
    fun `policy - charging exempts even at very low level`() {
        val policy = evaluatePowerPolicy(powerSaveMode = false, levelPercent = 10, charging = true)
        assertFalse(policy.isLowBattery)
        assertEquals(BatteryPolicy.MAX_FPS_CAP, policy.fpsCap)
        assertEquals(0f, policy.thermalThresholdOffsetC, 0.001f)
    }

    @Test
    fun `policy - full battery no degradation`() {
        val policy = evaluatePowerPolicy(powerSaveMode = false, levelPercent = 100, charging = false)
        assertFalse(policy.isLowBattery)
        assertEquals(BatteryPolicy.MAX_FPS_CAP, policy.fpsCap)
    }

    @Test
    fun `policy - unknown level treated as not low`() {
        val policy = evaluatePowerPolicy(powerSaveMode = false, levelPercent = -1, charging = false)
        assertFalse(policy.isLowBattery)
        assertEquals(BatteryPolicy.MAX_FPS_CAP, policy.fpsCap)
    }

    // ── 省电模式（2026-08-14 新增：30fps 上限，两级降载取 min） ──

    @Test
    fun `power save - caps fps at 30 regardless of battery`() {
        val policy = evaluatePowerPolicy(powerSaveMode = true, levelPercent = 100, charging = false)
        assertFalse(policy.isLowBattery)
        assertEquals(BatteryPolicy.POWER_SAVE_FPS_CAP, policy.fpsCap)
        assertEquals(0f, policy.thermalThresholdOffsetC, 0.001f)
    }

    @Test
    fun `power save - charging does not exempt power save cap`() {
        // 省电模式是用户主动意愿，充电不免除（与低电量豁免语义不同）
        val policy = evaluatePowerPolicy(powerSaveMode = true, levelPercent = 80, charging = true)
        assertEquals(BatteryPolicy.POWER_SAVE_FPS_CAP, policy.fpsCap)
    }

    @Test
    fun `power save - combined with low battery takes min 30`() {
        val policy = evaluatePowerPolicy(powerSaveMode = true, levelPercent = 15, charging = false)
        assertTrue(policy.isLowBattery)
        // min(低电量 45, 省电 30) = 30
        assertEquals(BatteryPolicy.POWER_SAVE_FPS_CAP, policy.fpsCap)
        // 低电量热控偏移仍生效
        assertEquals(BatteryPolicy.LOW_BATTERY_THRESHOLD_OFFSET_C, policy.thermalThresholdOffsetC, 0.001f)
    }

    @Test
    fun `power save - low battery alone stays 45 cap`() {
        val policy = evaluatePowerPolicy(powerSaveMode = false, levelPercent = 5, charging = false)
        assertEquals(BatteryPolicy.LOW_BATTERY_FPS_CAP, policy.fpsCap)
    }

    @Test
    fun `noop battery status - never degrades`() {
        assertFalse(NoopBatteryStatus.isLowBattery)
        assertFalse(NoopBatteryStatus.isPowerSaveMode)
        assertEquals(BatteryPolicy.MAX_FPS_CAP, NoopBatteryStatus.fpsCap)
        assertEquals(0f, NoopBatteryStatus.thermalThresholdOffsetC, 0.001f)
    }
}
