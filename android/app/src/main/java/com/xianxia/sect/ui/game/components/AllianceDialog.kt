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
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.components.GameButton

@Composable
fun AllianceDialog(
    sect: WorldSect?,
    gameData: com.xianxia.sect.core.model.GameData?,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val isAlly = sect?.let { viewModel.isAlly(it.id) } ?: false
    val remainingYears = sect?.let { viewModel.getAllianceRemainingYears(it.id) } ?: 0
    val allianceCost = sect?.level?.let { viewModel.getAllianceCost(it) } ?: 0L
    val spiritStones = gameData?.spiritStones ?: 0L
    val canAfford = spiritStones >= allianceCost
    val playerSect = gameData?.worldMapSects?.find { it.isPlayerSect }
    val relation = if (playerSect != null && sect != null) {
        gameData.sectRelations.find { 
            (it.sectId1 == playerSect.id && it.sectId2 == sect.id) ||
            (it.sectId1 == sect.id && it.sectId2 == playerSect.id)
        }?.favor ?: 0
    } else 0
    val meetsFavorRequirement = relation >= 90
    val hasOtherAlliance = sect?.allianceId != null && !isAlly
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
                                viewModel.dissolveAlliance(sect?.id ?: "")
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
                                viewModel.openEnvoyDiscipleSelectDialog()
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
            Text("好感度", fontSize = 14.sp, color = GameColors.TextSecondary)
            Text("$relation", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GameColors.TextPrimary)
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
            Text("好感度", fontSize = 14.sp, color = GameColors.TextSecondary)
            Text("$relation / 90", fontSize = 14.sp, fontWeight = FontWeight.Bold, 
                color = if (meetsFavorRequirement) GameColors.Success else GameColors.Error)
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("结盟费用", fontSize = 14.sp, color = GameColors.TextSecondary)
            Text(formatSpiritStones(allianceCost), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                color = if (canAfford) Color(0xFFFFD700) else GameColors.Error)
        }

        Spacer(modifier = Modifier.height(12.dp))

        HorizontalDivider(color = GameColors.Border)

        Spacer(modifier = Modifier.height(12.dp))

        Text("结盟条件", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GameColors.TextPrimary)

        Spacer(modifier = Modifier.height(8.dp))

        ConditionItem("好感度达到90以上", meetsFavorRequirement)
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
    disciples: List<Disciple>,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    var selectedDisciple by remember { mutableStateOf<Disciple?>(null) }
    val sectLevel = sect?.level ?: 0
    val requiredRealm = GameConfig.Realm.getName(
        when (sectLevel) {
            0 -> 7
            1 -> 5
            2 -> 4
            3 -> 3
            else -> 7
        }
    )

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

                if (disciples.isEmpty()) {
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
                        items(disciples, key = { it.id }) { disciple ->
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
                                viewModel.requestAlliance(sect?.id ?: "", disciple.id)
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
    disciple: Disciple,
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
                    text = disciple.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${GameConfig.Realm.getName(disciple.realm)} · ${disciple.realmLayer}层",
                    fontSize = 12.sp,
                    color = Color.Black
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "智${disciple.intelligence}",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                    Text(
                        text = "魅${disciple.charm}",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
            }
        }
    }
}

