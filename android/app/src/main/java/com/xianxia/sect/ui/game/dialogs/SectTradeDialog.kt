package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.R
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.core.util.SectRelationLevel
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.WorldMapViewModel
import com.xianxia.sect.ui.game.components.ItemDetailDialog
import com.xianxia.sect.ui.theme.GameColors
import java.util.Locale

@Composable
fun SectTradeDialog(
    sect: WorldSect?,
    gameData: GameData?,
    tradeItems: List<MerchantItem>,
    viewModel: GameViewModel,
    worldMapViewModel: WorldMapViewModel,
    onDismiss: () -> Unit
) {
    var selectedItem by remember { mutableStateOf<MerchantItem?>(null) }
    var buyQuantity by remember { mutableIntStateOf(1) }
    var showDetailDialog by remember { mutableStateOf(false) }
    var showRelationWarning by remember { mutableStateOf(false) }
    var lockedItemName by remember { mutableStateOf("") }
    var lockedItemRarity by remember { mutableIntStateOf(1) }

    LaunchedEffect(tradeItems) {
        val currentId = selectedItem?.id
        if (currentId != null) {
            val updated = tradeItems.find { it.id == currentId }
            selectedItem = updated
        }
    }

    val relation = if (gameData != null && sect != null) {
        GameUtils.getSectRelation(gameData.worldMapSects, gameData.sectRelations, sect.id)
    } else 0
    val isAlly = sect?.let { worldMapViewModel.isAlly(it.id) } ?: false

    val relationLevel = GameUtils.getSectRelationLevel(relation)
    val maxAllowedRarity = relationLevel.maxAllowedRarity

    val priceMultiplier = if (gameData != null && sect != null) {
        GameUtils.calculateSectTradePriceMultiplier(gameData.worldMapSects, gameData.sectRelations, gameData.alliances, sect.id)
    } else 1.0

    val relationColor = Color(relationLevel.colorHex)

    val canTrade = relationLevel in listOf(SectRelationLevel.NORMAL, SectRelationLevel.FRIENDLY, SectRelationLevel.INTIMATE)

    fun requiredFavorLevel(rarity: Int): String = when {
        rarity <= 2 -> "40（普通关系）"
        rarity <= 4 -> "60（友善关系）"
        else -> "80（至交关系）"
    }

    UnifiedGameDialog(onDismissRequest = onDismiss, title = "宗门交易", mode = DialogMode.Full, scrollableContent = false) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "关系:",
                        fontSize = 11.sp,
                        color = GameColors.TextSecondary
                    )
                    Text(
                        text = relationLevel.displayName,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = relationColor
                    )
                    Text(
                        text = "(${relation})",
                        fontSize = 11.sp,
                        color = GameColors.TextSecondary
                    )
                    if (isAlly) {
                        Text(
                            text = "(盟友)",
                            fontSize = 10.sp,
                            color = Color(0xFF4CAF50),
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "(${String.format(Locale.getDefault(), "%.1f%%", (1 - priceMultiplier) * 100)}折扣)",
                            fontSize = 10.sp,
                            color = Color(0xFF4CAF50)
                        )
                    } else if (relation >= 70) {
                        Text(
                            text = "(${String.format(Locale.getDefault(), "%.1f%%", (1 - priceMultiplier) * 100)}折扣)",
                            fontSize = 10.sp,
                            color = Color(0xFF4CAF50)
                        )
                    } else if (!canTrade) {
                        Text(
                            text = "(关系不足，无法交易)",
                            fontSize = 10.sp,
                            color = Color(0xFFF44336)
                        )
                    }
                }

                Text(
                    text = "灵石: ${GameUtils.formatNumber(gameData?.spiritStones ?: 0)}",
                    fontSize = 11.sp,
                    color = GameColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )

                if (tradeItems.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无商品\n请稍后再来",
                            fontSize = 12.sp,
                            color = GameColors.TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(60.dp),
                        modifier = Modifier
                            .weight(1f)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(tradeItems, key = { it.id }) { item ->
                            val canBuyThisItem = canTrade && item.rarity <= maxAllowedRarity
                            val adjustedPrice = (item.price * priceMultiplier).toLong()

                            UnifiedItemCard(
                                data = ItemCardData(
                                    id = item.id,
                                    name = item.name,
                                    description = item.description,
                                    rarity = item.rarity,
                                    quantity = item.quantity,
                                    additionalInfo = "${adjustedPrice}灵石",
                                    grade = item.grade,
                                    isLocked = !canBuyThisItem,
                                    isManual = item.type == "manual",
                                    isPill = item.type == "pill",
                                    isMaterial = item.type == "material"
                                ),
                                isSelected = selectedItem?.id == item.id,
                                showViewButton = true,
                                onClick = {
                                    if (!canBuyThisItem) {
                                        lockedItemName = item.name
                                        lockedItemRarity = item.rarity
                                        showRelationWarning = true
                                    } else {
                                        if (selectedItem?.id == item.id) {
                                            selectedItem = null
                                            buyQuantity = 1
                                        } else {
                                            selectedItem = item
                                            buyQuantity = 1
                                        }
                                    }
                                },
                                onViewDetail = {
                                    selectedItem = item
                                    showDetailDialog = true
                                }
                            )
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White,
                    tonalElevation = 4.dp
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        selectedItem?.let { item ->
                            val adjustedPrice = (item.price * priceMultiplier).toLong()
                            val totalPrice = adjustedPrice * buyQuantity
                            val canAfford = (gameData?.spiritStones ?: 0L) >= totalPrice

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = item.name,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = GameColors.TextPrimary
                                    )
                                    Text(
                                        text = "单价: $adjustedPrice 灵石",
                                        fontSize = 10.sp,
                                        color = GameColors.TextSecondary
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "购买数量:",
                                        fontSize = 11.sp,
                                        color = GameColors.TextSecondary
                                    )

                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(GameColors.Background)
                                            .clickable { buyQuantity = (buyQuantity - 1).coerceAtLeast(1) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("-", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GameColors.TextPrimary)
                                    }

                                    Text(
                                        text = "$buyQuantity",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = GameColors.TextPrimary,
                                        modifier = Modifier.widthIn(min = 24.dp),
                                        textAlign = TextAlign.Center
                                    )

                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(GameColors.Background)
                                            .clickable { buyQuantity = (buyQuantity + 1).coerceAtMost(item.quantity) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("+", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GameColors.TextPrimary)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "总价: $totalPrice 灵石",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (canAfford) GameColors.GoldDark else Color.Red
                                )

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    GameButton(
                                        text = "取消",
                                        onClick = {
                                            selectedItem = null
                                            buyQuantity = 1
                                        }
                                    )

                                    GameButton(
                                        text = "确认购买",
                                        onClick = {
                                            worldMapViewModel.buyFromSectTrade(item.id, buyQuantity)
                                            buyQuantity = 1
                                        },
                                        enabled = canAfford && buyQuantity > 0
                                    )
                                }
                            }
                        } ?: run {
                            Text(
                                text = "请选择要购买的商品",
                                fontSize = 12.sp,
                                color = GameColors.TextSecondary
                            )
                        }
                    }
                }
            }

            if (showRelationWarning) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x88000000))
                        .clickable { showRelationWarning = false },
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier.padding(32.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xF0333333),
                        tonalElevation = 8.dp
                    ) {
                        Box {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "好感度不足",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFF9800)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                if (!canTrade) {
                                    Text(
                                        text = "与该宗门好感度太低，无法进行交易",
                                        fontSize = 13.sp,
                                        color = Color.White,
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        text = "需要好感度达到40（普通关系）才能解锁交易",
                                        fontSize = 12.sp,
                                        color = Color(0xFFBDBDBD),
                                        textAlign = TextAlign.Center
                                    )
                                } else {
                                    Text(
                                        text = "与该宗门好感度太低，无法购买",
                                        fontSize = 13.sp,
                                        color = Color.White,
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        text = lockedItemName,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = GameColors.GoldDark,
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        text = "需要好感度达到${requiredFavorLevel(lockedItemRarity)}才能购买此品阶物品",
                                        fontSize = 12.sp,
                                        color = Color(0xFFBDBDBD),
                                        textAlign = TextAlign.Center
                                    )
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                Surface(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { showRelationWarning = false },
                                    color = Color(0xFF4CAF50)
                                ) {
                                    Text(
                                        text = "我知道了",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 10.dp)
                                    )
                                }
                            }

                            CloseButton(
                                onClick = { showRelationWarning = false },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = (-4).dp, y = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDetailDialog) {
        selectedItem?.let { item ->
            ItemDetailDialog(
                item = item,
                onDismiss = { showDetailDialog = false }
            )
        }
    }
}
