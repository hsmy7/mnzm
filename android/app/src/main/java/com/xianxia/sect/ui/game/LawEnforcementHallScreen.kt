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
import com.xianxia.sect.ui.components.HorizontalDiscipleCard
import com.xianxia.sect.core.util.isFollowed
import com.xianxia.sect.core.util.sortedByFollowAndRealm
import com.xianxia.sect.ui.game.components.SpiritRootAttributeFilterBar

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

    if (showElderSelection) {
        val availableDisciples = productionViewModel.getAvailableDisciplesForLawEnforcementElder()
        DiscipleSelectionDialog(
            title = "选择执法长老",
            disciples = availableDisciples,
            currentDiscipleId = lawElder?.id,
            requirementText = "需要化神及以上境界",
            onSelect = { disciple ->
                productionViewModel.assignElder(ElderSlotType.LAW_ENFORCEMENT, disciple.id)
                showElderSelection = false
            },
            onDismiss = { showElderSelection = false }
        )
    }

    showDiscipleSelection?.let { slotIndex ->
        val availableDisciples = productionViewModel.getAvailableDisciplesForLawEnforcementDisciple()
        val currentDisciple = lawDisciples.find { it.index == slotIndex }
        DiscipleSelectionDialog(
            title = "选择执法弟子",
            disciples = availableDisciples,
            currentDiscipleId = currentDisciple?.discipleId,
            requirementText = "需要内门弟子及以上",
            onSelect = { disciple ->
                productionViewModel.assignDirectDisciple("lawEnforcement", slotIndex, disciple.id)
                showDiscipleSelection = null
            },
            onDismiss = { showDiscipleSelection = null }
        )
    }

    if (showReserveDiscipleList) {
        ReserveDiscipleListDialog(
            reserveDisciples = reserveDisciplesWithInfo,
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
private fun DiscipleSelectionDialog(
    title: String,
    disciples: List<DiscipleAggregate>,
    currentDiscipleId: String?,
    requirementText: String,
    onSelect: (DiscipleAggregate) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedRealmFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedSpiritRootFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedAttributeSort by remember { mutableStateOf<String?>(null) }
    var spiritRootExpanded by remember { mutableStateOf(false) }
    var attributeExpanded by remember { mutableStateOf(false) }

    val realmFilters = listOf(
        0 to "仙人",
        1 to "渡劫",
        2 to "大乘",
        3 to "合体",
        4 to "炼虚",
        5 to "化神",
        6 to "元婴",
        7 to "金丹",
        8 to "筑基"
    )

    val realmCounts = remember(disciples) {
        disciples.filter { it.realmLayer > 0 }.groupingBy { it.realm }.eachCount()
    }

    val spiritRootCounts = remember(disciples) {
        disciples.filter { it.realmLayer > 0 }.groupingBy { it.getSpiritRootCount() }.eachCount()
    }

    val sortedDisciples = remember(disciples) {
        disciples.filter { it.realmLayer > 0 }.sortedByFollowAndRealm()
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
            if (disciples.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "暂无符合条件的弟子",
                        fontSize = 12.sp,
                        color = Color(0xFF999999)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = requirementText,
                        fontSize = 10.sp,
                        color = Color(0xFF666666)
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 500.dp)
                ) {
                    Text(
                        text = requirementText,
                        fontSize = 10.sp,
                        color = Color(0xFFE74C3C),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )

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
                        verticalArrangement = Arrangement.spacedBy(4.dp)
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
                                            fontSize = 8.sp,
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
                        items(
                            items = filteredDisciples,
                            key = { it.id }
                        ) { disciple ->
                            val isCurrent = disciple.id == currentDiscipleId
                            HorizontalDiscipleCard(
                                disciple = disciple,
                                isCurrent = isCurrent,
                                extraAttributes = listOf("智力" to disciple.intelligence),
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
        InnerDiscipleSelectionDialog(
            viewModel = viewModel,
            productionViewModel = productionViewModel,
            onDismiss = { showAddDiscipleDialog = false }
        )
    }
}

@Composable
private fun InnerDiscipleSelectionDialog(
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    onDismiss: () -> Unit
) {
    val availableDisciples = productionViewModel.getAvailableDisciplesForLawEnforcementReserve()
    val selectedDiscipleIds = remember { mutableStateListOf<String>() }

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
                        text = "(${availableDisciples.size})",
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
                    text = "需要内门弟子及以上",
                    fontSize = 9.sp,
                    color = Color(0xFFE74C3C),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                if (availableDisciples.isEmpty()) {
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
                        items(availableDisciples, key = { it.id }) { disciple ->
                            val isSelected = selectedDiscipleIds.contains(disciple.id)
                            SelectableDiscipleCard(
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
                val hasSelection = selectedDiscipleIds.isNotEmpty()
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (hasSelection) Color(0xFFE74C3C) else Color(0xFFCCCCCC))
                        .clickable(enabled = hasSelection) {
                            if (hasSelection) {
                                productionViewModel.addReserveDisciples(selectedDiscipleIds.toList())
                                onDismiss()
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = if (hasSelection) "添加(${selectedDiscipleIds.size})" else "请选择弟子",
                        fontSize = 12.sp,
                        color = Color.White
                    )
                }
            }
        }
    )
}

@Composable
private fun SelectableDiscipleCard(
    disciple: DiscipleAggregate,
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
