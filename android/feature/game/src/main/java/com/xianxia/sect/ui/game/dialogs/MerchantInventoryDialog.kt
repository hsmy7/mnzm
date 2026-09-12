package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.util.sortedByWatchedThenRarity
import com.xianxia.sect.ui.game.components.watchKeyOf
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.theme.ButtonSizes

@Composable
fun InventorySelectDialog(viewModel: GameViewModel, onDismiss: () -> Unit) {
    val equipment by viewModel.equipmentStacks.collectAsStateWithLifecycle()
    val manuals by viewModel.manualStacks.collectAsStateWithLifecycle()
    val pills by viewModel.pills.collectAsStateWithLifecycle()
    val gameData by viewModel.gameData.collectAsStateWithLifecycle()

    var selectedFilter by remember { mutableStateOf(ListingFilter.ALL) }
    val selectedItems = remember { mutableStateMapOf<String, Int>() }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }

    val watchedKeys by viewModel.watchedItemIds.collectAsStateWithLifecycle()

    val listedItemIds = remember(gameData?.playerListedItems) {
        gameData?.playerListedItems?.map { it.itemId }?.toSet() ?: emptySet()
    }
    val sortedEquipment = remember(equipment, listedItemIds, watchedKeys) { filterAndSortItems(equipment, listedItemIds,
        watchedKeys) }
    val sortedManuals = remember(manuals, listedItemIds, watchedKeys) { filterAndSortItems(manuals, listedItemIds,
        watchedKeys) }
    val sortedPills = remember(pills, listedItemIds, watchedKeys) { filterAndSortItems(pills, listedItemIds,
        watchedKeys) }

    UnifiedGameDialog(onDismissRequest = onDismiss, title = "选择上架道具", mode = DialogMode.Full, scrollableContent = false,
        headerActions = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("已选${selectedItems.size}种", fontSize = 11.sp, color = GameColors.TextSecondary)
                GameButton(text = "确认上架", onClick = { if (selectedItems.isNotEmpty()) showConfirmDialog = true },
                    enabled = selectedItems.isNotEmpty())
            }
        }
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                ListingFilter.entries.forEach { filter ->
                    ListingFilterButton(text = filter.displayName, selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter })
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().background(GameColors.CardBackground,
                RoundedCornerShape(4.dp)).padding(4.dp)) {
                ItemsSelectPane(selectedFilter, sortedEquipment, sortedManuals, sortedPills,
                    selectedItems, watchedKeys, viewModel)
            }
        }
    }

    if (showConfirmDialog) {
        ConfirmListingDialog(selectedCount = selectedItems.size, totalCount = selectedItems.values.sum(),
            onConfirm = {
                if (!isSubmitting) {
                    isSubmitting = true
                    viewModel.inventory.listItemsToMerchant(selectedItems.entries.map { it.key to it.value })
                    selectedItems.clear(); showConfirmDialog = false; onDismiss()
                }
            },
            onDismiss = { showConfirmDialog = false })
    }
}

/** 按当前筛选 tab 渲染对应的上架选择网格。 */
@Composable
private fun ItemsSelectPane(
    selectedFilter: ListingFilter,
    equipment: List<EquipmentStack>,
    manuals: List<ManualStack>,
    pills: List<Pill>,
    selectedItems: MutableMap<String, Int>,
    watchedKeys: Set<String>,
    viewModel: GameViewModel
) {
    when (selectedFilter) {
        ListingFilter.ALL -> AllItemsSelectGrid(equipment = equipment, manuals = manuals, pills = pills,
            selectedItems = selectedItems, watchedKeys = watchedKeys, viewModel = viewModel)
        ListingFilter.EQUIPMENT -> InventorySelectGrid(items = equipment, selectedItems = selectedItems,
            emptyMessage = "暂无装备", watchedKeys = watchedKeys, viewModel = viewModel)
        ListingFilter.MANUAL -> InventorySelectGrid(items = manuals, selectedItems = selectedItems,
            emptyMessage = "暂无功法", watchedKeys = watchedKeys, viewModel = viewModel)
        ListingFilter.PILL -> InventorySelectGrid(items = pills, selectedItems = selectedItems,
            emptyMessage = "暂无丹药", watchedKeys = watchedKeys, viewModel = viewModel)
    }
}

/** 上架网格条目键（InventorySelectGrid 用）：装备/功法/丹药按 id，未知类型退化为身份哈希 */
private fun <T> selectGridItemKey(item: T): String = when (item) {
    is EquipmentStack -> "eq_${item.id}"
    is ManualStack -> "mn_${item.id}"
    is Pill -> "pl_${item.id}"
    else -> "unk_${System.identityHashCode(item)}"
}

/** 全部道具网格条目键（AllItemsSelectGrid 用）：丹药键含数量以区分不同数量堆叠 */
private fun <T> allItemsGridItemKey(item: T): String = when (item) {
    is EquipmentStack -> "eq_${item.id}"
    is ManualStack -> "mn_${item.id}"
    is Pill -> "pl_${item.id}_${item.quantity}"
    else -> "unk_${System.identityHashCode(item)}"
}

/** 网格条目 id（两网格共用）：未知类型退化为空串 */
private fun <T> inventoryItemId(item: T): String = when (item) {
    is EquipmentStack -> item.id
    is ManualStack -> item.id
    is Pill -> item.id
    else -> ""
}

