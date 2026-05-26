package com.xianxia.sect.ui.game.dialogs

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
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.ProductionViewModel
import com.xianxia.sect.ui.game.ProductionTheme
import com.xianxia.sect.ui.game.ProductionElderSelectionDialog
import com.xianxia.sect.ui.game.ProductionDirectDiscipleSelectionDialog
import com.xianxia.sect.ui.game.DiscipleDetailRequest
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorConfig
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorDialog
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.ElderBonusInfoButton
import com.xianxia.sect.ui.components.ElderBonusInfoProvider
import com.xianxia.sect.ui.components.FollowedTag
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.PortraitDiscipleCard
import com.xianxia.sect.ui.components.UnifiedDiscipleSlot
import com.xianxia.sect.ui.components.DiscipleSlotWithActions
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

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "执法堂",
        mode = DialogMode.Half,
        scrollableContent = false,
        headerActions = {
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
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "维护宗门纪律，执行门规",
                    fontSize = 10.sp,
                    color = Color(0xFFE74C3C),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    textAlign = TextAlign.Center
                )

                LawElderSection(
                    elder = lawElder,
                    onElderClick = { lawElder?.let { viewModel.showDiscipleDetail(DiscipleDetailRequest(it, disciples)) } },
                    onElderRemove = { productionViewModel.removeElder(ElderSlotType.LAW_ENFORCEMENT) },
                    onElderSwap = { showElderSelection = true }
                )

                LawDisciplesSection(
                    lawDisciples = lawDisciples,
                    disciples = disciples,
                    onDiscipleClick = { index ->
                        val slot = lawDisciples.find { it.index == index }
                        val d = if (slot != null && slot.isActive) disciples.find { it.id == slot.discipleId } else null
                        d?.let { viewModel.showDiscipleDetail(DiscipleDetailRequest(it, disciples)) }
                    },
                    onDiscipleRemove = { index -> productionViewModel.removeDirectDisciple("lawEnforcement", index) },
                    onDiscipleSwap = { index -> showDiscipleSelection = index }
                )
            }
        }
    }

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
    onElderRemove: () -> Unit,
    onElderSwap: () -> Unit = {}
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
            onRemove = onElderRemove,
            onSwap = onElderSwap
        )
    }
}

@Composable
private fun LawDisciplesSection(
    lawDisciples: List<DirectDiscipleSlot>,
    disciples: List<DiscipleAggregate>,
    onDiscipleClick: (Int) -> Unit,
    onDiscipleRemove: (Int) -> Unit,
    onDiscipleSwap: (Int) -> Unit = {}
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
                val slot = lawDisciples.find { it.index == index }
                val disciple = if (slot != null && slot.isActive) disciples.find { it.id == slot.discipleId } else null
                val spiritRootColor = slot?.discipleSpiritRootColor ?: ""
                LawDiscipleSlotItem(
                    disciple = disciple,
                    isActive = slot?.isActive == true,
                    spiritRootColor = spiritRootColor,
                    onClick = { onDiscipleClick(index) },
                    onRemove = { onDiscipleRemove(index) },
                    onSwap = { onDiscipleSwap(index) }
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            (4..7).forEach { index ->
                val slot = lawDisciples.find { it.index == index }
                val disciple = if (slot != null && slot.isActive) disciples.find { it.id == slot.discipleId } else null
                val spiritRootColor = slot?.discipleSpiritRootColor ?: ""
                LawDiscipleSlotItem(
                    disciple = disciple,
                    isActive = slot?.isActive == true,
                    spiritRootColor = spiritRootColor,
                    onClick = { onDiscipleClick(index) },
                    onRemove = { onDiscipleRemove(index) },
                    onSwap = { onDiscipleSwap(index) }
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
    onRemove: () -> Unit,
    onSwap: () -> Unit = {}
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

        DiscipleSlotWithActions(
            disciple = elder,
            borderColor = borderColor,
            onSlotClick = { onClick() },
            onEmptySlotClick = { onSwap() },
            onDismiss = { onRemove() },
            onSwap = { onSwap() }
        )
    }
}

@Composable
private fun LawDiscipleSlotItem(
    disciple: DiscipleAggregate?,
    isActive: Boolean,
    spiritRootColor: String,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onSwap: () -> Unit = {}
) {
    val borderColor = if (isActive) {
        try {
            Color(android.graphics.Color.parseColor(spiritRootColor))
        } catch (e: Exception) {
            Color(0xFFE74C3C)
        }
    } else {
        GameColors.Border
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "执法弟子",
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Spacer(modifier = Modifier.height(2.dp))

        DiscipleSlotWithActions(
            disciple = if (isActive) disciple else null,
            borderColor = borderColor,
            onSlotClick = { onClick() },
            onEmptySlotClick = { onSwap() },
            onDismiss = { onRemove() },
            onSwap = { onSwap() }
        )
    }
}

@Composable
private fun CommonDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = title,
        mode = DialogMode.Half,
        scrollableContent = false,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)
            ) {
                content()
            }
        }
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

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "储备弟子 (${sortedReserveDisciples.size})",
        mode = DialogMode.Half,
        scrollableContent = false,
        headerActions = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("推荐智力", fontSize = 10.sp, color = Color(0xFF4CAF50))
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
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "执法弟子空缺时自动补位",
                fontSize = 9.sp,
                color = Color.Black,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                textAlign = TextAlign.Center
            )
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
                        color = Color.Black
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(sortedReserveDisciples, key = { it.id }) { disciple ->
                        PortraitDiscipleCard(
                            disciple = disciple,
                            actions = {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(GameColors.PageBackground)
                                        .border(1.dp, GameColors.Border, RoundedCornerShape(4.dp))
                                        .clickable { productionViewModel.removeReserveDisciple(disciple.id) }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(text = "移除", fontSize = 10.sp, color = Color.Black)
                                }
                            },
                            onClick = {}
                        )
                    }
                }
            }
        }
    }

    if (showAddDiscipleDialog) {
        val rawDisciples = remember(allDisciples, elderSlots) {
            allDisciples.filter {
                it.isAlive && it.realmLayer > 0 && it.age >= 5 &&
                it.status == DiscipleStatus.IDLE && it.discipleType == "inner" &&
                !elderSlots.isDiscipleInAnyPosition(it.id)
            }
        }
        DiscipleSelectorDialog(
            config = DiscipleSelectorConfig(title = "内门弟子"),
            disciples = rawDisciples,
            onDismiss = { showAddDiscipleDialog = false },
            onConfirm = { selected ->
                productionViewModel.addReserveDisciples(selected.map { it.id })
                showAddDiscipleDialog = false
            }
        )
    }
}
