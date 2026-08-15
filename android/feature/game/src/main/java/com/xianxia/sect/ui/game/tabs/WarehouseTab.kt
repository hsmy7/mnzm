@file:Suppress("TooManyFunctions") // 拆分聚合:提取的私有辅助函数集中在原文件,文件级复杂度为拆分代价
package com.xianxia.sect.ui.game.tabs

import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.ui.components.clickableWithSound
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.RewardCardItem
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.model.StorageBag
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.RewardDisplayDialog
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.components.ItemDetailDialog
import com.xianxia.sect.ui.game.components.watchKeyOf
import com.xianxia.sect.core.util.sortedByWatchedThenRarity
import com.xianxia.sect.ui.theme.ButtonSizes
import com.xianxia.sect.ui.theme.GameColors

internal fun getWarehouseItemIsLocked(item: Any): Boolean = when (item) {
    is EquipmentStack -> item.isLocked
    is ManualStack -> item.isLocked
    is Pill -> item.isLocked
    is Material -> item.isLocked
    is Herb -> item.isLocked
    is Seed -> item.isLocked
    else -> false
}
internal enum class WarehouseFilter(val displayName: String) {
    ALL("全部"),
    EQUIPMENT("装备"),
    PILL("丹药"),
    MANUAL("功法"),
    HERB("草药"),
    SEED("种子"),
    MATERIAL("材料")
}

data class SpiritStoneInfo(val grade: SpiritStoneGrade, val quantity: Long)

/** 仓库物品统一条目（WarehouseTab 拆分，原函数内局部数据类提升为文件级） */
private data class WarehouseItemData(
    val id: String,
    val name: String,
    val rarity: Int,
    val item: Any
)

/** 仓库数据流订阅打包（WarehouseTab 拆分，参数 >6 规避 LongParameterList） */
private data class WarehouseFlows(
    val equipmentStacks: List<EquipmentStack>,
    val manualStacks: List<ManualStack>,
    val pills: List<Pill>,
    val materials: List<Material>,
    val herbs: List<Herb>,
    val seeds: List<Seed>,
    val storageBags: List<StorageBag>,
    val spiritStoneTotals: GameViewModel.SpiritStoneTotals,
    val watchedKeys: Set<String>,
    val bagRewardCards: List<RewardCardItem>
)

/** 仓库派生状态（WarehouseTab 拆分） */
private data class WarehouseState(
    val spiritStoneCards: List<Pair<String, SpiritStoneInfo>>,
    val equipment: List<EquipmentStack>,
    val manuals: List<ManualStack>,
    val sortedPills: List<Pill>,
    val sortedMaterials: List<Material>,
    val sortedHerbs: List<Herb>,
    val sortedSeeds: List<Seed>,
    val sortedBags: List<StorageBag>,
    val allSortedItems: List<WarehouseItemData>,
    val watchedKeys: Set<String>
)

/** 仓库交互回调组（WarehouseTab 拆分，参数 >6 规避 LongParameterList） */
private data class WarehouseActions(
    val onFilterSelected: (WarehouseFilter) -> Unit,
    val onPageChange: (Int) -> Unit,
    val onPageClamp: (Int) -> Unit,
    val onItemSelect: (String) -> Unit,
    val onItemLongPress: (String) -> Unit,
    val onOpenBag: (String) -> Unit
)

/** 仓库排序物品打包（WarehouseTab 拆分，参数 >6 规避 LongParameterList） */
private data class WarehouseSortedItems(
    val equipment: List<EquipmentStack>,
    val manuals: List<ManualStack>,
    val sortedPills: List<Pill>,
    val sortedMaterials: List<Material>,
    val sortedHerbs: List<Herb>,
    val sortedSeeds: List<Seed>,
    val sortedBags: List<StorageBag>,
    val spiritStoneCards: List<Pair<String, SpiritStoneInfo>>
)

/** 仓库网格分页参数（WarehouseTab 拆分，参数 >6 规避 LongParameterList） */
private data class WarehouseGridConfig(
    val pageItems: List<WarehouseItemData>,
    val columns: Int,
    val spacing: Dp,
    val cellSize: Dp
)

/** 仓库物品详情元数据（WarehouseTab 拆分，参数 >6 规避 LongParameterList） */
private data class WarehouseDetailItem(
    val itemId: String,
    val itemType: String,
    val itemQuantity: Int,
    val isLocked: Boolean,
    val itemRarity: Int,
    val itemName: String
)

