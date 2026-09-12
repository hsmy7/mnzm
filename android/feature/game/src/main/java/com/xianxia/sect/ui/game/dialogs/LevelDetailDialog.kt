@file:Suppress("TooManyFunctions") // 私有辅助函数集中在本文件
package com.xianxia.sect.ui.game.dialogs

import com.xianxia.sect.core.util.GameUtils
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.engine.SectCombatPowerCalculator
import com.xianxia.sect.ui.components.SpriteImage
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.LevelType
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
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
import com.xianxia.sect.ui.game.map.MapItem
import kotlinx.coroutines.launch
import com.xianxia.sect.ui.theme.ButtonSizes
import com.xianxia.sect.ui.game.delegate.releaseDiscipleForReassignment

private val beastNames =
    listOf("tiger", "wolf", "snake", "bear", "eagle", "fox", "dragon", "turtle")

@Composable
fun LevelDetailDialog(
    level: MapItem.Level,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    onAttack: (List<String?>) -> Unit,
    onDismiss: () -> Unit
) {
    val slots = remember { mutableStateListOf<String?>().apply { repeat(8) { add(null) } } }
    var targetSlotIndex by remember { mutableIntStateOf(-1) }
    var showDiscipleSelection by remember { mutableStateOf(false) }
    // 低血量二次确认（会话级状态：点"我知道了"后仅当前界面不再弹，关闭重开重新检查）
    var lowHpAcknowledged by remember { mutableStateOf(false) }
    var showLowHpWarning by remember { mutableStateOf(false) }

    val gameData by viewModel.gameData.collectAsStateWithLifecycle()
    val equipmentInstances by viewModel.equipmentInstances.collectAsStateWithLifecycle()
    val manualInstances by viewModel.manualInstances.collectAsStateWithLifecycle()
    val discipleMap = disciples.associateBy { it.id }
    val equipmentMap = remember(equipmentInstances) { equipmentInstances.associateBy { it.id } }
    val manualMap = remember(manualInstances) { manualInstances.associateBy { it.id } }
    val dialogTitle = if (level.levelType == LevelType.CAVE && level.caveName.isNotEmpty()) level.caveName else level
        .name

    UnifiedGameDialog(onDismissRequest = onDismiss, title = dialogTitle, mode = DialogMode.Half,
        scrollableContent = false) {
        LevelDialogContent(
            level = level,
            slots = slots,
            discipleMap = discipleMap,
            slotCallbacks = LevelSlotCallbacks(
                onDiscipleClick = { d -> viewModel.overlays.showDiscipleDetail(DiscipleDetailRequest(d, disciples)) },
                onEmptySlotClick = { slotIndex -> targetSlotIndex = slotIndex; showDiscipleSelection = true },
                onSlotDismiss = { slotIndex -> slots[slotIndex] = null },
                onSlotSwap = { slotIndex -> targetSlotIndex = slotIndex; showDiscipleSelection = true },
                onOneClickAppoint = { oneClickAppoint(slots, disciples) }
            ),
            onAttack = {
                if (shouldWarnLowHp(slots, discipleMap, equipmentMap, manualMap, gameData, lowHpAcknowledged)) {
                    showLowHpWarning = true
                } else {
                    onAttack(slots.toList())
                }
            },
            onDismiss = onDismiss
        )
    }

    if (showLowHpWarning) {
        LevelLowHpWarningDialog(
            onConfirm = { showLowHpWarning = false; lowHpAcknowledged = true; onAttack(slots.toList()) },
            onDismiss = { showLowHpWarning = false }
        )
    }

    if (showDiscipleSelection && targetSlotIndex >= 0) {
        LevelSlotSelectionDialog(
            disciples = disciples,
            alreadySelectedIds = slots.filterNotNull().toSet(),
            viewModel = viewModel,
            onSelect = { id -> slots[targetSlotIndex] = id; showDiscipleSelection = false; targetSlotIndex = -1 },
            onDismiss = { showDiscipleSelection = false; targetSlotIndex = -1 }
        )
    }
}

