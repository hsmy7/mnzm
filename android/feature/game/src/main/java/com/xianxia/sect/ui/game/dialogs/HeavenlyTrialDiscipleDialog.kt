package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.DiscipleSlot
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.game.DiscipleDetailRequest
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.HeavenlyTrialViewModel
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorConfig
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorDialog

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
        val currentSlotDiscipleId = selectedDisciples[pickerSlotIndex]?.id
        val alreadySelectedIds = selectedDisciples
            .filterIndexed { idx, d -> idx != pickerSlotIndex && d != null }
            .mapNotNull { it?.id }
            .toSet()
        val collectedGameData by gameViewModel.gameData.collectAsState()
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
                title = "选择弟子",
                emptyMessage = "暂无空闲弟子",
                currentId = currentSlotDiscipleId,
                additionalCheck = { d ->
                    d.realmLayer > 0 && (d.id == currentSlotDiscipleId || d.id !in alreadySelectedIds)
                },
                alwaysIncludeCurrentId = true
            ),
            disciples = aliveDisciples,
            showAllEnabled = showAllEnabled,
            battleAndExplorationIds = battleAndExplorationIds,
            onDismiss = { showDisciplePicker = false },
            onConfirm = { selected ->
                selected.firstOrNull()?.let { disciple ->
                    if (showAllEnabled && disciple.status != DiscipleStatus.IDLE) {
                        gameViewModel.releaseDiscipleForReassignment(disciple.id)
                    }
                    selectedDisciples[pickerSlotIndex] = disciple
                    showDisciplePicker = false
                }
            }
        )
    }
}

