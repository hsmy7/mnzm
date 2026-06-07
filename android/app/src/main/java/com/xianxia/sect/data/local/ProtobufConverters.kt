package com.xianxia.sect.data.local

import androidx.room.TypeConverter
import android.util.Log
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.data.serialization.NullSafeProtoBuf
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import java.util.Base64
import net.jpountz.lz4.LZ4Compressor
import net.jpountz.lz4.LZ4Factory
import net.jpountz.lz4.LZ4FastDecompressor

/**
 * ## ProtobufConverters - 纯 Protobuf 的 Room TypeConverter
 *
 * ### 设计目标
 * 1. 完全替代 [ModelConverters] 中的 Gson JSON 字符串序列化
 * 2. 使用 **kotlinx.serialization.protobuf.ProtoBuf** 进行二进制序列化
 * 3. 通过 **Base64 编码** 将 ByteArray 转换为 String 存储（Room TEXT 列兼容）
 * 4. **KSP 安全**：无匿名内部类，避免 KSP NPE 问题
 * 5. **无 Gson 依赖**：不需要向后兼容
 *
 * ### 性能优势
 * - 二进制体积比 JSON 小 30-50%
 * - 序列化/反序列化速度提升 2-5x
 * - 类型安全：编译时检查 schema 一致性
 */
object ProtobufConverters {

    private const val TAG = "ProtobufConverters"

    /**
     * Room TypeConverter 用 ProtoBuf 实例 — encodeDefaults=false
     * 直接序列化领域对象（含 String?/Int? 可空字段），null 字段自动省略
     */
    private val protoBuf = NullSafeProtoBuf.roomProtoBuf

    // ==================== Serializer 实例（避免重复创建）====================

    private val stringListSerializer: KSerializer<List<String>> = ListSerializer(String.serializer())
    private val stringStringMapSerializer: KSerializer<Map<String, String>> =
        MapSerializer(String.serializer(), String.serializer())
    private val stringIntMapSerializer: KSerializer<Map<String, Int>> =
        MapSerializer(String.serializer(), Int.serializer())
    private val intIntMapSerializer: KSerializer<Map<Int, Int>> =
        MapSerializer(Int.serializer(), Int.serializer())
    private val stringDoubleMapSerializer: KSerializer<Map<String, Double>> =
        MapSerializer(String.serializer(), Double.serializer())
    private val intBooleanMapSerializer: KSerializer<Map<Int, Boolean>> =
        MapSerializer(Int.serializer(), Boolean.serializer())
    private val intSetSerializer: KSerializer<Set<Int>> =
        SetSerializer(Int.serializer())

    // LZ4 BLOB 压缩（写入前压缩，读出时解压）
    private const val LZ4_COMPRESSION_THRESHOLD = 1024
    private const val BLOB_COMPRESSED_MARKER: Byte = 0x01

    private val lz4Compressor: LZ4Compressor by lazy {
        LZ4Factory.fastestInstance().fastCompressor()
    }

    private val lz4Decompressor: LZ4FastDecompressor by lazy {
        LZ4Factory.fastestInstance().fastDecompressor()
    }

    // ==================== 工具方法 ====================

    private fun bytesToBase64(data: ByteArray): String =
        @Suppress("NewApi")
        Base64.getEncoder().encodeToString(data)

    private fun base64ToBytes(encoded: String): ByteArray =
        try {
            @Suppress("NewApi")
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            ByteArray(0)
        }

    // 预检查：Collection/Map 超过此大小拒绝序列化，防止 protoBuf 分配 GB 级 byte array
    private const val MAX_COLLECTION_SIZE = 100_000

    /**
     * 通用序列化方法：将任意 @Serializable 对象编码为 Base64 字符串
     * encodeDefaults=false 使可空字段为 null 时自动省略，符合 ProtoBuf proto3 语义
     */
    private fun <T : Any> encodeToBase64(serializer: KSerializer<T>, value: T): String {
        if (isTooLarge(value, serializer.descriptor.serialName)) return ""
        try {
            val bytes = protoBuf.encodeToByteArray(serializer, value)
            return bytesToBase64(bytes)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during serialization of ${serializer.descriptor.serialName}!")
            return ""
        } catch (e: Throwable) {
            Log.e(TAG, "Protobuf serialization FAILED for ${serializer.descriptor.serialName}, data will be lost!", e)
            return ""
        }
    }

    /**
     * 可空类型序列化方法
     */
    private fun <T : Any> encodeNullableToBase64(serializer: KSerializer<T>, value: T?): String {
        if (value == null) return ""
        if (isTooLarge(value, serializer.descriptor.serialName)) return ""
        try {
            val bytes = protoBuf.encodeToByteArray(serializer, value)
            return bytesToBase64(bytes)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during nullable serialization of ${serializer.descriptor.serialName}!")
            return ""
        } catch (e: Throwable) {
            Log.e(TAG, "Protobuf nullable serialization FAILED for ${serializer.descriptor.serialName}, data will be lost!", e)
            return ""
        }
    }

