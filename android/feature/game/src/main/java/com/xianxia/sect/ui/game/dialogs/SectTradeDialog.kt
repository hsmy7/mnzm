package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.domain.FavorDomain
import com.xianxia.sect.core.model.SectRelationLevel
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.ui.components.ItemCardData
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xianxia.sect.core.util.sortedByWatchedThenRarity
import com.xianxia.sect.ui.game.components.watchKeyOf
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.WorldMapInteractionViewModel
import com.xianxia.sect.ui.game.components.ItemDetailDialog
import com.xianxia.sect.ui.theme.GameColors
import androidx.compose.ui.platform.LocalLocale

/** 宗门交易对话框 UI 状态（SectTradeDialog 拆分） */
private class SectTradeDialogState {
    var selectedItem by mutableStateOf<MerchantItem?>(null)
    var buyQuantity by mutableIntStateOf(1)
    var showDetailDialog by mutableStateOf(false)
    var showRelationWarning by mutableStateOf(false)
    var lockedItemName by mutableStateOf("")
    var lockedItemRarity by mutableIntStateOf(1)
}

/** 宗门关系信息（SectTradeDialog 拆分） */
private data class SectTradeRelationInfo(
    val relation: Int,
    val isAlly: Boolean,
    val relationLevel: SectRelationLevel,
    val maxAllowedRarity: Int,
    val priceMultiplier: Double,
    val relationColor: Color,
    val canTrade: Boolean
)

@Composable
fun SectTradeDialog(
    sect: WorldSect?,
    gameData: GameData?,
    tradeItems: List<MerchantItem>,
    viewModel: GameViewModel,
    interactionViewModel: WorldMapInteractionViewModel,
    onDismiss: () -> Unit
) {
    val state = remember { SectTradeDialogState() }

    LaunchedEffect(tradeItems) {
        val currentId = state.selectedItem?.id
        if (currentId != null) {
            val updated = tradeItems.find { it.id == currentId }
            state.selectedItem = updated
        }
    }

    val watchedKeys by viewModel.watchedItemIds.collectAsStateWithLifecycle()
    val sortedTradeItems = remember(tradeItems, watchedKeys) {
        // id 去重兜底：损坏存档可能出现重复/空 id 商品，
        // 防 LazyVerticalGrid key="" 重复崩溃（Bugly #5079/#3091）
        tradeItems.distinctBy { it.id }.sortedByWatchedThenRarity(
            watchedKeys,
            keyOf = { watchKeyOf(it) },
            rarityOf = { it.rarity },
            nameOf = { it.name }
        )
    }

    val relationInfo = sectTradeRelationInfo(
        sect = sect,
        gameData = gameData,
        interactionViewModel = interactionViewModel
    )

    UnifiedGameDialog(onDismissRequest = onDismiss, title = "宗门交易", mode = DialogMode.Full, scrollableContent = false) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                SectTradeRelationHeader(relationInfo = relationInfo)

                SectTradeSpiritStonesLine(gameData = gameData)

                SectTradeItemGrid(
                    state = state,
                    tradeItems = tradeItems,
                    sortedTradeItems = sortedTradeItems,
                    relationInfo = relationInfo,
                    watchedKeys = watchedKeys
                )

                SectTradePurchasePanel(
                    state = state,
                    relationInfo = relationInfo,
                    gameData = gameData,
                    interactionViewModel = interactionViewModel
                )
            }

            SectTradeRelationWarning(
                state = state,
                canTrade = relationInfo.canTrade,
                onDismiss = { state.showRelationWarning = false }
            )
        }
    }

    SectTradeDetailDialogGate(
        state = state,
        viewModel = viewModel
    )
}

/** 宗门关系信息计算（SectTradeDialog 拆分）：好感度/盟友/可交易品阶/价格倍率 */
private fun sectTradeRelationInfo(
    sect: WorldSect?,
    gameData: GameData?,
    interactionViewModel: WorldMapInteractionViewModel
): SectTradeRelationInfo {
    val playerSect = gameData?.worldMapSects?.find { it.isPlayerSect }
    val relation = if (playerSect != null && sect != null) {
        FavorDomain.findFavor(gameData?.sectRelations ?: emptyList(), playerSect.id, sect.id)
    } else 0
    val isAlly = sect?.let { interactionViewModel.isAlly(it.id) } ?: false

    val relationLevel = FavorDomain.getLevel(relation)
    val maxAllowedRarity = relationLevel.maxAllowedRarity

    val priceMultiplier = if (playerSect != null && gameData != null && sect != null) {
        FavorDomain.calculateTradePriceMultiplier(gameData.sectRelations, gameData.alliances, sect.id, playerSect.id)
    } else 1.0

    val relationColor = Color(relationLevel.colorHex)

    val canTrade = relationLevel in listOf(SectRelationLevel.NORMAL, SectRelationLevel.FRIENDLY, SectRelationLevel.INTIMATE)
    return SectTradeRelationInfo(
        relation = relation,
        isAlly = isAlly,
        relationLevel = relationLevel,
        maxAllowedRarity = maxAllowedRarity,
        priceMultiplier = priceMultiplier,
        relationColor = relationColor,
        canTrade = canTrade
    )
}

