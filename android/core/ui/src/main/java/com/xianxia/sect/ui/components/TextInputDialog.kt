package com.xianxia.sect.ui.components

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider

/** 全局文本输入会话键（同刻仅一个文本输入会话；单例语义由游戏弹窗互斥保证） */
internal const val TEXT_INPUT_SESSION_KEY = "text_input_dialog"

/**
 * 统一文本输入对话框。
 *
 * 架构定位：**文本输入只存在于独立平台 Dialog 窗口**——键盘的全部交互
 * （insets 派发/经典 resize/焦点/系统栏对抗）由本窗口吸收，游戏主窗口
 * （含游戏渲染 Surface 的 Activity）对 IME 完全透明（manifest adjustNothing），
 * 杜绝"键盘出现 → 游戏窗口 resize → Surface swapchain 重建 → 界面闪烁"
 * 与"窗口焦点抖动传导 → 键盘反复弹收"两类振荡回路。
 *
 * 实现 = [StandardPromptDialog]（既有守卫体系：DialogSystemBarFreezeEffect /
 * DialogSoftInputGuard / DialogSystemBarGuard / ImeAwareContainer / DialogFocusGuard）
 * + 输入内容（自动聚焦 OutlinedTextField + 长度/校验行）
 * + 输入会话状态机（[InputSessionStateMachine]：open/close 幂等，防重复创建与
 *   CLOSING→OPENING 跳转）——**状态机与自动聚焦均在 Dialog 窗口组合上下文内执行**
 *   （[TextInputDialogContent]：LocalView/insets 均解析为本输入窗口，与焦点/键盘
 *   IME 目标一致；嵌套场景（如弟子详情窗口内弹出）同样正确）。
 *
 * softInputMode 按 API 决策（避免 API<30 经典 resize + 应用层位移双重避让）：
 * - API 30+：ADJUST_RESIZE（官方兼容模式 = insets 派发，窗口不真实 resize）；
 * - API < 30：ADJUST_PAN（官方 fallback，整窗平移完成避让，ImeAwareContainer 恒零位移）。
 *
 * 用法：所有文本输入场景（宗门/弟子改名、兑换码、创建宗门名）统一使用本组件，
 * 禁止在游戏主窗口内联文本输入。
 */
// 与 StandardPromptDialog/InlineStandardPromptDialog 同款平铺签名（20+ 调用点语义对齐）
@Suppress("LongParameterList")
@Composable
fun TextInputDialog(
    onDismissRequest: () -> Unit,
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String? = null,
    label: String? = null,
    maxLength: Int? = null,
    isError: Boolean = false,
    errorText: String? = null,
    confirmLabel: String = "确定",
    dismissLabel: String? = "取消",
    onConfirm: () -> Unit = onDismissRequest,
    onDismiss: (() -> Unit)? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    singleLine: Boolean = true,
    scrimEnabled: Boolean = true
) {
    StandardPromptDialog(
        onDismissRequest = onDismissRequest,
        title = title,
        confirmLabel = confirmLabel,
        dismissLabel = dismissLabel,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        dismissOnBackPress = true,
        dismissOnClickOutside = false,
        scrimEnabled = scrimEnabled,
        freezeSystemBars = true,
        // per-API：API30+ insets 派发 / API<30 PAN 兜底（防双重避让）
        softInputMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
    ) {
        TextInputDialogContent(
            TextInputContentConfig(
                value = value,
                onValueChange = onValueChange,
                placeholder = placeholder,
                label = label,
                maxLength = maxLength,
                isError = isError,
                errorText = errorText,
                onImeDone = onConfirm,
                keyboardType = keyboardType,
                imeAction = imeAction,
                singleLine = singleLine
            )
        )
    }
}

/**
 * 输入内容：自动聚焦输入框 + 长度/校验行 + 输入会话状态机接线。
 *
 * 渲染于本输入平台 Dialog 窗口组合上下文内——[LocalView]/Compose insets/
 * [ImeVisibilityTracker] 均解析为本输入窗口（嵌套场景亦正确），
 * 自动聚焦与键盘弹出确认以本窗口为真值。
 */
