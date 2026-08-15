package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.AutoBuyEntry
import com.xianxia.sect.core.model.AutoBuyCatalogItem
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.game.GameViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xianxia.sect.core.util.sortedByWatchedThenRarity
import com.xianxia.sect.core.util.watchKey
import com.xianxia.sect.core.util.WATCHABLE_ITEM_TYPES
import com.xianxia.sect.ui.game.components.ItemDetailDialog
import com.xianxia.sect.ui.theme.GameColors

// ── 筛选枚举 ────────────────────────────────────────────────────────

private enum class AutoBuyFilter(val displayName: String, val typeValue: String?) {
    ALL("全部", null),
    EQUIPMENT("装备", "equipment"),
    MANUAL("功法", "manual"),
    PILL("丹药", "pill"),
    MATERIAL("材料", "material"),
    HERB("灵草", "herb"),
    SEED("种子", "seed")
}

// ── 主对话框：自动购买列表 ──────────────────────────────────────────

/** 自动购买列表排序（AutoBuyDialog 拆分） */
private fun sortAutoBuyList(
    entries: List<AutoBuyEntry>,
    watchedKeys: Set<String>
): List<AutoBuyEntry> = entries
    .distinctBy { "${it.itemName}:${it.itemType}:${it.rarity}" }
    .sortedByWatchedThenRarity(
        watchedKeys,
        keyOf = {
            if (it.itemType in WATCHABLE_ITEM_TYPES) {
                watchKey(it.itemType, it.itemName)
            } else {
                null
            }
        },
        rarityOf = { it.rarity },
        nameOf = { it.itemName }
    )

/**
 * 自动购买主界面（半屏）。
 * 显示已添加的自动购买物品列表，支持新增和删除操作。
 */
@Composable
fun AutoBuyDialog(
    gameData: GameData?,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val watchedKeys by viewModel.watchedItemIds.collectAsStateWithLifecycle()
    val autoBuyList = remember(gameData?.autoBuyList, watchedKeys) {
        sortAutoBuyList(gameData?.autoBuyList ?: emptyList(), watchedKeys)
    }
    var deleteMode by remember { mutableStateOf(false) }
    var showItemSelectDialog by remember { mutableStateOf(false) }
    var detailItem by remember { mutableStateOf<MerchantItem?>(null) }
    val selectedForDeletion = remember { mutableStateMapOf<String, AutoBuyEntry>() }

    // 退出删除模式时清除选中
    fun exitDeleteMode() {
        deleteMode = false
        selectedForDeletion.clear()
    }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "自动购买",
        mode = DialogMode.Half,
        scrollableContent = false,
        headerActions = {
            AutoBuyHeaderActions(
                deleteMode = deleteMode,
                onAddClick = { showItemSelectDialog = true },
                onToggleDeleteMode = { if (deleteMode) exitDeleteMode() else deleteMode = true }
            )
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AutoBuyListContent(
                autoBuyList = autoBuyList,
                deleteMode = deleteMode,
                watchedKeys = watchedKeys,
                selectedForDeletion = selectedForDeletion,
                onDeleteConfirm = {
                    viewModel.removeAutoBuyEntries(selectedForDeletion.values.toList())
                    exitDeleteMode()
                },
                onDeleteCancel = { exitDeleteMode() },
                onShowDetail = { detailItem = it }
            )
        }
    }

    // 物品选择对话框
    if (showItemSelectDialog) {
        AutoBuyItemSelectDialog(
            viewModel = viewModel, existingList = autoBuyList,
            onConfirm = { entries -> viewModel.addAutoBuyEntries(entries); showItemSelectDialog = false },
            onDismiss = { showItemSelectDialog = false }
        )
    }

    // 物品详情弹窗（长按触发）
    detailItem?.let { item ->
        ItemDetailDialog(item = item, onDismiss = { detailItem = null }, viewModel = viewModel)
    }
}

/** 标题栏动作（AutoBuyDialog 拆分）：新增物品 + 删除物品切换 */
@Composable
private fun AutoBuyHeaderActions(
    deleteMode: Boolean,
    onAddClick: () -> Unit,
    onToggleDeleteMode: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GameButton(
            text = "新增物品",
            onClick = onAddClick
        )
        GameButton(
            text = if (deleteMode) "取消删除" else "删除物品",
            onClick = onToggleDeleteMode
        )
    }
}