    /** 检查集合/Map 大小是否异常，防止序列化分配 GB 级内存 */
    private fun isTooLarge(value: Any, name: String): Boolean {
        val size = when (value) {
            is Collection<*> -> value.size
            is Map<*, *> -> value.size
            else -> -1
        }
        if (size > MAX_COLLECTION_SIZE) {
            Log.e(TAG, "CRITICAL: $name has $size entries (>$MAX_COLLECTION_SIZE), refusing to serialize to prevent OOM!")
            return true
        }
        return false
    }

    // 超过 50MB 打印警告（正常值远小于此），超过 500MB 硬拒绝防 OOM
    private const val DECODE_WARN_LENGTH =  50_000_000  // 50MB Base64 — 异常偏大，记录警告
    private const val DECODE_HARD_LIMIT   = 500_000_000  // 500MB Base64 — 必然异常，拒绝解码

    private fun <T> decodeFromBase64(serializer: KSerializer<T>, encoded: String, default: () -> T): T {
        if (encoded.isEmpty()) return default()
        if (encoded.length > DECODE_HARD_LIMIT) {
            Log.e(TAG, "Deserialization REJECTED for ${serializer.descriptor.serialName}: encoded length ${encoded.length} exceeds hard limit $DECODE_HARD_LIMIT, returning default to prevent OOM")
            return default()
        }
        if (encoded.length > DECODE_WARN_LENGTH) {
            Log.w(TAG, "Deserialization WARNING for ${serializer.descriptor.serialName}: encoded length ${encoded.length} exceeds warn threshold $DECODE_WARN_LENGTH, attempting decode")
        }
        try {
            val bytes = base64ToBytes(encoded)
            if (bytes.isEmpty()) return default()
            return protoBuf.decodeFromByteArray(serializer, bytes)
        } catch (e: Throwable) {
            Log.e(TAG, "Deserialization FAILED for ${serializer.descriptor.serialName}, returning default! Encoded length: ${encoded.length}", e)
            return default()
        }
    }

    // ==================== 基本类型转换器 ====================

    // --- List<String> ---

    @TypeConverter
    @JvmStatic
    fun fromStringList(value: List<String>): String =
        encodeToBase64(stringListSerializer, value)

    @TypeConverter
    @JvmStatic
    fun toStringList(value: String): List<String> =
        decodeFromBase64(stringListSerializer, value) { emptyList() }

    // --- Map<String, String> ---

    @TypeConverter
    @JvmStatic
    fun fromStringMap(value: Map<String, String>): String =
        encodeToBase64(stringStringMapSerializer, value)

    @TypeConverter
    @JvmStatic
    fun toStringMap(value: String): Map<String, String> =
        decodeFromBase64(stringStringMapSerializer, value) { emptyMap() }

    // --- Map<String, Int> ---

    @TypeConverter
    @JvmStatic
    fun fromIntMap(value: Map<String, Int>): String =
        encodeToBase64(stringIntMapSerializer, value)

    @TypeConverter
    @JvmStatic
    fun toIntMap(value: String): Map<String, Int> =
        decodeFromBase64(stringIntMapSerializer, value) { emptyMap() }

    // --- Map<Int, Int> ---

    @TypeConverter
    @JvmStatic
    fun fromIntIntMap(value: Map<Int, Int>): String =
        encodeToBase64(intIntMapSerializer, value)

    @TypeConverter
    @JvmStatic
    fun toIntIntMap(value: String): Map<Int, Int> =
        decodeFromBase64(intIntMapSerializer, value) { emptyMap() }

    // --- Map<String, Double> ---

    @TypeConverter
    @JvmStatic
    fun fromDoubleMap(value: Map<String, Double>): String =
        encodeToBase64(stringDoubleMapSerializer, value)

    @TypeConverter
    @JvmStatic
    fun toDoubleMap(value: String): Map<String, Double> =
        decodeFromBase64(stringDoubleMapSerializer, value) { emptyMap() }

    // --- Map<Int, Boolean> ---

    @TypeConverter
    @JvmStatic
    fun fromIntBooleanMap(value: Map<Int, Boolean>): String =
        encodeToBase64(intBooleanMapSerializer, value)

    @TypeConverter
    @JvmStatic
    fun toIntBooleanMap(value: String): Map<Int, Boolean> =
        decodeFromBase64(intBooleanMapSerializer, value) { emptyMap() }

    // --- Set<Int> ---

    @TypeConverter
    @JvmStatic
    fun fromIntSet(value: Set<Int>): String =
        encodeToBase64(intSetSerializer, value)

    @TypeConverter
    @JvmStatic
    fun toIntSet(value: String): Set<Int> =
        decodeFromBase64(intSetSerializer, value) { emptySet() }

    // ==================== 枚举类型转换器 ====================
    // 枚举使用 name.toString() / valueOf() 存储，与原 ModelConverters 保持一致

    @TypeConverter
    @JvmStatic
    fun fromEquipmentSlot(value: EquipmentSlot): String = value.name

    @TypeConverter
    @JvmStatic
    fun toEquipmentSlot(value: String): EquipmentSlot =
        EquipmentSlot.entries.find { it.name == value } ?: EquipmentSlot.WEAPON

    @TypeConverter
    @JvmStatic
    fun fromManualType(value: ManualType): String = value.name

