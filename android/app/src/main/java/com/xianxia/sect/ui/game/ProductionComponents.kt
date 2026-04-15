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
import com.xianxia.sect.ui.components.ElderBonusInfo
import com.xianxia.sect.ui.components.ElderBonusInfoButton
import com.xianxia.sect.ui.components.ElderBonusInfoProvider
import com.xianxia.sect.ui.theme.GameColors

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
    displayName = "丹鼎殿",
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
    displayName = "天工峰",
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
                        Text(text = "×", fontSize = 16.sp, color = Color(0xFF666666))
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .then(if (enableScroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            ) {
                content()
            }
        },
        confirmButton = {}
    )
}

@Composable
fun ProductionElderSection(
    theme: ProductionTheme,
    elder: DiscipleAggregate?,
    onElderClick: () -> Unit,
    onElderRemove: () -> Unit
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
            Text(text = theme.elderTitle, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF666666))
            ElderBonusInfoButton(bonusInfo = theme.elderBonusInfo)
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(70.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(GameColors.PageBackground)
                    .border(2.dp, elderBorderColor, RoundedCornerShape(8.dp))
                    .clickable { onElderClick() },
                contentAlignment = Alignment.Center
            ) {
                if (elder != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = elder.name, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Black, maxLines = 1)
                        Text(text = elder.realmName, fontSize = 9.sp, color = Color(0xFF666666))
                        Text(
                            text = "${theme.coreAttributeName}: ${theme.getCoreAttributeValue(elder)}",
                            fontSize = 9.sp,
                            color = theme.coreAttributeColor
                        )
                    }
                } else {
                    Text(text = "点击任命", fontSize = 10.sp, color = Color(0xFF999999))
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
                ) { Text(text = "卸任", fontSize = 12.sp, color = Color.Black) }
            }
        }
    }
}

@Composable
fun ProductionDirectDiscipleSection(
    theme: ProductionTheme,
    directDisciples: List<DirectDiscipleSlot>,
    slotCount: Int,
    onDirectDiscipleClick: (Int) -> Unit,
    onDirectDiscipleRemove: (Int) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "亲传弟子", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF666666))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        ) {
            (0 until slotCount).forEach { index ->
                val disciple = directDisciples.getOrNull(index) ?: DirectDiscipleSlot(index = index)
                ProductionDirectDiscipleSlotItem(
                    theme = theme,
                    disciple = disciple,
                    onClick = { onDirectDiscipleClick(index) },
                    onRemove = { onDirectDiscipleRemove(index) }
                )
            }
        }
    }
}

@Composable
private fun ProductionDirectDiscipleSlotItem(
    theme: ProductionTheme,
    disciple: DirectDiscipleSlot,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    val borderColor = if (disciple.isActive) {
        try { Color(android.graphics.Color.parseColor(disciple.discipleSpiritRootColor)) }
        catch (e: Exception) { theme.defaultBorderColor }
    } else {
        GameColors.Border
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(55.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(GameColors.PageBackground)
                .border(1.dp, borderColor, RoundedCornerShape(6.dp))
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            if (disciple.isActive) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = disciple.discipleName, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.Black, maxLines = 1, textAlign = TextAlign.Center)
                    Text(text = disciple.discipleRealm, fontSize = 8.sp, color = Color(0xFF666666))
                }
            } else {
                Text(text = "+", fontSize = 20.sp, color = Color(0xFF999999))
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
            ) { Text(text = "卸任", fontSize = 9.sp, color = Color.Black) }
        }
    }
}

