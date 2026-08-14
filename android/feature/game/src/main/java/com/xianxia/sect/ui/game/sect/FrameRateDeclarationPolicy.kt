package com.xianxia.sect.ui.game.sect

/**
 * 帧率↔刷新率联动声明策略 — `Surface.setFrameRate` 决策纯函数（2026-08-14 平板省电）。
 *
 * ## 背景
 * 旧逻辑仅 effectiveFps ≤ 30 时声明降频，60fps 场景下 120Hz 平板面板保持 120Hz
 * 刷新而内容只有 60fps → 面板功耗约 2 倍（屏幕功耗占整机 40-50%，60Hz vs 120Hz
 * 差约 50% 屏耗）。行业依据：Android 官方 FPS 节流文档明确 120Hz 屏支持
 * 120/60/40/30 档位（games/optimize/adpf/gamemode/fps-throttling）。
 *
 * ## 决策规则
 * - displayFps ≤ 60：旧行为逐位一致（≤30 声明有效帧率 + 回升恢复声明防 OEM
 *   面板粘滞——华为/小米 ROM 上"让系统自然恢复"不成立）
 * - displayFps > 60（120/144Hz 高刷面板）：声明 {60, 30} 两档——会话首帧声明
 *   60（120→60 切换恰逢地图淡入遮罩掩盖黑屏，省屏耗 50% 的核心动作）；
 *   effectiveFps ≤ 30 声明 30（面板再降一档）；10fps 不声明 10（部分面板不支持
 *   低档，帧节拍由 [FrameDropPolicy.tickStep] 自行跳帧）
 * - 升档（30→60）防抖 2s：由宿主状态机执行（频繁切刷新率在部分 OEM 上
 *   触发 ~1s 黑屏；防抖只发生在每次闲置周期回收时一次，用户操作恢复瞬间可接受）
 *
 * ## iOS 对等
 * `CADisplayLink.preferredFrameRateRange`（ProMotion 屏按内容帧率降刷新率，
 * 无黑屏切换问题，直接声明目标帧率即可）。
 */
object FrameRateDeclarationPolicy {

    /** 常规面板刷新率上限（Hz）；高于此值视为高刷面板 */
    const val DISPLAY_FPS_NORMAL_MAX = 60

    /** 深闲置声明档（fps） */
    const val FPS_DECLARE_LOW = 30

    /**
     * 计算应声明的帧率。
     *
     * @param displayFps 面板刷新率（≤0 = provider 异常，不声明）
     * @param effectiveFps 有效渲染帧率（≥1）
     * @param lastDeclaredFps 已声明值（0 = 会话内从未声明）
     * @return 应声明的帧率；null = 本帧不声明（无变化/面板不支持声明）
     */
    fun targetDeclareFps(displayFps: Int, effectiveFps: Int, lastDeclaredFps: Int): Int? {
        return when {
            displayFps <= 0 -> null
            // 60Hz 面板：旧行为逐位一致——降频 ≤30 声明；回升恢复声明防面板粘滞
            displayFps <= DISPLAY_FPS_NORMAL_MAX -> normalPanelTarget(effectiveFps, lastDeclaredFps)
            // 高刷面板：{60, 30} 两档离散声明
            else -> highRefreshPanelTarget(effectiveFps, lastDeclaredFps)
        }
    }

    /** 60Hz 面板声明目标（旧行为逐位一致） */
    private fun normalPanelTarget(effectiveFps: Int, lastDeclaredFps: Int): Int? {
        val shouldDeclare = effectiveFps != lastDeclaredFps &&
            (effectiveFps <= FPS_DECLARE_LOW || lastDeclaredFps > 0)
        return if (shouldDeclare) effectiveFps else null
    }

    /** 高刷面板声明目标（{60, 30} 两档离散） */
    private fun highRefreshPanelTarget(effectiveFps: Int, lastDeclaredFps: Int): Int? {
        val target = if (effectiveFps <= FPS_DECLARE_LOW) FPS_DECLARE_LOW else DISPLAY_FPS_NORMAL_MAX
        return if (target != lastDeclaredFps) target else null
    }

    /**
     * 是否使用 FIXED_SOURCE 兼容模式。
     * DEFAULT 在 120Hz 面板上会帧倍频保持 120Hz（不省屏耗），
     * 高刷面板必须 FIXED_SOURCE；60Hz 面板维持 DEFAULT（旧行为）。
     */
    fun useFixedSource(displayFps: Int): Boolean = displayFps > DISPLAY_FPS_NORMAL_MAX
}
