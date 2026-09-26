package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.ui.components.GachaColors
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.game.GachaHistoryRow
import com.xianxia.sect.ui.theme.GameColors

/**
 * 寻访记录（Q40 的 50 条历史环，只读）。
 *
 * 顺序即环序：下标 0 是最新一条（🔴 与结果页的**抽取序**是两个口径，
 * `GachaRenderModel.historyRows` 的注释里钉着，不许互相拿来当输入）。
 *
 * 用 `Column + verticalScroll` 而不是 `LazyColumn`：历史条目没有唯一键
 * （同一个月抽到同一种灵草会重复），`items(key = …)` 撞键会直接崩
 * （本仓既有 Bugly #5079/#3091 那一类事故），而环容量上限就 50 条，无需回收。
 */
@Composable
fun GachaHistoryPanel(
    rows: List<GachaHistoryRow>,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = HISTORY_H_PADDING_DP.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp)
        ) {
            if (rows.isEmpty()) {
                Text(text = EMPTY_TEXT, fontSize = 12.sp, color = GameColors.TextPrimary)
            }
            rows.forEach { row -> HistoryLine(row) }
        }
        GameButton(text = BACK_TEXT, onClick = onBack)
    }
}

/** 一行：出货 Q31 色点 + 年月 + 「名称 ×数量」 */
@Composable
private fun HistoryLine(row: GachaHistoryRow) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SWATCH_GAP_DP.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(SWATCH_DP.dp)
                    .background(GachaColors.parse(row.colorHex), CircleShape)
            )
            Text(text = row.monthLabel, fontSize = 11.sp, color = GameColors.TextSecondary)
        }
        Text(
            text = "${row.displayName} ×${row.quantity}",
            fontSize = 11.sp,
            color = GameColors.TextPrimary,
        )
    }
}

private const val HISTORY_H_PADDING_DP = 16
private const val ROW_GAP_DP = 4
private const val SWATCH_DP = 8
private const val SWATCH_GAP_DP = 4
private const val BACK_TEXT = "返回"
private const val EMPTY_TEXT = "还没有寻访记录"
