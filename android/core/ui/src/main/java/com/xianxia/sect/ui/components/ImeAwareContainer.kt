package com.xianxia.sect.ui.components

import android.app.Activity
import android.view.View
import android.view.Window
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/** 键盘避让动画时长（毫秒）：跟随键盘显隐动画节奏的一次性状态切换，非每帧 insets 响应 */
private const val IME_AVOID_ANIMATION_MS = 200

/**
 * 平台 Dialog 窗口输入场景的键盘**事件驱动**避让容器
 * （依据 docs/ime-android-system-research.md M5）。
 *
 * 背景：Compose 平台 Dialog 窗口的 ime insets 历史不可靠（Google IssueTracker
 * #229378542，Compose 1.x 缺陷）；本容器以**本窗口键盘可见性翻转**为事件驱动——
 * 键盘可见 → 内容一次性上移（[animateDpAsState] 动画，非每帧 insets 响应、
 * 无 debounce），键盘收起 → 恢复。
 *
 * 按窗口隔离：位移量只取**本窗口**真值
 * （本窗口 Compose ime insets / [ImeVisibilityTracker.imeBottomFor] 本窗口跟踪值），
 * 杜绝"任一窗口键盘可见 → 所有窗口内容位移"的跨窗口污染；
 * API < 30 经典语义下窗口由系统 resize/PAN 兜底避让，本容器恒零位移（防双重避让）。
 *
 * 用法：平台 Dialog 容器（StandardPromptDialog/UnifiedGameDialog/SmallScreenDialog）
 * 在内容区挂载本容器；文本输入一律 [TextInputDialog]（独立平台窗口，本容器随
 * StandardPromptDialog 自动生效）；[InlineStandardPromptDialog]（Activity 覆盖层）
 * 保留用于无文本输入场景（键盘永不弹出，零位移）。
 *
 * @param windowOverride 测试/特定场景可注入窗口（默认经 LocalView 父链解析）
 */
@Composable
fun ImeAwareContainer(
    modifier: Modifier = Modifier,
    windowOverride: Window? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    // 当前窗口 Compose ime insets 底部高度（px；Dialog 窗口显式进入 insets 管线后
    // 可靠，历史缺陷场景为 0，兜底走本窗口跟踪器最近平台报告）
    val windowImeBottomPx: Int = WindowInsets.ime.getBottom(density)
    // 窗口解析：Dialog 窗口 → 继承父链 DialogWindowProvider；Activity 覆盖层 → LocalActivity
    val localView = LocalView.current
    val localActivity = LocalActivity.current
    val window = windowOverride ?: remember(localView) {
        generateSequence(localView) { it.parent as? View }
            .filterIsInstance<DialogWindowProvider>()
            .firstOrNull()
            ?.window
            ?: (localActivity as? Activity)?.window
    }
    // 事件源：本窗口键盘可见性翻转（本窗口 Compose insets 或本窗口跟踪器真值）。
    // ① 读取全局聚合值仅作**重组订阅信号**（翻转驱动容器重新计算，不参与位移判定）；
    // ② 位移判定只用**本窗口**真值——杜绝"任一窗口键盘可见 → 所有窗口内容位移"的跨窗口污染。
    val anyWindowImeVisibleForSubscription = ImeVisibilityTracker.isImeVisible
    val imeVisibleForWindow = windowImeBottomPx > 0 ||
        ImeVisibilityTracker.isImeVisibleFor(window) ||
        (window == null && anyWindowImeVisibleForSubscription)
    // 位移量（px）：本窗口真值兜底（未跟踪窗口回退全局聚合）；仅本窗口可见时生效
    val targetOffsetPx = ImeAvoidancePolicy.computeOffsetPx(
        windowImeBottomPx = windowImeBottomPx,
        trackedImeBottomPx = ImeVisibilityTracker.imeBottomFor(window),
        imeVisibleForWindow = imeVisibleForWindow
    )
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
