@file:Suppress("TooManyFunctions") // 拆分聚合:提取的私有辅助函数集中在原文件,文件级复杂度为拆分代价
package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.SpiritMineSlot
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.ui.components.ElderBonusInfoButton
import com.xianxia.sect.ui.components.ElderBonusInfoProvider
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.DiscipleSlot
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.game.SpiritMineViewModel
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.ProductionViewModel
import com.xianxia.sect.ui.game.DiscipleDetailRequest
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorConfig
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorDialog

/** 灵矿场对话框 UI 状态（SpiritMineDialog 拆分） */
private class SpiritMineDialogState {
    var showDiscipleSelection by mutableStateOf(false)
    var showDeaconSelection by mutableStateOf<Int?>(null)
    var swappingSlotIndex by mutableStateOf<Int?>(null)
}

/** 灵矿场上下文（SpiritMineDialog 拆分）：建筑定位 + 槽位 */
private data class SpiritMineContext(
    val globalMines: List<GridBuildingData>,
    val mineIndex: Int,
    val mineSectId: String,
    val mineStartIndex: Int,
    val slots: List<SpiritMineSlot>
)

/** 总产出与平均采矿加成（SpiritMineDialog 拆分） */
private data class SpiritMineOutput(
    val totalOutput: Long,
    val avgMiningBonus: Double
)

@Composable
fun SpiritMineDialog(
    buildingInstanceId: String = "",
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    spiritMineViewModel: SpiritMineViewModel,
    onDismiss: () -> Unit,
    spiritMineBaseOutput: Int = GameConfig.Production.SPIRIT_MINE_BASE_OUTPUT_PER_MINER,
    spiritMineMiningThreshold: Int = GameConfig.Production.SPIRIT_MINE_MINING_THRESHOLD,
    spiritMineMiningBonusRate: Double = GameConfig.Production.SPIRIT_MINE_MINING_BONUS_RATE
) {
    val disciples by viewModel.discipleAggregates.collectAsStateWithLifecycle()
    val gameData by viewModel.gameData.collectAsStateWithLifecycle()

    val state = remember { SpiritMineDialogState() }
    LaunchedEffect(Unit) {
        spiritMineViewModel.validateSpiritMineData()
    }

    val mineContext = spiritMineContext(buildingInstanceId = buildingInstanceId, gameData = gameData)
    SpiritMineSectMismatchDiagnostics(
        buildingInstanceId = buildingInstanceId,
        mineStartIndex = mineContext.mineStartIndex,
        mineSectId = mineContext.mineSectId,
        gameData = gameData
    )
    val emptySlotCount = mineContext.slots.count { !it.isActive }
    val showAllEnabled = gameData?.showAllAvailableDisciples ?: false
    val battleAndExplorationIds = remember { battleAndExplorationIdsFrom(gameData) }
    val deaconDisciples = spiritMineDeaconSlots(gameData)
    val discipleMap = disciples.associateBy { it.id }
    val deaconBonus = spiritMineDeaconBonus(deaconDisciples = deaconDisciples, discipleMap = discipleMap)
    val output = spiritMineOutput(
        slots = mineContext.slots,
        discipleMap = discipleMap,
        deaconBonus = deaconBonus,
        gameData = gameData,
        spiritMineBaseOutput = spiritMineBaseOutput,
        spiritMineMiningThreshold = spiritMineMiningThreshold,
        spiritMineMiningBonusRate = spiritMineMiningBonusRate
    )

    CommonDialog(
        title = "灵矿场",
        totalOutput = output.totalOutput,
        deaconBonus = deaconBonus,
        miningBonus = output.avgMiningBonus,
        onDismiss = onDismiss
    ) {
        SpiritMineDialogContent(
            state = state,
            deaconDisciples = deaconDisciples,
            disciples = disciples,
            viewModel = viewModel,
            spiritMineViewModel = spiritMineViewModel,
            mineContext = mineContext,
            emptySlotCount = emptySlotCount
        )
    }

    SpiritMineSelectionDialogs(
        state = state,
        deaconDisciples = deaconDisciples,
        spiritMineViewModel = spiritMineViewModel,
        mineIndex = mineContext.mineIndex,
        viewModel = viewModel,
        showAllEnabled = showAllEnabled,
        battleAndExplorationIds = battleAndExplorationIds
    )
}

