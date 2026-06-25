package com.xianxia.sect.ui.game.dialogs

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
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.util.sortedByFollowAndRealm
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.DiscipleSlot
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.PortraitDiscipleCard
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.game.ATTRIBUTE_FILTER_OPTIONS
import com.xianxia.sect.ui.game.DiscipleDetailRequest
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.HeavenlyTrialViewModel
import com.xianxia.sect.ui.game.SPIRIT_ROOT_FILTER_OPTIONS
import com.xianxia.sect.ui.game.applyFilters
import com.xianxia.sect.ui.game.components.SpiritRootAttributeFilterBar
import com.xianxia.sect.ui.game.getSpiritRootCount
import com.xianxia.sect.ui.game.REALM_FILTER_OPTIONS

@Composable
fun HeavenlyTrialDiscipleDialog(
    viewModel: HeavenlyTrialViewModel,
    gameViewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val aliveDisciples by viewModel.aliveDisciples.collectAsStateWithLifecycle()

    val selectedDisciples = remember { mutableStateListOf<DiscipleAggregate?>(null, null, null) }
    var showDisciplePicker by remember { mutableStateOf(false) }
    var pickerSlotIndex by remember { mutableStateOf(0) }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "选择出战弟子",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        // 3个弟子槽位
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            for (slotIdx in 0 until 3) {
                val disciple = selectedDisciples[slotIdx]
                DiscipleSlot(
                    disciple = disciple,
                    showActions = true,
                    onSlotClick = {
                        if (disciple != null) {
                            gameViewModel.showDiscipleDetail(
                                DiscipleDetailRequest(disciple, aliveDisciples)
                            )
                        }
                    },
                    onEmptySlotClick = {
                        pickerSlotIndex = slotIdx
                        showDisciplePicker = true
                    },
                    onDismiss = { selectedDisciples[slotIdx] = null },
                    onSwap = {
                        pickerSlotIndex = slotIdx
                        showDisciplePicker = true
                    }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 确认出战按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            GameButton("确认出战", onClick = {
                val chosen = selectedDisciples.filterNotNull()
                if (chosen.isNotEmpty()) {
                    viewModel.startCombat(chosen)
                }
            })
        }
    }

    // 选择弟子子界面（带境界筛选）
    if (showDisciplePicker) {
        DisciplePickerDialog(
            aliveDisciples = aliveDisciples,
            currentSlotDiscipleId = selectedDisciples[pickerSlotIndex]?.id,
            alreadySelectedIds = selectedDisciples
                .filterIndexed { idx, d -> idx != pickerSlotIndex && d != null }
                .mapNotNull { it?.id }
                .toSet(),
            onSelect = { disciple ->
                selectedDisciples[pickerSlotIndex] = disciple
                showDisciplePicker = false
            },
            onDismiss = { showDisciplePicker = false }
        )
    }
}

@Composable
private fun DisciplePickerDialog(
    aliveDisciples: List<DiscipleAggregate>,
    currentSlotDiscipleId: String?,
    alreadySelectedIds: Set<String>,
    onSelect: (DiscipleAggregate) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedRealmFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedSpiritRootFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedAttributeSort by remember { mutableStateOf<String?>(null) }
    var spiritRootExpanded by remember { mutableStateOf(false) }
    var attributeExpanded by remember { mutableStateOf(false) }
    var realmExpanded by remember { mutableStateOf(false) }

    val availableDisciples = remember(aliveDisciples, alreadySelectedIds) {
        aliveDisciples.filter {
            it.isAlive && it.status == DiscipleStatus.IDLE && it.realmLayer > 0 &&
            (it.id == currentSlotDiscipleId || it.id !in alreadySelectedIds)
        }.sortedByFollowAndRealm()
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
        title = "选择弟子",
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
        if (availableDisciples.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("暂无空闲弟子", fontSize = 12.sp, color = Color.Black)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
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
