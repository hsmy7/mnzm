package com.xianxia.sect.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.annotation.DrawableRes
import com.xianxia.sect.core.ui.R
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import android.app.Activity
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 在 Composable 挂载期间将目标窗口的 softInputMode 临时切换为 [mode]，
 * 卸载时自动恢复。适用于 [Dialog] 内的平台 Dialog 窗口和 Activity 内的 Box overlay。
 *
 * 解决 Xiaomi HyperOS 上 [adjustResize] 导致的键盘反复弹出收起频闪问题。
 */
@Composable
fun DialogSoftInputGuard(
    mode: Int = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
) {
    // 遍历 View 层级寻找 DialogWindowProvider（平台 Dialog 窗口）
    val dialogWindow = generateSequence(LocalView.current) {
        it.parent as? View
    }
        .filterIsInstance<DialogWindowProvider>()
        .firstOrNull()
        ?.window
    val targetWindow = dialogWindow
        ?: (LocalActivity.current as? Activity)?.window
        ?: return
    val originalMode = remember { targetWindow.attributes.softInputMode }
    DisposableEffect(targetWindow) {
        targetWindow.setSoftInputMode(mode)
        onDispose { targetWindow.setSoftInputMode(originalMode) }
    }
}

/**
 * 在 Dialog Window 上应用 hideSystemBars()，使对话框内容全屏无状态栏/导航栏。
 *
 * Compose Dialog 创建独立平台 Window，不继承 Activity 的 systemUiVisibility 标志。
 * 此 composable 在 Dialog 挂载时对该 Window 应用隐藏标志，卸载时不需恢复（Window 销毁）。
 *
 * 双路径方案（对标 GameActivity.hideSystemBars()）：
 * 1. WindowInsetsControllerCompat（现代 API，API 30+ 推荐方式）
 * 2. 传统 SYSTEM_UI_FLAG_*（国产 OEM ROM 兼容性，API < 35）
 */
@Composable
fun DialogSystemBarGuard() {
    val dialogWindow = generateSequence(LocalView.current) {
        it.parent as? View
    }
        .filterIsInstance<DialogWindowProvider>()
        .firstOrNull()
        ?.window
        ?: return

    DisposableEffect(dialogWindow) {
        // 路径 1: WindowInsetsController 方式（现代 API，API 30+ 推荐）
        WindowInsetsControllerCompat(dialogWindow, dialogWindow.decorView)
            .let { controller ->
                controller.hide(
                    WindowInsetsCompat.Type.statusBars() or
                        WindowInsetsCompat.Type.navigationBars()
                )
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }

        // 路径 2: 传统 SYSTEM_UI_FLAGS 方式（国产 OEM ROM 兼容，与 GameActivity.hideSystemBars 一致）
        // 注：始终执行（不按 API level 过滤），因为国产 OEM ROM 即使在 API 35+ 上
        // 仍可能对 WindowInsetsController 支持不完整，传统标志作为补充。在纯 AOSP 35+
        // 上这些 flag 是 deprecated 但无害的 no-op。
        @Suppress("DEPRECATION")
        dialogWindow.decorView.systemUiVisibility =
            dialogWindow.decorView.systemUiVisibility or
            (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
             View.SYSTEM_UI_FLAG_FULLSCREEN or
             View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
             View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
             View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
             View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)

        onDispose {
            // Dialog Window 销毁时自动清理，无需手动恢复
        }
    }
}

