package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xianxia.sect.R
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.ui.components.*
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorConfig
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorDialog
import com.xianxia.sect.ui.theme.ButtonSizes

@Composable
fun ResidenceDialog(
    buildingInstanceId: String,
    viewModel: GameViewModel,
    disciples: List<DiscipleAggregate>,
    gameData: GameData,
    onDismiss: () -> Unit
) {
    val building = gameData.placedBuildings.find { it.instanceId == buildingInstanceId } ?: return
    val isSingleResidence = building.displayName == "单人住所" || building.displayName == "中级单人住所"
    val isUpgraded = building.displayName == "中级单人住所"
    val slotCount = if (isSingleResidence) 1 else 4

    val residenceSlots = gameData.residenceSlots.filter { it.buildingInstanceId == buildingInstanceId }
    val slots = (0 until slotCount).map { index ->
        residenceSlots.find { it.slotIndex == index }
            ?: com.xianxia.sect.core.model.ResidenceSlot(buildingInstanceId = buildingInstanceId, slotIndex = index)
    }

    val bonusText = when (building.displayName) {
        "中级单人住所" -> "修炼速度+40%"
        "单人住所" -> "修炼速度+20%"
        "多人住所" -> "修炼速度+10%"
        else -> ""
    }

    var showDiscipleSelector by remember { mutableStateOf(false) }
    var selectedSlotIndex by remember { mutableStateOf(0) }
    var isSwapping by remember { mutableStateOf(false) }

    // Upgrade dialog state
    var showUpgradeConfirm by remember { mutableStateOf(false) }

    // Upgrade confirmation dialog
    if (showUpgradeConfirm) {
        val canAfford = gameData.spiritStones >= 50000L
        val config = LocalConfiguration.current
        val dialogWidth = (config.screenWidthDp * 0.4f).dp
        val dialogHeight = (config.screenHeightDp * 0.3f).dp
        Dialog(
            onDismissRequest = { showUpgradeConfirm = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            Box(
                modifier = Modifier
                    .width(dialogWidth)
                    .height(dialogHeight)
                    .clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.dialog_box),
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.FillBounds
                )
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "升级需要消耗 50000 灵石",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    GameButton(
                        text = "确认升级",
                        onClick = {
                            showUpgradeConfirm = false
                            viewModel.upgradeSingleResidence(buildingInstanceId)
                        },
                        enabled = canAfford
                    )
                }
            }
        }
    }

    // Main dialog
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "弟子住所",
        mode = DialogMode.Half
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
                        if (id.isNotEmpty()) disciples.find { it.id == id } else null
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
                                        viewModel.removeFromResidence(buildingInstanceId, slot.slotIndex)
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

            // Upgrade button (only for non-upgraded single residence)
            if (isSingleResidence && !isUpgraded) {
                GameButton(
                    text = "升级住所",
                    onClick = { showUpgradeConfirm = true },
                    width = ButtonSizes.StandardWidth,
                    height = ButtonSizes.StandardHeight
                )
            }
        }
    }

    // Disciple selector
    if (showDiscipleSelector) {
        val occupiedIds = gameData.residenceSlots.mapNotNull { it.discipleId.ifEmpty { null } }.toSet()
        val eligibleDisciples = disciples.filter {
            it.isAlive && it.status != DiscipleStatus.REFLECTING && it.id !in occupiedIds
        }
        DiscipleSelectorDialog(
            config = DiscipleSelectorConfig(
                title = if (isSwapping) "更换弟子" else "选择入住弟子",
                emptyMessage = "没有可分配的弟子"
            ),
            disciples = eligibleDisciples,
            onDismiss = { showDiscipleSelector = false; isSwapping = false },
            onConfirm = { selected ->
                if (selected.isNotEmpty()) {
                    viewModel.assignToResidence(buildingInstanceId, selectedSlotIndex, selected.first().id)
                }
                showDiscipleSelector = false
                isSwapping = false
            }
        )
    }
}