/** 购买品阶所需好感度文案（SectTradeDialog 拆分） */
private fun requiredFavorLevel(rarity: Int): String = when {
    rarity <= 2 -> "40（普通关系）"
    rarity <= 4 -> "60（友善关系）"
    else -> "80（至交关系）"
}

/** 关系信息标题行（SectTradeDialog 拆分） */
@Composable
private fun SectTradeRelationHeader(relationInfo: SectTradeRelationInfo) {
    val discountPercent = (1 - relationInfo.priceMultiplier) * 100
    val discountText = "(${String.format(LocalLocale.current.platformLocale, "%.1f%%", discountPercent)}折扣)"
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
            text = relationInfo.relationLevel.displayName,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = relationInfo.relationColor
        )
        Text(
            text = "(${relationInfo.relation})",
            fontSize = 11.sp,
            color = GameColors.TextSecondary
        )
        if (relationInfo.isAlly) {
            Text(
                text = "(盟友)",
                fontSize = 10.sp,
                color = GameColors.Success,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = discountText,
                fontSize = 10.sp,
                color = GameColors.Success
            )
        } else if (relationInfo.relation >= 70) {
            Text(
                text = discountText,
                fontSize = 10.sp,
                color = GameColors.Success
            )
        } else if (!relationInfo.canTrade) {
            Text(
                text = "(关系不足，无法交易)",
                fontSize = 10.sp,
                color = GameColors.Error
            )
        }
    }
}

/** 灵石余额行（SectTradeDialog 拆分） */
@Composable
private fun SectTradeSpiritStonesLine(gameData: GameData?) {
    Text(
        text = "下品:${GameUtils.formatNumber(gameData?.spiritStones ?: 0)} 中品:${GameUtils.formatNumber(gameData?.midGradeSpiritStones ?: 0)} 上品:${GameUtils.formatNumber(gameData?.highGradeSpiritStones ?: 0)}",
        fontSize = 11.sp,
        color = GameColors.TextSecondary,
        modifier = Modifier.padding(horizontal = 12.dp)
    )
}

/** 商品网格（SectTradeDialog 拆分）：空态提示 + 商品卡片列表 */
@Composable
private fun ColumnScope.SectTradeItemGrid(
    state: SectTradeDialogState,
    tradeItems: List<MerchantItem>,
    sortedTradeItems: List<MerchantItem>,
    relationInfo: SectTradeRelationInfo,
    watchedKeys: Set<String>
) {
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
            items(sortedTradeItems, key = { it.id }, contentType = { "merchant_item" }) { item ->
                val canBuyThisItem = relationInfo.canTrade && item.rarity <= relationInfo.maxAllowedRarity
                val adjustedPrice = (item.price * relationInfo.priceMultiplier).toLong()
                SectTradeItemCard(
                    item = item,
                    canBuyThisItem = canBuyThisItem,
                    adjustedPrice = adjustedPrice,
                    selectedItem = state.selectedItem,
                    watchedKeys = watchedKeys,
                    onClick = {
                        if (!canBuyThisItem) {
                            state.lockedItemName = item.name
                            state.lockedItemRarity = item.rarity
                            state.showRelationWarning = true
                        } else if (state.selectedItem?.id == item.id) {
                            state.selectedItem = null
                            state.buyQuantity = 1
                        } else {
                            state.selectedItem = item
                            state.buyQuantity = 1
                        }
                    },
                    onLongPress = {
                        state.selectedItem = item
                        state.showDetailDialog = true
                    }
                )
            }
        }
    }
}

/** 商品卡片（SectTradeDialog 拆分）：锁定/关注/选中态 */
@Composable
private fun SectTradeItemCard(
    item: MerchantItem,
    canBuyThisItem: Boolean,
    adjustedPrice: Long,
    selectedItem: MerchantItem?,
    watchedKeys: Set<String>,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    UnifiedItemCard(
        data = ItemCardData(
            id = item.id,
            name = item.name,
            description = item.description,
            rarity = item.rarity,
            quantity = item.quantity,
            additionalInfo = "${GameUtils.formatNumber(adjustedPrice)}灵石",
            grade = item.grade,
            isLocked = !canBuyThisItem,
            isManual = item.type == "manual",
            isPill = item.type == "pill",
            isHerb = item.type == "herb",
            isSeed = item.type == "seed",
            isMaterial = item.type == "material"
        ),
        isSelected = selectedItem?.id == item.id,
        isFollowed = watchKeyOf(item)?.let { it in watchedKeys } ?: false,
        onClick = onClick,
        onLongPress = onLongPress
    )
}

