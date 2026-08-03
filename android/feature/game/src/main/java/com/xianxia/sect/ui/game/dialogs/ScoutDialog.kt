package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.DiscipleSlot
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.game.DiscipleDetailRequest
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorConfig
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorDialog

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
        val currentSlotIndex = selectedSlotIndex ?: return
        val alreadySelectedIds = slots.filterNotNull().map { it.id }.filter { it != slots[currentSlotIndex]?.id }.toSet()
        val currentSlotDiscipleId = slots[currentSlotIndex]?.id
        val collectedGameData by viewModel.gameData.collectAsState()
        val showAllEnabled = collectedGameData.showAllAvailableDisciples
        val battleAndExplorationIds = remember(collectedGameData) {
            val battleIds = collectedGameData.battleTeams.flatMap { it.slots.map { it.discipleId } }
                .filter { it.isNotEmpty() }.toSet()
            val explorationIds = collectedGameData.caveExplorationTeams.flatMap { it.memberIds }
                .filter { it.isNotEmpty() }.toSet()
            battleIds + explorationIds
        }
        DiscipleSelectorDialog(
            config = DiscipleSelectorConfig(
                title = "选择探查弟子",
                emptyMessage = "暂无空闲弟子",
                currentId = currentSlotDiscipleId,
                additionalCheck = { d ->
                    d.realmLayer > 0 && (d.id == currentSlotDiscipleId || d.id !in alreadySelectedIds)
                },
                alwaysIncludeCurrentId = true
            ),
            disciples = disciples,
            showAllEnabled = showAllEnabled,
            viewModel = viewModel,
            battleAndExplorationIds = battleAndExplorationIds,
            onDismiss = {
                showDiscipleSelection = false
                selectedSlotIndex = null
            },
            onConfirm = { selected ->
                selected.firstOrNull()?.let { disciple ->
                    if (showAllEnabled && disciple.status != DiscipleStatus.IDLE) {
                        viewModel.releaseDiscipleForReassignment(disciple.id)
                    }
                    slots[currentSlotIndex] = disciple
                    showDiscipleSelection = false
                    selectedSlotIndex = null
                }
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
    DiscipleSlot(
        disciple = disciple,
        showActions = true,
        onSlotClick = { onSlotClick() },
        onEmptySlotClick = { onSwap() },
        onDismiss = { onDismiss() },
        onSwap = { onSwap() }
    )
}

