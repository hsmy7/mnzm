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
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.SpiritMineSlot
import com.xianxia.sect.ui.theme.GameColors

@Composable
fun SpiritMineDialog(
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val disciples by viewModel.disciples.collectAsState()
    val gameData by viewModel.gameData.collectAsState()
    
    var showDiscipleSelection by remember { mutableStateOf<Int?>(null) }
    var showDeaconSelection by remember { mutableStateOf<Int?>(null) }
    
    LaunchedEffect(Unit) {
        viewModel.validateSpiritMineData()
    }
    
    val mineSlots = gameData?.spiritMineSlots ?: emptyList()
    val slots = (0 until 6).map { index ->
        mineSlots.find { it.index == index } ?: SpiritMineSlot(index = index)
    }
    
    // 获取执事列表
    val deaconSlots = gameData?.elderSlots?.spiritMineDeaconDisciples ?: emptyList()
    val deaconDisciples = (0 until 2).map { index ->
        deaconSlots.find { it.index == index } ?: DirectDiscipleSlot(index = index)
    }
    
    // 计算执事道德加成
    val deaconBonus = deaconDisciples.mapNotNull { slot ->
        slot.discipleId?.let { id -> disciples.find { it.id == id } }
    }.sumOf { disciple ->
        // 以道德50为基准，每高5点增加2%产量
        val moralityDiff = disciple.morality - 50
        (moralityDiff / 5.0) * 0.02
    }
    
    val baseOutput = slots.map { slot ->
        if (slot.discipleId == null) {
            0L
        } else {
            val disciple = disciples.find { it.id == slot.discipleId }
            val baseOutput = when (disciple?.realm) {
                0 -> 250000L  // 仙人
                1 -> 90000L   // 渡劫
                2 -> 35000L   // 大乘
                3 -> 13000L   // 合体
                4 -> 5000L    // 炼虚
                5 -> 2000L    // 化神
                6 -> 800L     // 元婴
                7 -> 300L     // 金丹
                8 -> 120L     // 筑基
                9 -> 50L      // 炼气
                else -> 50L
            }
            baseOutput
        }
    }.sum()
    
    // 应用执事加成
    val totalOutput = (baseOutput * (1 + deaconBonus)).toLong()

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
            // 执事板块
            SpiritMineDeaconSection(
                deaconSlots = deaconDisciples,
                disciples = disciples,
                onDeaconClick = { index -> showDeaconSelection = index },
                onDeaconRemove = { index -> viewModel.removeSpiritMineDeacon(index) }
            )
            
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = GameColors.Border,
                thickness = 1.dp
            )
            
            // 矿工槽位
            Text(
                text = "矿工槽位",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF666666)
            )
            
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
                            onAssign = { showDiscipleSelection = slot.index },
                            onRemove = { viewModel.removeDiscipleFromSpiritMineSlot(slot.index) }
                        )
                    }
                }
            }
        }
    }

    showDiscipleSelection?.let { slotIndex ->
        val currentDiscipleId = slots.getOrNull(slotIndex)?.discipleId
        DiscipleSelectionDialog(
            disciples = disciples.filter { disciple -> 
                disciple.isAlive && 
                disciple.realmLayer > 0 &&
                disciple.status == DiscipleStatus.IDLE
            },
            currentDiscipleId = currentDiscipleId,
            onSelect = { disciple ->
                viewModel.assignDiscipleToSpiritMineSlot(slotIndex, disciple.id, disciple.name)
                showDiscipleSelection = null
            },
            onDismiss = { showDiscipleSelection = null }
        )
    }
    
    showDeaconSelection?.let { slotIndex ->
        val currentDeaconId = deaconDisciples.getOrNull(slotIndex)?.discipleId
        SpiritMineDeaconSelectionDialog(
            disciples = viewModel.getAvailableDisciplesForSpiritMineDeacon(),
            currentDeaconId = currentDeaconId,
            onSelect = { disciple ->
                viewModel.assignSpiritMineDeacon(slotIndex, disciple.id)
                showDeaconSelection = null
            },
            onDismiss = { showDeaconSelection = null }
        )
    }
}