@Composable
private fun TextInputDialogContent(config: TextInputContentConfig) {
    // 自动聚焦 + 键盘弹出确认重试（动画空闲期重试，isVisible 终止）
    val focusRequester = rememberImeAwareAutoFocusRequester()
    // 输入会话状态机接线（挂载 OPENING / 键盘可见翻转 OPENING→OPEN / 销毁 CLOSING→CLOSED）
    val localView = LocalView.current
    val inputWindow = remember(localView) {
        generateSequence(localView) { it.parent as? View }
            .filterIsInstance<DialogWindowProvider>()
            .firstOrNull()
            ?.window
    }
    DisposableEffect(inputWindow) {
        InputSessionStateMachine.open(inputWindow, TEXT_INPUT_SESSION_KEY)
        // 键盘可见翻转 → OPENING→OPEN（本输入窗口真值驱动，防跨窗口误确认）
        val onImeFlip: () -> Unit = {
            if (ImeVisibilityTracker.isImeVisibleFor(inputWindow)) {
                InputSessionStateMachine.markOpen(inputWindow, TEXT_INPUT_SESSION_KEY)
            }
        }
        if (inputWindow != null) {
            ImeVisibilityTracker.attach(inputWindow, onImeFlip)
        }
        onDispose {
            if (inputWindow != null) {
                ImeVisibilityTracker.removeOnFlip(inputWindow, onImeFlip)
            }
            InputSessionStateMachine.close(inputWindow, TEXT_INPUT_SESSION_KEY)
            // 末拍落定：本帧所有 onDispose 清理（含 DialogFocusGuard 清焦点收键盘）完成后再宣告 CLOSED，
            // 保证 CLOSING 是真实存在的相位、CLOSED 后才允许下一次打开
            Handler(Looper.getMainLooper()).post {
                InputSessionStateMachine.finishClose(inputWindow, TEXT_INPUT_SESSION_KEY)
            }
        }
    }

    OutlinedTextField(
        value = config.value,
        onValueChange = { newValue ->
            if (config.maxLength == null || newValue.length <= config.maxLength) {
                config.onValueChange(newValue)
            }
        },
        placeholder = config.placeholder?.let { text ->
            @Composable { Text(text, color = Color(0xFF999999)) }
        },
        label = config.label?.let { text ->
            @Composable { Text(text, color = Color(0xFF999999), fontSize = 12.sp) }
        },
        singleLine = config.singleLine,
        isError = config.isError,
        textStyle = TextStyle(color = Color.Black, fontSize = 14.sp),
        keyboardOptions = KeyboardOptions(
            keyboardType = config.keyboardType,
            imeAction = config.imeAction
        ),
        keyboardActions = KeyboardActions(
            onDone = { config.onImeDone() }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
    )
    // 长度计数行 / 校验错误行（无 maxLength 且无错误时隐藏）
    TextInputContentHint(config)
}

/** 长度/校验提示行（TextInputDialogContent 拆分：函数体 ≤60 行） */
@Composable
private fun TextInputContentHint(config: TextInputContentConfig) {
    if (config.errorText != null || config.maxLength != null) {
        Text(
            text = config.errorText ?: "${config.value.length}/${config.maxLength}",
            fontSize = 11.sp,
            color = if (config.isError) Color(0xFFEF5350) else Color.Black,
            textAlign = TextAlign.End,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        )
    }
}

/** 输入内容配置（data class 不计入 detekt LongParameterList） */
private data class TextInputContentConfig(
    val value: String,
    val onValueChange: (String) -> Unit,
    val placeholder: String?,
    val label: String?,
    val maxLength: Int?,
    val isError: Boolean,
    val errorText: String?,
    val onImeDone: () -> Unit,
    val keyboardType: KeyboardType,
    val imeAction: ImeAction,
    val singleLine: Boolean
)
