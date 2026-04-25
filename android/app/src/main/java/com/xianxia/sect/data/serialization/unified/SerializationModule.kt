package com.xianxia.sect.data.serialization.unified

import android.util.Log
import com.xianxia.sect.data.compression.CompressionAlgorithm
import com.xianxia.sect.data.compression.DataCompressor
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.serialization.NullSafeProtoBuf
import com.xianxia.sect.data.unified.SerializationException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.*

@Singleton
class SerializationModule @Inject constructor(
    private val serializationEngine: UnifiedSerializationEngine,
    private val saveDataConverter: SaveDataConverter
) {

    companion object {
        private const val TAG = "SerializationModule"
    }

    fun serializeAndCompressSaveData(data: SaveData): ByteArray {
        return try {
            val serializableData = saveDataConverter.toSerializable(data)
            val context = SerializationContext(
                format = SerializationFormat.PROTOBUF,
                compression = CompressionType.LZ4,
                compressThreshold = 1024,
                includeChecksum = true
            )
            val result = serializationEngine.serialize(
                serializableData,
                context,
                kotlinx.serialization.serializer<SerializableSaveData>()
            )
            result.data
        } catch (e: Exception) {
            Log.e(TAG, "Protobuf serialization failed", e)
            throw SerializationException("Failed to serialize save data via Protobuf", e)
        }
    }

    fun deserializeSaveData(data: ByteArray): SaveData {
        return try {
            deserializeProtobufData(data)
                ?: throw SerializationException("Protobuf deserialization returned null (data may be corrupted or checksum invalid)")
        } catch (e: SerializationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize save data via Protobuf", e)
            throw SerializationException("Failed to deserialize save data via Protobuf", e)
        }
    }

    fun deserializeProtobufData(data: ByteArray): SaveData? {
        return try {
            val context = SerializationContext(
                format = SerializationFormat.PROTOBUF,
                compression = CompressionType.LZ4,
                includeChecksum = true
            )
            val result = serializationEngine.deserialize<SerializableSaveData>(
                data,
                context,
                kotlinx.serialization.serializer()
            )
            if (result.isSuccess && result.data != null) {
                saveDataConverter.fromSerializable(result.data)
            } else {
                Log.w(TAG, "Protobuf deserialization returned invalid result, checksum valid: ${result.checksumValid}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize protobuf data", e)
            null
        }
    }
}

// ========================================================================
// From UnifiedSerializationEngine.kt
// ========================================================================

object SerializationConstants {
    const val MAGIC_HEADER: Short = 0x5853
    const val FORMAT_VERSION: Byte = 3
    const val HEADER_SIZE = 10
    const val CHECKSUM_SIZE = 32

    /** statsCache 默认 TTL：10 分钟 */
    const val STATS_CACHE_TTL_MS = 10 * 60 * 1000L
}

enum class SerializationFormat(val code: Byte) {
    PROTOBUF(1);

    companion object {
        fun fromCode(code: Byte): SerializationFormat {
            return values().find { it.code == code } ?: PROTOBUF
        }
    }
}

/**
 * 支持的压缩算法枚举。
 *
 * - LZ4：极速压缩/解压，适用于热数据（频繁访问）
 * - Zstd：更高压缩率，适用于冷数据（归档存储）
 *
 * 压缩类型头字节映射：0x00=未压缩, 0x01=LZ4, 0x02=Zstd
 */
enum class CompressionType(val code: Byte) {
    LZ4(1),
    ZSTD(2);

    companion object {
        fun fromCode(code: Byte): CompressionType {
            return entries.find { it.code == code } ?: LZ4
        }
    }
}

data class SerializationContext(
    val format: SerializationFormat = SerializationFormat.PROTOBUF,
    val compression: CompressionType = CompressionType.LZ4,
    val compressThreshold: Int = 1024,
    val includeChecksum: Boolean = true,
    val version: Int = 1,
    val prettyPrint: Boolean = false
)

data class SerializationResult(
    val data: ByteArray,
    val originalSize: Int,
    val compressedSize: Int,
    val format: SerializationFormat,
    val compression: CompressionType,
    val checksum: ByteArray,
    val serializationTimeMs: Long,
    val compressionTimeMs: Long
) {
    val compressionRatio: Double
        get() = if (originalSize > 0) compressedSize.toDouble() / originalSize else 1.0

    val totalSavedBytes: Int
        get() = (originalSize - compressedSize).coerceAtLeast(0)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SerializationResult) return false
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int = data.contentHashCode()
}

data class DeserializationResult<T>(
    val data: T?,
    val format: SerializationFormat,
    val compression: CompressionType,
    val checksumValid: Boolean,
    val deserializationTimeMs: Long,
    val decompressionTimeMs: Long,
    val error: Throwable? = null
) {
    val isSuccess: Boolean get() = data != null && error == null
}

// ========================================================================
// 分级配额系统
// ========================================================================

/**
 * 序列化数据分级配额限制。
 *
 * 替代原有的单一 [SerializationConstants.MAX_DATA_SIZE]（200MB 硬上限），
 * 按数据域分别设置合理上限，防止单一异常域撑爆整体存档。
 *
 * ## 配额设计原则
 * - **coreMaxBytes**（64KB）：核心宗门元数据，通常 < 10KB
 * - **discipleMaxBytes**（10MB）：弟子列表，单个弟子约 1-2KB，上限 ~5000 人
 * - **inventoryMaxBytes**（5MB）：装备/功法/丹药/材料/草药/种子合计
 * - **worldMaxBytes**（5MB）：世界地图、探索信息等
 * - **combatMaxBytes**（20MB）：战斗日志，单条约 1-5KB
 * - **missionMaxBytes**（2MB）：任务数据
 * - **totalMaxBytes**（200MB）：全局硬上限，防止总和溢出
 *
 * ## 使用方式
 * ```kotlin
 * val quota = SerializationQuota()
 * val result = engine.validateAgainstQuota(saveData, quota)
 * if (!result.isValid) {
 *     // 处理超限：截断战斗日志 / 限制弟子数量 等
 * }
 * ```
 */
data class SerializationQuota(
    /** 核心宗门数据上限（SerializableGameData + 元信息），默认 64KB */
    val coreMaxBytes: Int = 64 * 1024,
    /** 弟子列表上限，默认 10MB（约 5000-10000 名弟子） */
    val discipleMaxBytes: Int = 10 * 1024 * 1024,
    /** 物品背包总上限（equipment + manuals + pills + materials + herbs + seeds），默认 5MB */
    val inventoryMaxBytes: Int = 5 * 1024 * 1024,
    /** 世界数据上限（worldMapSects, alliances 等），默认 5MB */
    val worldMaxBytes: Int = 5 * 1024 * 1024,
    /** 战斗日志上限，默认 20MB */
    val combatMaxBytes: Int = 20 * 1024 * 1024,
    /** 任务数据上限（activeMissions + availableMissions），默认 2MB */
    val missionMaxBytes: Int = 2 * 1024 * 1024,
    /** 总计硬上限（所有域之和），默认 200MB */
    val totalMaxBytes: Int = 200 * 1024 * 1024,
    /** 最大弟子数量限制，默认 5000 */
    val maxDiscipleCount: Int = 5000,
    /** 最大战斗日志数量限制，默认 10000 */
    val maxBattleLogCount: Int = 10000
) {
    companion object {
        /** 严格模式配额（用于生产环境） */
        val STRICT = SerializationQuota(
            coreMaxBytes = 64 * 1024,
            discipleMaxBytes = 10 * 1024 * 1024,
            inventoryMaxBytes = 5 * 1024 * 1024,
            worldMaxBytes = 5 * 1024 * 1024,
            combatMaxBytes = 20 * 1024 * 1024,
            missionMaxBytes = 2 * 1024 * 1024,
            totalMaxBytes = 200 * 1024 * 1024,
            maxDiscipleCount = 5000,
            maxBattleLogCount = 10000
        )

        /** 宽松模式配额（用于调试/测试） */
        val LENIENT = SerializationQuota(
            coreMaxBytes = 128 * 1024,
            discipleMaxBytes = 50 * 1024 * 1024,
            inventoryMaxBytes = 20 * 1024 * 1024,
            worldMaxBytes = 20 * 1024 * 1024,
            combatMaxBytes = 100 * 1024 * 1024,
            missionMaxBytes = 10 * 1024 * 1024,
            totalMaxBytes = 500 * 1024 * 1024,
            maxDiscipleCount = 20000,
            maxBattleLogCount = 50000
        )
    }
}

/**
 * 配额验证结果。
 */
data class QuotaValidationResult(
    /** 是否通过所有配额检查 */
    val isValid: Boolean,
    /** 各域的超限详情（空表示全部通过） */
    val violations: List<QuotaViolation> = emptyList(),
    /** 估算的总字节数 */
    val estimatedTotalBytes: Long = 0L
) {
    /** 是否有任一域超限 */
    val hasViolations: Boolean get() = violations.isNotEmpty()
}

/**
 * 单个配额违规记录。
 */
data class QuotaViolation(
    /** 违规的域名 */
    val domain: String,
    /** 当前值（字节或条目数） */
    val actualValue: Long,
    /** 允许的上限 */
    val limit: Long,
    /** 单位描述 */
    val unit: String = "bytes"
) {
    override fun toString(): String =
        "QuotaViolation(domain='$domain', actual=$actualValue$unit, limit=$limit$unit)"
}

