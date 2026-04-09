package com.xianxia.sect.data.facade

import android.content.Context
import android.util.Log
// 迁移说明：引入 StorageGateway 替代直接依赖 UnifiedSaveRepository（2026-04-07）
// 原因：统一存储访问入口，遵循 Storage Gateway 模式
import com.xianxia.sect.data.StorageGateway
import com.xianxia.sect.data.unified.RepositoryStats
import com.xianxia.sect.data.wal.WALProvider
import com.xianxia.sect.data.wal.EnhancedWALStats
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class StorageSystemStats(
    val totalSaves: Long,
    val totalLoads: Long,
    val avgSaveTimeMs: Long,
    val avgLoadTimeMs: Long,
    val cacheHitRate: Float,
    val totalStorageBytes: Long,
    val activeSlots: Int,
    val activeTransactions: Int,
    val walStats: EnhancedWALStats?
)

data class StorageUsage(
    val totalSize: Long,
    val savesSize: Long,
    val backupsSize: Long,
    val cacheSize: Long
)

@Suppress("DEPRECATION")
@Singleton
class StorageStatsCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storageGateway: StorageGateway,
    // 高级功能（stats/slots）仍需通过 UnifiedSaveRepository 访问
    private val unifiedSaveRepository: com.xianxia.sect.data.unified.UnifiedSaveRepository,
    private val wal: WALProvider
) {
    companion object {
        private const val TAG = "StorageStatsCollector"
        private const val UPDATE_INTERVAL_MS = 5000L
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _stats = MutableStateFlow(StorageSystemStats(
        totalSaves = 0,
        totalLoads = 0,
        avgSaveTimeMs = 0,
        avgLoadTimeMs = 0,
        cacheHitRate = 0f,
        totalStorageBytes = 0,
        activeSlots = 0,
        activeTransactions = 0,
        walStats = null
    ))
    
    val stats: StateFlow<StorageSystemStats> = _stats.asStateFlow()
    
    private val saveTimes = mutableListOf<Long>()
    private val loadTimes = mutableListOf<Long>()
    
    private var updateJob: Job? = null
    
    init {
        startPeriodicUpdate()
    }
    
    private fun startPeriodicUpdate() {
        updateJob = scope.launch {
            while (isActive) {
                updateStats()
                delay(UPDATE_INTERVAL_MS)
            }
        }
    }
    
    private suspend fun updateStats() {
        try {
            // 使用 unifiedSaveRepository.getStats() 替代已删除的 storageGateway.getRepositoryStats()
            val repoStats = unifiedSaveRepository.getStats()
            val walStats = wal.getStats()
            val usage = calculateStorageUsage()

            val avgSave = if (saveTimes.isNotEmpty()) saveTimes.average().toLong() else 0L
            val avgLoad = if (loadTimes.isNotEmpty()) loadTimes.average().toLong() else 0L

            _stats.value = StorageSystemStats(
                totalSaves = repoStats.totalSaves,
                totalLoads = repoStats.totalLoads,
                avgSaveTimeMs = avgSave,
                avgLoadTimeMs = avgLoad,
                cacheHitRate = repoStats.cacheHitRate,
                totalStorageBytes = usage.totalSize,
                // 使用 unifiedSaveRepository.getSaveSlots() 替代已删除的 storageGateway.getUnifiedSaveSlots()
                activeSlots = unifiedSaveRepository.getSaveSlots().count { !it.isEmpty },
                activeTransactions = repoStats.activeTransactions,
                walStats = walStats
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update stats", e)
        }
    }
    
    fun recordSaveTime(durationMs: Long) {
        synchronized(saveTimes) {
            saveTimes.add(durationMs)
            if (saveTimes.size > 100) {
                saveTimes.removeAt(0)
            }
        }
    }
    
    fun recordLoadTime(durationMs: Long) {
        synchronized(loadTimes) {
            loadTimes.add(durationMs)
            if (loadTimes.size > 100) {
                loadTimes.removeAt(0)
            }
        }
    }
    
    suspend fun calculateStorageUsage(): StorageUsage {
        var savesSize = 0L
        var backupsSize = 0L
        var cacheSize = 0L
        
        try {
            val savesDir = File(context.filesDir, "saves")
            if (savesDir.exists()) {
                savesSize = savesDir.walkTopDown()
                    .filter { it.isFile }
                    .map { it.length() }
                    .sum()
            }
            
            val backupsDir = File(context.filesDir, "backups")
            if (backupsDir.exists()) {
                backupsSize = backupsDir.walkTopDown()
                    .filter { it.isFile }
                    .map { it.length() }
                    .sum()
            }
            
            val cacheDir = context.cacheDir
            if (cacheDir.exists()) {
                cacheSize = cacheDir.walkTopDown()
                    .filter { it.isFile }
                    .map { it.length() }
                    .sum()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate storage usage", e)
        }
        
        return StorageUsage(
            totalSize = savesSize + backupsSize + cacheSize,
            savesSize = savesSize,
            backupsSize = backupsSize,
            cacheSize = cacheSize
        )
    }
    
    fun getSystemStats(): StorageSystemStats = _stats.value

    // 使用 unifiedSaveRepository.getStats() 替代已删除的 storageGateway.getRepositoryStats()
    fun getRepositoryStats(): RepositoryStats = unifiedSaveRepository.getStats()

    fun shutdown() {
        updateJob?.cancel()
        scope.cancel()
    }
}
