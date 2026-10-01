package com.xianxia.sect.data.engine

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 封装 StorageEngine 的 5 个后台维护调度依赖，
 * 将 StorageEngine 构造参数收敛到门面。
 */
@Singleton
class StorageMaintenanceFacade @Inject constructor(
    val pruningScheduler: DataPruningScheduler,
    val archiveScheduler: DataArchiveScheduler,
    val memoryGuard: ProactiveMemoryGuard,
    val metricsReporter: StorageMetricsReporter,
    val taskScheduler: com.xianxia.sect.core.util.BackgroundTaskScheduler
) {
    companion object {
        private const val TAG = "StorageMaintFacade"

        /** 存储指标周期上报节拍（秒）——30 分钟批量出报，非阻塞（data-analytics §1.3） */
        private const val METRICS_REPORT_INTERVAL_SECONDS = 1800
    }

    fun startMaintenance() {
        taskScheduler.register("MemoryGuard", 10) { memoryGuard.performCheck() }
        taskScheduler.register("DataPruning", 300) { pruningScheduler.performPruning() }
        taskScheduler.register("DataArchive", 600) { archiveScheduler.performArchive() }
        taskScheduler.register("StorageMetricsReport", METRICS_REPORT_INTERVAL_SECONDS) {
            metricsReporter.reportOnce()
        }
        taskScheduler.start()
        Log.i(TAG, "Storage maintenance started")
    }

    fun stopMaintenance() {
        taskScheduler.stop()
        Log.i(TAG, "Storage maintenance stopped")
    }

    fun shutdown() {
        memoryGuard.shutdown()
        pruningScheduler.shutdown()
        archiveScheduler.shutdown()
        Log.i(TAG, "Maintenance facade shutdown completed")
    }
}
