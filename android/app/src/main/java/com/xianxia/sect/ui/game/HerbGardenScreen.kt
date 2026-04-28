package com.xianxia.sect.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
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
import com.xianxia.sect.core.model.ElderSlotType
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.PlantSlotData
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.game.HerbGardenViewModel
import com.xianxia.sect.ui.game.ProductionViewModel
import com.xianxia.sect.core.util.isFollowed
import com.xianxia.sect.core.util.sortedByFollowAttributeAndRealm
import com.xianxia.sect.ui.components.FollowedTag
import com.xianxia.sect.ui.components.HorizontalDiscipleCard
import com.xianxia.sect.ui.components.ElderBonusInfoButton
import com.xianxia.sect.ui.components.ElderBonusInfoProvider
import com.xianxia.sect.ui.theme.getRarityColor
import com.xianxia.sect.ui.game.components.ItemDetailDialog
import com.xianxia.sect.ui.game.components.SpiritRootAttributeFilterBar

@Composable
fun HerbGardenDialog(
    plantSlots: List<PlantSlotData>,
    seeds: List<Seed>,
    gameData: GameData?,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    herbGardenViewModel: HerbGardenViewModel,
    onDismiss: () -> Unit
) {
    var showSeedSelection by remember { mutableStateOf<Int?>(null) }
    var showElderSelection by remember { mutableStateOf(false) }
    var showDirectDiscipleSelection by remember { mutableStateOf<Int?>(null) }
    var showElderRemoveConfirm by remember { mutableStateOf(false) }

    val elderSlots = gameData?.elderSlots ?: ElderSlots()
    val herbGardenElder = productionViewModel.getElderDisciple(elderSlots.herbGardenElder)
    val herbGardenDisciples = elderSlots.herbGardenDisciples
    val hasDirectDisciples = herbGardenDisciples.any { it.isActive }

    CommonDialog(
        title = "灵药宛",
        onDismiss = onDismiss
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            HerbGardenElderSection(
                elder = herbGardenElder,
                onElderClick = { showElderSelection = true },
                onElderRemove = {
                    if (hasDirectDisciples) {
                        showElderRemoveConfirm = true
                    } else {
                        productionViewModel.removeElder(ElderSlotType.HERB_GARDEN)
                    }
                }
            )

            HerbGardenDirectDiscipleSection(
                directDisciples = herbGardenDisciples,
                onDirectDiscipleClick = { index -> showDirectDiscipleSelection = index },
                onDirectDiscipleRemove = { index -> productionViewModel.removeDirectDisciple("herbGarden", index) }
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = GameColors.Border,
                thickness = 1.dp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "种植槽",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF666666)
                )
                val autoPlantEnabled by herbGardenViewModel.autoPlantEnabled.collectAsState()
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (autoPlantEnabled) Color(0xFFFFD700) else Color(0xFF999999))
                        .clickable { herbGardenViewModel.toggleAutoPlant() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (autoPlantEnabled) "自动种植:开" else "自动种植:关",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (autoPlantEnabled) Color.Black else Color.White
                    )
                }
            }

            plantSlots.chunked(3).forEach { rowSlots ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                ) {
                    rowSlots.forEach { slot ->
                        PlantSlotItem(
                            slot = slot,
                            gameData = gameData,
                            onClick = {
                                if (slot.status == "idle" || slot.status == "mature") {
                                    showSeedSelection = slot.index
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    showSeedSelection?.let { slotIndex ->
        SeedPlantingDialog(
            seeds = seeds,
            onSelect = { seed ->
                herbGardenViewModel.plantSeed(slotIndex, seed)
                showSeedSelection = null
            },
            onDismiss = { showSeedSelection = null }
        )
    }

    if (showElderSelection) {
        val currentElderId = elderSlots.herbGardenElder
        HerbGardenElderSelectionDialog(
            disciples = disciples.filter { it.isAlive && it.realm <= 6 },
            currentElderId = currentElderId,
            elderSlots = elderSlots,
            onDismiss = { showElderSelection = false },
            onSelect = { discipleId ->
                productionViewModel.assignElder(ElderSlotType.HERB_GARDEN, discipleId)
                showElderSelection = false
            }
        )
    }

    showDirectDiscipleSelection?.let { slotIndex ->
        HerbGardenDirectDiscipleSelectionDialog(
            disciples = disciples.filter { it.isAlive },
            elderSlots = elderSlots,
            onDismiss = { showDirectDiscipleSelection = null },
            onSelect = { discipleId ->
                productionViewModel.assignDirectDisciple("herbGarden", slotIndex, discipleId)
                showDirectDiscipleSelection = null
            }
        )
    }

    if (showElderRemoveConfirm) {
        ElderRemoveConfirmDialog(
            elderName = herbGardenElder?.name ?: "长老",
            discipleCount = herbGardenDisciples.count { it.isActive },
            onConfirm = {
                productionViewModel.removeElder(ElderSlotType.HERB_GARDEN)
                showElderRemoveConfirm = false
            },
            onDismiss = { showElderRemoveConfirm = false }
        )
    }
}

@Composable
private fun ElderRemoveConfirmDialog(
    elderName: String,
    discipleCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Text(
                text = "确认卸任",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
        },
        text = {
            Column {
                Text(
                    text = "确定要让 $elderName 卸任灵植长老吗？",
                    fontSize = 12.sp,
                    color = Color.Black
                )
                if (discipleCount > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "⚠️ 卸任后将同时移除 $discipleCount 位亲传弟子！",
                        fontSize = 11.sp,
                        color = Color(0xFFE74C3C),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        confirmButton = {
            GameButton(
                text = "确认卸任",
                onClick = onConfirm
            )
        },
        dismissButton = {
            GameButton(
                text = "取消",
                onClick = onDismiss
            )
        }
    )
}

private fun getOccupiedIds(elderSlots: ElderSlots): Pair<List<String>, List<String>> {
    val allElderIds = listOf(
        elderSlots.viceSectMaster,
        elderSlots.herbGardenElder,
        elderSlots.alchemyElder,
        elderSlots.forgeElder,
        elderSlots.outerElder,
        elderSlots.preachingElder,
        elderSlots.lawEnforcementElder,
        elderSlots.innerElder,
        elderSlots.qingyunPreachingElder
    ).filter { !it.isNullOrBlank() }

    val allDirectDiscipleIds = listOf(
        elderSlots.herbGardenDisciples,
        elderSlots.alchemyDisciples,
        elderSlots.forgeDisciples,
        elderSlots.preachingMasters,
        elderSlots.lawEnforcementDisciples,
        elderSlots.lawEnforcementReserveDisciples,
        elderSlots.qingyunPreachingMasters,
        elderSlots.spiritMineDeaconDisciples
    ).flatten().mapNotNull { it.discipleId }

    return Pair(allElderIds, allDirectDiscipleIds)
}

@Composable
private fun HerbGardenElderSection(
    elder: DiscipleAggregate?,
    onElderClick: () -> Unit,
    onElderRemove: () -> Unit
) {
    val elderBorderColor = if (elder != null) {
        try {
            Color(android.graphics.Color.parseColor(elder.spiritRoot.countColor))
        } catch (e: Exception) {
            Color(0xFF4CAF50)
        }
    } else {
        GameColors.Border
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "灵植长老",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF666666)
            )
            ElderBonusInfoButton(bonusInfo = ElderBonusInfoProvider.getHerbGardenElderInfo())
        }

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
                            text = "灵植: ${elder.spiritPlanting}",
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
private fun HerbGardenDirectDiscipleSection(
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
                HerbGardenDirectDiscipleSlotItem(
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
private fun HerbGardenDirectDiscipleSlotItem(
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
private fun HerbGardenElderSelectionDialog(
    disciples: List<DiscipleAggregate>,
    currentElderId: String?,
    elderSlots: ElderSlots,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
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

    val (allElderIds, allDirectDiscipleIds) = remember(elderSlots) { getOccupiedIds(elderSlots) }

    val filteredDisciplesBase = remember(disciples, allElderIds, allDirectDiscipleIds) {
        disciples.filter {
            it.realmLayer > 0 &&
            it.age >= 5 &&
            it.status == DiscipleStatus.IDLE &&
            it.discipleType == "inner" &&
            it.realm <= 6 &&
            it.id !in allElderIds &&
            it.id !in allDirectDiscipleIds
        }
    }

    val realmCounts = remember(filteredDisciplesBase) {
        filteredDisciplesBase.groupingBy { it.realm }.eachCount()
    }

    val spiritRootCounts = remember(filteredDisciplesBase) {
        filteredDisciplesBase.groupingBy { it.getSpiritRootCount() }.eachCount()
    }

    val sortedDisciples = remember(filteredDisciplesBase) {
        filteredDisciplesBase.sortedByFollowAttributeAndRealm("spiritPlanting")
    }

    val filteredDisciples = remember(sortedDisciples, selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort) {
        sortedDisciples.applyFilters(selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort, "spiritPlanting")
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
                    text = "选择灵植长老",
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
                    text = "推荐属性: 灵植",
                    fontSize = 10.sp,
                    color = Color(0xFF999999),
                    modifier = Modifier.padding(bottom = 8.dp)
                )

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
                    realmFilters.chunked(4).forEach { chunk ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            chunk.forEach { (realmVal, name) ->
                                val isSelected = realmVal in selectedRealmFilter
                                val count = realmCounts[realmVal] ?: 0
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (isSelected) GameColors.Gold.copy(alpha = 0.3f) else GameColors.PageBackground)
                                        .border(1.dp, if (isSelected) GameColors.Gold else GameColors.Border, RoundedCornerShape(4.dp))
                                        .clickable { selectedRealmFilter = if (isSelected) selectedRealmFilter - realmVal else selectedRealmFilter + realmVal }
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
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(
                            items = filteredDisciples,
                            key = { it.id }
                        ) { disciple ->
                            HerbGardenDiscipleSelectionCard(
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
private fun HerbGardenDiscipleSelectionCard(
    disciple: DiscipleAggregate,
    onClick: () -> Unit
) {
    HorizontalDiscipleCard(
        disciple = disciple,
        extraAttributes = listOf("灵植" to disciple.spiritPlanting),
        onClick = onClick
    )
}

@Composable
private fun HerbGardenDirectDiscipleSelectionDialog(
    disciples: List<DiscipleAggregate>,
    elderSlots: ElderSlots,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
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

    val (allElderIds, allDirectDiscipleIds) = remember(elderSlots) { getOccupiedIds(elderSlots) }

    val filteredDisciplesBase = remember(disciples, allElderIds, allDirectDiscipleIds) {
        disciples.filter {
            it.realmLayer > 0 &&
            it.age >= 5 &&
            it.status == DiscipleStatus.IDLE &&
            it.discipleType == "inner" &&
            it.id !in allElderIds &&
            it.id !in allDirectDiscipleIds
        }
    }

    val realmCounts = remember(filteredDisciplesBase) {
        filteredDisciplesBase.groupingBy { it.realm }.eachCount()
    }

    val spiritRootCounts = remember(filteredDisciplesBase) {
        filteredDisciplesBase.groupingBy { it.getSpiritRootCount() }.eachCount()
    }

    val sortedDisciples = remember(filteredDisciplesBase) {
        filteredDisciplesBase.sortedByFollowAttributeAndRealm("spiritPlanting")
    }

    val filteredDisciples = remember(sortedDisciples, selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort) {
        sortedDisciples.applyFilters(selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort, "spiritPlanting")
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
                    text = "推荐属性: 灵植",
                    fontSize = 10.sp,
                    color = Color(0xFF999999),
                    modifier = Modifier.padding(bottom = 8.dp)
                )

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
                    realmFilters.chunked(4).forEach { chunk ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            chunk.forEach { (realmVal, name) ->
                                val isSelected = realmVal in selectedRealmFilter
                                val count = realmCounts[realmVal] ?: 0
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (isSelected) GameColors.Gold.copy(alpha = 0.3f) else GameColors.PageBackground)
                                        .border(1.dp, if (isSelected) GameColors.Gold else GameColors.Border, RoundedCornerShape(4.dp))
                                        .clickable { selectedRealmFilter = if (isSelected) selectedRealmFilter - realmVal else selectedRealmFilter + realmVal }
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
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(
                            items = filteredDisciples,
                            key = { it.id }
                        ) { disciple ->
                            HerbGardenDiscipleSelectionCard(
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
private fun PlantSlotItem(
    slot: PlantSlotData,
    gameData: GameData?,
    onClick: () -> Unit
) {
    val statusColor = when (slot.status) {
        "idle", "mature" -> Color(0xFF999999)
        "growing" -> Color(0xFF4CAF50)
        else -> Color(0xFF999999)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "种植槽 ${slot.index + 1}",
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
                .clickable {
                    if (slot.status == "idle" || slot.status == "mature") onClick()
                },
            contentAlignment = Alignment.Center
        ) {
            when (slot.status) {
                "growing" -> {
                    val remainingMonths = if (gameData != null) {
                        slot.remainingTime(gameData.gameYear, gameData.gameMonth)
                    } else 0
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = slot.seedName.ifEmpty { "未知" },
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
    }
}

@Composable
private fun SeedPlantingDialog(
    seeds: List<Seed>,
    onSelect: (Seed) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedSeed by remember { mutableStateOf<Seed?>(null) }
    var clickedSeed by remember { mutableStateOf<Seed?>(null) }
    var showDetail by remember { mutableStateOf(false) }

    if (showDetail) {
        clickedSeed?.let { seed ->
            ItemDetailDialog(
                item = seed,
                onDismiss = { showDetail = false }
            )
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
                    text = "选择种子",
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
            if (seeds.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无种子",
                        fontSize = 12.sp,
                        color = Color(0xFF999999)
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    gridItems(seeds) { seed ->
                        SeedSelectionCard(
                            seed = seed,
                            isSelected = selectedSeed?.id == seed.id,
                            isClicked = clickedSeed?.id == seed.id,
                            onSelect = {
                                if (selectedSeed?.id == seed.id) {
                                    selectedSeed = null
                                    clickedSeed = null
                                } else {
                                    selectedSeed = seed
                                    clickedSeed = seed
                                }
                            },
                            onViewDetail = {
                                clickedSeed = seed
                                showDetail = true
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            GameButton(
                text = "确认种植",
                onClick = {
                    selectedSeed?.let { seed ->
                        onSelect(seed)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedSeed != null
            )
        }
    )
}

@Composable
private fun SeedSelectionCard(
    seed: Seed,
    isSelected: Boolean,
    isClicked: Boolean,
    onSelect: () -> Unit,
    onViewDetail: () -> Unit
) {
    val rarityColor = getRarityColor(seed.rarity)

    Box(
        modifier = Modifier.size(68.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(6.dp))
                .background(GameColors.PageBackground)
                .border(
                    width = if (isSelected) 3.dp else 2.dp,
                    color = if (isSelected) GameColors.SelectedBorder else rarityColor,
                    shape = RoundedCornerShape(6.dp)
                )
                .clickable { onSelect() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = seed.name,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = GameColors.TextPrimary,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            if (seed.quantity > 1) {
                Text(
                    text = "x${seed.quantity}",
                    fontSize = 9.sp,
                    color = GameColors.TextSecondary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                )
            }
        }

        if (isClicked) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .size(22.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(GameColors.SelectedBorder)
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        onClick = { onViewDetail() }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "查看",
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun CommonDialog(
    title: String,
    onDismiss: () -> Unit,
    titleActions: @Composable RowScope.() -> Unit = {},
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    titleActions()
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
                    .verticalScroll(rememberScrollState())
            ) {
                content()
            }
        },
        confirmButton = {}
    )
}
