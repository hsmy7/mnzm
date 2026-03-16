package com.xianxia.sect.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.ui.theme.GameColors

/**
 * 统一的游戏按钮组件
 * 使用米色背景、黑色字体、浅棕色边框的统一样式
 *
 * @param text 按钮显示的文本
 * @param onClick 点击回调
 * @param modifier 修饰符
 * @param enabled 是否启用，默认为 true
 */
@Composable
fun GameButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backgroundColor: Color? = null
) {
    val actualBackgroundColor = when {
        !enabled -> Color(0xFFE0E0E0)
        backgroundColor != null -> backgroundColor
        else -> GameColors.ButtonBackground
    }
    val contentColor = if (enabled) Color.Black else Color(0xFF9E9E9E)
    val borderColor = if (enabled) GameColors.ButtonBorder else Color(0xFFBDBDBD)

    Button(
        onClick = onClick,
        modifier = modifier.height(32.dp),
        enabled = enabled,
        shape = RoundedCornerShape(4.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = actualBackgroundColor,
            contentColor = contentColor,
            disabledContainerColor = Color(0xFFE0E0E0),
            disabledContentColor = Color(0xFF9E9E9E)
        ),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}
