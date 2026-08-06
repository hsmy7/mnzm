package com.xianxia.sect.core.concurrent

import com.xianxia.sect.core.thermal.ThermalReader
import com.xianxia.sect.core.thermal.ThermalState
import com.xianxia.sect.core.util.DomainLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 热控控制器 — 根据设备温度和帧率动态调整并行度与渲染质量。
 *
 * ## 多级降级阶梯（P1.4）
 *
 * | 等级 | 温度 | 帧率条件 | 并行 | 渲染 | 帧率 |
 * |------|------|---------|------|------|------|
 * | GREEN | <40°C | ≥30fps | 全(4) | 完整 | 60 |
 * | YELLOW|40-42°C|25-30fps| 半(2) | 低特效 | 45 |
 * | ORANGE|42-45°C|20-25fps| 单(1) | 关后处理 | 30 |
 * | RED | >45°C | <20fps | 单(1) | 最低 | 30锁定 |
 *
 * ## 温度读取（参见 [ThermalReader]）
 * - Channel 1: PowerManager.getThermalHeadroom() (API 30+) — 主动预测
 * - Channel 2: PowerManager.currentThermalStatus (API 29+) — 被动状态
 * - Channel 3: sysfs + BatteryManager — 降级回退
 *
 * 降温后逐步升档，防止反复跳变。
 *
 * @param profiler 设备能力分析器
 * @param thermalReader 三通道温度读取器
 * @param checkIntervalMs 检查间隔（ms），测试可注入短间隔
 */
