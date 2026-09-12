package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.core.model.YearlyReport
import com.xianxia.sect.ui.components.clickableWithSound
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*


// ══════════════════════════════════════════════════════════════
// 年报日志组件（共9行：汇总行 + 8项指标各自独立数据行）
// ══════════════════════════════════════════════════════════════

/**
 * 年报列表——第一级界面
 */
@Composable
internal fun YearlyReportList(
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
                    // weight + ellipsis 防窄屏横向溢出（年份行占满剩余空间）
                    Text(
                        "第${report.year}年", fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, color = Color.Black,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        Modifier.weight(1f, fill = false),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 弟子净变化 = 新增 - 死亡 - 脱离
                        val discipleDelta = report.newDisciples - report.deceasedDisciples - report.desertedDisciples
                        val discipleColor = when {
                            discipleDelta > 0 -> GameColors.Success
                            discipleDelta < 0 -> GameColors.Error
                            else -> Color.Black
                        }
                        Text(
                            "弟子: ${formatSigned(discipleDelta)}", fontSize = 11.sp,
                            color = discipleColor, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        // 灵石净变化 = 收入 - 支出
                        val stoneDelta = report.totalIncome - report.totalExpenditure
                        val stoneColor = when {
                            stoneDelta > 0 -> GameColors.Success
                            stoneDelta < 0 -> GameColors.Error
                            else -> Color.Black
                        }
                        Text(
                            "灵石: ${formatSigned(stoneDelta)}", fontSize = 11.sp,
                            color = stoneColor, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
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
internal fun YearlyReportDetailDialog(
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
            ReportSummarySection(report = report)

            ReportDivider()

            // ── Row 2: 灵石收入来源 ──
            ReportIncomeSection(report = report)

            ReportDivider()

            // ── Row 3: 灵石支出来源 ──
            ReportExpenditureSection(report = report)

            ReportDivider()

            // ── Row 4: 装备来源（品阶 + 途径） ──
            ReportEquipmentSection(report = report)

            ReportDivider()

            // ── Row 5: 丹药来源（品阶 + 途径） ──
            ReportPillSection(report = report)

            ReportDivider()

            // ── Row 6: 草药来源（途径） ──
            ReportHerbSection(report = report)

            ReportDivider()

            // ── Row 7: 弟子变动（新增/死亡/脱离合并一行） ──
            ReportDiscipleChangesSection(report = report)
        }
    }
}

// ── 年报详情子组件 ──

/** 年报汇总区：8 项指标 FlowRow */
@Composable
internal fun ReportSummarySection(report: YearlyReport) {
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
}

/** 灵石收入来源区 */
@Composable
internal fun ReportIncomeSection(report: YearlyReport) {
    SectionTitle("【灵石收入来源】")
    // 过滤 0 值条目（读档/历史存档可能残留 0 值键），空判以过滤后集合为准
    val mergedIncome = remember(report) {
        mergeSellEntries(report.incomeBySource).filterValues { it > 0 }
    }
    if (mergedIncome.isEmpty()) {
        EmptyDataText("无")
    } else {
        mergedIncome.entries.sortedByDescending { it.value }.forEach { (key, value) ->
            DataText("  ${sourceDisplayName(key)}: +$value")
        }
    }
}

/** 灵石支出来源区 */
@Composable
internal fun ReportExpenditureSection(report: YearlyReport) {
    SectionTitle("【灵石支出来源】")
    val filteredExpenditure = remember(report) {
        report.expenditureByReason.filterValues { it > 0 }
    }
    if (filteredExpenditure.isEmpty()) {
        EmptyDataText("无")
    } else {
        filteredExpenditure.entries.sortedByDescending { it.value }.forEach { (key, value) ->
            DataText("  ${reasonDisplayName(key)}: -$value")
        }
    }
}

/** 装备来源区（品阶 + 途径） */
@Composable
internal fun ReportEquipmentSection(report: YearlyReport) {
    SectionTitle("【装备来源】")
    val equipItems = remember(report) { report.equipmentBySource.entries.filter { it.value > 0 } }
    if (equipItems.isEmpty()) {
        EmptyDataText("无")
    } else {
        // 按品阶汇总
        val byGrade = equipItems.groupBy({ it.key.substringAfter(":") }, { it.value }).mapValues { it.value.sum() }
        DataText(byGrade.entries.sortedByDescending { it.key.toIntOrNull() ?: 0 }.joinToString("  ") { (g,
            c) -> "${g}阶 ×$c" })
        // 按途径汇总
        val bySrc = equipItems.groupBy({ it.key.substringBefore(":") }, { it.value }).mapValues { it.value.sum() }
        DataText(bySrc.entries.sortedByDescending { it.value }.joinToString("  ") { (s,
            c) -> "${equipSourceName(s)} ×$c" })
    }
}

/** 丹药来源区（品阶 + 途径） */
@Composable
internal fun ReportPillSection(report: YearlyReport) {
    SectionTitle("【丹药来源】")
    val pillItems = remember(report) { report.pillBySource.entries.filter { it.value > 0 } }
    if (pillItems.isEmpty()) {
        EmptyDataText("无")
    } else {
        // 按品阶汇总
        val byGrade = pillItems.groupBy({ it.key.substringAfter(":") }, { it.value }).mapValues { it.value.sum() }
        DataText(byGrade.entries.sortedByDescending { it.value }.joinToString("  ") { (g, c) ->
            val name = when (g) { "HIGH" -> "上品"; "MEDIUM" -> "中品"; else -> "下品" }
            "$name ×$c"
        })
        // 按途径汇总
        val bySrc = pillItems.groupBy({ it.key.substringBefore(":") }, { it.value }).mapValues { it.value.sum() }
        DataText(bySrc.entries.sortedByDescending { it.value }.joinToString("  ") { (s,
            c) -> "${pillSourceName(s)} ×$c" })
    }
}

/** 草药来源区（途径） */
@Composable
internal fun ReportHerbSection(report: YearlyReport) {
    SectionTitle("【草药来源】")
    val herbItems = remember(report) { report.herbBySource.entries.filter { it.value > 0 } }
    if (herbItems.isEmpty()) {
        EmptyDataText("无")
    } else {
        DataText(herbItems.sortedByDescending { it.value }.joinToString("  ") { (key, count) ->
            "${herbSourceName(key)} ×$count"
        })
    }
}

/** 弟子变动区（新增/死亡/脱离） */
@Composable
internal fun ReportDiscipleChangesSection(report: YearlyReport) {
    SectionTitle("【弟子变动】")
    DataText("  新增弟子: ${report.newDisciples} 人")
    DataText("  死亡弟子: ${report.deceasedDisciples} 人")
    DataText("  脱离弟子: ${report.desertedDisciples} 人")
}
