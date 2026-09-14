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
import com.xianxia.sect.ui.game.assignDirectDisciple
import com.xianxia.sect.ui.game.assignElder
import com.xianxia.sect.ui.game.getAvailableDisciplesForInnerElder
import com.xianxia.sect.ui.game.getAvailableDisciplesForQingyunPreachingElder
import com.xianxia.sect.ui.game.getAvailableDisciplesForQingyunPreachingMaster
import com.xianxia.sect.ui.game.getInnerElder
import com.xianxia.sect.ui.game.getQingyunPreachingElder
import com.xianxia.sect.ui.game.getQingyunPreachingMasters
import com.xianxia.sect.ui.game.removeDirectDisciple
import com.xianxia.sect.ui.game.removeElder

@Composable
@Suppress("UnusedParameter") // gameData: 弹窗/组件统一签名约定：保持调用点参数面一致并预留子组件扩展消费
fun QingyunPeakDialog(
    disciples: List<DiscipleAggregate>,
    gameData: GameData?,
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    onDismiss: () -> Unit,
) {
    var showInnerElderSelection by remember { mutableStateOf(false) }
    var showPreachingElderSelection by remember { mutableStateOf(false) }
    var showPreachingMasterSelection by remember { mutableStateOf<Int?>(null) }
    val state = buildQingyunPeakState(productionViewModel = productionViewModel, disciples = disciples)

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "青云塔",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        QingyunPeakContent(
            state = state,
            disciples = disciples,
            viewModel = viewModel,
            productionViewModel = productionViewModel,
            onInnerElderSwap = { showInnerElderSelection = true },
            onPreachingElderSwap = { showPreachingElderSelection = true },
            onPreachingMasterSwap = { showPreachingMasterSelection = it }
        )
    }

    if (showInnerElderSelection) {
        QingyunInnerElderSelectionDialog(
            state = state, productionViewModel = productionViewModel, viewModel = viewModel,
            onSelect = { disciple ->
                productionViewModel.assignElder(ElderSlotType.INNER_ELDER, disciple.id)
                showInnerElderSelection = false
            },
            onDismiss = { showInnerElderSelection = false }
        )
    }
    if (showPreachingElderSelection) {
        QingyunPreachingElderSelectionDialog(
            state = state, productionViewModel = productionViewModel, viewModel = viewModel,
            onSelect = { disciple ->
                productionViewModel.assignElder(ElderSlotType.CLOUD_PREACHING, disciple.id)
                showPreachingElderSelection = false
            },
            onDismiss = { showPreachingElderSelection = false }
        )
    }
    showPreachingMasterSelection?.let { slotIndex ->
        QingyunPreachingMasterSelectionDialog(
            state = state, productionViewModel = productionViewModel, viewModel = viewModel,
            slotIndex = slotIndex,
            onSelect = { disciple ->
                productionViewModel.assignDirectDisciple("qingyunPreaching", slotIndex, disciple.id)
                showPreachingMasterSelection = null
            },
            onDismiss = { showPreachingMasterSelection = null }
        )
    }
}

/** 青云塔派生状态 */
private data class QingyunPeakState(
    val innerElder: DiscipleAggregate?,
    val preachingElder: DiscipleAggregate?,
    val preachingMasters: List<DirectDiscipleSlot>,
    val discipleMap: Map<String, DiscipleAggregate>
)

/** 青云塔派生状态计算 */
private fun buildQingyunPeakState(
    productionViewModel: ProductionViewModel,
    disciples: List<DiscipleAggregate>
): QingyunPeakState = QingyunPeakState(
    innerElder = productionViewModel.getInnerElder(),
    preachingElder = productionViewModel.getQingyunPreachingElder(),
    preachingMasters = productionViewModel.getQingyunPreachingMasters(),
    discipleMap = disciples.associateBy { it.id }
)

