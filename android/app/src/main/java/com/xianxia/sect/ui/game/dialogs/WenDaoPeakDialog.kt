package com.xianxia.sect.ui.game.dialogs

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
import com.xianxia.sect.ui.components.ElderBonusInfoProvider
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import androidx.compose.ui.window.DialogProperties
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.ProductionViewModel
import com.xianxia.sect.ui.game.PeakElderSection
import com.xianxia.sect.ui.game.PeakElderSlotConfig
import com.xianxia.sect.ui.game.PeakPreachingMasterSection
import com.xianxia.sect.ui.game.PeakPreachingMasterConfig
import com.xianxia.sect.ui.game.PeakDiscipleListSection
import com.xianxia.sect.ui.game.PeakDiscipleSelectionDialog
import com.xianxia.sect.ui.game.DiscipleDetailDialog

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
    var selectedDiscipleDetail by remember { mutableStateOf<DiscipleAggregate?>(null) }

    val outerElder = productionViewModel.getOuterElder()
    val preachingElder = productionViewModel.getPreachingElder()
    val preachingMasters = productionViewModel.getPreachingMasters()
    val outerDisciples = disciples.filter { it.isAlive && it.discipleType == "outer" }

    UnifiedGameDialog(
        onDismissRequest = { viewModel.closeCurrentDialog() },
        title = "问道塔",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)
        ) {
            Text(
                text = "管理外门弟子与传道修行",
                fontSize = 11.sp,
                color = GameColors.TextSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))
        PeakElderSection(
            slot1 = PeakElderSlotConfig(
                title = "外门长老",
                elder = outerElder,
                bonusInfo = ElderBonusInfoProvider.getOuterElderInfo(),
                onClick = { selectedDiscipleDetail = outerElder },
                onRemove = { productionViewModel.removeElder(ElderSlotType.OUTER_ELDER) },
                onSwap = { showOuterElderSelection = true }
            ),
            slot2 = PeakElderSlotConfig(
                title = "问道塔传道长老",
                elder = preachingElder,
                bonusInfo = ElderBonusInfoProvider.getWenDaoPreachingElderInfo(),
                onClick = { selectedDiscipleDetail = preachingElder },
                onRemove = { productionViewModel.removeElder(ElderSlotType.PREACHING) },
                onSwap = { showPreachingElderSelection = true }
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
            disciples = disciples,
            onMasterClick = { index ->
                val master = preachingMasters.find { it.index == index }
                val d = if (master?.isActive == true) disciples.find { it.id == master.discipleId } else null
                selectedDiscipleDetail = d
            },
            onMasterRemove = { index -> productionViewModel.removeDirectDisciple("preaching", index) },
            onMasterSwap = { index -> showPreachingMasterSelection = index }
        )

        Spacer(modifier = Modifier.height(16.dp))

        PeakDiscipleListSection(
            sectionTitle = "外门弟子",
            emptyText = "暂无外门弟子",
            disciples = outerDisciples
        )
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

    selectedDiscipleDetail?.let { disciple ->
        DiscipleDetailDialog(
            disciple = disciple,
            allDisciples = disciples,
            gameData = gameData,
            viewModel = viewModel,
            onDismiss = { selectedDiscipleDetail = null }
        )
    }
}
