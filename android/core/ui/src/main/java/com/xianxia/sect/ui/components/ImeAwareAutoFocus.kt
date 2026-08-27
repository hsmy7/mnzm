package com.xianxia.sect.ui.components

import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.TextView
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
 * 当前视图树中是否已有文本输入焦点。
 *
 * 聚焦重试守卫（2026-08 第四根因键盘频闪根治）：HyperOS 2 / MagicOS 8/9 等 ROM 上
 * IME insets 检测信号可能不稳定（键盘已弹出但 `imeVisible` 未翻转），此时重复
 * requestFocus 无意义且会触发 ROM 智能输入法反复重弹键盘——已有文本输入焦点即
 * 说明焦点请求已生效，放弃重试交由输入法/系统自行落定。
 *
 * Compose 场景补充（2026-08 第五根因修复）：Compose 文本字段（OutlinedTextField 等）
 * 不使用 [EditText]，焦点在 Compose FocusManager 中管理——Android 层 `findFocus()`
 * 返回 ComposeView（AndroidComposeView，继承 ViewGroup 而非 TextView），原判定恒
 * false 导致重试守卫失效。Compose 内部聚焦时（IME 连接需要）ComposeView 自身持有
 * Android 焦点，据此补充 `focused === view` 判定。副作用分析：Compose 非文本节点
 * （Button）聚焦时若 ComposeView 自持焦点会误判命中——但该场景本无 IME 需求，
 * 跳过重试无任何副作用（原逻辑重试 requestFocus 同样无 IME 效果）。
 *
 * internal 供 Robolectric 单测直接驱动判定逻辑。
 */
internal fun hasTextInputFocus(view: View): Boolean {
    val focused = view.findFocus() ?: return false
    return focused is EditText ||
        (focused is TextView && focused.inputType != 0) ||
        (view.hasFocus() && focused === view)
}

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
            // 输入框已有焦点但 IME 未确认：重复 requestFocus 无意义且可能触发
            // ROM 智能输入法反复重弹键盘（检测信号不稳定场景），放弃重试
            if (hasTextInputFocus(view)) {
                Log.d(TAG, "IME 未在 ${AUTO_FOCUS_RETRY_INTERVAL_MS}ms 内确认，但输入框已有焦点，跳过重复聚焦")
                return@LaunchedEffect
            }
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
