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
import com.xianxia.sect.core.util.isFollowed
import com.xianxia.sect.core.util.sortedByFollowAndRealm
import com.xianxia.sect.ui.components.FollowedTag
import com.xianxia.sect.ui.theme.GameColors

@Composable
fun LibraryDialog(
    manuals: List<ManualInstance>,
    disciples: List<DiscipleAggregate>,
    gameData: GameData?,
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    onDismiss: () -> Unit
) {
    var showDiscipleSelection by remember { mutableStateOf<Int?>(null) }
    
    val librarySlots = gameData?.librarySlots ?: emptyList()
    val slots = (0 until 3).map { index ->
        librarySlots.find { it.index == index } ?: LibrarySlot(index = index)
    }

    CommonDialog(
        title = "藏经阁",
        onDismiss = onDismiss
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "入驻弟子功法熟练度增长速度提高50%",
                fontSize = 10.sp,
                color = Color(0xFF4CAF50)
            )
            
            slots.chunked(3).forEach { rowSlots ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                ) {
                    rowSlots.forEach { slot ->
                        val disciple = slot.discipleId?.let { id -> disciples.find { it.id == id } }
                        LibrarySlotItem(
                            slot = slot,
                            disciple = disciple,
                            onAssign = { showDiscipleSelection = slot.index },
                            onRemove = { productionViewModel.removeDiscipleFromLibrarySlot(slot.index) }
                        )
                    }
                }
            }
        }
    }

    showDiscipleSelection?.let { slotIndex ->
        val currentDiscipleId = slots.getOrNull(slotIndex)?.discipleId
        LibraryDiscipleSelectionDialog(
            disciples = disciples.filter { disciple -> 
                disciple.isAlive && 
                disciple.status != DiscipleStatus.REFLECTING &&
                (disciple.status == DiscipleStatus.IDLE || disciple.id == currentDiscipleId)
            },
            currentDiscipleId = currentDiscipleId,
            onSelect = { disciple ->
                productionViewModel.assignDiscipleToLibrarySlot(slotIndex, disciple.id, disciple.name)
                showDiscipleSelection = null
            },
            onDismiss = { showDiscipleSelection = null }
        )
    }
}

@Composable
private fun LibrarySlotItem(
    slot: LibrarySlot,
    disciple: DiscipleAggregate?,
    onAssign: () -> Unit,
    onRemove: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "修炼位 ${slot.index + 1}",
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
                .clickable { onAssign() },
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
                            color = Color.Black,
                            maxLines = 1,
                            textAlign = TextAlign.Center
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
private fun LibraryDiscipleSelectionDialog(
    disciples: List<DiscipleAggregate>,
    currentDiscipleId: String?,
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
        8 to "筑基",
        9 to "炼气"
    )

    val realmCounts = remember(disciples) {
        disciples.filter { it.realmLayer > 0 && it.age >= 5 }.groupingBy { it.realm }.eachCount()
    }

    val spiritRootCounts = remember(disciples) {
        disciples.filter { it.realmLayer > 0 && it.age >= 5 }.groupingBy { it.getSpiritRootCount() }.eachCount()
    }

    val sortedDisciples = remember(disciples) {
        disciples.filter { it.realmLayer > 0 && it.age >= 5 }.sortedByFollowAndRealm()
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
                        text = "×",
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
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                content()
            }
        },
        confirmButton = {}
    )
}