@Composable
internal fun WarehouseTab(
    viewModel: GameViewModel,
    showBulkSellDialog: Boolean = false,
    onBulkSellDismiss: () -> Unit = {},
    onDismiss: () -> Unit = {}
) {
    val flows = collectWarehouseFlows(viewModel = viewModel)
    var selectedFilter by remember { mutableStateOf(WarehouseFilter.ALL) }
    var showDetailDialog by remember { mutableStateOf(false) }
    var selectedItemId by remember { mutableStateOf<String?>(null) }
    var currentPage by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val state = rememberWarehouseState(flows = flows)

    WarehouseContent(
        state = state,
        selectedFilter = selectedFilter,
        currentPage = currentPage,
        selectedItemId = selectedItemId,
        actions = WarehouseActions(
            onFilterSelected = { filter ->
                if (selectedFilter != filter) {
                    selectedFilter = filter
                    currentPage = 0
                    selectedItemId = null
                }
            },
            onPageChange = { page ->
                currentPage = page
                selectedItemId = null
            },
            onPageClamp = { currentPage = it },
            onItemSelect = { id ->
                selectedItemId = if (selectedItemId == id) null else id
            },
            onItemLongPress = { id ->
                selectedItemId = id
                showDetailDialog = true
            },
            onOpenBag = { id ->
                scope.launch { viewModel.openStorageBag(id) }
            }
        )
    )

    if (showDetailDialog) {
        WarehouseItemDetailSection(
            state = state,
            selectedItemId = selectedItemId,
            viewModel = viewModel,
            onDismiss = { showDetailDialog = false; selectedItemId = null }
        )
    }

    WarehouseTrailingDialogs(
        bagRewardCards = flows.bagRewardCards,
        showBulkSellDialog = showBulkSellDialog,
        viewModel = viewModel,
        onBulkSellDismiss = onBulkSellDismiss
    )
}

/** 仓库数据流订阅（WarehouseTab 拆分） */
@Composable
private fun collectWarehouseFlows(viewModel: GameViewModel): WarehouseFlows {
    val equipmentStacks by viewModel.equipmentStacks.collectAsStateWithLifecycle()
    val manualStacks by viewModel.manualStacks.collectAsStateWithLifecycle()
    val pills by viewModel.pills.collectAsStateWithLifecycle()
    val materials by viewModel.materials.collectAsStateWithLifecycle()
    val herbs by viewModel.herbs.collectAsStateWithLifecycle()
    val seeds by viewModel.seeds.collectAsStateWithLifecycle()
    val storageBags by viewModel.storageBags.collectAsStateWithLifecycle()
    val spiritStoneTotals by viewModel.spiritStoneTotals.collectAsStateWithLifecycle()
    val watchedKeys by viewModel.watchedItemIds.collectAsStateWithLifecycle()
    val bagRewardCards by viewModel.bagRewardCards.collectAsStateWithLifecycle()
    return WarehouseFlows(
        equipmentStacks = equipmentStacks,
        manualStacks = manualStacks,
        pills = pills,
        materials = materials,
        herbs = herbs,
        seeds = seeds,
        storageBags = storageBags,
        spiritStoneTotals = spiritStoneTotals,
        watchedKeys = watchedKeys,
        bagRewardCards = bagRewardCards
    )
}

