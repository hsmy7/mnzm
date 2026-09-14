package com.xianxia.sect.ui.game.tabs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.GridRow
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.components.ItemDetailDialog

/** 品阶筛选选项 */
private val BULK_SELL_RARITY_OPTIONS = listOf(
    1 to "凡品",
    2 to "灵品",
    3 to "宝品",
    4 to "玄品",
    5 to "地品",
    6 to "天品"
)

/** 物品类型筛选选项 */
private val BULK_SELL_TYPE_OPTIONS = listOf(
    "ALL" to "全部",
    "EQUIPMENT" to "装备",
    "PILL" to "丹药",
    "MANUAL" to "功法",
    "HERB" to "草药",
    "SEED" to "种子",
    "MATERIAL" to "材料"
)

/** 可出售物品筛选：按品阶 + 类型 + 未锁定过滤 */
@Composable
private fun <T> rememberSellableItems(
    items: List<T>,
    selectedRarities: Set<Int>,
    finalTypes: Set<String>,
    typeKey: String,
    raritySelector: (T) -> Int,
    lockedSelector: (T) -> Boolean
): List<T> = remember(items, selectedRarities, finalTypes) {
    if (selectedRarities.isNotEmpty() && finalTypes.contains(typeKey)) {
        items.filter { selectedRarities.contains(raritySelector(it)) && !lockedSelector(it) }
    } else emptyList()
}

/** 六类可出售物品聚合 */
private data class BulkSellSelection(
    val sellableEquipment: List<EquipmentStack>,
    val sellableManuals: List<ManualStack>,
    val sellablePills: List<Pill>,
    val sellableMaterials: List<Material>,
    val sellableHerbs: List<Herb>,
    val sellableSeeds: List<Seed>
)

/** 单类物品出售总价 */
private fun <T> sellValueOf(items: List<T>, basePrice: (T) -> Int, quantity: (T) -> Int): Long =
    items.sumOf { GameConfig.Rarity.calculateSellPrice(basePrice(it), quantity(it)) }

/** 六类可出售物品总价值 */
private fun sellableValue(selection: BulkSellSelection): Long =
    sellValueOf(items = selection.sellableEquipment, basePrice = { it.basePrice }, quantity = { it.quantity }) +
        sellValueOf(items = selection.sellableManuals, basePrice = { it.basePrice }, quantity = { it.quantity }) +
        sellValueOf(items = selection.sellablePills, basePrice = { it.basePrice }, quantity = { it.quantity }) +
        sellValueOf(items = selection.sellableMaterials, basePrice = { it.basePrice }, quantity = { it.quantity }) +
        sellValueOf(items = selection.sellableHerbs, basePrice = { it.basePrice }, quantity = { it.quantity }) +
        sellValueOf(items = selection.sellableSeeds, basePrice = { it.basePrice }, quantity = { it.quantity })

/** 可出售物品数量与总价值 */
private fun sellableTotals(selection: BulkSellSelection): Pair<Int, Long> {
    val totalItems = selection.sellableEquipment.size + selection.sellableManuals.size +
            selection.sellablePills.size + selection.sellableMaterials.size +
            selection.sellableHerbs.size + selection.sellableSeeds.size
    return totalItems to sellableValue(selection)
}

/** 一键出售筛选状态 */
private class BulkSellFilterState {
    var selectedRarities by mutableStateOf<Set<Int>>(emptySet())
    var selectedTypes by mutableStateOf<Set<String>>(emptySet())
}

@Composable
internal fun BulkSellDialog(
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val equipmentStacks by viewModel.equipmentStacks.collectAsStateWithLifecycle()
    val manualStacks by viewModel.manualStacks.collectAsStateWithLifecycle()
    val pills by viewModel.pills.collectAsStateWithLifecycle()
    val materials by viewModel.materials.collectAsStateWithLifecycle()
    val herbs by viewModel.herbs.collectAsStateWithLifecycle()
    val seeds by viewModel.seeds.collectAsStateWithLifecycle()

    val filterState = remember { BulkSellFilterState() }
    var showConfirmDialog by remember { mutableStateOf(false) }
    val finalTypes = remember(filterState.selectedTypes) {
        resolveBulkSellTypes(selectedTypes = filterState.selectedTypes)
    }
    val selection = buildBulkSellSelection(
        equipmentStacks = equipmentStacks, manualStacks = manualStacks, pills = pills,
        materials = materials, herbs = herbs, seeds = seeds,
        selectedRarities = filterState.selectedRarities, finalTypes = finalTypes
    )
    val (totalItems, totalValue) = sellableTotals(selection)

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "一键出售",
        mode = DialogMode.Half,
        showCloseButton = true,
        scrollableContent = false
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            BulkSellFilterList(
                filterState = filterState,
                selection = selection,
                totalItems = totalItems,
                viewModel = viewModel
            )
            // 底部按钮 — 固定显示，无需滚动
            BulkSellActionButtons(
                totalItems = totalItems,
                onDismiss = onDismiss,
                onConfirmClick = { showConfirmDialog = true }
            )
        }
    }

    if (showConfirmDialog) {
        BulkSellConfirmDialog(
            totalItems = totalItems,
            totalValue = totalValue,
            selectedRarities = filterState.selectedRarities,
            finalTypes = finalTypes,
            viewModel = viewModel,
            onDismissDialog = { showConfirmDialog = false },
            onConfirmDone = onDismiss
        )
    }
}

