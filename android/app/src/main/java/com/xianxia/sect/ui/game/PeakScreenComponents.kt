package com.xianxia.sect.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.*
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.components.ElderBonusInfoButton
import com.xianxia.sect.ui.components.ElderBonusInfo
import com.xianxia.sect.ui.components.ElderBonusInfoProvider
import com.xianxia.sect.ui.components.FollowedTag
import com.xianxia.sect.ui.components.HorizontalDiscipleCard
import com.xianxia.sect.core.util.isFollowed
import com.xianxia.sect.ui.game.components.SpiritRootAttributeFilterBar

data class PeakElderSlotConfig(
    val title: String,
    val elder: DiscipleAggregate?,
    val bonusInfo: ElderBonusInfo,
    val onClick: () -> Unit,
    val onRemove: () -> Unit
)

data class PeakPreachingMasterConfig(
    val label: String,
    val bonusInfo: ElderBonusInfo
)

@Composable
fun PeakDialog(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
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
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = subtitle,
                    fontSize = 10.sp,
                    color = Color(0xFF4CAF50),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
                content()
            }
        },
        confirmButton = {}
    )
}

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

        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(GameColors.PageBackground)
                .border(1.dp, borderColor, RoundedCornerShape(6.dp))
                .clickable(onClick = config.onClick),
            contentAlignment = Alignment.Center
        ) {
            if (config.elder != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = config.elder.name,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        maxLines = 1
                    )
                    Text(
                        text = config.elder.realmName,
                        fontSize = 8.sp,
                        color = Color(0xFF666666),
                        maxLines = 1
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

        if (config.elder != null) {
            Spacer(modifier = Modifier.height(4.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(GameColors.PageBackground)
                    .border(1.dp, GameColors.Border, RoundedCornerShape(4.dp))
                    .clickable(onClick = config.onRemove)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "卸任",
                    fontSize = 10.sp,
                    color = Color.Black
                )
            }
        }
    }
}

@Composable
fun PeakPreachingMasterSection(
    sectionTitle: String,
    masterConfig: PeakPreachingMasterConfig,
    preachingMasters: List<DirectDiscipleSlot>,
    onMasterClick: (Int) -> Unit,
    onMasterRemove: (Int) -> Unit
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
                PeakPreachingMasterSlotItem(
                    master = master,
                    config = masterConfig,
                    onClick = { onMasterClick(index) },
                    onRemove = { onMasterRemove(index) }
                )
            }
        }
    }
}

@Composable
private fun PeakPreachingMasterSlotItem(
    master: DirectDiscipleSlot?,
    config: PeakPreachingMasterConfig,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
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
                color = Color(0xFF666666)
            )
            ElderBonusInfoButton(bonusInfo = config.bonusInfo)
        }
        Spacer(modifier = Modifier.height(2.dp))

        val borderColor = if (master != null && master.isActive) {
            try {
                Color(android.graphics.Color.parseColor(master.discipleSpiritRootColor))
            } catch (e: Exception) {
                Color(0xFF9C27B0)
            }
        } else {
            GameColors.Border
        }

        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(GameColors.PageBackground)
                .border(1.dp, borderColor, RoundedCornerShape(4.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (master != null && master.isActive) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = master.discipleName,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        maxLines = 1
                    )
                    Text(
                        text = master.discipleRealm,
                        fontSize = 7.sp,
                        color = Color(0xFF666666),
                        maxLines = 1
                    )
                }
            } else {
                Text(
                    text = "+",
                    fontSize = 16.sp,
                    color = Color(0xFF999999)
                )
            }
        }

        if (master != null && master.isActive) {
            Spacer(modifier = Modifier.height(2.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(GameColors.PageBackground)
                    .border(1.dp, GameColors.Border, RoundedCornerShape(3.dp))
                    .clickable(onClick = onRemove)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "卸任",
                    fontSize = 8.sp,
                    color = Color.Black
                )
            }
        }
    }
}

