package com.xianxia.sect.ui.game

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xianxia.sect.core.model.*
import com.xianxia.sect.ui.components.ElderBonusInfoProvider

@Composable
fun QingyunPeakDialog(
    disciples: List<DiscipleAggregate>,
    gameData: GameData?,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    var showInnerElderSelection by remember { mutableStateOf(false) }
    var showPreachingElderSelection by remember { mutableStateOf(false) }
    var showPreachingMasterSelection by remember { mutableStateOf<Int?>(null) }

    val innerElder = viewModel.getInnerElder()
    val preachingElder = viewModel.getQingyunPreachingElder()
    val preachingMasters = viewModel.getQingyunPreachingMasters()
    val innerDisciples = viewModel.getInnerDisciples()

    PeakDialog(
        title = "青云峰",
        subtitle = "管理内门弟子与传道修行",
        onDismiss = onDismiss
    ) {
        PeakElderSection(
            slot1 = PeakElderSlotConfig(
                title = "内门长老",
                elder = innerElder,
                bonusInfo = ElderBonusInfoProvider.getInnerElderInfo(),
                onClick = { showInnerElderSelection = true },
                onRemove = { viewModel.removeElder("innerElder") }
            ),
            slot2 = PeakElderSlotConfig(
                title = "青云峰传道长老",
                elder = preachingElder,
                bonusInfo = ElderBonusInfoProvider.getPreachingElderInfo(),
                onClick = { showPreachingElderSelection = true },
                onRemove = { viewModel.removeElder("qingyunPreachingElder") }
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        PeakPreachingMasterSection(
            sectionTitle = "青云峰传道师",
            masterConfig = PeakPreachingMasterConfig(
                label = "青云峰传道师",
                bonusInfo = ElderBonusInfoProvider.getQingyunPreachingMasterInfo()
            ),
            preachingMasters = preachingMasters,
            onMasterClick = { index -> showPreachingMasterSelection = index },
            onMasterRemove = { index -> viewModel.removeDirectDisciple("qingyunPreaching", index) }
        )

        Spacer(modifier = Modifier.height(12.dp))

        PeakDiscipleListSection(
            sectionTitle = "内门弟子",
            emptyText = "暂无内门弟子",
            disciples = innerDisciples,
            maxHeightDp = 150.dp,
            truncateAt = null
        )
    }

    if (showInnerElderSelection) {
        PeakDiscipleSelectionDialog(
            title = "选择内门长老",
            disciples = viewModel.getAvailableDisciplesForInnerElder(),
            currentDiscipleId = innerElder?.id,
            requirementText = "需要元婴及以上境界",
            onSelect = { disciple ->
                viewModel.assignElder("innerElder", disciple.id)
                showInnerElderSelection = false
            },
            onDismiss = { showInnerElderSelection = false }
        )
    }

    if (showPreachingElderSelection) {
        PeakDiscipleSelectionDialog(
            title = "选择青云峰传道长老",
            disciples = viewModel.getAvailableDisciplesForQingyunPreachingElder(),
            currentDiscipleId = preachingElder?.id,
            requirementText = "需要元婴及以上境界",
            onSelect = { disciple ->
                viewModel.assignElder("qingyunPreachingElder", disciple.id)
                showPreachingElderSelection = false
            },
            onDismiss = { showPreachingElderSelection = false }
        )
    }

    showPreachingMasterSelection?.let { slotIndex ->
        val currentMaster = preachingMasters.find { it.index == slotIndex }
        PeakDiscipleSelectionDialog(
            title = "选择青云峰传道师",
            disciples = viewModel.getAvailableDisciplesForQingyunPreachingMaster(),
            currentDiscipleId = currentMaster?.discipleId,
            requirementText = "需要金丹及以上境界",
            onSelect = { disciple ->
                viewModel.assignDirectDisciple("qingyunPreaching", slotIndex, disciple.id)
                showPreachingMasterSelection = null
            },
            onDismiss = { showPreachingMasterSelection = null }
        )
    }
}
