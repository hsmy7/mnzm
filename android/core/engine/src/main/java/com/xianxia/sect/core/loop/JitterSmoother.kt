package com.xianxia.sect.core.loop

/**
 * 插值因子时间平滑器（对标 Godot physics_jitter_fix 的帧率抖动平滑）。
 *
 * 一阶滤波（EWMA）：`smoothed += k × (raw - smoothed)`——10Hz 逻辑帧的
 * raw alpha 每 tick 从 ~0.9 跳回 ~0.1，直接消费会产生可见节奏抖动；
 * 平滑后渲染插值权重连续变化。**只平滑渲染插值因子**，不进入任何游戏
 * 状态写入路径（确定性由 CurrentAlphaDeterminismGuardTest 锁定）。
 *
 * 与 Godot 语义差异（设计记录）：Godot physics_jitter_fix 平滑的是 tick
 * 时机本身（游戏时钟随抖动偏离真实时间）；本项目游戏时间由 GameTimeClock
 * 单调墙钟独立累积，与循环帧时间解耦——平滑只作用于渲染契约 alpha，
 * 游戏时间与 RNG 序列完全不受影响。
 */
class JitterSmoother(
    private val smoothingFactor: Float = DEFAULT_SMOOTHING_FACTOR
) {

    init {
        // 构造守卫（对抗性审查 2026-08-13 边界#5）：k>1 输出发散震荡、k<0 反向发散
        require(smoothingFactor in 0f..1f) {
            "smoothingFactor 越界: $smoothingFactor（EWMA 稳定域为 [0, 1]）"
        }
    }

    private var smoothed = 0f
    private var initialized = false

    /**
     * 滤波一次，返回平滑后的 alpha。
     *
     * @param rawAlpha 原始插值因子 0..1（NaN/越界钳制——NaN 穿透 coerceIn 显式拦截）
     * @return 平滑后插值因子 [0, 1]
     */
    fun filter(rawAlpha: Float): Float {
        val raw = if (rawAlpha.isNaN()) 0f else rawAlpha.coerceIn(0f, 1f)
        if (!initialized) {
            initialized = true
            smoothed = raw
        } else {
            smoothed += smoothingFactor * (raw - smoothed)
        }
        return smoothed
    }

    /** 重置状态（循环重启/换线程恢复时调用——防旧线程残留滤波状态） */
    fun reset() {
        smoothed = 0f
        initialized = false
    }

    companion object {
        /** 默认平滑系数（0.5 半权重——与 Godot physics_jitter_fix 默认 0.5 对齐） */
        const val DEFAULT_SMOOTHING_FACTOR = 0.5f
    }
}
