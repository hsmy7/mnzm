package com.xianxia.sect.ui.game.components.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.util.sortedByWatchedThenRarity
import com.xianxia.sect.ui.components.*
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.components.watchKeyOf
import com.xianxia.sect.ui.game.components.ItemDetailDialog
import com.xianxia.sect.ui.theme.GameColors
import kotlinx.coroutines.launch
import com.xianxia.sect.ui.components.clickableWithSound

@Composable
fun StorageBagDialog(
    items: List<StorageBagItem>,
    spiritStones: Long,
    disciple: DiscipleAggregate,
    viewModel: GameViewModel?,
    onDismiss: () -> Unit
) {
    var showRewardDialog by remember { mutableStateOf(false) }
    var selectedItem by remember { mutableStateOf<StorageBagItem?>(null) }
    var showDetailDialog by remember { mutableStateOf(false) }

    val watchedKeys = viewModel?.watchedItemIds?.collectAsStateWithLifecycle()?.value
        ?: emptySet()
    val sortedItems = remember(items, watchedKeys) {
        items.sortedByWatchedThenRarity(
            watchedKeys,
            keyOf = { watchKeyOf(it) },
            rarityOf = { it.rarity },
            nameOf = { it.name }
        )
    }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "储物袋",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFFFF9800))
                        .clickableWithSound { showRewardDialog = true }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "赏赐",
                        fontSize = 10.sp,
                        color = Color.White
                    )
                }
                Text(
                    text = "灵石:$spiritStones",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2196F3)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                if (sortedItems.isEmpty()) {
                    Text(
                        text = "储物袋为空",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                } else {
                    Text(
                        text = "共 ${sortedItems.size} 种物品",
                        fontSize = 11.sp,
                        color = Color.Black
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(60.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(sortedItems, key = { index, item -> "${item.itemId}_$index" }) { index, item ->
                            UnifiedItemCard(
                                data = ItemCardData(
                                    id = item.itemId,
                                    name = item.name,
                                    rarity = item.rarity,
                                    quantity = item.quantity,
                                    grade = item.grade,
                                    isManual = item.itemType == "manual_stack" || item.itemType == "manual_instance",
                                    isPill = item.itemType == "pill",
                                    isMaterial = item.itemType == "material"
                                ),
                                isSelected = selectedItem?.itemId == item.itemId,
                                isFollowed = watchKeyOf(item)?.let { it in watchedKeys } ?: false,
                                onClick = {
                                    selectedItem = if (selectedItem?.itemId == item.itemId) null else item
                                },
                                onLongPress = {
                                    selectedItem = item
                                    showDetailDialog = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDetailDialog) {
        selectedItem?.let { item ->
            ItemDetailDialog(
                item = item,
                onDismiss = { showDetailDialog = false },
                viewModel = viewModel,
                extraActions = {
                    GameButton(
                        text = "没收",
                        onClick = {
                            viewModel?.confiscateStorageBagItem(disciple.id, item)
                            showDetailDialog = false
                        },
                        modifier = Modifier.height(32.dp)
                    )
                }
            )
        }
    }

    if (showRewardDialog && viewModel != null) {
        RewardItemsDialog(
            disciple = disciple,
            viewModel = viewModel,
            onDismiss = { showRewardDialog = false }
        )
    }
}

private enum class RewardFilter(val displayName: String) {
    ALL("全部"),
    EQUIPMENT("装备"),
    PILL("丹药"),
    MANUAL("功法"),
    HERB("草药"),
    SEED("种子"),
    MATERIAL("材料")
}

@Composable
private fun RewardItemsDialog(
    disciple: DiscipleAggregate,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    var selectedFilter by remember { mutableStateOf(RewardFilter.ALL) }
    var selectedItem by remember { mutableStateOf<RewardSelectedItem?>(null) }
    var rewardQuantity by remember { mutableIntStateOf(1) }
    var showDetailDialog by remember { mutableStateOf(false) }
    var detailItem by remember { mutableStateOf<Any?>(null) }
    var isRewarding by remember { mutableStateOf(false) }
    val rewardScope = rememberCoroutineScope()

    val equipmentStacks by viewModel.equipmentStacks.collectAsStateWithLifecycle()
    val manualStacks by viewModel.manualStacks.collectAsStateWithLifecycle()
    val pills by viewModel.pills.collectAsStateWithLifecycle()
    val materials by viewModel.materials.collectAsStateWithLifecycle()
    val herbs by viewModel.herbs.collectAsStateWithLifecycle()
    val seeds by viewModel.seeds.collectAsStateWithLifecycle()
    val watchedKeys by viewModel.watchedItemIds.collectAsStateWithLifecycle()

    val availableManuals = manualStacks
    val availableEquipment = equipmentStacks

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "赏赐道具",
        mode = DialogMode.Full,
        scrollableContent = false
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilterButton(
                    text = RewardFilter.ALL.displayName,
                    selected = selectedFilter == RewardFilter.ALL,
                    onClick = { selectedFilter = RewardFilter.ALL },
                    modifier = Modifier.weight(1f)
                )
                FilterButton(
                    text = RewardFilter.EQUIPMENT.displayName,
                    selected = selectedFilter == RewardFilter.EQUIPMENT,
                    onClick = { selectedFilter = RewardFilter.EQUIPMENT },
                    modifier = Modifier.weight(1f)
                )
                FilterButton(
                    text = RewardFilter.PILL.displayName,
                    selected = selectedFilter == RewardFilter.PILL,
                    onClick = { selectedFilter = RewardFilter.PILL },
                    modifier = Modifier.weight(1f)
                )
                FilterButton(
                    text = RewardFilter.MANUAL.displayName,
                    selected = selectedFilter == RewardFilter.MANUAL,
                    onClick = { selectedFilter = RewardFilter.MANUAL },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilterButton(
                    text = RewardFilter.HERB.displayName,
                    selected = selectedFilter == RewardFilter.HERB,
                    onClick = { selectedFilter = RewardFilter.HERB },
                    modifier = Modifier.weight(1f)
                )
                FilterButton(
                    text = RewardFilter.SEED.displayName,
                    selected = selectedFilter == RewardFilter.SEED,
                    onClick = { selectedFilter = RewardFilter.SEED },
                    modifier = Modifier.weight(1f)
                )
                FilterButton(
                    text = RewardFilter.MATERIAL.displayName,
                    selected = selectedFilter == RewardFilter.MATERIAL,
                    onClick = { selectedFilter = RewardFilter.MATERIAL },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(GameColors.CardBackground)
            ) {
                when (selectedFilter) {
                    RewardFilter.ALL -> RewardAllItemsGrid(
                        equipment = availableEquipment,
                        manuals = availableManuals,
                        pills = pills,
                        materials = materials,
                        herbs = herbs,
                        seeds = seeds,
                        watchedKeys = watchedKeys,
                        selectedItem = selectedItem,
                        onItemSelect = { item ->
                            selectedItem = if (selectedItem?.id == item.id) null else item
                            rewardQuantity = 1
                        },
                        onViewDetail = { item ->
                            detailItem = item
                            showDetailDialog = true
                        }
                    )
                    RewardFilter.EQUIPMENT -> RewardItemGrid(
                        items = availableEquipment,
                        watchedKeys = watchedKeys,
                        selectedItem = selectedItem,
                        onItemSelect = { item ->
                            selectedItem = if (selectedItem?.id == item.id) null else item
                            rewardQuantity = 1
                        },
                        onViewDetail = { item ->
                            detailItem = item
                            showDetailDialog = true
                        }
                    )
                    RewardFilter.PILL -> RewardItemGrid(
                        items = pills,
                        watchedKeys = watchedKeys,
                        selectedItem = selectedItem,
                        onItemSelect = { item ->
                            selectedItem = if (selectedItem?.id == item.id) null else item
                            rewardQuantity = 1
                        },
                        onViewDetail = { item ->
                            detailItem = item
                            showDetailDialog = true
                        }
                    )
                    RewardFilter.MANUAL -> RewardItemGrid(
                        items = availableManuals,
                        watchedKeys = watchedKeys,
                        selectedItem = selectedItem,
                        onItemSelect = { item ->
                            selectedItem = if (selectedItem?.id == item.id) null else item
                            rewardQuantity = 1
                        },
                        onViewDetail = { item ->
                            detailItem = item
                            showDetailDialog = true
                        }
                    )
                    RewardFilter.HERB -> RewardItemGrid(
                        items = herbs,
                        watchedKeys = watchedKeys,
                        selectedItem = selectedItem,
                        onItemSelect = { item ->
                            selectedItem = if (selectedItem?.id == item.id) null else item
                            rewardQuantity = 1
                        },
                        onViewDetail = { item ->
                            detailItem = item
                            showDetailDialog = true
                        }
                    )
                    RewardFilter.SEED -> RewardItemGrid(
                        items = seeds,
                        watchedKeys = watchedKeys,
                        selectedItem = selectedItem,
                        onItemSelect = { item ->
                            selectedItem = if (selectedItem?.id == item.id) null else item
                            rewardQuantity = 1
                        },
                        onViewDetail = { item ->
                            detailItem = item
                            showDetailDialog = true
                        }
                    )
                    RewardFilter.MATERIAL -> RewardItemGrid(
                        items = materials,
                        watchedKeys = watchedKeys,
                        selectedItem = selectedItem,
                        onItemSelect = { item ->
                            selectedItem = if (selectedItem?.id == item.id) null else item
                            rewardQuantity = 1
                        },
                        onViewDetail = { item ->
                            detailItem = item
                            showDetailDialog = true
                        }
                    )
                }
            }

            RewardBottomPanel(
                selectedItem = selectedItem,
                rewardQuantity = rewardQuantity,
                maxQuantity = selectedItem?.quantity ?: 1,
                isRewarding = isRewarding,
                onQuantityChange = { rewardQuantity = it },
                onRewardClick = {
                    val item = selectedItem
                    if (item != null && rewardQuantity > 0 && !isRewarding) {
                        isRewarding = true
                        rewardScope.launch {
                            try {
                                viewModel.rewardItemsToDisciple(disciple.id, listOf(item.copy(quantity = rewardQuantity)))
                            } finally {
                                selectedItem = null
                                rewardQuantity = 1
                                isRewarding = false
                            }
                        }
                    }
                }
            )
        }
    }

    if (showDetailDialog) {
        detailItem?.let { item ->
            ItemDetailDialog(
                item = item,
                onDismiss = { showDetailDialog = false },
                viewModel = viewModel
            )
        }
    }
}

@Composable
private fun <T> RewardItemGrid(
    items: List<T>,
    selectedItem: RewardSelectedItem?,
    watchedKeys: Set<String> = emptySet(),
    onItemSelect: (RewardSelectedItem) -> Unit,
    onViewDetail: (Any) -> Unit = {}
) {
    if (items.isEmpty()) {
        EmptyListMessage("暂无道具")
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(60.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = items,
                key = { item ->
                    when (item) {
                        is EquipmentStack -> "equipment_${item.id}"
                        is ManualStack -> "manual_${item.id}"
                        is Pill -> "pill_${item.id}_${item.quantity}"
                        is Material -> "material_${item.id}_${item.quantity}"
                        is Herb -> "herb_${item.id}_${item.quantity}"
                        is Seed -> "seed_${item.id}_${item.quantity}"
                        else -> "unknown_${System.identityHashCode(item)}"
                    }
                },
                contentType = { "reward_item" }
            ) { item ->
                val currentSelectedItem = remember(item) {
                    when (item) {
                        is EquipmentStack -> RewardSelectedItem(item.id, "equipment", item.name, item.rarity, 1)
                        is ManualStack -> RewardSelectedItem(item.id, "manual", item.name, item.rarity, 1)
                        is Pill -> RewardSelectedItem(item.id, "pill", item.name, item.rarity, item.quantity, item.grade.displayName)
                        is Material -> RewardSelectedItem(item.id, "material", item.name, item.rarity, item.quantity)
                        is Herb -> RewardSelectedItem(item.id, "herb", item.name, item.rarity, item.quantity)
                        is Seed -> RewardSelectedItem(item.id, "seed", item.name, item.rarity, item.quantity)
                        else -> null
                    }
                }

                if (currentSelectedItem != null) {
                    val isSelected = selectedItem?.id == currentSelectedItem.id
                    UnifiedItemCard(
                        data = ItemCardData(
                            name = currentSelectedItem.name,
                            rarity = currentSelectedItem.rarity,
                            quantity = currentSelectedItem.quantity,
                            grade = currentSelectedItem.grade,
                            isManual = currentSelectedItem.type == "manual",
                            isPill = currentSelectedItem.type == "pill",
                            isMaterial = currentSelectedItem.type == "material"
                        ),
                        isSelected = isSelected,
                        isFollowed = watchKeyOf(item)?.let { it in watchedKeys } ?: false,
                        onClick = { onItemSelect(currentSelectedItem) },
                        onLongPress = { onViewDetail(item as Any) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RewardAllItemsGrid(
    equipment: List<EquipmentStack>,
    manuals: List<ManualStack>,
    pills: List<Pill>,
    materials: List<Material>,
    herbs: List<Herb>,
    seeds: List<Seed>,
    selectedItem: RewardSelectedItem?,
    watchedKeys: Set<String> = emptySet(),
    onItemSelect: (RewardSelectedItem) -> Unit,
    onViewDetail: (Any) -> Unit = {}
) {
    val allItems: List<GameItem> =
        (equipment + manuals + pills + materials + herbs + seeds)
            .sortedByWatchedThenRarity(watchedKeys)

    if (allItems.isEmpty()) {
        EmptyListMessage("暂无道具")
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(60.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = allItems,
                key = { item ->
                    when (item) {
                        is EquipmentStack -> "equipment_${item.id}"
                        is ManualStack -> "manual_${item.id}"
                        is Pill -> "pill_${item.id}_${item.quantity}"
                        is Material -> "material_${item.id}_${item.quantity}"
                        is Herb -> "herb_${item.id}_${item.quantity}"
                        is Seed -> "seed_${item.id}_${item.quantity}"
                        else -> "unknown_${System.identityHashCode(item)}"
                    }
                },
                contentType = { "game_item" }
            ) { item ->
                val currentSelectedItem = remember(item) {
                    when (item) {
                        is EquipmentStack -> RewardSelectedItem(item.id, "equipment", item.name, item.rarity, 1)
                        is ManualStack -> RewardSelectedItem(item.id, "manual", item.name, item.rarity, 1)
                        is Pill -> RewardSelectedItem(item.id, "pill", item.name, item.rarity, item.quantity, item.grade.displayName)
                        is Material -> RewardSelectedItem(item.id, "material", item.name, item.rarity, item.quantity)
                        is Herb -> RewardSelectedItem(item.id, "herb", item.name, item.rarity, item.quantity)
                        is Seed -> RewardSelectedItem(item.id, "seed", item.name, item.rarity, item.quantity)
                        else -> null
                    }
                }

                if (currentSelectedItem != null) {
                    val isSelected = selectedItem?.id == currentSelectedItem.id
                    UnifiedItemCard(
                        data = ItemCardData(
                            name = currentSelectedItem.name,
                            rarity = currentSelectedItem.rarity,
                            quantity = currentSelectedItem.quantity,
                            grade = currentSelectedItem.grade,
                            isManual = currentSelectedItem.type == "manual",
                            isPill = currentSelectedItem.type == "pill",
                            isMaterial = currentSelectedItem.type == "material"
                        ),
                        isSelected = isSelected,
                        isFollowed = watchKeyOf(item)?.let { it in watchedKeys } ?: false,
                        onClick = { onItemSelect(currentSelectedItem) },
                        onLongPress = { onViewDetail(item as Any) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RewardBottomPanel(
    selectedItem: RewardSelectedItem?,
    rewardQuantity: Int,
    maxQuantity: Int,
    isRewarding: Boolean = false,
    onQuantityChange: (Int) -> Unit,
    onRewardClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (selectedItem != null) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (rewardQuantity > 1 && !isRewarding) Color(0xFF4CAF50) else GameColors.Border)
                            .clickableWithSound(enabled = rewardQuantity > 1 && !isRewarding) { onQuantityChange(rewardQuantity - 1) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "-",
                            fontSize = 16.sp,
                            color = if (rewardQuantity > 1 && !isRewarding) Color.White else Color.Black
                        )
                    }

                    Text(
                        text = "$rewardQuantity",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isRewarding) GameColors.TextSecondary else Color.Black
                    )

                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (rewardQuantity < maxQuantity && !isRewarding) Color(0xFF4CAF50) else GameColors.Border)
                            .clickableWithSound(enabled = rewardQuantity < maxQuantity && !isRewarding) { onQuantityChange(rewardQuantity + 1) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "+",
                            fontSize = 16.sp,
                            color = if (rewardQuantity < maxQuantity && !isRewarding) Color.White else Color.Black
                        )
                    }

                    Text(
                        text = "/ $maxQuantity",
                        fontSize = 12.sp,
                        color = GameColors.TextSecondary
                    )
                } else {
                    Text(
                        text = "请选择要赏赐的道具",
                        fontSize = 12.sp,
                        color = GameColors.TextSecondary
                    )
                }
            }

            GameButton(
                text = if (isRewarding) "赏赐中..." else "赏赐",
                onClick = onRewardClick,
                modifier = Modifier.height(36.dp),
                enabled = selectedItem != null && rewardQuantity > 0 && !isRewarding
            )
        }
    }
}

@Composable
private fun FilterButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) Color.Black else Color(0xFFEEEEEE))
            .clickableWithSound { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) Color.White else Color.Black
        )
    }
}