@Singleton
class ThermalController @Inject constructor(
    private val profiler: DeviceCapabilityProfiler,
    private val thermalReader: ThermalReader,
    private val checkIntervalMs: Long = CHECK_INTERVAL_MS
) {
    companion object {
        private const val TAG = "ThermalController"
        /** 默认检查间隔（ms） */
        const val CHECK_INTERVAL_MS = 10_000L
        /** 各级温度阈值 */
        private const val TEMP_RED_THRESHOLD_C = 45f
        private const val TEMP_ORANGE_THRESHOLD_C = 42f
        private const val TEMP_YELLOW_THRESHOLD_C = 40f
        private const val TEMP_GREEN_THRESHOLD_C = 38f
        /** 各级帧率阈值 */
        private const val FPS_RED_THRESHOLD = 20f
        private const val FPS_ORANGE_THRESHOLD = 25f
        private const val FPS_YELLOW_THRESHOLD = 30f
        /** 降级后稳定检查次数 */
        private const val STABILIZE_CHECKS = 3
    }

    // ──────── 降级等级定义 ────────

    /** 热控降级等级 */
    enum class DegradationLevel(
        val workerCount: Int,
        val renderingQuality: Float,
        val recommendedTargetFps: Int,
        val disableParticles: Boolean,
        val disablePostProcessing: Boolean,
        val lockFrameRate: Boolean
    ) {
        GREEN(4, 1.0f, 60, false, false, false),
        YELLOW(2, 0.8f, 45, false, false, false),
        ORANGE(1, 0.6f, 30, true, true, true),
        RED(1, 0.4f, 30, true, true, true)
    }

    /** 当前降级等级 */
    @Volatile
    var currentLevel: DegradationLevel = DegradationLevel.GREEN
        private set

    /** 渲染质量因子 */
    @Volatile
    var renderingQualityFactor: Float = 1.0f
        private set

    /** 推荐目标帧率 */
    @Volatile
    var recommendedTargetFps: Int = 60
        private set

    /** 是否关闭粒子特效 */
    @Volatile
    var particlesDisabled: Boolean = false
        private set

    /** 是否关闭后处理 */
    @Volatile
    var postProcessingDisabled: Boolean = false
        private set

    /** 当前是否处于过热降级状态 */
    @Volatile
    var isThrottled: Boolean = false
        private set

    /** 当前热状态 */
    @Volatile
    var currentThermalState: ThermalState = ThermalState.UNKNOWN
        private set

    private var lastCheckTime = 0L
    private var consecutiveLowFps = 0
    private var upgradeCounter = 0

    /** 温度阈值偏移（°C，负值提前降载；低电量时由接线方传 -2） */
    @Volatile
    private var thresholdOffsetC = 0f

    /** 上次记录的温度（°C），-1 表示不可用 */
    @Volatile
    var lastTemperature: Float = -1f
        private set

    /** 当前有效的并行度 */
    val effectiveParallelism: Int
        get() = if (isThrottled) currentLevel.workerCount else profiler.recommendedWorkerCount

    /** 是否允许并行 tick */
    val enableParallelTick: Boolean
        get() = !isThrottled && profiler.enableParallelTick

    init {
        // 注册热状态回调（平台支持时）
        thermalReader.registerThermalCallback { state ->
            currentThermalState = state
            DomainLog.d(TAG, "Thermal state changed to $state")
        }
    }

    /**
     * 检查是否需要降级并行度，返回当前等级。
     *
     * @param recentFps 最近的帧率（0 表示未知）
     * @return 当前降级等级
     */
    fun checkAndAdjust(recentFps: Float): DegradationLevel {
        val now = System.currentTimeMillis()
        if (now - lastCheckTime < checkIntervalMs) {
            return currentLevel
        }
        lastCheckTime = now

        val temp = readTemperature()
        lastTemperature = temp

        val newLevel = evaluateLevel(temp, recentFps)
        applyLevel(newLevel)

        DomainLog.d(TAG, "Thermal: temp=${temp}°C, fps=$recentFps, " +
            "level=${newLevel.name}, quality=${"%.2f".format(newLevel.renderingQuality)}, " +
            "workers=${newLevel.workerCount}, throttle=$isThrottled")

        return currentLevel
    }

    /**
     * 设置温度阈值偏移（°C）。负值提前降载（如低电量 -2°C），正值延后。
     * 由接线方（GameEngineCore tick）随电量状态低频更新；reset 时清零。
     *
     * @param offset 阈值偏移量（°C）
     */
    fun setThresholdOffsetC(offset: Float) {
        thresholdOffsetC = offset
    }

    /**
     * 根据温度和帧率评估降级等级。
     */
    private fun evaluateLevel(temp: Float, recentFps: Float): DegradationLevel {
        // ── 温度驱动降级（阈值带电量偏移） ──
        if (temp > 0) {
            when {
                temp >= TEMP_RED_THRESHOLD_C + thresholdOffsetC -> return DegradationLevel.RED
                temp >= TEMP_ORANGE_THRESHOLD_C + thresholdOffsetC -> return DegradationLevel.ORANGE
                temp >= TEMP_YELLOW_THRESHOLD_C + thresholdOffsetC -> return DegradationLevel.YELLOW
                temp <= TEMP_GREEN_THRESHOLD_C -> { /* 已冷却，继续检查 */ }
            }
        }

        // ── 帧率驱动降级 ──
        if (recentFps > 0) {
            when {
                recentFps < FPS_RED_THRESHOLD -> {
                    consecutiveLowFps++
                    if (consecutiveLowFps >= STABILIZE_CHECKS) {
                        consecutiveLowFps = 0
                        return DegradationLevel.RED
                    }
                }
                recentFps < FPS_ORANGE_THRESHOLD -> {
                    consecutiveLowFps++
                    if (consecutiveLowFps >= STABILIZE_CHECKS) {
                        consecutiveLowFps = 0
                        return DegradationLevel.ORANGE
                    }
                }
                recentFps < FPS_YELLOW_THRESHOLD -> {
                    consecutiveLowFps++
                    if (consecutiveLowFps >= STABILIZE_CHECKS) {
                        consecutiveLowFps = 0
                        return DegradationLevel.YELLOW
                    }
                }
                else -> { consecutiveLowFps = 0 }
            }
        }

        // ── 已降级 → 升档检查（阈值带电量偏移，与降级判定一致） ──
        if (currentLevel != DegradationLevel.GREEN) {
            val shouldUpgrade = when (currentLevel) {
                DegradationLevel.RED -> temp <= 0 || temp <= TEMP_ORANGE_THRESHOLD_C + thresholdOffsetC
                DegradationLevel.ORANGE -> temp <= 0 || temp <= TEMP_YELLOW_THRESHOLD_C + thresholdOffsetC
                DegradationLevel.YELLOW -> temp <= 0 || temp <= TEMP_GREEN_THRESHOLD_C + thresholdOffsetC
                else -> true
            }
            if (shouldUpgrade && recentFps >= FPS_YELLOW_THRESHOLD) {
                upgradeCounter++
                if (upgradeCounter >= STABILIZE_CHECKS) {
                    upgradeCounter = 0
                    return when (currentLevel) {
                        DegradationLevel.RED -> DegradationLevel.ORANGE
                        DegradationLevel.ORANGE -> DegradationLevel.YELLOW
                        DegradationLevel.YELLOW -> DegradationLevel.GREEN
                        else -> DegradationLevel.GREEN
                    }
                }
            } else {
                upgradeCounter = 0
            }
            return currentLevel
        }

        return DegradationLevel.GREEN
    }

    private fun applyLevel(level: DegradationLevel) {
        val prevLevel = currentLevel
        currentLevel = level
        renderingQualityFactor = level.renderingQuality
        recommendedTargetFps = level.recommendedTargetFps
        particlesDisabled = level.disableParticles
        postProcessingDisabled = level.disablePostProcessing

        isThrottled = level != DegradationLevel.GREEN
        profiler.forceSerialByThermal = isThrottled

        if (prevLevel != level) {
            val action = if (level < prevLevel) "升级" else "降级"
            DomainLog.i(TAG, "Thermal $action: ${prevLevel.name} → ${level.name} " +
                "(temp=${lastTemperature}°C, quality=${"%.2f".format(level.renderingQuality)}, " +
                "fps=$recommendedTargetFps, workers=${level.workerCount})")
        }
    }

    /**
     * 读取设备温度，委托给 [ThermalReader] 三通道方案。
     *
     * 优先级：
     * 1. `ThermalReader.temperatureCelsius`（含 headroom 反推）
     * 2. 热状态映射（当 headroom 不可用时）
     * 3. -1f（完全不可用）
     */
    private fun readTemperature(): Float {
        // 第一优先：ThermalReader 温度值
        val celsius = thermalReader.temperatureCelsius
        if (celsius >= 0f && celsius <= 80f) return celsius

        // 第二优先：用 headroom 估算
        val hr = thermalReader.thermalHeadroom
        if (!hr.isNaN() && hr >= 0f) {
            return headroomToTemperature(hr)
        }

        // 第三优先：热状态映射
        return when (thermalReader.thermalState) {
            ThermalState.CRITICAL -> 46f
            ThermalState.SERIOUS -> 43f
            ThermalState.FAIR -> 40f
            ThermalState.NOMINAL -> 36f
            ThermalState.UNKNOWN -> -1f
        }
    }

    /** headroom (0.0~1.0+) → 估算温度 (°C) */
    private fun headroomToTemperature(headroom: Float): Float = when {
        headroom >= 1.0f -> 46f
        headroom >= 0.95f -> 44f
        headroom >= 0.85f -> 42f
        headroom >= 0.5f -> 40f
        headroom >= 0.05f -> 38f
        else -> 36f
    }

    /** 重置所有状态（设备从休眠恢复时调用） */
    fun reset() {
        currentLevel = DegradationLevel.GREEN
        isThrottled = false
        renderingQualityFactor = 1.0f
        recommendedTargetFps = 60
        particlesDisabled = false
        postProcessingDisabled = false
        currentThermalState = ThermalState.UNKNOWN
        profiler.forceSerialByThermal = false
        consecutiveLowFps = 0
        upgradeCounter = 0
        lastCheckTime = 0L
        lastTemperature = -1f
        thresholdOffsetC = 0f
        thermalReader.unregisterThermalCallback()
    }
}
