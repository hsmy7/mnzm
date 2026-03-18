package com.xianxia.sect.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.PlantSlotData
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.theme.GameColors

@Composable
fun HerbGardenDialog(
    plantSlots: List<PlantSlotData>,
    seeds: List<Seed>,
    gameData: GameData?,
    disciples: List<Disciple>,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    var showSeedSelection by remember { mutableStateOf<Int?>(null) }
    var showElderSelection by remember { mutableStateOf(false) }
    var showDirectDiscipleSelection by remember { mutableStateOf<Int?>(null) }
    var showElderRemoveConfirm by remember { mutableStateOf(false) }
    var showReserveDiscipleDialog by remember { mutableStateOf(false) }

    val elderSlots = gameData?.elderSlots ?: ElderSlots()
    val herbGardenElder = viewModel.getElderDisciple(elderSlots.herbGardenElder)
    val herbGardenDisciples = elderSlots.herbGardenDisciples
    val hasDirectDisciples = herbGardenDisciples.any { it.isActive }
    val reserveDisciplesWithInfo = viewModel.getHerbGardenReserveDisciplesWithInfo()

    CommonDialog(
        title = "灵药宛",
        onDismiss = onDismiss,
        titleActions = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF4CAF50))
                    .clickable { showReserveDiscipleDialog = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "储备弟子",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            HerbGardenElderSection(
                elder = herbGardenElder,
                onElderClick = { showElderSelection = true },
                onElderRemove = { 
                    if (hasDirectDisciples) {
                        showElderRemoveConfirm = true
                    } else {
                        viewModel.removeElder("herbGarden")
                    }
                }
            )
            
            HerbGardenDirectDiscipleSection(
                directDisciples = herbGardenDisciples,
                onDirectDiscipleClick = { index -> showDirectDiscipleSelection = index },
                onDirectDiscipleRemove = { index -> viewModel.removeDirectDisciple("herbGarden", index) }
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = GameColors.Border,
                thickness = 1.dp
            )

            Text(
                text = "种植槽",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF666666)
            )

            plantSlots.chunked(3).forEach { rowSlots ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                ) {
                    rowSlots.forEach { slot ->
                        PlantSlotItem(
                            slot = slot,
                            gameData = gameData,
                            onClick = {
                                if (slot.status == "idle") {
                                    showSeedSelection = slot.index
                                }
                            },
                            onRemove = {
                                viewModel.clearPlantSlot(slot.index)
                            }
                        )
                    }
                }
            }
        }
    }

    showSeedSelection?.let { slotIndex ->
        SeedSelectionDialog(
            seeds = seeds,
            onSelect = { seed ->
                viewModel.plantSeed(slotIndex, seed)
                showSeedSelection = null
            },
            onDismiss = { showSeedSelection = null }
        )
    }

    if (showElderSelection) {
        val currentElderId = elderSlots.herbGardenElder
        HerbGardenElderSelectionDialog(
            disciples = disciples.filter { it.isAlive && it.realm <= 6 },
            currentElderId = currentElderId,
            elderSlots = elderSlots,
            onDismiss = { showElderSelection = false },
            onSelect = { discipleId ->
                viewModel.assignElder("herbGarden", discipleId)
                showElderSelection = false
            }
        )
    }

    showDirectDiscipleSelection?.let { slotIndex ->
        HerbGardenDirectDiscipleSelectionDialog(
            disciples = disciples.filter { it.isAlive },
            elderSlots = elderSlots,
            onDismiss = { showDirectDiscipleSelection = null },
            onSelect = { discipleId ->
                viewModel.assignDirectDisciple("herbGarden", slotIndex, discipleId)
                showDirectDiscipleSelection = null
            }
        )
    }

    if (showElderRemoveConfirm) {
        ElderRemoveConfirmDialog(
            elderName = herbGardenElder?.name ?: "长老",
            discipleCount = herbGardenDisciples.count { it.isActive },
            onConfirm = {
                viewModel.removeElder("herbGarden")
                showElderRemoveConfirm = false
            },
            onDismiss = { showElderRemoveConfirm = false }
        )
    }

    if (showReserveDiscipleDialog) {
        HerbGardenReserveDiscipleDialog(
            reserveDisciples = reserveDisciplesWithInfo,
            viewModel = viewModel,
            onDismiss = { showReserveDiscipleDialog = false }
        )
    }
}

