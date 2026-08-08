package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.LibrarySlot
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.ui.components.DiscipleSlot
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.ProductionViewModel
import com.xianxia.sect.ui.game.DiscipleDetailRequest
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorConfig
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorDialog
import com.xianxia.sect.ui.game.dialogs.shared.ScrollableInfoDialog



@Composable
fun LibraryDialog(
    manuals: List<ManualInstance>,
    disciples: List<DiscipleAggregate>,
    gameData: GameData?,
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    onDismiss: () -> Unit
) {
    var showDiscipleSelection by remember { mutableStateOf<Int?>(null) }

    val librarySlots = gameData?.librarySlots ?: emptyList()
    val slots = (0 until 3).map { index ->
        librarySlots.find { it.index == index } ?: LibrarySlot(index = index)
    }
    val discipleMap = disciples.associateBy { it.id }

    ScrollableInfoDialog(
        title = "藏经阁",
        onDismiss = onDismiss
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "入驻弟子功法熟练度增长速度提高50%",
                fontSize = 10.sp,
                color = GameColors.Success
            )
            
            slots.chunked(3).forEach { rowSlots ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                ) {
                    rowSlots.forEach { slot ->
                        val disciple = slot.discipleId?.let { id -> discipleMap[id] }
                        LibrarySlotItem(
                            slot = slot,
                            disciple = disciple,
                            onAssign = { showDiscipleSelection = slot.index },
                            onRemove = { productionViewModel.removeDiscipleFromLibrarySlot(slot.index) },
                            onSwap = { showDiscipleSelection = slot.index },
                            onSlotClick = { disciple?.let { viewModel.showDiscipleDetail(DiscipleDetailRequest(it, disciples)) } }
                        )
                    }
                }
            }
        }
    }

    showDiscipleSelection?.let { slotIndex ->
        val currentDiscipleId = slots.getOrNull(slotIndex)?.discipleId
        val showAllEnabled = gameData?.showAllAvailableDisciples == true
        val battleAndExplorationIds = remember(gameData) {
            val battleIds = gameData?.battleTeams.orEmpty()
                .flatMap { it.slots.map { it.discipleId } }.filter { it.isNotEmpty() }.toSet()
            val explorationIds = gameData?.caveExplorationTeams.orEmpty()
                .flatMap { it.memberIds }.filter { it.isNotEmpty() }.toSet()
            battleIds + explorationIds
        }
        DiscipleSelectorDialog(
            config = DiscipleSelectorConfig(
                title = "选择弟子",
                emptyMessage = "暂无可用弟子",
                currentId = currentDiscipleId,
                additionalCheck = { it.realmLayer > 0 && it.age >= 5 },
                alwaysIncludeCurrentId = true
            ),
            disciples = disciples,
            showAllEnabled = showAllEnabled,
            viewModel = viewModel,
            battleAndExplorationIds = battleAndExplorationIds,
            onDismiss = { showDiscipleSelection = null },
            onConfirm = { selected ->
                selected.firstOrNull()?.let { disciple ->
                    if (showAllEnabled && disciple.status != DiscipleStatus.IDLE) {
                        viewModel.releaseDiscipleForReassignment(disciple.id)
                    }
                    productionViewModel.assignDiscipleToLibrarySlot(slotIndex, disciple.id, disciple.name)
                    showDiscipleSelection = null
                }
            }
        )
    }

}

@Composable
private fun LibrarySlotItem(
    slot: LibrarySlot,
    disciple: DiscipleAggregate?,
    onAssign: () -> Unit,
    onRemove: () -> Unit,
    onSwap: () -> Unit,
    onSlotClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "修炼位 ${slot.index + 1}",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Spacer(modifier = Modifier.height(8.dp))
        val borderColor = if (disciple != null) {
            try {
                Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
            } catch (e: Exception) {
                GameColors.Border
            }
        } else {
            GameColors.Border
        }
        DiscipleSlot(
            disciple = if (slot.discipleId.isNotEmpty()) disciple else null,
            borderColor = borderColor,
            showActions = true,
            onSlotClick = { onSlotClick() },
            onEmptySlotClick = { onAssign() },
            onDismiss = { onRemove() },
            onSwap = { onSwap() }
        )
    }
}

