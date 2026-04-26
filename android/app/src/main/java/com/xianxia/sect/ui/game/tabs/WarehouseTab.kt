package com.xianxia.sect.ui.game.tabs

import androidx.compose.animation.*
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Dialog
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.RewardSelectedItem
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.util.isFollowed
import com.xianxia.sect.ui.components.DiscipleAttrText
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.ui.game.ATTRIBUTE_FILTER_OPTIONS
import com.xianxia.sect.ui.game.AttributeFilterOption
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.SPIRIT_ROOT_FILTER_OPTIONS
import com.xianxia.sect.ui.game.applyFilters
import com.xianxia.sect.ui.game.components.ItemDetailDialog
import com.xianxia.sect.ui.game.components.SpiritRootAttributeFilterBar
import com.xianxia.sect.ui.game.getAttributeValue
import com.xianxia.sect.ui.game.getSpiritRootCount
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

internal fun getWarehouseItemBasePrice(item: Any): Int = when (item) {
    is EquipmentStack -> item.basePrice
    is ManualStack -> item.basePrice
    is Pill -> item.basePrice
    is Material -> item.basePrice
    is Herb -> item.basePrice
    is Seed -> item.basePrice
    else -> 0
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

@Composable
internal fun WarehouseTab(viewModel: GameViewModel) {
    val equipmentStacks by viewModel.equipmentStacks.collectAsState()
    val manualStacks by viewModel.manualStacks.collectAsState()
    val pills by viewModel.pills.collectAsState()
    val materials by viewModel.materials.collectAsState()
    val herbs by viewModel.herbs.collectAsState()
    val seeds by viewModel.seeds.collectAsState()
    
    val equipment = remember(equipmentStacks) {
        equipmentStacks.sortedWith(compareByDescending<EquipmentStack> { it.rarity }.thenBy { it.name })
    }
    
    val manuals = remember(manualStacks) {
        manualStacks.sortedWith(compareByDescending<ManualStack> { it.rarity }.thenBy { it.name })
    }
    
    val sortedPills = remember(pills) {
        pills.sortedWith(compareByDescending<Pill> { it.rarity }.thenBy { it.name })
    }
    
    val sortedMaterials = remember(materials) {
        materials.sortedWith(compareByDescending<Material> { it.rarity }.thenBy { it.name })
    }
    
    val sortedHerbs = remember(herbs) {
        herbs.sortedWith(compareByDescending<Herb> { it.rarity }.thenBy { it.name })
    }
    
    val sortedSeeds = remember(seeds) {
        seeds.sortedWith(compareByDescending<Seed> { it.rarity }.thenBy { it.name })
    }
    
    data class WarehouseItemData(
        val id: String,
        val name: String,
        val rarity: Int,
        val item: Any
    )
    
    val allSortedItems = remember(equipment, manuals, sortedPills, sortedMaterials, sortedHerbs, sortedSeeds) {
        val items = mutableListOf<WarehouseItemData>()
        equipment.forEach { items.add(WarehouseItemData(it.id, it.name, it.rarity, it)) }
        manuals.forEach { items.add(WarehouseItemData(it.id, it.name, it.rarity, it)) }
        sortedPills.forEach { items.add(WarehouseItemData(it.id, it.name, it.rarity, it)) }
        sortedMaterials.forEach { items.add(WarehouseItemData(it.id, it.name, it.rarity, it)) }
        sortedHerbs.forEach { items.add(WarehouseItemData(it.id, it.name, it.rarity, it)) }
        sortedSeeds.forEach { items.add(WarehouseItemData(it.id, it.name, it.rarity, it)) }
        items.sortedWith(compareByDescending<WarehouseItemData> { it.rarity }.thenBy { it.name })
    }
    
    var selectedFilter by remember { mutableStateOf(WarehouseFilter.ALL) }
    var showDetailDialog by remember { mutableStateOf(false) }
    var selectedItemId by remember { mutableStateOf<String?>(null) }
    val selectedItem by remember {
        derivedStateOf {
            selectedItemId?.let { id ->
                equipment.find { it.id == id }
                    ?: manuals.find { it.id == id }
                    ?: sortedPills.find { it.id == id }
                    ?: sortedMaterials.find { it.id == id }
                    ?: sortedHerbs.find { it.id == id }
                    ?: sortedSeeds.find { it.id == id }
            }
        }
    }
    var showBulkSellDialog by remember { mutableStateOf(false) }
    var currentPage by remember { mutableIntStateOf(0) }
    
    val currentFilterItems = remember(selectedFilter, allSortedItems, equipment, sortedPills, manuals, sortedHerbs, sortedSeeds, sortedMaterials) {
        when (selectedFilter) {
            WarehouseFilter.ALL -> allSortedItems
            WarehouseFilter.EQUIPMENT -> equipment.map { WarehouseItemData(it.id, it.name, it.rarity, it) }
            WarehouseFilter.PILL -> sortedPills.map { WarehouseItemData(it.id, it.name, it.rarity, it) }
            WarehouseFilter.MANUAL -> manuals.map { WarehouseItemData(it.id, it.name, it.rarity, it) }
            WarehouseFilter.HERB -> sortedHerbs.map { WarehouseItemData(it.id, it.name, it.rarity, it) }
            WarehouseFilter.SEED -> sortedSeeds.map { WarehouseItemData(it.id, it.name, it.rarity, it) }
            WarehouseFilter.MATERIAL -> sortedMaterials.map { WarehouseItemData(it.id, it.name, it.rarity, it) }
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GameColors.PageBackground)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "仓库",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFE74C3C))
                    .clickable { showBulkSellDialog = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "一键出售",
                    fontSize = 12.sp,
                    color = Color.White
                )
            }
        }
        
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WarehouseFilterButton(
                    text = WarehouseFilter.ALL.displayName,
                    selected = selectedFilter == WarehouseFilter.ALL,
                    onClick = { 
                        if (selectedFilter != WarehouseFilter.ALL) {
                            selectedFilter = WarehouseFilter.ALL
                            currentPage = 0
                            selectedItemId = null
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                WarehouseFilterButton(
                    text = WarehouseFilter.EQUIPMENT.displayName,
                    selected = selectedFilter == WarehouseFilter.EQUIPMENT,
                    onClick = { 
                        if (selectedFilter != WarehouseFilter.EQUIPMENT) {
                            selectedFilter = WarehouseFilter.EQUIPMENT
                            currentPage = 0
                            selectedItemId = null
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                WarehouseFilterButton(
                    text = WarehouseFilter.PILL.displayName,
                    selected = selectedFilter == WarehouseFilter.PILL,
                    onClick = { 
                        if (selectedFilter != WarehouseFilter.PILL) {
                            selectedFilter = WarehouseFilter.PILL
                            currentPage = 0
                            selectedItemId = null
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                WarehouseFilterButton(
                    text = WarehouseFilter.MANUAL.displayName,
                    selected = selectedFilter == WarehouseFilter.MANUAL,
                    onClick = { 
                        if (selectedFilter != WarehouseFilter.MANUAL) {
                            selectedFilter = WarehouseFilter.MANUAL
                            currentPage = 0
                            selectedItemId = null
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WarehouseFilterButton(
                    text = WarehouseFilter.HERB.displayName,
                    selected = selectedFilter == WarehouseFilter.HERB,
                    onClick = { 
                        if (selectedFilter != WarehouseFilter.HERB) {
                            selectedFilter = WarehouseFilter.HERB
                            currentPage = 0
                            selectedItemId = null
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                WarehouseFilterButton(
                    text = WarehouseFilter.SEED.displayName,
                    selected = selectedFilter == WarehouseFilter.SEED,
                    onClick = { 
                        if (selectedFilter != WarehouseFilter.SEED) {
                            selectedFilter = WarehouseFilter.SEED
                            currentPage = 0
                            selectedItemId = null
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                WarehouseFilterButton(
                    text = WarehouseFilter.MATERIAL.displayName,
                    selected = selectedFilter == WarehouseFilter.MATERIAL,
                    onClick = { 
                        if (selectedFilter != WarehouseFilter.MATERIAL) {
                            selectedFilter = WarehouseFilter.MATERIAL
                            currentPage = 0
                            selectedItemId = null
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.weight(1f))
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
                val cellSize = 56.dp
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
                                                else -> 1
                                            },
                                            grade = (warehouseItem.item as? Pill)?.grade?.displayName,
                                            isLocked = getWarehouseItemIsLocked(warehouseItem.item)
                                        ),
                                        isSelected = selectedItemId == warehouseItem.id,
                                        showViewButton = true,
                                        onClick = { selectedItemId = if (selectedItemId == warehouseItem.id) null else warehouseItem.id },
                                        onViewDetail = { selectedItemId = warehouseItem.id; showDetailDialog = true }
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
            val basePrice = getWarehouseItemBasePrice(item)
            var showDiscipleSelectDialog by remember { mutableStateOf(false) }
            var showSellDialog by remember { mutableStateOf(false) }

            ItemDetailDialog(
                item = item,
                onDismiss = {
                    showDetailDialog = false
                    selectedItemId = null
                },
                extraActions = {
                    if (!isLocked) {
                        GameButton(
                            text = "售卖",
                            onClick = { showSellDialog = true },
                            backgroundColor = Color(0xFFFF6B35)
                        )
                    }
                    GameButton(
                        text = if (isLocked) "已锁定" else "锁定",
                        onClick = { viewModel.toggleItemLock(itemId, itemType) },
                        backgroundColor = if (isLocked) Color(0xFFFFD700) else null
                    )
                    GameButton(
                        text = "赏赐",
                        onClick = { showDiscipleSelectDialog = true }
                    )
                }
            )

            if (showSellDialog) {
                SellConfirmDialog(
                    itemName = itemName,
                    maxQuantity = itemQuantity,
                    basePrice = basePrice,
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
    
    if (showBulkSellDialog) {
        BulkSellDialog(
            viewModel = viewModel,
            onDismiss = { showBulkSellDialog = false }
        )
    }
}

@Composable
internal fun DiscipleSelectForRewardDialog(
    itemName: String,
    itemId: String,
    itemType: String,
    itemRarity: Int,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val disciples by viewModel.discipleAggregates.collectAsState()
    val pills by viewModel.pills.collectAsState()
    val materials by viewModel.materials.collectAsState()
    val herbs by viewModel.herbs.collectAsState()
    val seeds by viewModel.seeds.collectAsState()
    val equipmentStacks by viewModel.equipmentStacks.collectAsState()
    val manualStacks by viewModel.manualStacks.collectAsState()
    
    val aliveDisciples = remember(disciples) {
        disciples.filter { it.isAlive && it.status != DiscipleStatus.REFLECTING }
    }
    
    val currentQuantity by remember(pills, materials, herbs, seeds, equipmentStacks, manualStacks, itemType, itemId) {
        derivedStateOf {
            when (itemType) {
                "pill" -> pills.find { it.id == itemId }?.quantity ?: 0
                "material" -> materials.find { it.id == itemId }?.quantity ?: 0
                "herb" -> herbs.find { it.id == itemId }?.quantity ?: 0
                "seed" -> seeds.find { it.id == itemId }?.quantity ?: 0
                "equipment" -> equipmentStacks.find { it.id == itemId }?.quantity ?: 0
                "manual" -> manualStacks.find { it.id == itemId }?.quantity ?: 0
                else -> 0
            }
        }
    }
    
    var isRewarding by remember { mutableStateOf(false) }
    var selectedRealmFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedSpiritRootFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedAttributeSort by remember { mutableStateOf<String?>(null) }
    var spiritRootExpanded by remember { mutableStateOf(false) }
    var attributeExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    
    val realmFilters = listOf(
        0 to "仙人",
        1 to "渡劫",
        2 to "大乘",
        3 to "合体",
        4 to "炼虚",
        5 to "化神",
        6 to "元婴",
        7 to "金丹",
        8 to "筑基",
        9 to "炼气"
    )
    
    val realmCounts = remember(aliveDisciples) {
        aliveDisciples.groupingBy { it.realm }.eachCount()
    }

    val spiritRootCounts = remember(aliveDisciples) {
        aliveDisciples.groupingBy { it.getSpiritRootCount() }.eachCount()
    }

    val filteredAndSortedDisciples = remember(aliveDisciples, selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort) {
        aliveDisciples.applyFilters(selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort)
    }
    
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f),
            shape = RoundedCornerShape(16.dp),
            color = GameColors.PageBackground
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "赏赐弟子",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                        Text(
                            text = "物品: $itemName (剩余: $currentQuantity)",
                            fontSize = 12.sp,
                            color = Color(0xFF666666)
                        )
                    }
                    GameButton(
                        text = "关闭",
                        onClick = onDismiss
                    )
                }
                
                SpiritRootAttributeFilterBar(
                    selectedSpiritRootFilter = selectedSpiritRootFilter,
                    selectedAttributeSort = selectedAttributeSort,
                    spiritRootExpanded = spiritRootExpanded,
                    attributeExpanded = attributeExpanded,
                    spiritRootCounts = spiritRootCounts,
                    onSpiritRootFilterSelected = { selectedSpiritRootFilter = selectedSpiritRootFilter + it },
                    onSpiritRootFilterRemoved = { selectedSpiritRootFilter = selectedSpiritRootFilter - it },
                    onAttributeSortSelected = { selectedAttributeSort = it },
                    onSpiritRootExpandToggle = { spiritRootExpanded = !spiritRootExpanded },
                    onAttributeExpandToggle = { attributeExpanded = !attributeExpanded },
                    isCompact = true
                )

                RealmFilterBar(
                    filters = realmFilters,
                    realmCounts = realmCounts,
                    selectedFilter = selectedRealmFilter,
                    onFilterSelected = { selectedRealmFilter = selectedRealmFilter + it },
                    onFilterRemoved = { selectedRealmFilter = selectedRealmFilter - it }
                )
                
                if (currentQuantity <= 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "物品已全部赏赐完毕",
                            fontSize = 14.sp,
                            color = GameColors.TextSecondary
                        )
                    }
                } else if (filteredAndSortedDisciples.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无可赏赐的弟子",
                            fontSize = 14.sp,
                            color = GameColors.TextSecondary
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        items(filteredAndSortedDisciples, key = { it.id }) { disciple ->
                            DiscipleCard(
                                disciple = disciple,
                                onClick = {
                                    if (!isRewarding && currentQuantity > 0) {
                                        scope.launch {
                                            isRewarding = true
                                            try {
                                                viewModel.rewardItemsToDisciple(
                                                    disciple.id,
                                                    listOf(RewardSelectedItem(
                                                        id = itemId,
                                                        type = itemType,
                                                        name = itemName,
                                                        rarity = itemRarity,
                                                        quantity = 1
                                                    ))
                                                )
                                            } finally {
                                                isRewarding = false
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SellConfirmDialog(
    itemName: String,
    maxQuantity: Int,
    basePrice: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var sellQuantity by remember { mutableIntStateOf(1) }
    var isEditingQuantity by remember { mutableStateOf(false) }
    var quantityInput by remember { mutableStateOf("1") }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    LaunchedEffect(maxQuantity) {
        if (sellQuantity > maxQuantity) {
            sellQuantity = maxQuantity.coerceAtLeast(1)
            quantityInput = sellQuantity.toString()
        }
    }

    val totalPrice = GameConfig.Rarity.calculateSellPrice(basePrice, sellQuantity)

    LaunchedEffect(isEditingQuantity) {
        if (isEditingQuantity) {
            focusRequester.requestFocus()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Text(
                text = "售卖物品",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = itemName,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF333333)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "售卖数量",
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                    Text(
                        text = "最大: $maxQuantity",
                        fontSize = 11.sp,
                        color = Color(0xFF999999)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (sellQuantity > 1) Color(0xFFE0E0E0) else Color(0xFFF5F5F5))
                            .clickable(enabled = sellQuantity > 1) {
                                sellQuantity = (sellQuantity - 1).coerceAtLeast(1)
                                quantityInput = sellQuantity.toString()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "−",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (sellQuantity > 1) Color.Black else Color(0xFFBDBDBD)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    if (isEditingQuantity) {
                        OutlinedTextField(
                            value = quantityInput,
                            onValueChange = { input ->
                                val filtered = input.filter { it.isDigit() }
                                if (filtered.isEmpty()) {
                                    quantityInput = ""
                                    sellQuantity = 1
                                } else {
                                    val parsed = filtered.toIntOrNull() ?: 0
                                    if (parsed > maxQuantity) {
                                        quantityInput = maxQuantity.toString()
                                        sellQuantity = maxQuantity
                                    } else if (parsed == 0) {
                                        quantityInput = ""
                                        sellQuantity = 1
                                    } else {
                                        quantityInput = filtered
                                        sellQuantity = parsed
                                    }
                                }
                            },
                            modifier = Modifier
                                .width(80.dp)
                                .focusRequester(focusRequester)
                                .onFocusChanged { focusState ->
                                    if (!focusState.isFocused && isEditingQuantity) {
                                        isEditingQuantity = false
                                        quantityInput = sellQuantity.toString()
                                    }
                                },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            ),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                                imeAction = androidx.compose.ui.text.input.ImeAction.Done
                            ),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                onDone = {
                                    isEditingQuantity = false
                                    quantityInput = sellQuantity.toString()
                                }
                            ),
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = GameColors.Primary,
                                unfocusedBorderColor = Color(0xFFCCCCCC)
                            )
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .width(80.dp)
                                .height(48.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .border(1.dp, Color(0xFFCCCCCC), RoundedCornerShape(6.dp))
                                .background(Color.White)
                                .clickable {
                                    isEditingQuantity = true
                                    quantityInput = sellQuantity.toString()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$sellQuantity",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (sellQuantity < maxQuantity) Color(0xFFE0E0E0) else Color(0xFFF5F5F5))
                            .clickable(enabled = sellQuantity < maxQuantity) {
                                sellQuantity = (sellQuantity + 1).coerceAtMost(maxQuantity)
                                quantityInput = sellQuantity.toString()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "+",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (sellQuantity < maxQuantity) Color.Black else Color(0xFFBDBDBD)
                        )
                    }
                }

                HorizontalDivider(color = Color(0xFFE0E0E0), thickness = 1.dp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "单价",
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                    Text(
                        text = "${GameConfig.Rarity.calculateSellPrice(basePrice, 1)} 灵石",
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "总计",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Text(
                        text = "$totalPrice 灵石",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFF6B35)
                    )
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GameButton(
                    text = "取消",
                    onClick = onDismiss
                )
                GameButton(
                    text = "确认售卖",
                    onClick = { onConfirm(sellQuantity) },
                    backgroundColor = Color(0xFFFF6B35)
                )
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BulkSellDialog(
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val equipmentStacks by viewModel.equipmentStacks.collectAsState()
    val manualStacks by viewModel.manualStacks.collectAsState()
    val pills by viewModel.pills.collectAsState()
    val materials by viewModel.materials.collectAsState()
    val herbs by viewModel.herbs.collectAsState()
    val seeds by viewModel.seeds.collectAsState()
    
    var selectedRarities by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedTypes by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    
    // 使用游戏中的品阶名称：凡品、灵品、宝品、玄品、地品、天品
    val rarityOptions = listOf(
        1 to "凡品",
        2 to "灵品",
        3 to "宝品",
        4 to "玄品",
        5 to "地品",
        6 to "天品"
    )
    
    // 使用仓库的分类：全部、装备、丹药、功法、草药、种子、材料
    val typeOptions = listOf(
        "ALL" to "全部",
        "EQUIPMENT" to "装备",
        "PILL" to "丹药",
        "MANUAL" to "功法",
        "HERB" to "草药",
        "SEED" to "种子",
        "MATERIAL" to "材料"
    )
    
    // 计算可出售的物品
    val finalTypes = remember(selectedTypes) {
        if (selectedTypes.contains("ALL")) {
            setOf("EQUIPMENT", "PILL", "MANUAL", "HERB", "SEED", "MATERIAL")
        } else {
            selectedTypes
        }
    }
    
    val sellableEquipment = remember(equipmentStacks, selectedRarities, finalTypes) {
        if (selectedRarities.isNotEmpty() && finalTypes.contains("EQUIPMENT")) {
            equipmentStacks.filter { selectedRarities.contains(it.rarity) && !it.isLocked }
        } else emptyList()
    }
    
    val sellableManuals = remember(manualStacks, selectedRarities, finalTypes) {
        if (selectedRarities.isNotEmpty() && finalTypes.contains("MANUAL")) {
            manualStacks.filter { selectedRarities.contains(it.rarity) && !it.isLocked }
        } else emptyList()
    }
    
    val sellablePills = remember(pills, selectedRarities, finalTypes) {
        if (selectedRarities.isNotEmpty() && finalTypes.contains("PILL")) {
            pills.filter { selectedRarities.contains(it.rarity) && !it.isLocked }
        } else emptyList()
    }
    
    val sellableMaterials = remember(materials, selectedRarities, finalTypes) {
        if (selectedRarities.isNotEmpty() && finalTypes.contains("MATERIAL")) {
            materials.filter { selectedRarities.contains(it.rarity) && !it.isLocked }
        } else emptyList()
    }
    
    val sellableHerbs = remember(herbs, selectedRarities, finalTypes) {
        if (selectedRarities.isNotEmpty() && finalTypes.contains("HERB")) {
            herbs.filter { selectedRarities.contains(it.rarity) && !it.isLocked }
        } else emptyList()
    }
    
    val sellableSeeds = remember(seeds, selectedRarities, finalTypes) {
        if (selectedRarities.isNotEmpty() && finalTypes.contains("SEED")) {
            seeds.filter { selectedRarities.contains(it.rarity) && !it.isLocked }
        } else emptyList()
    }
    
    val totalItems = sellableEquipment.size + sellableManuals.size + sellablePills.size +
            sellableMaterials.size + sellableHerbs.size + sellableSeeds.size

    val totalValue = sellableEquipment.sumOf { GameConfig.Rarity.calculateSellPrice(it.basePrice, it.quantity) } +
            sellableManuals.sumOf { GameConfig.Rarity.calculateSellPrice(it.basePrice, it.quantity) } +
            sellablePills.sumOf { GameConfig.Rarity.calculateSellPrice(it.basePrice, it.quantity) } +
            sellableMaterials.sumOf { GameConfig.Rarity.calculateSellPrice(it.basePrice, it.quantity) } +
            sellableHerbs.sumOf { GameConfig.Rarity.calculateSellPrice(it.basePrice, it.quantity) } +
            sellableSeeds.sumOf { GameConfig.Rarity.calculateSellPrice(it.basePrice, it.quantity) }
    
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = GameColors.PageBackground
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "一键出售",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                
                // 品阶选择 - 4列显示，支持多选
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "选择品阶（可多选）：",
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                    // 分4列显示
                    rarityOptions.chunked(4).forEach { rowOptions ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowOptions.forEach { (rarity, name) ->
                                val isSelected = selectedRarities.contains(rarity)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSelected) Color.Black else Color(0xFFF0F0F0))
                                        .clickable {
                                            selectedRarities = if (isSelected) {
                                                selectedRarities - rarity
                                            } else {
                                                selectedRarities + rarity
                                            }
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = name,
                                        fontSize = 11.sp,
                                        color = if (isSelected) Color.White else Color.Black
                                    )
                                }
                            }
                            // 补齐4列
                            repeat(4 - rowOptions.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                
                // 类型选择 - 4列显示
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "选择物品类型（可多选）：",
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                    // 分4列显示
                    typeOptions.chunked(4).forEach { rowOptions ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowOptions.forEach { (type, name) ->
                                val isSelected = selectedTypes.contains(type)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSelected) Color.Black else Color(0xFFF0F0F0))
                                        .clickable { 
                                            selectedTypes = if (isSelected) {
                                                selectedTypes - type
                                            } else {
                                                selectedTypes + type
                                            }
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = name,
                                        fontSize = 11.sp,
                                        color = if (isSelected) Color.White else Color.Black
                                    )
                                }
                            }
                            // 补齐4列
                            repeat(4 - rowOptions.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                
                // 可出售物品列表
                if (totalItems > 0) {
                    Text(
                        text = "可出售物品（共${totalItems}件）：",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 装备
                        if (sellableEquipment.isNotEmpty()) {
                            item {
                                Text(
                                    text = "装备 (${sellableEquipment.size}件)",
                                    fontSize = 11.sp,
                                    color = Color(0xFF666666),
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            items(sellableEquipment.chunked(4)) { rowItems ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowItems.forEach { item ->
                                        UnifiedItemCard(
                                            data = ItemCardData(name = item.name, rarity = item.rarity),
                                            showQuantity = false,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    repeat(4 - rowItems.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                        
                        // 功法
                        if (sellableManuals.isNotEmpty()) {
                            item {
                                Text(
                                    text = "功法 (${sellableManuals.size}件)",
                                    fontSize = 11.sp,
                                    color = Color(0xFF666666),
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            items(sellableManuals.chunked(4)) { rowItems ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowItems.forEach { item ->
                                        UnifiedItemCard(
                                            data = ItemCardData(name = item.name, rarity = item.rarity),
                                            showQuantity = false,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    repeat(4 - rowItems.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                        
                        // 丹药
                        if (sellablePills.isNotEmpty()) {
                            item {
                                Text(
                                    text = "丹药 (${sellablePills.size}件)",
                                    fontSize = 11.sp,
                                    color = Color(0xFF666666),
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            items(sellablePills.chunked(4)) { rowItems ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowItems.forEach { item ->
                                        UnifiedItemCard(
                                            data = ItemCardData(name = item.name, rarity = item.rarity, quantity = item.quantity, grade = item.grade.displayName),
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    repeat(4 - rowItems.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                        
                        // 材料
                        if (sellableMaterials.isNotEmpty()) {
                            item {
                                Text(
                                    text = "材料 (${sellableMaterials.size}件)",
                                    fontSize = 11.sp,
                                    color = Color(0xFF666666),
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            items(sellableMaterials.chunked(4)) { rowItems ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowItems.forEach { item ->
                                        UnifiedItemCard(
                                            data = ItemCardData(name = item.name, rarity = item.rarity, quantity = item.quantity),
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    repeat(4 - rowItems.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                        
                        // 草药
                        if (sellableHerbs.isNotEmpty()) {
                            item {
                                Text(
                                    text = "草药 (${sellableHerbs.size}件)",
                                    fontSize = 11.sp,
                                    color = Color(0xFF666666),
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            items(sellableHerbs.chunked(4)) { rowItems ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowItems.forEach { item ->
                                        UnifiedItemCard(
                                            data = ItemCardData(name = item.name, rarity = item.rarity, quantity = item.quantity),
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    repeat(4 - rowItems.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                        
                        // 种子
                        if (sellableSeeds.isNotEmpty()) {
                            item {
                                Text(
                                    text = "种子 (${sellableSeeds.size}件)",
                                    fontSize = 11.sp,
                                    color = Color(0xFF666666),
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            items(sellableSeeds.chunked(4)) { rowItems ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowItems.forEach { item ->
                                        UnifiedItemCard(
                                            data = ItemCardData(name = item.name, rarity = item.rarity, quantity = item.quantity),
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    repeat(4 - rowItems.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                } else if (selectedRarities.isNotEmpty() && selectedTypes.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "没有符合条件的物品",
                            fontSize = 12.sp,
                            color = Color(0xFF999999)
                        )
                    }
                }
                
                // 按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(GameColors.Border)
                            .clickable { onDismiss() }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "取消",
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (totalItems > 0) Color(0xFFE74C3C) else Color(0xFFCCCCCC)
                            )
                            .then(
                                if (totalItems > 0) {
                                    Modifier.clickable {
                                        showConfirmDialog = true
                                    }
                                } else {
                                    Modifier
                                }
                            )
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "确认出售",
                            fontSize = 12.sp,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            containerColor = GameColors.PageBackground,
            title = {
                Text(
                    text = "确认出售",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            },
            text = {
                Column {
                    Text(
                        text = "确定要出售以下物品吗？",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "物品数量: ${totalItems} 件",
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                    Text(
                        text = "获得灵石: $totalValue（原价80%）",
                        fontSize = 12.sp,
                        color = GameColors.Success
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "此操作不可撤销！",
                        fontSize = 11.sp,
                        color = GameColors.Error
                    )
                }
            },
            confirmButton = {
                GameButton(
                    text = "确认出售",
                    onClick = {
                        viewModel.bulkSellItems(selectedRarities, finalTypes)
                        showConfirmDialog = false
                        onDismiss()
                    }
                )
            },
            dismissButton = {
                GameButton(
                    text = "取消",
                    onClick = { showConfirmDialog = false }
                )
            }
        )
    }
}

@Composable
internal fun WarehouseFilterButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Color.Black else GameColors.ButtonBackground)
            .border(1.dp, if (selected) Color.Black else GameColors.ButtonBorder, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
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
                .background(if (currentPage > 1) Color(0xFF3498DB) else Color(0xFFCCCCCC))
                .clickable(enabled = currentPage > 1) { onFirstPage() },
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
                .background(if (currentPage > 1) Color(0xFF3498DB) else Color(0xFFCCCCCC))
                .clickable(enabled = currentPage > 1) { onPreviousPage() },
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
            color = Color(0xFF333333),
            fontWeight = FontWeight.Medium
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (currentPage < totalPages) Color(0xFF3498DB) else Color(0xFFCCCCCC))
                .clickable(enabled = currentPage < totalPages) { onNextPage() },
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
                .background(if (currentPage < totalPages) Color(0xFF3498DB) else Color(0xFFCCCCCC))
                .clickable(enabled = currentPage < totalPages) { onLastPage() },
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
            color = Color(0xFF999999)
        )
    }
}

internal fun getRarityColor(rarity: Int): Color = when (rarity) {
    1 -> Color(0xFF95A5A6)
    2 -> Color(0xFF27AE60)
    3 -> Color(0xFF3498DB)
    4 -> Color(0xFF9B59B6)
    5 -> Color(0xFFF39C12)
    6 -> Color(0xFFE74C3C)
    else -> Color(0xFF95A5A6)
}
