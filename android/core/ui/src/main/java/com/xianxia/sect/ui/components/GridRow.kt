package com.xianxia.sect.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 通用网格布局 Composable，将列表按固定列数分行渲染。
 *
 * 每行使用 [Row] 横向排列，行内不足 [columns] 的位置用等宽 [Spacer] 占位，
 * 保证所有行的单元格宽度一致（依赖 [Modifier.weight]）。
 *
 * @param items 待渲染的数据列表
 * @param columns 每行的列数
 * @param modifier 应用于整体网格（外层 Column）的修饰符
 * @param horizontalArrangement 行内单元格的横向排布
 * @param verticalArrangement 行与行之间的纵向排布
 * @param itemContent 单个数据项的渲染内容，接收 [RowScope] 以便调用方通过 [RowScope.weight] 控制宽度
 */
@Composable
fun <T> GridRow(
    items: List<T>,
    columns: Int = 4,
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(8.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    itemContent: @Composable RowScope.(T) -> Unit
) {
    if (items.isEmpty()) return
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
