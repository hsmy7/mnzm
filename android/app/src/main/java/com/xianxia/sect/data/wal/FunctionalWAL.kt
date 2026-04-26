package com.xianxia.sect.data.wal

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.StorageConstants
import com.xianxia.sect.data.unified.SaveError
import com.xianxia.sect.data.unified.SaveResult
import com.xianxia.sect.di.ApplicationScopeProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

/**
 * 应用级 Write-Ahead Log (WAL) 实现。
 *
 * 替代 NoOpWAL，提供真正的事务持久化和崩溃恢复能力。
 *
 * 设计要点：
 * 1. 二进制 WAL 条目格式：[magic(2B)] [type(1B)] [txnId(8B)] [slotId(4B)] [timestamp(8B)] [dataLen(4B)] [data(var)] [checksum(32B SHA-256)]
 * 2. 快照数据存储在独立文件中，WAL 条目仅保存引用元数据
 * 3. 使用 ReentrantLock 保证写入原子性，ConcurrentHashMap 管理活跃事务
 * 4. 周期性 flush + 累积字节阈值强制 flush 双重保障
 * 5. Checkpoint 压缩 WAL 文件，移除已完成事务的条目
 */
@Singleton
class FunctionalWAL @Inject constructor(
    @ApplicationContext private val context: Context,
    private val applicationScopeProvider: ApplicationScopeProvider
) : WALProvider {
    companion object {
        private const val TAG = "FunctionalWAL"

        /** WAL 条目 magic 字节: 0x57='W', 0x34='4' (即 0xW4) */
        private const val MAGIC_BYTE_1: Byte = 0x57
        private const val MAGIC_BYTE_2: Byte = 0x34

        /** 条目固定头部大小: magic(2) + type(1) + txnId(8) + slotId(4) + timestamp(8) + dataLength(4) = 27 */
        private const val ENTRY_HEADER_SIZE = 27

        /** SHA-256 校验和长度 */
        private const val CHECKSUM_SIZE = 32

        /** 最小完整条目大小 (头部 + 校验和，无 data) */
        private const val ENTRY_MIN_SIZE = ENTRY_HEADER_SIZE + CHECKSUM_SIZE

        /** 快照文件命名格式: slot_{slotId}_txn_{txnId}.snap (与 SnapshotRetentionPolicy 兼容) */
        private const val SNAPSHOT_FILE_FORMAT = "slot_%d_txn_%d.snap"
    }

    /** 解析后的 WAL 条目 */
    private data class ParsedEntry(
        val type: WALEntryType,
        val txnId: Long,
        val slotId: Int,
        val timestamp: Long,
        val data: ByteArray,
        val valid: Boolean
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /** 恢复过程中追踪的事务信息 */
    private data class RecoveryTxnInfo(
        val slotId: Int,
        val operation: WALEntryType?,
        val timestamp: Long
    )

    // ==================== 状态 ====================

    private val activeTransactions = ConcurrentHashMap<Long, TransactionRecord>()
    private val txnLocks = ConcurrentHashMap<Long, ReentrantLock>()
    private var cachedInitEntries: List<ParsedEntry>? = null
    private val txnIdGenerator = AtomicLong(System.currentTimeMillis())
    private val writeLock = ReentrantLock()
    private val pendingBytes = AtomicLong(0L)
    private val isShutdown = AtomicBoolean(false)

    // 统计计数器
    private val totalTransactions = AtomicLong(0L)
    private val committedCount = AtomicLong(0L)
    private val abortedCount = AtomicLong(0L)
    private val commitsSinceCheckpoint = AtomicLong(0L)
    private var lastCheckpointTime: Long = 0L

    // 文件路径
    private val walDir: File = File(context.filesDir, StorageConstants.WAL_DIR_NAME)
    private val walFile: File = File(walDir, StorageConstants.WAL_FILE_NAME)
    private val snapshotDir: File = File(walDir, StorageConstants.SNAPSHOT_DIR_NAME)

    // 输出流 (所有访问在 writeLock 内)
    private var bufferedOutputStream: BufferedOutputStream? = null

    // 协程作用域 (flush 定时器)
    private val scope get() = applicationScopeProvider.ioScope
    private var flushJob: Job? = null

    // ==================== 初始化 ====================

    init {
        try {
            walDir.mkdirs()
            snapshotDir.mkdirs()
            openWALFile()
            recoverTxnIdCounter()
            startFlushTimer()
            Log.i(TAG, "FunctionalWAL initialized: dir=${walDir.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "FunctionalWAL initialization failed", e)
        }
    }

    /**
     * 打开 WAL 文件，以追加模式创建 BufferedOutputStream。
     * 调用方须持有 writeLock 或在单线程 init 中调用。
     */
    private fun openWALFile() {
        writeLock.withLock {
            try {
                closeStreamsInternal()
                if (!walFile.exists()) {
                    walFile.parentFile?.mkdirs()
                    walFile.createNewFile()
                }
                bufferedOutputStream = BufferedOutputStream(
                    FileOutputStream(walFile, true),
                    StorageConstants.WAL_BUFFER_SIZE_BYTES
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open WAL file: ${walFile.absolutePath}", e)
            }
        }
    }

    /**
     * 从已有 WAL 文件中恢复 txnId 计数器，避免 ID 冲突。
     */
    private fun recoverTxnIdCounter() {
        try {
            if (!walFile.exists() || walFile.length() == 0L) return
            val data = walFile.readBytes()
            val entries = parseEntries(data)
            cachedInitEntries = entries
            val maxTxnId = entries.maxOfOrNull { it.txnId } ?: 0L
            txnIdGenerator.updateAndGet { current -> maxOf(current, maxTxnId + 1) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to recover txnId counter from WAL", e)
        }
    }

    /**
     * 启动周期性 flush 定时器。
     */
    private fun startFlushTimer() {
        flushJob?.cancel()
        flushJob = scope.launch {
            while (isActive && !isShutdown.get()) {
                delay(StorageConstants.WAL_FLUSH_INTERVAL_MS)
                try {
                    flushInternal()
                } catch (e: Exception) {
                    Log.e(TAG, "Periodic flush failed", e)
                }
            }
        }
    }

    // ==================== 流管理 ====================

    private fun flushInternal() {
        writeLock.withLock {
            try {
                bufferedOutputStream?.flush()
                pendingBytes.set(0L)
            } catch (e: Exception) {
                Log.e(TAG, "Flush failed", e)
            }
        }
    }

    /**
     * 写入后检查累积字节数，超过阈值则强制 flush。
     */
    private fun conditionalFlush(bytesWritten: Int) {
        val pending = pendingBytes.addAndGet(bytesWritten.toLong())
        if (pending >= StorageConstants.WAL_MAX_PENDING_BYTES) {
            flushInternal()
        }
    }

    private fun closeStreamsInternal() {
        try {
            bufferedOutputStream?.flush()
            bufferedOutputStream?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing output stream", e)
        }
        bufferedOutputStream = null
    }

    // ==================== 二进制格式读写 ====================

    /**
     * 写入一条 WAL 条目。
     *
     * 格式: [magic(2B)] [type(1B)] [txnId(8B)] [slotId(4B)] [timestamp(8B)] [dataLen(4B)] [data(var)] [checksum(32B)]
     * 校验和覆盖范围: magic 到 data 的全部字节。
     */
    private fun writeEntry(
        type: WALEntryType,
        txnId: Long,
        slotId: Int,
        data: ByteArray = ByteArray(0)
    ): Boolean {
        writeLock.withLock {
            try {
                val timestamp = System.currentTimeMillis()

                // 构建条目字节 (不含校验和)
                val baos = ByteArrayOutputStream(ENTRY_HEADER_SIZE + data.size)
                val dos = DataOutputStream(baos)
                dos.writeByte(MAGIC_BYTE_1.toInt())
                dos.writeByte(MAGIC_BYTE_2.toInt())
                dos.writeByte(type.ordinal)
                dos.writeLong(txnId)
                dos.writeInt(slotId)
                dos.writeLong(timestamp)
                dos.writeInt(data.size)
                dos.write(data)
                dos.flush()

                val entryBytes = baos.toByteArray()
                val checksum = sha256(entryBytes)

                // 写入缓冲流
                val bos = bufferedOutputStream ?: run {
                    Log.e(TAG, "BufferedOutputStream is null, cannot write entry")
                    return false
                }
                bos.write(entryBytes)
                bos.write(checksum)

                conditionalFlush(entryBytes.size + CHECKSUM_SIZE)
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write WAL entry: type=$type, txnId=$txnId, slot=$slotId", e)
                return false
            }
        }
    }

    /**
     * 解析 WAL 文件中的所有条目。
     * 跳过校验和无效的条目，从 magic 字节重新同步。
     */
    private fun parseEntries(data: ByteArray): List<ParsedEntry> {
        val entries = mutableListOf<ParsedEntry>()
        var offset = 0

        while (offset + ENTRY_MIN_SIZE <= data.size) {
            try {
                // 查找 magic 字节
                if (data[offset] != MAGIC_BYTE_1 || data[offset + 1] != MAGIC_BYTE_2) {
                    offset++
                    continue
                }

                val headerStart = offset

                // 读取 type
                val typeOrdinal = data[offset + 2].toInt() and 0xFF
                val type = WALEntryType.entries.getOrNull(typeOrdinal)
                if (type == null) {
                    offset += 3
                    continue
                }

                // 读取固定头部字段
                val txnId = readLong(data, offset + 3)
                val slotId = readInt(data, offset + 11)
                val timestamp = readLong(data, offset + 15)
                val dataLength = readInt(data, offset + 23)

                // 验证 dataLength 合理性
                if (dataLength < 0) {
                    offset += 3
                    continue
                }

                val entryEnd = offset + ENTRY_HEADER_SIZE + dataLength + CHECKSUM_SIZE
                if (entryEnd > data.size) {
                    // 不完整条目，停止解析
                    break
                }

                // 提取 data
                val entryData = data.copyOfRange(
                    offset + ENTRY_HEADER_SIZE,
                    offset + ENTRY_HEADER_SIZE + dataLength
                )

                // 验证校验和
                val storedChecksum = data.copyOfRange(
                    offset + ENTRY_HEADER_SIZE + dataLength,
                    entryEnd
                )
                val computedChecksum = sha256(
                    data.copyOfRange(headerStart, offset + ENTRY_HEADER_SIZE + dataLength)
                )
                val valid = storedChecksum.contentEquals(computedChecksum)

                entries.add(ParsedEntry(type, txnId, slotId, timestamp, entryData, valid))

                offset = entryEnd
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing WAL entry at offset $offset", e)
                offset++
            }
        }

        return entries
    }

    private fun sha256(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data)
    }

    /** 从字节数组大端序读取 Long */
    private fun readLong(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFF) shl 56) or
                ((data[offset + 1].toLong() and 0xFF) shl 48) or
                ((data[offset + 2].toLong() and 0xFF) shl 40) or
                ((data[offset + 3].toLong() and 0xFF) shl 32) or
                ((data[offset + 4].toLong() and 0xFF) shl 24) or
                ((data[offset + 5].toLong() and 0xFF) shl 16) or
                ((data[offset + 6].toLong() and 0xFF) shl 8) or
                (data[offset + 7].toLong() and 0xFF)
    }

    /** 从字节数组大端序读取 Int */
    private fun readInt(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
    }

    // ==================== 快照文件管理 ====================

    /**
     * 将快照数据保存到独立文件。
     * 文件命名: slot_{slotId}_txn_{txnId}.snap
     */
    private fun saveSnapshotFile(slot: Int, txnId: Long, data: ByteArray): File? {
        return try {
            if (data.size > StorageConstants.MAX_SNAPSHOT_SIZE_BYTES) {
                Log.w(TAG, "Snapshot too large: ${data.size}B for slot $slot, limit=${StorageConstants.MAX_SNAPSHOT_SIZE_BYTES}")
                return null
            }
            val file = File(snapshotDir, SNAPSHOT_FILE_FORMAT.format(slot, txnId))
            FileOutputStream(file).use { fos ->
                fos.write(data)
                fos.fd.sync()
            }
            Log.d(TAG, "Snapshot saved: ${file.name}, size=${data.size}B")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save snapshot for slot $slot, txnId $txnId", e)
            null
        }
    }

    private fun deleteSnapshotFile(file: File?) {
        if (file == null) return
        try {
            if (file.exists()) {
                file.delete()
                Log.d(TAG, "Snapshot deleted: ${file.name}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete snapshot: ${file.name}", e)
        }
    }

    /**
     * 查找指定 slot 的最新快照文件。
     */
    private fun findLatestSnapshotFile(slot: Int): File? {
        if (!snapshotDir.exists()) return null
        return snapshotDir.listFiles()
            ?.filter { it.name.startsWith("slot_${slot}_txn_") && it.name.endsWith(".snap") }
            ?.maxByOrNull { it.lastModified() }
    }

    /**
     * 根据 txnId 查找对应的快照文件。
     */
    private fun findSnapshotFile(slot: Int, txnId: Long): File? {
        if (!snapshotDir.exists()) return null
        val file = File(snapshotDir, SNAPSHOT_FILE_FORMAT.format(slot, txnId))
        return if (file.exists()) file else null
    }

    /**
     * 清理过期快照文件。
     * 遵循 StorageConstants 中的保留策略。
     */
    private fun cleanupOldSnapshots() {
        try {
            if (!snapshotDir.exists()) return
            val now = System.currentTimeMillis()
            val maxAge = StorageConstants.MAX_SNAPSHOT_AGE_MS
            val minKeep = StorageConstants.MIN_SNAPSHOTS_TO_KEEP

            val snapshots = snapshotDir.listFiles()
                ?.filter { it.name.endsWith(".snap") }
                ?.sortedByDescending { it.lastModified() }
                ?: return

            var kept = 0
            var deleted = 0
            var totalSize = 0L

            for (snap in snapshots) {
                totalSize += snap.length()
                val age = now - snap.lastModified()
                if (kept >= minKeep && (age > maxAge || totalSize > StorageConstants.MAX_TOTAL_SNAPSHOTS_SIZE_BYTES)) {
                    snap.delete()
                    deleted++
                } else {
                    kept++
                }
            }

            if (deleted > 0) {
                Log.d(TAG, "Snapshot cleanup: deleted=$deleted, kept=$kept")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Snapshot cleanup failed", e)
        }
    }

    // ==================== 公开 API ====================

    /**
     * 开始一个新事务。
     *
     * 如果提供了 [snapshotProvider]，会在事务开始前保存快照到磁盘，
     * 以便在崩溃后恢复该 slot 的数据。
     *
     * @param slot 目标存档槽位
     * @param operation 事务操作类型 (通常为 DATA)
     * @param snapshotProvider 快照数据提供者，传入 slot 返回序列化后的字节数组
     * @return 成功时返回事务 ID，失败时返回 WAL_ERROR
     */
    override suspend fun beginTransaction(
        slot: Int,
        operation: WALEntryType,
        snapshotProvider: (suspend (Int) -> ByteArray)?
    ): SaveResult<Long> {
        if (isShutdown.get()) {
            return SaveResult.failure(SaveError.WAL_ERROR, "WAL is shutdown")
        }

        return withContext(Dispatchers.IO) {
            try {
                val txnId = txnIdGenerator.incrementAndGet()

                // 保存快照 (如果提供了 provider)
                var snapshotFile: File? = null
                if (snapshotProvider != null) {
                    try {
                        val snapshotData = snapshotProvider(slot)
                        snapshotFile = saveSnapshotFile(slot, txnId, snapshotData)
                    } catch (e: Exception) {
                        Log.w(TAG, "Snapshot provider failed for slot=$slot, txnId=$txnId", e)
                        // 快照失败不阻止事务开始，但恢复时可能缺少快照
                    }
                }

                // 写入 BEGIN 条目，data 字段存储操作类型名称
                val entryData = operation.name.toByteArray(Charsets.UTF_8)
                if (!writeEntry(WALEntryType.BEGIN, txnId, slot, entryData)) {
                    // 写入失败，清理已保存的快照
                    deleteSnapshotFile(snapshotFile)
                    return@withContext SaveResult.failure(
                        SaveError.WAL_ERROR,
                        "Failed to write BEGIN entry for transaction $txnId"
                    )
                }

                // 注册活跃事务
                val record = TransactionRecord(
                    txnId = txnId,
                    slot = slot,
                    operation = operation,
                    startTime = System.currentTimeMillis(),
                    snapshotFile = snapshotFile
                )
                activeTransactions[txnId] = record
                txnLocks[txnId] = ReentrantLock()
                totalTransactions.incrementAndGet()

                Log.d(TAG, "Transaction begun: txnId=$txnId, slot=$slot, op=$operation, hasSnapshot=${snapshotFile != null}")
                SaveResult.success(txnId)
            } catch (e: Exception) {
                Log.e(TAG, "beginTransaction failed: slot=$slot", e)
                SaveResult.failure(SaveError.WAL_ERROR, "Failed to begin transaction: ${e.message}", e)
            }
        }
    }

    /**
     * 提交事务。
     *
     * 写入 COMMIT 条目并强制 flush，确保数据持久化。
     * 提交后删除关联的快照文件（不再需要恢复）。
     *
     * @param txnId 事务 ID
     * @param checksum 数据校验和 (由调用方提供)
     * @param gameEvent 关联的游戏事件
     * @param currentGameYear 当前游戏年份
     */
    override suspend fun commit(
        txnId: Long,
        checksum: String,
        gameEvent: String?,
        currentGameYear: Int
    ): SaveResult<Unit> {
        if (isShutdown.get()) {
            return SaveResult.failure(SaveError.WAL_ERROR, "WAL is shutdown")
        }

        return withContext(Dispatchers.IO) {
            try {
                val lock = txnLocks[txnId]
                if (lock == null) {
                    return@withContext SaveResult.failure(
                        SaveError.WAL_ERROR,
                        "Transaction $txnId not found or already completed"
                    )
                }
                lock.withLock {
                    val record = activeTransactions[txnId]
                    if (record == null || !record.compareAndSetStatus(TransactionStatus.ACTIVE, TransactionStatus.COMMITTING)) {
                        return@withContext SaveResult.failure(
                            SaveError.WAL_ERROR,
                            "Transaction $txnId not found or already completed"
                        )
                    }

                    val metaData = buildString {
                        append("checksum=").append(checksum)
                        append("|gameEvent=").append(gameEvent ?: "")
                        append("|currentGameYear=").append(currentGameYear)
                    }.toByteArray(Charsets.UTF_8)

                    if (!writeEntry(WALEntryType.COMMIT, txnId, record.slot, metaData)) {
                        record.statusRef.set(TransactionStatus.ACTIVE)
                        return@withContext SaveResult.failure(
                            SaveError.WAL_ERROR,
                            "Failed to write COMMIT entry for transaction $txnId"
                        )
                    }

                    flushInternal()

                    record.statusRef.set(TransactionStatus.COMMITTED)
                    record.checksum = checksum
                    record.gameEvent = gameEvent
                    record.currentGameYear = currentGameYear
                    activeTransactions.remove(txnId)
                    committedCount.incrementAndGet()

                    deleteSnapshotFile(record.snapshotFile)

                    val sinceCp = commitsSinceCheckpoint.incrementAndGet()
                    if (sinceCp >= StorageConstants.CHECKPOINT_INTERVAL) {
                        launchCheckpoint()
                    } else {
                        val fileSize = try { walFile.length() } catch (_: Exception) { 0L }
                        if (fileSize > StorageConstants.MAX_WAL_SIZE_BYTES) {
                            launchCheckpoint()
                        }
                    }

                    Log.d(TAG, "Transaction committed: txnId=$txnId, slot=${record.slot}")
                    SaveResult.success(Unit)
                }.also {
                    txnLocks.remove(txnId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "commit failed: txnId=$txnId", e)
                SaveResult.failure(SaveError.WAL_ERROR, "Failed to commit: ${e.message}", e)
            }
        }
    }

    /**
     * 创建重要快照 (独立于事务生命周期)。
     *
     * 用于关键游戏事件 (境界突破、年份变化等) 的数据保护。
     * 快照数据保存到独立文件，WAL 中记录 SNAPSHOT 条目。
     *
     * @param slot 目标槽位
     * @param snapshotProvider 快照数据提供者
     * @param eventType 事件类型标识
     * @param currentGameYear 当前游戏年份
     * @return 成功时返回快照事务 ID
     */
    override suspend fun createImportantSnapshot(
        slot: Int,
        snapshotProvider: suspend (Int) -> ByteArray,
        eventType: String,
        currentGameYear: Int
    ): SaveResult<Long> {
        if (isShutdown.get()) {
            return SaveResult.failure(SaveError.WAL_ERROR, "WAL is shutdown")
        }

        return withContext(Dispatchers.IO) {
            try {
                val txnId = txnIdGenerator.incrementAndGet()

                // 获取快照数据
                val snapshotData = snapshotProvider(slot)

                // 保存到独立文件
                val snapshotFile = saveSnapshotFile(slot, txnId, snapshotData)

                // 构建 SNAPSHOT 条目数据: 文件引用 + 元数据
                val entryData = buildString {
                    append("snapshotFile=").append(snapshotFile?.name ?: "")
                    append("|eventType=").append(eventType)
                    append("|currentGameYear=").append(currentGameYear)
                }.toByteArray(Charsets.UTF_8)

                // 写入 SNAPSHOT 条目
                if (!writeEntry(WALEntryType.SNAPSHOT, txnId, slot, entryData)) {
                    // 写入失败，清理已保存的快照
                    deleteSnapshotFile(snapshotFile)
                    return@withContext SaveResult.failure(
                        SaveError.WAL_ERROR,
                        "Failed to write SNAPSHOT entry for transaction $txnId"
                    )
                }
                flushInternal()

                totalTransactions.incrementAndGet()

                Log.d(TAG, "Important snapshot created: txnId=$txnId, slot=$slot, event=$eventType, size=${snapshotData.size}B")
                SaveResult.success(txnId)
            } catch (e: Exception) {
                Log.e(TAG, "createImportantSnapshot failed: slot=$slot", e)
                SaveResult.failure(SaveError.WAL_ERROR, "Failed to create snapshot: ${e.message}", e)
            }
        }
    }

    /**
     * 异步中止事务。
     *
     * 写入 ABORT 条目，删除关联快照。
     */
    override suspend fun abort(txnId: Long): SaveResult<Unit> {
        if (isShutdown.get()) {
            return SaveResult.failure(SaveError.WAL_ERROR, "WAL is shutdown")
        }

        return withContext(Dispatchers.IO) {
            performAbort(txnId)
        }
    }

    /**
     * 同步中止事务。
     *
     * 可从非协程上下文调用。
     */
    override fun abortSync(txnId: Long): SaveResult<Unit> {
        if (isShutdown.get()) {
            return SaveResult.failure(SaveError.WAL_ERROR, "WAL is shutdown")
        }
        return performAbort(txnId)
    }

    /**
     * 中止事务的内部实现。
     */
    private fun performAbort(txnId: Long): SaveResult<Unit> {
        val lock = txnLocks[txnId] ?: return run {
            Log.w(TAG, "abort: Transaction $txnId not found in lock map")
            SaveResult.success(Unit)
        }
        return lock.withLock {
            try {
                val record = activeTransactions[txnId]
                if (record == null || !record.compareAndSetStatus(TransactionStatus.ACTIVE, TransactionStatus.ABORTING)) {
                    return@withLock SaveResult.success(Unit)
                }

                if (!writeEntry(WALEntryType.ABORT, txnId, record.slot)) {
                    record.statusRef.set(TransactionStatus.ACTIVE)
                    return@withLock SaveResult.failure(
                        SaveError.WAL_ERROR,
                        "Failed to write ABORT entry for transaction $txnId"
                    )
                }
                flushInternal()

                record.statusRef.set(TransactionStatus.ABORTED)
                activeTransactions.remove(txnId)
                abortedCount.incrementAndGet()

                deleteSnapshotFile(record.snapshotFile)

                Log.d(TAG, "Transaction aborted: txnId=$txnId, slot=${record.slot}")
                SaveResult.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "abort failed: txnId=$txnId", e)
                SaveResult.failure(SaveError.WAL_ERROR, "Failed to abort: ${e.message}", e)
            }
        }.also {
            txnLocks.remove(txnId)
        }
    }

    /**
     * 执行 WAL 恢复。
     *
     * 扫描 WAL 文件，查找未完成的事务 (BEGIN 无对应 COMMIT/ABORT)，
     * 检查是否有可用快照，将未完成事务注册到活跃事务表。
     *
     * @return 恢复结果，包含可恢复和不可恢复的 slot 集合
     */
    override suspend fun recover(): RecoveryResult {
        return withContext(Dispatchers.IO) {
            try {
                val cached = cachedInitEntries
                val entries = if (cached != null) {
                    cachedInitEntries = null
                    cached
                } else {
                    if (!walFile.exists() || walFile.length() == 0L) {
                        Log.i(TAG, "No WAL file to recover")
                        return@withContext RecoveryResult(true, emptySet(), emptySet(), emptyList())
                    }
                    val walData = walFile.readBytes()
                    parseEntries(walData)
                }

                if (entries.isEmpty()) {
                    return@withContext RecoveryResult(true, emptySet(), emptySet(), emptyList())
                }

                // 追踪事务生命周期
                val beginEntries = mutableMapOf<Long, RecoveryTxnInfo>()
                val completedTxnIds = mutableSetOf<Long>()

                for (entry in entries) {
                    if (!entry.valid) continue
                    when (entry.type) {
                        WALEntryType.BEGIN -> {
                            val operation = try {
                                WALEntryType.valueOf(String(entry.data, Charsets.UTF_8))
                            } catch (_: Exception) { null }
                            beginEntries[entry.txnId] = RecoveryTxnInfo(
                                slotId = entry.slotId,
                                operation = operation,
                                timestamp = entry.timestamp
                            )
                        }
                        WALEntryType.COMMIT -> completedTxnIds.add(entry.txnId)
                        WALEntryType.ABORT -> completedTxnIds.add(entry.txnId)
                        WALEntryType.SNAPSHOT -> { /* 独立快照，不影响事务状态 */ }
                        WALEntryType.DATA -> { /* DATA 条目不影响事务完成状态 */ }
                    }
                }

                // 查找未完成事务
                val uncommitted = beginEntries.filter { (txnId, _) -> txnId !in completedTxnIds }

                if (uncommitted.isEmpty()) {
                    Log.i(TAG, "No uncommitted transactions found")
                    // 仍需更新 txnIdGenerator
                    updateTxnIdFromEntries(entries)
                    return@withContext RecoveryResult(true, emptySet(), emptySet(), emptyList())
                }

                val recoveredSlots = mutableSetOf<Int>()
                val failedSlots = mutableSetOf<Int>()
                val errors = mutableListOf<String>()

                for ((txnId, info) in uncommitted) {
                    val slot = info.slotId
                    val snapshotFile = findSnapshotFile(slot, txnId)
                    val hasSnapshot = snapshotFile != null && snapshotFile.exists()

                    // 将未完成事务注册到活跃事务表
                    val record = TransactionRecord(
                        txnId = txnId,
                        slot = slot,
                        operation = info.operation ?: WALEntryType.DATA,
                        startTime = info.timestamp,
                        snapshotFile = snapshotFile
                    )
                    activeTransactions[txnId] = record

                    if (hasSnapshot) {
                        recoveredSlots.add(slot)
                        Log.i(TAG, "Recoverable transaction: txnId=$txnId, slot=$slot (snapshot available)")
                    } else {
                        failedSlots.add(slot)
                        errors.add("No snapshot for uncommitted transaction $txnId on slot $slot")
                        Log.w(TAG, "Unrecoverable transaction: txnId=$txnId, slot=$slot (no snapshot)")
                    }
                }

                // 更新 txnIdGenerator 避免冲突
                updateTxnIdFromEntries(entries)

                val success = failedSlots.isEmpty()
                Log.i(TAG, "Recovery completed: recovered=${recoveredSlots.size}, failed=${failedSlots.size}, errors=${errors.size}")
                RecoveryResult(success, recoveredSlots, failedSlots, errors)
            } catch (e: Exception) {
                Log.e(TAG, "Recovery failed", e)
                RecoveryResult(false, emptySet(), emptySet(), listOf("Recovery failed: ${e.message}"))
            }
        }
    }

    /**
     * 从解析的条目中更新 txnIdGenerator，确保后续生成不冲突。
     */
    private fun updateTxnIdFromEntries(entries: List<ParsedEntry>) {
        val maxTxnId = entries.maxOfOrNull { it.txnId } ?: 0L
        txnIdGenerator.updateAndGet { current -> maxOf(current, maxTxnId + 1) }
    }

    /**
     * 从快照恢复指定 slot 的数据。
     *
     * 查找该 slot 的最新快照文件，读取数据后传递给 [dataConsumer]。
     *
     * @param slot 目标槽位
     * @param dataConsumer 数据消费者，返回 true 表示接受，false 表示拒绝
     * @return 恢复结果
     */
    override suspend fun restoreFromSnapshot(
        slot: Int,
        dataConsumer: suspend (ByteArray) -> Boolean
    ): SaveResult<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val snapshotFile = findLatestSnapshotFile(slot)
                if (snapshotFile == null || !snapshotFile.exists()) {
                    return@withContext SaveResult.failure(
                        SaveError.SLOT_CORRUPTED,
                        "No snapshot found for slot $slot"
                    )
                }

                val data = snapshotFile.readBytes()
                if (data.isEmpty()) {
                    return@withContext SaveResult.failure(
                        SaveError.SLOT_CORRUPTED,
                        "Snapshot file is empty for slot $slot"
                    )
                }

                val accepted = dataConsumer(data)

                if (accepted) {
                    Log.i(TAG, "Snapshot restored: slot=$slot, file=${snapshotFile.name}, size=${data.size}B")
                    SaveResult.success(Unit)
                } else {
                    Log.w(TAG, "Snapshot data rejected by consumer: slot=$slot")
                    SaveResult.failure(
                        SaveError.RESTORE_FAILED,
                        "Data consumer rejected snapshot for slot $slot"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "restoreFromSnapshot failed: slot=$slot", e)
                SaveResult.failure(SaveError.RESTORE_FAILED, "Failed to restore snapshot: ${e.message}", e)
            }
        }
    }

    /**
     * 执行 checkpoint 操作，压缩 WAL 文件。
     *
     * 移除已完成事务 (COMMITTED/ABORTED) 的条目，
     * 保留活跃事务条目和有效快照引用。
     *
     * @return true 表示成功
     */
    override suspend fun checkpoint(): Boolean {
        return withContext(Dispatchers.IO) {
            launchCheckpoint()
        }
    }

    /**
     * 内部 checkpoint 实现。
     *
     * 流程:
     * 1. flush 并关闭当前输出流
     * 2. 读取并解析 WAL 文件
     * 3. 过滤: 保留活跃事务条目 + 有效快照条目
     * 4. 写入临时文件
     * 5. 原子替换 WAL 文件
     * 6. 重新打开输出流
     */
    private fun launchCheckpoint(): Boolean {
        return writeLock.withLock {
            try {
                // 步骤 1: flush 并关闭
                try {
                    bufferedOutputStream?.flush()
                    bufferedOutputStream?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error flushing before checkpoint", e)
                }
                bufferedOutputStream = null

                // 步骤 2: 读取 WAL
                if (!walFile.exists() || walFile.length() == 0L) {
                    openWALFileInternal()
                    lastCheckpointTime = System.currentTimeMillis()
                    commitsSinceCheckpoint.set(0L)
                    return true
                }

                val walData = try {
                    walFile.readBytes()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read WAL file for checkpoint", e)
                    openWALFileInternal()
                    return false
                }

                val entries = parseEntries(walData)

                // 步骤 3: 过滤
                val activeTxnIds = activeTransactions.keys
                val filteredEntries = entries.filter { entry ->
                    if (!entry.valid) return@filter false

                    // 保留活跃事务的所有条目
                    if (entry.txnId in activeTxnIds) return@filter true

                    // 保留快照条目 (仅当快照文件仍存在时)
                    if (entry.type == WALEntryType.SNAPSHOT) {
                        val metaData = String(entry.data, Charsets.UTF_8)
                        val fileName = metaData.split("|")
                            .find { it.startsWith("snapshotFile=") }
                            ?.removePrefix("snapshotFile=")
                            ?.trim()
                        if (!fileName.isNullOrEmpty()) {
                            return@filter File(snapshotDir, fileName).exists()
                        }
                        return@filter false
                    }

                    // 移除已完成事务的条目
                    false
                }

                // 步骤 4: 写入临时文件
                val tempFile = File(walDir, "${StorageConstants.WAL_FILE_NAME}.cp")
                val tempBos = BufferedOutputStream(
                    FileOutputStream(tempFile),
                    StorageConstants.WAL_BUFFER_SIZE_BYTES
                )

                for (entry in filteredEntries) {
                    val baos = ByteArrayOutputStream(ENTRY_HEADER_SIZE + entry.data.size)
                    val dos = DataOutputStream(baos)
                    dos.writeByte(MAGIC_BYTE_1.toInt())
                    dos.writeByte(MAGIC_BYTE_2.toInt())
                    dos.writeByte(entry.type.ordinal)
                    dos.writeLong(entry.txnId)
                    dos.writeInt(entry.slotId)
                    dos.writeLong(entry.timestamp)
                    dos.writeInt(entry.data.size)
                    dos.write(entry.data)
                    dos.flush()

                    val entryBytes = baos.toByteArray()
                    val checksum = sha256(entryBytes)
                    tempBos.write(entryBytes)
                    tempBos.write(checksum)
                }

                tempBos.flush()
                tempBos.close()

                // 步骤 5: 原子替换
                val oldFile = File(walDir, "${StorageConstants.WAL_FILE_NAME}.old")
                if (oldFile.exists()) oldFile.delete()

                if (walFile.exists()) {
                    walFile.renameTo(oldFile)
                }

                if (!tempFile.renameTo(walFile)) {
                    // 替换失败，尝试回滚
                    Log.e(TAG, "Failed to rename temp WAL file, attempting rollback")
                    if (oldFile.exists()) {
                        oldFile.renameTo(walFile)
                    }
                    openWALFileInternal()
                    return false
                }

                // 清理旧文件
                if (oldFile.exists()) {
                    oldFile.delete()
                }

                // 步骤 6: 重新打开输出流
                openWALFileInternal()

                // 更新统计
                lastCheckpointTime = System.currentTimeMillis()
                commitsSinceCheckpoint.set(0L)

                // 清理过期快照
                cleanupOldSnapshots()

                Log.i(TAG, "Checkpoint completed: kept=${filteredEntries.size} entries, size=${walFile.length()}B")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Checkpoint failed", e)
                try {
                    openWALFileInternal()
                } catch (reopenEx: Exception) {
                    Log.e(TAG, "Failed to reopen WAL after checkpoint failure", reopenEx)
                }
                false
            }
        }
    }

    /**
     * 在 writeLock 内打开 WAL 文件 (不重新获取锁)。
     * 须在 writeLock.withLock 块内调用。
     */
    private fun openWALFileInternal() {
        try {
            if (!walFile.exists()) {
                walFile.parentFile?.mkdirs()
                walFile.createNewFile()
            }
            bufferedOutputStream = BufferedOutputStream(
                FileOutputStream(walFile, true),
                StorageConstants.WAL_BUFFER_SIZE_BYTES
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open WAL file internally", e)
        }
    }

    // ==================== 查询方法 ====================

    override fun hasActiveTransactions(): Boolean = !activeTransactions.isEmpty()

    override fun getActiveTransactionCount(): Int = activeTransactions.size

    override fun getActiveTransactionIds(): Set<Long> = activeTransactions.keys.toSet()

    override fun getTransactionRecord(txnId: Long): TransactionRecord? = activeTransactions[txnId]

    override fun getStats(): EnhancedWALStats {
        val fileSize = try {
            if (walFile.exists()) walFile.length() else 0L
        } catch (_: Exception) { 0L }

        return EnhancedWALStats(
            activeTransactions = activeTransactions.size,
            totalTransactions = totalTransactions.get(),
            committedCount = committedCount.get(),
            abortedCount = abortedCount.get(),
            walFileSize = fileSize,
            lastCheckpointTime = lastCheckpointTime
        )
    }

    // ==================== 生命周期管理 ====================

    /**
     * 清空所有 WAL 数据。
     *
     * 删除 WAL 文件和所有快照，重置内存状态，重新初始化输出流。
     */
    override fun clear() {
        writeLock.withLock {
            try {
                closeStreamsInternal()

                // 删除 WAL 文件
                if (walFile.exists()) {
                    walFile.delete()
                }

                // 删除快照
                if (snapshotDir.exists()) {
                    snapshotDir.listFiles()?.forEach { file ->
                        if (file.name.endsWith(".snap") || file.name == "snapshot_registry.json") {
                            file.delete()
                        }
                    }
                }

                // 重置状态
                activeTransactions.clear()
                txnLocks.clear()
                totalTransactions.set(0L)
                committedCount.set(0L)
                abortedCount.set(0L)
                commitsSinceCheckpoint.set(0L)
                lastCheckpointTime = 0L
                pendingBytes.set(0L)

                // 重新初始化
                openWALFileInternal()

                Log.i(TAG, "WAL cleared and reinitialized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear WAL", e)
            }
        }
    }

    /**
     * 关闭 WAL，释放所有资源。
     *
     * 关闭后所有操作将返回 WAL_ERROR。
     */
    override fun shutdown() {
        if (isShutdown.getAndSet(true)) return

        try {
            flushJob?.cancel()
            flushJob = null

            writeLock.withLock {
                closeStreamsInternal()
            }

            Log.i(TAG, "FunctionalWAL shutdown complete")
        } catch (e: Exception) {
            Log.e(TAG, "Error during shutdown", e)
        }
    }
}
