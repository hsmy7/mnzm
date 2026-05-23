package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.CaveStatus
import com.xianxia.sect.core.model.CultivatorCave
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.util.isFollowed
import com.xianxia.sect.core.util.sortedByFollowAndRealm
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.PortraitDiscipleCard
import com.xianxia.sect.ui.game.components.SpiritRootAttributeFilterBar
import com.xianxia.sect.ui.game.ATTRIBUTE_FILTER_OPTIONS
import com.xianxia.sect.ui.game.AttributeFilterOption
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.SPIRIT_ROOT_FILTER_OPTIONS
import com.xianxia.sect.ui.game.WorldMapViewModel
import com.xianxia.sect.ui.game.applyFilters
import com.xianxia.sect.ui.game.getAttributeValue
import com.xianxia.sect.ui.game.getSpiritRootCount
import com.xianxia.sect.ui.game.tabs.REALM_FILTER_OPTIONS
import com.xianxia.sect.ui.theme.GameColors

@Composable
internal fun CaveDetailDialog(
    cave: CultivatorCave,
    gameData: GameData?,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    worldMapViewModel: WorldMapViewModel,
    onDismiss: () -> Unit
) {
    val currentYear = gameData?.gameYear ?: 1
    val currentMonth = gameData?.gameMonth ?: 1
    val remainingMonths = cave.getRemainingMonths(currentYear, currentMonth)

    var showDiscipleSelection by remember { mutableStateOf(false) }
    var selectedDisciples by remember { mutableStateOf<List<DiscipleAggregate>>(emptyList()) }

    val statusColor = when (cave.status) {
        CaveStatus.AVAILABLE -> Color(0xFF9C27B0)
        CaveStatus.EXPLORING -> Color(0xFFFF9800)
        CaveStatus.EXPLORED -> Color(0xFF4CAF50)
        CaveStatus.EXPIRED -> Color(0xFF9E9E9E)
    }

    val statusText = when (cave.status) {
        CaveStatus.AVAILABLE -> "可探索"
        CaveStatus.EXPLORING -> "探索中"
        CaveStatus.EXPLORED -> "已探索"
        CaveStatus.EXPIRED -> "已消失"
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
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = cave.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Text(
                        text = statusText,
                        fontSize = 10.sp,
                        color = Color.White,
                        modifier = Modifier
                            .background(statusColor, RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
                CloseButton(onClick = onDismiss)
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
                    Column {
                        Text(
                            text = "洞府境界",
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                        Text(
                            text = cave.ownerRealmName,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }

                    if (cave.status != CaveStatus.EXPIRED) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "剩余时间",
                                fontSize = 12.sp,
                                color = Color.Black
                            )
                            Text(
                                text = "${remainingMonths}月",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (remainingMonths <= 3) Color(0xFFF44336) else Color.Black
                            )
                        }
                    }
                }

                HorizontalDivider(color = GameColors.Border, thickness = 1.dp)

                when (cave.status) {
                    CaveStatus.AVAILABLE -> {
                        Text(
                            text = "此洞府尚未被探索，派遣弟子前往探索可获得丰厚奖励。",
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                        if (selectedDisciples.isNotEmpty()) {
                            Text(
                                text = "已选择 ${selectedDisciples.size}/10 人: ${selectedDisciples.joinToString("、") { it.name }}",
                                fontSize = 11.sp,
                                color = Color(0xFF4CAF50)
                            )
                        }
                    }
                    CaveStatus.EXPLORING -> {
                        val exploringTeam = gameData?.caveExplorationTeams?.find { it.caveId == cave.id }
                        if (exploringTeam != null) {
                            val progress = exploringTeam.getProgressPercent(currentYear, currentMonth)
                            Column {
                                Text(
                                    text = "探索队伍: ${exploringTeam.memberNames.joinToString("、")}",
                                    fontSize = 12.sp,
                                    color = Color.Black
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    LinearProgressIndicator(
                                        progress = { progress / 100f },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(4.dp)),
                                        color = Color(0xFF4CAF50),
                                        trackColor = GameColors.Border
                                    )
                                    Text(
                                        text = "$progress%",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF4CAF50)
                                    )
                                }
                            }
                        }
                    }
                    CaveStatus.EXPLORED -> {
                        Text(
                            text = "此洞府已被探索完毕。",
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                    }
                    CaveStatus.EXPIRED -> {
                        Text(
                            text = "此洞府已经消失，无法再进行探索。",
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (cave.status == CaveStatus.AVAILABLE) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (selectedDisciples.isNotEmpty()) {
                        GameButton(
                            text = "确认派遣",
                            onClick = {
                                worldMapViewModel.startCaveExploration(cave, selectedDisciples)
                                onDismiss()
                            }
                        )
                    }
                    GameButton(
                        text = if (selectedDisciples.isEmpty()) "选择弟子" else "修改选择",
                        onClick = { showDiscipleSelection = true }
                    )
                }
            }
        },
        dismissButton = null
    )

    if (showDiscipleSelection) {
        CaveDiscipleSelectionDialog(
            disciples = disciples,
            selectedDisciples = selectedDisciples,
            maxSelection = 10,
            caveRealm = cave.ownerRealm,
            onConfirm = {
                selectedDisciples = it
                showDiscipleSelection = false
            },
            onDismiss = { showDiscipleSelection = false }
        )
    }
}

@Composable
internal fun CaveDiscipleSelectionDialog(
    disciples: List<DiscipleAggregate>,
    selectedDisciples: List<DiscipleAggregate>,
    maxSelection: Int,
    caveRealm: Int,
    onConfirm: (List<DiscipleAggregate>) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedRealmFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedSpiritRootFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedAttributeSort by remember { mutableStateOf<String?>(null) }
    var spiritRootExpanded by remember { mutableStateOf(false) }
    var attributeExpanded by remember { mutableStateOf(false) }
    var realmExpanded by remember { mutableStateOf(false) }
    var currentSelected by remember(selectedDisciples) { @Suppress("MutableCollectionMutableState") mutableStateOf(selectedDisciples.toMutableList()) }

    val availableDisciples = remember(disciples, caveRealm, selectedDisciples) {
        val selectedIds = selectedDisciples.map { it.id }.toSet()
        disciples.filter { disciple ->
            disciple.isAlive &&
            disciple.status == DiscipleStatus.IDLE &&
            disciple.realmLayer > 0 &&
            disciple.age >= 5 &&
            disciple.realm <= caveRealm &&
            disciple.id !in selectedIds
        }.sortedByFollowAndRealm()
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

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "选择探索弟子 (${currentSelected.size}/$maxSelection)",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        Column(Modifier.fillMaxSize()) {
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
                            val isSelected = disciple.id in currentSelected.map { it.id }
                            PortraitDiscipleCard(
                                disciple = disciple,
                                isSelected = isSelected,
                                onClick = {
                                    if (isSelected) {
                                        currentSelected = currentSelected.filter { it.id != disciple.id }.toMutableList()
                                    } else if (currentSelected.size < maxSelection) {
                                        currentSelected = (currentSelected + disciple).toMutableList()
                                    }
                                }
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                GameButton(
                    text = "清空",
                    onClick = { currentSelected = mutableListOf() }
                )
                GameButton(
                    text = "确认",
                    onClick = { onConfirm(currentSelected) }
                )
            }
        }
    }
}
