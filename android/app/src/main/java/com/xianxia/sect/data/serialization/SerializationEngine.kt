package com.xianxia.sect.data.serialization

import com.xianxia.sect.data.serialization.NullSafeProtoBuf
import kotlinx.serialization.*

object SerializationConfig {
    const val MAGIC_HEADER: Short = 0x5853
    const val VERSION: Byte = 1
    const val COMPRESSION_THRESHOLD = 256
}

/**
 * 序列化模式枚举。
 *
 * 当前仅 [PROTOBUF] 为活跃模式。
 */
enum class SerializationMode {
    /** Protobuf 序列化模式（当前唯一支持的路径） */
    PROTOBUF
}

data class SerializationContext(
    val mode: SerializationMode = SerializationMode.PROTOBUF,
    val version: Int = 1
)

data class SerializationResult(
    val data: ByteArray,
    val originalSize: Int,
    val compressedSize: Int,
    val mode: SerializationMode,
    val checksum: Long
) {
    val compressionRatio: Double
        get() = if (originalSize > 0) compressedSize.toDouble() / originalSize else 1.0
}
