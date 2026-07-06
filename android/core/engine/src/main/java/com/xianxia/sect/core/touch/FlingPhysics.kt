package com.xianxia.sect.core.touch

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 惯性滑行物理引擎 — 纯 Kotlin 跨平台。
 *
 * 使用**线性减速模型**（恒减速度）：
 *   v(t) = v0 - deceleration × t
 *   dv/dt = -deceleration × sign(v)
 *
 * 比指数衰减更可控、物理直觉更清晰。
 * 减速 3000px/s → 停止 ≈ 3000/1500 = 2 秒。
 *
 * @param deceleration 减速度 (px/s²)，越大停得越快
 * @param velocityThreshold 停止速度阈值 (px/s)，低于此值归零
 */
class FlingPhysics(
    private val deceleration: Float = 1500f,
    private val velocityThreshold: Float = 25f
) {
    private var vx = 0f
    private var vy = 0f
    private var active = false

    /** 是否正在惯性滑行中 */
    val isActive: Boolean get() = active

    /** 当前速度 X (px/s) */
    val velocityX: Float get() = vx

    /** 当前速度 Y (px/s) */
    val velocityY: Float get() = vy

    /** 当前速度标量 (px/s) */
    val speed: Float get() = sqrt(vx * vx + vy * vy)

    /**
     * 启动惯性滑行。
     * @param vx 初始 X 速度 (px/s)
     * @param vy 初始 Y 速度 (px/s)
     */
    fun start(vx: Float, vy: Float) {
        this.vx = vx.coerceIn(-MAX_VELOCITY, MAX_VELOCITY)
        this.vy = vy.coerceIn(-MAX_VELOCITY, MAX_VELOCITY)
        active = true
    }

    /**
     * 更新一帧。
     * @param dt 帧时间间隔（秒），建议 0.016f (60fps)
     * @return 本帧的位移量 (px)
     */
    fun update(dt: Float): Offset2D {
        if (!active) return Offset2D(0f, 0f)

        val dx = vx * dt
        val dy = vy * dt

        // 线性减速: dv = -deceleration × dt × sign(v)
        val decel = deceleration * dt
        vx = if (vx > 0) maxOf(0f, vx - decel) else minOf(0f, vx + decel)
        vy = if (vy > 0) maxOf(0f, vy - decel) else minOf(0f, vy + decel)

        // 低于阈值则停止
        if (abs(vx) < velocityThreshold) vx = 0f
        if (abs(vy) < velocityThreshold) vy = 0f

        if (vx == 0f && vy == 0f) {
            active = false
        }

        return Offset2D(dx, dy)
    }

    /** 停止惯性滑行 */
    fun stop() {
        vx = 0f
        vy = 0f
        active = false
    }

    companion object {
        /** 最大初速度限制 (px/s)，防止极端情况 */
        private const val MAX_VELOCITY = 15000f

        /** 默认帧间隔 (ms) */
        const val DEFAULT_FRAME_INTERVAL_MS = 16L

        /** 最小触发的 fling 速度 (px/s) */
        const val MIN_FLING_VELOCITY = 200f
    }
}

/**
 * 二维位移偏移（纯 Kotlin 跨平台）。
 */
data class Offset2D(
    val dx: Float,
    val dy: Float
)
