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
    var showDiscipleSelection by remember { mutableStateOf(false) }
    var showExpandConfirm by remember { mutableStateOf(false) }

    val activeMissions = gameData?.activeMissions ?: emptyList()
    val availableMissions = gameData?.availableMissions ?: emptyList()
    val missionSlots = gameData?.missionSlots ?: 2
    val usedSlots = activeMissions.size
    val currentYear = gameData?.gameYear ?: 1
    val currentMonth = gameData?.gameMonth ?: 1

    CommonDialog(
        title = "任务阁",
        onDismiss = onDismiss
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "任务槽位：$usedSlots/$missionSlots",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                
                if (MissionSystem.canExpandSlots(missionSlots)) {
                    val expandCost = MissionSystem.calculateExpandCost(missionSlots)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF4CAF50))
                            .clickable { showExpandConfirm = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "扩建 $expandCost 灵石",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            if (activeMissions.isNotEmpty()) {
                Text(
                    text = "进行中的任务",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF666666)
                )

                activeMissions.forEach { mission ->
                    ActiveMissionCard(
                        mission = mission,
                        currentYear = currentYear,
                        currentMonth = currentMonth
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = GameColors.Border,
                    thickness = 1.dp
                )
            }

            Text(
                text = "可接取的任务",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF666666)
            )

            if (availableMissions.isEmpty()) {
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
                    modifier = Modifier.heightIn(max = 300.dp)
                ) {
                    items(availableMissions) { mission ->
                        AvailableMissionCard(
                            mission = mission,
                            onClick = {
                                if (usedSlots < missionSlots) {
                                    selectedMission = mission
                                    showDiscipleSelection = true
                                }
                            },
                            enabled = usedSlots < missionSlots
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

    if (showExpandConfirm) {
        val expandCost = MissionSystem.calculateExpandCost(missionSlots)
        AlertDialog(
            onDismissRequest = { showExpandConfirm = false },
            containerColor = GameColors.PageBackground,
            title = {
                Text(
                    text = "扩建任务槽位",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            },
            text = {
                Text(
                    text = "确定花费 $expandCost 灵石扩建一个任务槽位吗？\n当前槽位：$missionSlots → ${missionSlots + 1}",
                    fontSize = 12.sp,
                    color = Color.Black
                )
            },
            confirmButton = {
                GameButton(
                    text = "确认扩建",
                    onClick = {
                        viewModel.expandMissionSlots()
                        showExpandConfirm = false
                    }
                )
            },
            dismissButton = {
                GameButton(
                    text = "取消",
                    onClick = { showExpandConfirm = false }
                )
            }
        )
    }
}

@Composable
private fun ActiveMissionCard(
    mission: ActiveMission,
    currentYear: Int,
    currentMonth: Int
) {
    val progress = mission.getProgressPercent(currentYear, currentMonth)
    val remainingMonths = mission.getRemainingMonths(currentYear, currentMonth)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF5F5F5))
            .border(1.dp, GameColors.Border, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = mission.missionName,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            Text(
                text = mission.difficulty.displayName,
                fontSize = 10.sp,
                color = getDifficultyColor(mission.difficulty)
            )
        }

        Text(
            text = "弟子：${mission.discipleNames.take(3).joinToString("、")}${if (mission.discipleNames.size > 3) "..." else ""}",
            fontSize = 10.sp,
            color = Color(0xFF666666)
        )

        LinearProgressIndicator(
            progress = { progress / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = Color(0xFF4CAF50),
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

@Composable
private fun AvailableMissionCard(
    mission: Mission,
    onClick: () -> Unit,
    enabled: Boolean
) {
    val bgColor = if (enabled) Color(0xFFF5F5F5) else Color(0xFFE0E0E0)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.dp, GameColors.Border, RoundedCornerShape(8.dp))
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = mission.name,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) Color.Black else Color(0xFF999999)
            )
            Text(
                text = mission.difficulty.displayName,
                fontSize = 10.sp,
                color = getDifficultyColor(mission.difficulty)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = getRewardPreview(mission),
                fontSize = 10.sp,
                color = Color(0xFF4CAF50)
            )

            if (enabled) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF4CAF50))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "选择弟子",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            } else {
                Text(
                    text = "槽位已满",
                    fontSize = 10.sp,
                    color = Color(0xFF999999)
                )
            }
        }
    }
}

@Composable
private fun DiscipleSelectionDialog(
    mission: Mission,
    disciples: List<Disciple>,
    onConfirm: (List<Disciple>) -> Unit,
    onDismiss: () -> Unit
) {
    val selectedDisciples = remember { mutableStateListOf<Disciple>() }

    val eligibleDisciples = disciples.filter { disciple ->
        val disciplePosition = if (disciple.discipleType == "outer") "外门弟子" else "内门弟子"
        disciple.isAlive &&
        disciple.status == DiscipleStatus.IDLE &&
        disciplePosition in mission.difficulty.allowedPositions &&
        disciple.realm <= mission.minRealm
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Text(
                text = "选择弟子 (${selectedDisciples.size}/${MissionSystem.TEAM_SIZE})",
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
                        items(eligibleDisciples) { disciple ->
                            val isSelected = selectedDisciples.contains(disciple)
                            
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) Color(0xFF4CAF50).copy(alpha = 0.2f) else Color(0xFFF5F5F5))
                                    .border(
                                        1.dp,
                                        if (isSelected) Color(0xFF4CAF50) else GameColors.Border,
                                        RoundedCornerShape(4.dp)
                                    )
                                    .clickable {
                                        if (isSelected) {
                                            selectedDisciples.remove(disciple)
                                        } else if (selectedDisciples.size < MissionSystem.TEAM_SIZE) {
                                            selectedDisciples.add(disciple)
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
                                        color = Color(0xFF4CAF50),
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
                enabled = selectedDisciples.size == MissionSystem.TEAM_SIZE,
                onClick = { onConfirm(selectedDisciples.toList()) }
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

private fun getRewardPreview(mission: Mission): String {
    val rewards = mutableListOf<String>()
    
    if (mission.rewards.spiritStones > 0) {
        rewards.add("${mission.rewards.spiritStones}灵石")
    }
    
    if (mission.rewards.materialCountMin > 0) {
        rewards.add("妖兽材料")
    }
    
    if (mission.rewards.herbCountMin > 0 || mission.rewards.seedCountMin > 0) {
        rewards.add("草药/种子")
    }
    
    if (mission.rewards.extraItemChance > 0) {
        rewards.add("道具")
    }
    
    return if (rewards.isEmpty()) "无奖励" else "奖励：${rewards.joinToString("、")}"
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
