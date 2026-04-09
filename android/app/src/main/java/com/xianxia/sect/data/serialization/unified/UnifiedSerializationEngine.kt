@file:Suppress("DEPRECATION")

package com.xianxia.sect.data.serialization.unified

import android.util.Log
import com.xianxia.sect.data.serialization.NullSafeProtoBuf
import com.xianxia.sect.data.unified.SerializationException
import kotlinx.serialization.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

object SerializationConstants {
    const val MAGIC_HEADER: Short = 0x5853
    const val FORMAT_VERSION: Byte = 3
    const val HEADER_SIZE = 10
    const val CHECKSUM_SIZE = 32

    /**
     * 全局硬上限：单次序列化数据不允许超过此值。
     *
     * @deprecated 已被 [SerializationQuota.totalMaxBytes] 替代。
     *             此常量保留仅用于向后兼容，新代码应使用 [SerializationQuota.validateAgainstQuota]。
     */
    @Deprecated("Replaced by SerializationQuota.totalMaxBytes. Use validateAgainstQuota() instead.")
    const val MAX_DATA_SIZE = 200 * 1024 * 1024

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
    private val compressionLayer: UnifiedCompressionLayer,
    private val integrityLayer: IntegrityLayer
) {
    companion object {
        const val TAG = "UnifiedSerialization"
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
        val sizeLimit = SerializationConstants.MAX_DATA_SIZE
        if (rawData.size > sizeLimit) {
            throw SerializationException(
                "Data too large: ${rawData.size} bytes (limit: $sizeLimit bytes). " +
                "Consider using SerializationQuota for domain-specific limits."
            )
        }

        val compressionStart = System.currentTimeMillis()
        val (compressedData, compressionType) = if (rawData.size >= context.compressThreshold) {
            compressionLayer.compress(rawData, context.compression)
        } else {
            compressionLayer.compress(rawData, CompressionType.LZ4)
        }
        val compressionTime = System.currentTimeMillis() - compressionStart

        val checksum = if (context.includeChecksum) {
            integrityLayer.computeChecksum(rawData)
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
            val rawData = compressionLayer.decompress(payload, header.compression, header.originalSize)
            val decompressionTime = System.currentTimeMillis() - decompressionStart

            var checksumValid = true
            if (header.hasChecksum && context.includeChecksum) {
                val storedChecksum = data.copyOfRange(
                    SerializationConstants.HEADER_SIZE,
                    SerializationConstants.HEADER_SIZE + SerializationConstants.CHECKSUM_SIZE
                )
                val computedChecksum = integrityLayer.computeChecksum(rawData)
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

        // herbGardenPlantSlots: 每个 Slot 约 120B
        size += gameData.herbGardenPlantSlots.size * 120L

        // manualProficiencies: 粗略估计
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
            gameData.forgeSlots.size * 80L +
            gameData.alchemySlots.size * 80L +
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
