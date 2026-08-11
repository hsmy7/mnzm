package com.xianxia.sect.ui.game.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.ui.components.SpriteImage

/**
 * 玉符货币栏（宗门信息卡片外部右侧）：
 * 半透明胶囊条 + 左侧玉符图标（12dp，与卡片内灵石图标同尺寸）+ 栏内持有数量
 * + 右侧"+"广告按钮（观看激励视频获得 3 玉符）。
 *
 * 整体可点击，弹出玉符说明/倒计时对话框；"+"按钮为内层独立点击区
 * （Compose 内层 clickable 优先于外层），弹出玉符广告确认对话框。
 * 半透明深底 + 白字为商业游戏货币栏通用做法。
 *
 * @param jadeSymbols 当前持有玉符数量（发放时自动 +1 实时刷新）
 * @param onClick 点击回调（打开玉符对话框）
 * @param onAddClick 点击"+"回调（打开玉符广告对话框）
 */
@Composable
internal fun JadeSymbolBadge(
    jadeSymbols: Int,
    onClick: () -> Unit,
    onAddClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .background(
                color = Color(0x80000000),
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        SpriteImage(
            name = "jade_symbol",
            contentDescription = "玉符",
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = "$jadeSymbols",
            fontSize = 12.sp,
            color = Color.White,
            // 单行 + 最小宽度保证 4 位数字完整显示（12sp 数字 ≈7dp/位，外层 Row 空间不足时
            // 无约束 Text 会换行截断；玉符累计无上限，4 位长期可达）
            minLines = 1,
            maxLines = 1,
            modifier = Modifier.widthIn(min = FOUR_DIGITS_MIN_WIDTH)
        )
        SpriteImage(
            name = "jade_add_button",
            contentDescription = "观看广告获得玉符",
            modifier = Modifier
                .size(ADD_BUTTON_SIZE)
                .clickable(onClick = onAddClick)
        )
    }
}

/** 玉符数量栏数字区最小宽度（容纳 4 位 12sp 数字） */
private val FOUR_DIGITS_MIN_WIDTH = 30.dp

/** "+"广告按钮尺寸（与货币栏高度协调的小按钮） */
private val ADD_BUTTON_SIZE = 14.dp