/** "ALL" 类型展开 */
private fun resolveBulkSellTypes(selectedTypes: Set<String>): Set<String> =
    if (selectedTypes.contains("ALL")) {
        setOf("EQUIPMENT", "PILL", "MANUAL", "HERB", "SEED", "MATERIAL")
    } else {
        selectedTypes
    }

/** 六类可出售列表构建：品阶 + 类型 + 未锁定过滤 */
@Suppress("LongParameterList")
@Composable
private fun buildBulkSellSelection(
    equipmentStacks: List<EquipmentStack>,
    manualStacks: List<ManualStack>,
    pills: List<Pill>,
    materials: List<Material>,
    herbs: List<Herb>,
    seeds: List<Seed>,
    selectedRarities: Set<Int>,
    finalTypes: Set<String>
): BulkSellSelection = BulkSellSelection(
    sellableEquipment = rememberSellableItems(
        items = equipmentStacks, selectedRarities = selectedRarities, finalTypes = finalTypes,
        typeKey = "EQUIPMENT", raritySelector = { it.rarity }, lockedSelector = { it.isLocked }
    ),
    sellableManuals = rememberSellableItems(
        items = manualStacks, selectedRarities = selectedRarities, finalTypes = finalTypes,
        typeKey = "MANUAL", raritySelector = { it.rarity }, lockedSelector = { it.isLocked }
    ),
    sellablePills = rememberSellableItems(
        items = pills, selectedRarities = selectedRarities, finalTypes = finalTypes,
        typeKey = "PILL", raritySelector = { it.rarity }, lockedSelector = { it.isLocked }
    ),
    sellableMaterials = rememberSellableItems(
        items = materials, selectedRarities = selectedRarities, finalTypes = finalTypes,
        typeKey = "MATERIAL", raritySelector = { it.rarity }, lockedSelector = { it.isLocked }
    ),
    sellableHerbs = rememberSellableItems(
        items = herbs, selectedRarities = selectedRarities, finalTypes = finalTypes,
        typeKey = "HERB", raritySelector = { it.rarity }, lockedSelector = { it.isLocked }
    ),
    sellableSeeds = rememberSellableItems(
        items = seeds, selectedRarities = selectedRarities, finalTypes = finalTypes,
        typeKey = "SEED", raritySelector = { it.rarity }, lockedSelector = { it.isLocked }
    )
)

/** 筛选列表区：品阶 + 类型 + 可出售物品明细 */
@Composable
private fun ColumnScope.BulkSellFilterList(
    filterState: BulkSellFilterState,
    selection: BulkSellSelection,
    totalItems: Int,
    viewModel: GameViewModel
) {
    var showDetailDialog by remember { mutableStateOf(false) }
    var detailItem by remember { mutableStateOf<Any?>(null) }
    LazyColumn(
        modifier = Modifier
            .weight(1f)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "选择品阶（可多选）：",
                    fontSize = 12.sp,
                    color = Color.Black
                )
                BulkSellRarityFilter(filterState = filterState)
            }
        }

        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "选择物品类型（可多选）：",
                    fontSize = 12.sp,
                    color = Color.Black
                )
                BulkSellTypeFilter(filterState = filterState)
            }
        }

        item {
            BulkSellSellableList(
                selection = selection,
                totalItems = totalItems,
                filterState = filterState,
                onItemLongPress = { item ->
                    detailItem = item
                    showDetailDialog = true
                }
            )
        }
    }

    if (showDetailDialog && detailItem != null) {
        ItemDetailDialog(
            item = checkNotNull(detailItem) { "detailItem is null" },
            onDismiss = {
                showDetailDialog = false
                detailItem = null
            },
            viewModel = viewModel
        )
    }
}

