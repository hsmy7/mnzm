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

/** 键盘未弹出的重试请求上限（防失控，2 次后放弃交由用户手动点击） */
private const val AUTO_FOCUS_MAX_RETRIES = 2

/** 每次聚焦请求后等待键盘弹出的确认超时（毫秒） */
private const val AUTO_FOCUS_RETRY_INTERVAL_MS = 800L

/**
 * 全局循环上限（重试次数 × 动画暂停容忍系数）：
 * 键盘动画进行中不烧重试次数，但循环本身必须有界——动画信号长期卡住（ROM 缺陷）
 * 时最多 [AUTO_FOCUS_MAX_ITERATIONS] 拍后放弃，绝不无限重试。
 */
private const val AUTO_FOCUS_MAX_ITERATIONS = AUTO_FOCUS_MAX_RETRIES * 3

/**
 * 自动聚焦重试决策输入（data class 不计入 detekt LongParameterList）。
 */
internal data class AutoFocusRetryInputs(
    val reRequests: Int,
    val maxRetries: Int,
    val iterations: Int,
    val maxIterations: Int,
    val imeVisible: Boolean,
    val animating: Boolean,
    val hasTextFocus: Boolean
)

/**
 * 自动聚焦下一步决策（纯函数，Robolectric 单测直接驱动）。
 *
 * 规则（docs/ime-android-system-research.md M10）：
 * - 键盘可见 → 已确认（终止）；
 * - 键盘动画进行中 → 等待动画结束（**不烧重试次数**——动画中 requestFocus 会被
 *   系统以 PHASE_CLIENT_ANIMATION_CANCEL 取消，反复取消 = 反复重弹）；
 * - 文本输入焦点已持有 → 放弃重试（重复 requestFocus 无意义，且可能触发 ROM
 *   智能输入法反复重弹键盘）；
 * - 重试上限或循环上限耗尽 → 放弃（交由用户手动点击，绝不无限重试）。
 */
internal fun autoFocusNextAction(inputs: AutoFocusRetryInputs): AutoFocusNextAction = when {
    inputs.imeVisible -> AutoFocusNextAction.IME_CONFIRMED
    inputs.animating -> AutoFocusNextAction.WAIT_ANIMATION
    inputs.hasTextFocus -> AutoFocusNextAction.FOCUS_ALREADY_SET
    inputs.reRequests >= inputs.maxRetries || inputs.iterations >= inputs.maxIterations ->
        AutoFocusNextAction.EXHAUSTED
    else -> AutoFocusNextAction.REQUEST_FOCUS
}

/** 自动聚焦重试决策结果 */
internal enum class AutoFocusNextAction { IME_CONFIRMED, WAIT_ANIMATION, FOCUS_ALREADY_SET, EXHAUSTED, REQUEST_FOCUS }

/**
 * 当前视图树中是否已有文本输入焦点。
 *
 * 聚焦重试守卫：HyperOS 2 / MagicOS 8/9 等 ROM 上
 * IME insets 检测信号可能不稳定（键盘已弹出但 `imeVisible` 未翻转），此时重复
 * requestFocus 无意义且会触发 ROM 智能输入法反复重弹键盘——已有文本输入焦点即
 * 说明焦点请求已生效，放弃重试交由输入法/系统自行落定。
 *
 * Compose 场景：Compose 文本字段（OutlinedTextField 等）
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
     * 背景：荣耀智慧输入法（百度定制版）等存在键盘首次弹出失败/自动收起的
     * 稳定性缺陷，单次 requestFocus 后键盘被系统收起即无恢复机制。本组件在
     * 聚焦后监听 IME insets：超时未弹出则重新聚焦（上限 [AUTO_FOCUS_MAX_RETRIES]
     * 次），成功后停止；键盘动画进行中等待动画结束再重试（防
     * PHASE_CLIENT_ANIMATION_CANCEL 取消，且动画中放弃会导致键盘补齐无恢复路径）；
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
        // 等待布局完成后再请求焦点，避免与覆盖层首帧布局竞态；
        // 窗口焦点未就绪（平台 Dialog 窗口创建初期）时不发出无效请求
        requestFocusWhenWindowFocused(view, focusRequester)
        var reRequests = 0
        var iterations = 0
        while (iterations < AUTO_FOCUS_MAX_ITERATIONS) {
            iterations++
            delay(AUTO_FOCUS_RETRY_INTERVAL_MS)
            when (
                autoFocusNextAction(
                    AutoFocusRetryInputs(
                        reRequests = reRequests,
                        maxRetries = AUTO_FOCUS_MAX_RETRIES,
                        iterations = iterations,
                        maxIterations = AUTO_FOCUS_MAX_ITERATIONS,
                        imeVisible = latestImeVisible,
                        animating = ImeAnimationTracker.isAnimating,
                        hasTextFocus = hasTextInputFocus(view)
                    )
                )
            ) {
                AutoFocusNextAction.IME_CONFIRMED -> return@LaunchedEffect
                AutoFocusNextAction.WAIT_ANIMATION ->
                    Log.d(TAG, "IME 动画进行中，等待动画结束后再确认（第 $iterations 拍）")
                AutoFocusNextAction.FOCUS_ALREADY_SET -> {
                    Log.d(TAG, "IME 未在 ${AUTO_FOCUS_RETRY_INTERVAL_MS}ms 内确认，但输入框已有焦点，跳过重复聚焦")
                    return@LaunchedEffect
                }
                AutoFocusNextAction.EXHAUSTED -> {
                    Log.w(TAG, "自动聚焦重试耗尽：IME 仍未弹出（可能为 OEM 输入法缺陷或系统拦截）")
                    return@LaunchedEffect
                }
                AutoFocusNextAction.REQUEST_FOCUS -> {
                    Log.d(
                        TAG,
                        "IME 未在 ${AUTO_FOCUS_RETRY_INTERVAL_MS}ms 内弹出，重试聚焦 " +
                            "${reRequests + 1}/$AUTO_FOCUS_MAX_RETRIES"
                    )
                    requestFocusWhenWindowFocused(view, focusRequester)
                    reRequests++
                }
            }
        }
        Log.w(TAG, "自动聚焦重试耗尽（循环上限）：IME 仍未弹出")
    }
    return focusRequester
}

/** 窗口焦点就绪后 requestFocus（post 到下一帧；无窗口焦点时静默跳过，交由重试拍补齐） */
private fun requestFocusWhenWindowFocused(view: View, focusRequester: FocusRequester) {
    view.post {
        if (view.hasWindowFocus()) {
            focusRequester.requestFocus()
        } else {
            Log.d(TAG, "窗口焦点未就绪，本次聚焦请求跳过（等待重试拍）")
        }
    }
}
