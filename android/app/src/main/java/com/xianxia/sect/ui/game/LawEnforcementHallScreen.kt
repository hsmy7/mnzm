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
import com.xianxia.sect.core.model.*
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.components.ElderBonusInfoButton
import com.xianxia.sect.ui.components.ElderBonusInfoProvider
import com.xianxia.sect.ui.components.FollowedTag
import com.xianxia.sect.core.util.isFollowed
import com.xianxia.sect.core.util.sortedByFollowAndRealm

@Composable
fun LawEnforcementHallDialog(
    disciples: List<DiscipleAggregate>,
    gameData: GameData?,
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    onDismiss: () -> Unit
) {
    var showElderSelection by remember { mutableStateOf(false) }
    var showDiscipleSelection by remember { mutableStateOf<Int?>(null) }
    var showReserveDiscipleList by remember { mutableStateOf(false) }

    val lawElder = productionViewModel.getLawEnforcementElder()
    val lawDisciples = productionViewModel.getLawEnforcementDisciples()
    val reserveDisciplesWithInfo = productionViewModel.getLawEnforcementReserveDisciplesWithInfo()

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
                    text = "执法堂",
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
                            .background(Color(0xFFE74C3C))
                            .clickable { showReserveDiscipleList = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "储备弟子(${reserveDisciplesWithInfo.size})",
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
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "维护宗门纪律，执行门规",
                    fontSize = 10.sp,
                    color = Color(0xFFE74C3C)
                )

                LawElderSection(
                    elder = lawElder,
                    onElderClick = { showElderSelection = true },
                    onElderRemove = { productionViewModel.removeElder(ElderSlotType.LAW_ENFORCEMENT) }
                )

                LawDisciplesSection(
                    lawDisciples = lawDisciples,
                    onDiscipleClick = { index -> showDiscipleSelection = index },
                    onDiscipleRemove = { index -> productionViewModel.removeDirectDisciple("lawEnforcement", index) }
                )
            }
        },
        confirmButton = {}
    )

    val lawTheme = remember {
        ProductionTheme(
            buildingId = "lawEnforcement",
            displayName = "执法堂",
            elderTitle = "执法长老",
            elderBonusInfo = ElderBonusInfoProvider.getLawEnforcementElderInfo(),
            coreAttributeName = "智力",
            coreAttributeColor = Color(0xFFE74C3C),
            defaultBorderColor = Color(0xFFE74C3C),
            workingStatusColor = Color(0xFF2196F3),
            selectedHighlightColor = Color(0xFFFFD700),
            reserveButtonBackgroundColor = Color(0xFFE74C3C),
            reserveButtonTextColor = Color.White,
            slotLabelPrefix = "执法",
            selectionDialogTitle = "",
            startProductionText = "",
            elderSelectionTitle = "选择执法长老",
            recommendAttributeText = "智力",
            getCoreAttributeValue = { it.intelligence },
            getElderId = { it.lawEnforcementElder },
            getDirectDisciples = { it.lawEnforcementDisciples },
            elderSortComparator = compareByDescending<DiscipleAggregate> { it.intelligence }
                .thenBy { it.realm }
                .thenByDescending { it.realmLayer },
            directDiscipleSortComparator = compareBy<DiscipleAggregate> { it.realm }
                .thenByDescending { it.realmLayer }
                .thenByDescending { it.intelligence }
        )
    }

    if (showElderSelection) {
        ProductionElderSelectionDialog(
            theme = lawTheme,
            disciples = disciples.filter { it.isAlive },
            currentElderId = lawElder?.id,
            elderSlots = gameData?.elderSlots ?: ElderSlots(),
            onDismiss = { showElderSelection = false },
            onSelect = { discipleId ->
                productionViewModel.assignElder(ElderSlotType.LAW_ENFORCEMENT, discipleId)
                showElderSelection = false
            },
        )
    }

    showDiscipleSelection?.let { slotIndex ->
        ProductionDirectDiscipleSelectionDialog(
            theme = lawTheme,
            disciples = disciples.filter { it.isAlive },
            elderSlots = gameData?.elderSlots ?: ElderSlots(),
            onDismiss = { showDiscipleSelection = null },
            onSelect = { discipleId ->
                productionViewModel.assignDirectDisciple("lawEnforcement", slotIndex, discipleId)
                showDiscipleSelection = null
            }
        )
    }

    if (showReserveDiscipleList) {
        ReserveDiscipleListDialog(
            reserveDisciples = reserveDisciplesWithInfo,
            allDisciples = disciples.filter { it.isAlive },
            elderSlots = gameData?.elderSlots ?: ElderSlots(),
            viewModel = viewModel,
            productionViewModel = productionViewModel,
            onDismiss = { showReserveDiscipleList = false }
        )
    }
}

@Composable
private fun LawElderSection(
    elder: DiscipleAggregate?,
    onElderClick: () -> Unit,
    onElderRemove: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(GameColors.CardBackground)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "执法长老",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Spacer(modifier = Modifier.height(8.dp))

        ElderSlotItem(
            title = "执法长老",
            elder = elder,
            bonusInfo = ElderBonusInfoProvider.getLawEnforcementElderInfo(),
            onClick = onElderClick,
            onRemove = onElderRemove
        )
    }
}

