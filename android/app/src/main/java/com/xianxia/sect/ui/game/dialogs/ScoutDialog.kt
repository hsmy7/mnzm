package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.util.sortedByFollowAndRealm
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.DiscipleSlotWithActions
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.PortraitDiscipleCard
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.game.ATTRIBUTE_FILTER_OPTIONS
import com.xianxia.sect.ui.game.DiscipleDetailRequest
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.applyFilters
import com.xianxia.sect.ui.game.components.SpiritRootAttributeFilterBar
import com.xianxia.sect.ui.game.getSpiritRootCount
import com.xianxia.sect.ui.game.tabs.REALM_FILTER_OPTIONS

@Composable
internal fun ScoutDialog(
    sectName: String,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    onScout: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val slots = remember { mutableStateListOf<DiscipleAggregate?>().apply { repeat(10) { add(null) } } }
    var selectedSlotIndex by remember { mutableStateOf<Int?>(null) }
    var showDiscipleSelection by remember { mutableStateOf(false) }

    val filledCount = slots.count { it != null }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "探查 — $sectName",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (row in 0..1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                    ) {
                        for (col in 0..4) {
                            val slotIndex = row * 5 + col
                            if (slotIndex < slots.size) {
                                ScoutSlotBox(
                                    disciple = slots[slotIndex],
                                    onSlotClick = {
                                        val disciple = slots[slotIndex]
                                        if (disciple != null) {
                                            viewModel.showDiscipleDetail(DiscipleDetailRequest(disciple, disciples))
                                        } else {
                                            selectedSlotIndex = slotIndex
                                            showDiscipleSelection = true
                                        }
                                    },
                                    onDismiss = {
                                        slots[slotIndex] = null
                                    },
                                    onSwap = {
                                        selectedSlotIndex = slotIndex
                                        showDiscipleSelection = true
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "已选择 $filledCount/10 名弟子",
                    fontSize = 11.sp,
                    color = Color.Black
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GameButton(
                    text = "取消",
                    onClick = onDismiss
                )
                GameButton(
                    text = "探查",
                    onClick = {
                        val selected = slots.mapNotNull { it?.id }
                        if (selected.isNotEmpty()) {
                            onScout(selected)
                        }
                    },
                    enabled = filledCount > 0
                )
            }
        }
    }

    if (showDiscipleSelection && selectedSlotIndex != null) {
        val currentSlotIndex = selectedSlotIndex!!
        val alreadySelectedIds = slots.filterNotNull().map { it.id }.filter { it != slots[currentSlotIndex]?.id }.toSet()
        ScoutDiscipleSelectionDialog(
            disciples = disciples,
            currentSlotDiscipleId = slots[currentSlotIndex]?.id,
            alreadySelectedIds = alreadySelectedIds,
            onSelect = { disciple ->
                slots[currentSlotIndex] = disciple
                showDiscipleSelection = false
                selectedSlotIndex = null
            },
            onDismiss = {
                showDiscipleSelection = false
                selectedSlotIndex = null
            }
        )
    }
}

@Composable
private fun ScoutSlotBox(
    disciple: DiscipleAggregate?,
    onSlotClick: () -> Unit,
    onDismiss: () -> Unit,
    onSwap: () -> Unit
) {
    DiscipleSlotWithActions(
        disciple = disciple,
        onSlotClick = { onSlotClick() },
        onEmptySlotClick = { onSwap() },
        onDismiss = { onDismiss() },
        onSwap = { onSwap() }
    )
}

@Composable
private fun ScoutDiscipleSelectionDialog(
    disciples: List<DiscipleAggregate>,
    currentSlotDiscipleId: String? = null,
    alreadySelectedIds: Set<String> = emptySet(),
    onSelect: (DiscipleAggregate) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedRealmFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedSpiritRootFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedAttributeSort by remember { mutableStateOf<String?>(null) }
    var spiritRootExpanded by remember { mutableStateOf(false) }
    var attributeExpanded by remember { mutableStateOf(false) }
    var realmExpanded by remember { mutableStateOf(false) }

    val availableDisciples = remember(disciples, alreadySelectedIds) {
        disciples.filter {
            it.isAlive && it.status == DiscipleStatus.IDLE && it.realmLayer > 0 &&
            (it.id == currentSlotDiscipleId || it.id !in alreadySelectedIds)
        }
            .sortedByFollowAndRealm()
    }

    val realmCounts = remember(availableDisciples) {
        availableDisciples.groupingBy { it.realm }.eachCount()
    }

    val spiritRootCounts = remember(availableDisciples) {
        availableDisciples.groupingBy { it.getSpiritRootCount() }.eachCount()
    }

    val filteredDisciples = remember(availableDisciples, selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort) {
        availableDisciples.applyFilters(selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort)
    }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "选择探查弟子",
        mode = DialogMode.Half,
        scrollableContent = false,
        headerContent = {
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
        }
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                if (availableDisciples.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无空闲弟子",
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filteredDisciples, key = { it.id }) { disciple ->
                            PortraitDiscipleCard(
                                disciple = disciple,
                                isSelected = disciple.id == currentSlotDiscipleId,
                                onClick = { onSelect(disciple) }
                            )
                        }
                    }
                }
            }
        }
    }
}
