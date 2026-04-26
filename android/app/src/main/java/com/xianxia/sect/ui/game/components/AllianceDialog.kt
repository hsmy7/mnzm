package com.xianxia.sect.ui.game.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.AlertDialog
import android.graphics.Color as AndroidColor
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.WorldMapViewModel
import com.xianxia.sect.ui.components.DiscipleAttrText
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.FollowedTag
import com.xianxia.sect.core.util.isFollowed
import com.xianxia.sect.core.util.sortedByFollowAndRealm
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.core.util.SectRelationLevel
import com.xianxia.sect.ui.game.getSpiritRootCount
import com.xianxia.sect.ui.game.applyFilters
import com.xianxia.sect.ui.game.components.SpiritRootAttributeFilterBar

@Composable
fun AllianceDialog(
    sect: WorldSect?,
    gameData: com.xianxia.sect.core.model.GameData?,
    viewModel: GameViewModel,
    worldMapViewModel: WorldMapViewModel,
    onDismiss: () -> Unit
) {
    val isAlly = sect?.let { worldMapViewModel.isAlly(it.id) } ?: false
    val remainingYears = sect?.let { worldMapViewModel.getAllianceRemainingYears(it.id) } ?: 0
    val allianceCost = sect?.level?.let { worldMapViewModel.getAllianceCost(it) } ?: 0L
    val spiritStones = gameData?.spiritStones ?: 0L
    val canAfford = spiritStones >= allianceCost
    val playerSect = gameData?.worldMapSects?.find { it.isPlayerSect }
    val relation = if (playerSect != null && sect != null) {
        gameData.sectRelations.find { 
            (it.sectId1 == playerSect.id && it.sectId2 == sect.id) ||
            (it.sectId1 == sect.id && it.sectId2 == playerSect.id)
        }?.favor ?: 0
    } else 0
    val meetsFavorRequirement = relation >= GameConfig.Diplomacy.MIN_ALLIANCE_FAVOR
    val hasOtherAlliance = sect?.allianceId?.isNotEmpty() == true && !isAlly
    var showAlreadyAllianceDialog by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = RoundedCornerShape(16.dp),
            color = GameColors.PageBackground
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Text(
                    text = sect?.name ?: "宗门",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = GameColors.TextPrimary
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (isAlly) {
                    AllyStatusSection(
                        sect = sect,
                        remainingYears = remainingYears,
                        relation = relation
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        GameButton(
                            text = "关闭",
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        )
                        GameButton(
                            text = "解除结盟",
                            onClick = {
                                worldMapViewModel.dissolveAlliance(sect?.id ?: "")
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    NonAllySection(
                        sect = sect,
                        allianceCost = allianceCost,
                        canAfford = canAfford,
                        meetsFavorRequirement = meetsFavorRequirement,
                        relation = relation,
                        spiritStones = spiritStones,
                        hasOtherAlliance = hasOtherAlliance,
                        onAllianceClick = {
                            if (hasOtherAlliance) {
                                showAlreadyAllianceDialog = true
                            } else {
                                worldMapViewModel.openEnvoyDiscipleSelectDialog()
                            }
                        },
                        onCloseClick = onDismiss
                    )
                }
            }
        }
    }
    
    if (showAlreadyAllianceDialog) {
        AlertDialog(
            onDismissRequest = { showAlreadyAllianceDialog = false },
            title = { Text("提示") },
            text = { Text("该宗门已有盟友") },
            confirmButton = {
                GameButton(
                    text = "确定",
                    onClick = { showAlreadyAllianceDialog = false }
                )
            }
        )
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
private fun AllyStatusSection(
    sect: WorldSect?,
    remainingYears: Int,
    relation: Int
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("结盟状态", fontSize = 14.sp, color = GameColors.TextSecondary)
            Text("已结盟", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GameColors.Success)
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("关系", fontSize = 14.sp, color = GameColors.TextSecondary)
            val relationLevel = GameUtils.getSectRelationLevel(relation)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(relationLevel.displayName, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(relationLevel.colorHex))
                Text("($relation)", fontSize = 14.sp, color = GameColors.TextSecondary)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("盟约剩余年限", fontSize = 14.sp, color = GameColors.TextSecondary)
            Text("${remainingYears}年", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GameColors.TextPrimary)
        }

        Spacer(modifier = Modifier.height(12.dp))

        HorizontalDivider(color = GameColors.Border)

        Spacer(modifier = Modifier.height(12.dp))

        Text("结盟效果", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GameColors.TextPrimary)

        Spacer(modifier = Modifier.height(8.dp))

        val effects = listOf(
            "交易价格优惠 10%",
            "可请求战争支援",
            "盟友弟子可结为道侣"
        )
        effects.forEach { effect ->
            Text("• $effect", fontSize = 13.sp, color = GameColors.TextSecondary)
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
private fun NonAllySection(
    sect: WorldSect?,
    allianceCost: Long,
    canAfford: Boolean,
    meetsFavorRequirement: Boolean,
    relation: Int,
    spiritStones: Long,
    hasOtherAlliance: Boolean,
    onAllianceClick: () -> Unit,
    onCloseClick: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("关系", fontSize = 14.sp, color = GameColors.TextSecondary)
            val relationLevel = GameUtils.getSectRelationLevel(relation)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(relationLevel.displayName, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(relationLevel.colorHex))
                Text("$relation / ${GameConfig.Diplomacy.MIN_ALLIANCE_FAVOR}", fontSize = 14.sp,
                    color = if (meetsFavorRequirement) GameColors.Success else GameColors.Error)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("结盟费用", fontSize = 14.sp, color = GameColors.TextSecondary)
            Text(GameUtils.formatNumber(allianceCost), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                color = if (canAfford) Color(0xFFFFD700) else GameColors.Error)
        }

        Spacer(modifier = Modifier.height(12.dp))

        HorizontalDivider(color = GameColors.Border)

        Spacer(modifier = Modifier.height(12.dp))

        Text("结盟条件", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GameColors.TextPrimary)

        Spacer(modifier = Modifier.height(8.dp))

        ConditionItem("关系达到至交(${GameConfig.Diplomacy.MIN_ALLIANCE_FAVOR}以上)", meetsFavorRequirement)
        ConditionItem("灵石充足", canAfford)
        ConditionItem("派遣弟子游说", true)
        ConditionItem("对方无其他盟友", !hasOtherAlliance)

        Spacer(modifier = Modifier.height(16.dp))

        Text("结盟效果", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GameColors.TextPrimary)

        Spacer(modifier = Modifier.height(8.dp))

        val effects = listOf(
            "交易价格优惠 10%",
            "可请求战争支援",
            "盟友弟子可结为道侣"
        )
        effects.forEach { effect ->
            Text("• $effect", fontSize = 13.sp, color = GameColors.TextSecondary)
            Spacer(modifier = Modifier.height(4.dp))
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GameButton(
                text = "关闭",
                onClick = onCloseClick,
                modifier = Modifier.weight(1f)
            )
            GameButton(
                text = "结盟",
                onClick = onAllianceClick,
                enabled = meetsFavorRequirement && canAfford,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ConditionItem(text: String, isMet: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (isMet) "✓" else "✗",
            fontSize = 14.sp,
            color = if (isMet) GameColors.Success else GameColors.Error
        )
        Text(
            text = text,
            fontSize = 13.sp,
            color = if (isMet) GameColors.TextPrimary else GameColors.TextSecondary
        )
    }
}

@Composable
fun EnvoyDiscipleSelectDialog(
    sect: WorldSect?,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    worldMapViewModel: WorldMapViewModel,
    onDismiss: () -> Unit
) {
    var selectedDisciple by remember { mutableStateOf<DiscipleAggregate?>(null) }
    var selectedSpiritRootFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedAttributeSort by remember { mutableStateOf<String?>(null) }
    var spiritRootExpanded by remember { mutableStateOf(false) }
    var attributeExpanded by remember { mutableStateOf(false) }
    val sectLevel = sect?.level ?: 0
    val requiredRealm = GameConfig.Realm.getName(worldMapViewModel.getEnvoyRealmRequirement(sectLevel))

    val spiritRootCounts = remember(disciples) {
        disciples.groupingBy { it.getSpiritRootCount() }.eachCount()
    }

    val filteredDisciples = remember(disciples, selectedSpiritRootFilter, selectedAttributeSort) {
        disciples.applyFilters(emptySet(), selectedSpiritRootFilter, selectedAttributeSort)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f),
            shape = RoundedCornerShape(16.dp),
            color = GameColors.PageBackground
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFFFF9800),
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "选择游说弟子",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "需要境界: $requiredRealm 及以上",
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }

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

                if (filteredDisciples.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "没有符合条件的弟子",
                            fontSize = 14.sp,
                            color = Color.Black
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredDisciples, key = { it.id }) { disciple ->
                            DiscipleSelectCard(
                                disciple = disciple,
                                isSelected = selectedDisciple?.id == disciple.id,
                                onClick = { selectedDisciple = disciple }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GameButton(
                        text = "确认游说",
                        onClick = {
                            selectedDisciple?.let { disciple ->
                                worldMapViewModel.requestAlliance(sect?.id ?: "", disciple.id)
                            }
                        },
                        enabled = selectedDisciple != null,
                        modifier = Modifier.weight(1f)
                    )
                    GameButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscipleSelectCard(
    disciple: DiscipleAggregate,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) GameColors.Primary else GameColors.Border

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, borderColor, RoundedCornerShape(8.dp)),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) GameColors.Primary.copy(alpha = 0.05f) else GameColors.PageBackground
        ),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = disciple.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = GameColors.TextPrimary
                    )
                    if (disciple.isFollowed) {
                        FollowedTag()
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${GameConfig.Realm.getName(disciple.realm)} · ${disciple.realmLayer}层",
                    fontSize = 12.sp,
                    color = GameColors.TextSecondary
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "智${disciple.intelligence}",
                        fontSize = 12.sp,
                        color = GameColors.TextSecondary
                    )
                    Text(
                        text = "魅${disciple.charm}",
                        fontSize = 12.sp,
                        color = GameColors.TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun AllySelectCard(
    ally: WorldSect,
    relation: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) GameColors.Primary else GameColors.Border

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, borderColor, RoundedCornerShape(8.dp)),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) GameColors.Primary.copy(alpha = 0.05f) else GameColors.PageBackground
        ),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = ally.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = GameColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = ally.levelName,
                    fontSize = 12.sp,
                    color = GameColors.TextSecondary
                )
            }

            val relationLevel = GameUtils.getSectRelationLevel(relation)
            Text(
                text = "${relationLevel.displayName} $relation",
                fontSize = 12.sp,
                color = Color(relationLevel.colorHex)
            )
        }
    }
}

