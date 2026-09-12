package com.xianxia.sect.data.wal

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.StorageConstants
import com.xianxia.sect.data.unified.SaveError
import com.xianxia.sect.data.unified.SaveResult
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.perf.ThermalStatusProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
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
  *1. 二进制 WAL 条目格式：[magic(2B)] [type(1B)] [txnId(8B)] [slotId(4B)] [timestamp(8B)] [dataLen(4B)] [data(var)]
  * [checksum(32B SHA-256)]
 * 2. 快照数据存储在独立文件中，WAL 条目仅保存引用元数据
 * 3. 使用 ReentrantLock 保证写入原子性，ConcurrentHashMap 管理活跃事务
 * 4. 周期性 flush + 累积字节阈值强制 flush 双重保障
 * 5. Checkpoint 压缩 WAL 文件，移除已完成事务的条目
 */
@Singleton
class FunctionalWAL @Inject constructor(
    @ApplicationContext private val context: Context,
    internal val scopeProvider: CoroutineScopeProvider,
    internal val thermalMonitor: ThermalStatusProvider
) : WALProvider {
    companion object {
        internal const val TAG = "FunctionalWAL"

        /** WAL 条目 magic 字节: 0x57='W', 0x34='4' (即 0xW4) */
        internal const val MAGIC_BYTE_1: Byte = 0x57
        internal const val MAGIC_BYTE_2: Byte = 0x34

        /** 条目固定头部大小: magic(2) + type(1) + txnId(8) + slotId(4) + timestamp(8) + dataLength(4) = 27 */
        internal const val ENTRY_HEADER_SIZE = 27

        /** SHA-256 校验和长度 */
        internal const val CHECKSUM_SIZE = 32

        /** 最小完整条目大小 (头部 + 校验和，无 data) */
        internal const val ENTRY_MIN_SIZE = ENTRY_HEADER_SIZE + CHECKSUM_SIZE

        /** 恢复注册的未完成事务保留期（30 天）——超期条目不再登记，
         *  防止 WAL 文件随崩溃次数单调增长 */
        internal const val RECOVERED_TXN_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }

    /** 解析后的 WAL 条目 */

    // ==================== 状态 ====================

    internal val activeTransactions = ConcurrentHashMap<Long, TransactionRecord>()
    internal val txnLocks = ConcurrentHashMap<Long, ReentrantLock>()
    internal var cachedInitEntries: List<ParsedEntry>? = null
    internal val txnIdGenerator = AtomicLong(System.currentTimeMillis())
    internal val writeLock = ReentrantLock()
    internal val pendingBytes = AtomicLong(0L)
    internal val isShutdown = AtomicBoolean(false)

    // 统计计数器
    internal val totalTransactions = AtomicLong(0L)
    internal val committedCount = AtomicLong(0L)
    internal val abortedCount = AtomicLong(0L)
    internal val commitsSinceCheckpoint = AtomicLong(0L)
    internal var lastCheckpointTime: Long = 0L

    // 文件路径
    internal val walDir: File = File(context.filesDir, StorageConstants.WAL_DIR_NAME)
    internal val walFile: File = File(walDir, StorageConstants.WAL_FILE_NAME)

    // 输出流 (所有访问在 writeLock 内)
    internal var bufferedOutputStream: BufferedOutputStream? = null

    // 协程作用域 (flush 定时器)
    internal val scope get() = scopeProvider.ioScope
    internal var flushJob: Job? = null

    // ==================== 初始化 ====================

    init {
        // 防御兜底: WAL 初始化失败不阻断进程启动(后续写路径自会暴露), 异常类型不可枚举
        @Suppress("TooGenericExceptionCaught")
        try {
            walDir.mkdirs()
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
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
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





    // ==================== 流管理 ====================







    // ==================== 二进制格式读写 ====================











    // ==================== 公开 API ====================

    /**
     * 开始一个新事务。
     *
     * 仅作日志记账（BEGIN 条目 + 活跃事务注册）；崩溃恢复职责由
     * Room WAL 事务原子性与 .sav 备份承担。
     *
     * @param slot 目标存档槽位
     * @param operation 事务操作类型 (通常为 DATA)
     * @return 成功时返回事务 ID，失败时返回 WAL_ERROR
     */
    @Suppress("TooGenericExceptionCaught") // 异常显式包装进 Result 上抛, 非静默吞噬
    override suspend fun beginTransaction(
        slot: Int,
        operation: WALEntryType
    ): SaveResult<Long> {
        if (isShutdown.get()) {
            return SaveResult.failure(SaveError.WAL_ERROR, "WAL is shutdown")
        }

        return withContext(Dispatchers.IO) {
            try {
                val txnId = txnIdGenerator.incrementAndGet()

                // 写入 BEGIN 条目，data 字段存储操作类型名称
                val entryData = operation.name.toByteArray(Charsets.UTF_8)
                if (!writeEntry(WALEntryType.BEGIN, txnId, slot, entryData)) {
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
                    startTime = System.currentTimeMillis()
                )
                activeTransactions[txnId] = record
                txnLocks[txnId] = ReentrantLock()
                totalTransactions.incrementAndGet()

                Log.d(TAG, "Transaction begun: txnId=$txnId, slot=$slot, op=$operation")
                SaveResult.success(txnId)
            } catch (e: CancellationException) {
                throw e // 取消穿透: 取消时上抛, 不以 WAL_ERROR 冒充事务记账失败
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
    @Suppress("TooGenericExceptionCaught") // 异常显式包装进 Result 上抛, 非静默吞噬
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
                    if (record == null || !record.compareAndSetStatus(TransactionStatus.ACTIVE,
                        TransactionStatus.COMMITTING)) {
                        return@withContext SaveResult.failure(
                            SaveError.WAL_ERROR,
                            "Transaction $txnId not found or already completed"
                        )
                    }
                    performCommit(
                        txnId = txnId,
                        checksum = checksum,
                        gameEvent = gameEvent,
                        currentGameYear = currentGameYear,
                        record = record
                    )
                }.also {
                    txnLocks.remove(txnId)
                }
            } catch (e: CancellationException) {
                throw e // 取消穿透: 取消时上抛(事务状态留在 COMMITTING 由 recover 兜底), 不以 WAL_ERROR 冒充
            } catch (e: Exception) {
                Log.e(TAG, "commit failed: txnId=$txnId", e)
                SaveResult.failure(SaveError.WAL_ERROR, "Failed to commit: ${e.message}", e)
            }
        }
    }



    /**
     * 异步中止事务。
     *
     * 写入 ABORT 条目并解除活跃事务注册。
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
     * 执行 WAL 恢复。
     *
     * 扫描 WAL 文件，查找未完成的事务 (BEGIN 无对应 COMMIT/ABORT)，
     * 检查是否有可用快照，将未完成事务注册到活跃事务表。
     *
     * @return 恢复结果，包含可恢复和不可恢复的 slot 集合
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    override suspend fun recover(): RecoveryResult {
        return withContext(Dispatchers.IO) {
            try {
                val entries = readEntriesForRecovery()
                    ?: return@withContext RecoveryResult(true, emptySet(), emptySet(), emptyList())

                if (entries.isEmpty()) {
                    return@withContext RecoveryResult(true, emptySet(), emptySet(), emptyList())
                }

                // 追踪事务生命周期
                val (beginEntries, completedTxnIds) = trackTransactionLifecycle(entries = entries)

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

                registerUncommittedTransactions(
                    uncommitted = uncommitted,
                    failedSlots = failedSlots,
                    errors = errors
                )

                // 更新 txnIdGenerator 避免冲突
                updateTxnIdFromEntries(entries)

                val success = failedSlots.isEmpty()
                Log.i(TAG, "Recovery completed: recovered=${recoveredSlots.size}, failed=${failedSlots.size}, " +
                    "errors=${errors.size}")
                RecoveryResult(success, recoveredSlots, failedSlots, errors)
            } catch (e: CancellationException) {
                throw e // 取消穿透: 取消时上抛, 不以"恢复失败"冒充(避免误触发恢复失败处置)
            } catch (e: Exception) {
                Log.e(TAG, "Recovery failed", e)
                RecoveryResult(false, emptySet(), emptySet(), listOf("Recovery failed: ${e.message}"))
            }
        }
    }









    /**
     * 执行 checkpoint 操作，压缩 WAL 文件。
     *
     * 移除已完成事务 (COMMITTED/ABORTED) 的条目，保留活跃事务条目。
     *
     * @return true 表示成功
     */
    override suspend fun checkpoint(): Boolean {
        return withContext(Dispatchers.IO) {
            launchCheckpoint()
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
     * 删除 WAL 文件，重置内存状态，重新初始化输出流。
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    override fun clear() {
        writeLock.withLock {
            try {
                closeStreamsInternal()

                // 删除 WAL 文件
                if (walFile.exists()) {
                    walFile.delete()
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
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
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









/**
 * 从已有 WAL 文件中恢复 txnId 计数器，避免 ID 冲突。
 */
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal fun FunctionalWAL.recoverTxnIdCounter() {
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
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal fun FunctionalWAL.startFlushTimer() {
    flushJob?.cancel()
    flushJob = scope.launch {
        while (isActive && !isShutdown.get()) {
            val interval = when {
                thermalMonitor.shouldEmergencySave() -> 5000L
                thermalMonitor.shouldReduceWorkload() -> 3000L
                else -> StorageConstants.WAL_FLUSH_INTERVAL_MS
            }
            delay(interval)
            try {
                flushInternal()
            } catch (e: CancellationException) {
                throw e // 取消穿透: flush 定时器停止时静默退出, 不误报周期 flush 失败
            } catch (e: Exception) {
                Log.e(TAG, "Periodic flush failed", e)
            }
        }
    }
}

/**
 * 写入后检查累积字节数，超过阈值则强制 flush。
 */
internal fun FunctionalWAL.conditionalFlush(bytesWritten: Int) {
    val pending = pendingBytes.addAndGet(bytesWritten.toLong())
    if (pending >= StorageConstants.WAL_MAX_PENDING_BYTES) {
        flushInternal()
    }
}

@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal fun FunctionalWAL.closeStreamsInternal() {
    try {
        bufferedOutputStream?.flush()
        bufferedOutputStream?.close()
    } catch (e: Exception) {
        Log.w(TAG, "Error closing output stream", e)
    }
    bufferedOutputStream = null
}

/**
 * 提交事务的内部实现：在事务锁内写入 COMMIT 条目并强制 flush，
 * 登记提交状态并触发 checkpoint 判定。
 */
internal fun FunctionalWAL.performCommit(
    txnId: Long,
    checksum: String,
    gameEvent: String?,
    currentGameYear: Int,
    record: TransactionRecord
): SaveResult<Unit> {
    val metaData = buildString {
        append("checksum=").append(checksum)
        append("|gameEvent=").append(gameEvent ?: "")
        append("|currentGameYear=").append(currentGameYear)
    }.toByteArray(Charsets.UTF_8)

    if (!writeEntry(WALEntryType.COMMIT, txnId, record.slot, metaData)) {
        record.statusRef.set(TransactionStatus.ACTIVE)
        return SaveResult.failure(
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

    val sinceCp = commitsSinceCheckpoint.incrementAndGet()
    if (sinceCp >= StorageConstants.CHECKPOINT_INTERVAL) {
        if (!thermalMonitor.shouldReduceWorkload()) {
            launchCheckpoint()
        }
    } else {
        val fileSize = try { walFile.length() } catch (_: Exception) { 0L }
        if (fileSize > StorageConstants.MAX_WAL_SIZE_BYTES) {
            launchCheckpoint()
        }
    }

    Log.d(TAG, "Transaction committed: txnId=$txnId, slot=${record.slot}")
    return SaveResult.success(Unit)
}

/**
 * 中止事务的内部实现。
 */
@Suppress("TooGenericExceptionCaught") // 异常显式包装进 Result 上抛, 非静默吞噬
internal fun FunctionalWAL.performAbort(txnId: Long): SaveResult<Unit> {
    val lock = txnLocks[txnId] ?: return run {
        Log.w(TAG, "abort: Transaction $txnId not found in lock map")
        SaveResult.success(Unit)
    }
    return lock.withLock {
        try {
            val record = activeTransactions[txnId]
            if (record == null || !record.compareAndSetStatus(TransactionStatus.ACTIVE,
                TransactionStatus.ABORTING)) {
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
 * 读取 WAL 条目：优先使用 init 缓存，否则读文件解析；
 * 文件不存在或为空时返回 null。
 */
internal fun FunctionalWAL.readEntriesForRecovery(): List<ParsedEntry>? {
    val cached = cachedInitEntries
    return if (cached != null) {
        cachedInitEntries = null
        cached
    } else {
        if (!walFile.exists() || walFile.length() == 0L) {
            Log.i(TAG, "No WAL file to recover")
            null
        } else {
            parseEntries(walFile.readBytes())
        }
    }
}

/**
 * 追踪事务生命周期：汇总 BEGIN 条目与已完成事务 ID 集合。
 *
 * @return (BEGIN 条目表, 已完成事务 ID 集合)
 */
internal fun FunctionalWAL.trackTransactionLifecycle(
    entries: List<ParsedEntry>
): Pair<Map<Long, RecoveryTxnInfo>, Set<Long>> {
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

    return beginEntries to completedTxnIds
}

/**
 * 注册未完成事务：超保留期的未完成事务跳过，其余登记活跃事务表。
 */
internal fun FunctionalWAL.registerUncommittedTransactions(
    uncommitted: Map<Long, RecoveryTxnInfo>,
    failedSlots: MutableSet<Int>,
    errors: MutableList<String>
) {
    for ((txnId, info) in uncommitted) {
        val slot = info.slotId

        // 超保留期的未完成事务不注册——崩溃遗留的 BEGIN 条目若无任何代码
        // abort/commit，checkpoint 会保留其条目，WAL 文件随崩溃次数单调增长
        if (info.timestamp > 0L &&
            System.currentTimeMillis() - info.timestamp > FunctionalWAL.RECOVERED_TXN_RETENTION_MS
        ) {
            Log.w(TAG, "跳过超保留期未完成事务 $txnId (slot=$slot, " +
                "age=${(System.currentTimeMillis() - info.timestamp) / 86_400_000L} 天)")
            continue
        }

        // 将未完成事务注册到活跃事务表——恢复依赖 Room WAL 事务原子性
        // + .sav 备份，未完成事务仅登记供监控
        val record = TransactionRecord(
            txnId = txnId,
            slot = slot,
            operation = info.operation ?: WALEntryType.DATA,
            startTime = info.timestamp
        )
        activeTransactions[txnId] = record

        failedSlots.add(slot)
        errors.add("Uncommitted transaction $txnId on slot $slot (DB 事务已回滚，无需快照恢复)")
        Log.w(TAG, "Uncommitted transaction: txnId=$txnId, slot=$slot")
    }
}

/**
 * 从解析的条目中更新 txnIdGenerator，确保后续生成不冲突。
 */
internal fun FunctionalWAL.updateTxnIdFromEntries(entries: List<ParsedEntry>) {
    val maxTxnId = entries.maxOfOrNull { it.txnId } ?: 0L
    txnIdGenerator.updateAndGet { current -> maxOf(current, maxTxnId + 1) }
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
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal fun FunctionalWAL.launchCheckpoint(): Boolean {
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

                // 保留活跃事务的所有条目；移除已完成事务的条目
                entry.txnId in activeTxnIds
            }

            // 步骤 4: 写入临时文件
            val tempFile = File(walDir, "${StorageConstants.WAL_FILE_NAME}.cp")
            val tempBos = BufferedOutputStream(
                FileOutputStream(tempFile),
                StorageConstants.WAL_BUFFER_SIZE_BYTES
            )
            writeCheckpointEntries(tempBos = tempBos, entries = filteredEntries)

            // 步骤 5+6: 原子替换 + 重新打开 + 更新统计
            replaceWalFile(tempFile = tempFile, keptEntries = filteredEntries.size)
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
 * 写入过滤后的条目到临时文件：逐条重建二进制条目格式。
 */
internal fun FunctionalWAL.writeCheckpointEntries(tempBos: BufferedOutputStream, entries: List<ParsedEntry>) {
    for (entry in entries) {
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
}

/**
 * 原子替换 WAL 文件：旧文件回退 + 清理 + 重新打开 + 统计更新。
 *
 * @return true 表示替换成功；替换失败已回滚时返回 false
 */
internal fun FunctionalWAL.replaceWalFile(tempFile: File, keptEntries: Int): Boolean {
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

    Log.i(TAG, "Checkpoint completed: kept=$keptEntries entries, size=${walFile.length()}B")
    return true
}

/**
 * 在 writeLock 内打开 WAL 文件 (不重新获取锁)。
 * 须在 writeLock.withLock 块内调用。
 */
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal fun FunctionalWAL.openWALFileInternal() {
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

private val TAG = FunctionalWAL.TAG
private const val MAGIC_BYTE_1 = FunctionalWAL.MAGIC_BYTE_1
private const val MAGIC_BYTE_2 = FunctionalWAL.MAGIC_BYTE_2
private const val ENTRY_HEADER_SIZE = FunctionalWAL.ENTRY_HEADER_SIZE
private const val CHECKSUM_SIZE = FunctionalWAL.CHECKSUM_SIZE
private const val ENTRY_MIN_SIZE = FunctionalWAL.ENTRY_MIN_SIZE




