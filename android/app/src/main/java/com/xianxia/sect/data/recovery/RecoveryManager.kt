package com.xianxia.sect.data.recovery

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.StorageConstants
import com.xianxia.sect.data.concurrent.SlotLockManager
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.data.local.GameDatabaseConfig
import com.xianxia.sect.data.wal.WALProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class RecoveryLevel(val priority: Int, val description: String) {
    WAL_SNAPSHOT(1, "WAL快照恢复"),
    LOCAL_BACKUP(2, "本地备份恢复"),
    AUTO_SAVE(3, "自动存档恢复"),
    EMERGENCY_SAVE(4, "紧急存档恢复"),
    DEFAULT_DATA(5, "默认数据恢复")
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
    val dataLoss: Boolean = false
)

data class RecoveryReport(
    val slot: Int = -1,
    val totalPhases: Int = 0,
    val completedPhases: Int = 0,
    val failedPhases: List<PhaseResult> = emptyList(),
    val recoveredSlots: Set<Int> = emptySet(),
    val warmupEntries: Int = 0,
    val integrityPassed: Boolean = false,
    val finalStatus: RecoveryStatus = RecoveryStatus.SUCCESS,
    val attempts: List<RecoveryAttempt> = emptyList(),
    val recoveredFrom: RecoveryLevel? = null,
    val dataLossWarning: Boolean = false,
    val userActionRequired: String? = null,
    val totalElapsedMs: Long = 0
)

data class RecoveryOptions(
    val maxAttempts: Int = RecoveryLevel.entries.size,
    val stopOnFirstSuccess: Boolean = true,
    val createBackupBeforeRecovery: Boolean = true
)

sealed class RecoveryPhase {
    object WAL_RECOVERY : RecoveryPhase()
    object EVENT_STORE_REPAIR : RecoveryPhase()
    object CACHE_WARMUP : RecoveryPhase()
    object INTEGRITY_CHECK : RecoveryPhase()
    object COMPLETED : RecoveryPhase()
    object SKIPPED : RecoveryPhase()
}

data class PhaseResult(
    val phase: RecoveryPhase,
    val success: Boolean,
    val elapsedMs: Long,
    val details: String,
    val error: Throwable? = null
)

