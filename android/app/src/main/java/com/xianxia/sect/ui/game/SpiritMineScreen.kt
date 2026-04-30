package com.xianxia.sect.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.xianxia.sect.ui.components.FollowedTag
import com.xianxia.sect.ui.components.HorizontalDiscipleCard
import com.xianxia.sect.ui.game.components.SpiritRootAttributeFilterBar
import com.xianxia.sect.core.util.isFollowed
import com.xianxia.sect.ui.game.SpiritMineViewModel

@Composable
fun SpiritMineDialog(
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    spiritMineViewModel: SpiritMineViewModel,
    onDismiss: () -> Unit
) {
    val disciples by viewModel.discipleAggregates.collectAsState()
    val gameData by viewModel.gameData.collectAsState()
    
    var showDiscipleSelection by remember { mutableStateOf(false) }
    var showDeaconSelection by remember { mutableStateOf<Int?>(null) }
    var showExpansionDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        spiritMineViewModel.validateSpiritMineData()
    }

    val expansions = gameData?.spiritMineExpansions ?: 0
    val totalSlots = 1 + expansions
    val maxExpansions = GameConfig.Production.MAX_SPIRIT_MINE_EXPANSIONS
    val canExpand = expansions < maxExpansions

    val mineSlots = gameData?.spiritMineSlots ?: emptyList()
    val slots = (0 until totalSlots).map { index ->
        mineSlots.find { it.index == index } ?: SpiritMineSlot(index = index)
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
                onDeaconClick = { index -> showDeaconSelection = index },
                onDeaconRemove = { index -> spiritMineViewModel.removeSpiritMineDeacon(index) }
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
                        text = "矿工槽位 ($emptySlotCount/$totalSlots 空闲)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF666666)
                    )
                    ElderBonusInfoButton(bonusInfo = ElderBonusInfoProvider.getSpiritMineMinerInfo())
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 扩建按钮
                    GameButton(
                        text = if (canExpand) "扩建" else "已满",
                        onClick = { showExpansionDialog = true },
                        enabled = canExpand,
                        backgroundColor = Color(0xFF2196F3)
                    )
                    // 一键任命按钮
                    GameButton(
                        text = "一键任命",
                        onClick = { spiritMineViewModel.autoAssignSpiritMineMiners() },
                        enabled = emptySlotCount > 0,
                        backgroundColor = Color(0xFF4CAF50)
                    )
                }
            }

            slots.chunked(3).forEach { rowSlots ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                ) {
                    rowSlots.forEach { slot ->
                        val disciple = slot.discipleId?.let { id -> disciples.find { it.id == id } }
                        SpiritMineSlotItem(
                            slot = slot,
                            disciple = disciple,
                            onAssign = { if (emptySlotCount > 0) showDiscipleSelection = true },
                            onRemove = { spiritMineViewModel.removeDiscipleFromSpiritMineSlot(slot.index) }
                        )
                    }
                }
            }
        }
    }

    if (showDiscipleSelection) {
        val availableDisciples = spiritMineViewModel.getAvailableDisciplesForSpiritMining()
        val miningSelectedIds = remember { mutableStateListOf<String>() }
        FilteredMultiSelectDialog(
            title = "选择采矿弟子（外门，最多${emptySlotCount}名）",
            disciples = availableDisciples,
            selectedIds = miningSelectedIds,
            maxSelection = emptySlotCount,
            extraCardAttrName = "采矿",
            extraCardAttrValue = { it.mining },
            confirmText = "确认采矿",
            onConfirm = {
                val selected = availableDisciples.filter { it.id in miningSelectedIds }.take(emptySlotCount)
                spiritMineViewModel.assignDisciplesToSpiritMineSlots(selected)
                showDiscipleSelection = false
            },
            onDismiss = { showDiscipleSelection = false }
        )
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

    if (showExpansionDialog) {
        val cost = spiritMineViewModel.getExpansionCost()
        val canAfford = (gameData?.spiritStones ?: 0) >= cost
        SpiritMineExpansionDialog(
            expansionCost = cost,
            currentExpansions = expansions,
            canAfford = canAfford,
            onConfirm = {
                spiritMineViewModel.expandSpiritMine()
                showExpansionDialog = false
            },
            onDismiss = { showExpansionDialog = false }
        )
    }
}

@Composable
private fun SpiritMineDeaconSection(
    deaconSlots: List<DirectDiscipleSlot>,
    disciples: List<DiscipleAggregate>,
    onDeaconClick: (Int) -> Unit,
    onDeaconRemove: (Int) -> Unit
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
                color = Color(0xFF666666)
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
                    onClick = { onDeaconClick(deaconSlot.index) },
                    onRemove = { onDeaconRemove(deaconSlot.index) }
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
    onClick: () -> Unit,
    onRemove: () -> Unit
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
            color = Color(0xFF666666)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .size(65.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(GameColors.PageBackground)
                .border(
                    2.dp,
                    borderColor,
                    RoundedCornerShape(8.dp)
                )
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            if (deaconSlot.isActive && disciple != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = disciple.name,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            maxLines = 1
                        )
                    }
                    Text(
                        text = disciple.realmName,
                        fontSize = 9.sp,
                        color = Color(0xFF666666)
                    )
                    Text(
                        text = "道德: ${disciple.morality}",
                        fontSize = 9.sp,
                        color = Color(0xFF4CAF50)
                    )
                }
            } else {
                Text(
                    text = "点击任命",
                    fontSize = 10.sp,
                    color = Color(0xFF999999)
                )
            }
        }
        if (deaconSlot.isActive) {
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(GameColors.PageBackground)
                    .border(1.dp, GameColors.Border, RoundedCornerShape(6.dp))
                    .clickable { onRemove() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "卸任",
                    fontSize = 12.sp,
                    color = Color.Black
                )
            }
        }
    }
}

