package com.xianxia.sect.ui.game.tabs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.RewardSelectedItem
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.REALM_FILTER_OPTIONS
import com.xianxia.sect.ui.game.applyFilters
import com.xianxia.sect.ui.game.components.SpiritRootAttributeFilterBar
import com.xianxia.sect.ui.game.filterByDiscipleStatus
import com.xianxia.sect.ui.game.getSpiritRootCount
import com.xianxia.sect.ui.theme.GameColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 赏赐物品上下文 */
private data class RewardItemContext(
    val itemId: String,
    val itemType: String,
    val itemName: String,
    val itemRarity: Int
)

/** 赏赐弟子筛选状态 */
private class RewardDiscipleFilterState {
    var selectedRealmFilter by mutableStateOf<Set<Int>>(emptySet())
    var selectedSpiritRootFilter by mutableStateOf<Set<Int>>(emptySet())
    var selectedAttributeSort by mutableStateOf<String?>(null)
    var spiritRootExpanded by mutableStateOf(false)
    var attributeExpanded by mutableStateOf(false)
    var realmExpanded by mutableStateOf(false)
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
    val disciples by viewModel.discipleAggregates.collectAsStateWithLifecycle()
    val pills by viewModel.pills.collectAsStateWithLifecycle()
    val materials by viewModel.materials.collectAsStateWithLifecycle()
    val herbs by viewModel.herbs.collectAsStateWithLifecycle()
    val seeds by viewModel.seeds.collectAsStateWithLifecycle()
    val equipmentStacks by viewModel.equipmentStacks.collectAsStateWithLifecycle()
    val manualStacks by viewModel.manualStacks.collectAsStateWithLifecycle()

    val filterState = remember { RewardDiscipleFilterState() }
    val gameData by viewModel.gameData.collectAsState()
    val currentQuantity by remember(itemType, itemId) {
        derivedStateOf {
            currentRewardQuantity(
                itemType = itemType,
                itemId = itemId,
                pills = pills,
                materials = materials,
                herbs = herbs,
                seeds = seeds,
                equipmentStacks = equipmentStacks,
                manualStacks = manualStacks
            )
        }
    }
    val itemContext = RewardItemContext(
        itemId = itemId, itemType = itemType, itemName = itemName, itemRarity = itemRarity
    )
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "赏赐弟子",
        mode = DialogMode.Full,
        showCloseButton = true,
        scrollableContent = false,
        headerContent = {
            RewardHeaderContent(itemName = itemName, currentQuantity = currentQuantity)
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            DiscipleRewardFilterBar(
                disciples = disciples,
                gameData = gameData,
                filterState = filterState,
                viewModel = viewModel
            )
            DiscipleRewardGrid(
                disciples = disciples,
                gameData = gameData,
                filterState = filterState,
                currentQuantity = currentQuantity,
                viewModel = viewModel,
                itemContext = itemContext
            )
        }
    }
}

/** 当前物品剩余数量查询 */
@Suppress("LongParameterList")
private fun currentRewardQuantity(
    itemType: String,
    itemId: String,
    pills: List<Pill>,
    materials: List<Material>,
    herbs: List<Herb>,
    seeds: List<Seed>,
    equipmentStacks: List<EquipmentStack>,
    manualStacks: List<ManualStack>
): Int = when (itemType) {
    "pill" -> pills.find { it.id == itemId }?.quantity ?: 0
    "material" -> materials.find { it.id == itemId }?.quantity ?: 0
    "herb" -> herbs.find { it.id == itemId }?.quantity ?: 0
    "seed" -> seeds.find { it.id == itemId }?.quantity ?: 0
    "equipment" -> equipmentStacks.find { it.id == itemId }?.quantity ?: 0
    "manual" -> manualStacks.find { it.id == itemId }?.quantity ?: 0
    else -> 0
}

