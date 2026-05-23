package com.xianxia.sect.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

import com.xianxia.sect.R
import com.xianxia.sect.ui.theme.AppTypography
import com.xianxia.sect.ui.theme.CornerRadius
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.theme.Spacing

enum class DialogMode { Half, Full, Auto }

@Composable
fun UnifiedGameDialog(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    mode: DialogMode = DialogMode.Half,
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    headerActions: @Composable (() -> Unit)? = null,
    headerContent: @Composable (() -> Unit)? = null,
    scrollableContent: Boolean = true,
    titleColor: Color = Color.Black,
    titleFontSize: TextUnit = AppTypography.Title,
    showCloseButton: Boolean = true,
    content: @Composable () -> Unit
) {
    if (dismissOnBackPress) {
        BackHandler(onBack = onDismissRequest)
    }

    val (widthModifier, heightModifier) = when (mode) {
        DialogMode.Half -> Pair(
            Modifier.fillMaxWidth(0.83f),
            Modifier.fillMaxHeight(0.78f)
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

    Box(
        modifier = Modifier
            .fillMaxSize()
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
            modifier = modifier
                .then(widthModifier)
                .then(heightModifier)
                .pointerInput(Unit) {
                    while (true) {
                        detectTapGestures { /* consume tap to prevent click-through, scroll passes through */ }
                    }
                }
                .clip(RoundedCornerShape(CornerRadius.LG))
        ) {
            Image(
                painter = painterResource(id = R.drawable.bg_horizontal),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop
            )
            Column(modifier = Modifier.fillMaxSize()) {
                // Unified header
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Spacing.MD, end = Spacing.MD, top = 4.dp),
                    contentAlignment = Alignment.Center
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
                                CloseButton(onClick = onDismissRequest)
                            }
                        }
                    }
                }
                // Header extension content (e.g. filter bar)
                headerContent?.invoke()
                // Scrollable content
                val contentScrollModifier = if (scrollableContent) {
                    Modifier.verticalScroll(rememberScrollState())
                } else {
                    Modifier
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .then(contentScrollModifier)
                        .padding(horizontal = Spacing.MD)
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * 替代 Compose [androidx.compose.ui.window.Dialog]。
 * 无遮罩覆盖层 — 内容直接在当前 Box 层级内渲染，不创建独立窗口。
 * 点击外部区域或按返回键触发 onDismissRequest。
 */
@Deprecated(
    message = "Use UnifiedGameDialog(mode = DialogMode.Full) instead",
    replaceWith = ReplaceWith(
        "UnifiedGameDialog(onDismissRequest = onDismissRequest, title = \"\", mode = DialogMode.Full, content = content)",
        "com.xianxia.sect.ui.components.UnifiedGameDialog"
    ),
    level = DeprecationLevel.WARNING
)
@Composable
fun GameFullDialog(
    onDismissRequest: () -> Unit,
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    content: @Composable () -> Unit
) {
    if (dismissOnBackPress) {
        BackHandler(onBack = onDismissRequest)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (dismissOnClickOutside) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismissRequest
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/**
 * 替代 Compose Material3 [androidx.compose.material3.AlertDialog]。
 * 居中卡片式弹窗，无遮罩。用于确认/选择对话框。
 */
@Deprecated(
    message = "Use StandardPromptDialog instead",
    replaceWith = ReplaceWith(
        "StandardPromptDialog(onDismissRequest = onDismissRequest, title = \"\", text = \"\", confirmLabel = \"确定\", dismissLabel = \"取消\")",
        "com.xianxia.sect.ui.components.StandardPromptDialog"
    )
)
@Composable
fun GameAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    text: @Composable (() -> Unit)? = null,
    dismissButton: @Composable (() -> Unit)? = null,
    containerColor: Color = GameColors.PageBackground,
    shape: Shape = androidx.compose.material3.AlertDialogDefaults.shape,
    tonalElevation: Dp = 0.dp
) {
    BackHandler(onBack = onDismissRequest)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismissRequest
            ),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = modifier.widthIn(max = 560.dp),
            shape = shape,
            color = containerColor,
            tonalElevation = tonalElevation
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                title()
                if (text != null) {
                    text()
                }
                Row {
                    dismissButton?.invoke()
                    confirmButton()
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

/**
 * Unified dialog wrapper for both full-screen and half-screen game dialogs.
 * Full-screen mode: content fills entire window.
 * Half-screen mode: content constrained to standard size (85% width × 78% height), centered.
 * Includes bg_horizontal background image automatically.
 */
@Deprecated(
    message = "Use UnifiedGameDialog with DialogMode instead",
    replaceWith = ReplaceWith(
        "UnifiedGameDialog(onDismissRequest = onDismissRequest, title = \"\", mode = if (isFullScreen) DialogMode.Full else DialogMode.Half, content = content)",
        "com.xianxia.sect.ui.components.UnifiedGameDialog"
    ),
    level = DeprecationLevel.WARNING
)
@Composable
fun HalfScreenDialog(
    onDismissRequest: () -> Unit,
    isFullScreen: Boolean = false,
    widthFraction: Float = DialogDefaults.HalfScreenWidthFraction,
    heightFraction: Float = DialogDefaults.HalfScreenHeightFraction,
    content: @Composable () -> Unit
) {
    if (isFullScreen) {
        // Box overlay in the Activity window — immune to platform Dialog-window manipulation
        GameFullDialog(
            onDismissRequest = onDismissRequest,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Image(
                    painter = painterResource(id = R.drawable.bg_horizontal),
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop
                )
                content()
            }
        }
    } else {
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Transparent
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(widthFraction)
                            .fillMaxHeight(heightFraction)
                            .clip(RoundedCornerShape(DialogDefaults.CornerRadius))
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.bg_horizontal),
                            contentDescription = null,
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.Crop
                        )
                        content()
                    }
                }
            }
        }
    }
}
