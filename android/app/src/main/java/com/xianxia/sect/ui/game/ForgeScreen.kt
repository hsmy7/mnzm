package com.xianxia.sect.ui.game

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
import com.xianxia.sect.core.data.ForgeRecipeDatabase
import com.xianxia.sect.core.model.*
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.theme.GameColors

@Composable
fun ForgeDialog(
    forgeSlots: List<ForgeSlot>,
    materials: List<Material>,
    gameData: GameData?,
    viewModel: GameViewModel,
    colors: com.xianxia.sect.ui.theme.XianxiaColorScheme,
    onDismiss: () -> Unit
) {
    var showEquipmentSelection by remember { mutableStateOf(false) }
    var selectedSlotIndex by remember { mutableStateOf<Int?>(null) }
    
    val disciples by viewModel.disciples.collectAsState()
    var showElderSelection by remember { mutableStateOf(false) }
    var showDirectDiscipleSelection by remember { mutableStateOf<Int?>(null) }
    
    val elderSlots = gameData?.elderSlots
    val forgeElder = elderSlots?.forgeElder?.let { viewModel.getElderDisciple(it) }
    val forgeDisciples = elderSlots?.forgeDisciples ?: emptyList()
    val forgeInnerDisciples = elderSlots?.forgeInnerDisciples ?: emptyList()
    var showInnerDiscipleSelection by remember { mutableStateOf<Int?>(null) }
    
    var showReserveDiscipleDialog by remember { mutableStateOf(false) }
    var showAddReserveDialog by remember { mutableStateOf(false) }

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
                    text = "天工峰",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFFF9800))
                            .clickable { showReserveDiscipleDialog = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "储备弟子",
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
                modifier = Modifier.heightIn(max = 400.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    ForgeElderSection(
                        elder = forgeElder,
                        onElderClick = { showElderSelection = true },
                        onElderRemove = { viewModel.removeElder("forge") }
                    )
                    
                    ForgeDirectDiscipleSection(
                        directDisciples = forgeDisciples,
                        onDirectDiscipleClick = { index -> showDirectDiscipleSelection = index },
                        onDirectDiscipleRemove = { index -> viewModel.removeDirectDisciple("forge", index) }
                    )
                    
                    ForgeInnerDiscipleSection(
                        innerDisciples = forgeInnerDisciples,
                        onInnerDiscipleClick = { index -> showInnerDiscipleSelection = index },
                        onInnerDiscipleRemove = { index -> viewModel.removeInnerDisciple("forge", index) }
                    )
                    
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = GameColors.Border,
                        thickness = 1.dp
                    )
                    
                    Text(
                        text = "炼器槽位",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF666666)
                    )
                    
                    (0 until 3).chunked(3).forEach { rowIndexes ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                        ) {
                            rowIndexes.forEach { index ->
                                val slot = forgeSlots.getOrNull(index)
                                ForgeSlotItem(
                                    slot = slot,
                                    index = index,
                                    gameData = gameData,
                                    onClick = {
                                        if (slot?.status == ForgeSlotStatus.IDLE || slot == null) {
                                            selectedSlotIndex = index
                                            viewModel.selectForgeSlot(slot ?: ForgeSlot(slotIndex = index))
                                            showEquipmentSelection = true
                                        }
                                    },
                                    onRemove = {
                                        viewModel.clearForgeSlot(index)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )

    if (showEquipmentSelection && selectedSlotIndex != null) {
        EquipmentSelectionDialog(
            materials = materials,
            slotIndex = selectedSlotIndex!!,
            viewModel = viewModel,
            onDismiss = {
                showEquipmentSelection = false
                selectedSlotIndex = null
            }
        )
    }
    
    if (showElderSelection) {
        ForgeElderSelectionDialog(
            disciples = disciples.filter { it.isAlive && it.realm <= 6 },
            currentElderId = elderSlots?.forgeElder,
            elderSlots = elderSlots ?: ElderSlots(),
            onDismiss = { showElderSelection = false },
            onSelect = { discipleId ->
                viewModel.assignElder("forge", discipleId)
                showElderSelection = false
            }
        )
    }
    
    showDirectDiscipleSelection?.let { slotIndex ->
        ForgeDirectDiscipleSelectionDialog(
            disciples = disciples.filter { it.isAlive },
            elderSlots = elderSlots ?: ElderSlots(),
            onDismiss = { showDirectDiscipleSelection = null },
            onSelect = { discipleId ->
                viewModel.assignDirectDisciple("forge", slotIndex, discipleId)
                showDirectDiscipleSelection = null
            }
        )
    }
    
    showInnerDiscipleSelection?.let { slotIndex ->
        ForgeInnerDiscipleSelectionDialog(
            disciples = disciples.filter { it.isAlive },
            elderSlots = elderSlots ?: ElderSlots(),
            onDismiss = { showInnerDiscipleSelection = null },
            onSelect = { discipleId ->
                viewModel.assignInnerDisciple("forge", slotIndex, discipleId)
                showInnerDiscipleSelection = null
            }
        )
    }
    
    if (showReserveDiscipleDialog) {
        ForgeReserveDiscipleDialog(
            viewModel = viewModel,
            onDismiss = { showReserveDiscipleDialog = false },
            onAddClick = { showAddReserveDialog = true }
        )
    }
    
    if (showAddReserveDialog) {
        ForgeAddReserveDiscipleDialog(
            viewModel = viewModel,
            onDismiss = { showAddReserveDialog = false },
            onConfirm = { selectedIds ->
                viewModel.addForgeReserveDisciples(selectedIds)
                showAddReserveDialog = false
            }
        )
    }
}

@Composable
private fun ForgeElderSection(
    elder: Disciple?,
    onElderClick: () -> Unit,
    onElderRemove: () -> Unit
) {
    val elderBorderColor = if (elder != null) {
        try {
            Color(android.graphics.Color.parseColor(elder.spiritRoot.countColor))
        } catch (e: Exception) {
            Color(0xFFFF9800)
        }
    } else {
        GameColors.Border
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "天工长老",
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
                            text = "炼器: ${elder.artifactRefining}",
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
private fun ForgeDirectDiscipleSection(
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
                ForgeDirectDiscipleSlotItem(
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
private fun ForgeInnerDiscipleSection(
    innerDisciples: List<DirectDiscipleSlot>,
    onInnerDiscipleClick: (Int) -> Unit,
    onInnerDiscipleRemove: (Int) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "内门弟子",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF666666)
        )
        
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                (0 until 4).forEach { index ->
                    val disciple = innerDisciples.getOrNull(index) ?: DirectDiscipleSlot(index = index)
                    ForgeInnerDiscipleSlotItem(
                        index = index,
                        disciple = disciple,
                        onClick = { onInnerDiscipleClick(index) },
                        onRemove = { onInnerDiscipleRemove(index) }
                    )
                }
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                (4 until 8).forEach { index ->
                    val disciple = innerDisciples.getOrNull(index) ?: DirectDiscipleSlot(index = index)
                    ForgeInnerDiscipleSlotItem(
                        index = index,
                        disciple = disciple,
                        onClick = { onInnerDiscipleClick(index) },
                        onRemove = { onInnerDiscipleRemove(index) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ForgeDirectDiscipleSlotItem(
    index: Int,
    disciple: DirectDiscipleSlot,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    val borderColor = if (disciple.isActive) {
        try {
            Color(android.graphics.Color.parseColor(disciple.discipleSpiritRootColor))
        } catch (e: Exception) {
            Color(0xFFFF9800)
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
private fun ForgeInnerDiscipleSlotItem(
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
                .size(50.dp)
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
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        maxLines = 1,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = disciple.discipleRealm,
                        fontSize = 7.sp,
                        color = Color(0xFF666666)
                    )
                }
            } else {
                Text(
                    text = "+",
                    fontSize = 16.sp,
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
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "卸任",
                    fontSize = 8.sp,
                    color = Color.Black
                )
            }
        }
    }
}

@Composable
private fun ForgeElderSelectionDialog(
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

    val filteredDisciplesBase = remember(disciples, elderSlots) {
        disciples.filter {
            it.realmLayer > 0 &&
            it.age >= 5 &&
            it.status == DiscipleStatus.IDLE &&
            it.discipleType == "inner" &&
            !isDiscipleInAnyPosition(it.id, elderSlots)
        }
    }

    val realmCounts = remember(filteredDisciplesBase) {
        filteredDisciplesBase.groupingBy { it.realm }.eachCount()
    }

    val sortedDisciples = remember(filteredDisciplesBase) {
        filteredDisciplesBase.sortedWith(
            compareByDescending<Disciple> { it.artifactRefining }
                .thenBy { it.realm }
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
                    text = "选择天工长老",
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
                    text = "推荐属性: 炼器",
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
                            ForgeDiscipleSelectionCard(
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
private fun ForgeDiscipleSelectionCard(
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
                text = "炼器:${disciple.artifactRefining}",
                fontSize = 7.sp,
                color = spiritRootColor,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ForgeDirectDiscipleSelectionDialog(
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

    val allDirectDiscipleIds = remember(elderSlots) {
        listOf(
            elderSlots.herbGardenDisciples,
            elderSlots.alchemyDisciples,
            elderSlots.forgeDisciples,
            elderSlots.libraryDisciples
        ).flatten().mapNotNull { it.discipleId }
    }

    val filteredDisciplesBase = remember(disciples, elderSlots, allDirectDiscipleIds) {
        disciples.filter {
            it.realmLayer > 0 &&
            it.age >= 5 &&
            it.status == DiscipleStatus.IDLE &&
            it.discipleType == "inner" &&
            !isDiscipleInAnyPosition(it.id, elderSlots) &&
            !allDirectDiscipleIds.contains(it.id)
        }
    }

    val realmCounts = remember(filteredDisciplesBase) {
        filteredDisciplesBase.groupingBy { it.realm }.eachCount()
    }

    val sortedDisciples = remember(filteredDisciplesBase) {
        filteredDisciplesBase.sortedWith(
            compareBy<Disciple> { it.realm }
                .thenByDescending { it.realmLayer }
                .thenByDescending { it.artifactRefining }
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
                    text = "推荐属性: 炼器",
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
                            ForgeDiscipleSelectionCard(
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
private fun ForgeInnerDiscipleSelectionDialog(
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

    val allDirectDiscipleIds = remember(elderSlots) {
        listOf(
            elderSlots.herbGardenDisciples,
            elderSlots.alchemyDisciples,
            elderSlots.forgeDisciples,
            elderSlots.libraryDisciples
        ).flatten().mapNotNull { it.discipleId }
    }

    val allInnerDiscipleIds = remember(elderSlots) {
        listOf(
            elderSlots.forgeInnerDisciples,
            elderSlots.alchemyInnerDisciples,
            elderSlots.herbGardenInnerDisciples
        ).flatten().mapNotNull { it.discipleId }
    }

    val filteredDisciplesBase = remember(disciples, elderSlots, allDirectDiscipleIds, allInnerDiscipleIds) {
        disciples.filter {
            it.realmLayer > 0 &&
            it.age >= 5 &&
            it.status == DiscipleStatus.IDLE &&
            it.discipleType == "inner" &&
            !isDiscipleInAnyPosition(it.id, elderSlots) &&
            !allDirectDiscipleIds.contains(it.id) &&
            !allInnerDiscipleIds.contains(it.id)
        }
    }

    val realmCounts = remember(filteredDisciplesBase) {
        filteredDisciplesBase.groupingBy { it.realm }.eachCount()
    }

    val sortedDisciples = remember(filteredDisciplesBase) {
        filteredDisciplesBase.sortedWith(
            compareBy<Disciple> { it.realm }
                .thenByDescending { it.realmLayer }
                .thenByDescending { it.artifactRefining }
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
                    text = "推荐属性: 炼器",
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
                            ForgeDiscipleSelectionCard(
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

private fun isDiscipleInAnyPosition(discipleId: String, elderSlots: ElderSlots): Boolean {
    if (elderSlots.viceSectMaster == discipleId) {
        return true
    }
    
    val allElderIds = listOf(
        elderSlots.herbGardenElder,
        elderSlots.alchemyElder,
        elderSlots.forgeElder,
        elderSlots.libraryElder,
        elderSlots.outerElder,
        elderSlots.preachingElder,
        elderSlots.lawEnforcementElder,
        elderSlots.innerElder,
        elderSlots.qingyunPreachingElder
    )

    if (allElderIds.contains(discipleId)) {
        return true
    }

    val allDirectDiscipleIds = listOf(
        elderSlots.herbGardenDisciples,
        elderSlots.alchemyDisciples,
        elderSlots.forgeDisciples,
        elderSlots.libraryDisciples,
        elderSlots.preachingMasters,
        elderSlots.lawEnforcementDisciples,
        elderSlots.lawEnforcementReserveDisciples,
        elderSlots.qingyunPreachingMasters,
        elderSlots.spiritMineDeaconDisciples,
        elderSlots.alchemyReserveDisciples,
        elderSlots.herbGardenReserveDisciples,
        elderSlots.forgeReserveDisciples
    ).flatten().mapNotNull { it.discipleId }
    
    if (allDirectDiscipleIds.contains(discipleId)) {
        return true
    }
    
    val allInnerDiscipleIds = listOf(
        elderSlots.forgeInnerDisciples,
        elderSlots.alchemyInnerDisciples,
        elderSlots.herbGardenInnerDisciples
    ).flatten().mapNotNull { it.discipleId }
    
    return allInnerDiscipleIds.contains(discipleId)
}

@Composable
private fun ForgeSlotItem(
    slot: ForgeSlot?,
    index: Int,
    gameData: GameData?,
    onClick: () -> Unit,
    onRemove: () -> Unit = {}
) {
    val isIdle = slot?.status == ForgeSlotStatus.IDLE || slot == null
    val isWorking = slot?.status == ForgeSlotStatus.WORKING

    val statusColor = when {
        isWorking -> Color(0xFFFF9800)
        else -> GameColors.Border
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "炼器槽 ${index + 1}",
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
            when {
                isWorking && slot != null && gameData != null -> {
                    val remainingMonths = slot.getRemainingMonths(gameData.gameYear, gameData.gameMonth)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = slot.equipmentName ?: "",
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
        if (!isIdle) {
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
private fun EquipmentSelectionDialog(
    materials: List<Material>,
    slotIndex: Int,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    var selectedRecipe by remember { mutableStateOf<ForgeRecipeDatabase.ForgeRecipe?>(null) }
    var clickedRecipe by remember { mutableStateOf<ForgeRecipeDatabase.ForgeRecipe?>(null) }
    var showDetail by remember { mutableStateOf(false) }

    CommonDialog(
        title = "选择装备",
        onDismiss = onDismiss
    ) {
        val allRecipes = ForgeRecipeDatabase.getAllRecipes()
        
        data class RecipeWithStatus(
            val recipe: ForgeRecipeDatabase.ForgeRecipe,
            val canCraft: Boolean
        )
        
        val recipesWithStatus = remember(allRecipes, materials) {
            allRecipes.map { recipe ->
                val canCraft = recipe.materials.all { (materialId, requiredQuantity) ->
                    val materialData = com.xianxia.sect.core.data.BeastMaterialDatabase.getMaterialById(materialId)
                    val materialName = materialData?.name
                    val materialRarity = materialData?.rarity ?: 1
                    val material = materials.find { it.name == materialName && it.rarity == materialRarity }
                    material != null && material.quantity >= requiredQuantity
                }
                RecipeWithStatus(recipe, canCraft)
            }
        }
        
        val sortedRecipes = remember(recipesWithStatus) {
            val (craftable, uncraftable) = recipesWithStatus.partition { it.canCraft }
            craftable.sortedByDescending { it.recipe.rarity } + uncraftable
        }
        
        Column {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(300.dp)
            ) {
                items(sortedRecipes) { recipeWithStatus ->
                    val recipe = recipeWithStatus.recipe
                    val hasEnoughMaterials = recipeWithStatus.canCraft
                    val rarityColor = try {
                        Color(android.graphics.Color.parseColor(GameConfig.Rarity.getColor(recipe.rarity)))
                    } catch (e: Exception) {
                        Color(0xFF95a5a6)
                    }

                    Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (hasEnoughMaterials) GameColors.PageBackground else GameColors.CardBackground)
                                .border(
                                    2.dp,
                                    if (selectedRecipe?.id == recipe.id) Color(0xFFFF9800) else rarityColor,
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable {
                                    if (selectedRecipe?.id == recipe.id) {
                                        selectedRecipe = null
                                        clickedRecipe = null
                                    } else {
                                        selectedRecipe = recipe
                                        clickedRecipe = recipe
                                    }
                                }
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = recipe.name,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (hasEnoughMaterials) Color.Black else Color(0xFF999999),
                                    maxLines = 2,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${recipe.duration}月",
                                    fontSize = 9.sp,
                                    color = if (hasEnoughMaterials) Color(0xFF666666) else Color(0xFF999999)
                                )
                            }
                        }
                        
                        if (clickedRecipe?.id == recipe.id) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = (-2).dp, y = 2.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFFFF9800))
                                    .clickable { showDetail = true }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "查看",
                                    fontSize = 8.sp,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            val selectedRecipeStatus = sortedRecipes.find { it.recipe.id == selectedRecipe?.id }
            val hasEnoughMaterialsForSelected = selectedRecipeStatus?.canCraft ?: false
            
            GameButton(
                text = "开始炼制",
                onClick = {
                    selectedRecipe?.let { recipe ->
                        viewModel.startForge(slotIndex, recipe)
                        onDismiss()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedRecipe != null && hasEnoughMaterialsForSelected
            )
        }
    }

    if (showDetail && clickedRecipe != null) {
        EquipmentDetailDialog(
            recipe = clickedRecipe!!,
            materials = materials,
            onDismiss = { showDetail = false }
        )
    }
}

@Composable
private fun EquipmentDetailDialog(
    recipe: ForgeRecipeDatabase.ForgeRecipe,
    materials: List<Material>,
    onDismiss: () -> Unit
) {
    val rarityColor = try {
        Color(android.graphics.Color.parseColor(GameConfig.Rarity.getColor(recipe.rarity)))
    } catch (e: Exception) {
        Color(0xFF95a5a6)
    }

    val template = com.xianxia.sect.core.data.EquipmentDatabase.getTemplateByName(recipe.name)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Text(
                text = recipe.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "品阶: ${recipe.tier}阶",
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                    Text(
                        text = "时间: ${recipe.duration}月",
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                }

                Text(
                    text = "所需材料:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    recipe.materials.forEach { (materialId, requiredQuantity) ->
                        val materialData = com.xianxia.sect.core.data.BeastMaterialDatabase.getMaterialById(materialId)
                        val materialName = materialData?.name
                        val materialRarity = materialData?.rarity ?: 1
                        val material = materials.find { it.name == materialName && it.rarity == materialRarity }
                        val hasEnough = material != null && material.quantity >= requiredQuantity
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = materialName ?: materialId,
                                fontSize = 11.sp,
                                color = if (hasEnough) Color.Black else Color(0xFFE74C3C)
                            )
                            Text(
                                text = "${material?.quantity ?: 0}/$requiredQuantity",
                                fontSize = 11.sp,
                                color = if (hasEnough) Color(0xFF4CAF50) else Color(0xFFE74C3C)
                            )
                        }
                    }
                }

                Text(
                    text = "属性加成:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "部位: ${recipe.type.displayName}",
                        fontSize = 11.sp,
                        color = Color(0xFF666666)
                    )

                    if (template != null) {
                        if (template.physicalAttack > 0) {
                            Text(
                                text = "物理攻击 +${template.physicalAttack}",
                                fontSize = 11.sp,
                                color = Color(0xFF666666)
                            )
                        }
                        if (template.magicAttack > 0) {
                            Text(
                                text = "法术攻击 +${template.magicAttack}",
                                fontSize = 11.sp,
                                color = Color(0xFF666666)
                            )
                        }
                        if (template.physicalDefense > 0) {
                            Text(
                                text = "物理防御 +${template.physicalDefense}",
                                fontSize = 11.sp,
                                color = Color(0xFF666666)
                            )
                        }
                        if (template.magicDefense > 0) {
                            Text(
                                text = "法术防御 +${template.magicDefense}",
                                fontSize = 11.sp,
                                color = Color(0xFF666666)
                            )
                        }
                        if (template.speed > 0) {
                            Text(
                                text = "身法 +${template.speed}",
                                fontSize = 11.sp,
                                color = Color(0xFF666666)
                            )
                        }
                        if (template.hp > 0) {
                            Text(
                                text = "生命 +${template.hp}",
                                fontSize = 11.sp,
                                color = Color(0xFF666666)
                            )
                        }
                        if (template.mp > 0) {
                            Text(
                                text = "法力 +${template.mp}",
                                fontSize = 11.sp,
                                color = Color(0xFF666666)
                            )
                        }
                        if (template.critChance > 0) {
                            Text(
                                text = "暴击率 +${String.format("%.1f", template.critChance * 100)}%",
                                fontSize = 11.sp,
                                color = Color(0xFF666666)
                            )
                        }
                    }
                }

                Text(
                    text = "描述:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Text(
                    text = recipe.description,
                    fontSize = 11.sp,
                    color = Color(0xFF666666)
                )
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun CommonDialog(
    title: String,
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
                Text(
                    text = title,
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
                modifier = Modifier.heightIn(max = 400.dp)
            ) {
                content()
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun ForgeReserveDiscipleDialog(
    viewModel: GameViewModel,
    onDismiss: () -> Unit,
    onAddClick: () -> Unit
) {
    val reserveDisciples by remember { derivedStateOf { viewModel.getForgeReserveDisciplesWithInfo() } }
    
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
                    text = "储备弟子",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFFF9800))
                            .clickable { onAddClick() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "添加",
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
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                if (reserveDisciples.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无储备弟子",
                            fontSize = 12.sp,
                            color = Color(0xFF999999)
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(reserveDisciples.size) { index ->
                            val disciple = reserveDisciples[index]
                            ForgeReserveDiscipleCard(
                                disciple = disciple,
                                onRemove = { viewModel.removeForgeReserveDisciple(disciple.id) }
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
private fun ForgeReserveDiscipleCard(
    disciple: Disciple,
    onRemove: () -> Unit
) {
    val spiritRootColor = try {
        Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
    } catch (e: Exception) {
        Color(0xFF666666)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(GameColors.PageBackground)
                .border(1.dp, spiritRootColor, RoundedCornerShape(6.dp)),
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
                    text = disciple.spiritRoot.name,
                    fontSize = 8.sp,
                    color = spiritRootColor,
                    maxLines = 1
                )
                Text(
                    text = disciple.realmName,
                    fontSize = 8.sp,
                    color = Color(0xFF666666),
                    maxLines = 1
                )
                Text(
                    text = "炼器:${disciple.artifactRefining}",
                    fontSize = 7.sp,
                    color = Color(0xFF4CAF50),
                    maxLines = 1
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(GameColors.PageBackground)
                .border(1.dp, GameColors.Border, RoundedCornerShape(4.dp))
                .clickable { onRemove() }
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = "移除",
                fontSize = 8.sp,
                color = Color.Black
            )
        }
    }
}

@Composable
private fun ForgeAddReserveDiscipleDialog(
    viewModel: GameViewModel,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    val availableDisciples by remember { derivedStateOf { viewModel.getAvailableDisciplesForForgeReserve() } }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    
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
                    text = "推荐属性: 炼器",
                    fontSize = 10.sp,
                    color = Color(0xFF999999),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                if (availableDisciples.isEmpty()) {
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
                        items(availableDisciples.size) { index ->
                            val disciple = availableDisciples[index]
                            val isSelected = selectedIds.contains(disciple.id)
                            ForgeAddReserveDiscipleCard(
                                disciple = disciple,
                                isSelected = isSelected,
                                onClick = {
                                    selectedIds = if (isSelected) {
                                        selectedIds - disciple.id
                                    } else {
                                        selectedIds + disciple.id
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
                GameButton(
                    text = "添加${if (selectedIds.isNotEmpty()) "(${selectedIds.size})" else ""}",
                    onClick = { onConfirm(selectedIds.toList()) }
                )
            }
        }
    )
}

@Composable
private fun ForgeAddReserveDiscipleCard(
    disciple: Disciple,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val spiritRootColor = try {
        Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
    } catch (e: Exception) {
        Color(0xFF666666)
    }
    
    val borderColor = if (isSelected) Color(0xFFFFD700) else spiritRootColor
    val borderWidth = if (isSelected) 2.dp else 1.dp

    Box(
        modifier = Modifier
            .size(60.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(GameColors.PageBackground)
            .border(borderWidth, borderColor, RoundedCornerShape(6.dp))
            .clickable { onClick() },
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
                text = disciple.spiritRoot.name,
                fontSize = 8.sp,
                color = spiritRootColor,
                maxLines = 1
            )
            Text(
                text = disciple.realmName,
                fontSize = 8.sp,
                color = Color(0xFF666666),
                maxLines = 1
            )
            Text(
                text = "炼器:${disciple.artifactRefining}",
                fontSize = 7.sp,
                color = Color(0xFF4CAF50),
                maxLines = 1
            )
        }
    }
}