/** 槽位交互回调 */
private data class LevelSlotCallbacks(
    val onDiscipleClick: (DiscipleAggregate) -> Unit,
    val onEmptySlotClick: (Int) -> Unit,
    val onSlotDismiss: (Int) -> Unit,
    val onSlotSwap: (Int) -> Unit,
    val onOneClickAppoint: () -> Unit
)

/** 对话框内容列：顶部信息 + 槽位网格 + 按钮 */
@Composable
private fun LevelDialogContent(
    level: MapItem.Level,
    slots: SnapshotStateList<String?>,
    discipleMap: Map<String, DiscipleAggregate>,
    slotCallbacks: LevelSlotCallbacks,
    onAttack: () -> Unit,
    onDismiss: () -> Unit
) {
    val occupiedCount by remember { derivedStateOf { slots.count { it != null } } }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ========== Top section: image + info ==========
        LevelInfoHeader(level = level)

        Spacer(modifier = Modifier.height(16.dp))

        // ========== Middle section: 2x4 slots ==========
        LevelSlotsGrid(
            slots = slots,
            discipleMap = discipleMap,
            callbacks = slotCallbacks
        )

        Spacer(modifier = Modifier.height(12.dp))

        // One-click appoint button
        GameButton(
            text = "一键任命",
            onClick = slotCallbacks.onOneClickAppoint,
            width = ButtonSizes.StandardWidth,
            height = ButtonSizes.StandardHeight,
            fontSize = 11.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ========== Bottom section: action buttons ==========
        LevelActionButtons(
            occupiedCount = occupiedCount,
            onAttack = onAttack,
            onDismiss = onDismiss
        )
    }
}

/** 顶部图片+信息区 */
@Composable
private fun LevelInfoHeader(level: MapItem.Level) {
    val spriteName = remember(level) {
        when (level.levelType) {
            LevelType.BEAST -> beastNames.getOrElse(level.beastType ?: 0) { "turtle" }
            LevelType.CAVE -> "cave_" + ((level.caveImageIndex).coerceIn(0, 2) + 1)
        }
    }
    val realmDisplayName = remember(level) {
        GameConfig.Realm.getName(level.realm)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SpriteImage(
            name = spriteName,
            contentDescription = null,
            modifier = Modifier.size(120.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        LevelInfoText(
            level = level,
            realmDisplayName = realmDisplayName
        )
    }
}

/** 信息文字列：名称/战力 + 境界 + 数量 */
@Composable
private fun LevelInfoText(level: MapItem.Level, realmDisplayName: String) {
    Column {
        LevelTitleRow(level = level)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = realmDisplayName,
            fontSize = 14.sp,
            color = Color.Black
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "数量：${GameUtils.formatNumber(level.count)}",
            fontSize = 12.sp,
            color = Color.Black
        )
    }
}

/** 名称/战力行：洞府名/守护兽名 + 妖兽总战力 */
@Composable
private fun LevelTitleRow(level: MapItem.Level) {
    if (level.levelType == LevelType.CAVE && level.caveName.isNotEmpty()) {
        Text(
            text = level.caveName,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "守护兽：${level.name}",
            fontSize = 13.sp,
            color = Color.Black
        )
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = level.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            if (level.levelType == LevelType.BEAST && level.beastMaxHp > 0) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "总战力",
                    fontSize = 13.sp,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = GameUtils.formatNumber(
                        SectCombatPowerCalculator.calculateBeastCombatPower(
                            maxHp = level.beastMaxHp,
                            physicalAttack = level.beastPhysicalAttack,
                            magicAttack = level.beastMagicAttack,
                            physicalDefense = level.beastPhysicalDefense,
                            magicDefense = level.beastMagicDefense,
                            speed = level.beastSpeed
                        )
                    ),
                    fontSize = 13.sp,
                    color = Color.Black
                )
            }
        }
    }
}

