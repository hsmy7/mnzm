package com.xianxia.sect.core.perf

/**
 * 帧指标采集会话端口（平台能力接口化，计划 v2 批 8-1 / C-06 退役批次重构）。
 *
 * Android 实现见 app 层 `WindowFrameMetricsSession`
 * （Window.OnFrameMetricsAvailableListener + FrameMetrics）。
 * 引擎侧保持零 Android 依赖（R-02）：卡顿判定/统计聚合全部留在 [FrameMetricsMonitor]。
 */
interface FrameMetricsSession {
    /**
     * 开始采集。
     * @param onSample 帧采样回调（主线程）；不支持的指标以 -1 表示
     */
    fun start(onSample: (totalDurationNs: Long, drawDurationNs: Long, layoutDurationNs: Long) -> Unit)

    fun stop()
}
