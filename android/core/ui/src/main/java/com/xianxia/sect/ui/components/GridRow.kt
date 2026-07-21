package com.xianxia.sect.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 通用网格布局 Composable，将列表按固定列数分行渲染。
 *
 * 每行使用 [Row] 横向排列，行内不足 [columns] 的位置用等宽 [Spacer] 占位，
 * 保证所有行的单元格宽度一致（依赖 [Modifier.weight]）。
 *
 * @param items 待渲染的数据列表
 * @param columns 每行的列数（当 [maxColumnWidth] 未提供时使用）
 * @param maxColumnWidth 可选，每列最大宽度。提供时根据屏幕宽度自动计算列数，覆盖 [columns]
 * @param modifier 应用于整体网格（外层 Column）的修饰符
 * @param horizontalArrangement 行内单元格的横向排布
 * @param verticalArrangement 行与行之间的纵向排布
 * @param itemContent 单个数据项的渲染内容，接收 [RowScope] 以便调用方通过 [RowScope.weight] 控制宽度
 */
@Composable
fun <T> GridRow(
    items: List<T>,
    columns: Int = 4,
    maxColumnWidth: Dp = Dp.Unspecified,
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(8.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    itemContent: @Composable RowScope.(T) -> Unit
) {
    if (items.isEmpty()) return
    if (maxColumnWidth != Dp.Unspecified) {
        require(maxColumnWidth > 0.dp) { "maxColumnWidth must be positive, got $maxColumnWidth" }
        BoxWithConstraints {
            val calculatedColumns = (maxWidth / maxColumnWidth).toInt().coerceAtLeast(1)
            GridRowContent(
                items = items,
                columns = calculatedColumns,
                horizontalArrangement = horizontalArrangement,
                verticalArrangement = verticalArrangement,
                itemContent = itemContent,
                modifier = modifier
            )
        }
    } else {
        GridRowContent(
            items = items,
            columns = columns,
            horizontalArrangement = horizontalArrangement,
            verticalArrangement = verticalArrangement,
            itemContent = itemContent,
            modifier = modifier
        )
    }
}

@Composable
private fun <T> GridRowContent(
    items: List<T>,
    columns: Int,
    horizontalArrangement: Arrangement.Horizontal,
    verticalArrangement: Arrangement.Vertical,
    itemContent: @Composable RowScope.(T) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = verticalArrangement
    ) {
        items.chunked(columns).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = horizontalArrangement
            ) {
                rowItems.forEach { item ->
                    itemContent(item)
                }
                repeat(columns - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
