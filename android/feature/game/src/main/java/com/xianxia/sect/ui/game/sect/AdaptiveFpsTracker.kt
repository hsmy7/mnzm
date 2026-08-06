package com.xianxia.sect.ui.game.sect

/**
 * 自适应帧率追踪器 — 渲染线程 EWMA 帧时间平滑 + 动态降帧建议。
 *
 * 自 SoftwareCanvasBackend.recordFrameTime 提取的纯逻辑，VULKAN/SOFTWARE
 * 双路径统一挂载（原仅 SOFTWARE 路径生效，两路径帧率策略不对称）。
 *
 * 注意：**仅做降级（绝不提升）**，升帧由外部场景/热控/性能模式 Flow 控制。
 *
 * @param alpha EWMA 平滑系数
 * @param hysteresisMs 降帧防抖窗口（ms）
 */
class AdaptiveFpsTracker(
    private val alpha: Float = EWMA_ALPHA,
    private val hysteresisMs: Long = FPS_HYSTERESIS_MS
) {
    companion object {
        private const val EWMA_ALPHA = 0.3f
        private const val FPS_HYSTERESIS_MS = 1_000L
        /** 首帧实际耗时上限（45fps 档）— 防 JIT 预热/Bitmap 分配等首帧异常耗时拖低帧率 */
        private const val FIRST_FRAME_CAP_NS = 22_000_000L
        private const val FPS_60_THRESHOLD_NS = 22_000_000L
        private const val FPS_45_THRESHOLD_NS = 33_000_000L
        private const val FPS_30_THRESHOLD_NS = 50_000_000L
        /** 自适应降帧下限（帧耗时 >50ms 不再继续降） */
        const val MIN_FPS = 20
    }

    private var ewmaFrameTimeNs = 0L
    private var currentCalculatedFps = 60
    private var lastFpsSwitchMs = 0L

    /**
     * 记录帧渲染时间，返回建议目标帧率（EWMA 平滑 + 1 秒防抖）。
     *
     * @param actualNs 实际帧耗时（ns）
     * @param nowMs 当前时间戳（ms）
     * @return 建议帧率（60/45/30/20）
     */
    fun recordFrameTime(actualNs: Long, nowMs: Long): Int {
        var result: Int
        if (ewmaFrameTimeNs == 0L) {
            // 首帧使用实际耗时但上限 22ms（45fps 档），防止 JIT 预热等首帧异常
            ewmaFrameTimeNs = actualNs.coerceAtMost(FIRST_FRAME_CAP_NS)
            result = 60
        } else {
            ewmaFrameTimeNs = (alpha * actualNs + (1 - alpha) * ewmaFrameTimeNs).toLong()
            result = if (nowMs - lastFpsSwitchMs < hysteresisMs) {
                currentCalculatedFps
            } else {
                val fps = when {
                    ewmaFrameTimeNs <= FPS_60_THRESHOLD_NS -> 60
                    ewmaFrameTimeNs <= FPS_45_THRESHOLD_NS -> 45
                    ewmaFrameTimeNs <= FPS_30_THRESHOLD_NS -> 30
                    else -> MIN_FPS
                }
                if (fps != currentCalculatedFps) {
                    currentCalculatedFps = fps
                    lastFpsSwitchMs = nowMs
                }
                fps
            }
        }
        return result
    }
}
