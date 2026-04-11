package com.xianxia.sect.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.core.util.sortedByFollowAndRealm
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.state.DialogStateManager.DialogType
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.theme.getRealmColor
import com.xianxia.sect.ui.game.components.InventoryDialog
import com.xianxia.sect.ui.components.discipleCardBorder
import com.xianxia.sect.ui.components.DiscipleAttrText
import com.xianxia.sect.ui.components.DiscipleCardStyles

@Suppress("DEPRECATION")
@Composable
fun GameScreen(
    viewModel: GameViewModel,
    onLogout: () -> Unit
) {
    val disciples by viewModel.disciples.collectAsState()
    val gameData by viewModel.gameData.collectAsState()
    val seeds by viewModel.seeds.collectAsState()
    val equipment by viewModel.equipment.collectAsState()
    val manuals by viewModel.manuals.collectAsState()
    val pills by viewModel.pills.collectAsState()
    val materials by viewModel.materials.collectAsState()
    val herbs by viewModel.herbs.collectAsState()
    val events by viewModel.events.collectAsState()

    val currentDialog by viewModel.dialogStateManager.currentDialog.collectAsState()

    val showRecruitDialog = currentDialog?.type == DialogType.Recruit
    val showInventoryDialog = currentDialog?.type == DialogType.Inventory
    val showDiplomacyDialog = currentDialog?.type == DialogType.Diplomacy
    val showMerchantDialog = currentDialog?.type == DialogType.Merchant
    val showEventLogDialog = currentDialog?.type == DialogType.EventLog
    val showSalaryConfigDialog = currentDialog?.type == DialogType.SalaryConfig

    var selectedRealmFilter by remember { mutableStateOf<Int?>(null) }

    val realmFilters = listOf(
        null to "全部",
        9 to "炼气",
        8 to "筑基",
        7 to "金丹",
        6 to "元婴",
        5 to "化神",
        4 to "炼虚",
        3 to "合体",
        2 to "大乘",
        1 to "渡劫",
        0 to "仙人"
    )

    val filteredDisciples = remember(disciples, selectedRealmFilter) {
        val baseList = if (selectedRealmFilter == null) {
            disciples.filter { it.isAlive }
        } else {
            disciples.filter { it.isAlive && it.realm == selectedRealmFilter }
        }
        baseList.sortedByFollowAndRealm()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(GameColors.PageBackground)
        ) {
            // 顶部标题栏
            GameTopBar(
                sectName = gameData?.sectName ?: "青云宗",
                onLogout = onLogout
            )

            // 快捷按钮栏
            QuickActionBar(
                viewModel = viewModel
            )

            // 境界筛选栏
            RealmFilterBar(
                filters = realmFilters,
                selectedFilter = selectedRealmFilter,
                onFilterSelected = { selectedRealmFilter = it }
            )

            // 弟子列表
            DiscipleList(
                disciples = filteredDisciples,
                modifier = Modifier.weight(1f)
            )
        }

        if (showRecruitDialog) {
            val recruitList by viewModel.recruitListAggregates.collectAsState()
            RecruitDialog(
                recruitList = recruitList,
                gameData = gameData,
                viewModel = viewModel,
                onDismiss = { viewModel.closeRecruitDialog() }
            )
        }

        if (showInventoryDialog) {
            InventoryDialog(
                equipment = equipment,
                manuals = manuals,
                pills = pills,
                materials = materials,
                herbs = herbs,
                seeds = seeds,
                viewModel = viewModel,
                onDismiss = { viewModel.closeInventoryDialog() }
            )
        }

        if (showDiplomacyDialog) {
            DiplomacyDialog(
                gameData = gameData,
                viewModel = viewModel,
                onDismiss = { viewModel.closeDiplomacyDialog() }
            )
        }

        if (showMerchantDialog) {
            MerchantDialog(
                gameData = gameData,
                viewModel = viewModel,
                onDismiss = { viewModel.closeMerchantDialog() }
            )
        }

        if (showEventLogDialog) {
            EventLogDialog(
                events = events,
                onDismiss = { viewModel.closeEventLogDialog() }
            )
        }

        if (showSalaryConfigDialog) {
            SalaryConfigDialog(
                gameData = gameData,
                viewModel = viewModel,
                onDismiss = { viewModel.closeSalaryConfigDialog() }
            )
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun QuickActionBar(
    viewModel: GameViewModel
) {
    val scrollState = rememberScrollState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(GameColors.PageBackground)
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        QuickActionButton(
            text = "招募",
            color = Color.Black,
            onClick = { viewModel.openRecruitDialog() }
        )
        QuickActionButton(
            text = "背包",
            color = Color.Black,
            onClick = { viewModel.openInventoryDialog() }
        )
        QuickActionButton(
            text = "外交",
            color = Color.Black,
            onClick = { viewModel.openDiplomacyDialog() }
        )
        QuickActionButton(
            text = "商人",
            color = Color.Black,
            onClick = { viewModel.openMerchantDialog() }
        )
        QuickActionButton(
            text = "日志",
            color = Color.Black,
            onClick = { viewModel.openEventLogDialog() }
        )
        QuickActionButton(
            text = "月俸",
            color = Color.Black,
            onClick = { viewModel.openSalaryConfigDialog() }
        )
    }
}

@Composable
private fun QuickActionButton(
    text: String,
    color: Color,
    onClick: () -> Unit
) {
    GameButton(
        text = text,
        onClick = onClick,
        modifier = Modifier.height(36.dp)
    )
}

@Composable
private fun GameTopBar(
    sectName: String,
    onLogout: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(GameColors.PageBackground)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = sectName,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )

        GameButton(
            text = "退出",
            onClick = onLogout
        )
    }
}

