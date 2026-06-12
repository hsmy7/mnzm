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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.RewardSelectedItem
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.HalfScreenDialog
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.SPIRIT_ROOT_FILTER_OPTIONS
import com.xianxia.sect.ui.game.applyFilters
import com.xianxia.sect.ui.game.components.SpiritRootAttributeFilterBar
import com.xianxia.sect.ui.game.getAttributeValue
import com.xianxia.sect.ui.game.getSpiritRootCount
import com.xianxia.sect.ui.theme.GameColors
import kotlinx.coroutines.launch

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

    val aliveDisciples = remember {
        disciples.filter { it.isAlive && it.status != DiscipleStatus.REFLECTING }
    }

    val currentQuantity by remember(itemType, itemId) {
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
    var realmExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val realmCounts = remember(aliveDisciples) {
        aliveDisciples.groupingBy { it.realm }.eachCount()
    }

    val spiritRootCounts = remember(aliveDisciples) {
        aliveDisciples.groupingBy { it.getSpiritRootCount() }.eachCount()
    }

    val filteredAndSortedDisciples = remember(aliveDisciples, selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort) {
        aliveDisciples.applyFilters(selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort)
            .distinctBy { it.id }
    }

    HalfScreenDialog(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
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
                        color = Color.Black
                    )
                }
                CloseButton(onClick = onDismiss)
            }

            SpiritRootAttributeFilterBar(
                selectedSpiritRootFilter = selectedSpiritRootFilter,
                selectedAttributeSort = selectedAttributeSort,
                selectedRealmFilter = selectedRealmFilter,
                realmFilterOptions = REALM_FILTER_OPTIONS,
                realmCounts = realmCounts,
                spiritRootExpanded = spiritRootExpanded,
                attributeExpanded = attributeExpanded,
                realmExpanded = realmExpanded,
                spiritRootCounts = spiritRootCounts,
                onSpiritRootFilterSelected = { selectedSpiritRootFilter = selectedSpiritRootFilter + it },
                onSpiritRootFilterRemoved = { selectedSpiritRootFilter = selectedSpiritRootFilter - it },
                onAttributeSortSelected = { selectedAttributeSort = it },
                onRealmFilterSelected = { selectedRealmFilter = selectedRealmFilter + it },
                onRealmFilterRemoved = { selectedRealmFilter = selectedRealmFilter - it },
                onSpiritRootExpandToggle = { spiritRootExpanded = !spiritRootExpanded },
                onAttributeExpandToggle = { attributeExpanded = !attributeExpanded },
                onRealmExpandToggle = { realmExpanded = !realmExpanded },
                isCompact = true
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
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
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
