@file:Suppress("TooManyFunctions") // 私有辅助函数集中在本文件
package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xianxia.sect.core.engine.MerchantRefreshResult
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.core.util.sortedByWatchedThenRarity
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.SpriteImage
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.components.JadePurchaseFlow
import com.xianxia.sect.ui.game.components.JadePurchaseOutcome
import com.xianxia.sect.ui.game.components.QuantitySelector
import com.xianxia.sect.ui.game.components.QuantitySelectorSizes
import com.xianxia.sect.ui.game.components.QUANTITY_MIN
import com.xianxia.sect.ui.game.components.watchKeyOf
import com.xianxia.sect.ui.theme.GameColors

// 提取的子文件：MerchantListingDialog.kt, MerchantInventoryDialog.kt

/** 商人紧凑布局尺寸（28dp 按钮，匹配既有视觉） */
private val merchantQuantitySizes = QuantitySelectorSizes(
    buttonSize = 28.dp,
    numberBoxWidth = 56.dp,
    numberBoxHeight = 28.dp,
    buttonCornerRadius = 4.dp,
    buttonFontSize = 14.sp,
)

/** 云游商人对话框状态 */
private class MerchantDialogState {
    var selectedItem by mutableStateOf<MerchantItem?>(null)
    var buyQuantity by mutableIntStateOf(1)
    var showDetailDialog by mutableStateOf(false)
    var showListingDialog by mutableStateOf(false)
    var showAutoBuyDialog by mutableStateOf(false)
    var selectedFilter by mutableStateOf(MerchantFilter.ALL)
    var merchantMode by mutableStateOf(MerchantMode.BUY)
    var showSellConfirmDialog by mutableStateOf(false)
    var selectedAcquisitionItem by mutableStateOf<MerchantItem?>(null)
    var showJadeDialog by mutableStateOf(false)
    var showNoChancesDialog by mutableStateOf(false)

    /** 清空购买面板选中（刷新/切换筛选/购买完成后复用） */
    fun clearBuySelection() {
        selectedItem = null
        buyQuantity = 1
    }

    /** 清空购买 + 收购两侧选中（切换 Tab 时复用） */
    fun clearAllSelection() {
        selectedItem = null
        selectedAcquisitionItem = null
        buyQuantity = 1
    }

    /** 商品点击切换选中（同商品再次点击取消选中） */
    fun toggleBuyItem(item: MerchantItem) {
        if (selectedItem?.id == item.id) {
            clearBuySelection()
        } else {
            selectedItem = item
            buyQuantity = 1
        }
    }
}

/** 商人购买面板参数打包（MerchantDialog 拆分，参数 >8 规避 LongParameterList） */
private data class MerchantBuyPanelParams(
    val merchantItems: List<MerchantItem>,
    val filteredItems: List<MerchantItem>,
    val selectedFilter: MerchantFilter,
    val selectedItem: MerchantItem?,
    val buyQuantity: Int,
    val spiritStones: Long,
    val watchedKeys: Set<String>
)

/** 商人收购面板参数打包（MerchantDialog 拆分，参数 >8 规避 LongParameterList） */
private data class MerchantAcquisitionPanelParams(
    val acquisitionItems: List<MerchantItem>,
    val watchedKeys: Set<String>
)

