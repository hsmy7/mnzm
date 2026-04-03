package com.xianxia.sect.data.local

import androidx.room.TypeConverter
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.ProductionSlot
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.protobuf.ProtoBuf
import java.util.Base64

/**
 * ## ProtobufConverters - 纯 Protobuf 的 Room TypeConverter
 *
 * ### 设计目标
 * 1. 完全替代 [ModelConverters] 中的 Gson JSON 字符串序列化
 * 2. 使用 **kotlinx.serialization.protobuf.ProtoBuf** 进行二进制序列化
 * 3. 通过 **Base64 编码** 将 ByteArray 转换为 String 存储（Room TEXT 列兼容）
 * 4. **KSP 安全**：无匿名内部类，避免 KSP NPE 问题
 * 5. **无 Gson 依赖**：删档测试版本，不需要向后兼容
 *
 * ### 性能优势
 * - 二进制体积比 JSON 小 30-50%
 * - 序列化/反序列化速度提升 2-5x
 * - 类型安全：编译时检查 schema 一致性
 */
object ProtobufConverters {

    @OptIn(ExperimentalSerializationApi::class)
    private val protoBuf = ProtoBuf {
        encodeDefaults = true
    }

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

    // ==================== 工具方法 ====================

    private fun bytesToBase64(data: ByteArray): String =
        Base64.getEncoder().encodeToString(data)

    private fun base64ToBytes(encoded: String): ByteArray =
        try {
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            ByteArray(0)
        }

    /**
     * 通用序列化方法：将任意 @Serializable 对象编码为 Base64 字符串
     */
    private fun <T : Any> encodeToBase64(serializer: KSerializer<T>, value: T): String {
        try {
            val bytes = protoBuf.encodeToByteArray(serializer, value)
            return bytesToBase64(bytes)
        } catch (e: Exception) {
            return ""
        }
    }

    /**
     * 可空类型序列化方法
     */
    private fun <T : Any> encodeNullableToBase64(serializer: KSerializer<T>, value: T?): String {
        if (value == null) return ""
        try {
            val bytes = protoBuf.encodeToByteArray(serializer, value)
            return bytesToBase64(bytes)
        } catch (e: Exception) {
            return ""
        }
    }

    /**
     * 通用反序列化方法：从 Base64 字符串解码为对象
     */
    private fun <T> decodeFromBase64(serializer: KSerializer<T>, encoded: String, default: () -> T): T {
        if (encoded.isEmpty()) return default()
        try {
            val bytes = base64ToBytes(encoded)
            if (bytes.isEmpty()) return default()
            return protoBuf.decodeFromByteArray(serializer, bytes)
        } catch (e: Exception) {
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
        else -> ManualType.entries.find { it.name == value } ?: ManualType.MIND
    }

    @TypeConverter
    @JvmStatic
    fun fromPillCategory(value: PillCategory): String = value.name

    @TypeConverter
    @JvmStatic
    fun toPillCategory(value: String): PillCategory = when (value) {
        "BATTLE" -> PillCategory.BATTLE_PHYSICAL
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
    fun fromEventType(value: EventType): String = value.name

    @TypeConverter
    @JvmStatic
    fun toEventType(value: String): EventType =
        EventType.entries.find { it.name == value } ?: EventType.INFO

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

    @TypeConverter
    @JvmStatic
    fun fromDungeonRewards(value: DungeonRewards): String =
        encodeToBase64(DungeonRewards.serializer(), value)

    @TypeConverter
    @JvmStatic
    fun toDungeonRewards(value: String): DungeonRewards =
        decodeFromBase64(DungeonRewards.serializer(), value) { DungeonRewards() }

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
    fun fromBattleTeam(value: BattleTeam?): String =
        encodeNullableToBase64(BattleTeam.serializer(), value)

    @TypeConverter
    @JvmStatic
    fun toBattleTeam(value: String): BattleTeam? =
        decodeFromBase64(BattleTeam.serializer().nullable, value) { null }

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
        decodeFromBase64(EquipmentNurtureData.serializer().nullable, value) { null }

    // ==================== 复杂列表转换器（纯 Protobuf）====================

    @TypeConverter
    @JvmStatic
    fun fromDiscipleList(value: List<Disciple>): String =
        encodeToBase64(ListSerializer(Disciple.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toDiscipleList(value: String): List<Disciple> =
        decodeFromBase64(ListSerializer(Disciple.serializer()), value) { emptyList() }

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
    fun fromLibrarySlotList(value: List<LibrarySlot>): String =
        encodeToBase64(ListSerializer(LibrarySlot.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toLibrarySlotList(value: String): List<LibrarySlot> =
        decodeFromBase64(ListSerializer(LibrarySlot.serializer()), value) { emptyList() }

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
    fun fromAIBattleTeamList(value: List<AIBattleTeam>): String =
        encodeToBase64(ListSerializer(AIBattleTeam.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toAIBattleTeamList(value: String): List<AIBattleTeam> =
        decodeFromBase64(ListSerializer(AIBattleTeam.serializer()), value) { emptyList() }

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
}