/** 中部 2x4 弟子槽位网格 */
@Composable
private fun LevelSlotsGrid(
    slots: SnapshotStateList<String?>,
    discipleMap: Map<String, DiscipleAggregate>,
    callbacks: LevelSlotCallbacks
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (row in 0 until 2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
            ) {
                for (col in 0 until 4) {
                    val slotIndex = row * 4 + col
                    val discipleId = slots[slotIndex]
                    val disciple =
                        if (discipleId != null) discipleMap[discipleId]
                        else null

                    LevelSlotBox(
                        disciple = disciple,
                        onSlotClick = {
                            if (disciple != null) callbacks.onDiscipleClick(disciple)
                            else callbacks.onEmptySlotClick(slotIndex)
                        },
                        onDismiss = { callbacks.onSlotDismiss(slotIndex) },
                        onSwap = { callbacks.onSlotSwap(slotIndex) }
                    )
                }
            }
        }
    }
}

/** 底部操作按钮 */
@Composable
private fun LevelActionButtons(
    occupiedCount: Int,
    onAttack: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        GameButton(
            text = "进攻",
            onClick = onAttack,
            enabled = occupiedCount > 0,
            width = ButtonSizes.StandardWidth,
            height = ButtonSizes.StandardHeight,
            fontSize = 11.sp
        )
        GameButton(
            text = "关闭",
            onClick = onDismiss,
            width = ButtonSizes.StandardWidth,
            height = ButtonSizes.StandardHeight,
            fontSize = 11.sp
        )
    }
}

/** 低血量二次确认弹窗 */
@Composable
private fun LevelLowHpWarningDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    StandardPromptDialog(
        onDismissRequest = onDismiss,
        title = "弟子血量未满",
        text = "队伍中有弟子血量未满，是否仍要发起进攻？",
        confirmLabel = "我知道了",
        onConfirm = onConfirm,
        dismissLabel = null
    )
}

/**
 * 进攻前低血量检查：队伍中存在血量未满弟子且未确认过时返回 true。
 * 原 onAttack 内联判定逻辑原样搬移。
 */
private fun shouldWarnLowHp(
    slots: SnapshotStateList<String?>,
    discipleMap: Map<String, DiscipleAggregate>,
    equipmentMap: Map<String, EquipmentInstance>,
    manualMap: Map<String, ManualInstance>,
    gameData: GameData?,
    lowHpAcknowledged: Boolean
): Boolean {
    if (lowHpAcknowledged) return false
    val team = slots.mapNotNull { it?.let { id -> discipleMap[id] } }
    return hasLowHpDisciple(
        team, equipmentMap, manualMap,
        gameData?.manualProficiencies ?: emptyMap(),
        gameData?.bloodRefinementPctTotals ?: emptyMap()
    )
}

/**
 * 一键任命：空槽位填入最高境界空闲弟子。
 * 原 oneClickAppoint 内联逻辑原样搬移。
 */
private fun oneClickAppoint(
    slots: SnapshotStateList<String?>,
    disciples: List<DiscipleAggregate>
) {
    val idleDisciples = disciples.filter {
        it.isAlive && it.status == DiscipleStatus.IDLE && it.realmLayer > 0 && it.age >= 5
    }.sortedWith(
        compareBy<DiscipleAggregate> { it.realm }
            .thenByDescending { it.realmLayer }
    )

    val assignedIds = slots.filterNotNull().toSet()
    val available = idleDisciples.filter { it.id !in assignedIds }

    var idx = 0
    for (i in 0 until 8) {
        if (slots[i] == null && idx < available.size) {
            slots[i] = available[idx].id
            idx++
        }
    }
}

// ==================== Slot Box Component ====================

