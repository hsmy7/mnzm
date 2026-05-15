package com.xianxia.sect.ui.game.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.AlertDialog
import android.graphics.Color as AndroidColor
import com.xianxia.sect.R
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.WorldMapViewModel
import com.xianxia.sect.ui.components.DiscipleAttrText
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.DialogDefaults
import com.xianxia.sect.ui.components.GameButton
import androidx.compose.ui.window.DialogProperties
import com.xianxia.sect.ui.components.FollowedTag
import com.xianxia.sect.ui.components.PortraitDiscipleCard
import com.xianxia.sect.ui.components.HalfScreenDialog
import com.xianxia.sect.core.util.isFollowed
import com.xianxia.sect.core.util.sortedByFollowAndRealm
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.core.util.SectRelationLevel
import com.xianxia.sect.ui.game.getSpiritRootCount
import com.xianxia.sect.ui.game.applyFilters
import com.xianxia.sect.ui.game.components.SpiritRootAttributeFilterBar
import com.xianxia.sect.ui.game.tabs.REALM_FILTER_OPTIONS

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

    HalfScreenDialog(onDismissRequest = onDismiss, isFullScreen = true) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("联盟", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                CloseButton(onClick = onDismiss)
            }
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)
            ) {
                if (isAlly) {
                    AllyStatusSection(
                        sect = sect,
                        remainingYears = remainingYears,
                        relation = relation
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    GameButton(
                        text = "解除结盟",
                        onClick = {
                            worldMapViewModel.dissolveAlliance(sect?.id ?: "")
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
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
        HalfScreenDialog(onDismissRequest = { showAlreadyAllianceDialog = false }) {
            Column(modifier = Modifier.padding(20.dp)) {
                    Text("提示", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("该宗门已有盟友", fontSize = 12.sp, color = Color.Black)
                    Spacer(modifier = Modifier.height(16.dp))
                    GameButton(
                        text = "确定",
                        onClick = { showAlreadyAllianceDialog = false },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
            }
        }
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

        GameButton(
            text = "结盟",
            onClick = onAllianceClick,
            enabled = meetsFavorRequirement && canAfford,
            modifier = Modifier.fillMaxWidth()
        )
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
    var selectedRealmFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedSpiritRootFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedAttributeSort by remember { mutableStateOf<String?>(null) }
    var spiritRootExpanded by remember { mutableStateOf(false) }
    var attributeExpanded by remember { mutableStateOf(false) }
    var realmExpanded by remember { mutableStateOf(false) }
    val sectLevel = sect?.level ?: 0
    val requiredRealm = GameConfig.Realm.getName(worldMapViewModel.getEnvoyRealmRequirement(sectLevel))

    val realmCounts = remember(disciples) {
        disciples.groupingBy { it.realm }.eachCount()
    }

    val spiritRootCounts = remember(disciples) {
        disciples.groupingBy { it.getSpiritRootCount() }.eachCount()
    }

    val filteredDisciples = remember(disciples, selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort) {
        disciples.applyFilters(selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort)
    }

    HalfScreenDialog(onDismissRequest = onDismiss) {
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
                    }
                }

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

                if (filteredDisciples.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "没有符合条件的弟子",
                            fontSize = 14.sp,
                            color = Color.Black
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "需要境界: $requiredRealm 及以上",
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
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filteredDisciples, key = { it.id }) { disciple ->
                            PortraitDiscipleCard(
                                disciple = disciple,
                                isSelected = selectedDisciple?.id == disciple.id,
                                onClick = { selectedDisciple = disciple },
                                customAttributes = {
                                    DiscipleAttrText("智力", disciple.intelligence)
                                    DiscipleAttrText("魅力", disciple.charm)
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
    var realmExpanded by remember { mutableStateOf(false) }
    val maxSelectCount = 7

    val realmCounts = remember(disciples) { disciples.groupingBy { it.realm }.eachCount() }

    val spiritRootCounts = remember(disciples) {
        disciples.groupingBy { it.getSpiritRootCount() }.eachCount()
    }

    val filteredDisciples = remember(disciples, selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort) {
        disciples.applyFilters(selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort)
    }

    HalfScreenDialog(onDismissRequest = onDismiss) {
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
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filteredDisciples.size, key = { filteredDisciples[it].id }) { index ->
                            val disciple = filteredDisciples[index]
                            PortraitDiscipleCard(
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