/** 网格条目名称（两网格共用）：未知类型退化为空串 */
private fun <T> inventoryItemName(item: T): String = when (item) {
    is EquipmentStack -> item.name
    is ManualStack -> item.name
    is Pill -> item.name
    else -> ""
}

/** 网格条目稀有度（两网格共用）：未知类型退化为 1 */
private fun <T> inventoryItemRarity(item: T): Int = when (item) {
    is EquipmentStack -> item.rarity
    is ManualStack -> item.rarity
    is Pill -> item.rarity
    else -> 1
}

/** 网格条目数量（两网格共用）：未知类型退化为 1 */
private fun <T> inventoryItemQuantity(item: T): Int = when (item) {
    is EquipmentStack -> item.quantity
    is ManualStack -> item.quantity
    is Pill -> item.quantity
    else -> 1
}

@Composable
private fun <T> InventorySelectGrid(
    items: List<T>,
    selectedItems: MutableMap<String, Int>,
    emptyMessage: String,
    watchedKeys: Set<String> = emptySet(),
    viewModel: GameViewModel? = null
) {
    var selectedItem by remember { mutableStateOf<T?>(null) }
    var showDetailDialog by remember { mutableStateOf(false) }

    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(emptyMessage, fontSize = 12.sp,
            color = GameColors.TextSecondary) }
    } else {
        LazyVerticalGrid(columns = GridCells.Adaptive(60.dp), modifier = Modifier.fillMaxSize().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { selectGridItemKey(it) }, contentType = { "inventory_item" }) { item ->
                val id = inventoryItemId(item)
                val name = inventoryItemName(item)
                val rar = inventoryItemRarity(item)
                val qty = inventoryItemQuantity(item)
                val isSelected = selectedItems.containsKey(id)
                UnifiedItemCard(data = ItemCardData(id = id, name = name, rarity = rar, quantity = qty,
                    grade = (item as? Pill)?.grade?.displayName, isManual = item is ManualStack, isPill = item is Pill),
                    isSelected = isSelected,
                    isFollowed = watchKeyOf(item)?.let { it in watchedKeys } ?: false,
                    onClick = { if (isSelected) selectedItems.remove(id) else selectedItems[id] = qty },
                    onLongPress = { selectedItem = item; showDetailDialog = true })
            }
        }
    }
    if (showDetailDialog) {
        selectedItem?.let {
            com.xianxia.sect.ui.game.components.ItemDetailDialog(
                item = it,
                onDismiss = { showDetailDialog = false },
                viewModel = viewModel
            )
        }
    }
}

@Composable
private fun AllItemsSelectGrid(
    equipment: List<EquipmentStack>, manuals: List<ManualStack>, pills: List<Pill>,
    selectedItems: MutableMap<String, Int>,
    watchedKeys: Set<String> = emptySet(),
    viewModel: GameViewModel? = null
) {
    var selectedItem by remember { mutableStateOf<Any?>(null) }
    var showDetailDialog by remember { mutableStateOf(false) }

    val allItems = remember(equipment, manuals, pills, watchedKeys) {
        val items = mutableListOf<Any>()
        items.addAll(equipment); items.addAll(manuals); items.addAll(pills)
        items.sortedByWatchedThenRarity(
            watchedKeys,
            keyOf = { watchKeyOf(it) },
            rarityOf = { item -> inventoryItemRarity(item) },
            nameOf = { item -> inventoryItemName(item) }
        )
    }

    if (allItems.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("暂无道具", fontSize = 12.sp,
            color = GameColors.TextSecondary) }
    } else {
        LazyVerticalGrid(columns = GridCells.Adaptive(60.dp), modifier = Modifier.fillMaxSize().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(allItems, key = { allItemsGridItemKey(it) }, contentType = { "inventory_item" }) { item ->
                val id = inventoryItemId(item)
                val name = inventoryItemName(item)
                val rarity = inventoryItemRarity(item)
                val qty = inventoryItemQuantity(item)
                val isSelected = selectedItems.containsKey(id)
                UnifiedItemCard(data = ItemCardData(id = id, name = name, rarity = rarity, quantity = qty,
                    grade = (item as? Pill)?.grade?.displayName, isManual = item is ManualStack, isPill = item is Pill),
                    isSelected = isSelected,
                    isFollowed = watchKeyOf(item)?.let { it in watchedKeys } ?: false,
                    onClick = { if (isSelected) selectedItems.remove(id) else selectedItems[id] = qty },
                    onLongPress = { selectedItem = item; showDetailDialog = true })
            }
        }
    }
    if (showDetailDialog) {
        selectedItem?.let {
            com.xianxia.sect.ui.game.components.ItemDetailDialog(
                item = it,
                onDismiss = { showDetailDialog = false },
                viewModel = viewModel
            )
        }
    }
}

@Composable
private fun ConfirmListingDialog(selectedCount: Int, totalCount: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    UnifiedGameDialog(onDismissRequest = onDismiss, title = "确认上架", mode = DialogMode.Half) {
        Column(Modifier.padding(20.dp)) {
            Spacer(Modifier.height(4.dp))
            Text("确定要上架 $selectedCount 种道具（共 $totalCount 件）吗？", fontSize = 12.sp, color = GameColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            Text("弟子购买价格为原价的80%", fontSize = 11.sp, color = GameColors.TextSecondary)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GameButton(text = "取消", onClick = onDismiss, modifier = Modifier.width(ButtonSizes.StandardWidth))
                GameButton(text = "确认", onClick = onConfirm, modifier = Modifier.width(ButtonSizes.StandardWidth))
            }
        }
    }
}