@Composable
fun MerchantDialog(
    gameData: GameData?,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val state = remember { MerchantDialogState() }
    val merchantItems = gameData?.travelingMerchantItems ?: emptyList()
    val acquisitionItems = gameData?.merchantAcquisitionItems ?: emptyList()
    val equipment by viewModel.equipmentStacks.collectAsStateWithLifecycle()
    val manuals by viewModel.manualStacks.collectAsStateWithLifecycle()
    val pills by viewModel.pills.collectAsStateWithLifecycle()
    val materials by viewModel.materials.collectAsStateWithLifecycle()
    val herbs by viewModel.herbs.collectAsStateWithLifecycle()
    val seeds by viewModel.seeds.collectAsStateWithLifecycle()
    val watchedKeys by viewModel.watchedItemIds.collectAsStateWithLifecycle()
    val warehouseQuantityOf = { item: MerchantItem ->
        merchantWarehouseQuantity(item, equipment, manuals, pills, materials, herbs, seeds)
    }
    val filteredItems = remember(merchantItems, state.selectedFilter, watchedKeys) {
        sortMerchantItems(merchantItems, state.selectedFilter, watchedKeys)
    }
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "云游商人",
        titleAlignment = Alignment.CenterStart,
        mode = DialogMode.Full,
        scrollableContent = false,
        // 含购买数量常驻输入框：冻结宿主窗口系统栏操作，避免键盘弹出时部分机型频闪
        freezeSystemBars = true,
        // 玉符购买弹窗渲染在窗口级 overlay 槽位（content 列内渲染会被挤压为 0 高度，
        // 57352e02 兑换码事故同源回归机制，见 StandardPromptDialogTest 0 高度用例）
        overlay = {
            MerchantJadeOverlay(
                show = state.showJadeDialog,
                jadeSymbols = gameData?.jadeSymbols ?: 0,
                onPurchase = { merchantRefreshPurchaseOutcome(viewModel.merchant.purchaseMerchantRefresh()) },
                onDismiss = { state.showJadeDialog = false }
            )
        },
        headerActions = {
            MerchantHeaderActions(
                gameData = gameData, viewModel = viewModel,
                onOpenListing = { state.showListingDialog = true },
                onOpenAutoBuy = { state.showAutoBuyDialog = true },
                onRefreshSuccess = { state.clearBuySelection() },
                onNoChances = { state.showNoChancesDialog = true },
                onOpenJade = { state.showJadeDialog = true }
            )
        }
    ) {
        MerchantModeContent(
            state = state, viewModel = viewModel, gameData = gameData,
            merchantItems = merchantItems, filteredItems = filteredItems,
            acquisitionItems = acquisitionItems, watchedKeys = watchedKeys,
            warehouseQuantityOf = warehouseQuantityOf
        )
    }
    MerchantSubDialogs(
        state = state, viewModel = viewModel,
        gameData = gameData, warehouseQuantityOf = warehouseQuantityOf
    )
}

/** 商人商品排序：筛选 + id 去重 + 已关注优先 */
private fun sortMerchantItems(
    merchantItems: List<MerchantItem>,
    selectedFilter: MerchantFilter,
    watchedKeys: Set<String>
): List<MerchantItem> {
    val items = if (selectedFilter == MerchantFilter.ALL) merchantItems
    else merchantItems.filter { it.type == selectedFilter.typeValue }
    // id 去重兜底：损坏存档可能出现重复/空 id 商品，
    // 防 LazyVerticalGrid key="" 重复崩溃（Bugly #5079/#3091）
    return items.distinctBy { it.id }.sortedByWatchedThenRarity(
        watchedKeys,
        keyOf = { watchKeyOf(it) },
        rarityOf = { it.rarity },
        nameOf = { it.name }
    )
}

/** 商人商品仓库持有量：按类型统计同名同稀有度数量 */
@Suppress("CyclomaticComplexMethod")
private fun merchantWarehouseQuantity(
    item: MerchantItem,
    equipment: List<EquipmentStack>,
    manuals: List<ManualStack>,
    pills: List<Pill>,
    materials: List<Material>,
    herbs: List<Herb>,
    seeds: List<Seed>
): Int = when (item.type.lowercase()) {
    "equipment" -> equipment.filter { it.name == item.name && it.rarity == item.rarity }.sumOf { it.quantity }
    "manual" -> manuals.filter { it.name == item.name && it.rarity == item.rarity }.sumOf { it.quantity }
    "pill" -> pills.filter { it.name == item.name && it.rarity == item.rarity && it.grade.displayName == (item
        .grade ?: "") }.sumOf { it.quantity }
    "material" -> materials.filter { it.name == item.name && it.rarity == item.rarity }.sumOf { it.quantity }
    "herb" -> herbs.filter { it.name == item.name && it.rarity == item.rarity }.sumOf { it.quantity }
    "seed" -> seeds.filter { it.name == item.name && it.rarity == item.rarity }.sumOf { it.quantity }
    else -> 0
}