@Composable
fun RequestSupportDialog(
    allies: List<WorldSect>,
    disciples: List<Disciple>,
    gameData: com.xianxia.sect.core.model.GameData?,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    var selectedAlly by remember { mutableStateOf<WorldSect?>(null) }
    var selectedDisciple by remember { mutableStateOf<Disciple?>(null) }
    
    val playerSect = gameData?.worldMapSects?.find { it.isPlayerSect }
    val allyFavors = remember(playerSect, gameData?.sectRelations, allies) {
        if (playerSect != null) {
            allies.associateWith { ally ->
                gameData?.sectRelations?.find { 
                    (it.sectId1 == playerSect.id && it.sectId2 == ally.id) ||
                    (it.sectId1 == ally.id && it.sectId2 == playerSect.id)
                }?.favor ?: 0
            }
        } else emptyMap()
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
                    color = Color(0xFFFF9800),
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "请求支援",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "选择盟友和求援弟子",
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }

                if (allies.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "没有盟友",
                            fontSize = 14.sp,
                            color = Color.Black
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "选择盟友",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        LazyColumn(
                            modifier = Modifier.heightIn(max = 150.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(allies, key = { it.id }) { ally ->
                                AllySelectCard(
                                    ally = ally,
                                    relation = allyFavors[ally] ?: 0,
                                    isSelected = selectedAlly?.id == ally.id,
                                    onClick = { selectedAlly = ally }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "选择求援弟子 (需金丹及以上)",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        if (disciples.isEmpty()) {
                            Text(
                                text = "没有符合条件的弟子",
                                fontSize = 13.sp,
                                color = GameColors.TextSecondary
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.heightIn(max = 200.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(disciples, key = { it.id }) { disciple ->
                                    DiscipleSelectCard(
                                        disciple = disciple,
                                        isSelected = selectedDisciple?.id == disciple.id,
                                        onClick = { selectedDisciple = disciple }
                                    )
                                }
                            }
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
                        text = "请求支援",
                        onClick = {
                            selectedAlly?.let { ally ->
                                selectedDisciple?.let { disciple ->
                                    viewModel.requestSupport(ally.id, disciple.id)
                                }
                            }
                        },
                        enabled = selectedAlly != null && selectedDisciple != null,
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
                    color = Color.Black
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = ally.levelName,
                    fontSize = 12.sp,
                    color = Color.Black
                )
            }

            Text(
                text = "好感度 $relation",
                fontSize = 12.sp,
                color = Color.Black
            )
        }
    }
}

private fun formatSpiritStones(amount: Long): String {
    return when {
        amount >= 100000000 -> "${amount / 100000000}亿"
        amount >= 10000 -> "${amount / 10000}万"
        else -> amount.toString()
    }
}

@Composable
fun ScoutDiscipleSelectDialog(
    sect: WorldSect?,
    disciples: List<Disciple>,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    var selectedDisciples by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedRealmFilter by remember { mutableStateOf<Int?>(null) }
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

    val realmCounts = disciples.groupingBy { it.realm }.eachCount()

    val filteredDisciples = if (selectedRealmFilter == null) {
        disciples.sortedBy { it.realm }
    } else {
        disciples.filter { it.realm == selectedRealmFilter }.sortedBy { it.realm }
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

                ScoutRealmFilterBar(
                    filters = realmFilters,
                    realmCounts = realmCounts,
                    selectedFilter = selectedRealmFilter,
                    onFilterSelected = { selectedRealmFilter = it }
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
                        items(filteredDisciples, key = { it.id }) { disciple ->
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
                                viewModel.startScoutMission(selectedDisciples.toList(), sect.id)
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
    selectedFilter: Int?,
    onFilterSelected: (Int?) -> Unit
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
                val isSelected = selectedFilter == realm
                val count = realmCounts[realm] ?: 0
                ScoutFilterChip(
                    text = "$name $count",
                    isSelected = isSelected,
                    onClick = { onFilterSelected(if (isSelected) null else realm) },
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
                val isSelected = selectedFilter == realm
                val count = realmCounts[realm] ?: 0
                ScoutFilterChip(
                    text = "$name $count",
                    isSelected = isSelected,
                    onClick = { onFilterSelected(if (isSelected) null else realm) },
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
            .background(if (isSelected) GameColors.Border else GameColors.PageBackground)
            .border(1.dp, GameColors.Border, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = Color.Black
        )
    }
}

@Composable
private fun ScoutDiscipleCard(
    disciple: Disciple,
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
                Text(
                    text = "悟性:${disciple.comprehension}",
                    fontSize = 11.sp,
                    color = Color(0xFF666666)
                )
                Text(
                    text = "忠诚:${disciple.loyalty}",
                    fontSize = 11.sp,
                    color = Color(0xFF666666)
                )
                Text(
                    text = "道德:${disciple.morality}",
                    fontSize = 11.sp,
                    color = Color(0xFF666666)
                )
            }
        }
    }
}
