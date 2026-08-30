package com.xianxia.sect.ui.components

/**
 * IME 交互统一判定状态机（2026-09 IME 状态机根治，
 * 依据 docs/ime-android-system-research.md M3/M6/M9/M10）。
 *
 * 聚合键盘可见性（[ImeVisibilityTracker]，isVisible 真值）、键盘动画状态
 * （[ImeAnimationTracker]）、输入对话框冻结（[SystemBarFreezeScope]）为
 * **单一真相源**，供 [SystemBarHidePolicy] 与系统栏恢复链路查询——
 * 消除"多窗口各持一份 insets/焦点状态"的状态分叉（Flutter #191156 同类根因）。
 *
 * 规则：
 * - 键盘可见 或 动画进行中 或 输入对话框冻结 → 系统栏必须冻结（零 hide/show 切换，
 *   防 hide×IME 同控制器对抗与动画期闪屏，M9）
 * - 键盘不可见且无键盘动画 → 系统栏恢复放行（恢复链路：动画 onEnd 回调 + isVisible
 *   复查 + 延时兜底，M10 陈旧 insets 防御）
 */
object ImeStateMachine {

    /** 系统栏当前是否应冻结（输入对话框冻结 / 键盘可见 / 键盘动画中，任一成立即冻结） */
    fun isSystemBarFrozen(): Boolean =
        SystemBarFreezeScope.isFrozen ||
            ImeVisibilityTracker.isImeVisible ||
            ImeAnimationTracker.isAnimating

    /** 系统栏恢复是否放行（键盘不可见且无键盘动画） */
    fun canRestoreSystemBars(): Boolean =
        !ImeVisibilityTracker.isImeVisible && !ImeAnimationTracker.isAnimating

    /** 冻结原因（日志用，实机验证回路切断） */
    fun freezeReason(): String = buildList {
        if (SystemBarFreezeScope.isFrozen) add("frozen=true")
        if (ImeVisibilityTracker.isImeVisible) add("imeVisible=true")
        if (ImeAnimationTracker.isAnimating) add("imeAnimating=true")
    }.joinToString(", ").ifEmpty { "无" }
}
