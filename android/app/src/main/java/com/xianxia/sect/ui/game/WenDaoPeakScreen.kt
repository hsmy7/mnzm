package com.xianxia.sect.ui.game

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.*
import com.xianxia.sect.core.model.*
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.ElderBonusInfoProvider
import com.xianxia.sect.ui.components.HalfScreenDialog
import androidx.compose.ui.window.DialogProperties
import com.xianxia.sect.ui.theme.GameColors

@Composable
fun WenDaoPeakDialog(
    disciples: List<DiscipleAggregate>,
    gameData: GameData?,
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
) {
    var showOuterElderSelection by remember { mutableStateOf(false) }
    var showPreachingElderSelection by remember { mutableStateOf(false) }
    var showPreachingMasterSelection by remember { mutableStateOf<Int?>(null) }

    val outerElder = productionViewModel.getOuterElder()
    val preachingElder = productionViewModel.getPreachingElder()
    val preachingMasters = productionViewModel.getPreachingMasters()
    val outerDisciples = disciples.filter { it.isAlive && it.discipleType == "outer" }

    HalfScreenDialog(onDismissRequest = { viewModel.closeCurrentDialog() }, isFullScreen = false) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("问道塔", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    Text("管理外门弟子与传道修行", fontSize = 11.sp, color = GameColors.TextSecondary)
                }
                CloseButton(onClick = { viewModel.closeCurrentDialog() })
            }
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)
            ) {
        PeakElderSection(
            slot1 = PeakElderSlotConfig(
                title = "外门长老",
                elder = outerElder,
                bonusInfo = ElderBonusInfoProvider.getOuterElderInfo(),
                onClick = { showOuterElderSelection = true },
                onRemove = { productionViewModel.removeElder(ElderSlotType.OUTER_ELDER) }
            ),
            slot2 = PeakElderSlotConfig(
                title = "问道塔传道长老",
                elder = preachingElder,
                bonusInfo = ElderBonusInfoProvider.getWenDaoPreachingElderInfo(),
                onClick = { showPreachingElderSelection = true },
                onRemove = { productionViewModel.removeElder(ElderSlotType.PREACHING) }
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        PeakPreachingMasterSection(
            sectionTitle = "问道塔传道师",
            masterConfig = PeakPreachingMasterConfig(
                label = "问道塔传道师",
                bonusInfo = ElderBonusInfoProvider.getPreachingMasterInfo()
            ),
            preachingMasters = preachingMasters,
            onMasterClick = { index -> showPreachingMasterSelection = index },
            onMasterRemove = { index -> productionViewModel.removeDirectDisciple("preaching", index) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        PeakDiscipleListSection(
            sectionTitle = "外门弟子",
            emptyText = "暂无外门弟子",
            disciples = outerDisciples
        )
            }
        }
    }

    if (showOuterElderSelection) {
        PeakDiscipleSelectionDialog(
            title = "选择外门长老",
            disciples = productionViewModel.getAvailableDisciplesForOuterElder(),
            currentDiscipleId = outerElder?.id,
            requirementText = "需要: 内门弟子 · 空闲中",
            onSelect = { disciple ->
                productionViewModel.assignElder(ElderSlotType.OUTER_ELDER, disciple.id)
                showOuterElderSelection = false
            },
            onDismiss = { showOuterElderSelection = false }
        )
    }

    if (showPreachingElderSelection) {
        PeakDiscipleSelectionDialog(
            title = "选择问道塔传道长老",
            disciples = productionViewModel.getAvailableDisciplesForPreachingElder(),
            currentDiscipleId = preachingElder?.id,
            requirementText = "需要: 内门弟子 · 空闲中",
            onSelect = { disciple ->
                productionViewModel.assignElder(ElderSlotType.PREACHING, disciple.id)
                showPreachingElderSelection = false
            },
            onDismiss = { showPreachingElderSelection = false }
        )
    }

    showPreachingMasterSelection?.let { slotIndex ->
        val currentMaster = preachingMasters.find { it.index == slotIndex }
        PeakDiscipleSelectionDialog(
            title = "选择问道塔传道师",
            disciples = productionViewModel.getAvailableDisciplesForPreachingMaster(),
            currentDiscipleId = currentMaster?.discipleId,
            requirementText = "需要: 内门弟子 · 空闲中",
            onSelect = { disciple ->
                productionViewModel.assignDirectDisciple("preaching", slotIndex, disciple.id)
                showPreachingMasterSelection = null
            },
            onDismiss = { showPreachingMasterSelection = null }
        )
    }
}
