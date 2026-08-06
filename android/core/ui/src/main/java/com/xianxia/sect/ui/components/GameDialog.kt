package com.xianxia.sect.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.background

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

import androidx.annotation.DrawableRes
import com.xianxia.sect.core.ui.R
import com.xianxia.sect.ui.theme.AppTypography
import com.xianxia.sect.ui.theme.CornerRadius
import com.xianxia.sect.ui.theme.Spacing

/** 对话框尺寸模式：Half=0.83w/0.78h，Large=0.95w/0.9h（存档管理），Full=全屏，Auto=0.83w 包高 */
enum class DialogMode { Half, Large, Full, Auto }

/**
 * 对话框窗口触摸 → 刷新引擎闲置计时的全局钩子（宿主 CompositionLocalProvider 提供）。
 *
 * Dialog 是独立 Window，触摸不触发 Activity.onUserInteraction——若不做此桥接，
 * 对话框内挂机（炼丹/锻造/弟子详情等）5s 即触发动态帧率降档。
 * CompositionLocal 经 Dialog 组合子树继承，宿主一处提供即可覆盖全部对话框。
 */
val LocalOnUserInteraction = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
fun UnifiedGameDialog(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    mode: DialogMode = DialogMode.Half,
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    scrimEnabled: Boolean = true,
    headerActions: @Composable (() -> Unit)? = null,
    headerContent: @Composable (() -> Unit)? = null,
    scrollableContent: Boolean = false,
    titleColor: Color = Color.Black,
    titleFontSize: TextUnit = AppTypography.Title,
    titleAlignment: Alignment = Alignment.Center,
    showCloseButton: Boolean = true,
    /** 是否渲染标题栏（false 时隐藏 header 且内容区零 padding，供全屏内容覆盖使用） */
    showHeader: Boolean = true,
    @DrawableRes backgroundRes: Int = SpriteResRegistry.resolve("bg_horizontal")
        ?: R.drawable.bg_horizontal,
    @DrawableRes closeButtonRes: Int = SpriteResRegistry.resolve("ui_close_button")
        ?: R.drawable.ui_close_button,
    content: @Composable () -> Unit
) {
    // 对话框窗口内任意触摸 → 刷新引擎闲置计时（独立 Window 不触发
    // Activity.onUserInteraction）。由宿主 CompositionLocal 提供
    // （GameOverlayHost 统一接线），防对话框内挂机误触发动态帧率降档。
    val onDialogTouch = LocalOnUserInteraction.current
    if (dismissOnBackPress) {
        BackHandler(onBack = onDismissRequest)
    }

    val (widthModifier, heightModifier) = when (mode) {
        DialogMode.Half -> Pair(
            Modifier.fillMaxWidth(0.83f),
            Modifier.fillMaxHeight(0.78f)
        )
        DialogMode.Large -> Pair(
            Modifier.fillMaxWidth(0.95f),
            Modifier.fillMaxHeight(0.9f)
        )
        DialogMode.Full -> Pair(
            Modifier.fillMaxSize(),
            Modifier.fillMaxSize()
        )
        DialogMode.Auto -> Pair(
            Modifier.fillMaxWidth(0.83f),
            Modifier
        )
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
        // 切换 softInputMode，切断 OEM 键盘频闪震荡回路（必须放在 Dialog {} 块内，才能获取 Dialog Window 引用）
        DialogSoftInputGuard()
        // 隐藏 Dialog Window 的系统状态栏/导航栏（必须放在 Dialog {} 块内，才能获取 Dialog Window 引用）
        DialogSystemBarGuard()
        // Dialog 窗口销毁前清除焦点并隐藏软键盘：UnifiedGameDialog 覆盖全部
        // 含输入框的对话框（AutoManagement/PatrolTower 等），防文本选择
        // FloatingActionMode 在窗口 token 失效后弹 PopupWindow 崩溃（Bugly #3026）
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
                )
                .then(
                    // 对话框窗口内任意触摸 → 刷新引擎闲置计时（防挂机误降帧）
                    if (onDialogTouch != null) {
                        Modifier.pointerInput(onDialogTouch) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                onDialogTouch()
                            }
                        }
                    } else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = modifier
                    .then(widthModifier)
                    .then(heightModifier)
                    .clickableWithSound(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .clip(RoundedCornerShape(CornerRadius.LG))
            ) {
                // backgroundRes = 0 时不绘制背景图（纯色背景由调用方内容区提供）
                if (backgroundRes != 0) {
                    Image(
                        painter = painterResource(id = backgroundRes),
                        contentDescription = null,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                Column(modifier = Modifier.fillMaxSize()) {
                    // Unified header（showHeader=false 时整体隐藏，供全屏内容覆盖）
                    if (showHeader) {
                        val headerH = if (mode == DialogMode.Full) 32.dp else Spacing.MD
                        val headerTopPadding = if (mode == DialogMode.Full) 4.dp else Spacing.XS
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = headerH, end = headerH, top = headerTopPadding),
                            contentAlignment = titleAlignment
                        ) {
                            Text(
                                text = title,
                                fontSize = titleFontSize,
                                fontWeight = FontWeight.Bold,
                                color = titleColor
                            )
                            if (showCloseButton || headerActions != null) {
                                Row(
                                    modifier = Modifier.align(Alignment.CenterEnd),
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.SM),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    headerActions?.invoke()
                                    if (showCloseButton) {
                                        CloseButton(onClick = onDismissRequest, closeButtonRes = closeButtonRes)
                                    }
                                }
                            }
                        }
                        // Header extension content (e.g. filter bar)
                        headerContent?.invoke()
                    }
                    // Scrollable content
                    val contentScrollModifier = if (scrollableContent) {
                        Modifier.verticalScroll(rememberScrollState())
                    } else {
                        Modifier
                    }
                    val contentHPadding = if (!showHeader) {
                        0.dp
                    } else if (mode == DialogMode.Full) {
                        32.dp
                    } else {
                        Spacing.MD
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .then(contentScrollModifier)
                            .padding(horizontal = contentHPadding)
                    ) {
                        content()
                    }
                }
            }
        }
    }
}

object DialogDefaults {
    /** Width fraction for half-screen dialogs: leaves ~7.5% margin on each side */
    const val HalfScreenWidthFraction = 0.83f
    /** Height fraction for half-screen dialogs: 78% of screen height */
    const val HalfScreenHeightFraction = 0.78f
    /** Standard max height for scrollable CommonDialog-style wrappers */
    val CommonMaxHeight: Dp = 280.dp
    /** Standard corner radius for dialog boxes */
    val CornerRadius: Dp = 12.dp
}