    @TypeConverter
    @JvmStatic
    fun toManualType(value: String): ManualType = when (value) {
        "MOVEMENT" -> ManualType.SUPPORT
        "PRODUCTION" -> ManualType.SUPPORT
        else -> ManualType.entries.find { it.name == value } ?: ManualType.MIND
    }

    @TypeConverter
    @JvmStatic
    fun fromPillCategory(value: PillCategory): String = value.name

    @TypeConverter
    @JvmStatic
    fun toPillCategory(value: String): PillCategory = when (value) {
        "BATTLE" -> PillCategory.BATTLE
        "BATTLE_PHYSICAL", "BATTLE_MAGIC", "BATTLE_STATUS" -> PillCategory.BATTLE
        "BREAKTHROUGH" -> PillCategory.CULTIVATION
        "HEALING" -> PillCategory.FUNCTIONAL
        else -> PillCategory.entries.find { it.name == value } ?: PillCategory.CULTIVATION
    }

    @TypeConverter
    @JvmStatic
    fun fromMaterialCategory(value: MaterialCategory): String = value.name

    @TypeConverter
    @JvmStatic
    fun toMaterialCategory(value: String): MaterialCategory =
        MaterialCategory.entries.find { it.name == value } ?: MaterialCategory.BEAST_HIDE

    @TypeConverter
    @JvmStatic
    fun fromDiscipleStatus(value: DiscipleStatus): String = value.name

    @TypeConverter
    @JvmStatic
    fun toDiscipleStatus(value: String): DiscipleStatus =
        DiscipleStatus.entries.find { it.name == value } ?: DiscipleStatus.IDLE

    @TypeConverter
    @JvmStatic
    fun fromTeamStatus(value: TeamStatus): String = value.name

    @TypeConverter
    @JvmStatic
    fun toTeamStatus(value: String): TeamStatus =
        TeamStatus.entries.find { it.name == value } ?: TeamStatus.IDLE

    @TypeConverter
    @JvmStatic
    fun fromSlotStatus(value: SlotStatus): String = value.name

    @TypeConverter
    @JvmStatic
    fun toSlotStatus(value: String): SlotStatus =
        SlotStatus.entries.find { it.name == value } ?: SlotStatus.IDLE

    @TypeConverter
    @JvmStatic
    fun fromRecipeType(value: RecipeType): String = value.name

    @TypeConverter
    @JvmStatic
    fun toRecipeType(value: String): RecipeType =
        RecipeType.entries.find { it.name == value } ?: RecipeType.PILL

    @TypeConverter
    @JvmStatic
    fun fromBattleType(value: BattleType): String = value.name

    @TypeConverter
    @JvmStatic
    fun toBattleType(value: String): BattleType =
        BattleType.entries.find { it.name == value } ?: BattleType.PVE

    @TypeConverter
    @JvmStatic
    fun fromBattleResult(value: BattleResult): String = value.name

    @TypeConverter
    @JvmStatic
    fun toBattleResult(value: String): BattleResult =
        BattleResult.entries.find { it.name == value } ?: BattleResult.DRAW

    // ==================== 复杂对象转换器（纯 Protobuf）====================

    @TypeConverter
    @JvmStatic
    fun fromGameSettingsData(value: GameSettingsData?): String =
        encodeNullableToBase64(GameSettingsData.serializer(), value)

    @TypeConverter
    @JvmStatic
    fun toGameSettingsData(value: String): GameSettingsData? =
        decodeFromBase64(GameSettingsData.serializer().nullable, value) { null }

    // DungeonRewards converters removed

    @TypeConverter
    @JvmStatic
    fun fromElderSlots(value: ElderSlots): String =
        encodeToBase64(ElderSlots.serializer(), value)

    @TypeConverter
    @JvmStatic
    fun toElderSlots(value: String): ElderSlots =
        decodeFromBase64(ElderSlots.serializer(), value) { ElderSlots() }

    @TypeConverter
    @JvmStatic
    fun fromSectPolicies(value: SectPolicies): String =
        encodeToBase64(SectPolicies.serializer(), value)

    @TypeConverter
    @JvmStatic
    fun toSectPolicies(value: String): SectPolicies =
        decodeFromBase64(SectPolicies.serializer(), value) { SectPolicies() }

    @TypeConverter
    @JvmStatic
    fun fromSectScoutInfo(value: SectScoutInfo?): String =
        encodeNullableToBase64(SectScoutInfo.serializer(), value)

    @TypeConverter
    @JvmStatic
    fun toSectScoutInfo(value: String): SectScoutInfo? =
        decodeFromBase64(SectScoutInfo.serializer().nullable, value) { null }

    @TypeConverter
    @JvmStatic
    fun fromEquipmentNurtureData(value: EquipmentNurtureData?): String =
        encodeNullableToBase64(EquipmentNurtureData.serializer(), value)

    @TypeConverter
    @JvmStatic
    fun toEquipmentNurtureData(value: String): EquipmentNurtureData? =
        decodeFromBase64(EquipmentNurtureData.serializer(), value) { EquipmentNurtureData("", 0) }

    // ==================== 复杂列表转换器（纯 Protobuf）====================

    @TypeConverter
    @JvmStatic
    fun fromDiscipleList(value: List<Disciple>): String {
        val result = encodeToBase64(ListSerializer(Disciple.serializer()), value)
        if (result.isEmpty() && value.isNotEmpty()) {
            Log.e(TAG, "CRITICAL: fromDiscipleList serialization FAILED for ${value.size} disciples! Data will be lost on save!")
        }
        return result
    }

