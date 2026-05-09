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
import com.xianxia.sect.ui.theme.GameColors

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
    val innerDisciples = disciples.filter { it.isAlive && it.discipleType == "inner" }

    HalfScreenDialog(onDismissRequest = { viewModel.closeCurrentDialog() }) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("青云塔", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    Text("管理内门弟子与传道修行", fontSize = 11.sp, color = GameColors.TextSecondary)
                }
                CloseButton(onClick = { viewModel.closeCurrentDialog() })
            }
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)
            ) {
        PeakElderSection(
            slot1 = PeakElderSlotConfig(
                title = "内门长老",
                elder = innerElder,
                bonusInfo = ElderBonusInfoProvider.getInnerElderInfo(),
                onClick = { showInnerElderSelection = true },
                onRemove = { productionViewModel.removeElder(ElderSlotType.INNER_ELDER) }
            ),
            slot2 = PeakElderSlotConfig(
                title = "青云塔传道长老",
                elder = preachingElder,
                bonusInfo = ElderBonusInfoProvider.getQingyunPreachingElderInfo(),
                onClick = { showPreachingElderSelection = true },
                onRemove = { productionViewModel.removeElder(ElderSlotType.CLOUD_PREACHING) }
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
            onMasterClick = { index -> showPreachingMasterSelection = index },
            onMasterRemove = { index -> productionViewModel.removeDirectDisciple("qingyunPreaching", index) }
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
