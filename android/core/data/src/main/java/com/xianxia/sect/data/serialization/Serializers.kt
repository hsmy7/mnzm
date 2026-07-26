package com.xianxia.sect.data.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * 可为空 Int 的自定义序列化器（Protobuf 不支持 null，使用 -1 作为哨兵值）。
 *
 * 适用字段：griefEndYear（-1 = 未设置悲伤期）
 */
object NullableIntSerializer : KSerializer<Int?> {
    override val descriptor = PrimitiveSerialDescriptor("NullableInt", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: Int?) {
        encoder.encodeInt(value ?: NullSafeProtoBuf.DEFAULT_INT_SENTINEL)
    }
    override fun deserialize(decoder: Decoder): Int? {
        val v = decoder.decodeInt()
        return if (v == NullSafeProtoBuf.DEFAULT_INT_SENTINEL) null else v
    }
}

/**
 * 可为空 String 的自定义序列化器（Protobuf 不支持 null，使用空字符串作为哨兵值）。
 *
 * 适用字段：partnerId, partnerSectId, parentId1, parentId2, masterId
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

/**
 * 可为空 Int 的自定义序列化器，使用 0 作为哨兵值（区别于 -1 的 griefEndYear 场景）。
 *
 * 适用字段：childBirthMonth（0 = 未设置）
 */
object NullableIntZeroSerializer : KSerializer<Int?> {
    override val descriptor = PrimitiveSerialDescriptor("NullableIntZero", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: Int?) {
        encoder.encodeInt(value ?: 0)
    }
    override fun deserialize(decoder: Decoder): Int? {
        val v = decoder.decodeInt()
        return if (v == 0) null else v
    }
}