@Composable
fun ProductionSlotItem(
    theme: ProductionTheme,
    productName: String?,
    isWorking: Boolean,
    isIdle: Boolean,
    remainingMonths: Int,
    index: Int,
    onClick: () -> Unit
) {
    val statusColor = if (isWorking) theme.workingStatusColor else GameColors.Border

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "${theme.slotLabelPrefix} ${index + 1}",
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
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            if (isWorking && productName != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = productName, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color.Black, maxLines = 1, textAlign = TextAlign.Center)
                    Text(text = "${remainingMonths}月", fontSize = 10.sp, color = Color(0xFF666666))
                }
            } else {
                Text(text = "+", fontSize = 24.sp, color = Color(0xFF999999))
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
    var selectedRealmFilter by remember { mutableStateOf<Int?>(null) }

    val filteredDisciplesBase = remember(disciples, elderSlots) {
        disciples.filter {
            it.realmLayer > 0 &&
            it.age >= 5 &&
            it.status == DiscipleStatus.IDLE &&
            it.discipleType == "inner" &&
            it.realm <= 6 &&
            !elderSlots.isDiscipleInAnyPosition(it.id)
        }
    }

    val realmCounts = remember(filteredDisciplesBase) {
        filteredDisciplesBase.groupingBy { it.realm }.eachCount()
    }

    val sortedDisciples = remember(filteredDisciplesBase) {
        filteredDisciplesBase.sortedWith(theme.elderSortComparator)
    }

    val filteredDisciples = remember(sortedDisciples, selectedRealmFilter) {
        if (selectedRealmFilter == null) sortedDisciples
        else sortedDisciples.filter { it.realm == selectedRealmFilter }
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
                Text(text = theme.elderSelectionTitle, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable { onDismiss() }
                        .background(GameColors.CardBackground),
                    contentAlignment = Alignment.Center
                ) { Text(text = "×", fontSize = 16.sp, color = Color(0xFF666666)) }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                Text(
                    text = "推荐属性: ${theme.recommendAttributeText}",
                    fontSize = 10.sp,
                    color = Color(0xFF999999),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                RealmFilterRow(realmFilters = REALM_FILTERS, selectedFilter = selectedRealmFilter, onFilterChange = { selectedRealmFilter = it })
                Spacer(modifier = Modifier.height(12.dp))

                if (filteredDisciples.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) { Text(text = "暂无可用弟子", fontSize = 12.sp, color = Color(0xFF999999)) }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredDisciples.size) { index ->
                            ProductionDiscipleSelectionCard(
                                theme = theme,
                                disciple = filteredDisciples[index],
                                onClick = { onSelect(filteredDisciples[index].id) }
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
fun ProductionDirectDiscipleSelectionDialog(
    theme: ProductionTheme,
    disciples: List<DiscipleAggregate>,
    elderSlots: ElderSlots,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    var selectedRealmFilter by remember { mutableStateOf<Int?>(null) }

    val allDirectDiscipleIds = remember(elderSlots) {
        listOf(
            elderSlots.herbGardenDisciples,
            elderSlots.alchemyDisciples,
            elderSlots.forgeDisciples
        ).flatten().mapNotNull { it.discipleId }
    }

    val filteredDisciplesBase = remember(disciples, elderSlots, allDirectDiscipleIds) {
        disciples.filter {
            it.realmLayer > 0 &&
            it.age >= 5 &&
            it.status == DiscipleStatus.IDLE &&
            it.discipleType == "inner" &&
            !elderSlots.isDiscipleInAnyPosition(it.id) &&
            !allDirectDiscipleIds.contains(it.id)
        }
    }

    val realmCounts = remember(filteredDisciplesBase) {
        filteredDisciplesBase.groupingBy { it.realm }.eachCount()
    }

    val sortedDisciples = remember(filteredDisciplesBase) {
        filteredDisciplesBase.sortedWith(theme.directDiscipleSortComparator)
    }

    val filteredDisciples = remember(sortedDisciples, selectedRealmFilter) {
        if (selectedRealmFilter == null) sortedDisciples
        else sortedDisciples.filter { it.realm == selectedRealmFilter }
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
                Text(text = "选择亲传弟子", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable { onDismiss() }
                        .background(GameColors.CardBackground),
                    contentAlignment = Alignment.Center
                ) { Text(text = "×", fontSize = 16.sp, color = Color(0xFF666666)) }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                Text(
                    text = "推荐属性: ${theme.recommendAttributeText}",
                    fontSize = 10.sp,
                    color = Color(0xFF999999),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                RealmFilterRow(realmFilters = REALM_FILTERS, selectedFilter = selectedRealmFilter, onFilterChange = { selectedRealmFilter = it })
                Spacer(modifier = Modifier.height(12.dp))

                if (filteredDisciples.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) { Text(text = "暂无可用弟子", fontSize = 12.sp, color = Color(0xFF999999)) }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredDisciples.size) { index ->
                            ProductionDiscipleSelectionCard(
                                theme = theme,
                                disciple = filteredDisciples[index],
                                onClick = { onSelect(filteredDisciples[index].id) }
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
private fun ProductionDiscipleSelectionCard(
    theme: ProductionTheme,
    disciple: DiscipleAggregate,
    onClick: () -> Unit
) {
    val spiritRootColor = try {
        Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
    } catch (e: Exception) {
        Color(0xFF666666)
    }

    Box(
        modifier = Modifier
            .size(60.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(GameColors.PageBackground)
            .border(1.dp, GameColors.Border, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = disciple.name, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.Black, maxLines = 1)
            Text(text = disciple.realmName, fontSize = 8.sp, color = Color(0xFF666666), maxLines = 1)
            Text(
                text = "${theme.coreAttributeName}:${theme.getCoreAttributeValue(disciple)}",
                fontSize = 7.sp,
                color = spiritRootColor,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun RealmFilterRow(
    realmFilters: List<Pair<Int, String>>,
    selectedFilter: Int?,
    onFilterChange: (Int?) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        realmFilters.chunked(5).forEach { chunk ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                chunk.forEach { (realmVal, name) ->
                    val isSelected = selectedFilter == realmVal
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isSelected) GameColors.Border else GameColors.PageBackground)
                            .border(1.dp, GameColors.Border, RoundedCornerShape(4.dp))
                            .clickable { onFilterChange(if (isSelected) null else realmVal) }
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = name,
                            fontSize = 9.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = Color.Black
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ProductionReserveDiscipleDialog(
    theme: ProductionTheme,
    reserveDisciples: List<DiscipleAggregate>,
    onDismiss: () -> Unit,
    onAddClick: () -> Unit,
    onRemove: (String) -> Unit
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
                Text(text = "储备弟子", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable { onDismiss() }
                            .background(GameColors.CardBackground),
                        contentAlignment = Alignment.Center
                    ) { Text(text = "×", fontSize = 16.sp, color = Color(0xFF666666)) }
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                if (reserveDisciples.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(text = "暂无储备弟子", fontSize = 12.sp, color = Color(0xFF999999)) }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(reserveDisciples) { disciple ->
                            ProductionReserveDiscipleCard(
                                theme = theme,
                                disciple = disciple,
                                onRemove = { onRemove(disciple.id) }
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
private fun ProductionReserveDiscipleCard(
    theme: ProductionTheme,
    disciple: DiscipleAggregate,
    onRemove: () -> Unit
) {
    val spiritRootColor = try {
        Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
    } catch (e: Exception) {
        Color(0xFF666666)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(GameColors.PageBackground)
                .border(1.dp, spiritRootColor, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = disciple.name, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.Black, maxLines = 1)
                Text(text = disciple.spiritRootType, fontSize = 7.sp, color = spiritRootColor, maxLines = 1)
                Text(text = disciple.realmName, fontSize = 8.sp, color = Color(0xFF666666), maxLines = 1)
                Text(
                    text = "${theme.coreAttributeName}:${theme.getCoreAttributeValue(disciple)}",
                    fontSize = 7.sp,
                    color = theme.coreAttributeColor,
                    maxLines = 1
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(GameColors.PageBackground)
                .border(1.dp, GameColors.Border, RoundedCornerShape(4.dp))
                .clickable { onRemove() }
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) { Text(text = "移除", fontSize = 8.sp, color = Color.Black) }
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

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "添加储备弟子", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable { onDismiss() }
                        .background(GameColors.CardBackground),
                    contentAlignment = Alignment.Center
                ) { Text(text = "×", fontSize = 16.sp, color = Color(0xFF666666)) }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                Text(
                    text = "推荐属性: ${theme.recommendAttributeText}",
                    fontSize = 10.sp,
                    color = Color(0xFF999999),
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                if (availableDisciples.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) { Text(text = "暂无可用弟子", fontSize = 12.sp, color = Color(0xFF999999)) }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(availableDisciples.size) { index ->
                            val disciple = availableDisciples[index]
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
        },
        confirmButton = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                com.xianxia.sect.ui.components.GameButton(
                    text = "添加${if (selectedIds.isNotEmpty()) "(${selectedIds.size})" else ""}",
                    onClick = { onConfirm(selectedIds.toList()) }
                )
            }
        }
    )
}

@Composable
private fun ProductionAddReserveDiscipleSelectCard(
    theme: ProductionTheme,
    disciple: DiscipleAggregate,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val spiritRootColor = try {
        Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
    } catch (e: Exception) {
        Color(0xFF666666)
    }

    val borderColor = if (isSelected) Color(0xFFFFD700) else spiritRootColor
    val borderWidth = if (isSelected) (if (theme == ALCHEMY_THEME) 3.dp else 2.dp) else 1.dp
    val bgColor = if (isSelected) (if (theme == ALCHEMY_THEME) Color(0xFFFFF8E1) else GameColors.PageBackground) else GameColors.PageBackground

    Box(
        modifier = Modifier
            .size(60.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(borderWidth, borderColor, RoundedCornerShape(6.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = disciple.name, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.Black, maxLines = 1)
            Text(text = disciple.spiritRootType, fontSize = 7.sp, color = spiritRootColor, maxLines = 1)
            Text(text = disciple.realmName, fontSize = 8.sp, color = Color(0xFF666666), maxLines = 1)
            Text(
                text = "${theme.coreAttributeName}:${theme.getCoreAttributeValue(disciple)}",
                fontSize = 7.sp,
                color = theme.coreAttributeColor,
                maxLines = 1
            )
        }
    }
}
