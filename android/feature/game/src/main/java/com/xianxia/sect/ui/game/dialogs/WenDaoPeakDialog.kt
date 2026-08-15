package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.*
import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.ElderSlotType
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.ui.components.ElderBonusInfoProvider
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
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
fun WenDaoPeakDialog(
    disciples: List<DiscipleAggregate>,
    gameData: GameData?,
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    onDismiss: () -> Unit,  // ← NEW: use instead of viewModel.closeCurrentDialog()
) {
    var showOuterElderSelection by remember { mutableStateOf(false) }
    var showPreachingElderSelection by remember { mutableStateOf(false) }
    var showPreachingMasterSelection by remember { mutableStateOf<Int?>(null) }
    val outerElder = productionViewModel.getOuterElder()
    val preachingElder = productionViewModel.getPreachingElder()
    val preachingMasters = productionViewModel.getPreachingMasters()
    val discipleMap = disciples.associateBy { it.id }

    WenDaoPeakDialogContent(
        onDismiss = onDismiss,
        contentData = WenDaoPeakContentData(
            outerElder = outerElder,
            preachingElder = preachingElder,
            preachingMasters = preachingMasters,
            disciples = disciples,
            discipleMap = discipleMap
        ),
        viewModel = viewModel,
        productionViewModel = productionViewModel,
        onShowOuterElderSelection = { showOuterElderSelection = true },
        onShowPreachingElderSelection = { showPreachingElderSelection = true },
        onShowPreachingMasterSelection = { showPreachingMasterSelection = it }
    )

    if (showOuterElderSelection) {
        OuterElderSelectionDialog(
            outerElder = outerElder,
            viewModel = viewModel,
            productionViewModel = productionViewModel,
            onDismiss = { showOuterElderSelection = false }
        )
    }

    if (showPreachingElderSelection) {
        PreachingElderSelectionDialog(
            preachingElder = preachingElder,
            viewModel = viewModel,
            productionViewModel = productionViewModel,
            onDismiss = { showPreachingElderSelection = false }
        )
    }

    showPreachingMasterSelection?.let { slotIndex ->
        PreachingMasterSelectionDialog(
            slotIndex = slotIndex,
            preachingMasters = preachingMasters,
            viewModel = viewModel,
            productionViewModel = productionViewModel,
            onDismiss = { showPreachingMasterSelection = null }
        )
    }
}

/** 问道塔对话框内容数据（WenDaoPeakDialog 拆分） */
private data class WenDaoPeakContentData(
    val outerElder: DiscipleAggregate?,
    val preachingElder: DiscipleAggregate?,
    val preachingMasters: List<DirectDiscipleSlot>,
    val disciples: List<DiscipleAggregate>,
    val discipleMap: Map<String, DiscipleAggregate>
)

/** 问道塔主内容（WenDaoPeakDialog 拆分）：外门长老 + 传道长老 + 传道师 */
@Composable
private fun WenDaoPeakDialogContent(
    onDismiss: () -> Unit,
    contentData: WenDaoPeakContentData,
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    onShowOuterElderSelection: () -> Unit,
    onShowPreachingElderSelection: () -> Unit,
    onShowPreachingMasterSelection: (Int) -> Unit
) {
    val outerElder = contentData.outerElder
    val preachingElder = contentData.preachingElder
    val disciples = contentData.disciples
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
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
                    title = "外门长老", elder = outerElder,
                    bonusInfo = ElderBonusInfoProvider.getOuterElderInfo(),
                    onClick = {
                        outerElder?.let { viewModel.showDiscipleDetail(DiscipleDetailRequest(it, disciples)) }
                    },
                    onRemove = { productionViewModel.removeElder(ElderSlotType.OUTER_ELDER) },
                    onSwap = { onShowOuterElderSelection() }
                ),
                slot2 = PeakElderSlotConfig(
                    title = "问道塔传道长老", elder = preachingElder,
                    bonusInfo = ElderBonusInfoProvider.getWenDaoPreachingElderInfo(),
                    onClick = {
                        preachingElder?.let { viewModel.showDiscipleDetail(DiscipleDetailRequest(it, disciples)) }
                    },
                    onRemove = { productionViewModel.removeElder(ElderSlotType.PREACHING) },
                    onSwap = { onShowPreachingElderSelection() }
                )
            )
            Spacer(modifier = Modifier.height(16.dp))
            PeakPreachingMasterSection(
                sectionTitle = "问道塔传道师",
                masterConfig = PeakPreachingMasterConfig(
                    label = "问道塔传道师", bonusInfo = ElderBonusInfoProvider.getPreachingMasterInfo()
                ),
                preachingMasters = contentData.preachingMasters,
                disciples = disciples,
                onMasterClick = { index ->
                    val master = contentData.preachingMasters.find { it.index == index }
                    val d = if (master?.isActive == true) contentData.discipleMap[master.discipleId] else null
                    d?.let { viewModel.showDiscipleDetail(DiscipleDetailRequest(it, disciples)) }
                },
                onMasterRemove = { index -> productionViewModel.removeDirectDisciple("preaching", index) },
                onMasterSwap = { index -> onShowPreachingMasterSelection(index) }
            )
        }
    }
}

/** 外门长老选择弹窗（WenDaoPeakDialog 拆分） */
@Composable
private fun OuterElderSelectionDialog(
    outerElder: DiscipleAggregate?,
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    onDismiss: () -> Unit
) {
    PeakDiscipleSelectionDialog(
        title = "选择外门长老",
        disciples = productionViewModel.getAvailableDisciplesForOuterElder(),
        currentDiscipleId = outerElder?.id,
        requirementText = "需要: 内门弟子 · 空闲中",
        onSelect = { disciple ->
            productionViewModel.assignElder(ElderSlotType.OUTER_ELDER, disciple.id)
            onDismiss()
        },
        onDismiss = onDismiss,
        defaultSortAttribute = "comprehension",
        viewModel = viewModel
    )
}

/** 问道塔传道长老选择弹窗（WenDaoPeakDialog 拆分） */
@Composable
private fun PreachingElderSelectionDialog(
    preachingElder: DiscipleAggregate?,
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    onDismiss: () -> Unit
) {
    PeakDiscipleSelectionDialog(
        title = "选择问道塔传道长老",
        disciples = productionViewModel.getAvailableDisciplesForPreachingElder(),
        currentDiscipleId = preachingElder?.id,
        requirementText = "需要: 内门弟子 · 空闲中",
        onSelect = { disciple ->
            productionViewModel.assignElder(ElderSlotType.PREACHING, disciple.id)
            onDismiss()
        },
        onDismiss = onDismiss,
        defaultSortAttribute = "teaching",
        viewModel = viewModel
    )
}

/** 问道塔传道师选择弹窗（WenDaoPeakDialog 拆分） */
@Composable
private fun PreachingMasterSelectionDialog(
    slotIndex: Int,
    preachingMasters: List<DirectDiscipleSlot>,
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    onDismiss: () -> Unit
) {
    val currentMaster = preachingMasters.find { it.index == slotIndex }
    PeakDiscipleSelectionDialog(
        title = "选择问道塔传道师",
        disciples = productionViewModel.getAvailableDisciplesForPreachingMaster(),
        currentDiscipleId = currentMaster?.discipleId,
        requirementText = "需要: 内门弟子 · 空闲中",
        onSelect = { disciple ->
            productionViewModel.assignDirectDisciple("preaching", slotIndex, disciple.id)
            onDismiss()
        },
        onDismiss = onDismiss,
        defaultSortAttribute = "teaching",
        viewModel = viewModel
    )
}
