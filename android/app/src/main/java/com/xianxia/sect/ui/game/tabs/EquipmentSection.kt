package com.xianxia.sect.ui.game.tabs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.UnifiedItemCard

@Composable
internal fun SellableEquipmentSection(
    items: List<EquipmentStack>,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    Text(
        text = "装备 (${items.size}件)",
        fontSize = 11.sp,
        color = Color.Black,
        modifier = modifier.padding(top = 4.dp)
    )
    items.chunked(4).forEach { rowItems ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            rowItems.forEach { item ->
                UnifiedItemCard(
                    data = ItemCardData(name = item.name, rarity = item.rarity),
                    showQuantity = false,
                    modifier = Modifier.weight(1f)
                )
            }
            repeat(4 - rowItems.size) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}
