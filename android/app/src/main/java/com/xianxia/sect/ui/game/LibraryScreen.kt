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
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.util.isFollowed
import com.xianxia.sect.core.util.sortedByFollowAndRealm
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.FollowedTag
import com.xianxia.sect.ui.components.PortraitDiscipleCard
import com.xianxia.sect.ui.components.HalfScreenDialog
import com.xianxia.sect.ui.components.UnifiedDiscipleSlot
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.game.components.SpiritRootAttributeFilterBar
import com.xianxia.sect.ui.game.tabs.REALM_FILTER_OPTIONS

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
        UnifiedDiscipleSlot(
            disciple = if (slot.discipleId.isNotEmpty()) disciple else null,
            borderColor = borderColor,
            onClick = { onAssign() }
        )
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
    var realmExpanded by remember { mutableStateOf(false) }

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

    HalfScreenDialog(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "选择弟子",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                CloseButton(onClick = onDismiss)
            }
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
                        color = Color.Black
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
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

                    Spacer(modifier = Modifier.height(12.dp))

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filteredDisciples, key = { it.id }) { disciple ->
                            val isCurrent = disciple.id == currentDiscipleId
                            PortraitDiscipleCard(
                                disciple = disciple,
                                isCurrent = isCurrent,
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
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    HalfScreenDialog(onDismissRequest = onDismiss, isFullScreen = false) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                CloseButton(onClick = onDismiss)
            }
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)
            ) {
                content()
            }
        }
    }
}