@Composable
fun ScoutDiscipleSelectDialog(
    sect: WorldSect?,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    worldMapViewModel: WorldMapViewModel,
    onDismiss: () -> Unit
) {
    var selectedDisciples by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedRealmFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedSpiritRootFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedAttributeSort by remember { mutableStateOf<String?>(null) }
    var spiritRootExpanded by remember { mutableStateOf(false) }
    var attributeExpanded by remember { mutableStateOf(false) }
    val maxSelectCount = 7

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

    val realmCounts = remember(disciples) { disciples.groupingBy { it.realm }.eachCount() }

    val spiritRootCounts = remember(disciples) {
        disciples.groupingBy { it.getSpiritRootCount() }.eachCount()
    }

    val filteredDisciples = remember(disciples, selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort) {
        disciples.applyFilters(selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f),
            shape = RoundedCornerShape(16.dp),
            color = GameColors.PageBackground
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF2196F3),
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "选择探查弟子",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "目标: ${sect?.name ?: "未知宗门"} · 已选 ${selectedDisciples.size}/$maxSelectCount 人",
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }

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

                ScoutRealmFilterBar(
                    filters = realmFilters,
                    realmCounts = realmCounts,
                    selectedFilter = selectedRealmFilter,
                    onFilterSelected = { selectedRealmFilter = selectedRealmFilter + it },
                    onFilterRemoved = { selectedRealmFilter = selectedRealmFilter - it }
                )

                if (filteredDisciples.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (disciples.isEmpty()) "没有空闲的弟子" else "该境界没有符合条件的弟子",
                            fontSize = 14.sp,
                            color = Color.Black
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredDisciples.size, key = { filteredDisciples[it].id }) { index ->
                            val disciple = filteredDisciples[index]
                            ScoutDiscipleCard(
                                disciple = disciple,
                                isSelected = selectedDisciples.contains(disciple.id),
                                onClick = {
                                    if (selectedDisciples.contains(disciple.id)) {
                                        selectedDisciples = selectedDisciples - disciple.id
                                    } else if (selectedDisciples.size < maxSelectCount) {
                                        selectedDisciples = selectedDisciples + disciple.id
                                    }
                                }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GameButton(
                        text = "开始探查",
                        onClick = {
                            if (selectedDisciples.isNotEmpty() && sect != null) {
                                worldMapViewModel.startScoutMission(selectedDisciples.toList(), sect.id)
                            }
                        },
                        enabled = selectedDisciples.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    )
                    GameButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ScoutRealmFilterBar(
    filters: List<Pair<Int, String>>,
    realmCounts: Map<Int, Int>,
    selectedFilter: Set<Int>,
    onFilterSelected: (Int) -> Unit,
    onFilterRemoved: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(GameColors.PageBackground)
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            filters.take(5).forEach { (realm, name) ->
                val isSelected = realm in selectedFilter
                val count = realmCounts[realm] ?: 0
                ScoutFilterChip(
                    text = "$name $count",
                    isSelected = isSelected,
                    onClick = { if (isSelected) onFilterRemoved(realm) else onFilterSelected(realm) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            filters.drop(5).forEach { (realm, name) ->
                val isSelected = realm in selectedFilter
                val count = realmCounts[realm] ?: 0
                ScoutFilterChip(
                    text = "$name $count",
                    isSelected = isSelected,
                    onClick = { if (isSelected) onFilterRemoved(realm) else onFilterSelected(realm) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ScoutFilterChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (isSelected) GameColors.Gold.copy(alpha = 0.3f) else GameColors.PageBackground)
            .border(1.dp, if (isSelected) GameColors.Gold else GameColors.Border, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) GameColors.GoldDark else Color.Black
        )
    }
}

@Composable
private fun ScoutDiscipleCard(
    disciple: DiscipleAggregate,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) Color(0xFF2196F3) else GameColors.Border

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, borderColor, RoundedCornerShape(8.dp)),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Color(0xFF2196F3).copy(alpha = 0.05f) else GameColors.PageBackground
        ),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = disciple.name,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    if (disciple.isFollowed) {
                        FollowedTag()
                    }
                    Text(
                        text = disciple.status.displayName,
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val spiritRootColor = try {
                    Color(AndroidColor.parseColor(disciple.spiritRoot.countColor))
                } catch (e: Exception) {
                    Color(0xFF666666)
                }
                Text(
                    text = disciple.spiritRootName,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = spiritRootColor,
                    maxLines = 1
                )
                Text(
                    text = disciple.realmName,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DiscipleAttrText("悟性", disciple.comprehension)
                DiscipleAttrText("忠诚", disciple.loyalty)
                DiscipleAttrText("道德", disciple.morality)
            }
        }
    }
}
