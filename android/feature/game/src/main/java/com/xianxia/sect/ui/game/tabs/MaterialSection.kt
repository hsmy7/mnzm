package com.xianxia.sect.ui.game.tabs

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.UnifiedItemCard

@Composable
internal fun SellableMaterialSection(
    items: List<Material>,
    modifier: Modifier = Modifier,
    onItemLongPress: (Any) -> Unit = {}
) {
    if (items.isEmpty()) return
    Text(
        text = "材料 (${items.size}件)",
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
                    data = ItemCardData(
                        name = item.name,
                        rarity = item.rarity,
                        quantity = item.quantity,
                        isMaterial = true
                    ),
                    modifier = Modifier.weight(1f),
                    onLongPress = { onItemLongPress(item) }
                )
            }
            repeat(4 - rowItems.size) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun SellableHerbSection(
    items: List<Herb>,
    modifier: Modifier = Modifier,
    onItemLongPress: (Any) -> Unit = {}
) {
    if (items.isEmpty()) return
    Text(
        text = "草药 (${items.size}件)",
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
                    data = ItemCardData(
                        name = item.name,
                        rarity = item.rarity,
                        quantity = item.quantity,
                        isHerb = true
                    ),
                    modifier = Modifier.weight(1f),
                    onLongPress = { onItemLongPress(item) }
                )
            }
            repeat(4 - rowItems.size) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun SellableSeedSection(
    items: List<Seed>,
    modifier: Modifier = Modifier,
    onItemLongPress: (Any) -> Unit = {}
) {
    if (items.isEmpty()) return
    Text(
        text = "种子 (${items.size}件)",
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
                    data = ItemCardData(
                        name = item.name,
                        rarity = item.rarity,
                        quantity = item.quantity,
                        isSeed = true
                    ),
                    modifier = Modifier.weight(1f),
                    onLongPress = { onItemLongPress(item) }
                )
            }
            repeat(4 - rowItems.size) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}
