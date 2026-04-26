package com.xianxia.sect.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.MissionSystem
import com.xianxia.sect.core.model.*
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.discipleCardBorder
import com.xianxia.sect.ui.components.DiscipleAttrText
import com.xianxia.sect.ui.components.DiscipleCardStyles
import com.xianxia.sect.ui.components.FollowedTag
import com.xianxia.sect.core.util.isFollowed
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.game.components.SpiritRootAttributeFilterBar

@Composable
fun MissionHallDialog(
    gameData: GameData?,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    var selectedMission by remember { mutableStateOf<Mission?>(null) }
    var selectedActiveMission by remember { mutableStateOf<ActiveMission?>(null) }
    var showDiscipleSelection by remember { mutableStateOf(false) }
    var showActiveMissionDetail by remember { mutableStateOf(false) }

    val activeMissions = gameData?.activeMissions ?: emptyList()
    val availableMissions = gameData?.availableMissions ?: emptyList()
    val currentYear = gameData?.gameYear ?: 1
    val currentMonth = gameData?.gameMonth ?: 1

    val busyDiscipleIds = remember(activeMissions) {
        activeMissions.flatMap { it.discipleIds }.toSet()
    }

    CommonDialog(
        title = "任务阁",
        onDismiss = onDismiss
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (availableMissions.isEmpty() && activeMissions.isEmpty()) {
                Text(
                    text = "暂无任务，每三月刷新",
                    fontSize = 11.sp,
                    color = Color(0xFF999999),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    textAlign = TextAlign.Center
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 400.dp)
                ) {
                    items(activeMissions, key = { it.id }) { activeMission ->
                        ActiveMissionCard(
                            mission = activeMission,
                            currentYear = currentYear,
                            currentMonth = currentMonth,
                            onClick = {
                                selectedActiveMission = activeMission
                                showActiveMissionDetail = true
                            }
                        )
                    }

                    items(availableMissions, key = { it.id }) { mission ->
                        AvailableMissionCard(
                            mission = mission,
                            onClick = {
                                selectedMission = mission
                                showDiscipleSelection = true
                            }
                        )
                    }
                }
            }
        }
    }

    if (showDiscipleSelection) {
        selectedMission?.let { mission ->
            DiscipleSelectionDialog(
                mission = mission,
                disciples = disciples,
                busyDiscipleIds = busyDiscipleIds,
                onConfirm = { selectedDisciples ->
                    viewModel.startMission(mission, selectedDisciples)
                    showDiscipleSelection = false
                    selectedMission = null
                },
                onDismiss = {
                    showDiscipleSelection = false
                    selectedMission = null
                }
            )
        }
    }

    if (showActiveMissionDetail) {
        selectedActiveMission?.let { mission ->
            ActiveMissionDetailDialog(
                mission = mission,
                disciples = disciples,
                currentYear = currentYear,
                currentMonth = currentMonth,
                onDismiss = {
                    showActiveMissionDetail = false
                    selectedActiveMission = null
                }
            )
        }
    }
}

@Composable
private fun ActiveMissionCard(
    mission: ActiveMission,
    currentYear: Int,
    currentMonth: Int,
    onClick: () -> Unit
) {
    val progress = mission.getProgressPercent(currentYear, currentMonth)
    val remainingMonths = mission.getRemainingMonths(currentYear, currentMonth)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFE3F2FD)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = mission.missionName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF333333)
                    )
                    Text(
                        text = "执行中",
                        fontSize = 10.sp,
                        color = Color(0xFF2196F3),
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = mission.difficulty.displayName,
                    fontSize = 10.sp,
                    color = getDifficultyColor(mission.difficulty)
                )
            }

            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = Color(0xFF2196F3),
                trackColor = Color(0xFFE0E0E0)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "剩余：$remainingMonths 月",
                    fontSize = 10.sp,
                    color = Color(0xFF666666)
                )
                Text(
                    text = "奖励：${formatSpiritStoneReward(mission.rewards)}",
                    fontSize = 10.sp,
                    color = Color(0xFFD4A017)
                )
            }
        }
    }
}

@Composable
private fun AvailableMissionCard(
    mission: Mission,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = GameColors.PageBackground
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = mission.name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF333333)
                )
                Text(
                    text = mission.difficulty.displayName,
                    fontSize = 10.sp,
                    color = getDifficultyColor(mission.difficulty)
                )
            }

            Text(
                text = mission.description,
                fontSize = 10.sp,
                color = Color(0xFF666666)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "耗时：${mission.duration}月",
                    fontSize = 10.sp,
                    color = Color(0xFF666666)
                )
                Text(
                    text = "需要：${mission.memberCount}名弟子",
                    fontSize = 10.sp,
                    color = Color(0xFF666666)
                )
                Text(
                    text = "奖励：${formatSpiritStoneReward(mission.rewards)}",
                    fontSize = 10.sp,
                    color = Color(0xFFD4A017)
                )
            }
        }
    }
}