/** 矿场槽位与建筑定位（SpiritMineDialog 拆分） */
private fun spiritMineContext(
    buildingInstanceId: String,
    gameData: GameData?
): SpiritMineContext {
    val globalMines = gameData?.placedBuildings?.filter { it.displayName == "灵矿场" } ?: emptyList()
    val mineIndex = globalMines.indexOfFirst { it.instanceId == buildingInstanceId }.coerceAtLeast(0)
    val mineSectId = globalMines.getOrNull(mineIndex)?.sectId ?: ""
    val mineStartIndex = mineIndex * 3

    val mineSlots = gameData?.spiritMineSlots ?: emptyList()
    val slots = (mineStartIndex until mineStartIndex + 3).map { index ->
        val slot = mineSlots.getOrNull(index)
        if (slot != null && slot.sectId == mineSectId) slot
        else SpiritMineSlot(index = index, sectId = mineSectId)
    }
    return SpiritMineContext(
        globalMines = globalMines,
        mineIndex = mineIndex,
        mineSectId = mineSectId,
        mineStartIndex = mineStartIndex,
        slots = slots
    )
}

/** 失配槽位计数（SpiritMineDialog 拆分）：真实槽位因 sectId 失配被虚构空槽替代的数量 */
private fun countSectIdMismatchedSlots(
    mineStartIndex: Int,
    mineSectId: String,
    gameData: GameData?
): Int = (mineStartIndex until mineStartIndex + 3).count { index ->
    val real = (gameData?.spiritMineSlots ?: emptyList()).getOrNull(index)
    real != null && real.sectId != mineSectId
}

/** B3 兜底诊断（SpiritMineDialog 拆分）：只读观测对齐是否生效 */
@Composable
private fun SpiritMineSectMismatchDiagnostics(
    buildingInstanceId: String,
    mineStartIndex: Int,
    mineSectId: String,
    gameData: GameData?
) {
    // B3 兜底诊断（只读）：真实槽位因 sectId 失配被虚构空槽替代——玩家看到空闲但数据中
    // 已被占用（任命不生效）。validateAndFixSpiritMineData 已在对话框打开时对齐，此处
    // 仅观测对齐是否生效（若持续出现说明对齐时机/覆盖不足）
    LaunchedEffect(buildingInstanceId, mineSectId) {
        val hiddenMismatched = countSectIdMismatchedSlots(
            mineStartIndex = mineStartIndex,
            mineSectId = mineSectId,
            gameData = gameData
        )
        if (hiddenMismatched > 0) {
            DomainLog.w(
                "SpiritMineDialog",
                "矿场槽位 sectId 失配: buildingId=$buildingInstanceId mineSectId=\"$mineSectId\" " +
                    "隐藏槽位数=$hiddenMismatched"
            )
        }
    }
}

/** 战斗/探索占用弟子 ID（SpiritMineDialog 拆分） */
private fun battleAndExplorationIdsFrom(gameData: GameData?): Set<String> {
    val gd = gameData
    if (gd != null) {
        val battleIds = gd.battleTeams.flatMap { it.slots.map { it.discipleId } }.filter { it.isNotEmpty() }.toSet()
        val explorationIds = gd.caveExplorationTeams.flatMap { it.memberIds }.filter { it.isNotEmpty() }.toSet()
        return battleIds + explorationIds
    } else {
        return emptySet()
    }
}

/** 执事槽位列表（SpiritMineDialog 拆分）：2 槽位，缺失补空 */
private fun spiritMineDeaconSlots(gameData: GameData?): List<DirectDiscipleSlot> {
    val deaconSlots = gameData?.elderSlots?.spiritMineDeaconDisciples ?: emptyList()
    return (0 until 2).map { index ->
        deaconSlots.find { it.index == index } ?: DirectDiscipleSlot(index = index)
    }
}

/** 执事道德加成（SpiritMineDialog 拆分）：超基线道德 × 0.01 累加 */
private fun spiritMineDeaconBonus(
    deaconDisciples: List<DirectDiscipleSlot>,
    discipleMap: Map<String, DiscipleAggregate>
): Double = deaconDisciples.mapNotNull { slot ->
    slot.discipleId?.let { id -> discipleMap[id] }
}.sumOf { disciple ->
    val baseline = GameConfig.PolicyConfig.ELDER_SKILL_BASELINE
    val diff = (disciple.morality - baseline).coerceAtLeast(0)
    diff * 0.01
}