/** 刷新次数购买结果转换 */
private fun merchantRefreshPurchaseOutcome(result: MerchantRefreshResult): JadePurchaseOutcome = when (result) {
    is MerchantRefreshResult.Success -> JadePurchaseOutcome.Success
    is MerchantRefreshResult.InsufficientJadeSymbols -> JadePurchaseOutcome.Insufficient
    is MerchantRefreshResult.LimitReached -> JadePurchaseOutcome.Success
    is MerchantRefreshResult.Error -> JadePurchaseOutcome.Failed("获取失败，请重试")
}

/** 玉符购买弹窗覆盖层：窗口级 overlay 槽位渲染 */
@Composable
private fun MerchantJadeOverlay(
    show: Boolean,
    jadeSymbols: Int,
    onPurchase: suspend () -> JadePurchaseOutcome,
    onDismiss: () -> Unit
) {
    if (show) {
        JadePurchaseFlow(
            title = "获取刷新次数",
            description = "消耗1玉符获取3次刷新次数",
            jadeSymbols = jadeSymbols,
            insufficientText = "玉符不足，无法获取刷新次数",
            purchase = onPurchase,
            onDismiss = onDismiss
        )
    }
}

/** 商人标题栏动作：灵石栏 + 上架/自动购买/刷新/玉符入口 */
@Composable
private fun MerchantHeaderActions(
    gameData: GameData?,
    viewModel: GameViewModel,
    onOpenListing: () -> Unit,
    onOpenAutoBuy: () -> Unit,
    onRefreshSuccess: () -> Unit,
    onNoChances: () -> Unit,
    onOpenJade: () -> Unit
) {
    val low = GameUtils.formatNumber(gameData?.spiritStones ?: 0)
    val mid = GameUtils.formatNumber(gameData?.midGradeSpiritStones ?: 0)
    val high = GameUtils.formatNumber(gameData?.highGradeSpiritStones ?: 0)
    Text("下品:$low 中品:$mid 上品:$high", fontSize = 12.sp, fontWeight = FontWeight.Bold,
        color = Color.Black, modifier = Modifier.padding(end = 8.dp))
    GameButton(text = "上架", onClick = onOpenListing)
    GameButton(text = "自动购买", onClick = onOpenAutoBuy)
    Spacer(Modifier.width(4.dp))
    val refreshChances = gameData?.merchantRefreshChances ?: 0
    GameButton(
        text = "刷新",
        onClick = {
            if (refreshChances > 0) {
                viewModel.merchant.refreshTravelingMerchantManual()
                // 刷新后商品可能被替换/变价,清空失效选中(对齐切 Tab/切筛选先例)
                onRefreshSuccess()
            } else {
                onNoChances()
            }
        }
    )
    Text("${refreshChances}次", fontSize = 12.sp, fontWeight = FontWeight.Bold,
        color = Color.White, modifier = Modifier.padding(start = 4.dp))
    SpriteImage(
        name = "ui_add_button",
        contentDescription = "获取刷新次数",
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .clickable { onOpenJade() },
        contentScale = ContentScale.FillBounds
    )
}

