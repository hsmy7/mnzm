package com.xianxia.sect.ui.game

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xianxia.sect.core.model.*
import com.xianxia.sect.ui.components.ElderBonusInfoProvider

@Composable
fun WenDaoPeakDialog(
    disciples: List<DiscipleAggregate>,
    gameData: GameData?,
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    onDismiss: () -> Unit
) {
    var showOuterElderSelection by remember { mutableStateOf(false) }
    var showPreachingElderSelection by remember { mutableStateOf(false) }
    var showPreachingMasterSelection by remember { mutableStateOf<Int?>(null) }

    val outerElder = productionViewModel.getOuterElder()
    val preachingElder = productionViewModel.getPreachingElder()
    val preachingMasters = productionViewModel.getPreachingMasters()
    val outerDisciples = disciples.filter { it.isAlive && it.discipleType == "outer" }

    PeakDialog(
        title = "问道峰",
        subtitle = "管理外门弟子与传道修行",
        onDismiss = onDismiss
    ) {
        PeakElderSection(
            slot1 = PeakElderSlotConfig(
                title = "外门长老",
                elder = outerElder,
                bonusInfo = ElderBonusInfoProvider.getOuterElderInfo(),
                onClick = { showOuterElderSelection = true },
                onRemove = { productionViewModel.removeElder("outerElder") }
            ),
            slot2 = PeakElderSlotConfig(
                title = "问道峰传道长老",
                elder = preachingElder,
                bonusInfo = ElderBonusInfoProvider.getPreachingElderInfo(),
                onClick = { showPreachingElderSelection = true },
                onRemove = { productionViewModel.removeElder("preachingElder") }
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        PeakPreachingMasterSection(
            sectionTitle = "问道峰传道师",
            masterConfig = PeakPreachingMasterConfig(
                label = "问道峰传道师",
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

    if (showOuterElderSelection) {
        PeakDiscipleSelectionDialog(
            title = "选择外门长老",
            disciples = productionViewModel.getAvailableDisciplesForOuterElder(),
            currentDiscipleId = outerElder?.id,
            requirementText = "需要元婴及以上境界",
            onSelect = { disciple ->
                productionViewModel.assignElder("outerElder", disciple.id)
                showOuterElderSelection = false
            },
            onDismiss = { showOuterElderSelection = false }
        )
    }

    if (showPreachingElderSelection) {
        PeakDiscipleSelectionDialog(
            title = "选择问道峰传道长老",
            disciples = productionViewModel.getAvailableDisciplesForPreachingElder(),
            currentDiscipleId = preachingElder?.id,
            requirementText = "需要元婴及以上境界",
            onSelect = { disciple ->
                productionViewModel.assignElder("preachingElder", disciple.id)
                showPreachingElderSelection = false
            },
            onDismiss = { showPreachingElderSelection = false }
        )
    }

    showPreachingMasterSelection?.let { slotIndex ->
        val currentMaster = preachingMasters.find { it.index == slotIndex }
        PeakDiscipleSelectionDialog(
            title = "选择问道峰传道师",
            disciples = productionViewModel.getAvailableDisciplesForPreachingMaster(),
            currentDiscipleId = currentMaster?.discipleId,
            requirementText = "需要金丹及以上境界",
            onSelect = { disciple ->
                productionViewModel.assignDirectDisciple("preaching", slotIndex, disciple.id)
                showPreachingMasterSelection = null
            },
            onDismiss = { showPreachingMasterSelection = null }
        )
    }
}
