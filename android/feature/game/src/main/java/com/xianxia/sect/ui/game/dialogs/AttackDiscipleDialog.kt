package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xianxia.sect.core.model.BloodRefinementPctTotal
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.util.sortedByFollowAndRealm
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.PortraitDiscipleCard
import com.xianxia.sect.ui.components.DiscipleSlot
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.game.DiscipleDetailRequest
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.hasLowHpDisciple
import com.xianxia.sect.ui.game.applyFilters
import com.xianxia.sect.ui.game.components.SpiritRootAttributeFilterBar
import com.xianxia.sect.ui.game.filterByDiscipleStatus
import com.xianxia.sect.ui.game.getSpiritRootCount
import com.xianxia.sect.ui.game.REALM_FILTER_OPTIONS
import kotlinx.coroutines.launch
import com.xianxia.sect.ui.game.delegate.releaseDiscipleForReassignment

@Composable
@Suppress("UnusedParameter") // sectName: 弹窗/组件统一签名约定：保持调用点参数面一致并预留子组件扩展消费
internal fun AttackDiscipleDialog(
    sectName: String,
    disciples: List<DiscipleAggregate>,
    gameData: GameData?,
    viewModel: GameViewModel,
    onAttack: (List<Pair<Int, DiscipleAggregate>>) -> Unit,
    onDismiss: () -> Unit
) {
    // 10 slots, each holds an optional DiscipleAggregate
    val slots = remember { mutableStateListOf<DiscipleAggregate?>().apply { repeat(10) { add(null) } } }
    val dialog = remember { AttackDialogState() }

    val equipmentInstances by viewModel.equipmentInstances.collectAsStateWithLifecycle()
    val manualInstances by viewModel.manualInstances.collectAsStateWithLifecycle()
    val equipmentMap = remember(equipmentInstances) { equipmentInstances.associateBy { it.id } }
    val manualMap = remember(manualInstances) { manualInstances.associateBy { it.id } }

    val filledCount = slots.count { it != null }

    AttackDialogContent(
        slots = slots,
        filledCount = filledCount,
        disciples = disciples,
        viewModel = viewModel,
        data = AttackDialogData(
            gameData = gameData,
            equipmentMap = equipmentMap,
            manualMap = manualMap
        ),
        dialog = dialog,
        onAttack = onAttack,
        onDismiss = onDismiss
    )
}

/** 进攻选择会话状态：4 项 mutableStateOf 会话级字段 */
private class AttackDialogState {
    var selectedSlotIndex by mutableStateOf<Int?>(null)
    var showDiscipleSelection by mutableStateOf(false)
    // 低血量二次确认（会话级状态：点"我知道了"后仅当前界面不再弹，关闭重开重新检查）
    var lowHpAcknowledged by mutableStateOf(false)
    var showLowHpWarning by mutableStateOf(false)
}

/** 进攻对话框派生数据（AttackDiscipleDialog 拆分，参数 >6 规避 LongParameterList） */
private data class AttackDialogData(
    val gameData: GameData?,
    val equipmentMap: Map<String, EquipmentInstance>,
    val manualMap: Map<String, ManualInstance>
)

/** 进攻对话框主体：槽位网格 + 底部按钮 + 低血量确认 + 弟子选择子弹窗 */
@Suppress("LongParameterList")
@Composable
private fun AttackDialogContent(
    slots: SnapshotStateList<DiscipleAggregate?>,
    filledCount: Int,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    data: AttackDialogData,
    dialog: AttackDialogState,
    onAttack: (List<Pair<Int, DiscipleAggregate>>) -> Unit,
    onDismiss: () -> Unit
) {
    val profs = data.gameData?.manualProficiencies ?: emptyMap()
    val pcts = data.gameData?.bloodRefinementPctTotals ?: emptyMap()

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "选择进攻弟子",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AttackSlotGrid(
                slots = slots,
                filledCount = filledCount,
                disciples = disciples,
                viewModel = viewModel,
                dialog = dialog
            )
            AttackActionButtons(
                filledCount = filledCount,
                onCancel = onDismiss,
                onAttack = {
                    if (shouldWarnLowHp(
                            slots.toList(), dialog.lowHpAcknowledged, data.equipmentMap, data.manualMap,
                            profs, pcts
                        )
                    ) {
                        dialog.showLowHpWarning = true
                    } else {
                        val selected = buildAttackParty(slots.toList())
                        if (selected.isNotEmpty()) {
                            onAttack(selected)
                        }
                    }
                }
            )
        }
    }

    LowHpConfirmDialog(
        show = dialog.showLowHpWarning,
        onDismiss = { dialog.showLowHpWarning = false },
        onConfirm = {
            dialog.showLowHpWarning = false
            dialog.lowHpAcknowledged = true
            val selected = buildAttackParty(slots.toList())
            if (selected.isNotEmpty()) {
                onAttack(selected)
            }
        }
    )

    AttackDiscipleSelectionSection(
        slots = slots,
        disciples = disciples,
        viewModel = viewModel,
        dialog = dialog
    )
}

