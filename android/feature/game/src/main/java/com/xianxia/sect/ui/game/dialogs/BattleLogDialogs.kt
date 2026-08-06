package com.xianxia.sect.ui.game.dialogs

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.ui.components.beastSpriteRes
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.BattleLogAction
import com.xianxia.sect.core.model.BattleLogRound
import com.xianxia.sect.core.model.BattleResult
import com.xianxia.sect.core.model.BattleType
import com.xianxia.sect.ui.components.BattleParticipantSlot
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.YearlyReport
import com.xianxia.sect.ui.components.clickableWithSound

/** 日志对话框标签页枚举 */
private enum class BattleLogTab(val label: String) {
    LOGS("战斗日志"),
    REPORT("年报日志")
}

private fun resolveBeastImageRes(enemyName: String): Int? {
    val idx = GameConfig.Beast.TYPES.indexOfFirst { enemyName.endsWith(it.name) }
    return if (idx >= 0) beastSpriteRes(idx) else null
}

/**
 * 推断战斗日志的具体战斗名称。
 * PVE 被妖兽战和任务战复用，需结合 details 区分。
 */
private fun resolveBattleTypeName(log: BattleLog): String = when (log.type) {
    BattleType.SECT_WAR ->
        if (log.attackerName == "玩家队伍") "宗门战" else "宗门防守战"
    BattleType.SCOUT -> "探查战"
    BattleType.CAVE_EXPLORATION -> "洞府战"
    BattleType.PVE ->
        if (log.details.contains("任务")) "任务战" else "妖兽战"
    BattleType.PVP -> "PVP战斗"
    BattleType.ENCOUNTER -> "遭遇战"
}

@Composable
internal fun BattleLogDetailDialog(
    log: BattleLog,
    onDismiss: () -> Unit,
    scrimEnabled: Boolean = true
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

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "战斗详情",
        mode = DialogMode.Half,
        scrollableContent = false,
        scrimEnabled = scrimEnabled
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
                HorizontalDivider(color = GameColors.SurfaceLightGray, thickness = 1.dp)

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "第${log.year}年${log.month}月",
                                fontSize = 12.sp,
                                color = Color.Black
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(resultColor)
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = resultText,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "战斗回合: ${log.turns}",
                            fontSize = 11.sp,
                            color = Color.Black
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "我方弟子",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    itemsIndexed(log.teamMembers.chunked(4), key = { index, _ -> "team_$index" }) { index, rowMembers ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                        ) {
                            rowMembers.forEach { member ->
                                BattleParticipantSlot(
                                    name = member.name,
                                    realmName = member.realmName,
                                    hp = member.hp,
                                    maxHp = member.maxHp,
                                    isAlive = member.isAlive,
                                    portraitRes = member.portraitRes
                                )
                            }
                            repeat(4 - rowMembers.size) {
                                Spacer(modifier = Modifier.width(52.dp).height(84.dp))
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = when (log.type) {
                                BattleType.PVE -> "敌方妖兽"
                                BattleType.SECT_WAR, BattleType.SCOUT -> "敌方宗门弟子"
                                BattleType.CAVE_EXPLORATION -> "敌方守护兽"
                                else -> "敌方"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    itemsIndexed(log.enemies.chunked(4), key = { index, _ -> "enemy_$index" }) { index, rowEnemies ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                        ) {
                            rowEnemies.forEach { enemy ->
                                val portraitRes = enemy.portraitRes.ifEmpty {
                                    val beastResId = resolveBeastImageRes(enemy.name)
                                    if (beastResId != null) "beast_$beastResId" else ""
                                }
                                BattleParticipantSlot(
                                    name = enemy.name,
                                    realmName = enemy.realmName,
                                    hp = enemy.hp,
                                    maxHp = enemy.maxHp,
                                    isAlive = enemy.isAlive,
                                    portraitRes = portraitRes
                                )
                            }
                            repeat(4 - rowEnemies.size) {
                                Spacer(modifier = Modifier.width(52.dp).height(84.dp))
                            }
                        }
                    }

                    // 战利品/被掠夺物品（敌方槽位区域下方）
                    if (log.drops.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = GameColors.SurfaceLightGray, thickness = 1.dp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (log.result == BattleResult.LOSE) "被掠夺物品" else "战利品",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            log.drops.forEach { drop ->
                                Text(
                                    text = "· $drop",
                                    fontSize = 11.sp,
                                    color = Color(0xFF555555)
                                )
                            }
                        }
                    }

                    if (log.rounds.isNotEmpty()) {
                        item {
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

                        itemsIndexed(log.rounds, key = { index, round -> "round_${round.roundNumber}_$index" }) { _, round ->
                            BattleRoundItem(round = round)
                        }
                    }
                }
            }
    }
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

    val typeColor = when (action.type) {
        "skill" -> Color(0xFF9C27B0)
        "support" -> GameColors.Success
        else -> Color.Black
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
                text = "$typeIcon ${action.attacker} → ${action.target}: ${action.damage}${skillText}${critText}${killText}",
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
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                BattleLogTab.entries.forEach { tab ->
                    val isActive = selectedTab == tab
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f).clickableWithSound { selectedTab = tab }
                    ) {
                        Text(tab.label, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            color = if (isActive) Color.Black else Color.Gray)
                        Box(Modifier.fillMaxWidth().height(2.dp)
                            .background(if (isActive) GameColors.GoldDark else Color.Gray))
                    }
                }
            }

            HorizontalDivider(color = GameColors.SurfaceLightGray, thickness = 1.dp)

            // 内容区必须用 weight(1f) 约束高度，否则内部 LazyColumn 会收到无穷高度报错
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
                                    BattleLogListItem(log = log, onClick = { selectedBattleLog = log })
                                }
                            }
                        }
                    }
                    BattleLogTab.REPORT -> {
                        YearlyReportList(
                            reports = yearlyReports,
                            onDetail = { selectedReport = it }
                        )
                    }
                }
            }
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

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(resultColor)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text = resultText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
// 年报日志组件（共9行：汇总行 + 8项指标各自独立数据行）
// ══════════════════════════════════════════════════════════════

