package com.xianxia.sect.data.local

import androidx.room.TypeConverter
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.ProductionSlot
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * ## CollectionConverters - 集合类型的 Room TypeConverter
 *
 * 处理 List/Map 类型的 Protobuf 二进制序列化。
 */
object CollectionConverters {

    // ==================== 复杂列表转换器（纯 Protobuf）====================

    @TypeConverter
    @JvmStatic
    fun fromDiscipleList(value: List<Disciple>): String {
        val result = ProtobufConverters.encodeToBase64(ListSerializer(Disciple.serializer()), value)
        if (result.isEmpty() && value.isNotEmpty()) {
            android.util.Log.e("CollectionConverters", "CRITICAL: fromDiscipleList serialization FAILED for ${value.size} disciples! Data will be lost on save!")
        }
        return result
    }

    @TypeConverter
    @JvmStatic
    fun toDiscipleList(value: String): List<Disciple> {
        val result = ProtobufConverters.decodeFromBase64(ListSerializer(Disciple.serializer()), value) { emptyList() }
        if (result.isEmpty() && value.isNotEmpty()) {
            android.util.Log.e("CollectionConverters", "CRITICAL: toDiscipleList deserialization returned empty list from non-empty data! Encoded length: ${value.length}")
        }
        return result
    }

    // aiSectDisciples — 数据量极大（全 AI 宗门弟子），走增量编码，TypeConverter 仅做 Room 兼容占位
    @TypeConverter
    @JvmStatic
    fun fromDiscipleListMap(value: Map<String, List<Disciple>>): String {
        if (value.isNotEmpty()) {
            android.util.Log.w("CollectionConverters", "aiSectDisciples has ${value.size} sects / ${value.values.sumOf { it.size }} disciples — should use incremental encoding, TypeConverter returns empty to avoid OOM")
        }
        return ""
    }

    @TypeConverter
    @JvmStatic
    fun toDiscipleListMap(value: String): Map<String, List<Disciple>> =
        if (value.isEmpty()) emptyMap()
        else ProtobufConverters.decodeFromBase64(MapSerializer(String.serializer(), ListSerializer(Disciple.serializer())), value) { emptyMap() }

