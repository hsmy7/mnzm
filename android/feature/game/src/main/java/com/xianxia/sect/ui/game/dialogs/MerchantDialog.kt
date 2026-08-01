package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.registry.PillRecipeDatabase
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GameItem
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.core.util.sortedByWatchedThenRarity
import com.xianxia.sect.ui.game.components.watchKeyOf
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.theme.ButtonSizes
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.components.SpriteResRegistry
import com.xianxia.sect.ui.components.StandardPromptDialog
import androidx.compose.foundation.shape.CircleShape

// 提取的子文件：MerchantListingDialog.kt, MerchantInventoryDialog.kt

@Composable
fun MerchantDialog(
    gameData: GameData?,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val merchantItems = gameData?.travelingMerchantItems ?: emptyList()
    var selectedItem by remember { mutableStateOf<MerchantItem?>(null) }
    var buyQuantity by remember { mutableIntStateOf(1) }
    var showDetailDialog by remember { mutableStateOf(false) }
    var showListingDialog by remember { mutableStateOf(false) }
    var showAutoBuyDialog by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf(MerchantFilter.ALL) }
    var merchantMode by remember { mutableStateOf(MerchantMode.BUY) }
    var showSellConfirmDialog by remember { mutableStateOf(false) }
    var selectedAcquisitionItem by remember { mutableStateOf<MerchantItem?>(null) }
    var showAdConfirmDialog by remember { mutableStateOf(false) }
    var showNoChancesDialog by remember { mutableStateOf(false) }
    var showAdCooldownDialog by remember { mutableStateOf(false) }
    var showAdLimitDialog by remember { mutableStateOf(false) }

    val equipment by viewModel.equipmentStacks.collectAsStateWithLifecycle()
    val manuals by viewModel.manualStacks.collectAsStateWithLifecycle()
    val pills by viewModel.pills.collectAsStateWithLifecycle()
    val materials by viewModel.materials.collectAsStateWithLifecycle()
    val herbs by viewModel.herbs.collectAsStateWithLifecycle()
    val seeds by viewModel.seeds.collectAsStateWithLifecycle()

    val acquisitionItems = gameData?.merchantAcquisitionItems ?: emptyList()

    val watchedKeys by viewModel.watchedItemIds.collectAsStateWithLifecycle()

    fun getWarehouseQuantity(item: MerchantItem): Int = when (item.type.lowercase()) {
        "equipment" -> equipment.filter { it.name == item.name && it.rarity == item.rarity }.sumOf { it.quantity }
        "manual" -> manuals.filter { it.name == item.name && it.rarity == item.rarity }.sumOf { it.quantity }
        "pill" -> pills.filter { it.name == item.name && it.rarity == item.rarity && it.grade.displayName == (item.grade ?: "") }.sumOf { it.quantity }
        "material" -> materials.filter { it.name == item.name && it.rarity == item.rarity }.sumOf { it.quantity }
        "herb" -> herbs.filter { it.name == item.name && it.rarity == item.rarity }.sumOf { it.quantity }
        "seed" -> seeds.filter { it.name == item.name && it.rarity == item.rarity }.sumOf { it.quantity }
        else -> 0
    }

    val filteredItems = remember(merchantItems, selectedFilter, watchedKeys) {
        val items = if (selectedFilter == MerchantFilter.ALL) merchantItems
        else merchantItems.filter { it.type == selectedFilter.typeValue }
        items.sortedByWatchedThenRarity(
            watchedKeys,
            keyOf = { watchKeyOf(it) },
            rarityOf = { it.rarity },
            nameOf = { it.name }
        )
    }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "云游商人",
        titleAlignment = Alignment.CenterStart,
        mode = DialogMode.Full,
        scrollableContent = false,
        headerActions = {
            val data = gameData
            val low = GameUtils.formatNumber(data?.spiritStones ?: 0)
            val mid = GameUtils.formatNumber(data?.midGradeSpiritStones ?: 0)
            val high = GameUtils.formatNumber(data?.highGradeSpiritStones ?: 0)
            Text("下品:$low 中品:$mid 上品:$high", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = Color.Black, modifier = Modifier.padding(end = 8.dp))
            GameButton(text = "上架", onClick = { showListingDialog = true })
            GameButton(text = "自动购买", onClick = { showAutoBuyDialog = true })
            Spacer(Modifier.width(4.dp))
            val refreshChances = data?.merchantRefreshChances ?: 0
            GameButton(
                text = "刷新",
                onClick = {
                    if (refreshChances > 0) {
                        viewModel.refreshTravelingMerchantManual()
                    } else {
                        showNoChancesDialog = true
                    }
                }
            )
            Text("${refreshChances}次", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = Color.White, modifier = Modifier.padding(start = 4.dp))
            if (viewModel != null) {
                Image(
                    painter = painterResource(id = SpriteResRegistry.resolve("ui_play_button") ?: 0),
                    contentDescription = "播放广告获得刷新次数",
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .clickable {
                            if (viewModel.isDailyAdLimitReached()) {
                                showAdLimitDialog = true
                            } else if (viewModel.isAdOnCooldown()) {
                                showAdCooldownDialog = true
                            } else {
                                showAdConfirmDialog = true
                            }
                        },
                    contentScale = ContentScale.FillBounds
                )
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 购买/收购 标签切换
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                MerchantMode.entries.forEach { mode ->
                    val isActive = merchantMode == mode
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f).clickable {
                            merchantMode = mode; selectedItem = null; selectedAcquisitionItem = null; buyQuantity = 1
                        }) {
                        Text(mode.displayName, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            color = if (isActive) Color.Black else Color.Gray)
                        Box(Modifier.fillMaxWidth().height(2.dp).background(if (isActive) GameColors.GoldDark else Color.Gray))
                    }
                }
            }

            when (merchantMode) {
                MerchantMode.BUY -> {
                    if (merchantItems.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("商人正在旅途中...\n请稍后再来", fontSize = 12.sp, color = GameColors.TextSecondary, textAlign = TextAlign.Center)
                        }
                    } else {
                        Column(Modifier.weight(1f)) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                MerchantFilter.entries.forEach { filter ->
                                    ListingFilterButton(text = filter.displayName, selected = selectedFilter == filter,
                                        onClick = { selectedFilter = filter; selectedItem = null; buyQuantity = 1 })
                                }
                            }
                            if (filteredItems.isEmpty()) {
                                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Text("该分类暂无物品", fontSize = 12.sp, color = GameColors.TextSecondary, textAlign = TextAlign.Center)
                                }
                            } else {
                                LazyVerticalGrid(columns = GridCells.Adaptive(60.dp),
                                    modifier = Modifier.weight(1f).padding(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(filteredItems, key = { it.id }, contentType = { "merchant_item" }) { item ->
                                        UnifiedItemCard(data = ItemCardData(id = item.id, name = item.name, rarity = item.rarity,
                                            quantity = item.quantity, additionalInfo = "${GameUtils.formatNumber(item.price)}灵石",
                                            grade = item.grade, isManual = item.type == "manual", isPill = item.type == "pill",
                                            isHerb = item.type == "herb", isSeed = item.type == "seed", isMaterial = item.type == "material"),
                                            isSelected = selectedItem?.id == item.id,
                                            isFollowed = watchKeyOf(item)?.let { it in watchedKeys } ?: false,
                                            onClick = { if (selectedItem?.id == item.id) { selectedItem = null; buyQuantity = 1 } else { selectedItem = item; buyQuantity = 1 } },
                                            onLongPress = { selectedItem = item; showDetailDialog = true })
                                    }
                                }
                            }
                        }
                    }
                    PurchasePanel(item = selectedItem, quantity = buyQuantity, maxQuantity = selectedItem?.quantity ?: 1,
                        spiritStones = gameData?.spiritStones ?: 0,
                        onQuantityChange = { qty -> selectedItem?.let { buyQuantity = qty.coerceIn(1, it.quantity) } },
                        onConfirm = { selectedItem?.let { viewModel.buyFromMerchant(it.id, buyQuantity); selectedItem = null; buyQuantity = 1 } },
                        onCancel = { selectedItem = null; buyQuantity = 1 })
                }

                MerchantMode.ACQUISITION -> {
                    val sortedAcquisitionItems = remember(acquisitionItems, watchedKeys) {
                        acquisitionItems.sortedByWatchedThenRarity(
                            watchedKeys,
                            keyOf = { watchKeyOf(it) },
                            rarityOf = { it.rarity },
                            nameOf = { it.name }
                        )
                    }
                    if (sortedAcquisitionItems.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("商人暂无收购需求\n请明年再来", fontSize = 12.sp, color = GameColors.TextSecondary, textAlign = TextAlign.Center)
                        }
                    } else {
                        Column(Modifier.weight(1f)) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("物品", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.weight(1.3f))
                                Text("收购数量", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                                Text("收购价格", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                                Text("出售", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                            }
                            HorizontalDivider(thickness = 1.dp, color = Color(0xFFBDBDBD))
                            LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(sortedAcquisitionItems, key = { it.id }, contentType = { "merchant_item" }) { item ->
                                    val warehouseQty = getWarehouseQuantity(item)
                                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.weight(1.3f)) {
                                            UnifiedItemCard(data = ItemCardData(id = item.id, name = item.name, rarity = item.rarity,
                                                quantity = item.quantity, additionalInfo = "${GameUtils.formatNumber(item.price)}灵石",
                                                grade = item.grade, isManual = item.type == "manual", isPill = item.type == "pill",
                                                isHerb = item.type == "herb", isSeed = item.type == "seed", isMaterial = item.type == "material"),
                                                isSelected = false,
                                                isFollowed = watchKeyOf(item)?.let { it in watchedKeys } ?: false,
                                                onClick = { if (item.quantity > 0 && warehouseQty > 0) { selectedAcquisitionItem = item; showSellConfirmDialog = true } },
                                                onLongPress = { selectedItem = item; showDetailDialog = true })
                                        }
                                        Text(GameUtils.formatNumber(item.quantity), fontSize = 11.sp, color = Color.Black, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                                        Text(GameUtils.formatNumber(item.price), fontSize = 11.sp, color = GameColors.GoldDark, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                            when {
                                                item.quantity == 0 -> Text("不再收购", color = Color.Red, fontSize = 10.sp)
                                                warehouseQty == 0 -> GameButton(text = "出售", onClick = {}, enabled = false)
                                                else -> GameButton(text = "出售", onClick = { selectedAcquisitionItem = item; showSellConfirmDialog = true })
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDetailDialog) {
        selectedItem?.let { item ->
            com.xianxia.sect.ui.game.components.ItemDetailDialog(
                item = item,
                onDismiss = { showDetailDialog = false },
                viewModel = viewModel
            )
        }
    }
    if (showListingDialog) {
        ListingManagementDialog(gameData = gameData, viewModel = viewModel, onDismiss = { showListingDialog = false })
    }
    if (showSellConfirmDialog) {
        selectedAcquisitionItem?.let { item ->
            val warehouseQty = getWarehouseQuantity(item)
            AcquisitionSellConfirmDialog(item = item, warehouseQuantity = warehouseQty,
                onConfirm = { quantity -> viewModel.sellToMerchant(item.id, quantity); showSellConfirmDialog = false; selectedAcquisitionItem = null },
                onDismiss = { showSellConfirmDialog = false; selectedAcquisitionItem = null })
        }
    }
    if (showAutoBuyDialog) {
        AutoBuyDialog(gameData = gameData, viewModel = viewModel, onDismiss = { showAutoBuyDialog = false })
    }

    // ── 广告相关对话框（StandardPromptDialog） ─────────────────────────

    if (showAdConfirmDialog) {
        StandardPromptDialog(
            onDismissRequest = { showAdConfirmDialog = false },
            title = "获得刷新次数",
            text = "观看广告获得刷新次数，最多观看20次广告。",
            dismissLabel = "取消",
            confirmLabel = "观看",
            onConfirm = {
                showAdConfirmDialog = false
                viewModel.watchAdForMerchantRefresh()
            }
        )
    }

    if (showNoChancesDialog) {
        StandardPromptDialog(
            onDismissRequest = { showNoChancesDialog = false },
            title = "无刷新次数",
            text = "已无刷新次数可通过观看广告获得",
            confirmLabel = "知道了",
            onConfirm = { showNoChancesDialog = false }
        )
    }

    if (showAdCooldownDialog) {
        StandardPromptDialog(
            onDismissRequest = { showAdCooldownDialog = false },
            title = "不可播放广告",
            text = "一分钟内只可观看一次广告",
            confirmLabel = "确认",
            onConfirm = { showAdCooldownDialog = false }
        )
    }

    if (showAdLimitDialog) {
        StandardPromptDialog(
            onDismissRequest = { showAdLimitDialog = false },
            title = "提示",
            text = "观看次数已达上限",
            confirmLabel = "知道了",
            onConfirm = { showAdLimitDialog = false }
        )
    }
}

@Composable
private fun PurchasePanel(
    item: MerchantItem?, quantity: Int, maxQuantity: Int, spiritStones: Long,
    onQuantityChange: (Int) -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit
) {
    if (item == null) return
    val totalPrice = item.price * quantity
    val canAfford = spiritStones >= totalPrice
    Surface(modifier = Modifier.fillMaxWidth(), color = GameColors.PageBackground, tonalElevation = 4.dp) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(item.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = GameColors.TextPrimary)
                    Text("单价: ${GameUtils.formatNumber(item.price)} 灵石", fontSize = 10.sp, color = GameColors.TextSecondary)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("购买数量:", fontSize = 11.sp, color = GameColors.TextSecondary)
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(4.dp)).background(GameColors.Background).clickable { onQuantityChange(quantity - 1) },
                        contentAlignment = Alignment.Center) { Text("-", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GameColors.TextPrimary) }
                    Text("$quantity", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = GameColors.TextPrimary, modifier = Modifier.widthIn(min = 24.dp), textAlign = TextAlign.Center)
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(4.dp)).background(GameColors.Background).clickable { onQuantityChange(quantity + 1) },
                        contentAlignment = Alignment.Center) { Text("+", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GameColors.TextPrimary) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("总价: ${GameUtils.formatNumber(totalPrice)} 灵石", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = if (canAfford) GameColors.GoldDark else Color.Red)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GameButton(text = "取消", onClick = onCancel)
                    GameButton(text = "确认购买", onClick = onConfirm, enabled = canAfford && quantity > 0)
                }
            }
        }
    }
}

