package com.xianxia.sect.ui.game

import androidx.compose.foundation.Image
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
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.equipmentSpriteRes
import com.xianxia.sect.ui.components.getRarityColor
import com.xianxia.sect.R
import com.xianxia.sect.core.model.*
import com.xianxia.sect.ui.components.ElderBonusInfo
import com.xianxia.sect.ui.components.ElderBonusInfoButton
import com.xianxia.sect.ui.components.ElderBonusInfoProvider
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.DialogDefaults
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.FollowedTag
import com.xianxia.sect.ui.theme.ButtonSizes
import com.xianxia.sect.ui.components.PortraitDiscipleCard
import com.xianxia.sect.ui.components.UnifiedDiscipleSlot
import com.xianxia.sect.ui.components.DiscipleSlotWithActions
import com.xianxia.sect.core.util.isFollowed
import com.xianxia.sect.ui.game.components.SpiritRootAttributeFilterBar

data class ProductionTheme(
    val buildingId: String,
    val displayName: String,
    val elderTitle: String,
    val elderBonusInfo: ElderBonusInfo,
    val coreAttributeName: String,
    val coreAttributeColor: Color,
    val defaultBorderColor: Color,
    val workingStatusColor: Color,
    val selectedHighlightColor: Color,
    val reserveButtonBackgroundColor: Color,
    val reserveButtonTextColor: Color,
    val slotLabelPrefix: String,
    val selectionDialogTitle: String,
    val startProductionText: String = "开始炼制",
    val elderSelectionTitle: String,
    val recommendAttributeText: String,
    val getCoreAttributeValue: (DiscipleAggregate) -> Int,
    val getElderId: (ElderSlots) -> String?,
    val getDirectDisciples: (ElderSlots) -> List<DirectDiscipleSlot>,
    val elderSortComparator: Comparator<DiscipleAggregate>,
    val directDiscipleSortComparator: Comparator<DiscipleAggregate>
)

val ALCHEMY_THEME = ProductionTheme(
    buildingId = "alchemy",
    displayName = "炼丹炉",
    elderTitle = "炼丹长老",
    elderBonusInfo = ElderBonusInfoProvider.getAlchemyElderInfo(),
    coreAttributeName = "炼丹",
    coreAttributeColor = Color(0xFF9C27B0),
    defaultBorderColor = Color(0xFF9C27B0),
    workingStatusColor = Color(0xFF2196F3),
    selectedHighlightColor = Color(0xFFFFD700),
    reserveButtonBackgroundColor = GameColors.ButtonBackground,
    reserveButtonTextColor = Color.Black,
    slotLabelPrefix = "炼丹槽",
    selectionDialogTitle = "选择丹药",
    startProductionText = "确认炼制",
    elderSelectionTitle = "选择炼丹长老",
    recommendAttributeText = "炼丹",
    getCoreAttributeValue = { it.pillRefining },
    getElderId = { it.alchemyElder },
    getDirectDisciples = { it.alchemyDisciples },
    elderSortComparator = compareByDescending<DiscipleAggregate> { it.pillRefining }
        .thenBy { it.realm }
        .thenByDescending { it.realmLayer },
    directDiscipleSortComparator = compareBy<DiscipleAggregate> { it.realm }
        .thenByDescending { it.realmLayer }
        .thenByDescending { it.pillRefining }
)

val FORGE_THEME = ProductionTheme(
    buildingId = "forge",
    displayName = "锻造坊",
    elderTitle = "天工长老",
    elderBonusInfo = ElderBonusInfoProvider.getForgeElderInfo(),
    coreAttributeName = "炼器",
    coreAttributeColor = Color(0xFF4CAF50),
    defaultBorderColor = Color(0xFFFF9800),
    workingStatusColor = Color(0xFFFF9800),
    selectedHighlightColor = Color(0xFFFF9800),
    reserveButtonBackgroundColor = Color(0xFFFF9800),
    reserveButtonTextColor = Color.White,
    slotLabelPrefix = "炼器槽",
    selectionDialogTitle = "选择装备",
    startProductionText = "确认锻造",
    elderSelectionTitle = "选择天工长老",
    recommendAttributeText = "炼器",
    getCoreAttributeValue = { it.artifactRefining },
    getElderId = { it.forgeElder },
    getDirectDisciples = { it.forgeDisciples },
    elderSortComparator = compareByDescending<DiscipleAggregate> { it.artifactRefining }
        .thenBy { it.realm }
        .thenByDescending { it.realmLayer },
    directDiscipleSortComparator = compareBy<DiscipleAggregate> { it.realm }
        .thenByDescending { it.realmLayer }
        .thenByDescending { it.artifactRefining }
)