@Composable
fun PeakDiscipleListSection(
    sectionTitle: String,
    emptyText: String,
    disciples: List<DiscipleAggregate>,
    maxHeightDp: Dp = 180.dp,
    truncateAt: Int? = 10
) {
    val sortedDisciples = remember(disciples) {
        disciples.sortedBy { it.spiritRoot.types.size }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(GameColors.CardBackground)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = sectionTitle,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            Text(
                text = "共${disciples.size}人",
                fontSize = 10.sp,
                color = Color(0xFF666666)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (sortedDisciples.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = emptyText,
                    fontSize = 12.sp,
                    color = Color(0xFF999999)
                )
            }
        } else {
            val displayItems = truncateAt?.let { sortedDisciples.take(it) } ?: sortedDisciples
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeightDp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(displayItems) { disciple ->
                    PeakDiscipleItem(disciple = disciple)
                }
                if (truncateAt != null && sortedDisciples.size > truncateAt) {
                    item {
                        Text(
                            text = "还有${sortedDisciples.size - truncateAt}名弟子...",
                            fontSize = 10.sp,
                            color = Color(0xFF999999),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PeakDiscipleItem(disciple: DiscipleAggregate) {
    val borderColor = try {
        Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
    } catch (e: Exception) {
        GameColors.Border
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(GameColors.PageBackground)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = disciple.name,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                if (disciple.isFollowed) {
                    FollowedTag()
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = disciple.spiritRootName,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = borderColor
                )
                Text(
                    text = disciple.realmName,
                    fontSize = 10.sp,
                    color = Color(0xFF666666)
                )
            }
        }
    }
}

@Composable
fun PeakDiscipleSelectionDialog(
    title: String,
    disciples: List<DiscipleAggregate>,
    currentDiscipleId: String?,
    requirementText: String,
    onSelect: (DiscipleAggregate) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedRealmFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedSpiritRootFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedAttributeSort by remember { mutableStateOf<String?>(null) }
    var spiritRootExpanded by remember { mutableStateOf(false) }
    var attributeExpanded by remember { mutableStateOf(false) }

    val realmFilters = listOf(
        0 to "仙人", 1 to "渡劫", 2 to "大乘", 3 to "合体",
        4 to "炼虚", 5 to "化神", 6 to "元婴", 7 to "金丹"
    )

    val realmCounts = remember(disciples) {
        disciples.filter { it.realmLayer > 0 }.groupingBy { it.realm }.eachCount()
    }

    val spiritRootCounts = remember(disciples) {
        disciples.filter { it.realmLayer > 0 }.groupingBy { it.getSpiritRootCount() }.eachCount()
    }

    val sortedDisciples = remember(disciples) {
        disciples.filter { it.realmLayer > 0 }.sortedWith(
            compareBy<DiscipleAggregate> { it.realm }.thenByDescending { it.realmLayer }
        )
    }

    val filteredDisciples = remember(sortedDisciples, selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort) {
        sortedDisciples.applyFilters(selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort)
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
                Text(text = title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                Box(
                    modifier = Modifier
                        .size(24.dp).clip(CircleShape)
                        .clickable { onDismiss() }
                        .background(GameColors.CardBackground),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "×", fontSize = 16.sp, color = Color(0xFF666666))
                }
            }
        },
        text = {
            if (disciples.isEmpty()) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "暂无符合条件的弟子", fontSize = 12.sp, color = Color(0xFF999999))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = requirementText, fontSize = 10.sp, color = Color(0xFF666666))
                }
            } else {
                Column(Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
                    Text(
                        text = requirementText, fontSize = 10.sp, color = Color(0xFF4CAF50),
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))

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

                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        realmFilters.chunked(4).forEach { chunk ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                chunk.forEach { (realm, name) ->
                                    val isSelected = realm in selectedRealmFilter
                                    val count = realmCounts[realm] ?: 0
                                    Box(
                                        modifier = Modifier
                                            .weight(1f).clip(RoundedCornerShape(4.dp))
                                            .background(if (isSelected) GameColors.Gold.copy(alpha = 0.3f) else GameColors.PageBackground)
                                            .border(1.dp, if (isSelected) GameColors.Gold else GameColors.Border, RoundedCornerShape(4.dp))
                                            .clickable { selectedRealmFilter = if (isSelected) selectedRealmFilter - realm else selectedRealmFilter + realm }
                                            .padding(vertical = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "$name $count", fontSize = 9.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) GameColors.GoldDark else Color.Black
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(filteredDisciples, key = { it.id }) { disciple ->
                            val isCurrent = disciple.id == currentDiscipleId
                            HorizontalDiscipleCard(
                                disciple = disciple,
                                isCurrent = isCurrent,
                                onClick = { onSelect(disciple) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}
