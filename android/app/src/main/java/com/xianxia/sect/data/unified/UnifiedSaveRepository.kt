package com.xianxia.sect.data.unified

import android.content.Context
import android.util.Log
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.data.archive.DataArchiver
import com.xianxia.sect.data.config.SaveLimitsConfig
import com.xianxia.sect.data.cache.GameDataCacheManager
import com.xianxia.sect.data.concurrent.SlotLockManager
import com.xianxia.sect.data.crypto.IntegrityValidator
import com.xianxia.sect.data.crypto.KeyFileSystemException
import com.xianxia.sect.data.crypto.KeyIntegrityException
import com.xianxia.sect.data.crypto.KeyPermissionException
import com.xianxia.sect.data.crypto.SaveCrypto
import com.xianxia.sect.data.crypto.SecureKeyManager
import com.xianxia.sect.data.incremental.IncrementalStorageManager
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.data.retry.RetryPolicy
import com.xianxia.sect.data.retry.RetryConfig
import com.xianxia.sect.data.retry.RetryResult
import com.xianxia.sect.data.engine.UnifiedStorageEngine
import com.xianxia.sect.data.transaction.RefactoredTransactionalSaveManager
import com.xianxia.sect.data.validation.StorageValidator
import com.xianxia.sect.data.wal.WALProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.DeprecationLevel
import kotlin.ReplaceWith

interface SaveRepository {
    suspend fun save(slot: Int, data: SaveData): SaveResult<SaveOperationStats>
    suspend fun load(slot: Int): SaveResult<SaveData>
    suspend fun delete(slot: Int): SaveResult<Unit>
    suspend fun hasSave(slot: Int): Boolean
    suspend fun getSlotInfo(slot: Int): SlotMetadata?
    fun getSaveSlots(): List<SaveSlot>
    suspend fun createBackup(slot: Int): SaveResult<String>
    suspend fun restoreFromBackup(slot: Int, backupId: String): SaveResult<SaveData>
    suspend fun getBackupVersions(slot: Int): List<BackupInfo>
    suspend fun verifyIntegrity(slot: Int): IntegrityResult
    suspend fun repair(slot: Int): SaveResult<Unit>
    suspend fun getDetailedIntegrityReport(slot: Int): IntegrityReport?
}

data class BackupInfo(
    val id: String,
    val slot: Int,
    val timestamp: Long,
    val size: Long,
    val checksum: String
)

sealed class IntegrityResult {
    data object Valid : IntegrityResult()
    data class Invalid(val errors: List<String>) : IntegrityResult()
    data class Tampered(val reason: String) : IntegrityResult()
}

data class IntegrityReport(
    val slot: Int,
    val isValid: Boolean,
    val dataHash: String,
    val merkleRoot: String,
    val signatureValid: Boolean,
    val hashValid: Boolean,
    val merkleValid: Boolean,
    val errors: List<String> = emptyList()
)

data class RepositoryStats(
    val totalSaves: Long = 0,
    val totalLoads: Long = 0,
    val cacheHitRate: Float = 0f,
    val activeTransactions: Int = 0,
    val storageBytes: Long = 0,
    val storageUsagePercent: Float = 0f
)

class StorageQuotaExceededError(
    message: String,
    val currentUsage: Long,
    val budgetLimit: Long,
    val requestedBytes: Long
) : IllegalStateException(message)

@Serializable
data class SlotMetadata(
    val slot: Int,
    val timestamp: Long,
    val gameYear: Int,
    val gameMonth: Int,
    val sectName: String,
    val discipleCount: Int,
    val spiritStones: Long,
    val fileSize: Long,
    val customName: String = "",
    val checksum: String = "",
    val version: String = "",
    val signedPayload: com.xianxia.sect.data.crypto.SignedPayload? = null,
    val dataHash: String = "",
    val merkleRoot: String = ""
)

/**
 * 分片元数据，描述单个分片文件的信息。
 */
@Serializable
data class ShardMeta(
    val index: Int,
    val type: String,
    val fileName: String,
    val fileSize: Long,
    val checksum: String
)

/**
 * 分片存档事务清单（Shard Manifest）。
 */
@Serializable
data class ShardManifest(
    val slot: Int,
    val coreFileName: String,
    val shards: List<ShardMeta>,
    val status: String,
    val timestamp: Long
)

/**
 * 文件序列化存档系统（已废弃）
 *
 * ⚠️ 废弃说明：
 * 本类为历史遗留的文件序列化存档系统，
 * 已被 [com.xianxia.sect.data.StorageGateway] 取代。
 *
 * 当前保留原因：
 * - 部分高级功能（加密/备份/完整性校验）尚未完全迁移
 * - 需要保证向后兼容性，避免破坏现有功能
 */
