package com.xianxia.sect.core.model

import androidx.room.TypeConverter
import com.xianxia.sect.data.GsonConfig
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/**
 * @deprecated 已被 [com.xianxia.sect.data.local.ProtobufConverters] 完全替代。
 * 所有 Room TypeConverter 现在使用纯 Protobuf 序列化（kotlinx.serialization.protobuf.ProtoBuf）。
 * 此文件保留仅为可能的非 Room 引用兼容，不应在新代码中使用。
 *
 * 迁移时间: 删档测试版本
 * 替代方案: ProtobufConverters（基于 kotlinx.serialization + Base64）
 */
@Deprecated(
    message = "已被 ProtobufConverters 完全替代，请使用 ProtobufConverters",
    replaceWith = ReplaceWith("ProtobufConverters", "com.xianxia.sect.data.local.ProtobufConverters")
)

// ==================== 类型引用工具（具名类实现，彻底避免匿名内部类触发 KSP NPE）===================
// KSP 1.x 已知 bug: KSDeclarationImpl.getSimpleName() 对匿名类返回 null
// 方案: 使用具名 ParameterizedTypeImpl 替代 object : TypeToken<T>() {} 匿名子类
// 详见: https://github.com/google/dagger/issues/4666
//      https://github.com/google/ksp/issues/2001

/**
 * 具名 ParameterizedType 实现，用于替代 Gson TypeToken 的匿名子类。
 * KSP 访问此类时 getSimpleName() 返回 "ParameterizedTypeImpl"（非 null），不会触发 NPE。
 */
private class ParameterizedTypeImpl(
    private val _rawType: Type,
    private val _typeArguments: Array<Type>
) : ParameterizedType {
    override fun getRawType(): Type = _rawType
    override fun getOwnerType(): Type? = null
    override fun getActualTypeArguments(): Array<Type> = _typeArguments
}

/**
 * 预计算泛型类型引用，无任何匿名内部类。
 * 所有类型通过具名 ParameterizedTypeImpl 构造，KSP 安全。
 */
private object GenericTypes {
    private fun listOf(elementType: Type): Type =
        ParameterizedTypeImpl(List::class.java, arrayOf(elementType))

    private fun mapOf(keyType: Type, valueType: Type): Type =
        ParameterizedTypeImpl(Map::class.java, arrayOf(keyType, valueType))

    val string: Type = String::class.java
    val int: Type = Int::class.javaPrimitiveType
        ?: throw IllegalStateException("Int primitive type is unexpectedly null")
    val integer: Type = Int::class.java
    val long: Type = Long::class.javaPrimitiveType
        ?: throw IllegalStateException("Long primitive type is unexpectedly null")
    val double: Type = Double::class.javaPrimitiveType
        ?: throw IllegalStateException("Double primitive type is unexpectedly null")
    val boolean: Type = Boolean::class.javaPrimitiveType
        ?: throw IllegalStateException("Boolean primitive type is unexpectedly null")

    // List 类型
    val stringList: Type = listOf(string)
    val merchantItemList: Type = listOf(MerchantItem::class.java)
    val exploredSectInfoList: Type = listOf(ExploredSectInfo::class.java) // actually used as Map value, but kept for pattern
    val storageBagItemList: Type = listOf(StorageBagItem::class.java)
    val battleLogMemberList: Type = listOf(BattleLogMember::class.java)
    val battleLogEnemyList: Type = listOf(BattleLogEnemy::class.java)
    val battleLogRoundList: Type = listOf(BattleLogRound::class.java)
    val battleLogActionList: Type = listOf(BattleLogAction::class.java)
    val plantSlotDataList: Type = listOf(PlantSlotData::class.java)
    val cultivatorCaveList: Type = listOf(CultivatorCave::class.java)
    val caveExplorationTeamList: Type = listOf(CaveExplorationTeam::class.java)
    val discipleList: Type = listOf(Disciple::class.java)
    val mineSlotList: Type = listOf(MineSlot::class.java)
    val aiCaveTeamList: Type = listOf(AICaveTeam::class.java)
    val worldSectList: Type = listOf(WorldSect::class.java)
    val spiritMineSlotList: Type = listOf(SpiritMineSlot::class.java)
    val librarySlotList: Type = listOf(LibrarySlot::class.java)
    val directDiscipleSlotList: Type = listOf(DirectDiscipleSlot::class.java)
    val allianceList: Type = listOf(Alliance::class.java)
    val sectRelationList: Type = listOf(SectRelation::class.java)
    val aiBattleTeamList: Type = listOf(AIBattleTeam::class.java)
    val activeMissionList: Type = listOf(ActiveMission::class.java)
    val missionList: Type = listOf(Mission::class.java)
    val buildingSlotList: Type = listOf(BuildingSlot::class.java)
    val alchemySlotList: Type = listOf(AlchemySlot::class.java)
    val productionSlotList: Type = listOf(com.xianxia.sect.core.model.production.ProductionSlot::class.java)

