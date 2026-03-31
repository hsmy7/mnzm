package com.xianxia.sect.data.transaction

import android.content.Context
import android.util.Log
import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.cache.CacheKey
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.unified.SaveError
import com.xianxia.sect.data.unified.SaveResult
import com.xianxia.sect.data.unified.SaveOperationStats
import com.xianxia.sect.data.wal.EnhancedTransactionalWAL
import com.xianxia.sect.data.wal.WALEntryType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

data class TransactionContext(
    val txnId: Long,
    val slot: Int,
    val startTime: Long,
    val snapshotPath: String?,
    var state: TransactionState
)

enum class TransactionState {
    ACTIVE,
    COMMITTED,
    ABORTED,
    ROLLED_BACK
}

data class TransactionalSaveProgress(
    val stage: String,
    val progress: Float,
    val bytesWritten: Long = 0
)

data class TransactionalSaveStats(
    val totalTransactions: Long = 0,
    val successfulTransactions: Long = 0,
    val failedTransactions: Long = 0,
    val rolledBackTransactions: Long = 0,
    val averageSaveTimeMs: Long = 0,
    val totalBytesWritten: Long = 0
)

@Singleton
class TransactionalSaveManager @Inject constructor(
    private val context: Context,
    private val database: GameDatabase,
    private val wal: EnhancedTransactionalWAL
) {
    companion object {
        private const val TAG = "TransactionalSaveMgr"
        private const val BACKUP_DIR = "transaction_backups"
        private const val TEMP_SLOT_OFFSET = 100
        private const val MAX_BACKUP_AGE_MS = 24 * 60 * 60 * 1000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val activeTransactions = ConcurrentHashMap<Long, TransactionContext>()
    private val txnCounter = AtomicLong(0)
    
    private val successfulTxns = AtomicLong(0)
    private val failedTxns = AtomicLong(0)
    private val rolledBackTxns = AtomicLong(0)
    private val totalSaveTime = AtomicLong(0)
    private val totalBytesWritten = AtomicLong(0)
    
    private val _progress = MutableStateFlow<TransactionalSaveProgress>(
        TransactionalSaveProgress("idle", 0f)
    )
    val progress: StateFlow<TransactionalSaveProgress> = _progress.asStateFlow()
    
    private val _stats = MutableStateFlow(TransactionalSaveStats())
    val stats: StateFlow<TransactionalSaveStats> = _stats.asStateFlow()
    
    private val backupDir: File by lazy {
        File(context.filesDir, BACKUP_DIR).apply {
            if (!exists()) mkdirs()
        }
    }

    suspend fun saveWithTransaction(
        slot: Int,
        data: SaveData,
        enableBackup: Boolean = true
    ): SaveResult<SaveOperationStats> {
        val startTime = System.currentTimeMillis()
        var txnId: Long? = null
        
        return try {
            _progress.value = TransactionalSaveProgress("starting", 0.05f)
            
            val beginResult = wal.beginTransaction(
                slot = slot,
                operation = WALEntryType.BEGIN,
                snapshotProvider = if (enableBackup) { s ->
                    createSnapshot(s)
                } else null
            )
            
            if (beginResult.isFailure) {
                return SaveResult.failure(
                    SaveError.TRANSACTION_FAILED,
                    "Failed to begin transaction: ${beginResult.getOrNull()}"
                )
            }
            
            txnId = beginResult.getOrNull()!!
            
            val txnContext = TransactionContext(
                txnId = txnId,
                slot = slot,
                startTime = startTime,
                snapshotPath = getSnapshotPath(slot, txnId),
                state = TransactionState.ACTIVE
            )
            activeTransactions[txnId] = txnContext
            
            _progress.value = TransactionalSaveProgress("saving_data", 0.1f)
            
            val tempSlot = TEMP_SLOT_OFFSET + slot
            
            val writeResult = writeToTempSlot(tempSlot, data)
            if (writeResult.isFailure) {
                throw TransactionException("Failed to write to temp slot: ${writeResult.getOrNull()}")
            }
            
            _progress.value = TransactionalSaveProgress("validating", 0.6f)
            
            val validationResult = validateData(tempSlot, data)
            if (!validationResult) {
                throw TransactionException("Data validation failed")
            }
            
            _progress.value = TransactionalSaveProgress("committing", 0.8f)
            
            val swapResult = atomicSwap(slot, tempSlot)
            if (swapResult.isFailure) {
                throw TransactionException("Atomic swap failed: ${swapResult.getOrNull()}")
            }
            
            val checksum = computeChecksum(data)
            
            val commitResult = wal.commit(txnId, checksum)
            if (commitResult.isFailure) {
                throw TransactionException("Failed to commit transaction")
            }
            
            txnContext.state = TransactionState.COMMITTED
            activeTransactions.remove(txnId)
            
            cleanupBackup(slot, txnId)
            
            val elapsed = System.currentTimeMillis() - startTime
            successfulTxns.incrementAndGet()
            totalSaveTime.addAndGet(elapsed)
            totalBytesWritten.addAndGet(writeResult.getOrNull()?.bytesWritten ?: 0)
            updateStats()
            
            _progress.value = TransactionalSaveProgress("completed", 1.0f)
            
            Log.i(TAG, "Transaction $txnId completed successfully for slot $slot in ${elapsed}ms")
            
            SaveResult.success(SaveOperationStats(
                bytesWritten = writeResult.getOrNull()?.bytesWritten ?: 0,
                timeMs = elapsed,
                checksum = checksum
            ))
            
        } catch (e: TransactionException) {
            Log.e(TAG, "Transaction failed for slot $slot", e)
            
            txnId?.let { tid ->
                wal.abort(tid)
                activeTransactions[tid]?.state = TransactionState.ABORTED
                activeTransactions.remove(tid)
                
                rollback(slot, tid)
            }
            
            failedTxns.incrementAndGet()
            updateStats()
            
            _progress.value = TransactionalSaveProgress("failed", 0f)
            
            SaveResult.failure(SaveError.TRANSACTION_FAILED, e.message ?: "Unknown error", e)
            
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during transaction for slot $slot", e)
            
            txnId?.let { tid ->
                wal.abort(tid)
                activeTransactions.remove(tid)
            }
            
            failedTxns.incrementAndGet()
            updateStats()
            
            _progress.value = TransactionalSaveProgress("error", 0f)
            
            SaveResult.failure(SaveError.UNKNOWN, e.message ?: "Unknown error", e)
        }
    }

    suspend fun saveBatchWithTransaction(
        operations: List<SaveOperation>
    ): SaveResult<List<SaveOperationStats>> {
        return coroutineScope {
            val results = operations.mapIndexed { index, op ->
                async {
                    saveWithTransaction(op.slot, op.data, op.enableBackup)
                }
            }.awaitAll()
            
            val failures = results.filter { it.isFailure }
            if (failures.isNotEmpty()) {
                SaveResult.failure(
                    SaveError.TRANSACTION_FAILED,
                    "${failures.size}/${operations.size} transactions failed"
                )
            } else {
                SaveResult.success(results.map { it.getOrNull()!! })
            }
        }
    }

    private suspend fun writeToTempSlot(
        tempSlot: Int,
        data: SaveData
    ): SaveResult<WriteResult> = withContext(Dispatchers.IO) {
        try {
            var totalBytes = 0L
            
            database.withTransaction {
                _progress.value = TransactionalSaveProgress("writing_game_data", 0.15f)
                
                data.gameData?.let { gd ->
                    database.gameDataDao().insert(gd)
                    totalBytes += estimateSize(gd)
                }
                
                _progress.value = TransactionalSaveProgress("writing_disciples", 0.25f)
                
                val batchSize = calculateOptimalBatchSize(data.disciples.size)
                data.disciples.chunked(batchSize).forEachIndexed { index, batch ->
                    database.discipleDao().insertAll(batch)
                    totalBytes += batch.sumOf { estimateSize(it) }
                    
                    val progress = 0.25f + (0.2f * (index + 1) / (data.disciples.size / batchSize + 1))
                    _progress.value = TransactionalSaveProgress("writing_disciples", progress)
                }
                
                _progress.value = TransactionalSaveProgress("writing_items", 0.5f)
                
                data.equipment.chunked(batchSize).forEach { batch ->
                    database.equipmentDao().insertAll(batch)
                    totalBytes += batch.sumOf { estimateSize(it) }
                }
                
                data.manuals.chunked(batchSize).forEach { batch ->
                    database.manualDao().insertAll(batch)
                    totalBytes += batch.sumOf { estimateSize(it) }
                }
                
                data.pills.chunked(batchSize).forEach { batch ->
                    database.pillDao().insertAll(batch)
                    totalBytes += batch.sumOf { estimateSize(it) }
                }
                
                data.materials.chunked(batchSize).forEach { batch ->
                    database.materialDao().insertAll(batch)
                    totalBytes += batch.sumOf { estimateSize(it) }
                }
                
                data.herbs.chunked(batchSize).forEach { batch ->
                    database.herbDao().insertAll(batch)
                    totalBytes += batch.sumOf { estimateSize(it) }
                }
                
                data.seeds.chunked(batchSize).forEach { batch ->
                    database.seedDao().insertAll(batch)
                    totalBytes += batch.sumOf { estimateSize(it) }
                }
                
                _progress.value = TransactionalSaveProgress("writing_other", 0.55f)
                
                data.explorationTeams.chunked(batchSize).forEach { batch ->
                    database.explorationTeamDao().insertAll(batch)
                    totalBytes += batch.sumOf { estimateSize(it) }
                }
                
                data.buildingSlots.chunked(batchSize).forEach { batch ->
                    database.buildingSlotDao().insertAll(batch)
                    totalBytes += batch.sumOf { estimateSize(it) }
                }
                
                data.events.chunked(batchSize).forEach { batch ->
                    database.gameEventDao().insertAll(batch)
                    totalBytes += batch.sumOf { estimateSize(it) }
                }
                
                data.battleLogs.chunked(batchSize).forEach { batch ->
                    database.battleLogDao().insertAll(batch)
                    totalBytes += batch.sumOf { estimateSize(it) }
                }
            }
            
            SaveResult.success(WriteResult(totalBytes))
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to temp slot", e)
            SaveResult.failure(SaveError.IO_ERROR, "Write failed: ${e.message}", e)
        }
    }

    private suspend fun validateData(tempSlot: Int, expectedData: SaveData): Boolean {
        return try {
            val gameData = database.gameDataDao().getGameDataSync()
            if (gameData == null && expectedData.gameData != null) {
                Log.e(TAG, "Validation failed: game data missing")
                return false
            }
            
            val disciples = database.discipleDao().getAllAliveSync()
            if (disciples.size != expectedData.disciples.count { it.isAlive }) {
                Log.e(TAG, "Validation failed: disciple count mismatch")
                return false
            }
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Validation error", e)
            false
        }
    }

    private suspend fun atomicSwap(slot: Int, tempSlot: Int): SaveResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val dbFile = GameDatabase.getDatabaseFile(context, slot)
            val tempDbFile = GameDatabase.getDatabaseFile(context, tempSlot)
            val walFile = GameDatabase.getWalFile(context, slot)
            val shmFile = GameDatabase.getShmFile(context, slot)
            
            database.close()
            
            val backupFile = File(dbFile.parent, "${dbFile.name}.bak_${System.currentTimeMillis()}")
            if (dbFile.exists()) {
                if (!dbFile.renameTo(backupFile)) {
                    dbFile.copyTo(backupFile, overwrite = true)
                }
            }
            
            if (tempDbFile.exists()) {
                if (!tempDbFile.renameTo(dbFile)) {
                    tempDbFile.copyTo(dbFile, overwrite = true)
                    tempDbFile.delete()
                }
            }
            
            if (backupFile.exists()) {
                backupFile.delete()
            }
            
            walFile.delete()
            shmFile.delete()
            
            Log.d(TAG, "Atomic swap completed for slot $slot")
            SaveResult.success(Unit)
            
        } catch (e: Exception) {
            Log.e(TAG, "Atomic swap failed for slot $slot", e)
            SaveResult.failure(SaveError.IO_ERROR, "Swap failed: ${e.message}", e)
        }
    }

    private suspend fun rollback(slot: Int, txnId: Long) = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Rolling back transaction $txnId for slot $slot")
            
            val snapshotFile = File(getSnapshotPath(slot, txnId))
            if (snapshotFile.exists()) {
                val dbFile = GameDatabase.getDatabaseFile(context, slot)
                snapshotFile.copyTo(dbFile, overwrite = true)
                
                Log.i(TAG, "Restored slot $slot from snapshot")
            }
            
            rolledBackTxns.incrementAndGet()
            activeTransactions[txnId]?.state = TransactionState.ROLLED_BACK
            activeTransactions.remove(txnId)
            
            updateStats()
            
        } catch (e: Exception) {
            Log.e(TAG, "Rollback failed for slot $slot", e)
        }
    }

    private suspend fun createSnapshot(slot: Int): ByteArray = withContext(Dispatchers.IO) {
        val dbFile = GameDatabase.getDatabaseFile(context, slot)
        if (dbFile.exists()) {
            dbFile.readBytes()
        } else {
            ByteArray(0)
        }
    }

    private fun getSnapshotPath(slot: Int, txnId: Long): String {
        return File(backupDir, "slot_${slot}_txn_${txnId}.snap").absolutePath
    }

    private fun cleanupBackup(slot: Int, txnId: Long) {
        try {
            val snapshotFile = File(getSnapshotPath(slot, txnId))
            if (snapshotFile.exists()) {
                snapshotFile.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup backup for slot $slot", e)
        }
    }

    private fun cleanupOldBackups() {
        val now = System.currentTimeMillis()
        backupDir.listFiles()?.forEach { file ->
            if (now - file.lastModified() > MAX_BACKUP_AGE_MS) {
                file.delete()
                Log.d(TAG, "Cleaned up old backup: ${file.name}")
            }
        }
    }

    private fun computeChecksum(data: SaveData): String {
        val hash = StringBuilder()
        data.gameData?.let { hash.append(it.currentSlot) }
        hash.append(data.disciples.size)
        hash.append(data.equipment.size)
        return hash.toString().hashCode().toString(16)
    }

    private fun calculateOptimalBatchSize(totalSize: Int): Int {
        return when {
            totalSize < 100 -> totalSize
            totalSize < 500 -> 100
            totalSize < 2000 -> 200
            else -> 500
        }
    }

    private fun estimateSize(value: Any): Long {
        return when (value) {
            is GameData -> 2048L
            is Disciple -> 512L
            is Equipment -> 256L
            is Manual -> 128L
            is Pill -> 64L
            is Material -> 32L
            is Herb -> 32L
            is Seed -> 32L
            is BattleLog -> 256L
            is GameEvent -> 128L
            is ExplorationTeam -> 128L
            is BuildingSlot -> 64L
            else -> 64L
        }
    }

    private fun updateStats() {
        val total = successfulTxns.get() + failedTxns.get()
        _stats.value = TransactionalSaveStats(
            totalTransactions = total,
            successfulTransactions = successfulTxns.get(),
            failedTransactions = failedTxns.get(),
            rolledBackTransactions = rolledBackTxns.get(),
            averageSaveTimeMs = if (total > 0) totalSaveTime.get() / total else 0,
            totalBytesWritten = totalBytesWritten.get()
        )
    }

    fun hasActiveTransactions(): Boolean = activeTransactions.isNotEmpty()

    fun getActiveTransactionCount(): Int = activeTransactions.size

    fun getActiveTransactions(): List<TransactionContext> = activeTransactions.values.toList()

    suspend fun recoverFromWAL(): SaveResult<RecoveryReport> {
        return try {
            val recovery = wal.recover()
            
            val report = RecoveryReport(
                recoveredSlots = recovery.recoveredSlots,
                failedSlots = recovery.failedSlots,
                errors = recovery.errors
            )
            
            for (slot in recovery.recoveredSlots) {
                wal.restoreFromSnapshot(slot) { data ->
                    try {
                        val dbFile = GameDatabase.getDatabaseFile(context, slot)
                        dbFile.writeBytes(data)
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to restore slot $slot from snapshot", e)
                        false
                    }
                }
            }
            
            Log.i(TAG, "WAL recovery completed: ${recovery.recoveredSlots.size} slots recovered")
            SaveResult.success(report)
            
        } catch (e: Exception) {
            Log.e(TAG, "WAL recovery failed", e)
            SaveResult.failure(SaveError.WAL_ERROR, "Recovery failed: ${e.message}", e)
        }
    }

    fun shutdown() {
        scope.cancel()
        
        runBlocking {
            activeTransactions.keys.toList().forEach { txnId ->
                wal.abort(txnId)
            }
        }
        
        cleanupOldBackups()
        
        Log.i(TAG, "TransactionalSaveManager shutdown completed")
    }
}

data class SaveOperation(
    val slot: Int,
    val data: SaveData,
    val enableBackup: Boolean = true
)

data class WriteResult(
    val bytesWritten: Long
)

data class RecoveryReport(
    val recoveredSlots: Set<Int>,
    val failedSlots: Set<Int>,
    val errors: List<String>
)

class TransactionException(message: String) : Exception(message)
