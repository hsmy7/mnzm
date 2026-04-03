package com.xianxia.sect.data.transaction

import android.content.Context
import android.util.Log
import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.concurrent.SlotLockManager
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.unified.*
import com.xianxia.sect.data.wal.EnhancedTransactionalWAL
import com.xianxia.sect.data.wal.WALEntryType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

data class SimpleTransactionContext(
    val txnId: Long,
    val slot: Int,
    val startTime: Long,
    val snapshotFile: File?,
    var completed: Boolean = false,
    var rolledBack: Boolean = false
)

data class EnhancedSaveProgress(
    val stage: Stage,
    val progress: Float,
    val message: String = ""
) {
    enum class Stage {
        IDLE,
        CREATING_SNAPSHOT,
        SAVING_DATABASE,
        SAVING_FILES,
        UPDATING_CACHE,
        VALIDATING,
        COMMITTING,
        COMPLETED,
        ROLLING_BACK,
        FAILED
    }
}

data class EnhancedTransactionStats(
    val activeTransactions: Int,
    val totalCommitted: Long,
    val totalAborted: Long,
    val totalRollbacks: Long,
    val averageDurationMs: Long
)

class EnhancedTransactionException(
    message: String,
    val canRollback: Boolean = true,
    cause: Throwable? = null
) : Exception(message, cause)