/** 仓库派生状态计算（WarehouseTab 拆分） */
@Composable
private fun rememberWarehouseState(flows: WarehouseFlows): WarehouseState {
    val spiritStoneCards = rememberSpiritStoneCards(spiritStoneTotals = flows.spiritStoneTotals)
    val equipment = remember(flows.equipmentStacks, flows.watchedKeys) {
        flows.equipmentStacks.sortedByWatchedThenRarity(flows.watchedKeys)
    }
    val manuals = remember(flows.manualStacks, flows.watchedKeys) {
        flows.manualStacks.sortedByWatchedThenRarity(flows.watchedKeys)
    }
    val sortedPills = remember(flows.pills, flows.watchedKeys) {
        flows.pills.sortedByWatchedThenRarity(flows.watchedKeys)
    }
    val sortedMaterials = remember(flows.materials, flows.watchedKeys) {
        flows.materials.sortedByWatchedThenRarity(flows.watchedKeys)
    }
    val sortedHerbs = remember(flows.herbs, flows.watchedKeys) {
        flows.herbs.sortedByWatchedThenRarity(flows.watchedKeys)
    }
    val sortedSeeds = remember(flows.seeds, flows.watchedKeys) {
        flows.seeds.sortedByWatchedThenRarity(flows.watchedKeys)
    }
    // 储物袋不可关注，保持原排序
    val sortedBags = remember(flows.storageBags) {
        flows.storageBags.sortedWith(compareByDescending<StorageBag> { it.rarity }.thenBy { it.name })
    }
    val allSortedItems = rememberAllSortedItems(
        items = WarehouseSortedItems(
            equipment = equipment,
            manuals = manuals,
            sortedPills = sortedPills,
            sortedMaterials = sortedMaterials,
            sortedHerbs = sortedHerbs,
            sortedSeeds = sortedSeeds,
            sortedBags = sortedBags,
            spiritStoneCards = spiritStoneCards
        ),
        watchedKeys = flows.watchedKeys
    )
    return WarehouseState(
        spiritStoneCards = spiritStoneCards,
        equipment = equipment,
        manuals = manuals,
        sortedPills = sortedPills,
        sortedMaterials = sortedMaterials,
        sortedHerbs = sortedHerbs,
        sortedSeeds = sortedSeeds,
        sortedBags = sortedBags,
        allSortedItems = allSortedItems,
        watchedKeys = flows.watchedKeys
    )
}

/** 灵石卡片分块（WarehouseTab 拆分）：按 100 万上限拆分为多张卡片 */
@Composable
private fun rememberSpiritStoneCards(
    spiritStoneTotals: GameViewModel.SpiritStoneTotals
): List<Pair<String, SpiritStoneInfo>> {
    return remember(spiritStoneTotals) {
        val cards = mutableListOf<Pair<String, SpiritStoneInfo>>()
        fun addCard(grade: SpiritStoneGrade, total: Long) {
            var remaining = total
            var index = 0
            while (remaining > 0) {
                val qty = minOf(remaining, 1_000_000L)
                cards.add("spirit_stone_${grade.name.lowercase()}_$index" to SpiritStoneInfo(grade, qty))
                remaining -= qty
                index++
            }
        }
        addCard(SpiritStoneGrade.LOW, spiritStoneTotals.low)
        addCard(SpiritStoneGrade.MID, spiritStoneTotals.mid)
        addCard(SpiritStoneGrade.HIGH, spiritStoneTotals.high)
        cards
    }
}

/** 全量物品合并排序（WarehouseTab 拆分）：关注优先 → 稀有度降序 */
@Composable
private fun rememberAllSortedItems(
    items: WarehouseSortedItems,
    watchedKeys: Set<String>
): List<WarehouseItemData> {
    return remember(
        items.equipment, items.manuals, items.sortedPills, items.sortedMaterials,
        items.sortedHerbs, items.sortedSeeds, items.sortedBags, items.spiritStoneCards, watchedKeys
    ) {
        val merged = mutableListOf<WarehouseItemData>()
        items.equipment.forEach { merged.add(WarehouseItemData(it.id, it.name, it.rarity, it)) }
        items.manuals.forEach { merged.add(WarehouseItemData(it.id, it.name, it.rarity, it)) }
        items.sortedPills.forEach { merged.add(WarehouseItemData(it.id, it.name, it.rarity, it)) }
        items.sortedMaterials.forEach { merged.add(WarehouseItemData(it.id, it.name, it.rarity, it)) }
        items.sortedHerbs.forEach { merged.add(WarehouseItemData(it.id, it.name, it.rarity, it)) }
        items.sortedSeeds.forEach { merged.add(WarehouseItemData(it.id, it.name, it.rarity, it)) }
        items.sortedBags.forEach { merged.add(WarehouseItemData(it.id, it.name, it.rarity, it)) }
        items.spiritStoneCards.forEach { (id, info) ->
            merged.add(0, WarehouseItemData(id, info.grade.displayName, when (info.grade) {
                SpiritStoneGrade.LOW -> 1
                SpiritStoneGrade.MID -> 3
                SpiritStoneGrade.HIGH -> 5
            }, info))
        }
        merged.sortedByWatchedThenRarity(
            watchedKeys,
            keyOf = { watchKeyOf(it.item) },
            rarityOf = { it.rarity },
            nameOf = { it.name }
        )
    }
}

