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

    /**
     * A4（2026-08-05）：解码用宽松 ProtoBuf 实例——ignoreUnknownKeys=true。
     *
     * 旧版 App 读新版云档/整档时，新增字段号在严格模式下抛
     * SerializationException（用户只看到"存档数据异常"）；宽松模式缺失
     * 字段取类默认值尽力解码，跨版本失败由下载前版本仲裁（VersionMismatch）
     * 提示，而非笼统的"数据异常"。编码与 Room TypeConverter 路径保持严格。
     */
    // A4（2026-08-05）实证：kotlinx.serialization 的 ProtoBuf 解码按 protobuf
    // wire format 规范跳过未知字段号（不抛异常），无需宽松配置实例——
    // 旧版 App 读新版云档时新字段自动跳过、缺失字段取默认值尽力解码；
    // 跨版本明确提示由 downloadSave 下载前版本仲裁（VersionMismatch）承担
    private val protoBufLenient = NullSafeProtoBuf.protoBuf

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
            // A4：解码用宽松实例（ignoreUnknownKeys=true），旧版 App 读新版档尽力解码
            val result: T? = protoBufLenient.decodeFromByteArray(serializer, rawData)
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
