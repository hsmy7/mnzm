package com.xianxia.sect.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xianxia.sect.core.ui.R

/**
 * 小屏界面 — 用于详情展示（物品详情、天赋详情、功法/装备详情等）。
 * 尺寸与 [StandardPromptDialog] 一致（50%W × 55%H），但使用 [R.drawable.bg_horizontal] 横向背景，
 * 与提示框的 [R.drawable.dialog_box] 背景形成视觉区分。
 *
 * 不含内置确认/取消按钮 — 内容区完全由调用方定义。
 */
@Composable
fun SmallScreenDialog(
    onDismissRequest: () -> Unit,
    title: String,
    titleColor: Color = Color.Black,
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    footer: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
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
        Box(
            modifier = Modifier
                .width(dialogWidth)
                .height(dialogHeight)
                .clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.bg_horizontal),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header: title + close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = titleColor,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                    CloseButton(onClick = onDismissRequest)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Scrollable content area
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    content()
                }
                // Footer area — outside scroll, pinned at bottom
                footer()
            }
        }
    }
}