@Composable
private fun RealmFilterBar(
    filters: List<Pair<Int?, String>>,
    selectedFilter: Int?,
    onFilterSelected: (Int?) -> Unit
) {
    val scrollState = rememberScrollState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(GameColors.PageBackground)
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        filters.forEach { (realm, name) ->
            val isSelected = selectedFilter == realm
            val backgroundColor = if (isSelected) {
                Color.White
            } else {
                Color.White
            }
            val textColor = if (isSelected) Color.Black else Color.Black

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(backgroundColor)
                    .clickable { onFilterSelected(realm) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = name,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = textColor
                )
            }
        }
    }
}

@Composable
private fun DiscipleList(
    disciples: List<DiscipleAggregate>,
    modifier: Modifier = Modifier
) {
    if (disciples.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "暂无弟子",
                fontSize = 12.sp,
                color = Color.Black
            )
        }
    } else {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(
                items = disciples,
                key = { it.id }
            ) { disciple ->
                DiscipleCard(disciple = disciple)
            }
        }
    }
}

@Composable
private fun DiscipleCard(
    disciple: DiscipleAggregate
) {
    val realmColor = getRealmColor(disciple.realm)

    val statusText = disciple.status.displayName

    val statusColor = when (disciple.status) {
        DiscipleStatus.IDLE -> Color(0xFF27AE60)
        DiscipleStatus.DEACONING -> Color(0xFFFF9800)
        DiscipleStatus.MINING -> Color(0xFF8D6E63)
        DiscipleStatus.STUDYING -> Color(0xFF2196F3)
        DiscipleStatus.PREACHING -> Color(0xFF9C27B0)
        DiscipleStatus.MANAGING -> Color(0xFFF44336)
        DiscipleStatus.LAW_ENFORCING -> Color(0xFF607D8B)
        DiscipleStatus.ON_MISSION -> Color(0xFF00BCD4)
        DiscipleStatus.REFLECTING -> Color(0xFF795548)
        DiscipleStatus.IN_TEAM -> Color(0xFF9B59B6)
        DiscipleStatus.DEAD -> Color(0xFF999999)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .discipleCardBorder()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // 第一行：名称 + 状态（状态在最右侧）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = disciple.name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF333333)
                )

                Text(
                    text = statusText,
                    fontSize = 11.sp,
                    color = statusColor
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 第二行：灵根/境界（境界在最右侧）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "灵根: ${disciple.spiritRootName}",
                    fontSize = 11.sp,
                    color = Color(0xFF666666)
                )

                Text(
                    text = disciple.realmName,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF333333)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 第三行：悟性/忠诚/道德
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DiscipleAttrText("悟性", disciple.comprehension)

                DiscipleAttrText("忠诚", disciple.loyalty)

                DiscipleAttrText("道德", disciple.morality)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 修为进度条
            if (disciple.realm != 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "修为",
                        fontSize = 11.sp,
                        color = Color(0xFF666666)
                    )

                    Text(
                        text = "${disciple.cultivation.toInt()}/${disciple.maxCultivation.toInt()}",
                        fontSize = 11.sp,
                        color = Color(0xFF999999)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(GameColors.CardBackground)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = disciple.cultivationProgress.toFloat().coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(
                                realmColor
                            )
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = GameUtils.formatPercent(disciple.cultivationProgress),
                    fontSize = 10.sp,
                    color = Color(0xFF999999)
                )
            }
        }
    }
}