@Composable
fun StandardPromptDialog(
    onDismissRequest: () -> Unit,
    title: String,
    text: String? = null,
    confirmLabel: String = "确定",
    onConfirm: () -> Unit = onDismissRequest,
    dismissLabel: String? = null,
    onDismiss: (() -> Unit)? = null,
    customButtons: (@Composable RowScope.() -> Unit)? = null,
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    showCloseButton: Boolean = false,
    titleColor: Color = Color.Black,
    @DrawableRes dialogBackgroundRes: Int = R.drawable.dialog_box,
    @DrawableRes buttonBackgroundRes: Int = R.drawable.ui_button,
    @DrawableRes closeButtonRes: Int = R.drawable.ui_close_button,
    content: @Composable (ColumnScope.() -> Unit) = {}
) {
    val config = LocalConfiguration.current
    val dialogWidth = (config.screenWidthDp * 0.5f).dp
    val dialogHeight = (config.screenHeightDp * 0.55f).dp

    Dialog(
        onDismissRequest = if (dismissOnClickOutside) onDismissRequest else {{}},
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = dismissOnBackPress,
            dismissOnClickOutside = dismissOnClickOutside
        )
    ) {
        // 在 Dialog 窗口内切换 softInputMode，切断 HyperOS 震荡回路
        DialogSoftInputGuard()
        // 隐藏 Dialog Window 的系统状态栏/导航栏（该 Window 不继承 Activity 的设置）
        DialogSystemBarGuard()

        // Dialog 窗口销毁前清除焦点，防止文本选择 FloatingActionMode
        // 在窗口 token 失效后尝试弹出 PopupWindow 导致 BadTokenException
        val dialogView = LocalView.current
        DisposableEffect(Unit) {
            onDispose {
                dialogView.clearFocus()
                val imm = dialogView.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(dialogView.windowToken, 0)
            }
        }

        Box(
            modifier = Modifier
                .width(dialogWidth)
                .height(dialogHeight)
                .clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = dialogBackgroundRes),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.FillBounds
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (showCloseButton) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = titleColor
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        CloseButton(onClick = onDismissRequest, closeButtonRes = closeButtonRes)
                    }
                } else {
                    Text(
                        text = title,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = titleColor,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (text != null) {
                    Text(
                        text = text,
                        fontSize = 12.sp,
                        color = Color.Black,
                        textAlign = TextAlign.Center
                    )
                }

                if (text != null && (customButtons != null || !showCloseButton)) {
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Content area: 输入框优先，按钮在底部且空间不足时折叠
                Column(modifier = Modifier.weight(1f)) {
                    content()

                    // 弹性空间：有富余空间时把按钮推到底部，空间不足时率先折叠
                    if (!showCloseButton) {
                        if (customButtons != null) {
                            Spacer(modifier = Modifier.weight(1f))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                customButtons()
                            }
                        } else if (dismissLabel != null) {
                            Spacer(modifier = Modifier.weight(1f))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                GameButton(
                                    text = dismissLabel,
                                    onClick = { (onDismiss ?: onDismissRequest)() },
                                    buttonBackgroundRes = buttonBackgroundRes
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                GameButton(
                                    text = confirmLabel,
                                    onClick = onConfirm,
                                    buttonBackgroundRes = buttonBackgroundRes
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                GameButton(
                                    text = confirmLabel,
                                    onClick = onConfirm,
                                    buttonBackgroundRes = buttonBackgroundRes
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 内联版标准提示框 — 不使用平台 [Dialog] 窗口，改为 Box 覆盖层渲染。
 *
 * 接口签名与 [StandardPromptDialog] 完全一致，但通过内联 Box overlay 避免
 * 平台 Dialog 窗口与 IME 键盘交互导致的频闪问题（[decorFitsSystemWindows] 与
 * [adjustResize] 组合引起的窗口尺寸震荡）。
 *
 * 屏幕尺寸在 composition 入口处 [remember] 缓存，键盘弹出后不再变化，
 * 从而彻底杜绝重组震荡。
 */
@Composable
fun InlineStandardPromptDialog(
    onDismissRequest: () -> Unit,
    title: String,
    text: String? = null,
    confirmLabel: String = "确定",
    onConfirm: () -> Unit = onDismissRequest,
    dismissLabel: String? = null,
    onDismiss: (() -> Unit)? = null,
    customButtons: (@Composable RowScope.() -> Unit)? = null,
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    showCloseButton: Boolean = false,
    titleColor: Color = Color.Black,
    @DrawableRes dialogBackgroundRes: Int = R.drawable.dialog_box,
    @DrawableRes buttonBackgroundRes: Int = R.drawable.ui_button,
    @DrawableRes closeButtonRes: Int = R.drawable.ui_close_button,
    content: @Composable (ColumnScope.() -> Unit) = {}
) {
    // 在 composition 入口处读取屏幕尺寸并用 remember 缓存，之后不再变化
    DialogSoftInputGuard()
    val screenConfig = LocalConfiguration.current
    val dialogWidth = remember { (screenConfig.screenWidthDp * 0.5f).dp }
    val dialogHeight = remember { (screenConfig.screenHeightDp * 0.55f).dp }

    if (dismissOnBackPress) {
        BackHandler { onDismissRequest() }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        // 隐藏 Dialog Window 的系统状态栏/导航栏
        DialogSystemBarGuard()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .background(Color(0x99000000)) // 半透明遮罩
                .then(
                    if (dismissOnClickOutside) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismissRequest
                        )
                    } else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
        Box(
            modifier = Modifier
                .width(dialogWidth)
                .height(dialogHeight)
                .clip(RoundedCornerShape(12.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {} // 阻止点击穿透到外层
                ),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = dialogBackgroundRes),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.FillBounds
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (showCloseButton) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = titleColor
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        CloseButton(onClick = onDismissRequest, closeButtonRes = closeButtonRes)
                    }
                } else {
                    Text(
                        text = title,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = titleColor,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (text != null) {
                    Text(
                        text = text,
                        fontSize = 12.sp,
                        color = Color.Black,
                        textAlign = TextAlign.Center
                    )
                }

                if (text != null && (customButtons != null || !showCloseButton)) {
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Content area: 输入框优先，按钮在底部且空间不足时折叠
                Column(modifier = Modifier.weight(1f)) {
                    content()

                    // 弹性空间：有富余空间时把按钮推到底部，空间不足时率先折叠
                    if (!showCloseButton) {
                        if (customButtons != null) {
                            Spacer(modifier = Modifier.weight(1f))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                customButtons()
                            }
                        } else if (dismissLabel != null) {
                            Spacer(modifier = Modifier.weight(1f))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                GameButton(
                                    text = dismissLabel,
                                    onClick = { (onDismiss ?: onDismissRequest)() },
                                    buttonBackgroundRes = buttonBackgroundRes
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                GameButton(
                                    text = confirmLabel,
                                    onClick = onConfirm,
                                    buttonBackgroundRes = buttonBackgroundRes
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                GameButton(
                                    text = confirmLabel,
                                    onClick = onConfirm,
                                    buttonBackgroundRes = buttonBackgroundRes
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    }
}
