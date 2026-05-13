package com.xianxia.sect.ui.game

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Dialog
import com.xianxia.sect.R
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Talent
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.theme.ButtonSizes
import com.xianxia.sect.ui.theme.getSpiritRootColor
import com.xianxia.sect.ui.components.discipleCardBorder
import com.xianxia.sect.ui.components.DiscipleAttrText
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.PortraitDiscipleCard
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.TalentDetailDialog
import com.xianxia.sect.ui.components.getTalentRarityColor
import com.xianxia.sect.ui.components.HalfScreenDialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun RecruitDialog(
    recruitList: List<DiscipleAggregate>,
    gameData: GameData?,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    var showAutoRecruitDialog by remember { mutableStateOf(false) }

    HalfScreenDialog(onDismissRequest = onDismiss, isFullScreen = true) {
        Column(modifier = Modifier.fillMaxSize()) {
                RecruitHeader(
                    gameData = gameData,
                    onDismiss = onDismiss,
                    onAutoRecruitClick = { showAutoRecruitDialog = true }
                )

                if (recruitList.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无可招募弟子\n请等待每年一月刷新",
                            fontSize = 12.sp,
                            color = GameColors.TextSecondary
                        )
                    }
                } else {
                    val sortedRecruitList = remember(recruitList) {
                        recruitList.sortedBy { it.spiritRoot.types.size }
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(sortedRecruitList, key = { it.id }) { disciple ->
                            PortraitDiscipleCard(
                                disciple = disciple,
                                isSelected = false,
                                actions = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        GameButton(
                                            text = "拒绝",
                                            onClick = { viewModel.rejectDiscipleFromList(disciple.id) },
                                            modifier = Modifier.width(ButtonSizes.StandardWidth)
                                        )
                                        GameButton(
                                            text = "同意",
                                            onClick = { viewModel.recruitDisciple(disciple) },
                                            modifier = Modifier.width(ButtonSizes.StandardWidth)
                                        )
                                    }
                                },
                                onClick = {}
                            )
                        }
                    }
                }
            }
        }
    if (showAutoRecruitDialog) {
        AutoRecruitFilterDialog(
            gameData = gameData,
            viewModel = viewModel,
            onDismiss = { showAutoRecruitDialog = false }
        )
    }
}

@Composable
private fun RecruitHeader(
    gameData: GameData?,
    onDismiss: () -> Unit,
    onAutoRecruitClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "弟子招募",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            Text(
                text = "当前灵石: ${gameData?.spiritStones ?: 0}",
                fontSize = 12.sp,
                color = GameColors.TextSecondary
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GameButton(
                text = "自动招募",
                onClick = onAutoRecruitClick
            )
            CloseButton(onClick = onDismiss)
        }
    }
}

private val ROOT_COUNT_OPTIONS = listOf(
    1 to "单灵根",
    2 to "双灵根",
    3 to "三灵根",
    4 to "四灵根",
    5 to "五灵根"
)

@Composable
private fun AutoRecruitFilterDialog(
    gameData: GameData?,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val initialFilter = gameData?.autoRecruitSpiritRootFilter ?: emptySet()
    var selectedFilter by remember { mutableStateOf(initialFilter) }

    HalfScreenDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
                Text(
                    text = "自动招募筛选",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ROOT_COUNT_OPTIONS.forEach { (count, name) ->
                        val rootColor = GameColors.getSpiritRootCountColor(count)
                        AutoRecruitFilterRow(
                            label = name,
                            labelColor = rootColor,
                            checked = count in selectedFilter,
                            onToggle = {
                                selectedFilter = if (count in selectedFilter) {
                                    selectedFilter - count
                                } else {
                                    selectedFilter + count
                                }
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                GameButton(
                    text = "保存",
                    onClick = {
                        viewModel.setAutoRecruitFilter(selectedFilter)
                        onDismiss()
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
        }
    }
}

@Composable
private fun AutoRecruitFilterRow(
    label: String,
    labelColor: Color,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = labelColor
        )

        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .border(
                    width = 1.5.dp,
                    color = if (checked) GameColors.Success else GameColors.Border,
                    shape = CircleShape
                )
                .background(
                    color = if (checked) GameColors.Success.copy(alpha = 0.15f) else Color.Transparent,
                    shape = CircleShape
                )
                .clickable { onToggle() },
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Text(
                    text = "✓",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = GameColors.Success
                )
            }
        }
    }
}
