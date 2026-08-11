package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.xianxia.sect.core.model.ActiveMission
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Mission
import com.xianxia.sect.core.model.MissionDifficulty
import com.xianxia.sect.core.model.MissionRewardConfig
import com.xianxia.sect.core.model.spiritStones
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.DiscipleSlot
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.theme.ButtonSizes
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.DiscipleDetailRequest
import com.xianxia.sect.ui.game.discipleHpFraction
import com.xianxia.sect.ui.components.rememberChasingProgress
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorConfig
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorDialog
import com.xianxia.sect.ui.game.filterByDiscipleStatus
import com.xianxia.sect.ui.game.dialogs.shared.ScrollableInfoDialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle



@Composable
fun MissionHallDialog(
    gameData: GameData?,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    var selectedMission by remember { mutableStateOf<Mission?>(null) }
    var selectedActiveMission by remember { mutableStateOf<ActiveMission?>(null) }
    var showDispatchDialog by remember { mutableStateOf(false) }
    var showActiveMissionDetail by remember { mutableStateOf(false) }

    val activeMissions = gameData?.activeMissions ?: emptyList()
    val availableMissions = gameData?.availableMissions ?: emptyList()
    val currentYear = gameData?.gameYear ?: 1
    val currentMonth = gameData?.gameMonth ?: 1

    // 执行弟子血条真实血量（含血炼口径，与详情页/引擎一致）：装备/功法实例走 viewModel 订阅
    val equipmentInstances by viewModel.equipmentInstances.collectAsStateWithLifecycle()
    val manualInstances by viewModel.manualInstances.collectAsStateWithLifecycle()
    val equipmentMap = remember(equipmentInstances) { equipmentInstances.associateBy { it.id } }
    val manualMap = remember(manualInstances) { manualInstances.associateBy { it.id } }
    val hpRatioById = remember(disciples, equipmentMap, manualMap, gameData) {
        disciples.associate { d ->
            val discipleProficiencies =
                gameData?.manualProficiencies?.get(d.id)?.associateBy { it.manualId } ?: emptyMap()
            d.id to discipleHpFraction(
                d, equipmentMap, manualMap,
                discipleProficiencies,
                gameData?.bloodRefinementPctTotals?.get(d.id)
            )
        }
    }

    val busyDiscipleIds = remember(activeMissions) {
        activeMissions.flatMap { it.discipleIds }.toSet()
    }

    ScrollableInfoDialog(
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
                    color = Color.Black,
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
                    lazyItems(activeMissions, key = { it.id }, contentType = { "active_mission" }) { activeMission ->
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

                    lazyItems(availableMissions, key = { it.id }, contentType = { "available_mission" }) { mission ->
                        AvailableMissionCard(
                            mission = mission,
                            onClick = {
                                selectedMission = mission
                                showDispatchDialog = true
                            }
                        )
                    }
                }
            }
        }
    }

    if (showDispatchDialog) {
        selectedMission?.let { mission ->
            MissionDispatchDialog(
                mission = mission,
                allDisciples = disciples,
                busyDiscipleIds = busyDiscipleIds,
                gameData = gameData,
                viewModel = viewModel,
                onDismiss = {
                    showDispatchDialog = false
                    selectedMission = null
                }
            )
        }
    }

    if (showActiveMissionDetail) {
        selectedActiveMission?.let { mission ->
            ActiveMissionDetailDialog(
                data = ActiveMissionDisplayData(mission, currentYear, currentMonth),
                disciples = disciples,
                hpRatioById = hpRatioById,
                onDiscipleClick = { it?.let { d -> viewModel.showDiscipleDetail(DiscipleDetailRequest(d, disciples)) } },
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
    val animMissionState = rememberChasingProgress(target = progress / 100f)

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
                        color = Color.Black
                    )
                    Text(
                        text = "执行中",
                        fontSize = 10.sp,
                        color = GameColors.Info,
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
                progress = { animMissionState.value },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = GameColors.Info,
                trackColor = GameColors.SurfaceLightGray
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "剩余：$remainingMonths 月",
                    fontSize = 10.sp,
                    color = Color.Black
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
                    color = Color.Black
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
                color = Color.Black
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = mission.difficulty.conditionText,
                    fontSize = 10.sp,
                    color = Color.Black
                )
                Text(
                    text = "耗时：${mission.duration}月",
                    fontSize = 10.sp,
                    color = Color.Black
                )
                Text(
                    text = "需要：${mission.memberCount}名弟子",
                    fontSize = 10.sp,
                    color = Color.Black
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

/** ActiveMissionDetailDialog 展示数据（打包参数，保持 Composable 参数 ≤6 个） */
private data class ActiveMissionDisplayData(
    val mission: ActiveMission,
    val currentYear: Int,
    val currentMonth: Int
)

@Composable
private fun ActiveMissionDetailDialog(
    data: ActiveMissionDisplayData,
    disciples: List<DiscipleAggregate>,
    hpRatioById: Map<String, Float>,
    onDiscipleClick: (DiscipleAggregate?) -> Unit,
    onDismiss: () -> Unit
) {
    val progress = data.mission.getProgressPercent(data.currentYear, data.currentMonth)
    val remainingMonths = data.mission.getRemainingMonths(data.currentYear, data.currentMonth)
    val animMissionState = rememberChasingProgress(target = progress / 100f)

    val discipleMap = remember(disciples) {
        disciples.associateBy { it.id }
    }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = data.mission.missionName,
        mode = DialogMode.Half,
        scrollableContent = false,
        headerActions = {
            Text(
                text = data.mission.difficulty.displayName,
                fontSize = 12.sp,
                color = getDifficultyColor(data.mission.difficulty)
            )
        }
    ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "难度：${data.mission.difficulty.displayName}",
                            fontSize = 11.sp,
                            color = getDifficultyColor(data.mission.difficulty)
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
                        color = Color.Black
                    )

                    LinearProgressIndicator(
                        progress = { animMissionState.value },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = GameColors.Success,
                        trackColor = GameColors.SurfaceLightGray
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "进度：$progress%",
                            fontSize = 11.sp,
                            color = Color.Black
                        )
                        Text(
                            text = "剩余：$remainingMonths 月",
                            fontSize = 11.sp,
                            color = Color.Black
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
                        color = Color.Black
                    )

                    Text(
                        text = formatSpiritStoneReward(data.mission.rewards),
                        fontSize = 11.sp,
                        color = Color(0xFFD4A017)
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = GameColors.Border,
                        thickness = 1.dp
                    )

                    Text(
                        text = "执行弟子 (${data.mission.memberCount}人)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )

                    val gridDiscipleIds = remember(data.mission.discipleIds) {
                        // 防御旧存档重复/空 id（MissionSystem 已加引擎侧校验），
                        // 防 LazyVerticalGrid key="" 重复崩溃（Bugly #5079/#3091）
                        data.mission.discipleIds.distinct()
                            .mapIndexed { index, id -> id.ifBlank { "blank_$index" } }
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.heightIn(max = 240.dp)
                    ) {
                        items(gridDiscipleIds, key = { it }, contentType = { "disciple_id" }) { discipleId ->
                            val index = gridDiscipleIds.indexOf(discipleId)
                            val name = if (index < data.mission.discipleNames.size) data.mission.discipleNames[index] else "未知"
                            val realm = if (index < data.mission.discipleRealms.size) data.mission.discipleRealms[index] else ""
                            val disciple = discipleMap[discipleId]

                            MissionDiscipleSlot(
                                disciple = disciple,
                                hpRatio = hpRatioById[discipleId] ?: 1f,
                                onClick = { onDiscipleClick(disciple) }
                            )
                        }
                    }
    }
}
}

@Composable
private fun MissionDiscipleSlot(
    disciple: DiscipleAggregate?,
    hpRatio: Float,
    onClick: () -> Unit = {}
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // HP bar above slot, width matches slot
        val hpColor = when {
            hpRatio > 0.6f -> GameColors.Success
            hpRatio > 0.3f -> GameColors.Warning
            else -> GameColors.Error
        }
        Box(
            modifier = Modifier
                .width(52.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(GameColors.SurfaceLightGray)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(hpRatio.coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(hpColor)
            )
        }

        DiscipleSlot(
            disciple = disciple,
            onSlotClick = { onClick() }
        )
    }
}

private fun formatSpiritStoneReward(rewards: MissionRewardConfig): String {
    val parts = mutableListOf<String>()
    if (rewards.spiritStones > 0 || rewards.spiritStonesMax > 0) {
        if (rewards.spiritStonesMax > 0) {
            parts.add("${GameUtils.formatNumber(rewards.spiritStones)}~${GameUtils.formatNumber(rewards.spiritStonesMax)}灵石")
        } else {
            parts.add("${GameUtils.formatNumber(rewards.spiritStones)}灵石")
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
        MissionDifficulty.SIMPLE -> GameColors.Success
        MissionDifficulty.NORMAL -> GameColors.Info
        MissionDifficulty.HARD -> GameColors.Warning
        MissionDifficulty.FORBIDDEN -> GameColors.Error
    }
}

@Composable
private fun MissionDispatchDialog(
    mission: Mission,
    allDisciples: List<DiscipleAggregate>,
    busyDiscipleIds: Set<String>,
    gameData: GameData?,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val selectedSlotIds = remember { mutableStateListOf<String?>(*Array(6) { null }) }
    var selectingSlotIndex by remember { mutableStateOf(-1) }

    val showAllEnabled = gameData?.showAllAvailableDisciples ?: false
    val battleAndExplorationIds = remember(gameData) {
        if (gameData != null) {
            val battleIds = gameData.battleTeams.flatMap { it.slots.map { it.discipleId } }.filter { it.isNotEmpty() }.toSet()
            val explorationIds = gameData.caveExplorationTeams.flatMap { it.memberIds }.filter { it.isNotEmpty() }.toSet()
            battleIds + explorationIds
        } else {
            emptySet()
        }
    }

    val eligibleDisciples = remember(allDisciples, busyDiscipleIds, showAllEnabled, battleAndExplorationIds) {
        allDisciples.filterByDiscipleStatus(showAllEnabled, battleAndExplorationIds, additionalCheck = { disciple ->
            disciple.id !in busyDiscipleIds
        })
    }

    val filledCount = selectedSlotIds.filterNotNull().size
    val discipleMap = remember(allDisciples) { allDisciples.associateBy { it.id } }

    if (selectingSlotIndex >= 0) {
        val alreadySelected = selectedSlotIds.filterNotNull().toSet()
        val availableForSlot = eligibleDisciples.filter {
            it.id !in alreadySelected || it.id == selectedSlotIds[selectingSlotIndex]
        }
        DiscipleSelectorDialog(
            config = DiscipleSelectorConfig(
                title = "选择弟子"
            ),
            disciples = availableForSlot,
            onConfirm = { selected ->
                selectedSlotIds[selectingSlotIndex] = selected.firstOrNull()?.id
                selectingSlotIndex = -1
            },
            onDismiss = { selectingSlotIndex = -1 },
            viewModel = viewModel,
            showAllEnabled = showAllEnabled,
            battleAndExplorationIds = battleAndExplorationIds
        )
    } else {
        UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "派遣队伍",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Mission info card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = mission.name,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = mission.difficulty.displayName,
                            fontSize = 10.sp,
                            color = getDifficultyColor(mission.difficulty)
                        )
                        Text(
                            text = mission.difficulty.conditionText,
                            fontSize = 10.sp,
                            color = Color.Black
                        )
                        Text(
                            text = "耗时：${mission.duration}月",
                            fontSize = 10.sp,
                            color = Color.Black
                        )
                        Text(
                            text = "奖励：${formatSpiritStoneReward(mission.rewards)}",
                            fontSize = 10.sp,
                            color = Color(0xFFD4A017)
                        )
                    }
                }
            }

            // One-click assign button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                GameButton(
                    text = "一键任命",
                    onClick = {
                        val alreadySelected = selectedSlotIds.filterNotNull().toSet()
                        val available = eligibleDisciples
                            .filter { it.id !in alreadySelected }
                            .sortedBy { it.realm }
                        var idx = 0
                        for (slot in 0 until 6) {
                            if (selectedSlotIds[slot] == null && idx < available.size) {
                                selectedSlotIds[slot] = available[idx].id
                                idx++
                            }
                        }
                    },
                    modifier = Modifier.width(ButtonSizes.StandardWidth)
                )
            }

            // Disciple slot grid header
            Text(
                text = "派遣弟子 (${filledCount}/6)",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )

            // 3×2 slot grid
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (row in 0..1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        for (col in 0..2) {
                            val slotIndex = row * 3 + col
                            val discipleId = selectedSlotIds[slotIndex]
                            val disciple = discipleId?.let { discipleMap[it] }
                            DiscipleSlot(
                                disciple = disciple,
                                showActions = true,
                                onSlotClick = {
                                    if (disciple != null) {
                                        viewModel.showDiscipleDetail(DiscipleDetailRequest(disciple, allDisciples))
                                    }
                                },
                                onEmptySlotClick = {
                                    selectingSlotIndex = slotIndex
                                },
                                onDismiss = {
                                    selectedSlotIds[slotIndex] = null
                                },
                                onSwap = {
                                    selectingSlotIndex = slotIndex
                                }
                            )
                        }
                    }
                }
            }

            // Bottom buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                GameButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.width(ButtonSizes.StandardWidth)
                )
                GameButton(
                    text = "派遣",
                    enabled = filledCount == 6,
                    onClick = {
                        val selected = eligibleDisciples.filter { it.id in selectedSlotIds.filterNotNull() }
                        viewModel.startMission(mission, selected)
                        onDismiss()
                    },
                    modifier = Modifier.width(ButtonSizes.StandardWidth)
                )
            }
        }
    }
    }

}

