package com.xianxia.sect.core.model

import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * BattleType ↔ String 序列化器。
 */
object BattleTypeAsStringSerializer : KSerializer<BattleType> {
    override val descriptor = PrimitiveSerialDescriptor("BattleType", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: BattleType) = encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder): BattleType = safeValueOf(decoder.decodeString(), BattleType.PVE)
}

/**
 * BattleResult ↔ String 序列化器。
 */
object BattleResultAsStringSerializer : KSerializer<BattleResult> {
    override val descriptor = PrimitiveSerialDescriptor("BattleResult", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: BattleResult) = encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder): BattleResult = safeValueOf(decoder.decodeString(), BattleResult.DRAW)
}

/**
 * CaveStatus ↔ String 序列化器。
 */
object CaveStatusAsStringSerializer : KSerializer<CaveStatus> {
    override val descriptor = PrimitiveSerialDescriptor("CaveStatus", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: CaveStatus) = encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder): CaveStatus = safeValueOf(decoder.decodeString(), CaveStatus.AVAILABLE)
}

/**
 * AITeamStatus ↔ String 序列化器。
 */
object AITeamStatusAsStringSerializer : KSerializer<AITeamStatus> {
    override val descriptor = PrimitiveSerialDescriptor("AITeamStatus", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: AITeamStatus) = encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder): AITeamStatus = safeValueOf(decoder.decodeString(), AITeamStatus.EXPLORING)
}

/**
 * BuildingType ↔ String 序列化器。
 */
object BuildingTypeAsStringSerializer : KSerializer<BuildingType> {
    override val descriptor = PrimitiveSerialDescriptor("BuildingType", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: BuildingType) = encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder): BuildingType = safeValueOf(decoder.decodeString(), BuildingType.ALCHEMY)
}

/**
 * ProductionSlotStatus ↔ String 序列化器。
 */
object ProductionSlotStatusAsStringSerializer : KSerializer<ProductionSlotStatus> {
    override val descriptor = PrimitiveSerialDescriptor("ProductionSlotStatus", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ProductionSlotStatus) = encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder): ProductionSlotStatus = safeValueOf(decoder.decodeString(), ProductionSlotStatus.IDLE)
}

/**
 * CaveExplorationStatus ↔ String 序列化器。
 */
object CaveExplorationStatusAsStringSerializer : KSerializer<CaveExplorationStatus> {
    override val descriptor = PrimitiveSerialDescriptor("CaveExplorationStatus", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: CaveExplorationStatus) = encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder): CaveExplorationStatus = safeValueOf(decoder.decodeString(), CaveExplorationStatus.TRAVELING)
}

/**
 * 可为空 String ↔ Protobuf 非空 String 转换（空字符串 ↔ null）。
 * 适用字段：recipeId、assignedDiscipleId、outputItemId 等。
 */
object NullableStringAsEmptySerializer : KSerializer<String?> {
    override val descriptor = PrimitiveSerialDescriptor("NullableStringAsEmpty", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: String?) { encoder.encodeString(value ?: "") }
    override fun deserialize(decoder: Decoder): String? { val v = decoder.decodeString(); return v.ifEmpty { null } }
}

/**
 * 可为空 Int ↔ Protobuf 非空 Int 转换（0 ↔ null）。
 */
object NullableIntAsZeroSerializer : KSerializer<Int?> {
    override val descriptor = PrimitiveSerialDescriptor("NullableIntAsZero", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: Int?) { encoder.encodeInt(value ?: 0) }
    override fun deserialize(decoder: Decoder): Int? { val v = decoder.decodeInt(); return if (v == 0) null else v }
}

/**
 * Set<Int> 作为 @ProtoPacked List<Int> 序列化/反序列化。
 */
object IntSetAsPackedListSerializer : KSerializer<Set<Int>> {
    override val descriptor = PrimitiveSerialDescriptor("IntSetAsPackedList", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: Set<Int>) {
        @Suppress("UNCHECKED_CAST")
        (kotlinx.serialization.serializer<List<Int>>() as KSerializer<Any>).serialize(encoder, value.toList())
    }
    override fun deserialize(decoder: Decoder): Set<Int> {
        @Suppress("UNCHECKED_CAST")
        val list = (kotlinx.serialization.serializer<List<Int>>() as KSerializer<Any>).deserialize(decoder) as List<Int>
        return list.toSet()
    }
}

/** 安全的 enum valueOf，无法解析时返回默认值。 */
private fun <T : Enum<T>> safeValueOf(name: String, default: T): T {
    return try { java.lang.Enum.valueOf(default::class.java as Class<T>, name) } catch (_: Exception) { default }
}
