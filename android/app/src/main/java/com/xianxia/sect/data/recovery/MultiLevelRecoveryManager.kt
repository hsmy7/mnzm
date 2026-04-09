@file:Suppress("UNUSED_VARIABLE", "DEPRECATION")
package com.xianxia.sect.data.recovery

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.concurrent.SlotLockManager
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.serialization.NullSafeProtoBuf
import com.xianxia.sect.data.serialization.unified.SaveDataConverter
import com.xianxia.sect.data.serialization.unified.SerializableSaveData
// 迁移说明：引入 StorageGateway 替代直接依赖 UnifiedSaveRepository（2026-04-07）
// 原因：统一存储访问入口，遵循 Storage Gateway 模式
import com.xianxia.sect.data.StorageGateway
import com.xianxia.sect.data.unified.*
import com.xianxia.sect.data.wal.WALProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
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

interface RecoveryStrategyBase {
    val level: RecoveryLevel
    suspend fun canRecover(slot: Int): Boolean
    suspend fun recover(slot: Int): SaveResult<SaveData>
}

@Suppress("DEPRECATION")
@Singleton
class MultiLevelRecoveryManager @Inject constructor(
    @ApplicationContext private val context: Context,
    // 迁移说明：将 saveRepository: UnifiedSaveRepository 替换为 storageGateway: StorageGateway（2026-04-07）
    // 原因：遵循 Storage Gateway 模式，统一存储访问入口
    // 影响范围：内部策略类（LocalBackupStrategy/AutoSaveStrategy/EmergencySaveStrategy）同步迁移
    private val storageGateway: StorageGateway,
    // 高级功能（backup/integrity/emergency/load）仍需通过 UnifiedSaveRepository 访问
    private val unifiedSaveRepository: com.xianxia.sect.data.unified.UnifiedSaveRepository,
    private val wal: WALProvider,
    private val lockManager: SlotLockManager
) {
    companion object {
        private const val TAG = "MultiLevelRecovery"
        private const val RECOVERY_DIR = "recovery"
        private const val RECOVERY_LOG_FILE = "recovery_log.pb"
        private val protoBuf = NullSafeProtoBuf.protoBuf
    }

    @Serializable
    data class RecoveryLogEntry(
        val timestamp: Long = 0L,
        val slot: Int = 0,
        val status: String = "",
        val attempts: List<RecoveryAttemptEntry> = emptyList()
    )

    @Serializable
    data class RecoveryAttemptEntry(
        val level: String = "",
        val status: String = "",
        val message: String = "",
        val durationMs: Long = 0L
    )
    
    private val recoveryDir: File by lazy {
        File(context.filesDir, RECOVERY_DIR).apply {
            if (!exists()) mkdirs()
        }
    }
    
    private val strategies: List<RecoveryStrategyBase> = listOf(
        WALSnapshotStrategy(wal),
        // 策略类构造参数从仅 StorageGateway 改为同时传入 StorageGateway 和 UnifiedSaveRepository（2026-04-07）
        LocalBackupStrategy(storageGateway, unifiedSaveRepository),
        AutoSaveStrategy(unifiedSaveRepository),
        EmergencySaveStrategy(unifiedSaveRepository)
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
            val successAttempt = report.attempts.firstOrNull { it.status == RecoveryStatus.SUCCESS }
            if (successAttempt != null && successAttempt.recoveredData != null) {
                SaveResult.success(successAttempt.recoveredData)
            } else {
                SaveResult.failure(SaveError.RESTORE_FAILED, "Quick recovery succeeded but no data recovered")
            }
        } else {
            SaveResult.failure(SaveError.RESTORE_FAILED, "Quick recovery failed: ${report.finalStatus}")
        }
    }
    
    suspend fun fullRecoveryCheck(): Map<Int, RecoveryReport> {
        val reports = mutableMapOf<Int, RecoveryReport>()

        for (slot in 1..lockManager.getMaxSlots()) {
            // 使用 unifiedSaveRepository.verifyIntegrity() 替代已删除的 storageGateway.verifyIntegrity()
            val integrity = unifiedSaveRepository.verifyIntegrity(slot)
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
            // 使用 unifiedSaveRepository.load() 替代已删除的 storageGateway.unifiedLoad()
            val result = unifiedSaveRepository.load(slot)
            if (result.isSuccess) {
                val data = result.getOrNull() ?: return
                val backupFile = File(recoveryDir, "pre_recovery_slot_${slot}_${System.currentTimeMillis()}.pb")
                val serializableData = SaveDataConverter().toSerializable(data)
                val bytes = NullSafeProtoBuf.protoBuf.encodeToByteArray(SerializableSaveData.serializer(), serializableData)
                backupFile.writeBytes(bytes)
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
                try {
                    protoBuf.decodeFromByteArray(kotlinx.serialization.builtins.ListSerializer(RecoveryLogEntry.serializer()), logFile.readBytes())
                } catch (e: Exception) {
                    emptyList()
                }
            } else emptyList()

            val newEntry = RecoveryLogEntry(
                timestamp = System.currentTimeMillis(),
                slot = slot,
                status = status.name,
                attempts = attempts.map { RecoveryAttemptEntry(
                    level = it.level.name,
                    status = it.status.name,
                    message = it.message,
                    durationMs = it.durationMs
                )}
            )

            val updated = (entries + newEntry).takeLast(100)
            logFile.writeBytes(protoBuf.encodeToByteArray(kotlinx.serialization.builtins.ListSerializer(RecoveryLogEntry.serializer()), updated))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log recovery attempt", e)
        }
    }
    
    fun getRecoveryHistory(): List<Map<String, Any>> {
        return try {
            val logFile = File(recoveryDir, RECOVERY_LOG_FILE)
            if (logFile.exists()) {
                val entries: List<RecoveryLogEntry> = protoBuf.decodeFromByteArray(kotlinx.serialization.builtins.ListSerializer(RecoveryLogEntry.serializer()), logFile.readBytes())
                entries.map { entry ->
                    mapOf(
                        "timestamp" to entry.timestamp,
                        "slot" to entry.slot,
                        "status" to entry.status,
                        "attempts" to entry.attempts.map { attempt ->
                            mapOf(
                                "level" to attempt.level,
                                "status" to attempt.status,
                                "message" to attempt.message,
                                "durationMs" to attempt.durationMs
                            )
                        }
                    )
                }
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clearRecoveryHistory() {
        try {
            val logFile = File(recoveryDir, RECOVERY_LOG_FILE)
            if (logFile.exists()) logFile.delete()
            recoveryDir.listFiles()?.filter { it.extension == "pb" }?.forEach { it.delete() }
            Log.i(TAG, "Cleared recovery history")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear recovery history", e)
        }
    }
}

class WALSnapshotStrategy(
    private val wal: WALProvider
) : RecoveryStrategyBase {
    private val protoBuf = ProtoBuf

    override val level = RecoveryLevel.WAL_SNAPSHOT

    override suspend fun canRecover(slot: Int): Boolean {
        return wal.hasActiveTransactions()
    }

    override suspend fun recover(slot: Int): SaveResult<SaveData> {
        var recoveredData: SaveData? = null

        val result = wal.restoreFromSnapshot(slot) { data ->
            try {
                val serialized = protoBuf.decodeFromByteArray(com.xianxia.sect.data.serialization.unified.SerializableSaveData.serializer(), data)
                recoveredData = com.xianxia.sect.data.serialization.unified.SaveDataConverter().fromSerializable(serialized)
                true
            } catch (e: Exception) {
                Log.w("WALSnapshotStrategy", "Failed to parse snapshot data", e)
                false
            }
        }
        
        return if (result.isSuccess && recoveredData != null) {
            SaveResult.success(recoveredData)
        } else {
            SaveResult.failure(SaveError.RESTORE_FAILED, "WAL snapshot recovery failed")
        }
    }
}

@Suppress("DEPRECATION")
class LocalBackupStrategy(
    // 同时接收 StorageGateway（用于基本操作）和 UnifiedSaveRepository（用于高级操作）
    private val storageGateway: StorageGateway,
    private val unifiedSaveRepository: com.xianxia.sect.data.unified.UnifiedSaveRepository
) : RecoveryStrategyBase {
    override val level = RecoveryLevel.LOCAL_BACKUP
    
    override suspend fun canRecover(slot: Int): Boolean {
        return unifiedSaveRepository.getBackupVersions(slot).isNotEmpty()
    }
    
    override suspend fun recover(slot: Int): SaveResult<SaveData> {
        val backups = unifiedSaveRepository.getBackupVersions(slot)
        if (backups.isEmpty()) {
            return SaveResult.failure(SaveError.SLOT_EMPTY, "No backup versions available")
        }
        
        return unifiedSaveRepository.restoreFromBackup(slot, backups.first().id)
    }
}

@Suppress("DEPRECATION")
class AutoSaveStrategy(
    // 使用 UnifiedSaveRepository 替代 StorageGateway（2026-04-07）
    private val unifiedSaveRepository: com.xianxia.sect.data.unified.UnifiedSaveRepository
) : RecoveryStrategyBase {
    override val level = RecoveryLevel.AUTO_SAVE
    
    override suspend fun canRecover(slot: Int): Boolean {
        return unifiedSaveRepository.hasSave(0)
    }
    
    override suspend fun recover(slot: Int): SaveResult<SaveData> {
        return unifiedSaveRepository.load(0)
    }
}

@Suppress("DEPRECATION")
class EmergencySaveStrategy(
    // 使用 UnifiedSaveRepository 替代 StorageGateway（2026-04-07）
    private val unifiedSaveRepository: com.xianxia.sect.data.unified.UnifiedSaveRepository
) : RecoveryStrategyBase {
    override val level = RecoveryLevel.EMERGENCY_SAVE
    
    override suspend fun canRecover(slot: Int): Boolean {
        return unifiedSaveRepository.hasEmergencySave()
    }
    
    override suspend fun recover(slot: Int): SaveResult<SaveData> {
        val data = unifiedSaveRepository.loadEmergencySave()
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
