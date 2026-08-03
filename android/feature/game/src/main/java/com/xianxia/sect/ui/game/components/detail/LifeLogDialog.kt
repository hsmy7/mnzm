package com.xianxia.sect.ui.game.components.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.theme.GameColors

/**
 * 弟子日志对话框。
 * 以半屏对话框展示弟子生平事件，按时间顺序排列。
 *
 * @param discipleName 弟子名称（用于标题）
 * @param events 日志事件列表，每个元素格式为 "xx岁：事件描述"
 * @param onDismiss 关闭对话框回调
 */
@Composable
fun LifeLogDialog(
    discipleName: String,
    events: List<String>,
    onDismiss: () -> Unit
) {
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "日志",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        if (events.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无日志",
                    fontSize = 14.sp,
                    color = Color.Black
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                itemsIndexed(events) { index, event ->
                    Text(
                        text = event,
                        fontSize = 12.sp,
                        color = Color.Black,
                        fontWeight = FontWeight.Normal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp)
                    )
                    if (index < events.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.fillMaxWidth(),
                            thickness = 0.5.dp,
                            color = GameColors.SurfaceLightGray
                        )
                    }
                }
            }
        }
    }
}