val HERB_GARDEN_THEME = ProductionTheme(
    buildingId = "herbGarden",
    displayName = "灵植阁",
    elderTitle = "灵植长老",
    elderBonusInfo = ElderBonusInfoProvider.getHerbGardenElderInfo(),
    coreAttributeName = "灵植",
    coreAttributeColor = Color(0xFF27AE60),
    defaultBorderColor = Color(0xFF27AE60),
    workingStatusColor = Color(0xFF2196F3),
    selectedHighlightColor = Color(0xFFFFD700),
    reserveButtonBackgroundColor = GameColors.ButtonBackground,
    reserveButtonTextColor = Color.Black,
    slotLabelPrefix = "种植槽",
    selectionDialogTitle = "选择种子",
    startProductionText = "确认种植",
    elderSelectionTitle = "选择灵植长老",
    recommendAttributeText = "灵植",
    getCoreAttributeValue = { it.spiritPlanting },
    getElderId = { it.herbGardenElder },
    getDirectDisciples = { it.herbGardenDisciples },
    elderSortComparator = compareByDescending<DiscipleAggregate> { it.spiritPlanting }
        .thenBy { it.realm }
        .thenByDescending { it.realmLayer },
    directDiscipleSortComparator = compareBy<DiscipleAggregate> { it.realm }
        .thenByDescending { it.realmLayer }
        .thenByDescending { it.spiritPlanting }
)

val SPIRIT_MINE_THEME = ProductionTheme(
    buildingId = "spiritMine",
    displayName = "灵矿场",
    elderTitle = "执事",
    elderBonusInfo = ElderBonusInfo( title = "执事", requiredAttribute = "道德", effectDescription = "管理灵矿场", bonusFormula = "道德值×效率"),
    coreAttributeName = "采矿",
    coreAttributeColor = Color(0xFF795548),
    defaultBorderColor = Color(0xFF795548),
    workingStatusColor = Color(0xFF2196F3),
    selectedHighlightColor = Color(0xFFFFD700),
    reserveButtonBackgroundColor = GameColors.ButtonBackground,
    reserveButtonTextColor = Color.Black,
    slotLabelPrefix = "采矿",
    selectionDialogTitle = "",
    startProductionText = "",
    elderSelectionTitle = "选择执事",
    recommendAttributeText = "采矿",
    getCoreAttributeValue = { it.mining },
    getElderId = { it.spiritMineDeaconDisciples.firstOrNull()?.discipleId ?: "" },
    getDirectDisciples = { emptyList() },
    elderSortComparator = compareByDescending<DiscipleAggregate> { it.mining }
        .thenBy { it.realm }
        .thenByDescending { it.realmLayer },
    directDiscipleSortComparator = compareBy<DiscipleAggregate> { it.realm }
)

private val REALM_FILTERS = listOf(
    0 to "仙人", 1 to "渡劫", 2 to "大乘", 3 to "合体", 4 to "炼虚",
    5 to "化神", 6 to "元婴", 7 to "金丹", 8 to "筑基", 9 to "炼气"
)

