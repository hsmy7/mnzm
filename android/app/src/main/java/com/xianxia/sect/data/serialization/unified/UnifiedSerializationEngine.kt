package com.xianxia.sect.data.serialization.unified

import android.util.Log
import kotlinx.serialization.*
import kotlinx.serialization.protobuf.ProtoBuf
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
    const val HEADER_SIZE = 8
    const val CHECKSUM_SIZE = 32
    const val MAX_DATA_SIZE = 200 * 1024 * 1024
}

enum class SerializationFormat(val code: Byte) {
    PROTOBUF(1);

    companion object {
        fun fromCode(code: Byte): SerializationFormat {
            return values().find { it.code == code } ?: PROTOBUF
        }
    }
}

enum class CompressionType(val code: Byte) {
    LZ4(1);

    companion object {
        fun fromCode(code: Byte): CompressionType {
            return values().find { it.code == code } ?: LZ4
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

class SerializationException(message: String, cause: Throwable? = null) : Exception(message, cause)

@Singleton
class UnifiedSerializationEngine @Inject constructor(
    private val compressionLayer: UnifiedCompressionLayer,
    private val integrityLayer: IntegrityLayer
) {
    companion object {
        const val TAG = "UnifiedSerialization"
    }
    
    @OptIn(ExperimentalSerializationApi::class)
    internal val protoBuf = ProtoBuf {
        encodeDefaults = true
    }
    
    private val statsCache = ConcurrentHashMap<String, SerializationStats>()
    
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
        
        if (rawData.size > SerializationConstants.MAX_DATA_SIZE) {
            throw SerializationException("Data too large: ${rawData.size} bytes")
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
        return statsCache[key] ?: SerializationStats()
    }
    
    fun clearStats() {
        statsCache.clear()
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
                compression = CompressionType.LZ4,
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
