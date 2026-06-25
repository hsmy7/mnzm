package com.xianxia.sect.ui.game.tabs

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.ui.components.GridRow
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.UnifiedItemCard

@Composable
internal fun SellablePillSection(
    items: List<Pill>,
    modifier: Modifier = Modifier,
    onItemLongPress: (Any) -> Unit = {}
) {
    if (items.isEmpty()) return
    Text(
        text = "丹药 (${items.size}件)",
        fontSize = 11.sp,
        color = Color.Black,
        modifier = modifier.padding(top = 4.dp)
    )
    GridRow(items = items) { item ->
        UnifiedItemCard(
            data = ItemCardData(
                name = item.name,
                rarity = item.rarity,
                quantity = item.quantity,
                grade = item.grade.displayName,
                isPill = true
            ),
            modifier = Modifier.weight(1f),
            onLongPress = { onItemLongPress(item) }
        )
    }
}
