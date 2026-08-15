@file:Suppress("TooManyFunctions") // 拆分聚合:提取的私有辅助函数集中在原文件,文件级复杂度为拆分代价
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
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameItem
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.RewardSelectedItem
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.model.spiritStones
import com.xianxia.sect.core.util.sortedByWatchedThenRarity
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.EmptyListMessage
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.UnifiedItemCard
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
            StorageBagHeaderRow(
                spiritStones = spiritStones,
                onRewardClick = { showRewardDialog = true }
            )
            Spacer(modifier = Modifier.height(12.dp))
            StorageBagItemList(
                sortedItems = sortedItems,
                selectedItem = selectedItem,
                watchedKeys = watchedKeys,
                onItemClick = { item ->
                    selectedItem = if (selectedItem?.itemId == item.itemId) null else item
                },
                onItemLongPress = { item ->
                    selectedItem = item
                    showDetailDialog = true
                }
            )
        }
    }

    StorageBagDetailDialog(
        item = if (showDetailDialog) selectedItem else null,
        viewModel = viewModel,
        disciple = disciple,
        onDismiss = { showDetailDialog = false }
    )

    if (showRewardDialog && viewModel != null) {
        RewardItemsDialog(
            disciple = disciple,
            viewModel = viewModel,
            onDismiss = { showRewardDialog = false }
        )
    }
}

/** 储物袋顶部操作行（StorageBagDialog 拆分）：赏赐入口 + 灵石余额 */
@Composable
private fun StorageBagHeaderRow(
    spiritStones: Long,
    onRewardClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(GameColors.Warning)
                .clickableWithSound { onRewardClick() }
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
            color = GameColors.Info
        )
    }
}

/** 储物袋物品区（StorageBagDialog 拆分）：空态/计数 + 物品网格 */
@Composable
private fun StorageBagItemList(
    sortedItems: List<StorageBagItem>,
    selectedItem: StorageBagItem?,
    watchedKeys: Set<String>,
    onItemClick: (StorageBagItem) -> Unit,
    onItemLongPress: (StorageBagItem) -> Unit
) {
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
                        onClick = { onItemClick(item) },
                        onLongPress = { onItemLongPress(item) }
                    )
                }
            }
        }
    }
}