    @TypeConverter
    @JvmStatic
    fun toDiscipleList(value: String): List<Disciple> {
        val result = decodeFromBase64(ListSerializer(Disciple.serializer()), value) { emptyList() }
        if (result.isEmpty() && value.isNotEmpty()) {
            Log.e(TAG, "CRITICAL: toDiscipleList deserialization returned empty list from non-empty data! Encoded length: ${value.length}")
        }
        return result
    }

    // aiSectDisciples — 数据量极大（全 AI 宗门弟子），走增量编码，TypeConverter 仅做 Room 兼容占位
    @TypeConverter
    @JvmStatic
    fun fromDiscipleListMap(value: Map<String, List<Disciple>>): String {
        if (value.isNotEmpty()) {
            Log.w(TAG, "aiSectDisciples has ${value.size} sects / ${value.values.sumOf { it.size }} disciples — should use incremental encoding, TypeConverter returns empty to avoid OOM")
        }
        return ""
    }

    @TypeConverter
    @JvmStatic
    fun toDiscipleListMap(value: String): Map<String, List<Disciple>> =
        if (value.isEmpty()) emptyMap()
        else decodeFromBase64(MapSerializer(String.serializer(), ListSerializer(Disciple.serializer())), value) { emptyMap() }

    @TypeConverter
    @JvmStatic
    fun fromBuildingSlotList(value: List<BuildingSlot>): String =
        encodeToBase64(ListSerializer(BuildingSlot.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toBuildingSlotList(value: String): List<BuildingSlot> =
        decodeFromBase64(ListSerializer(BuildingSlot.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromMerchantItemList(value: List<MerchantItem>): String =
        encodeToBase64(ListSerializer(MerchantItem.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toMerchantItemList(value: String): List<MerchantItem> =
        decodeFromBase64(ListSerializer(MerchantItem.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromStorageBagItemList(value: List<StorageBagItem>): String =
        encodeToBase64(ListSerializer(StorageBagItem.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toStorageBagItemList(value: String): List<StorageBagItem> =
        decodeFromBase64(ListSerializer(StorageBagItem.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromPlantSlotDataList(value: List<PlantSlotData>): String =
        encodeToBase64(ListSerializer(PlantSlotData.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toPlantSlotDataList(value: String): List<PlantSlotData> =
        decodeFromBase64(ListSerializer(PlantSlotData.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromCultivatorCaveList(value: List<CultivatorCave>): String =
        encodeToBase64(ListSerializer(CultivatorCave.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toCultivatorCaveList(value: String): List<CultivatorCave> =
        decodeFromBase64(ListSerializer(CultivatorCave.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromWorldLevelList(value: List<WorldLevel>): String =
        encodeToBase64(ListSerializer(WorldLevel.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toWorldLevelList(value: String): List<WorldLevel> =
        decodeFromBase64(ListSerializer(WorldLevel.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromCaveExplorationTeamList(value: List<CaveExplorationTeam>): String =
        encodeToBase64(ListSerializer(CaveExplorationTeam.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toCaveExplorationTeamList(value: String): List<CaveExplorationTeam> =
        decodeFromBase64(ListSerializer(CaveExplorationTeam.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromMineSlotList(value: List<MineSlot>): String =
        encodeToBase64(ListSerializer(MineSlot.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toMineSlotList(value: String): List<MineSlot> =
        decodeFromBase64(ListSerializer(MineSlot.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromAICaveTeamList(value: List<AICaveTeam>): String =
        encodeToBase64(ListSerializer(AICaveTeam.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toAICaveTeamList(value: String): List<AICaveTeam> =
        decodeFromBase64(ListSerializer(AICaveTeam.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromWorldSectList(value: List<WorldSect>): String =
        encodeToBase64(ListSerializer(WorldSect.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toWorldSectList(value: String): List<WorldSect> =
        decodeFromBase64(ListSerializer(WorldSect.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromSpiritMineSlotList(value: List<SpiritMineSlot>): String =
        encodeToBase64(ListSerializer(SpiritMineSlot.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toSpiritMineSlotList(value: String): List<SpiritMineSlot> =
        decodeFromBase64(ListSerializer(SpiritMineSlot.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromGridBuildingDataList(value: List<GridBuildingData>): String =
        encodeToBase64(ListSerializer(GridBuildingData.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toGridBuildingDataList(value: String): List<GridBuildingData> =
        decodeFromBase64(ListSerializer(GridBuildingData.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromLibrarySlotList(value: List<LibrarySlot>): String =
        encodeToBase64(ListSerializer(LibrarySlot.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toLibrarySlotList(value: String): List<LibrarySlot> =
        decodeFromBase64(ListSerializer(LibrarySlot.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromResidenceSlotList(value: List<ResidenceSlot>): String =
        encodeToBase64(ListSerializer(ResidenceSlot.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toResidenceSlotList(value: String): List<ResidenceSlot> =
        decodeFromBase64(ListSerializer(ResidenceSlot.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromSpiritFieldPlantList(value: List<SpiritFieldPlant>): String =
        encodeToBase64(ListSerializer(SpiritFieldPlant.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toSpiritFieldPlantList(value: String): List<SpiritFieldPlant> =
        decodeFromBase64(ListSerializer(SpiritFieldPlant.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromWarehouseGarrisonSlotList(value: List<WarehouseGarrisonSlot>): String =
        encodeToBase64(ListSerializer(WarehouseGarrisonSlot.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toWarehouseGarrisonSlotList(value: String): List<WarehouseGarrisonSlot> =
        decodeFromBase64(ListSerializer(WarehouseGarrisonSlot.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromDirectDiscipleSlotList(value: List<DirectDiscipleSlot>): String =
        encodeToBase64(ListSerializer(DirectDiscipleSlot.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toDirectDiscipleSlotList(value: String): List<DirectDiscipleSlot> =
        decodeFromBase64(ListSerializer(DirectDiscipleSlot.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromAllianceList(value: List<Alliance>): String =
        encodeToBase64(ListSerializer(Alliance.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toAllianceList(value: String): List<Alliance> =
        decodeFromBase64(ListSerializer(Alliance.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromSectRelationList(value: List<SectRelation>): String =
        encodeToBase64(ListSerializer(SectRelation.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toSectRelationList(value: String): List<SectRelation> =
        decodeFromBase64(ListSerializer(SectRelation.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromActiveMissionList(value: List<ActiveMission>): String =
        encodeToBase64(ListSerializer(ActiveMission.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toActiveMissionList(value: String): List<ActiveMission> =
        decodeFromBase64(ListSerializer(ActiveMission.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromMissionList(value: List<Mission>): String =
        encodeToBase64(ListSerializer(Mission.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toMissionList(value: String): List<Mission> =
        decodeFromBase64(ListSerializer(Mission.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromBattleTeam(value: BattleTeam?): String? =
        value?.let { encodeToBase64(BattleTeam.serializer(), it) }

    @TypeConverter
    @JvmStatic
    fun toBattleTeam(value: String?): BattleTeam? =
        value?.let { decodeFromBase64(BattleTeam.serializer(), it) { BattleTeam() } }

    @TypeConverter
    @JvmStatic
    fun fromAIBattleTeamList(value: List<AIBattleTeam>): String =
        encodeToBase64(ListSerializer(AIBattleTeam.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toAIBattleTeamList(value: String): List<AIBattleTeam> =
        decodeFromBase64(ListSerializer(AIBattleTeam.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromAlchemySlotList(value: List<AlchemySlot>): String =
        encodeToBase64(ListSerializer(AlchemySlot.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toAlchemySlotList(value: String): List<AlchemySlot> =
        decodeFromBase64(ListSerializer(AlchemySlot.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromProductionSlotList(value: List<ProductionSlot>): String =
        encodeToBase64(ListSerializer(ProductionSlot.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toProductionSlotList(value: String): List<ProductionSlot> =
        decodeFromBase64(ListSerializer(ProductionSlot.serializer()), value) { emptyList() }

    // ==================== 复杂 Map 转换器（纯 Protobuf）====================

    @TypeConverter
    @JvmStatic
    fun fromExploredSectInfoMap(value: Map<String, ExploredSectInfo>): String =
        encodeToBase64(MapSerializer(String.serializer(), ExploredSectInfo.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toExploredSectInfoMap(value: String): Map<String, ExploredSectInfo> =
        decodeFromBase64(MapSerializer(String.serializer(), ExploredSectInfo.serializer()), value) { emptyMap() }

    @TypeConverter
    @JvmStatic
    fun fromSectScoutInfoMap(value: Map<String, SectScoutInfo>): String =
        encodeToBase64(MapSerializer(String.serializer(), SectScoutInfo.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toSectScoutInfoMap(value: String): Map<String, SectScoutInfo> =
        decodeFromBase64(MapSerializer(String.serializer(), SectScoutInfo.serializer()), value) { emptyMap() }

    @TypeConverter
    @JvmStatic
    fun fromManualProficiencyDataMap(value: Map<String, List<ManualProficiencyData>>): String =
        encodeToBase64(MapSerializer(String.serializer(), ListSerializer(ManualProficiencyData.serializer())), value)

    @TypeConverter
    @JvmStatic
    fun toManualProficiencyDataMap(value: String): Map<String, List<ManualProficiencyData>> =
        decodeFromBase64(MapSerializer(String.serializer(), ListSerializer(ManualProficiencyData.serializer())), value) { emptyMap() }

    // ==================== 战斗日志相关（纯 Protobuf）====================

    @TypeConverter
    @JvmStatic
    fun fromBattleLogMemberList(value: List<BattleLogMember>): String =
        encodeToBase64(ListSerializer(BattleLogMember.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toBattleLogMemberList(value: String): List<BattleLogMember> =
        decodeFromBase64(ListSerializer(BattleLogMember.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromBattleLogEnemyList(value: List<BattleLogEnemy>): String =
        encodeToBase64(ListSerializer(BattleLogEnemy.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toBattleLogEnemyList(value: String): List<BattleLogEnemy> =
        decodeFromBase64(ListSerializer(BattleLogEnemy.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromBattleLogRoundList(value: List<BattleLogRound>): String =
        encodeToBase64(ListSerializer(BattleLogRound.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toBattleLogRoundList(value: String): List<BattleLogRound> =
        decodeFromBase64(ListSerializer(BattleLogRound.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromBattleLogActionList(value: List<BattleLogAction>): String =
        encodeToBase64(ListSerializer(BattleLogAction.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toBattleLogActionList(value: String): List<BattleLogAction> =
        decodeFromBase64(ListSerializer(BattleLogAction.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromBattleLogResult(value: BattleLogResult): String =
        encodeToBase64(BattleLogResult.serializer(), value)

    @TypeConverter
    @JvmStatic
    fun toBattleLogResult(value: String): BattleLogResult =
        decodeFromBase64(BattleLogResult.serializer(), value) { BattleLogResult() }

    @TypeConverter
    @JvmStatic
    fun fromSectDetailMap(value: Map<String, SectDetail>): String =
        encodeToBase64(MapSerializer(String.serializer(), SectDetail.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toSectDetailMap(value: String): Map<String, SectDetail> =
        decodeFromBase64(MapSerializer(String.serializer(), SectDetail.serializer()), value) { emptyMap() }

    // 巡视楼
    @TypeConverter
    @JvmStatic
    fun fromPatrolSlotList(value: List<PatrolSlot>): String =
        encodeToBase64(ListSerializer(PatrolSlot.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toPatrolSlotList(value: String): List<PatrolSlot> =
        decodeFromBase64(ListSerializer(PatrolSlot.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromPatrolConfig(value: PatrolConfig): String =
        encodeToBase64(PatrolConfig.serializer(), value)

    @TypeConverter
    @JvmStatic
    fun toPatrolConfig(value: String): PatrolConfig =
        decodeFromBase64(PatrolConfig.serializer(), value) { PatrolConfig() }

    @TypeConverter
    @JvmStatic
    fun fromPatrolConfigList(value: List<PatrolConfig>): String =
        encodeToBase64(ListSerializer(PatrolConfig.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toPatrolConfigList(value: String): List<PatrolConfig> =
        decodeFromBase64(ListSerializer(PatrolConfig.serializer()), value) { emptyList() }

    // ==================== 血炼系统转换器 ====================

    @TypeConverter
    @JvmStatic
    fun fromBloodRefinementProgress(value: BloodRefinementProgress): String =
        encodeToBase64(BloodRefinementProgress.serializer(), value)

    @TypeConverter
    @JvmStatic
    fun toBloodRefinementProgress(value: String): BloodRefinementProgress =
        decodeFromBase64(BloodRefinementProgress.serializer(), value) { BloodRefinementProgress() }

    @TypeConverter
    @JvmStatic
    fun fromBloodRefinementProgressMap(value: Map<String, BloodRefinementProgress>): String =
        encodeToBase64(MapSerializer(String.serializer(), BloodRefinementProgress.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toBloodRefinementProgressMap(value: String): Map<String, BloodRefinementProgress> =
        decodeFromBase64(MapSerializer(String.serializer(), BloodRefinementProgress.serializer()), value) { emptyMap() }

    @TypeConverter
    @JvmStatic
    fun fromStringListMap(value: Map<String, List<String>>): String =
        encodeToBase64(MapSerializer(String.serializer(), ListSerializer(String.serializer())), value)

    @TypeConverter
    @JvmStatic
    fun toStringListMap(value: String): Map<String, List<String>> =
        decodeFromBase64(MapSerializer(String.serializer(), ListSerializer(String.serializer())), value) { emptyMap() }

    // ═══════════════════════════════════════════════════════════
    // 增量编码 API（无 Base64，逐项/分批序列化）
    // ═══════════════════════════════════════════════════════════

    /**
     * Map<String, List<Disciple>> — 每个宗门独立一行
     * 用例：aiSectDisciples
     */
    suspend fun encodeDiscipleListMapIncremental(
        value: Map<String, List<Disciple>>,
        slotId: Int,
        keyPrefix: String,
        writer: suspend (List<GameHeavyData>) -> Unit
    ) {
        val serializer = ListSerializer(Disciple.serializer())
        for ((sectName, disciples) in value) {
            val bytes = encodeToBlobInternal(serializer, disciples)
            writer(GameHeavyData.chunk(slotId, GameHeavyData.chunkKey(keyPrefix, sectName), bytes))
        }
    }

    fun decodeDiscipleListMapFromRows(
        rows: List<GameHeavyData>,
        keyPrefix: String
    ): Map<String, List<Disciple>> {
        val serializer = ListSerializer(Disciple.serializer())
        val result = mutableMapOf<String, List<Disciple>>()
        for (row in rows) {
            val sectName = GameHeavyData.parseChunkKey(row.dataKey, keyPrefix) ?: continue
            result[sectName] = decodeFromBlobInternal(serializer, row.dataValue) { emptyList() }
        }
        return result
    }

    /**
     * Map<String, SectDetail> — 每个宗门独立一行
     * 用例：sectDetails
     */
    suspend fun encodeSectDetailMapIncremental(
        value: Map<String, SectDetail>,
        slotId: Int,
        keyPrefix: String,
        writer: suspend (List<GameHeavyData>) -> Unit
    ) {
        val serializer = SectDetail.serializer()
        for ((sectName, detail) in value) {
            val bytes = encodeToBlobInternal(serializer, detail)
            writer(GameHeavyData.chunk(slotId, GameHeavyData.chunkKey(keyPrefix, sectName), bytes))
        }
    }

    fun decodeSectDetailMapFromRows(
        rows: List<GameHeavyData>,
        keyPrefix: String
    ): Map<String, SectDetail> {
        val serializer = SectDetail.serializer()
        val result = mutableMapOf<String, SectDetail>()
        for (row in rows) {
            val sectName = GameHeavyData.parseChunkKey(row.dataKey, keyPrefix) ?: continue
            result[sectName] = decodeFromBlobInternal(serializer, row.dataValue) { SectDetail() }
        }
        return result
    }

    /**
     * Map<String, ExploredSectInfo> — 逐项
     * 用例：exploredSects
     */
    suspend fun encodeExploredSectInfoMapIncremental(
        value: Map<String, ExploredSectInfo>,
        slotId: Int,
        keyPrefix: String,
        writer: suspend (List<GameHeavyData>) -> Unit
    ) {
        val serializer = ExploredSectInfo.serializer()
        for ((sectName, info) in value) {
            val bytes = encodeToBlobInternal(serializer, info)
            writer(GameHeavyData.chunk(slotId, GameHeavyData.chunkKey(keyPrefix, sectName), bytes))
        }
    }

    fun decodeExploredSectInfoMapFromRows(
        rows: List<GameHeavyData>,
        keyPrefix: String
    ): Map<String, ExploredSectInfo> {
        val serializer = ExploredSectInfo.serializer()
        val result = mutableMapOf<String, ExploredSectInfo>()
        for (row in rows) {
            val sectName = GameHeavyData.parseChunkKey(row.dataKey, keyPrefix) ?: continue
            result[sectName] = decodeFromBlobInternal(serializer, row.dataValue) { ExploredSectInfo() }
        }
        return result
    }

    /**
     * Map<String, SectScoutInfo> — 逐项
     * 用例：scoutInfo
     */
    suspend fun encodeSectScoutInfoMapIncremental(
        value: Map<String, SectScoutInfo>,
        slotId: Int,
        keyPrefix: String,
        writer: suspend (List<GameHeavyData>) -> Unit
    ) {
        val serializer = SectScoutInfo.serializer()
        for ((sectName, info) in value) {
            val bytes = encodeToBlobInternal(serializer, info)
            writer(GameHeavyData.chunk(slotId, GameHeavyData.chunkKey(keyPrefix, sectName), bytes))
        }
    }

    fun decodeSectScoutInfoMapFromRows(
        rows: List<GameHeavyData>,
        keyPrefix: String
    ): Map<String, SectScoutInfo> {
        val serializer = SectScoutInfo.serializer()
        val result = mutableMapOf<String, SectScoutInfo>()
        for (row in rows) {
            val sectName = GameHeavyData.parseChunkKey(row.dataKey, keyPrefix) ?: continue
            result[sectName] = decodeFromBlobInternal(serializer, row.dataValue) { SectScoutInfo() }
        }
        return result
    }

    /**
     * Map<String, List<ManualProficiencyData>> — 逐项
     * 用例：manualProficiencies
     */
    suspend fun encodeManualProficiencyMapIncremental(
        value: Map<String, List<ManualProficiencyData>>,
        slotId: Int,
        keyPrefix: String,
        writer: suspend (List<GameHeavyData>) -> Unit
    ) {
        val serializer = ListSerializer(ManualProficiencyData.serializer())
        for ((key, proficiencies) in value) {
            val bytes = encodeToBlobInternal(serializer, proficiencies)
            writer(GameHeavyData.chunk(slotId, GameHeavyData.chunkKey(keyPrefix, key), bytes))
        }
    }

    fun decodeManualProficiencyMapFromRows(
        rows: List<GameHeavyData>,
        keyPrefix: String
    ): Map<String, List<ManualProficiencyData>> {
        val serializer = ListSerializer(ManualProficiencyData.serializer())
        val result = mutableMapOf<String, List<ManualProficiencyData>>()
        for (row in rows) {
            val key = GameHeavyData.parseChunkKey(row.dataKey, keyPrefix) ?: continue
            result[key] = decodeFromBlobInternal(serializer, row.dataValue) { emptyList() }
        }
        return result
    }

    /**
     * List<Disciple> — 每 100 条一批
     * 用例：recruitList
     */
    suspend fun encodeDiscipleListIncremental(
        value: List<Disciple>,
        slotId: Int,
        keyPrefix: String,
        writer: suspend (List<GameHeavyData>) -> Unit
    ) {
        val serializer = ListSerializer(Disciple.serializer())
        value.chunked(100).forEachIndexed { index, batch ->
            val bytes = encodeToBlobInternal(serializer, batch)
            writer(GameHeavyData.chunk(slotId, GameHeavyData.chunkKey(keyPrefix, index.toString()), bytes))
        }
    }

    fun decodeDiscipleListFromRows(
        rows: List<GameHeavyData>,
        keyPrefix: String
    ): List<Disciple> {
        val serializer = ListSerializer(Disciple.serializer())
        val result = mutableListOf<Disciple>()
        val relevant = rows.filter { it.dataKey.startsWith("$keyPrefix/") }
            .sortedBy { it.dataKey }
        for (row in relevant) {
            result.addAll(decodeFromBlobInternal(serializer, row.dataValue) { emptyList() })
        }
        return result
    }

    /**
     * List<WorldSect> — 每 50 条一批
     * 用例：worldMapSects
     */
    suspend fun encodeWorldSectListIncremental(
        value: List<WorldSect>,
        slotId: Int,
        keyPrefix: String,
        writer: suspend (List<GameHeavyData>) -> Unit
    ) {
        val serializer = ListSerializer(WorldSect.serializer())
        value.chunked(50).forEachIndexed { index, batch ->
            val bytes = encodeToBlobInternal(serializer, batch)
            writer(GameHeavyData.chunk(slotId, GameHeavyData.chunkKey(keyPrefix, index.toString()), bytes))
        }
    }

    fun decodeWorldSectListFromRows(
        rows: List<GameHeavyData>,
        keyPrefix: String
    ): List<WorldSect> {
        val serializer = ListSerializer(WorldSect.serializer())
        val result = mutableListOf<WorldSect>()
        val relevant = rows.filter { it.dataKey.startsWith("$keyPrefix/") }
            .sortedBy { it.dataKey }
        for (row in relevant) {
            result.addAll(decodeFromBlobInternal(serializer, row.dataValue) { emptyList() })
        }
        return result
    }

    // ═══════════════════════════════════════════════════════════
    // 底层 BLOB 编解码（无 Base64）
    // ═══════════════════════════════════════════════════════════

    private fun <T : Any> encodeToBlobInternal(serializer: KSerializer<T>, value: T): ByteArray {
        try {
            val sizeCheck = when (value) {
                is Collection<*> -> value.size
                is Map<*, *> -> value.size
                else -> -1
            }
            if (sizeCheck > MAX_COLLECTION_SIZE) {
                Log.e(TAG, "CRITICAL: BLOB encode ${serializer.descriptor.serialName} has $sizeCheck entries, refusing to prevent OOM!")
                return ByteArray(0)
            }
            val bytes = protoBuf.encodeToByteArray(serializer, value)
            if (bytes.size >= LZ4_COMPRESSION_THRESHOLD) {
                val maxCompressedLength = lz4Compressor.maxCompressedLength(bytes.size)
                val compressed = ByteArray(maxCompressedLength)
                val compressedLength = lz4Compressor.compress(bytes, 0, bytes.size, compressed, 0)
                if (compressedLength < bytes.size) {
                    // 0x01 marker + 4 bytes original size + LZ4 compressed data
                    val result = ByteArray(1 + 4 + compressedLength)
                    result[0] = BLOB_COMPRESSED_MARKER
                    result[1] = (bytes.size shr 24).toByte()
                    result[2] = (bytes.size shr 16).toByte()
                    result[3] = (bytes.size shr 8).toByte()
                    result[4] = bytes.size.toByte()
                    System.arraycopy(compressed, 0, result, 5, compressedLength)
                    return result
                }
            }
            return bytes
        } catch (e: Throwable) {
            Log.e(TAG, "Protobuf BLOB encode FAILED for ${serializer.descriptor.serialName}", e)
            return ByteArray(0)
        }
    }

    private fun <T> decodeFromBlobInternal(
        serializer: KSerializer<T>,
        data: ByteArray,
        default: () -> T
    ): T {
        if (data.isEmpty()) return default()

        // LZ4 压缩数据：0x01 标记 + 4 字节原始大小 + 压缩数据
        if (data.size > 5 && data[0] == BLOB_COMPRESSED_MARKER) {
            try {
                val originalSize = ((data[1].toInt() and 0xFF) shl 24) or
                                  ((data[2].toInt() and 0xFF) shl 16) or
                                  ((data[3].toInt() and 0xFF) shl 8) or
                                  (data[4].toInt() and 0xFF)
                val compressedData = data.copyOfRange(5, data.size)
                val decompressed = ByteArray(originalSize)
                lz4Decompressor.decompress(compressedData, 0, decompressed, 0, originalSize)
                return protoBuf.decodeFromByteArray(serializer, decompressed)
            } catch (e: Exception) {
                Log.w(TAG, "LZ4 decompression failed for ${serializer.descriptor.serialName}, falling back to direct decode", e)
            }
        }

        // 第一步：直接 protobuf 解码（新 BLOB 格式）
        try {
            return protoBuf.decodeFromByteArray(serializer, data)
        } catch (e1: Exception) {
            // 第二步：尝试 Base64 回退（旧数据经 CAST 迁移到 BLOB 列的 Base64 字符串）
            try {
                val base64String = data.decodeToString()
                val rawBytes = Base64.getDecoder().decode(base64String)
                return protoBuf.decodeFromByteArray(serializer, rawBytes)
            } catch (e2: Exception) {
                Log.e(TAG,
                    "BLOB decode FAILED for ${serializer.descriptor.serialName}, " +
                    "data.size=${data.size}, firstByte=${data.getOrNull(0)}, " +
                    "protoErr=${e1.message?.take(80)}, base64Err=${e2.message?.take(80)}"
                )
                return default()
            }
        }
    }
}
