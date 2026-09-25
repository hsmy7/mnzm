package com.xianxia.sect.data.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * 可为空 String 的自定义序列化器（Protobuf 不支持 null，使用空字符串作为哨兵值）。
 *
 * 适用形状：任意「可空引用 id」字段（ProtoBuf 侧以 "" 承载 null）。
 */
object NullableStringSerializer : KSerializer<String?> {
    override val descriptor = PrimitiveSerialDescriptor("NullableString", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: String?) {
        encoder.encodeString(value ?: "")
    }
    override fun deserialize(decoder: Decoder): String? {
        val v = decoder.decodeString()
        return v.ifEmpty { null }
    }
}

/**
 * 可为空 Long 的自定义序列化器（Protobuf 不支持 null，使用 -1L 作为哨兵值）。
 */
object NullableLongSerializer : KSerializer<Long?> {
    override val descriptor = PrimitiveSerialDescriptor("NullableLong", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: Long?) {
        encoder.encodeLong(value ?: NullSafeProtoBuf.DEFAULT_LONG_SENTINEL)
    }
    override fun deserialize(decoder: Decoder): Long? {
        val v = decoder.decodeLong()
        return if (v == NullSafeProtoBuf.DEFAULT_LONG_SENTINEL) null else v
    }
}
