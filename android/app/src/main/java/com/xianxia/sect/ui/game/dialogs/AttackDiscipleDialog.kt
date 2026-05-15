package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.util.sortedByFollowAndRealm
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.HalfScreenDialog
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.HalfScreenDialog
import com.xianxia.sect.ui.components.PortraitDiscipleCard
import com.xianxia.sect.ui.components.UnifiedDiscipleSlot
import com.xianxia.sect.ui.game.ATTRIBUTE_FILTER_OPTIONS
import com.xianxia.sect.ui.game.AttributeFilterOption
import com.xianxia.sect.ui.game.DiscipleDetailDialog
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.SPIRIT_ROOT_FILTER_OPTIONS
import com.xianxia.sect.ui.game.applyFilters
import com.xianxia.sect.ui.game.components.SpiritRootAttributeFilterBar
import com.xianxia.sect.ui.game.getSpiritRootCount
import com.xianxia.sect.ui.game.tabs.REALM_FILTER_OPTIONS
import com.xianxia.sect.ui.theme.GameColors

@Composable
internal fun AttackDiscipleDialog(
    sectName: String,
    disciples: List<DiscipleAggregate>,
    gameData: GameData?,
    viewModel: GameViewModel,
    onAttack: (List<Pair<Int, DiscipleAggregate>>) -> Unit,
    onDismiss: () -> Unit
) {
    // 10 slots, each holds an optional DiscipleAggregate
    val slots = remember { mutableStateListOf<DiscipleAggregate?>().apply { repeat(10) { add(null) } } }
    var selectedSlotIndex by remember { mutableStateOf<Int?>(null) }
    var showDiscipleSelection by remember { mutableStateOf(false) }
    var selectedDiscipleDetail by remember { mutableStateOf<DiscipleAggregate?>(null) }

    // Collect state for DiscipleDetailDialog
    val equipment by viewModel.equipment.collectAsState()
    val manuals by viewModel.manuals.collectAsState()
    val manualStacks by viewModel.manualStacks.collectAsState()
    val equipmentStacks by viewModel.equipmentStacks.collectAsState()

    val filledCount = slots.count { it != null }

    HalfScreenDialog(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Title
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "选择进攻弟子",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                CloseButton(onClick = onDismiss)
            }

            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 10 slots: 2 rows x 5 columns
                for (row in 0..1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                    ) {
                        for (col in 0..4) {
                            val slotIndex = row * 5 + col
                            if (slotIndex < slots.size) {
                                AttackSlotBox(
                                    disciple = slots[slotIndex],
                                    onSlotClick = {
                                        val disciple = slots[slotIndex]
                                        if (disciple != null) {
                                            selectedDiscipleDetail = disciple
                                        } else {
                                            selectedSlotIndex = slotIndex
                                            showDiscipleSelection = true
                                        }
                                    },
                                    onDismiss = {
                                        slots[slotIndex] = null
                                    },
                                    onSwap = {
                                        selectedSlotIndex = slotIndex
                                        showDiscipleSelection = true
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "已选择 $filledCount/10 名弟子",
                    fontSize = 11.sp,
                    color = Color.Black
                )
            }

            // Bottom buttons
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GameButton(
                    text = "取消",
                    onClick = onDismiss
                )
                GameButton(
                    text = "进攻",
                    onClick = {
                        val selected = slots.mapIndexedNotNull { index, disciple ->
                            if (disciple != null) index to disciple else null
                        }
                        if (selected.isNotEmpty()) {
                            onAttack(selected)
                        }
                    },
                    enabled = filledCount > 0
                )
            }
        }
    }

    // Disciple selection sub-dialog
    if (showDiscipleSelection && selectedSlotIndex != null) {
        val currentSlotIndex = selectedSlotIndex!!
        val alreadySelectedIds = slots.filterNotNull().map { it.id }.filter { it != slots[currentSlotIndex]?.id }.toSet()
        AttackDiscipleSelectionDialog(
            disciples = disciples,
            currentSlotDiscipleId = slots[currentSlotIndex]?.id,
            alreadySelectedIds = alreadySelectedIds,
            onSelect = { disciple ->
                slots[currentSlotIndex] = disciple
                showDiscipleSelection = false
                selectedSlotIndex = null
            },
            onDismiss = {
                showDiscipleSelection = false
                selectedSlotIndex = null
            }
        )
    }

    // Disciple detail dialog
    selectedDiscipleDetail?.let { disciple ->
        val updated = disciples.find { it.id == disciple.id } ?: disciple
        DiscipleDetailDialog(
            disciple = updated,
            allDisciples = disciples,
            allEquipment = equipment,
            allManuals = manuals,
            manualStacks = manualStacks,
            equipmentStacks = equipmentStacks,
            manualProficiencies = gameData?.manualProficiencies ?: emptyMap(),
            viewModel = viewModel,
            onDismiss = { selectedDiscipleDetail = null }
        )
    }
}

@Composable
private fun AttackSlotBox(
    disciple: DiscipleAggregate?,
    onSlotClick: () -> Unit,
    onDismiss: () -> Unit,
    onSwap: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        UnifiedDiscipleSlot(
            disciple = disciple,
            onClick = { onSlotClick() }
        )

        if (disciple != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "卸任",
                    fontSize = 9.sp,
                    color = Color(0xFFE53935),
                    modifier = Modifier.clickable { onDismiss() }
                )
                Text(
                    text = "更换",
                    fontSize = 9.sp,
                    color = Color.Black,
                    modifier = Modifier.clickable { onSwap() }
                )
            }
        }
    }
}

@Composable
private fun AttackDiscipleSelectionDialog(
    disciples: List<DiscipleAggregate>,
    currentSlotDiscipleId: String? = null,
    alreadySelectedIds: Set<String> = emptySet(),
    onSelect: (DiscipleAggregate) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedRealmFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedSpiritRootFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedAttributeSort by remember { mutableStateOf<String?>(null) }
    var spiritRootExpanded by remember { mutableStateOf(false) }
    var attributeExpanded by remember { mutableStateOf(false) }
    var realmExpanded by remember { mutableStateOf(false) }

    // Only IDLE disciples, exclude already selected ones (except current slot)
    val availableDisciples = remember(disciples, alreadySelectedIds) {
        disciples.filter {
            it.isAlive && it.status == DiscipleStatus.IDLE && it.realmLayer > 0 &&
            (it.id == currentSlotDiscipleId || it.id !in alreadySelectedIds)
        }
            .sortedByFollowAndRealm()
    }

    val realmCounts = remember(availableDisciples) {
        availableDisciples.groupingBy { it.realm }.eachCount()
    }

    val spiritRootCounts = remember(availableDisciples) {
        availableDisciples.groupingBy { it.getSpiritRootCount() }.eachCount()
    }

    val filteredDisciples = remember(availableDisciples, selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort) {
        availableDisciples.applyFilters(selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort)
    }

    HalfScreenDialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "选择进攻弟子",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                CloseButton(onClick = onDismiss)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                if (availableDisciples.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无空闲弟子",
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                    }
                } else {
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
                            PortraitDiscipleCard(
                                disciple = disciple,
                                isSelected = disciple.id == currentSlotDiscipleId,
                                onClick = { onSelect(disciple) }
                            )
                        }
                    }
                }
            }
        }
    }
}