/** 自动购买列表内容（AutoBuyDialog 拆分）：空态 / 网格 + 删除确认面板 */
@Composable
private fun ColumnScope.AutoBuyListContent(
    autoBuyList: List<AutoBuyEntry>,
    deleteMode: Boolean,
    watchedKeys: Set<String>,
    selectedForDeletion: SnapshotStateMap<String, AutoBuyEntry>,
    onDeleteConfirm: () -> Unit,
    onDeleteCancel: () -> Unit,
    onShowDetail: (MerchantItem) -> Unit
) {
    if (autoBuyList.isEmpty()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "暂无自动购买物品",
                fontSize = 14.sp,
                color = GameColors.TextSecondary
            )
        }
    } else {
        AutoBuyEntryGrid(
            autoBuyList = autoBuyList,
            deleteMode = deleteMode,
            watchedKeys = watchedKeys,
            selectedForDeletion = selectedForDeletion,
            onShowDetail = onShowDetail
        )
    }

    // 删除确认面板
    if (deleteMode && selectedForDeletion.isNotEmpty()) {
        DeleteConfirmPanel(
            selectedCount = selectedForDeletion.size,
            onConfirm = onDeleteConfirm,
            onCancel = onDeleteCancel
        )
    }
}

/** 自动购买条目网格（AutoBuyDialog 拆分） */
@Composable
private fun ColumnScope.AutoBuyEntryGrid(
    autoBuyList: List<AutoBuyEntry>,
    deleteMode: Boolean,
    watchedKeys: Set<String>,
    selectedForDeletion: SnapshotStateMap<String, AutoBuyEntry>,
    onShowDetail: (MerchantItem) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(60.dp),
        modifier = Modifier
            .weight(1f)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(
            items = autoBuyList,
            key = { "${it.itemName}:${it.itemType}:${it.rarity}" },
            contentType = { "auto_buy_entry" }
        ) { entry ->
            val key = "${entry.itemName}:${entry.itemType}:${entry.rarity}"
            val isSelected = deleteMode && selectedForDeletion.containsKey(key)

            UnifiedItemCard(
                data = ItemCardData(
                    id = key, name = entry.itemName, rarity = entry.rarity, quantity = 0,
                    isManual = entry.itemType == "manual", isPill = entry.itemType == "pill",
                    isMaterial = entry.itemType == "material", isHerb = entry.itemType == "herb",
                    isSeed = entry.itemType == "seed"
                ),
                isSelected = isSelected,
                isFollowed = watchKey(entry.itemType, entry.itemName) in watchedKeys,
                selectedBorderColor = Color.Red,
                showQuantity = false,
                onClick = {
                    if (deleteMode) {
                        if (isSelected) {
                            selectedForDeletion.remove(key)
                        } else {
                            selectedForDeletion[key] = entry
                        }
                    }
                },
                onLongPress = {
                    onShowDetail(
                        MerchantItem(
                            id = entry.itemName, name = entry.itemName, type = entry.itemType,
                            rarity = entry.rarity, quantity = 0, price = 0L
                        )
                    )
                }
            )
        }
    }
}

// ── 删除确认面板 ────────────────────────────────────────────────────

@Composable
private fun DeleteConfirmPanel(
    selectedCount: Int,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = GameColors.PageBackground,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "将物品从自动购买列表移除",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = GameColors.TextPrimary,
                modifier = Modifier.weight(1f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GameButton(text = "取消", onClick = onCancel)
                GameButton(
                    text = "确认删除",
                    onClick = onConfirm
                )
            }
        }
    }
}

// ── 物品选择对话框 ──────────────────────────────────────────────────

/** 自动购买可选物品过滤 + 排序（AutoBuyItemSelectDialog 拆分） */
private fun autoBuyAvailableItems(
    catalogItems: List<AutoBuyCatalogItem>,
    selectedFilter: AutoBuyFilter,
    existingKeys: Set<String>,
    watchedKeys: Set<String>
): List<AutoBuyCatalogItem> = catalogItems
    .filter { "${it.name}:${it.type}:${it.rarity}" !in existingKeys }
    .let { items ->
        if (selectedFilter == AutoBuyFilter.ALL) items
        else items.filter { it.type == selectedFilter.typeValue }
    }
    .sortedByWatchedThenRarity(
        watchedKeys,
        keyOf = { item ->
            if (item.type in WATCHABLE_ITEM_TYPES) {
                watchKey(item.type, item.name)
            } else {
                null
            }
        },
        rarityOf = { it.rarity },
        nameOf = { it.name }
    )

/**
 * 自动购买物品选择界面（半屏）。
 * 显示所有可购买物品（排除已在自动购买列表中的），按品阶降序排列。
 * 支持多选，选中物品显示金色边框。
 */
