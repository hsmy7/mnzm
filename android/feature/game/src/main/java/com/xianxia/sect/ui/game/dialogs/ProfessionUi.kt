package com.xianxia.sect.ui.game.dialogs

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.profession.ProfessionRules

/**
 * 职业等级标签颜色（用户指定：0 白 / 1 绿 / 2 蓝 / 3 紫 / 4 橙 / 5 红）。
 *
 * @param level 职业等级（0=无职业 ~ 5=丹圣/器圣）
 */
fun professionLabelColor(level: Int): Color = when (level.coerceIn(0, ProfessionRules.MAX_LEVEL)) {
    0 -> Color.White
    1 -> Color(0xFF4CAF50)
    2 -> Color(0xFF2196F3)
    3 -> Color(0xFF9C27B0)
    4 -> Color(0xFFFF9800)
    else -> Color(0xFFF44336)
}

/**
 * 炼丹/锻造槽位外部上方的弟子职业标签。
 * 字体大小 12dp，颜色随职业等级变化（无职业=白色）。
 *
 * @param level 弟子职业等级（无弟子时传 0 显示"无职业"）
 * @param isAlchemy true=炼丹职业名（炼丹师…丹圣），false=炼器职业名（炼器师…器圣）
 */
@Composable
fun ProfessionLabel(level: Int, isAlchemy: Boolean) {
    Text(
        text = ProfessionRules.displayName(level, isAlchemy),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = professionLabelColor(level)
    )
}
