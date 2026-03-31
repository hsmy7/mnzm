package com.xianxia.sect.data.recovery

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.concurrent.SlotLockManager
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.unified.*
import com.xianxia.sect.data.wal.EnhancedTransactionalWAL
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class RecoveryLevel(val priority: Int, val description: String) {
    WAL_SNAPSHOT(1, "WAL快照恢复"),
    LOCAL_BACKUP(2, "本地备份恢复"),
    AUTO_SAVE(3, "自动存档恢复"),
    EMERGENCY_SAVE(4, "紧急存档恢复"),
    CLOUD_BACKUP(5, "云端备份恢复"),
    DEFAULT_DATA(6, "默认数据恢复")
}

enum class RecoveryStatus {
    SUCCESS,
    PARTIAL_SUCCESS,
    FAILED,
    NO_DATA_AVAILABLE
}

data class RecoveryAttempt(
    val level: RecoveryLevel,
    val status: RecoveryStatus,
    val message: String,
    val durationMs: Long,
    val dataLoss: Boolean = false,
    val recoveredData: SaveData? = null
)

data class RecoveryReport(
    val slot: Int,
    val finalStatus: RecoveryStatus,
    val attempts: List<RecoveryAttempt>,
    val totalDurationMs: Long,
    val recoveredFrom: RecoveryLevel?,
    val dataLossWarning: Boolean,
    val userActionRequired: String?
)

data class RecoveryOptions(
    val maxAttempts: Int = RecoveryLevel.entries.size,
    val stopOnFirstSuccess: Boolean = true,
    val includeCloudRecovery: Boolean = false,
    val notifyUserOnDataLoss: Boolean = true,
    val createBackupBeforeRecovery: Boolean = true
)

interface RecoveryStrategy {
    val level: RecoveryLevel
    suspend fun canRecover(slot: Int): Boolean
    suspend fun recover(slot: Int): SaveResult<SaveData>
}

