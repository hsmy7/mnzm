package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.*
import com.xianxia.sect.ui.components.ElderBonusInfoButton
import com.xianxia.sect.ui.components.ElderBonusInfoProvider
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.DiscipleSlot
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.ProductionViewModel
import com.xianxia.sect.ui.game.DiscipleDetailRequest
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorDialog
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorConfig
import kotlinx.coroutines.launch

@Composable
fun WarehouseDialog(
    buildingInstanceId: String,
    gameData: GameData?,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    onDismiss: () -> Unit
) {
    var showGarrisonSelect by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val activeSectId = gameData?.activeSectId ?: ""
    val garrisonSlot = gameData?.warehouseGarrisons?.find {
        it.buildingInstanceId == buildingInstanceId
    }
    val discipleMap = disciples.associateBy { it.id }
    val garrisonDisciple = discipleMap[garrisonSlot?.discipleId]

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "仓库",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "驻守弟子",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                ElderBonusInfoButton(bonusInfo = ElderBonusInfoProvider.getWarehouseGarrisonInfo())
            }

            val borderColor = if (garrisonDisciple != null) {
                try {
                    Color(android.graphics.Color.parseColor(garrisonDisciple.spiritRoot.countColor))
                } catch (e: Exception) {
                    Color(0xFF4CAF50)
                }
            } else {
                Color(0xFFE0E0E0)
            }

            DiscipleSlot(
                disciple = garrisonDisciple,
                borderColor = borderColor,
                showActions = true,
                onSlotClick = {
                    garrisonDisciple?.let {
                        viewModel.showDiscipleDetail(DiscipleDetailRequest(it, disciples))
                    }
                },
                onEmptySlotClick = { showGarrisonSelect = true },
                onDismiss = {
                    scope.launch {
                        productionViewModel
                            .removeWarehouseGarrison(buildingInstanceId)
                    }
                },
                onSwap = { showGarrisonSelect = true }
            )
        }
    }

    if (showGarrisonSelect) {
        val showAllEnabled = gameData?.showAllAvailableDisciples ?: false
        val battleAndExplorationIds = remember(gameData) {
            val gd = gameData
            if (gd == null) return@remember emptySet()
            val battleIds = gd.battleTeams.flatMap { t ->
                t.slots.map { it.discipleId }
            }.filter { it.isNotEmpty() }.toSet()
            val explorationIds = gd.caveExplorationTeams.flatMap { t ->
                t.memberIds
            }.filter { it.isNotEmpty() }.toSet()
            battleIds + explorationIds
        }
        val availableDisciples = disciples.filter { d ->
            d.isAlive
                && (gameData?.warehouseGarrisons?.none { it.discipleId == d.id } ?: true)
        }

        DiscipleSelectorDialog(
            config = DiscipleSelectorConfig(title = "选择驻守弟子"),
            disciples = availableDisciples,
            showAllEnabled = showAllEnabled,
            battleAndExplorationIds = battleAndExplorationIds,
            onDismiss = { showGarrisonSelect = false },
            onConfirm = { selected ->
                if (selected.isNotEmpty()) {
                    scope.launch {
                        val disciple = selected.first()
                        if (showAllEnabled
                            && disciple.status != com.xianxia.sect.core.model.DiscipleStatus.IDLE
                        ) {
                            viewModel.releaseDiscipleFromAllSlotsAtomic(disciple.id)
                        }
                        productionViewModel.assignWarehouseGarrison(
                            buildingInstanceId, disciple.id,
                            disciple.name, activeSectId
                        )
                        showGarrisonSelect = false
                    }
                }
            },
            viewModel = viewModel
        )
    }
}
