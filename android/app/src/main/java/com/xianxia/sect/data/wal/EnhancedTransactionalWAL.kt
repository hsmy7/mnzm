package com.xianxia.sect.data.wal

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.unified.SaveError
import com.xianxia.sect.data.unified.SaveResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

data class TransactionSnapshot(
    val txnId: Long,
    val slot: Int,
    val timestamp: Long,
    val snapshotPath: String,
    val snapshotSize: Long,
    val checksum: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TransactionSnapshot) return false
        return txnId == other.txnId && checksum.contentEquals(other.checksum)
    }
    
    override fun hashCode(): Int = 31 * txnId.hashCode() + checksum.contentHashCode()
}

data class TransactionRecord(
    val txnId: Long,
    val slot: Int,
    val operation: WALEntryType,
    var state: EnhancedTransactionState,
    val startTime: Long,
    var endTime: Long = 0,
    var snapshot: TransactionSnapshot? = null,
    var checksum: String = ""
)

enum class EnhancedTransactionState {
    ACTIVE,
    COMMITTED,
    ABORTED,
    PREPARING
}

enum class WALEntryType(val code: Byte) {
    BEGIN(0x01),
    COMMIT(0x02),
    ABORT(0x03),
    DATA(0x04),
    CHECKPOINT(0x05),
    SNAPSHOT(0x06),
    ROLLBACK(0x07);
    
    companion object {
        fun fromCode(code: Byte): WALEntryType? = entries.find { it.code == code }
    }
}