/** 青云塔主内容区：长老槽位 + 传道师区块 */
@Composable
private fun QingyunPeakContent(
    state: QingyunPeakState,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    onInnerElderSwap: () -> Unit,
    onPreachingElderSwap: () -> Unit,
    onPreachingMasterSwap: (Int) -> Unit
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
                elder = state.innerElder,
                bonusInfo = ElderBonusInfoProvider.innerElderInfo,
                onClick = {
                    state.innerElder?.let { viewModel.overlays.showDiscipleDetail(DiscipleDetailRequest(it, disciples))
                        }
                },
                onRemove = { productionViewModel.removeElder(ElderSlotType.INNER_ELDER) },
                onSwap = onInnerElderSwap
            ),
            slot2 = PeakElderSlotConfig(
                title = "青云塔传道长老",
                elder = state.preachingElder,
                bonusInfo = ElderBonusInfoProvider.qingyunPreachingElderInfo,
                onClick = {
                    state.preachingElder?.let { viewModel.overlays.showDiscipleDetail(DiscipleDetailRequest(it,
                        disciples)) }
                },
                onRemove = { productionViewModel.removeElder(ElderSlotType.CLOUD_PREACHING) },
                onSwap = onPreachingElderSwap
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        PeakPreachingMasterSection(
            sectionTitle = "青云塔传道师",
            masterConfig = PeakPreachingMasterConfig(
                label = "青云塔传道师",
                bonusInfo = ElderBonusInfoProvider.qingyunPreachingMasterInfo
            ),
            preachingMasters = state.preachingMasters,
            disciples = disciples,
            onMasterClick = { index ->
                val master = state.preachingMasters.find { it.index == index }
                val d = if (master?.isActive == true) state.discipleMap[master.discipleId] else null
                d?.let { viewModel.overlays.showDiscipleDetail(DiscipleDetailRequest(it, disciples)) }
            },
            onMasterRemove = { index -> productionViewModel.removeDirectDisciple("qingyunPreaching", index) },
            onMasterSwap = onPreachingMasterSwap
        )
    }
}

/** 内门长老选择弹窗 */
@Composable
private fun QingyunInnerElderSelectionDialog(
    state: QingyunPeakState,
    productionViewModel: ProductionViewModel,
    viewModel: GameViewModel,
    onSelect: (DiscipleAggregate) -> Unit,
    onDismiss: () -> Unit
) {
    PeakDiscipleSelectionDialog(
        title = "选择内门长老",
        disciples = productionViewModel.getAvailableDisciplesForInnerElder(),
        currentDiscipleId = state.innerElder?.id,
        requirementText = "需要: 内门弟子 · 空闲中",
        onSelect = onSelect,
        onDismiss = onDismiss,
        defaultSortAttribute = "comprehension",
        viewModel = viewModel
    )
}

/** 青云塔传道长老选择弹窗 */
@Composable
private fun QingyunPreachingElderSelectionDialog(
    state: QingyunPeakState,
    productionViewModel: ProductionViewModel,
    viewModel: GameViewModel,
    onSelect: (DiscipleAggregate) -> Unit,
    onDismiss: () -> Unit
) {
    PeakDiscipleSelectionDialog(
        title = "选择青云塔传道长老",
        disciples = productionViewModel.getAvailableDisciplesForQingyunPreachingElder(),
        currentDiscipleId = state.preachingElder?.id,
        requirementText = "需要: 内门弟子 · 空闲中",
        onSelect = onSelect,
        onDismiss = onDismiss,
        defaultSortAttribute = "teaching",
        viewModel = viewModel
    )
}

/** 青云塔传道师选择弹窗 */
@Composable
private fun QingyunPreachingMasterSelectionDialog(
    state: QingyunPeakState,
    productionViewModel: ProductionViewModel,
    viewModel: GameViewModel,
    slotIndex: Int,
    onSelect: (DiscipleAggregate) -> Unit,
    onDismiss: () -> Unit
) {
    val currentMaster = state.preachingMasters.find { it.index == slotIndex }
    PeakDiscipleSelectionDialog(
        title = "选择青云塔传道师",
        disciples = productionViewModel.getAvailableDisciplesForQingyunPreachingMaster(),
        currentDiscipleId = currentMaster?.discipleId,
        requirementText = "需要: 内门弟子 · 空闲中",
        onSelect = onSelect,
        onDismiss = onDismiss,
        defaultSortAttribute = "teaching",
        viewModel = viewModel
    )
}
