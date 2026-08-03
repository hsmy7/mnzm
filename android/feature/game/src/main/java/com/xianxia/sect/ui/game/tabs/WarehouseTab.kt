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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
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

@Composable
internal fun WarehouseTab(
    viewModel: GameViewModel,
    showBulkSellDialog: Boolean = false,
    onBulkSellDismiss: () -> Unit = {},
    onDismiss: () -> Unit = {}
) {
    val equipmentStacks by viewModel.equipmentStacks.collectAsStateWithLifecycle()
    val manualStacks by viewModel.manualStacks.collectAsStateWithLifecycle()
    val pills by viewModel.pills.collectAsStateWithLifecycle()
    val materials by viewModel.materials.collectAsStateWithLifecycle()
    val herbs by viewModel.herbs.collectAsStateWithLifecycle()
    val seeds by viewModel.seeds.collectAsStateWithLifecycle()
    val storageBags by viewModel.storageBags.collectAsStateWithLifecycle()
    val gameData by viewModel.gameData.collectAsStateWithLifecycle()

    val spiritStoneCards = remember(gameData) {
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
        addCard(SpiritStoneGrade.LOW, gameData.spiritStones)
        addCard(SpiritStoneGrade.MID, gameData.midGradeSpiritStones)
        addCard(SpiritStoneGrade.HIGH, gameData.highGradeSpiritStones)
        cards
    }

    val watchedKeys by viewModel.watchedItemIds.collectAsStateWithLifecycle()

    val equipment = remember(equipmentStacks, watchedKeys) {
        equipmentStacks.sortedByWatchedThenRarity(watchedKeys)
    }

    val manuals = remember(manualStacks, watchedKeys) {
        manualStacks.sortedByWatchedThenRarity(watchedKeys)
    }

    val sortedPills = remember(pills, watchedKeys) {
        pills.sortedByWatchedThenRarity(watchedKeys)
    }

    val sortedMaterials = remember(materials, watchedKeys) {
        materials.sortedByWatchedThenRarity(watchedKeys)
    }

    val sortedHerbs = remember(herbs, watchedKeys) {
        herbs.sortedByWatchedThenRarity(watchedKeys)
    }

    val sortedSeeds = remember(seeds, watchedKeys) {
        seeds.sortedByWatchedThenRarity(watchedKeys)
    }

    // 储物袋不可关注，保持原排序
    val sortedBags = remember(storageBags) {
        storageBags.sortedWith(compareByDescending<StorageBag> { it.rarity }.thenBy { it.name })
    }

    data class WarehouseItemData(
        val id: String,
        val name: String,
        val rarity: Int,
        val item: Any
    )

    val allSortedItems = remember(equipment, manuals, sortedPills, sortedMaterials, sortedHerbs, sortedSeeds, sortedBags, spiritStoneCards, watchedKeys) {
        val items = mutableListOf<WarehouseItemData>()
        equipment.forEach { items.add(WarehouseItemData(it.id, it.name, it.rarity, it)) }
        manuals.forEach { items.add(WarehouseItemData(it.id, it.name, it.rarity, it)) }
        sortedPills.forEach { items.add(WarehouseItemData(it.id, it.name, it.rarity, it)) }
        sortedMaterials.forEach { items.add(WarehouseItemData(it.id, it.name, it.rarity, it)) }
        sortedHerbs.forEach { items.add(WarehouseItemData(it.id, it.name, it.rarity, it)) }
        sortedSeeds.forEach { items.add(WarehouseItemData(it.id, it.name, it.rarity, it)) }
        sortedBags.forEach { items.add(WarehouseItemData(it.id, it.name, it.rarity, it)) }
        spiritStoneCards.forEach { (id, info) ->
            items.add(0, WarehouseItemData(id, info.grade.displayName, when (info.grade) {
                SpiritStoneGrade.LOW -> 1
                SpiritStoneGrade.MID -> 3
                SpiritStoneGrade.HIGH -> 5
            }, info))
        }
        items.sortedByWatchedThenRarity(
            watchedKeys,
            keyOf = { watchKeyOf(it.item) },
            rarityOf = { it.rarity },
            nameOf = { it.name }
        )
    }

    var selectedFilter by remember { mutableStateOf(WarehouseFilter.ALL) }
    var showDetailDialog by remember { mutableStateOf(false) }
    var selectedItemId by remember { mutableStateOf<String?>(null) }
    val bagRewardCards by viewModel.bagRewardCards.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    val itemIndex = remember(allSortedItems) {
        allSortedItems.associateBy { it.id }
    }
    val selectedItem by remember(selectedItemId, itemIndex) {
        derivedStateOf {
            selectedItemId?.let { id ->
                if (id.startsWith("spirit_stone_")) {
                    spiritStoneCards.find { it.first == id }?.second
                } else {
                    itemIndex[id]?.item
                }
            }
        }
    }
    var currentPage by remember { mutableIntStateOf(0) }

    val currentFilterItems = remember(selectedFilter, allSortedItems, equipment, sortedPills, manuals, sortedHerbs, sortedSeeds, sortedMaterials, spiritStoneCards, sortedBags, watchedKeys) {
        when (selectedFilter) {
            WarehouseFilter.ALL -> allSortedItems
            WarehouseFilter.EQUIPMENT -> equipment.map { WarehouseItemData(it.id, it.name, it.rarity, it) }
            WarehouseFilter.PILL -> sortedPills.map { WarehouseItemData(it.id, it.name, it.rarity, it) }
            WarehouseFilter.MANUAL -> manuals.map { WarehouseItemData(it.id, it.name, it.rarity, it) }
            WarehouseFilter.HERB -> sortedHerbs.map { WarehouseItemData(it.id, it.name, it.rarity, it) }
            WarehouseFilter.SEED -> sortedSeeds.map { WarehouseItemData(it.id, it.name, it.rarity, it) }
            WarehouseFilter.MATERIAL -> {
                val items = mutableListOf<WarehouseItemData>()
                sortedMaterials.forEach { items.add(WarehouseItemData(it.id, it.name, it.rarity, it)) }
                sortedBags.forEach { items.add(WarehouseItemData(it.id, it.name, it.rarity, it)) }
                spiritStoneCards.forEach { (id, info) ->
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

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
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
                    onClick = {
                        if (selectedFilter != filter) {
                            selectedFilter = filter
                            currentPage = 0
                            selectedItemId = null
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (currentFilterItems.isEmpty()) {
            EmptyWarehouseMessage()
        } else {
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
                    SideEffect { currentPage = safeCurrentPage }
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
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(spacing)
                    ) {
                        pageItems.chunked(columns).forEach { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(spacing)
                            ) {
                                rowItems.forEach { warehouseItem ->
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
                                            selectedItemId = warehouseItem.id
                                            showDetailDialog = true
                                        },
                                        overlayButtonText = if (warehouseItem.item is StorageBag) "开启" else null,
                                        onOverlayButtonClick = if (warehouseItem.item is StorageBag) {
                                            {
                                                coroutineScope.launch {
                                                    viewModel.openStorageBag(warehouseItem.id)
                                                }
                                            }
                                        } else null,
                                        onClick = { selectedItemId = if (selectedItemId == warehouseItem.id) null else warehouseItem.id }
                                    )
                                }
                                repeat(columns - rowItems.size) {
                                    Spacer(modifier = Modifier.size(cellSize))
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    WarehousePagination(
                        currentPage = safeCurrentPage + 1,
                        totalPages = totalPages,
                        onPreviousPage = {
                            if (currentPage > 0) { currentPage--; selectedItemId = null }
                        },
                        onNextPage = {
                            if (currentPage < totalPages - 1) { currentPage++; selectedItemId = null }
                        },
                        onFirstPage = { currentPage = 0; selectedItemId = null },
                        onLastPage = { currentPage = totalPages - 1; selectedItemId = null }
                    )
                }
            }
        }
        }

    if (showDetailDialog && selectedItem == null) {
        showDetailDialog = false
        selectedItemId = null
    }

    if (showDetailDialog) {
        selectedItem?.let { item ->
            val itemId = when (item) {
                is EquipmentStack -> item.id
                is ManualStack -> item.id
                is Pill -> item.id
                is Material -> item.id
                is Herb -> item.id
                is Seed -> item.id
                else -> ""
            }
            val itemType = when (item) {
                is EquipmentStack -> "equipment"
                is ManualStack -> "manual"
                is Pill -> "pill"
                is Material -> "material"
                is Herb -> "herb"
                is Seed -> "seed"
                else -> ""
            }
            val currentItem = when (itemType) {
                "equipment" -> equipment.find { it.id == itemId }
                "manual" -> manuals.find { it.id == itemId }
                "pill" -> sortedPills.find { it.id == itemId }
                "material" -> sortedMaterials.find { it.id == itemId }
                "herb" -> sortedHerbs.find { it.id == itemId }
                "seed" -> sortedSeeds.find { it.id == itemId }
                else -> null
            }
            val itemQuantity = currentItem?.quantity ?: 0
            val isLocked = currentItem?.isLocked ?: false
            val itemRarity = when (item) {
                is EquipmentStack -> item.rarity
                is ManualStack -> item.rarity
                is Pill -> item.rarity
                is Material -> item.rarity
                is Herb -> item.rarity
                is Seed -> item.rarity
                else -> 1
            }
            val itemName = when (item) {
                is EquipmentStack -> item.name
                is ManualStack -> item.name
                is Pill -> item.name
                is Material -> item.name
                is Herb -> item.name
                is Seed -> item.name
                else -> ""
            }
            var showDiscipleSelectDialog by remember { mutableStateOf(false) }
            var showSellDialog by remember { mutableStateOf(false) }

            ItemDetailDialog(
                item = item,
                onDismiss = {
                    showDetailDialog = false
                    selectedItemId = null
                },
                viewModel = viewModel,
                // 灵石详情无任何操作按钮（不可售卖/锁定/赏赐/关注）
                extraActions = if (item is SpiritStoneInfo) {
                    null
                } else {
                    {
                        val scope = rememberCoroutineScope()
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
                                    if (!isLocked) {
                                        GameButton(
                                            text = "售卖",
                                            onClick = { showSellDialog = true }
                                        )
                                    }
                                    GameButton(
                                        text = if (isLocked) "已锁定" else "锁定",
                                        onClick = { viewModel.toggleItemLock(itemId, itemType) }
                                    )
                                    GameButton(
                                        text = "赏赐",
                                        onClick = { showDiscipleSelectDialog = true }
                                    )
                                }
                            }
                        }
                    }
                }
            )

            if (showSellDialog) {
                SellConfirmDialog(
                    itemName = itemName,
                    maxQuantity = itemQuantity,
                    onConfirm = { quantity ->
                        viewModel.sellItem(itemId, itemType, quantity)
                        showSellDialog = false
                        if (quantity >= itemQuantity) {
                            showDetailDialog = false
                            selectedItemId = null
                        }
                    },
                    onDismiss = { showSellDialog = false }
                )
            }

            if (showDiscipleSelectDialog) {
                DiscipleSelectForRewardDialog(
                    itemName = itemName,
                    itemId = itemId,
                    itemType = itemType,
                    itemRarity = itemRarity,
                    viewModel = viewModel,
                    onDismiss = { showDiscipleSelectDialog = false }
                )
            }
        }
    }

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
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (currentPage > 1) GameColors.Info else GameColors.DividerGray)
                .clickableWithSound(enabled = currentPage > 1) { onFirstPage() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "<<",
                fontSize = 10.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (currentPage > 1) GameColors.Info else GameColors.DividerGray)
                .clickableWithSound(enabled = currentPage > 1) { onPreviousPage() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "<",
                fontSize = 12.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = "第 $currentPage/$totalPages 页",
            fontSize = 12.sp,
            color = Color.Black,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.width(12.dp))

        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (currentPage < totalPages) GameColors.Info else GameColors.DividerGray)
                .clickableWithSound(enabled = currentPage < totalPages) { onNextPage() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = ">",
                fontSize = 12.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (currentPage < totalPages) GameColors.Info else GameColors.DividerGray)
                .clickableWithSound(enabled = currentPage < totalPages) { onLastPage() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = ">>",
                fontSize = 10.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
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
