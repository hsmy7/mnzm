package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.BattleLogAction
import com.xianxia.sect.core.model.BattleLogRound
import com.xianxia.sect.core.model.BattleResult
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.core.model.YearlyReport
import com.xianxia.sect.ui.components.clickableWithSound
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*


/** 战斗过程标题 */
@Composable
internal fun BattleRoundsHeader() {
    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider(color = GameColors.SurfaceLightGray, thickness = 1.dp)
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = "战斗过程",
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = Color.Black
    )
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
internal fun BattleRoundItem(
    round: BattleLogRound
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = "第${round.roundNumber}回合",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )

        round.actions.forEach { action ->
            BattleActionItem(action = action)
        }
    }
}

@Composable
internal fun BattleActionItem(
    action: BattleLogAction
) {
    val actionColor = when {
        action.isKill -> GameColors.Error
        action.isCrit -> GameColors.Warning
        else -> Color.Black
    }

    val typeIcon = when (action.type) {
        "skill" -> "✦"
        "support" -> "♡"
        else -> "⚔"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 2.dp)
    ) {
        if (action.message.isNotEmpty()) {
            Text(
                text = "$typeIcon ${action.message}",
                fontSize = 10.sp,
                color = actionColor
            )
        } else {
            val critText = if (action.isCrit) " [暴击]" else ""
            val killText = if (action.isKill) " [击杀]" else ""
            val skillText = action.skillName?.let { " [$it]" } ?: ""
            Text(
                text = "$typeIcon ${action.attacker} " +
                    "→ ${action.target}: ${action.damage}${skillText}${critText}${killText}",
                fontSize = 10.sp,
                color = actionColor
            )
        }
    }
}

@Composable
internal fun BattleLogListDialog(
    battleLogs: List<BattleLog>,
    yearlyReports: List<YearlyReport> = emptyList(),
    onDismiss: () -> Unit
) {
    var selectedBattleLog by remember { mutableStateOf<BattleLog?>(null) }
    var selectedTab by remember { mutableStateOf(BattleLogTab.LOGS) }
    var selectedReport by remember { mutableStateOf<YearlyReport?>(null) }
    val recentLogs = remember(battleLogs) {
        battleLogs.sortedByDescending { it.timestamp }.take(30)
    }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "日志",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        Column(Modifier.fillMaxSize()) {
            // 标签栏（同 MerchantDialog 模式）
            BattleLogTabBar(selectedTab = selectedTab, onTabSelected = { selectedTab = it })

            HorizontalDivider(color = GameColors.SurfaceLightGray, thickness = 1.dp)

            // 内容区必须用 weight(1f) 约束高度，否则内部 LazyColumn 会收到无穷高度报错
            BattleLogTabContent(
                selectedTab = selectedTab,
                recentLogs = recentLogs,
                yearlyReports = yearlyReports,
                onLogClick = { selectedBattleLog = it },
                onReportClick = { selectedReport = it }
            )
        }
    }

    selectedBattleLog?.let { log ->
        BattleLogDetailDialog(
            log = log,
            onDismiss = { selectedBattleLog = null }
        )
    }

    selectedReport?.let { report ->
        YearlyReportDetailDialog(
            report = report,
            onDismiss = { selectedReport = null }
        )
    }
}

/** 日志对话框标签栏 */
@Composable
internal fun BattleLogTabBar(
    selectedTab: BattleLogTab,
    onTabSelected: (BattleLogTab) -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        BattleLogTab.entries.forEach { tab ->
            val isActive = selectedTab == tab
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f).clickableWithSound { onTabSelected(tab) }
            ) {
                Text(tab.label, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = if (isActive) Color.Black else Color.Gray)
                Box(Modifier.fillMaxWidth().height(2.dp)
                    .background(if (isActive) GameColors.GoldDark else Color.Gray))
            }
        }
    }
}

/** 标签页内容区 */
@Composable
internal fun ColumnScope.BattleLogTabContent(
    selectedTab: BattleLogTab,
    recentLogs: List<BattleLog>,
    yearlyReports: List<YearlyReport>,
    onLogClick: (BattleLog) -> Unit,
    onReportClick: (YearlyReport) -> Unit
) {
    Box(Modifier.weight(1f).fillMaxWidth()) {
        when (selectedTab) {
            BattleLogTab.LOGS -> {
                if (recentLogs.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无战斗记录", fontSize = 14.sp, color = Color.Black)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(recentLogs, key = { it.id }, contentType = { "battle_log" }) { log ->
                            BattleLogListItem(log = log, onClick = { onLogClick(log) })
                        }
                    }
                }
            }
            BattleLogTab.REPORT -> {
                YearlyReportList(
                    reports = yearlyReports,
                    onDetail = onReportClick
                )
            }
        }
    }
}

@Composable
internal fun BattleLogListItem(
    log: BattleLog,
    onClick: () -> Unit
) {
    val resultColor = when (log.result) {
        BattleResult.WIN -> GameColors.Success
        BattleResult.LOSE -> GameColors.Error
        BattleResult.DRAW -> GameColors.Warning
    }

    val resultText = when (log.result) {
        BattleResult.WIN -> "胜利"
        BattleResult.LOSE -> "失败"
        BattleResult.DRAW -> "平局"
    }

    val typeText = resolveBattleTypeName(log)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickableWithSound(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8F8F8)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = typeText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Text(
                        text = "第${log.year}年${log.month}月",
                        fontSize = 11.sp,
                        color = Color.Black
                    )
                }
                Text(
                    text = "回合: ${log.turns} | 敌人: ${log.enemies.size}",
                    fontSize = 10.sp,
                    color = Color.Black
                )
            }

            BattleResultBadge(resultColor = resultColor, resultText = resultText)
        }
    }
}
