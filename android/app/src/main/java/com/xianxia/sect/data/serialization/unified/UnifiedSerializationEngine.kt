package com.xianxia.sect.data.serialization.unified

import android.util.Log
import com.xianxia.sect.data.compression.CompressionAlgorithm
import com.xianxia.sect.data.compression.DataCompressor
import com.xianxia.sect.data.serialization.NullSafeProtoBuf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

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

    internal val protoBuf = NullSafeProtoBuf.protoBuf

    private val statsCache = ConcurrentHashMap<String, Pair<SerializationStats, Long>>()

    data class SerializationStats(
        val totalOperations: Long = 0,
        val totalBytesSerialized: Long = 0,
        val totalBytesCompressed: Long = 0,
        val totalSerializationTime: Long = 0,
        val totalCompressionTime: Long = 0,
        val averageCompressionRatio: Double = 1.0
    )

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
            val actualCompressionType = when (result.algorithm) {
                CompressionAlgorithm.ZSTD -> CompressionType.ZSTD
                CompressionAlgorithm.GZIP -> CompressionType.GZIP
                CompressionAlgorithm.LZ4 -> CompressionType.LZ4
                CompressionAlgorithm.NONE -> CompressionType.NONE
            }
            result.data to actualCompressionType
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
                CompressionType.GZIP -> CompressionAlgorithm.GZIP
                CompressionType.NONE -> CompressionAlgorithm.NONE
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

    fun getStats(key: String? = null): SerializationStats {
        cleanupExpiredStats(SerializationConstants.STATS_CACHE_TTL_MS)

        return if (key != null) {
            statsCache[key]?.first ?: SerializationStats()
        } else {
            SerializationStats()
        }
    }

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

    fun clearStats() {
        val count = statsCache.size
        statsCache.clear()
        Log.d(TAG, "Cleared all $count stats cache entries")
    }

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

    fun validateAgainstQuota(
        data: SerializableSaveData,
        quota: SerializationQuota = SerializationQuota.STRICT
    ): QuotaValidationResult {
        val violations = mutableListOf<QuotaViolation>()
        var estimatedTotal = 0L

        val coreEstimate = estimateGameDataSize(data.gameData)
        estimatedTotal += coreEstimate
        if (coreEstimate > quota.coreMaxBytes) {
            violations.add(QuotaViolation("core", coreEstimate, quota.coreMaxBytes.toLong()))
        }

        val discipleCount = data.disciples.size.toLong()
        val discipleEstimate = discipleCount * 1500L
        estimatedTotal += discipleEstimate
        if (discipleCount > quota.maxDiscipleCount) {
            violations.add(QuotaViolation("disciple_count", discipleCount, quota.maxDiscipleCount.toLong(), "items"))
        }
        if (discipleEstimate > quota.discipleMaxBytes) {
            violations.add(QuotaViolation("disciples", discipleEstimate, quota.discipleMaxBytes.toLong()))
        }

        val inventoryItems = data.equipment.size +
            data.manuals.size +
            data.pills.size +
            data.materials.size +
            data.herbs.size +
            data.seeds.size
        val inventoryEstimate = inventoryItems.toLong() * 200L
        estimatedTotal += inventoryEstimate
        if (inventoryEstimate > quota.inventoryMaxBytes) {
            violations.add(QuotaViolation("inventory", inventoryEstimate, quota.inventoryMaxBytes.toLong()))
        }

        val worldEstimate = (data.gameData.worldMapSects.size * 500L) +
            (data.alliances.size * 200L)
        estimatedTotal += worldEstimate
        if (worldEstimate > quota.worldMaxBytes) {
            violations.add(QuotaViolation("world", worldEstimate, quota.worldMaxBytes.toLong()))
        }

        val battleLogCount = data.battleLogs.size.toLong()
        val combatEstimate = battleLogCount * 3000L
        estimatedTotal += combatEstimate
        if (battleLogCount > quota.maxBattleLogCount) {
            violations.add(QuotaViolation("battle_log_count", battleLogCount, quota.maxBattleLogCount.toLong(), "items"))
        }
        if (combatEstimate > quota.combatMaxBytes) {
            violations.add(QuotaViolation("combat", combatEstimate, quota.combatMaxBytes.toLong()))
        }

        val missionCount = (data.gameData.activeMissions.size + data.gameData.availableMissions.size).toLong()
        val missionEstimate = missionCount * 750L
        estimatedTotal += missionEstimate
        if (missionEstimate > quota.missionMaxBytes) {
            violations.add(QuotaViolation("missions", missionEstimate, quota.missionMaxBytes.toLong()))
        }

        val miscEstimate = (data.teams.size * 400L)
        estimatedTotal += miscEstimate

        if (estimatedTotal > quota.totalMaxBytes) {
            violations.add(QuotaViolation("total", estimatedTotal, quota.totalMaxBytes.toLong()))
        }

        return QuotaValidationResult(
            isValid = violations.isEmpty(),
            violations = violations,
            estimatedTotalBytes = estimatedTotal
        )
    }

    private fun estimateGameDataSize(gameData: SerializableGameData): Long {
        var size = 512L

        size += gameData.worldMapSects.size * 600L
        size += gameData.exploredSects.size * 200L
        size += gameData.scoutInfo.size * 150L
        size += gameData.manualProficiencies.values.sumOf { it.size * 80L }
        size += gameData.travelingMerchantItems.size * 100L
        size += gameData.playerListedItems.size * 100L
        size += gameData.recruitList.size * 24L
        size += gameData.cultivatorCaves.size * 200L
        size += gameData.caveExplorationTeams.size * 150L
        size += gameData.aiCaveTeams.size * 180L
        size += (gameData.unlockedRecipes.size +
            gameData.unlockedManuals.size) * 20L
        size += (gameData.spiritMineSlots.size * 60L +
            gameData.librarySlots.size * 40L +
            gameData.residenceSlots.size * 40L +
            gameData.productionSlots.size * 100L)
        size += gameData.alliances.size * 150L
        size += gameData.sectRelations.size * 60L
        size += gameData.activeMissions.size * 500L
        size += gameData.availableMissions.size * 350L

        return size
    }

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
