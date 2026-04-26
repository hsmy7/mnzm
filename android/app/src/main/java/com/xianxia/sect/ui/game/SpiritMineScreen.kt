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
import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.SpiritMineSlot
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.components.ElderBonusInfoButton
import com.xianxia.sect.ui.components.ElderBonusInfoProvider
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.FollowedTag
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
    
    LaunchedEffect(Unit) {
        spiritMineViewModel.validateSpiritMineData()
    }
    
    val mineSlots = gameData?.spiritMineSlots ?: emptyList()
    val slots = (0 until 12).map { index ->
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
        val moralityDiff = disciple.morality - 50
        (moralityDiff / 5.0) * 0.02
    }
    
    val baseOutput = slots.map { slot ->
        if (slot.discipleId.isEmpty()) {
            0L
        } else {
            60L
        }
    }.sum()

    val boostEffect = if (gameData?.sectPolicies?.spiritMineBoost == true) 1.2 else 1.0
    val totalOutput = (baseOutput * (1 + deaconBonus) * boostEffect).toLong()

    CommonDialog(
        title = "灵矿场",
        totalOutput = totalOutput,
        deaconBonus = deaconBonus,
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
                Text(
                    text = "矿工槽位 ($emptySlotCount/12 空闲)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF666666)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (emptySlotCount > 0) Color(0xFF4CAF50) else Color(0xFFCCCCCC))
                        .clickable(enabled = emptySlotCount > 0) {
                            spiritMineViewModel.autoAssignSpiritMineMiners()
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "一键任命",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
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
        SpiritMineDiscipleSelectionDialog(
            disciples = availableDisciples,
            maxSelectCount = emptySlotCount,
            onConfirm = { selectedDisciples ->
                spiritMineViewModel.assignDisciplesToSpiritMineSlots(selectedDisciples)
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
                        if (disciple.isFollowed) { FollowedTag() }
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
                        if (disciple.isFollowed) { FollowedTag() }
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

    val realmFilters = listOf(
        0 to "仙人", 1 to "渡劫", 2 to "大乘", 3 to "合体",
        4 to "炼虚", 5 to "化神", 6 to "元婴", 7 to "金丹", 8 to "筑基", 9 to "炼气"
    )

    val realmCounts = remember(disciples) {
        disciples.groupingBy { it.realm }.eachCount()
    }

    val sortedDisciples = remember(disciples) {
        disciples.sortedWith(
            compareBy<DiscipleAggregate> { it.realm }.thenByDescending { it.realmLayer }
        )
    }

    val filteredDisciples = remember(sortedDisciples, selectedRealmFilter) {
        if (selectedRealmFilter.isEmpty()) sortedDisciples
        else sortedDisciples.filter { it.realm in selectedRealmFilter }
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
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        realmFilters.take(5).forEach { (realm, name) ->
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        realmFilters.drop(5).forEach { (realm, name) ->
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
                    items(filteredDisciples) { disciple ->
                        val isSelected = disciple.id in selectedIds
                        val canSelectMore = selectedIds.size < maxSelectCount
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(GameColors.PageBackground)
                                .border(
                                    2.dp,
                                    if (isSelected) Color(0xFFFF9800) else Color(0xFFCCCCCC),
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable {
                                    selectedIds = if (isSelected) {
                                        selectedIds - disciple.id
                                    } else if (canSelectMore) {
                                        selectedIds + disciple.id
                                    } else {
                                        selectedIds
                                    }
                                }
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = disciple.name,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.Black
                                        )
                                        if (disciple.isFollowed) { FollowedTag() }
                                    }
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val spiritRootColor = try {
                                            Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
                                        } catch (e: Exception) {
                                            Color(0xFF666666)
                                        }
                                        Text(
                                            text = disciple.spiritRootName,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = spiritRootColor
                                        )
                                        Text(
                                            text = disciple.realmName,
                                            fontSize = 11.sp,
                                            color = Color(0xFF666666)
                                        )
                                    }
                                }
                                if (isSelected) {
                                    Text(
                                        text = "✓",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFF9800)
                                    )
                                }
                            }
                        }
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

    val realmFilters = listOf(
        0 to "仙人", 1 to "渡劫", 2 to "大乘", 3 to "合体",
        4 to "炼虚", 5 to "化神", 6 to "元婴", 7 to "金丹", 8 to "筑基", 9 to "炼气"
    )

    val realmCounts = remember(disciples) {
        disciples.groupingBy { it.realm }.eachCount()
    }

    val sortedDisciples = remember(disciples) {
        disciples.sortedWith(
            compareBy<DiscipleAggregate> { it.realm }.thenByDescending { it.realmLayer }
        )
    }

    val filteredDisciples = remember(sortedDisciples, selectedRealmFilter) {
        if (selectedRealmFilter.isEmpty()) sortedDisciples
        else sortedDisciples.filter { it.realm in selectedRealmFilter }
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
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            realmFilters.take(5).forEach { (realm, name) ->
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            realmFilters.drop(5).forEach { (realm, name) ->
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

                    Spacer(modifier = Modifier.height(12.dp))

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredDisciples) { disciple ->
                            val isCurrent = disciple.id == currentDeaconId
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isCurrent) GameColors.Border else GameColors.PageBackground)
                                    .border(1.dp, GameColors.Border, RoundedCornerShape(6.dp))
                                    .clickable { onSelect(disciple) }
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = disciple.name,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.Black
                                            )
                                            if (disciple.isFollowed) { FollowedTag() }
                                        }
                                        Text(
                                            text = "道德: ${disciple.morality}",
                                            fontSize = 10.sp,
                                            color = Color(0xFF4CAF50)
                                        )
                                    }
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val spiritRootColor = try {
                                            Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
                                        } catch (e: Exception) {
                                            Color(0xFF666666)
                                        }
                                        Text(
                                            text = disciple.spiritRootName,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = spiritRootColor
                                        )
                                        if (isCurrent) {
                                            Text(
                                                text = "当前",
                                                fontSize = 12.sp,
                                                color = Color(0xFF4CAF50)
                                            )
                                        } else {
                                            Text(
                                                text = disciple.realmName,
                                                fontSize = 12.sp,
                                                color = Color(0xFF666666)
                                            )
                                        }
                                    }
                                }
                            }
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