@Composable
private fun ActiveMissionDetailDialog(
    mission: ActiveMission,
    disciples: List<DiscipleAggregate>,
    currentYear: Int,
    currentMonth: Int,
    onDismiss: () -> Unit
) {
    val progress = mission.getProgressPercent(currentYear, currentMonth)
    val remainingMonths = mission.getRemainingMonths(currentYear, currentMonth)

    val discipleMap = remember(disciples) {
        disciples.associateBy { it.id }
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
                    text = mission.missionName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Text(
                    text = mission.difficulty.displayName,
                    fontSize = 12.sp,
                    color = getDifficultyColor(mission.difficulty)
                )
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "难度：${mission.difficulty.displayName}",
                        fontSize = 11.sp,
                        color = getDifficultyColor(mission.difficulty)
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = GameColors.Border,
                    thickness = 1.dp
                )

                Text(
                    text = "执行进度",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF333333)
                )

                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = Color(0xFF4CAF50),
                    trackColor = Color(0xFFE0E0E0)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "进度：$progress%",
                        fontSize = 11.sp,
                        color = Color(0xFF666666)
                    )
                    Text(
                        text = "剩余：$remainingMonths 月",
                        fontSize = 11.sp,
                        color = Color(0xFF666666)
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = GameColors.Border,
                    thickness = 1.dp
                )

                Text(
                    text = "任务奖励",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF333333)
                )

                Text(
                    text = formatSpiritStoneReward(mission.rewards),
                    fontSize = 11.sp,
                    color = Color(0xFFD4A017)
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = GameColors.Border,
                    thickness = 1.dp
                )

                Text(
                    text = "执行弟子 (${mission.memberCount}人)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF333333)
                )

                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 240.dp)
                ) {
                    items(mission.discipleIds, key = { it }) { discipleId ->
                        val index = mission.discipleIds.indexOf(discipleId)
                        val name = if (index < mission.discipleNames.size) mission.discipleNames[index] else "未知"
                        val realm = if (index < mission.discipleRealms.size) mission.discipleRealms[index] else ""
                        val disciple = discipleMap[discipleId]

                        MissionDiscipleSlot(
                            name = name,
                            realm = realm,
                            hpRatio = 1f
                        )
                    }
                }
            }
        },
        confirmButton = {
            GameButton(
                text = "关闭",
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun MissionDiscipleSlot(
    name: String,
    realm: String,
    hpRatio: Float
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFFF5F5F5))
            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(6.dp))
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFFE0E0E0))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(hpRatio.coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        when {
                            hpRatio > 0.6f -> Color(0xFF4CAF50)
                            hpRatio > 0.3f -> Color(0xFFFF9800)
                            else -> Color(0xFFF44336)
                        }
                    )
            )
        }

        Text(
            text = name,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF333333),
            maxLines = 1
        )

        Text(
            text = realm,
            fontSize = 9.sp,
            color = Color(0xFF666666)
        )
    }
}

