package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.DiscipleStatCalculator
import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.SpiritMineSlot
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.components.ElderBonusInfoButton
import com.xianxia.sect.ui.components.ElderBonusInfoProvider
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.theme.ButtonSizes
import com.xianxia.sect.ui.components.FollowedTag
import com.xianxia.sect.ui.components.PortraitDiscipleCard
import com.xianxia.sect.ui.components.UnifiedDiscipleSlot
import com.xianxia.sect.ui.components.DiscipleSlotWithActions
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.game.components.SpiritRootAttributeFilterBar
import com.xianxia.sect.ui.game.tabs.REALM_FILTER_OPTIONS
import com.xianxia.sect.core.util.isFollowed
import com.xianxia.sect.ui.game.SpiritMineViewModel
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.ProductionViewModel
import com.xianxia.sect.ui.game.DiscipleDetailRequest
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorConfig
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorDialog
import com.xianxia.sect.ui.game.getSpiritRootCount
import com.xianxia.sect.ui.game.applyFilters

@Composable
fun SpiritMineDialog(
    buildingInstanceId: String = "",
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    spiritMineViewModel: SpiritMineViewModel,
    onDismiss: () -> Unit
) {
    val disciples by viewModel.discipleAggregates.collectAsState()
    val gameData by viewModel.gameData.collectAsState()
    
    var showDiscipleSelection by remember { mutableStateOf(false) }
    var showDeaconSelection by remember { mutableStateOf<Int?>(null) }
    var swappingSlotIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        spiritMineViewModel.validateSpiritMineData()
    }

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

    val emptySlotCount = slots.count { !it.isActive }

    val deaconSlots = gameData?.elderSlots?.spiritMineDeaconDisciples ?: emptyList()
    val deaconDisciples = (0 until 2).map { index ->
        deaconSlots.find { it.index == index } ?: DirectDiscipleSlot(index = index)
    }

    val deaconBonus = deaconDisciples.mapNotNull { slot ->
        slot.discipleId?.let { id -> disciples.find { it.id == id } }
    }.sumOf { disciple ->
        val baseline = 80
        val diff = (disciple.morality - baseline).coerceAtLeast(0)
        diff * 0.01
    }

    // 计算总产出（含采矿属性加成）
    var miningBonus = 0.0
    val baseOutput = slots.map { slot ->
        if (slot.discipleId.isEmpty()) {
            0L
        } else {
            val disciple = disciples.find { it.id == slot.discipleId }
            if (disciple != null) {
                val mining = DiscipleStatCalculator.getBaseStats(disciple).mining
                if (mining > GameConfig.Production.SPIRIT_MINE_MINING_THRESHOLD) {
                    miningBonus += (mining - GameConfig.Production.SPIRIT_MINE_MINING_THRESHOLD) * GameConfig.Production.SPIRIT_MINE_MINING_BONUS_RATE
                }
            }
            GameConfig.Production.SPIRIT_MINE_BASE_OUTPUT_PER_MINER.toLong()
        }
    }.sum()

    val minerCount = slots.count { it.isActive }
    val avgMiningBonus = if (minerCount > 0) miningBonus / minerCount else 0.0

    val baseTotal = minerCount * GameConfig.Production.SPIRIT_MINE_BASE_OUTPUT_PER_MINER.toLong()
    val boostEffect = if (gameData?.sectPolicies?.spiritMineBoost == true) 1.2 else 1.0
    val totalOutput = (baseTotal * (1 + avgMiningBonus) * (1 + deaconBonus) * boostEffect).toLong()

    CommonDialog(
        title = "灵矿场",
        totalOutput = totalOutput,
        deaconBonus = deaconBonus,
        miningBonus = avgMiningBonus,
        onDismiss = onDismiss
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SpiritMineDeaconSection(
                deaconSlots = deaconDisciples,
                disciples = disciples,
                onDeaconClick = { it?.let { d -> viewModel.showDiscipleDetail(DiscipleDetailRequest(d, disciples)) } },
                onDeaconRemove = { index -> spiritMineViewModel.removeSpiritMineDeacon(index) },
                onDeaconSwap = { index -> showDeaconSelection = index }
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = GameColors.Border,
                thickness = 1.dp
            )

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

            if (globalMines.isEmpty()) {
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
                    slots.forEach { slot ->
                        val disciple = slot.discipleId.let { id -> disciples.find { d -> d.id == id } }
                        SpiritMineSlotItem(
                            slot = slot,
                            disciple = disciple,
                            onAssign = { if (emptySlotCount > 0) showDiscipleSelection = true },
                            onRemove = { spiritMineViewModel.removeDiscipleFromSpiritMineSlot(slot.index) },
                            onSwap = { swappingSlotIndex = slot.index; showDiscipleSelection = true },
                            onSlotClick = { disciple?.let { viewModel.showDiscipleDetail(DiscipleDetailRequest(it, disciples)) } }
                        )
                    }
                }
            }
        }
    }

    if (showDiscipleSelection) {
        val availableDisciples = spiritMineViewModel.getAvailableDisciplesForSpiritMining()
        val isSwapping = swappingSlotIndex != null

        if (isSwapping) {
            DiscipleSelectorDialog(
                config = DiscipleSelectorConfig(title = "选择替换弟子"),
                disciples = availableDisciples,
                onDismiss = { showDiscipleSelection = false; swappingSlotIndex = null },
                onConfirm = { selected ->
                    if (selected.isNotEmpty()) {
                        spiritMineViewModel.swapSpiritMineDisciple(
                            swappingSlotIndex!!, selected.first().id, mineIndex
                        )
                    }
                    showDiscipleSelection = false
                    swappingSlotIndex = null
                }
            )
        } else {
            DiscipleSelectorDialog(
                config = DiscipleSelectorConfig(title = "选择采矿弟子"),
                disciples = availableDisciples,
                onDismiss = { showDiscipleSelection = false },
                onConfirm = { selected ->
                    spiritMineViewModel.assignDisciplesToSpiritMineSlots(selected, mineIndex)
                    showDiscipleSelection = false
                }
            )
        }
    }

    showDeaconSelection?.let { slotIndex ->
        val currentDeaconId = deaconDisciples.getOrNull(slotIndex)?.discipleId
        SpiritMineDeaconSelectionDialog(
            disciples = spiritMineViewModel.getAvailableDisciplesForSpiritMineDeacon(),
            currentDeaconId = currentDeaconId,
            onSelect = { disciple ->
                spiritMineViewModel.assignSpiritMineDeacon(slotIndex, disciple.id)
                showDeaconSelection = null
            },
            onDismiss = { showDeaconSelection = null }
        )
    }


}

