package com.xianxia.sect.ui.components

import android.os.Build

/**
 * 键盘避让位移纯函数策略。
 *
 * 背景：旧 [ImeAwareContainer] 以"全局 IME 可见性 + 全局最近高度"计算位移——
 * 任一窗口键盘可见即所有窗口内容上移（跨窗口污染："界面反复跳动/下拉"）；
 * 且 API < 30 经典语义下窗口已被系统真实 resize（或 PAN 兜底平移），
 * 再叠加应用层位移 = 双重避让（历史"反复下拉"的标准配方）。
 *
 * 规则（全部为确定性决策，无延时/无启发式）：
 * - API < 30：窗口由系统经典 resize/PAN 兜底完成避让，应用层位移恒 0；
 * - API 30+：仅当**本窗口**键盘可见时，取 max(本窗口 Compose insets,
 *   本窗口跟踪器最近高度) × [IME_AVOID_OFFSET_FACTOR] 一次性上移
 *   （事件驱动翻转，非每帧 insets 响应）。
 */
object ImeAvoidancePolicy {

    /** 键盘可见时内容上移系数：居中对话框底部输入框露出并留白 */
    const val IME_AVOID_OFFSET_FACTOR = 0.6f

    /**
     * 计算本窗口键盘避让位移（px）。
     *
     * @param windowImeBottomPx 本窗口 Compose ime insets 底部高度（px）
     * @param trackedImeBottomPx 本窗口跟踪器最近平台报告高度（px，[ImeVisibilityTracker.imeBottomFor]）
     * @param imeVisibleForWindow 本窗口键盘是否可见（[ImeVisibilityTracker.isImeVisibleFor] 或 Compose 信号）
     * @param sdkInt 系统 API 级别（测试可注入）
     */
    fun computeOffsetPx(
        windowImeBottomPx: Int,
        trackedImeBottomPx: Int,
        imeVisibleForWindow: Boolean,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Int {
        // API<30 经典语义：系统 resize/PAN 兜底自身完成避让，应用层位移恒 0
        //（叠加位移 = 双重避让，历史上"界面反复下拉"的机制配方）；
        // API30+ 仅本窗口可见时用本窗口真值计算（防跨窗口污染）
        val eligible = sdkInt >= Build.VERSION_CODES.R && imeVisibleForWindow
        if (!eligible) return 0
        val bottom = maxOf(windowImeBottomPx, trackedImeBottomPx)
        return (bottom * IME_AVOID_OFFSET_FACTOR).toInt()
    }
}