@Composable
fun AutoBuyItemSelectDialog(
    viewModel: GameViewModel,
    existingList: List<AutoBuyEntry>,
    onConfirm: (List<AutoBuyEntry>) -> Unit,
    onDismiss: () -> Unit
) {
    val catalogItems = remember { viewModel.getAllAutoBuyableItems() }
    var selectedFilter by remember { mutableStateOf(AutoBuyFilter.ALL) }
    var detailItem by remember { mutableStateOf<MerchantItem?>(null) }
    val selectedItems = remember { mutableStateMapOf<String, AutoBuyEntry>() }

    // 已在自动购买列表中的物品 key 集合
    val existingKeys = remember(existingList) {
        existingList.map { "${it.itemName}:${it.itemType}:${it.rarity}" }.toSet()
    }

    // 过滤并排序（已关注优先 → 品阶降序）
    val watchedKeys by viewModel.watchedItemIds.collectAsStateWithLifecycle()
    val availableItems = remember(catalogItems, selectedFilter, existingKeys, watchedKeys) {
        autoBuyAvailableItems(catalogItems, selectedFilter, existingKeys, watchedKeys)
    }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "选择自动购买物品",
        mode = DialogMode.Full,
        scrollableContent = false
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 类型筛选行
            AutoBuyFilterRow(
                selectedFilter = selectedFilter,
                onFilterSelected = { selectedFilter = it }
            )

            if (availableItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "没有可添加的物品", fontSize = 14.sp, color = GameColors.TextSecondary)
                }
            } else {
                // 物品网格
                AutoBuyCatalogGrid(
                    availableItems = availableItems,
                    selectedItems = selectedItems,
                    watchedKeys = watchedKeys,
                    onShowDetail = { item ->
                        detailItem = MerchantItem(
                            id = item.name, name = item.name, type = item.type,
                            rarity = item.rarity, quantity = 0, price = 0L
                        )
                    }
                )
            }

            // 底部确认区域 — 仅选中物品后显示
            if (selectedItems.isNotEmpty()) {
                AutoBuyConfirmBar(
                    onConfirm = { onConfirm(selectedItems.values.toList()) },
                    onCancel = onDismiss
                )
            }
        }

        // 物品详情弹窗（长按触发）
        detailItem?.let { item ->
            ItemDetailDialog(item = item, onDismiss = { detailItem = null }, viewModel = viewModel)
        }
    }
}

/** 类型筛选行（AutoBuyItemSelectDialog 拆分） */
// 拆分搬移:参数保留原签名语义
@Suppress("UnusedParameter")
@Composable
private fun AutoBuyFilterRow(
    selectedFilter: AutoBuyFilter,
    onFilterSelected: (AutoBuyFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AutoBuyFilter.entries.forEach { filter ->
            GameButton(
                text = filter.displayName,
                onClick = { onFilterSelected(filter) }
            )
        }
    }
}

/** 可添加物品网格（AutoBuyItemSelectDialog 拆分） */
@Composable
private fun ColumnScope.AutoBuyCatalogGrid(
    availableItems: List<AutoBuyCatalogItem>,
    selectedItems: SnapshotStateMap<String, AutoBuyEntry>,
    watchedKeys: Set<String>,
    onShowDetail: (AutoBuyCatalogItem) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(60.dp),
        modifier = Modifier
            .weight(1f)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(
            items = availableItems,
            key = { "${it.name}:${it.type}:${it.rarity}" },
            contentType = { "catalog_item" }
        ) { item ->
            val key = "${item.name}:${item.type}:${item.rarity}"
            val isSelected = selectedItems.containsKey(key)

            UnifiedItemCard(
                data = ItemCardData(
                    id = key, name = item.name, rarity = item.rarity, quantity = 0,
                    isManual = item.type == "manual", isPill = item.type == "pill",
                    isMaterial = item.type == "material", isHerb = item.type == "herb",
                    isSeed = item.type == "seed"
                ),
                isSelected = isSelected,
                isFollowed = watchKey(item.type, item.name) in watchedKeys,
                showQuantity = false,
                onClick = {
                    if (isSelected) {
                        selectedItems.remove(key)
                    } else {
                        selectedItems[key] = AutoBuyEntry(
                            itemName = item.name,
                            itemType = item.type,
                            rarity = item.rarity
                        )
                    }
                },
                onLongPress = { onShowDetail(item) }
            )
        }
    }
}

/** 底部确认栏（AutoBuyItemSelectDialog 拆分）：每年自动购买提示 + 确认/取消 */
@Composable
private fun AutoBuyConfirmBar(
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = GameColors.PageBackground,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "每年自动购买此物品",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = GameColors.TextPrimary,
                modifier = Modifier.weight(1f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GameButton(text = "取消", onClick = onCancel)
                GameButton(
                    text = "确认新增",
                    onClick = onConfirm
                )
            }
        }
    }
}
