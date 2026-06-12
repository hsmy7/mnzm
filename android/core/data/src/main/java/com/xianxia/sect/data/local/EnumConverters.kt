package com.xianxia.sect.data.local

import androidx.room.TypeConverter
import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.serialization.NullSafeProtoBuf
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.nullable

/**
 * ## EnumConverters - 枚举/简单值类型的 Room TypeConverter
 *
 * 处理非集合类型的领域对象转换（Protobuf 二进制序列化）。
 */
object EnumConverters {

    private val protoBuf = NullSafeProtoBuf.roomProtoBuf

    // ==================== 复杂对象转换器（纯 Protobuf）====================

    @TypeConverter
    @JvmStatic
    fun fromGameSettingsData(value: GameSettingsData?): String =
        ProtobufConverters.encodeNullableToBase64(GameSettingsData.serializer(), value)

    @TypeConverter
    @JvmStatic
    fun toGameSettingsData(value: String): GameSettingsData? =
        ProtobufConverters.decodeFromBase64(GameSettingsData.serializer().nullable, value) { null }

    @TypeConverter
    @JvmStatic
    fun fromElderSlots(value: ElderSlots): String =
        ProtobufConverters.encodeToBase64(ElderSlots.serializer(), value)

    @TypeConverter
    @JvmStatic
    fun toElderSlots(value: String): ElderSlots =
        ProtobufConverters.decodeFromBase64(ElderSlots.serializer(), value) { ElderSlots() }

    @TypeConverter
    @JvmStatic
    fun fromSectPolicies(value: SectPolicies): String =
        ProtobufConverters.encodeToBase64(SectPolicies.serializer(), value)

    @TypeConverter
    @JvmStatic
    fun toSectPolicies(value: String): SectPolicies =
        ProtobufConverters.decodeFromBase64(SectPolicies.serializer(), value) { SectPolicies() }

    @TypeConverter
    @JvmStatic
    fun fromSectScoutInfo(value: SectScoutInfo?): String =
        ProtobufConverters.encodeNullableToBase64(SectScoutInfo.serializer(), value)

    @TypeConverter
    @JvmStatic
    fun toSectScoutInfo(value: String): SectScoutInfo? =
        ProtobufConverters.decodeFromBase64(SectScoutInfo.serializer().nullable, value) { null }

    @TypeConverter
    @JvmStatic
    fun fromEquipmentNurtureData(value: EquipmentNurtureData?): String =
        ProtobufConverters.encodeNullableToBase64(EquipmentNurtureData.serializer(), value)

    @TypeConverter
    @JvmStatic
    fun toEquipmentNurtureData(value: String): EquipmentNurtureData? =
        ProtobufConverters.decodeFromBase64(EquipmentNurtureData.serializer(), value) { EquipmentNurtureData("", 0) }

    @TypeConverter
    @JvmStatic
    fun fromBattleTeam(value: BattleTeam?): String? =
        value?.let { ProtobufConverters.encodeToBase64(BattleTeam.serializer(), it) }

    @TypeConverter
    @JvmStatic
    fun toBattleTeam(value: String?): BattleTeam? =
        value?.let { ProtobufConverters.decodeFromBase64(BattleTeam.serializer(), it) { BattleTeam() } }

    @TypeConverter
    @JvmStatic
    fun fromBattleLogResult(value: BattleLogResult): String =
        ProtobufConverters.encodeToBase64(BattleLogResult.serializer(), value)

    @TypeConverter
    @JvmStatic
    fun toBattleLogResult(value: String): BattleLogResult =
        ProtobufConverters.decodeFromBase64(BattleLogResult.serializer(), value) { BattleLogResult() }

    @TypeConverter
    @JvmStatic
    fun fromPatrolConfig(value: PatrolConfig): String =
        ProtobufConverters.encodeToBase64(PatrolConfig.serializer(), value)

    @TypeConverter
    @JvmStatic
    fun toPatrolConfig(value: String): PatrolConfig =
        ProtobufConverters.decodeFromBase64(PatrolConfig.serializer(), value) { PatrolConfig() }

    @TypeConverter
    @JvmStatic
    fun fromBloodRefinementProgress(value: BloodRefinementProgress): String =
        ProtobufConverters.encodeToBase64(BloodRefinementProgress.serializer(), value)

    @TypeConverter
    @JvmStatic
    fun toBloodRefinementProgress(value: String): BloodRefinementProgress =
        ProtobufConverters.decodeFromBase64(BloodRefinementProgress.serializer(), value) { BloodRefinementProgress() }
}
