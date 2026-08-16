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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.platform.testTag
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

/**
 * 宿主已绘制单例遮罩标记（GameOverlayHost 提供 true）。
 *
 * v4.0.81 后遮罩收敛为 GameOverlayHost 根节点单例（GameOverlayScrim），所有
 * 对话框窗口应保持透明、不自画遮罩，否则多层 0x99000000 半透明黑 α 复合叠加
 * （1-(1-0.6)^n）会令界面外近黑。本标记经 CompositionLocal 透传进 Dialog 组合
 * 子树（与 LocalOnUserInteraction 同机制），宿主下任意对话框（含嵌套子对话框）
 * 自动禁用自画遮罩；宿主外独立界面不受影响，仍默认自画。
 */
val LocalDialogScrimHosted = androidx.compose.runtime.staticCompositionLocalOf<Boolean> { false }

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
    /** 窗口级覆盖层槽位（如内联兑换码弹窗）：frame 内容之后渲染（z 序最高），fillMaxSize 覆盖整个窗口 */
    overlay: @Composable (() -> Unit)? = null,
    @DrawableRes backgroundRes: Int = SpriteResRegistry.resolve("bg_horizontal")
        ?: R.drawable.bg_horizontal,
    @DrawableRes closeButtonRes: Int = SpriteResRegistry.resolve("ui_close_button")
        ?: R.drawable.ui_close_button,
    /** 含文本输入框时传 true：挂载期间冻结宿主窗口系统栏操作（荣耀X70键盘频闪根治，见 SystemBarFreezeScope KDoc） */
    freezeSystemBars: Boolean = false,
    content: @Composable () -> Unit
) {
    // 输入对话框挂载期间冻结宿主窗口系统栏操作，切断键盘弹出收起振荡回路的
    // 放大器环节（荣耀 X70 根治，见 SystemBarFreezeScope KDoc）
    SystemBarFreezeEffect(freezeSystemBars)

    // 对话框窗口内任意触摸 → 刷新引擎闲置计时（独立 Window 不触发
    // Activity.onUserInteraction）。由宿主 CompositionLocal 提供
    // （GameOverlayHost 统一接线），防对话框内挂机误触发动态帧率降档。
    val onDialogTouch = LocalOnUserInteraction.current
    // 宿主（GameOverlayHost）已画单例遮罩时强制禁用自画遮罩，防多窗口遮罩 α 叠加变黑
    val scrimHosted = LocalDialogScrimHosted.current
    val scrimActuallyEnabled = scrimEnabled && !scrimHosted
    if (dismissOnBackPress) {
        BackHandler(onBack = onDismissRequest)
    }

    val (widthModifier, heightModifier) = dialogModeModifiers(mode)

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

        DialogScrim(
            onDismissRequest = onDismissRequest,
            scrimEnabled = scrimActuallyEnabled,
            dismissOnClickOutside = dismissOnClickOutside,
            onDialogTouch = onDialogTouch
        ) {
            DialogFrame(
                modifier = modifier,
                widthModifier = widthModifier,
                heightModifier = heightModifier,
                backgroundRes = backgroundRes,
                showHeader = showHeader,
                title = title,
                mode = mode,
                titleColor = titleColor,
                titleFontSize = titleFontSize,
                titleAlignment = titleAlignment,
                showCloseButton = showCloseButton,
                headerActions = headerActions,
                headerContent = headerContent,
                closeButtonRes = closeButtonRes,
                onDismissRequest = onDismissRequest,
                scrollableContent = scrollableContent,
                content = content,
                overlay = overlay
            )
        }
    }
}

/** 对话框尺寸模式 → (宽, 高) 修饰符（UnifiedGameDialog 拆分） */
private fun dialogModeModifiers(mode: DialogMode): Pair<Modifier, Modifier> = when (mode) {
    DialogMode.Half -> Pair(
        Modifier.fillMaxWidth(DialogDefaults.HalfScreenWidthFraction),
        Modifier.fillMaxHeight(DialogDefaults.HalfScreenHeightFraction)
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
        Modifier.fillMaxWidth(DialogDefaults.HalfScreenWidthFraction),
        Modifier
    )
}

/** 框架内容（UnifiedGameDialog 拆分）：背景图 + 标题栏 + 内容区 + 窗口级覆盖层 */
@Composable
@Suppress("LongParameterList") // 拆分聚合：18 个平铺参数均为原公共函数参数的搬移（detekt 对 @Composable 不豁免）
private fun BoxScope.DialogFrame(
    modifier: Modifier,
    widthModifier: Modifier,
    heightModifier: Modifier,
    @DrawableRes backgroundRes: Int,
    showHeader: Boolean,
    title: String,
    mode: DialogMode,
    titleColor: Color,
    titleFontSize: TextUnit,
    titleAlignment: Alignment,
    showCloseButton: Boolean,
    headerActions: (@Composable () -> Unit)?,
    headerContent: (@Composable () -> Unit)?,
    @DrawableRes closeButtonRes: Int,
    onDismissRequest: () -> Unit,
    scrollableContent: Boolean,
    content: @Composable () -> Unit,
    overlay: (@Composable () -> Unit)?
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
            if (showHeader) {
                DialogHeader(
                    title = title,
                    mode = mode,
                    titleColor = titleColor,
                    titleFontSize = titleFontSize,
                    titleAlignment = titleAlignment,
                    showCloseButton = showCloseButton,
                    headerActions = headerActions,
                    headerContent = headerContent,
                    closeButtonRes = closeButtonRes,
                    onDismissRequest = onDismissRequest
                )
            }
            DialogContentArea(
                scrollableContent = scrollableContent,
                showHeader = showHeader,
                mode = mode,
                content = content
            )
        }
    }
    // 窗口级覆盖层槽位：frame 之后渲染（外层 BoxScope 内 z 序最高），
    // 内联覆盖层 fillMaxSize 覆盖整个窗口（含 header 与内容区 padding）
    overlay?.invoke()
}

/** 遮罩层（UnifiedGameDialog 拆分）：scrim 背景 + 点击外部关闭 + 触摸闲置计时刷新 */
@Composable
private fun DialogScrim(
    onDismissRequest: () -> Unit,
    scrimEnabled: Boolean,
    dismissOnClickOutside: Boolean,
    onDialogTouch: (() -> Unit)?,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (scrimEnabled) Modifier.background(Color(0x99000000)).testTag("scrim")
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
        contentAlignment = Alignment.Center,
        content = content
    )
}

/** 标题栏（UnifiedGameDialog 拆分）：标题 + 关闭按钮/头部动作 + header 扩展内容 */
@Composable
@Suppress("LongParameterList") // 拆分聚合：10 个平铺参数均为原公共函数参数的搬移（detekt 对 @Composable 不豁免）
private fun DialogHeader(
    title: String,
    mode: DialogMode,
    titleColor: Color,
    titleFontSize: TextUnit,
    titleAlignment: Alignment,
    showCloseButton: Boolean,
    headerActions: (@Composable () -> Unit)?,
    headerContent: (@Composable () -> Unit)?,
    @DrawableRes closeButtonRes: Int,
    onDismissRequest: () -> Unit
) {
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

/** 内容区（UnifiedGameDialog 拆分）：滚动修饰 + 水平 padding + content 槽位 */
@Composable
private fun ColumnScope.DialogContentArea(
    scrollableContent: Boolean,
    showHeader: Boolean,
    mode: DialogMode,
    content: @Composable () -> Unit
) {
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
