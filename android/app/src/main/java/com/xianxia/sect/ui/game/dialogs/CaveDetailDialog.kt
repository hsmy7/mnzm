package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import com.xianxia.sect.core.model.CaveStatus
import com.xianxia.sect.core.model.CultivatorCave
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.util.sortedByFollowAndRealm
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.WorldMapViewModel
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorConfig
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorDialog
import com.xianxia.sect.ui.theme.GameColors

@Composable
internal fun CaveDetailDialog(
    cave: CultivatorCave,
    gameData: GameData?,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    worldMapViewModel: WorldMapViewModel,
    onDismiss: () -> Unit
) {
    val currentYear = gameData?.gameYear ?: 1
    val currentMonth = gameData?.gameMonth ?: 1
    val remainingMonths = cave.getRemainingMonths(currentYear, currentMonth)

    var showDiscipleSelection by remember { mutableStateOf(false) }
    var selectedDisciple by remember { mutableStateOf<DiscipleAggregate?>(null) }

    val statusColor = when (cave.status) {
        CaveStatus.AVAILABLE -> Color(0xFF9C27B0)
        CaveStatus.EXPLORING -> Color(0xFFFF9800)
        CaveStatus.EXPLORED -> Color(0xFF4CAF50)
        CaveStatus.EXPIRED -> Color(0xFF9E9E9E)
    }

    val statusText = when (cave.status) {
        CaveStatus.AVAILABLE -> "可探索"
        CaveStatus.EXPLORING -> "探索中"
        CaveStatus.EXPLORED -> "已探索"
        CaveStatus.EXPIRED -> "已消失"
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = cave.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Text(
                        text = statusText,
                        fontSize = 10.sp,
                        color = Color.White,
                        modifier = Modifier
                            .background(statusColor, RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
                CloseButton(onClick = onDismiss)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "洞府境界",
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                        Text(
                            text = cave.ownerRealmName,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }

                    if (cave.status != CaveStatus.EXPIRED) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "剩余时间",
                                fontSize = 12.sp,
                                color = Color.Black
                            )
                            Text(
                                text = "${remainingMonths}月",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (remainingMonths <= 3) Color(0xFFF44336) else Color.Black
                            )
                        }
                    }
                }

                HorizontalDivider(color = GameColors.Border, thickness = 1.dp)

                when (cave.status) {
                    CaveStatus.AVAILABLE -> {
                        Text(
                            text = "此洞府尚未被探索，派遣弟子前往探索可获得丰厚奖励。",
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                        if (selectedDisciple != null) {
                            Text(
                                text = "已选择: ${selectedDisciple?.name}",
                                fontSize = 11.sp,
                                color = Color(0xFF4CAF50)
                            )
                        }
                    }
                    CaveStatus.EXPLORING -> {
                        val exploringTeam = gameData?.caveExplorationTeams?.find { it.caveId == cave.id }
                        if (exploringTeam != null) {
                            val progress = exploringTeam.getProgressPercent(currentYear, currentMonth)
                            Column {
                                Text(
                                    text = "探索队伍: ${exploringTeam.memberNames.joinToString("、")}",
                                    fontSize = 12.sp,
                                    color = Color.Black
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    LinearProgressIndicator(
                                        progress = { progress / 100f },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(4.dp)),
                                        color = Color(0xFF4CAF50),
                                        trackColor = GameColors.Border
                                    )
                                    Text(
                                        text = "$progress%",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF4CAF50)
                                    )
                                }
                            }
                        }
                    }
                    CaveStatus.EXPLORED -> {
                        Text(
                            text = "此洞府已被探索完毕。",
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                    }
                    CaveStatus.EXPIRED -> {
                        Text(
                            text = "此洞府已经消失，无法再进行探索。",
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (cave.status == CaveStatus.AVAILABLE) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (selectedDisciple != null) {
                        GameButton(
                            text = "确认派遣",
                            onClick = {
                                selectedDisciple?.let {
                                    worldMapViewModel.startCaveExploration(cave, listOf(it))
                                }
                                onDismiss()
                            }
                        )
                    }
                    GameButton(
                        text = if (selectedDisciple == null) "选择弟子" else "修改选择",
                        onClick = { showDiscipleSelection = true }
                    )
                }
            }
        },
        dismissButton = null
    )

    if (showDiscipleSelection) {
        val availableCaveDisciples = remember(disciples, cave.ownerRealm) {
            disciples.filter { disciple ->
                disciple.isAlive &&
                disciple.status == DiscipleStatus.IDLE &&
                disciple.realmLayer > 0 &&
                disciple.age >= 5 &&
                disciple.realm <= cave.ownerRealm
            }.sortedByFollowAndRealm()
        }
        DiscipleSelectorDialog(
            config = DiscipleSelectorConfig(title = "选择探索弟子"),
            disciples = availableCaveDisciples,
            onDismiss = { showDiscipleSelection = false },
            onConfirm = { selected ->
                selectedDisciple = selected.firstOrNull()
                showDiscipleSelection = false
            }
        )
    }
}