/** 储物袋物品详情弹窗（StorageBagDialog 拆分）：详情 + 没收操作 */
@Composable
private fun StorageBagDetailDialog(
    item: StorageBagItem?,
    viewModel: GameViewModel?,
    disciple: DiscipleAggregate,
    onDismiss: () -> Unit
) {
    if (item != null) {
        ItemDetailDialog(
            item = item,
            onDismiss = onDismiss,
            viewModel = viewModel,
            extraActions = {
                GameButton(
                    text = "没收",
                    onClick = {
                        viewModel?.confiscateStorageBagItem(disciple.id, item)
                        onDismiss()
                    },
                    modifier = Modifier.height(32.dp)
                )
            }
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
    var isRewarding by remember { mutableStateOf(false) }
    val rewardScope = rememberCoroutineScope()

    val lists = rememberRewardInventory(viewModel = viewModel)
    val watchedKeys by viewModel.watchedItemIds.collectAsStateWithLifecycle()

    val onItemSelect: (RewardSelectedItem) -> Unit = { item ->
        selectedItem = if (selectedItem?.id == item.id) null else item
        rewardQuantity = 1
    }
    val onRewardClick: () -> Unit = {
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

    RewardDetailHost(viewModel = viewModel) { onViewDetail ->
        UnifiedGameDialog(
            onDismissRequest = onDismiss,
            title = "赏赐道具",
            mode = DialogMode.Full,
            scrollableContent = false
        ) {
            RewardItemsContent(
                data = RewardDialogData(
                    selectedFilter = selectedFilter,
                    lists = lists,
                    watchedKeys = watchedKeys,
                    selectedItem = selectedItem,
                    rewardQuantity = rewardQuantity,
                    maxQuantity = selectedItem?.quantity ?: 1,
                    isRewarding = isRewarding
                ),
                onFilterSelected = { selectedFilter = it },
                onQuantityChange = { rewardQuantity = it },
                onRewardClick = onRewardClick,
                onItemSelect = onItemSelect,
                onViewDetail = onViewDetail
            )
        }
    }
}

/** 赏赐详情弹窗宿主（RewardItemsDialog 拆分）：持有详情状态并渲染 ItemDetailDialog */
@Composable
private fun RewardDetailHost(
    viewModel: GameViewModel,
    content: @Composable (onViewDetail: (Any) -> Unit) -> Unit
) {
    var showDetailDialog by remember { mutableStateOf(false) }
    var detailItem by remember { mutableStateOf<Any?>(null) }
    content { item ->
        detailItem = item
        showDetailDialog = true
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

/** 赏赐面板六类物品列表收集（RewardItemsDialog 拆分）：统一订阅 ViewModel StateFlow */
@Composable
private fun rememberRewardInventory(viewModel: GameViewModel): RewardItemLists {
    val equipmentStacks by viewModel.equipmentStacks.collectAsStateWithLifecycle()
    val manualStacks by viewModel.manualStacks.collectAsStateWithLifecycle()
    val pills by viewModel.pills.collectAsStateWithLifecycle()
    val materials by viewModel.materials.collectAsStateWithLifecycle()
    val herbs by viewModel.herbs.collectAsStateWithLifecycle()
    val seeds by viewModel.seeds.collectAsStateWithLifecycle()
    return RewardItemLists(
        equipment = equipmentStacks,
        manuals = manualStacks,
        pills = pills,
        materials = materials,
        herbs = herbs,
        seeds = seeds
    )
}

/** 赏赐面板六类物品列表打包（RewardItemsDialog 拆分，参数 >6 规避 LongParameterList） */
private data class RewardItemLists(
    val equipment: List<EquipmentStack>,
    val manuals: List<ManualStack>,
    val pills: List<Pill>,
    val materials: List<Material>,
    val herbs: List<Herb>,
    val seeds: List<Seed>
)

/** 赏赐面板渲染状态打包（RewardItemsDialog 拆分，参数 >6 规避 LongParameterList） */
private data class RewardDialogData(
    val selectedFilter: RewardFilter,
    val lists: RewardItemLists,
    val watchedKeys: Set<String>,
    val selectedItem: RewardSelectedItem?,
    val rewardQuantity: Int,
    val maxQuantity: Int,
    val isRewarding: Boolean
)

/** 赏赐面板主体（RewardItemsDialog 拆分）：筛选行 + 物品区 + 底部操作栏 */
@Composable
private fun RewardItemsContent(
    data: RewardDialogData,
    onFilterSelected: (RewardFilter) -> Unit,
    onQuantityChange: (Int) -> Unit,
    onRewardClick: () -> Unit,
    onItemSelect: (RewardSelectedItem) -> Unit,
    onViewDetail: (Any) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        RewardFilterRows(
            selectedFilter = data.selectedFilter,
            onFilterSelected = onFilterSelected
        )
        Spacer(Modifier.height(4.dp))
        RewardGridArea(
            selectedFilter = data.selectedFilter,
            lists = data.lists,
            watchedKeys = data.watchedKeys,
            selectedItem = data.selectedItem,
            onItemSelect = onItemSelect,
            onViewDetail = onViewDetail
        )
        RewardBottomPanel(
            selectedItem = data.selectedItem,
            rewardQuantity = data.rewardQuantity,
            maxQuantity = data.maxQuantity,
            isRewarding = data.isRewarding,
            onQuantityChange = onQuantityChange,
            onRewardClick = onRewardClick
        )
    }
}

/** 赏赐筛选按钮两行（RewardItemsDialog 拆分）：第一行 全部/装备/丹药/功法，第二行 草药/种子/材料 */
@Composable
private fun RewardFilterRows(
    selectedFilter: RewardFilter,
    onFilterSelected: (RewardFilter) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        FilterButton(
            text = RewardFilter.ALL.displayName,
            selected = selectedFilter == RewardFilter.ALL,
            onClick = { onFilterSelected(RewardFilter.ALL) },
            modifier = Modifier.weight(1f)
        )
        FilterButton(
            text = RewardFilter.EQUIPMENT.displayName,
            selected = selectedFilter == RewardFilter.EQUIPMENT,
            onClick = { onFilterSelected(RewardFilter.EQUIPMENT) },
            modifier = Modifier.weight(1f)
        )
        FilterButton(
            text = RewardFilter.PILL.displayName,
            selected = selectedFilter == RewardFilter.PILL,
            onClick = { onFilterSelected(RewardFilter.PILL) },
            modifier = Modifier.weight(1f)
        )
        FilterButton(
            text = RewardFilter.MANUAL.displayName,
            selected = selectedFilter == RewardFilter.MANUAL,
            onClick = { onFilterSelected(RewardFilter.MANUAL) },
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
            onClick = { onFilterSelected(RewardFilter.HERB) },
            modifier = Modifier.weight(1f)
        )
        FilterButton(
            text = RewardFilter.SEED.displayName,
            selected = selectedFilter == RewardFilter.SEED,
            onClick = { onFilterSelected(RewardFilter.SEED) },
            modifier = Modifier.weight(1f)
        )
        FilterButton(
            text = RewardFilter.MATERIAL.displayName,
            selected = selectedFilter == RewardFilter.MATERIAL,
            onClick = { onFilterSelected(RewardFilter.MATERIAL) },
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.weight(1f))
    }
}

/** 赏赐物品区（RewardItemsDialog 拆分）：按筛选渲染全量/单类网格 */
@Composable
private fun ColumnScope.RewardGridArea(
    selectedFilter: RewardFilter,
    lists: RewardItemLists,
    watchedKeys: Set<String>,
    selectedItem: RewardSelectedItem?,
    onItemSelect: (RewardSelectedItem) -> Unit,
    onViewDetail: (Any) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .background(GameColors.CardBackground)
    ) {
        if (selectedFilter == RewardFilter.ALL) {
            RewardAllItemsGrid(
                equipment = lists.equipment,
                manuals = lists.manuals,
                pills = lists.pills,
                materials = lists.materials,
                herbs = lists.herbs,
                seeds = lists.seeds,
                watchedKeys = watchedKeys,
                selectedItem = selectedItem,
                onItemSelect = onItemSelect,
                onViewDetail = onViewDetail
            )
        } else {
            RewardItemGrid(
                items = lists.itemsFor(filter = selectedFilter),
                watchedKeys = watchedKeys,
                selectedItem = selectedItem,
                onItemSelect = onItemSelect,
                onViewDetail = onViewDetail
            )
        }
    }
}

/** 非"全部"筛选 → 对应物品列表（RewardItemsDialog 拆分；ALL 由调用方单独走全量网格） */
private fun RewardItemLists.itemsFor(filter: RewardFilter): List<GameItem> = when (filter) {
    RewardFilter.EQUIPMENT -> equipment
    RewardFilter.PILL -> pills
    RewardFilter.MANUAL -> manuals
    RewardFilter.HERB -> herbs
    RewardFilter.SEED -> seeds
    RewardFilter.MATERIAL -> materials
    RewardFilter.ALL -> emptyList()
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
                key = { rewardItemKey(it as Any) },
                contentType = { "reward_item" }
            ) { item ->
                RewardGridItemCard(
                    item = item as Any,
                    watchedKeys = watchedKeys,
                    selectedItem = selectedItem,
                    onItemSelect = onItemSelect,
                    onViewDetail = onViewDetail
                )
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
                key = { rewardItemKey(it) },
                contentType = { "game_item" }
            ) { item ->
                RewardGridItemCard(
                    item = item,
                    watchedKeys = watchedKeys,
                    selectedItem = selectedItem,
                    onItemSelect = onItemSelect,
                    onViewDetail = onViewDetail
                )
            }
        }
    }
}