/**
 * 年报列表——第一级界面
 */
@Composable
private fun YearlyReportList(
    reports: List<YearlyReport>,
    onDetail: (YearlyReport) -> Unit
) {
    if (reports.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无年报数据", fontSize = 13.sp, color = Color(0xFF888888))
        }
        return
    }
    val sorted = remember(reports) { reports.sortedByDescending { it.year } }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        itemsIndexed(sorted, key = { index, r -> "report_${r.year}_$index" }) { _, report ->
            Card(
                modifier = Modifier.fillMaxWidth().clickableWithSound { onDetail(report) },
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF8F8F8)),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("第${report.year}年", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // 弟子净变化 = 新增 - 死亡 - 脱离
                        val discipleDelta = report.newDisciples - report.deceasedDisciples - report.desertedDisciples
                        val discipleColor = when {
                            discipleDelta > 0 -> GameColors.Success
                            discipleDelta < 0 -> GameColors.Error
                            else -> Color.Black
                        }
                        Text("弟子: ${formatSigned(discipleDelta)}", fontSize = 11.sp, color = discipleColor)
                        // 灵石净变化 = 收入 - 支出
                        val stoneDelta = report.totalIncome - report.totalExpenditure
                        val stoneColor = when {
                            stoneDelta > 0 -> GameColors.Success
                            stoneDelta < 0 -> GameColors.Error
                            else -> Color.Black
                        }
                        Text("灵石: ${formatSigned(stoneDelta)}", fontSize = 11.sp, color = stoneColor)
                    }
                }
            }
        }
    }
}

/**
 * 年报详情对话框——第二级界面
 *
 * 共 7 行：
 *   Row1 汇总     — FlowRow 自适应排列 8 项指标
 *   Row2 灵石收入来源 — 来源明细（灵矿、战斗等）
 *   Row3 灵石支出来源 — 来源明细（商人购买、年俸等）
 *   Row4 总锻造装备数量
 *   Row5 总炼制丹药数量
 *   Row6 总收获草药数量
 *   Row7 弟子变动    — 含新增/死亡/脱离
 */
