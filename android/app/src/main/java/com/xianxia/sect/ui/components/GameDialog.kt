package com.xianxia.sect.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xianxia.sect.R
import com.xianxia.sect.ui.theme.GameColors

/**
 * 替代 Compose [androidx.compose.ui.window.Dialog]。
 * 无遮罩覆盖层 — 内容直接在当前 Box 层级内渲染，不创建独立窗口。
 * 点击外部区域或按返回键触发 onDismissRequest。
 */
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
    /** Width fraction for half-screen dialogs: leaves ~3.5% margin on each side */
    const val HalfScreenWidthFraction = 0.85f
    /** Height fraction for half-screen dialogs: reduced from 0.85f */
    const val HalfScreenHeightFraction = 0.90f
    /** Standard max height for scrollable CommonDialog-style wrappers */
    val CommonMaxHeight: Dp = 280.dp
    /** Standard corner radius for dialog boxes */
    val CornerRadius: Dp = 12.dp
}

/**
 * Half-screen dialog with standardized width (93%) and height (78%).
 * Includes bg_screen background image. For Box-variant half-screen dialogs.
 */
@Composable
fun HalfScreenDialog(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit
) {
    GameFullDialog(onDismissRequest = onDismissRequest) {
        Box(
            modifier = Modifier
                .fillMaxWidth(DialogDefaults.HalfScreenWidthFraction)
                .fillMaxHeight(DialogDefaults.HalfScreenHeightFraction)
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