/** 仓库主内容区（WarehouseTab 拆分）：筛选行 + 网格或空态 */
@Composable
private fun WarehouseContent(
    state: WarehouseState,
    selectedFilter: WarehouseFilter,
    currentPage: Int,
    selectedItemId: String?,
    actions: WarehouseActions
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        WarehouseFilterRow(
            selectedFilter = selectedFilter,
            onFilterSelected = actions.onFilterSelected
        )

        Spacer(modifier = Modifier.height(12.dp))

        val currentFilterItems = rememberCurrentFilterItems(
            state = state,
            selectedFilter = selectedFilter
        )
        if (currentFilterItems.isEmpty()) {
            EmptyWarehouseMessage()
        } else {
            WarehouseGrid(
                state = state,
                currentFilterItems = currentFilterItems,
                currentPage = currentPage,
                selectedItemId = selectedItemId,
                actions = actions
            )
        }
    }
}

/** 当前筛选物品列表（WarehouseTab 拆分） */
@Composable
private fun rememberCurrentFilterItems(
    state: WarehouseState,
    selectedFilter: WarehouseFilter
): List<WarehouseItemData> {
    return remember(
        selectedFilter, state.allSortedItems, state.equipment, state.sortedPills, state.manuals,
        state.sortedHerbs, state.sortedSeeds, state.sortedMaterials, state.spiritStoneCards,
        state.sortedBags, state.watchedKeys
    ) {
        when (selectedFilter) {
            WarehouseFilter.ALL -> state.allSortedItems
            WarehouseFilter.EQUIPMENT -> state.equipment.map { WarehouseItemData(it.id, it.name, it.rarity, it) }
            WarehouseFilter.PILL -> state.sortedPills.map { WarehouseItemData(it.id, it.name, it.rarity, it) }
            WarehouseFilter.MANUAL -> state.manuals.map { WarehouseItemData(it.id, it.name, it.rarity, it) }
            WarehouseFilter.HERB -> state.sortedHerbs.map { WarehouseItemData(it.id, it.name, it.rarity, it) }
            WarehouseFilter.SEED -> state.sortedSeeds.map { WarehouseItemData(it.id, it.name, it.rarity, it) }
            WarehouseFilter.MATERIAL -> {
                val items = mutableListOf<WarehouseItemData>()
                state.sortedMaterials.forEach { items.add(WarehouseItemData(it.id, it.name, it.rarity, it)) }
                state.sortedBags.forEach { items.add(WarehouseItemData(it.id, it.name, it.rarity, it)) }
                state.spiritStoneCards.forEach { (id, info) ->
                    items.add(0, WarehouseItemData(id, info.grade.displayName, when (info.grade) {
                        SpiritStoneGrade.LOW -> 1
                        SpiritStoneGrade.MID -> 3
                        SpiritStoneGrade.HIGH -> 5
                    }, info))
                }
                items
            }
        }
    }
}

/** 仓库筛选按钮行（WarehouseTab 拆分） */
@Composable
private fun WarehouseFilterRow(
    selectedFilter: WarehouseFilter,
    onFilterSelected: (WarehouseFilter) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WarehouseFilter.entries.forEach { filter ->
            WarehouseFilterButton(
                text = filter.displayName,
                selected = selectedFilter == filter,
                onClick = { onFilterSelected(filter) }
            )
        }
    }
}