@Composable
private fun LevelSlotBox(
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

// ==================== Disciple Selection Dialog ====================

/** 筛选状态 */
private data class LevelFilterState(
    val selectedSpiritRootFilter: Set<Int>,
    val selectedAttributeSort: String?,
    val selectedRealmFilter: Set<Int>,
    val spiritRootExpanded: Boolean,
    val attributeExpanded: Boolean,
    val realmExpanded: Boolean
)

/** 筛选计数数据 */
private data class LevelFilterCounts(
    val realmCounts: Map<Int, Int>,
    val realmFilterOptions: List<Pair<Int, String>>,
    val spiritRootCounts: Map<Int, Int>,
    val showAllEnabled: Boolean
)

/** 筛选回调 */
private data class LevelFilterCallbacks(
    val onSpiritRootFilterSelected: (Int) -> Unit,
    val onSpiritRootFilterRemoved: (Int) -> Unit,
    val onAttributeSortSelected: (String?) -> Unit,
    val onRealmFilterSelected: (Int) -> Unit,
    val onRealmFilterRemoved: (Int) -> Unit
)

/** 展开开关回调 */
private data class LevelFilterToggleCallbacks(
    val onSpiritRootExpandToggle: () -> Unit,
    val onAttributeExpandToggle: () -> Unit,
    val onRealmExpandToggle: () -> Unit,
    val onShowAllToggle: () -> Unit
)

/** 选择数据源 */
private data class LevelSelectionSource(
    val disciples: List<DiscipleAggregate>,
    val alreadySelectedIds: Set<String>,
    val viewModel: GameViewModel
)

@Composable
private fun LevelSlotSelectionDialog(
    disciples: List<DiscipleAggregate>,
    alreadySelectedIds: Set<String> = emptySet(),
    viewModel: GameViewModel,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedRealmFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedSpiritRootFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedAttributeSort by remember { mutableStateOf<String?>(null) }
    var spiritRootExpanded by remember { mutableStateOf(false) }
    var attributeExpanded by remember { mutableStateOf(false) }
    var realmExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val stateGameData by viewModel.gameData.collectAsState()
    val showAllEnabled = stateGameData.showAllAvailableDisciples

    LevelSelectionDialogUi(
        state = LevelFilterState(
            selectedSpiritRootFilter = selectedSpiritRootFilter,
            selectedAttributeSort = selectedAttributeSort,
            selectedRealmFilter = selectedRealmFilter,
            spiritRootExpanded = spiritRootExpanded,
            attributeExpanded = attributeExpanded,
            realmExpanded = realmExpanded
        ),
        source = LevelSelectionSource(
            disciples = disciples,
            alreadySelectedIds = alreadySelectedIds,
            viewModel = viewModel
        ),
        onDismiss = onDismiss,
        filterCallbacks = LevelFilterCallbacks(
            onSpiritRootFilterSelected = { selectedSpiritRootFilter = selectedSpiritRootFilter + it },
            onSpiritRootFilterRemoved = { selectedSpiritRootFilter = selectedSpiritRootFilter - it },
            onAttributeSortSelected = { selectedAttributeSort = it },
            onRealmFilterSelected = { selectedRealmFilter = selectedRealmFilter + it },
            onRealmFilterRemoved = { selectedRealmFilter = selectedRealmFilter - it }
        ),
        toggleCallbacks = LevelFilterToggleCallbacks(
            onSpiritRootExpandToggle = { spiritRootExpanded = !spiritRootExpanded },
            onAttributeExpandToggle = { attributeExpanded = !attributeExpanded },
            onRealmExpandToggle = { realmExpanded = !realmExpanded },
            onShowAllToggle = { viewModel.settings.setShowAllAvailableDisciples(!showAllEnabled) }
        ),
        onDiscipleClick = { disciple ->
            scope.launch {
                if (showAllEnabled && disciple.status != DiscipleStatus.IDLE) {
                    viewModel.disciple.releaseDiscipleForReassignment(disciple.id)
                }
                onSelect(disciple.id)
            }
        }
    )
}

/** 选择对话框主体：筛选栏 + 弟子网格 */
@Composable
private fun LevelSelectionDialogUi(
    state: LevelFilterState,
    source: LevelSelectionSource,
    onDismiss: () -> Unit,
    filterCallbacks: LevelFilterCallbacks,
    toggleCallbacks: LevelFilterToggleCallbacks,
    onDiscipleClick: (DiscipleAggregate) -> Unit
) {
    val stateGameData by source.viewModel.gameData.collectAsState()
    val showAllEnabled = stateGameData.showAllAvailableDisciples
    val battleAndExplorationIds = remember(stateGameData) {
        val battleIds = stateGameData.battleTeams.flatMap { it.slots.map { it.discipleId } }.filter { it.isNotEmpty() }
            .toSet()
        val explorationIds = stateGameData.caveExplorationTeams.flatMap { it.memberIds }.filter { it.isNotEmpty() }
            .toSet()
        battleIds + explorationIds
    }
    val idleDisciples = remember(source.disciples, source.alreadySelectedIds, showAllEnabled, battleAndExplorationIds) {
        source.disciples.filterByDiscipleStatus(showAllEnabled, battleAndExplorationIds, additionalCheck = { d ->
            d.realmLayer > 0 && d.age >= 5 && d.id !in source.alreadySelectedIds
        })
    }
    val realmCounts = remember(idleDisciples) {
        idleDisciples.groupingBy { it.realm }.eachCount()
    }
    val spiritRootCounts = remember(idleDisciples) {
        idleDisciples.groupingBy { it.getSpiritRootCount() }.eachCount()
    }
    val filteredDisciples = remember(
        idleDisciples,
        state.selectedRealmFilter,
        state.selectedSpiritRootFilter,
        state.selectedAttributeSort
    ) {
        idleDisciples.applyFilters(
            state.selectedRealmFilter,
            state.selectedSpiritRootFilter,
            state.selectedAttributeSort
        )
    }

    UnifiedGameDialog(
        onDismissRequest = onDismiss, title = "选择弟子",
        mode = DialogMode.Half, scrollableContent = false,
        headerContent = {
            LevelFilterBar(
                state = state,
                counts = LevelFilterCounts(
                    realmCounts = realmCounts,
                    realmFilterOptions = REALM_FILTER_OPTIONS,
                    spiritRootCounts = spiritRootCounts,
                    showAllEnabled = showAllEnabled
                ),
                callbacks = filterCallbacks,
                toggleCallbacks = toggleCallbacks
            )
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            LevelSelectionGrid(
                filteredDisciples = filteredDisciples,
                idleDisciples = idleDisciples,
                onDiscipleClick = onDiscipleClick
            )
        }
    }
}

/** 弟子筛选栏 */
@Composable
private fun LevelFilterBar(
    state: LevelFilterState,
    counts: LevelFilterCounts,
    callbacks: LevelFilterCallbacks,
    toggleCallbacks: LevelFilterToggleCallbacks
) {
    SpiritRootAttributeFilterBar(
        selectedSpiritRootFilter = state.selectedSpiritRootFilter,
        selectedAttributeSort = state.selectedAttributeSort,
        selectedRealmFilter = state.selectedRealmFilter,
        realmFilterOptions = counts.realmFilterOptions,
        realmCounts = counts.realmCounts,
        spiritRootExpanded = state.spiritRootExpanded,
        attributeExpanded = state.attributeExpanded,
        realmExpanded = state.realmExpanded,
        spiritRootCounts = counts.spiritRootCounts,
        onSpiritRootFilterSelected = callbacks.onSpiritRootFilterSelected,
        onSpiritRootFilterRemoved = callbacks.onSpiritRootFilterRemoved,
        onAttributeSortSelected = callbacks.onAttributeSortSelected,
        onRealmFilterSelected = callbacks.onRealmFilterSelected,
        onRealmFilterRemoved = callbacks.onRealmFilterRemoved,
        onSpiritRootExpandToggle = toggleCallbacks.onSpiritRootExpandToggle,
        onAttributeExpandToggle = toggleCallbacks.onAttributeExpandToggle,
        onRealmExpandToggle = toggleCallbacks.onRealmExpandToggle,
        isCompact = true,
        showAllCheckboxVisible = true,
        showAllEnabled = counts.showAllEnabled,
        onShowAllToggle = toggleCallbacks.onShowAllToggle
    )
}

/** 弟子选择网格 */
@Composable
private fun ColumnScope.LevelSelectionGrid(
    filteredDisciples: List<DiscipleAggregate>,
    idleDisciples: List<DiscipleAggregate>,
    onDiscipleClick: (DiscipleAggregate) -> Unit
) {
    if (idleDisciples.isEmpty()) {
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
        if (filteredDisciples.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无符合条件的弟子",
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
                        isSelected = false,
                        onClick = { onDiscipleClick(disciple) }
                    )
                }
            }
        }
    }
}