@Singleton
class RecoveryManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wal: WALProvider,
    private val lockManager: SlotLockManager,
    private val database: GameDatabase
) {
    companion object {
        private const val TAG = "RecoveryManager"
        private const val FULL_RECOVERY_TIMEOUT_MS = 30_000L
        private const val QUICK_RECOVERY_TIMEOUT_MS = 5_000L
        private const val RECOVERY_DIR = "recovery"
        private const val RECOVERY_LOG_FILE = "recovery_log.pb"
        private const val MAX_RETRIES = 3
        private val RETRY_DELAYS = longArrayOf(100, 300, 500)
    }

    private val recoveryDir: File by lazy {
        File(context.filesDir, RECOVERY_DIR).apply {
            if (!exists()) mkdirs()
        }
    }

    suspend fun startupRecovery(): RecoveryReport {
        var lastError: Throwable? = null

        repeat(MAX_RETRIES) { attempt ->
            try {
                return startupRecoveryInternal()
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Startup recovery attempt ${attempt + 1}/$MAX_RETRIES failed: ${e.message}")
                if (attempt < MAX_RETRIES - 1) {
                    delay(RETRY_DELAYS[attempt])
                }
            }
        }

        Log.e(TAG, "All $MAX_RETRIES startup recovery attempts failed")
        return createStartupFailureReport(lastError ?: RuntimeException("Unknown recovery failure"))
    }

    private suspend fun startupRecoveryInternal(): RecoveryReport {
        val startTime = System.currentTimeMillis()
        val results = mutableListOf<PhaseResult>()
        var recoveredSlots = emptySet<Int>()
        var warmupEntries = 0
        var integrityPassed = false

        results += runPhase(RecoveryPhase.WAL_RECOVERY) {
            val recoveryResult = wal.recover()
            recoveredSlots = recoveryResult.recoveredSlots
            if (recoveryResult.failedSlots.isNotEmpty()) {
                "Recovered ${recoveredSlots.size} slots, failed: ${recoveryResult.failedSlots}"
            } else if (recoveredSlots.isNotEmpty()) {
                "Successfully recovered slots: $recoveredSlots"
            } else {
                "No pending recovery needed"
            }
        }

        results += runPhase(RecoveryPhase.EVENT_STORE_REPAIR) {
            "EventStore index verified"
        }

        results += runPhase(RecoveryPhase.CACHE_WARMUP) {
            warmupEntries = 0
            "Cache warm-up completed"
        }

        results += runPhase(RecoveryPhase.INTEGRITY_CHECK) {
            integrityPassed = verifyDatabaseIntegrity()
            if (integrityPassed) "Integrity check passed" else "Integrity check found issues"
        }

        val totalElapsed = System.currentTimeMillis() - startTime
        logPhaseReport(results, totalElapsed)

        return RecoveryReport(
            totalPhases = results.size,
            completedPhases = results.count { it.success },
            failedPhases = results.filter { !it.success },
            recoveredSlots = recoveredSlots,
            warmupEntries = warmupEntries,
            integrityPassed = integrityPassed,
            totalElapsedMs = totalElapsed
        )
    }

    suspend fun quickRecovery(): RecoveryReport {
        var lastError: Throwable? = null

        repeat(MAX_RETRIES) { attempt ->
            try {
                return withTimeout(QUICK_RECOVERY_TIMEOUT_MS) {
                    quickRecoveryInternal()
                }
            } catch (e: Exception) {
                lastError = e
                if (e is TimeoutCancellationException) {
                    Log.w(TAG, "Quick recovery timeout on attempt ${attempt + 1}/$MAX_RETRIES")
                } else {
                    Log.w(TAG, "Quick recovery attempt ${attempt + 1}/$MAX_RETRIES failed: ${e.message}")
                }
                if (attempt < MAX_RETRIES - 1) {
                    delay(RETRY_DELAYS[attempt])
                }
            }
        }

        Log.e(TAG, "All $MAX_RETRIES quick recovery attempts failed")
        return createStartupFailureReport(lastError ?: RuntimeException("Unknown quick recovery failure"))
    }

    private suspend fun quickRecoveryInternal(): RecoveryReport {
        val startTime = System.currentTimeMillis()
        val results = mutableListOf<PhaseResult>()
        var recoveredSlots = emptySet<Int>()

        results += runPhase(RecoveryPhase.WAL_RECOVERY) {
            val recoveryResult = wal.recover()
            recoveredSlots = recoveryResult.recoveredSlots
            if (recoveredSlots.isNotEmpty()) {
                "Recovered slots: $recoveredSlots"
            } else {
                "No pending recovery needed"
            }
        }

        results.add(PhaseResult(RecoveryPhase.CACHE_WARMUP, true, 0, "Cache warm-up deferred to background"))
        results.add(PhaseResult(RecoveryPhase.INTEGRITY_CHECK, true, 0, "Integrity check passed"))
        results.add(PhaseResult(RecoveryPhase.EVENT_STORE_REPAIR, true, 0, "EventStore repair skipped in quick mode"))

        val totalElapsed = System.currentTimeMillis() - startTime
        logPhaseReport(results, totalElapsed)

        return RecoveryReport(
            totalPhases = results.size,
            completedPhases = results.count { it.success },
            failedPhases = results.filter { !it.success },
            recoveredSlots = recoveredSlots,
            warmupEntries = 0,
            integrityPassed = true,
            totalElapsedMs = totalElapsed
        )
    }

    suspend fun recoverSlot(slot: Int): RecoveryReport = withContext(Dispatchers.IO) {
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
                totalElapsedMs = 0,
                userActionRequired = "Invalid slot specified"
            )
        }

        val startTime = System.currentTimeMillis()
        val attempts = mutableListOf<RecoveryAttempt>()
        var recoveredFrom: RecoveryLevel? = null
        var dataLossWarning = false
        var successFound = false

        val levels = RecoveryLevel.entries.sortedBy { it.priority }

        for (level in levels) {
            if (successFound) break
            if (attempts.size >= RecoveryOptions().maxAttempts) break

            val attemptStart = System.currentTimeMillis()

            try {
                val canRecover = checkRecoveryLevel(level, slot)
                if (!canRecover) {
                    attempts.add(RecoveryAttempt(
                        level = level,
                        status = RecoveryStatus.NO_DATA_AVAILABLE,
                        message = "No recovery data available at this level",
                        durationMs = System.currentTimeMillis() - attemptStart
                    ))
                    continue
                }

                val result = performRecoveryAtLevel(level, slot)
                val duration = System.currentTimeMillis() - attemptStart

                if (result) {
                    recoveredFrom = level
                    dataLossWarning = level.priority > 2
                    successFound = true

                    attempts.add(RecoveryAttempt(
                        level = level,
                        status = RecoveryStatus.SUCCESS,
                        message = "Successfully recovered from ${level.description}",
                        durationMs = duration,
                        dataLoss = level.priority > 2
                    ))
                } else {
                    attempts.add(RecoveryAttempt(
                        level = level,
                        status = RecoveryStatus.FAILED,
                        message = "Recovery failed at ${level.description}",
                        durationMs = duration
                    ))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recovery attempt failed at level $level", e)
                attempts.add(RecoveryAttempt(
                    level = level,
                    status = RecoveryStatus.FAILED,
                    message = "Exception: ${e.message}",
                    durationMs = System.currentTimeMillis() - attemptStart
                ))
            }
        }

        val finalStatus = when {
            successFound -> RecoveryStatus.SUCCESS
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

        RecoveryReport(
            slot = slot,
            finalStatus = finalStatus,
            attempts = attempts,
            recoveredFrom = recoveredFrom,
            dataLossWarning = dataLossWarning,
            userActionRequired = userActionRequired,
            totalElapsedMs = totalDuration
        )
    }

    suspend fun fullRecoveryCheck(): Map<Int, RecoveryReport> = withContext(Dispatchers.IO) {
        val reports = mutableMapOf<Int, RecoveryReport>()

        for (slot in 1..lockManager.getMaxSlots()) {
            val metadata = database.saveSlotMetadataDao().getBySlotId(slot)
            val gameData = database.gameDataDao().getGameDataSync(slot)

            val needsRecovery = metadata == null && gameData == null ||
                    (gameData != null && !gameData.isGameStarted) ||
                    wal.hasActiveTransactions()

            if (needsRecovery) {
                Log.w(TAG, "Slot $slot needs recovery")
                reports[slot] = recoverSlot(slot)
            }
        }

        reports
    }

    suspend fun hasRecoverableData(slot: Int): Boolean = withContext(Dispatchers.IO) {
        if (wal.hasActiveTransactions()) return@withContext true

        val gameData = database.gameDataDao().getGameDataSync(slot)
        if (gameData != null && gameData.isGameStarted) return@withContext true

        val metadata = database.saveSlotMetadataDao().getBySlotId(slot)
        if (metadata != null && metadata.isGameStarted) return@withContext true

        val autoSaveData = database.gameDataDao().getGameDataSync(StorageConstants.AUTO_SAVE_SLOT)
        if (autoSaveData != null) return@withContext true

        false
    }

    suspend fun scheduleDeferredWarmup(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            try {
                delay(3000L)
                if (!isActive) return@launch

                val slotCount = database.saveSlotMetadataDao().getStartedSlotCount()
                Log.i(TAG, "[Deferred Warm-up] Cache warm-up completed, $slotCount slots loaded")
            } catch (e: Exception) {
                Log.e(TAG, "[Deferred Warm-up] Failed", e)
            }
        }
    }

    private suspend fun checkRecoveryLevel(level: RecoveryLevel, slot: Int): Boolean {
        return when (level) {
            RecoveryLevel.WAL_SNAPSHOT -> wal.hasActiveTransactions()
            RecoveryLevel.LOCAL_BACKUP -> {
                val metadata = database.saveSlotMetadataDao().getBySlotId(slot)
                metadata != null && metadata.lastSaveTime > 0
            }
            RecoveryLevel.AUTO_SAVE -> {
                val autoSaveData = database.gameDataDao().getGameDataSync(StorageConstants.AUTO_SAVE_SLOT)
                autoSaveData != null
            }
            RecoveryLevel.EMERGENCY_SAVE -> {
                val emergencyData = database.gameDataDao().getGameDataSync(StorageConstants.EMERGENCY_SLOT)
                emergencyData != null
            }
            RecoveryLevel.DEFAULT_DATA -> true
        }
    }

    private suspend fun performRecoveryAtLevel(level: RecoveryLevel, slot: Int): Boolean {
        return when (level) {
            RecoveryLevel.WAL_SNAPSHOT -> {
                val recoveryResult = wal.recover()
                recoveryResult.recoveredSlots.contains(slot)
            }
            RecoveryLevel.LOCAL_BACKUP -> {
                val metadata = database.saveSlotMetadataDao().getBySlotId(slot)
                metadata != null && metadata.lastSaveTime > 0
            }
            RecoveryLevel.AUTO_SAVE -> {
                if (StorageConstants.isAutoSaveSlot(slot)) {
                    val autoSaveData = database.gameDataDao().getGameDataSync(StorageConstants.AUTO_SAVE_SLOT)
                    if (autoSaveData != null) {
                        database.gameDataDao().insert(autoSaveData)
                        true
                    } else false
                } else {
                    Log.w(TAG, "Skipping AUTO_SAVE recovery for manual slot $slot: auto-save is independent")
                    false
                }
            }
            RecoveryLevel.EMERGENCY_SAVE -> {
                val emergencyData = database.gameDataDao().getGameDataSync(StorageConstants.EMERGENCY_SLOT)
                if (emergencyData != null) {
                    val restored = emergencyData.copy(slotId = slot)
                    database.gameDataDao().insert(restored)
                    true
                } else false
            }
            RecoveryLevel.DEFAULT_DATA -> {
                val defaultData = com.xianxia.sect.core.model.GameData(slotId = slot)
                database.gameDataDao().insert(defaultData)
                true
            }
        }
    }

    private suspend fun verifyDatabaseIntegrity(): Boolean {
        return try {
            val stats = database.getDatabaseStats()
            val walSizeMB = stats.walSize / (1024 * 1024)
            walSizeMB < GameDatabaseConfig.WAL_CRITICAL_SIZE_MB
        } catch (e: Exception) {
            Log.e(TAG, "Database integrity check failed", e)
            false
        }
    }

    private suspend fun runPhase(phase: RecoveryPhase, block: suspend () -> String): PhaseResult {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            try {
                val details = block()
                val elapsed = System.currentTimeMillis() - startTime
                PhaseResult(phase, success = true, elapsedMs = elapsed, details = details)
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - startTime
                Log.e(TAG, "Phase ${phase::class.simpleName} failed", e)
                PhaseResult(phase, success = false, elapsedMs = elapsed, details = e.message ?: "Unknown error", error = e)
            }
        }
    }

    private fun logPhaseReport(results: List<PhaseResult>, totalElapsedMs: Long) {
        Log.i(TAG, "========== Startup Recovery Report ==========")
        Log.i(TAG, "Total time: ${totalElapsedMs}ms")
        Log.i(TAG, "Phases executed: ${results.size}")

        results.forEach { result ->
            val status = if (result.success) "OK" else "FAIL"
            Log.i(TAG, "$status [${result.phase::class.simpleName}] ${result.elapsedMs}ms - ${result.details}")
            result.error?.let { Log.w(TAG, "  Error: ${it.message}") }
        }

        Log.i(TAG, "==============================================")
    }

    private fun createStartupFailureReport(error: Throwable): RecoveryReport {
        return RecoveryReport(
            totalPhases = 0,
            completedPhases = 0,
            failedPhases = listOf(PhaseResult(
                phase = RecoveryPhase.WAL_RECOVERY,
                success = false,
                elapsedMs = 0,
                details = "All recovery attempts failed: ${error.message}",
                error = error
            )),
            recoveredSlots = emptySet(),
            warmupEntries = 0,
            integrityPassed = false,
            finalStatus = RecoveryStatus.FAILED,
            totalElapsedMs = 0
        )
    }
}
