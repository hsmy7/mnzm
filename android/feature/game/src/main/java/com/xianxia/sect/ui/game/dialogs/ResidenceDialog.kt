package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.ui.game.filterByDiscipleStatus
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.ui.components.*
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorConfig
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorDialog

@Composable
fun ResidenceDialog(
    buildingInstanceId: String,
    viewModel: GameViewModel,
    disciples: List<DiscipleAggregate>,
    gameData: GameData,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val building = gameData.placedBuildings.find { it.instanceId == buildingInstanceId } ?: return
    val feature = com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry.findByDisplayName(building.displayName)
    val isSingleResidence = feature?.isResidence == true && feature.slotGroups.any { it is com.xianxia.sect.core.engine.domain.building.SlotGroup.Residence && it.slotsPerInstance == 1 }
    val slotCount = if (isSingleResidence) 1 else 4

    val residenceSlots = gameData.residenceSlots.filter { it.buildingInstanceId == buildingInstanceId }
    val slots = (0 until slotCount).map { index ->
        residenceSlots.find { it.slotIndex == index }
            ?: com.xianxia.sect.core.model.ResidenceSlot(buildingInstanceId = buildingInstanceId, slotIndex = index)
    }

    val bonusText = feature?.residenceSpeedBonus ?: ""
    val discipleMap = disciples.associateBy { it.id }

    var showDiscipleSelector by remember { mutableStateOf(false) }
    var selectedSlotIndex by remember { mutableStateOf(0) }
    var isSwapping by remember { mutableStateOf(false) }

    // Main dialog
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "弟子住所",
        mode = DialogMode.Half,
        scrollableContent = true
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Bonus text
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                val bonusParts = bonusText.split("+")
                if (bonusParts.size == 2) {
                    Text(
                        text = bonusParts[0],
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                    Text(
                        text = "+${bonusParts[1]}",
                        fontSize = 12.sp,
                        color = Color(0xFF4CAF50),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Disciple slots
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
            ) {
                slots.forEach { slot ->
                    val disciple = slot.discipleId.let { id ->
                        if (id.isNotEmpty()) discipleMap[id] else null
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        DiscipleSlot(
                            disciple = disciple,
                            onEmptySlotClick = {
                                selectedSlotIndex = slot.slotIndex
                                isSwapping = false
                                showDiscipleSelector = true
                            }
                        )
                        if (disciple != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    text = "搬离",
                                    fontSize = 9.sp,
                                    color = Color(0xFFE53935),
                                    modifier = Modifier.clickable {
                                        scope.launch {
                                            viewModel.removeFromResidence(buildingInstanceId, slot.slotIndex)
                                        }
                                    }
                                )
                                Text(
                                    text = "更换",
                                    fontSize = 9.sp,
                                    color = Color.Black,
                                    modifier = Modifier.clickable {
                                        selectedSlotIndex = slot.slotIndex
                                        isSwapping = true
                                        showDiscipleSelector = true
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }

    // Disciple selector
    if (showDiscipleSelector) {
        val showAllEnabled = gameData.showAllAvailableDisciples
        val battleAndExplorationIds = remember {
            val battleIds = gameData.battleTeams.flatMap { it.slots.map { it.discipleId } }.filter { it.isNotEmpty() }.toSet()
            val explorationIds = gameData.caveExplorationTeams.flatMap { it.memberIds }.filter { it.isNotEmpty() }.toSet()
            battleIds + explorationIds
        }
        val occupiedIds = gameData.residenceSlots.mapNotNull { it.discipleId.ifEmpty { null } }.toSet()
        val eligibleDisciples = disciples.filter { it.isAlive && it.id !in occupiedIds }
        DiscipleSelectorDialog(
            config = DiscipleSelectorConfig(
                title = if (isSwapping) "更换弟子" else "选择入住弟子",
                emptyMessage = "没有可分配的弟子"
            ),
            disciples = eligibleDisciples,
            onDismiss = { showDiscipleSelector = false; isSwapping = false },
            onConfirm = { selected ->
                if (selected.isNotEmpty()) {
                    scope.launch {
                        viewModel.assignToResidence(buildingInstanceId, selectedSlotIndex, selected.first().id)
                    }
                }
                showDiscipleSelector = false
                isSwapping = false
            },
            viewModel = viewModel,
            showAllEnabled = showAllEnabled,
            battleAndExplorationIds = battleAndExplorationIds
        )
    }
}
