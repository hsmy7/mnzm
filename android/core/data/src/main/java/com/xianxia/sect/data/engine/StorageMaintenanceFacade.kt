package com.xianxia.sect.data.engine

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 封装 StorageEngine 的 4 个后台维护调度依赖，
 * 将 StorageEngine 构造参数从 16 降至 12。
 */
@Singleton
class StorageMaintenanceFacade @Inject constructor(
    val pruningScheduler: DataPruningScheduler,
    val archiveScheduler: DataArchiveScheduler,
    val memoryGuard: ProactiveMemoryGuard,
    val taskScheduler: com.xianxia.sect.core.util.BackgroundTaskScheduler
) {
    companion object {
        private const val TAG = "StorageMaintFacade"
    }

    fun startMaintenance() {
        taskScheduler.register("MemoryGuard", 10) { memoryGuard.performCheck() }
        taskScheduler.register("DataPruning", 300) { pruningScheduler.performPruning() }
        taskScheduler.register("DataArchive", 600) { archiveScheduler.performArchive() }
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