@Composable
private fun DiscipleSelectionDialog(
    mission: Mission,
    disciples: List<DiscipleAggregate>,
    busyDiscipleIds: Set<String>,
    onConfirm: (List<DiscipleAggregate>) -> Unit,
    onDismiss: () -> Unit
) {
    val selectedDiscipleIds = remember { mutableStateListOf<String>() }
    var selectedSpiritRootFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedAttributeSort by remember { mutableStateOf<String?>(null) }
    var spiritRootExpanded by remember { mutableStateOf(false) }
    var attributeExpanded by remember { mutableStateOf(false) }

    val eligibleDisciples = disciples.filter { disciple ->
        val disciplePosition = if (disciple.discipleType == "outer") "外门弟子" else "内门弟子"
        disciple.isAlive &&
        disciple.status == DiscipleStatus.IDLE &&
        disciple.id !in busyDiscipleIds &&
        disciplePosition in mission.difficulty.allowedPositions &&
        disciple.realm <= mission.difficulty.minRealm
    }

    val spiritRootCounts = remember(eligibleDisciples) {
        eligibleDisciples.groupingBy { it.getSpiritRootCount() }.eachCount()
    }

    val filteredDisciples = remember(eligibleDisciples, selectedSpiritRootFilter, selectedAttributeSort) {
        eligibleDisciples.applyFilters(emptySet(), selectedSpiritRootFilter, selectedAttributeSort)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Text(
                text = "选择弟子 (${selectedDiscipleIds.size}/${mission.memberCount})",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "任务：${mission.name}",
                    fontSize = 11.sp,
                    color = Color(0xFF666666)
                )
                Text(
                    text = "要求：${mission.difficulty.allowedPositions.joinToString("/")}，${com.xianxia.sect.core.GameConfig.Realm.getName(mission.difficulty.minRealm)}及以上，空闲状态",
                    fontSize = 11.sp,
                    color = Color(0xFF666666)
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = GameColors.Border,
                    thickness = 1.dp
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

                if (filteredDisciples.isEmpty()) {
                    Text(
                        text = "没有符合条件的弟子",
                        fontSize = 11.sp,
                        color = Color(0xFF999999),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        textAlign = TextAlign.Center
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.heightIn(max = 300.dp)
                    ) {
                        items(filteredDisciples, key = { it.id }) { disciple ->
                            val isSelected = selectedDiscipleIds.contains(disciple.id)

                            SelectionDiscipleCard(
                                disciple = disciple,
                                isSelected = isSelected,
                                onClick = {
                                    if (isSelected) {
                                        selectedDiscipleIds.remove(disciple.id)
                                    } else if (selectedDiscipleIds.size < mission.memberCount) {
                                        selectedDiscipleIds.add(disciple.id)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            GameButton(
                text = "确认派遣",
                enabled = selectedDiscipleIds.size == mission.memberCount,
                onClick = {
                    val selectedDisciples = eligibleDisciples.filter { it.id in selectedDiscipleIds }
                    onConfirm(selectedDisciples)
                }
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

@Composable
private fun SelectionDiscipleCard(
    disciple: DiscipleAggregate,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) Color(0xFFFFD700) else Color.Transparent
    val backgroundColor = if (isSelected) Color(0xFFFFF8E1) else Color.White

    val spiritRootColor = try {
        Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
    } catch (e: Exception) {
        Color(0xFF666666)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .discipleCardBorder()
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 2.dp,
                        color = Color(0xFFFFD700),
                        shape = DiscipleCardStyles.mediumShape
                    )
                } else {
                    Modifier
                }
            )
            .background(backgroundColor, DiscipleCardStyles.mediumShape)
            .clickable { onClick() }
            .padding(DiscipleCardStyles.cardPadding)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
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
                        text = if (disciple.discipleType == "outer") "外门弟子" else "内门弟子",
                        fontSize = 11.sp,
                        color = Color(0xFF666666)
                    )
                }
                if (isSelected) {
                    Text(
                        text = "✓",
                        fontSize = 14.sp,
                        color = Color(0xFFFFD700),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = disciple.spiritRootName,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = spiritRootColor,
                    maxLines = 1
                )
                Text(
                    text = disciple.realmNameOnly,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DiscipleAttrText("悟性", disciple.comprehension, fontSize = 10.sp)
                DiscipleAttrText("忠诚", disciple.loyalty, fontSize = 10.sp)
            }
        }
    }
}

private fun formatSpiritStoneReward(rewards: MissionRewardConfig): String {
    val parts = mutableListOf<String>()
    if (rewards.spiritStones > 0 || rewards.spiritStonesMax > 0) {
        if (rewards.spiritStonesMax > 0) {
            parts.add("${rewards.spiritStones}~${rewards.spiritStonesMax}灵石")
        } else {
            parts.add("${rewards.spiritStones}灵石")
        }
    }
    if (rewards.materialCountMin > 0) {
        val rarityNames = (rewards.materialMinRarity..rewards.materialMaxRarity).map { r ->
            when (r) {
                1 -> "凡品"
                2 -> "灵品"
                3 -> "宝品"
                4 -> "玄品"
                5 -> "地品"
                6 -> "天品"
                else -> ""
            }
        }.filter { it.isNotEmpty() }
        val rarityStr = rarityNames.joinToString("/")
        val countStr = if (rewards.materialCountMin == rewards.materialCountMax) {
            "${rewards.materialCountMin}"
        } else {
            "${rewards.materialCountMin}~${rewards.materialCountMax}"
        }
        parts.add("${countStr}个${rarityStr}妖兽材料")
    }
    return parts.joinToString("、")
}

private fun getDifficultyColor(difficulty: MissionDifficulty): Color {
    return when (difficulty) {
        MissionDifficulty.SIMPLE -> Color(0xFF4CAF50)
        MissionDifficulty.NORMAL -> Color(0xFF2196F3)
        MissionDifficulty.HARD -> Color(0xFFFF9800)
        MissionDifficulty.FORBIDDEN -> Color(0xFFF44336)
    }
}

@Composable
private fun CommonDialog(
    title: String,
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
                        .clip(RoundedCornerShape(12.dp))
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
            Column {
                content()
            }
        },
        confirmButton = {}
    )
}
