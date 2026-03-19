package com.xianxia.sect.ui.game

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
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.MissionSystem
import com.xianxia.sect.core.model.*
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.theme.GameColors

@Composable
fun MissionHallDialog(
    gameData: GameData?,
    disciples: List<Disciple>,
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
                    text = "暂无可用任务，每年年初刷新",
                    fontSize = 11.sp,
                    color = Color(0xFF999999),
                    modifier = Modifier.fillMaxWidth(),
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

    if (showDiscipleSelection && selectedMission != null) {
        DiscipleSelectionDialog(
            mission = selectedMission!!,
            disciples = disciples,
            busyDiscipleIds = busyDiscipleIds,
            onConfirm = { selectedDisciples ->
                viewModel.startMission(selectedMission!!, selectedDisciples)
                showDiscipleSelection = false
                selectedMission = null
            },
            onDismiss = {
                showDiscipleSelection = false
                selectedMission = null
            }
        )
    }

    if (showActiveMissionDetail && selectedActiveMission != null) {
        ActiveMissionDetailDialog(
            mission = selectedActiveMission!!,
            currentYear = currentYear,
            currentMonth = currentMonth,
            onDismiss = {
                showActiveMissionDetail = false
                selectedActiveMission = null
            }
        )
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
                        text = "进行中",
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
                    text = "进度：$progress%",
                    fontSize = 10.sp,
                    color = Color(0xFF666666)
                )
                Text(
                    text = "剩余：$remainingMonths 月",
                    fontSize = 10.sp,
                    color = Color(0xFF666666)
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "时长：${mission.duration}月",
                    fontSize = 10.sp,
                    color = Color(0xFF666666)
                )
                Text(
                    text = "需要：${mission.memberCount}名弟子",
                    fontSize = 10.sp,
                    color = Color(0xFF666666)
                )
                Text(
                    text = "最低境界：${mission.realmRequirement}",
                    fontSize = 10.sp,
                    color = Color(0xFF666666)
                )
            }
        }
    }
}

@Composable
private fun ActiveMissionDetailDialog(
    mission: ActiveMission,
    currentYear: Int,
    currentMonth: Int,
    onDismiss: () -> Unit
) {
    val progress = mission.getProgressPercent(currentYear, currentMonth)
    val remainingMonths = mission.getRemainingMonths(currentYear, currentMonth)

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
                        text = "任务类型：${mission.missionType.displayName}",
                        fontSize = 11.sp,
                        color = Color(0xFF666666)
                    )
                    Text(
                        text = "成功率：${(mission.successRate * 100).toInt()}%",
                        fontSize = 11.sp,
                        color = Color(0xFF4CAF50)
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
                    text = "执行弟子 (${mission.memberCount}人)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF333333)
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    mission.discipleNames.forEachIndexed { index, name ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFFF5F5F5))
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${index + 1}. $name",
                                fontSize = 11.sp,
                                color = Color(0xFF333333)
                            )
                            Text(
                                text = "执行中",
                                fontSize = 10.sp,
                                color = Color(0xFF2196F3)
                            )
                        }
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
private fun DiscipleSelectionDialog(
    mission: Mission,
    disciples: List<Disciple>,
    busyDiscipleIds: Set<String>,
    onConfirm: (List<Disciple>) -> Unit,
    onDismiss: () -> Unit
) {
    val selectedDiscipleIds = remember { mutableStateListOf<String>() }

    val eligibleDisciples = disciples.filter { disciple ->
        val disciplePosition = if (disciple.discipleType == "outer") "外门弟子" else "内门弟子"
        disciple.isAlive &&
        disciple.status == DiscipleStatus.IDLE &&
        disciple.id !in busyDiscipleIds &&
        disciplePosition in mission.difficulty.allowedPositions &&
        disciple.realm <= mission.minRealm
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Text(
                text = "选择弟子 (${selectedDiscipleIds.size}/${MissionSystem.TEAM_SIZE})",
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
                    text = "要求：${mission.difficulty.allowedPositions.joinToString("/")}，${mission.realmRequirement}及以上",
                    fontSize = 11.sp,
                    color = Color(0xFF666666)
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = GameColors.Border,
                    thickness = 1.dp
                )

                if (eligibleDisciples.isEmpty()) {
                    Text(
                        text = "没有符合条件的弟子",
                        fontSize = 11.sp,
                        color = Color(0xFF999999),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.heightIn(max = 300.dp)
                    ) {
                        items(eligibleDisciples, key = { it.id }) { disciple ->
                            val isSelected = selectedDiscipleIds.contains(disciple.id)
                            
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) Color(0xFFFFD700).copy(alpha = 0.2f) else Color(0xFFF5F5F5))
                                    .border(
                                        1.dp,
                                        if (isSelected) Color(0xFFFFD700) else GameColors.Border,
                                        RoundedCornerShape(4.dp)
                                    )
                                    .clickable {
                                        if (isSelected) {
                                            selectedDiscipleIds.remove(disciple.id)
                                        } else if (selectedDiscipleIds.size < MissionSystem.TEAM_SIZE) {
                                            selectedDiscipleIds.add(disciple.id)
                                        }
                                    }
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = disciple.name,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black
                                    )
                                    Text(
                                        text = "${if (disciple.discipleType == "outer") "外门弟子" else "内门弟子"} | ${GameConfig.Realm.get(disciple.realm).name}",
                                        fontSize = 9.sp,
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
                        }
                    }
                }
            }
        },
        confirmButton = {
            GameButton(
                text = "确认派遣",
                enabled = selectedDiscipleIds.size == MissionSystem.TEAM_SIZE,
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

private fun getDifficultyColor(difficulty: MissionDifficulty): Color {
    return when (difficulty) {
        MissionDifficulty.YELLOW -> Color(0xFFD4A017)
        MissionDifficulty.MYSTERIOUS -> Color(0xFF6B4E9B)
        MissionDifficulty.EARTH -> Color(0xFF8B4513)
        MissionDifficulty.HEAVEN -> Color(0xFF1E90FF)
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