/** 总产出计算（SpiritMineDialog 拆分）：采矿加成 + 执事加成 + 政策加成 */
private fun spiritMineOutput(
    slots: List<SpiritMineSlot>,
    discipleMap: Map<String, DiscipleAggregate>,
    deaconBonus: Double,
    gameData: GameData?,
    spiritMineBaseOutput: Int,
    spiritMineMiningThreshold: Int,
    spiritMineMiningBonusRate: Double
): SpiritMineOutput {
    // 计算总产出（含采矿属性加成）
    var miningBonus = 0.0
    val baseOutput = slots.map { slot ->
        if (slot.discipleId.isEmpty()) {
            0L
        } else {
            val disciple = discipleMap[slot.discipleId]
            if (disciple != null) {
                val mining = DiscipleStatCalculator.getBaseStats(disciple).mining
                if (mining > spiritMineMiningThreshold) {
                    miningBonus += (mining - spiritMineMiningThreshold) * spiritMineMiningBonusRate
                }
            }
            spiritMineBaseOutput.toLong()
        }
    }.sum()

    val minerCount = slots.count { it.isActive }
    val avgMiningBonus = if (minerCount > 0) miningBonus / minerCount else 0.0

    val baseTotal = minerCount * spiritMineBaseOutput.toLong()
    val boostEffect = if (gameData?.sectPolicies?.spiritMineBoost == true) 1.2 else 1.0
    val totalOutput = (baseTotal * (1 + avgMiningBonus) * (1 + deaconBonus) * boostEffect).toLong()
    return SpiritMineOutput(totalOutput = totalOutput, avgMiningBonus = avgMiningBonus)
}

/** 灵矿场主内容（SpiritMineDialog 拆分）：执事区 + 矿工区 + 槽位行 */
@Composable
private fun SpiritMineDialogContent(
    state: SpiritMineDialogState,
    deaconDisciples: List<DirectDiscipleSlot>,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    spiritMineViewModel: SpiritMineViewModel,
    mineContext: SpiritMineContext,
    emptySlotCount: Int
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SpiritMineDeaconArea(
            deaconDisciples = deaconDisciples,
            disciples = disciples,
            viewModel = viewModel,
            spiritMineViewModel = spiritMineViewModel,
            onDeaconSwap = { index -> state.showDeaconSelection = index }
        )

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = GameColors.Border,
            thickness = 1.dp
        )

        SpiritMineMinerHeader(
            emptySlotCount = emptySlotCount,
            mineIndex = mineContext.mineIndex,
            spiritMineViewModel = spiritMineViewModel
        )

        SpiritMineSlotRow(
            mineContext = mineContext,
            disciples = disciples,
            viewModel = viewModel,
            spiritMineViewModel = spiritMineViewModel,
            emptySlotCount = emptySlotCount,
            onMinerAssign = { state.showDiscipleSelection = true },
            onMinerSwap = { index ->
                state.swappingSlotIndex = index
                state.showDiscipleSelection = true
            }
        )
    }
}

/** 灵矿执事区（SpiritMineDialog 拆分） */
@Composable
private fun SpiritMineDeaconArea(
    deaconDisciples: List<DirectDiscipleSlot>,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    spiritMineViewModel: SpiritMineViewModel,
    onDeaconSwap: (Int) -> Unit
) {
    val discipleMap = disciples.associateBy { it.id }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "灵矿执事",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            ElderBonusInfoButton(bonusInfo = ElderBonusInfoProvider.getSpiritMineDeaconInfo())
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
        ) {
            deaconDisciples.forEach { deaconSlot ->
                val disciple = deaconSlot.discipleId?.let { id -> discipleMap[id] }
                SpiritMineDeaconSlotItem(
                    index = deaconSlot.index,
                    deaconSlot = deaconSlot,
                    disciple = disciple,
                    onSlotClick = {
                        disciple?.let { viewModel.showDiscipleDetail(DiscipleDetailRequest(it, disciples)) }
                    },
                    onRemove = { spiritMineViewModel.removeSpiritMineDeacon(deaconSlot.index) },
                    onSwap = { onDeaconSwap(deaconSlot.index) }
                )
            }
        }
    }
}

