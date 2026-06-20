package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import com.xianxia.sect.core.model.AutoBuyCatalogItem
import com.xianxia.sect.core.model.AutoBuyEntry
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.game.GameViewModel
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
    val autoBuyList = remember(gameData?.autoBuyList) {
        (gameData?.autoBuyList ?: emptyList())
            .distinctBy { "${it.itemName}:${it.itemType}:${it.rarity}" }
    }
    var deleteMode by remember { mutableStateOf(false) }
    var showItemSelectDialog by remember { mutableStateOf(false) }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GameButton(
                    text = "新增物品",
                    onClick = { showItemSelectDialog = true }
                )
                GameButton(
                    text = if (deleteMode) "取消删除" else "删除物品",
                    onClick = {
                        if (deleteMode) exitDeleteMode()
                        else deleteMode = true
                    }
                )
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
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
                        key = { "${it.itemName}:${it.itemType}:${it.rarity}" }
                    ) { entry ->
                        val key = "${entry.itemName}:${entry.itemType}:${entry.rarity}"
                        val isSelected = deleteMode && selectedForDeletion.containsKey(key)

                        UnifiedItemCard(
                            data = ItemCardData(
                                id = key,
                                name = entry.itemName,
                                rarity = entry.rarity,
                                quantity = 0,
                                isManual = entry.itemType == "manual",
                                isPill = entry.itemType == "pill",
                                isMaterial = entry.itemType == "material",
                                isHerb = entry.itemType == "herb",
                                isSeed = entry.itemType == "seed"
                            ),
                            isSelected = isSelected,
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
                            }
                        )
                    }
                }
            }

            // 删除确认面板
            if (deleteMode && selectedForDeletion.isNotEmpty()) {
                DeleteConfirmPanel(
                    selectedCount = selectedForDeletion.size,
                    onConfirm = {
                        viewModel.removeAutoBuyEntries(
                            selectedForDeletion.values.toList()
                        )
                        exitDeleteMode()
                    },
                    onCancel = { exitDeleteMode() }
                )
            }
        }
    }

    // 物品选择对话框
    if (showItemSelectDialog) {
        AutoBuyItemSelectDialog(
            viewModel = viewModel,
            existingList = autoBuyList,
            onConfirm = { entries ->
                viewModel.addAutoBuyEntries(entries)
                showItemSelectDialog = false
            },
            onDismiss = { showItemSelectDialog = false }
        )
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
    val selectedItems = remember { mutableStateMapOf<String, AutoBuyEntry>() }

    // 已在自动购买列表中的物品 key 集合
    val existingKeys = remember(existingList) {
        existingList.map { "${it.itemName}:${it.itemType}:${it.rarity}" }.toSet()
    }

    // 过滤并排序
    val availableItems = remember(catalogItems, selectedFilter, existingKeys) {
        catalogItems
            .filter { "${it.name}:${it.type}:${it.rarity}" !in existingKeys }
            .let { items ->
                if (selectedFilter == AutoBuyFilter.ALL) items
                else items.filter { it.type == selectedFilter.typeValue }
            }
    }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "选择自动购买物品",
        mode = DialogMode.Full,
        scrollableContent = false
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 类型筛选行
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
                        onClick = { selectedFilter = filter }
                    )
                }
            }

            if (availableItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "没有可添加的物品",
                        fontSize = 14.sp,
                        color = GameColors.TextSecondary
                    )
                }
            } else {
                // 物品网格
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
                        key = { "${it.name}:${it.type}:${it.rarity}" }
                    ) { item ->
                        val key = "${item.name}:${item.type}:${item.rarity}"
                        val isSelected = selectedItems.containsKey(key)

                        UnifiedItemCard(
                            data = ItemCardData(
                                id = key,
                                name = item.name,
                                rarity = item.rarity,
                                quantity = 0,
                                isManual = item.type == "manual",
                                isPill = item.type == "pill",
                                isMaterial = item.type == "material",
                                isHerb = item.type == "herb",
                                isSeed = item.type == "seed"
                            ),
                            isSelected = isSelected,
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
                            }
                        )
                    }
                }
            }

            // 底部确认区域 — 仅选中物品后显示
            if (selectedItems.isNotEmpty()) {
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
                            GameButton(text = "取消", onClick = onDismiss)
                            GameButton(
                                text = "确认新增",
                                onClick = { onConfirm(selectedItems.values.toList()) }
                            )
                        }
                    }
                }
            }
        }
    }
}
