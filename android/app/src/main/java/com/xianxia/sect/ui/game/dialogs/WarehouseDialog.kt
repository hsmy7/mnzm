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
import com.xianxia.sect.ui.components.DiscipleSlotWithActions
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.ProductionViewModel
import com.xianxia.sect.ui.game.DiscipleDetailRequest

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

    val activeSectId = gameData?.activeSectId ?: ""
    val garrisonSlot = gameData?.warehouseGarrisons?.find {
        it.buildingInstanceId == buildingInstanceId
    }
    val garrisonDisciple = disciples.find { it.id == garrisonSlot?.discipleId }

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

            DiscipleSlotWithActions(
                disciple = garrisonDisciple,
                borderColor = borderColor,
                onSlotClick = {
                    garrisonDisciple?.let {
                        viewModel.showDiscipleDetail(DiscipleDetailRequest(it, disciples))
                    }
                },
                onEmptySlotClick = { showGarrisonSelect = true },
                onDismiss = {
                    productionViewModel.removeWarehouseGarrison(buildingInstanceId)
                },
                onSwap = { showGarrisonSelect = true }
            )
        }
    }

    if (showGarrisonSelect) {
        val availableDisciples = disciples.filter { d ->
            d.isAlive && d.status == DiscipleStatus.IDLE &&
                gameData?.warehouseGarrisons?.none { it.discipleId == d.id } == true
        }.sortedWith(compareBy<DiscipleAggregate> { it.realm }
            .thenByDescending { it.realmLayer })

        UnifiedGameDialog(
            onDismissRequest = { showGarrisonSelect = false },
            title = "选择驻守弟子",
            mode = DialogMode.Half,
            scrollableContent = false
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (availableDisciples.isEmpty()) {
                    Text("无可用弟子", fontSize = 12.sp, color = Color.Black)
                } else {
                    availableDisciples.forEach { d ->
                        DiscipleSlotWithActions(
                            disciple = d,
                            borderColor = try {
                                Color(android.graphics.Color.parseColor(d.spiritRoot.countColor))
                            } catch (e: Exception) {
                                Color(0xFF4CAF50)
                            },
                            onSlotClick = {
                                productionViewModel.assignWarehouseGarrison(
                                    buildingInstanceId, d.id, d.name, activeSectId
                                )
                                showGarrisonSelect = false
                            },
                            onEmptySlotClick = {},
                            onDismiss = {},
                            onSwap = {}
                        )
                    }
                }
            }
        }
    }
}