/** 仓库分页网格（WarehouseTab 拆分）：BoxWithConstraints 计算列/行 + 分页 */
@Composable
private fun ColumnScope.WarehouseGrid(
    state: WarehouseState,
    currentFilterItems: List<WarehouseItemData>,
    currentPage: Int,
    selectedItemId: String?,
    actions: WarehouseActions
) {
    BoxWithConstraints(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
    ) {
        val cellSize = 60.dp
        val spacing = 8.dp
        val paginationHeight = 44.dp
        val availableHeight = maxHeight - paginationHeight
        val columns = maxOf(1, ((maxWidth + spacing) / (cellSize + spacing)).toInt())
        val rows = maxOf(1, ((availableHeight + spacing) / (cellSize + spacing)).toInt())
        val pageSize = columns * rows
        val totalPages = remember(currentFilterItems, pageSize) {
            maxOf(1, (currentFilterItems.size + pageSize - 1) / pageSize)
        }
        val safeCurrentPage = currentPage.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
        if (safeCurrentPage != currentPage) {
            SideEffect { actions.onPageClamp(safeCurrentPage) }
        }
        val pageItems = remember(currentFilterItems, safeCurrentPage, pageSize) {
            val start = safeCurrentPage * pageSize
            if (start < currentFilterItems.size) {
                currentFilterItems.drop(start).take(pageSize)
            } else {
                emptyList()
            }
        }
        Column(modifier = Modifier.fillMaxSize()) {
            WarehouseGridRows(
                config = WarehouseGridConfig(
                    pageItems = pageItems,
                    columns = columns,
                    spacing = spacing,
                    cellSize = cellSize
                ),
                state = state,
                selectedItemId = selectedItemId,
                onItemSelect = actions.onItemSelect,
                onItemLongPress = actions.onItemLongPress,
                onOpenBag = actions.onOpenBag
            )
            Spacer(modifier = Modifier.height(8.dp))
            WarehousePagination(
                currentPage = safeCurrentPage + 1,
                totalPages = totalPages,
                onPreviousPage = {
                    if (currentPage > 0) { actions.onPageChange(currentPage - 1) }
                },
                onNextPage = {
                    if (currentPage < totalPages - 1) { actions.onPageChange(currentPage + 1) }
                },
                onFirstPage = { actions.onPageChange(0) },
                onLastPage = { actions.onPageChange(totalPages - 1) }
            )
        }
    }
}

/** 仓库网格行区（WarehouseTab 拆分）：按列数分行的物品卡 */
@Composable
private fun ColumnScope.WarehouseGridRows(
    config: WarehouseGridConfig,
    state: WarehouseState,
    selectedItemId: String?,
    onItemSelect: (String) -> Unit,
    onItemLongPress: (String) -> Unit,
    onOpenBag: (String) -> Unit
) {
    Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(config.spacing)
    ) {
        config.pageItems.chunked(config.columns).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(config.spacing)
            ) {
                rowItems.forEach { warehouseItem ->
                    WarehouseGridCard(
                        warehouseItem = warehouseItem,
                        selectedItemId = selectedItemId,
                        watchedKeys = state.watchedKeys,
                        onSelect = onItemSelect,
                        onLongPress = onItemLongPress,
                        onOpenBag = onOpenBag
                    )
                }
                repeat(config.columns - rowItems.size) {
                    Spacer(modifier = Modifier.size(config.cellSize))
                }
            }
        }
    }
}

/** 仓库网格物品卡（WarehouseTab 拆分）：构造 ItemCardData 复用 UnifiedItemCard */
@Composable
private fun WarehouseGridCard(
    warehouseItem: WarehouseItemData,
    selectedItemId: String?,
    watchedKeys: Set<String>,
    onSelect: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onOpenBag: (String) -> Unit
) {
    UnifiedItemCard(
        data = ItemCardData(
            id = warehouseItem.id,
            name = warehouseItem.name,
            rarity = warehouseItem.rarity,
            quantity = when (val item = warehouseItem.item) {
                is EquipmentStack -> item.quantity
                is ManualStack -> item.quantity
                is Pill -> item.quantity
                is Material -> item.quantity
                is Herb -> item.quantity
                is Seed -> item.quantity
                is StorageBag -> item.quantity
                is SpiritStoneInfo -> item.quantity.toInt()
                else -> 1
            },
            grade = (warehouseItem.item as? Pill)?.grade?.displayName,
            isLocked = getWarehouseItemIsLocked(warehouseItem.item),
            isManual = warehouseItem.item is ManualStack,
            isPill = warehouseItem.item is Pill,
            isMaterial = warehouseItem.item is Material,
            isHerb = warehouseItem.item is Herb,
            isSeed = warehouseItem.item is Seed,
            spiritStoneGrade = (warehouseItem.item as? SpiritStoneInfo)?.grade,
            isBag = warehouseItem.item is StorageBag
        ),
        isSelected = selectedItemId == warehouseItem.id,
        isFollowed = watchKeyOf(warehouseItem.item)
            ?.let { it in watchedKeys } ?: false,
        onLongPress = {
            onLongPress(warehouseItem.id)
        },
        overlayButtonText = if (warehouseItem.item is StorageBag) "开启" else null,
        onOverlayButtonClick = if (warehouseItem.item is StorageBag) {
            {
                onOpenBag(warehouseItem.id)
            }
        } else null,
        onClick = { onSelect(warehouseItem.id) }
    )
}