@Composable
private fun SpiritMineSlotItem(
    slot: SpiritMineSlot,
    disciple: DiscipleAggregate?,
    onAssign: () -> Unit,
    onRemove: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "灵矿槽 ${slot.index + 1}",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Spacer(modifier = Modifier.height(8.dp))
        val borderColor = if (disciple != null) {
            try {
                Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
            } catch (e: Exception) {
                GameColors.Border
            }
        } else {
            GameColors.Border
        }
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(GameColors.PageBackground)
                .border(
                    1.dp,
                    borderColor,
                    RoundedCornerShape(8.dp)
                )
                .clickable { if (slot.discipleId.isEmpty()) onAssign() else onRemove() },
            contentAlignment = Alignment.Center
        ) {
            if (slot.discipleId.isNotEmpty() && disciple != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = disciple.name,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }
                    Text(
                        text = disciple.realmName,
                        fontSize = 10.sp,
                        color = Color(0xFF666666)
                    )
                }
            } else {
                Text(
                    text = "+",
                    fontSize = 24.sp,
                    color = Color(0xFF999999)
                )
            }
        }
        if (slot.isActive) {
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(GameColors.PageBackground)
                    .border(1.dp, GameColors.Border, RoundedCornerShape(6.dp))
                    .clickable { onRemove() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "卸任",
                    fontSize = 12.sp,
                    color = Color.Black
                )
            }
        }
    }
}

