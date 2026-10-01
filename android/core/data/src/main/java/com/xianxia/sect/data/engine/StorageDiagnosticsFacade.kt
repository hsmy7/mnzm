package com.xianxia.sect.data.engine

import com.xianxia.sect.data.archive.ArchiveOverview
import com.xianxia.sect.data.archive.ArchiveReader
import com.xianxia.sect.data.archive.ArchivedBattleLogSummary
import com.xianxia.sect.data.archive.ArchivedDiscipleSummary
import com.xianxia.sect.data.incremental.ChangeLogEntity
import com.xianxia.sect.data.incremental.ChangeLogPersistence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** 诊断展示的最近保存变更条数 */
internal const val DIAGNOSTICS_RECENT_CHANGES_LIMIT = 50

/** 诊断展示的最近归档条数（战报/陨落历史） */
internal const val DIAGNOSTICS_ARCHIVED_ROWS_LIMIT = 20

/** 单条保存变更的诊断展示行（载荷摘要已解码为可读文本） */
data class ChangeLogDiagnosticRow(
    val timestampMs: Long,
    val displayTime: String,
    val tableName: String,
    val recordId: String,
    val operation: String,
    val summary: String
)

/** 存档诊断只读报告：关键计数器 + 最近保存变更 + 归档概要 */
data class StorageDiagnosticsReport(
    val metrics: StorageMetricsSnapshot,
    val recentChanges: List<ChangeLogDiagnosticRow>,
    val archiveOverview: ArchiveOverview,
    val recentArchivedBattleLogs: List<ArchivedBattleLogSummary>,
    val recentArchivedDisciples: List<ArchivedDiscipleSummary>
)

/**
 * 存档诊断只读聚合面：设置页诊断入口（SS3-e）的数据源。
 *
 * 聚合三路读面——`change_log` 最近保存变更、`StorageMetrics` 计数器快照、
 * 归档表读面（[ArchiveReader]）——全部只读，不触碰存档主流程与任何写路径。
 */
@Singleton
class StorageDiagnosticsFacade @Inject constructor(
    private val changeLogPersistence: ChangeLogPersistence,
    private val storageMetrics: StorageMetrics,
    private val archiveReader: ArchiveReader
) {
    suspend fun snapshot(): StorageDiagnosticsReport = withContext(Dispatchers.IO) {
        StorageDiagnosticsReport(
            metrics = storageMetrics.snapshot(),
            recentChanges = changeLogPersistence
                .getRecentChanges(DIAGNOSTICS_RECENT_CHANGES_LIMIT)
                .map { it.toDiagnosticRow() },
            archiveOverview = archiveReader.overview(),
            recentArchivedBattleLogs = archiveReader.listRecentBattleLogs(DIAGNOSTICS_ARCHIVED_ROWS_LIMIT),
            recentArchivedDisciples = archiveReader.listRecentDisciples(DIAGNOSTICS_ARCHIVED_ROWS_LIMIT)
        )
    }
}

/** change_log 行 → 诊断展示行（`new_value` 载荷为 UTF-8 摘要文本） */
internal fun ChangeLogEntity.toDiagnosticRow(): ChangeLogDiagnosticRow = ChangeLogDiagnosticRow(
    timestampMs = timestamp,
    displayTime = DIAGNOSTICS_TIME_FORMAT.get()!!.format(Date(timestamp)),
    tableName = tableName,
    recordId = recordId,
    operation = operation,
    summary = newValue?.toString(Charsets.UTF_8) ?: ""
)

private val DIAGNOSTICS_TIME_FORMAT = ThreadLocal.withInitial {
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
}
