package com.xianxia.sect.ui.components

import android.app.Activity
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsCompat
import com.xianxia.sect.core.ui.R
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 在 Composable 挂载期间将目标窗口的 softInputMode 临时切换为 [mode]，
 * 卸载时自动恢复。适用于 [Dialog] 内的平台 Dialog 窗口和 Activity 内的 Box overlay。
 *
 * 使用 [SOFT_INPUT_ADJUST_PAN] 替代 [SOFT_INPUT_ADJUST_NOTHING] 以兼容
 * 国产 ROM（小米 HyperOS 等）上 [adjustResize] 导致的键盘反复弹出收起频闪问题。
 * [ADJUST_PAN] 不做窗口 resize（切断振荡回路），仅平移内容。
 *
 * 使用本组件的窗口**禁止再叠加 imePadding**——pan + padding 双重位移正是
 * 历史键盘振荡频闪的根因配方（2026-08 根治，见 [isInsideDialogWindow] 与
 * rules/dialog-soft-input-guard.md）。
 *
 * 行业调研结论（2026-07）：
 * - Google IssueTracker #229378542: imePadding 在 Dialog 内不可靠
 * - StackOverflow 社区共识: adjustPan 是 Compose Dialog 输入框的最佳实践
 * - Xiaomi MIUI/HyperOS 已知缺陷: imePadding 在 Dialog 窗口上无法正确处理 keyboard insets
 * - Unity/Flutter 游戏行业: adjustNothing + 手动键盘高度监听
 *
 * @see DialogSystemBarGuard
 */
@Composable
fun DialogSoftInputGuard(
    mode: Int = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
) {
    // 遍历 View 层级寻找 DialogWindowProvider（平台 Dialog 窗口）
    val dialogWindow = generateSequence(LocalView.current) {
        it.parent as? View
    }
        .filterIsInstance<DialogWindowProvider>()
        .firstOrNull()
        ?.window
    val targetWindow = dialogWindow
        ?: run {
            // 回退路径：部分国产 ROM 的 Dialog 视图层级可能不包含 DialogWindowProvider，
            // 尝试通过 rootView（Dialog 窗口的顶层 View）获取
            val rootView = LocalView.current.rootView
            (rootView as? DialogWindowProvider)?.window
        }
        ?: (LocalActivity.current as? Activity)?.window
        ?: run {
            Log.w("DialogSoftInputGuard", "无法获取 Dialog/Activity 窗口引用，" +
                "softInputMode 防护失效——当前 ROM 可能在 Dialog 窗口上触发键盘振荡")
            return
        }
    val originalMode = remember { targetWindow.attributes.softInputMode }
    DisposableEffect(targetWindow) {
        targetWindow.setSoftInputMode(mode)
        onDispose {
            try {
                targetWindow.setSoftInputMode(originalMode)
            } catch (_: Exception) {
                // OEM 定制 Window 实现可能在窗口销毁后抛出异常，安全忽略
            }
        }
    }
}

/**
 * 输入对话框挂载期间的宿主窗口系统栏冻结 Effect（2026-08 荣耀 X70 键盘频闪根治）。
 *
 * [enabled] 为 true（含文本输入的对话框）时，挂载期间通过 [SystemBarFreezeScope]
 * 冻结宿主 Activity 的系统栏隐藏操作，销毁时解冻并触发宿主恢复隐藏。
 * 提取为独立 composable 供 [InlineStandardPromptDialog] 与 [UnifiedGameDialog] 复用，
 * 避免在各容器函数内增加分支复杂度（detekt CyclomaticComplexMethod 阈值守卫）。
 */
@Composable
internal fun SystemBarFreezeEffect(enabled: Boolean) {
    if (enabled) {
        DisposableEffect(Unit) {
            SystemBarFreezeScope.enterFreeze()
            onDispose { SystemBarFreezeScope.exitFreeze() }
        }
    }
}

/**
 * 判定给定 [View] 是否处于平台 Dialog 窗口（Compose [Dialog] 创建的独立 Window）内。
 *
 * 通过遍历 View 父链查找 [DialogWindowProvider] 实现。用于决定键盘避让机制：
 * - Dialog 窗口内：外层窗口已由 [DialogSoftInputGuard] 应用 ADJUST_PAN 单一避让，
 *   内层容器必须禁用 imePadding（pan + padding 双重位移 = 国产 ROM 键盘振荡根因）
 * - Activity 窗口内：保持 manifest adjustResize + imePadding 官方标准组合
 */
