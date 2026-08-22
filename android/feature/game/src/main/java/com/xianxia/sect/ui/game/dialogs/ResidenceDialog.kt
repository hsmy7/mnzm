package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ResidenceSlot
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.DiscipleSlot
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorConfig
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorDialog
import com.xianxia.sect.ui.theme.GameColors



@Composable
fun ResidenceDialog(
    buildingInstanceId: String,
    viewModel: GameViewModel,
    disciples: List<DiscipleAggregate>,
    gameData: GameData,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val building = gameData.placedBuildings.find { it.instanceId == buildingInstanceId } ?: return
    val state = rememberResidenceDerivedState(building, buildingInstanceId, gameData, disciples, viewModel)

    var showDiscipleSelector by remember { mutableStateOf(false) }
    var selectedSlotIndex by remember { mutableIntStateOf(0) }
    var isSwapping by remember { mutableStateOf(false) }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "弟子住所",
        mode = DialogMode.Half,
        scrollableContent = true
    ) {
        ResidenceDialogContent(
            params = ResidenceContentParams(
                slots = state.slots,
                discipleMap = state.discipleMap,
                bonusText = state.bonusText,
                upgradeDef = state.upgradeDef,
                upgradeCost = state.upgradeCost,
                hasEnoughStones = state.hasEnoughStones,
                hasSectLevel = state.hasSectLevel
            ),
            onEmptySlotClick = { selectedSlotIndex = it; isSwapping = false; showDiscipleSelector = true },
            onMoveOut = { index ->
                scope.launch {
                    viewModel.removeFromResidence(buildingInstanceId, index)
                }
            },
            onSwap = { selectedSlotIndex = it; isSwapping = true; showDiscipleSelector = true },
            onUpgrade = { scope.launch { viewModel.upgradeResidence(buildingInstanceId) } }
        )
    }
    if (showDiscipleSelector) {
        ResidenceDiscipleSelector(
            buildingInstanceId = buildingInstanceId,
            gameData = gameData,
            disciples = disciples,
            viewModel = viewModel,
            selectedSlotIndex = selectedSlotIndex,
            isSwapping = isSwapping,
            onDismiss = { showDiscipleSelector = false; isSwapping = false }
        )
    }
}

/** 住所弹窗派生状态（ResidenceDialog 拆分——函数行数收敛；每次重组重算，行为与原内联一致）。 */
private data class ResidenceDerivedState(
    val slots: List<ResidenceSlot>,
    val discipleMap: Map<String, DiscipleAggregate>,
    val bonusText: String,
    val upgradeDef: com.xianxia.sect.core.engine.domain.building.BuildingUpgradeDef?,
    val upgradeCost: Long,
    val hasEnoughStones: Boolean,
    val hasSectLevel: Boolean
)

/** 住所弹窗派生状态计算（拆分自 ResidenceDialog——LongMethod 收敛）。 */
@Composable
private fun rememberResidenceDerivedState(
    building: com.xianxia.sect.core.model.GridBuildingData,
    buildingInstanceId: String,
    gameData: GameData,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel
): ResidenceDerivedState {
    val feature = com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
        .findByDisplayName(building.displayName)
    val isSingleResidence = feature?.isResidence == true &&
        feature.slotGroups.any {
            it is com.xianxia.sect.core.engine.domain.building.SlotGroup.Residence && it.slotsPerInstance == 1
        }
    val slotCount = if (isSingleResidence) 1 else 4

    val residenceSlots = gameData.residenceSlots.filter { it.buildingInstanceId == buildingInstanceId }
    val slots = (0 until slotCount).map { index ->
        residenceSlots.find { it.slotIndex == index }
            ?: com.xianxia.sect.core.model.ResidenceSlot(buildingInstanceId = buildingInstanceId, slotIndex = index)
    }
    val upgradeDef = com.xianxia.sect.core.engine.domain.building.BuildingUpgradeRegistry
        .findUpgrade(building.buildingId)
    val upgradeCost = upgradeDef?.let {
        com.xianxia.sect.core.engine.domain.building.BuildingUpgradeRegistry.upgradeCost(it)
    } ?: 0L
    val playerSectLevel by viewModel.playerSectLevel.collectAsStateWithLifecycle()
    return ResidenceDerivedState(
        slots = slots,
        discipleMap = disciples.associateBy { it.id },
        bonusText = feature?.residenceSpeedBonus ?: "",
        upgradeDef = upgradeDef,
        upgradeCost = upgradeCost,
        hasEnoughStones = gameData.spiritStones >= upgradeCost,
        hasSectLevel = playerSectLevel >= com.xianxia.sect.core.SectLevel.MEDIUM
    )
}

/** 住所内容区参数分组（ResidenceDialogContent LongParameterList 收敛）。 */
private data class ResidenceContentParams(
    val slots: List<ResidenceSlot>,
    val discipleMap: Map<String, DiscipleAggregate>,
    val bonusText: String,
    val upgradeDef: com.xianxia.sect.core.engine.domain.building.BuildingUpgradeDef?,
    val upgradeCost: Long,
    val hasEnoughStones: Boolean,
    val hasSectLevel: Boolean
)

