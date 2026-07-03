package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.GameItem
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.theme.ButtonSizes

@Immutable
data class PlayerListItem(
    val id: String, val name: String, val type: String,
    val rarity: Int, val quantity: Int, val price: Long, val itemId: String
)

@Composable
fun ListingManagementDialog(
    gameData: com.xianxia.sect.core.model.GameData?,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val playerListedItems = gameData?.playerListedItems ?: emptyList()
    var showInventorySelectDialog by remember { mutableStateOf(false) }
    val listItems = remember(playerListedItems) {
        playerListedItems.map { item ->
            PlayerListItem(id = item.id, name = item.name, type = item.type,
                rarity = item.rarity, quantity = item.quantity, price = item.price, itemId = item.itemId)
        }
    }

    UnifiedGameDialog(onDismissRequest = onDismiss, title = "上架管理", mode = DialogMode.Full, scrollableContent = false,
        headerActions = { GameButton(text = "上架", onClick = { showInventorySelectDialog = true }) }
    ) {
        Column(Modifier.fillMaxSize()) {
            if (listItems.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().background(GameColors.CardBackground, RoundedCornerShape(4.dp)).padding(8.dp),
                    contentAlignment = Alignment.Center) { Text("暂无上架道具", fontSize = 12.sp, color = GameColors.TextSecondary) }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth().background(GameColors.CardBackground, RoundedCornerShape(4.dp)).padding(8.dp)) {
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("道具名称", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = GameColors.TextSecondary, modifier = Modifier.weight(1f))
                            Text("数量", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = GameColors.TextSecondary, modifier = Modifier.width(60.dp), textAlign = TextAlign.Center)
                            Text("价格", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = GameColors.TextSecondary, modifier = Modifier.width(60.dp), textAlign = TextAlign.Center)
                            Text("操作", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = GameColors.TextSecondary, modifier = Modifier.width(60.dp), textAlign = TextAlign.Center)
                        }
                        HorizontalDivider(thickness = 1.dp, color = Color(0xFFBDBDBD))
                        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                            itemsIndexed(listItems, key = { _, item -> item.id }) { index, item ->
                                Column {
                                    ListedItemCard(item = item, onDelist = { viewModel.removePlayerListedItem(item.id) })
                                    if (index < listItems.lastIndex) HorizontalDivider(thickness = 1.dp, color = Color(0xFFBDBDBD))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showInventorySelectDialog) {
        InventorySelectDialog(viewModel = viewModel, onDismiss = { showInventorySelectDialog = false })
    }
}

@Composable
private fun ListedItemCard(item: PlayerListItem, onDelist: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(item.name, fontSize = 11.sp, color = Color.Black, modifier = Modifier.weight(1f))
        Text("×${item.quantity}", fontSize = 11.sp, color = Color.Black, modifier = Modifier.width(60.dp), textAlign = TextAlign.Center)
        Text("${item.price}灵石", fontSize = 11.sp, color = GameColors.GoldDark, modifier = Modifier.width(60.dp), textAlign = TextAlign.Center)
        GameButton(text = "下架", onClick = onDelist, modifier = Modifier.width(60.dp))
    }
}

fun <T : GameItem> filterAndSortItems(items: List<T>, excludeIds: Set<String>): List<T> {
    return items.filter { it.id !in excludeIds }
        .sortedWith(compareByDescending<T> { it.rarity }.thenBy { it.name })
}

enum class ListingFilter(val displayName: String) {
    ALL("全部"), EQUIPMENT("装备"), MANUAL("功法"), PILL("丹药")
}

enum class MerchantFilter(val displayName: String, val typeValue: String?) {
    ALL("全部", null), EQUIPMENT("装备", "equipment"), MANUAL("功法", "manual"),
    PILL("丹药", "pill"), MATERIAL("材料", "material"), HERB("草药", "herb"), SEED("种子", "seed")
}

enum class MerchantMode(val displayName: String) { BUY("购买"), ACQUISITION("收购") }

@Composable
fun ListingFilterButton(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.width(ButtonSizes.StandardWidth).height(ButtonSizes.Large)
        .clip(RoundedCornerShape(4.dp)).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Image(painter = painterResource(id = R.drawable.ui_button), contentDescription = null,
            modifier = Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (selected) Color.White else Color.Black)
    }
}