@Composable
private fun ElderRemoveConfirmDialog(
    elderName: String,
    discipleCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Text(
                text = "确认卸任",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
        },
        text = {
            Column {
                Text(
                    text = "确定要让 $elderName 卸任灵植长老吗？",
                    fontSize = 12.sp,
                    color = Color.Black
                )
                if (discipleCount > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "⚠️ 卸任后将同时移除 $discipleCount 位亲传弟子！",
                        fontSize = 11.sp,
                        color = Color(0xFFE74C3C),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        confirmButton = {
            GameButton(
                text = "确认卸任",
                onClick = onConfirm
            )
        },
        dismissButton = {
            GameButton(
                text = "取消",
                onClick = onDismiss
            )
        }
    )
}

private fun getOccupiedIds(elderSlots: ElderSlots): Pair<List<String>, List<String>> {
    val allElderIds = listOf(
        elderSlots.herbGardenElder,
        elderSlots.alchemyElder,
        elderSlots.forgeElder,
        elderSlots.libraryElder
    ).filterNotNull()

    val allDirectDiscipleIds = listOf(
        elderSlots.herbGardenDisciples,
        elderSlots.alchemyDisciples,
        elderSlots.forgeDisciples,
        elderSlots.libraryDisciples
    ).flatten().mapNotNull { it.discipleId }

    return Pair(allElderIds, allDirectDiscipleIds)
}

@Composable
private fun HerbGardenElderSection(
    elder: Disciple?,
    onElderClick: () -> Unit,
    onElderRemove: () -> Unit
) {
    val elderBorderColor = if (elder != null) {
        try {
            Color(android.graphics.Color.parseColor(elder.spiritRoot.countColor))
        } catch (e: Exception) {
            Color(0xFF4CAF50)
        }
    } else {
        GameColors.Border
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "灵植长老",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF666666)
        )
        
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(70.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(GameColors.PageBackground)
                    .border(
                        2.dp,
                        elderBorderColor,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { onElderClick() },
                contentAlignment = Alignment.Center
            ) {
                if (elder != null) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = elder.name,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            maxLines = 1
                        )
                        Text(
                            text = elder.realmName,
                            fontSize = 9.sp,
                            color = Color(0xFF666666)
                        )
                        Text(
                            text = "灵植: ${elder.spiritPlanting}",
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
            if (elder != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(GameColors.PageBackground)
                        .border(1.dp, GameColors.Border, RoundedCornerShape(6.dp))
                        .clickable { onElderRemove() }
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
}

@Composable
private fun HerbGardenDirectDiscipleSection(
    directDisciples: List<DirectDiscipleSlot>,
    onDirectDiscipleClick: (Int) -> Unit,
    onDirectDiscipleRemove: (Int) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "亲传弟子",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF666666)
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        ) {
            (0 until 3).forEach { index ->
                val disciple = directDisciples.getOrNull(index) ?: DirectDiscipleSlot(index = index)
                HerbGardenDirectDiscipleSlotItem(
                    index = index,
                    disciple = disciple,
                    onClick = { onDirectDiscipleClick(index) },
                    onRemove = { onDirectDiscipleRemove(index) }
                )
            }
        }
    }
}

@Composable
private fun HerbGardenDirectDiscipleSlotItem(
    index: Int,
    disciple: DirectDiscipleSlot,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    val borderColor = if (disciple.isActive) {
        try {
            Color(android.graphics.Color.parseColor(disciple.discipleSpiritRootColor))
        } catch (e: Exception) {
            Color(0xFF4CAF50)
        }
    } else {
        GameColors.Border
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(55.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(GameColors.PageBackground)
                .border(
                    1.dp,
                    borderColor,
                    RoundedCornerShape(6.dp)
                )
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            if (disciple.isActive) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = disciple.discipleName,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        maxLines = 1,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = disciple.discipleRealm,
                        fontSize = 8.sp,
                        color = Color(0xFF666666)
                    )
                }
            } else {
                Text(
                    text = "+",
                    fontSize = 20.sp,
                    color = Color(0xFF999999)
                )
            }
        }
        if (disciple.isActive) {
            Spacer(modifier = Modifier.height(2.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(GameColors.PageBackground)
                    .border(1.dp, GameColors.Border, RoundedCornerShape(4.dp))
                    .clickable { onRemove() }
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "卸任",
                    fontSize = 9.sp,
                    color = Color.Black
                )
            }
        }
    }
}

