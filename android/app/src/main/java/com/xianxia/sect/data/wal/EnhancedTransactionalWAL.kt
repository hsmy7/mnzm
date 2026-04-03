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
import java.util.concurrent.locks.ReentrantLock
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
        private const val MAX_WAL_SIZE = 10L * 1024 * 1024L
        private const val MAX_SNAPSHOT_AGE_MS = 12 * 60 * 60 * 1000L
        
        @Volatile
        private var instance: EnhancedTransactionalWAL? = null
        
        fun getInstance(context: Context): EnhancedTransactionalWAL {
            return instance ?: synchronized(this) {
                instance ?: EnhancedTransactionalWAL(context.applicationContext).also { instance = it }
            }
        }

        fun resetInstance() {
            synchronized(this) {
                instance?.shutdown()
                instance = null
            }
        }
    }
    
    private val txnCounter = AtomicLong(System.currentTimeMillis())
    private val activeTransactions = ConcurrentHashMap<Long, TransactionRecord>()
    private val lock = ReentrantReadWriteLock()
    private val operationCount = AtomicLong(0)

    // Buffered WAL Writer - 延迟初始化，首次使用时创建
    private val walWriter: BufferedWALWriter by lazy {
        BufferedWALWriter(walFile)
    }
    
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
                walWriter.forceFlush()  // commit后立即flush确保持久化

                activeTransactions.remove(txnId)
                
                record.snapshot?.let { snapshot ->
                    markSnapshotForCleanup(snapshot)
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
                walWriter.forceFlush()  // abort后立即flush确保持久化

                activeTransactions.remove(txnId)
                
                Log.d(TAG, "Transaction $txnId aborted")
                
                SaveResult.success(Unit)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to abort transaction $txnId", e)
                SaveResult.failure(SaveError.WAL_ERROR, "Abort failed: ${e.message}", e)
            }
        }
    }
    
    fun abortSync(txnId: Long): SaveResult<Unit> {
        return lock.write {
            val record = activeTransactions[txnId] ?: run {
                Log.w(TAG, "Attempted to abort unknown transaction $txnId")
                return SaveResult.failure(SaveError.WAL_ERROR, "Unknown transaction: $txnId")
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
                walWriter.forceFlush()  // abort后立即flush确保持久化

                activeTransactions.remove(txnId)
                
                Log.d(TAG, "Transaction $txnId aborted (sync)")
                
                SaveResult.success(Unit)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to abort transaction $txnId (sync)", e)
                SaveResult.failure(SaveError.WAL_ERROR, "Abort failed: ${e.message}", e)
            }
        }
    }
    
    suspend fun recover(): RecoveryResult = withContext(Dispatchers.IO) {
        // 先flush确保所有pending数据持久化到磁盘，再进行恢复读取
        walWriter.forceFlush()

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
    
    private val snapshotsPendingCleanup = ConcurrentHashMap<String, TransactionSnapshot>()
    
    private fun markSnapshotForCleanup(snapshot: TransactionSnapshot) {
        snapshotsPendingCleanup[snapshot.snapshotPath] = snapshot
        Log.d(TAG, "Snapshot marked for cleanup: ${snapshot.snapshotPath}")
    }
    
    private fun cleanupMarkedSnapshots() {
        if (snapshotsPendingCleanup.isEmpty()) return
        
        val toCleanup = snapshotsPendingCleanup.values.toList()
        snapshotsPendingCleanup.clear()
        
        toCleanup.forEach { snapshot ->
            cleanupSnapshot(snapshot)
        }
        
        Log.d(TAG, "Cleaned up ${toCleanup.size} marked snapshots")
    }
    
    private fun cleanupOldSnapshots() {
        val now = System.currentTimeMillis()
        val totalSize = snapshotDir.listFiles()?.sumOf { it.length() } ?: 0L
        val dynamicMaxAge = if (totalSize > 50 * 1024 * 1024L) {
            MAX_SNAPSHOT_AGE_MS / 2
        } else if (totalSize > 100 * 1024 * 1024L) {
            MAX_SNAPSHOT_AGE_MS / 4
        } else {
            MAX_SNAPSHOT_AGE_MS
        }
        
        snapshotDir.listFiles()?.forEach { file ->
            if (now - file.lastModified() > dynamicMaxAge) {
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
                // 先flush所有pending数据，确保compact前所有数据已写入文件
                walWriter.forceFlush()

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
            walWriter.append(record.toBytes())
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
            walWriter.sync()
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
                lastCheckpointTime = if (checkpointFile.exists()) checkpointFile.lastModified() else 0,
                // Buffered writer 监控指标
                bufferedWriterPendingBytes = walWriter.getPendingBytes(),
                bufferFlushCount = walWriter.bufferFlushCount,
                totalBufferedWrites = walWriter.totalBufferedWrites
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
        // 关键修复：将 walWriter.close() 移到 lock.write 块外部，
        // 避免 BufferedWALWriter.close() 内部再次获取 lock 导致的重入死锁。
        // ReentrantLock 虽然支持重入，但这里的设计意图是分离关注点：
        // lock.write 保护 activeTransactions 的清理，close() 独立处理 IO 资源释放。
        val writerToClose: BufferedWALWriter?
        lock.write {
            writerToClose = walWriter.takeIf { it.isOpen() }
            activeTransactions.clear()
            Log.i(TAG, "EnhancedTransactionalWAL shutdown: transactions cleared")
        }
        // 在锁外关闭 writer，避免重入死锁
        try {
            writerToClose?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing WAL writer during shutdown", e)
        }
        Log.i(TAG, "EnhancedTransactionalWAL shutdown completed")
    }

    /**
     * Buffered WAL Writer - 缓冲写入器
     *
     * 核心优化目标：消除高频 FileOutputStream open/close 开销
     *
     * 设计要点：
     * 1. 持久化打开 FileOutputStream + BufferedOutputStream
     * 2. 数据先写入内存 buffer，满足条件后批量 flush 到磁盘
     * 3. 自动 flush 策略：pendingBytes >= maxPendingBytes 或时间间隔 >= flushIntervalMs
     * 4. 关键操作（commit/abort/checkpoint）后强制 flush + sync 保证持久化
     *
     * 线程安全与资源安全：
     * - 所有 public 方法入口检查 [isClosed] 状态
     * - [initialize] 支持半初始化状态检测与自动恢复
     * - [close] 使用 finally 保障，确保异常时不跳过清理步骤
     * - 提供 [finalize] 作为最后防线（不推荐依赖 GC）
     */
    private class BufferedWALWriter(
        private val walFile: File,
        private val bufferSize: Int = 64 * 1024,       // 64KB buffer
        private val flushIntervalMs: Long = 1000L,      // 1秒自动flush
        private val maxPendingBytes: Long = 256 * 1024   // 256KB pending数据强制flush
    ) {
        @Volatile
        var isClosed = false
            private set

        private var outputStream: BufferedOutputStream? = null
        private var fileOutputStream: FileOutputStream? = null
        private var lastFlushTime = 0L
        private var pendingBytes = 0L
        private val lock = ReentrantLock()
        @Volatile
        var bufferFlushCount = 0L
            private set
        @Volatile
        var totalBufferedWrites = 0L
            private set

        /**
         * 检查 writer 是否已关闭，若已关闭则抛出 IllegalStateException
         */
        private fun checkNotClosed() {
            if (isClosed) {
                throw IllegalStateException("BufferedWALWriter is already closed for file: ${walFile.name}")
            }
        }

        /**
         * 初始化 writer，打开文件流
         * 必须在使用前调用
         *
         * 半初始化恢复逻辑：
         * 如果 fileOutputStream != null 但 outputStream == null，
         * 说明上次 initialize 中途失败（如磁盘满），先清理残留再重试。
         */
        fun initialize() {
            lock.lock()
            try {
                checkNotClosed()

                if (outputStream != null) return  // 已完全初始化

                // 半初始化恢复：上一次 initialize 在创建 BufferedOutputStream 前失败
                if (fileOutputStream != null) {
                    Log.w(TAG, "Detected half-initialized state in BufferedWALWriter, cleaning up before retry")
                    try {
                        fileOutputStream?.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error closing residual fileOutputStream during recovery", e)
                    }
                    fileOutputStream = null
                }

                walFile.parentFile?.mkdirs()
                fileOutputStream = FileOutputStream(walFile, true)
                outputStream = BufferedOutputStream(fileOutputStream, bufferSize)
                lastFlushTime = System.currentTimeMillis()
                Log.d(TAG, "BufferedWALWriter initialized for ${walFile.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize BufferedWALWriter", e)
                // 初始化失败时清理可能处于半开状态的资源
                if (outputStream == null && fileOutputStream != null) {
                    try {
                        fileOutputStream?.close()
                    } catch (closeEx: Exception) {
                        Log.w(TAG, "Error cleaning up fileOutputStream after init failure", closeEx)
                    }
                    fileOutputStream = null
                }
                throw e
            } finally {
                lock.unlock()
            }
        }

        /**
         * 追加数据到缓冲区
         * 数据写入内存 buffer，不立即刷盘
         * 当 pendingBytes 或时间达到阈值时自动 flush
         */
        fun append(data: ByteArray) {
            lock.lock()
            try {
                checkNotClosed()
                ensureInitialized()
                outputStream?.write(data) ?: throw IllegalStateException("BufferedWALWriter not initialized")
                pendingBytes += data.size.toLong()
                totalBufferedWrites++

                // 自动 flush 检查
                val now = System.currentTimeMillis()
                if (pendingBytes >= maxPendingBytes || (now - lastFlushTime) >= flushIntervalMs) {
                    performFlush()
                    lastFlushTime = now
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to append to buffered writer", e)
                throw e
            } finally {
                lock.unlock()
            }
        }

        /**
         * 强制刷盘 - 将 buffer 数据写入文件系统
         * 用于 commit/abort 等关键操作后的持久化保证
         */
        fun forceFlush() {
            lock.lock()
            try {
                if (isClosed || outputStream == null) return
                performFlush()
                lastFlushTime = System.currentTimeMillis()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to force flush", e)
                throw e
            } finally {
                lock.unlock()
            }
        }

        /**
         * 同步刷盘 - flush + fd.sync()
         * 确保数据真正写入物理磁盘
         */
        fun sync() {
            lock.lock()
            try {
                if (isClosed || outputStream == null) return
                performFlush()
                // 调用底层 fd.sync() 确保数据落盘
                fileOutputStream?.fd?.sync()
                lastFlushTime = System.currentTimeMillis()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync buffered writer", e)
                throw e
            } finally {
                lock.unlock()
            }
        }

        /**
         * 关闭 writer，释放资源
         * shutdown 时调用
         *
         * 使用 .use{} 思路的 finally 保障：
         * 即使中间步骤抛出异常，后续的清理步骤仍会执行。
         * 可安全多次调用（幂等）。
         */
        fun close() {
            if (isClosed) return  // 幂等：已关闭则直接返回

            lock.lock()
            try {
                if (isClosed) return  // 双重检查（加锁后）

                var error: Throwable? = null

                // Step 1: flush buffered data
                outputStream?.let { stream ->
                    try {
                        stream.flush()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error flushing on close", e)
                        error = error?.let { RuntimeException("Multiple errors on close", it) } ?: e
                    }
                }

                // Step 2: close buffered stream
                outputStream?.let { stream ->
                    try {
                        stream.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error closing output stream", e)
                        error = error?.let { RuntimeException("Multiple errors on close", it) } ?: e
                    }
                }

                // Step 3: sync and close file output stream
                fileOutputStream?.let { fos ->
                    try {
                        fos.fd.sync()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error syncing fd on close", e)
                        // sync 失败不是致命错误，继续执行 close
                    }
                    try {
                        fos.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error closing file output stream", e)
                        error = error?.let { RuntimeException("Multiple errors on close", it) } ?: e
                    }
                }

                // Step 4: 清理引用（无论是否出错都必须执行）
                outputStream = null
                fileOutputStream = null
                pendingBytes = 0L
                isClosed = true

                Log.d(TAG, "BufferedWALWriter closed")

                // 如果有累积错误，抛出最后一个（保留日志信息）
                val capturedError = error
                if (capturedError != null && capturedError is java.io.IOException) {
                    throw capturedError
                }
            } finally {
                lock.unlock()
            }
        }

        /**
         * 获取当前 pending 字节数（尚未 flush 的数据量）
         */
        fun getPendingBytes(): Long {
            lock.lock()
            try {
                return pendingBytes
            } finally {
                lock.unlock()
            }
        }

        /**
         * 检查 writer 是否已初始化并打开
         */
        fun isOpen(): Boolean {
            lock.lock()
            try {
                return !isClosed && outputStream != null
            } finally {
                lock.unlock()
            }
        }

        /**
         * 确保 writer 已初始化
         */
        private fun ensureInitialized() {
            if (outputStream == null) {
                initialize()
            }
        }

        /**
         * 执行实际的 flush 操作
         */
        private fun performFlush() {
            try {
                outputStream?.flush()
                bufferFlushCount++
                pendingBytes = 0L
            } catch (e: Exception) {
                Log.e(TAG, "Flush failed", e)
                throw e
            }
        }

        /**
         * 安全网：finalize 作为最后防线防止资源泄漏
         *
         * 注意：不应依赖 finalize 来管理资源生命周期。
         * 正确的使用方式是通过 shutdown() 显式关闭。
         * 此方法仅作为防御性编程的最后手段。
         */
        protected fun finalize() {
            if (!isClosed) {
                Log.w(TAG, "BufferedWALWriter was garbage collected without being closed! Forcing cleanup.")
                try {
                    close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in finalize() cleanup", e)
                }
            }
        }
    }
}

data class EnhancedWALStats(
    val activeTransactions: Int,
    val walFileSize: Long,
    val totalOperations: Long,
    val lastCheckpointTime: Long,
    // Buffered writer 监控指标
    val bufferedWriterPendingBytes: Long = 0,
    val bufferFlushCount: Long = 0,
    val totalBufferedWrites: Long = 0
)