@Composable
private fun SpiritMineDeaconSection(
    deaconSlots: List<DirectDiscipleSlot>,
    disciples: List<Disciple>,
    onDeaconClick: (Int) -> Unit,
    onDeaconRemove: (Int) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "灵矿执事",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF666666)
        )
        
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
    disciple: Disciple?,
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
                    Text(
                        text = disciple.name,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        maxLines = 1
                    )
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
    disciple: Disciple?,
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
                .clickable { if (slot.discipleId == null) onAssign() else onAssign() },
            contentAlignment = Alignment.Center
        ) {
            if (slot.discipleId != null && disciple != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = disciple.name,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
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
private fun SpiritMineDeaconSelectionDialog(
    disciples: List<Disciple>,
    currentDeaconId: String?,
    onSelect: (Disciple) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedRealmFilter by remember { mutableStateOf<Int?>(null) }

    val realmFilters = listOf(
        0 to "仙人",
        1 to "渡劫",
        2 to "大乘",
        3 to "合体",
        4 to "炼虚",
        5 to "化神",
        6 to "元婴",
        7 to "金丹",
        8 to "筑基",
        9 to "炼气"
    )

    val realmCounts = remember(disciples) {
        disciples.groupingBy { it.realm }.eachCount()
    }

    val sortedDisciples = remember(disciples) {
        disciples.sortedWith(
            compareBy<Disciple> { it.realm }
                .thenByDescending { it.realmLayer }
        )
    }

    val filteredDisciples = remember(sortedDisciples, selectedRealmFilter) {
        if (selectedRealmFilter == null) {
            sortedDisciples
        } else {
            sortedDisciples.filter { it.realm == selectedRealmFilter }
        }
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
                                val isSelected = selectedRealmFilter == realm
                                val count = realmCounts[realm] ?: 0
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (isSelected) GameColors.Border else GameColors.PageBackground)
                                        .border(1.dp, GameColors.Border, RoundedCornerShape(4.dp))
                                        .clickable { selectedRealmFilter = if (isSelected) null else realm }
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$name $count",
                                        fontSize = 9.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = Color.Black
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            realmFilters.drop(5).forEach { (realm, name) ->
                                val isSelected = selectedRealmFilter == realm
                                val count = realmCounts[realm] ?: 0
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (isSelected) GameColors.Border else GameColors.PageBackground)
                                        .border(1.dp, GameColors.Border, RoundedCornerShape(4.dp))
                                        .clickable { selectedRealmFilter = if (isSelected) null else realm }
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$name $count",
                                        fontSize = 9.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = Color.Black
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
                                        Text(
                                            text = disciple.name,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.Black
                                        )
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
private fun DiscipleSelectionDialog(
    disciples: List<Disciple>,
    currentDiscipleId: String?,
    onSelect: (Disciple) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedRealmFilter by remember { mutableStateOf<Int?>(null) }

    val realmFilters = listOf(
        0 to "仙人",
        1 to "渡劫",
        2 to "大乘",
        3 to "合体",
        4 to "炼虚",
        5 to "化神",
        6 to "元婴",
        7 to "金丹",
        8 to "筑基",
        9 to "炼气"
    )

    val realmCounts = remember(disciples) {
        disciples.filter { it.realmLayer > 0 && it.age >= 5 }.groupingBy { it.realm }.eachCount()
    }

    val sortedDisciples = remember(disciples) {
        disciples.filter { it.realmLayer > 0 && it.age >= 5 }.sortedWith(
            compareBy<Disciple> { it.realm }
                .thenByDescending { it.realmLayer }
        )
    }

    val filteredDisciples = remember(sortedDisciples, selectedRealmFilter) {
        if (selectedRealmFilter == null) {
            sortedDisciples
        } else {
            sortedDisciples.filter { it.realm == selectedRealmFilter }
        }
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
                    text = "选择弟子",
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
                        text = "暂无可用弟子",
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
                                val isSelected = selectedRealmFilter == realm
                                val count = realmCounts[realm] ?: 0
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (isSelected) GameColors.Border else GameColors.PageBackground)
                                        .border(1.dp, GameColors.Border, RoundedCornerShape(4.dp))
                                        .clickable { selectedRealmFilter = if (isSelected) null else realm }
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$name $count",
                                        fontSize = 9.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = Color.Black
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            realmFilters.drop(5).forEach { (realm, name) ->
                                val isSelected = selectedRealmFilter == realm
                                val count = realmCounts[realm] ?: 0
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (isSelected) GameColors.Border else GameColors.PageBackground)
                                        .border(1.dp, GameColors.Border, RoundedCornerShape(4.dp))
                                        .clickable { selectedRealmFilter = if (isSelected) null else realm }
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$name $count",
                                        fontSize = 9.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = Color.Black
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
                            val isCurrent = disciple.id == currentDiscipleId
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
                                    Text(
                                        text = disciple.name,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black
                                    )
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
