package com.xianxia.sect.ui.game

import androidx.compose.foundation.background
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
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.theme.getRealmColor
import com.xianxia.sect.ui.game.components.InventoryDialog

@Composable
fun GameScreen(
    viewModel: GameViewModel,
    onLogout: () -> Unit
) {
    val disciples by viewModel.disciples.collectAsState()
    val gameData by viewModel.gameData.collectAsState()
    val plantSlots = gameData?.herbGardenPlantSlots ?: emptyList()
    val seeds by viewModel.seeds.collectAsState()
    val equipment by viewModel.equipment.collectAsState()
    val manuals by viewModel.manuals.collectAsState()
    val pills by viewModel.pills.collectAsState()
    val materials by viewModel.materials.collectAsState()
    val herbs by viewModel.herbs.collectAsState()
    val events by viewModel.events.collectAsState()

    val showRecruitDialog by viewModel.showRecruitDialog.collectAsState()
    val showInventoryDialog by viewModel.showInventoryDialog.collectAsState()
    val showDiplomacyDialog by viewModel.showDiplomacyDialog.collectAsState()
    val showMerchantDialog by viewModel.showMerchantDialog.collectAsState()
    val showEventLogDialog by viewModel.showEventLogDialog.collectAsState()
    val showSalaryConfigDialog by viewModel.showSalaryConfigDialog.collectAsState()

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
        baseList.sortedWith(
            compareBy<Disciple> { it.realm }
                .thenByDescending { it.realmLayer }
                .thenByDescending { it.cultivationProgress }
        )
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
            RecruitDialog(
                recruitList = gameData?.recruitList ?: emptyList(),
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

        TextButton(onClick = onLogout) {
            Text(
                text = "退出",
                fontSize = 12.sp,
                color = Color.Black
            )
        }
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
    disciples: List<Disciple>,
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
    disciple: Disciple
) {
    val realmColor = getRealmColor(disciple.realm)

    val statusText = when (disciple.status.name) {
        "IDLE" -> "空闲"
        "CULTIVATING" -> "修炼中"
        "WORKING" -> "工作中"
        "EXPLORING" -> "探索中"
        else -> disciple.status.name
    }

    val statusColor = when (disciple.status.name) {
        "IDLE" -> Color(0xFF27AE60)
        "CULTIVATING" -> Color(0xFF3498DB)
        "WORKING" -> Color(0xFFF39C12)
        "EXPLORING" -> Color(0xFF9B59B6)
        else -> Color(0xFF666666)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = GameColors.PageBackground
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp
        )
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
                Text(
                    text = "悟性: ${disciple.comprehension}",
                    fontSize = 11.sp,
                    color = Color(0xFF666666)
                )

                Text(
                    text = "忠诚: ${disciple.loyalty}",
                    fontSize = 11.sp,
                    color = Color(0xFF666666)
                )

                Text(
                    text = "道德: ${disciple.morality}",
                    fontSize = 11.sp,
                    color = Color(0xFF666666)
                )
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