/** 弟子住所主内容区（ResidenceDialog 拆分）：加成文案 + 槽位行 + 升级区 */
@Composable
private fun ResidenceDialogContent(
    params: ResidenceContentParams,
    onEmptySlotClick: (Int) -> Unit,
    onMoveOut: (Int) -> Unit,
    onSwap: (Int) -> Unit,
    onUpgrade: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Bonus text
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            val bonusParts = params.bonusText.split("+")
            if (bonusParts.size == 2) {
                Text(
                    text = bonusParts[0],
                    fontSize = 12.sp,
                    color = Color.Black
                )
                Text(
                    text = "+${bonusParts[1]}",
                    fontSize = 12.sp,
                    color = GameColors.Success,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Disciple slots
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
        ) {
            params.slots.forEach { slot ->
                val disciple = slot.discipleId.let { id ->
                    if (id.isNotEmpty()) params.discipleMap[id] else null
                }
                ResidenceSlotColumn(
                    slot = slot,
                    disciple = disciple,
                    onEmptySlotClick = { onEmptySlotClick(slot.slotIndex) },
                    onMoveOut = { onMoveOut(slot.slotIndex) },
                    onSwap = { onSwap(slot.slotIndex) }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 住所升级区（仅初级住所显示）：条件文本（满足=白 / 不满足=红）+ 升级按钮
        if (params.upgradeDef != null) {
            ResidenceUpgradeSection(
                upgradeCost = params.upgradeCost,
                hasEnoughStones = params.hasEnoughStones,
                hasSectLevel = params.hasSectLevel,
                onUpgrade = onUpgrade
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

/** 住所升级区（ResidenceDialog 拆分）：条件文本 + 升级按钮（白=满足 / 红=不满足）。 */
@Composable
private fun ResidenceUpgradeSection(
    upgradeCost: Long,
    hasEnoughStones: Boolean,
    hasSectLevel: Boolean,
    onUpgrade: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "消耗${upgradeCost}灵石",
            fontSize = 12.sp,
            color = if (hasEnoughStones) Color.White else Color(0xFFE53935)
        )
        Spacer(modifier = Modifier.height(6.dp))
        GameButton(
            text = "升级",
            enabled = hasEnoughStones && hasSectLevel,
            onClick = onUpgrade
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "需要宗门等级达到中型",
            fontSize = 12.sp,
            color = if (hasSectLevel) Color.White else Color(0xFFE53935)
        )
    }
}

/** 单个住所槽位（ResidenceDialog 拆分）：弟子槽 + 搬离/更换操作 */
// 拆分搬移:参数保留原签名语义
@Suppress("UnusedParameter")
@Composable
private fun ResidenceSlotColumn(
    slot: ResidenceSlot,
    disciple: DiscipleAggregate?,
    onEmptySlotClick: () -> Unit,
    onMoveOut: () -> Unit,
    onSwap: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        DiscipleSlot(
            disciple = disciple,
            onEmptySlotClick = onEmptySlotClick
        )
        if (disciple != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "搬离",
                    fontSize = 9.sp,
                    color = Color(0xFFE53935),
                    modifier = Modifier.clickable { onMoveOut() }
                )
                Text(
                    text = "更换",
                    fontSize = 9.sp,
                    color = Color.Black,
                    modifier = Modifier.clickable { onSwap() }
                )
            }
        }
    }
}

/** 入住/更换弟子选择弹窗（ResidenceDialog 拆分） */
@Composable
private fun ResidenceDiscipleSelector(
    buildingInstanceId: String,
    gameData: GameData,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    selectedSlotIndex: Int,
    isSwapping: Boolean,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val showAllEnabled = gameData.showAllAvailableDisciples
    val battleAndExplorationIds = remember {
        val battleIds = gameData.battleTeams.flatMap { it.slots.map { it.discipleId } }.filter { it.isNotEmpty() }.toSet()
        val explorationIds = gameData.caveExplorationTeams.flatMap { it.memberIds }.filter { it.isNotEmpty() }.toSet()
        battleIds + explorationIds
    }
    val occupiedIds = gameData.residenceSlots.mapNotNull { it.discipleId.ifEmpty { null } }.toSet()
    val eligibleDisciples = disciples.filter { it.isAlive && it.id !in occupiedIds }
    DiscipleSelectorDialog(
        config = DiscipleSelectorConfig(
            title = if (isSwapping) "更换弟子" else "选择入住弟子",
            emptyMessage = "没有可分配的弟子"
        ),
        disciples = eligibleDisciples,
        onDismiss = onDismiss,
        onConfirm = { selected ->
            if (selected.isNotEmpty()) {
                scope.launch {
                    viewModel.assignToResidence(buildingInstanceId, selectedSlotIndex, selected.first().id)
                }
            }
            onDismiss()
        },
        viewModel = viewModel,
        showAllEnabled = showAllEnabled,
        battleAndExplorationIds = battleAndExplorationIds
    )
}