@Singleton
class MultiLevelRecoveryManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val saveRepository: UnifiedSaveRepository,
    private val wal: EnhancedTransactionalWAL,
    private val lockManager: SlotLockManager
) {
    companion object {
        private const val TAG = "MultiLevelRecovery"
        private const val RECOVERY_DIR = "recovery"
        private const val RECOVERY_LOG_FILE = "recovery_log.json"
    }
    
    private val recoveryDir: File by lazy {
        File(context.filesDir, RECOVERY_DIR).apply {
            if (!exists()) mkdirs()
        }
    }
    
    private val strategies: List<RecoveryStrategy> = listOf(
        WALSnapshotStrategy(wal),
        LocalBackupStrategy(saveRepository),
        AutoSaveStrategy(saveRepository),
        EmergencySaveStrategy(saveRepository)
    )
    
    suspend fun recover(
        slot: Int,
        options: RecoveryOptions = RecoveryOptions()
    ): RecoveryReport = withContext(Dispatchers.IO) {
        if (!lockManager.isValidSlot(slot)) {
            return@withContext RecoveryReport(
                slot = slot,
                finalStatus = RecoveryStatus.FAILED,
                attempts = listOf(RecoveryAttempt(
                    level = RecoveryLevel.DEFAULT_DATA,
                    status = RecoveryStatus.FAILED,
                    message = "Invalid slot: $slot",
                    durationMs = 0
                )),
                totalDurationMs = 0,
                recoveredFrom = null,
                dataLossWarning = false,
                userActionRequired = "Invalid slot specified"
            )
        }
        
        val startTime = System.currentTimeMillis()
        val attempts = mutableListOf<RecoveryAttempt>()
        var recoveredData: SaveData? = null
        var recoveredFrom: RecoveryLevel? = null
        var dataLossWarning = false
        
        if (options.createBackupBeforeRecovery) {
            createRecoveryBackup(slot)
        }
        
        val sortedStrategies = strategies.sortedBy { it.level.priority }
        
        for (strategy in sortedStrategies) {
            if (attempts.size >= options.maxAttempts) break
            if (recoveredData != null && options.stopOnFirstSuccess) break
            
            val attemptStart = System.currentTimeMillis()
            
            try {
                if (!strategy.canRecover(slot)) {
                    attempts.add(RecoveryAttempt(
                        level = strategy.level,
                        status = RecoveryStatus.NO_DATA_AVAILABLE,
                        message = "No recovery data available at this level",
                        durationMs = System.currentTimeMillis() - attemptStart
                    ))
                    continue
                }
                
                val result = strategy.recover(slot)
                val duration = System.currentTimeMillis() - attemptStart
                
                when {
                    result.isSuccess -> {
                        recoveredData = result.getOrNull()
                        recoveredFrom = strategy.level
                        
                        attempts.add(RecoveryAttempt(
                            level = strategy.level,
                            status = RecoveryStatus.SUCCESS,
                            message = "Successfully recovered from ${strategy.level.description}",
                            durationMs = duration,
                            dataLoss = strategy.level.priority > 2,
                            recoveredData = recoveredData
                        ))
                        
                        dataLossWarning = strategy.level.priority > 2
                    }
                    result.isFailure -> {
                        val error = (result as SaveResult.Failure).error
                        attempts.add(RecoveryAttempt(
                            level = strategy.level,
                            status = RecoveryStatus.FAILED,
                            message = "Recovery failed: ${result.message}",
                            durationMs = duration
                        ))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recovery attempt failed at level ${strategy.level}", e)
                attempts.add(RecoveryAttempt(
                    level = strategy.level,
                    status = RecoveryStatus.FAILED,
                    message = "Exception: ${e.message}",
                    durationMs = System.currentTimeMillis() - attemptStart
                ))
            }
        }
        
        val finalStatus = when {
            recoveredData != null -> RecoveryStatus.SUCCESS
            attempts.any { it.status == RecoveryStatus.PARTIAL_SUCCESS } -> RecoveryStatus.PARTIAL_SUCCESS
            attempts.all { it.status == RecoveryStatus.NO_DATA_AVAILABLE } -> RecoveryStatus.NO_DATA_AVAILABLE
            else -> RecoveryStatus.FAILED
        }
        
        val totalDuration = System.currentTimeMillis() - startTime
        
        val userActionRequired = when {
            finalStatus == RecoveryStatus.SUCCESS && dataLossWarning -> 
                "部分数据可能丢失，请检查游戏进度"
            finalStatus == RecoveryStatus.FAILED -> 
                "恢复失败，请尝试手动导入存档或联系支持"
            finalStatus == RecoveryStatus.NO_DATA_AVAILABLE -> 
                "没有可恢复的数据"
            else -> null
        }
        
        logRecoveryAttempt(slot, attempts, finalStatus)
        
        RecoveryReport(
            slot = slot,
            finalStatus = finalStatus,
            attempts = attempts,
            totalDurationMs = totalDuration,
            recoveredFrom = recoveredFrom,
            dataLossWarning = dataLossWarning,
            userActionRequired = userActionRequired
        )
    }
    
    suspend fun quickRecover(slot: Int): SaveResult<SaveData> {
        val report = recover(slot, RecoveryOptions(stopOnFirstSuccess = true))
        
        return if (report.finalStatus == RecoveryStatus.SUCCESS) {
            SaveResult.success(report.attempts.first { it.status == RecoveryStatus.SUCCESS }.recoveredData!!)
        } else {
            SaveResult.failure(SaveError.RESTORE_FAILED, "Quick recovery failed: ${report.finalStatus}")
        }
    }
    
    suspend fun fullRecoveryCheck(): Map<Int, RecoveryReport> {
        val reports = mutableMapOf<Int, RecoveryReport>()
        
        for (slot in 1..lockManager.getMaxSlots()) {
            val integrity = saveRepository.verifyIntegrity(slot)
            if (integrity is IntegrityResult.Invalid) {
                Log.w(TAG, "Slot $slot has integrity issues, attempting recovery")
                reports[slot] = recover(slot)
            }
        }
        
        return reports
    }
    
    suspend fun hasRecoverableData(slot: Int): Boolean {
        return strategies.any { it.canRecover(slot) }
    }
    
    fun getAvailableRecoveryLevels(slot: Int): List<RecoveryLevel> {
        return strategies.filter { 
            runBlocking { it.canRecover(slot) }
        }.map { it.level }
    }
    
    private suspend fun createRecoveryBackup(slot: Int) {
        try {
            val result = saveRepository.load(slot)
            if (result.isSuccess) {
                val data = result.getOrNull() ?: return
                val backupFile = File(recoveryDir, "pre_recovery_slot_${slot}_${System.currentTimeMillis()}.json")
                val json = com.xianxia.sect.data.GsonConfig.createGson().toJson(data)
                backupFile.writeText(json)
                Log.d(TAG, "Created recovery backup for slot $slot")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create recovery backup for slot $slot", e)
        }
    }
    
    private fun logRecoveryAttempt(slot: Int, attempts: List<RecoveryAttempt>, status: RecoveryStatus) {
        try {
            val logFile = File(recoveryDir, RECOVERY_LOG_FILE)
            val entries = if (logFile.exists()) {
                com.xianxia.sect.data.GsonConfig.createGson().fromJson(
                    logFile.readText(),
                    List::class.java
                ) as? List<Map<String, Any>> ?: emptyList()
            } else emptyList()
            
            val newEntry = mapOf(
                "timestamp" to System.currentTimeMillis(),
                "slot" to slot,
                "status" to status.name,
                "attempts" to attempts.map { mapOf(
                    "level" to it.level.name,
                    "status" to it.status.name,
                    "message" to it.message,
                    "durationMs" to it.durationMs
                )}
            )
            
            val updated = (entries + newEntry).takeLast(100)
            logFile.writeText(com.xianxia.sect.data.GsonConfig.createGson().toJson(updated))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log recovery attempt", e)
        }
    }
    
    fun getRecoveryHistory(): List<Map<String, Any>> {
        return try {
            val logFile = File(recoveryDir, RECOVERY_LOG_FILE)
            if (logFile.exists()) {
                com.xianxia.sect.data.GsonConfig.createGson().fromJson(
                    logFile.readText(),
                    List::class.java
                ) as? List<Map<String, Any>> ?: emptyList()
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    fun clearRecoveryHistory() {
        try {
            val logFile = File(recoveryDir, RECOVERY_LOG_FILE)
            if (logFile.exists()) logFile.delete()
            recoveryDir.listFiles()?.filter { it.extension == "json" }?.forEach { it.delete() }
            Log.i(TAG, "Cleared recovery history")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear recovery history", e)
        }
    }
}

class WALSnapshotStrategy(
    private val wal: EnhancedTransactionalWAL
) : RecoveryStrategy {
    override val level = RecoveryLevel.WAL_SNAPSHOT
    
    override suspend fun canRecover(slot: Int): Boolean {
        return wal.hasActiveTransactions()
    }
    
    override suspend fun recover(slot: Int): SaveResult<SaveData> {
        var recoveredData: SaveData? = null
        
        val result = wal.restoreFromSnapshot(slot) { data ->
            try {
                recoveredData = com.xianxia.sect.data.GsonConfig.createGson()
                    .fromJson(String(data, Charsets.UTF_8), SaveData::class.java)
                true
            } catch (e: Exception) {
                Log.w("WALSnapshotStrategy", "Failed to parse snapshot data", e)
                false
            }
        }
        
        return if (result.isSuccess && recoveredData != null) {
            SaveResult.success(recoveredData!!)
        } else {
            SaveResult.failure(SaveError.RESTORE_FAILED, "WAL snapshot recovery failed")
        }
    }
}

class LocalBackupStrategy(
    private val saveRepository: UnifiedSaveRepository
) : RecoveryStrategy {
    override val level = RecoveryLevel.LOCAL_BACKUP
    
    override suspend fun canRecover(slot: Int): Boolean {
        return saveRepository.getBackupVersions(slot).isNotEmpty()
    }
    
    override suspend fun recover(slot: Int): SaveResult<SaveData> {
        val backups = saveRepository.getBackupVersions(slot)
        if (backups.isEmpty()) {
            return SaveResult.failure(SaveError.SLOT_EMPTY, "No backup versions available")
        }
        
        return saveRepository.restoreFromBackup(slot, backups.first().id)
    }
}

class AutoSaveStrategy(
    private val saveRepository: UnifiedSaveRepository
) : RecoveryStrategy {
    override val level = RecoveryLevel.AUTO_SAVE
    
    override suspend fun canRecover(slot: Int): Boolean {
        return saveRepository.hasSave(0)
    }
    
    override suspend fun recover(slot: Int): SaveResult<SaveData> {
        return saveRepository.load(0)
    }
}

class EmergencySaveStrategy(
    private val saveRepository: UnifiedSaveRepository
) : RecoveryStrategy {
    override val level = RecoveryLevel.EMERGENCY_SAVE
    
    override suspend fun canRecover(slot: Int): Boolean {
        return saveRepository.hasEmergencySave()
    }
    
    override suspend fun recover(slot: Int): SaveResult<SaveData> {
        val data = saveRepository.loadEmergencySave()
        return if (data != null) {
            SaveResult.success(data)
        } else {
            SaveResult.failure(SaveError.SLOT_EMPTY, "No emergency save available")
        }
    }
}

private fun runBlocking(block: suspend () -> Boolean): Boolean {
    return kotlinx.coroutines.runBlocking { block() }
}