/** 10 槽位网格：2 行 × 5 列 + 已选计数 */
@Composable
private fun ColumnScope.AttackSlotGrid(
    slots: SnapshotStateList<DiscipleAggregate?>,
    filledCount: Int,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    dialog: AttackDialogState
) {
    Column(
        modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 10 slots: 2 rows x 5 columns
        for (row in 0..1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
            ) {
                for (col in 0..4) {
                    val slotIndex = row * 5 + col
                    if (slotIndex < slots.size) {
                        AttackSlotBox(
                            disciple = slots[slotIndex],
                            onSlotClick = {
                                val disciple = slots[slotIndex]
                                if (disciple != null) {
                                    viewModel.overlays.showDiscipleDetail(DiscipleDetailRequest(disciple, disciples))
                                } else {
                                    dialog.selectedSlotIndex = slotIndex
                                    dialog.showDiscipleSelection = true
                                }
                            },
                            onDismiss = {
                                slots[slotIndex] = null
                            },
                            onSwap = {
                                dialog.selectedSlotIndex = slotIndex
                                dialog.showDiscipleSelection = true
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "已选择 $filledCount/10 名弟子",
            fontSize = 11.sp,
            color = Color.Black
        )
    }
}

/** 底部操作按钮：取消/进攻 */
@Composable
private fun AttackActionButtons(
    filledCount: Int,
    onCancel: () -> Unit,
    onAttack: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        GameButton(
            text = "取消",
            onClick = onCancel
        )
        GameButton(
            text = "进攻",
            onClick = onAttack,
            enabled = filledCount > 0
        )
    }
}

/** 弟子选择子弹窗入口：按当前槽位过滤已选弟子 */
@Composable
private fun AttackDiscipleSelectionSection(
    slots: SnapshotStateList<DiscipleAggregate?>,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    dialog: AttackDialogState
) {
    if (dialog.showDiscipleSelection && dialog.selectedSlotIndex != null) {
        val currentSlotIndex = dialog.selectedSlotIndex ?: return
        val alreadySelectedIds = slots.filterNotNull().map { it.id }.filter { it != slots[currentSlotIndex]?.id }
            .toSet()
        AttackDiscipleSelectionDialog(
            disciples = disciples,
            currentSlotDiscipleId = slots[currentSlotIndex]?.id,
            alreadySelectedIds = alreadySelectedIds,
            viewModel = viewModel,
            onSelect = { disciple ->
                slots[currentSlotIndex] = disciple
                dialog.showDiscipleSelection = false
                dialog.selectedSlotIndex = null
            },
            onDismiss = {
                dialog.showDiscipleSelection = false
                dialog.selectedSlotIndex = null
            }
        )
    }
}

@Composable
private fun AttackSlotBox(
    disciple: DiscipleAggregate?,
    onSlotClick: () -> Unit,
    onDismiss: () -> Unit,
    onSwap: () -> Unit
) {
    DiscipleSlot(
        disciple = disciple,
        showActions = true,
        onSlotClick = { onSlotClick() },
        onEmptySlotClick = { onSwap() },
        onDismiss = { onDismiss() },
        onSwap = { onSwap() }
    )
}

/** 进攻弟子筛选数据（AttackDiscipleSelectionDialog 拆分，参数 >6 规避 LongParameterList） */
private data class AttackFilterData(
    val selectedSpiritRootFilter: Set<Int>,
    val selectedAttributeSort: String?,
    val selectedRealmFilter: Set<Int>,
    val realmCounts: Map<Int, Int>,
    val spiritRootCounts: Map<Int, Int>,
    val showAllEnabled: Boolean
)

/** 进攻弟子筛选回调（AttackDiscipleSelectionDialog 拆分，参数 >6 规避 LongParameterList） */
private data class AttackFilterCallbacks(
    val onSpiritRootFilterSelected: (Int) -> Unit,
    val onSpiritRootFilterRemoved: (Int) -> Unit,
    val onAttributeSortSelected: (String?) -> Unit,
    val onRealmFilterSelected: (Int) -> Unit,
    val onRealmFilterRemoved: (Int) -> Unit,
    val onShowAllToggle: () -> Unit
)

@Composable
private fun AttackDiscipleSelectionDialog(
    disciples: List<DiscipleAggregate>,
    currentSlotDiscipleId: String? = null,
    alreadySelectedIds: Set<String> = emptySet(),
    viewModel: GameViewModel,
    onSelect: (DiscipleAggregate) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedRealmFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedSpiritRootFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedAttributeSort by remember { mutableStateOf<String?>(null) }
    val gameData by viewModel.gameData.collectAsState()
    val showAllEnabled = gameData.showAllAvailableDisciples
    val battleAndExplorationIds = remember(gameData) {
        val battleIds = gameData.battleTeams.flatMap { it.slots.map { it.discipleId } }.filter { it.isNotEmpty() }
            .toSet()
        val explorationIds = gameData.caveExplorationTeams.flatMap { it.memberIds }.filter { it.isNotEmpty() }.toSet()
        battleIds + explorationIds
    }
    // Only IDLE disciples, exclude already selected ones (except current slot)
    val availableDisciples = remember(disciples, alreadySelectedIds, showAllEnabled, battleAndExplorationIds) {
        disciples.filterByDiscipleStatus(showAllEnabled, battleAndExplorationIds, additionalCheck = { d ->
            d.realmLayer > 0 && (d.id == currentSlotDiscipleId || d.id !in alreadySelectedIds)
        }).sortedByFollowAndRealm()
    }
    val (realmCounts, spiritRootCounts) = remember(availableDisciples) {
        availableDisciples.groupingBy { it.realm }.eachCount() to
            availableDisciples.groupingBy { it.getSpiritRootCount() }.eachCount()
    }
    val filteredDisciples = remember(availableDisciples, selectedRealmFilter, selectedSpiritRootFilter,
        selectedAttributeSort) {
        availableDisciples.applyFilters(selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort)
    }
    UnifiedGameDialog(
        onDismissRequest = onDismiss, title = "选择进攻弟子",
        mode = DialogMode.Half, scrollableContent = false,
        headerContent = {
            AttackDiscipleFilterBar(
                data = AttackFilterData(
                    selectedSpiritRootFilter = selectedSpiritRootFilter,
                    selectedAttributeSort = selectedAttributeSort,
                    selectedRealmFilter = selectedRealmFilter,
                    realmCounts = realmCounts,
                    spiritRootCounts = spiritRootCounts,
                    showAllEnabled = showAllEnabled
                ),
                callbacks = AttackFilterCallbacks(
                    onSpiritRootFilterSelected = { selectedSpiritRootFilter = selectedSpiritRootFilter + it },
                    onSpiritRootFilterRemoved = { selectedSpiritRootFilter = selectedSpiritRootFilter - it },
                    onAttributeSortSelected = { selectedAttributeSort = it },
                    onRealmFilterSelected = { selectedRealmFilter = selectedRealmFilter + it },
                    onRealmFilterRemoved = { selectedRealmFilter = selectedRealmFilter - it },
                    onShowAllToggle = { viewModel.settings.setShowAllAvailableDisciples(!showAllEnabled) }
                )
            )
        }
    ) {
        AttackDiscipleGrid(
            filteredDisciples = filteredDisciples,
            availableDisciples = availableDisciples,
            currentSlotDiscipleId = currentSlotDiscipleId,
            showAllEnabled = showAllEnabled,
            viewModel = viewModel,
            onSelect = onSelect
        )
    }
}

/** 进攻弟子筛选栏：展开态本地持有 */
@Composable
private fun AttackDiscipleFilterBar(
    data: AttackFilterData,
    callbacks: AttackFilterCallbacks
) {
    var spiritRootExpanded by remember { mutableStateOf(false) }
    var attributeExpanded by remember { mutableStateOf(false) }
    var realmExpanded by remember { mutableStateOf(false) }
    SpiritRootAttributeFilterBar(
        selectedSpiritRootFilter = data.selectedSpiritRootFilter,
        selectedAttributeSort = data.selectedAttributeSort,
        selectedRealmFilter = data.selectedRealmFilter,
        realmFilterOptions = REALM_FILTER_OPTIONS,
        realmCounts = data.realmCounts,
        spiritRootExpanded = spiritRootExpanded,
        attributeExpanded = attributeExpanded,
        realmExpanded = realmExpanded,
        spiritRootCounts = data.spiritRootCounts,
        onSpiritRootFilterSelected = callbacks.onSpiritRootFilterSelected,
        onSpiritRootFilterRemoved = callbacks.onSpiritRootFilterRemoved,
        onAttributeSortSelected = callbacks.onAttributeSortSelected,
        onRealmFilterSelected = callbacks.onRealmFilterSelected,
        onRealmFilterRemoved = callbacks.onRealmFilterRemoved,
        onSpiritRootExpandToggle = { spiritRootExpanded = !spiritRootExpanded },
        onAttributeExpandToggle = { attributeExpanded = !attributeExpanded },
        onRealmExpandToggle = { realmExpanded = !realmExpanded },
        isCompact = true,
        showAllCheckboxVisible = true,
        showAllEnabled = data.showAllEnabled,
        onShowAllToggle = callbacks.onShowAllToggle
    )
}

/** 进攻弟子网格：空态提示 + 弟子网格 */
@Composable
private fun AttackDiscipleGrid(
    filteredDisciples: List<DiscipleAggregate>,
    availableDisciples: List<DiscipleAggregate>,
    currentSlotDiscipleId: String?,
    showAllEnabled: Boolean,
    viewModel: GameViewModel,
    onSelect: (DiscipleAggregate) -> Unit
) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            if (availableDisciples.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无空闲弟子",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
            } else {

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredDisciples, key = { it.id }, contentType = { "disciple" }) { disciple ->
                        PortraitDiscipleCard(
                            disciple = disciple,
                            isSelected = disciple.id == currentSlotDiscipleId,
                            onClick = {
                                scope.launch {
                                    if (showAllEnabled && disciple.status != DiscipleStatus.IDLE) {
                                        viewModel.disciple.releaseDiscipleForReassignment(disciple.id)
                                    }
                                    onSelect(disciple)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

// ── 低血量二次确认辅助 ──

/** 判定是否需弹低血量确认（未确认过且队伍中存在血量未满弟子） */
private fun shouldWarnLowHp(
    slots: List<DiscipleAggregate?>,
    lowHpAcknowledged: Boolean,
    equipmentMap: Map<String, EquipmentInstance>,
    manualMap: Map<String, ManualInstance>,
    manualProficiencies: Map<String, List<ManualProficiencyData>>,
    bloodRefinementPctTotals: Map<String, BloodRefinementPctTotal>
): Boolean = !lowHpAcknowledged && hasLowHpDisciple(
    slots.filterNotNull(), equipmentMap, manualMap,
    manualProficiencies, bloodRefinementPctTotals
)

/** 从 10 槽位构建出战队伍（槽位索引 → 弟子），供进攻按钮与确认弹窗共用 */
private fun buildAttackParty(
    slots: List<DiscipleAggregate?>
): List<Pair<Int, DiscipleAggregate>> =
    slots.mapIndexedNotNull { index, disciple ->
        if (disciple != null) index to disciple else null
    }

@Composable
private fun LowHpConfirmDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    if (show) {
        StandardPromptDialog(
            onDismissRequest = onDismiss,
            title = "弟子血量未满",
            text = "队伍中有弟子血量未满，是否仍要发起进攻？",
            confirmLabel = "我知道了",
            onConfirm = onConfirm,
            dismissLabel = null
        )
    }
}
