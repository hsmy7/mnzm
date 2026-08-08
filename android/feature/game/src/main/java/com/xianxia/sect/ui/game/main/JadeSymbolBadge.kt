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
 * 半透明胶囊条 + 左侧玉符图标（12dp，与卡片内灵石图标同尺寸）+ 栏内持有数量。
 *
 * 整体可点击，弹出玉符说明/倒计时对话框。半透明深底 + 白字为商业游戏
 * 货币栏通用做法（参考货币计数常驻可见 + 可点击直达的行业惯例）。
 *
 * @param jadeSymbols 当前持有玉符数量（发放时自动 +1 实时刷新）
 * @param onClick 点击回调（打开玉符对话框）
 */
@Composable
internal fun JadeSymbolBadge(
    jadeSymbols: Int,
    onClick: () -> Unit
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
    }
}

/** 玉符数量栏数字区最小宽度（容纳 4 位 12sp 数字） */
private val FOUR_DIGITS_MIN_WIDTH = 30.dp
