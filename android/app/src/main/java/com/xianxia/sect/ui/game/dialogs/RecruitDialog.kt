package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Talent
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.theme.ButtonSizes
import com.xianxia.sect.ui.theme.getSpiritRootColor
import com.xianxia.sect.ui.components.discipleCardBorder
import com.xianxia.sect.ui.components.DiscipleAttrText
import com.xianxia.sect.ui.components.PortraitDiscipleCard
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.TalentDetailDialog
import com.xianxia.sect.ui.components.getTalentRarityColor
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalConfiguration

@Composable
fun RecruitDialog(
    recruitList: List<DiscipleAggregate>,
    gameData: GameData?,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    var showAutoRecruitDialog by remember { mutableStateOf(false) }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "弟子招募",
        mode = DialogMode.Full,
        scrollableContent = false,
        headerActions = {
            GameButton(
                text = "自动招募",
                onClick = { showAutoRecruitDialog = true }
            )
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "当前灵石: ${gameData?.spiritStones ?: 0}",
                    fontSize = 12.sp,
                    color = GameColors.TextSecondary,
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 8.dp)
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
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
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
    val hasChanges = selectedFilter != initialFilter
    var showUnsavedDialog by remember { mutableStateOf(false) }

    val saveAndDismiss = {
        viewModel.setAutoRecruitFilter(selectedFilter)
        onDismiss()
    }

    val handleClose = {
        if (hasChanges) {
            showUnsavedDialog = true
        } else {
            onDismiss()
        }
    }

    UnifiedGameDialog(
        onDismissRequest = handleClose,
        title = "自动招募筛选",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp)
            ) {

                Spacer(modifier = Modifier.height(12.dp))

                // 5-column filter grid
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(ROOT_COUNT_OPTIONS, key = { it.first }) { (count, name) ->
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

                Spacer(modifier = Modifier.height(12.dp))

                // Bottom buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    GameButton(text = "取消", onClick = onDismiss)
                    GameButton(text = "保存", onClick = saveAndDismiss)
                }
            }

            if (showUnsavedDialog) {
                val config = LocalConfiguration.current
                val dialogWidth = (config.screenWidthDp * 0.4f).dp
                val dialogHeight = (config.screenHeightDp * 0.45f).dp
                Dialog(
                    onDismissRequest = { showUnsavedDialog = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
                ) {
                    Box(
                        modifier = Modifier
                            .width(dialogWidth)
                            .height(dialogHeight)
                            .clip(RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.dialog_box),
                            contentDescription = null,
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.FillBounds
                        )
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "未保存更改",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "您所做的更改尚未保存，若直接退出则视为取消更改",
                                fontSize = 12.sp,
                                color = Color.Black
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                GameButton(text = "关闭", onClick = {
                                    showUnsavedDialog = false
                                    onDismiss()
                                })
                                GameButton(text = "保存", onClick = {
                                    showUnsavedDialog = false
                                    saveAndDismiss()
                                })
                            }
                        }
                    }
                }
            }
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
                    color = Color.Black,
                    shape = CircleShape
                )
                .background(
                    color = if (checked) Color.Black.copy(alpha = 0.15f) else Color.Transparent,
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
                    color = Color.Black
                )
            }
        }
    }
}