/** 仓库物品详情弹窗（WarehouseTab 拆分）：内联出售覆盖层 + 操作行 + 赏赐弹窗 */
@Composable
private fun WarehouseItemDetailSection(
    state: WarehouseState,
    selectedItemId: String?,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val item = rememberWarehouseSelectedItem(state = state, selectedItemId = selectedItemId)
    if (item == null) {
        onDismiss()
        return
    }
    val detail = warehouseDetailItem(item = item, state = state)
    var showDiscipleSelectDialog by remember { mutableStateOf(false) }
    var showSellDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    ItemDetailDialog(
        item = item,
        onDismiss = onDismiss,
        viewModel = viewModel,
        // 出售数量确认（内联覆盖层）必须渲染在 ItemDetailDialog 窗口内容内，
        // 否则被其 SmallScreenDialog 平台窗口遮挡而不可见（2026-08 键盘频闪根治）
        overlay = {
            if (showSellDialog) {
                WarehouseSellOverlay(
                    itemName = detail.itemName,
                    maxQuantity = detail.itemQuantity,
                    onConfirm = { quantity ->
                        viewModel.sellItem(detail.itemId, detail.itemType, quantity)
                        showSellDialog = false
                        if (quantity >= detail.itemQuantity) {
                            onDismiss()
                        }
                    },
                    onDismiss = { showSellDialog = false }
                )
            }
        },
        // 灵石详情无任何操作按钮（不可售卖/锁定/赏赐/关注）
        extraActions = if (item is SpiritStoneInfo) {
            null
        } else {
            {
                WarehouseDetailActionRow(
                    item = item,
                    detail = detail,
                    scope = scope,
                    viewModel = viewModel,
                    onSellClick = { showSellDialog = true },
                    onGiftClick = { showDiscipleSelectDialog = true }
                )
            }
        }
    )
    if (showDiscipleSelectDialog) {
        DiscipleSelectForRewardDialog(
            itemName = detail.itemName,
            itemId = detail.itemId,
            itemType = detail.itemType,
            itemRarity = detail.itemRarity,
            viewModel = viewModel,
            onDismiss = { showDiscipleSelectDialog = false }
        )
    }
}

/** 仓库选中物品解析（WarehouseTab 拆分）：灵石卡片/物品索引派生 */
@Composable
private fun rememberWarehouseSelectedItem(
    state: WarehouseState,
    selectedItemId: String?
): Any? {
    val itemIndex = remember(state.allSortedItems) {
        state.allSortedItems.associateBy { it.id }
    }
    return remember(selectedItemId, itemIndex) {
        derivedStateOf {
            selectedItemId?.let { id ->
                if (id.startsWith("spirit_stone_")) {
                    state.spiritStoneCards.find { it.first == id }?.second
                } else {
                    itemIndex[id]?.item
                }
            }
        }
    }.value
}

/** 仓库物品详情元数据解析（WarehouseTab 拆分） */
private fun warehouseDetailItem(
    item: Any,
    state: WarehouseState
): WarehouseDetailItem {
    val ref = warehouseItemRef(item)
    val currentItem = when (ref.type) {
        "equipment" -> state.equipment.find { it.id == ref.id }
        "manual" -> state.manuals.find { it.id == ref.id }
        "pill" -> state.sortedPills.find { it.id == ref.id }
        "material" -> state.sortedMaterials.find { it.id == ref.id }
        "herb" -> state.sortedHerbs.find { it.id == ref.id }
        "seed" -> state.sortedSeeds.find { it.id == ref.id }
        else -> null
    }
    return WarehouseDetailItem(
        itemId = ref.id,
        itemType = ref.type,
        itemQuantity = currentItem?.quantity ?: 0,
        isLocked = currentItem?.isLocked ?: false,
        itemRarity = ref.rarity,
        itemName = ref.name
    )
}

/** 仓库物品基础元数据（WarehouseTab 拆分）：id/类型/稀有度/名称单次 when 解析 */
private data class WarehouseItemRef(
    val id: String,
    val type: String,
    val rarity: Int,
    val name: String
)

/** 仓库物品基础元数据解析（WarehouseTab 拆分） */
private fun warehouseItemRef(item: Any): WarehouseItemRef = when (item) {
    is EquipmentStack -> WarehouseItemRef(item.id, "equipment", item.rarity, item.name)
    is ManualStack -> WarehouseItemRef(item.id, "manual", item.rarity, item.name)
    is Pill -> WarehouseItemRef(item.id, "pill", item.rarity, item.name)
    is Material -> WarehouseItemRef(item.id, "material", item.rarity, item.name)
    is Herb -> WarehouseItemRef(item.id, "herb", item.rarity, item.name)
    is Seed -> WarehouseItemRef(item.id, "seed", item.rarity, item.name)
    else -> WarehouseItemRef("", "", 1, "")
}

