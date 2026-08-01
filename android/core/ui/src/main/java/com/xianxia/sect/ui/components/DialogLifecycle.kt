package com.xianxia.sect.ui.components

import androidx.lifecycle.Lifecycle

/**
 * 生命周期是否允许渲染 Dialog（≥ [Lifecycle.State.STARTED]）。
 *
 * 引擎事件弹窗在 Activity 销毁窗口期（token 失效但组合仍挂载）渲染
 * Dialog 会抛 BadTokenException（Bugly #3098），渲染处统一用此判定门控。
 * 纯函数便于单测。
 */
fun Lifecycle.State.canRenderDialogs(): Boolean = isAtLeast(Lifecycle.State.STARTED)
