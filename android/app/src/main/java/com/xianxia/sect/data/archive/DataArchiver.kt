@file:Suppress("DEPRECATION")

package com.xianxia.sect.data.archive

import android.content.Context
import android.util.Log
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.GameEvent
import com.xianxia.sect.data.compression.CompressionAlgorithm
import com.xianxia.sect.data.compression.DataCompressor
import com.xianxia.sect.data.serialization.NullSafeProtoBuf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

// ==================== 归档数据模型 ====================

@Serializable
data class ArchiveEntry(
    val id: String,
    val archivedAt: Long = System.currentTimeMillis(),
    val dataType: ArchivedDataType,
    val timestamp: Long,
    val year: Int,
    val month: Int,
    val dataChecksum: String,
    val compressedSize: Long,
    val originalSize: Int,
    val entryCount: Int,
    val earliestTimestamp: Long,
    val latestTimestamp: Long,
    val compressionAlgorithm: String = CompressionAlgorithm.LZ4.name
)

@Serializable
data class ArchiveIndex(
    val version: Int = DataArchiver.ARCHIVE_INDEX_VERSION,
    val lastUpdated: Long = System.currentTimeMillis(),
    val entries: List<ArchiveIndexEntry> = emptyList()
)

@Serializable
data class ArchiveIndexEntry(
    val fileName: String,
    val dataType: ArchivedDataType,
    val yearMonth: String,
    val entryCount: Int,
    val earliestTimestamp: Long,
    val latestTimestamp: Long,
    val fileSize: Long,
    val createdAt: Long
)

data class ArchiveStats(
    val totalArchiveFiles: Int = 0,
    val totalArchivedEntries: Long = 0L,
    val totalBattleLogsArchived: Long = 0L,
    val totalGameEventsArchived: Long = 0L,
    val totalDiskUsageBytes: Long = 0L,
    val compressionRatio: Double = 0.0,
    val oldestArchiveTimestamp: Long = 0L,
    val newestArchiveTimestamp: Long = 0L,
    val archiveFileCountByType: Map<ArchivedDataType, Int> = emptyMap(),
    val monthlyDistribution: Map<String, Int> = emptyMap()
)

data class ArchiveResult(
    val success: Boolean,
    val archivedCount: Int = 0,
    val remainingCount: Int = 0,
    val archiveFilePath: String? = null,
    val error: String? = null
)

data class QueryResult<T>(
    val entries: List<T>,
    val totalCount: Int,
    val queriedFromFiles: List<String>
)

enum class ArchivedDataType {
    BATTLE_LOG,
    GAME_EVENT;

    val filePrefix: String get() = when (this) {
        BATTLE_LOG -> "battle_log"
        GAME_EVENT -> "game_event"
    }

    companion object {
        fun fromPrefix(prefix: String): ArchivedDataType? =
            entries.find { it.filePrefix == prefix }
    }
}

// ==================== 归档批次数据（内部序列化用） ====================

@Serializable
data class ArchivedBatch(
    val version: Int = DataArchiver.ARCHIVE_DATA_VERSION,
    val createdAt: Long = System.currentTimeMillis(),
    val dataType: ArchivedDataType,
    val entries: List<ArchivedItem>
)

@Serializable
data class ArchivedItem(
    val originalData: ByteArray,
    val timestamp: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ArchivedItem
        return timestamp == other.timestamp && originalData.contentEquals(other.originalData)
    }

    override fun hashCode(): Int {
        var result = timestamp.hashCode()
        result = 31 * result + originalData.contentHashCode()
        return result
    }
}

// ==================== DataArchiver 主类 ====================