data class WALRecord(
    val txnId: Long,
    val slot: Int,
    val type: WALEntryType,
    val timestamp: Long = System.currentTimeMillis(),
    val checksum: String = "",
    val snapshotPath: String = "",
    val metadata: Map<String, String> = emptyMap()
) {
    fun toBytes(): ByteArray {
        val baos = ByteArrayOutputStream(256)
        DataOutputStream(baos).use { dos ->
            dos.writeInt(MAGIC)
            dos.writeShort(VERSION.toInt())
            dos.writeLong(txnId)
            dos.writeInt(slot)
            dos.writeByte(type.code.toInt())
            dos.writeLong(timestamp)
            dos.writeUTF(checksum)
            dos.writeUTF(snapshotPath)
            dos.writeInt(metadata.size)
            metadata.forEach { (k, v) ->
                dos.writeUTF(k)
                dos.writeUTF(v)
            }
        }
        val data = baos.toByteArray()
        
        val result = ByteBuffer.allocate(4 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        result.putInt(data.size)
        result.put(data)
        return result.array()
    }
    
    companion object {
        const val MAGIC = 0x58534156
        const val VERSION = 4
        
        fun fromBytes(buffer: ByteBuffer): WALRecord? {
            return try {
                val magic = buffer.int
                if (magic != MAGIC) return null
                
                val version = buffer.short.toInt()
                if (version > VERSION) return null
                
                val txnId = buffer.long
                val slot = buffer.int
                val typeCode = buffer.get()
                val type = WALEntryType.fromCode(typeCode) ?: return null
                val timestamp = buffer.long
                val checksum = readUTF(buffer)
                val snapshotPath = readUTF(buffer)
                val metadataSize = buffer.int
                val metadata = mutableMapOf<String, String>()
                repeat(metadataSize) {
                    metadata[readUTF(buffer)] = readUTF(buffer)
                }
                
                WALRecord(txnId, slot, type, timestamp, checksum, snapshotPath, metadata)
            } catch (e: Exception) {
                Log.w("WALRecord", "Failed to parse record: ${e.message}")
                null
            }
        }
        
        private fun readUTF(buffer: ByteBuffer): String {
            val len = buffer.short.toInt() and 0xFFFF
            val bytes = ByteArray(len)
            buffer.get(bytes)
            return String(bytes, Charsets.UTF_8)
        }
    }
}

data class RecoveryResult(
    val success: Boolean,
    val recoveredSlots: Set<Int>,
    val failedSlots: Set<Int>,
    val errors: List<String>
)

class EnhancedTransactionalWAL private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "EnhancedTransactionalWAL"
        private const val WAL_DIR = "wal_v4"
        private const val WAL_FILE = "transactions.wal"
        private const val SNAPSHOT_DIR = "snapshots"
        private const val CHECKPOINT_FILE = "checkpoint.dat"
        private const val MAX_WAL_SIZE = 20L * 1024 * 1024
        private const val MAX_SNAPSHOT_AGE_MS = 24 * 60 * 60 * 1000L
        
        @Volatile
        private var instance: EnhancedTransactionalWAL? = null
        
        fun getInstance(context: Context): EnhancedTransactionalWAL {
            return instance ?: synchronized(this) {
                instance ?: EnhancedTransactionalWAL(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val txnCounter = AtomicLong(System.currentTimeMillis())
    private val activeTransactions = ConcurrentHashMap<Long, TransactionRecord>()
    private val lock = ReentrantReadWriteLock()
    private val operationCount = AtomicLong(0)
    
    private val walDir: File by lazy {
        File(context.filesDir, WAL_DIR).apply {
            if (!exists()) mkdirs()
        }
    }
    
    private val snapshotDir: File by lazy {
        File(walDir, SNAPSHOT_DIR).apply {
            if (!exists()) mkdirs()
        }
    }
    
    private val walFile: File by lazy { File(walDir, WAL_FILE) }
    private val checkpointFile: File by lazy { File(walDir, CHECKPOINT_FILE) }
    
    suspend fun beginTransaction(
        slot: Int,
        operation: WALEntryType,
        snapshotProvider: (suspend (Int) -> ByteArray)?
    ): SaveResult<Long> = withContext(Dispatchers.IO) {
        val snapshotData = if (snapshotProvider != null) {
            try {
                snapshotProvider(slot)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get snapshot data for slot $slot", e)
                null
            }
        } else null
        
        lock.write {
            try {
                val txnId = txnCounter.incrementAndGet()
                
                val snapshot = if (snapshotData != null && snapshotData.isNotEmpty()) {
                    createSnapshotInternal(txnId, slot, snapshotData)
                } else null
                
                val record = TransactionRecord(
                    txnId = txnId,
                    slot = slot,
                    operation = operation,
                    state = EnhancedTransactionState.ACTIVE,
                    startTime = System.currentTimeMillis(),
                    snapshot = snapshot
                )
                
                activeTransactions[txnId] = record
                
                appendRecord(WALRecord(
                    txnId = txnId,
                    slot = slot,
                    type = WALEntryType.BEGIN,
                    snapshotPath = snapshot?.snapshotPath ?: ""
                ))
                
                Log.d(TAG, "Transaction $txnId started for slot $slot with snapshot=${snapshot != null}")
                SaveResult.success(txnId)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to begin transaction for slot $slot", e)
                SaveResult.failure(SaveError.WAL_ERROR, "Failed to begin transaction: ${e.message}", e)
            }
        }
    }
    
    private fun createSnapshotInternal(
        txnId: Long,
        slot: Int,
        data: ByteArray
    ): TransactionSnapshot? {
        return try {
            val snapshotFile = File(snapshotDir, "slot_${slot}_txn_${txnId}.snap")
            val tempFile = File(snapshotDir, "slot_${slot}_txn_${txnId}.snap.tmp")
            
            FileOutputStream(tempFile).use { fos ->
                fos.write(data)
                fos.fd.sync()
            }
            
            if (!tempFile.renameTo(snapshotFile)) {
                tempFile.delete()
                Log.w(TAG, "Failed to rename snapshot file for txn $txnId")
                return null
            }
            
            val checksum = computeChecksum(data)
            
            TransactionSnapshot(
                txnId = txnId,
                slot = slot,
                timestamp = System.currentTimeMillis(),
                snapshotPath = snapshotFile.absolutePath,
                snapshotSize = data.size.toLong(),
                checksum = checksum
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create snapshot for txn $txnId", e)
            null
        }
    }
    
    private suspend fun createSnapshot(
        txnId: Long,
        slot: Int,
        snapshotProvider: suspend (Int) -> ByteArray
    ): TransactionSnapshot? {
        return try {
            val data = snapshotProvider(slot)
            if (data.isEmpty()) return null
            
            createSnapshotInternal(txnId, slot, data)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create snapshot for txn $txnId", e)
            null
        }
    }
    
    suspend fun commit(txnId: Long, checksum: String = ""): SaveResult<Unit> = withContext(Dispatchers.IO) {
        lock.write {
            val record = activeTransactions[txnId] ?: run {
                Log.w(TAG, "Attempted to commit unknown transaction $txnId")
                return@withContext SaveResult.failure(SaveError.WAL_ERROR, "Unknown transaction: $txnId")
            }
            
            try {
                record.state = EnhancedTransactionState.COMMITTED
                record.endTime = System.currentTimeMillis()
                record.checksum = checksum
                
                appendRecord(WALRecord(
                    txnId = txnId,
                    slot = record.slot,
                    type = WALEntryType.COMMIT,
                    checksum = checksum
                ))
                
                sync()
                
                activeTransactions.remove(txnId)
                
                record.snapshot?.let { snapshot ->
                    cleanupSnapshot(snapshot)
                }
                
                if (operationCount.incrementAndGet() % 100 == 0L) {
                    checkpoint()
                }
                
                val elapsed = record.endTime - record.startTime
                Log.d(TAG, "Transaction $txnId committed in ${elapsed}ms")
                
                SaveResult.success(Unit)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to commit transaction $txnId", e)
                SaveResult.failure(SaveError.WAL_ERROR, "Commit failed: ${e.message}", e)
            }
        }
    }
    
    suspend fun abort(txnId: Long): SaveResult<Unit> = withContext(Dispatchers.IO) {
        lock.write {
            val record = activeTransactions[txnId] ?: run {
                Log.w(TAG, "Attempted to abort unknown transaction $txnId")
                return@withContext SaveResult.failure(SaveError.WAL_ERROR, "Unknown transaction: $txnId")
            }
            
            try {
                record.state = EnhancedTransactionState.ABORTED
                record.endTime = System.currentTimeMillis()
                
                appendRecord(WALRecord(
                    txnId = txnId,
                    slot = record.slot,
                    type = WALEntryType.ABORT
                ))
                
                sync()
                
                activeTransactions.remove(txnId)
                
                Log.d(TAG, "Transaction $txnId aborted")
                
                SaveResult.success(Unit)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to abort transaction $txnId", e)
                SaveResult.failure(SaveError.WAL_ERROR, "Abort failed: ${e.message}", e)
            }
        }
    }
    
    suspend fun recover(): RecoveryResult = withContext(Dispatchers.IO) {
        lock.read {
            if (!walFile.exists()) {
                return@withContext RecoveryResult(true, emptySet(), emptySet(), emptyList())
            }
            
            val uncommittedTxns = mutableListOf<TransactionRecord>()
            val committedTxns = mutableSetOf<Long>()
            val errors = mutableListOf<String>()
            
            try {
                readAllRecords { record ->
                    when (record.type) {
                        WALEntryType.BEGIN -> {
                            uncommittedTxns.add(TransactionRecord(
                                txnId = record.txnId,
                                slot = record.slot,
                                operation = WALEntryType.BEGIN,
                                state = EnhancedTransactionState.ACTIVE,
                                startTime = record.timestamp,
                                snapshot = if (record.snapshotPath.isNotEmpty()) {
                                    TransactionSnapshot(
                                        txnId = record.txnId,
                                        slot = record.slot,
                                        timestamp = record.timestamp,
                                        snapshotPath = record.snapshotPath,
                                        snapshotSize = 0,
                                        checksum = ByteArray(0)
                                    )
                                } else null
                            ))
                        }
                        WALEntryType.COMMIT -> committedTxns.add(record.txnId)
                        WALEntryType.ABORT -> {
                            uncommittedTxns.removeAll { it.txnId == record.txnId }
                        }
                        else -> {}
                    }
                }
                
                val pendingTxns = uncommittedTxns.filter { it.txnId !in committedTxns }
                
                if (pendingTxns.isNotEmpty()) {
                    Log.w(TAG, "Found ${pendingTxns.size} uncommitted transactions")
                }
                
                val recoveredSlots = mutableSetOf<Int>()
                val failedSlots = mutableSetOf<Int>()
                
                pendingTxns.forEach { txn ->
                    val snapshot = txn.snapshot
                    if (snapshot != null && File(snapshot.snapshotPath).exists()) {
                        recoveredSlots.add(txn.slot)
                        Log.i(TAG, "Slot ${txn.slot} has recoverable snapshot from txn ${txn.txnId}")
                    } else {
                        failedSlots.add(txn.slot)
                        errors.add("No snapshot available for slot ${txn.slot}")
                    }
                }
                
                RecoveryResult(
                    success = failedSlots.isEmpty(),
                    recoveredSlots = recoveredSlots,
                    failedSlots = failedSlots,
                    errors = errors
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "WAL recovery failed", e)
                RecoveryResult(false, emptySet(), emptySet(), listOf(e.message ?: "Unknown error"))
            }
        }
    }
    
    suspend fun restoreFromSnapshot(
        slot: Int,
        dataConsumer: suspend (ByteArray) -> Boolean
    ): SaveResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val snapshotFiles = snapshotDir.listFiles { file ->
                file.name.startsWith("slot_${slot}_txn_") && file.extension == "snap"
            }?.sortedByDescending { it.lastModified() }
            
            if (snapshotFiles.isNullOrEmpty()) {
                return@withContext SaveResult.failure(SaveError.SLOT_CORRUPTED, "No snapshot found for slot $slot")
            }
            
            for (snapshotFile in snapshotFiles) {
                try {
                    val data = snapshotFile.readBytes()
                    if (dataConsumer(data)) {
                        Log.i(TAG, "Restored slot $slot from snapshot ${snapshotFile.name}")
                        return@withContext SaveResult.success(Unit)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to restore from snapshot ${snapshotFile.name}", e)
                }
            }
            
            SaveResult.failure(SaveError.RESTORE_FAILED, "All snapshots failed for slot $slot")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore snapshot for slot $slot", e)
            SaveResult.failure(SaveError.RESTORE_FAILED, "Snapshot restore failed: ${e.message}", e)
        }
    }
    
    private fun cleanupSnapshot(snapshot: TransactionSnapshot) {
        try {
            val file = File(snapshot.snapshotPath)
            if (file.exists()) {
                file.delete()
                Log.d(TAG, "Cleaned up snapshot: ${snapshot.snapshotPath}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup snapshot: ${snapshot.snapshotPath}", e)
        }
    }
    
    private fun cleanupOldSnapshots() {
        val now = System.currentTimeMillis()
        snapshotDir.listFiles()?.forEach { file ->
            if (now - file.lastModified() > MAX_SNAPSHOT_AGE_MS) {
                file.delete()
                Log.d(TAG, "Cleaned up old snapshot: ${file.name}")
            }
        }
    }
    
    suspend fun checkpoint(): Boolean = withContext(Dispatchers.IO) {
        lock.write {
            if (activeTransactions.isNotEmpty()) {
                Log.w(TAG, "Checkpoint skipped - ${activeTransactions.size} active transactions")
                return@withContext false
            }
            
            try {
                saveCheckpoint()
                
                appendRecord(WALRecord(
                    txnId = txnCounter.incrementAndGet(),
                    slot = 0,
                    type = WALEntryType.CHECKPOINT
                ))
                
                compact()
                cleanupOldSnapshots()
                
                Log.i(TAG, "WAL checkpoint completed")
                true
                
            } catch (e: Exception) {
                Log.e(TAG, "Checkpoint failed", e)
                false
            }
        }
    }
    
    private fun saveCheckpoint() {
        try {
            val data = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
            data.putLong(System.currentTimeMillis())
            data.putLong(txnCounter.get())
            data.putLong(operationCount.get())
            data.putLong(activeTransactions.size.toLong())
            
            FileOutputStream(checkpointFile).use { fos ->
                fos.write(data.array())
                fos.fd.sync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save checkpoint", e)
        }
    }
    
    private fun appendRecord(record: WALRecord) {
        try {
            walDir.mkdirs()
            FileOutputStream(walFile, true).use { fos ->
                fos.write(record.toBytes())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to append WAL record", e)
            throw e
        }
    }
    
    private fun readAllRecords(consumer: (WALRecord) -> Unit) {
        if (!walFile.exists()) return
        
        FileInputStream(walFile).use { fis ->
            val channel = fis.channel
            val buffer = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
            
            while (channel.position() < channel.size()) {
                buffer.clear()
                buffer.limit(4)
                if (channel.read(buffer) != 4) break
                
                buffer.flip()
                val recordSize = buffer.int
                
                if (recordSize <= 0 || recordSize > 65536) break
                
                buffer.clear()
                buffer.limit(recordSize)
                if (channel.read(buffer) != recordSize) break
                
                buffer.flip()
                WALRecord.fromBytes(buffer)?.let(consumer)
            }
        }
    }
    
    private fun compact() {
        if (!walFile.exists()) return
        
        try {
            val size = walFile.length()
            if (size > MAX_WAL_SIZE) {
                val tempFile = File(walDir, "transactions.wal.tmp")
                
                val committedTxns = mutableSetOf<Long>()
                readAllRecords { record ->
                    if (record.type == WALEntryType.COMMIT) {
                        committedTxns.add(record.txnId)
                    }
                }
                
                FileOutputStream(tempFile).use { fos ->
                    readAllRecords { record ->
                        if (record.txnId in committedTxns || record.type == WALEntryType.BEGIN) {
                            fos.write(record.toBytes())
                        }
                    }
                }
                
                walFile.delete()
                tempFile.renameTo(walFile)
                
                Log.i(TAG, "WAL compacted: $size -> ${walFile.length()} bytes")
            }
        } catch (e: Exception) {
            Log.e(TAG, "WAL compaction failed", e)
        }
    }
    
    private fun sync() {
        try {
            if (walFile.exists()) {
                FileOutputStream(walFile, true).use { fos ->
                    fos.fd.sync()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync WAL", e)
        }
    }
    
    private fun computeChecksum(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }
    
    fun hasActiveTransactions(): Boolean = activeTransactions.isNotEmpty()
    
    fun getActiveTransactionCount(): Int = activeTransactions.size
    
    fun getActiveTransactionIds(): Set<Long> = activeTransactions.keys.toSet()
    
    fun getTransactionRecord(txnId: Long): TransactionRecord? = activeTransactions[txnId]
    
    fun getStats(): EnhancedWALStats {
        return lock.read {
            EnhancedWALStats(
                activeTransactions = activeTransactions.size,
                walFileSize = if (walFile.exists()) walFile.length() else 0,
                totalOperations = operationCount.get(),
                lastCheckpointTime = if (checkpointFile.exists()) checkpointFile.lastModified() else 0
            )
        }
    }
    
    fun clear() {
        lock.write {
            try {
                if (walFile.exists()) walFile.delete()
                if (checkpointFile.exists()) checkpointFile.delete()
                activeTransactions.clear()
                Log.d(TAG, "WAL cleared")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear WAL", e)
            }
        }
    }
    
    fun shutdown() {
        lock.write {
            sync()
            activeTransactions.clear()
            Log.i(TAG, "EnhancedTransactionalWAL shutdown completed")
        }
    }
}

data class EnhancedWALStats(
    val activeTransactions: Int,
    val walFileSize: Long,
    val totalOperations: Long,
    val lastCheckpointTime: Long
)