@Singleton
class EnhancedTransactionalManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: GameDatabase,
    private val wal: EnhancedTransactionalWAL,
    private val lockManager: SlotLockManager,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "EnhancedTxManager"
        private const val SNAPSHOT_DIR = "tx_snapshots"
        private const val MAX_BATCH_SIZE = 200
    }

    private val txnCounter = AtomicLong(0)
    private val committedCount = AtomicLong(0)
    private val abortedCount = AtomicLong(0)
    private val rollbackCount = AtomicLong(0)
    private val totalDurationMs = AtomicLong(0)

    private val _progress = MutableStateFlow(EnhancedSaveProgress(
        EnhancedSaveProgress.Stage.IDLE, 0f
    ))
    val progress: StateFlow<EnhancedSaveProgress> = _progress.asStateFlow()

    private val activeTransactions = mutableMapOf<Long, SimpleTransactionContext>()
    private val transactionsLock = Any()

    private val snapshotDir: File by lazy {
        File(context.filesDir, SNAPSHOT_DIR).apply {
            if (!exists()) mkdirs()
        }
    }

    suspend fun saveWithTransaction(
        slot: Int,
        data: SaveData,
        saveOperation: suspend (Int, SaveData) -> SaveResult<SaveOperationStats>
    ): SaveResult<SaveOperationStats> {
        if (!lockManager.isValidSlot(slot)) {
            return SaveResult.failure(SaveError.INVALID_SLOT, "Invalid slot: $slot")
        }

        val startTime = System.currentTimeMillis()
        var txnContext: SimpleTransactionContext? = null

        return lockManager.withWriteLockSuspend(slot) {
            try {
                txnContext = beginTransaction(slot)
                val ctx = txnContext
                    ?: throw IllegalStateException("Transaction context is null after beginTransaction")

                _progress.value = EnhancedSaveProgress(
                    EnhancedSaveProgress.Stage.CREATING_SNAPSHOT, 0.1f, "Creating backup snapshot"
                )
                val snapshotFile = createSnapshot(ctx, data)
                val ctxWithSnapshot = if (snapshotFile != null) {
                    ctx.copy(snapshotFile = snapshotFile)
                } else {
                    ctx
                }

                _progress.value = EnhancedSaveProgress(
                    EnhancedSaveProgress.Stage.SAVING_DATABASE, 0.2f, "Saving to database"
                )
                saveToDatabase(slot, data)

                _progress.value = EnhancedSaveProgress(
                    EnhancedSaveProgress.Stage.SAVING_FILES, 0.5f, "Saving files"
                )
                val result = saveOperation(slot, data)

                if (result.isFailure) {
                    throw EnhancedTransactionException(
                        "Save operation failed: ${(result as? SaveResult.Failure)?.message}",
                        canRollback = true
                    )
                }

                _progress.value = EnhancedSaveProgress(
                    EnhancedSaveProgress.Stage.VALIDATING, 0.8f, "Validating"
                )
                validateSave(slot, data)

                _progress.value = EnhancedSaveProgress(
                    EnhancedSaveProgress.Stage.COMMITTING, 0.9f, "Committing"
                )
                commitTransaction(ctxWithSnapshot)

                val elapsed = System.currentTimeMillis() - startTime
                _progress.value = EnhancedSaveProgress(
                    EnhancedSaveProgress.Stage.COMPLETED, 1.0f, "Completed in ${elapsed}ms"
                )

                result

            } catch (e: CancellationException) {
                txnContext?.let { ctx ->
                    rollbackTransaction(ctx, "Cancelled")
                }
                throw e
            } catch (e: EnhancedTransactionException) {
                Log.e(TAG, "Transaction failed for slot $slot", e)
                
                txnContext?.let { ctx ->
                    if (e.canRollback) {
                        _progress.value = EnhancedSaveProgress(
                            EnhancedSaveProgress.Stage.ROLLING_BACK, 0.95f, "Rolling back"
                        )
                        rollbackTransaction(ctx, e.message ?: "Transaction failed")
                    }
                }
                
                _progress.value = EnhancedSaveProgress(
                    EnhancedSaveProgress.Stage.FAILED, 0f, e.message ?: "Unknown error"
                )
                
                SaveResult.failure(SaveError.TRANSACTION_FAILED, e.message ?: "Transaction failed", e)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error in transaction for slot $slot", e)
                
                txnContext?.let { ctx ->
                    _progress.value = EnhancedSaveProgress(
                        EnhancedSaveProgress.Stage.ROLLING_BACK, 0.95f, "Rolling back"
                    )
                    rollbackTransaction(ctx, e.message ?: "Unexpected error")
                }
                
                _progress.value = EnhancedSaveProgress(
                    EnhancedSaveProgress.Stage.FAILED, 0f, e.message ?: "Unknown error"
                )
                
                SaveResult.failure(SaveError.UNKNOWN, e.message ?: "Unknown error", e)
            }
        }
    }

    private suspend fun beginTransaction(slot: Int): SimpleTransactionContext {
        val txnId = txnCounter.incrementAndGet()

        val walResult = wal.beginTransaction(slot, WALEntryType.BEGIN) { targetSlot ->
            serializeCurrentData(targetSlot)
        }

        if (walResult.isFailure) {
            throw EnhancedTransactionException(
                "Failed to begin WAL transaction: ${(walResult as? SaveResult.Failure)?.message}",
                canRollback = false
            )
        }

        val ctx = SimpleTransactionContext(
            txnId = walResult.getOrThrow(),
            slot = slot,
            startTime = System.currentTimeMillis(),
            snapshotFile = null
        )

        synchronized(transactionsLock) {
            activeTransactions[ctx.txnId] = ctx
        }

        Log.d(TAG, "Transaction ${ctx.txnId} started for slot $slot")
        return ctx
    }

    private fun createSnapshot(ctx: SimpleTransactionContext, data: SaveData): File? {
        return try {
            val snapshotFile = File(snapshotDir, "slot_${ctx.slot}_txn_${ctx.txnId}.snap")
            val snapshotData = serializeSnapshot(data)
            
            snapshotFile.parentFile?.mkdirs()
            snapshotFile.writeBytes(snapshotData)
            
            Log.d(TAG, "Created snapshot for transaction ${ctx.txnId}")
            snapshotFile
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create snapshot for transaction ${ctx.txnId}", e)
            null
        }
    }

    private suspend fun saveToDatabase(slot: Int, data: SaveData) = withContext(Dispatchers.IO) {
        try {
            database.gameDataDao().insert(data.gameData)
            
            if (data.disciples.isNotEmpty()) {
                data.disciples.chunked(MAX_BATCH_SIZE).forEach { batch ->
                    database.discipleDao().insertAll(batch)
                }
            }
            
            if (data.equipment.isNotEmpty()) {
                data.equipment.chunked(MAX_BATCH_SIZE).forEach { batch ->
                    database.equipmentDao().insertAll(batch)
                }
            }
            
            if (data.manuals.isNotEmpty()) {
                data.manuals.chunked(MAX_BATCH_SIZE).forEach { batch ->
                    database.manualDao().insertAll(batch)
                }
            }
            
            if (data.pills.isNotEmpty()) {
                data.pills.chunked(MAX_BATCH_SIZE).forEach { batch ->
                    database.pillDao().insertAll(batch)
                }
            }
            
            if (data.materials.isNotEmpty()) {
                data.materials.chunked(MAX_BATCH_SIZE).forEach { batch ->
                    database.materialDao().insertAll(batch)
                }
            }
            
            if (data.herbs.isNotEmpty()) {
                data.herbs.chunked(MAX_BATCH_SIZE).forEach { batch ->
                    database.herbDao().insertAll(batch)
                }
            }
            
            if (data.seeds.isNotEmpty()) {
                data.seeds.chunked(MAX_BATCH_SIZE).forEach { batch ->
                    database.seedDao().insertAll(batch)
                }
            }
            
            if (data.teams.isNotEmpty()) {
                data.teams.chunked(MAX_BATCH_SIZE).forEach { batch ->
                    database.explorationTeamDao().insertAll(batch)
                }
            }
            
            if (data.events.isNotEmpty()) {
                data.events.chunked(MAX_BATCH_SIZE).forEach { batch ->
                    database.gameEventDao().insertAll(batch)
                }
            }
            
            if (data.battleLogs.isNotEmpty()) {
                data.battleLogs.chunked(MAX_BATCH_SIZE).forEach { batch ->
                    database.battleLogDao().insertAll(batch)
                }
            }
        } catch (e: Exception) {
            throw EnhancedTransactionException("Database save failed: ${e.message}", canRollback = true, e)
        }
    }

    private suspend fun validateSave(slot: Int, data: SaveData) {
        try {
            val savedGameData = database.gameDataDao().getGameDataSync()
            if (savedGameData == null) {
                throw EnhancedTransactionException("Validation failed: game data not saved", canRollback = true)
            }
            
            if (savedGameData.sectName != data.gameData.sectName) {
                throw EnhancedTransactionException("Validation failed: sect name mismatch", canRollback = true)
            }
        } catch (e: EnhancedTransactionException) {
            throw e
        } catch (e: Exception) {
            throw EnhancedTransactionException("Validation failed: ${e.message}", canRollback = true, e)
        }
    }

    private suspend fun commitTransaction(ctx: SimpleTransactionContext) {
        wal.commit(ctx.txnId).getOrThrow()

        synchronized(transactionsLock) {
            activeTransactions.remove(ctx.txnId)
        }

        ctx.snapshotFile?.delete()

        val duration = System.currentTimeMillis() - ctx.startTime
        committedCount.incrementAndGet()
        totalDurationMs.addAndGet(duration)

        ctx.completed = true
        Log.d(TAG, "Transaction ${ctx.txnId} committed for slot ${ctx.slot} in ${duration}ms")
    }

    private suspend fun rollbackTransaction(ctx: SimpleTransactionContext, reason: String) {
        try {
            Log.i(TAG, "Rolling back transaction ${ctx.txnId} for slot ${ctx.slot}: $reason")

            wal.abort(ctx.txnId)

            rollbackCount.incrementAndGet()
            ctx.rolledBack = true

            Log.i(TAG, "Transaction ${ctx.txnId} rolled back successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Rollback failed for transaction ${ctx.txnId}", e)
        } finally {
            ctx.snapshotFile?.delete()
            
            synchronized(transactionsLock) {
                activeTransactions.remove(ctx.txnId)
            }
            
            abortedCount.incrementAndGet()
        }
    }

    private fun serializeSnapshot(data: SaveData): ByteArray {
        return com.xianxia.sect.data.GsonConfig.createGson()
            .toJson(data).toByteArray(Charsets.UTF_8)
    }

    private fun serializeCurrentData(slot: Int): ByteArray {
        return try {
            val gameData = runBlocking { database.gameDataDao().getGameDataSync() }
            if (gameData != null) {
                com.xianxia.sect.data.GsonConfig.createGson()
                    .toJson(gameData).toByteArray(Charsets.UTF_8)
            } else {
                ByteArray(0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize current data", e)
            ByteArray(0)
        }
    }

    fun getActiveTransactionCount(): Int = synchronized(transactionsLock) { 
        activeTransactions.size 
    }

    fun hasActiveTransactions(): Boolean = synchronized(transactionsLock) { 
        activeTransactions.isNotEmpty() 
    }

    fun getStats(): EnhancedTransactionStats {
        val committed = committedCount.get()
        return EnhancedTransactionStats(
            activeTransactions = getActiveTransactionCount(),
            totalCommitted = committed,
            totalAborted = abortedCount.get(),
            totalRollbacks = rollbackCount.get(),
            averageDurationMs = if (committed > 0) totalDurationMs.get() / committed else 0
        )
    }

    fun shutdown() {
        synchronized(transactionsLock) {
            activeTransactions.values.forEach { ctx ->
                if (!ctx.completed && !ctx.rolledBack) {
                    try {
                        runBlocking { wal.abort(ctx.txnId) }
                        ctx.snapshotFile?.delete()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to abort transaction ${ctx.txnId} during shutdown", e)
                    }
                }
            }
            activeTransactions.clear()
        }

        scope.cancel()

        Log.i(TAG, "EnhancedTransactionalManager shutdown completed")
    }
}