@Composable
fun ProductionCommonDialog(
    title: String,
    theme: ProductionTheme,
    onDismiss: () -> Unit,
    enableScroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = title,
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = DialogDefaults.CommonMaxHeight)
                        .then(if (enableScroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                        .padding(horizontal = 16.dp)
                ) {
                    content()
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
}

@Composable
fun ProductionElderSection(
    theme: ProductionTheme,
    elder: DiscipleAggregate?,
    onSlotClick: () -> Unit,
    onElderRemove: () -> Unit,
    onSwap: () -> Unit = {}
) {
    val elderBorderColor = if (elder != null) {
        try { Color(android.graphics.Color.parseColor(elder.spiritRoot.countColor)) }
        catch (e: Exception) { theme.defaultBorderColor }
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
            Text(text = theme.elderTitle, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            ElderBonusInfoButton(bonusInfo = theme.elderBonusInfo)
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
fun ProductionDirectDiscipleSection(
    theme: ProductionTheme,
    directDisciples: List<DirectDiscipleSlot>,
    disciples: List<DiscipleAggregate>,
    slotCount: Int,
    onDirectDiscipleClick: (Int) -> Unit,
    onDirectDiscipleRemove: (Int) -> Unit,
    onDirectDiscipleSwap: (Int) -> Unit = {}
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "亲传弟子", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        ) {
            (0 until slotCount).forEach { index ->
                val slot = directDisciples.getOrNull(index) ?: DirectDiscipleSlot(index = index)
                val disciple = if (slot.isActive) disciples.find { it.id == slot.discipleId } else null
                val borderColor = if (slot.isActive) {
                    try { Color(android.graphics.Color.parseColor(disciple?.spiritRoot?.countColor)) }
                    catch (e: Exception) { theme.defaultBorderColor }
                } else {
                    GameColors.Border
                }
                ProductionDirectDiscipleSlotItem(
                    disciple = disciple,
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
private fun ProductionDirectDiscipleSlotItem(
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
fun ProductionSlotItem(
    theme: ProductionTheme,
    productName: String?,
    isWorking: Boolean,
    isIdle: Boolean,
    remainingMonths: Int,
    index: Int,
    productRarity: Int = 1,
    totalDuration: Int = 1,
    onCancel: (() -> Unit)? = null,
    onReplace: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val progress = if (isWorking && totalDuration > 0) {
        ((totalDuration - remainingMonths).coerceIn(0, totalDuration).toFloat() / totalDuration)
    } else 0f

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (isWorking) {
            Text(
                text = "剩余 ${remainingMonths} 月",
                fontSize = 10.sp,
                color = Color.Black
            )
            Spacer(modifier = Modifier.height(2.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = Color(0xFF4CAF50),
                trackColor = GameColors.Border
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(GameColors.PageBackground)
                .border(1.dp, GameColors.Border, RoundedCornerShape(6.dp))
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            if (isWorking && productName != null) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(getRarityColor(productRarity)),
                        contentAlignment = Alignment.Center
                    ) {
                        val spriteRes = equipmentSpriteRes(productName)
                        if (spriteRes != null) {
                            Image(
                                painter = painterResource(id = spriteRes),
                                contentDescription = productName,
                                modifier = Modifier.fillMaxSize().padding(3.dp),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            Text(
                                text = "敬请期待",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(2.dp)
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(14.dp)
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = productName,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 2.dp)
                        )
                    }
                }
            } else {
                Text(text = "+", fontSize = 24.sp, color = Color.Black)
            }
        }

        if (isWorking && onCancel != null && onReplace != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
            ) {
                Text(
                    text = "取消",
                    fontSize = 9.sp,
                    color = Color(0xFFE53935),
                    modifier = Modifier.clickable { onCancel() }
                )
                Text(
                    text = "更换",
                    fontSize = 9.sp,
                    color = Color.Black,
                    modifier = Modifier.clickable { onReplace() }
                )
            }
        }
    }
}

@Composable
fun ProductionElderSelectionDialog(
    theme: ProductionTheme,
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
    var realmExpanded by remember { mutableStateOf(false) }

    val filteredDisciplesBase = remember(disciples, elderSlots) {
        disciples.filter { it.isEligibleForProductionPosition && !elderSlots.isDiscipleInAnyPosition(it.id) }
    }

    val realmCounts = remember(filteredDisciplesBase) {
        filteredDisciplesBase.groupingBy { it.realm }.eachCount()
    }

    val spiritRootCounts = remember(filteredDisciplesBase) {
        filteredDisciplesBase.groupingBy { it.getSpiritRootCount() }.eachCount()
    }

    val sortedDisciples = remember(filteredDisciplesBase) {
        filteredDisciplesBase.sortedWith(theme.elderSortComparator)
    }

    val filteredDisciples = remember(sortedDisciples, selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort) {
        sortedDisciples.applyFilters(selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort, ATTRIBUTE_FILTER_OPTIONS.find { it.name == theme.recommendAttributeText }?.key)
    }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = theme.elderSelectionTitle,
        mode = DialogMode.Half,
        scrollableContent = false,
        headerActions = {
            Text("推荐${theme.recommendAttributeText}", fontSize = 10.sp, color = Color(0xFF4CAF50))
        }
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                SpiritRootAttributeFilterBar(
                    selectedSpiritRootFilter = selectedSpiritRootFilter,
                    selectedAttributeSort = selectedAttributeSort,
                    selectedRealmFilter = selectedRealmFilter,
                    realmFilterOptions = REALM_FILTERS,
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
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    if (filteredDisciples.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(text = "暂无可用弟子", fontSize = 12.sp, color = Color.Black)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "需要: 空闲中 · 未担任其他职务",
                                    fontSize = 10.sp,
                                    color = Color(0xFF888888)
                                )
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(filteredDisciples, key = { it.id }) { disciple ->
                                ProductionDiscipleSelectionCard(
                                    theme = theme,
                                    disciple = disciple,
                                    onClick = { onSelect(disciple.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
}

@Composable
fun ProductionDirectDiscipleSelectionDialog(
    theme: ProductionTheme,
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
    var realmExpanded by remember { mutableStateOf(false) }

    val allDirectDiscipleIds = remember(elderSlots) {
        listOf(
            elderSlots.herbGardenDisciples,
            elderSlots.alchemyDisciples,
            elderSlots.forgeDisciples
        ).flatten().mapNotNull { it.discipleId }
    }

    val filteredDisciplesBase = remember(disciples, elderSlots, allDirectDiscipleIds) {
        disciples.filter { it.isEligibleForInnerPosition && !elderSlots.isDiscipleInAnyPosition(it.id) && !allDirectDiscipleIds.contains(it.id) }
    }

    val realmCounts = remember(filteredDisciplesBase) {
        filteredDisciplesBase.groupingBy { it.realm }.eachCount()
    }

    val spiritRootCounts = remember(filteredDisciplesBase) {
        filteredDisciplesBase.groupingBy { it.getSpiritRootCount() }.eachCount()
    }

    val sortedDisciples = remember(filteredDisciplesBase) {
        filteredDisciplesBase.sortedWith(theme.directDiscipleSortComparator)
    }

    val filteredDisciples = remember(sortedDisciples, selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort) {
        sortedDisciples.applyFilters(selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort, ATTRIBUTE_FILTER_OPTIONS.find { it.name == theme.recommendAttributeText }?.key)
    }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "选择${theme.slotLabelPrefix.removeSuffix("槽").trim()}弟子",
        mode = DialogMode.Half,
        scrollableContent = false,
        headerActions = {
            Text("推荐${theme.recommendAttributeText}", fontSize = 10.sp, color = Color(0xFF4CAF50))
        }
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                SpiritRootAttributeFilterBar(
                    selectedSpiritRootFilter = selectedSpiritRootFilter,
                    selectedAttributeSort = selectedAttributeSort,
                    selectedRealmFilter = selectedRealmFilter,
                    realmFilterOptions = REALM_FILTERS,
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
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    if (filteredDisciples.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "暂无可用弟子", fontSize = 12.sp, color = Color.Black)
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(filteredDisciples, key = { it.id }) { disciple ->
                                ProductionDiscipleSelectionCard(
                                    theme = theme,
                                    disciple = disciple,
                                    onClick = { onSelect(disciple.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
}

@Composable
private fun ProductionDiscipleSelectionCard(
    theme: ProductionTheme,
    disciple: DiscipleAggregate,
    onClick: () -> Unit
) {
    PortraitDiscipleCard(
        disciple = disciple,
        onClick = onClick
    )
}

@Composable
fun ProductionReserveDiscipleDialog(
    theme: ProductionTheme,
    reserveDisciples: List<DiscipleAggregate>,
    onDismiss: () -> Unit,
    onAddClick: () -> Unit,
    onRemove: (String) -> Unit
) {
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "储备弟子",
        mode = DialogMode.Half,
        scrollableContent = false,
        headerActions = {
            Text("推荐${theme.recommendAttributeText}", fontSize = 10.sp, color = Color(0xFF4CAF50))
        }
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(theme.reserveButtonBackgroundColor)
                            .clickable { onAddClick() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(text = "添加", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = theme.reserveButtonTextColor)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                if (reserveDisciples.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(text = "暂无储备弟子", fontSize = 12.sp, color = Color.Black) }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(reserveDisciples, key = { it.id }) { disciple ->
                            ProductionReserveDiscipleCard(
                                theme = theme,
                                disciple = disciple,
                                onRemove = { onRemove(disciple.id) }
                            )
                        }
                    }
                }
            }
        }
}

@Composable
private fun ProductionReserveDiscipleCard(
    theme: ProductionTheme,
    disciple: DiscipleAggregate,
    onRemove: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PortraitDiscipleCard(
            disciple = disciple,
            onClick = {}
        )
        Spacer(modifier = Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(GameColors.PageBackground)
                .border(1.dp, GameColors.Border, RoundedCornerShape(4.dp))
                .clickable { onRemove() }
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) { Text(text = "移除", fontSize = 10.sp, color = Color.Black) }
    }
}

@Composable
fun ProductionAddReserveDiscipleDialog(
    theme: ProductionTheme,
    availableDisciples: List<DiscipleAggregate>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "添加储备弟子",
        mode = DialogMode.Half,
        scrollableContent = false,
        headerActions = {
            Text("推荐${theme.recommendAttributeText}", fontSize = 10.sp, color = Color(0xFF4CAF50))
        }
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    if (availableDisciples.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) { Text(text = "暂无可用弟子", fontSize = 12.sp, color = Color.Black) }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(availableDisciples, key = { it.id }) { disciple ->
                                val isSelected = selectedIds.contains(disciple.id)
                                ProductionAddReserveDiscipleSelectCard(
                                    theme = theme,
                                    disciple = disciple,
                                    isSelected = isSelected,
                                    onClick = {
                                        selectedIds = if (isSelected) selectedIds - disciple.id
                                        else selectedIds + disciple.id
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    GameButton(
                        text = "添加${if (selectedIds.isNotEmpty()) "(${selectedIds.size})" else ""}",
                        onClick = { onConfirm(selectedIds.toList()) },
                        modifier = Modifier.width(ButtonSizes.StandardWidth)
                    )
                }
            }
        }
}

@Composable
fun FilteredMultiSelectDialog(
    title: String,
    disciples: List<DiscipleAggregate>,
    selectedIds: SnapshotStateList<String>,
    maxSelection: Int? = null,
    confirmEnabled: (Int) -> Boolean = { it > 0 },
    confirmText: String = "确认",
    showDismiss: Boolean = false,
    dismissText: String = "取消",
    extraCardAttrName: String? = null,
    headerContent: (@Composable () -> Unit)? = null,
    bottomContent: (@Composable () -> Unit)? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedRealmFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedSpiritRootFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedAttributeSort by remember { mutableStateOf<String?>(null) }
    var spiritRootExpanded by remember { mutableStateOf(false) }
    var attributeExpanded by remember { mutableStateOf(false) }
    var realmExpanded by remember { mutableStateOf(false) }

    val realmCounts = remember(disciples) {
        disciples.groupingBy { it.realm }.eachCount()
    }

    val spiritRootCounts = remember(disciples) {
        disciples.groupingBy { it.getSpiritRootCount() }.eachCount()
    }

    val sortedDisciples = remember(disciples) {
        disciples.sortedWith(compareBy<DiscipleAggregate> { it.realm }.thenByDescending { it.realmLayer })
    }

    val filteredDisciples = remember(sortedDisciples, selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort) {
        sortedDisciples.applyFilters(selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort)
    }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = title,
        mode = DialogMode.Half,
        scrollableContent = false,
        headerActions = if (extraCardAttrName != null) {
            { Text("推荐$extraCardAttrName", fontSize = 10.sp, color = Color(0xFF4CAF50)) }
        } else null
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                ) {
                    if (headerContent != null) {
                        headerContent()
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    SpiritRootAttributeFilterBar(
                        selectedSpiritRootFilter = selectedSpiritRootFilter,
                        selectedAttributeSort = selectedAttributeSort,
                        selectedRealmFilter = selectedRealmFilter,
                        realmFilterOptions = REALM_FILTERS,
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

                    if (filteredDisciples.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
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
                                val isSelected = selectedIds.contains(disciple.id)
                                val canSelect = maxSelection == null || selectedIds.size < maxSelection || isSelected
                                PortraitDiscipleCard(
                                    disciple = disciple,
                                    isSelected = isSelected,
                                    onClick = {
                                        if (isSelected) {
                                            selectedIds.remove(disciple.id)
                                        } else if (canSelect) {
                                            selectedIds.add(disciple.id)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (showDismiss) Arrangement.SpaceBetween else Arrangement.End
                ) {
                    if (showDismiss) {
                        GameButton(text = dismissText, onClick = onDismiss, modifier = Modifier.width(ButtonSizes.StandardWidth))
                    }
                    GameButton(
                        text = confirmText,
                        onClick = onConfirm,
                        enabled = confirmEnabled(selectedIds.size),
                        modifier = Modifier.width(ButtonSizes.StandardWidth)
                    )
                }
            }
        }
}

@Composable
private fun ProductionAddReserveDiscipleSelectCard(
    theme: ProductionTheme,
    disciple: DiscipleAggregate,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    PortraitDiscipleCard(
        disciple = disciple,
        isSelected = isSelected,
        onClick = onClick
    )
}
