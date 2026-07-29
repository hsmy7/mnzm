package com.xianxia.sect.ui.game.dialogs

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
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Talent
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.theme.getSpiritRootColor
import com.xianxia.sect.ui.components.discipleCardBorder
import com.xianxia.sect.ui.components.DiscipleAttrText
import com.xianxia.sect.ui.components.PortraitDiscipleCard
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.getTalentRarityColor
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.StandardPromptDialog

@Composable
fun RecruitDialog(
    recruitList: List<DiscipleAggregate>,
    gameData: GameData?,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    var showRecruitManagementDialog by remember { mutableStateOf(false) }
    var showRejectConfirm by remember { mutableStateOf<String?>(null) }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "弟子招募",
        mode = DialogMode.Full,
        scrollableContent = false,
        headerActions = {
            GameButton(
                text = "招募管理",
                onClick = { showRecruitManagementDialog = true }
            )
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
                if (recruitList.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无可招募弟子\n招募每3年刷新一次，请耐心等待",
                            fontSize = 12.sp,
                            color = GameColors.TextSecondary
                        )
                    }
                } else {
                    val sortedRecruitList = remember(recruitList) {
                        recruitList.sortedBy { it.spiritRoot.types.count { it.isNotBlank() } }
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
                        items(sortedRecruitList, key = { it.id }, contentType = { "disciple" }) { disciple ->
                            PortraitDiscipleCard(
                                disciple = disciple,
                                isSelected = false,
                                showStatus = false,
                                actions = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        GameButton(
                                            text = "拒绝",
                                            onClick = { showRejectConfirm = disciple.id },
                                            modifier = Modifier.weight(1f)
                                        )
                                        GameButton(
                                            text = "同意",
                                            onClick = { viewModel.recruitDisciple(disciple) },
                                            modifier = Modifier.weight(1f)
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
    if (showRecruitManagementDialog) {
        RecruitManagementDialog(
            gameData = gameData,
            viewModel = viewModel,
            onDismiss = { showRecruitManagementDialog = false }
        )
    }

    // F8: 拒绝确认对话框
    if (showRejectConfirm != null) {
        StandardPromptDialog(
            onDismissRequest = { showRejectConfirm = null },
            title = "确认拒绝",
            text = "确定要拒绝该弟子吗？拒绝后将无法恢复，下一年度才可能刷新到新弟子。",
            confirmLabel = "拒绝",
            onConfirm = {
                val id = showRejectConfirm ?: return@StandardPromptDialog
                viewModel.rejectDiscipleFromList(id)
                showRejectConfirm = null
            },
            dismissLabel = "取消",
            onDismiss = { showRejectConfirm = null }
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
private fun RecruitManagementDialog(
    gameData: GameData?,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val initialRecruitFilter = gameData?.autoRecruitSpiritRootFilter ?: emptySet()
    val initialRejectFilter = gameData?.autoRejectSpiritRootFilter ?: emptySet()
    var recruitFilter by remember { mutableStateOf(initialRecruitFilter) }
    var rejectFilter by remember { mutableStateOf(initialRejectFilter) }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "招募管理",
        mode = DialogMode.Half,
        scrollableContent = true
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // ═══ 区域 1：自动招募 ═══
            Text(
                text = "自动招募",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            Spacer(modifier = Modifier.height(8.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(ROOT_COUNT_OPTIONS, key = { it.first }, contentType = { "root_count_option" }) { (count, name) ->
                    val baseColor = GameColors.getSpiritRootCountColor(count)
                    val rootColor = if (count == 5) Color.Black else baseColor
                    AutoRecruitFilterRow(
                        label = name,
                        labelColor = rootColor,
                        checked = count in recruitFilter,
                        onToggle = {
                            val newFilter = if (count in recruitFilter) {
                                recruitFilter - count
                            } else {
                                recruitFilter + count
                            }
                            recruitFilter = newFilter
                            viewModel.setAutoRecruitFilter(newFilter)
                        }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp),
                color = GameColors.Border, thickness = 1.dp)

            // ═══ 区域 2：自动拒绝 ═══
            Text(
                text = "自动拒绝",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            Spacer(modifier = Modifier.height(8.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(ROOT_COUNT_OPTIONS, key = { it.first }, contentType = { "root_count_option" }) { (count, name) ->
                    val baseColor = GameColors.getSpiritRootCountColor(count)
                    val rootColor = if (count == 5) Color.Black else baseColor
                    AutoRecruitFilterRow(
                        label = name,
                        labelColor = rootColor,
                        checked = count in rejectFilter,
                        onToggle = {
                            val newFilter = if (count in rejectFilter) {
                                rejectFilter - count
                            } else {
                                rejectFilter + count
                            }
                            rejectFilter = newFilter
                            viewModel.setAutoRejectFilter(newFilter)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Bottom button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                GameButton(text = "关闭", onClick = onDismiss)
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
        horizontalArrangement = Arrangement.spacedBy(4.dp),
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