@Composable
private fun YearlyReportDetailDialog(
    report: YearlyReport,
    onDismiss: () -> Unit
) {
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "第${report.year}年年度报告",
        mode = DialogMode.Half,
        scrollableContent = false  // 内容已内部使用 verticalScroll，避免嵌套
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {

            // ── Row 1: 汇总 ──
            SectionTitle("【汇总】")
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SummaryCard("灵石总收入", "+${report.totalIncome}", GameColors.Success)
                SummaryCard("灵石总支出", "-${report.totalExpenditure}", GameColors.Error)
                SummaryCard("总锻造装备", "${report.forgeCompleted}")
                SummaryCard("总炼制丹药", "${report.alchemyCompleted}")
                SummaryCard("总收获草药", "${report.herbsHarvested}")
                SummaryCard("新增弟子", "+${report.newDisciples}", GameColors.Success)
                SummaryCard("死亡弟子", "-${report.deceasedDisciples}", GameColors.Error)
                SummaryCard("脱离弟子", "-${report.desertedDisciples}", GameColors.Error)
            }

            ReportDivider()

            // ── Row 2: 灵石收入来源 ──
            SectionTitle("【灵石收入来源】")
            val mergedIncome = remember(report) { mergeSellEntries(report.incomeBySource) }
            if (mergedIncome.isEmpty()) {
                EmptyDataText("无")
            } else {
                mergedIncome.entries.sortedByDescending { it.value }.forEach { (key, value) ->
                    DataText("  ${sourceDisplayName(key)}: +$value")
                }
            }

            ReportDivider()

            // ── Row 3: 灵石支出来源 ──
            SectionTitle("【灵石支出来源】")
            if (report.expenditureByReason.isEmpty()) {
                EmptyDataText("无")
            } else {
                report.expenditureByReason.entries.sortedByDescending { it.value }.forEach { (key, value) ->
                    DataText("  ${reasonDisplayName(key)}: -$value")
                }
            }

            ReportDivider()

            // ── Row 4: 装备来源（品阶 + 途径） ──
            SectionTitle("【装备来源】")
            val equipItems = remember(report) { report.equipmentBySource.entries.filter { it.value > 0 } }
            if (equipItems.isEmpty()) { EmptyDataText("无") }
            else {
                // 按品阶汇总
                val byGrade = equipItems.groupBy({ it.key.substringAfter(":") }, { it.value }).mapValues { it.value.sum() }
                DataText(byGrade.entries.sortedByDescending { it.key.toIntOrNull() ?: 0 }.joinToString("  ") { (g, c) -> "${g}阶 ×$c" })
                // 按途径汇总
                val bySrc = equipItems.groupBy({ it.key.substringBefore(":") }, { it.value }).mapValues { it.value.sum() }
                DataText(bySrc.entries.sortedByDescending { it.value }.joinToString("  ") { (s, c) -> "${equipSourceName(s)} ×$c" })
            }

            ReportDivider()

            // ── Row 5: 丹药来源（品阶 + 途径） ──
            SectionTitle("【丹药来源】")
            val pillItems = remember(report) { report.pillBySource.entries.filter { it.value > 0 } }
            if (pillItems.isEmpty()) { EmptyDataText("无") }
            else {
                // 按品阶汇总
                val byGrade = pillItems.groupBy({ it.key.substringAfter(":") }, { it.value }).mapValues { it.value.sum() }
                DataText(byGrade.entries.sortedByDescending { it.value }.joinToString("  ") { (g, c) ->
                    val name = when (g) { "HIGH" -> "上品"; "MEDIUM" -> "中品"; else -> "下品" }
                    "$name ×$c"
                })
                // 按途径汇总
                val bySrc = pillItems.groupBy({ it.key.substringBefore(":") }, { it.value }).mapValues { it.value.sum() }
                DataText(bySrc.entries.sortedByDescending { it.value }.joinToString("  ") { (s, c) -> "${pillSourceName(s)} ×$c" })
            }

            ReportDivider()

            // ── Row 6: 草药来源（途径） ──
            SectionTitle("【草药来源】")
            val herbItems = remember(report) { report.herbBySource.entries.filter { it.value > 0 } }
            if (herbItems.isEmpty()) { EmptyDataText("无") }
            else {
                DataText(herbItems.sortedByDescending { it.value }.joinToString("  ") { (key, count) ->
                    "${herbSourceName(key)} ×$count"
                })
            }

            ReportDivider()

            // ── Row 7: 弟子变动（新增/死亡/脱离合并一行） ──
            SectionTitle("【弟子变动】")
            DataText("  新增弟子: ${report.newDisciples} 人")
            DataText("  死亡弟子: ${report.deceasedDisciples} 人")
            DataText("  脱离弟子: ${report.desertedDisciples} 人")
        }
    }
}

