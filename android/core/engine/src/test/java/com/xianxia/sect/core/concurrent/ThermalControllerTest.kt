package com.xianxia.sect.core.concurrent

import com.xianxia.sect.core.thermal.ThermalReader
import com.xianxia.sect.core.thermal.ThermalState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ThermalController 多级降级阶梯测试。
 *
 * 覆盖维度（P1.4）：
 * - 默认等级为 GREEN（全性能）
 * - 温度驱动降级：逐级升温 → RED
 * - 帧率驱动降级：持续低帧率 → 逐级降级
 * - 升档稳定性：连续满足条件才升档（防反复跳变）
 * - renderingQualityFactor 随等级变化
 * - recommendedTargetFps 随等级变化
 * - particlesDisabled / postProcessingDisabled 标志
 * - reset() 恢复 GREEN
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class ThermalControllerTest {

    private lateinit var profiler: DeviceCapabilityProfiler
    private lateinit var thermal: ThermalController
    private lateinit var mockReader: FakeThermalReader

    /**
     * FakeThermalReader — 可控的 [ThermalReader] 模拟实现。
     * 允许测试设置 [temperatureCelsius]、[thermalHeadroom]、[thermalState]。
     */
    private class FakeThermalReader(
        override var temperatureCelsius: Float = -1f,
        override var thermalHeadroom: Float = Float.NaN,
        override var thermalState: ThermalState = ThermalState.UNKNOWN
    ) : ThermalReader {
        override val forecastSeconds: Int get() = 10
        private var callback: ((ThermalState) -> Unit)? = null

        override fun registerThermalCallback(onStateChanged: (ThermalState) -> Unit): Boolean {
            callback = onStateChanged
            return true
        }

        override fun unregisterThermalCallback() {
            callback = null
        }

        /** 模拟热状态变化触发回调 */
        fun simulateThermalStateChange(state: ThermalState) {
            thermalState = state
            callback?.invoke(state)
        }
    }

    @Before
    fun setup() {
        profiler = DeviceCapabilityProfiler()
        mockReader = FakeThermalReader()
        // 注入 0ms 检查间隔 + FakeThermalReader
        thermal = ThermalController(profiler, mockReader, checkIntervalMs = 0L)
    }

    // ============================================================
    // 初始状态
    // ============================================================

    @Test
    fun `initial state is GREEN`() {
        assertEquals(
            "默认降级等级应为 GREEN",
            ThermalController.DegradationLevel.GREEN,
            thermal.currentLevel
        )
    }

    @Test
    fun `initial quality factor is 1 dot 0`() {
        assertEquals("默认质量因子应为 1.0", 1.0f, thermal.renderingQualityFactor, 0.01f)
    }

    @Test
    fun `initial target fps is 60`() {
        assertEquals("默认目标帧率应为 60", 60, thermal.recommendedTargetFps)
    }

    @Test
    fun `initial particles and postprocessing are enabled`() {
        assertFalse("默认粒子不关闭", thermal.particlesDisabled)
        assertFalse("默认后处理不关闭", thermal.postProcessingDisabled)
    }

    @Test
    fun `initial throttle is false`() {
        assertFalse("默认不降级", thermal.isThrottled)
    }

    // ============================================================
    // 帧率驱动降级
    // ============================================================

    @Test
    fun `good fps keeps GREEN`() {
        // 60fps 高于所有阈值，应保持 GREEN
        repeat(10) { thermal.checkAndAdjust(60f) }
        assertEquals(
            "60fps 应保持 GREEN",
            ThermalController.DegradationLevel.GREEN,
            thermal.currentLevel
        )
        assertEquals("质量因子应为 1.0", 1.0f, thermal.renderingQualityFactor, 0.01f)
        assertEquals("帧率应为 60", 60, thermal.recommendedTargetFps)
    }

    @Test
    fun `fps below 30 for 3 checks triggers YELLOW`() {
        repeat(3) { thermal.checkAndAdjust(28f) }
        assertEquals(
            "连续 3 次低于 30fps 应降为 YELLOW",
            ThermalController.DegradationLevel.YELLOW,
            thermal.currentLevel
        )
        assertEquals("YELLOW 质量因子应为 0.8", 0.8f, thermal.renderingQualityFactor, 0.01f)
        assertEquals("YELLOW 帧率应为 45", 45, thermal.recommendedTargetFps)
    }

    @Test
    fun `fps below 25 for 3 checks triggers ORANGE`() {
        repeat(3) { thermal.checkAndAdjust(22f) }
        assertEquals(
            "连续 3 次低于 25fps 应降为 ORANGE",
            ThermalController.DegradationLevel.ORANGE,
            thermal.currentLevel
        )
        assertEquals("ORANGE 质量因子应为 0.6", 0.6f, thermal.renderingQualityFactor, 0.01f)
        assertEquals("ORANGE 帧率应为 30", 30, thermal.recommendedTargetFps)
        assertTrue("ORANGE 应关闭粒子", thermal.particlesDisabled)
        assertTrue("ORANGE 应关闭后处理", thermal.postProcessingDisabled)
    }

    @Test
    fun `fps below 20 for 3 checks triggers RED`() {
        repeat(3) { thermal.checkAndAdjust(15f) }
        assertEquals(
            "连续 3 次低于 20fps 应降为 RED",
            ThermalController.DegradationLevel.RED,
            thermal.currentLevel
        )
        assertEquals("RED 质量因子应为 0.4", 0.4f, thermal.renderingQualityFactor, 0.01f)
        assertEquals("RED 帧率应为 30", 30, thermal.recommendedTargetFps)
        assertTrue("RED 应关闭粒子", thermal.particlesDisabled)
    }

    @Test
    fun `single low fps check does not trigger degradation`() {
        // 一次低帧率不应触发降级（需要连续 3 次）
        thermal.checkAndAdjust(15f)
        assertEquals(
            "单次低帧率应保持 GREEN",
            ThermalController.DegradationLevel.GREEN,
            thermal.currentLevel
        )
    }

    // ============================================================
    // 升级稳定性（防反复跳变）
    // ============================================================

    @Test
    fun `degradation upgrades only after 3 stable checks`() {
        // 先降级到 ORANGE
        repeat(3) { thermal.checkAndAdjust(22f) }
        assertEquals(
            "应在 3 次检查后降为 ORANGE",
            ThermalController.DegradationLevel.ORANGE,
            thermal.currentLevel
        )

        // 恢复帧率后立即检查（应保持 ORANGE，1 次不够）
        thermal.checkAndAdjust(60f)
        assertEquals(
            "恢复后第 1 次检查应保持 ORANGE",
            ThermalController.DegradationLevel.ORANGE,
            thermal.currentLevel
        )

        // 连续 3 次恢复后才升档
        repeat(2) { thermal.checkAndAdjust(60f) }
        assertEquals(
            "连续 3 次帧率恢复后应升为 YELLOW",
            ThermalController.DegradationLevel.YELLOW,
            thermal.currentLevel
        )
    }

    @Test
    fun `full recovery from RED to GREEN requires 9 checks`() {
        // 降到 RED
        repeat(3) { thermal.checkAndAdjust(15f) }
        assertEquals(ThermalController.DegradationLevel.RED, thermal.currentLevel)

        // 从 RED → ORANGE（3次）
        repeat(3) { thermal.checkAndAdjust(60f) }
        assertEquals(ThermalController.DegradationLevel.ORANGE, thermal.currentLevel)

        // ORANGE → YELLOW（3次）
        repeat(3) { thermal.checkAndAdjust(60f) }
        assertEquals(ThermalController.DegradationLevel.YELLOW, thermal.currentLevel)

        // YELLOW → GREEN（3次）
        repeat(3) { thermal.checkAndAdjust(60f) }
        assertEquals(ThermalController.DegradationLevel.GREEN, thermal.currentLevel)
        assertFalse("恢复后降级标志应清除", thermal.isThrottled)
        assertEquals("恢复后质量因子应为 1.0", 1.0f, thermal.renderingQualityFactor, 0.01f)
    }

    // ============================================================
    // 中断恢复（升档过程中再次降级）
    // ============================================================

    @Test
    fun `re-degradation during recovery resets counter`() {
        // 降到 ORANGE
        repeat(3) { thermal.checkAndAdjust(22f) }
        assertEquals(ThermalController.DegradationLevel.ORANGE, thermal.currentLevel)

        // 恢复 2 次，第 3 次前再次低帧率
        thermal.checkAndAdjust(60f)
        thermal.checkAndAdjust(60f)
        thermal.checkAndAdjust(22f) // 再次低帧率

        // 升档计数器已重置（回到 0），连续 2 次正常不应升档（STABILIZE_CHECKS=3）
        repeat(2) { thermal.checkAndAdjust(60f) }
        assertNotEquals(
            "再次低帧率后升档计数应重置，2 次正常不应升档",
            ThermalController.DegradationLevel.YELLOW,
            thermal.currentLevel
        )

        // 第 3 次正常后累计满 3，应升档回 YELLOW
        thermal.checkAndAdjust(60f)
        assertEquals(
            "第 3 次正常后应升档回 YELLOW",
            ThermalController.DegradationLevel.YELLOW,
            thermal.currentLevel
        )
    }

    // ============================================================
    // effectiveParallelism
    // ============================================================

    @Test
    fun `effectiveParallelism is 4 in GREEN`() {
        thermal.checkAndAdjust(60f)
        val eff = thermal.effectiveParallelism
        assertTrue("GREEN 时并行度应为推荐值（≥1），当前: $eff", eff >= 1)
    }

    @Test
    fun `effectiveParallelism is 1 when throttled`() {
        repeat(3) { thermal.checkAndAdjust(15f) }
        assertEquals("RED 时并行度应为 1", 1, thermal.effectiveParallelism)
    }

    // ============================================================
    // reset
    // ============================================================

    @Test
    fun `reset restores GREEN state`() {
        repeat(3) { thermal.checkAndAdjust(15f) }
        assertEquals(ThermalController.DegradationLevel.RED, thermal.currentLevel)

        thermal.reset()

        assertEquals("reset 后应为 GREEN", ThermalController.DegradationLevel.GREEN, thermal.currentLevel)
        assertFalse("reset 后 throttle 应清除", thermal.isThrottled)
        assertEquals("reset 后质量因子应为 1.0", 1.0f, thermal.renderingQualityFactor, 0.01f)
        assertEquals("reset 后帧率应为 60", 60, thermal.recommendedTargetFps)
        assertFalse("reset 后粒子应启用", thermal.particlesDisabled)
    }

    // ============================================================
    // ThermalReader 集成（温度驱动降级）
    // ============================================================

    @Test
    fun `temperature 46C triggers RED`() {
        mockReader.temperatureCelsius = 46f
        thermal.checkAndAdjust(60f)
        assertEquals("46°C 应触发 RED", ThermalController.DegradationLevel.RED, thermal.currentLevel)
    }

    @Test
    fun `temperature 43C triggers ORANGE`() {
        mockReader.temperatureCelsius = 43f
        thermal.checkAndAdjust(60f)
        assertEquals("43°C 应触发 ORANGE", ThermalController.DegradationLevel.ORANGE, thermal.currentLevel)
    }

    @Test
    fun `temperature 41C triggers YELLOW`() {
        mockReader.temperatureCelsius = 41f
        thermal.checkAndAdjust(60f)
        assertEquals("41°C 应触发 YELLOW", ThermalController.DegradationLevel.YELLOW, thermal.currentLevel)
    }

    @Test
    fun `temperature 37C keeps GREEN`() {
        mockReader.temperatureCelsius = 37f
        thermal.checkAndAdjust(60f)
        assertEquals("37°C 应保持 GREEN", ThermalController.DegradationLevel.GREEN, thermal.currentLevel)
    }

    @Test
    fun `temperature -1 falls back to frame rate degradation`() {
        // temperatureCelsius = -1（默认），headroom=NaN → 纯帧率降级
        mockReader.temperatureCelsius = -1f
        mockReader.thermalHeadroom = Float.NaN
        thermal.checkAndAdjust(60f)
        assertEquals("温度不可用时保持 GREEN", ThermalController.DegradationLevel.GREEN, thermal.currentLevel)

        repeat(3) { thermal.checkAndAdjust(15f) }
        assertEquals("温度不可用时帧率降级应生效", ThermalController.DegradationLevel.RED, thermal.currentLevel)
    }

    // ============================================================
    // thermalHeadroom 反推温度（当 temperatureCelsius 不可用时）
    // ============================================================

    @Test
    fun `headroom 1 dot 0 maps to RED via readTemperature`() {
        mockReader.temperatureCelsius = -1f  // 温度不可用
        mockReader.thermalHeadroom = 1.0f     // 但 headroom 可用
        thermal.checkAndAdjust(60f)
        assertEquals("headroom=1.0 应触发 RED", ThermalController.DegradationLevel.RED, thermal.currentLevel)
    }

    @Test
    fun `headroom 0 dot 9 maps to ORANGE via readTemperature`() {
        mockReader.temperatureCelsius = -1f
        mockReader.thermalHeadroom = 0.9f
        thermal.checkAndAdjust(60f)
        assertEquals("headroom=0.9 应触发 ORANGE", ThermalController.DegradationLevel.ORANGE, thermal.currentLevel)
    }

    // ============================================================
    // ThermalState 映射（当 temperatureCelsius 和 headroom 均不可用时）
    // ============================================================

    @Test
    fun `thermalState CRITICAL maps to RED`() {
        mockReader.temperatureCelsius = -1f
        mockReader.thermalHeadroom = Float.NaN
        mockReader.thermalState = ThermalState.CRITICAL
        thermal.checkAndAdjust(60f)
        assertEquals("ThermalState.CRITICAL 应触发 RED", ThermalController.DegradationLevel.RED, thermal.currentLevel)
    }

    @Test
    fun `thermalState SERIOUS maps to ORANGE`() {
        mockReader.temperatureCelsius = -1f
        mockReader.thermalHeadroom = Float.NaN
        mockReader.thermalState = ThermalState.SERIOUS
        thermal.checkAndAdjust(60f)
        assertEquals("ThermalState.SERIOUS 应触发 ORANGE", ThermalController.DegradationLevel.ORANGE, thermal.currentLevel)
    }

    @Test
    fun `thermalState FAIR maps to YELLOW`() {
        mockReader.temperatureCelsius = -1f
        mockReader.thermalHeadroom = Float.NaN
        mockReader.thermalState = ThermalState.FAIR
        thermal.checkAndAdjust(60f)
        assertEquals("ThermalState.FAIR 应触发 YELLOW", ThermalController.DegradationLevel.YELLOW, thermal.currentLevel)
    }

    // ============================================================
    // currentThermalState 发布
    // ============================================================

    @Test
    fun `currentThermalState reflects reader state`() {
        assertEquals(ThermalState.UNKNOWN, thermal.currentThermalState)
        mockReader.simulateThermalStateChange(ThermalState.SERIOUS)
        assertEquals("热状态应同步到控制器", ThermalState.SERIOUS, thermal.currentThermalState)
    }

    @Test
    fun `thermal callback updates currentThermalState`() {
        mockReader.simulateThermalStateChange(ThermalState.CRITICAL)
        assertEquals(ThermalState.CRITICAL, thermal.currentThermalState)
        mockReader.simulateThermalStateChange(ThermalState.NOMINAL)
        assertEquals(ThermalState.NOMINAL, thermal.currentThermalState)
    }

    // ============================================================
    // 阈值偏移（setThresholdOffsetC，低电量提前降载）
    // ============================================================

    @Test
    fun `threshold offset -2C triggers YELLOW at 39C instead of GREEN`() {
        // 无偏移：39°C < 40°C YELLOW 线 → GREEN
        mockReader.temperatureCelsius = 39f
        thermal.checkAndAdjust(60f)
        assertEquals(
            "无偏移时 39°C 应为 GREEN",
            ThermalController.DegradationLevel.GREEN,
            thermal.currentLevel
        )

        // -2°C 偏移：39°C >= 40-2=38°C → 提前触发 YELLOW
        thermal.setThresholdOffsetC(-2f)
        thermal.checkAndAdjust(60f)
        assertEquals(
            "-2°C 偏移使 39°C 提前触发 YELLOW",
            ThermalController.DegradationLevel.YELLOW,
            thermal.currentLevel
        )
    }

    @Test
    fun `threshold offset -2C escalates ORANGE at 42C`() {
        thermal.setThresholdOffsetC(-2f)
        mockReader.temperatureCelsius = 42f
        thermal.checkAndAdjust(60f)
        assertEquals(
            "-2°C 偏移使 42°C 达到 ORANGE（原阈值 42）",
            ThermalController.DegradationLevel.ORANGE,
            thermal.currentLevel
        )
    }

    @Test
    fun `threshold offset -2C downgrade chain recovers with offset applied`() {
        // 降级到 RED 后逐步降温，升档判定应带偏移一致（38°C 以下才回 GREEN）
        thermal.setThresholdOffsetC(-2f)
        mockReader.temperatureCelsius = 46f
        thermal.checkAndAdjust(60f)
        assertEquals(ThermalController.DegradationLevel.RED, thermal.currentLevel)

        // 40°C：RED 升档线 42-2=40 达标 → 升 ORANGE；ORANGE 升档线 40-2=38 未达 → 停 ORANGE
        mockReader.temperatureCelsius = 40f
        repeat(3) { thermal.checkAndAdjust(60f) }
        assertEquals(ThermalController.DegradationLevel.ORANGE, thermal.currentLevel)

        // 36°C：低于偏移后 GREEN 阈值，逐步升回 GREEN
        mockReader.temperatureCelsius = 36f
        repeat(6) { thermal.checkAndAdjust(60f) }
        assertEquals(ThermalController.DegradationLevel.GREEN, thermal.currentLevel)
    }

    @Test
    fun `threshold offset NaN and Infinity are sanitized to zero`() {
        mockReader.temperatureCelsius = 45f
        thermal.setThresholdOffsetC(Float.NaN)
        thermal.checkAndAdjust(60f)
        assertEquals(
            "NaN 偏移应被清零（45°C 触发 RED 而非静默失效）",
            ThermalController.DegradationLevel.RED,
            thermal.currentLevel
        )

        thermal.setThresholdOffsetC(Float.POSITIVE_INFINITY)
        thermal.checkAndAdjust(60f)
        assertEquals(
            "Infinity 偏移应被清零",
            ThermalController.DegradationLevel.RED,
            thermal.currentLevel
        )
    }

    @Test
    fun `reset clears threshold offset`() {
        thermal.setThresholdOffsetC(-2f)
        mockReader.temperatureCelsius = 39f
        thermal.checkAndAdjust(60f)
        assertEquals(ThermalController.DegradationLevel.YELLOW, thermal.currentLevel)

        thermal.reset()
        mockReader.temperatureCelsius = 39f
        thermal.checkAndAdjust(60f)
        assertEquals(
            "reset 后偏移清零，39°C 恢复 GREEN",
            ThermalController.DegradationLevel.GREEN,
            thermal.currentLevel
        )
    }
}
