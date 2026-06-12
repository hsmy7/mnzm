package com.xianxia.sect.data.serialization.unified

import android.util.Log
import kotlinx.serialization.ExperimentalSerializationApi

object SerializationConstants {
    const val MAGIC_HEADER: Short = 0x5853
    const val FORMAT_VERSION: Byte = 3
    const val HEADER_SIZE = 10
    const val CHECKSUM_SIZE = 32
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

enum class CompressionType(val code: Byte) {
    NONE(0),
    LZ4(1),
    ZSTD(2),
    GZIP(3);
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

enum class DataType {
    HOT_DATA,
    COLD_DATA,
    DELTA,
    PERFORMANCE_CRITICAL
}

internal inline fun <reified T : Enum<T>> safeEnumValueOf(
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

internal inline fun <reified T : Enum<T>> safeEnumValueOfIgnoreCase(
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