/** 头部剩余数量提示 */
@Composable
private fun RewardHeaderContent(
    itemName: String,
    currentQuantity: Int
) {
    Text(
        text = "物品: $itemName (剩余: $currentQuantity)",
        fontSize = 12.sp,
        color = Color.Black,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

/** 弟子筛选栏：境界/灵根/属性筛选 + 显示全部 */
@Composable
private fun DiscipleRewardFilterBar(
    disciples: List<DiscipleAggregate>,
    gameData: GameData,
    filterState: RewardDiscipleFilterState,
    viewModel: GameViewModel
) {
    val showAllEnabled = gameData.showAllAvailableDisciples
    val battleAndExplorationIds = remember(gameData) {
        battleAndExplorationIdsFrom(gameData)
    }
    val aliveDisciples = remember(disciples, showAllEnabled, battleAndExplorationIds) {
        disciples.filterByDiscipleStatus(showAllEnabled, battleAndExplorationIds)
    }
    val realmCounts = remember(aliveDisciples) {
        aliveDisciples.groupingBy { it.realm }.eachCount()
    }
    val spiritRootCounts = remember(aliveDisciples) {
        aliveDisciples.groupingBy { it.getSpiritRootCount() }.eachCount()
    }
    SpiritRootAttributeFilterBar(
        selectedSpiritRootFilter = filterState.selectedSpiritRootFilter,
        selectedAttributeSort = filterState.selectedAttributeSort,
        selectedRealmFilter = filterState.selectedRealmFilter,
        realmFilterOptions = REALM_FILTER_OPTIONS,
        realmCounts = realmCounts,
        spiritRootExpanded = filterState.spiritRootExpanded,
        attributeExpanded = filterState.attributeExpanded,
        realmExpanded = filterState.realmExpanded,
        spiritRootCounts = spiritRootCounts,
        onSpiritRootFilterSelected = {
            filterState.selectedSpiritRootFilter = filterState.selectedSpiritRootFilter + it
        },
        onSpiritRootFilterRemoved = {
            filterState.selectedSpiritRootFilter = filterState.selectedSpiritRootFilter - it
        },
        onAttributeSortSelected = { filterState.selectedAttributeSort = it },
        onRealmFilterSelected = { filterState.selectedRealmFilter = filterState.selectedRealmFilter + it },
        onRealmFilterRemoved = { filterState.selectedRealmFilter = filterState.selectedRealmFilter - it },
        onSpiritRootExpandToggle = { filterState.spiritRootExpanded = !filterState.spiritRootExpanded },
        onAttributeExpandToggle = { filterState.attributeExpanded = !filterState.attributeExpanded },
        onRealmExpandToggle = { filterState.realmExpanded = !filterState.realmExpanded },
        isCompact = true,
        showAllCheckboxVisible = true,
        showAllEnabled = showAllEnabled,
        onShowAllToggle = { viewModel.settings.setShowAllAvailableDisciples(!showAllEnabled) }
    )
}

/** 战斗/探索占用弟子 ID */
private fun battleAndExplorationIdsFrom(gameData: GameData): Set<String> {
    val battleIds = gameData.battleTeams.flatMap { it.slots.map { it.discipleId } }.filter { it.isNotEmpty() }.toSet()
    val explorationIds = gameData.caveExplorationTeams.flatMap { it.memberIds }.filter { it.isNotEmpty() }.toSet()
    return battleIds + explorationIds
}

/** 赏赐弟子网格：空态提示 + 弟子卡片列表 */
@Composable
private fun ColumnScope.DiscipleRewardGrid(
    disciples: List<DiscipleAggregate>,
    gameData: GameData,
    filterState: RewardDiscipleFilterState,
    currentQuantity: Int,
    viewModel: GameViewModel,
    itemContext: RewardItemContext
) {
    var isRewarding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val showAllEnabled = gameData.showAllAvailableDisciples
    val battleAndExplorationIds = remember(gameData) {
        battleAndExplorationIdsFrom(gameData)
    }
    val aliveDisciples = remember(disciples, showAllEnabled, battleAndExplorationIds) {
        disciples.filterByDiscipleStatus(showAllEnabled, battleAndExplorationIds)
    }
    val filteredAndSortedDisciples = remember(
        aliveDisciples, filterState.selectedRealmFilter,
        filterState.selectedSpiritRootFilter, filterState.selectedAttributeSort
    ) {
        aliveDisciples.applyFilters(
            filterState.selectedRealmFilter, filterState.selectedSpiritRootFilter, filterState.selectedAttributeSort
        )
            .distinctBy { it.id }
    }
    if (currentQuantity <= 0) {
        DiscipleRewardEmptyState(text = "物品已全部赏赐完毕")
    } else if (filteredAndSortedDisciples.isEmpty()) {
        DiscipleRewardEmptyState(text = "暂无可赏赐的弟子")
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(filteredAndSortedDisciples, key = { it.id }, contentType = { "disciple" }) { disciple ->
                val onClick = rewardDiscipleClickHandler(
                    itemContext = itemContext,
                    isRewarding = isRewarding,
                    currentQuantity = currentQuantity,
                    scope = scope,
                    viewModel = viewModel,
                    onIsRewardingChanged = { isRewarding = it }
                )
                DiscipleCard(
                    disciple = disciple,
                    onClick = { onClick(disciple) }
                )
            }
        }
    }
}

/** 空态提示 */
@Composable
private fun ColumnScope.DiscipleRewardEmptyState(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            color = GameColors.TextSecondary
        )
    }
}

/** 赏赐点击处理：防重复发放 + 发放物品 */
private fun rewardDiscipleClickHandler(
    itemContext: RewardItemContext,
    isRewarding: Boolean,
    currentQuantity: Int,
    scope: CoroutineScope,
    viewModel: GameViewModel,
    onIsRewardingChanged: (Boolean) -> Unit
): (DiscipleAggregate) -> Unit = { disciple ->
    if (!isRewarding && currentQuantity > 0) {
        scope.launch {
            onIsRewardingChanged(true)
            try {
                viewModel.disciple.rewardItemsToDisciple(
                    disciple.id,
                    listOf(RewardSelectedItem(
                        id = itemContext.itemId,
                        type = itemContext.itemType,
                        name = itemContext.itemName,
                        rarity = itemContext.itemRarity,
                        quantity = 1
                    ))
                )
            } finally {
                onIsRewardingChanged(false)
            }
        }
    }
}