/** 品阶多选按钮 */
@Composable
private fun BulkSellRarityFilter(filterState: BulkSellFilterState) {
    GridRow(items = BULK_SELL_RARITY_OPTIONS, maxColumnWidth = 80.dp) { (rarity, name) ->
        val isSelected = filterState.selectedRarities.contains(rarity)
        GameButton(
            text = if (isSelected) "✓ $name" else name,
            onClick = {
                filterState.selectedRarities = if (isSelected) {
                    filterState.selectedRarities - rarity
                } else {
                    filterState.selectedRarities + rarity
                }
            },
            enabled = true,
            modifier = Modifier.weight(1f),
            height = 34.dp,
            fontSize = 11.sp
        )
    }
}

/** 物品类型多选按钮 */
@Composable
private fun BulkSellTypeFilter(filterState: BulkSellFilterState) {
    GridRow(items = BULK_SELL_TYPE_OPTIONS, maxColumnWidth = 80.dp) { (type, name) ->
        val isSelected = filterState.selectedTypes.contains(type)
        GameButton(
            text = if (isSelected) "✓ $name" else name,
            onClick = {
                filterState.selectedTypes = if (isSelected) {
                    filterState.selectedTypes - type
                } else {
                    filterState.selectedTypes + type
                }
            },
            enabled = true,
            modifier = Modifier.weight(1f),
            height = 34.dp,
            fontSize = 11.sp
        )
    }
}

/** 可出售物品明细列表 */
@Composable
private fun BulkSellSellableList(
    selection: BulkSellSelection,
    totalItems: Int,
    filterState: BulkSellFilterState,
    onItemLongPress: (Any) -> Unit
) {
    if (totalItems > 0) {
        Text(
            text = "可出售物品（共${totalItems}件）：",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SellableEquipmentSection(selection.sellableEquipment, onItemLongPress = onItemLongPress)
            SellableManualSection(selection.sellableManuals, onItemLongPress = onItemLongPress)
            SellablePillSection(selection.sellablePills, onItemLongPress = onItemLongPress)
            SellableMaterialSection(selection.sellableMaterials, onItemLongPress = onItemLongPress)
            SellableHerbSection(selection.sellableHerbs, onItemLongPress = onItemLongPress)
            SellableSeedSection(selection.sellableSeeds, onItemLongPress = onItemLongPress)
        }
    } else if (filterState.selectedRarities.isNotEmpty() && filterState.selectedTypes.isNotEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "没有符合条件的物品",
                fontSize = 12.sp,
                color = Color.Black
            )
        }
    }
}

/** 底部按钮：取消 / 确认出售 */
@Composable
private fun BulkSellActionButtons(
    totalItems: Int,
    onDismiss: () -> Unit,
    onConfirmClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        GameButton(
            text = "取消",
            onClick = onDismiss,
            modifier = Modifier.weight(1f)
        )
        GameButton(
            text = "确认出售",
            onClick = onConfirmClick,
            enabled = totalItems > 0,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 确认出售弹窗 */
@Composable
private fun BulkSellConfirmDialog(
    totalItems: Int,
    totalValue: Long,
    selectedRarities: Set<Int>,
    finalTypes: Set<String>,
    viewModel: GameViewModel,
    onDismissDialog: () -> Unit,
    onConfirmDone: () -> Unit
) {
    var isSelling by remember { mutableStateOf(false) }
    StandardPromptDialog(
        onDismissRequest = onDismissDialog,
        title = "确认出售",
        confirmLabel = "确认出售",
        onConfirm = {
            if (isSelling) return@StandardPromptDialog
            isSelling = true
            viewModel.merchant.bulkSellItems(selectedRarities, finalTypes)
            onDismissDialog()
            onConfirmDone()
        },
        dismissLabel = "取消",
        onDismiss = onDismissDialog
    ) {
        Text(
            text = "确定要出售以下物品吗？",
            fontSize = 12.sp,
            color = Color.Black
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "物品数量: ${totalItems} 件",
            fontSize = 12.sp,
            color = Color.Black
        )
        Text(
            text = "获得灵石: ${GameUtils.formatNumber(totalValue)}（原价80%）",
            fontSize = 12.sp,
            color = Color.Black
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "此操作不可撤销！",
            fontSize = 11.sp,
            color = Color.Black
        )
    }
}
