package com.xianxia.sect.ui.components

import android.util.Log
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay

private const val TAG = "ImeGuard"

/** 键盘未弹出的重试次数上限（防失控，2 次后放弃交由用户手动点击） */
private const val AUTO_FOCUS_MAX_RETRIES = 2

/** 每次聚焦请求后等待键盘弹出的确认超时（毫秒） */
private const val AUTO_FOCUS_RETRY_INTERVAL_MS = 800L

/**
 * 创建带"IME 弹出确认 + 有限重试"的自动聚焦 [FocusRequester]。
 *
 * 背景（2026-08 荣耀 X70 键盘频闪根治）：
 * 荣耀智慧输入法（百度定制版）存在键盘首次弹出失败/自动收起的稳定性缺陷；
 * 旧实现仅单次 requestFocus，键盘被系统收起后无恢复机制，与系统自动重弹
 * 叠加即表现为"键盘反复弹出收起"。本组件在聚焦后监听 IME insets：
 * 超时未弹出则重新聚焦（上限 [AUTO_FOCUS_MAX_RETRIES] 次），成功后停止，
 * 重试耗尽仍失败则记录日志并放弃（交由用户手动点击，避免无限重试回路）。
 *
 * 用法：调用方以 `val focusRequester = rememberImeAwareAutoFocusRequester()`
 * 替换 `remember { FocusRequester() }`，其余与普通 FocusRequester 一致。
 *
 * @return 已接入聚焦确认重试的 FocusRequester
 */
@Composable
fun rememberImeAwareAutoFocusRequester(): FocusRequester {
    val focusRequester = remember { FocusRequester() }
    val view = LocalView.current
    val density = LocalDensity.current
    // IME insets 底部高度 > 0 即键盘可见；insets 变化驱动 recomposition，
    // rememberUpdatedState 保证 LaunchedEffect 内读到最新值而不重启协程
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val latestImeVisible by rememberUpdatedState(imeVisible)

    LaunchedEffect(Unit) {
        // 等待布局完成后再请求焦点，避免与覆盖层首帧布局竞态
        view.post { focusRequester.requestFocus() }
        var attempt = 0
        while (attempt < AUTO_FOCUS_MAX_RETRIES) {
            delay(AUTO_FOCUS_RETRY_INTERVAL_MS)
            if (latestImeVisible) return@LaunchedEffect
            Log.d(
                TAG,
                "IME 未在 ${AUTO_FOCUS_RETRY_INTERVAL_MS}ms 内弹出，重试聚焦 " +
                    "${attempt + 1}/$AUTO_FOCUS_MAX_RETRIES"
            )
            view.post { focusRequester.requestFocus() }
            attempt++
        }
        if (!latestImeVisible) {
            Log.w(TAG, "自动聚焦重试耗尽：IME 仍未弹出（可能为 OEM 输入法缺陷或系统拦截）")
        }
    }
    return focusRequester
}
