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

import com.xianxia.sect.ui.game.PeakDiscipleSelectionDialog
import com.xianxia.sect.ui.game.DiscipleDetailRequest

@Composable
fun QingyunPeakDialog(
    disciples: List<DiscipleAggregate>,
    gameData: GameData?,
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
) {
    var showInnerElderSelection by remember { mutableStateOf(false) }
    var showPreachingElderSelection by remember { mutableStateOf(false) }
    var showPreachingMasterSelection by remember { mutableStateOf<Int?>(null) }
    val innerElder = productionViewModel.getInnerElder()
    val preachingElder = productionViewModel.getQingyunPreachingElder()
    val preachingMasters = productionViewModel.getQingyunPreachingMasters()
    UnifiedGameDialog(
        onDismissRequest = { viewModel.closeCurrentDialog() },
        title = "青云塔",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)
        ) {
            Text(
                text = "管理内门弟子与传道修行",
                fontSize = 11.sp,
                color = GameColors.TextSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))
        PeakElderSection(
            slot1 = PeakElderSlotConfig(
                title = "内门长老",
                elder = innerElder,
                bonusInfo = ElderBonusInfoProvider.getInnerElderInfo(),
                onClick = { innerElder?.let { viewModel.showDiscipleDetail(DiscipleDetailRequest(it, disciples)) } },
                onRemove = { productionViewModel.removeElder(ElderSlotType.INNER_ELDER) },
                onSwap = { showInnerElderSelection = true }
            ),
            slot2 = PeakElderSlotConfig(
                title = "青云塔传道长老",
                elder = preachingElder,
                bonusInfo = ElderBonusInfoProvider.getQingyunPreachingElderInfo(),
                onClick = { preachingElder?.let { viewModel.showDiscipleDetail(DiscipleDetailRequest(it, disciples)) } },
                onRemove = { productionViewModel.removeElder(ElderSlotType.CLOUD_PREACHING) },
                onSwap = { showPreachingElderSelection = true }
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        PeakPreachingMasterSection(
            sectionTitle = "青云塔传道师",
            masterConfig = PeakPreachingMasterConfig(
                label = "青云塔传道师",
                bonusInfo = ElderBonusInfoProvider.getQingyunPreachingMasterInfo()
            ),
            preachingMasters = preachingMasters,
            disciples = disciples,
            onMasterClick = { index ->
                val master = preachingMasters.find { it.index == index }
                val d = if (master?.isActive == true) disciples.find { it.id == master.discipleId } else null
                d?.let { viewModel.showDiscipleDetail(DiscipleDetailRequest(it, disciples)) }
            },
            onMasterRemove = { index -> productionViewModel.removeDirectDisciple("qingyunPreaching", index) },
            onMasterSwap = { index -> showPreachingMasterSelection = index }
        )
            }
    }

    if (showInnerElderSelection) {
        PeakDiscipleSelectionDialog(
            title = "选择内门长老",
            disciples = productionViewModel.getAvailableDisciplesForInnerElder(),
            currentDiscipleId = innerElder?.id,
            requirementText = "需要: 内门弟子 · 空闲中",
            onSelect = { disciple ->
                productionViewModel.assignElder(ElderSlotType.INNER_ELDER, disciple.id)
                showInnerElderSelection = false
            },
            onDismiss = { showInnerElderSelection = false }
        )
    }

    if (showPreachingElderSelection) {
        PeakDiscipleSelectionDialog(
            title = "选择青云塔传道长老",
            disciples = productionViewModel.getAvailableDisciplesForQingyunPreachingElder(),
            currentDiscipleId = preachingElder?.id,
            requirementText = "需要: 内门弟子 · 空闲中",
            onSelect = { disciple ->
                productionViewModel.assignElder(ElderSlotType.CLOUD_PREACHING, disciple.id)
                showPreachingElderSelection = false
            },
            onDismiss = { showPreachingElderSelection = false }
        )
    }

    showPreachingMasterSelection?.let { slotIndex ->
        val currentMaster = preachingMasters.find { it.index == slotIndex }
        PeakDiscipleSelectionDialog(
            title = "选择青云塔传道师",
            disciples = productionViewModel.getAvailableDisciplesForQingyunPreachingMaster(),
            currentDiscipleId = currentMaster?.discipleId,
            requirementText = "需要: 内门弟子 · 空闲中",
            onSelect = { disciple ->
                productionViewModel.assignDirectDisciple("qingyunPreaching", slotIndex, disciple.id)
                showPreachingMasterSelection = null
            },
            onDismiss = { showPreachingMasterSelection = null }
        )
    }

}
