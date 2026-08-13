package com.xianxia.sect.core.animation

/**
 * 地图淡入过渡 — 纯函数时间驱动（渲染线程每帧独立计算，无累积误差）。
 *
 * ## 设计
 * - 每帧由 [alphaAt] 从单调时钟 elapsed 计算 alpha，不维护动画状态——
 *   热控降帧（10fps 挂机档）下淡入时长按墙钟精确 300ms，不受帧率影响
 * - EaseOutCubic 缓动（[EasingConstants.EASE_OUT_CUBIC]，2026-08-13 收敛统一曲线来源）：
 *   快速启动、柔和结束
 * - 双端消费同一数学来源：Vulkan（C++ g_fadeAlpha × quad alpha）与
 *   Canvas（SoftwareCanvasBackend 合成 paint.alpha）行为一致
 * - 保持纯函数架构（渲染线程无协程/无状态，天然帧率无关），不引入
 *   [EngineTween] 状态驱动——淡入数学已内嵌于本纯函数
 *
 * ## 触发
 * [com.xianxia.sect.ui.game.sect.NativeSurfaceView.fadeIn]——渲染线程每次启动
 * （surface 初始化，覆盖首次进入/重入/降级路径）自动触发，幂等。
 */
object FadeTransition {

    /** 默认淡入时长（毫秒）：300ms 业界标准过渡时长（Material Motion 200-300ms 区间） */
    const val DEFAULT_DURATION_MS = 300L

    /**
     * 计算 t 时刻的淡入 alpha（0-1，clamp 防御）。
     *
     * @param elapsedNs 距淡入开始已流逝的纳秒（单调时钟差值，可为负——时钟回绕防御）
     * @param durationNs 淡入总时长纳秒（≤0 视为已完成，恒返回 1）
     * @return alpha 0-1：t=0 → 0（全透明）、t≥duration → 1（完全不透明）
     */
    fun alphaAt(elapsedNs: Long, durationNs: Long): Float {
        if (durationNs <= 0L) return 1f
        val t = (elapsedNs.toFloat() / durationNs.toFloat()).coerceIn(0f, 1f)
        return EasingConstants.EASE_OUT_CUBIC(t)
    }
}
