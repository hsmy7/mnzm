package com.xianxia.sect.core.util

import com.xianxia.sect.ui.components.SystemBarFreezeScope

/**
 * 系统栏隐藏策略（2026-08 荣耀 X70 键盘频闪根治）。
 *
 * 统一判定 Activity 的 hideSystemBars() 是否应跳过，双守卫：
 * 1. 输入对话框挂载期间（[SystemBarFreezeScope.isFrozen]）——输入场景零窗口操作；
 * 2. 键盘可见期间（[ImeVisibilityTracker.isImeVisible]）——Android 15 强制
 *    edge-to-edge 下 IME 可见期间系统接管导航栏，应用 hide() 与其对抗会引发
 *    insets 翻转，与荣耀 MagicOS 键盘弹出/收起期间的窗口焦点抖动叠加，
 *    形成"键盘弹出→收起→再弹出"振荡回路。
 */
object SystemBarHidePolicy {

    /** 是否应跳过本次 hideSystemBars() 调用 */
    fun shouldSkipHide(): Boolean =
        SystemBarFreezeScope.isFrozen || ImeVisibilityTracker.isImeVisible

    /** 跳过原因（日志用，便于实机验证回路切断） */
    fun skipReason(): String = when {
        SystemBarFreezeScope.isFrozen && ImeVisibilityTracker.isImeVisible ->
            "frozen=true, imeVisible=true"
        SystemBarFreezeScope.isFrozen -> "frozen=true, imeVisible=false"
        else -> "frozen=false, imeVisible=true"
    }
}
