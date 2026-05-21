package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
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
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.theme.ButtonSizes
import com.xianxia.sect.ui.game.HerbGardenViewModel
import com.xianxia.sect.ui.game.ProductionViewModel
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.HERB_GARDEN_THEME
import com.xianxia.sect.ui.game.ProductionElderSelectionDialog
import com.xianxia.sect.ui.game.ProductionDirectDiscipleSelectionDialog
import com.xianxia.sect.ui.game.DiscipleDetailDialog
import com.xianxia.sect.ui.game.ProductionSlotItem
import com.xianxia.sect.core.util.isFollowed
import com.xianxia.sect.core.util.sortedByFollowAttributeAndRealm
import com.xianxia.sect.ui.components.FollowedTag
import com.xianxia.sect.ui.components.ElderBonusInfoButton
import com.xianxia.sect.ui.components.ElderBonusInfoProvider
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.UnifiedDiscipleSlot
import com.xianxia.sect.ui.components.DiscipleSlotWithActions
import com.xianxia.sect.ui.game.components.ItemDetailDialog

@Composable
fun HerbGardenDialog(
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
    var selectedDiscipleDetail by remember { mutableStateOf<DiscipleAggregate?>(null) }
    var replaceSlotIndex by remember { mutableStateOf<Int?>(null) }

    val elderSlots = gameData?.elderSlots ?: ElderSlots()
    val herbGardenElder = disciples.find { it.id == elderSlots.herbGardenElder }
    val herbGardenDisciples = elderSlots.herbGardenDisciples
    val hasDirectDisciples = herbGardenDisciples.any { it.isActive }

    val plantSlots by viewModel.plantSlots.collectAsState()

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "灵植阁",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                HerbGardenElderSection(
                    elder = herbGardenElder,
                    onSlotClick = { selectedDiscipleDetail = herbGardenElder },
                    onElderRemove = {
                        if (hasDirectDisciples) {
                            showElderRemoveConfirm = true
                        } else {
                            productionViewModel.removeElder(ElderSlotType.HERB_GARDEN)
                        }
                    },
                    onSwap = { showElderSelection = true }
                )

                HerbGardenDirectDiscipleSection(
                    directDisciples = herbGardenDisciples,
                    disciples = disciples,
                    onDirectDiscipleClick = { index ->
                        val slot = herbGardenDisciples.getOrNull(index)
                        val d = if (slot?.isActive == true) disciples.find { it.id == slot.discipleId } else null
                        selectedDiscipleDetail = d
                    },
                    onDirectDiscipleRemove = { index -> productionViewModel.removeDirectDisciple("herbGarden", index) },
                    onDirectDiscipleSwap = { index -> showDirectDiscipleSelection = index }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = GameColors.Border,
                    thickness = 1.dp
                )

                plantSlots.sortedBy { it.slotIndex }.forEach { slot ->
                    val isWorking = slot.status == ProductionSlotStatus.WORKING
                    val isIdle = slot.status == ProductionSlotStatus.IDLE
                    val remainingMonths = if (isWorking && gameData != null)
                        slot.remainingTime(gameData.gameYear, gameData.gameMonth) else 0
                    val seedRarity = seeds.find { it.name == slot.recipeName }?.rarity ?: 1

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val autoEnabled = slot.autoRestartEnabled
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (autoEnabled) Color(0xFFFFD700) else Color.Black)
                                .clickable { herbGardenViewModel.toggleAuto(slot.slotIndex) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (autoEnabled) "自动种植:开" else "自动种植:关",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (autoEnabled) Color.Black else Color.White
                            )
                        }
                    }

                    ProductionSlotItem(
                        theme = HERB_GARDEN_THEME,
                        productName = slot.recipeName.ifEmpty { null },
                        isWorking = isWorking,
                        isIdle = isIdle,
                        remainingMonths = remainingMonths,
                        index = slot.slotIndex,
                        productRarity = seedRarity,
                        totalDuration = slot.duration,
                        onCancel = if (isWorking) { { herbGardenViewModel.cancelPlantSlot(slot.slotIndex) } } else null,
                        onReplace = if (isWorking) { {
                            replaceSlotIndex = slot.slotIndex
                            showSeedSelection = slot.slotIndex
                        } } else null,
                        onClick = {
                            if (isIdle || slot.status == ProductionSlotStatus.COMPLETED) {
                                showSeedSelection = slot.slotIndex
                            }
                        }
                    )
                }
            }
        }
    }

    showSeedSelection?.let { slotIndex ->
        val isReplacing = replaceSlotIndex != null
        SeedPlantingDialog(
            seeds = seeds,
            onDismiss = {
                showSeedSelection = null
                replaceSlotIndex = null
            },
            onConfirmOverride = if (isReplacing) { { seed ->
                herbGardenViewModel.cancelPlantSlot(slotIndex)
                herbGardenViewModel.plantSeed(slotIndex, seed)
            } } else null,
            onSelect = if (!isReplacing) { { seed ->
                herbGardenViewModel.plantSeed(slotIndex, seed)
            } } else null
        )
    }

    if (showElderSelection) {
        val currentElderId = elderSlots.herbGardenElder
        ProductionElderSelectionDialog(
            theme = HERB_GARDEN_THEME,
            disciples = disciples.filter { it.isAlive },
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
        ProductionDirectDiscipleSelectionDialog(
            theme = HERB_GARDEN_THEME,
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

    selectedDiscipleDetail?.let { disciple ->
        DiscipleDetailDialog(
            disciple = disciple,
            allDisciples = disciples,
            gameData = gameData,
            viewModel = viewModel,
            onDismiss = { selectedDiscipleDetail = null }
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
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "确认卸任",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)
            ) {
                Spacer(modifier = Modifier.height(12.dp))
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
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GameButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.width(ButtonSizes.StandardWidth)
                    )
                    GameButton(
                        text = "确认卸任",
                        onClick = onConfirm,
                        modifier = Modifier.width(ButtonSizes.StandardWidth)
                    )
                }
            }
        }
    }
}

