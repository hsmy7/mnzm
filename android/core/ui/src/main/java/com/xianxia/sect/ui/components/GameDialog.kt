package com.xianxia.sect.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable

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

enum class DialogMode { Half, Full, Auto }

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
    @DrawableRes backgroundRes: Int = SpriteResRegistry.resolve("bg_horizontal")
        ?: R.drawable.bg_horizontal,
    @DrawableRes closeButtonRes: Int = SpriteResRegistry.resolve("ui_close_button")
        ?: R.drawable.ui_close_button,
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

        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (scrimEnabled) Modifier.background(Color(0x99000000))
                    else Modifier
                )
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
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .clip(RoundedCornerShape(CornerRadius.LG))
            ) {
                Image(
                    painter = painterResource(id = backgroundRes),
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop
                )
                Column(modifier = Modifier.fillMaxSize()) {
                    // Unified header
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
                            .padding(horizontal = if (mode == DialogMode.Full) 32.dp else Spacing.MD)
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
