package com.xianxia.sect.ui.game

import androidx.compose.animation.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.xianxia.sect.core.model.WarTeam
import com.xianxia.sect.core.model.WarTeamStatus
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.theme.getRealmColor

@Composable
fun WarHallDialog(
    warTeams: List<WarTeam>,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("战堂队伍", "创建队伍")

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(16.dp),
            color = GameColors.CardBackground
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                WarHallHeader(onDismiss = onDismiss)

                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = GameColors.CardBackground,
                    contentColor = GameColors.Primary
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title, fontSize = 12.sp) }
                        )
                    }
                }

                when (selectedTab) {
                    0 -> WarTeamList(
                        warTeams = warTeams,
                        viewModel = viewModel
                    )
                    1 -> CreateWarTeamTab(
                        viewModel = viewModel,
                        onTeamCreated = { selectedTab = 0 }
                    )
                }
            }
        }
    }
}

@Composable
private fun WarHallHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "战堂",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )

        TextButton(onClick = onDismiss) {
            Text("关闭", color = GameColors.TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun WarTeamList(
    warTeams: List<WarTeam>,
    viewModel: GameViewModel
) {
    if (warTeams.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "暂无战堂队伍\n请先创建队伍",
                fontSize = 12.sp,
                color = GameColors.TextSecondary
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(warTeams) { team ->
                WarTeamCard(team = team, viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun WarTeamCard(
    team: WarTeam,
    viewModel: GameViewModel
) {
    var expanded by remember { mutableStateOf(false) }
    val statusColor = when (team.status) {
        WarTeamStatus.IDLE -> GameColors.Success
        WarTeamStatus.STATIONED -> GameColors.Info
        WarTeamStatus.ATTACKING -> GameColors.Error
        WarTeamStatus.DEFENDING -> GameColors.Warning
        WarTeamStatus.RETURNING -> GameColors.TextSecondary
        WarTeamStatus.SCOUTING -> GameColors.CultivationPurple
        WarTeamStatus.TRAVELING -> GameColors.SpiritBlue
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = team.name,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = GameColors.TextPrimary
                    )
                    Text(
                        text = "队长: ${team.leaderName}",
                        fontSize = 12.sp,
                        color = GameColors.TextSecondary
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(statusColor.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = team.status.displayName,
                        fontSize = 12.sp,
                        color = statusColor,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("人数", "${team.members.size}")
                StatItem("战力", "${team.totalPower}")
                StatItem("胜场", "${team.battleWins}")
                StatItem("败场", "${team.battleLosses}")
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    Divider(color = GameColors.Divider)
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "队伍成员",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = GameColors.TextPrimary
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    team.members.forEach { member ->
                        val isDead = member.discipleId in team.deadMemberIds
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .then(
                                    if (isDead) {
                                        Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0xFFE0E0E0).copy(alpha = 0.5f))
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    } else {
                                        Modifier
                                    }
                                )
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = member.name + if (member.isLeader) " (队长)" else "",
                                    fontSize = 12.sp,
                                    color = if (isDead) Color(0xFF999999) else GameColors.TextPrimary
                                )
                                Text(
                                    text = member.realmName,
                                    fontSize = 12.sp,
                                    color = if (isDead) Color(0xFF999999) else getRealmColor(member.realm)
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(1.dp))
                                    .background(Color(0xFFE0E0E0))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(if (isDead) 0f else 1f)
                                        .fillMaxHeight()
                                        .background(if (isDead) Color.Transparent else Color(0xFF4CAF50))
                                )
                            }
                        }
                    }

                    if (team.canOperate) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Button(
                                onClick = { viewModel.disbandWarTeam(team.id) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = GameColors.Error
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("解散", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = GameColors.Primary
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = GameColors.TextTertiary
        )
    }
}

@Composable
private fun CreateWarTeamTab(
    viewModel: GameViewModel,
    onTeamCreated: () -> Unit
) {
    val disciples by viewModel.disciples.collectAsState()
    // 只显示空闲且有境界的弟子，按境界从高到低排序
    val availableDisciples = remember(disciples) {
        disciples.filter { 
            it.isAlive && 
            it.status == com.xianxia.sect.core.model.DiscipleStatus.IDLE && 
            it.realmLayer > 0 && 
            it.age >= 5 
        }.sortedWith(
            compareBy<com.xianxia.sect.core.model.Disciple> { it.realm }
                .thenByDescending { it.realmLayer }
        )
    }

    var teamName by remember { mutableStateOf("") }
    var selectedLeader by remember { mutableStateOf<String?>(null) }
    val selectedMembers = remember { mutableStateListOf<String>() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        OutlinedTextField(
            value = teamName,
            onValueChange = { teamName = it },
            label = { Text("队伍名称", fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "选择队长",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = GameColors.TextPrimary
        )

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(availableDisciples) { disciple ->
                val isSelected = selectedLeader == disciple.id
                DiscipleSelectCard(
                    disciple = disciple,
                    isSelected = isSelected,
                    onClick = {
                        selectedLeader = disciple.id
                        if (!selectedMembers.contains(disciple.id)) {
                            selectedMembers.add(disciple.id)
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "选择队员 (已选 ${selectedMembers.size} 人)",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = GameColors.TextPrimary
        )

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(availableDisciples.filter { it.id != selectedLeader }) { disciple ->
                val isSelected = selectedMembers.contains(disciple.id)
                DiscipleSelectCard(
                    disciple = disciple,
                    isSelected = isSelected,
                    onClick = {
                        if (isSelected) {
                            selectedMembers.remove(disciple.id)
                        } else {
                            if (selectedMembers.size < 5) {
                                selectedMembers.add(disciple.id)
                            }
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                if (teamName.isNotBlank() && selectedLeader != null && selectedMembers.isNotEmpty()) {
                    viewModel.createWarTeam(teamName, selectedLeader!!, selectedMembers.toList())
                    onTeamCreated()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = teamName.isNotBlank() && selectedLeader != null && selectedMembers.isNotEmpty()
        ) {
            Text("创建队伍", fontSize = 12.sp)
        }
    }
}

@Composable
private fun DiscipleSelectCard(
    disciple: com.xianxia.sect.core.model.Disciple,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) GameColors.CardBackgroundSelected else Color.White
        ),
        border = if (isSelected) {
            androidx.compose.foundation.BorderStroke(2.dp, GameColors.Primary)
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = disciple.name,
                fontSize = 13.sp,
                color = GameColors.TextPrimary
            )
            Text(
                text = disciple.realmName,
                fontSize = 12.sp,
                color = getRealmColor(disciple.realm)
            )
        }
    }
}
