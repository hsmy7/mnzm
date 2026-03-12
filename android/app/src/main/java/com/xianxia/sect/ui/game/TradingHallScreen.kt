package com.xianxia.sect.ui.game

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.TradingItem
import com.xianxia.sect.ui.theme.GameColors

@Composable
fun TradingHallDialog(
    gameData: GameData?,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("出售", "求购")

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(16.dp),
            color = GameColors.CardBackground
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TradingHallHeader(
                    gameData = gameData,
                    onDismiss = onDismiss
                )

                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = GameColors.CardBackground,
                    contentColor = GameColors.Secondary
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title, fontSize = 12.sp) }
                        )
                    }
                }

                when (selectedTab) {
                    0 -> TradingSellTab(
                        sellList = gameData?.tradingSellList ?: emptyList(),
                        viewModel = viewModel
                    )
                    1 -> TradingBuyTab(
                        buyList = gameData?.tradingBuyList ?: emptyList(),
                        viewModel = viewModel
                    )
                }
            }
        }
    }
}

@Composable
private fun TradingHallHeader(
    gameData: GameData?,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "交易堂",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            Text(
                text = "当前灵石: ${gameData?.spiritStones ?: 0}",
                fontSize = 12.sp,
                color = GameColors.TextSecondary
            )
        }

        TextButton(onClick = onDismiss) {
            Text("关闭", color = GameColors.TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun TradingSellTab(
    sellList: List<TradingItem>,
    viewModel: GameViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        if (sellList.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无上架商品",
                    fontSize = 12.sp,
                    color = GameColors.TextSecondary
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sellList) { item ->
                    TradingItemCard(
                        item = item,
                        onBuy = { viewModel.buyFromTradingHall(item.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TradingBuyTab(
    buyList: List<TradingItem>,
    viewModel: GameViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        if (buyList.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无求购信息",
                    fontSize = 12.sp,
                    color = GameColors.TextSecondary
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(buyList) { item ->
                    TradingItemCard(
                        item = item,
                        onSell = { viewModel.sellToTradingHall(item.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TradingItemCard(
    item: TradingItem,
    onBuy: (() -> Unit)? = null,
    onSell: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = GameColors.TextPrimary
                )
                Text(
                    text = "类型: ${getItemTypeText(item.type)}",
                    fontSize = 12.sp,
                    color = GameColors.TextSecondary
                )
                RarityBadge(rarity = item.rarity)
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${item.price} 灵石",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = GameColors.GoldDark
                )
                Text(
                    text = "数量: ${item.quantity}",
                    fontSize = 12.sp,
                    color = GameColors.TextTertiary
                )

                onBuy?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = it,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Text("购买", fontSize = 12.sp)
                    }
                }

                onSell?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = it,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GameColors.Secondary
                        )
                    ) {
                        Text("出售", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun RarityBadge(rarity: Int) {
    val (text, color) = when (rarity) {
        1 -> "普通" to GameColors.RarityCommon
        2 -> "灵品" to GameColors.RaritySpirit
        3 -> "宝品" to GameColors.RarityTreasure
        4 -> "玄品" to GameColors.RarityMystic
        5 -> "地品" to GameColors.RarityEarth
        6 -> "天品" to GameColors.RarityHeaven
        else -> "普通" to GameColors.RarityCommon
    }

    Box(
        modifier = Modifier
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.2f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            color = color
        )
    }
}

private fun getItemTypeText(type: String): String = when (type) {
    "equipment" -> "装备"
    "manual" -> "功法"
    "pill" -> "丹药"
    "material" -> "材料"
    "herb" -> "灵药"
    else -> type
}