/** 物品网格稳定 key（RewardItemGrid/RewardAllItemsGrid 拆分） */
private fun rewardItemKey(item: Any): String = when (item) {
    is EquipmentStack -> "equipment_${item.id}"
    is ManualStack -> "manual_${item.id}"
    is Pill -> "pill_${item.id}_${item.quantity}"
    is Material -> "material_${item.id}_${item.quantity}"
    is Herb -> "herb_${item.id}_${item.quantity}"
    is Seed -> "seed_${item.id}_${item.quantity}"
    else -> "unknown_${System.identityHashCode(item)}"
}

/** 奖励网格单项卡片（RewardItemGrid/RewardAllItemsGrid 拆分） */
@Composable
private fun RewardGridItemCard(
    item: Any,
    watchedKeys: Set<String>,
    selectedItem: RewardSelectedItem?,
    onItemSelect: (RewardSelectedItem) -> Unit,
    onViewDetail: (Any) -> Unit
) {
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
            RewardQuantityStepper(
                selectedItem = selectedItem,
                rewardQuantity = rewardQuantity,
                maxQuantity = maxQuantity,
                isRewarding = isRewarding,
                onQuantityChange = onQuantityChange
            )

            GameButton(
                text = if (isRewarding) "赏赐中..." else "赏赐",
                onClick = onRewardClick,
                modifier = Modifier.height(36.dp),
                enabled = selectedItem != null && rewardQuantity > 0 && !isRewarding
            )
        }
    }
}

/** 赏赐数量调节器（RewardBottomPanel 拆分）：减号/数量/加号/上限 */
@Composable
private fun RewardQuantityStepper(
    selectedItem: RewardSelectedItem?,
    rewardQuantity: Int,
    maxQuantity: Int,
    isRewarding: Boolean,
    onQuantityChange: (Int) -> Unit
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
                    .background(if (rewardQuantity > 1 && !isRewarding) GameColors.Success else GameColors.Border)
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
                    .background(if (rewardQuantity < maxQuantity && !isRewarding) GameColors.Success else GameColors.Border)
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
