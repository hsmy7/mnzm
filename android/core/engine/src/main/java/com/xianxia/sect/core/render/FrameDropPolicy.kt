package com.xianxia.sect.core.render

import kotlin.math.ceil

/**
 * 帧跳过策略 — 纯函数：显示刷新率与有效帧率之间的节拍换算。
 *
 * ## 设计
 * - RenderThread 以显示刷新率（默认 60Hz）为节拍，每 [tickStep] 个节拍渲染一帧：
 *   effectiveFps=30 @ 60Hz → step=2（每 2 节拍 1 帧 = 30fps）；10fps → step=6
 * - 节拍对齐比整体 sleep(1/effectiveFps) 更均匀：渲染帧落在固定节拍点，
 *   热控升降帧即时生效（每次循环重算 step，无状态累积）
 * - ceil 取整保证渲染帧率 ≤ 目标帧率（不超速渲染浪费电量）
 *
 * ## 跨平台
 * - Vulkan 路径：FIFO 交换链天然 vsync 节拍；Canvas 路径：VsyncGate
 *   （Choreographer）提供节拍；两者共用同一 [tickStep] 数学（iOS 对等：
 *   CADisplayLink 以显示刷新率回调，同换算）
 */
object FrameDropPolicy {

    /**
     * 计算每渲染 1 帧需要跳过的节拍数。
     *
     * @param displayFps 显示刷新率（Hz，≤0 按 1 处理防御除零）
     * @param effectiveFps 有效帧率（Hz，≤0 返回 1——不跳帧，最坏=不超速）
     * @return ≥1：tick % step == 0 时渲染
     */
    fun tickStep(displayFps: Int, effectiveFps: Int): Int {
        if (effectiveFps <= 0) return 1
        val d = displayFps.coerceAtLeast(1)
        val step = ceil(d.toFloat() / effectiveFps).toInt()
        return step.coerceAtLeast(1)
    }
}