/** 购买/收购模式主体：标签切换 + 模式分支 */
@Suppress("LongParameterList")
@Composable
private fun MerchantModeContent(
    state: MerchantDialogState,
    viewModel: GameViewModel,
    gameData: GameData?,
    merchantItems: List<MerchantItem>,
    filteredItems: List<MerchantItem>,
    acquisitionItems: List<MerchantItem>,
    watchedKeys: Set<String>,
    warehouseQuantityOf: (MerchantItem) -> Int
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 购买/收购 标签切换
        MerchantModeTabs(
            merchantMode = state.merchantMode,
            onModeSelect = { mode ->
                state.merchantMode = mode
                state.clearAllSelection()
            }
        )

        when (state.merchantMode) {
            MerchantMode.BUY -> MerchantBuyMode(
                params = MerchantBuyPanelParams(
                    merchantItems = merchantItems,
                    filteredItems = filteredItems,
                    selectedFilter = state.selectedFilter,
                    selectedItem = state.selectedItem,
                    buyQuantity = state.buyQuantity,
                    spiritStones = gameData?.spiritStones ?: 0,
                    watchedKeys = watchedKeys
                ),
                onFilterSelect = { filter -> state.selectedFilter = filter; state.clearBuySelection() },
                onItemClick = { item -> state.toggleBuyItem(item) },
                onItemLongPress = { item ->
                    state.selectedItem = item
                    state.showDetailDialog = true
                },
                onQuantityChange = { qty ->
                    state.selectedItem?.let { state.buyQuantity = qty.coerceAtLeast(QUANTITY_MIN) }
                },
                onConfirm = {
                    state.selectedItem?.let {
                        viewModel.inventory.buyFromMerchant(it.id, state.buyQuantity)
                        state.clearBuySelection()
                    }
                },
                onCancel = { state.clearBuySelection() }
            )

            MerchantMode.ACQUISITION -> MerchantAcquisitionMode(
                params = MerchantAcquisitionPanelParams(
                    acquisitionItems = acquisitionItems,
                    watchedKeys = watchedKeys
                ),
                warehouseQuantityOf = warehouseQuantityOf,
                onSellClick = { item ->
                    state.selectedAcquisitionItem = item
                    state.showSellConfirmDialog = true
                },
                onItemLongPress = { item ->
                    state.selectedItem = item
                    state.showDetailDialog = true
                }
            )
        }
    }
}

/** 购买/收购标签切换行 */
@Composable
private fun MerchantModeTabs(
    merchantMode: MerchantMode,
    onModeSelect: (MerchantMode) -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        MerchantMode.entries.forEach { mode ->
            val isActive = merchantMode == mode
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f).clickable { onModeSelect(mode) }) {
                Text(mode.displayName, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = if (isActive) Color.Black else Color.Gray)
                Box(Modifier.fillMaxWidth().height(2.dp).background(if (isActive) GameColors.GoldDark else Color.Gray))
            }
        }
    }
}

/** 商人购买模式内容：空态 + 筛选行 + 商品网格 + 购买面板 */
@Composable
private fun ColumnScope.MerchantBuyMode(
    params: MerchantBuyPanelParams,
    onFilterSelect: (MerchantFilter) -> Unit,
    onItemClick: (MerchantItem) -> Unit,
    onItemLongPress: (MerchantItem) -> Unit,
    onQuantityChange: (Int) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    if (params.merchantItems.isEmpty()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("商人正在旅途中...\n请稍后再来", fontSize = 12.sp, color = GameColors.TextSecondary, textAlign = TextAlign.Center)
        }
    } else {
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MerchantFilter.entries.forEach { filter ->
                    ListingFilterButton(text = filter.displayName, selected = params.selectedFilter == filter,
                        onClick = { onFilterSelect(filter) })
                }
            }
            if (params.filteredItems.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("该分类暂无物品", fontSize = 12.sp, color = GameColors.TextSecondary, textAlign = TextAlign.Center)
                }
            } else {
                LazyVerticalGrid(columns = GridCells.Adaptive(60.dp),
                    modifier = Modifier.weight(1f).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(params.filteredItems, key = { it.id }, contentType = { "merchant_item" }) { item ->
                        UnifiedItemCard(data = ItemCardData(id = item.id, name = item.name, rarity = item.rarity,
                            quantity = item.quantity, additionalInfo = "${GameUtils.formatNumber(item.price)}灵石",
                            grade = item.grade, isManual = item.type == "manual", isPill = item.type == "pill",
                            isHerb = item.type == "herb", isSeed = item.type == "seed",
                                isMaterial = item.type == "material"),
                            isSelected = params.selectedItem?.id == item.id,
                            isFollowed = watchKeyOf(item)?.let { it in params.watchedKeys } ?: false,
                            onClick = {
                                // 0 库存商品不可选购（对齐收购页门卫，防损坏存档 0 库存商品进入购买面板）
                                if (item.quantity > 0) {
                                    onItemClick(item)
                                }
                            },
                            onLongPress = { onItemLongPress(item) })
                    }
                }
            }
        }
    }
    PurchasePanel(
        item = params.selectedItem, quantity = params.buyQuantity,
        maxQuantity = params.selectedItem?.quantity ?: 1,
        spiritStones = params.spiritStones,
        onQuantityChange = onQuantityChange,
        onConfirm = onConfirm,
        onCancel = onCancel
    )
}

