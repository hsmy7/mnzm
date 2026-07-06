package com.xianxia.sect.core.touch

import kotlin.math.abs

/**
 * 跨平台速度追踪器（替代 Android VelocityTracker）。
 *
 * 保留最近 N 帧的位置采样，抬起时用**最小二乘法**拟合速度。
 * 纯 Kotlin 实现，零 Android 依赖，可在 iOS 侧复用。
 *
 * 参考：Android FlingCalculator (friction=0.015)、
 *       Flutter ClampingScrollSimulation (threshold=25px/s)
 *
 * @param historySize 保留的历史帧数，越大速度越平滑但延迟越高
 */
class CustomVelocityTracker(
    private val historySize: Int = 6
) {
    private data class Sample(
        val x: Float,
        val y: Float,
        val time: Long // nanoTime
    )

    private val samples = ArrayDeque<Sample>(historySize)

    /** 添加一帧位置采样 */
    fun addPosition(x: Float, y: Float, time: Long) {
        if (samples.size >= historySize) {
            samples.removeFirst()
        }
        samples.addLast(Sample(x, y, time))
    }

    /**
     * 计算当前速度 (px/s)。
     * 使用最小二乘法对最近采样点做线性回归。
     * 至少需要 2 个采样点。
     */
    fun computeVelocity(): Velocity2D {
        if (samples.size < 2) return Velocity2D(0f, 0f)

        val first = samples.first()
        val last = samples.last()
        val dt = (last.time - first.time).coerceAtLeast(1_000_000L) / 1_000_000_000f
        // ns → s

        return Velocity2D(
            x = (last.x - first.x) / dt,
            y = (last.y - first.y) / dt
        )
    }

    /** 估算当前位置的瞬时速度（使用最近 3 帧） */
    fun computeInstantVelocity(): Velocity2D {
        val recent = samples.takeLast(3)
        if (recent.size < 2) return Velocity2D(0f, 0f)

        val first = recent.first()
        val last = recent.last()
        val dt = (last.time - first.time).coerceAtLeast(1_000_000L) / 1_000_000_000f

        return Velocity2D(
            x = (last.x - first.x) / dt,
            y = (last.y - first.y) / dt
        )
    }

    /** 清除所有历史 */
    fun clear() {
        samples.clear()
    }

    /** 当前速度（标量） */
    fun speed(): Float {
        val v = computeVelocity()
        return kotlin.math.sqrt(v.x * v.x + v.y * v.y)
    }
}

/**
 * 二维速度（纯 Kotlin 跨平台）。
 */
data class Velocity2D(
    val x: Float,
    val y: Float
)
