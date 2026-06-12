package com.xianxia.sect.data.wal

import com.xianxia.sect.data.unified.SaveResult
import java.util.concurrent.atomic.AtomicReference

enum class TransactionStatus { ACTIVE, COMMITTING, ABORTING, COMMITTED, ABORTED }

enum class WALEntryType {
    BEGIN, COMMIT, ABORT, SNAPSHOT, DATA
}

data class RecoveryResult(
    val success: Boolean,
    val recoveredSlots: Set<Int>,
    val failedSlots: Set<Int>,
    val errors: List<String>
)

data class EnhancedWALStats(
    val activeTransactions: Int = 0,
    val totalTransactions: Long = 0,
    val committedCount: Long = 0,
    val abortedCount: Long = 0,
    val walFileSize: Long = 0,
    val lastCheckpointTime: Long = 0
)

data class TransactionRecord(
    val txnId: Long,
    val slot: Int,
    val operation: WALEntryType,
    val startTime: Long,
    val snapshotFile: java.io.File?,
    val statusRef: AtomicReference<TransactionStatus> = AtomicReference(TransactionStatus.ACTIVE),
    var checksum: String = "",
    var gameEvent: String? = null,
    var currentGameYear: Int = 0
) {
    val status: TransactionStatus get() = statusRef.get()

    fun compareAndSetStatus(expected: TransactionStatus, newStatus: TransactionStatus): Boolean =
        statusRef.compareAndSet(expected, newStatus)
}

interface WALProvider {

    suspend fun beginTransaction(
        slot: Int,
        operation: WALEntryType,
        snapshotProvider: (suspend (Int) -> ByteArray)?
    ): SaveResult<Long>

    suspend fun commit(
        txnId: Long,
        checksum: String = "",
        gameEvent: String? = null,
        currentGameYear: Int = 0
    ): SaveResult<Unit>

    suspend fun createImportantSnapshot(
        slot: Int,
        snapshotProvider: suspend (Int) -> ByteArray,
        eventType: String,
        currentGameYear: Int = 0
    ): SaveResult<Long>

    suspend fun abort(txnId: Long): SaveResult<Unit>

    fun abortSync(txnId: Long): SaveResult<Unit>

    suspend fun recover(): RecoveryResult

    suspend fun restoreFromSnapshot(
        slot: Int,
        dataConsumer: suspend (ByteArray) -> Boolean
    ): SaveResult<Unit>

    suspend fun checkpoint(): Boolean

    fun hasActiveTransactions(): Boolean

    fun getActiveTransactionCount(): Int

    fun getActiveTransactionIds(): Set<Long>

    fun getTransactionRecord(txnId: Long): TransactionRecord?

    fun getStats(): EnhancedWALStats

    fun clear()

    fun shutdown()
}
