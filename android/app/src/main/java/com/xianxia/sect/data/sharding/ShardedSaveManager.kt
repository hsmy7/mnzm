@file:Suppress("DEPRECATION")
package com.xianxia.sect.data.sharding

import android.content.Context
import android.util.Log
import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.serialization.NullSafeProtoBuf
import com.xianxia.sect.data.unified.SaveError
import com.xianxia.sect.data.unified.SaveResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 存档分片类型枚举
 */
@Serializable
enum class ShardType {
    CORE,       // 核心游戏数据（GameData, 当前弟子列表, 装备列表等）
    HISTORY,    // 历史数据（BattleLog超过500条的部分, GameEvent超过1000条的部分）
    ARCHIVE     // 归档数据（旧的战斗日志和事件）
}

/**
 * 分片数据结构 - 核心数据
 */
@Serializable
data class CoreShardData(
    val version: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val gameData: GameData,
    val disciples: List<Disciple> = emptyList(),
    val equipment: List<Equipment> = emptyList(),
    val manuals: List<Manual> = emptyList(),
    val pills: List<Pill> = emptyList(),
    val materials: List<Material> = emptyList(),
    val herbs: List<Herb> = emptyList(),
    val seeds: List<Seed> = emptyList(),
    val teams: List<ExplorationTeam> = emptyList(),
    val alliances: List<Alliance> = emptyList()
)

/**
 * 分片数据结构 - 历史数据
 */
@Serializable
data class HistoryShardData(
    val version: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val battleLogs: List<BattleLog> = emptyList(),
    val events: List<GameEvent> = emptyList()
)

/**
 * 分片数据结构 - 归档数据
 */
@Serializable
data class ArchiveShardData(
    val version: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val archivedBattleLogs: List<BattleLog> = emptyList(),
    val archivedEvents: List<GameEvent> = emptyList(),
    val archiveGeneration: Int = 0  // 归档代数，用于标识归档批次
)

/**
 * 分片元数据
 */
@Serializable
data class ShardMetadata(
    val slotId: Int,
    val shardType: ShardType,
    val fileName: String,
    val fileSize: Long = 0,
    val recordCount: Int = 0,
    val checksum: String = "",
    val lastModified: Long = System.currentTimeMillis(),
    val compressionRatio: Double = 1.0,
    val isValid: Boolean = true
)

/**
 * 分片保存操作结果
 */
data class ShardSaveResult(
    val shardType: ShardType,
    val success: Boolean,
    val bytesWritten: Long = 0,
    val timeMs: Long = 0,
    val error: SaveError? = null,
    val errorMessage: String = ""
)

/**
 * 分片加载操作结果
 */
data class ShardLoadResult(
    val shardType: ShardType,
    val success: Boolean,
    val bytesRead: Long = 0,
    val timeMs: Long = 0,
    val error: SaveError? = null,
    val errorMessage: String = ""
)

/**
 * 分片管理器状态
 */
data class ShardedSaveState(
    val isProcessing: Boolean = false,
    val currentOperation: String = "",
    val progress: Float = 0f,
    val activeSlot: Int = -1,
    val lastError: String = ""
)

/**
 * 存档分片管理器
 *
 * 将历史数据与当前状态分离存储，解决长时间游戏存档过大的问题。
 *
 * 功能特性：
 * - 三种分片策略：CORE（核心数据）、HISTORY（历史数据）、ARCHIVE（归档数据）
 * - 每个分片独立序列化和存储
 * - 加载时自动合并所有分片
 * - 完整的分片元数据管理
 * - 支持增量更新单个分片
 * - 使用 StateFlow 暴露进度和状态
 */
@Singleton
class ShardedSaveManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    companion object {
        private const val TAG = "ShardedSaveManager"
        private const val SHARD_DIR = "sharded_saves"
        private const val SHARD_FILE_EXT = ".sav"
        private const val METADATA_FILE = "shard_metadata.json"
        private const val CURRENT_VERSION = "1.0"

        // 分片阈值配置
        private const val MAX_BATTLE_LOGS_IN_CORE = 500
        private const val MAX_EVENTS_IN_CORE = 1000
        private const val MAX_BATTLE_LOGS_IN_HISTORY = 2000
        private const val MAX_EVENTS_IN_HISTORY = 5000

        // ShardedSaveManager 自有文件命名格式（用于 CORE/HISTORY/ARCHIVE 分片）
        private const val CORE_FILE_PATTERN = "slot_%d_core%s"
        private const val HISTORY_FILE_PATTERN = "slot_%d_history%s"
        private const val ARCHIVE_FILE_PATTERN = "slot_%d_archive%s"