    // Map 类型
    val stringStringMap: Type = mapOf(string, string)
    val stringIntMap: Type = mapOf(string, int)
    val intIntMap: Type = mapOf(int, int)
    val stringDoubleMap: Type = mapOf(string, double)
    val intBooleanMap: Type = mapOf(int, boolean)
    val sectScoutInfoMap: Type = mapOf(string, SectScoutInfo::class.java)
    val manualProficiencyDataMap: Type = mapOf(string, listOf(ManualProficiencyData::class.java))
}

// ==================== Room TypeConverter ====================

object ModelConverters {
    private val gson = GsonConfig.createGsonForRoom()
    
    @TypeConverter
    @JvmStatic
    fun fromStringList(value: List<String>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toStringList(value: String): List<String> =
        gson.fromJson(value, GenericTypes.stringList) ?: emptyList()
    
    @TypeConverter
    @JvmStatic
    fun fromStringMap(value: Map<String, String>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toStringMap(value: String): Map<String, String> =
        gson.fromJson(value, GenericTypes.stringStringMap) ?: emptyMap()
    
    @TypeConverter
    @JvmStatic
    fun fromIntMap(value: Map<String, Int>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toIntMap(value: String): Map<String, Int> =
        gson.fromJson(value, GenericTypes.stringIntMap) ?: emptyMap()
    
    @TypeConverter
    @JvmStatic
    fun fromIntIntMap(value: Map<Int, Int>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toIntIntMap(value: String): Map<Int, Int> =
        gson.fromJson(value, GenericTypes.intIntMap) ?: emptyMap()
    
    @TypeConverter
    @JvmStatic
    fun fromDoubleMap(value: Map<String, Double>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toDoubleMap(value: String): Map<String, Double> =
        gson.fromJson(value, GenericTypes.stringDoubleMap) ?: emptyMap()
    
    @TypeConverter
    @JvmStatic
    fun fromEquipmentSlot(value: EquipmentSlot): String = value.name
    
    @TypeConverter
    @JvmStatic
    fun toEquipmentSlot(value: String): EquipmentSlot =
        EquipmentSlot.values().find { it.name == value } ?: EquipmentSlot.WEAPON
    
    @TypeConverter
    @JvmStatic
    fun fromManualType(value: ManualType): String = value.name
    
    @TypeConverter
    @JvmStatic
    fun toManualType(value: String): ManualType = when (value) {
        "MOVEMENT" -> ManualType.SUPPORT
        else -> ManualType.values().find { it.name == value } ?: ManualType.MIND
    }
    
    @TypeConverter
    @JvmStatic
    fun fromPillCategory(value: PillCategory): String = value.name
    
    @TypeConverter
    @JvmStatic
    fun toPillCategory(value: String): PillCategory {
        return when (value) {
            "BATTLE" -> PillCategory.BATTLE_PHYSICAL
            else -> PillCategory.values().find { it.name == value } ?: PillCategory.CULTIVATION
        }
    }
    
    @TypeConverter
    @JvmStatic
    fun fromMaterialCategory(value: MaterialCategory): String = value.name
    
    @TypeConverter
    @JvmStatic
    fun toMaterialCategory(value: String): MaterialCategory =
        MaterialCategory.values().find { it.name == value } ?: MaterialCategory.BEAST_HIDE
    
    @TypeConverter
    @JvmStatic
    fun fromDiscipleStatus(value: DiscipleStatus): String = value.name
    
    @TypeConverter
    @JvmStatic
    fun toDiscipleStatus(value: String): DiscipleStatus =
        DiscipleStatus.values().find { it.name == value } ?: DiscipleStatus.IDLE
    
    @TypeConverter
    @JvmStatic
    fun fromTeamStatus(value: TeamStatus): String = value.name
    
    @TypeConverter
    @JvmStatic
    fun toTeamStatus(value: String): TeamStatus =
        TeamStatus.values().find { it.name == value } ?: TeamStatus.IDLE
    
    @TypeConverter
    @JvmStatic
    fun fromSlotStatus(value: SlotStatus): String = value.name
    
    @TypeConverter
    @JvmStatic
    fun toSlotStatus(value: String): SlotStatus =
        SlotStatus.values().find { it.name == value } ?: SlotStatus.IDLE
    
    @TypeConverter
    @JvmStatic
    fun fromEventType(value: EventType): String = value.name
    
    @TypeConverter
    @JvmStatic
    fun toEventType(value: String): EventType =
        EventType.values().find { it.name == value } ?: EventType.INFO
    
    @TypeConverter
    @JvmStatic
    fun fromRecipeType(value: RecipeType): String = value.name
    
    @TypeConverter
    @JvmStatic
    fun toRecipeType(value: String): RecipeType =
        RecipeType.values().find { it.name == value } ?: RecipeType.PILL
    
    @TypeConverter
    @JvmStatic
    fun fromGameSettingsData(value: GameSettingsData): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toGameSettingsData(value: String): GameSettingsData =
        gson.fromJson(value, GameSettingsData::class.java) ?: GameSettingsData()
    
    @TypeConverter
    @JvmStatic
    fun fromMerchantItemList(value: List<MerchantItem>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toMerchantItemList(value: String): List<MerchantItem> =
        gson.fromJson(value, GenericTypes.merchantItemList) ?: emptyList()
    
    @TypeConverter
    @JvmStatic
    fun fromExploredSectInfoMap(value: Map<String, ExploredSectInfo>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toExploredSectInfoMap(value: String): Map<String, ExploredSectInfo> =
        gson.fromJson(value, GenericTypes.sectScoutInfoMap) ?: emptyMap()
    
    @TypeConverter
    @JvmStatic
    fun fromStorageBagItemList(value: List<StorageBagItem>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toStorageBagItemList(value: String): List<StorageBagItem> =
        gson.fromJson(value, GenericTypes.storageBagItemList) ?: emptyList()
    
    @TypeConverter
    @JvmStatic
    fun fromBattleLogMemberList(value: List<BattleLogMember>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toBattleLogMemberList(value: String): List<BattleLogMember> =
        gson.fromJson(value, GenericTypes.battleLogMemberList) ?: emptyList()
    
    @TypeConverter
    @JvmStatic
    fun fromBattleLogEnemyList(value: List<BattleLogEnemy>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toBattleLogEnemyList(value: String): List<BattleLogEnemy> =
        gson.fromJson(value, GenericTypes.battleLogEnemyList) ?: emptyList()
    
    @TypeConverter
    @JvmStatic
    fun fromBattleLogRoundList(value: List<BattleLogRound>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toBattleLogRoundList(value: String): List<BattleLogRound> =
        gson.fromJson(value, GenericTypes.battleLogRoundList) ?: emptyList()
    
    @TypeConverter
    @JvmStatic
    fun fromBattleLogActionList(value: List<BattleLogAction>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toBattleLogActionList(value: String): List<BattleLogAction> =
        gson.fromJson(value, GenericTypes.battleLogActionList) ?: emptyList()
    
    @TypeConverter
    @JvmStatic
    fun fromBattleLogResult(value: BattleLogResult): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toBattleLogResult(value: String): BattleLogResult =
        gson.fromJson(value, BattleLogResult::class.java) ?: BattleLogResult()
    
    @TypeConverter
    @JvmStatic
    fun fromPlantSlotDataList(value: List<PlantSlotData>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toPlantSlotDataList(value: String): List<PlantSlotData> =
        gson.fromJson(value, GenericTypes.plantSlotDataList) ?: emptyList()
    
    @TypeConverter
    @JvmStatic
    fun fromManualProficiencyDataMap(value: Map<String, List<ManualProficiencyData>>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toManualProficiencyDataMap(value: String): Map<String, List<ManualProficiencyData>> =
        gson.fromJson(value, GenericTypes.manualProficiencyDataMap) ?: emptyMap()
    
    @TypeConverter
    @JvmStatic
    fun fromIntBooleanMap(value: Map<Int, Boolean>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toIntBooleanMap(value: String): Map<Int, Boolean> =
        gson.fromJson(value, GenericTypes.intBooleanMap) ?: emptyMap()
    
    @TypeConverter
    @JvmStatic
    fun fromCultivatorCaveList(value: List<CultivatorCave>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toCultivatorCaveList(value: String): List<CultivatorCave> =
        gson.fromJson(value, GenericTypes.cultivatorCaveList) ?: emptyList()
    
    @TypeConverter
    @JvmStatic
    fun fromCaveExplorationTeamList(value: List<CaveExplorationTeam>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toCaveExplorationTeamList(value: String): List<CaveExplorationTeam> =
        gson.fromJson(value, GenericTypes.caveExplorationTeamList) ?: emptyList()
    
    @TypeConverter
    @JvmStatic
    fun fromDiscipleList(value: List<Disciple>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toDiscipleList(value: String): List<Disciple> =
        gson.fromJson(value, GenericTypes.discipleList) ?: emptyList()
    
    @TypeConverter
    @JvmStatic
    fun fromMineSlotList(value: List<MineSlot>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toMineSlotList(value: String): List<MineSlot> =
        gson.fromJson(value, GenericTypes.mineSlotList) ?: emptyList()
    
    @TypeConverter
    @JvmStatic
    fun fromDungeonRewards(value: DungeonRewards): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toDungeonRewards(value: String): DungeonRewards =
        gson.fromJson(value, DungeonRewards::class.java) ?: DungeonRewards()
    
    @TypeConverter
    @JvmStatic
    fun fromAICaveTeamList(value: List<AICaveTeam>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toAICaveTeamList(value: String): List<AICaveTeam> =
        gson.fromJson(value, GenericTypes.aiCaveTeamList) ?: emptyList()
    
    @TypeConverter
    @JvmStatic
    fun fromWorldSectList(value: List<WorldSect>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toWorldSectList(value: String): List<WorldSect> =
        gson.fromJson(value, GenericTypes.worldSectList) ?: emptyList()
    
    @TypeConverter
    @JvmStatic
    fun fromSectScoutInfo(value: SectScoutInfo?): String = if (value != null) gson.toJson(value) else ""
    
    @TypeConverter
    @JvmStatic
    fun toSectScoutInfo(value: String): SectScoutInfo? =
        if (value.isEmpty()) null else gson.fromJson(value, SectScoutInfo::class.java)
    
    @TypeConverter
    @JvmStatic
    fun fromSectScoutInfoMap(value: Map<String, SectScoutInfo>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toSectScoutInfoMap(value: String): Map<String, SectScoutInfo> =
        gson.fromJson(value, GenericTypes.sectScoutInfoMap) ?: emptyMap()
    
    @TypeConverter
    @JvmStatic
    fun fromBattleType(value: BattleType): String = value.name
    
    @TypeConverter
    @JvmStatic
    fun toBattleType(value: String): BattleType =
        BattleType.values().find { it.name == value } ?: BattleType.PVE
    
    @TypeConverter
    @JvmStatic
    fun fromBattleResult(value: BattleResult): String = value.name
    
    @TypeConverter
    @JvmStatic
    fun toBattleResult(value: String): BattleResult =
        BattleResult.values().find { it.name == value } ?: BattleResult.DRAW
    
    @TypeConverter
    @JvmStatic
    fun fromElderSlots(value: ElderSlots): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toElderSlots(value: String): ElderSlots =
        gson.fromJson(value, ElderSlots::class.java) ?: ElderSlots()
    
    @TypeConverter
    @JvmStatic
    fun fromSectPolicies(value: SectPolicies): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toSectPolicies(value: String): SectPolicies =
        gson.fromJson(value, SectPolicies::class.java) ?: SectPolicies()
    
    @TypeConverter
    @JvmStatic
    fun fromSpiritMineSlotList(value: List<SpiritMineSlot>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toSpiritMineSlotList(value: String): List<SpiritMineSlot> =
        gson.fromJson(value, GenericTypes.spiritMineSlotList) ?: emptyList()
    
    @TypeConverter
    @JvmStatic
    fun fromLibrarySlotList(value: List<LibrarySlot>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toLibrarySlotList(value: String): List<LibrarySlot> =
        gson.fromJson(value, GenericTypes.librarySlotList) ?: emptyList()
    
    @TypeConverter
    @JvmStatic
    fun fromDirectDiscipleSlotList(value: List<DirectDiscipleSlot>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toDirectDiscipleSlotList(value: String): List<DirectDiscipleSlot> =
        gson.fromJson(value, GenericTypes.directDiscipleSlotList) ?: emptyList()
    
    @TypeConverter
    @JvmStatic
    fun fromAllianceList(value: List<Alliance>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toAllianceList(value: String): List<Alliance> =
        gson.fromJson(value, GenericTypes.allianceList) ?: emptyList()
    
    @TypeConverter
    @JvmStatic
    fun fromSectRelationList(value: List<SectRelation>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toSectRelationList(value: String): List<SectRelation> =
        gson.fromJson(value, GenericTypes.sectRelationList) ?: emptyList()
    
    @TypeConverter
    @JvmStatic
    fun fromBattleTeam(value: BattleTeam?): String = if (value != null) gson.toJson(value) else ""
    
    @TypeConverter
    @JvmStatic
    fun toBattleTeam(value: String): BattleTeam? =
        if (value.isEmpty()) null else gson.fromJson(value, BattleTeam::class.java)
    
    @TypeConverter
    @JvmStatic
    fun fromAIBattleTeamList(value: List<AIBattleTeam>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toAIBattleTeamList(value: String): List<AIBattleTeam> =
        gson.fromJson(value, GenericTypes.aiBattleTeamList) ?: emptyList()
    
    @TypeConverter
    @JvmStatic
    fun fromActiveMissionList(value: List<ActiveMission>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toActiveMissionList(value: String): List<ActiveMission> =
        gson.fromJson(value, GenericTypes.activeMissionList) ?: emptyList()
    
    @TypeConverter
    @JvmStatic
    fun fromMissionList(value: List<Mission>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toMissionList(value: String): List<Mission> =
        gson.fromJson(value, GenericTypes.missionList) ?: emptyList()
    
    @TypeConverter
    @JvmStatic
    fun fromBuildingSlotList(value: List<BuildingSlot>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toBuildingSlotList(value: String): List<BuildingSlot> =
        gson.fromJson(value, GenericTypes.buildingSlotList) ?: emptyList()
    
    @TypeConverter
    @JvmStatic
    fun fromAlchemySlotList(value: List<AlchemySlot>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toAlchemySlotList(value: String): List<AlchemySlot> =
        gson.fromJson(value, GenericTypes.alchemySlotList) ?: emptyList()
    
    @TypeConverter
    @JvmStatic
    fun fromProductionSlotList(value: List<com.xianxia.sect.core.model.production.ProductionSlot>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toProductionSlotList(value: String): List<com.xianxia.sect.core.model.production.ProductionSlot> =
        gson.fromJson(value, GenericTypes.productionSlotList) ?: emptyList()
    
    @TypeConverter
    @JvmStatic
    fun fromEquipmentNurtureData(value: EquipmentNurtureData?): String = 
        if (value != null) gson.toJson(value) else ""
    
    @TypeConverter
    @JvmStatic
    fun toEquipmentNurtureData(value: String): EquipmentNurtureData? =
        if (value.isEmpty()) null else gson.fromJson(value, EquipmentNurtureData::class.java)
}