    @TypeConverter
    @JvmStatic
    fun fromBuildingSlotList(value: List<BuildingSlot>): String =
        ProtobufConverters.encodeToBase64(ListSerializer(BuildingSlot.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toBuildingSlotList(value: String): List<BuildingSlot> =
        ProtobufConverters.decodeFromBase64(ListSerializer(BuildingSlot.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromMerchantItemList(value: List<MerchantItem>): String =
        ProtobufConverters.encodeToBase64(ListSerializer(MerchantItem.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toMerchantItemList(value: String): List<MerchantItem> =
        ProtobufConverters.decodeFromBase64(ListSerializer(MerchantItem.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromStorageBagItemList(value: List<StorageBagItem>): String =
        ProtobufConverters.encodeToBase64(ListSerializer(StorageBagItem.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toStorageBagItemList(value: String): List<StorageBagItem> =
        ProtobufConverters.decodeFromBase64(ListSerializer(StorageBagItem.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromPlantSlotDataList(value: List<PlantSlotData>): String =
        ProtobufConverters.encodeToBase64(ListSerializer(PlantSlotData.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toPlantSlotDataList(value: String): List<PlantSlotData> =
        ProtobufConverters.decodeFromBase64(ListSerializer(PlantSlotData.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromCultivatorCaveList(value: List<CultivatorCave>): String =
        ProtobufConverters.encodeToBase64(ListSerializer(CultivatorCave.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toCultivatorCaveList(value: String): List<CultivatorCave> =
        ProtobufConverters.decodeFromBase64(ListSerializer(CultivatorCave.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromWorldLevelList(value: List<WorldLevel>): String =
        ProtobufConverters.encodeToBase64(ListSerializer(WorldLevel.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toWorldLevelList(value: String): List<WorldLevel> =
        ProtobufConverters.decodeFromBase64(ListSerializer(WorldLevel.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromCaveExplorationTeamList(value: List<CaveExplorationTeam>): String =
        ProtobufConverters.encodeToBase64(ListSerializer(CaveExplorationTeam.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toCaveExplorationTeamList(value: String): List<CaveExplorationTeam> =
        ProtobufConverters.decodeFromBase64(ListSerializer(CaveExplorationTeam.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromMineSlotList(value: List<MineSlot>): String =
        ProtobufConverters.encodeToBase64(ListSerializer(MineSlot.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toMineSlotList(value: String): List<MineSlot> =
        ProtobufConverters.decodeFromBase64(ListSerializer(MineSlot.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromAICaveTeamList(value: List<AICaveTeam>): String =
        ProtobufConverters.encodeToBase64(ListSerializer(AICaveTeam.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toAICaveTeamList(value: String): List<AICaveTeam> =
        ProtobufConverters.decodeFromBase64(ListSerializer(AICaveTeam.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromWorldSectList(value: List<WorldSect>): String =
        ProtobufConverters.encodeToBase64(ListSerializer(WorldSect.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toWorldSectList(value: String): List<WorldSect> =
        ProtobufConverters.decodeFromBase64(ListSerializer(WorldSect.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromSpiritMineSlotList(value: List<SpiritMineSlot>): String =
        ProtobufConverters.encodeToBase64(ListSerializer(SpiritMineSlot.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toSpiritMineSlotList(value: String): List<SpiritMineSlot> =
        ProtobufConverters.decodeFromBase64(ListSerializer(SpiritMineSlot.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromGridBuildingDataList(value: List<GridBuildingData>): String =
        ProtobufConverters.encodeToBase64(ListSerializer(GridBuildingData.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toGridBuildingDataList(value: String): List<GridBuildingData> =
        ProtobufConverters.decodeFromBase64(ListSerializer(GridBuildingData.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromLibrarySlotList(value: List<LibrarySlot>): String =
        ProtobufConverters.encodeToBase64(ListSerializer(LibrarySlot.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toLibrarySlotList(value: String): List<LibrarySlot> =
        ProtobufConverters.decodeFromBase64(ListSerializer(LibrarySlot.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromResidenceSlotList(value: List<ResidenceSlot>): String =
        ProtobufConverters.encodeToBase64(ListSerializer(ResidenceSlot.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toResidenceSlotList(value: String): List<ResidenceSlot> =
        ProtobufConverters.decodeFromBase64(ListSerializer(ResidenceSlot.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromSpiritFieldPlantList(value: List<SpiritFieldPlant>): String =
        ProtobufConverters.encodeToBase64(ListSerializer(SpiritFieldPlant.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toSpiritFieldPlantList(value: String): List<SpiritFieldPlant> =
        ProtobufConverters.decodeFromBase64(ListSerializer(SpiritFieldPlant.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromWarehouseGarrisonSlotList(value: List<WarehouseGarrisonSlot>): String =
        ProtobufConverters.encodeToBase64(ListSerializer(WarehouseGarrisonSlot.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toWarehouseGarrisonSlotList(value: String): List<WarehouseGarrisonSlot> =
        ProtobufConverters.decodeFromBase64(ListSerializer(WarehouseGarrisonSlot.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromDirectDiscipleSlotList(value: List<DirectDiscipleSlot>): String =
        ProtobufConverters.encodeToBase64(ListSerializer(DirectDiscipleSlot.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toDirectDiscipleSlotList(value: String): List<DirectDiscipleSlot> =
        ProtobufConverters.decodeFromBase64(ListSerializer(DirectDiscipleSlot.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromAllianceList(value: List<Alliance>): String =
        ProtobufConverters.encodeToBase64(ListSerializer(Alliance.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toAllianceList(value: String): List<Alliance> =
        ProtobufConverters.decodeFromBase64(ListSerializer(Alliance.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromSectRelationList(value: List<SectRelation>): String =
        ProtobufConverters.encodeToBase64(ListSerializer(SectRelation.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toSectRelationList(value: String): List<SectRelation> =
        ProtobufConverters.decodeFromBase64(ListSerializer(SectRelation.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromActiveMissionList(value: List<ActiveMission>): String =
        ProtobufConverters.encodeToBase64(ListSerializer(ActiveMission.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toActiveMissionList(value: String): List<ActiveMission> =
        ProtobufConverters.decodeFromBase64(ListSerializer(ActiveMission.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromMissionList(value: List<Mission>): String =
        ProtobufConverters.encodeToBase64(ListSerializer(Mission.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toMissionList(value: String): List<Mission> =
        ProtobufConverters.decodeFromBase64(ListSerializer(Mission.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromAIBattleTeamList(value: List<AIBattleTeam>): String =
        ProtobufConverters.encodeToBase64(ListSerializer(AIBattleTeam.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toAIBattleTeamList(value: String): List<AIBattleTeam> =
        ProtobufConverters.decodeFromBase64(ListSerializer(AIBattleTeam.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromAlchemySlotList(value: List<AlchemySlot>): String =
        ProtobufConverters.encodeToBase64(ListSerializer(AlchemySlot.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toAlchemySlotList(value: String): List<AlchemySlot> =
        ProtobufConverters.decodeFromBase64(ListSerializer(AlchemySlot.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromProductionSlotList(value: List<ProductionSlot>): String =
        ProtobufConverters.encodeToBase64(ListSerializer(ProductionSlot.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toProductionSlotList(value: String): List<ProductionSlot> =
        ProtobufConverters.decodeFromBase64(ListSerializer(ProductionSlot.serializer()), value) { emptyList() }

    // ==================== 复杂 Map 转换器（纯 Protobuf）====================

    @TypeConverter
    @JvmStatic
    fun fromExploredSectInfoMap(value: Map<String, ExploredSectInfo>): String =
        ProtobufConverters.encodeToBase64(MapSerializer(String.serializer(), ExploredSectInfo.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toExploredSectInfoMap(value: String): Map<String, ExploredSectInfo> =
        ProtobufConverters.decodeFromBase64(MapSerializer(String.serializer(), ExploredSectInfo.serializer()), value) { emptyMap() }

    @TypeConverter
    @JvmStatic
    fun fromSectScoutInfoMap(value: Map<String, SectScoutInfo>): String =
        ProtobufConverters.encodeToBase64(MapSerializer(String.serializer(), SectScoutInfo.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toSectScoutInfoMap(value: String): Map<String, SectScoutInfo> =
        ProtobufConverters.decodeFromBase64(MapSerializer(String.serializer(), SectScoutInfo.serializer()), value) { emptyMap() }

    @TypeConverter
    @JvmStatic
    fun fromManualProficiencyDataMap(value: Map<String, List<ManualProficiencyData>>): String =
        ProtobufConverters.encodeToBase64(MapSerializer(String.serializer(), ListSerializer(ManualProficiencyData.serializer())), value)

    @TypeConverter
    @JvmStatic
    fun toManualProficiencyDataMap(value: String): Map<String, List<ManualProficiencyData>> =
        ProtobufConverters.decodeFromBase64(MapSerializer(String.serializer(), ListSerializer(ManualProficiencyData.serializer())), value) { emptyMap() }

    // ==================== 战斗日志相关（纯 Protobuf）====================

    @TypeConverter
    @JvmStatic
    fun fromBattleLogMemberList(value: List<BattleLogMember>): String =
        ProtobufConverters.encodeToBase64(ListSerializer(BattleLogMember.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toBattleLogMemberList(value: String): List<BattleLogMember> =
        ProtobufConverters.decodeFromBase64(ListSerializer(BattleLogMember.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromBattleLogEnemyList(value: List<BattleLogEnemy>): String =
        ProtobufConverters.encodeToBase64(ListSerializer(BattleLogEnemy.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toBattleLogEnemyList(value: String): List<BattleLogEnemy> =
        ProtobufConverters.decodeFromBase64(ListSerializer(BattleLogEnemy.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromBattleLogRoundList(value: List<BattleLogRound>): String =
        ProtobufConverters.encodeToBase64(ListSerializer(BattleLogRound.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toBattleLogRoundList(value: String): List<BattleLogRound> =
        ProtobufConverters.decodeFromBase64(ListSerializer(BattleLogRound.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromBattleLogActionList(value: List<BattleLogAction>): String =
        ProtobufConverters.encodeToBase64(ListSerializer(BattleLogAction.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toBattleLogActionList(value: String): List<BattleLogAction> =
        ProtobufConverters.decodeFromBase64(ListSerializer(BattleLogAction.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromSectDetailMap(value: Map<String, SectDetail>): String =
        ProtobufConverters.encodeToBase64(MapSerializer(String.serializer(), SectDetail.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toSectDetailMap(value: String): Map<String, SectDetail> =
        ProtobufConverters.decodeFromBase64(MapSerializer(String.serializer(), SectDetail.serializer()), value) { emptyMap() }

    // 巡视楼
    @TypeConverter
    @JvmStatic
    fun fromPatrolSlotList(value: List<PatrolSlot>): String =
        ProtobufConverters.encodeToBase64(ListSerializer(PatrolSlot.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toPatrolSlotList(value: String): List<PatrolSlot> =
        ProtobufConverters.decodeFromBase64(ListSerializer(PatrolSlot.serializer()), value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromPatrolConfigList(value: List<PatrolConfig>): String =
        ProtobufConverters.encodeToBase64(ListSerializer(PatrolConfig.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toPatrolConfigList(value: String): List<PatrolConfig> =
        ProtobufConverters.decodeFromBase64(ListSerializer(PatrolConfig.serializer()), value) { emptyList() }

    // ==================== 血炼系统转换器 ====================

    @TypeConverter
    @JvmStatic
    fun fromBloodRefinementProgressMap(value: Map<String, BloodRefinementProgress>): String =
        ProtobufConverters.encodeToBase64(MapSerializer(String.serializer(), BloodRefinementProgress.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toBloodRefinementProgressMap(value: String): Map<String, BloodRefinementProgress> =
        ProtobufConverters.decodeFromBase64(MapSerializer(String.serializer(), BloodRefinementProgress.serializer()), value) { emptyMap() }

    @TypeConverter
    @JvmStatic
    fun fromStringListMap(value: Map<String, List<String>>): String =
        ProtobufConverters.encodeToBase64(MapSerializer(String.serializer(), ListSerializer(String.serializer())), value)

    @TypeConverter
    @JvmStatic
    fun toStringListMap(value: String): Map<String, List<String>> =
        ProtobufConverters.decodeFromBase64(MapSerializer(String.serializer(), ListSerializer(String.serializer())), value) { emptyMap() }

    // ==================== 每日签到转换器 ====================

    @TypeConverter
    @JvmStatic
    fun fromSignInState(value: SignInState): String =
        ProtobufConverters.encodeToBase64(SignInState.serializer(), value)

    @TypeConverter
    @JvmStatic
    fun toSignInState(value: String): SignInState =
        ProtobufConverters.decodeFromBase64(SignInState.serializer(), value) { SignInState() }

    // ==================== 天道试炼转换器 ====================

    @TypeConverter
    @JvmStatic
    fun fromHeavenlyTrialSaveData(value: HeavenlyTrialSaveData): String =
        ProtobufConverters.encodeToBase64(HeavenlyTrialSaveData.serializer(), value)

    @TypeConverter
    @JvmStatic
    fun toHeavenlyTrialSaveData(value: String): HeavenlyTrialSaveData =
        ProtobufConverters.decodeFromBase64(HeavenlyTrialSaveData.serializer(), value) { HeavenlyTrialSaveData() }

    // ==================== 自动购买列表转换器 ====================

    @TypeConverter
    @JvmStatic
    fun fromAutoBuyEntryList(value: List<AutoBuyEntry>): String =
        ProtobufConverters.encodeToBase64(ListSerializer(AutoBuyEntry.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toAutoBuyEntryList(value: String): List<AutoBuyEntry> =
        ProtobufConverters.decodeFromBase64(ListSerializer(AutoBuyEntry.serializer()), value) { emptyList() }

    // ==================== 邮件领取记录转换器 ====================

    @TypeConverter
    @JvmStatic
    fun fromMailClaimRecordList(value: List<MailClaimRecord>): String =
        ProtobufConverters.encodeToBase64(ListSerializer(MailClaimRecord.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toMailClaimRecordList(value: String): List<MailClaimRecord> =
        ProtobufConverters.decodeFromBase64(ListSerializer(MailClaimRecord.serializer()), value) { emptyList() }

    // ==================== 宗门等级领取记录转换器 ====================

    @TypeConverter
    @JvmStatic
    fun fromSectLevelClaimRecordList(value: List<SectLevelClaimRecord>): String =
        ProtobufConverters.encodeToBase64(ListSerializer(SectLevelClaimRecord.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toSectLevelClaimRecordList(value: String): List<SectLevelClaimRecord> =
        ProtobufConverters.decodeFromBase64(ListSerializer(SectLevelClaimRecord.serializer()), value) { emptyList() }
}