@Singleton
class UnifiedSerializationEngine @Inject constructor(
    private val dataCompressor: DataCompressor
) {
    companion object {
        const val TAG = "UnifiedSerialization"
    }

    private fun computeChecksum(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data)
    }

    /**
     * 统一的 ProtoBuf 实例（来自 NullSafeProtoBuf 工具类）
     *
     * 配置：encodeDefaults = true
     * 确保所有字段都被序列化，即使值为默认值
     * 这对于版本兼容性至关重要
     */
    internal val protoBuf = NullSafeProtoBuf.protoBuf

    /**
     * 统计缓存：key -> (stats, createdAt)
     *
     * 内部值类型为 Pair<SerializationStats, Long>，其中 Long 为创建时间戳（毫秒）。
     * 通过 [cleanupExpiredStats] 可清理超过 TTL 的过期条目。
     * TTL 默认值见 [SerializationConstants.STATS_CACHE_TTL_MS]（10 分钟）。
     */
    private val statsCache = ConcurrentHashMap<String, Pair<SerializationStats, Long>>()

    /**
     * 序列化统计数据。
     */
    data class SerializationStats(
        val totalOperations: Long = 0,
        val totalBytesSerialized: Long = 0,
        val totalBytesCompressed: Long = 0,
        val totalSerializationTime: Long = 0,
        val totalCompressionTime: Long = 0,
        val averageCompressionRatio: Double = 1.0
    )

    // ========================================================================
    // 核心：serialize / deserialize
    // ========================================================================

    @OptIn(ExperimentalSerializationApi::class)
    fun <T> serialize(
        data: T,
        context: SerializationContext = SerializationContext(),
        serializer: KSerializer<T>
    ): SerializationResult {
        if (data == null) {
            throw SerializationException("Cannot serialize null data")
        }

        val serializationStart = System.currentTimeMillis()

        val rawData = protoBuf.encodeToByteArray(serializer, data)

        val serializationTime = System.currentTimeMillis() - serializationStart

        // 使用分级配额检查替代单一 MAX_DATA_SIZE
        val sizeLimit = SerializationQuota.STRICT.totalMaxBytes
        if (rawData.size > sizeLimit) {
            throw SerializationException(
                "Data too large: ${rawData.size} bytes (limit: $sizeLimit bytes). " +
                "Consider using SerializationQuota for domain-specific limits."
            )
        }

        val compressionStart = System.currentTimeMillis()
        val algo = when (context.compression) {
            CompressionType.ZSTD -> CompressionAlgorithm.ZSTD
            else -> CompressionAlgorithm.LZ4
        }
        val (compressedData, compressionType) = if (rawData.size >= context.compressThreshold) {
            val result = dataCompressor.compress(rawData, algo)
            result.data to context.compression
        } else {
            val result = dataCompressor.compress(rawData, CompressionAlgorithm.LZ4)
            result.data to CompressionType.LZ4
        }
        val compressionTime = System.currentTimeMillis() - compressionStart

        val checksum = if (context.includeChecksum) {
            computeChecksum(rawData)
        } else {
            ByteArray(SerializationConstants.CHECKSUM_SIZE)
        }

        val finalData = buildFinalData(
            compressedData,
            context.format,
            compressionType,
            checksum,
            rawData.size
        )

        // 更新统计缓存
        recordStats(serializationTime, compressionTime, rawData.size, compressedData.size)

        return SerializationResult(
            data = finalData,
            originalSize = rawData.size,
            compressedSize = compressedData.size,
            format = context.format,
            compression = compressionType,
            checksum = checksum,
            serializationTimeMs = serializationTime,
            compressionTimeMs = compressionTime
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun <T> deserialize(
        data: ByteArray,
        context: SerializationContext = SerializationContext(),
        serializer: KSerializer<T>
    ): DeserializationResult<T> {
        if (data.size < SerializationConstants.HEADER_SIZE) {
            return DeserializationResult(
                data = null,
                format = SerializationFormat.PROTOBUF,
                compression = CompressionType.LZ4,
                checksumValid = false,
                deserializationTimeMs = 0,
                decompressionTimeMs = 0,
                error = SerializationException("Data too small: ${data.size} bytes")
            )
        }

        try {
            val header = parseHeader(data)

            val payloadStart = SerializationConstants.HEADER_SIZE +
                (if (header.hasChecksum) SerializationConstants.CHECKSUM_SIZE else 0)
            val payload = data.copyOfRange(payloadStart, data.size)

            val decompressionStart = System.currentTimeMillis()
            val decompressAlgo = when (header.compression) {
                CompressionType.ZSTD -> CompressionAlgorithm.ZSTD
                else -> CompressionAlgorithm.LZ4
            }
            val rawData = dataCompressor.decompress(payload, decompressAlgo, header.originalSize)
            val decompressionTime = System.currentTimeMillis() - decompressionStart

            var checksumValid = true
            if (header.hasChecksum && context.includeChecksum) {
                val storedChecksum = data.copyOfRange(
                    SerializationConstants.HEADER_SIZE,
                    SerializationConstants.HEADER_SIZE + SerializationConstants.CHECKSUM_SIZE
                )
                val computedChecksum = computeChecksum(rawData)
                checksumValid = storedChecksum.contentEquals(computedChecksum)

                if (!checksumValid) {
                    Log.w(TAG, "Checksum mismatch detected")
                }
            }

            val deserializationStart = System.currentTimeMillis()
            val result: T? = protoBuf.decodeFromByteArray(serializer, rawData)
            val deserializationTime = System.currentTimeMillis() - deserializationStart

            return DeserializationResult(
                data = result,
                format = header.format,
                compression = header.compression,
                checksumValid = checksumValid,
                deserializationTimeMs = deserializationTime,
                decompressionTimeMs = decompressionTime
            )
        } catch (e: Exception) {
            Log.e(TAG, "Deserialization failed", e)
            return DeserializationResult(
                data = null,
                format = SerializationFormat.PROTOBUF,
                compression = CompressionType.LZ4,
                checksumValid = false,
                deserializationTimeMs = 0,
                decompressionTimeMs = 0,
                error = e
            )
        }
    }

    // ========================================================================
    // 格式检测
    // ========================================================================

    fun detectFormat(data: ByteArray): SerializationFormat {
        if (data.size < SerializationConstants.HEADER_SIZE) {
            return SerializationFormat.PROTOBUF
        }

        val buffer = DataInputStream(ByteArrayInputStream(data))
        val magic = buffer.readShort()

        return if (magic == SerializationConstants.MAGIC_HEADER) {
            buffer.readByte()
            val formatCode = buffer.readByte()
            SerializationFormat.fromCode(formatCode)
        } else {
            SerializationFormat.PROTOBUF
        }
    }

    // ========================================================================
    // Header 构建 / 解析
    // ========================================================================

    private fun buildFinalData(
        payload: ByteArray,
        format: SerializationFormat,
        compression: CompressionType,
        checksum: ByteArray,
        originalSize: Int
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        dos.writeShort(SerializationConstants.MAGIC_HEADER.toInt())
        dos.writeByte(SerializationConstants.FORMAT_VERSION.toInt())
        dos.writeByte(format.code.toInt())
        dos.writeByte(compression.code.toInt())
        dos.writeByte(if (checksum.isNotEmpty()) 1 else 0)
        dos.writeInt(originalSize)

        if (checksum.isNotEmpty()) {
            dos.write(checksum)
        }

        dos.write(payload)

        return baos.toByteArray()
    }

    private data class HeaderInfo(
        val format: SerializationFormat,
        val compression: CompressionType,
        val hasChecksum: Boolean,
        val originalSize: Int
    )

    private fun parseHeader(data: ByteArray): HeaderInfo {
        val dis = DataInputStream(ByteArrayInputStream(data))

        val magic = dis.readShort()
        if (magic != SerializationConstants.MAGIC_HEADER) {
            return HeaderInfo(
                format = SerializationFormat.PROTOBUF,
                compression = CompressionType.LZ4,
                hasChecksum = false,
                originalSize = data.size
            )
        }

        dis.readByte()
        val formatCode = dis.readByte()
        val compressionCode = dis.readByte()
        val hasChecksum = dis.readByte() != 0.toByte()
        val originalSize = dis.readInt()

        return HeaderInfo(
            format = SerializationFormat.fromCode(formatCode),
            compression = CompressionType.fromCode(compressionCode),
            hasChecksum = hasChecksum,
            originalSize = originalSize
        )
    }

    // ========================================================================
    // 统计缓存（带 TTL 过期机制）
    // ========================================================================

    /**
     * 获取指定 key 的统计信息。
     *
     * 如果 key 为 null 或不存在，返回默认空统计。
     * **自动触发过期清理**：每次调用时清理超过 [SerializationConstants.STATS_CACHE_TTL_MS] 的旧条目。
     *
     * @param key 统计 key，null 表示获取聚合统计（暂未实现，返回默认值）
     * @return 该 key 对应的统计快照
     */
    fun getStats(key: String? = null): SerializationStats {
        // 自动清理过期条目（惰性清理，不影响主路径性能）
        cleanupExpiredStats(SerializationConstants.STATS_CACHE_TTL_MS)

        return if (key != null) {
            statsCache[key]?.first ?: SerializationStats()
        } else {
            // TODO: 聚合所有 key 的统计信息？当前返回默认值
            SerializationStats()
        }
    }

    /**
     * 清理超过指定时间的过期统计条目。
     *
     * @param maxAgeMs 最大存活时间（毫秒），超过此时间的条目将被移除。
     *                 默认为 [SerializationConstants.STATS_CACHE_TTL_MS]（10 分钟）。
     * @return 被清理的条目数量
     */
    fun cleanupExpiredStats(maxAgeMs: Long = SerializationConstants.STATS_CACHE_TTL_MS): Int {
        val now = System.currentTimeMillis()
        var removedCount = 0

        val expiredKeys = statsCache.entries.filter { (_, value) ->
            now - value.second > maxAgeMs
        }.map { it.key }

        for (key in expiredKeys) {
            statsCache.remove(key)?.let { removedCount++ }
        }

        if (removedCount > 0) {
            Log.d(TAG, "Cleaned up $removedCount expired stats cache entries (maxAge=${maxAgeMs}ms)")
        }

        return removedCount
    }

    /**
     * 清空所有统计缓存。
     */
    fun clearStats() {
        val count = statsCache.size
        statsCache.clear()
        Log.d(TAG, "Cleared all $count stats cache entries")
    }

    /**
     * 记录一次序列化操作到统计缓存。
     */
    private fun recordStats(
        serializationTime: Long,
        compressionTime: Long,
        originalSize: Int,
        compressedSize: Int
    ) {
        val defaultKey = "_default"
        val now = System.currentTimeMillis()

        statsCache.compute(defaultKey) { _, existing ->
            val prev = existing?.first ?: SerializationStats()
            val newOps = prev.totalOperations + 1
            val newBytesSerialized = prev.totalBytesSerialized + originalSize
            val newBytesCompressed = prev.totalBytesCompressed + compressedSize
            val newSerTime = prev.totalSerializationTime + serializationTime
            val newCompTime = prev.totalCompressionTime + compressionTime
            val newRatio = if (newOps > 0 && originalSize > 0) {
                newBytesCompressed.toDouble() / newBytesSerialized
            } else {
                prev.averageCompressionRatio
            }

            Pair(
                SerializationStats(
                    totalOperations = newOps,
                    totalBytesSerialized = newBytesSerialized,
                    totalBytesCompressed = newBytesCompressed,
                    totalSerializationTime = newSerTime,
                    totalCompressionTime = newCompTime,
                    averageCompressionRatio = newRatio
                ),
                now
            )
        }
    }

    // ========================================================================
    // 分级配额验证
    // ========================================================================

    /**
     * 验证 [SerializableSaveData] 是否符合给定的 [SerializationQuota] 限制。
     *
     * 检查项包括：
     * - 核心数据大小（gameData 序列化后估算）
     * - 弟子数量及估算大小
     * - 物品背包总量（equipment + manuals + pills + materials + herbs + seeds）
     * - 战斗日志数量
     * - 任务数据量
     * - 总计硬上限
     *
     * 注意：此处使用**估算值**（基于 ProtoBuf 字段数量的粗略计算），
     * 而非实际序列化后的大小。精确大小需在 [serialize] 后检查。
     *
     * @param data 待验证的存档数据
     * @param quota 配额限制，默认使用 [SerializationQuota.STRICT]
     * @return 验证结果，包含是否通过及违规详情
     */
    fun validateAgainstQuota(
        data: SerializableSaveData,
        quota: SerializationQuota = SerializationQuota.STRICT
    ): QuotaValidationResult {
        val violations = mutableListOf<QuotaViolation>()
        var estimatedTotal = 0L

        // --- 核心数据 ---
        // 估算：gameData 字段多但大多为空集合/map，典型值 2-8KB
        val coreEstimate = estimateGameDataSize(data.gameData)
        estimatedTotal += coreEstimate
        if (coreEstimate > quota.coreMaxBytes) {
            violations.add(QuotaViolation("core", coreEstimate, quota.coreMaxBytes.toLong()))
        }

        // --- 弟子列表 ---
        val discipleCount = data.disciples.size.toLong()
        // 单个弟子约 800B-2KB（含 storageBagItems 等嵌套）
        val discipleEstimate = discipleCount * 1500L
        estimatedTotal += discipleEstimate
        if (discipleCount > quota.maxDiscipleCount) {
            violations.add(QuotaViolation("disciple_count", discipleCount, quota.maxDiscipleCount.toLong(), "items"))
        }
        if (discipleEstimate > quota.discipleMaxBytes) {
            violations.add(QuotaViolation("disciples", discipleEstimate, quota.discipleMaxBytes.toLong()))
        }

        // --- 物品背包（合并计算）---
        val inventoryItems = data.equipment.size +
            data.manuals.size +
            data.pills.size +
            data.materials.size +
            data.herbs.size +
            data.seeds.size
        // 单个物品约 100-300B
        val inventoryEstimate = inventoryItems.toLong() * 200L
        estimatedTotal += inventoryEstimate
        if (inventoryEstimate > quota.inventoryMaxBytes) {
            violations.add(QuotaViolation("inventory", inventoryEstimate, quota.inventoryMaxBytes.toLong()))
        }

        // --- 世界数据 ---
        // gameData 中嵌套了 worldMapSects, alliances 等，粗略估算
        val worldEstimate = (data.gameData.worldMapSects.size * 500L) +
            (data.alliances.size * 200L)
        estimatedTotal += worldEstimate
        if (worldEstimate > quota.worldMaxBytes) {
            violations.add(QuotaViolation("world", worldEstimate, quota.worldMaxBytes.toLong()))
        }

        // --- 战斗日志 ---
        val battleLogCount = data.battleLogs.size.toLong()
        // 单条战斗日志约 1-5KB（含 rounds/actions）
        val combatEstimate = battleLogCount * 3000L
        estimatedTotal += combatEstimate
        if (battleLogCount > quota.maxBattleLogCount) {
            violations.add(QuotaViolation("battle_log_count", battleLogCount, quota.maxBattleLogCount.toLong(), "items"))
        }
        if (combatEstimate > quota.combatMaxBytes) {
            violations.add(QuotaViolation("combat", combatEstimate, quota.combatMaxBytes.toLong()))
        }

        // --- 任务数据 ---
        val missionCount = (data.gameData.activeMissions.size + data.gameData.availableMissions.size).toLong()
        // 单个任务约 500B-1KB
        val missionEstimate = missionCount * 750L
        estimatedTotal += missionEstimate
        if (missionEstimate > quota.missionMaxBytes) {
            violations.add(QuotaViolation("missions", missionEstimate, quota.missionMaxBytes.toLong()))
        }

        // --- 探索队 / 事件 ---
        val miscEstimate = (data.teams.size * 400L) + (data.events.size * 300L)
        estimatedTotal += miscEstimate

        // --- 总计硬上限 ---
        if (estimatedTotal > quota.totalMaxBytes) {
            violations.add(QuotaViolation("total", estimatedTotal, quota.totalMaxBytes.toLong()))
        }

        return QuotaValidationResult(
            isValid = violations.isEmpty(),
            violations = violations,
            estimatedTotalBytes = estimatedTotal
        )
    }

    /**
     * 粗略估算 [SerializableGameData] 的序列化大小（字节）。
     *
     * 基于 ProtoBuf 编码规则和字段类型的近似计算。
     */
    private fun estimateGameDataSize(gameData: SerializableGameData): Long {
        var size = 512L // 固定基础开销（version/timestamp 等标量字段）

        // worldMapSects: 每个 Sect 约 300B-1KB
        size += gameData.worldMapSects.size * 600L

        // exploredSects: 每个 Map 条目约 200B
        size += gameData.exploredSects.size * 200L

        // scoutInfo: 每个 Scout 约 150B
        size += gameData.scoutInfo.size * 150L

        size += gameData.manualProficiencies.values.sumOf { it.size * 80L }

        // travelingMerchantItems: 每个 Item 约 100B
        size += gameData.travelingMerchantItems.size * 100L
        size += gameData.playerListedItems.size * 100L

        // recruitList: 仅 ID 列表
        size += gameData.recruitList.size * 24L

        // cultivatorCaves: 每个 Cave 约 200B
        size += gameData.cultivatorCaves.size * 200L

        // caveExplorationTeams / aiCaveTeams
        size += gameData.caveExplorationTeams.size * 150L
        size += gameData.aiCaveTeams.size * 180L

        // unlocked lists
        size += (gameData.unlockedDungeons.size + gameData.unlockedRecipes.size +
            gameData.unlockedManuals.size) * 20L

        // building slots
        size += (gameData.spiritMineSlots.size * 60L +
            gameData.librarySlots.size * 40L +
            gameData.productionSlots.size * 100L)

        // alliances / sectRelations
        size += gameData.alliances.size * 150L
        size += gameData.sectRelations.size * 60L

        // missions
        size += gameData.activeMissions.size * 500L
        size += gameData.availableMissions.size * 350L

        return size
    }

    // ========================================================================
    // 上下文推荐
    // ========================================================================

    fun getRecommendedContext(dataSize: Int, dataType: DataType): SerializationContext {
        return when (dataType) {
            DataType.HOT_DATA -> SerializationContext(
                format = SerializationFormat.PROTOBUF,
                compression = CompressionType.LZ4,
                compressThreshold = 512
            )
            DataType.COLD_DATA -> SerializationContext(
                format = SerializationFormat.PROTOBUF,
                compression = CompressionType.ZSTD,
                compressThreshold = 256
            )
            DataType.DELTA -> SerializationContext(
                format = SerializationFormat.PROTOBUF,
                compression = CompressionType.LZ4,
                compressThreshold = 128
            )
            DataType.PERFORMANCE_CRITICAL -> SerializationContext(
                format = SerializationFormat.PROTOBUF,
                compression = CompressionType.LZ4,
                compressThreshold = 256
            )
        }
    }
}

enum class DataType {
    HOT_DATA,
    COLD_DATA,
    DELTA,
    PERFORMANCE_CRITICAL
}

// ========================================================================
// From SaveDataConverter.kt
// ========================================================================

sealed class MigrationResult {
    data class Success(val data: SerializableSaveData) : MigrationResult()
    data class Failed(val error: Throwable, val fallbackData: SerializableSaveData? = null) : MigrationResult()
}

interface VersionMigrator {
    val fromVersion: String
    val toVersion: String
    suspend fun migrate(data: SerializableSaveData): SerializableSaveData
}

private inline fun <reified T : Enum<T>> safeEnumValueOf(
    value: String,
    defaultValue: T,
    fieldName: String,
    context: String = ""
): T {
    return try {
        enumValueOf<T>(value)
    } catch (e: IllegalArgumentException) {
        val validValues = enumValues<T>().map { it.name }
        Log.w("SaveDataConverter", "Invalid enum value '$value' for field '$fieldName' in $context. " +
              "Valid values: ${validValues.joinToString()}. Using default: $defaultValue")
        defaultValue
    }
}


private inline fun <reified T : Enum<T>> safeEnumValueOfIgnoreCase(
    value: String,
    defaultValue: T,
    fieldName: String,
    context: String = ""
): T {
    return try {
        enumValues<T>().find { it.name.equals(value, ignoreCase = true) } ?: run {
            val validValues = enumValues<T>().map { it.name }
            Log.w("SaveDataConverter", "Invalid enum value '$value' for field '$fieldName' in $context. " +
                  "Valid values: ${validValues.joinToString()}. Using default: $defaultValue")
            defaultValue
        }
    } catch (e: Exception) {
        Log.w("SaveDataConverter", "Error parsing enum value '$value' for field '$fieldName' in $context: ${e.message}")
        defaultValue
    }
}

@Singleton
class SaveDataMigrator @Inject constructor() {
    companion object {
        private const val TAG = "SaveDataMigrator"
        private const val CURRENT_VERSION = "4.0"
    }

    private val migrators = mutableListOf<VersionMigrator>()

    init {
        registerMigrator(V1ToV2Migrator())
        registerMigrator(V2ToV3Migrator())
        registerMigrator(V3ToV4Migrator())
    }

    fun registerMigrator(migrator: VersionMigrator) {
        migrators.add(migrator)
    }

    suspend fun migrate(data: SerializableSaveData): MigrationResult {
        var currentData = data

        try {
            while (currentData.version != CURRENT_VERSION) {
                val migrator = findMigrator(currentData.version)
                    ?: return MigrationResult.Failed(
                        IllegalStateException("No migrator found for version ${currentData.version}"),
                        currentData
                    )

                Log.i(TAG, "Migrating from ${migrator.fromVersion} to ${migrator.toVersion}")
                currentData = migrator.migrate(currentData)
            }

            return MigrationResult.Success(currentData)
        } catch (e: Exception) {
            Log.e(TAG, "Migration failed", e)
            return MigrationResult.Failed(e, currentData)
        }
    }

    fun needsMigration(version: String): Boolean {
        return version != CURRENT_VERSION
    }

    private fun findMigrator(fromVersion: String): VersionMigrator? {
        return migrators.find { it.fromVersion == fromVersion }
    }
}

class V1ToV2Migrator : VersionMigrator {
    override val fromVersion: String = "1.0"
    override val toVersion: String = "2.0"

    override suspend fun migrate(data: SerializableSaveData): SerializableSaveData {
        return data.copy(
            version = toVersion,
            gameData = data.gameData.copy(
                playerProtectionEnabled = data.gameData.playerProtectionEnabled,
                playerProtectionStartYear = data.gameData.playerProtectionStartYear
            )
        )
    }
}

class V2ToV3Migrator : VersionMigrator {
    override val fromVersion: String = "2.0"
    override val toVersion: String = "3.0"

    override suspend fun migrate(data: SerializableSaveData): SerializableSaveData {
        return data.copy(version = toVersion)
    }
}

class V3ToV4Migrator : VersionMigrator {
    override val fromVersion: String = "3.0"
    override val toVersion: String = "4.0"

    // 启发式判断：旧存档中 duration 以"月"为单位存储（1-12），
    // 新版本以"天"为单位存储（最小30），所以 >0 && <=12 的值需要乘以30
    private fun migrateDiscipleDuration(disciple: SerializableDisciple): SerializableDisciple {
        return disciple.copy(
            cultivationSpeedDuration = if (disciple.cultivationSpeedDuration > 0 && disciple.cultivationSpeedDuration <= 12)
                disciple.cultivationSpeedDuration * 30 else disciple.cultivationSpeedDuration,
            pillEffectDuration = if (disciple.pillEffectDuration > 0 && disciple.pillEffectDuration <= 12)
                disciple.pillEffectDuration * 30 else disciple.pillEffectDuration
        )
    }

    override suspend fun migrate(data: SerializableSaveData): SerializableSaveData {
        return data.copy(
            version = toVersion,
            disciples = data.disciples.map { migrateDiscipleDuration(it) },
            gameData = data.gameData.copy(
                recruitList = data.gameData.recruitList.map { migrateDiscipleDuration(it) },
                aiSectDisciples = data.gameData.aiSectDisciples.map { entry ->
                    entry.copy(disciples = entry.disciples.map { migrateDiscipleDuration(it) })
                }
            )
        )
    }
}

@Singleton
class SaveDataConverter @Inject constructor() {
    companion object {
        private const val TAG = "SaveDataConverter"
    }

    fun toSerializable(saveData: com.xianxia.sect.data.model.SaveData): SerializableSaveData {
        return SerializableSaveData(
            version = saveData.version ?: "1.0",
            timestamp = saveData.timestamp ?: System.currentTimeMillis(),
            gameData = convertGameData(saveData.gameData),
            disciples = saveData.disciples?.map { convertDisciple(it) } ?: emptyList(),
            equipment = saveData.equipmentInstances?.map { convertEquipment(it) } ?: emptyList(),
            manuals = saveData.manualInstances?.map { convertManual(it) } ?: emptyList(),
            pills = saveData.pills?.map { convertPill(it) } ?: emptyList(),
            materials = saveData.materials?.map { convertMaterial(it) } ?: emptyList(),
            herbs = saveData.herbs?.map { convertHerb(it) } ?: emptyList(),
            seeds = saveData.seeds?.map { convertSeed(it) } ?: emptyList(),
            teams = saveData.teams?.map { convertTeam(it) } ?: emptyList(),
            events = saveData.events?.map { convertEvent(it) } ?: emptyList(),
            battleLogs = saveData.battleLogs?.map { convertBattleLog(it) } ?: emptyList(),
            alliances = saveData.alliances?.map { convertAlliance(it) } ?: emptyList()
        )
    }

    fun fromSerializable(data: SerializableSaveData): com.xianxia.sect.data.model.SaveData {
        return com.xianxia.sect.data.model.SaveData(
            version = data.version,
            timestamp = data.timestamp,
            gameData = convertBackGameData(data.gameData),
            disciples = data.disciples.map { convertBackDisciple(it) },
            equipmentStacks = emptyList(),
            equipmentInstances = data.equipment.map { convertBackEquipment(it) },
            manualStacks = emptyList(),
            manualInstances = data.manuals.map { convertBackManual(it) },
            pills = data.pills.map { convertBackPill(it) },
            materials = data.materials.map { convertBackMaterial(it) },
            herbs = data.herbs.map { convertBackHerb(it) },
            seeds = data.seeds.map { convertBackSeed(it) },
            teams = data.teams.map { convertBackTeam(it) },
            events = data.events.map { convertBackEvent(it) },
            battleLogs = data.battleLogs.map { convertBackBattleLog(it) },
            alliances = data.alliances.map { convertBackAlliance(it) },
            productionSlots = data.gameData?.productionSlots?.map { convertBackProductionSlot(it) } ?: emptyList()
        )
    }

    private fun convertGameData(gameData: com.xianxia.sect.core.model.GameData?): SerializableGameData {
        if (gameData == null) return SerializableGameData()

        return SerializableGameData(
            sectName = gameData.sectName ?: "青云宗",
            currentSlot = gameData.currentSlot ?: 1,
            gameYear = gameData.gameYear ?: 1,
            gameMonth = gameData.gameMonth ?: 1,
            gameDay = gameData.gameDay ?: 1,
            spiritStones = gameData.spiritStones ?: 1000,
            spiritHerbs = gameData.spiritHerbs ?: 0,
            autoSaveIntervalMonths = gameData.autoSaveIntervalMonths ?: 3,
            monthlySalary = gameData.monthlySalary ?: emptyMap(),
            monthlySalaryEnabled = gameData.monthlySalaryEnabled ?: emptyMap(),
            worldMapSects = gameData.worldMapSects?.map { convertWorldSect(it, gameData.sectDetails?.get(it.id)) } ?: emptyList(),
            sectDetails = gameData.sectDetails?.mapValues { convertSectDetail(it.value) } ?: emptyMap(),
            exploredSects = gameData.exploredSects?.mapValues { convertExploredSectInfo(it.value) } ?: emptyMap(),
            scoutInfo = gameData.scoutInfo?.mapValues { convertSectScoutInfo(it.value) } ?: emptyMap(),
            manualProficiencies = gameData.manualProficiencies?.mapValues {
                it.value.map { prof -> convertManualProficiency(prof) }
            } ?: emptyMap(),
            travelingMerchantItems = gameData.travelingMerchantItems?.map { convertMerchantItem(it) } ?: emptyList(),
            merchantLastRefreshYear = gameData.merchantLastRefreshYear ?: 0,
            merchantRefreshCount = gameData.merchantRefreshCount ?: 0,
            playerListedItems = gameData.playerListedItems?.map { convertMerchantItem(it) } ?: emptyList(),
            recruitList = gameData.recruitList?.map { convertDisciple(it) } ?: emptyList(),
            lastRecruitYear = gameData.lastRecruitYear ?: 0,
            cultivatorCaves = gameData.cultivatorCaves?.map { convertCultivatorCave(it) } ?: emptyList(),
            caveExplorationTeams = gameData.caveExplorationTeams?.map { convertCaveExplorationTeam(it) } ?: emptyList(),
            aiCaveTeams = gameData.aiCaveTeams?.map { convertAICaveTeam(it) } ?: emptyList(),
            unlockedDungeons = gameData.unlockedDungeons ?: emptyList(),
            unlockedRecipes = gameData.unlockedRecipes ?: emptyList(),
            unlockedManuals = gameData.unlockedManuals ?: emptyList(),
            lastSaveTime = gameData.lastSaveTime ?: 0L,
            elderSlots = convertElderSlots(gameData.elderSlots),
            spiritMineSlots = gameData.spiritMineSlots?.map { convertSpiritMineSlot(it) } ?: emptyList(),
            librarySlots = gameData.librarySlots?.map { convertLibrarySlot(it) } ?: emptyList(),
            productionSlots = gameData.productionSlots?.map { convertProductionSlot(it) } ?: emptyList(),
            alliances = gameData.alliances?.map { convertAlliance(it) } ?: emptyList(),
            sectRelations = gameData.sectRelations?.map { convertSectRelation(it) } ?: emptyList(),
            playerAllianceSlots = gameData.playerAllianceSlots ?: 3,
            sectPolicies = convertSectPolicies(gameData.sectPolicies),
            // 战斗队伍：使用 NullSafeProtoBuf 的专用方法
            battleTeam = NullSafeProtoBuf.battleTeamToProto(gameData.battleTeam),
            aiBattleTeams = gameData.aiBattleTeams?.map { convertAIBattleTeam(it) } ?: emptyList(),
            usedRedeemCodes = gameData.usedRedeemCodes ?: emptyList(),
            playerProtectionEnabled = gameData.playerProtectionEnabled ?: true,
            playerProtectionStartYear = gameData.playerProtectionStartYear ?: 1,
            playerHasAttackedAI = gameData.playerHasAttackedAI ?: false,
            activeMissions = gameData.activeMissions?.map { convertActiveMission(it) } ?: emptyList(),
            availableMissions = gameData.availableMissions?.map { convertMission(it) } ?: emptyList(),
            aiSectDisciples = gameData.aiSectDisciples?.map { (sectId, disciples) ->
                SerializableAiSectDiscipleEntry(
                    sectId = sectId,
                    disciples = disciples.map { convertDisciple(it) }
                )
            } ?: emptyList(),
            smartBattleEnabled = gameData.smartBattleEnabled ?: false
        )
    }

    private fun convertBackGameData(data: SerializableGameData): com.xianxia.sect.core.model.GameData {
        return com.xianxia.sect.core.model.GameData(
            sectName = data.sectName,
            currentSlot = data.currentSlot,
            gameYear = data.gameYear,
            gameMonth = data.gameMonth,
            gameDay = data.gameDay,
            spiritStones = data.spiritStones,
            spiritHerbs = data.spiritHerbs,
            autoSaveIntervalMonths = data.autoSaveIntervalMonths,
            monthlySalary = data.monthlySalary,
            monthlySalaryEnabled = data.monthlySalaryEnabled,
            worldMapSects = data.worldMapSects.map { convertBackWorldSect(it) },
            sectDetails = if (data.sectDetails.isNotEmpty()) {
                data.sectDetails.mapValues { convertBackSectDetail(it.value) }
            } else {
                data.worldMapSects.associate { it.id to extractSectDetailFromWorldSect(it) }
            },
            exploredSects = data.exploredSects.mapValues { convertBackExploredSectInfo(it.value) },
            scoutInfo = data.scoutInfo.mapValues { convertBackSectScoutInfo(it.value) },
            manualProficiencies = data.manualProficiencies.mapValues {
                it.value.map { prof -> convertBackManualProficiency(prof) }
            },
            travelingMerchantItems = data.travelingMerchantItems.map { convertBackMerchantItem(it) },
            merchantLastRefreshYear = data.merchantLastRefreshYear,
            merchantRefreshCount = data.merchantRefreshCount,
            playerListedItems = data.playerListedItems.map { convertBackMerchantItem(it) },
            recruitList = data.recruitList.map { convertBackDisciple(it) },
            lastRecruitYear = data.lastRecruitYear,
            cultivatorCaves = data.cultivatorCaves.map { convertBackCultivatorCave(it) },
            caveExplorationTeams = data.caveExplorationTeams.map { convertBackCaveExplorationTeam(it) },
            aiCaveTeams = data.aiCaveTeams.map { convertBackAICaveTeam(it) },
            unlockedDungeons = data.unlockedDungeons,
            unlockedRecipes = data.unlockedRecipes,
            unlockedManuals = data.unlockedManuals,
            lastSaveTime = data.lastSaveTime,
            elderSlots = convertBackElderSlots(data.elderSlots),
            spiritMineSlots = data.spiritMineSlots.map { convertBackSpiritMineSlot(it) },
            librarySlots = data.librarySlots.map { convertBackLibrarySlot(it) },
            productionSlots = data.productionSlots.map { convertBackProductionSlot(it) },
            alliances = data.alliances.map { convertBackAlliance(it) },
            sectRelations = data.sectRelations.map { convertBackSectRelation(it) },
            playerAllianceSlots = data.playerAllianceSlots,
            sectPolicies = convertBackSectPolicies(data.sectPolicies),
            // 战斗队伍：使用 NullSafeProtoBuf 的反向转换方法
            battleTeam = NullSafeProtoBuf.battleTeamFromProto(data.battleTeam),
            aiBattleTeams = data.aiBattleTeams.map { convertBackAIBattleTeam(it) },
            usedRedeemCodes = data.usedRedeemCodes,
            playerProtectionEnabled = data.playerProtectionEnabled,
            playerProtectionStartYear = data.playerProtectionStartYear,
            playerHasAttackedAI = data.playerHasAttackedAI,
            activeMissions = data.activeMissions.map { convertBackActiveMission(it) },
            availableMissions = data.availableMissions.map { convertBackMission(it) },
            aiSectDisciples = data.aiSectDisciples.associate { entry ->
                entry.sectId to entry.disciples.map { convertBackDisciple(it) }
            },
            smartBattleEnabled = data.smartBattleEnabled
        )
    }

    private fun convertDisciple(disciple: com.xianxia.sect.core.model.Disciple): SerializableDisciple {
        return SerializableDisciple(
            id = NullSafeProtoBuf.stringToProto(disciple.id),
            name = NullSafeProtoBuf.stringToProto(disciple.name),
            surname = NullSafeProtoBuf.stringToProto(disciple.surname),
            realm = disciple.realm ?: 0,
            realmLayer = disciple.realmLayer ?: 0,
            cultivation = disciple.cultivation ?: 0.0,
            spiritRootType = NullSafeProtoBuf.stringToProto(disciple.spiritRootType),
            age = disciple.age ?: 0,
            lifespan = disciple.lifespan ?: 0,
            isAlive = disciple.isAlive ?: true,
            gender = NullSafeProtoBuf.stringToProto(disciple.gender, "男"),
            // 关系字段：使用 relationIdToProto/relationIdFromProto
            partnerId = NullSafeProtoBuf.relationIdToProto(disciple.social.partnerId),
            partnerSectId = NullSafeProtoBuf.relationIdToProto(disciple.social.partnerSectId),
            parentId1 = NullSafeProtoBuf.relationIdToProto(disciple.social.parentId1),
            parentId2 = NullSafeProtoBuf.relationIdToProto(disciple.social.parentId2),
            lastChildYear = disciple.social.lastChildYear ?: 0,
            // 悲伤期结束年份：使用专用方法（哨兵值 -1）
            griefEndYear = NullSafeProtoBuf.griefEndYearToProto(disciple.social.griefEndYear),
            // 装备 ID 字段：使用 equipmentIdToProto/equipmentIdFromProto
            weaponId = NullSafeProtoBuf.equipmentIdToProto(disciple.equipment.weaponId),
            armorId = NullSafeProtoBuf.equipmentIdToProto(disciple.equipment.armorId),
            bootsId = NullSafeProtoBuf.equipmentIdToProto(disciple.equipment.bootsId),
            accessoryId = NullSafeProtoBuf.equipmentIdToProto(disciple.equipment.accessoryId),
            // 列表和 Map 类型
            manualIds = NullSafeProtoBuf.listToProto(disciple.manualIds),
            talentIds = NullSafeProtoBuf.listToProto(disciple.talentIds),
            manualMasteries = NullSafeProtoBuf.mapToProto(disciple.manualMasteries),
            // 装备培养数据：使用专用方法
            weaponNurture = NullSafeProtoBuf.nurtureDataToProto(disciple.equipment.weaponNurture),
            armorNurture = NullSafeProtoBuf.nurtureDataToProto(disciple.equipment.armorNurture),
            bootsNurture = NullSafeProtoBuf.nurtureDataToProto(disciple.equipment.bootsNurture),
            accessoryNurture = NullSafeProtoBuf.nurtureDataToProto(disciple.equipment.accessoryNurture),
            // 数值字段
            spiritStones = disciple.equipment.spiritStones ?: 0,
            soulPower = disciple.equipment.soulPower ?: 0,
            storageBagItems = NullSafeProtoBuf.listToProto(disciple.equipment.storageBagItems)?.map { convertStorageBagItem(it) } ?: emptyList(),
            storageBagSpiritStones = disciple.equipment.storageBagSpiritStones ?: 0L,
            status = disciple.status.name,
            statusData = NullSafeProtoBuf.mapToProto(disciple.statusData),
            cultivationSpeedBonus = disciple.cultivationSpeedBonus ?: 0.0,
            cultivationSpeedDuration = disciple.cultivationSpeedDuration ?: 0,
            pillPhysicalAttackBonus = disciple.pillEffects.pillPhysicalAttackBonus ?: 0,
            pillMagicAttackBonus = disciple.pillEffects.pillMagicAttackBonus ?: 0,
            pillPhysicalDefenseBonus = disciple.pillEffects.pillPhysicalDefenseBonus ?: 0,
            pillMagicDefenseBonus = disciple.pillEffects.pillMagicDefenseBonus ?: 0,
            pillHpBonus = disciple.pillEffects.pillHpBonus ?: 0,
            pillMpBonus = disciple.pillEffects.pillMpBonus ?: 0,
            pillSpeedBonus = disciple.pillEffects.pillSpeedBonus ?: 0,
            pillCritRateBonus = disciple.pillEffects.pillCritRateBonus ?: 0.0,
            pillCritEffectBonus = disciple.pillEffects.pillCritEffectBonus ?: 0.0,
            pillCultivationSpeedBonus = disciple.pillEffects.pillCultivationSpeedBonus ?: 0.0,
            pillSkillExpSpeedBonus = disciple.pillEffects.pillSkillExpSpeedBonus ?: 0.0,
            pillNurtureSpeedBonus = disciple.pillEffects.pillNurtureSpeedBonus ?: 0.0,
            pillEffectDuration = disciple.pillEffects.pillEffectDuration ?: 0,
            activePillCategory = disciple.pillEffects.activePillCategory ?: "",
            totalCultivation = disciple.combat.totalCultivation ?: 0L,
            breakthroughCount = disciple.combat.breakthroughCount ?: 0,
            breakthroughFailCount = disciple.combat.breakthroughFailCount ?: 0,
            intelligence = disciple.skills.intelligence ?: 0,
            charm = disciple.skills.charm ?: 0,
            loyalty = disciple.skills.loyalty ?: 0,
            comprehension = disciple.skills.comprehension ?: 0,
            artifactRefining = disciple.skills.artifactRefining ?: 0,
            pillRefining = disciple.skills.pillRefining ?: 0,
            spiritPlanting = disciple.skills.spiritPlanting ?: 0,
            teaching = disciple.skills.teaching ?: 0,
            morality = disciple.skills.morality ?: 0,
            salaryPaidCount = disciple.skills.salaryPaidCount ?: 0,
            salaryMissedCount = disciple.skills.salaryMissedCount ?: 0,
            recruitedMonth = disciple.usage.recruitedMonth ?: 0,
            hpVariance = disciple.combat.hpVariance ?: 0,
            mpVariance = disciple.combat.mpVariance ?: 0,
            physicalAttackVariance = disciple.combat.physicalAttackVariance ?: 0,
            magicAttackVariance = disciple.combat.magicAttackVariance ?: 0,
            physicalDefenseVariance = disciple.combat.physicalDefenseVariance ?: 0,
            magicDefenseVariance = disciple.combat.magicDefenseVariance ?: 0,
            speedVariance = disciple.combat.speedVariance ?: 0,
            baseHp = disciple.combat.baseHp ?: 0,
            baseMp = disciple.combat.baseMp ?: 0,
            basePhysicalAttack = disciple.combat.basePhysicalAttack ?: 0,
            baseMagicAttack = disciple.combat.baseMagicAttack ?: 0,
            basePhysicalDefense = disciple.combat.basePhysicalDefense ?: 0,
            baseMagicDefense = disciple.combat.baseMagicDefense ?: 0,
            baseSpeed = disciple.combat.baseSpeed ?: 0,
            discipleType = NullSafeProtoBuf.stringToProto(disciple.discipleType, "outer"),
            usedFunctionalPillTypes = NullSafeProtoBuf.listToProto(disciple.usage.usedFunctionalPillTypes),
            usedExtendLifePillIds = NullSafeProtoBuf.listToProto(disciple.usage.usedExtendLifePillIds),
            hasReviveEffect = disciple.usage.hasReviveEffect ?: false,
            hasClearAllEffect = disciple.usage.hasClearAllEffect ?: false,
            currentHp = disciple.combat.currentHp,
            currentMp = disciple.combat.currentMp
        )
    }

    private fun convertBackDisciple(data: SerializableDisciple): com.xianxia.sect.core.model.Disciple {
        // 使用 NullSafeProtoBuf 的反向转换方法
        val weaponId = NullSafeProtoBuf.equipmentIdFromProto(data.weaponId)
        val armorId = NullSafeProtoBuf.equipmentIdFromProto(data.armorId)
        val bootsId = NullSafeProtoBuf.equipmentIdFromProto(data.bootsId)
        val accessoryId = NullSafeProtoBuf.equipmentIdFromProto(data.accessoryId)

        val weaponNurture = NullSafeProtoBuf.nurtureDataFromProto(data.weaponNurture)
        val armorNurture = NullSafeProtoBuf.nurtureDataFromProto(data.armorNurture)
        val bootsNurture = NullSafeProtoBuf.nurtureDataFromProto(data.bootsNurture)
        val accessoryNurture = NullSafeProtoBuf.nurtureDataFromProto(data.accessoryNurture)

        // 关系字段：使用 relationIdFromProto
        val partnerId = NullSafeProtoBuf.relationIdFromProto(data.partnerId)
        val partnerSectId = NullSafeProtoBuf.relationIdFromProto(data.partnerSectId)
        val parentId1 = NullSafeProtoBuf.relationIdFromProto(data.parentId1)
        val parentId2 = NullSafeProtoBuf.relationIdFromProto(data.parentId2)

        // 悲伤期结束年份：使用专用方法（哨兵值 -1）
        val griefEndYear = NullSafeProtoBuf.griefEndYearFromProto(data.griefEndYear)

        return com.xianxia.sect.core.model.Disciple(
            id = data.id,
            name = data.name,
            surname = data.surname,
            realm = data.realm,
            realmLayer = data.realmLayer,
            cultivation = data.cultivation,
            spiritRootType = data.spiritRootType,
            age = data.age,
            lifespan = data.lifespan,
            isAlive = data.isAlive,
            gender = data.gender,
            manualIds = data.manualIds,
            talentIds = data.talentIds,
            manualMasteries = data.manualMasteries,
            status = safeEnumValueOf(data.status, com.xianxia.sect.core.model.DiscipleStatus.IDLE, "status", "Disciple"),
            statusData = data.statusData,
            cultivationSpeedBonus = data.cultivationSpeedBonus,
            cultivationSpeedDuration = data.cultivationSpeedDuration,
            discipleType = data.discipleType.ifEmpty { "outer" },
            combat = com.xianxia.sect.core.model.CombatAttributes(
                baseHp = data.baseHp,
                baseMp = data.baseMp,
                basePhysicalAttack = data.basePhysicalAttack,
                baseMagicAttack = data.baseMagicAttack,
                basePhysicalDefense = data.basePhysicalDefense,
                baseMagicDefense = data.baseMagicDefense,
                baseSpeed = data.baseSpeed,
                hpVariance = data.hpVariance,
                mpVariance = data.mpVariance,
                physicalAttackVariance = data.physicalAttackVariance,
                magicAttackVariance = data.magicAttackVariance,
                physicalDefenseVariance = data.physicalDefenseVariance,
                magicDefenseVariance = data.magicDefenseVariance,
                speedVariance = data.speedVariance,
                totalCultivation = data.totalCultivation,
                breakthroughCount = data.breakthroughCount,
                breakthroughFailCount = data.breakthroughFailCount,
                currentHp = data.currentHp,
                currentMp = data.currentMp
            ),
            pillEffects = com.xianxia.sect.core.model.PillEffects(
                pillPhysicalAttackBonus = data.pillPhysicalAttackBonus,
                pillMagicAttackBonus = data.pillMagicAttackBonus,
                pillPhysicalDefenseBonus = data.pillPhysicalDefenseBonus,
                pillMagicDefenseBonus = data.pillMagicDefenseBonus,
                pillHpBonus = data.pillHpBonus,
                pillMpBonus = data.pillMpBonus,
                pillSpeedBonus = data.pillSpeedBonus,
                pillCritRateBonus = data.pillCritRateBonus,
                pillCritEffectBonus = data.pillCritEffectBonus,
                pillCultivationSpeedBonus = data.pillCultivationSpeedBonus,
                pillSkillExpSpeedBonus = data.pillSkillExpSpeedBonus,
                pillNurtureSpeedBonus = data.pillNurtureSpeedBonus,
                pillEffectDuration = data.pillEffectDuration,
                activePillCategory = data.activePillCategory
            ),
            equipment = com.xianxia.sect.core.model.EquipmentSet(
                weaponId = weaponId ?: "",
                armorId = armorId ?: "",
                bootsId = bootsId ?: "",
                accessoryId = accessoryId ?: "",
                weaponNurture = weaponNurture ?: com.xianxia.sect.core.model.EquipmentNurtureData("", 0),
                armorNurture = armorNurture ?: com.xianxia.sect.core.model.EquipmentNurtureData("", 0),
                bootsNurture = bootsNurture ?: com.xianxia.sect.core.model.EquipmentNurtureData("", 0),
                accessoryNurture = accessoryNurture ?: com.xianxia.sect.core.model.EquipmentNurtureData("", 0),
                storageBagItems = data.storageBagItems.map { convertBackStorageBagItem(it) },
                storageBagSpiritStones = data.storageBagSpiritStones,
                spiritStones = data.spiritStones,
                soulPower = data.soulPower
            ),
            social = com.xianxia.sect.core.model.SocialData(
                partnerId = partnerId,
                partnerSectId = partnerSectId,
                parentId1 = parentId1,
                parentId2 = parentId2,
                lastChildYear = data.lastChildYear,
                griefEndYear = griefEndYear
            ),
            skills = com.xianxia.sect.core.model.SkillStats(
                intelligence = data.intelligence,
                charm = data.charm,
                loyalty = data.loyalty,
                comprehension = data.comprehension,
                artifactRefining = data.artifactRefining,
                pillRefining = data.pillRefining,
                spiritPlanting = data.spiritPlanting,
                teaching = data.teaching,
                morality = data.morality,
                salaryPaidCount = data.salaryPaidCount,
                salaryMissedCount = data.salaryMissedCount
            ),
            usage = com.xianxia.sect.core.model.UsageTracking(
                usedFunctionalPillTypes = data.usedFunctionalPillTypes,
                usedExtendLifePillIds = data.usedExtendLifePillIds,
                recruitedMonth = data.recruitedMonth,
                hasReviveEffect = data.hasReviveEffect,
                hasClearAllEffect = data.hasClearAllEffect
            )
        )
    }

    private fun convertEquipmentNurture(data: com.xianxia.sect.core.model.EquipmentNurtureData): SerializableEquipmentNurtureData {
        return SerializableEquipmentNurtureData(
            equipmentId = data.equipmentId ?: "",
            rarity = data.rarity ?: 0,
            nurtureLevel = data.nurtureLevel ?: 0,
            nurtureProgress = data.nurtureProgress ?: 0.0
        )
    }

    private fun convertBackEquipmentNurture(data: SerializableEquipmentNurtureData): com.xianxia.sect.core.model.EquipmentNurtureData {
        return com.xianxia.sect.core.model.EquipmentNurtureData(
            equipmentId = data.equipmentId,
            rarity = data.rarity,
            nurtureLevel = data.nurtureLevel,
            nurtureProgress = data.nurtureProgress
        )
    }

    private fun convertStorageBagItem(item: com.xianxia.sect.core.model.StorageBagItem): SerializableStorageBagItem {
        return SerializableStorageBagItem(
            itemId = item.itemId ?: "",
            itemType = item.itemType ?: "",
            name = item.name ?: "",
            rarity = item.rarity ?: 0,
            quantity = item.quantity ?: 1,
            obtainedYear = item.obtainedYear ?: 1,
            obtainedMonth = item.obtainedMonth ?: 1,
            effect = item.effect?.let { convertItemEffect(it) } ?: SerializableItemEffect(),
            grade = item.grade ?: "",
            forgetYear = item.forgetYear ?: 0,
            forgetMonth = item.forgetMonth ?: 0,
            forgetDay = item.forgetDay ?: 0
        )
    }

    private fun convertBackStorageBagItem(data: SerializableStorageBagItem): com.xianxia.sect.core.model.StorageBagItem {
        val effect = data.effect.takeIf { it.cultivationSpeedPercent != 0.0 || it.skillExpSpeedPercent != 0.0 || it.nurtureSpeedPercent != 0.0 || it.breakthroughChance != 0.0 || it.targetRealm != 0 || it.cultivationAdd != 0 || it.skillExpAdd != 0 || it.nurtureAdd != 0 || it.healMaxHpPercent != 0.0 || it.mpRecoverMaxMpPercent != 0.0 || it.hpAdd != 0 || it.mpAdd != 0 || it.extendLife != 0 || it.physicalAttackAdd != 0 || it.magicAttackAdd != 0 || it.physicalDefenseAdd != 0 || it.magicDefenseAdd != 0 || it.speedAdd != 0 || it.critRateAdd != 0.0 || it.critEffectAdd != 0.0 || it.intelligenceAdd != 0 || it.charmAdd != 0 || it.loyaltyAdd != 0 || it.comprehensionAdd != 0 || it.artifactRefiningAdd != 0 || it.pillRefiningAdd != 0 || it.spiritPlantingAdd != 0 || it.teachingAdd != 0 || it.moralityAdd != 0 || it.revive || it.clearAll || it.duration != 0 || it.minRealm != 9 || it.pillCategory.isNotEmpty() || it.pillType.isNotEmpty() }?.let { convertBackItemEffect(it) }

        return com.xianxia.sect.core.model.StorageBagItem(
            itemId = data.itemId,
            itemType = data.itemType,
            name = data.name,
            rarity = data.rarity,
            quantity = data.quantity,
            obtainedYear = data.obtainedYear,
            obtainedMonth = data.obtainedMonth,
            effect = effect,
            grade = data.grade.takeIf { it.isNotEmpty() },
            forgetYear = data.forgetYear.takeIf { it > 0 },
            forgetMonth = data.forgetMonth.takeIf { it > 0 },
            forgetDay = data.forgetDay.takeIf { it > 0 }
        )
    }

    private fun convertItemEffect(effect: com.xianxia.sect.core.model.ItemEffect): SerializableItemEffect {
        return SerializableItemEffect(
            cultivationSpeedPercent = effect.cultivationSpeedPercent ?: 0.0,
            skillExpSpeedPercent = effect.skillExpSpeedPercent ?: 0.0,
            nurtureSpeedPercent = effect.nurtureSpeedPercent ?: 0.0,
            breakthroughChance = effect.breakthroughChance ?: 0.0,
            targetRealm = effect.targetRealm ?: 0,
            cultivationAdd = effect.cultivationAdd ?: 0,
            skillExpAdd = effect.skillExpAdd ?: 0,
            nurtureAdd = effect.nurtureAdd ?: 0,
            healMaxHpPercent = effect.healMaxHpPercent ?: 0.0,
            mpRecoverMaxMpPercent = effect.mpRecoverMaxMpPercent ?: 0.0,
            hpAdd = effect.hpAdd ?: 0,
            mpAdd = effect.mpAdd ?: 0,
            extendLife = effect.extendLife ?: 0,
            physicalAttackAdd = effect.physicalAttackAdd ?: 0,
            magicAttackAdd = effect.magicAttackAdd ?: 0,
            physicalDefenseAdd = effect.physicalDefenseAdd ?: 0,
            magicDefenseAdd = effect.magicDefenseAdd ?: 0,
            speedAdd = effect.speedAdd ?: 0,
            critRateAdd = effect.critRateAdd ?: 0.0,
            critEffectAdd = effect.critEffectAdd ?: 0.0,
            intelligenceAdd = effect.intelligenceAdd ?: 0,
            charmAdd = effect.charmAdd ?: 0,
            loyaltyAdd = effect.loyaltyAdd ?: 0,
            comprehensionAdd = effect.comprehensionAdd ?: 0,
            artifactRefiningAdd = effect.artifactRefiningAdd ?: 0,
            pillRefiningAdd = effect.pillRefiningAdd ?: 0,
            spiritPlantingAdd = effect.spiritPlantingAdd ?: 0,
            teachingAdd = effect.teachingAdd ?: 0,
            moralityAdd = effect.moralityAdd ?: 0,
            revive = effect.revive ?: false,
            clearAll = effect.clearAll ?: false,
            isAscension = effect.isAscension ?: false,
            duration = effect.duration ?: 0,
            cannotStack = effect.cannotStack ?: true,
            minRealm = effect.minRealm ?: 9,
            pillCategory = effect.pillCategory ?: "",
            pillType = effect.pillType ?: ""
        )
    }

    private fun convertBackItemEffect(data: SerializableItemEffect): com.xianxia.sect.core.model.ItemEffect {
        return com.xianxia.sect.core.model.ItemEffect(
            cultivationSpeedPercent = data.cultivationSpeedPercent,
            skillExpSpeedPercent = data.skillExpSpeedPercent,
            nurtureSpeedPercent = data.nurtureSpeedPercent,
            breakthroughChance = data.breakthroughChance,
            targetRealm = data.targetRealm,
            cultivationAdd = data.cultivationAdd,
            skillExpAdd = data.skillExpAdd,
            nurtureAdd = data.nurtureAdd,
            healMaxHpPercent = data.healMaxHpPercent,
            mpRecoverMaxMpPercent = data.mpRecoverMaxMpPercent,
            hpAdd = data.hpAdd,
            mpAdd = data.mpAdd,
            extendLife = data.extendLife,
            physicalAttackAdd = data.physicalAttackAdd,
            magicAttackAdd = data.magicAttackAdd,
            physicalDefenseAdd = data.physicalDefenseAdd,
            magicDefenseAdd = data.magicDefenseAdd,
            speedAdd = data.speedAdd,
            critRateAdd = data.critRateAdd,
            critEffectAdd = data.critEffectAdd,
            intelligenceAdd = data.intelligenceAdd,
            charmAdd = data.charmAdd,
            loyaltyAdd = data.loyaltyAdd,
            comprehensionAdd = data.comprehensionAdd,
            artifactRefiningAdd = data.artifactRefiningAdd,
            pillRefiningAdd = data.pillRefiningAdd,
            spiritPlantingAdd = data.spiritPlantingAdd,
            teachingAdd = data.teachingAdd,
            moralityAdd = data.moralityAdd,
            revive = data.revive,
            clearAll = data.clearAll,
            isAscension = data.isAscension,
            duration = data.duration,
            cannotStack = data.cannotStack,
            minRealm = data.minRealm,
            pillCategory = data.pillCategory,
            pillType = data.pillType
        )
    }

    private fun convertEquipment(equipment: com.xianxia.sect.core.model.EquipmentInstance): SerializableEquipment {
        return SerializableEquipment(
            id = equipment.id,
            name = equipment.name,
            type = equipment.slot.name,
            rarity = equipment.rarity,
            level = equipment.nurtureLevel,
            stats = mapOf(
                "physicalAttack" to equipment.physicalAttack,
                "magicAttack" to equipment.magicAttack,
                "physicalDefense" to equipment.physicalDefense,
                "magicDefense" to equipment.magicDefense,
                "speed" to equipment.speed,
                "hp" to equipment.hp,
                "mp" to equipment.mp
            ),
            description = equipment.description,
            critChance = equipment.critChance,
            isEquipped = equipment.isEquipped,
            nurtureLevel = equipment.nurtureLevel,
            nurtureProgress = equipment.nurtureProgress,
            minRealm = equipment.minRealm,
            ownerId = equipment.ownerId ?: "",
            quantity = 1
        )
    }

    private fun convertBackEquipment(data: SerializableEquipment): com.xianxia.sect.core.model.EquipmentInstance {
        val ownerId = data.ownerId.ifEmpty { null }

        return com.xianxia.sect.core.model.EquipmentInstance(
            id = data.id,
            name = data.name,
            rarity = data.rarity,
            slot = safeEnumValueOf(data.type, com.xianxia.sect.core.model.EquipmentSlot.WEAPON, "type", "Equipment"),
            physicalAttack = data.stats["physicalAttack"] ?: 0,
            magicAttack = data.stats["magicAttack"] ?: 0,
            physicalDefense = data.stats["physicalDefense"] ?: 0,
            magicDefense = data.stats["magicDefense"] ?: 0,
            speed = data.stats["speed"] ?: 0,
            hp = data.stats["hp"] ?: 0,
            mp = data.stats["mp"] ?: 0,
            description = data.description,
            critChance = data.critChance,
            nurtureLevel = data.nurtureLevel,
            nurtureProgress = data.nurtureProgress,
            minRealm = data.minRealm ?: 9,
            ownerId = ownerId,
            isEquipped = data.isEquipped
        )
    }

    private fun convertManual(manual: com.xianxia.sect.core.model.ManualInstance): SerializableManual {
        return SerializableManual(
            id = manual.id,
            name = manual.name,
            type = manual.type.name,
            rarity = manual.rarity,
            stats = manual.stats,
            description = manual.description
        )
    }

    private fun convertBackManual(data: SerializableManual): com.xianxia.sect.core.model.ManualInstance {
        return com.xianxia.sect.core.model.ManualInstance(
            id = data.id,
            name = data.name,
            rarity = data.rarity,
            type = safeEnumValueOf(data.type, com.xianxia.sect.core.model.ManualType.MIND, "type", "Manual"),
            stats = data.stats,
            description = data.description,
            ownerId = null,
            isLearned = false
        )
    }

    private fun convertPill(pill: com.xianxia.sect.core.model.Pill): SerializablePill {
        return SerializablePill(
            id = pill.id,
            name = pill.name,
            type = pill.category.name,
            rarity = pill.rarity,
            effects = mapOf(
                "breakthroughChance" to pill.breakthroughChance,
                "targetRealm" to pill.targetRealm.toDouble(),
                "isAscension" to if (pill.isAscension) 1.0 else 0.0,
                "cultivationSpeedPercent" to pill.cultivationSpeedPercent,
                "skillExpSpeedPercent" to pill.skillExpSpeedPercent,
                "nurtureSpeedPercent" to pill.nurtureSpeedPercent,
                "duration" to pill.duration.toDouble(),
                "cannotStack" to if (pill.cannotStack) 1.0 else 0.0,
                "cultivationAdd" to pill.cultivationAdd.toDouble(),
                "skillExpAdd" to pill.skillExpAdd.toDouble(),
                "nurtureAdd" to pill.nurtureAdd.toDouble(),
                "extendLife" to pill.extendLife.toDouble(),
                "physicalAttackAdd" to pill.physicalAttackAdd.toDouble(),
                "magicAttackAdd" to pill.magicAttackAdd.toDouble(),
                "physicalDefenseAdd" to pill.physicalDefenseAdd.toDouble(),
                "magicDefenseAdd" to pill.magicDefenseAdd.toDouble(),
                "hpAdd" to pill.hpAdd.toDouble(),
                "mpAdd" to pill.mpAdd.toDouble(),
                "speedAdd" to pill.speedAdd.toDouble(),
                "critRateAdd" to pill.critRateAdd,
                "critEffectAdd" to pill.critEffectAdd,
                "healMaxHpPercent" to pill.healMaxHpPercent,
                "mpRecoverMaxMpPercent" to pill.mpRecoverMaxMpPercent,
                "intelligenceAdd" to pill.intelligenceAdd.toDouble(),
                "charmAdd" to pill.charmAdd.toDouble(),
                "loyaltyAdd" to pill.loyaltyAdd.toDouble(),
                "comprehensionAdd" to pill.comprehensionAdd.toDouble(),
                "artifactRefiningAdd" to pill.artifactRefiningAdd.toDouble(),
                "pillRefiningAdd" to pill.pillRefiningAdd.toDouble(),
                "spiritPlantingAdd" to pill.spiritPlantingAdd.toDouble(),
                "teachingAdd" to pill.teachingAdd.toDouble(),
                "moralityAdd" to pill.moralityAdd.toDouble(),
                "revive" to if (pill.revive) 1.0 else 0.0,
                "clearAll" to if (pill.clearAll) 1.0 else 0.0,
                "minRealm" to pill.minRealm.toDouble(),
                "grade" to pill.grade.ordinal.toDouble(),
                "pillType" to 0.0
            ),
            description = pill.description,
            quantity = pill.quantity
        )
    }

    private fun convertBackPill(data: SerializablePill): com.xianxia.sect.core.model.Pill {
        return com.xianxia.sect.core.model.Pill(
            id = data.id,
            name = data.name,
            rarity = data.rarity,
            category = safeEnumValueOf(data.type, com.xianxia.sect.core.model.PillCategory.CULTIVATION, "type", "Pill"),
            breakthroughChance = data.effects["breakthroughChance"] ?: 0.0,
            targetRealm = (data.effects["targetRealm"] ?: 0.0).toInt(),
            isAscension = (data.effects["isAscension"] ?: 0.0) > 0.5,
            cultivationSpeedPercent = data.effects["cultivationSpeedPercent"] ?: 0.0,
            skillExpSpeedPercent = data.effects["skillExpSpeedPercent"] ?: 0.0,
            nurtureSpeedPercent = data.effects["nurtureSpeedPercent"] ?: 0.0,
            duration = (data.effects["duration"] ?: 0.0).toInt(),
            cannotStack = (data.effects["cannotStack"] ?: 0.0) > 0.5,
            cultivationAdd = (data.effects["cultivationAdd"] ?: 0.0).toInt(),
            skillExpAdd = (data.effects["skillExpAdd"] ?: 0.0).toInt(),
            nurtureAdd = (data.effects["nurtureAdd"] ?: 0.0).toInt(),
            extendLife = (data.effects["extendLife"] ?: 0.0).toInt(),
            physicalAttackAdd = (data.effects["physicalAttackAdd"] ?: 0.0).toInt(),
            magicAttackAdd = (data.effects["magicAttackAdd"] ?: 0.0).toInt(),
            physicalDefenseAdd = (data.effects["physicalDefenseAdd"] ?: 0.0).toInt(),
            magicDefenseAdd = (data.effects["magicDefenseAdd"] ?: 0.0).toInt(),
            hpAdd = (data.effects["hpAdd"] ?: 0.0).toInt(),
            mpAdd = (data.effects["mpAdd"] ?: 0.0).toInt(),
            speedAdd = (data.effects["speedAdd"] ?: 0.0).toInt(),
            critRateAdd = data.effects["critRateAdd"] ?: 0.0,
            critEffectAdd = data.effects["critEffectAdd"] ?: 0.0,
            healMaxHpPercent = data.effects["healMaxHpPercent"] ?: 0.0,
            mpRecoverMaxMpPercent = data.effects["mpRecoverMaxMpPercent"] ?: 0.0,
            intelligenceAdd = (data.effects["intelligenceAdd"] ?: 0.0).toInt(),
            charmAdd = (data.effects["charmAdd"] ?: 0.0).toInt(),
            loyaltyAdd = (data.effects["loyaltyAdd"] ?: 0.0).toInt(),
            comprehensionAdd = (data.effects["comprehensionAdd"] ?: 0.0).toInt(),
            artifactRefiningAdd = (data.effects["artifactRefiningAdd"] ?: 0.0).toInt(),
            pillRefiningAdd = (data.effects["pillRefiningAdd"] ?: 0.0).toInt(),
            spiritPlantingAdd = (data.effects["spiritPlantingAdd"] ?: 0.0).toInt(),
            teachingAdd = (data.effects["teachingAdd"] ?: 0.0).toInt(),
            moralityAdd = (data.effects["moralityAdd"] ?: 0.0).toInt(),
            revive = (data.effects["revive"] ?: 0.0) > 0.5,
            clearAll = (data.effects["clearAll"] ?: 0.0) > 0.5,
            minRealm = (data.effects["minRealm"] ?: 9.0).toInt(),
            grade = com.xianxia.sect.core.model.PillGrade.entries.getOrNull((data.effects["grade"] ?: 1.0).toInt()) ?: com.xianxia.sect.core.model.PillGrade.MEDIUM,
            description = data.description,
            quantity = data.quantity
        )
    }

    private fun convertMaterial(material: com.xianxia.sect.core.model.Material): SerializableMaterial {
        return SerializableMaterial(
            id = material.id,
            name = material.name,
            type = material.category.name,
            rarity = material.rarity,
            quantity = material.quantity,
            description = material.description
        )
    }

    private fun convertBackMaterial(data: SerializableMaterial): com.xianxia.sect.core.model.Material {
        return com.xianxia.sect.core.model.Material(
            id = data.id,
            name = data.name,
            rarity = data.rarity,
            category = safeEnumValueOf(data.type, com.xianxia.sect.core.model.MaterialCategory.BEAST_HIDE, "type", "Material"),
            quantity = data.quantity,
            description = data.description
        )
    }

    private fun convertHerb(herb: com.xianxia.sect.core.model.Herb): SerializableHerb {
        return SerializableHerb(
            id = herb.id,
            name = herb.name,
            rarity = herb.rarity,
            quantity = herb.quantity,
            age = 0,
            description = herb.description
        )
    }

    private fun convertBackHerb(data: SerializableHerb): com.xianxia.sect.core.model.Herb {
        return com.xianxia.sect.core.model.Herb(
            id = data.id,
            name = data.name,
            rarity = data.rarity,
            quantity = data.quantity,
            description = data.description
        )
    }

    private fun convertSeed(seed: com.xianxia.sect.core.model.Seed): SerializableSeed {
        return SerializableSeed(
            id = seed.id,
            name = seed.name,
            rarity = seed.rarity,
            growTime = seed.growTime,
            yieldMin = seed.yield,
            yieldMax = seed.yield,
            quantity = seed.quantity,
            description = seed.description
        )
    }

    private fun convertBackSeed(data: SerializableSeed): com.xianxia.sect.core.model.Seed {
        return com.xianxia.sect.core.model.Seed(
            id = data.id,
            name = data.name,
            rarity = data.rarity,
            growTime = data.growTime,
            yield = data.yieldMin,
            quantity = data.quantity,
            description = data.description
        )
    }

    private fun convertTeam(team: com.xianxia.sect.core.model.ExplorationTeam): SerializableExplorationTeam {
        return SerializableExplorationTeam(
            id = team.id,
            name = team.name,
            memberIds = team.memberIds,
            status = team.status.name,
            targetSectId = team.scoutTargetSectId ?: "",
            startYear = team.startYear,
            startMonth = team.startMonth,
            duration = team.duration,
            currentProgress = team.progress
        )
    }

    private fun convertBackTeam(data: SerializableExplorationTeam): com.xianxia.sect.core.model.ExplorationTeam {
        return com.xianxia.sect.core.model.ExplorationTeam(
            id = data.id,
            name = data.name,
            memberIds = data.memberIds,
            startYear = data.startYear,
            startMonth = data.startMonth,
            duration = data.duration,
            status = safeEnumValueOfIgnoreCase(data.status, com.xianxia.sect.core.model.ExplorationStatus.TRAVELING, "status", "ExplorationTeam"),
            progress = data.currentProgress,
            scoutTargetSectId = data.targetSectId
        )
    }

    private fun convertEvent(event: com.xianxia.sect.core.model.GameEvent): SerializableGameEvent {
        return SerializableGameEvent(
            id = event.id,
            type = event.type.name,
            title = "",
            description = event.message,
            timestamp = event.timestamp,
            gameYear = event.year,
            gameMonth = event.month,
            data = emptyMap()
        )
    }

    private fun convertBackEvent(data: SerializableGameEvent): com.xianxia.sect.core.model.GameEvent {
        return com.xianxia.sect.core.model.GameEvent(
            id = data.id,
            message = data.description,
            type = safeEnumValueOfIgnoreCase(data.type, com.xianxia.sect.core.model.EventType.INFO, "type", "GameEvent"),
            timestamp = data.timestamp,
            year = data.gameYear,
            month = data.gameMonth
        )
    }

    private fun convertBattleLog(log: com.xianxia.sect.core.model.BattleLog): SerializableBattleLog {
        return SerializableBattleLog(
            id = log.id,
            timestamp = log.timestamp,
            gameYear = log.year,
            gameMonth = log.month,
            attackerSectId = "",
            attackerSectName = log.attackerName,
            defenderSectId = "",
            defenderSectName = log.defenderName,
            result = log.result.name,
            rounds = log.rounds.map { convertBattleLogRound(it) },
            attackerMembers = log.teamMembers.map { convertBattleLogMember(it) },
            defenderMembers = log.enemies.map { convertBattleLogEnemy(it) },
            rewards = emptyMap(),
            type = log.type.name,
            details = log.details,
            drops = log.drops,
            dungeonName = log.dungeonName
        )
    }

    private fun convertBackBattleLog(data: SerializableBattleLog): com.xianxia.sect.core.model.BattleLog {
        return com.xianxia.sect.core.model.BattleLog(
            id = data.id,
            timestamp = data.timestamp,
            year = data.gameYear,
            month = data.gameMonth,
            type = safeEnumValueOf(data.type, com.xianxia.sect.core.model.BattleType.PVE, "type", "BattleLog"),
            attackerName = data.attackerSectName,
            defenderName = data.defenderSectName,
            result = safeEnumValueOf(data.result, com.xianxia.sect.core.model.BattleResult.DRAW, "result", "BattleLog"),
            details = data.details,
            drops = data.drops,
            dungeonName = data.dungeonName,
            rounds = data.rounds.map { convertBackBattleLogRound(it) },
            teamMembers = data.attackerMembers.map { convertBackBattleLogMember(it) },
            enemies = data.defenderMembers.map { convertBackBattleLogEnemy(it) }
        )
    }

    private fun convertBattleLogRound(round: com.xianxia.sect.core.model.BattleLogRound): SerializableBattleLogRound {
        return SerializableBattleLogRound(
            roundNumber = round.roundNumber,
            actions = round.actions.map { convertBattleLogAction(it) }
        )
    }

    private fun convertBackBattleLogRound(data: SerializableBattleLogRound): com.xianxia.sect.core.model.BattleLogRound {
        return com.xianxia.sect.core.model.BattleLogRound(
            roundNumber = data.roundNumber,
            actions = data.actions.map { convertBackBattleLogAction(it) }
        )
    }

    private fun convertBattleLogAction(action: com.xianxia.sect.core.model.BattleLogAction): SerializableBattleLogAction {
        return SerializableBattleLogAction(
            actorId = "",
            actorName = action.attacker,
            targetType = action.attackerType,
            targetId = "",
            targetName = action.target,
            skillName = action.skillName ?: "",
            damage = action.damage,
            isCritical = action.isCrit,
            effect = action.message
        )
    }

    private fun convertBackBattleLogAction(data: SerializableBattleLogAction): com.xianxia.sect.core.model.BattleLogAction {
        return com.xianxia.sect.core.model.BattleLogAction(
            attacker = data.actorName,
            attackerType = data.targetType,
            target = data.targetName,
            damage = data.damage,
            isCrit = data.isCritical,
            message = data.effect,
            skillName = data.skillName
        )
    }

    private fun convertBattleLogMember(member: com.xianxia.sect.core.model.BattleLogMember): SerializableBattleLogMember {
        return SerializableBattleLogMember(
            discipleId = member.id,
            name = member.name,
            realm = member.realm,
            isAlive = member.isAlive,
            remainingHp = member.hp,
            maxHp = member.maxHp,
            remainingMp = member.mp,
            maxMp = member.maxMp
        )
    }

    private fun convertBackBattleLogMember(data: SerializableBattleLogMember): com.xianxia.sect.core.model.BattleLogMember {
        return com.xianxia.sect.core.model.BattleLogMember(
            id = data.discipleId,
            name = data.name,
            realm = data.realm,
            hp = data.remainingHp,
            maxHp = data.maxHp,
            mp = data.remainingMp,
            maxMp = data.maxMp,
            isAlive = data.isAlive
        )
    }

    private fun convertBattleLogEnemy(enemy: com.xianxia.sect.core.model.BattleLogEnemy): SerializableBattleLogMember {
        return SerializableBattleLogMember(
            discipleId = enemy.id,
            name = enemy.name,
            realm = enemy.realm,
            isAlive = enemy.isAlive,
            remainingHp = enemy.hp,
            maxHp = enemy.maxHp
        )
    }

    private fun convertBackBattleLogEnemy(data: SerializableBattleLogMember): com.xianxia.sect.core.model.BattleLogEnemy {
        return com.xianxia.sect.core.model.BattleLogEnemy(
            id = data.discipleId,
            name = data.name,
            realm = data.realm,
            hp = data.remainingHp,
            maxHp = data.maxHp,
            isAlive = data.isAlive
        )
    }

    private fun convertAlliance(alliance: com.xianxia.sect.core.model.Alliance): SerializableAlliance {
        return SerializableAlliance(
            id = alliance.id ?: "",
            sectIds = alliance.sectIds ?: emptyList(),
            startYear = alliance.startYear ?: 0,
            initiatorId = alliance.initiatorId ?: "",
            envoyDiscipleId = alliance.envoyDiscipleId ?: ""
        )
    }

    private fun convertBackAlliance(data: SerializableAlliance): com.xianxia.sect.core.model.Alliance {
        return com.xianxia.sect.core.model.Alliance(
            id = data.id,
            sectIds = data.sectIds,
            startYear = data.startYear,
            initiatorId = data.initiatorId,
            envoyDiscipleId = data.envoyDiscipleId
        )
    }

    private fun convertWorldSect(sect: com.xianxia.sect.core.model.WorldSect, detail: com.xianxia.sect.core.model.SectDetail?): SerializableWorldSect {
        return SerializableWorldSect(
            id = sect.id ?: "",
            name = sect.name ?: "",
            level = sect.level ?: 0,
            levelName = sect.levelName ?: "",
            x = sect.x ?: 0f,
            y = sect.y ?: 0f,
            distance = sect.distance ?: 0,
            isPlayerSect = sect.isPlayerSect ?: false,
            discovered = sect.discovered ?: false,
            isKnown = sect.isKnown ?: false,
            relation = sect.relation ?: 0,
            disciples = sect.disciples ?: emptyMap(),
            maxRealm = sect.maxRealm ?: 0,
            connectedSectIds = sect.connectedSectIds ?: emptyList(),
            isOccupied = sect.isOccupied ?: false,
            occupierTeamId = sect.occupierTeamId ?: "",
            occupierTeamName = sect.occupierTeamName ?: "",
            mineSlots = detail?.mineSlots?.map { convertMineSlot(it) } ?: emptyList(),
            occupationTime = detail?.occupationTime ?: 0L,
            isOwned = detail?.isOwned ?: false,
            expiryYear = detail?.expiryYear ?: 0,
            expiryMonth = detail?.expiryMonth ?: 0,
            scoutInfo = detail?.scoutInfo?.let { convertSectScoutInfo(it) } ?: SerializableSectScoutInfo(sectId="", sectName="", scoutYear=0, scoutMonth=0, discipleCount=0, maxRealm=0, isKnown=false, expiryYear=0, expiryMonth=0),
            tradeItems = detail?.tradeItems?.map { convertMerchantItem(it) } ?: emptyList(),
            tradeLastRefreshYear = detail?.tradeLastRefreshYear ?: 0,
            lastGiftYear = detail?.lastGiftYear ?: 0,
            allianceId = sect.allianceId ?: "",
            allianceStartYear = sect.allianceStartYear ?: 0,
            isRighteous = sect.isRighteous ?: true,
            isPlayerOccupied = sect.isPlayerOccupied ?: false,
            occupierBattleTeamId = sect.occupierBattleTeamId ?: "",
            isUnderAttack = sect.isUnderAttack ?: false,
            attackerSectId = sect.attackerSectId ?: "",
            occupierSectId = sect.occupierSectId ?: "",
            warehouse = detail?.warehouse?.let { convertSectWarehouse(it) } ?: SerializableSectWarehouse(),
            giftPreference = detail?.giftPreference?.name ?: "NONE"
        )
    }

    private fun convertBackWorldSect(data: SerializableWorldSect): com.xianxia.sect.core.model.WorldSect {
        val occupierTeamId = data.occupierTeamId.ifEmpty { "" }
        val allianceId = data.allianceId.ifEmpty { "" }
        val occupierBattleTeamId = data.occupierBattleTeamId.ifEmpty { "" }
        val attackerSectId = data.attackerSectId.ifEmpty { "" }
        val occupierSectId = data.occupierSectId.ifEmpty { "" }
        return com.xianxia.sect.core.model.WorldSect(
            id = data.id,
            name = data.name,
            level = data.level,
            levelName = data.levelName,
            x = data.x,
            y = data.y,
            distance = data.distance,
            isPlayerSect = data.isPlayerSect,
            discovered = data.discovered,
            isKnown = data.isKnown,
            relation = data.relation,
            disciples = data.disciples,
            maxRealm = data.maxRealm,
            connectedSectIds = data.connectedSectIds,
            isOccupied = data.isOccupied,
            occupierTeamId = occupierTeamId,
            occupierTeamName = data.occupierTeamName,
            allianceId = allianceId,
            allianceStartYear = data.allianceStartYear,
            isRighteous = data.isRighteous,
            isPlayerOccupied = data.isPlayerOccupied,
            occupierBattleTeamId = occupierBattleTeamId,
            isUnderAttack = data.isUnderAttack,
            attackerSectId = attackerSectId,
            occupierSectId = occupierSectId
        )
    }

    private fun extractSectDetailFromWorldSect(data: SerializableWorldSect): com.xianxia.sect.core.model.SectDetail {
        val scoutInfo = data.scoutInfo.takeIf { it.sectId.isNotEmpty() }?.let { convertBackSectScoutInfo(it) } ?: com.xianxia.sect.core.model.SectScoutInfo()

        return com.xianxia.sect.core.model.SectDetail(
            sectId = data.id,
            mineSlots = data.mineSlots.map { convertBackMineSlot(it) },
            occupationTime = data.occupationTime,
            isOwned = data.isOwned,
            expiryYear = data.expiryYear,
            expiryMonth = data.expiryMonth,
            scoutInfo = scoutInfo,
            tradeItems = data.tradeItems.map { convertBackMerchantItem(it) },
            tradeLastRefreshYear = data.tradeLastRefreshYear,
            lastGiftYear = data.lastGiftYear,
            warehouse = convertBackSectWarehouse(data.warehouse),
            giftPreference = try {
                com.xianxia.sect.core.model.GiftPreferenceType.valueOf(data.giftPreference)
            } catch (e: Exception) {
                com.xianxia.sect.core.model.GiftPreferenceType.NONE
            }
        )
    }

    private fun convertSectDetail(detail: com.xianxia.sect.core.model.SectDetail): SerializableSectDetail {
        return SerializableSectDetail(
            sectId = detail.sectId,
            mineSlots = detail.mineSlots.map { convertMineSlot(it) },
            occupationTime = detail.occupationTime,
            isOwned = detail.isOwned,
            expiryYear = detail.expiryYear,
            expiryMonth = detail.expiryMonth,
            scoutInfo = convertSectScoutInfo(detail.scoutInfo),
            tradeItems = detail.tradeItems.map { convertMerchantItem(it) },
            tradeLastRefreshYear = detail.tradeLastRefreshYear,
            lastGiftYear = detail.lastGiftYear,
            warehouse = convertSectWarehouse(detail.warehouse),
            giftPreference = detail.giftPreference.name
        )
    }

    private fun convertBackSectDetail(data: SerializableSectDetail): com.xianxia.sect.core.model.SectDetail {
        val scoutInfo = data.scoutInfo.takeIf { it.sectId.isNotEmpty() }?.let { convertBackSectScoutInfo(it) } ?: com.xianxia.sect.core.model.SectScoutInfo()

        return com.xianxia.sect.core.model.SectDetail(
            sectId = data.sectId,
            mineSlots = data.mineSlots.map { convertBackMineSlot(it) },
            occupationTime = data.occupationTime,
            isOwned = data.isOwned,
            expiryYear = data.expiryYear,
            expiryMonth = data.expiryMonth,
            scoutInfo = scoutInfo,
            tradeItems = data.tradeItems.map { convertBackMerchantItem(it) },
            tradeLastRefreshYear = data.tradeLastRefreshYear,
            lastGiftYear = data.lastGiftYear,
            warehouse = convertBackSectWarehouse(data.warehouse),
            giftPreference = try {
                com.xianxia.sect.core.model.GiftPreferenceType.valueOf(data.giftPreference)
            } catch (e: Exception) {
                com.xianxia.sect.core.model.GiftPreferenceType.NONE
            }
        )
    }

    private fun convertMineSlot(slot: com.xianxia.sect.core.model.MineSlot): SerializableMineSlot {
        return SerializableMineSlot(
            index = slot.index ?: 0,
            discipleId = slot.discipleId ?: "",
            discipleName = slot.discipleName ?: "",
            output = slot.output ?: 0,
            efficiency = slot.efficiency ?: 1.0,
            isActive = slot.isActive ?: false
        )
    }

    private fun convertBackMineSlot(data: SerializableMineSlot): com.xianxia.sect.core.model.MineSlot {
        return com.xianxia.sect.core.model.MineSlot(
            index = data.index,
            discipleId = data.discipleId,
            discipleName = data.discipleName,
            output = data.output,
            efficiency = data.efficiency,
            isActive = data.isActive
        )
    }

    private fun convertSectWarehouse(warehouse: com.xianxia.sect.core.model.SectWarehouse?): SerializableSectWarehouse {
        if (warehouse == null) return SerializableSectWarehouse()
        return SerializableSectWarehouse(
            items = warehouse.items?.map { convertWarehouseItem(it) } ?: emptyList(),
            spiritStones = warehouse.spiritStones ?: 0L
        )
    }

    private fun convertBackSectWarehouse(data: SerializableSectWarehouse): com.xianxia.sect.core.model.SectWarehouse {
        return com.xianxia.sect.core.model.SectWarehouse(
            items = data.items.map { convertBackWarehouseItem(it) },
            spiritStones = data.spiritStones
        )
    }

    private fun convertWarehouseItem(item: com.xianxia.sect.core.model.WarehouseItem): SerializableWarehouseItem {
        return SerializableWarehouseItem(
            itemId = item.itemId ?: "",
            itemName = item.itemName ?: "",
            itemType = item.itemType ?: "",
            rarity = item.rarity ?: 0,
            quantity = item.quantity ?: 0
        )
    }

    private fun convertBackWarehouseItem(data: SerializableWarehouseItem): com.xianxia.sect.core.model.WarehouseItem {
        return com.xianxia.sect.core.model.WarehouseItem(
            itemId = data.itemId,
            itemName = data.itemName,
            itemType = data.itemType,
            rarity = data.rarity,
            quantity = data.quantity
        )
    }

    private fun convertSectScoutInfo(info: com.xianxia.sect.core.model.SectScoutInfo): SerializableSectScoutInfo {
        return SerializableSectScoutInfo(
            sectId = info.sectId ?: "",
            sectName = info.sectName ?: "",
            scoutYear = info.scoutYear ?: 0,
            scoutMonth = info.scoutMonth ?: 0,
            discipleCount = info.discipleCount ?: 0,
            maxRealm = info.maxRealm ?: 0,
            resources = info.resources ?: emptyMap(),
            isKnown = info.isKnown ?: false,
            disciples = info.disciples ?: emptyMap(),
            expiryYear = info.expiryYear ?: 0,
            expiryMonth = info.expiryMonth ?: 0
        )
    }

    private fun convertBackSectScoutInfo(data: SerializableSectScoutInfo): com.xianxia.sect.core.model.SectScoutInfo {
        return com.xianxia.sect.core.model.SectScoutInfo(
            sectId = data.sectId,
            sectName = data.sectName,
            scoutYear = data.scoutYear,
            scoutMonth = data.scoutMonth,
            discipleCount = data.discipleCount,
            maxRealm = data.maxRealm,
            resources = data.resources,
            isKnown = data.isKnown,
            disciples = data.disciples,
            expiryYear = data.expiryYear,
            expiryMonth = data.expiryMonth
        )
    }

    private fun convertExploredSectInfo(info: com.xianxia.sect.core.model.ExploredSectInfo): SerializableExploredSectInfo {
        return SerializableExploredSectInfo(
            sectId = info.sectId ?: "",
            sectName = info.sectName ?: "",
            year = info.year ?: 0,
            month = info.month ?: 0,
            duration = info.duration ?: 0,
            memberIds = info.memberIds ?: emptyList(),
            memberNames = info.memberNames ?: emptyList(),
            events = info.events ?: emptyList(),
            rewards = info.rewards ?: emptyList(),
            battleCount = info.battleCount ?: 0,
            casualties = info.casualties ?: 0,
            discipleCount = info.discipleCount ?: 0,
            maxRealm = info.maxRealm ?: 0
        )
    }

    private fun convertBackExploredSectInfo(data: SerializableExploredSectInfo): com.xianxia.sect.core.model.ExploredSectInfo {
        return com.xianxia.sect.core.model.ExploredSectInfo(
            sectId = data.sectId,
            sectName = data.sectName,
            year = data.year,
            month = data.month,
            duration = data.duration,
            memberIds = data.memberIds,
            memberNames = data.memberNames,
            events = data.events,
            rewards = data.rewards,
            battleCount = data.battleCount,
            casualties = data.casualties,
            discipleCount = data.discipleCount,
            maxRealm = data.maxRealm
        )
    }

    private fun convertMerchantItem(item: com.xianxia.sect.core.model.MerchantItem): SerializableMerchantItem {
        return SerializableMerchantItem(
            id = item.id ?: "",
            name = item.name ?: "",
            type = item.type ?: "",
            itemId = item.itemId ?: "",
            rarity = item.rarity ?: 0,
            price = item.price,
            quantity = item.quantity ?: 0,
            description = item.description ?: "",
            obtainedYear = item.obtainedYear ?: 1,
            obtainedMonth = item.obtainedMonth ?: 1,
            grade = item.grade ?: ""
        )
    }

    private fun convertBackMerchantItem(data: SerializableMerchantItem): com.xianxia.sect.core.model.MerchantItem {
        return com.xianxia.sect.core.model.MerchantItem(
            id = data.id,
            name = data.name,
            type = data.type,
            itemId = data.itemId,
            rarity = data.rarity,
            price = data.price,
            quantity = data.quantity,
            description = data.description,
            obtainedYear = data.obtainedYear,
            obtainedMonth = data.obtainedMonth,
            grade = data.grade.takeIf { it.isNotEmpty() }
        )
    }

    private fun convertPlantSlot(slot: com.xianxia.sect.core.model.PlantSlotData): SerializablePlantSlotData {
        return SerializablePlantSlotData(
            index = slot.index ?: 0,
            status = slot.status ?: "empty",
            seedId = slot.seedId ?: "",
            seedName = slot.seedName ?: "",
            startYear = slot.startYear ?: 0,
            startMonth = slot.startMonth ?: 0,
            growTime = slot.growTime ?: 0,
            expectedYield = slot.expectedYield ?: 0,
            harvestAmount = slot.expectedYield ?: 0,
            harvestHerbId = slot.seedId ?: ""
        )
    }

    private fun convertBackPlantSlot(data: SerializablePlantSlotData): com.xianxia.sect.core.model.PlantSlotData {
        return com.xianxia.sect.core.model.PlantSlotData(
            index = data.index,
            status = data.status,
            seedId = data.seedId,
            seedName = data.seedName,
            startYear = data.startYear,
            startMonth = data.startMonth,
            growTime = data.growTime,
            expectedYield = data.expectedYield,
            harvestAmount = data.expectedYield,
            harvestHerbId = data.seedId
        )
    }

    private fun convertManualProficiency(prof: com.xianxia.sect.core.model.ManualProficiencyData): SerializableManualProficiencyData {
        return SerializableManualProficiencyData(
            manualId = prof.manualId ?: "",
            manualName = prof.manualName ?: "",
            proficiency = prof.proficiency ?: 0.0,
            maxProficiency = prof.maxProficiency ?: 0,
            level = prof.level ?: 0,
            masteryLevel = prof.masteryLevel ?: 0
        )
    }

    private fun convertBackManualProficiency(data: SerializableManualProficiencyData): com.xianxia.sect.core.model.ManualProficiencyData {
        return com.xianxia.sect.core.model.ManualProficiencyData(
            manualId = data.manualId,
            manualName = data.manualName,
            proficiency = data.proficiency,
            maxProficiency = data.maxProficiency,
            level = data.level,
            masteryLevel = data.masteryLevel
        )
    }

    private fun convertCultivatorCave(cave: com.xianxia.sect.core.model.CultivatorCave): SerializableCultivatorCave {
        return SerializableCultivatorCave(
            id = cave.id,
            name = cave.name,
            level = cave.ownerRealm,
            x = cave.x,
            y = cave.y,
            ownerSectId = "",
            ownerSectName = cave.ownerRealmName,
            disciples = emptyList(),
            resources = emptyMap(),
            discovered = cave.isExplored
        )
    }

    private fun convertBackCultivatorCave(data: SerializableCultivatorCave): com.xianxia.sect.core.model.CultivatorCave {
        return com.xianxia.sect.core.model.CultivatorCave(
            id = data.id,
            name = data.name,
            ownerRealm = data.level,
            ownerRealmName = data.ownerSectName,
            x = data.x,
            y = data.y,
            isExplored = data.discovered
        )
    }

    private fun convertCaveExplorationTeam(team: com.xianxia.sect.core.model.CaveExplorationTeam): SerializableCaveExplorationTeam {
        return SerializableCaveExplorationTeam(
            id = team.id,
            name = team.caveName,
            memberIds = team.memberIds,
            targetCaveId = team.caveId,
            status = team.status.name,
            startYear = team.startYear,
            startMonth = team.startMonth,
            duration = team.duration
        )
    }

    private fun convertBackCaveExplorationTeam(data: SerializableCaveExplorationTeam): com.xianxia.sect.core.model.CaveExplorationTeam {
        return com.xianxia.sect.core.model.CaveExplorationTeam(
            id = data.id,
            caveId = data.targetCaveId,
            caveName = data.name,
            memberIds = data.memberIds,
            startYear = data.startYear,
            startMonth = data.startMonth,
            duration = data.duration,
            status = safeEnumValueOf(data.status, com.xianxia.sect.core.model.CaveExplorationStatus.TRAVELING, "status", "CaveExplorationTeam")
        )
    }

    private fun convertAICaveTeam(team: com.xianxia.sect.core.model.AICaveTeam): SerializableAICaveTeam {
        return SerializableAICaveTeam(
            id = team.id,
            sectId = team.sectId,
            sectName = team.sectName,
            targetCaveId = team.caveId,
            disciples = emptyList(),
            status = team.status.name,
            startYear = 0,
            startMonth = 0
        )
    }

    private fun convertBackAICaveTeam(data: SerializableAICaveTeam): com.xianxia.sect.core.model.AICaveTeam {
        return com.xianxia.sect.core.model.AICaveTeam(
            id = data.id,
            caveId = data.targetCaveId,
            sectId = data.sectId,
            sectName = data.sectName,
            disciples = emptyList(),
            status = safeEnumValueOf(data.status, com.xianxia.sect.core.model.AITeamStatus.EXPLORING, "status", "AICaveTeam")
        )
    }

    private fun convertElderSlots(slots: com.xianxia.sect.core.model.ElderSlots?): SerializableElderSlots {
        if (slots == null) return SerializableElderSlots()
        return SerializableElderSlots(
            viceSectMaster = slots.viceSectMaster ?: "",
            herbGardenElder = slots.herbGardenElder ?: "",
            alchemyElder = slots.alchemyElder ?: "",
            forgeElder = slots.forgeElder ?: "",
            outerElder = slots.outerElder ?: "",
            preachingElder = slots.preachingElder ?: "",
            preachingMasters = slots.preachingMasters?.map { convertDirectDiscipleSlot(it) } ?: emptyList(),
            lawEnforcementElder = slots.lawEnforcementElder ?: "",
            lawEnforcementDisciples = slots.lawEnforcementDisciples?.map { convertDirectDiscipleSlot(it) } ?: emptyList(),
            lawEnforcementReserveDisciples = slots.lawEnforcementReserveDisciples?.map { convertDirectDiscipleSlot(it) } ?: emptyList(),
            innerElder = slots.innerElder ?: "",
            qingyunPreachingElder = slots.qingyunPreachingElder ?: "",
            qingyunPreachingMasters = slots.qingyunPreachingMasters?.map { convertDirectDiscipleSlot(it) } ?: emptyList(),
            herbGardenDisciples = slots.herbGardenDisciples?.map { convertDirectDiscipleSlot(it) } ?: emptyList(),
            alchemyDisciples = slots.alchemyDisciples?.map { convertDirectDiscipleSlot(it) } ?: emptyList(),
            forgeDisciples = slots.forgeDisciples?.map { convertDirectDiscipleSlot(it) } ?: emptyList(),
            herbGardenReserveDisciples = slots.herbGardenReserveDisciples?.map { convertDirectDiscipleSlot(it) } ?: emptyList(),
            alchemyReserveDisciples = slots.alchemyReserveDisciples?.map { convertDirectDiscipleSlot(it) } ?: emptyList(),
            forgeReserveDisciples = slots.forgeReserveDisciples?.map { convertDirectDiscipleSlot(it) } ?: emptyList(),
            spiritMineDeaconDisciples = slots.spiritMineDeaconDisciples?.map { convertDirectDiscipleSlot(it) } ?: emptyList()
        )
    }

    private fun convertBackElderSlots(data: SerializableElderSlots): com.xianxia.sect.core.model.ElderSlots {
        return com.xianxia.sect.core.model.ElderSlots(
            viceSectMaster = data.viceSectMaster,
            herbGardenElder = data.herbGardenElder,
            alchemyElder = data.alchemyElder,
            forgeElder = data.forgeElder,
            outerElder = data.outerElder,
            preachingElder = data.preachingElder,
            preachingMasters = data.preachingMasters.map { convertBackDirectDiscipleSlot(it) },
            lawEnforcementElder = data.lawEnforcementElder,
            lawEnforcementDisciples = data.lawEnforcementDisciples.map { convertBackDirectDiscipleSlot(it) },
            lawEnforcementReserveDisciples = data.lawEnforcementReserveDisciples.map { convertBackDirectDiscipleSlot(it) },
            innerElder = data.innerElder,
            qingyunPreachingElder = data.qingyunPreachingElder,
            qingyunPreachingMasters = data.qingyunPreachingMasters.map { convertBackDirectDiscipleSlot(it) },
            herbGardenDisciples = data.herbGardenDisciples.map { convertBackDirectDiscipleSlot(it) },
            alchemyDisciples = data.alchemyDisciples.map { convertBackDirectDiscipleSlot(it) },
            forgeDisciples = data.forgeDisciples.map { convertBackDirectDiscipleSlot(it) },
            herbGardenReserveDisciples = data.herbGardenReserveDisciples.map { convertBackDirectDiscipleSlot(it) },
            alchemyReserveDisciples = data.alchemyReserveDisciples.map { convertBackDirectDiscipleSlot(it) },
            forgeReserveDisciples = data.forgeReserveDisciples.map { convertBackDirectDiscipleSlot(it) },
            spiritMineDeaconDisciples = data.spiritMineDeaconDisciples.map { convertBackDirectDiscipleSlot(it) }
        )
    }

    private fun convertDirectDiscipleSlot(slot: com.xianxia.sect.core.model.DirectDiscipleSlot): SerializableDirectDiscipleSlot {
        return SerializableDirectDiscipleSlot(
            index = slot.index ?: 0,
            discipleId = slot.discipleId ?: "",
            discipleName = slot.discipleName ?: "",
            discipleRealm = slot.discipleRealm ?: "",
            discipleSpiritRootColor = slot.discipleSpiritRootColor ?: ""
        )
    }

    private fun convertBackDirectDiscipleSlot(data: SerializableDirectDiscipleSlot): com.xianxia.sect.core.model.DirectDiscipleSlot {
        return com.xianxia.sect.core.model.DirectDiscipleSlot(
            index = data.index,
            discipleId = data.discipleId,
            discipleName = data.discipleName,
            discipleRealm = data.discipleRealm,
            discipleSpiritRootColor = data.discipleSpiritRootColor
        )
    }

    private fun convertSpiritMineSlot(slot: com.xianxia.sect.core.model.SpiritMineSlot): SerializableSpiritMineSlot {
        return SerializableSpiritMineSlot(
            index = slot.index ?: 0,
            discipleId = slot.discipleId ?: "",
            discipleName = slot.discipleName ?: "",
            output = slot.output ?: 0
        )
    }

    private fun convertBackSpiritMineSlot(data: SerializableSpiritMineSlot): com.xianxia.sect.core.model.SpiritMineSlot {
        return com.xianxia.sect.core.model.SpiritMineSlot(
            index = data.index,
            discipleId = data.discipleId,
            discipleName = data.discipleName,
            output = data.output
        )
    }

    private fun convertLibrarySlot(slot: com.xianxia.sect.core.model.LibrarySlot): SerializableLibrarySlot {
        return SerializableLibrarySlot(
            index = slot.index ?: 0,
            discipleId = slot.discipleId ?: "",
            discipleName = slot.discipleName ?: ""
        )
    }

    private fun convertBackLibrarySlot(data: SerializableLibrarySlot): com.xianxia.sect.core.model.LibrarySlot {
        return com.xianxia.sect.core.model.LibrarySlot(
            index = data.index,
            discipleId = data.discipleId,
            discipleName = data.discipleName
        )
    }

    private fun convertBuildingSlot(slot: com.xianxia.sect.core.model.BuildingSlot): SerializableBuildingSlot {
        return SerializableBuildingSlot(
            id = slot.id,
            type = slot.type.name,
            discipleId = slot.discipleId ?: "",
            discipleName = slot.discipleName,
            recipeId = slot.recipeId ?: "",
            recipeName = slot.recipeName,
            progress = 0.0,
            status = slot.status.name,
            startYear = slot.startYear,
            startMonth = slot.startMonth,
            resultItemId = "",
            resultQuantity = 0
        )
    }

    private fun convertBackBuildingSlot(data: SerializableBuildingSlot): com.xianxia.sect.core.model.BuildingSlot {
        return com.xianxia.sect.core.model.BuildingSlot(
            id = data.id,
            type = safeEnumValueOf(data.type, com.xianxia.sect.core.model.production.SlotType.IDLE, "type", "BuildingSlot"),
            discipleId = data.discipleId,
            discipleName = data.discipleName,
            startYear = data.startYear,
            startMonth = data.startMonth,
            recipeId = data.recipeId,
            recipeName = data.recipeName,
            status = safeEnumValueOf(data.status, com.xianxia.sect.core.model.SlotStatus.IDLE, "status", "BuildingSlot")
        )
    }

    private fun convertAlchemySlot(slot: com.xianxia.sect.core.model.AlchemySlot): SerializableAlchemySlot {
        return SerializableAlchemySlot(
            id = slot.id,
            discipleId = "",
            discipleName = "",
            recipeId = slot.recipeId ?: "",
            recipeName = slot.recipeName,
            progress = 0.0,
            status = slot.status.name,
            startYear = slot.startYear,
            startMonth = slot.startMonth,
            resultItemId = "",
            resultQuantity = 0
        )
    }

    private fun convertBackAlchemySlot(data: SerializableAlchemySlot): com.xianxia.sect.core.model.AlchemySlot {
        return com.xianxia.sect.core.model.AlchemySlot(
            id = data.id,
            recipeId = data.recipeId,
            recipeName = data.recipeName,
            startYear = data.startYear,
            startMonth = data.startMonth,
            status = safeEnumValueOf(data.status, com.xianxia.sect.core.model.AlchemySlotStatus.IDLE, "status", "AlchemySlot")
        )
    }

    private fun convertProductionSlot(slot: com.xianxia.sect.core.model.production.ProductionSlot): SerializableProductionSlot {
        return SerializableProductionSlot(
            id = slot.id,
            slotIndex = slot.slotIndex,
            buildingType = slot.buildingType.name,
            buildingId = slot.buildingId,
            status = slot.status.name,
            recipeId = slot.recipeId ?: "",
            recipeName = slot.recipeName,
            startYear = slot.startYear,
            startMonth = slot.startMonth,
            duration = slot.duration,
            assignedDiscipleId = slot.assignedDiscipleId ?: "",
            assignedDiscipleName = slot.assignedDiscipleName,
            successRate = slot.successRate,
            outputItemId = slot.outputItemId ?: "",
            outputItemName = slot.outputItemName,
            outputItemRarity = slot.outputItemRarity
        )
    }

    private fun convertBackProductionSlot(data: SerializableProductionSlot): com.xianxia.sect.core.model.production.ProductionSlot {
        return com.xianxia.sect.core.model.production.ProductionSlot(
            id = data.id,
            slotIndex = data.slotIndex,
            buildingType = safeEnumValueOf(data.buildingType, com.xianxia.sect.core.model.production.BuildingType.ALCHEMY, "buildingType", "ProductionSlot"),
            buildingId = data.buildingId,
            status = safeEnumValueOf(data.status, com.xianxia.sect.core.model.production.ProductionSlotStatus.IDLE, "status", "ProductionSlot"),
            recipeId = data.recipeId,
            recipeName = data.recipeName,
            startYear = data.startYear,
            startMonth = data.startMonth,
            duration = data.duration,
            assignedDiscipleId = data.assignedDiscipleId,
            assignedDiscipleName = data.assignedDiscipleName,
            successRate = data.successRate,
            outputItemId = data.outputItemId,
            outputItemName = data.outputItemName,
            outputItemRarity = data.outputItemRarity
        )
    }

    private fun convertSectRelation(relation: com.xianxia.sect.core.model.SectRelation): SerializableSectRelation {
        return SerializableSectRelation(
            sectId1 = relation.sectId1 ?: "",
            sectId2 = relation.sectId2 ?: "",
            favor = relation.favor ?: 0,
            lastInteractionYear = relation.lastInteractionYear ?: 0,
            noGiftYears = relation.noGiftYears ?: 0
        )
    }

    private fun convertBackSectRelation(data: SerializableSectRelation): com.xianxia.sect.core.model.SectRelation {
        return com.xianxia.sect.core.model.SectRelation(
            sectId1 = data.sectId1,
            sectId2 = data.sectId2,
            favor = data.favor,
            lastInteractionYear = data.lastInteractionYear,
            noGiftYears = data.noGiftYears
        )
    }

    private fun convertSectPolicies(policies: com.xianxia.sect.core.model.SectPolicies?): SerializableSectPolicies {
        if (policies == null) return SerializableSectPolicies()
        return SerializableSectPolicies(
            spiritMineBoost = policies.spiritMineBoost ?: false,
            enhancedSecurity = policies.enhancedSecurity ?: false,
            alchemyIncentive = policies.alchemyIncentive ?: false,
            forgeIncentive = policies.forgeIncentive ?: false,
            herbCultivation = policies.herbCultivation ?: false,
            cultivationSubsidy = policies.cultivationSubsidy ?: false,
            manualResearch = policies.manualResearch ?: false,
            autoPlant = policies.autoPlant ?: false,
            autoAlchemy = policies.autoAlchemy ?: false,
            autoForge = policies.autoForge ?: false
        )
    }

    private fun convertBackSectPolicies(data: SerializableSectPolicies): com.xianxia.sect.core.model.SectPolicies {
        return com.xianxia.sect.core.model.SectPolicies(
            spiritMineBoost = data.spiritMineBoost,
            enhancedSecurity = data.enhancedSecurity,
            alchemyIncentive = data.alchemyIncentive,
            forgeIncentive = data.forgeIncentive,
            herbCultivation = data.herbCultivation,
            cultivationSubsidy = data.cultivationSubsidy,
            manualResearch = data.manualResearch,
            autoPlant = data.autoPlant,
            autoAlchemy = data.autoAlchemy,
            autoForge = data.autoForge
        )
    }

    private fun convertBattleTeam(team: com.xianxia.sect.core.model.BattleTeam): SerializableBattleTeam {
        return SerializableBattleTeam(
            id = team.id ?: "",
            name = team.name ?: "",
            slots = team.slots?.map { convertBattleTeamSlot(it) } ?: emptyList(),
            isAtSect = team.isAtSect ?: true,
            currentX = team.currentX ?: 0f,
            currentY = team.currentY ?: 0f,
            targetX = team.targetX ?: 0f,
            targetY = team.targetY ?: 0f,
            status = team.status ?: "",
            targetSectId = team.targetSectId ?: "",
            originSectId = team.originSectId ?: "",
            route = team.route ?: emptyList(),
            currentRouteIndex = team.currentRouteIndex ?: 0,
            moveProgress = team.moveProgress ?: 0f,
            isOccupying = team.isOccupying ?: false,
            occupiedSectId = team.occupiedSectId ?: "",
            isReturning = team.isReturning ?: false
        )
    }

    private fun convertBackBattleTeam(data: SerializableBattleTeam): com.xianxia.sect.core.model.BattleTeam {
        val targetSectId = data.targetSectId.ifEmpty { "" }
        val originSectId = data.originSectId.ifEmpty { "" }
        val occupiedSectId = data.occupiedSectId.ifEmpty { "" }

        return com.xianxia.sect.core.model.BattleTeam(
            id = data.id,
            name = data.name,
            slots = data.slots.map { convertBackBattleTeamSlot(it) },
            isAtSect = data.isAtSect,
            currentX = data.currentX,
            currentY = data.currentY,
            targetX = data.targetX,
            targetY = data.targetY,
            status = data.status,
            targetSectId = targetSectId,
            originSectId = originSectId,
            route = data.route,
            currentRouteIndex = data.currentRouteIndex,
            moveProgress = data.moveProgress,
            isOccupying = data.isOccupying,
            occupiedSectId = occupiedSectId,
            isReturning = data.isReturning
        )
    }

    private fun convertBattleTeamSlot(slot: com.xianxia.sect.core.model.BattleTeamSlot): SerializableBattleTeamSlot {
        return SerializableBattleTeamSlot(
            index = slot.index,
            discipleId = slot.discipleId ?: "",
            discipleName = slot.discipleName,
            discipleRealm = slot.discipleRealm,
            slotType = slot.slotType.name,
            isAlive = slot.isAlive
        )
    }

    private fun convertBackBattleTeamSlot(data: SerializableBattleTeamSlot): com.xianxia.sect.core.model.BattleTeamSlot {
        return com.xianxia.sect.core.model.BattleTeamSlot(
            index = data.index,
            discipleId = data.discipleId,
            discipleName = data.discipleName,
            discipleRealm = data.discipleRealm,
            slotType = safeEnumValueOf(data.slotType, com.xianxia.sect.core.model.BattleSlotType.DISCIPLE, "slotType", "BattleTeamSlot"),
            isAlive = data.isAlive
        )
    }

    private fun convertAIBattleTeam(team: com.xianxia.sect.core.model.AIBattleTeam): SerializableAIBattleTeam {
        return SerializableAIBattleTeam(
            id = team.id ?: "",
            attackerSectId = team.attackerSectId ?: "",
            attackerSectName = team.attackerSectName ?: "",
            defenderSectId = team.defenderSectId ?: "",
            defenderSectName = team.defenderSectName ?: "",
            disciples = team.disciples?.map { convertDisciple(it) } ?: emptyList(),
            currentX = team.currentX ?: 0f,
            currentY = team.currentY ?: 0f,
            targetX = team.targetX ?: 0f,
            targetY = team.targetY ?: 0f,
            attackerStartX = team.attackerStartX ?: 0f,
            attackerStartY = team.attackerStartY ?: 0f,
            moveProgress = team.moveProgress ?: 0f,
            status = team.status ?: "",
            route = team.route ?: emptyList(),
            currentRouteIndex = team.currentRouteIndex ?: 0,
            startYear = team.startYear ?: 0,
            startMonth = team.startMonth ?: 0,
            isPlayerDefender = team.isPlayerDefender ?: false
        )
    }

    private fun convertBackAIBattleTeam(data: SerializableAIBattleTeam): com.xianxia.sect.core.model.AIBattleTeam {
        return com.xianxia.sect.core.model.AIBattleTeam(
            id = data.id,
            attackerSectId = data.attackerSectId,
            attackerSectName = data.attackerSectName,
            defenderSectId = data.defenderSectId,
            defenderSectName = data.defenderSectName,
            disciples = data.disciples.map { convertBackDisciple(it) },
            currentX = data.currentX,
            currentY = data.currentY,
            targetX = data.targetX,
            targetY = data.targetY,
            attackerStartX = data.attackerStartX,
            attackerStartY = data.attackerStartY,
            moveProgress = data.moveProgress,
            status = data.status,
            route = data.route,
            currentRouteIndex = data.currentRouteIndex,
            startYear = data.startYear,
            startMonth = data.startMonth,
            isPlayerDefender = data.isPlayerDefender
        )
    }

    private fun convertActiveMission(mission: com.xianxia.sect.core.model.ActiveMission): SerializableActiveMission {
        return SerializableActiveMission(
            id = mission.id,
            missionId = mission.missionId,
            name = mission.missionName,
            description = "",
            startYear = mission.startYear,
            startMonth = mission.startMonth,
            assignedDisciples = mission.discipleIds,
            progress = 0,
            targetProgress = mission.duration,
            status = "ACTIVE",
            missionType = mission.template.name,
            difficulty = mission.difficulty.ordinal,
            discipleNames = mission.discipleNames,
            discipleRealms = mission.discipleRealms,
            spiritStones = mission.rewards.spiritStones,
            spiritStonesMax = mission.rewards.spiritStonesMax,
            materialCountMin = mission.rewards.materialCountMin,
            materialCountMax = mission.rewards.materialCountMax,
            materialMinRarity = mission.rewards.materialMinRarity,
            materialMaxRarity = mission.rewards.materialMaxRarity,
            pillCountMin = mission.rewards.pillCountMin,
            pillCountMax = mission.rewards.pillCountMax,
            pillMinRarity = mission.rewards.pillMinRarity,
            pillMaxRarity = mission.rewards.pillMaxRarity,
            equipmentChance = mission.rewards.equipmentChance,
            equipmentMinRarity = mission.rewards.equipmentMinRarity,
            equipmentMaxRarity = mission.rewards.equipmentMaxRarity,
            manualChance = mission.rewards.manualChance,
            manualMinRarity = mission.rewards.manualMinRarity,
            manualMaxRarity = mission.rewards.manualMaxRarity,
            baseSpiritStones = mission.rewards.baseSpiritStones,
            baseMaterialCountMin = mission.rewards.baseMaterialCountMin,
            baseMaterialCountMax = mission.rewards.baseMaterialCountMax,
            baseMaterialMinRarity = mission.rewards.baseMaterialMinRarity,
            baseMaterialMaxRarity = mission.rewards.baseMaterialMaxRarity
        )
    }

    private fun convertBackActiveMission(data: SerializableActiveMission): com.xianxia.sect.core.model.ActiveMission {
        val difficulty = com.xianxia.sect.core.model.MissionDifficulty.entries.getOrNull(data.difficulty)
            ?: com.xianxia.sect.core.model.MissionDifficulty.SIMPLE
        val template = migrateMissionTemplate(data.missionType)

        return com.xianxia.sect.core.model.ActiveMission(
            id = data.id,
            missionId = data.missionId,
            missionName = data.name,
            template = template,
            difficulty = difficulty,
            discipleIds = data.assignedDisciples,
            discipleNames = data.discipleNames.ifEmpty { data.assignedDisciples.map { "" } },
            discipleRealms = data.discipleRealms,
            startYear = data.startYear,
            startMonth = data.startMonth,
            duration = data.targetProgress,
            rewards = com.xianxia.sect.core.model.MissionRewardConfig(
                spiritStones = data.spiritStones,
                spiritStonesMax = data.spiritStonesMax,
                materialCountMin = data.materialCountMin,
                materialCountMax = data.materialCountMax,
                materialMinRarity = data.materialMinRarity,
                materialMaxRarity = data.materialMaxRarity,
                pillCountMin = data.pillCountMin,
                pillCountMax = data.pillCountMax,
                pillMinRarity = data.pillMinRarity,
                pillMaxRarity = data.pillMaxRarity,
                equipmentChance = data.equipmentChance,
                equipmentMinRarity = data.equipmentMinRarity,
                equipmentMaxRarity = data.equipmentMaxRarity,
                manualChance = data.manualChance,
                manualMinRarity = data.manualMinRarity,
                manualMaxRarity = data.manualMaxRarity,
                baseSpiritStones = data.baseSpiritStones,
                baseMaterialCountMin = data.baseMaterialCountMin,
                baseMaterialCountMax = data.baseMaterialCountMax,
                baseMaterialMinRarity = data.baseMaterialMinRarity,
                baseMaterialMaxRarity = data.baseMaterialMaxRarity
            ),
            missionType = template.missionType,
            enemyType = template.enemyType,
            triggerChance = template.triggerChance
        )
    }

    private fun convertMission(mission: com.xianxia.sect.core.model.Mission): SerializableMission {
        return SerializableMission(
            id = mission.id,
            name = mission.name,
            description = mission.description,
            difficulty = mission.difficulty.ordinal,
            minDisciples = 1,
            maxDisciples = 5,
            duration = mission.duration,
            rewards = emptyMap(),
            requirements = emptyMap(),
            type = mission.template.name,
            createdYear = mission.createdYear,
            createdMonth = mission.createdMonth,
            spiritStones = mission.rewards.spiritStones,
            spiritStonesMax = mission.rewards.spiritStonesMax,
            materialCountMin = mission.rewards.materialCountMin,
            materialCountMax = mission.rewards.materialCountMax,
            materialMinRarity = mission.rewards.materialMinRarity,
            materialMaxRarity = mission.rewards.materialMaxRarity,
            pillCountMin = mission.rewards.pillCountMin,
            pillCountMax = mission.rewards.pillCountMax,
            pillMinRarity = mission.rewards.pillMinRarity,
            pillMaxRarity = mission.rewards.pillMaxRarity,
            equipmentChance = mission.rewards.equipmentChance,
            equipmentMinRarity = mission.rewards.equipmentMinRarity,
            equipmentMaxRarity = mission.rewards.equipmentMaxRarity,
            manualChance = mission.rewards.manualChance,
            manualMinRarity = mission.rewards.manualMinRarity,
            manualMaxRarity = mission.rewards.manualMaxRarity,
            baseSpiritStones = mission.rewards.baseSpiritStones,
            baseMaterialCountMin = mission.rewards.baseMaterialCountMin,
            baseMaterialCountMax = mission.rewards.baseMaterialCountMax,
            baseMaterialMinRarity = mission.rewards.baseMaterialMinRarity,
            baseMaterialMaxRarity = mission.rewards.baseMaterialMaxRarity
        )
    }

    private fun convertBackMission(data: SerializableMission): com.xianxia.sect.core.model.Mission {
        val difficulty = com.xianxia.sect.core.model.MissionDifficulty.entries.getOrNull(data.difficulty)
            ?: com.xianxia.sect.core.model.MissionDifficulty.SIMPLE
        val template = migrateMissionTemplate(data.type)

        return com.xianxia.sect.core.model.Mission(
            id = data.id,
            template = template,
            name = data.name,
            description = data.description,
            difficulty = difficulty,
            duration = data.duration,
            rewards = com.xianxia.sect.core.model.MissionRewardConfig(
                spiritStones = data.spiritStones,
                spiritStonesMax = data.spiritStonesMax,
                materialCountMin = data.materialCountMin,
                materialCountMax = data.materialCountMax,
                materialMinRarity = data.materialMinRarity,
                materialMaxRarity = data.materialMaxRarity,
                pillCountMin = data.pillCountMin,
                pillCountMax = data.pillCountMax,
                pillMinRarity = data.pillMinRarity,
                pillMaxRarity = data.pillMaxRarity,
                equipmentChance = data.equipmentChance,
                equipmentMinRarity = data.equipmentMinRarity,
                equipmentMaxRarity = data.equipmentMaxRarity,
                manualChance = data.manualChance,
                manualMinRarity = data.manualMinRarity,
                manualMaxRarity = data.manualMaxRarity,
                baseSpiritStones = data.baseSpiritStones,
                baseMaterialCountMin = data.baseMaterialCountMin,
                baseMaterialCountMax = data.baseMaterialCountMax,
                baseMaterialMinRarity = data.baseMaterialMinRarity,
                baseMaterialMaxRarity = data.baseMaterialMaxRarity
            ),
            missionType = template.missionType,
            enemyType = template.enemyType,
            triggerChance = template.triggerChance,
            createdYear = data.createdYear,
            createdMonth = data.createdMonth
        )
    }

    private fun migrateMissionTemplate(name: String): com.xianxia.sect.core.model.MissionTemplate {
        return when (name) {
            "ESCORT" -> com.xianxia.sect.core.model.MissionTemplate.ESCORT_CARAVAN
            "SUPPRESS_BEASTS" -> com.xianxia.sect.core.model.MissionTemplate.SUPPRESS_LOW_BEASTS
            "SUPPRESS_BEASTS_NORMAL" -> com.xianxia.sect.core.model.MissionTemplate.SUPPRESS_JINDAN_BEASTS
            else -> try {
                com.xianxia.sect.core.model.MissionTemplate.valueOf(name)
            } catch (e: Exception) {
                com.xianxia.sect.core.model.MissionTemplate.ESCORT_CARAVAN
            }
        }
    }
}