/** 出售数量确认覆盖层（WarehouseTab 拆分） */
@Composable
private fun WarehouseSellOverlay(
    itemName: String,
    maxQuantity: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    SellConfirmDialog(
        itemName = itemName,
        maxQuantity = maxQuantity,
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

/** 物品详情操作行（WarehouseTab 拆分）：全部开启/售卖/锁定/赏赐 */
@Composable
private fun WarehouseDetailActionRow(
    item: Any,
    detail: WarehouseDetailItem,
    scope: CoroutineScope,
    viewModel: GameViewModel,
    onSellClick: () -> Unit,
    onGiftClick: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            // 储物袋详情：仅保留全部开启
            item is StorageBag -> GameButton(
                text = "全部开启",
                onClick = {
                    scope.launch {
                        viewModel.openAllStorageBags(item.id)
                    }
                }
            )
            else -> {
                if (!detail.isLocked) {
                    GameButton(
                        text = "售卖",
                        onClick = onSellClick
                    )
                }
                GameButton(
                    text = if (detail.isLocked) "已锁定" else "锁定",
                    onClick = { viewModel.toggleItemLock(detail.itemId, detail.itemType) }
                )
                GameButton(
                    text = "赏赐",
                    onClick = onGiftClick
                )
            }
        }
    }
}

/** 仓库尾部弹窗（WarehouseTab 拆分）：储物袋开启奖励 + 批量出售 */
@Composable
private fun WarehouseTrailingDialogs(
    bagRewardCards: List<RewardCardItem>,
    showBulkSellDialog: Boolean,
    viewModel: GameViewModel,
    onBulkSellDismiss: () -> Unit
) {
    if (bagRewardCards.isNotEmpty()) {
        RewardDisplayDialog(
            title = "储物袋开启",
            cards = bagRewardCards,
            confirmLabel = "确认",
            onConfirm = { viewModel.enqueueBagRewardCards() }
        )
    }

    if (showBulkSellDialog) {
        BulkSellDialog(
            viewModel = viewModel,
            onDismiss = onBulkSellDismiss
        )
    }
}

@Composable
internal fun WarehouseFilterButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val contentAlpha = if (selected) 1f else 0.6f
    Box(
        modifier = Modifier
            .width(ButtonSizes.StandardWidth)
            .height(ButtonSizes.StandardHeight)
            .alpha(contentAlpha)
            .clip(RoundedCornerShape(4.dp))
            .clickableWithSound { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ui_button),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds
        )
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) Color.White else Color.Black
        )
    }
}

@Composable
internal fun WarehousePagination(
    currentPage: Int,
    totalPages: Int,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onFirstPage: () -> Unit,
    onLastPage: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        WarehousePaginationButton(
            text = "<<", fontSize = 10.sp, enabled = currentPage > 1, onClick = onFirstPage
        )

        Spacer(modifier = Modifier.width(8.dp))

        WarehousePaginationButton(
            text = "<", fontSize = 12.sp, enabled = currentPage > 1, onClick = onPreviousPage
        )

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = "第 $currentPage/$totalPages 页",
            fontSize = 12.sp,
            color = Color.Black,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.width(12.dp))

        WarehousePaginationButton(
            text = ">", fontSize = 12.sp, enabled = currentPage < totalPages, onClick = onNextPage
        )

        Spacer(modifier = Modifier.width(8.dp))

        WarehousePaginationButton(
            text = ">>", fontSize = 10.sp, enabled = currentPage < totalPages, onClick = onLastPage
        )
    }
}

/** 分页按钮（WarehousePagination 拆分） */
@Composable
private fun WarehousePaginationButton(
    text: String,
    fontSize: TextUnit,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (enabled) Color(0xFF3498DB) else GameColors.DividerGray)
            .clickableWithSound(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = fontSize,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
internal fun EmptyWarehouseMessage() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "暂无物品",
            fontSize = 12.sp,
            color = Color.Black
        )
    }
}

internal fun getRarityColor(rarity: Int): Color = com.xianxia.sect.ui.theme.getRarityColor(rarity)
