package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.Image
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.xianxia.sect.R
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.LevelType
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.PortraitDiscipleCard
import com.xianxia.sect.ui.components.UnifiedDiscipleSlot
import com.xianxia.sect.ui.components.DiscipleSlotWithActions
import com.xianxia.sect.ui.game.AttributeFilterOption
import com.xianxia.sect.ui.game.ATTRIBUTE_FILTER_OPTIONS
import com.xianxia.sect.ui.game.DiscipleDetailDialog
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.SPIRIT_ROOT_FILTER_OPTIONS
import com.xianxia.sect.ui.game.applyFilters
import com.xianxia.sect.ui.game.components.SpiritRootAttributeFilterBar
import com.xianxia.sect.ui.game.getAttributeValue
import com.xianxia.sect.ui.game.getSpiritRootCount
import com.xianxia.sect.ui.game.tabs.REALM_FILTER_OPTIONS
import com.xianxia.sect.ui.game.map.MapItem
import com.xianxia.sect.ui.theme.ButtonSizes
import com.xianxia.sect.ui.theme.GameColors

@Composable
fun LevelDetailDialog(
    level: MapItem.Level,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    onAttack: (List<String?>) -> Unit,
    onDismiss: () -> Unit
) {
    val slots = remember { mutableStateListOf(*arrayOfNulls<String?>(8)) }
    var targetSlotIndex by remember { mutableIntStateOf(-1) }
    var showDiscipleSelection by remember { mutableStateOf(false) }
    var selectedDiscipleDetail by remember { mutableStateOf<DiscipleAggregate?>(null) }

    // Collect state needed by DiscipleDetailDialog
    val equipment by viewModel.equipment.collectAsState()
    val manuals by viewModel.manuals.collectAsState()
    val manualStacks by viewModel.manualStacks.collectAsState()
    val equipmentStacks by viewModel.equipmentStacks.collectAsState()
    val gameData by viewModel.gameData.collectAsState()

    val imageRes = remember(level) {
        when (level.levelType) {
            LevelType.BEAST -> when (level.beastType ?: 0) {
                0 -> R.drawable.tiger_beast
                1 -> R.drawable.wolf_beast
                2 -> R.drawable.snake_beast
                3 -> R.drawable.bear_beast
                4 -> R.drawable.eagle_beast
                5 -> R.drawable.fox_beast
                6 -> R.drawable.dragon_beast
                else -> R.drawable.turtle_beast
            }
            LevelType.CAVE -> when (level.caveImageIndex) {
                0 -> R.drawable.cave_1
                1 -> R.drawable.cave_2
                else -> R.drawable.cave_3
            }
        }
    }

    val realmDisplayName = remember(level) {
        GameConfig.Realm.getName(level.realm)
    }

    val occupiedCount by remember { derivedStateOf { slots.count { it != null } } }

    // One-click appoint: fill empty slots with highest-realm idle disciples
    val oneClickAppoint: () -> Unit = {
        val idleDisciples = disciples.filter {
            it.isAlive && it.status == DiscipleStatus.IDLE && it.realmLayer > 0 && it.age >= 5
        }.sortedWith(
            compareByDescending<DiscipleAggregate> { it.realm }
                .thenByDescending { it.realmLayer }
        )

        val assignedIds = slots.filterNotNull().toSet()
        val available = idleDisciples.filter { it.id !in assignedIds }

        var idx = 0
        for (i in 0 until 8) {
            if (slots[i] == null && idx < available.size) {
                slots[i] = available[idx].id
                idx++
            }
        }
    }

    val dialogTitle = if (level.levelType == LevelType.CAVE && level.caveName.isNotEmpty()) level.caveName else level.name

    UnifiedGameDialog(onDismissRequest = onDismiss, title = dialogTitle, mode = DialogMode.Half, scrollableContent = false) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ========== Top section: image + info ==========
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(id = imageRes),
                    contentDescription = null,
                    modifier = Modifier.size(120.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    if (level.levelType == LevelType.CAVE && level.caveName.isNotEmpty()) {
                        Text(
                            text = level.caveName,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "守护兽：${level.name}",
                            fontSize = 13.sp,
                            color = Color.Black
                        )
                    } else {
                        Text(
                            text = level.name,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = realmDisplayName,
                        fontSize = 14.sp,
                        color = Color.Black
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "数量：${level.count}",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ========== Middle section: 2x4 slots ==========
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (row in 0 until 2) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                    ) {
                        for (col in 0 until 4) {
                            val slotIndex = row * 4 + col
                            val discipleId = slots[slotIndex]
                            val disciple =
                                if (discipleId != null) disciples.find { it.id == discipleId }
                                else null

                            LevelSlotBox(
                                disciple = disciple,
                                onSlotClick = {
                                    if (disciple != null) {
                                        selectedDiscipleDetail = disciple
                                    } else {
                                        targetSlotIndex = slotIndex
                                        showDiscipleSelection = true
                                    }
                                },
                                onDismiss = {
                                    slots[slotIndex] = null
                                },
                                onSwap = {
                                    targetSlotIndex = slotIndex
                                    showDiscipleSelection = true
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // One-click appoint button
            GameButton(
                text = "一键任命",
                onClick = oneClickAppoint,
                width = ButtonSizes.StandardWidth,
                height = ButtonSizes.StandardHeight,
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ========== Bottom section: action buttons ==========
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                GameButton(
                    text = "进攻",
                    onClick = { onAttack(slots.toList()) },
                    enabled = occupiedCount > 0,
                    width = ButtonSizes.StandardWidth,
                    height = ButtonSizes.StandardHeight,
                    fontSize = 11.sp
                )
                GameButton(
                    text = "关闭",
                    onClick = onDismiss,
                    width = ButtonSizes.StandardWidth,
                    height = ButtonSizes.StandardHeight,
                    fontSize = 11.sp
                )
            }
        }
    }

    // ========== Disciple selection dialog ==========
    if (showDiscipleSelection && targetSlotIndex >= 0) {
        val alreadySelectedIds = slots.filterNotNull().toSet()
        LevelSlotSelectionDialog(
            disciples = disciples,
            alreadySelectedIds = alreadySelectedIds,
            onSelect = { discipleId ->
                slots[targetSlotIndex] = discipleId
                showDiscipleSelection = false
                targetSlotIndex = -1
            },
            onDismiss = {
                showDiscipleSelection = false
                targetSlotIndex = -1
            }
        )
    }

    // ========== Disciple detail dialog ==========
    selectedDiscipleDetail?.let { disciple ->
        val updated = disciples.find { it.id == disciple.id } ?: disciple
        DiscipleDetailDialog(
            disciple = updated,
            allDisciples = disciples,
            gameData = gameData,
            viewModel = viewModel,
            onDismiss = { selectedDiscipleDetail = null }
        )
    }
}

// ==================== Slot Box Component ====================

@Composable
private fun LevelSlotBox(
    disciple: DiscipleAggregate?,
    onSlotClick: () -> Unit,
    onDismiss: () -> Unit,
    onSwap: () -> Unit
) {
    DiscipleSlotWithActions(
        disciple = disciple,
        onSlotClick = { onSlotClick() },
        onEmptySlotClick = { onSwap() },
        onDismiss = { onDismiss() },
        onSwap = { onSwap() }
    )
}

// ==================== Disciple Selection Dialog ====================

@Composable
private fun LevelSlotSelectionDialog(
    disciples: List<DiscipleAggregate>,
    alreadySelectedIds: Set<String> = emptySet(),
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedRealmFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedSpiritRootFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedAttributeSort by remember { mutableStateOf<String?>(null) }
    var spiritRootExpanded by remember { mutableStateOf(false) }
    var attributeExpanded by remember { mutableStateOf(false) }
    var realmExpanded by remember { mutableStateOf(false) }

    val idleDisciples = remember(disciples, alreadySelectedIds) {
        disciples.filter {
            it.isAlive && it.status == DiscipleStatus.IDLE && it.realmLayer > 0 && it.age >= 5 && it.id !in alreadySelectedIds
        }
    }

    val realmCounts = remember(idleDisciples) {
        idleDisciples.groupingBy { it.realm }.eachCount()
    }

    val spiritRootCounts = remember(idleDisciples) {
        idleDisciples.groupingBy { it.getSpiritRootCount() }.eachCount()
    }

    val filteredDisciples = remember(
        idleDisciples,
        selectedRealmFilter,
        selectedSpiritRootFilter,
        selectedAttributeSort
    ) {
        idleDisciples.applyFilters(
            selectedRealmFilter,
            selectedSpiritRootFilter,
            selectedAttributeSort
        )
    }

    UnifiedGameDialog(onDismissRequest = onDismiss, title = "选择弟子", mode = DialogMode.Half, scrollableContent = false,
        headerContent = {
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
                onSpiritRootFilterSelected = {
                    selectedSpiritRootFilter = selectedSpiritRootFilter + it
                },
                onSpiritRootFilterRemoved = {
                    selectedSpiritRootFilter = selectedSpiritRootFilter - it
                },
                onAttributeSortSelected = { selectedAttributeSort = it },
                onRealmFilterSelected = { selectedRealmFilter = selectedRealmFilter + it },
                onRealmFilterRemoved = { selectedRealmFilter = selectedRealmFilter - it },
                onSpiritRootExpandToggle = { spiritRootExpanded = !spiritRootExpanded },
                onAttributeExpandToggle = { attributeExpanded = !attributeExpanded },
                onRealmExpandToggle = { realmExpanded = !realmExpanded },
                isCompact = true
            )
        }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        ) {
            if (idleDisciples.isEmpty()) {
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
                if (filteredDisciples.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无符合条件的弟子",
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filteredDisciples, key = { it.id }) { disciple ->
                            PortraitDiscipleCard(
                                disciple = disciple,
                                isSelected = false,
                                onClick = { onSelect(disciple.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}