/** 矿工标题行（SpiritMineDialog 拆分）：空闲计数 + 一键任命 */
@Composable
private fun SpiritMineMinerHeader(
    emptySlotCount: Int,
    mineIndex: Int,
    spiritMineViewModel: SpiritMineViewModel
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "灵矿场 | 矿工 ($emptySlotCount/3 空闲)",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            ElderBonusInfoButton(bonusInfo = ElderBonusInfoProvider.getSpiritMineMinerInfo())
        }
        GameButton(
            text = "一键任命",
            onClick = { spiritMineViewModel.autoAssignSpiritMineMiners(mineIndex) },
            enabled = emptySlotCount > 0
        )
    }
}

/** 矿工槽位行（SpiritMineDialog 拆分）：未建造提示或 3 槽位 */
@Composable
private fun SpiritMineSlotRow(
    mineContext: SpiritMineContext,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    spiritMineViewModel: SpiritMineViewModel,
    emptySlotCount: Int,
    onMinerAssign: () -> Unit,
    onMinerSwap: (Int) -> Unit
) {
    if (mineContext.globalMines.isEmpty()) {
        Text(
            text = "尚未建造灵矿场，请在宗门地图上建造",
            fontSize = 11.sp,
            color = Color.Black
        )
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
        ) {
            mineContext.slots.forEach { slot ->
                val disciple = slot.discipleId.let { id -> disciples.find { d -> d.id == id } }
                SpiritMineSlotItem(
                    slot = slot,
                    disciple = disciple,
                    onAssign = { if (emptySlotCount > 0) onMinerAssign() },
                    onRemove = { spiritMineViewModel.removeDiscipleFromSpiritMineSlot(slot.index) },
                    onSwap = { onMinerSwap(slot.index) },
                    onSlotClick = {
                        disciple?.let { viewModel.showDiscipleDetail(DiscipleDetailRequest(it, disciples)) }
                    }
                )
            }
        }
    }
}

/** 弟子/执事选择弹窗（SpiritMineDialog 拆分） */
@Composable
private fun SpiritMineSelectionDialogs(
    state: SpiritMineDialogState,
    deaconDisciples: List<DirectDiscipleSlot>,
    spiritMineViewModel: SpiritMineViewModel,
    mineIndex: Int,
    viewModel: GameViewModel,
    showAllEnabled: Boolean,
    battleAndExplorationIds: Set<String>
) {
    SpiritMineDiscipleSelection(
        state = state,
        spiritMineViewModel = spiritMineViewModel,
        mineIndex = mineIndex,
        viewModel = viewModel,
        showAllEnabled = showAllEnabled,
        battleAndExplorationIds = battleAndExplorationIds
    )
    SpiritMineDeaconSelection(
        state = state,
        deaconDisciples = deaconDisciples,
        spiritMineViewModel = spiritMineViewModel,
        viewModel = viewModel,
        showAllEnabled = showAllEnabled,
        battleAndExplorationIds = battleAndExplorationIds
    )
}

/** 采矿弟子选择弹窗（SpiritMineDialog 拆分）：任命 / 替换 */
@Composable
private fun SpiritMineDiscipleSelection(
    state: SpiritMineDialogState,
    spiritMineViewModel: SpiritMineViewModel,
    mineIndex: Int,
    viewModel: GameViewModel,
    showAllEnabled: Boolean,
    battleAndExplorationIds: Set<String>
) {
    if (state.showDiscipleSelection) {
        val availableDisciples = spiritMineViewModel.getAvailableDisciplesForSpiritMining(excludeAssigned = false)
        val isSwapping = state.swappingSlotIndex != null

        if (isSwapping) {
            DiscipleSelectorDialog(
                config = DiscipleSelectorConfig(title = "选择替换弟子", defaultSortAttribute = "mining"),
                disciples = availableDisciples,
                onDismiss = { state.showDiscipleSelection = false; state.swappingSlotIndex = null },
                onConfirm = { selected ->
                    if (selected.isNotEmpty()) {
                        val swapIndex = state.swappingSlotIndex
                        if (swapIndex != null) {
                            spiritMineViewModel.swapSpiritMineDisciple(
                                swapIndex, selected.first().id, mineIndex
                            )
                        }
                    }
                    state.showDiscipleSelection = false
                    state.swappingSlotIndex = null
                },
                viewModel = viewModel,
                showAllEnabled = showAllEnabled,
                battleAndExplorationIds = battleAndExplorationIds
            )
        } else {
            DiscipleSelectorDialog(
                config = DiscipleSelectorConfig(title = "选择采矿弟子", defaultSortAttribute = "mining"),
                disciples = availableDisciples,
                onDismiss = { state.showDiscipleSelection = false },
                onConfirm = { selected ->
                    spiritMineViewModel.assignDisciplesToSpiritMineSlots(selected, mineIndex)
                    state.showDiscipleSelection = false
                },
                viewModel = viewModel,
                showAllEnabled = showAllEnabled,
                battleAndExplorationIds = battleAndExplorationIds
            )
        }
    }
}