internal fun isInsideDialogWindow(view: View): Boolean =
    generateSequence(view) { it.parent as? View }
        .filterIsInstance<DialogWindowProvider>()
        .firstOrNull() != null

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
    scrimEnabled: Boolean = true,
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
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = dismissOnBackPress,
            dismissOnClickOutside = false
        )
    ) {
        // 在 Dialog 窗口内切换 softInputMode，切断 HyperOS 震荡回路
        DialogSoftInputGuard()
        // 隐藏 Dialog Window 的系统状态栏/导航栏（该 Window 不继承 Activity 的设置）
        DialogSystemBarGuard()
        // Dialog 窗口销毁前清除焦点并隐藏软键盘，防止文本选择 FloatingActionMode
        // 在窗口 token 失效后尝试弹出 PopupWindow 导致 BadTokenException（Bugly #3026）
        DialogFocusGuard()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (scrimEnabled) Modifier.background(Color(0x99000000))
                    else Modifier
                )
                .then(
                    if (dismissOnClickOutside) {
                        Modifier.clickableWithSound(
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
                    .clickableWithSound(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
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

/**
 * 内联版标准提示框 — 不使用平台 [Dialog] 窗口，改为 Box 覆盖层渲染。
 *
 * 接口签名与 [StandardPromptDialog] 完全一致，但通过内联 Box overlay 避免
 * 平台 Dialog 窗口与 IME 键盘交互导致的频闪问题（[decorFitsSystemWindows] 与
 * [adjustResize] 组合引起的窗口尺寸震荡）。2026-08 恢复覆盖层形态（历史上
 * 2133597c 曾统一改回平台 Dialog 窗口造成键盘振荡回归，见
 * docs/adr/dialog-system-refactoring.md）。
 *
 * 屏幕尺寸在 composition 入口处 [remember] 缓存，键盘弹出后不再变化，
 * 从而彻底杜绝重组震荡。
 *
 * 键盘避让双上下文机制：
 * - 渲染于 Activity 层：窗口保持 manifest adjustResize + 本组件 [imePadding]
 *   = Google 官方标准组合（单一避让）
 * - 渲染于平台 Dialog 窗口内（嵌套在 [UnifiedGameDialog] 等窗口内部）：外层
 *   窗口已由 [DialogSoftInputGuard] 应用 ADJUST_PAN 单一避让，本组件自动禁用
 *   imePadding——pan + padding 双重位移是国产 ROM 键盘振荡频闪的历史根因。
 * 窗口上下文由 [isInsideDialogWindow] 自动检测，调用方无需关心。
 *
 * 系统栏冻结机制（2026-08 荣耀 X70 键盘频闪根治）：
 * [freezeSystemBars] 为 true（含文本输入的对话框）时，挂载期间通过
 * [SystemBarFreezeScope] 冻结宿主 Activity 的系统栏隐藏操作。Android 15
 * 强制 edge-to-edge 下 IME 可见期间系统接管导航栏，应用 hide() 与其对抗 +
 * 荣耀 MagicOS 键盘弹出/收起期间的窗口焦点抖动（onWindowFocusChanged 反复
 * 回调触发 hide()）会形成"键盘弹出→收起→再弹出"振荡回路，冻结后切断放大器。
 * 销毁时解冻并触发宿主恢复系统栏隐藏。无输入框的提示框传 false（默认），
 * 保持原有行为。
 */
// 平铺参数签名与 StandardPromptDialog 对齐，聚合数据类会破坏 20+ 调用点语义
@Suppress("LongParameterList")
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
    scrimEnabled: Boolean = true,
    titleColor: Color = Color.Black,
    @DrawableRes dialogBackgroundRes: Int = R.drawable.dialog_box,
    @DrawableRes buttonBackgroundRes: Int = R.drawable.ui_button,
    @DrawableRes closeButtonRes: Int = R.drawable.ui_close_button,
    freezeSystemBars: Boolean = false,
    content: @Composable (ColumnScope.() -> Unit) = {}
) {
    // 输入对话框挂载期间冻结宿主窗口系统栏操作，切断键盘弹出收起振荡回路的
    // 放大器环节（荣耀 X70 根治，见 SystemBarFreezeScope KDoc）
    SystemBarFreezeEffect(freezeSystemBars)

    // 在 composition 入口处读取屏幕尺寸并用 remember 缓存，之后不再变化
    val screenConfig = LocalConfiguration.current
    val dialogWidth = remember { (screenConfig.screenWidthDp * 0.5f).dp }
    val dialogHeight = remember { (screenConfig.screenHeightDp * 0.55f).dp }

    if (dismissOnBackPress) {
        BackHandler { onDismissRequest() }
    }

    // 覆盖层销毁前清除焦点并隐藏软键盘，防止文本选择 FloatingActionMode
    // 在窗口 token 失效后尝试弹出 PopupWindow 导致 BadTokenException（Bugly #3026）
    DialogFocusGuard()

    // 检测是否处于平台 Dialog 窗口内（嵌套在 UnifiedGameDialog 等窗口内部时）：
    // 外层窗口已由 DialogSoftInputGuard 应用 ADJUST_PAN 单一避让，必须禁用 imePadding，
    // 避免 pan + padding 双重位移（国产 ROM 键盘振荡根因）；Activity 层保持
    // manifest adjustResize + imePadding 官方标准组合。
    val dialogView = LocalView.current
    val insideDialogWindow = remember { isInsideDialogWindow(dialogView) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (!insideDialogWindow) Modifier.imePadding() else Modifier)
            .then(
                if (scrimEnabled) Modifier.background(Color(0x99000000))
                else Modifier
            )
            .then(
                if (dismissOnClickOutside) {
                    Modifier.clickableWithSound(
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
                .clickableWithSound(
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
