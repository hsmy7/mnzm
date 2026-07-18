package com.xianxia.sect.ui.game.components.messagebar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 消息栏收起态——左下角半透明消息摘要。
 * 仅显示上下两条横线表示区域，固定三行文本高度。
 *
 * @param latestMessage 最新消息文本，null 时显示"暂无消息"
 * @param onClick 展开回调
 */
@Composable
fun MessageBarCollapsed(
    latestMessage: String?,
    onClick: () -> Unit
) {
    val lineColor = Color(0x66FFFFFF)

    Box(
        modifier = Modifier
            .width(220.dp)
            .height(64.dp)
            .background(Color(0x80000000))
            .clickable(onClick = onClick)
    ) {
        // 上横线
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(lineColor)
                .align(Alignment.TopCenter)
        )
        // 下横线
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(lineColor)
                .align(Alignment.BottomCenter)
        )
        // 消息文本
        Text(
            text = if (latestMessage.isNullOrBlank()) "暂无消息" else latestMessage,
            fontSize = 10.sp,
            color = Color.White,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}