@Composable
private fun SpiritMineDiscipleSelectionDialog(
    disciples: List<DiscipleAggregate>,
    maxSelectCount: Int,
    onConfirm: (List<DiscipleAggregate>) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedRealmFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedSpiritRootFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedAttributeSort by remember { mutableStateOf<String?>(null) }
    var spiritRootExpanded by remember { mutableStateOf(false) }
    var attributeExpanded by remember { mutableStateOf(false) }

    val realmFilters = listOf(
        0 to "仙人", 1 to "渡劫", 2 to "大乘", 3 to "合体",
        4 to "炼虚", 5 to "化神", 6 to "元婴", 7 to "金丹", 8 to "筑基", 9 to "炼气"
    )

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

    val canConfirm = selectedIds.isNotEmpty()

    CommonDialog(
        title = "选择采矿弟子（外门，最多${maxSelectCount}名）",
        onDismiss = onDismiss
    ) {
        Column {
            if (disciples.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无可用外门弟子",
                        fontSize = 12.sp,
                        color = Color(0xFF999999)
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))

                SpiritRootAttributeFilterBar(
                    selectedSpiritRootFilter = selectedSpiritRootFilter,
                    selectedAttributeSort = selectedAttributeSort,
                    spiritRootExpanded = spiritRootExpanded,
                    attributeExpanded = attributeExpanded,
                    spiritRootCounts = spiritRootCounts,
                    onSpiritRootFilterSelected = { selectedSpiritRootFilter = selectedSpiritRootFilter + it },
                    onSpiritRootFilterRemoved = { selectedSpiritRootFilter = selectedSpiritRootFilter - it },
                    onAttributeSortSelected = { selectedAttributeSort = it },
                    onSpiritRootExpandToggle = { spiritRootExpanded = !spiritRootExpanded },
                    onAttributeExpandToggle = { attributeExpanded = !attributeExpanded },
                    isCompact = true
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    realmFilters.chunked(4).forEach { chunk ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            chunk.forEach { (realm, name) ->
                                val isSelected = realm in selectedRealmFilter
                                val count = realmCounts[realm] ?: 0
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (isSelected) GameColors.Gold.copy(alpha = 0.3f) else GameColors.PageBackground)
                                        .border(1.dp, if (isSelected) GameColors.Gold else GameColors.Border, RoundedCornerShape(4.dp))
                                        .clickable { selectedRealmFilter = if (isSelected) selectedRealmFilter - realm else selectedRealmFilter + realm }
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$name $count",
                                        fontSize = 9.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) GameColors.GoldDark else Color.Black
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "已选: ${selectedIds.size}/${maxSelectCount}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF4CAF50)
                )

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredDisciples, key = { it.id }) { disciple ->
                        val isSelected = disciple.id in selectedIds
                        val canSelectMore = selectedIds.size < maxSelectCount
                        HorizontalDiscipleCard(
                            disciple = disciple,
                            isSelected = isSelected,
                            extraAttributes = listOf("采矿" to disciple.mining),
                            onClick = {
                                selectedIds = if (isSelected) {
                                    selectedIds - disciple.id
                                } else if (canSelectMore) {
                                    selectedIds + disciple.id
                                } else {
                                    selectedIds
                                }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            GameButton(
                text = "确认采矿",
                onClick = {
                    val selected = sortedDisciples.filter { it.id in selectedIds }.take(maxSelectCount)
                    onConfirm(selected)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = canConfirm
            )
        }
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

    val realmFilters = listOf(
        0 to "仙人", 1 to "渡劫", 2 to "大乘", 3 to "合体",
        4 to "炼虚", 5 to "化神", 6 to "元婴", 7 to "金丹", 8 to "筑基", 9 to "炼气"
    )

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

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "选择执事（内门弟子）",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable { onDismiss() }
                        .background(GameColors.CardBackground),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "x",
                        fontSize = 16.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
        },
        text = {
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
                        color = Color(0xFF999999)
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 500.dp)
                ) {
                    Spacer(modifier = Modifier.height(8.dp))

                    SpiritRootAttributeFilterBar(
                        selectedSpiritRootFilter = selectedSpiritRootFilter,
                        selectedAttributeSort = selectedAttributeSort,
                        spiritRootExpanded = spiritRootExpanded,
                        attributeExpanded = attributeExpanded,
                        spiritRootCounts = spiritRootCounts,
                        onSpiritRootFilterSelected = { selectedSpiritRootFilter = selectedSpiritRootFilter + it },
                        onSpiritRootFilterRemoved = { selectedSpiritRootFilter = selectedSpiritRootFilter - it },
                        onAttributeSortSelected = { selectedAttributeSort = it },
                        onSpiritRootExpandToggle = { spiritRootExpanded = !spiritRootExpanded },
                        onAttributeExpandToggle = { attributeExpanded = !attributeExpanded },
                        isCompact = true
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        realmFilters.chunked(4).forEach { chunk ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                chunk.forEach { (realm, name) ->
                                    val isSelected = realm in selectedRealmFilter
                                    val count = realmCounts[realm] ?: 0
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(if (isSelected) GameColors.Gold.copy(alpha = 0.3f) else GameColors.PageBackground)
                                            .border(1.dp, if (isSelected) GameColors.Gold else GameColors.Border, RoundedCornerShape(4.dp))
                                            .clickable { selectedRealmFilter = if (isSelected) selectedRealmFilter - realm else selectedRealmFilter + realm }
                                            .padding(vertical = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "$name $count",
                                            fontSize = 9.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) GameColors.GoldDark else Color.Black
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredDisciples, key = { it.id }) { disciple ->
                            val isCurrent = disciple.id == currentDeaconId
                            HorizontalDiscipleCard(
                                disciple = disciple,
                                isCurrent = isCurrent,
                                extraAttributes = listOf("道德" to disciple.morality),
                                onClick = { onSelect(disciple) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun CommonDialog(
    title: String,
    totalOutput: Long = 0L,
    deaconBonus: Double = 0.0,
    miningBonus: Double = 0.0,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = title,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Column {
                        Text(
                            text = "总产量: $totalOutput/月",
                            fontSize = 10.sp,
                            color = Color(0xFF4CAF50)
                        )
                        if (miningBonus > 0) {
                            Text(
                                text = "采矿加成: +${(miningBonus * 100).toInt()}%",
                                fontSize = 9.sp,
                                color = Color(0xFFFF9800)
                            )
                        }
                        if (deaconBonus > 0) {
                            Text(
                                text = "执事加成: +${(deaconBonus * 100).toInt()}%",
                                fontSize = 9.sp,
                                color = Color(0xFF2196F3)
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable { onDismiss() }
                        .background(GameColors.CardBackground),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "x",
                        fontSize = 16.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 450.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                content()
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun SpiritMineExpansionDialog(
    expansionCost: Long,
    currentExpansions: Int,
    canAfford: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val nextSlots = 1 + currentExpansions + 1
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Text(
                text = "扩建灵矿场",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "确认扩建灵矿场吗？",
                    fontSize = 12.sp,
                    color = Color.Black
                )
                Text(
                    text = "矿工槽位: ${1 + currentExpansions} → $nextSlots",
                    fontSize = 12.sp,
                    color = Color(0xFF4CAF50)
                )
                Text(
                    text = "扩建费用: $expansionCost 灵石",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (canAfford) Color(0xFF2196F3) else Color(0xFFE74C3C)
                )
                if (!canAfford) {
                    Text(
                        text = "灵石不足！",
                        fontSize = 11.sp,
                        color = Color(0xFFE74C3C)
                    )
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(GameColors.PageBackground)
                        .border(1.dp, GameColors.Border, RoundedCornerShape(6.dp))
                        .clickable { onDismiss() }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "取消",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (canAfford) Color(0xFF2196F3) else Color(0xFFCCCCCC))
                        .clickable(enabled = canAfford) { onConfirm() }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "确认扩建",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    )
}