@Composable
private fun HerbGardenElderSelectionDialog(
    disciples: List<Disciple>,
    currentElderId: String?,
    elderSlots: ElderSlots,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
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

    val (allElderIds, allDirectDiscipleIds) = remember(elderSlots) { getOccupiedIds(elderSlots) }

    val filteredDisciplesBase = remember(disciples, allElderIds, allDirectDiscipleIds) {
        disciples.filter {
            it.realmLayer > 0 &&
            it.age >= 5 &&
            it.status == DiscipleStatus.IDLE &&
            it.discipleType == "inner" &&
            it.id !in allElderIds &&
            it.id !in allDirectDiscipleIds
        }
    }

    val realmCounts = remember(filteredDisciplesBase) {
        filteredDisciplesBase.groupingBy { it.realm }.eachCount()
    }

    val sortedDisciples = remember(filteredDisciplesBase) {
        filteredDisciplesBase.sortedWith(
            compareByDescending<Disciple> { disciple ->
                disciple.spiritPlanting
            }.thenBy { it.realm }
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
                    text = "选择灵植长老",
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
                        text = "×",
                        fontSize = 16.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                Text(
                    text = "推荐属性: 灵植",
                    fontSize = 10.sp,
                    color = Color(0xFF999999),
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        realmFilters.take(5).forEach { (realmVal, name) ->
                            val isSelected = selectedRealmFilter == realmVal
                            val count = realmCounts[realmVal] ?: 0
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) GameColors.Border else GameColors.PageBackground)
                                    .border(1.dp, GameColors.Border, RoundedCornerShape(4.dp))
                                    .clickable { selectedRealmFilter = if (isSelected) null else realmVal }
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
                        realmFilters.drop(5).forEach { (realmVal, name) ->
                            val isSelected = selectedRealmFilter == realmVal
                            val count = realmCounts[realmVal] ?: 0
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) GameColors.Border else GameColors.PageBackground)
                                    .border(1.dp, GameColors.Border, RoundedCornerShape(4.dp))
                                    .clickable { selectedRealmFilter = if (isSelected) null else realmVal }
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

                if (filteredDisciples.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无可用弟子",
                            fontSize = 12.sp,
                            color = Color(0xFF999999)
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredDisciples.size) { index ->
                            val disciple = filteredDisciples[index]
                            HerbGardenDiscipleSelectionCard(
                                disciple = disciple,
                                onClick = { onSelect(disciple.id) }
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
private fun HerbGardenReserveDiscipleDialog(
    reserveDisciples: List<Disciple>,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    var showAddDiscipleDialog by remember { mutableStateOf(false) }
    
    val sortedReserveDisciples = remember(reserveDisciples) {
        reserveDisciples.sortedByDescending { it.spiritPlanting }
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "储备弟子",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Text(
                        text = "(${sortedReserveDisciples.size})",
                        fontSize = 10.sp,
                        color = Color(0xFF999999)
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF4CAF50))
                            .clickable { showAddDiscipleDialog = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "+ 添加",
                            fontSize = 10.sp,
                            color = Color.White
                        )
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
                            text = "×",
                            fontSize = 16.sp,
                            color = Color(0xFF666666)
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "内门弟子空缺时自动补位",
                    fontSize = 9.sp,
                    color = Color(0xFF999999),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                if (sortedReserveDisciples.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无储备弟子",
                            fontSize = 12.sp,
                            color = Color(0xFF999999)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(sortedReserveDisciples, key = { it.id }) { disciple ->
                            HerbGardenReserveDiscipleCard(
                                disciple = disciple,
                                onRemove = { viewModel.removeHerbGardenReserveDisciple(disciple.id) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )

    if (showAddDiscipleDialog) {
        HerbGardenAddReserveDiscipleDialog(
            viewModel = viewModel,
            onDismiss = { showAddDiscipleDialog = false }
        )
    }
}

@Composable
private fun HerbGardenReserveDiscipleCard(
    disciple: Disciple,
    onRemove: () -> Unit
) {
    val spiritRootColor = try {
        Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
    } catch (e: Exception) {
        Color(0xFF666666)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFFF8F8F8))
            .border(1.dp, GameColors.Border, RoundedCornerShape(6.dp))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = disciple.name,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        maxLines = 1
                    )
                    Text(
                        text = disciple.spiritRootName,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = spiritRootColor,
                        maxLines = 1
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "灵植: ${disciple.spiritPlanting}",
                        fontSize = 10.sp,
                        color = Color(0xFF4CAF50)
                    )
                    Text(
                        text = disciple.realmName,
                        fontSize = 10.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
            
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(GameColors.PageBackground)
                    .border(1.dp, GameColors.Border, RoundedCornerShape(4.dp))
                    .clickable(onClick = onRemove)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "移除",
                    fontSize = 10.sp,
                    color = Color(0xFF666666)
                )
            }
        }
    }
}

@Composable
private fun HerbGardenAddReserveDiscipleDialog(
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val availableDisciples = viewModel.getAvailableDisciplesForHerbGardenReserve()
    val selectedDiscipleIds = remember { mutableStateListOf<String>() }
    
    val sortedDisciples = remember(availableDisciples) {
        availableDisciples.sortedByDescending { it.spiritPlanting }
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "内门弟子",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Text(
                        text = "(${sortedDisciples.size})",
                        fontSize = 10.sp,
                        color = Color(0xFF999999)
                    )
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
                        text = "×",
                        fontSize = 16.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "推荐属性: 灵植",
                    fontSize = 9.sp,
                    color = Color(0xFF4CAF50),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                if (sortedDisciples.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无符合条件的弟子",
                            fontSize = 12.sp,
                            color = Color(0xFF999999)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(sortedDisciples, key = { it.id }) { disciple ->
                            val isSelected = selectedDiscipleIds.contains(disciple.id)
                            HerbGardenSelectableDiscipleCard(
                                disciple = disciple,
                                isSelected = isSelected,
                                onClick = {
                                    if (isSelected) {
                                        selectedDiscipleIds.remove(disciple.id)
                                    } else {
                                        selectedDiscipleIds.add(disciple.id)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF4CAF50))
                        .clickable {
                            if (selectedDiscipleIds.isNotEmpty()) {
                                viewModel.addHerbGardenReserveDisciples(selectedDiscipleIds.toList())
                            }
                            onDismiss()
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "添加${if (selectedDiscipleIds.isNotEmpty()) "(${selectedDiscipleIds.size})" else ""}",
                        fontSize = 12.sp,
                        color = Color.White
                    )
                }
            }
        }
    )
}

@Composable
private fun HerbGardenSelectableDiscipleCard(
    disciple: Disciple,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val spiritRootColor = try {
        Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
    } catch (e: Exception) {
        Color(0xFF666666)
    }

    val borderColor = if (isSelected) Color(0xFFFFD700) else GameColors.Border
    val borderWidth = if (isSelected) 2.dp else 1.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (isSelected) Color(0xFFFFFBF0) else Color(0xFFF8F8F8))
            .border(borderWidth, borderColor, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = disciple.name,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        maxLines = 1
                    )
                    Text(
                        text = disciple.spiritRootName,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = spiritRootColor,
                        maxLines = 1
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "灵植: ${disciple.spiritPlanting}",
                        fontSize = 10.sp,
                        color = Color(0xFF4CAF50)
                    )
                    Text(
                        text = disciple.realmName,
                        fontSize = 10.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
            
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFFD700)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "✓",
                        fontSize = 12.sp,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun HerbGardenDiscipleSelectionCard(
    disciple: Disciple,
    onClick: () -> Unit
) {
    val spiritRootColor = try {
        Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
    } catch (e: Exception) {
        Color(0xFF666666)
    }

    Box(
        modifier = Modifier
            .size(60.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(GameColors.PageBackground)
            .border(1.dp, GameColors.Border, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = disciple.name,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                maxLines = 1
            )
            Text(
                text = disciple.realmName,
                fontSize = 8.sp,
                color = Color(0xFF666666),
                maxLines = 1
            )
            Text(
                text = "灵植:${disciple.spiritPlanting}",
                fontSize = 7.sp,
                color = spiritRootColor,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun HerbGardenDirectDiscipleSelectionDialog(
    disciples: List<Disciple>,
    elderSlots: ElderSlots,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
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

    val (allElderIds, allDirectDiscipleIds) = remember(elderSlots) { getOccupiedIds(elderSlots) }

    val filteredDisciplesBase = remember(disciples, allElderIds, allDirectDiscipleIds) {
        disciples.filter {
            it.realmLayer > 0 &&
            it.age >= 5 &&
            it.status == DiscipleStatus.IDLE &&
            it.discipleType == "inner" &&
            it.id !in allElderIds &&
            it.id !in allDirectDiscipleIds
        }
    }

    val realmCounts = remember(filteredDisciplesBase) {
        filteredDisciplesBase.groupingBy { it.realm }.eachCount()
    }

    val sortedDisciples = remember(filteredDisciplesBase) {
        filteredDisciplesBase.sortedWith(
            compareByDescending<Disciple> { disciple ->
                disciple.spiritPlanting
            }.thenBy { it.realm }
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
                    text = "选择亲传弟子",
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
                        text = "×",
                        fontSize = 16.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                Text(
                    text = "推荐属性: 灵植",
                    fontSize = 10.sp,
                    color = Color(0xFF999999),
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        realmFilters.take(5).forEach { (realmVal, name) ->
                            val isSelected = selectedRealmFilter == realmVal
                            val count = realmCounts[realmVal] ?: 0
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) GameColors.Border else GameColors.PageBackground)
                                    .border(1.dp, GameColors.Border, RoundedCornerShape(4.dp))
                                    .clickable { selectedRealmFilter = if (isSelected) null else realmVal }
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
                        realmFilters.drop(5).forEach { (realmVal, name) ->
                            val isSelected = selectedRealmFilter == realmVal
                            val count = realmCounts[realmVal] ?: 0
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) GameColors.Border else GameColors.PageBackground)
                                    .border(1.dp, GameColors.Border, RoundedCornerShape(4.dp))
                                    .clickable { selectedRealmFilter = if (isSelected) null else realmVal }
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

                if (filteredDisciples.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无可用弟子",
                            fontSize = 12.sp,
                            color = Color(0xFF999999)
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredDisciples.size) { index ->
                            val disciple = filteredDisciples[index]
                            HerbGardenDiscipleSelectionCard(
                                disciple = disciple,
                                onClick = { onSelect(disciple.id) }
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
private fun HerbGardenInnerDiscipleSelectionDialog(
    disciples: List<Disciple>,
    elderSlots: ElderSlots,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
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

    val (allElderIds, allDirectDiscipleIds) = remember(elderSlots) { getOccupiedIds(elderSlots) }

    val filteredDisciplesBase = remember(disciples, allElderIds, allDirectDiscipleIds) {
        disciples.filter {
            it.realmLayer > 0 &&
            it.age >= 5 &&
            it.status == DiscipleStatus.IDLE &&
            it.discipleType == "inner" &&
            it.id !in allElderIds &&
            it.id !in allDirectDiscipleIds
        }
    }

    val realmCounts = remember(filteredDisciplesBase) {
        filteredDisciplesBase.groupingBy { it.realm }.eachCount()
    }

    val sortedDisciples = remember(filteredDisciplesBase) {
        filteredDisciplesBase.sortedWith(
            compareByDescending<Disciple> { disciple ->
                disciple.spiritPlanting
            }.thenBy { it.realm }
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
                    text = "选择内门弟子",
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
                        text = "×",
                        fontSize = 16.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                Text(
                    text = "推荐属性: 灵植",
                    fontSize = 10.sp,
                    color = Color(0xFF999999),
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        realmFilters.take(5).forEach { (realmVal, name) ->
                            val isSelected = selectedRealmFilter == realmVal
                            val count = realmCounts[realmVal] ?: 0
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) GameColors.Border else GameColors.PageBackground)
                                    .border(1.dp, GameColors.Border, RoundedCornerShape(4.dp))
                                    .clickable { selectedRealmFilter = if (isSelected) null else realmVal }
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
                        realmFilters.drop(5).forEach { (realmVal, name) ->
                            val isSelected = selectedRealmFilter == realmVal
                            val count = realmCounts[realmVal] ?: 0
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) GameColors.Border else GameColors.PageBackground)
                                    .border(1.dp, GameColors.Border, RoundedCornerShape(4.dp))
                                    .clickable { selectedRealmFilter = if (isSelected) null else realmVal }
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

                if (filteredDisciples.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无可用弟子",
                            fontSize = 12.sp,
                            color = Color(0xFF999999)
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredDisciples.size) { index ->
                            val disciple = filteredDisciples[index]
                            HerbGardenDiscipleSelectionCard(
                                disciple = disciple,
                                onClick = { onSelect(disciple.id) }
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
private fun PlantSlotItem(
    slot: PlantSlotData,
    gameData: GameData?,
    onClick: () -> Unit,
    onRemove: () -> Unit = {}
) {
    val statusColor = when (slot.status) {
        "idle" -> Color(0xFF999999)
        "growing" -> Color(0xFF4CAF50)
        else -> Color(0xFF999999)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "种植槽 ${slot.index + 1}",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(GameColors.PageBackground)
                .border(1.dp, statusColor, RoundedCornerShape(8.dp))
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            when (slot.status) {
                "idle" -> {
                    Text(
                        text = "+",
                        fontSize = 24.sp,
                        color = Color(0xFF999999)
                    )
                }
                "growing" -> {
                    val remainingMonths = if (gameData != null) {
                        slot.remainingTime(gameData.gameYear, gameData.gameMonth)
                    } else 0
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = slot.seedName,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            maxLines = 1,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "${remainingMonths}月",
                            fontSize = 10.sp,
                            color = Color(0xFF666666)
                        )
                    }
                }
                else -> {
                    Text(
                        text = "+",
                        fontSize = 24.sp,
                        color = Color(0xFF999999)
                    )
                }
            }
        }
        if (slot.status != "idle") {
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
                    text = "移除",
                    fontSize = 12.sp,
                    color = Color.Black
                )
            }
        }
    }
}

@Composable
private fun SeedSelectionDialog(
    seeds: List<Seed>,
    onSelect: (Seed) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedSeed by remember { mutableStateOf<Seed?>(null) }
    var showDetail by remember { mutableStateOf(false) }

    if (showDetail && selectedSeed != null) {
        SeedDetailDialog(
            seed = selectedSeed!!,
            onDismiss = { showDetail = false }
        )
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
                    text = "选择种子",
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
                        text = "×",
                        fontSize = 16.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
        },
        text = {
            if (seeds.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无种子",
                        fontSize = 12.sp,
                        color = Color(0xFF999999)
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(seeds) { seed ->
                        SeedSelectionCard(
                            seed = seed,
                            isSelected = selectedSeed?.id == seed.id,
                            onSelect = {
                                if (selectedSeed?.id == seed.id) {
                                    selectedSeed = null
                                } else {
                                    selectedSeed = seed
                                }
                            },
                            onViewDetail = {
                                selectedSeed = seed
                                showDetail = true
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            GameButton(
                text = "确认种植",
                onClick = {
                    selectedSeed?.let { seed ->
                        onSelect(seed)
                    }
                },
                enabled = selectedSeed != null
            )
        }
    )
}

@Composable
private fun SeedSelectionCard(
    seed: Seed,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onViewDetail: () -> Unit
) {
    val rarityColor = when (seed.rarity) {
        1 -> Color(0xFF95A5A6)
        2 -> Color(0xFF27AE60)
        3 -> Color(0xFF3498DB)
        4 -> Color(0xFF9B59B6)
        5 -> Color(0xFFF39C12)
        6 -> Color(0xFFE74C3C)
        else -> Color(0xFF95A5A6)
    }

    Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(6.dp))
                .background(if (isSelected) Color(0xFFFFF8E1) else GameColors.PageBackground)
                .border(
                    if (isSelected) 3.dp else 2.dp,
                    if (isSelected) Color(0xFFFFD700) else rarityColor,
                    RoundedCornerShape(6.dp)
                )
                .clickable { onSelect() }
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = seed.name,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) Color(0xFFFFD700) else rarityColor,
                    maxLines = 2,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "x${seed.quantity}",
                    fontSize = 9.sp,
                    color = Color(0xFF666666)
                )
            }
        }

        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-2).dp, y = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFFFD700))
                    .clickable { onViewDetail() }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "查看",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun SeedDetailDialog(
    seed: Seed,
    onDismiss: () -> Unit
) {
    val rarityColor = when (seed.rarity) {
        1 -> Color(0xFF95A5A6)
        2 -> Color(0xFF27AE60)
        3 -> Color(0xFF3498DB)
        4 -> Color(0xFF9B59B6)
        5 -> Color(0xFFF39C12)
        6 -> Color(0xFFE74C3C)
        else -> Color(0xFF95A5A6)
    }
    val rarityName = when (seed.rarity) {
        1 -> "凡品"
        2 -> "灵品"
        3 -> "仙品"
        4 -> "神品"
        5 -> "圣品"
        6 -> "帝品"
        else -> "凡品"
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
                    text = seed.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = rarityColor
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
                        text = "×",
                        fontSize = 16.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "稀有度:",
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                    Text(
                        text = rarityName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = rarityColor
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "生长时间:",
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                    Text(
                        text = "${seed.growTime}月",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "预期产量:",
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                    Text(
                        text = "${seed.yield}",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "持有数量:",
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                    Text(
                        text = "${seed.quantity}",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "描述:",
                    fontSize = 12.sp,
                    color = Color(0xFF666666)
                )
                Text(
                    text = seed.description,
                    fontSize = 11.sp,
                    color = Color(0xFF333333)
                )

                val herb = com.xianxia.sect.core.data.HerbDatabase.getHerbFromSeed(seed.id)
                if (herb != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "长成后:",
                            fontSize = 12.sp,
                            color = Color(0xFF666666)
                        )
                        Text(
                            text = herb.name,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF27AE60)
                        )
                    }
                    if (herb.category.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "草药类型:",
                                fontSize = 12.sp,
                                color = Color(0xFF666666)
                            )
                            Text(
                                text = herb.category,
                                fontSize = 12.sp,
                                color = Color.Black
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
    onDismiss: () -> Unit,
    titleActions: @Composable RowScope.() -> Unit = {},
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
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    titleActions()
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable { onDismiss() }
                            .background(GameColors.CardBackground),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "×",
                            fontSize = 16.sp,
                            color = Color(0xFF666666)
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                content()
            }
        },
        confirmButton = {}
    )
}