@Deprecated(
    message = "Use StorageGateway instead. This class will be removed after migration.",
    level = DeprecationLevel.WARNING,
    replaceWith = ReplaceWith(
        "StorageGateway",
        "com.xianxia.sect.data.StorageGateway"
    )
)
@Singleton
class UnifiedSaveRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: GameDatabase,
    private val cacheManager: GameDataCacheManager,
    private val transactionalSaveManager: RefactoredTransactionalSaveManager,
    private val wal: WALProvider,
    private val lockManager: SlotLockManager,
    private val fileHandler: SaveFileHandler,
    private val backupManager: BackupManager,
    private val serializationHelper: SerializationHelper,
    private val metadataManager: MetadataManager,
    private val saveLimitsConfig: SaveLimitsConfig,
    private val dataArchiver: DataArchiver,
    private val incrementalStorageManager: IncrementalStorageManager
) : SaveRepository {

    companion object {
        private const val TAG = "UnifiedSaveRepository"
        private const val AUTO_SAVE_SLOT = 0
        private const val EMERGENCY_SLOT = -1
        private const val MAGIC_NEW_FORMAT = "XSAV"
        private const val SHARD_FILE_SUFFIX_PATTERN = ".shard_%03d"
        
        /** 分片写入结果 */
        data class ShardWriteResult(
            val file: java.io.File,
            val bytesWritten: Long,
            val meta: ShardMeta
        )
        
        /** 分片内容数据结构 */
        data class ShardContent(
            val type: String,
            val items: List<Any> = emptyList(),
            val isBinary: Boolean = false,
            val rawJson: String? = null
        )
        
        /** 密钥缓存条目 */
        data class CachedKey(
            val key: ByteArray,
            val acquiredAt: Long
        ) {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false
                other as CachedKey
                return key.contentEquals(other.key) && acquiredAt == other.acquiredAt
            }
            
            override fun hashCode(): Int {
                var result = key.contentHashCode()
                result = 31 * result + acquiredAt.hashCode()
                return result
            }
        }
    }

    // ==================== 内部类：分片策略 ====================
    
    /**
     * 分片策略处理器 - 封装所有分片相关逻辑
     *
     * 职责：
     * - 分片保存（performShardedSave）
     * - 分片加载与合并（loadAndMergeShardData）
     * - 分片文件管理（检测、清理、回滚）
     * - 分片序列化/反序列化
     */
    inner class ShardingStrategy {
        
        private val shardJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        
        /**
         * 执行分片降级存档（事务化版本）
         */
        suspend fun performShardedSave(slot: Int, data: SaveData, estimatedSize: Long): SaveResult<SaveOperationStats> {
            return lockManager.withWriteLockLight(slot) {
                val writtenShardFiles = mutableListOf<java.io.File>()
                var shardManifestFile: java.io.File? = null

                try {
                    Log.i(TAG, "Starting sharded save for slot $slot (estimated ${estimatedSize / 1024 / 1024}MB)")

                    val coreData = data.copy(battleLogs = emptyList(), events = emptyList())
                    val saveFile = fileHandler.getSaveFile(slot)

                    // 事务开始：创建 PENDING 状态的 shard manifest
                    val pendingManifest = ShardManifest(
                        slot = slot,
                        coreFileName = saveFile.name,
                        shards = emptyList(),
                        status = "PENDING",
                        timestamp = System.currentTimeMillis()
                    )
                    shardManifestFile = getShardManifestFile(slot)
                    val manifestFile = shardManifestFile
                        ?: throw IllegalStateException("Failed to create shard manifest file for slot $slot")
                    writeShardManifest(manifestFile, pendingManifest)
                    Log.d(TAG, "[$slot] Created PENDING shard manifest")

                    val retryConfig = RetryConfig(
                        maxRetries = 3,
                        initialDelayMs = 100L,
                        maxDelayMs = 5000L,
                        backoffMultiplier = 2.0,
                        jitterFactor = 0.2,
                        retryableErrors = setOf(OutOfMemoryError::class.java, java.io.IOException::class.java)
                    )

                    val result: RetryResult<SaveResult<SaveOperationStats>> = RetryPolicy.executeWithRetry(retryConfig) {
                        // 写入核心存档
                        val coreRawData = serializationHelper.serializeAndCompressSaveData(coreData.copy(timestamp = System.currentTimeMillis()))

                        if (coreRawData.size > saveLimitsConfig.maxSaveSizeBytes) {
                            throw IllegalStateException("Core save data exceeds maximum size even after sharding: ${coreRawData.size} bytes")
                        }

                        val encryptedCore = encryptionHelper.encryptData(coreRawData)

                        val coreSuccess = fileHandler.writeAtomically(
                            targetFile = saveFile,
                            data = encryptedCore,
                            magicHeader = MAGIC_NEW_FORMAT.toByteArray()
                        )

                        if (!coreSuccess) {
                            throw IllegalStateException("Failed to write core save file atomically")
                        }

                        // 写入 BattleLog 分片
                        var totalBytesWritten = encryptedCore.size.toLong()
                        var shardIndex = 0
                        val completedShards = mutableListOf<ShardMeta>()

                        val battleLogShards = splitIntoShards(data.battleLogs, saveLimitsConfig.shardMaxSizeBytes.toInt())
                        for (shard in battleLogShards) {
                            val shardResult = writeSingleShard(slot, shardIndex, "battle_logs", shard, data)
                            writtenShardFiles.add(shardResult.file)
                            totalBytesWritten += shardResult.bytesWritten
                            completedShards.add(shardResult.meta)
                            persistUpdatedManifest(manifestFile, pendingManifest, completedShards)
                            shardIndex++
                        }

                        // 写入 Event 分片
                        val eventShards = splitIntoShards(data.events, saveLimitsConfig.shardMaxSizeBytes.toInt())
                        for (shard in eventShards) {
                            val shardResult = writeSingleShard(slot, shardIndex, "events", shard, data)
                            writtenShardFiles.add(shardResult.file)
                            totalBytesWritten += shardResult.bytesWritten
                            completedShards.add(shardResult.meta)
                            persistUpdatedManifest(manifestFile, pendingManifest, completedShards)
                            shardIndex++
                        }

                        // 事务提交：将 manifest 状态改为 COMMITTED
                        commitShardTransaction(manifestFile, slot, saveFile, completedShards)

                        // 清理旧分片文件
                        cleanupOldShardFiles(slot, shardIndex)

                        metadataManager.updateSlotMetadata(slot, data, totalBytesWritten)
                        updateCacheAfterSave(slot, data)

                        Log.i(TAG, "Sharded save completed for slot $slot: $totalBytesWritten bytes across $shardIndex shards")
                        SaveResult.success(SaveOperationStats(
                            bytesWritten = totalBytesWritten,
                            timeMs = 0,
                            wasEncrypted = true
                        ))
                    }

                    when (result) {
                        is RetryResult.Success -> result.data
                        is RetryResult.Failure -> {
                            Log.e(TAG, "[$slot] Sharded save failed after ${result.totalAttempts} attempts, rolling back")
                            rollbackShardedSave(slot, shardManifestFile, writtenShardFiles)
                            SaveResult.failure(SaveError.SAVE_FAILED,
                                "Sharded save failed after ${result.totalAttempts} attempts: ${result.lastError?.message}", result.lastError)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[$slot] Sharded save error, rolling back", e)
                    rollbackShardedSave(slot, shardManifestFile, writtenShardFiles)
                    SaveResult.failure(SaveError.SAVE_FAILED, e.message ?: "Unknown error during sharded save", e)
                }
            }
        }
        
        /**
         * 写入单个分片的统一方法
         */
        private suspend fun writeSingleShard(
            slot: Int,
            shardIndex: Int,
            type: String,
            items: List<Any>,
            originalData: SaveData
        ): ShardWriteResult {
            val shardFile = getShardFile(slot, shardIndex)
            val shardData = serializeShard(type, items)
            val encryptedShard = encryptionHelper.encryptData(shardData)
            val shardChecksum = encryptionHelper.computeFullDataSignature(originalData)

            fileHandler.writeAtomically(shardFile, encryptedShard, "SHRD".toByteArray())

            return ShardWriteResult(
                file = shardFile,
                bytesWritten = encryptedShard.size.toLong(),
                meta = ShardMeta(
                    index = shardIndex,
                    type = type,
                    fileName = shardFile.name,
                    fileSize = encryptedShard.size.toLong(),
                    checksum = shardChecksum
                )
            )
        }
        
        /**
         * 持久化更新后的manifest
         */
        private fun persistUpdatedManifest(
            manifestFile: java.io.File,
            pendingManifest: ShardManifest,
            completedShards: MutableList<ShardMeta>
        ) {
            val updatedManifest = pendingManifest.copy(shards = completedShards.toList())
            writeShardManifest(manifestFile, updatedManifest)
        }
        
        /**
         * 提交分片事务
         */
        private fun commitShardTransaction(
            manifestFile: java.io.File,
            slot: Int,
            saveFile: java.io.File,
            completedShards: List<ShardMeta>
        ) {
            val committedManifest = ShardManifest(
                slot = slot,
                coreFileName = saveFile.name,
                shards = completedShards.toList(),
                status = "COMMITTED",
                timestamp = System.currentTimeMillis()
            )
            writeShardManifest(manifestFile, committedManifest)
            Log.i(TAG, "[$slot] Shard transaction COMMITTED: ${completedShards.size} shards")
        }
        
        /**
         * 加载并合并分片数据到主存档
         */
        suspend fun loadAndMergeShardData(slot: Int, mainData: SaveData): SaveData {
            val shardFiles = detectShardFiles(slot)
            if (shardFiles.isEmpty()) {
                return mainData
            }

            // 验证 shard manifest 状态
            val manifest = readShardManifest(slot)
            if (!validateManifestStatus(slot, manifest, shardFiles)) {
                return mainData
            }

            Log.i(TAG, "Detected ${shardFiles.size} shard files for slot $slot, loading and merging")

            val mergedBattleLogs = mutableListOf<BattleLog>()
            val mergedEvents = mutableListOf<GameEvent>()
            var loadedShardCount = 0
            var failedShardCount = 0

            for ((shardIndex, shardFile) in shardFiles) {
                try {
                    val shardContent = loadSingleShardFile(shardFile)
                    if (shardContent != null) {
                        mergeShardContent(shardContent, shardIndex, mergedBattleLogs, mergedEvents)
                        loadedShardCount++
                    } else {
                        Log.w(TAG, "Failed to parse shard content from ${shardFile.name} for slot $slot")
                        failedShardCount++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load shard file ${shardFile.name} for slot $slot", e)
                    failedShardCount++
                }
            }

            if (failedShardCount > 0) {
                Log.w(TAG, "$failedShardCount of ${shardFiles.size} shards failed to load for slot $slot")
            }

            Log.i(TAG, "Shard merge completed for slot $slot: loaded $loadedShardCount shards, " +
                    "${mergedBattleLogs.size} battle logs, ${mergedEvents.size} events")

            return mainData.copy(
                battleLogs = mergeListDedupSorted(mainData.battleLogs, mergedBattleLogs),
                events = mergeListDedupSorted(mainData.events, mergedEvents)
            )
        }
        
        /**
         * 验证manifest状态，返回是否应该继续加载
         */
        private fun validateManifestStatus(
            slot: Int,
            manifest: ShardManifest?,
            shardFiles: List<Pair<Int, java.io.File>>
        ): Boolean {
            if (manifest == null) return true
            
            return when (manifest.status) {
                "ABORTED" -> {
                    Log.w(TAG, "[$slot] Shard manifest status is ABORTED, ignoring all shards")
                    cleanupAbortedShards(slot, shardFiles.map { it.second })
                    false
                }
                "PENDING" -> {
                    Log.w(TAG, "[$slot] Shard manifest status is PENDING, attempting to load with caution")
                    true
                }
                "COMMITTED" -> {
                    Log.d(TAG, "[$slot] Shard manifest status is COMMITTED, proceeding")
                    true
                }
                else -> {
                    Log.d(TAG, "[$slot] Unknown manifest status '${manifest.status}', loading anyway")
                    true
                }
            }
        }
        
        /**
         * 合并单个分片内容到对应的列表
         */
        private fun mergeShardContent(
            content: ShardContent,
            shardIndex: Int,
            battleLogs: MutableList<BattleLog>,
            events: MutableList<GameEvent>
        ) {
            when (content.type) {
                "battle_logs" -> {
                    val parsed = parseShardItemsAs<BattleLog>(content)
                    if (parsed.isNotEmpty()) {
                        battleLogs.addAll(parsed)
                    } else {
                        Log.w(TAG, "Shard $shardIndex: battle_logs items could not be deserialized")
                    }
                }
                "events" -> {
                    val parsed = parseShardItemsAs<GameEvent>(content)
                    if (parsed.isNotEmpty()) {
                        events.addAll(parsed)
                    } else {
                        Log.w(TAG, "Shard $shardIndex: events items could not be deserialized")
                    }
                }
                else -> {
                    Log.w(TAG, "Unknown shard type '${content.type}' in shard $shardIndex")
                }
            }
        }
        
        /**
         * 合并列表：去重 + 按时间戳降序排序
         */
        private inline fun <reified T> mergeListDedupSorted(
            original: List<T>,
            merged: List<T>
        ): List<T> where T : Any {
            if (merged.isEmpty()) return original
            return (original + merged).distinctBy { item ->
                when (item) {
                    is BattleLog -> item.id
                    is GameEvent -> item.id
                    else -> item.hashCode()
                }
            }.sortedByDescending { item ->
                when (item) {
                    is BattleLog -> item.timestamp
                    is GameEvent -> item.timestamp
                    else -> 0L
                }
            }
        }
        
        /**
         * 将列表按大小限制拆分为多个分片
         */
        private fun <T> splitIntoShards(items: List<T>, maxBytesPerShard: Int): List<List<T>> {
            if (items.isEmpty()) return emptyList()

            val shards = mutableListOf<List<T>>()
            var currentShard = mutableListOf<T>()
            var currentSize = 0

            for (item in items) {
                val itemSize = estimateShardItemSize(item as Any)
                if (currentSize + itemSize > maxBytesPerShard && currentShard.isNotEmpty()) {
                    shards.add(currentShard)
                    currentShard = mutableListOf()
                    currentSize = 0
                }
                currentShard.add(item)
                currentSize += itemSize
            }

            if (currentShard.isNotEmpty()) {
                shards.add(currentShard)
            }

            return shards
        }
        
        /**
         * 序列化分片数据（使用 Protobuf 二进制格式）
         */
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        private inline fun <reified T> serializeShard(type: String, items: List<T>): ByteArray {
            val header = "${type}|${items.size}|".toByteArray(Charsets.UTF_8)
            try {
                val dataBytes = ProtoBuf.encodeToByteArray(
                    kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<T>()),
                    items
                )
                return header + dataBytes
            } catch (e: Exception) {
                Log.w(TAG, "Protobuf shard serialization failed for type=$type, falling back to JSON", e)
                val jsonStr = shardJson.encodeToString(items)
                val json = StringBuilder()
                json.append("{\"type\":\"$type\",\"count\":${items.size},\"data\":")
                json.append(jsonStr)
                json.append("}")
                return json.toString().toByteArray(Charsets.UTF_8)
            }
        }
        
        /**
         * 解析分片项为指定类型（Phase 2修复：直接ProtoBuf反序列化，移除JSON round-trip）
         */
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        private inline fun <reified T> parseShardItemsAs(content: ShardContent): List<T> {
            return try {
                if (content.isBinary && content.items.isNotEmpty()) {
                    val rawItem = content.items.first()
                    val dataBytes = when (rawItem) {
                        is ByteArray -> rawItem
                        is String -> rawItem.toByteArray(Charsets.UTF_8)
                        else -> throw kotlinx.serialization.SerializationException(
                            "Unexpected shard item type: ${rawItem::class.simpleName}"
                        )
                    }
                    // Phase 2 FIX: 直接使用 ProtoBuf 反序列化，而非 JSON round-trip
                    ProtoBuf.decodeFromByteArray(
                        kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<T>()),
                        dataBytes
                    )
                } else if (content.rawJson != null) {
                    shardJson.decodeFromString<List<T>>(content.rawJson)
                } else if (content.items.isNotEmpty()) {
                    // 最终回退：仅当无法使用 Protobuf 时才使用 JSON
                    val jsonStr = shardJson.encodeToString(content.items)
                    shardJson.decodeFromString<List<T>>(jsonStr)
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to deserialize shard items as ${T::class.simpleName}", e)
                emptyList()
            }
        }
        
        /**
         * 检测指定槽位的所有分片文件
         */
        fun detectShardFiles(slot: Int): List<Pair<Int, java.io.File>> {
            val saveFile = fileHandler.getSaveFile(slot)
            val parentDir = saveFile.parentFile ?: return emptyList()

            val shardPattern = Regex("""${Regex.escape(saveFile.name)}\.shard_(\d+)$""")

            return try {
                parentDir.listFiles()
                    ?.mapNotNull { file ->
                        shardPattern.find(file.name)?.let { matchResult ->
                            val index = matchResult.groupValues[1].toIntOrNull()
                            if (index != null) index to file else null
                        }
                    }
                    ?.sortedBy { it.first }
                    ?: emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "Error detecting shard files for slot $slot", e)
                emptyList()
            }
        }
        
        /**
         * 加载并解析单个分片文件
         */
        suspend fun loadSingleShardFile(shardFile: java.io.File): ShardContent? {
            if (!shardFile.exists()) return null

            val SHARD_MAGIC = "SHRD"
            val fileData = try {
                shardFile.readBytes()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read shard file ${shardFile.name}", e)
                return null
            }

            if (fileData.size < SHARD_MAGIC.length ||
                String(fileData, 0, SHARD_MAGIC.length) != SHARD_MAGIC) {
                Log.w(TAG, "Invalid magic header in shard file ${shardFile.name}")
                return null
            }

            val encryptedData = fileData.copyOfRange(SHARD_MAGIC.length, fileData.size)

            val rawData = try {
                encryptionHelper.decryptData(encryptedData)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decrypt shard file ${shardFile.name}", e)
                return null
            } ?: run {
                Log.w(TAG, "Decryption returned null for shard file ${shardFile.name}")
                return null
            }

            return try {
                val rawStr = String(rawData, Charsets.UTF_8)
                if (rawStr.startsWith("{") && rawStr.contains("\"type\"")) {
                    parseJsonShard(rawStr, shardFile.name)
                } else {
                    parseProtobufShard(rawData, shardFile.name)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse shard content from file ${shardFile.name}", e)
                null
            }
        }
        
        private fun parseProtobufShard(rawData: ByteArray, fileName: String): ShardContent? {
            try {
                val headerEnd = rawData.indexOfFirst { it == '|'.code.toByte() }
                    .takeIf { it >= 0 } ?: return null
                val countEnd = rawData.copyOfRange(headerEnd + 1, rawData.size)
                    .indexOfFirst { it == '|'.code.toByte() }
                    .let { if (it >= 0) it + headerEnd + 1 else -1 }
                    .takeIf { it > headerEnd } ?: return null
                val type = String(rawData, 0, headerEnd, Charsets.UTF_8)
                val dataBytes = rawData.copyOfRange(countEnd + 1, rawData.size)
                return ShardContent(type = type, items = listOf(dataBytes), isBinary = true)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse protobuf shard from $fileName", e)
                return null
            }
        }
        
        private fun parseJsonShard(jsonStr: String, fileName: String): ShardContent? {
            return try {
                val jsonObj = shardJson.parseToJsonElement(jsonStr) as? JsonObject ?: return null
                val type = jsonObj["type"]?.jsonPrimitive?.contentOrNull ?: ""
                val dataElement = jsonObj["data"]
                val rawJsonData = if (dataElement != null) shardJson.encodeToString(dataElement) else null
                val items: List<Any> = when {
                    dataElement is JsonArray -> emptyList()
                    else -> emptyList()
                }
                ShardContent(type = type, items = items, isBinary = false, rawJson = rawJsonData)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse JSON shard content from $fileName", e)
                null
            }
        }
        
        // ====== 分片文件管理方法 ======
        
        fun getShardFile(slot: Int, shardIndex: Int): java.io.File {
            val saveFile = fileHandler.getSaveFile(slot)
            val shardName = saveFile.name + String.format(SHARD_FILE_SUFFIX_PATTERN, shardIndex)
            return java.io.File(saveFile.parentFile, shardName)
        }
        
        fun getShardManifestFile(slot: Int): java.io.File {
            val saveFile = fileHandler.getSaveFile(slot)
            return java.io.File(saveFile.parentFile, "${saveFile.name}.shard_manifest.json")
        }
        
        private fun writeShardManifest(file: java.io.File, manifest: ShardManifest) {
            try {
                file.parentFile?.mkdirs()
                val tempFile = java.io.File(file.parent, "${file.name}.tmp_${System.currentTimeMillis()}")
                try {
                    tempFile.writeText(shardJson.encodeToString(manifest))
                    if (!tempFile.renameTo(file)) {
                        file.writeText(shardJson.encodeToString(manifest))
                    }
                } finally {
                    if (tempFile.exists()) tempFile.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write shard manifest: ${e.message}")
            }
        }
        
        fun readShardManifest(slot: Int): ShardManifest? {
            return try {
                val file = getShardManifestFile(slot)
                if (!file.exists()) return null
                shardJson.decodeFromString<ShardManifest>(file.readText())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read shard manifest for slot $slot: ${e.message}")
                null
            }
        }
        
        fun rollbackShardedSave(
            slot: Int,
            manifestFile: java.io.File?,
            writtenShardFiles: List<java.io.File>
        ) {
            try {
                manifestFile?.let { file ->
                    val abortedManifest = ShardManifest(
                        slot = slot,
                        coreFileName = "",
                        shards = emptyList(),
                        status = "ABORTED",
                        timestamp = System.currentTimeMillis()
                    )
                    writeShardManifest(file, abortedManifest)
                    Log.w(TAG, "[$slot] Shard manifest marked as ABORTED")
                }

                var deletedCount = 0
                for (shardFile in writtenShardFiles) {
                    try {
                        if (shardFile.exists() && shardFile.delete()) {
                            deletedCount++
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "[$slot] Failed to delete shard file during rollback: ${shardFile.name}", e)
                    }
                }

                Log.w(TAG, "[$slot] Sharded save rolled back: deleted $deletedCount/${writtenShardFiles.size} shard files")
            } catch (e: Exception) {
                Log.e(TAG, "[$slot] Error during sharded save rollback", e)
            }
        }
        
        private fun cleanupOldShardFiles(slot: Int, currentShardCount: Int) {
            try {
                val saveFile = fileHandler.getSaveFile(slot)
                val parentDir = saveFile.parentFile ?: return

                parentDir.listFiles()?.filter { file ->
                    file.name.startsWith(saveFile.name) &&
                    file.name.contains(".shard_") &&
                    Regex("""\.shard_(\d+)""").find(file.name)?.groupValues?.get(1)?.toIntOrNull()
                        ?.let { it >= currentShardCount } == true
                }?.forEach { file ->
                    if (file.delete()) {
                        Log.d(TAG, "Cleaned old shard file: ${file.name}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cleanup old shard files for slot $slot", e)
            }
        }
        
        private fun cleanupAbortedShards(slot: Int, shardFiles: List<java.io.File>) {
            try {
                var cleanedCount = 0
                for (shardFile in shardFiles) {
                    if (shardFile.exists() && shardFile.delete()) {
                        cleanedCount++
                    }
                }
                val manifestFile = getShardManifestFile(slot)
                if (manifestFile.exists()) {
                    manifestFile.delete()
                }
                Log.i(TAG, "[$slot] Cleaned up $cleanedCount aborted shard files and manifest")
            } catch (e: Exception) {
                Log.w(TAG, "[$slot] Failed to cleanup aborted shards", e)
            }
        }
        
        /**
         * 估算单个分片项的字节大小
         */
        @Suppress("DEPRECATION")
        fun estimateShardItemSize(item: Any): Int {
            return when (item) {
                is ByteArray -> item.size
                is String -> item.toByteArray(Charsets.UTF_8).size
                is Int -> 4
                is Long -> 8
                is Double -> 8
                is Float -> 4
                is Boolean -> 1
                is Short -> 2
                is Byte -> 1
                is BattleLog -> 600
                is GameEvent -> 250
                is Disciple -> 800
                is Equipment -> 350
                is Manual -> 280
                is Pill -> 180
                is Material -> 150
                is Herb -> 160
                is Seed -> 140
                is ExplorationTeam -> 450
                is Alliance -> 300
                is ProductionSlot -> 200
                is List<*> -> item.sumOf { estimateShardItemSize(it!!) } + 16
                is Map<*, *> -> item.entries.sumOf { 
                    (estimateShardItemSize(it.key!!) + estimateShardItemSize(it.value!!)) 
                } + 32
                else -> try {
                    shardJson.encodeToString(item).toByteArray(Charsets.UTF_8).size
                } catch (e: Exception) {
                    item.toString().toByteArray(Charsets.UTF_8).size
                }
            }
        }
        
    }

    // ==================== 内部类：加密助手 ====================
    
    /**
     * 加密助手 - 封装加密/解密操作，含密钥缓存
     *
     * Phase 3 优化：
     * - 添加短期密钥缓存（TTL=30s）
     * - 批量加密时复用同一密钥实例
     * - 统一错误处理和日志记录
     */
    inner class EncryptionHelper {
        
        /** 密钥缓存（TTL=30秒） */
        @Volatile
        private var cachedKeyEntry: CachedKey? = null
        private val KEY_CACHE_TTL_MS = 30_000L
        private val keyMutex = kotlinx.coroutines.sync.Mutex()
        
        private suspend fun acquireKey(): ByteArray {
            // 快速路径：检查缓存是否有效（无锁）
            val cached = cachedKeyEntry
            if (cached != null && (System.currentTimeMillis() - cached.acquiredAt) < KEY_CACHE_TTL_MS) {
                Log.d(TAG, "EncryptionHelper: Using cached key (${cached.key.size} bytes, age=${System.currentTimeMillis() - cached.acquiredAt}ms)")
                return cached.key
            }
            
            // 慢速路径：加锁获取新密钥
            return keyMutex.withLock {
                // 双重检查：在获取锁后再次检查缓存
                val doubleChecked = cachedKeyEntry
                if (doubleChecked != null && (System.currentTimeMillis() - doubleChecked.acquiredAt) < KEY_CACHE_TTL_MS) {
                    return@withLock doubleChecked.key
                }
                
                // 缓存失效或不存在，重新获取
                Log.d(TAG, "EncryptionHelper: Acquiring new encryption key...")
                val key = try {
                    SecureKeyManager.getOrCreateKey(context)
                } catch (keyEx: KeyIntegrityException) {
                    Log.e(TAG, "EncryptionHelper: Key integrity error", keyEx)
                    throw IllegalStateException("Encryption failed: Key integrity check failed. Error: ${keyEx.message}", keyEx)
                } catch (permEx: KeyPermissionException) {
                    Log.e(TAG, "EncryptionHelper: Key permission denied", permEx)
                    throw IllegalStateException("Encryption failed: Permission denied. Error: ${permEx.message}", permEx)
                } catch (fsEx: KeyFileSystemException) {
                    Log.e(TAG, "EncryptionHelper: File system error during key access", fsEx)
                    throw IllegalStateException("Encryption failed: Cannot access key storage. Error: ${fsEx.message}", fsEx)
                } catch (ex: Exception) {
                    Log.e(TAG, "EncryptionHelper: Unexpected error during key acquisition", ex)
                    throw IllegalStateException("Encryption failed: Unable to obtain encryption key. Error: ${ex.message}", ex)
                }

                if (key.isEmpty()) {
                    Log.e(TAG, "EncryptionHelper: Key is null or empty after acquisition")
                    throw IllegalStateException("Encryption failed: Obtained key is null or empty")
                }
                
                // 更新缓存
                cachedKeyEntry = CachedKey(key, System.currentTimeMillis())
                Log.d(TAG, "EncryptionHelper: Key acquired and cached (${key.size} bytes)")
                
                key
            }
        }
        
        /**
         * 加密数据（含密钥缓存）
         */
        suspend fun encryptData(data: ByteArray): ByteArray {
            val key = acquireKey()

            return try {
                val encrypted = SaveCrypto.encryptAsync(data, key)

                if (encrypted.isEmpty()) {
                    Log.e(TAG, "EncryptionHelper: Encryption returned empty result")
                    throw IllegalStateException("Encryption failed: Encryptor returned empty result")
                }

                Log.d(TAG, "EncryptionHelper: Encryption completed (${data.size} -> ${encrypted.size} bytes)")
                encrypted
            } catch (oomEx: OutOfMemoryError) {
                Log.e(TAG, "EncryptionHelper: OOM during encryption - data size: ${data.size} bytes", oomEx)
                throw OutOfMemoryError("Encryption OOM: Input size ${data.size} bytes exceeds available memory").apply { initCause(oomEx) }
            } catch (cryptoEx: javax.crypto.BadPaddingException) {
                Log.e(TAG, "EncryptionHelper: Bad padding error", cryptoEx)
                throw IllegalStateException("Encryption failed: Bad padding", cryptoEx)
            } catch (cryptoEx: javax.crypto.IllegalBlockSizeException) {
                Log.e(TAG, "EncryptionHelper: Illegal block size", cryptoEx)
                throw IllegalStateException("Encryption failed: Illegal block size", cryptoEx)
            } catch (cryptoEx: java.security.InvalidKeyException) {
                Log.e(TAG, "EncryptionHelper: Invalid key", cryptoEx)
                throw IllegalStateException("Encryption failed: Invalid key format", cryptoEx)
            } catch (ex: Exception) {
                Log.e(TAG, "EncryptionHelper: Unexpected encryption error", ex)
                throw IllegalStateException("Encryption failed: ${ex.javaClass.simpleName} - ${ex.message}", ex)
            }
        }
        
        /**
         * 解密数据（复用密钥缓存）
         */
        suspend fun decryptData(data: ByteArray): ByteArray? {
            val key = acquireKey()
            return SaveCrypto.decryptAsync(data, key)
        }
        
        /**
         * 计算完整数据签名（协程安全版本）
         *
         * 注意：此方法已从普通函数改为 suspend 函数以避免 runBlocking 死锁风险。
         * 原因：acquireKey() 是 suspend 函数，在协程上下文中调用 runBlocking 会导致死锁。
         */
        suspend fun computeFullDataSignature(data: SaveData): String {
            val key = acquireKey()
            return IntegrityValidator.computeFullDataSignature(data, key)
        }
        
        /**
         * 清除密钥缓存（在密钥可能失效时调用）
         */
        fun invalidateKeyCache() {
            cachedKeyEntry = null
            Log.d(TAG, "EncryptionHelper: Key cache invalidated")
        }
    }
    
    // ==================== 内部类：槽位查询引擎 ====================
    
    /**
     * 槽位查询引擎 - 处理 getSaveSlots 的复杂查询逻辑
     *
     * 原问题：
     * - getSaveSlots() 约150行，6层 if-else 嵌套
     * - 圈复杂度极高，难以维护和测试
     *
     * 解决方案：
     * - 将每个条件分支提取为独立方法
     * - 使用策略模式处理不同状态组合
     * - 降低主方法的圈复杂度
     */
    inner class SlotQueryEngine {
        
        /**
         * 获取所有存档槽位信息
         */
        fun getSaveSlots(): List<SaveSlot> {
            return try {
                (1..lockManager.getMaxSlots()).map { slot ->
                    querySingleSlot(slot)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in getSaveSlots, returning empty slots", e)
                (1..lockManager.getMaxSlots()).map { slot ->
                    SaveSlot(slot, "", 0, 1, 1, "", 0, 0, true)
                }
            }
        }
        
        /**
         * 查询单个槽位的状态信息（降低圈复杂度的核心方法）
         */
        private fun querySingleSlot(slot: Int): SaveSlot {
            val meta = metadataManager.getFromCache(slot)
            val saveFile = fileHandler.getSaveFile(slot)
            val fullExists = saveFile.exists()
            val incrementalExists = runCatching { incrementalStorageManager.hasSave(slot) }.getOrDefault(false)
            val hasData = fullExists || incrementalExists || meta != null

            return when {
                !hasData -> createEmptySlot(slot)
                fullExists && meta != null -> createSlotFromFullAndMeta(slot, meta)
                incrementalExists -> createSlotFromIncremental(slot)
                fullExists -> createSlotFromFullOnly(slot, saveFile, meta)
                meta != null -> handleStaleCache(slot)
                else -> createEmptySlot(slot) // 兜底
            }
        }
        
        private fun createEmptySlot(slot: Int): SaveSlot {
            return SaveSlot(slot, "", 0, 1, 1, "", 0, 0, true)
        }
        
        private fun createSlotFromFullAndMeta(slot: Int, meta: SlotMetadata): SaveSlot {
            return SaveSlot(
                slot = slot,
                name = "Save $slot",
                timestamp = meta.timestamp,
                gameYear = meta.gameYear,
                gameMonth = meta.gameMonth,
                sectName = meta.sectName,
                discipleCount = meta.discipleCount,
                spiritStones = meta.spiritStones,
                isEmpty = false,
                customName = meta.sectName
            )
        }
        
        private fun createSlotFromIncremental(slot: Int): SaveSlot {
            val incInfo = runCatching { incrementalStorageManager.getSlotInfo(slot) }.getOrNull()
            return if (incInfo != null) {
                SaveSlot(
                    slot = slot,
                    name = "Save $slot",
                    timestamp = incInfo.timestamp,
                    gameYear = incInfo.gameYear,
                    gameMonth = incInfo.gameMonth,
                    sectName = incInfo.sectName,
                    discipleCount = incInfo.discipleCount,
                    spiritStones = incInfo.spiritStones,
                    isEmpty = false,
                    customName = incInfo.sectName.ifEmpty { "存档 $slot" }
                )
            } else {
                Log.w(TAG, "getSaveSlots[$slot]: Incremental exists but no metadata available")
                SaveSlot(
                    slot = slot,
                    name = "Save $slot",
                    timestamp = System.currentTimeMillis(),
                    gameYear = 1,
                    gameMonth = 1,
                    sectName = "存档 $slot",
                    discipleCount = 0,
                    spiritStones = 0,
                    isEmpty = false,
                    customName = ""
                )
            }
        }
        
        private fun createSlotFromFullOnly(
            slot: Int,
            saveFile: java.io.File,
            meta: SlotMetadata?
        ): SaveSlot {
            Log.d(TAG, "getSaveSlots[$slot]: File exists but no cached metadata, attempting to load from disk")
            return try {
                val loadedMeta = metadataManager.loadSlotMetadata(slot)
                if (loadedMeta != null) {
                    Log.d(TAG, "getSaveSlots[$slot]: Successfully loaded metadata from disk")
                    createSlotFromFullAndMeta(slot, loadedMeta)
                } else {
                    createDegradedSlotFromFile(slot, saveFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "getSaveSlots[$slot]: Error loading metadata from disk", e)
                createCorruptedSlot(slot, saveFile)
            }
        }
        
        private fun createDegradedSlotFromFile(slot: Int, saveFile: java.io.File): SaveSlot {
            val fileSize = saveFile.length()
            val lastModified = saveFile.lastModified()
            Log.w(TAG, "getSaveSlots[$slot]: File exists ($fileSize bytes) but no metadata found, showing degraded info")

            return if (fileSize < MAGIC_NEW_FORMAT.length) {
                Log.e(TAG, "getSaveSlots[$slot]: File size ($fileSize bytes) is smaller than magic header length, file may be corrupted")
                SaveSlot(
                    slot = slot,
                    name = "Save $slot",
                    timestamp = lastModified,
                    gameYear = 1,
                    gameMonth = 1,
                    sectName = "损坏的存档 $slot",
                    discipleCount = 0,
                    spiritStones = 0,
                    isEmpty = false,
                    customName = ""
                )
            } else {
                SaveSlot(
                    slot = slot,
                    name = "Save $slot",
                    timestamp = lastModified,
                    gameYear = 1,
                    gameMonth = 1,
                    sectName = "存档 $slot (${fileSize / 1024}KB)",
                    discipleCount = 0,
                    spiritStones = 0,
                    isEmpty = false,
                    customName = ""
                )
            }
        }
        
        private fun createCorruptedSlot(slot: Int, saveFile: java.io.File): SaveSlot {
            return SaveSlot(
                slot = slot,
                name = "Save $slot",
                timestamp = saveFile.lastModified(),
                gameYear = 1,
                gameMonth = 1,
                sectName = "存档 $slot (需修复)",
                discipleCount = 0,
                spiritStones = 0,
                isEmpty = false,
                customName = ""
            )
        }
        
        private fun handleStaleCache(slot: Int): SaveSlot {
            Log.w(TAG, "getSaveSlots[$slot]: Only cached metadata exists but file missing, clearing stale cache")
            metadataManager.removeFromCache(slot)
            return createEmptySlot(slot)
        }
    }

    // ==================== 主类实例变量和方法 ====================
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var emergencyTxnId: AtomicLong = AtomicLong(0)
    private val migratingSlots = ConcurrentHashMap<Int, Long>()

    private val saveCount = AtomicLong(0)
    private val loadCount = AtomicLong(0)
    private val cacheHits = AtomicLong(0)
    private val cacheMisses = AtomicLong(0)
    
    private val _currentSlot = MutableStateFlow(1)
    val currentSlot: StateFlow<Int> = _currentSlot.asStateFlow()
    
    // 内部类实例（延迟初始化以避免循环依赖）
    private val shardingStrategy by lazy { ShardingStrategy() }
    private val encryptionHelper by lazy { EncryptionHelper() }
    private val slotQueryEngine by lazy { SlotQueryEngine() }
    
    init {
        metadataManager.loadAllMetadata(lockManager.getMaxSlots())
    }
    
    override suspend fun save(slot: Int, data: SaveData): SaveResult<SaveOperationStats> = withContext(Dispatchers.IO) {
        if (!lockManager.isValidSlot(slot)) {
            return@withContext SaveResult.failure(SaveError.INVALID_SLOT, "Invalid slot: $slot")
        }
        
        val cleanedData = cleanSaveDataWithArchive(data)
        val estimatedSize = estimateSaveDataSize(cleanedData)
        
        // 检查是否需要分片降级
        if (estimatedSize > saveLimitsConfig.shardingThresholdBytes) {
            Log.i(TAG, "Estimated size ${estimatedSize / 1024 / 1024}MB exceeds threshold, attempting sharded save")
            return@withContext shardingStrategy.performShardedSave(slot, cleanedData, estimatedSize)
        }
        
        // 内存和存储配额检查
        if (!checkMemoryAndStorageQuota(estimatedSize)) {
            return@withContext SaveResult.failure(SaveError.OUT_OF_MEMORY, "Insufficient memory or storage quota exceeded")
        }

        saveCount.incrementAndGet()
        
        return@withContext lockManager.withWriteLockLight(slot) {
            // 优先尝试增量保存
            val incrementalResult = attemptIncrementalSave(slot, cleanedData)
            if (incrementalResult != null && incrementalResult.isSuccess) {
                return@withWriteLockLight incrementalResult
            }

            // 回退到全量保存
            performFullSave(slot, cleanedData)
        }
    }
    
    /**
     * 检查内存和存储配额
     */
    private suspend fun checkMemoryAndStorageQuota(estimatedSize: Long): Boolean {
        if (!fileHandler.checkAvailableMemory()) {
            fileHandler.forceGcAndWait()
            if (!fileHandler.checkAvailableMemory()) {
                Log.e(TAG, "Insufficient memory for save operation")
                return false
            }
        }

        if (!fileHandler.checkTotalStorageBudget(estimatedSize)) {
            val result = fileHandler.enforceStorageQuota(estimatedSize, setOf())
            if (!result.success && !fileHandler.checkTotalStorageBudget(estimatedSize)) {
                val currentUsage = fileHandler.calculateTotalStorageBytes()
                throw StorageQuotaExceededError(
                    "Storage quota exceeded: current=${currentUsage}, limit=${SaveFileHandler.TOTAL_STORAGE_BUDGET_BYTES}, requested=$estimatedSize",
                    currentUsage,
                    SaveFileHandler.TOTAL_STORAGE_BUDGET_BYTES,
                    estimatedSize
                )
            }
            if (result.deletedFiles.isNotEmpty()) {
                Log.w(TAG, "Freed ${result.freedBytes} bytes by deleting ${result.deletedFiles.size} old files")
            }
        }
        return true
    }
    
    /**
     * 尝试增量保存路径
     */
    private suspend fun attemptIncrementalSave(slot: Int, data: SaveData): SaveResult<SaveOperationStats>? {
        return try {
            Log.d(TAG, "save[$slot] Attempting INCREMENTAL path...")
            val incResult = incrementalStorageManager.saveIncremental(slot, data.copy(timestamp = System.currentTimeMillis()))
            if (incResult.success) {
                Log.i(TAG, "save[$slot] INCREMENTAL path success: deltaCount=${incResult.deltaCount}")
                updateCacheAfterSave(slot, data)
                SaveResult.success(SaveOperationStats(
                    bytesWritten = incResult.savedBytes,
                    timeMs = incResult.elapsedMs,
                    wasEncrypted = true
                ))
            } else {
                Log.w(TAG, "save[$slot] INCREMENTAL path failed: ${incResult.error}, fallback to FULL")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "save[$slot] INCREMENTAL path exception, fallback to FULL: ${e.message}", e)
            null
        }
    }
    
    /**
     * 执行全量保存路径
     */
    private suspend fun performFullSave(slot: Int, data: SaveData): SaveResult<SaveOperationStats> {
        Log.i(TAG, "save[$slot] Using FULL path")
        
        val retryConfig = RetryConfig(
            maxRetries = 3,
            initialDelayMs = 100L,
            maxDelayMs = 5000L,
            backoffMultiplier = 2.0,
            jitterFactor = 0.2,
            retryableErrors = setOf(OutOfMemoryError::class.java, java.io.IOException::class.java)
        )

        val result: RetryResult<SaveResult<SaveOperationStats>> = RetryPolicy.executeWithRetry(retryConfig) {
            executeSingleSaveAttempt(slot, data)
        }

        return when (result) {
            is RetryResult.Success -> {
                syncBaselineAfterFullSave(slot, data)
                result.data
            }
            is RetryResult.Failure -> mapSaveError(result, slot)
        }
    }
    
    /**
     * 执行单次保存尝试
     */
    private suspend fun executeSingleSaveAttempt(slot: Int, data: SaveData): SaveResult<SaveOperationStats> {
        val saveFile = fileHandler.getSaveFile(slot)
        val rawData = serializationHelper.serializeAndCompressSaveData(data.copy(timestamp = System.currentTimeMillis()))

        if (rawData.size > saveLimitsConfig.maxSaveSizeBytes) {
            throw IllegalStateException("Save data exceeds maximum size: ${rawData.size} bytes")
        }

        if (!fileHandler.hasEnoughDiskSpace(saveFile, rawData.size.toLong())) {
            throw IllegalStateException("Insufficient disk space")
        }

        val encryptedData = encryptionHelper.encryptData(rawData)

        val success = fileHandler.writeAtomically(
            targetFile = saveFile,
            data = encryptedData,
            magicHeader = MAGIC_NEW_FORMAT.toByteArray()
        )

        if (!success) {
            throw IllegalStateException("Failed to write save file atomically")
        }

        // Post-save 验证
        verifyPostSave(slot, saveFile)

        metadataManager.updateSlotMetadata(slot, data, encryptedData.size.toLong())
        updateCacheAfterSave(slot, data)

        Log.i(TAG, "Save completed for slot $slot via FULL path: ${encryptedData.size} bytes")
        return SaveResult.success(SaveOperationStats(
            bytesWritten = encryptedData.size.toLong(),
            timeMs = 0,
            wasEncrypted = true
        ))
    }
    
    /**
     * Post-save 验证步骤
     */
    private fun verifyPostSave(slot: Int, saveFile: java.io.File) {
        val verificationStart = System.currentTimeMillis()
        try {
            if (!saveFile.exists()) {
                throw IllegalStateException("Post-save verification failed: Save file does not exist")
            }

            val actualSize = saveFile.length()
            if (actualSize <= 0) {
                throw IllegalStateException("Post-save verification failed: Save file is empty")
            }

            val headerBytes = saveFile.inputStream().use { input ->
                val header = ByteArray(MAGIC_NEW_FORMAT.length)
                val bytesRead = input.read(header)
                if (bytesRead > 0) header.copyOf(bytesRead) else ByteArray(0)
            }

            if (headerBytes.isEmpty()) {
                throw IllegalStateException("Post-save verification failed: Read returned empty")
            }

            val headerStr = String(headerBytes, 0, minOf(MAGIC_NEW_FORMAT.length, headerBytes.size))
            if (!headerStr.startsWith(MAGIC_NEW_FORMAT)) {
                Log.w(TAG, "Post-save warning: Magic header mismatch")
            }

            val verifyTime = System.currentTimeMillis() - verificationStart
            Log.d(TAG, "Post-save verification passed for slot=$slot in ${verifyTime}ms")
        } catch (verifyEx: IllegalStateException) {
            Log.e(TAG, "Post-save verification FAILED for slot=$slot", verifyEx)
            throw verifyEx
        } catch (ex: Exception) {
            Log.w(TAG, "Post-save verification non-critical error for slot=$slot", ex)
        }
    }
    
    /**
     * 全量保存成功后同步增量存储基线
     */
    private suspend fun syncBaselineAfterFullSave(slot: Int, data: SaveData) {
        try {
            val syncResult = incrementalStorageManager.saveFull(slot, data)
            if (syncResult.success) {
                Log.i(TAG, "save[$slot] FULL path success, baseline synced: snapshotSize=${syncResult.savedBytes}")
            } else {
                Log.w(TAG, "save[$slot] FULL path success but baseline sync failed (non-fatal)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "save[$slot] FULL path success but baseline sync exception (non-fatal)", e)
        }
    }
    
    /**
     * 映射保存错误类型
     */
    private fun mapSaveError(result: RetryResult.Failure, slot: Int): SaveResult<SaveOperationStats> {
        return when (val error = result.lastError) {
            is KeyIntegrityException -> {
                SaveResult.failure(SaveError.KEY_DERIVATION_ERROR, "Key error: ${error.message}", error)
            }
            is KeyPermissionException -> {
                SaveResult.failure(SaveError.KEY_DERIVATION_ERROR, "Permission denied: ${error.message}", error)
            }
            is KeyFileSystemException -> {
                SaveResult.failure(SaveError.IO_ERROR, "File system error: ${error.message}", error)
            }
            is OutOfMemoryError -> {
                fileHandler.forceGcAndWait()
                SaveResult.failure(SaveError.OUT_OF_MEMORY, "OOM after ${result.totalAttempts} retries", error)
            }
            is IllegalStateException -> {
                if (error.message?.contains("maximum size") == true ||
                    error.message?.contains("disk space") == true ||
                    error.message?.contains("rename") == true) {
                    SaveResult.failure(SaveError.SAVE_FAILED, error.message ?: "Unknown error", error)
                } else {
                    SaveResult.failure(SaveError.OUT_OF_MEMORY, "Failed after ${result.totalAttempts} attempts", error)
                }
            }
            else -> {
                Log.e(TAG, "Failed to save slot $slot after ${result.totalAttempts} attempts", error)
                SaveResult.failure(SaveError.SAVE_FAILED, error.message ?: "Unknown error", error)
            }
        }
    }
    
    override suspend fun load(slot: Int): SaveResult<SaveData> {
        if (!lockManager.isValidSlot(slot)) {
            return SaveResult.failure(SaveError.INVALID_SLOT, "Invalid slot: $slot")
        }
        
        loadCount.incrementAndGet()
        
        val cached = loadFromCache(slot)
        if (cached != null) {
            cacheHits.incrementAndGet()
            return SaveResult.success(cached)
        }
        
        cacheMisses.incrementAndGet()
        
        return lockManager.withReadLockLight(slot) {
            // 优先尝试增量加载
            val incrementalLoadResult = attemptIncrementalLoad(slot)
            if (incrementalLoadResult != null && incrementalLoadResult.isSuccess) {
                return@withReadLockLight incrementalLoadResult
            }
            
            // 回退到全量加载
            Log.i(TAG, "load[$slot] Using FULL path")
            loadFromFile(slot)
        }.onSuccess { data ->
            updateCache(slot, data)
        }
    }
    
    /**
     * 尝试增量加载路径
     */
    private suspend fun attemptIncrementalLoad(slot: Int): SaveResult<SaveData>? {
        return try {
            Log.d(TAG, "load[$slot] Attempting INCREMENTAL path...")
            val incLoadResult = incrementalStorageManager.load(slot)
            if (incLoadResult.data != null) {
                Log.i(TAG, "load[$slot] INCREMENTAL path success: appliedDeltas=${incLoadResult.appliedDeltas}")
                updateCache(slot, incLoadResult.data)
                SaveResult.success(incLoadResult.data)
            } else {
                Log.w(TAG, "load[$slot] INCREMENTAL path returned empty, fallback to FULL")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "load[$slot] INCREMENTAL path exception, fallback to FULL: ${e.message}", e)
            null
        }
    }
    
    override suspend fun delete(slot: Int): SaveResult<Unit> {
        if (!lockManager.isValidSlot(slot)) {
            return SaveResult.failure(SaveError.INVALID_SLOT, "Invalid slot: $slot")
        }
        
        return lockManager.withWriteLockLight(slot) {
            try {
                val saveFile = fileHandler.getSaveFile(slot)
                val metaFile = fileHandler.getMetaFile(slot)
                
                val fullDeleteSuccess = fileHandler.deleteFiles(saveFile, metaFile)
                
                val incrementalDeleted = try {
                    incrementalStorageManager.delete(slot)
                } catch (e: Exception) {
                    Log.w(TAG, "delete[$slot] Incremental cleanup failed (non-fatal)", e)
                    false
                }
                
                backupManager.deleteBackupVersions(slot)
                invalidateCache(slot)
                metadataManager.removeFromCache(slot)
                
                if (fullDeleteSuccess && incrementalDeleted) {
                    Log.i(TAG, "delete[$slot] Success: full files + incremental cleaned")
                    SaveResult.success(Unit)
                } else if (fullDeleteSuccess) {
                    Log.w(TAG, "delete[$slot] Partial success: full deleted, incremental incomplete")
                    SaveResult.success(Unit)
                } else {
                    SaveResult.failure(SaveError.DELETE_FAILED, "Partial deletion for slot $slot")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete slot $slot", e)
                SaveResult.failure(SaveError.DELETE_FAILED, e.message ?: "Unknown error", e)
            }
        }
    }
    
    override suspend fun hasSave(slot: Int): Boolean {
        if (!lockManager.isValidSlot(slot)) return false
        val fullExists = fileHandler.getSaveFile(slot).exists()
        val incrementalExists = try {
            incrementalStorageManager.hasSave(slot)
        } catch (e: Exception) {
            Log.w(TAG, "hasSave[$slot] Incremental query failed: ${e.message}")
            false
        }
        return fullExists || incrementalExists
    }
    
    override suspend fun getSlotInfo(slot: Int): SlotMetadata? {
        if (!lockManager.isValidSlot(slot)) return null
        
        metadataManager.getFromCache(slot)?.let { return it }
        
        val fullMeta = metadataManager.loadSlotMetadata(slot)
        
        val incrementalSlotInfo = try {
            incrementalStorageManager.getSlotInfo(slot)
        } catch (e: Exception) {
            Log.w(TAG, "getSlotInfo[$slot] Incremental query failed: ${e.message}")
            null
        }
        
        return when {
            fullMeta != null && incrementalSlotInfo != null -> {
                fullMeta.copy(timestamp = maxOf(fullMeta.timestamp, incrementalSlotInfo.timestamp))
            }
            fullMeta != null -> fullMeta
            incrementalSlotInfo != null -> {
                SlotMetadata(
                    slot = slot,
                    timestamp = incrementalSlotInfo.timestamp,
                    gameYear = incrementalSlotInfo.gameYear,
                    gameMonth = incrementalSlotInfo.gameMonth,
                    sectName = incrementalSlotInfo.sectName,
                    discipleCount = incrementalSlotInfo.discipleCount,
                    spiritStones = incrementalSlotInfo.spiritStones,
                    fileSize = 0,
                    customName = incrementalSlotInfo.customName
                )
            }
            else -> null
        }
    }
    
    override fun getSaveSlots(): List<SaveSlot> {
        return slotQueryEngine.getSaveSlots()
    }
    
    override suspend fun createBackup(slot: Int): SaveResult<String> {
        return backupManager.createBackup(slot)
    }
    
    override suspend fun restoreFromBackup(slot: Int, backupId: String): SaveResult<SaveData> {
        return backupManager.restoreFromBackup(slot, backupId) { loadSlot -> loadFromFile(loadSlot) }
    }
    
    override suspend fun getBackupVersions(slot: Int): List<BackupInfo> {
        return backupManager.getBackupVersions(slot)
    }
    
    override suspend fun verifyIntegrity(slot: Int): IntegrityResult {
        val slotValidation = StorageValidator.validateSlotRange(slot, lockManager.getMaxSlots())
        if (!slotValidation.isValid) {
            return IntegrityResult.Invalid(slotValidation.errors.map { it.message })
        }

        return lockManager.withReadLockLight(slot) {
            try {
                val saveFile = fileHandler.getSaveFile(slot)
                val fileExistsResult = StorageValidator.validateFileExists(saveFile, "Save file")
                if (!fileExistsResult.isValid) {
                    return@withReadLockLight IntegrityResult.Invalid(fileExistsResult.errors.map { it.message })
                }

                val metaFile = fileHandler.getMetaFile(slot)
                val metaExistsResult = StorageValidator.validateFileExists(metaFile, "Meta file")
                if (!metaExistsResult.isValid) {
                    return@withReadLockLight IntegrityResult.Invalid(metaExistsResult.errors.map { it.message })
                }

                val data = loadFromFile(slot).getOrNull()
                if (data == null) {
                    return@withReadLockLight IntegrityResult.Invalid(listOf("Failed to load save data"))
                }

                val meta = metadataManager.loadSlotMetadata(slot)
                if (meta == null) {
                    return@withReadLockLight IntegrityResult.Invalid(listOf("Failed to load metadata"))
                }

                validateIntegrity(slot, data, meta)
            } catch (e: Exception) {
                Log.e(TAG, "Integrity verification failed for slot $slot", e)
                IntegrityResult.Invalid(listOf(e.message ?: "Unknown error"))
            }
        }
    }
    
    /**
     * 完整性验证核心逻辑
     */
    private suspend fun validateIntegrity(slot: Int, data: SaveData, meta: SlotMetadata): IntegrityResult {
        return if (meta.signedPayload != null) {
            val signatureResult = StorageValidator.validateSignedData(data, meta.signedPayload, SecureKeyManager.getOrCreateKey(context))
            when {
                signatureResult.isValid -> IntegrityResult.Valid
                signatureResult.errors.any { it.code.startsWith("SIGNATURE_TAMPERED") } ->
                    IntegrityResult.Tampered(signatureResult.errors.first { it.code.startsWith("SIGNATURE_TAMPERED") }.message)
                signatureResult.errors.any { it.code.contains("EXPIRED") } ->
                    IntegrityResult.Invalid(listOf("Signature expired"))
                else -> IntegrityResult.Invalid(signatureResult.errors.map { it.message })
            }
        } else {
            validateLegacyChecksum(data, meta, slot)
        }
    }
    
    /**
     * 旧版checksum验证
     */
    private suspend fun validateLegacyChecksum(data: SaveData, meta: SlotMetadata, slot: Int): IntegrityResult {
        if (meta.checksum.isEmpty()) {
            Log.w(TAG, "Missing checksum for slot $slot, integrity cannot be verified")
            return IntegrityResult.Invalid(listOf("Missing checksum - integrity cannot be verified"))
        }

        val currentChecksum = encryptionHelper.computeFullDataSignature(data)
        if (meta.checksum != currentChecksum) {
            val currentLegacyChecksum = computeLegacyDataChecksum(data)
            if (meta.checksum != currentLegacyChecksum) {
                Log.w(TAG, "Checksum mismatch for slot $slot")
                return IntegrityResult.Tampered("Checksum mismatch")
            }
        }

        return IntegrityResult.Valid
    }
    
    override suspend fun getDetailedIntegrityReport(slot: Int): IntegrityReport? {
        val slotValidation = StorageValidator.validateSlotRange(slot, lockManager.getMaxSlots())
        if (!slotValidation.isValid) return null

        return lockManager.withReadLockLight(slot) {
        try {
            val saveFile = fileHandler.getSaveFile(slot)
            val fileExistsResult = StorageValidator.validateFileExists(saveFile, "Save file")
            if (!fileExistsResult.isValid) return@withReadLockLight null

            val data = loadFromFile(slot).getOrNull() ?: return@withReadLockLight null
            val meta = metadataManager.loadSlotMetadata(slot) ?: return@withReadLockLight null

            buildDetailedReport(slot, data, meta)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate integrity report for slot $slot", e)
            null
        }
    }
    }
    
    /**
     * 构建详细完整性报告
     */
    private suspend fun buildDetailedReport(slot: Int, data: SaveData, meta: SlotMetadata): IntegrityReport {
        val key = SecureKeyManager.getOrCreateKey(context)
        val currentDataHash = IntegrityValidator.computeFullDataSignature(data, key)
        val currentMerkleRoot = IntegrityValidator.computeMerkleRoot(data)

        val metaValidation = StorageValidator.validateMetadata(meta)
        val errors = mutableListOf<String>().apply { addAll(metaValidation.errors.map { it.message }) }

        var signatureValid = true
        var hashValid = true
        var merkleValid = true

        if (meta.signedPayload != null) {
            val signatureResult = StorageValidator.validateSignedData(data, meta.signedPayload, key)
            signatureValid = signatureResult.isValid
            if (!signatureValid) errors.add("Signature verification failed: ${signatureResult.errors.firstOrNull()?.message}")

            hashValid = currentDataHash == meta.signedPayload.dataHash
            if (!hashValid) errors.add("Data hash mismatch")

            merkleValid = currentMerkleRoot == meta.signedPayload.merkleRoot
            if (!merkleValid) errors.add("Merkle root mismatch")
        } else {
            hashValid = meta.dataHash.isEmpty() || currentDataHash == meta.dataHash
            if (!hashValid && meta.dataHash.isNotEmpty()) errors.add("Data hash mismatch")

            merkleValid = meta.merkleRoot.isEmpty() || currentMerkleRoot == meta.merkleRoot
            if (!merkleValid && meta.merkleRoot.isNotEmpty()) errors.add("Merkle root mismatch")
        }

        return IntegrityReport(
            slot = slot,
            isValid = errors.isEmpty(),
            dataHash = currentDataHash,
            merkleRoot = currentMerkleRoot,
            signatureValid = signatureValid,
            hashValid = hashValid,
            merkleValid = merkleValid,
            errors = errors
        )
    }
    
    override suspend fun repair(slot: Int): SaveResult<Unit> {
        if (!lockManager.isValidSlot(slot)) {
            return SaveResult.failure(SaveError.INVALID_SLOT, "Invalid slot: $slot")
        }
        
        val integrity = verifyIntegrity(slot)
        if (integrity is IntegrityResult.Valid) {
            return SaveResult.success(Unit)
        }
        
        val backups = backupManager.getBackupVersions(slot)
        if (backups.isEmpty()) {
            return SaveResult.failure(SaveError.SLOT_CORRUPTED, "No backup available for repair")
        }
        
        for (backup in backups) {
            val result = restoreFromBackup(slot, backup.id)
            if (result.isSuccess) {
                val restoredIntegrity = verifyIntegrity(slot)
                if (restoredIntegrity is IntegrityResult.Valid) {
                    Log.i(TAG, "Repaired slot $slot from backup ${backup.id}")
                    return SaveResult.success(Unit)
                }
            }
        }
        
        return SaveResult.failure(SaveError.RESTORE_FAILED, "All backup restore attempts failed")
    }
    
    suspend fun autoSave(data: SaveData): SaveResult<SaveOperationStats> {
        return save(AUTO_SAVE_SLOT, data)
    }
    
    suspend fun emergencySave(data: SaveData): SaveResult<SaveOperationStats> {
        return withContext(Dispatchers.IO) {
            lockManager.withWriteLockLight(EMERGENCY_SLOT) {
                var txnId: Long = 0
                try {
                    val emergencyFile = fileHandler.getEmergencySaveFile()
                    val cleanedData = cleanSaveDataWithArchive(data)
                    val rawData = serializationHelper.serializeAndCompressSaveData(cleanedData.copy(timestamp = System.currentTimeMillis()))
                    
                    // 安全检查
                    if (rawData.size > saveLimitsConfig.maxSaveSizeBytes) {
                        return@withWriteLockLight SaveResult.failure(SaveError.SAVE_FAILED,
                            "Emergency save data exceeds maximum size: ${rawData.size} bytes")
                    }

                    if (!fileHandler.checkAvailableMemory()) {
                        fileHandler.forceGcAndWait()
                        if (!fileHandler.checkAvailableMemory()) {
                            return@withWriteLockLight SaveResult.failure(SaveError.OUT_OF_MEMORY,
                                "Insufficient memory for emergency save after GC")
                        }
                    }

                    val estimatedSize = rawData.size.toLong()
                    if (!fileHandler.checkTotalStorageBudget(estimatedSize)) {
                        val result = fileHandler.enforceStorageQuota(estimatedSize, setOf(EMERGENCY_SLOT))
                        if (!result.success && !fileHandler.checkTotalStorageBudget(estimatedSize)) {
                            return@withWriteLockLight SaveResult.failure(SaveError.SAVE_FAILED,
                                "Storage quota exceeded for emergency save")
                        }
                    }
                    
                    val encryptedData = encryptionHelper.encryptData(rawData)

                    val txnResult = wal.beginTransaction(EMERGENCY_SLOT, com.xianxia.sect.data.wal.WALEntryType.DATA, null)
                    if (txnResult.isSuccess) {
                        txnId = txnResult.getOrNull() ?: 0
                        emergencyTxnId.set(txnId)
                    }

                    val success = fileHandler.writeAtomically(
                        targetFile = emergencyFile,
                        data = encryptedData,
                        magicHeader = MAGIC_NEW_FORMAT.toByteArray()
                    )

                    if (!success) {
                        if (txnId > 0) {
                            wal.abort(txnId)
                            emergencyTxnId.set(0)
                        }
                        return@withWriteLockLight SaveResult.failure(SaveError.SAVE_FAILED, "Emergency save failed")
                    }

                    if (txnId > 0) {
                        wal.commit(txnId)
                    }

                    updateCacheAfterSave(EMERGENCY_SLOT, cleanedData)
                    Log.i(TAG, "Emergency save completed with transaction $txnId")
                    SaveResult.success(SaveOperationStats(bytesWritten = encryptedData.size.toLong()))
                } catch (e: Exception) {
                    Log.e(TAG, "Emergency save failed", e)
                    if (txnId > 0) {
                        try {
                            wal.abort(txnId)
                            emergencyTxnId.set(0)
                        } catch (abortEx: Exception) {
                            Log.e(TAG, "Failed to abort emergency transaction", abortEx)
                        }
                    }
                    SaveResult.failure(SaveError.SAVE_FAILED, e.message ?: "Unknown error", e)
                }
            }
        }
    }
    
    fun hasEmergencySave(): Boolean = fileHandler.getEmergencySaveFile().exists()
    
    suspend fun loadEmergencySave(): SaveData? {
        val file = fileHandler.getEmergencySaveFile()
        if (!file.exists()) return null

        return lockManager.withReadLockLight(EMERGENCY_SLOT) {
            withContext(Dispatchers.IO) {
                try {
                    val fileData = file.readBytes() ?: return@withContext null
                    var data: SaveData? = null

                    if (fileData.size >= MAGIC_NEW_FORMAT.length &&
                        String(fileData, 0, MAGIC_NEW_FORMAT.length) == MAGIC_NEW_FORMAT) {
                        val encryptedData = fileData.copyOfRange(MAGIC_NEW_FORMAT.length, fileData.size)
                        val rawData = encryptionHelper.decryptData(encryptedData)
                        if (rawData != null) {
                            data = try {
                                serializationHelper.deserializeSaveData(rawData)
                            } catch (e: SerializationException) {
                                Log.w(TAG, "Emergency save deserialization failed", e)
                                null
                            }
                        }
                    } else {
                        val rawData = encryptionHelper.decryptData(fileData)
                        if (rawData != null) {
                            data = try {
                                serializationHelper.deserializeSaveData(rawData)
                            } catch (e: SerializationException) {
                                Log.w(TAG, "Emergency save deserialization failed (legacy path)", e)
                                null
                            }
                        }
                    }

                    if (data != null) {
                        shardingStrategy.loadAndMergeShardData(EMERGENCY_SLOT, data)
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load emergency save", e)
                    null
                }
            }
        }
    }
    
    fun clearEmergencySave(): Boolean {
        val file = fileHandler.getEmergencySaveFile()
        return if (file.exists()) file.delete() else true
    }
    
    fun setCurrentSlot(slot: Int) {
        if (lockManager.isValidSlot(slot)) {
            _currentSlot.value = slot
        }
    }
    
    fun getStats(): RepositoryStats {
        val total = saveCount.get() + loadCount.get()
        val hits = cacheHits.get()
        val hitRate = if (total > 0) hits.toFloat() / total else 0f

        return RepositoryStats(
            totalSaves = saveCount.get(),
            totalLoads = loadCount.get(),
            cacheHitRate = hitRate,
            activeTransactions = transactionalSaveManager.getActiveTransactionCount(),
            storageBytes = fileHandler.calculateTotalStorageBytes(),
            storageUsagePercent = fileHandler.getStorageUsagePercent()
        )
    }
    
    private suspend fun loadFromFile(slot: Int): SaveResult<SaveData> {
        return withContext(Dispatchers.IO) {
            val migrationStartTime = migratingSlots[slot]
            if (migrationStartTime != null) {
                if (System.currentTimeMillis() - migrationStartTime < 30_000L) {
                    Log.w(TAG, "Slot $slot is currently being migrated, skipping")
                    return@withContext SaveResult.failure(SaveError.LOAD_FAILED, "Slot $slot is currently being migrated")
                }
            }

            try {
                val saveFile = fileHandler.getSaveFile(slot)
                if (!saveFile.exists()) {
                    return@withContext SaveResult.failure(SaveError.SLOT_EMPTY, "No save for slot $slot")
                }

                val fileData = saveFile.readBytes() ?: return@withContext SaveResult.failure(SaveError.LOAD_FAILED, "Failed to read save file")

                if (fileData.size >= MAGIC_NEW_FORMAT.length &&
                    String(fileData, 0, MAGIC_NEW_FORMAT.length) == MAGIC_NEW_FORMAT) {
                    val encryptedData = fileData.copyOfRange(MAGIC_NEW_FORMAT.length, fileData.size)

                    val loadRetryConfig = RetryConfig(
                        maxRetries = 2,
                        initialDelayMs = 50L,
                        maxDelayMs = 2000L,
                        backoffMultiplier = 2.0,
                        jitterFactor = 0.2,
                        retryableErrors = setOf(OutOfMemoryError::class.java, java.io.IOException::class.java)
                    )

                    val decryptResult: RetryResult<SaveResult<SaveData>> = RetryPolicy.executeWithRetry(loadRetryConfig) {
                        val rawData = encryptionHelper.decryptData(encryptedData)

                        if (rawData == null) {
                            throw IllegalStateException("Decryption failed")
                        }

                        var data = serializationHelper.deserializeSaveData(rawData)
                        data = shardingStrategy.loadAndMergeShardData(slot, data)

                        SaveResult.success(data)
                    }

                    when (decryptResult) {
                        is RetryResult.Success -> decryptResult.data
                        is RetryResult.Failure -> {
                            when (val error = decryptResult.lastError) {
                                is IllegalStateException -> {
                                    when (error.message) {
                                        "Decryption failed" -> SaveResult.failure(SaveError.DECRYPTION_ERROR, "Decryption failed")
                                        else -> SaveResult.failure(SaveError.LOAD_FAILED, error.message ?: "Unknown error", error)
                                    }
                                }
                                else -> {
                                    Log.e(TAG, "Failed to load slot $slot after ${decryptResult.totalAttempts} attempts", error)
                                    SaveResult.failure(SaveError.LOAD_FAILED, error.message ?: "Unknown error", error)
                                }
                            }
                        }
                    }
                } else {
                    Log.e(TAG, "load[$slot] Unknown save file format (non XSAV header), legacy format support removed")
                    SaveResult.failure(SaveError.SLOT_CORRUPTED, "Unknown save format: legacy format support has been removed")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to load slot $slot", e)
                SaveResult.failure(SaveError.LOAD_FAILED, e.message ?: "Unknown error", e)
            }
        }
    }
    
    private suspend fun loadFromCache(slot: Int): SaveData? {
        return try {
            cacheManager.getOrNull(com.xianxia.sect.data.cache.CacheKey(
                type = "save_data",
                slot = slot,
                id = slot.toString(),
                ttl = 300_000L
            ))
        } catch (e: Exception) {
            null
        }
    }
    
    private fun updateCache(slot: Int, data: SaveData) {
        try {
            scope.launch(Dispatchers.IO) {
                cacheManager.put(
                    com.xianxia.sect.data.cache.CacheKey(
                        type = "save_data",
                        slot = slot,
                        id = slot.toString(),
                        ttl = 60_000L
                    ),
                    data
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update cache for slot $slot", e)
        }
    }
    
    private fun invalidateCache(slot: Int) {
        try {
            cacheManager.remove(com.xianxia.sect.data.cache.CacheKey(
                type = "save_data",
                slot = slot,
                id = slot.toString()
            ))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to invalidate cache for slot $slot", e)
        }
    }
    
    private fun updateCacheAfterSave(slot: Int, data: SaveData) {
        try {
            cacheManager.putWithoutTracking(
                com.xianxia.sect.data.cache.CacheKey(
                    type = "save_data",
                    slot = slot,
                    id = slot.toString(),
                    ttl = 300_000L
                ),
                data
            )
            
            cacheManager.putWithoutTracking(
                com.xianxia.sect.data.cache.CacheKey(
                    type = "game_data",
                    slot = slot,
                    id = "current",
                    ttl = 300_000L
                ),
                data.gameData
            )
            
            Log.d(TAG, "Cache updated after save for slot $slot")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update cache after save for slot $slot", e)
            invalidateCache(slot)
        }
    }
    
    /**
     * 清理存档数据：超限的 BattleLog 和 GameEvent 通过 DataArchiver 归档到磁盘
     */
    private suspend fun cleanSaveDataWithArchive(data: SaveData): SaveData {
        val maxBattleLogs = saveLimitsConfig.maxBattleLogs
        val maxGameEvents = saveLimitsConfig.maxGameEvents

        // 归档超限的 BattleLog
        val cleanedBattleLogs = if (data.battleLogs.size > maxBattleLogs) {
            val archiveResult = dataArchiver.archiveBattleLogsIfNeeded(data.battleLogs, maxBattleLogs)
            if (archiveResult.success && archiveResult.archivedCount > 0) {
                Log.i(TAG, "Archived ${archiveResult.archivedCount} battle logs, ${archiveResult.remainingCount} retained in save")
            } else if (!archiveResult.success) {
                Log.w(TAG, "Archive failed (${archiveResult.error}), falling back to truncation for battle logs")
            }
            dataArchiver.getRetainedBattleLogs(data.battleLogs, maxBattleLogs)
        } else {
            data.battleLogs
        }

        // 归档超限的 GameEvent
        val cleanedEvents = if (data.events.size > maxGameEvents) {
            val archiveResult = dataArchiver.archiveGameEventsIfNeeded(data.events, maxGameEvents)
            if (archiveResult.success && archiveResult.archivedCount > 0) {
                Log.i(TAG, "Archived ${archiveResult.archivedCount} game events, ${archiveResult.remainingCount} retained in save")
            } else if (!archiveResult.success) {
                Log.w(TAG, "Archive failed (${archiveResult.error}), falling back to truncation for game events")
            }
            dataArchiver.getRetainedGameEvents(data.events, maxGameEvents)
        } else {
            data.events
        }

        return data.copy(
            battleLogs = cleanedBattleLogs,
            events = cleanedEvents
        )
    }
    
    /**
     * 精确估算存档数据的序列化后大小（字节）
     */
    private fun estimateSaveDataSize(data: SaveData): Long {
        return UnifiedStorageEngine.estimateSaveSize(data)
    }
    
    /**
     * 计算旧版数据校验和（向后兼容）
     */
    private fun computeLegacyDataChecksum(data: SaveData): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(data.version.toByteArray())
        digest.update(data.timestamp.toString().toByteArray())
        digest.update(data.gameData.spiritStones.toString().toByteArray())
        digest.update(data.disciples.size.toString().toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    
    fun shutdown() {
        scope.cancel()
        Log.i(TAG, "UnifiedSaveRepository shutdown completed")
    }
}