/** 商人收购模式内容：空态 + 列表头 + 收购列表 */
@Composable
private fun ColumnScope.MerchantAcquisitionMode(
    params: MerchantAcquisitionPanelParams,
    warehouseQuantityOf: (MerchantItem) -> Int,
    onSellClick: (MerchantItem) -> Unit,
    onItemLongPress: (MerchantItem) -> Unit
) {
    val sortedAcquisitionItems = remember(params.acquisitionItems, params.watchedKeys) {
        // id 去重兜底：防 LazyColumn key="" 重复崩溃（Bugly #5079/#3091）
        params.acquisitionItems.distinctBy { it.id }.sortedByWatchedThenRarity(
            params.watchedKeys,
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
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("物品", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black,
                    modifier = Modifier.weight(1.3f))
                Text("收购数量", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Text("收购价格", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Text("出售", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            }
            HorizontalDivider(thickness = 1.dp, color = GameColors.ButtonDisabled)
            LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(sortedAcquisitionItems, key = { it.id }, contentType = { "merchant_item" }) { item ->
                    AcquisitionItemRow(
                        item = item,
                        warehouseQty = warehouseQuantityOf(item),
                        watchedKeys = params.watchedKeys,
                        onSellClick = onSellClick,
                        onItemLongPress = onItemLongPress
                    )
                }
            }
        }
    }
}

/** 收购列表单项：物品卡 + 收购数量/价格 + 出售按钮 */
@Composable
private fun AcquisitionItemRow(
    item: MerchantItem,
    warehouseQty: Int,
    watchedKeys: Set<String>,
    onSellClick: (MerchantItem) -> Unit,
    onItemLongPress: (MerchantItem) -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1.3f)) {
            UnifiedItemCard(data = ItemCardData(id = item.id, name = item.name, rarity = item.rarity,
                quantity = item.quantity, additionalInfo = "${GameUtils.formatNumber(item.price)}灵石",
                grade = item.grade, isManual = item.type == "manual", isPill = item.type == "pill",
                isHerb = item.type == "herb", isSeed = item.type == "seed", isMaterial = item.type == "material"),
                isSelected = false,
                isFollowed = watchKeyOf(item)?.let { it in watchedKeys } ?: false,
                onClick = { if (item.quantity > 0 && warehouseQty > 0) { onSellClick(item) } },
                onLongPress = { onItemLongPress(item) })
        }
        Text(GameUtils.formatNumber(item.quantity), fontSize = 11.sp, color = Color.Black,
            modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Text(GameUtils.formatNumber(item.price), fontSize = 11.sp, color = GameColors.GoldDark,
            modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            when {
                item.quantity == 0 -> Text("不再收购", color = Color.Red, fontSize = 10.sp)
                warehouseQty == 0 -> GameButton(text = "出售", onClick = {}, enabled = false)
                else -> GameButton(text = "出售", onClick = { onSellClick(item) })
            }
        }
    }
}