@Composable
private fun AcquisitionSellConfirmDialog(
    item: MerchantItem, warehouseQuantity: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit
) {
    val maxSellable = minOf(warehouseQuantity, item.quantity)
    var sellQuantity by remember { mutableIntStateOf(1) }
    val totalPrice = item.price * sellQuantity
    UnifiedGameDialog(onDismissRequest = onDismiss, title = "出售确认", mode = DialogMode.Half) {
        Column(Modifier.padding(20.dp)) {
            Text(item.name, fontWeight = FontWeight.Bold, color = com.xianxia.sect.ui.theme.getRarityColor(item.rarity), fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            Text("仓库拥有: ${GameUtils.formatNumber(warehouseQuantity)} 个", color = Color.Black, fontSize = 12.sp)
            Text("商人收购: 最多 ${GameUtils.formatNumber(item.quantity)} 个", color = Color.Black, fontSize = 12.sp)
            Text("最大可售: ${GameUtils.formatNumber(maxSellable)} 个", color = Color.Black, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("出售数量:", color = Color.Black, fontSize = 12.sp)
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(4.dp)).background(GameColors.Background).clickable { sellQuantity = (sellQuantity - 1).coerceAtLeast(1) },
                    contentAlignment = Alignment.Center) { Text("-", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GameColors.TextPrimary) }
                Text("$sellQuantity", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = GameColors.TextPrimary, modifier = Modifier.widthIn(min = 24.dp), textAlign = TextAlign.Center)
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(4.dp)).background(GameColors.Background).clickable { sellQuantity = (sellQuantity + 1).coerceAtMost(maxSellable) },
                    contentAlignment = Alignment.Center) { Text("+", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GameColors.TextPrimary) }
            }
            Spacer(Modifier.height(8.dp))
            Text("总价: ${GameUtils.formatNumber(totalPrice)} 灵石", color = GameColors.GoldDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GameButton(text = "取消", onClick = onDismiss)
                GameButton(text = "确认出售", onClick = { onConfirm(sellQuantity) }, enabled = sellQuantity > 0 && maxSellable > 0)
            }
        }
    }
}

private fun getRarityColor(rarity: Int): Color = com.xianxia.sect.ui.theme.getRarityColor(rarity)

private fun getRarityName(rarity: Int): String = when (rarity) {
    1 -> "凡品"; 2 -> "灵品"; 3 -> "宝品"; 4 -> "玄品"; 5 -> "地品"; 6 -> "天品"; else -> "凡品"
}