@Singleton
class DataArchiver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataCompressor: DataCompressor
) {
    companion object {
        private const val TAG = "DataArchiver"
        const val ARCHIVE_INDEX_VERSION = 1
        const val ARCHIVE_DATA_VERSION = 1

        // 默认配置（可被 StorageConfig 覆盖）
        const val DEFAULT_MAX_BATTLE_LOGS_IN_MEMORY = 500
        const val DEFAULT_MAX_GAME_EVENTS_IN_MEMORY = 1000
        const val DEFAULT_RETENTION_MONTHS = 12
        const val DEFAULT_ARCHIVE_DIR_NAME = "archives"

        // 使用 ThreadLocal 保证 SimpleDateFormat 线程安全
        private val DATE_FORMAT: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM", Locale.CHINA)
        }
        private val TIMESTAMP_FORMAT: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
            SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.CHINA)
        }
    }

    private val archiveBaseDir: File by lazy {
        File(context.filesDir, DEFAULT_ARCHIVE_DIR_NAME).apply { mkdirs() }
    }

    private val indexMutex = Mutex()

    /**
     * 统一的 ProtoBuf 实例（来自 NullSafeProtoBuf 工具类）
     *
     * 配置：encodeDefaults = true
     * 确保所有字段都被序列化，即使值为默认值
     */
    private val protoBuf = NullSafeProtoBuf.protoBuf

    // ==================== 公开 API：归档入口 ====================

    /**
     * 检查并归档超限的 BattleLog。
     *
     * @param battleLogs 当前全量 BattleLog 列表
     * @param maxInMemory 主存档中保留的最大条数（默认 500）
     * @return ArchiveResult 包含归档结果和剩余列表信息
     */
    suspend fun archiveBattleLogsIfNeeded(
        battleLogs: List<BattleLog>,
        maxInMemory: Int = DEFAULT_MAX_BATTLE_LOGS_IN_MEMORY
    ): ArchiveResult {
        if (battleLogs.size <= maxInMemory) {
            return ArchiveResult(success = true, archivedCount = 0, remainingCount = battleLogs.size)
        }

        val sorted = battleLogs.sortedBy { it.timestamp }
        val toArchive = sorted.take(battleLogs.size - maxInMemory)
        val toKeep = sorted.drop(toArchive.size)

        return try {
            val batchId = generateBatchId(ArchivedDataType.BATTLE_LOG)
            val archivedFile = writeArchiveFile(
                dataType = ArchivedDataType.BATTLE_LOG,
                batchId = batchId,
                items = toArchive.map { log ->
                    ArchivedItem(
                        originalData = serializeBattleLog(log),
                        timestamp = log.timestamp
                    )
                },
                timestamps = toArchive.map { it.timestamp }
            )

            updateIndex(archivedFile, ArchivedDataType.BATTLE_LOG, toArchive)

            Log.i(TAG, "Archived ${toArchive.size} battle logs to $archivedFile")

            ArchiveResult(
                success = true,
                archivedCount = toArchive.size,
                remainingCount = toKeep.size,
                archiveFilePath = archivedFile.absolutePath
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to archive battle logs", e)
            ArchiveResult(success = false, error = e.message)
        }
    }

    /**
     * 检查并归档超限的 GameEvent。
     */
    suspend fun archiveGameEventsIfNeeded(
        events: List<GameEvent>,
        maxInMemory: Int = DEFAULT_MAX_GAME_EVENTS_IN_MEMORY
    ): ArchiveResult {
        if (events.size <= maxInMemory) {
            return ArchiveResult(success = true, archivedCount = 0, remainingCount = events.size)
        }

        val sorted = events.sortedBy { it.timestamp }
        val toArchive = sorted.take(events.size - maxInMemory)
        val toKeep = sorted.drop(toArchive.size)

        return try {
            val batchId = generateBatchId(ArchivedDataType.GAME_EVENT)
            val archivedFile = writeArchiveFile(
                dataType = ArchivedDataType.GAME_EVENT,
                batchId = batchId,
                items = toArchive.map { event ->
                    ArchivedItem(
                        originalData = serializeGameEvent(event),
                        timestamp = event.timestamp
                    )
                },
                timestamps = toArchive.map { it.timestamp }
            )

            updateIndex(archivedFile, ArchivedDataType.GAME_EVENT, toArchive)

            Log.i(TAG, "Archived ${toArchive.size} game events to $archivedFile")

            ArchiveResult(
                success = true,
                archivedCount = toArchive.size,
                remainingCount = toKeep.size,
                archiveFilePath = archivedFile.absolutePath
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to archive game events", e)
            ArchiveResult(success = false, error = e.message)
        }
    }

    /**
     * 获取应保留在主存档中的 BattleLog 列表（最近 N 条）。
     */
    fun getRetainedBattleLogs(battleLogs: List<BattleLog>, maxInMemory: Int = DEFAULT_MAX_BATTLE_LOGS_IN_MEMORY): List<BattleLog> {
        return if (battleLogs.size <= maxInMemory) {
            battleLogs
        } else {
            battleLogs.sortedByDescending { it.timestamp }.take(maxInMemory)
        }
    }

    /**
     * 获取应保留在主存档中的 GameEvent 列表（最近 N 条）。
     */
    fun getRetainedGameEvents(events: List<GameEvent>, maxInMemory: Int = DEFAULT_MAX_GAME_EVENTS_IN_MEMORY): List<GameEvent> {
        return if (events.size <= maxInMemory) {
            events
        } else {
            events.sortedByDescending { it.timestamp }.take(maxInMemory)
        }
    }

    // ==================== 公开 API：查询与恢复 ====================

    /**
     * 按时间范围查询已归档的 BattleLog。
     *
     * @param startMs 起始时间戳（含），null 表示不限
     * @param endMs 结束时间戳（含），null 表示不限
     */
    suspend fun queryBattleLogs(startMs: Long? = null, endMs: Long? = null): QueryResult<BattleLog> {
        return queryArchived(ArchivedDataType.BATTLE_LOG, startMs, endMs) { bytes ->
            deserializeBattleLog(bytes)
        }
    }

    /**
     * 按时间范围查询已归档的 GameEvent。
     */
    suspend fun queryGameEvents(startMs: Long? = null, endMs: Long? = null): QueryResult<GameEvent> {
        return queryArchived(ArchivedDataType.GAME_EVENT, startMs, endMs) { bytes ->
            deserializeGameEvent(bytes)
        }
    }

    /**
     * 将指定时间范围的归档数据恢复到主存档中。
     * 返回合并后的完整列表（去重，按时间排序）。
     */
    suspend fun restoreBattleLogs(
        currentLogs: List<BattleLog>,
        startMs: Long? = null,
        endMs: Long? = null
    ): List<BattleLog> {
        val archived = queryBattleLogs(startMs, endMs).entries
        val merged = (currentLogs + archived).distinctBy { it.id }
        return merged.sortedBy { it.timestamp }
    }

    suspend fun restoreGameEvents(
        currentEvents: List<GameEvent>,
        startMs: Long? = null,
        endMs: Long? = null
    ): List<GameEvent> {
        val archived = queryGameEvents(startMs, endMs).entries
        val merged = (currentEvents + archived).distinctBy { it.id }
        return merged.sortedBy { it.timestamp }
    }

    // ==================== 公开 API：清理与统计 ====================

    /**
     * 清理超过保留期限的归档文件。
     *
     * @param retentionMonths 保留月数，默认 12 个月
     * @return 被删除的文件数量
     */
    suspend fun cleanupExpiredArchives(retentionMonths: Int = DEFAULT_RETENTION_MONTHS): Int {
        val cutoff = Calendar.getInstance().apply {
            add(Calendar.MONTH, -retentionMonths)
        }.timeInMillis

        val index = loadIndex()
        val expiredEntries = index.entries.filter { it.latestTimestamp < cutoff }

        var deletedCount = 0
        for (entry in expiredEntries) {
            val file = File(archiveBaseDir, entry.fileName)
            if (file.exists() && file.delete()) {
                deletedCount++
                Log.d(TAG, "Deleted expired archive: ${entry.fileName}")
            }
        }

        if (deletedCount > 0) {
            rebuildIndex()
        }

        return deletedCount
    }

    /**
     * 获取归档统计信息。
     */
    suspend fun getArchiveStats(): ArchiveStats {
        val index = loadIndex()
        val now = System.currentTimeMillis()

        var totalEntries = 0L
        var totalBattleLogs = 0L
        var totalGameEvents = 0L
        var totalSize = 0L
        var totalOriginalSize = 0L
        var oldestTs = now
        var newestTs = 0L
        val typeCount = mutableMapOf<ArchivedDataType, Int>()
        val monthlyDist = mutableMapOf<String, Int>()

        for (entry in index.entries) {
            totalEntries += entry.entryCount.toLong()
            totalSize += entry.fileSize
            typeCount[entry.dataType] = (typeCount[entry.dataType] ?: 0) + 1
            monthlyDist[entry.yearMonth] = (monthlyDist[entry.yearMonth] ?: 0) + 1

            if (entry.earliestTimestamp < oldestTs) oldestTs = entry.earliestTimestamp
            if (entry.latestTimestamp > newestTs) newestTs = entry.latestTimestamp

            when (entry.dataType) {
                ArchivedDataType.BATTLE_LOG -> totalBattleLogs += entry.entryCount.toLong()
                ArchivedDataType.GAME_EVENT -> totalGameEvents += entry.entryCount.toLong()
            }
        }

        return ArchiveStats(
            totalArchiveFiles = index.entries.size,
            totalArchivedEntries = totalEntries,
            totalBattleLogsArchived = totalBattleLogs,
            totalGameEventsArchived = totalGameEvents,
            totalDiskUsageBytes = totalSize,
            compressionRatio = if (totalOriginalSize > 0) totalOriginalSize.toDouble() / totalSize.coerceAtLeast(1) else 0.0,
            oldestArchiveTimestamp = if (index.entries.isEmpty()) 0 else oldestTs,
            newestArchiveTimestamp = newestTs,
            archiveFileCountByType = typeCount,
            monthlyDistribution = monthlyDist
        )
    }

    /**
     * 获取所有归档文件的总大小（字节）。
     */
    fun getTotalArchiveSize(): Long {
        return archiveBaseDir.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".idx") }
            .sumOf { it.length() }
    }

    // ==================== 内部实现：文件写入 ====================

    private suspend fun writeArchiveFile(
        dataType: ArchivedDataType,
        batchId: String,
        items: List<ArchivedItem>,
        timestamps: List<Long>
    ): File {
        val batch = ArchivedBatch(
            dataType = dataType,
            entries = items
        )

        val serialized = protoBuf.encodeToByteArray(ArchivedBatch.serializer(), batch)
        val checksum = computeChecksum(serialized)
        val compressed = dataCompressor.compress(serialized, CompressionAlgorithm.LZ4)

        val calendar = Calendar.getInstance()
        val yearMonth = DATE_FORMAT.get()!!.format(calendar.time)
        val monthDir = File(archiveBaseDir, "${dataType.filePrefix}_$yearMonth").apply { mkdirs() }

        val fileName = "${dataType.filePrefix}_${TIMESTAMP_FORMAT.get()!!.format(calendar.time)}_$batchId.arc"
        val file = File(monthDir, fileName)

        file.writeBytes(compressed.data)

        Log.d(TAG, "Written archive file: ${file.absolutePath} (${compressed.data.size} bytes, original: ${serialized.size})")

        return file
    }

    // ==================== 内部实现：索引管理 ====================

    private suspend fun updateIndex(
        file: File,
        dataType: ArchivedDataType,
        archivedItems: List<Any>
    ) {
        indexMutex.withLock {
            val index = loadIndexInternal()

            val timestamps = when (archivedItems.firstOrNull()) {
                is BattleLog -> archivedItems.map { (it as BattleLog).timestamp }
                is GameEvent -> archivedItems.map { (it as GameEvent).timestamp }
                else -> emptyList()
            }

            val calendar = Calendar.getInstance()
            val yearMonth = DATE_FORMAT.get()!!.format(calendar.time)

            val newIndexEntry = ArchiveIndexEntry(
                fileName = file.relativeTo(archiveBaseDir).path,
                dataType = dataType,
                yearMonth = yearMonth,
                entryCount = archivedItems.size,
                earliestTimestamp = timestamps.minOrNull() ?: 0L,
                latestTimestamp = timestamps.maxOrNull() ?: 0L,
                fileSize = file.length(),
                createdAt = System.currentTimeMillis()
            )

            val updatedIndex = index.copy(
                lastUpdated = System.currentTimeMillis(),
                entries = index.entries + newIndexEntry
            )

            saveIndexInternal(updatedIndex)
        }
    }

    private fun loadIndex(): ArchiveIndex {
        val indexFile = getIndexFile()
        if (!indexFile.exists()) return ArchiveIndex()

        return try {
            val bytes = indexFile.readBytes()
            protoBuf.decodeFromByteArray(ArchiveIndex.serializer(), bytes)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load index, returning empty", e)
            ArchiveIndex()
        }
    }

    private fun loadIndexInternal(): ArchiveIndex = loadIndex()

    private fun saveIndexInternal(index: ArchiveIndex) {
        val indexFile = getIndexFile()
        val bytes = protoBuf.encodeToByteArray(ArchiveIndex.serializer(), index)
        indexFile.writeBytes(bytes)
    }

    private suspend fun rebuildIndex() {
        indexMutex.withLock {
            val newEntries = mutableListOf<ArchiveIndexEntry>()

            archiveBaseDir.walkTopDown()
                .filter { it.isFile && it.extension == "arc" }
                .forEach { file ->
                    try {
                        val header = readArchiveHeader(file)
                        val cal = Calendar.getInstance().apply { timeInMillis = header.createdAt }
                        val yearMonth = DATE_FORMAT.get()!!.format(cal.time)

                        newEntries.add(ArchiveIndexEntry(
                            fileName = file.relativeTo(archiveBaseDir).path,
                            dataType = header.dataType,
                            yearMonth = yearMonth,
                            entryCount = header.entryCount,
                            earliestTimestamp = header.earliestTimestamp,
                            latestTimestamp = header.latestTimestamp,
                            fileSize = file.length(),
                            createdAt = header.createdAt
                        ))
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping corrupt archive file: ${file.name}", e)
                    }
                }

            saveIndexInternal(ArchiveIndex(entries = newEntries))
            Log.i(TAG, "Index rebuilt with ${newEntries.size} entries")
        }
    }

    private fun getIndexFile(): File = File(archiveBaseDir, "archive_index.pb")

    // ==================== 内部实现：查询逻辑 ====================

    private suspend fun <T> queryArchived(
        dataType: ArchivedDataType,
        startMs: Long?,
        endMs: Long?,
        deserializer: (ByteArray) -> T
    ): QueryResult<T> {
        val index = loadIndex()
        val relevantFiles = index.entries
            .filter { it.dataType == dataType }
            .filter { startMs == null || it.latestTimestamp >= startMs }
            .filter { endMs == null || it.earliestTimestamp <= endMs }
            .sortedBy { it.earliestTimestamp }

        val results = mutableListOf<T>()
        val queriedFiles = mutableListOf<String>()

        for (entry in relevantFiles) {
            val file = File(archiveBaseDir, entry.fileName)
            if (!file.exists()) continue

            try {
                val items = readArchiveItems(file, dataType)
                for (item in items) {
                    val inRange = (startMs == null || item.timestamp >= startMs) &&
                                  (endMs == null || item.timestamp <= endMs)
                    if (inRange) {
                        results.add(deserializer(item.originalData))
                    }
                }
                queriedFiles.add(file.absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read archive file: ${file.name}", e)
            }
        }

        return QueryResult(results, results.size, queriedFiles)
    }

    private data class RawArchivedItem(val originalData: ByteArray, val timestamp: Long)

    private fun readArchiveItems(file: File, expectedType: ArchivedDataType): List<RawArchivedItem> {
        val compressedBytes = file.readBytes()
        val decompressed = dataCompressor.decompressWithHeader(compressedBytes)
        val batch = protoBuf.decodeFromByteArray(ArchivedBatch.serializer(), decompressed)

        if (batch.dataType != expectedType) {
            throw IllegalArgumentException("Expected ${expectedType.name}, got ${batch.dataType.name}")
        }

        return batch.entries.map { RawArchivedItem(it.originalData, it.timestamp) }
    }

    private data class ArchiveHeader(
        val dataType: ArchivedDataType,
        val createdAt: Long,
        val entryCount: Int,
        val earliestTimestamp: Long,
        val latestTimestamp: Long
    )

    private fun readArchiveHeader(file: File): ArchiveHeader {
        val compressedBytes = file.readBytes()
        val decompressed = dataCompressor.decompressWithHeader(compressedBytes)
        val batch = protoBuf.decodeFromByteArray(ArchivedBatch.serializer(), decompressed)

        val timestamps = batch.entries.map { it.timestamp }
        return ArchiveHeader(
            dataType = batch.dataType,
            createdAt = batch.createdAt,
            entryCount = batch.entries.size,
            earliestTimestamp = timestamps.minOrNull() ?: 0L,
            latestTimestamp = timestamps.maxOrNull() ?: 0L
        )
    }

    // ==================== 内部实现：序列化辅助 ====================

    @OptIn(ExperimentalSerializationApi::class)
    private fun serializeBattleLog(log: BattleLog): ByteArray {
        return protoBuf.encodeToByteArray(BattleLog.serializer(), log)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun deserializeBattleLog(bytes: ByteArray): BattleLog {
        return protoBuf.decodeFromByteArray(BattleLog.serializer(), bytes)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun serializeGameEvent(event: GameEvent): ByteArray {
        return protoBuf.encodeToByteArray(GameEvent.serializer(), event)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun deserializeGameEvent(bytes: ByteArray): GameEvent {
        return protoBuf.decodeFromByteArray(GameEvent.serializer(), bytes)
    }

    private fun computeChecksum(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun generateBatchId(dataType: ArchivedDataType): String {
        val timestamp = TIMESTAMP_FORMAT.get()!!.format(java.util.Date())
        val random = (Math.random() * 10000).toInt().toString().padStart(4, '0')
        return "${dataType.filePrefix}_" + timestamp + "_" + random
    }
}
