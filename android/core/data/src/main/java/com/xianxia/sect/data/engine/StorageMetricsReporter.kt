package com.xianxia.sect.data.engine

import android.util.Log
import com.xianxia.sect.core.util.AnalyticsEvents
import com.xianxia.sect.core.util.AnalyticsTracker
import com.xianxia.sect.data.archive.ArchiveReader
import com.xianxia.sect.data.incremental.ChangeLogPersistence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 存储指标周期上报器：聚合 `StorageMetrics` 计数器 + `change_log` 待同步读数 +
 * 归档表概览，经 [AnalyticsTracker]（TapDB）批量异步出报。
 *
 * 挂接在存储维护调度（30 分钟节拍，见 `StorageMaintenanceFacade`），零游戏主链路
 * 依赖；上报失败静默降级（rules/data-analytics.md §1.3：埋点不影响玩家体验）。
 * 事件定义与属性白名单见 [AnalyticsEvents.STORAGE_METRICS_REPORT]（三处同步在册）。
 */
@Singleton
class StorageMetricsReporter @Inject constructor(
    private val storageMetrics: StorageMetrics,
    private val changeLogPersistence: ChangeLogPersistence,
    private val archiveReader: ArchiveReader,
    private val analyticsTracker: AnalyticsTracker
) {
    companion object {
        private const val TAG = "StorageMetricsReporter"
    }

    /** 聚合当前读数并出报一次（维护调度节拍调用；失败不重试，下个节拍再报） */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 上报链异常不可枚举, 静默降级不进主链路
    suspend fun reportOnce() = withContext(Dispatchers.IO) {
        try {
            val snapshot = storageMetrics.snapshot()
            val changeLogPending = changeLogPersistence.getPendingCount()
            val overview = archiveReader.overview()

            analyticsTracker.trackEvent(
                AnalyticsEvents.STORAGE_METRICS_REPORT,
                mapOf(
                    AnalyticsEvents.PROP_STORAGE_SAVE_COUNT to snapshot.saveCount,
                    AnalyticsEvents.PROP_STORAGE_LOAD_COUNT to snapshot.loadCount,
                    AnalyticsEvents.PROP_STORAGE_CACHE_HIT_COUNT to snapshot.cacheHitCount,
                    AnalyticsEvents.PROP_STORAGE_CACHE_MISS_COUNT to snapshot.cacheMissCount,
                    AnalyticsEvents.PROP_STORAGE_BACKUP_FAILURE_COUNT to snapshot.backupFailureCount,
                    AnalyticsEvents.PROP_STORAGE_BACKUP_RESTORE_COUNT to snapshot.backupRestoreCount,
                    AnalyticsEvents.PROP_STORAGE_BACKUP_SKIPPED_COUNT to snapshot.backupSkippedOversizeCount,
                    AnalyticsEvents.PROP_STORAGE_JADE_DRIFT_COUNT to snapshot.jadeLedgerDriftCount,
                    AnalyticsEvents.PROP_STORAGE_CHANGE_LOG_PENDING to changeLogPending,
                    AnalyticsEvents.PROP_STORAGE_ARCHIVE_BATTLE_LOG_ROWS to overview.battleLogCount,
                    AnalyticsEvents.PROP_STORAGE_ARCHIVE_DISCIPLE_ROWS to overview.discipleCount,
                    AnalyticsEvents.PROP_STORAGE_INCREMENTAL_SAVE_COUNT to snapshot.incrementalSaveCount,
                    AnalyticsEvents.PROP_STORAGE_FULL_SAVE_COUNT to snapshot.fullSaveCount,
                    AnalyticsEvents.PROP_STORAGE_DIRTY_FALLBACK_COUNT to snapshot.dirtyFallbackCount,
                    AnalyticsEvents.PROP_STORAGE_LAST_FULL_SAVE_REASON to (snapshot.lastFullSaveReason ?: "NONE")
                )
            )
            Log.d(TAG, "storage metrics reported: saves=${snapshot.saveCount}, " +
                "drift=${snapshot.jadeLedgerDriftCount}, changeLogPending=$changeLogPending")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "storage metrics report skipped: ${e.message}")
        }
    }
}