@Composable
private fun HerbGardenElderSection(
    elder: DiscipleAggregate?,
    onSlotClick: () -> Unit,
    onElderRemove: () -> Unit,
    onSwap: () -> Unit = {}
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
                color = Color.Black
            )
            ElderBonusInfoButton(bonusInfo = ElderBonusInfoProvider.getHerbGardenElderInfo())
        }

        DiscipleSlotWithActions(
            disciple = elder,
            borderColor = elderBorderColor,
            onSlotClick = { onSlotClick() },
            onEmptySlotClick = { onSwap() },
            onDismiss = { onElderRemove() },
            onSwap = { onSwap() }
        )
    }
}

@Composable
private fun HerbGardenDirectDiscipleSection(
    directDisciples: List<DirectDiscipleSlot>,
    disciples: List<DiscipleAggregate>,
    onDirectDiscipleClick: (Int) -> Unit,
    onDirectDiscipleRemove: (Int) -> Unit,
    onDirectDiscipleSwap: (Int) -> Unit = {}
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "亲传弟子",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        ) {
            (0 until 3).forEach { index ->
                val slot = directDisciples.getOrNull(index) ?: DirectDiscipleSlot(index = index)
                val agg = if (slot.isActive) disciples.find { it.id == slot.discipleId } else null
                val borderColor = if (slot.isActive) {
                    try { Color(android.graphics.Color.parseColor(agg?.spiritRoot?.countColor)) }
                    catch (e: Exception) { Color(0xFF4CAF50) }
                } else {
                    GameColors.Border
                }
                HerbGardenDirectDiscipleSlotItem(
                    disciple = agg,
                    borderColor = borderColor,
                    onSlotClick = { onDirectDiscipleClick(index) },
                    onDismiss = { onDirectDiscipleRemove(index) },
                    onSwap = { onDirectDiscipleSwap(index) }
                )
            }
        }
    }
}

@Composable
private fun HerbGardenDirectDiscipleSlotItem(
    disciple: DiscipleAggregate?,
    borderColor: Color,
    onSlotClick: () -> Unit,
    onDismiss: () -> Unit,
    onSwap: () -> Unit
) {
    DiscipleSlotWithActions(
        disciple = disciple,
        borderColor = borderColor,
        onSlotClick = { onSlotClick() },
        onEmptySlotClick = { onSwap() },
        onDismiss = { onDismiss() },
        onSwap = { onSwap() }
    )
}

@Composable
private fun SeedPlantingDialog(
    seeds: List<Seed>,
    onDismiss: () -> Unit,
    onSelect: ((Seed) -> Unit)? = null,
    onConfirmOverride: ((Seed) -> Unit)? = null
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

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "选择种子",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp)
            ) {
                Spacer(modifier = Modifier.height(12.dp))
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
                            color = Color.Black
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(60.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 340.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        gridItems(seeds) { seed ->
                            UnifiedItemCard(
                                data = ItemCardData(
                                    name = seed.name,
                                    rarity = seed.rarity,
                                    quantity = seed.quantity
                                ),
                                isSelected = selectedSeed?.id == seed.id,
                                showViewButton = true,
                                onClick = {
                                    if (selectedSeed?.id == seed.id) {
                                        selectedSeed = null
                                        clickedSeed = null
                                    } else {
                                        selectedSeed = seed
                                        clickedSeed = seed
                                    }
                                },
                                onViewDetail = { showDetail = true }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                GameButton(
                    text = "确认种植",
                    onClick = {
                        selectedSeed?.let { seed ->
                            if (onConfirmOverride != null) {
                                onConfirmOverride(seed)
                            } else {
                                onSelect?.invoke(seed)
                            }
                            onDismiss()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selectedSeed != null
                )
            }
        }
    }
}
