package com.xianxia.sect.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.*
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.components.ElderBonusInfoButton
import com.xianxia.sect.ui.components.ElderBonusInfo
import com.xianxia.sect.ui.components.DialogDefaults
import com.xianxia.sect.ui.components.PortraitDiscipleCard
import com.xianxia.sect.ui.components.DiscipleSlot
import com.xianxia.sect.ui.game.components.SpiritRootAttributeFilterBar
import kotlinx.coroutines.launch

data class PeakElderSlotConfig(
    val title: String,
    val elder: DiscipleAggregate?,
    val bonusInfo: ElderBonusInfo,
    val onClick: () -> Unit,
    val onRemove: () -> Unit,
    val onSwap: () -> Unit = {}
)

data class PeakPreachingMasterConfig(
    val label: String,
    val bonusInfo: ElderBonusInfo
)

@Composable
fun PeakElderSection(
    slot1: PeakElderSlotConfig,
    slot2: PeakElderSlotConfig
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
            text = "长老",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PeakElderSlotItem(config = slot1)
            PeakElderSlotItem(config = slot2)
        }
    }
}

@Composable
private fun PeakElderSlotItem(config: PeakElderSlotConfig) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = config.title,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            ElderBonusInfoButton(bonusInfo = config.bonusInfo)
        }
        Spacer(modifier = Modifier.height(4.dp))

        val borderColor = if (config.elder != null) {
            try {
                Color(android.graphics.Color.parseColor(config.elder.spiritRoot.countColor))
            } catch (e: Exception) {
                Color(0xFFE0E0E0)
            }
        } else {
            GameColors.Border
        }

        DiscipleSlot(
            disciple = config.elder,
            borderColor = borderColor,
            showActions = true,
            onSlotClick = { config.onClick() },
            onEmptySlotClick = { config.onSwap() },
            onDismiss = { config.onRemove() },
            onSwap = { config.onSwap() }
        )
    }
}

@Composable
fun PeakPreachingMasterSection(
    sectionTitle: String,
    masterConfig: PeakPreachingMasterConfig,
    preachingMasters: List<DirectDiscipleSlot>,
    disciples: List<DiscipleAggregate>,
    onMasterClick: (Int) -> Unit,
    onMasterRemove: (Int) -> Unit,
    onMasterSwap: (Int) -> Unit = {}
) {
    val discipleMap = disciples.associateBy { it.id }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(GameColors.CardBackground)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = sectionTitle,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            (0..3).forEach { index ->
                val master = preachingMasters.find { it.index == index }
                val agg = if (master?.isActive == true) discipleMap[master.discipleId] else null
                val spiritRootColor = master?.discipleSpiritRootColor ?: ""
                PeakPreachingMasterSlotItem(
                    disciple = agg,
                    isActive = master?.isActive == true,
                    spiritRootColor = spiritRootColor,
                    config = masterConfig,
                    onClick = { onMasterClick(index) },
                    onRemove = { onMasterRemove(index) },
                    onSwap = { onMasterSwap(index) }
                )
            }
        }
    }
}

@Composable
private fun PeakPreachingMasterSlotItem(
    disciple: DiscipleAggregate?,
    isActive: Boolean,
    spiritRootColor: String,
    config: PeakPreachingMasterConfig,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onSwap: () -> Unit = {}
) {
    val borderColor = if (isActive) {
        try {
            Color(android.graphics.Color.parseColor(spiritRootColor))
        } catch (e: Exception) {
            Color(0xFF9C27B0)
        }
    } else {
        GameColors.Border
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = config.label,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            ElderBonusInfoButton(bonusInfo = config.bonusInfo)
        }
        Spacer(modifier = Modifier.height(2.dp))

        DiscipleSlot(
            disciple = if (isActive) disciple else null,
            borderColor = borderColor,
            showActions = true,
            onSlotClick = { onClick() },
            onEmptySlotClick = { onSwap() },
            onDismiss = { onRemove() },
            onSwap = { onSwap() }
        )
    }
}

@Composable
fun PeakDiscipleSelectionDialog(
    title: String,
    disciples: List<DiscipleAggregate>,
    currentDiscipleId: String?,
    requirementText: String,
    viewModel: GameViewModel,
    onSelect: (DiscipleAggregate) -> Unit,
    onDismiss: () -> Unit,
    defaultSortAttribute: String? = null
) {
    var selectedRealmFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedSpiritRootFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedAttributeSort by remember { mutableStateOf<String?>(null) }
    var spiritRootExpanded by remember { mutableStateOf(false) }
    var attributeExpanded by remember { mutableStateOf(false) }
    var realmExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val showAllEnabled = viewModel.showAllAvailableDisciplesSnapshot

    val battleAndExplorationIds = viewModel.battleAndExplorationIdsSnapshot

    val baseDisciples = remember(disciples, showAllEnabled, battleAndExplorationIds) {
        disciples.filterByDiscipleStatus(showAllEnabled, battleAndExplorationIds, additionalCheck = { d ->
            d.realmLayer > 0
        })
    }

    val realmCounts = remember(baseDisciples) {
        baseDisciples.groupingBy { it.realm }.eachCount()
    }

    val spiritRootCounts = remember(baseDisciples) {
        baseDisciples.groupingBy { it.getSpiritRootCount() }.eachCount()
    }

    val filteredDisciples = remember(baseDisciples, selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort, defaultSortAttribute) {
        baseDisciples.applyFilters(selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort, defaultSortAttribute)
    }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = title,
        mode = DialogMode.Half,
        scrollableContent = false,
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
                onSpiritRootFilterSelected = { selectedSpiritRootFilter = selectedSpiritRootFilter + it },
                onSpiritRootFilterRemoved = { selectedSpiritRootFilter = selectedSpiritRootFilter - it },
                onAttributeSortSelected = { selectedAttributeSort = it },
                onRealmFilterSelected = { selectedRealmFilter = selectedRealmFilter + it },
                onRealmFilterRemoved = { selectedRealmFilter = selectedRealmFilter - it },
                onSpiritRootExpandToggle = { spiritRootExpanded = !spiritRootExpanded },
                onAttributeExpandToggle = { attributeExpanded = !attributeExpanded },
                onRealmExpandToggle = { realmExpanded = !realmExpanded },
                isCompact = true,
                showAllCheckboxVisible = true,
                showAllEnabled = showAllEnabled,
                onShowAllToggle = { viewModel.setShowAllAvailableDisciples(!showAllEnabled) }
            )
        }
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Column(Modifier.fillMaxWidth().heightIn(max = DialogDefaults.CommonMaxHeight)) {
                    if (filteredDisciples.isEmpty()) {
                        Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "暂无符合条件的弟子", fontSize = 12.sp, color = Color.Black)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = requirementText, fontSize = 10.sp, color = Color.Black)
                        }
                    } else {
                        LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(filteredDisciples, key = { it.id }, contentType = { "disciple" }) { disciple ->
                                val isCurrent = disciple.id == currentDiscipleId
                                PortraitDiscipleCard(
                                    disciple = disciple,
                                    isCurrent = isCurrent,
                                    onClick = {
                                        scope.launch {
                                            if (showAllEnabled && disciple.status != DiscipleStatus.IDLE) {
                                                viewModel.releaseDiscipleForReassignment(disciple.id)
                                            }
                                            onSelect(disciple)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
}
