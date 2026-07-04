package com.xianxia.sect.core.concurrent

import android.os.Build
import android.os.CpuUsageInfo
import android.os.health.SystemHealthManager
import com.xianxia.sect.core.util.DomainLog
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 热控控制器 — 根据设备温度和帧率动态调整并行度。
 *
 * 当设备发热或帧率下降时自动降级并行度，防止过热降频导致性能更差。
 * 设备冷却后自动恢复全并行。
 */
@Singleton
class ThermalController @Inject constructor(
    private val profiler: DeviceCapabilityProfiler
) {
    companion object {
        private const val TAG = "ThermalController"
        /** 降级检查间隔（ms） */
        private const val CHECK_INTERVAL_MS = 10_000L
        /** 过热阈值（°C）——超过此值降级并行度 */
        private const val OVERHEAT_THRESHOLD_C = 45f
        /** 恢复阈值（°C）——低于此值恢复全并行 */
        private const val RECOVER_THRESHOLD_C = 40f
        /** 帧率过低阈值（连续低于此值触发降级） */
        private const val LOW_FPS_THRESHOLD = 25f
        /** 降级后冷却检查次数 */
        private const val COOLDOWN_CHECKS = 3
    }

    /** 当前是否处于过热降级状态 */
    @Volatile
    var isThrottled: Boolean = false
        private set

    private var lastCheckTime = 0L
    private var consecutiveLowFps = 0
    private var cooldownCounter = 0

    /** 上次记录的温度（°C），-1 表示无法读取 */
    @Volatile
    var lastTemperature: Float = -1f
        private set

    /** 当前有效的并行度（考虑降级后的值） */
    val effectiveParallelism: Int
        get() = if (isThrottled) 1 else profiler.recommendedWorkerCount

    /** 是否允许并行 tick */
    val enableParallelTick: Boolean
        get() = !isThrottled && profiler.enableParallelTick

    /**
     * 检查是否需要降级并行度。
     * 每 [CHECK_INTERVAL_MS] 执行一次实际检查。
     *
     * @param recentFps 最近的帧率（0 表示未知）
     * @return true 表示降级已生效或不变，false 表示全并行可恢复
     */
    fun checkAndAdjust(recentFps: Float): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastCheckTime < CHECK_INTERVAL_MS) {
            return isThrottled
        }
        lastCheckTime = now

        val temp = readTemperature()

        // 过热检测
        if (temp > 0 && temp >= OVERHEAT_THRESHOLD_C) {
            if (!isThrottled) {
                DomainLog.w(TAG, "Thermal throttle: temp=${temp}°C >= ${OVERHEAT_THRESHOLD_C}°C")
            }
            isThrottled = true
            profiler.forceSerialByThermal = true
            cooldownCounter = 0
            lastTemperature = temp
            return true
        }

        // 低帧率检测
        if (recentFps > 0 && recentFps < LOW_FPS_THRESHOLD) {
            consecutiveLowFps++
            if (consecutiveLowFps >= COOLDOWN_CHECKS && !isThrottled) {
                DomainLog.w(TAG, "FPS throttle: fps=$recentFps < ${LOW_FPS_THRESHOLD} for $consecutiveLowFps checks")
                isThrottled = true
                profiler.forceSerialByThermal = true
                cooldownCounter = 0
                lastTemperature = temp
                return true
            }
        } else {
            consecutiveLowFps = 0
        }

        // 降级后冷却恢复
        if (isThrottled) {
            if (temp <= 0 || temp <= RECOVER_THRESHOLD_C) {
                cooldownCounter++
                if (cooldownCounter >= COOLDOWN_CHECKS) {
                    DomainLog.i(TAG, "Thermal recovery: temp=${temp}°C, resetting throttle")
                    isThrottled = false
                    profiler.forceSerialByThermal = false
                    consecutiveLowFps = 0
                    cooldownCounter = 0
                    return false
                }
            } else {
                cooldownCounter = 0
            }
        }

        lastTemperature = temp
        return isThrottled
    }

    /**
     * 读取设备温度。
     *
     * 尝试多个热区文件（不同厂商路径不同），均失败返回 -1。
     */
    private fun readTemperature(): Float {
        // 常见的热区文件路径（按优先级排列）
        val thermalPaths = listOf(
            "/sys/class/thermal/thermal_zone0/temp",  // 通用
            "/sys/class/thermal/thermal_zone1/temp",
            "/sys/class/thermal/thermal_zone2/temp",
            "/sys/class/thermal/thermal_zone10/temp", // 有些 SoC 的 CPU 温度在此
            "/sys/devices/virtual/thermal/thermal_zone0/temp",
            "/sys/devices/virtual/thermal/thermal_zone1/temp",
        )
        for (path in thermalPaths) {
            try {
                val content = File(path).readText().trim()
                val temp = content.toFloatOrNull() ?: continue
                // 内核温度通常以毫摄氏度（m°C）返回
                return if (temp > 1000) temp / 1000f else temp
            } catch (_: Exception) {
                continue
            }
        }
        return -1f
    }

    /** 重置所有状态（设备从休眠恢复时调用） */
    fun reset() {
        isThrottled = false
        profiler.forceSerialByThermal = false
        consecutiveLowFps = 0
        cooldownCounter = 0
        lastCheckTime = 0L
        lastTemperature = -1f
    }
}