/** 购买面板（SectTradeDialog 拆分）：已选商品信息 + 数量 + 总价 + 购买 */
@Composable
private fun SectTradePurchasePanel(
    state: SectTradeDialogState,
    relationInfo: SectTradeRelationInfo,
    gameData: GameData?,
    interactionViewModel: WorldMapInteractionViewModel
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            state.selectedItem?.let { item ->
                val adjustedPrice = (item.price * relationInfo.priceMultiplier).toLong()
                val totalPrice = adjustedPrice * state.buyQuantity
                val canAfford = (gameData?.spiritStones ?: 0L) >= totalPrice

                SectTradeSelectedItemInfo(item = item, adjustedPrice = adjustedPrice)

                SectTradeQuantityStepper(
                    buyQuantity = state.buyQuantity,
                    onDecrease = { state.buyQuantity = (state.buyQuantity - 1).coerceAtLeast(1) },
                    onIncrease = { state.buyQuantity = (state.buyQuantity + 1).coerceAtMost(item.quantity) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                SectTradeTotalRow(
                    totalPrice = totalPrice,
                    canAfford = canAfford,
                    buyQuantity = state.buyQuantity,
                    onCancel = {
                        state.selectedItem = null
                        state.buyQuantity = 1
                    },
                    onConfirm = {
                        interactionViewModel.buyFromSectTrade(item.id, state.buyQuantity)
                        state.buyQuantity = 1
                    }
                )
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

/** 已选商品信息（SectTradeDialog 拆分）：名称 + 单价 */
@Composable
private fun SectTradeSelectedItemInfo(
    item: MerchantItem,
    adjustedPrice: Long
) {
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
                text = "单价: ${GameUtils.formatNumber(adjustedPrice)} 灵石",
                fontSize = 10.sp,
                color = GameColors.TextSecondary
            )
        }
    }
}

/** 购买数量步进器（SectTradeDialog 拆分）：- 数量 + */
@Composable
private fun SectTradeQuantityStepper(
    buyQuantity: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
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
                .clickable(onClick = onDecrease),
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
                .clickable(onClick = onIncrease),
            contentAlignment = Alignment.Center
        ) {
            Text("+", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GameColors.TextPrimary)
        }
    }
}

/** 总价 + 取消/确认购买（SectTradeDialog 拆分） */
@Composable
private fun SectTradeTotalRow(
    totalPrice: Long,
    canAfford: Boolean,
    buyQuantity: Int,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "总价: ${GameUtils.formatNumber(totalPrice)} 灵石",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (canAfford) GameColors.GoldDark else Color.Red
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GameButton(
                text = "取消",
                onClick = onCancel
            )

            GameButton(
                text = "确认购买",
                onClick = onConfirm,
                enabled = canAfford && buyQuantity > 0
            )
        }
    }
}

/** 好感度不足警告覆盖层（SectTradeDialog 拆分） */
@Composable
private fun SectTradeRelationWarning(
    state: SectTradeDialogState,
    canTrade: Boolean,
    onDismiss: () -> Unit
) {
    if (state.showRelationWarning) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(onClick = onDismiss),
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
                        SectTradeRelationWarningContent(
                            canTrade = canTrade,
                            lockedItemName = state.lockedItemName,
                            lockedItemRarity = state.lockedItemRarity,
                            onDismiss = onDismiss
                        )
                    }
                    CloseButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = (-4).dp, y = 4.dp)
                    )
                }
            }
        }
    }
}

/** 好感度不足警告内容（SectTradeDialog 拆分） */
@Composable
private fun SectTradeRelationWarningContent(
    canTrade: Boolean,
    lockedItemName: String,
    lockedItemRarity: Int,
    onDismiss: () -> Unit
) {
    Text(
        text = "好感度不足",
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = GameColors.Warning
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
            color = GameColors.ButtonDisabled,
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
            color = GameColors.ButtonDisabled,
            textAlign = TextAlign.Center
        )
    }
    Spacer(modifier = Modifier.height(16.dp))
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onDismiss),
        color = GameColors.Success
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

/** 商品详情弹窗（SectTradeDialog 拆分） */
@Composable
private fun SectTradeDetailDialogGate(
    state: SectTradeDialogState,
    viewModel: GameViewModel
) {
    if (state.showDetailDialog) {
        state.selectedItem?.let { item ->
            ItemDetailDialog(
                item = item,
                onDismiss = { state.showDetailDialog = false },
                viewModel = viewModel
            )
        }
    }
}