@Composable
private fun SpiritMineDeaconSection(
    deaconSlots: List<DirectDiscipleSlot>,
    disciples: List<DiscipleAggregate>,
    onDeaconClick: (DiscipleAggregate?) -> Unit,
    onDeaconRemove: (Int) -> Unit,
    onDeaconSwap: (Int) -> Unit = {}
) {
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
            deaconSlots.forEach { deaconSlot ->
                val disciple = deaconSlot.discipleId?.let { id -> disciples.find { it.id == id } }
                SpiritMineDeaconSlotItem(
                    index = deaconSlot.index,
                    deaconSlot = deaconSlot,
                    disciple = disciple,
                    onSlotClick = { onDeaconClick(disciple) },
                    onRemove = { onDeaconRemove(deaconSlot.index) },
                    onSwap = { onDeaconSwap(deaconSlot.index) }
                )
            }
        }
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
            Color(0xFF4CAF50)
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
        DiscipleSlotWithActions(
            disciple = if (deaconSlot.isActive) disciple else null,
            borderColor = borderColor,
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
        DiscipleSlotWithActions(
            disciple = if (slot.discipleId.isNotEmpty()) disciple else null,
            borderColor = borderColor,
            onSlotClick = { onSlotClick() },
            onEmptySlotClick = { onAssign() },
            onDismiss = { onRemove() },
            onSwap = { onSwap() }
        )
    }
}

@Composable
private fun SpiritMineDeaconSelectionDialog(
    disciples: List<DiscipleAggregate>,
    currentDeaconId: String?,
    onSelect: (DiscipleAggregate) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedRealmFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedSpiritRootFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedAttributeSort by remember { mutableStateOf<String?>(null) }
    var spiritRootExpanded by remember { mutableStateOf(false) }
    var attributeExpanded by remember { mutableStateOf(false) }
    var realmExpanded by remember { mutableStateOf(false) }

    val realmCounts = remember(disciples) {
        disciples.groupingBy { it.realm }.eachCount()
    }

    val spiritRootCounts = remember(disciples) {
        disciples.filter { it.realmLayer > 0 }.groupingBy { it.getSpiritRootCount() }.eachCount()
    }

    val sortedDisciples = remember(disciples) {
        disciples.filter { it.realmLayer > 0 }.sortedWith(
            compareBy<DiscipleAggregate> { it.realm }.thenByDescending { it.realmLayer }
        )
    }

    val filteredDisciples = remember(sortedDisciples, selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort) {
        sortedDisciples.applyFilters(selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort)
    }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "选择执事（内门弟子）",
        mode = DialogMode.Half,
        scrollableContent = false,
        headerContent = {
            SpiritRootAttributeFilterBar(
                selectedSpiritRootFilter = selectedSpiritRootFilter,
                selectedAttributeSort = selectedAttributeSort,
                selectedRealmFilter = selectedRealmFilter,
                realmFilterOptions = REALM_FILTER_OPTIONS,
                realmCounts = realmCounts,
                spiritRootExpanded = spiritRootExpanded,
                attributeExpanded = attributeExpanded,
                realmExpanded = realmExpanded,
                spiritRootCounts = spiritRootCounts,
                onSpiritRootFilterSelected = { selectedSpiritRootFilter = selectedSpiritRootFilter + it },
                onSpiritRootFilterRemoved = { selectedSpiritRootFilter = selectedSpiritRootFilter - it },
                onAttributeSortSelected = { selectedAttributeSort = it },
                onRealmFilterSelected = { selectedRealmFilter = selectedRealmFilter + it },
                onRealmFilterRemoved = { selectedRealmFilter = selectedRealmFilter - it },
                onSpiritRootExpandToggle = { spiritRootExpanded = !spiritRootExpanded },
                onAttributeExpandToggle = { attributeExpanded = !attributeExpanded },
                onRealmExpandToggle = { realmExpanded = !realmExpanded },
                isCompact = true
            )
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (disciples.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无可用内门弟子",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filteredDisciples, key = { it.id }) { disciple ->
                            val isCurrent = disciple.id == currentDeaconId
                            PortraitDiscipleCard(
                                disciple = disciple,
                                isCurrent = isCurrent,
                                extraAttributes = listOf("道德" to disciple.morality),
                                onClick = { onSelect(disciple) }
                            )
                        }
                    }
                }
            }
        }
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
                Text(text = "总产量: $totalOutput/月", fontSize = 10.sp, color = Color(0xFF4CAF50))
                if (miningBonus > 0) {
                    Text(text = "采矿加成: +${(miningBonus * 100).toInt()}%", fontSize = 9.sp, color = Color(0xFFFF9800))
                }
                if (deaconBonus > 0) {
                    Text(text = "执事加成: +${(deaconBonus * 100).toInt()}%", fontSize = 9.sp, color = Color(0xFF2196F3))
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