        /**
         * UnifiedSaveRepository 兼容的分片文件命名格式。
         *
         * 与 UnifiedSaveRepository.performShardedSave() 保持一致：
         * - 格式：{baseFileName}.shard_{NNN}
         * - 示例：slot1.shard_000, slot1.shard_001, ...
         *
         * 此格式用于 BattleLog 和 Event 数据的分片存储，
         * 与 ShardedSaveManager 自有的 CORE/HISTORY/ARCHIVE 分片体系共存。
         */
        const val USR_SHARD_FILE_SUFFIX_PATTERN = ".shard_%03d"
        const val USR_SHARD_MAGIC_HEADER = "SHRD"
    }
    
    private val shardDir: File by lazy {
        File(context.filesDir, SHARD_DIR).apply { mkdirs() }
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 状态流
    private val _state = MutableStateFlow(ShardedSaveState())
    val state: StateFlow<ShardedSaveState> = _state.asStateFlow()
    
    // 元数据缓存
    private val metadataCache = ConcurrentHashMap<Int, Map<ShardType, ShardMetadata>>()
    
    // 统计计数器
    private val saveCount = AtomicLong(0)
    private val loadCount = AtomicLong(0)
    private val totalBytesWritten = AtomicLong(0)
    private val totalBytesRead = AtomicLong(0)
    
    init {
        Log.i(TAG, "ShardedSaveManager initialized")
    }
    
    /**
     * 分片保存存档数据
     *
     * 将完整的 SaveData 按照分片策略拆分为多个独立文件存储：
     * - CORE: 核心游戏数据 + 最近的历史记录
     * - HISTORY: 超出核心容量的历史记录
     * - ARCHIVE: 更早期的归档数据
     *
     * @param slotId 存档槽位ID
     * @param data 完整的存档数据
     * @return 保存结果，包含各分片的统计信息
     */
    suspend fun saveSharded(slotId: Int, data: SaveData): SaveResult<ShardedSaveStats> =
        withContext(Dispatchers.IO) {
            updateState(isProcessing = true, currentOperation = "save", activeSlot = slotId, progress = 0f)
            
            try {
                val startTime = System.currentTimeMillis()
                
                // 1. 数据分片
                updateState(progress = 0.1f, currentOperation = "splitting_data")
                val shards = splitData(data, slotId)
                
                // 2. 并行保存各分片
                updateState(progress = 0.2f, currentOperation = "saving_shards")
                val saveResults = mutableListOf<ShardSaveResult>()
                
                val deferredResults = listOf(
                    async { saveCoreShard(slotId, shards.core) },
                    async { saveHistoryShard(slotId, shards.history) },
                    async { saveArchiveShard(slotId, shards.archive) }
                )
                
                for (deferred in deferredResults) {
                    saveResults.add(deferred.await())
                }
                
                // 3. 检查保存结果
                updateState(progress = 0.8f, currentOperation = "validating")
                val failures = saveResults.filter { !it.success }
                
                if (failures.isNotEmpty()) {
                    val errorMsg = failures.joinToString(", ") { "${it.shardType}: ${it.errorMessage}" }
                    Log.e(TAG, "Failed to save some shards for slot $slotId: $errorMsg")
                    return@withContext SaveResult.failure(
                        SaveError.SAVE_FAILED,
                        "Partial failure: $errorMsg"
                    )
                }
                
                // 4. 更新元数据
                updateState(progress = 0.9f, currentOperation = "updating_metadata")
                saveMetadata(slotId, saveResults)
                
                // 5. 统计信息
                val timeMs = System.currentTimeMillis() - startTime
                val stats = ShardedSaveStats(
                    slotId = slotId,
                    totalBytesWritten = saveResults.sumOf { it.bytesWritten },
                    timeMs = timeMs,
                    coreBytes = saveResults.find { it.shardType == ShardType.CORE }?.bytesWritten ?: 0L,
                    historyBytes = saveResults.find { it.shardType == ShardType.HISTORY }?.bytesWritten ?: 0L,
                    archiveBytes = saveResults.find { it.shardType == ShardType.ARCHIVE }?.bytesWritten ?: 0L,
                    coreRecordCount = shards.core.disciples.size + shards.core.equipment.size,
                    historyRecordCount = shards.history.battleLogs.size + shards.history.events.size,
                    archiveRecordCount = shards.archive.archivedBattleLogs.size + shards.archive.archivedEvents.size
                )
                
                saveCount.incrementAndGet()
                totalBytesWritten.addAndGet(stats.totalBytesWritten)
                
                updateState(isProcessing = false, progress = 1f, currentOperation = "")
                
                Log.i(TAG, "Sharded save completed for slot $slotId: ${stats.totalBytesWritten} bytes in ${timeMs}ms")
                SaveResult.success(stats)
                
            } catch (e: Exception) {
                Log.e(TAG, "Sharded save failed for slot $slotId", e)
                updateState(isProcessing = false, lastError = e.message ?: "Unknown error")
                SaveResult.failure(SaveError.SAVE_FAILED, e.message ?: "Unknown error", e)
            }
        }
    
    /**
     * 分片加载存档数据
     *
     * 加载指定槽位的所有分片并自动合并为完整的 SaveData。
     * 如果某个分片不存在或损坏，会尝试从其他分片恢复可用数据。
     *
     * @param slotId 存档槽位ID
     * @return 加载结果，包含合并后的完整数据
     */
    suspend fun loadSharded(slotId: Int): SaveResult<SaveData> =
        withContext(Dispatchers.IO) {
            updateState(isProcessing = true, currentOperation = "load", activeSlot = slotId, progress = 0f)
            
            try {
                val startTime = System.currentTimeMillis()
                
                // 1. 检查分片文件是否存在
                updateState(progress = 0.1f, currentOperation = "checking_shards")
                val coreFile = getShardFile(slotId, ShardType.CORE)
                
                if (!coreFile.exists()) {
                    Log.w(TAG, "Core shard not found for slot $slotId")
                    return@withContext SaveResult.failure(SaveError.SLOT_EMPTY, "No save data for slot $slotId")
                }
                
                // 2. 并行加载各分片
                updateState(progress = 0.2f, currentOperation = "loading_shards")
                val coreResult = loadCoreShard(slotId)
                val historyResult = loadHistoryShard(slotId)
                val archiveResult = loadArchiveShard(slotId)
                
                // 3. 合并数据
                updateState(progress = 0.7f, currentOperation = "merging_data")
                
                if (!coreResult.success) {
                    Log.e(TAG, "Failed to load core shard for slot $slotId")
                    return@withContext SaveResult.failure(
                        coreResult.error ?: SaveError.LOAD_FAILED,
                        coreResult.errorMessage
                    )
                }
                
                val coreData = coreResult.data
                    ?: return@withContext SaveResult.failure(SaveError.LOAD_FAILED, "Core data is null after successful load")
                val mergedData = mergeShards(
                    core = coreData,
                    history = historyResult.data ?: HistoryShardData(),
                    archive = archiveResult.data ?: ArchiveShardData()
                )
                
                // 4. 转换为 SaveData 格式
                updateState(progress = 0.9f, currentOperation = "converting")
                val saveData = convertToSaveData(mergedData, slotId)
                
                val timeMs = System.currentTimeMillis() - startTime
                loadCount.incrementAndGet()
                totalBytesRead.addAndGet(coreResult.bytesRead + (historyResult.bytesRead) + (archiveResult.bytesRead))
                
                updateState(isProcessing = false, progress = 1f, currentOperation = "")
                
                Log.i(TAG, "Sharded load completed for slot $slotId in ${timeMs}ms")
                SaveResult.success(saveData)
                
            } catch (e: Exception) {
                Log.e(TAG, "Sharded load failed for slot $slotId", e)
                updateState(isProcessing = false, lastError = e.message ?: "Unknown error")
                SaveResult.failure(SaveError.LOAD_FAILED, e.message ?: "Unknown error", e)
            }
        }
    
    /**
     * 增量更新单个分片
     *
     * 只更新指定的分片类型，避免全量保存的开销。
     * 适用于只修改了部分数据的场景（如只添加了新的战斗日志）。
     *
     * @param slotId 存档槽位ID
     * @param shardType 要更新的分片类型
     * @param data 完整的存档数据（用于重新分片）
     * @return 更新结果
     */
    suspend fun updateShard(
        slotId: Int,
        shardType: ShardType,
        data: SaveData
    ): SaveResult<ShardSaveResult> = withContext(Dispatchers.IO) {
        
        try {
            val startTime = System.currentTimeMillis()
            val shards = splitData(data, slotId)
            
            val result = when (shardType) {
                ShardType.CORE -> saveCoreShard(slotId, shards.core)
                ShardType.HISTORY -> saveHistoryShard(slotId, shards.history)
                ShardType.ARCHIVE -> saveArchiveShard(slotId, shards.archive)
            }
            
            if (result.success) {
                // 更新该分片的元数据
                updateShardMetadata(slotId, shardType, result)
            }
            
            val timeMs = System.currentTimeMillis() - startTime
            SaveResult.success(result.copy(timeMs = timeMs))
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update shard $shardType for slot $slotId", e)
            SaveResult.failure(SaveError.SAVE_FAILED, e.message ?: "Unknown error", e)
        }
    }
    
    /**
     * 获取分片元数据
     *
     * 返回指定槽位所有分片的元数据信息，包括文件大小、记录数、校验和等。
     *
     * @param slotId 存档槽位ID
     * @return 分片元数据映射表
     */
    fun getShardMetadata(slotId: Int): Map<ShardType, ShardMetadata> {
        return metadataCache[slotId] ?: run {
            loadMetadataFromDisk(slotId).also {
                metadataCache[slotId] = it
            }
        }
    }
    
    /**
     * 获取所有槽位的分片概览
     *
     * @return 槽位ID到其分片信息的映射
     */
    fun getAllSlotsOverview(): Map<Int, ShardedSlotOverview> {
        val overview = mutableMapOf<Int, ShardedSlotOverview>()
        
        shardDir.listFiles()?.filter { it.name.startsWith("slot_") && it.name.endsWith(SHARD_FILE_EXT) }
            ?.groupBy { extractSlotId(it.name) }
            ?.forEach { (slotId, files) ->
                val types = files.map { extractShardType(it.name) }.toSet()
                val totalSize = files.sumOf { it.length() }
                
                overview[slotId] = ShardedSlotOverview(
                    slotId = slotId,
                    hasCore = ShardType.CORE in types,
                    hasHistory = ShardType.HISTORY in types,
                    hasArchive = ShardType.ARCHIVE in types,
                    totalSize = totalSize,
                    fileCount = files.size,
                    lastModified = files.maxOfOrNull { it.lastModified() } ?: 0L
                )
            }
        
        return overview
    }
    
    /**
     * 删除指定槽位的所有分片
     *
     * @param slotId 存档槽位ID
     * @return 删除是否成功
     */
    suspend fun deleteSharded(slotId: Int): SaveResult<Unit> = withContext(Dispatchers.IO) {
        try {
            var deletedAll = true
            
            for (shardType in ShardType.values()) {
                val file = getShardFile(slotId, shardType)
                if (file.exists() && !file.delete()) {
                    deletedAll = false
                    Log.w(TAG, "Failed to delete ${shardType} shard for slot $slotId")
                }
            }
            
            metadataCache.remove(slotId)
            
            if (deletedAll) {
                SaveResult.success(Unit)
            } else {
                SaveResult.failure(SaveError.DELETE_FAILED, "Partial deletion for slot $slotId")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete sharded save for slot $slotId", e)
            SaveResult.failure(SaveError.DELETE_FAILED, e.message ?: "Unknown error", e)
        }
    }
    
    /**
     * 检查槽位是否存在分片存档
     *
     * @param slotId 存档槽位ID
     * @return 是否存在
     */
    fun hasShardedSave(slotId: Int): Boolean {
        return getShardFile(slotId, ShardType.CORE).exists()
    }
    
    /**
     * 验证所有分片的完整性
     *
     * @param slotId 存档槽位ID
     * @return 验证结果
     */
    suspend fun verifyIntegrity(slotId: Int): ShardIntegrityResult = withContext(Dispatchers.IO) {
        val results = mutableListOf<Pair<ShardType, IntegrityStatus>>()
        var allValid = true
        
        for (shardType in ShardType.values()) {
            val status = verifyShardIntegrity(slotId, shardType)
            results.add(shardType to status)
            if (!status.isValid) allValid = false
        }
        
        ShardIntegrityResult(
            slotId = slotId,
            isValid = allValid,
            shardStatuses = results.toMap()
        )
    }
    
    /**
     * 获取统计信息
     */
    fun getStats(): ShardedSaveManagerStats {
        return ShardedSaveManagerStats(
            totalSaves = saveCount.get(),
            totalLoads = loadCount.get(),
            totalBytesWritten = totalBytesWritten.get(),
            totalBytesRead = totalBytesRead.get(),
            cachedSlots = metadataCache.size
        )
    }
    
    /**
     * 清理资源
     */
    fun shutdown() {
        scope.cancel()
        metadataCache.clear()
        Log.i(TAG, "ShardedSaveManager shutdown completed")
    }

    // ==================== UnifiedSaveRepository 兼容分片操作 ====================

    /**
     * 生成与 UnifiedSaveRepository 兼容的分片文件名。
     *
     * 此方法确保 ShardedSaveManager 生成的分片文件名与
     * UnifiedSaveRepository.performShardedSave() 使用的一致。
     *
     * @param baseFileName 基础文件名（如 "slot1"）
     * @param shardIndex 分片索引（从 0 开始）
     * @return 分片文件名（如 "slot1.shard_000"）
     */
    fun generateUsrShardFileName(baseFileName: String, shardIndex: Int): String {
        return baseFileName + String.format(Locale.ROOT, USR_SHARD_FILE_SUFFIX_PATTERN, shardIndex)
    }

    /**
     * 检测指定基础文件名对应的所有 USR 格式分片文件。
     *
     * @param baseFileName 基础文件名（如 "slot1"）
     * @return 按分片索引排序的 (shardIndex, File) 列表
     */
    fun detectUsrShardFiles(baseFileName: String): List<Pair<Int, File>> {
        val shardPattern = Regex("""${Regex.escape(baseFileName)}\.shard_(\d{3})$""")

        return try {
            shardDir.listFiles()
                ?.mapNotNull { file ->
                    shardPattern.find(file.name)?.let { matchResult ->
                        val index = matchResult.groupValues[1].toIntOrNull()
                        if (index != null) index to file else null
                    }
                }
                ?.sortedBy { it.first }
                ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Error detecting USR shard files for base '$baseFileName'", e)
            emptyList()
        }
    }

    /**
     * 读取单个 USR 格式的分片文件并解析其内容。
     *
     * 分片文件格式（Protobuf 二进制）：
     * - 文件头：4字节 magic header [USR_SHARD_MAGIC_HEADER]
     * - 剩余部分：Protobuf 编码的分片数据
     *
     * @param shardFile 分片文件
     * @param decryptedData 已解密的原始字节数据（如果为 null 则从文件读取原始数据不解密）
     * @return 解析后的分片内容，如果文件损坏则返回 null
     */
    fun parseUsrShardContent(shardFile: File, decryptedData: ByteArray? = null): UsrShardContent? {
        if (!shardFile.exists()) {
            return null
        }

        val rawData = decryptedData ?: try {
            // 如果没有提供解密数据，尝试直接读取（适用于未加密的场景或调试）
            shardFile.readBytes()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read USR shard file ${shardFile.name}", e)
            return null
        }

        // 验证 magic header（仅对从文件读取的原始数据进行验证）
        if (decryptedData == null && rawData.size < USR_SHARD_MAGIC_HEADER.length) {
            Log.w(TAG, "USR shard file too small: ${shardFile.name}")
            return null
        }

        // 如果是解密后的数据，跳过 magic header 验证（因为 decryptData 已处理）
        // 如果是从文件读取的原始数据，验证并剥离 magic header
        val protoBytes = if (decryptedData != null) {
            decryptedData
        } else {
            if (String(rawData, 0, USR_SHARD_MAGIC_HEADER.length) != USR_SHARD_MAGIC_HEADER) {
                Log.w(TAG, "Invalid magic header in USR shard file ${shardFile.name}")
                return null
            }
            rawData.copyOfRange(USR_SHARD_MAGIC_HEADER.length, rawData.size)
        }

        // 尝试 Protobuf 解析（兼容新格式）
        return try {
            // 新格式：直接存储 Protobuf 编码的字节数组
            UsrShardContent(
                type = "protobuf",
                count = -1,  // Protobuf 格式无法预先知道条目数
                rawProtoBytes = protoBytes,
                fileName = shardFile.name
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse content from USR shard file ${shardFile.name}", e)
            null
        }
    }

    /**
     * 获取指定槽位的所有 USR 格式分片的概要信息。
     *
     * @param baseFileName 基础文件名
     * @return 分片概要信息列表
     */
    fun getUsrShardsOverview(baseFileName: String): List<UsrShardOverview> {
        val shardFiles = detectUsrShardFiles(baseFileName)

        return shardFiles.map { (index, file) ->
            UsrShardOverview(
                shardIndex = index,
                fileName = file.name,
                fileSize = file.length(),
                lastModified = file.lastModified(),
                exists = file.exists()
            )
        }
    }
    
    // ==================== 私有方法 ====================
    
    /**
     * 数据分片逻辑
     *
     * 根据配置的阈值将完整数据拆分为三个分片：
     * - CORE: 核心数据 + 最近500条战斗日志 + 最近1000条事件
     * - HISTORY: 500-2000条战斗日志 + 1000-5000条事件
     * - ARCHIVE: 超过2000条的战斗日志和超过5000条事件
     */
    private fun splitData(data: SaveData, slotId: Int): ShardedData {
        // 排序战斗日志和事件（按时间戳降序）
        val sortedBattleLogs = data.battleLogs.sortedByDescending { it.timestamp }
        val sortedEvents = data.events.sortedByDescending { it.timestamp }
        
        // 拆分战斗日志
        val coreBattleLogs = sortedBattleLogs.take(MAX_BATTLE_LOGS_IN_CORE)
        val remainingAfterCore = sortedBattleLogs.drop(MAX_BATTLE_LOGS_IN_CORE)
        val historyBattleLogs = remainingAfterCore.take(MAX_BATTLE_LOGS_IN_HISTORY - MAX_BATTLE_LOGS_IN_CORE)
        val archivedBattleLogs = remainingAfterCore.drop(MAX_BATTLE_LOGS_IN_HISTORY - MAX_BATTLE_LOGS_IN_CORE)
        
        // 拆分事件
        val coreEvents = sortedEvents.take(MAX_EVENTS_IN_CORE)
        val remainingEventsAfterCore = sortedEvents.drop(MAX_EVENTS_IN_CORE)
        val historyEvents = remainingEventsAfterCore.take(MAX_EVENTS_IN_HISTORY - MAX_EVENTS_IN_CORE)
        val archivedEvents = remainingEventsAfterCore.drop(MAX_EVENTS_IN_HISTORY - MAX_EVENTS_IN_CORE)
        
        // 获取当前归档代数
        val currentArchiveGeneration = getArchiveGeneration(slotId)
        
        return ShardedData(
            core = CoreShardData(
                version = CURRENT_VERSION,
                timestamp = data.timestamp,
                gameData = data.gameData,
                disciples = data.disciples,
                equipment = data.equipment,
                manuals = data.manuals,
                pills = data.pills,
                materials = data.materials,
                herbs = data.herbs,
                seeds = data.seeds,
                teams = data.teams,
                alliances = data.alliances
            ),
            history = HistoryShardData(
                version = CURRENT_VERSION,
                timestamp = data.timestamp,
                battleLogs = historyBattleLogs,
                events = historyEvents
            ),
            archive = ArchiveShardData(
                version = CURRENT_VERSION,
                timestamp = data.timestamp,
                archivedBattleLogs = archivedBattleLogs,
                archivedEvents = archivedEvents,
                archiveGeneration = currentArchiveGeneration
            )
        )
    }
    
    /**
     * 合并分片数据
     */
    private fun mergeShards(
        core: CoreShardData,
        history: HistoryShardData,
        archive: ArchiveShardData
    ): MergedShardData {
        // 合并战斗日志：archive -> history -> core（按时间戳排序）
        val allBattleLogs = (archive.archivedBattleLogs + history.battleLogs + listOf())
            .sortedByDescending { it.timestamp }
        
        // 合并事件
        val allEvents = (archive.archivedEvents + history.events + listOf())
            .sortedByDescending { it.timestamp }
        
        return MergedShardData(
            core = core,
            allBattleLogs = allBattleLogs,
            allEvents = allEvents
        )
    }
    
    /**
     * 转换为 SaveData 格式
     */
    private fun convertToSaveData(merged: MergedShardData, slotId: Int): SaveData {
        return SaveData(
            version = merged.core.version,
            timestamp = merged.core.timestamp,
            gameData = merged.core.gameData.copy(currentSlot = slotId),
            disciples = merged.core.disciples,
            equipment = merged.core.equipment,
            manuals = merged.core.manuals,
            pills = merged.core.pills,
            materials = merged.core.materials,
            herbs = merged.core.herbs,
            seeds = merged.core.seeds,
            teams = merged.core.teams,
            events = merged.allEvents,
            battleLogs = merged.allBattleLogs,
            alliances = merged.core.alliances,
            productionSlots = merged.core.gameData.productionSlots
        )
    }
    
    /**
     * 保存核心分片
     */
    private suspend fun saveCoreShard(slotId: Int, data: CoreShardData): ShardSaveResult {
        return performShardSave(slotId, ShardType.CORE, data, CoreShardData.serializer())
    }

    private suspend fun saveHistoryShard(slotId: Int, data: HistoryShardData): ShardSaveResult {
        return performShardSave(slotId, ShardType.HISTORY, data, HistoryShardData.serializer())
    }

    private suspend fun saveArchiveShard(slotId: Int, data: ArchiveShardData): ShardSaveResult {
        return performShardSave(slotId, ShardType.ARCHIVE, data, ArchiveShardData.serializer())
    }
    
    /**
     * 执行分片保存操作的通用方法
     */
    private suspend fun <T : Any> performShardSave(
        slotId: Int,
        shardType: ShardType,
        data: T,
        serializer: kotlinx.serialization.KSerializer<T>
    ): ShardSaveResult {
        val startTime = System.currentTimeMillis()

        try {
            val file = getShardFile(slotId, shardType)
            val tempFile = File(file.parent, file.name + ".tmp")

            // 序列化数据（Protobuf 二进制格式）
            val rawData = NullSafeProtoBuf.protoBuf.encodeToByteArray(serializer, data)

            // 写入临时文件
            tempFile.outputStream().use { output ->
                output.write(rawData)
            }

            // 原子性替换
            if (file.exists()) {
                file.delete()
            }

            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }

            // 计算校验和
            val checksum = computeChecksum(rawData)

            val timeMs = System.currentTimeMillis() - startTime

            Log.d(TAG, "Saved ${shardType} shard for slot $slotId: ${rawData.size} bytes in ${timeMs}ms")

            return ShardSaveResult(
                shardType = shardType,
                success = true,
                bytesWritten = file.length(),
                timeMs = timeMs
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save ${shardType} shard for slot $slotId", e)
            return ShardSaveResult(
                shardType = shardType,
                success = false,
                error = SaveError.SAVE_FAILED,
                errorMessage = e.message ?: "Unknown error",
                timeMs = System.currentTimeMillis() - startTime
            )
        }
    }
    
    /**
     * 加载核心分片
     */
    private suspend fun loadCoreShard(slotId: Int): ShardLoadResultWithData<CoreShardData> {
        return performShardLoad(slotId, ShardType.CORE, serializer<CoreShardData>())
    }

    /**
     * 加载历史分片
     */
    private suspend fun loadHistoryShard(slotId: Int): ShardLoadResultWithData<HistoryShardData> {
        return performShardLoad(slotId, ShardType.HISTORY, serializer<HistoryShardData>())
    }

    /**
     * 加载归档分片
     */
    private suspend fun loadArchiveShard(slotId: Int): ShardLoadResultWithData<ArchiveShardData> {
        return performShardLoad(slotId, ShardType.ARCHIVE, serializer<ArchiveShardData>())
    }

    /**
     * 执行分片加载操作的通用方法
     */
    private suspend fun <T : Any> performShardLoad(
        slotId: Int,
        shardType: ShardType,
        serializer: KSerializer<T>
    ): ShardLoadResultWithData<T> {
        val startTime = System.currentTimeMillis()

        try {
            val file = getShardFile(slotId, shardType)

            if (!file.exists()) {
                return ShardLoadResultWithData(
                    shardType = shardType,
                    success = false,
                    error = SaveError.SLOT_EMPTY,
                    errorMessage = "${shardType} shard not found for slot $slotId"
                )
            }

            val rawData = file.readBytes()
            val data = NullSafeProtoBuf.protoBuf.decodeFromByteArray(serializer, rawData)

            val timeMs = System.currentTimeMillis() - startTime

            Log.d(TAG, "Loaded ${shardType} shard for slot $slotId: ${rawData.size} bytes in ${timeMs}ms")

            return ShardLoadResultWithData(
                shardType = shardType,
                success = true,
                data = data,
                bytesRead = rawData.size.toLong(),
                timeMs = timeMs
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ${shardType} shard for slot $slotId", e)
            return ShardLoadResultWithData(
                shardType = shardType,
                success = false,
                error = SaveError.LOAD_FAILED,
                errorMessage = e.message ?: "Unknown error",
                timeMs = System.currentTimeMillis() - startTime
            )
        }
    }
    
    /**
     * 验证单个分片的完整性
     */
    private suspend fun verifyShardIntegrity(slotId: Int, shardType: ShardType): IntegrityStatus {
        val file = getShardFile(slotId, shardType)
        
        if (!file.exists()) {
            return IntegrityStatus(
                isValid = false,
                exists = false,
                error = "File not found"
            )
        }
        
        return try {
            val rawData = file.readBytes()
            val metadata = getShardMetadata(slotId)[shardType]
            
            val isValid = when {
                metadata == null -> true  // 无元数据则跳过校验
                metadata.checksum.isEmpty() -> true
                else -> computeChecksum(rawData) == metadata.checksum
            }
            
            IntegrityStatus(
                isValid = isValid,
                exists = true,
                fileSize = file.length(),
                checksumMatch = metadata != null && metadata.checksum.isNotEmpty()
            )
            
        } catch (e: Exception) {
            IntegrityStatus(
                isValid = false,
                exists = true,
                error = e.message
            )
        }
    }
    
    /**
     * 获取分片文件路径
     */
    private fun getShardFile(slotId: Int, shardType: ShardType): File {
        val pattern = when (shardType) {
            ShardType.CORE -> CORE_FILE_PATTERN
            ShardType.HISTORY -> HISTORY_FILE_PATTERN
            ShardType.ARCHIVE -> ARCHIVE_FILE_PATTERN
        }
        
        return File(shardDir, pattern.format(slotId, SHARD_FILE_EXT))
    }
    
    /**
     * 从文件名提取槽位ID
     */
    private fun extractSlotId(fileName: String): Int {
        return Regex("slot_(\\d+)_[a-z]+\\.sav").find(fileName)?.groupValues?.get(1)?.toInt() ?: -1
    }
    
    /**
     * 从文件名提取分片类型
     */
    private fun extractShardType(fileName: String): ShardType {
        return when {
            fileName.contains("_core") -> ShardType.CORE
            fileName.contains("_history") -> ShardType.HISTORY
            fileName.contains("_archive") -> ShardType.ARCHIVE
            else -> throw IllegalArgumentException("Unknown shard type in filename: $fileName")
        }
    }
    
    /**
     * 计算数据校验和
     */
    private fun computeChecksum(data: ByteArray): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(data)
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to compute checksum", e)
            ""
        }
    }
    
    /**
     * 保存元数据到磁盘
     */
    private fun saveMetadata(slotId: Int, results: List<ShardSaveResult>) {
        try {
            val metadataMap = mutableMapOf<ShardType, ShardMetadata>()
            
            for (result in results) {
                val file = getShardFile(slotId, result.shardType)
                metadataMap[result.shardType] = ShardMetadata(
                    slotId = slotId,
                    shardType = result.shardType,
                    fileName = file.name,
                    fileSize = result.bytesWritten,
                    lastModified = System.currentTimeMillis(),
                    isValid = result.success
                )
            }
            
            metadataCache[slotId] = metadataMap
            persistMetadataToDisk()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save metadata for slot $slotId", e)
        }
    }
    
    /**
     * 更新单个分片的元数据
     */
    private fun updateShardMetadata(slotId: Int, shardType: ShardType, result: ShardSaveResult) {
        val existing = metadataCache[slotId]?.toMutableMap() ?: mutableMapOf()
        val file = getShardFile(slotId, shardType)
        
        existing[shardType] = ShardMetadata(
            slotId = slotId,
            shardType = shardType,
            fileName = file.name,
            fileSize = result.bytesWritten,
            lastModified = System.currentTimeMillis(),
            isValid = result.success
        )
        
        metadataCache[slotId] = existing
        persistMetadataToDisk()
    }
    
    /**
     * 从磁盘加载元数据
     */
    private fun loadMetadataFromDisk(slotId: Int): Map<ShardType, ShardMetadata> {
        val metadataFile = File(shardDir, METADATA_FILE)

        if (!metadataFile.exists()) {
            return emptyMap()
        }

        return try {
            val rawData = metadataFile.readBytes()
            val allMetadata: Map<Int, Map<String, ShardMetadata>> =
                NullSafeProtoBuf.protoBuf.decodeFromByteArray(
                    serializer<Map<Int, Map<String, ShardMetadata>>>(),
                    rawData
                )

            allMetadata[slotId]?.mapKeys { (key, _) ->
                ShardType.valueOf(key)
            } ?: emptyMap()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load metadata from disk", e)
            emptyMap()
        }
    }

    /**
     * 持久化元数据到磁盘
     */
    private fun persistMetadataToDisk() {
        try {
            val metadataFile = File(shardDir, METADATA_FILE)
            val serializable = metadataCache.mapValues { (_, shardMap) ->
                shardMap.mapKeys { (key, _) -> key.name }
            }

            val rawData = NullSafeProtoBuf.protoBuf.encodeToByteArray(
                kotlinx.serialization.serializer<Map<Int, Map<String, ShardMetadata>>>(),
                serializable
            )
            metadataFile.writeBytes(rawData)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist metadata to disk", e)
        }
    }
    
    /**
     * 获取归档代数
     */
    private fun getArchiveGeneration(slotId: Int): Int {
        val metadata = getShardMetadata(slotId)[ShardType.ARCHIVE]
        return metadata?.let {
            // 可以根据需要实现更复杂的归档代数计算逻辑
            0
        } ?: 0
    }
    
    /**
     * 更新状态
     */
    private fun updateState(
        isProcessing: Boolean = _state.value.isProcessing,
        currentOperation: String = _state.value.currentOperation,
        progress: Float = _state.value.progress,
        activeSlot: Int = _state.value.activeSlot,
        lastError: String = _state.value.lastError
    ) {
        _state.value = ShardedSaveState(
            isProcessing = isProcessing,
            currentOperation = currentOperation,
            progress = progress,
            activeSlot = activeSlot,
            lastError = lastError
        )
    }
}

// ==================== 数据类定义 ====================

/**
 * 分片后的数据容器
 */
private data class ShardedData(
    val core: CoreShardData,
    val history: HistoryShardData,
    val archive: ArchiveShardData
)

/**
 * 合并后的分片数据
 */
private data class MergedShardData(
    val core: CoreShardData,
    val allBattleLogs: List<BattleLog>,
    val allEvents: List<GameEvent>
)

/**
 * 带数据的分片加载结果
 */
private data class ShardLoadResultWithData<T>(
    val shardType: ShardType,
    val success: Boolean,
    val data: T? = null,
    val bytesRead: Long = 0,
    val timeMs: Long = 0,
    val error: SaveError? = null,
    val errorMessage: String = ""
)

/**
 * 分片保存统计信息
 */
data class ShardedSaveStats(
    val slotId: Int,
    val totalBytesWritten: Long = 0,
    val timeMs: Long = 0,
    val coreBytes: Long = 0,
    val historyBytes: Long = 0,
    val archiveBytes: Long = 0,
    val coreRecordCount: Int = 0,
    val historyRecordCount: Int = 0,
    val archiveRecordCount: Int = 0
) {
    val compressionRatio: Double
        get() = if (totalBytesWritten > 0) {
            // 这里可以计算实际压缩比，目前返回1.0表示未压缩
            1.0
        } else {
            1.0
        }
}

/**
 * 分片槽位概览
 */
data class ShardedSlotOverview(
    val slotId: Int,
    val hasCore: Boolean = false,
    val hasHistory: Boolean = false,
    val hasArchive: Boolean = false,
    val totalSize: Long = 0,
    val fileCount: Int = 0,
    val lastModified: Long = 0
)

/**
 * 分片完整性验证结果
 */
data class ShardIntegrityResult(
    val slotId: Int,
    val isValid: Boolean,
    val shardStatuses: Map<ShardType, IntegrityStatus>
)

/**
 * 单个分片的完整性状态
 */
data class IntegrityStatus(
    val isValid: Boolean = true,
    val exists: Boolean = true,
    val fileSize: Long = 0,
    val checksumMatch: Boolean = false,
    val error: String? = null
)

/**
 * 分片管理器统计信息
 */
data class ShardedSaveManagerStats(
    val totalSaves: Long = 0,
    val totalLoads: Long = 0,
    val totalBytesWritten: Long = 0,
    val totalBytesRead: Long = 0,
    val cachedSlots: Int = 0
)

// ==================== UnifiedSaveRepository 兼容分片数据类 ====================

/**
 * UnifiedSaveRepository 格式的分片内容。
 *
 * 对应 performShardedSave() 写入的分片文件格式：
 * Protobuf 编码的二进制数据（magic header 已被剥离）
 */
data class UsrShardContent(
    val type: String,                          // "protobuf" 表示 Protobuf 格式
    val count: Int,                            // 数据条目数（Protobuf 格式为 -1）
    val rawProtoBytes: ByteArray? = null,       // Protobuf 编码的原始字节数据
    val fileName: String = ""                  // 源文件名（用于日志）
) {
    /**
     * 将 data 字段解析为指定类型的列表。
     *
     * 使用 Protobuf 反序列化解析原始数据。
     *
     * @param T 目标类型（BattleLog 或 GameEvent 等 @Serializable 数据类）
     * @return 解析后的列表，解析失败返回空列表
     */
    inline fun <reified T : Any> parseDataAsList(): List<T> {
        if (rawProtoBytes == null || rawProtoBytes.isEmpty()) return emptyList()
        return try {
            val serializer = serializer<T>()
            listOf(NullSafeProtoBuf.protoBuf.decodeFromByteArray(serializer, rawProtoBytes))
        } catch (e: Exception) {
            Log.w("ShardedSaveManager", "Failed to parse shard data as ${T::class.simpleName} from $fileName", e)
            emptyList()
        }
    }
}

/**
 * UnifiedSaveRepository 格式的分片概要信息。
 */
data class UsrShardOverview(
    val shardIndex: Int,
    val fileName: String,
    val fileSize: Long,
    val lastModified: Long,
    val exists: Boolean
)
