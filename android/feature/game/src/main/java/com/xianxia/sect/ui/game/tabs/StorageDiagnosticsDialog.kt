package com.xianxia.sect.ui.game.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xianxia.sect.data.engine.StorageDiagnosticsReport
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.game.StorageDiagnosticsViewModel
import com.xianxia.sect.ui.theme.GameColors

/**
 * 存档诊断对话框（SS3-e，设置页只读入口）：
 * 关键存储计数器 + 最近保存变更（change_log）+ 归档概要（战报/陨落历史）。
 * 全程只读；数据经 [StorageDiagnosticsViewModel] 单次拉取。
 */
@Composable
internal fun StorageDiagnosticsDialog(onDismiss: () -> Unit) {
    val viewModel: StorageDiagnosticsViewModel = hiltViewModel()
    val report by viewModel.report.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refresh() }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "存档诊断",
        mode = DialogMode.Auto,
        backgroundRes = com.xianxia.sect.feature.game.R.drawable.bg_horizontal,
        dismissOnClickOutside = false
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (loading && report == null) {
                Text(text = "读取中…", fontSize = 12.sp, color = Color.Black)
            }
            report?.let { current ->
                MetricsSection(current)
                RecentChangesSection(current)
                ArchiveSection(current)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/** 关键计数器段：两列键值对 */
@Composable
private fun MetricsSection(report: StorageDiagnosticsReport) {
    val metrics = report.metrics
    DiagnosticsCard(title = "关键计数器") {
        MetricsRow("保存次数", metrics.saveCount, "读档次数", metrics.loadCount)
        MetricsRow("缓存命中", metrics.cacheHitCount, "缓存未命中", metrics.cacheMissCount)
        MetricsRow("备份成功", metrics.backupSuccessCount, "备份失败", metrics.backupFailureCount)
        MetricsRow("备份恢复", metrics.backupRestoreCount, "备份超限跳过", metrics.backupSkippedOversizeCount)
        Text(
            text = "账本漂移（缓存≠账本重锚）: ${metrics.jadeLedgerDriftCount} 次" +
                (metrics.lastJadeLedgerDriftOp?.let { "（最近: $it）" } ?: ""),
            fontSize = 11.sp,
            color = Color.Black
        )
    }
}

/** 最近保存变更段：change_log 诊断行 */
@Composable
private fun RecentChangesSection(report: StorageDiagnosticsReport) {
    DiagnosticsCard(title = "最近保存变更（${report.recentChanges.size} 条）") {
        if (report.recentChanges.isEmpty()) {
            Text(text = "暂无变更记录", fontSize = 11.sp, color = Color.Black)
        }
        report.recentChanges.forEach { change ->
            Text(
                text = "${change.displayTime}  [${change.tableName}] ${change.recordId} " +
                    "${change.operation} ${change.summary}",
                fontSize = 10.sp,
                color = Color.Black,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** 归档概要段：两表行数 + 最近归档明细 */
@Composable
private fun ArchiveSection(report: StorageDiagnosticsReport) {
    val overview = report.archiveOverview
    DiagnosticsCard(title = "归档概要") {
        Text(
            text = "归档战报 ${overview.battleLogCount} 条 · 陨落弟子 ${overview.discipleCount} 名",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        report.recentArchivedBattleLogs.forEach { log ->
            Text(
                text = "战报 ${log.attackerName} vs ${log.defenderName}（${log.result}）",
                fontSize = 10.sp,
                color = Color.Black
            )
        }
        report.recentArchivedDisciples.forEach { disciple ->
            Text(
                text = "陨落 ${disciple.name}（境界 ${disciple.realm}）",
                fontSize = 10.sp,
                color = Color.Black
            )
        }
    }
}

@Composable
private fun DiagnosticsCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(GameColors.CardBackground)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = GameColors.GoldDark
        )
        content()
    }
}

@Composable
private fun MetricsRow(
    leftLabel: String, leftValue: Long,
    rightLabel: String, rightValue: Long
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "$leftLabel: $leftValue", fontSize = 11.sp, color = Color.Black)
        Text(text = "$rightLabel: $rightValue", fontSize = 11.sp, color = Color.Black)
    }
}