// ── 年报详情子组件 ──

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Black)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun DataText(text: String) {
    Text(text, fontSize = 11.sp, color = Color(0xFF333333))
}

@Composable
private fun EmptyDataText(text: String) {
    Text("  $text", fontSize = 11.sp, color = Color(0xFF888888))
}

@Composable
private fun ReportDivider() {
    Spacer(Modifier.height(8.dp))
    HorizontalDivider(color = GameColors.SurfaceLightGray, thickness = 1.dp)
    Spacer(Modifier.height(8.dp))
}

/**
 * 汇总区单个指标卡片
 */
@Composable
private fun SummaryCard(label: String, value: String, valueColor: Color = Color.Black) {
    Column(
        modifier = Modifier
            .width(80.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFFF5F5F5))
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 9.sp, color = Color(0xFF666666))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = valueColor)
    }
}

// ══════════════════════════════════════════════════════════════
// 工具函数
// ══════════════════════════════════════════════════════════════

/**
 * 归并 Sell(*) 条目为统一的"售卖"条目。
 */
private fun mergeSellEntries(map: Map<String, Long>): Map<String, Long> {
    val result = mutableMapOf<String, Long>()
    map.forEach { (key, value) ->
        if (key.startsWith("Sell")) {
            result["Sell"] = (result["Sell"] ?: 0L) + value
        } else {
            result[key] = value
        }
    }
    return result
}

/** SpiritStoneSource.key → 中文显示名 */
private fun sourceDisplayName(key: String): String = when (key) {
    "Mine" -> "灵矿"; "Battle" -> "战斗"; "Quest" -> "任务"
    "Mail" -> "邮件"; "MerchantTrade" -> "交易"
    "Exploration" -> "探索"; "RedeemCode" -> "兑换码"; "Cave" -> "洞府"
    "HeavenlyTrial" -> "天道试炼"; "SectLevelReward" -> "宗门等级奖励"
    "Salary" -> "俸禄"; "StorageBag" -> "储物袋"; "Refund" -> "退款"
    "Sell" -> "售卖"; "Internal" -> "内部"
    else -> key
}

/** SpiritStoneReason.key → 中文显示名 */
private fun reasonDisplayName(key: String): String = when (key) {
    "Building" -> "建筑"; "PolicyCost" -> "政策消耗"; "Salary" -> "年俸"
    "Gift" -> "赠礼"; "Diplomacy" -> "外交"; "VassalTribute" -> "附属上贡"
    "Purchase" -> "购买"; "AutoSell" -> "自动售卖"; "Exchange" -> "兑换"
    "Theft" -> "盗窃"; "ExplorationLoot" -> "探索战利品"; "BeastTribute" -> "妖兽上贡"
    "Internal" -> "内部"
    else -> key
}

/** 装备来源名 */
private fun equipSourceName(key: String): String = when (key) {
    "forge" -> "锻造"; "battle" -> "战斗"; "exploration" -> "探索"
    "quest" -> "任务"; "mail" -> "邮件"; "cave" -> "洞府"
    "trial" -> "天道试炼"; "merchant" -> "商人"
    "sect_level" -> "宗门等级"; "storage_bag" -> "储物袋"
    "building" -> "建筑"; "unknown" -> "未知"
    else -> key
}

/** 丹药来源名 */
private fun pillSourceName(key: String): String = when (key) {
    "alchemy" -> "炼丹"; "battle" -> "战斗"; "exploration" -> "探索"
    "quest" -> "任务"; "mail" -> "邮件"; "cave" -> "洞府"
    "trial" -> "天道试炼"; "merchant" -> "商人"
    "sect_level" -> "宗门等级"; "storage_bag" -> "储物袋"
    "building" -> "建筑"; "unknown" -> "未知"
    else -> key
}

/** 草药来源名 */
private fun herbSourceName(key: String): String = when (key) {
    "spirit_field" -> "灵田"; "exploration" -> "探索"; "battle" -> "战斗"
    "quest" -> "任务"; "mail" -> "邮件"; "storage_bag" -> "储物袋"
    "cave" -> "洞府"; "trial" -> "天道试炼"; "merchant" -> "商人"
    "unknown" -> "未知"
    else -> key
}

/** 带符号格式化：正数加 "+"，负数保留 "-" */
private fun formatSigned(value: Long): String = if (value > 0) "+$value" else "$value"
private fun formatSigned(value: Int): String = if (value > 0) "+$value" else "$value"