@Composable
private fun LawDisciplesSection(
    lawDisciples: List<DirectDiscipleSlot>,
    onDiscipleClick: (Int) -> Unit,
    onDiscipleRemove: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(GameColors.CardBackground)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "执法弟子",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            ElderBonusInfoButton(bonusInfo = ElderBonusInfoProvider.getLawEnforcementDiscipleInfo())
        }
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            (0..3).forEach { index ->
                val disciple = lawDisciples.find { it.index == index }
                LawDiscipleSlotItem(
                    disciple = disciple,
                    onClick = { onDiscipleClick(index) },
                    onRemove = { onDiscipleRemove(index) }
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            (4..7).forEach { index ->
                val disciple = lawDisciples.find { it.index == index }
                LawDiscipleSlotItem(
                    disciple = disciple,
                    onClick = { onDiscipleClick(index) },
                    onRemove = { onDiscipleRemove(index) }
                )
            }
        }
    }
}

@Composable
private fun ReserveDiscipleCard(
    disciple: DiscipleAggregate,
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
                    if (disciple.isFollowed) {
                        FollowedTag()
                    }
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
                        text = "智力: ${disciple.intelligence}",
                        fontSize = 10.sp,
                        color = Color(0xFF666666)
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
private fun ElderSlotItem(
    title: String,
    elder: DiscipleAggregate?,
    bonusInfo: com.xianxia.sect.ui.components.ElderBonusInfo,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            ElderBonusInfoButton(bonusInfo = bonusInfo)
        }
        Spacer(modifier = Modifier.height(4.dp))

        val borderColor = if (elder != null) {
            try {
                Color(android.graphics.Color.parseColor(elder.spiritRoot.countColor))
            } catch (e: Exception) {
                Color(0xFFE0E0E0)
            }
        } else {
            GameColors.Border
        }

        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(GameColors.PageBackground)
                .border(1.dp, borderColor, RoundedCornerShape(6.dp))
                .clickable(onClick = onClick),
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
                        fontSize = 8.sp,
                        color = Color(0xFF666666),
                        maxLines = 1
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

        if (elder != null) {
            Spacer(modifier = Modifier.height(4.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(GameColors.PageBackground)
                    .border(1.dp, GameColors.Border, RoundedCornerShape(4.dp))
                    .clickable(onClick = onRemove)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "卸任",
                    fontSize = 10.sp,
                    color = Color.Black
                )
            }
        }
    }
}

@Composable
private fun LawDiscipleSlotItem(
    disciple: DirectDiscipleSlot?,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "执法弟子",
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF666666)
        )
        Spacer(modifier = Modifier.height(2.dp))

        val borderColor = if (disciple != null && disciple.isActive) {
            try {
                Color(android.graphics.Color.parseColor(disciple.discipleSpiritRootColor))
            } catch (e: Exception) {
                Color(0xFFE74C3C)
            }
        } else {
            GameColors.Border
        }

        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(GameColors.PageBackground)
                .border(1.dp, borderColor, RoundedCornerShape(4.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (disciple != null && disciple.isActive) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = disciple.discipleName,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        maxLines = 1
                    )
                    Text(
                        text = disciple.discipleRealm,
                        fontSize = 7.sp,
                        color = Color(0xFF666666),
                        maxLines = 1
                    )
                }
            } else {
                Text(
                    text = "+",
                    fontSize = 14.sp,
                    color = Color(0xFF999999)
                )
            }
        }

        if (disciple != null && disciple.isActive) {
            Spacer(modifier = Modifier.height(2.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(GameColors.PageBackground)
                    .border(1.dp, GameColors.Border, RoundedCornerShape(3.dp))
                    .clickable(onClick = onRemove)
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

@Composable
private fun ReserveDiscipleListDialog(
    reserveDisciples: List<DiscipleAggregate>,
    allDisciples: List<DiscipleAggregate>,
    elderSlots: ElderSlots,
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    onDismiss: () -> Unit
) {
    var showAddDiscipleDialog by remember { mutableStateOf(false) }
    
    val sortedReserveDisciples = remember(reserveDisciples) {
        reserveDisciples.sortedByFollowAndRealm()
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
                            .background(Color(0xFFE74C3C))
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
                    text = "执法弟子空缺时自动补位",
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
                            ReserveDiscipleCard(
                                disciple = disciple,
                                onRemove = { productionViewModel.removeReserveDisciple(disciple.id) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )

    if (showAddDiscipleDialog) {
        val rawDisciples = remember(allDisciples, elderSlots) {
            allDisciples.filter {
                it.isAlive && it.realmLayer > 0 && it.age >= 5 &&
                it.status == DiscipleStatus.IDLE && it.discipleType == "inner" &&
                !elderSlots.isDiscipleInAnyPosition(it.id)
            }
        }
        val reserveSelectedIds = remember { mutableStateListOf<String>() }
        FilteredMultiSelectDialog(
            title = "内门弟子",
            disciples = rawDisciples,
            selectedIds = reserveSelectedIds,
            headerContent = {
                Text(
                    text = "需要内门弟子及以上",
                    fontSize = 9.sp,
                    color = Color(0xFFE74C3C),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            },
            confirmText = if (reserveSelectedIds.isNotEmpty()) "添加(${reserveSelectedIds.size})" else "请选择弟子",
            confirmEnabled = { reserveSelectedIds.isNotEmpty() },
            onConfirm = {
                productionViewModel.addReserveDisciples(reserveSelectedIds.toList())
                showAddDiscipleDialog = false
            },
            onDismiss = { showAddDiscipleDialog = false }
        )
    }
}
