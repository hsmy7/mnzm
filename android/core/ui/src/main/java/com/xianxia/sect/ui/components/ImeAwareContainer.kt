package com.xianxia.sect.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.max

/** 键盘避让动画时长（毫秒）：跟随键盘显隐动画节奏的一次性状态切换，非每帧 insets 响应 */
private const val IME_AVOID_ANIMATION_MS = 200

/**
 * 键盘可见时内容上移系数：居中对话框底部输入框露出并留白。
 * 真机矩阵验证后按需调整（对话框高度 / 键盘高度比例相关）。
 */
private const val IME_AVOID_OFFSET_FACTOR = 0.6f

/**
 * 平台 Dialog 窗口输入场景的键盘**事件驱动**避让容器（2026-09 IME 状态机根治，
 * 依据 docs/ime-android-system-research.md M5）。
 *
 * 背景：Compose 平台 Dialog 窗口的 ime insets 历史不可靠（Google IssueTracker
 * #229378542，Compose 1.x 缺陷）；本容器以**键盘可见性翻转**为事件驱动——
 * 键盘可见 → 内容一次性上移（[animateDpAsState] 动画，非每帧 insets 响应、
 * 无 debounce），键盘收起 → 恢复。位移量优先取当前窗口 Compose ime insets，
 * 兜底取 [ImeVisibilityTracker.lastImeBottomPx]（全局最近一次平台报告，M10
 * 零陈旧值重放语义）。
 *
 * 用法：平台 Dialog 容器（StandardPromptDialog/UnifiedGameDialog/SmallScreenDialog）
 * 在内容区挂载本容器；Activity 层输入走官方标准 `imePadding`（[InlineStandardPromptDialog]）。
 */
@Composable
fun ImeAwareContainer(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    // 当前窗口 Compose ime insets 底部高度（px；Dialog 窗口显式进入 insets 管线后
    // 可靠，历史缺陷场景为 0，fallback 走全局最近平台报告）
    val windowImeBottomPx: Int = WindowInsets.ime.getBottom(density)
    // 事件源：键盘可见性翻转（本窗口 insets 或全局任一窗口可见）
    val imeVisible = windowImeBottomPx > 0 || ImeVisibilityTracker.isImeVisible
    // 位移量（px）：本窗口优先，全局兜底；仅可见时生效（收起恒 0）
    val targetOffsetPx = if (imeVisible) {
        (max(windowImeBottomPx, ImeVisibilityTracker.lastImeBottomPx) * IME_AVOID_OFFSET_FACTOR).toInt()
    } else {
        0
    }
    val offsetY by animateDpAsState(
        targetValue = with(density) { targetOffsetPx.toDp() },
        animationSpec = tween(durationMillis = IME_AVOID_ANIMATION_MS),
        label = "imeAwareOffset"
    )
    Box(
        modifier = modifier.offset(y = -offsetY),
        contentAlignment = Alignment.Center,
        content = content
    )
}