/** 商人次级弹窗：详情/上架管理/出售确认/自动购买/无刷新次数 */
@Composable
private fun MerchantSubDialogs(
    state: MerchantDialogState,
    viewModel: GameViewModel,
    gameData: GameData?,
    warehouseQuantityOf: (MerchantItem) -> Int
) {
    if (state.showDetailDialog) {
        state.selectedItem?.let { item ->
            com.xianxia.sect.ui.game.components.ItemDetailDialog(
                item = item,
                onDismiss = { state.showDetailDialog = false },
                viewModel = viewModel
            )
        }
    }
    if (state.showListingDialog) {
        ListingManagementDialog(
            gameData = gameData, viewModel = viewModel,
            onDismiss = { state.showListingDialog = false }
        )
    }
    if (state.showSellConfirmDialog) {
        state.selectedAcquisitionItem?.let { item ->
            val warehouseQty = warehouseQuantityOf(item)
            AcquisitionSellConfirmDialog(
                item = item, warehouseQuantity = warehouseQty,
                onConfirm = { quantity ->
                    viewModel.inventory.sellToMerchant(item.id, quantity)
                    state.showSellConfirmDialog = false
                    state.selectedAcquisitionItem = null
                },
                onDismiss = {
                    state.showSellConfirmDialog = false
                    state.selectedAcquisitionItem = null
                }
            )
        }
    }
    if (state.showAutoBuyDialog) {
        AutoBuyDialog(gameData = gameData, viewModel = viewModel, onDismiss = { state.showAutoBuyDialog = false })
    }

    if (state.showNoChancesDialog) {
        StandardPromptDialog(
            onDismissRequest = { state.showNoChancesDialog = false },
            title = "无刷新次数",
            text = "已无刷新次数，消耗1玉符可获取3次刷新次数",
            confirmLabel = "知道了",
            onConfirm = { state.showNoChancesDialog = false }
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = GameColors.TextPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("单价: ${GameUtils.formatNumber(item.price)} 灵石", fontSize = 10.sp,
                        color = GameColors.TextSecondary)
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("购买数量:", fontSize = 11.sp, color = GameColors.TextSecondary)
                    // key(item.id)：切换商品时重建组件，清空编辑态残留的输入串与焦点
                    // （否则编辑态输入中点选其他商品 → 输入框显示与提交数量脱节）
                    key(item.id) {
                        QuantitySelector(
                            quantity = quantity,
                            maxQuantity = maxQuantity,
                            onQuantityChange = onQuantityChange,
                            sizes = merchantQuantitySizes
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
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
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "出售确认",
        mode = DialogMode.Half,
        // 含出售数量常驻输入框：冻结宿主窗口系统栏操作，避免键盘弹出时部分机型频闪
        freezeSystemBars = true
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(item.name, fontWeight = FontWeight.Bold, color = com.xianxia.sect.ui.theme.getRarityColor(item.rarity),
                fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            Text("仓库拥有: ${GameUtils.formatNumber(warehouseQuantity)} 个", color = Color.Black, fontSize = 12.sp)
            Text("商人收购: 最多 ${GameUtils.formatNumber(item.quantity)} 个", color = Color.Black, fontSize = 12.sp)
            Text("最大可售: ${GameUtils.formatNumber(maxSellable)} 个", color = Color.Black, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("出售数量:", color = Color.Black, fontSize = 12.sp)
                QuantitySelector(
                    quantity = sellQuantity,
                    maxQuantity = maxSellable,
                    onQuantityChange = { sellQuantity = it },
                    sizes = merchantQuantitySizes
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("总价: ${GameUtils.formatNumber(totalPrice)} 灵石", color = GameColors.GoldDark,
                fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GameButton(text = "取消", onClick = onDismiss)
                GameButton(text = "确认出售", onClick = { onConfirm(sellQuantity) },
                    enabled = sellQuantity > 0 && maxSellable > 0)
            }
        }
    }
}

private fun getRarityColor(rarity: Int): Color = com.xianxia.sect.ui.theme.getRarityColor(rarity)