/** 执事选择弹窗（SpiritMineDialog 拆分） */
@Composable
private fun SpiritMineDeaconSelection(
    state: SpiritMineDialogState,
    deaconDisciples: List<DirectDiscipleSlot>,
    spiritMineViewModel: SpiritMineViewModel,
    viewModel: GameViewModel,
    showAllEnabled: Boolean,
    battleAndExplorationIds: Set<String>
) {
    state.showDeaconSelection?.let { slotIndex ->
        val currentDeaconId = deaconDisciples.getOrNull(slotIndex)?.discipleId
        DiscipleSelectorDialog(
            config = DiscipleSelectorConfig(
                title = "选择执事",
                defaultSortAttribute = "morality",
                currentId = currentDeaconId,
                extraAttributesProvider = { listOf("道德" to it.morality) }
            ),
            disciples = spiritMineViewModel.getAvailableDisciplesForSpiritMineDeacon(),
            onConfirm = { selected ->
                if (selected.isNotEmpty()) {
                    spiritMineViewModel.assignSpiritMineDeacon(slotIndex, selected.first().id)
                }
                state.showDeaconSelection = null
            },
            onDismiss = { state.showDeaconSelection = null },
            viewModel = viewModel,
            showAllEnabled = showAllEnabled,
            battleAndExplorationIds = battleAndExplorationIds
        )
    }
}

@Composable
private fun SpiritMineDeaconSlotItem(
    index: Int,
    deaconSlot: DirectDiscipleSlot,
    disciple: DiscipleAggregate?,
    onSlotClick: () -> Unit,
    onRemove: () -> Unit,
    onSwap: () -> Unit
) {
    val borderColor = if (deaconSlot.isActive) {
        try {
            Color(android.graphics.Color.parseColor(deaconSlot.discipleSpiritRootColor))
        } catch (e: Exception) {
            GameColors.Success
        }
    } else {
        GameColors.Border
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "执事 ${index + 1}",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Spacer(modifier = Modifier.height(4.dp))
        DiscipleSlot(
            disciple = if (deaconSlot.isActive) disciple else null,
            borderColor = borderColor,
            showActions = true,
            onSlotClick = { onSlotClick() },
            onEmptySlotClick = { onSwap() },
            onDismiss = { onRemove() },
            onSwap = { onSwap() }
        )
    }
}

@Composable
private fun SpiritMineSlotItem(
    slot: SpiritMineSlot,
    disciple: DiscipleAggregate?,
    onAssign: () -> Unit,
    onRemove: () -> Unit,
    onSwap: () -> Unit,
    onSlotClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val borderColor = if (disciple != null) {
            try {
                Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
            } catch (e: Exception) {
                GameColors.Border
            }
        } else {
            GameColors.Border
        }
        DiscipleSlot(
            disciple = if (slot.discipleId.isNotEmpty()) disciple else null,
            borderColor = borderColor,
            showActions = true,
            onSlotClick = { onSlotClick() },
            onEmptySlotClick = { onAssign() },
            onDismiss = { onRemove() },
            onSwap = { onSwap() }
        )
    }
}

@Composable
private fun CommonDialog(
    title: String,
    totalOutput: Long = 0L,
    deaconBonus: Double = 0.0,
    miningBonus: Double = 0.0,
    onDismiss: () -> Unit,
    headerContent: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = title,
        mode = DialogMode.Half,
        scrollableContent = false,
        headerContent = headerContent,
        headerActions = {
            Column {
                Text(text = "总产量: $totalOutput/月", fontSize = 10.sp, color = GameColors.Success)
                if (miningBonus > 0) {
                    Text(text = "采矿加成: +${(miningBonus * 100).toInt()}%", fontSize = 9.sp, color = GameColors.Warning)
                }
                if (deaconBonus > 0) {
                    Text(text = "执事加成: +${(deaconBonus * 100).toInt()}%", fontSize = 9.sp, color = GameColors.Info)
                }
            }
        },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)
            ) {
                content()
            }
        }
    )
}

