package com.xianxia.sect.core.model

import androidx.room.TypeConverter
import com.xianxia.sect.data.GsonConfig
import com.google.gson.reflect.TypeToken

object ModelConverters {
    private val gson = GsonConfig.createGsonForRoom()
    
    @TypeConverter
    @JvmStatic
    fun fromStringList(value: List<String>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toStringList(value: String): List<String> =
        gson.fromJson(value, object : TypeToken<List<String>>() {}.type) ?: emptyList()
    
    @TypeConverter
    @JvmStatic
    fun fromStringMap(value: Map<String, String>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toStringMap(value: String): Map<String, String> =
        gson.fromJson(value, object : TypeToken<Map<String, String>>() {}.type) ?: emptyMap()
    
    @TypeConverter
    @JvmStatic
    fun fromIntMap(value: Map<String, Int>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toIntMap(value: String): Map<String, Int> =
        gson.fromJson(value, object : TypeToken<Map<String, Int>>() {}.type) ?: emptyMap()
    
    @TypeConverter
    @JvmStatic
    fun fromIntIntMap(value: Map<Int, Int>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toIntIntMap(value: String): Map<Int, Int> =
        gson.fromJson(value, object : TypeToken<Map<Int, Int>>() {}.type) ?: emptyMap()
    
    @TypeConverter
    @JvmStatic
    fun fromDoubleMap(value: Map<String, Double>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toDoubleMap(value: String): Map<String, Double> =
        gson.fromJson(value, object : TypeToken<Map<String, Double>>() {}.type) ?: emptyMap()
    
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
    fun toManualType(value: String): ManualType =
        ManualType.values().find { it.name == value } ?: ManualType.MIND
    
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
        gson.fromJson(value, object : TypeToken<List<MerchantItem>>() {}.type) ?: emptyList()
    
    @TypeConverter
    @JvmStatic
    fun fromExploredSectInfoMap(value: Map<String, ExploredSectInfo>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toExploredSectInfoMap(value: String): Map<String, ExploredSectInfo> =
        gson.fromJson(value, object : TypeToken<Map<String, ExploredSectInfo>>() {}.type) ?: emptyMap()
    
    @TypeConverter
    @JvmStatic
    fun fromStorageBagItemList(value: List<StorageBagItem>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toStorageBagItemList(value: String): List<StorageBagItem> =
        gson.fromJson(value, object : TypeToken<List<StorageBagItem>>() {}.type) ?: emptyList()
    
    @TypeConverter
    @JvmStatic
    fun fromBattleLogMemberList(value: List<BattleLogMember>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toBattleLogMemberList(value: String): List<BattleLogMember> =
        gson.fromJson(value, object : TypeToken<List<BattleLogMember>>() {}.type) ?: emptyList()
    
    @TypeConverter
    @JvmStatic
    fun fromBattleLogEnemyList(value: List<BattleLogEnemy>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toBattleLogEnemyList(value: String): List<BattleLogEnemy> =
        gson.fromJson(value, object : TypeToken<List<BattleLogEnemy>>() {}.type) ?: emptyList()
    
    @TypeConverter
    @JvmStatic
    fun fromBattleLogRoundList(value: List<BattleLogRound>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toBattleLogRoundList(value: String): List<BattleLogRound> =
        gson.fromJson(value, object : TypeToken<List<BattleLogRound>>() {}.type) ?: emptyList()
    
    @TypeConverter
    @JvmStatic
    fun fromBattleLogActionList(value: List<BattleLogAction>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toBattleLogActionList(value: String): List<BattleLogAction> =
        gson.fromJson(value, object : TypeToken<List<BattleLogAction>>() {}.type) ?: emptyList()
    
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
        gson.fromJson(value, object : TypeToken<List<PlantSlotData>>() {}.type) ?: emptyList()
    
    @TypeConverter
    @JvmStatic
    fun fromManualProficiencyDataMap(value: Map<String, List<ManualProficiencyData>>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toManualProficiencyDataMap(value: String): Map<String, List<ManualProficiencyData>> =
        gson.fromJson(value, object : TypeToken<Map<String, List<ManualProficiencyData>>>() {}.type) ?: emptyMap()
    
    @TypeConverter
    @JvmStatic
    fun fromIntBooleanMap(value: Map<Int, Boolean>): String = gson.toJson(value)
    
    @TypeConverter
    @JvmStatic
    fun toIntBooleanMap(value: String): Map<Int, Boolean> =
        gson.fromJson(value, object : TypeToken<Map<Int, Boolean>>() {}.type) ?: emptyMap()

    @TypeConverter
    @JvmStatic
    fun fromCultivatorCaveList(value: List<CultivatorCave>): String = gson.toJson(value)

    @TypeConverter
    @JvmStatic
    fun toCultivatorCaveList(value: String): List<CultivatorCave> =
        gson.fromJson(value, object : TypeToken<List<CultivatorCave>>() {}.type) ?: emptyList()

    @TypeConverter
    @JvmStatic
    fun fromCaveExplorationTeamList(value: List<CaveExplorationTeam>): String = gson.toJson(value)

    @TypeConverter
    @JvmStatic
    fun toCaveExplorationTeamList(value: String): List<CaveExplorationTeam> =
        gson.fromJson(value, object : TypeToken<List<CaveExplorationTeam>>() {}.type) ?: emptyList()

    @TypeConverter
    @JvmStatic
    fun fromDiscipleList(value: List<Disciple>): String = gson.toJson(value)

    @TypeConverter
    @JvmStatic
    fun toDiscipleList(value: String): List<Disciple> =
        gson.fromJson(value, object : TypeToken<List<Disciple>>() {}.type) ?: emptyList()

    @TypeConverter
    @JvmStatic
    fun fromMineSlotList(value: List<MineSlot>): String = gson.toJson(value)

    @TypeConverter
    @JvmStatic
    fun toMineSlotList(value: String): List<MineSlot> =
        gson.fromJson(value, object : TypeToken<List<MineSlot>>() {}.type) ?: emptyList()

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
        gson.fromJson(value, object : TypeToken<List<AICaveTeam>>() {}.type) ?: emptyList()

    @TypeConverter
    @JvmStatic
    fun fromWorldSectList(value: List<WorldSect>): String = gson.toJson(value)

    @TypeConverter
    @JvmStatic
    fun toWorldSectList(value: String): List<WorldSect> =
        gson.fromJson(value, object : TypeToken<List<WorldSect>>() {}.type) ?: emptyList()

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
        gson.fromJson(value, object : TypeToken<Map<String, SectScoutInfo>>() {}.type) ?: emptyMap()

    @TypeConverter
    @JvmStatic
    fun fromTradingItemList(value: List<TradingItem>): String = gson.toJson(value)

    @TypeConverter
    @JvmStatic
    fun toTradingItemList(value: String): List<TradingItem> =
        gson.fromJson(value, object : TypeToken<List<TradingItem>>() {}.type) ?: emptyList()

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
        gson.fromJson(value, object : TypeToken<List<SpiritMineSlot>>() {}.type) ?: emptyList()

    @TypeConverter
    @JvmStatic
    fun fromLibrarySlotList(value: List<LibrarySlot>): String = gson.toJson(value)

    @TypeConverter
    @JvmStatic
    fun toLibrarySlotList(value: String): List<LibrarySlot> =
        gson.fromJson(value, object : TypeToken<List<LibrarySlot>>() {}.type) ?: emptyList()

    @TypeConverter
    @JvmStatic
    fun fromDirectDiscipleSlotList(value: List<DirectDiscipleSlot>): String = gson.toJson(value)

    @TypeConverter
    @JvmStatic
    fun toDirectDiscipleSlotList(value: String): List<DirectDiscipleSlot> =
        gson.fromJson(value, object : TypeToken<List<DirectDiscipleSlot>>() {}.type) ?: emptyList()

    @TypeConverter
    @JvmStatic
    fun fromAllianceList(value: List<Alliance>): String = gson.toJson(value)

    @TypeConverter
    @JvmStatic
    fun toAllianceList(value: String): List<Alliance> =
        gson.fromJson(value, object : TypeToken<List<Alliance>>() {}.type) ?: emptyList()

    @TypeConverter
    @JvmStatic
    fun fromSupportTeamList(value: List<SupportTeam>): String = gson.toJson(value)

    @TypeConverter
    @JvmStatic
    fun toSupportTeamList(value: String): List<SupportTeam> =
        gson.fromJson(value, object : TypeToken<List<SupportTeam>>() {}.type) ?: emptyList()

    @TypeConverter
    @JvmStatic
    fun fromSectRelationList(value: List<SectRelation>): String = gson.toJson(value)

    @TypeConverter
    @JvmStatic
    fun toSectRelationList(value: String): List<SectRelation> =
        gson.fromJson(value, object : TypeToken<List<SectRelation>>() {}.type) ?: emptyList()

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
        gson.fromJson(value, object : TypeToken<List<AIBattleTeam>>() {}.type) ?: emptyList()

    @TypeConverter
    @JvmStatic
    fun fromEquipmentNurtureData(value: EquipmentNurtureData?): String = 
        if (value != null) gson.toJson(value) else ""

    @TypeConverter
    @JvmStatic
    fun toEquipmentNurtureData(value: String): EquipmentNurtureData? =
        if (value.isEmpty()) null else gson.fromJson(value, EquipmentNurtureData::class.java)

    @TypeConverter
    @JvmStatic
    fun fromActiveMissionList(value: List<ActiveMission>): String = gson.toJson(value)

    @TypeConverter
    @JvmStatic
    fun toActiveMissionList(value: String): List<ActiveMission> =
        gson.fromJson(value, object : TypeToken<List<ActiveMission>>() {}.type) ?: emptyList()

    @TypeConverter
    @JvmStatic
    fun fromMissionList(value: List<Mission>): String = gson.toJson(value)

    @TypeConverter
    @JvmStatic
    fun toMissionList(value: String): List<Mission> =
        gson.fromJson(value, object : TypeToken<List<Mission>>() {}.type) ?: emptyList()
}
