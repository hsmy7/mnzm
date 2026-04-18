package com.xianxia.sect.data.serialization.unified

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.*
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class SerializableSaveSlot(
    @ProtoNumber(1) val slot: Int,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val timestamp: Long,
    @ProtoNumber(4) val gameYear: Int,
    @ProtoNumber(5) val gameMonth: Int,
    @ProtoNumber(6) val sectName: String,
    @ProtoNumber(7) val discipleCount: Int,
    @ProtoNumber(8) val spiritStones: Long,
    @ProtoNumber(9) val isEmpty: Boolean = false,
    @ProtoNumber(10) val customName: String = ""
)

@Serializable
data class SerializableGameData(
    @ProtoNumber(1) val id: String = "game_data",
    @ProtoNumber(2) val sectName: String = "青云宗",
    @ProtoNumber(3) val currentSlot: Int = 1,
    @ProtoNumber(4) val gameYear: Int = 1,
    @ProtoNumber(5) val gameMonth: Int = 1,
    @ProtoNumber(6) val gameDay: Int = 1,
    @ProtoNumber(7) val spiritStones: Long = 1000,
    @ProtoNumber(8) val spiritHerbs: Int = 0,
    @ProtoNumber(9) val autoSaveIntervalMonths: Int = 3,
    @ProtoNumber(10) val monthlySalary: Map<Int, Int> = emptyMap(),
    @ProtoNumber(11) val monthlySalaryEnabled: Map<Int, Boolean> = emptyMap(),
    @ProtoNumber(12) val worldMapSects: List<SerializableWorldSect> = emptyList(),
    @ProtoNumber(13) val exploredSects: Map<String, SerializableExploredSectInfo> = emptyMap(),
    @ProtoNumber(14) val scoutInfo: Map<String, SerializableSectScoutInfo> = emptyMap(),
    @ProtoNumber(15) @Deprecated("Migrated to productionSlots") val herbGardenPlantSlots: List<SerializablePlantSlotData> = emptyList(),
    @ProtoNumber(16) val manualProficiencies: Map<String, List<SerializableManualProficiencyData>> = emptyMap(),
    @ProtoNumber(17) val travelingMerchantItems: List<SerializableMerchantItem> = emptyList(),
    @ProtoNumber(18) val merchantLastRefreshYear: Int = 0,
    @ProtoNumber(19) val merchantRefreshCount: Int = 0,
    @ProtoNumber(20) val playerListedItems: List<SerializableMerchantItem> = emptyList(),
    @ProtoNumber(24) val recruitList: List<SerializableDisciple> = emptyList(),
    @ProtoNumber(25) val lastRecruitYear: Int = 0,
    @ProtoNumber(26) val cultivatorCaves: List<SerializableCultivatorCave> = emptyList(),
    @ProtoNumber(27) val caveExplorationTeams: List<SerializableCaveExplorationTeam> = emptyList(),
    @ProtoNumber(28) val aiCaveTeams: List<SerializableAICaveTeam> = emptyList(),
    @ProtoNumber(29) val unlockedDungeons: List<String> = emptyList(),
    @ProtoNumber(30) val unlockedRecipes: List<String> = emptyList(),
    @ProtoNumber(31) val unlockedManuals: List<String> = emptyList(),
    @ProtoNumber(32) val lastSaveTime: Long = 0L,
    @ProtoNumber(33) val elderSlots: SerializableElderSlots = SerializableElderSlots(),
    @ProtoNumber(34) val spiritMineSlots: List<SerializableSpiritMineSlot> = emptyList(),
    @ProtoNumber(35) val librarySlots: List<SerializableLibrarySlot> = emptyList(),
    @ProtoNumber(36) @Deprecated("Migrated to productionSlots") val forgeSlots: List<SerializableBuildingSlot> = emptyList(),
    @ProtoNumber(37) @Deprecated("Migrated to productionSlots") val alchemySlots: List<SerializableAlchemySlot> = emptyList(),
    @ProtoNumber(52) val productionSlots: List<SerializableProductionSlot> = emptyList(),
    @ProtoNumber(38) val alliances: List<SerializableAlliance> = emptyList(),
    @ProtoNumber(39) val sectRelations: List<SerializableSectRelation> = emptyList(),
    @ProtoNumber(40) val playerAllianceSlots: Int = 3,
    @ProtoNumber(42) val sectPolicies: SerializableSectPolicies = SerializableSectPolicies(),
    @ProtoNumber(43) val battleTeam: SerializableBattleTeam = SerializableBattleTeam(id="", name="", slots = emptyList(), isAtSect=true, currentX=0f, currentY=0f, targetX=0f, targetY=0f, status="", targetSectId="", originSectId="", route = emptyList(), currentRouteIndex=0, moveProgress=0f, isOccupying=false, isReturning=false),
    @ProtoNumber(44) val aiBattleTeams: List<SerializableAIBattleTeam> = emptyList(),
    @ProtoNumber(45) val usedRedeemCodes: List<String> = emptyList(),
    @ProtoNumber(46) val playerProtectionEnabled: Boolean = true,
    @ProtoNumber(47) val playerProtectionStartYear: Int = 1,
    @ProtoNumber(48) val playerHasAttackedAI: Boolean = false,
    @ProtoNumber(49) val activeMissions: List<SerializableActiveMission> = emptyList(),
    @ProtoNumber(50) val availableMissions: List<SerializableMission> = emptyList(),
    @ProtoNumber(53) val aiSectDisciples: List<SerializableAiSectDiscipleEntry> = emptyList(),
    @ProtoNumber(54) val sectDetails: Map<String, SerializableSectDetail> = emptyMap(),
    @ProtoNumber(55) val smartBattleEnabled: Boolean = false
)

@Serializable
data class SerializableAiSectDiscipleEntry(
    @ProtoNumber(1) val sectId: String,
    @ProtoNumber(2) val disciples: List<SerializableDisciple> = emptyList()
)

@Serializable
data class SerializableDisciple(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val realm: Int,
    @ProtoNumber(4) val realmLayer: Int,
    @ProtoNumber(5) val cultivation: Double,
    @ProtoNumber(6) val spiritRootType: String,
    @ProtoNumber(7) val age: Int,
    @ProtoNumber(8) val lifespan: Int,
    @ProtoNumber(9) val isAlive: Boolean,
    @ProtoNumber(10) val gender: String,
    @ProtoNumber(11) val partnerId: String = "",
    @ProtoNumber(12) val partnerSectId: String = "",
    @ProtoNumber(13) val parentId1: String = "",
    @ProtoNumber(14) val parentId2: String = "",
    @ProtoNumber(15) val lastChildYear: Int,
    @ProtoNumber(16) val griefEndYear: Int = -1,
    @ProtoNumber(17) val weaponId: String = "",
    @ProtoNumber(18) val armorId: String = "",
    @ProtoNumber(19) val bootsId: String = "",
    @ProtoNumber(20) val accessoryId: String = "",
    @ProtoNumber(21) val manualIds: List<String> = emptyList(),
    @ProtoNumber(22) val talentIds: List<String> = emptyList(),
    @ProtoNumber(23) val manualMasteries: Map<String, Int> = emptyMap(),
    @ProtoNumber(24) val weaponNurture: SerializableEquipmentNurtureData = SerializableEquipmentNurtureData(equipmentId="", rarity=0),
    @ProtoNumber(25) val armorNurture: SerializableEquipmentNurtureData = SerializableEquipmentNurtureData(equipmentId="", rarity=0),
    @ProtoNumber(26) val bootsNurture: SerializableEquipmentNurtureData = SerializableEquipmentNurtureData(equipmentId="", rarity=0),
    @ProtoNumber(27) val accessoryNurture: SerializableEquipmentNurtureData = SerializableEquipmentNurtureData(equipmentId="", rarity=0),
    @ProtoNumber(28) val spiritStones: Int,
    @ProtoNumber(29) val soulPower: Int,
    @ProtoNumber(30) val storageBagItems: List<SerializableStorageBagItem> = emptyList(),
    @ProtoNumber(31) val storageBagSpiritStones: Long,
    @ProtoNumber(32) val status: String,
    @ProtoNumber(33) val statusData: Map<String, String> = emptyMap(),
    @ProtoNumber(34) val cultivationSpeedBonus: Double,
    @ProtoNumber(35) val cultivationSpeedDuration: Int,
    @ProtoNumber(36) val pillPhysicalAttackBonus: Int,
    @ProtoNumber(37) val pillMagicAttackBonus: Int,
    @ProtoNumber(38) val pillPhysicalDefenseBonus: Int,
    @ProtoNumber(39) val pillMagicDefenseBonus: Int,
    @ProtoNumber(40) val pillHpBonus: Int,
    @ProtoNumber(41) val pillMpBonus: Int,
    @ProtoNumber(42) val pillSpeedBonus: Int,
    @ProtoNumber(43) val pillCritRateBonus: Double = 0.0,
    @ProtoNumber(44) val pillCritEffectBonus: Double = 0.0,
    @ProtoNumber(45) val pillCultivationSpeedBonus: Double = 0.0,
    @ProtoNumber(46) val pillSkillExpSpeedBonus: Double = 0.0,
    @ProtoNumber(47) val pillNurtureSpeedBonus: Double = 0.0,
    @ProtoNumber(48) val pillEffectDuration: Int,
    @ProtoNumber(49) val activePillCategory: String = "",
    @ProtoNumber(44) val totalCultivation: Long,
    @ProtoNumber(45) val breakthroughCount: Int,
    @ProtoNumber(46) val breakthroughFailCount: Int,
    @ProtoNumber(48) val intelligence: Int,
    @ProtoNumber(49) val charm: Int,
    @ProtoNumber(50) val loyalty: Int,
    @ProtoNumber(51) val comprehension: Int,
    @ProtoNumber(52) val artifactRefining: Int,
    @ProtoNumber(53) val pillRefining: Int,
    @ProtoNumber(54) val spiritPlanting: Int,
    @ProtoNumber(55) val teaching: Int,
    @ProtoNumber(56) val morality: Int,
    @ProtoNumber(57) val salaryPaidCount: Int,
    @ProtoNumber(58) val salaryMissedCount: Int,
    @ProtoNumber(59) val recruitedMonth: Int,
    @ProtoNumber(60) val hpVariance: Int,
    @ProtoNumber(61) val mpVariance: Int,
    @ProtoNumber(62) val physicalAttackVariance: Int,
    @ProtoNumber(63) val magicAttackVariance: Int,
    @ProtoNumber(64) val physicalDefenseVariance: Int,
    @ProtoNumber(65) val magicDefenseVariance: Int,
    @ProtoNumber(66) val speedVariance: Int,
    @ProtoNumber(67) val baseHp: Int,
    @ProtoNumber(68) val baseMp: Int,
    @ProtoNumber(69) val basePhysicalAttack: Int,
    @ProtoNumber(70) val baseMagicAttack: Int,
    @ProtoNumber(71) val basePhysicalDefense: Int,
    @ProtoNumber(72) val baseMagicDefense: Int,
    @ProtoNumber(73) val baseSpeed: Int,
    @ProtoNumber(74) val discipleType: String,
    @ProtoNumber(75) val usedFunctionalPillTypes: List<String> = emptyList(),
    @ProtoNumber(76) val usedExtendLifePillIds: List<String> = emptyList(),
    @ProtoNumber(77) val hasReviveEffect: Boolean,
    @ProtoNumber(78) val hasClearAllEffect: Boolean,
    @ProtoNumber(79) val currentHp: Int = -1,
    @ProtoNumber(80) val currentMp: Int = -1
)

@Serializable
data class SerializableEquipment(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val type: String,
    @ProtoNumber(4) val rarity: Int,
    @ProtoNumber(5) val level: Int,
    @ProtoNumber(6) val stats: Map<String, Int> = emptyMap(),
    @ProtoNumber(7) val description: String = "",
    @ProtoNumber(8) val obtainedYear: Int = 1,
    @ProtoNumber(9) val obtainedMonth: Int = 1,
    @ProtoNumber(10) val critChance: Double = 0.0,
    @ProtoNumber(11) val isEquipped: Boolean = false,
    @ProtoNumber(12) val equippedBy: String = "",
    @ProtoNumber(13) val nurtureLevel: Int = 0,
    @ProtoNumber(14) val nurtureProgress: Double = 0.0,
    @ProtoNumber(15) val minRealm: Int = 9,
    @ProtoNumber(16) val ownerId: String = "",
    @ProtoNumber(17) val quantity: Int = 1
)

@Serializable
data class SerializableManual(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val type: String,
    @ProtoNumber(4) val rarity: Int,
    @ProtoNumber(5) val stats: Map<String, Int> = emptyMap(),
    @ProtoNumber(6) val description: String = "",
    @ProtoNumber(7) val cultivationSpeedPercent: Double = 0.0,
    @ProtoNumber(8) val obtainedYear: Int = 1,
    @ProtoNumber(9) val obtainedMonth: Int = 1
)

@Serializable
data class SerializablePill(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val type: String,
    @ProtoNumber(4) val rarity: Int,
    @ProtoNumber(5) val effects: Map<String, Double> = emptyMap(),
    @ProtoNumber(6) val description: String = "",
    @ProtoNumber(7) val quantity: Int = 1,
    @ProtoNumber(8) val obtainedYear: Int = 1,
    @ProtoNumber(9) val obtainedMonth: Int = 1
)

@Serializable
data class SerializableMaterial(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val type: String,
    @ProtoNumber(4) val rarity: Int,
    @ProtoNumber(5) val quantity: Int = 1,
    @ProtoNumber(6) val description: String = "",
    @ProtoNumber(7) val obtainedYear: Int = 1,
    @ProtoNumber(8) val obtainedMonth: Int = 1
)

@Serializable
data class SerializableHerb(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val rarity: Int,
    @ProtoNumber(4) val quantity: Int = 1,
    @ProtoNumber(5) val age: Int = 0,
    @ProtoNumber(6) val description: String = "",
    @ProtoNumber(7) val obtainedYear: Int = 1,
    @ProtoNumber(8) val obtainedMonth: Int = 1
)

@Serializable
data class SerializableSeed(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val rarity: Int,
    @ProtoNumber(4) val growTime: Int,
    @ProtoNumber(5) val yieldMin: Int,
    @ProtoNumber(6) val yieldMax: Int,
    @ProtoNumber(7) val quantity: Int = 1,
    @ProtoNumber(8) val description: String = ""
)

@Serializable
data class SerializableExplorationTeam(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val memberIds: List<String> = emptyList(),
    @ProtoNumber(4) val status: String = "idle",
    @ProtoNumber(5) val targetSectId: String = "",
    @ProtoNumber(6) val startYear: Int = 0,
    @ProtoNumber(7) val startMonth: Int = 0,
    @ProtoNumber(8) val duration: Int = 0,
    @ProtoNumber(9) val currentProgress: Int = 0
)

@Serializable
data class SerializableGameEvent(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val type: String,
    @ProtoNumber(3) val title: String,
    @ProtoNumber(4) val description: String,
    @ProtoNumber(5) val timestamp: Long,
    @ProtoNumber(6) val gameYear: Int,
    @ProtoNumber(7) val gameMonth: Int,
    @ProtoNumber(8) val data: Map<String, String> = emptyMap()
)

@Serializable
data class SerializableBattleLog(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val timestamp: Long,
    @ProtoNumber(3) val gameYear: Int,
    @ProtoNumber(4) val gameMonth: Int,
    @ProtoNumber(5) val attackerSectId: String,
    @ProtoNumber(6) val attackerSectName: String,
    @ProtoNumber(7) val defenderSectId: String,
    @ProtoNumber(8) val defenderSectName: String,
    @ProtoNumber(9) val result: String,
    @ProtoNumber(10) val rounds: List<SerializableBattleLogRound> = emptyList(),
    @ProtoNumber(11) val attackerMembers: List<SerializableBattleLogMember> = emptyList(),
    @ProtoNumber(12) val defenderMembers: List<SerializableBattleLogMember> = emptyList(),
    @ProtoNumber(13) val rewards: Map<String, Int> = emptyMap(),
    @ProtoNumber(14) val type: String = "PVE",
    @ProtoNumber(15) val details: String = "",
    @ProtoNumber(16) val drops: List<String> = emptyList(),
    @ProtoNumber(17) val dungeonName: String = ""
)

@Serializable
data class SerializableAlliance(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val sectIds: List<String> = emptyList(),
    @ProtoNumber(3) val startYear: Int,
    @ProtoNumber(4) val initiatorId: String,
    @ProtoNumber(5) val envoyDiscipleId: String
)

@Serializable
data class SerializableSaveData(
    @ProtoNumber(1) val version: String,
    @ProtoNumber(2) val timestamp: Long,
    @ProtoNumber(3) val gameData: SerializableGameData,
    @ProtoNumber(4) val disciples: List<SerializableDisciple> = emptyList(),
    @ProtoNumber(5) val equipment: List<SerializableEquipment> = emptyList(),
    @ProtoNumber(6) val manuals: List<SerializableManual> = emptyList(),
    @ProtoNumber(7) val pills: List<SerializablePill> = emptyList(),
    @ProtoNumber(8) val materials: List<SerializableMaterial> = emptyList(),
    @ProtoNumber(9) val herbs: List<SerializableHerb> = emptyList(),
    @ProtoNumber(10) val seeds: List<SerializableSeed> = emptyList(),
    @ProtoNumber(11) val teams: List<SerializableExplorationTeam> = emptyList(),
    @ProtoNumber(12) val events: List<SerializableGameEvent> = emptyList(),
    @ProtoNumber(13) val battleLogs: List<SerializableBattleLog> = emptyList(),
    @ProtoNumber(14) val alliances: List<SerializableAlliance> = emptyList()
)

@Serializable
data class SerializableEquipmentNurtureData(
    @ProtoNumber(1) val equipmentId: String,
    @ProtoNumber(2) val rarity: Int,
    @ProtoNumber(3) val nurtureLevel: Int = 0,
    @ProtoNumber(4) val nurtureProgress: Double = 0.0
)

@Serializable
data class SerializableStorageBagItem(
    @ProtoNumber(1) val itemId: String,
    @ProtoNumber(2) val itemType: String,
    @ProtoNumber(3) val name: String,
    @ProtoNumber(4) val rarity: Int,
    @ProtoNumber(5) val quantity: Int = 1,
    @ProtoNumber(6) val obtainedYear: Int = 1,
    @ProtoNumber(7) val obtainedMonth: Int = 1,
    @ProtoNumber(8) val effect: SerializableItemEffect = SerializableItemEffect(),
    @ProtoNumber(9) val grade: String = ""
)

@Serializable
data class SerializableItemEffect(
    @ProtoNumber(1) val cultivationSpeedPercent: Double = 0.0,
    @ProtoNumber(2) val skillExpSpeedPercent: Double = 0.0,
    @ProtoNumber(3) val nurtureSpeedPercent: Double = 0.0,
    @ProtoNumber(4) val breakthroughChance: Double = 0.0,
    @ProtoNumber(5) val targetRealm: Int = 0,
    @ProtoNumber(6) val cultivationAdd: Int = 0,
    @ProtoNumber(7) val skillExpAdd: Int = 0,
    @ProtoNumber(8) val nurtureAdd: Int = 0,
    @ProtoNumber(9) val healMaxHpPercent: Double = 0.0,
    @ProtoNumber(10) val mpRecoverMaxMpPercent: Double = 0.0,
    @ProtoNumber(11) val hpAdd: Int = 0,
    @ProtoNumber(12) val mpAdd: Int = 0,
    @ProtoNumber(13) val extendLife: Int = 0,
    @ProtoNumber(14) val physicalAttackAdd: Int = 0,
    @ProtoNumber(15) val magicAttackAdd: Int = 0,
    @ProtoNumber(16) val physicalDefenseAdd: Int = 0,
    @ProtoNumber(17) val magicDefenseAdd: Int = 0,
    @ProtoNumber(18) val speedAdd: Int = 0,
    @ProtoNumber(19) val critRateAdd: Double = 0.0,
    @ProtoNumber(20) val critEffectAdd: Double = 0.0,
    @ProtoNumber(21) val intelligenceAdd: Int = 0,
    @ProtoNumber(22) val charmAdd: Int = 0,
    @ProtoNumber(23) val loyaltyAdd: Int = 0,
    @ProtoNumber(24) val comprehensionAdd: Int = 0,
    @ProtoNumber(25) val artifactRefiningAdd: Int = 0,
    @ProtoNumber(26) val pillRefiningAdd: Int = 0,
    @ProtoNumber(27) val spiritPlantingAdd: Int = 0,
    @ProtoNumber(28) val teachingAdd: Int = 0,
    @ProtoNumber(29) val moralityAdd: Int = 0,
    @ProtoNumber(30) val revive: Boolean = false,
    @ProtoNumber(31) val clearAll: Boolean = false,
    @ProtoNumber(32) val isAscension: Boolean = false,
    @ProtoNumber(33) val duration: Int = 0,
    @ProtoNumber(34) val cannotStack: Boolean = true,
    @ProtoNumber(35) val minRealm: Int = 9,
    @ProtoNumber(36) val pillCategory: String = "",
    @ProtoNumber(37) val pillType: String = ""
)

@Serializable
data class SerializableWorldSect(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val level: Int,
    @ProtoNumber(4) val levelName: String,
    @ProtoNumber(5) val x: Float,
    @ProtoNumber(6) val y: Float,
    @ProtoNumber(7) val distance: Int,
    @ProtoNumber(8) val isPlayerSect: Boolean,
    @ProtoNumber(9) val discovered: Boolean,
    @ProtoNumber(10) val isKnown: Boolean,
    @ProtoNumber(11) val relation: Int,
    @ProtoNumber(12) val disciples: Map<Int, Int> = emptyMap(),
    @ProtoNumber(13) val maxRealm: Int,
    @ProtoNumber(14) val connectedSectIds: List<String> = emptyList(),
    @ProtoNumber(15) val isOccupied: Boolean,
    @ProtoNumber(16) val occupierTeamId: String = "",
    @ProtoNumber(17) val occupierTeamName: String,
    @ProtoNumber(18) val mineSlots: List<SerializableMineSlot> = emptyList(),
    @ProtoNumber(19) val occupationTime: Long,
    @ProtoNumber(20) val isOwned: Boolean,
    @ProtoNumber(21) val expiryYear: Int,
    @ProtoNumber(22) val expiryMonth: Int,
    @ProtoNumber(23) val scoutInfo: SerializableSectScoutInfo = SerializableSectScoutInfo(sectId="", sectName="", scoutYear=0, scoutMonth=0, discipleCount=0, maxRealm=0, isKnown=false, expiryYear=0, expiryMonth=0),
    @ProtoNumber(24) val tradeItems: List<SerializableMerchantItem> = emptyList(),
    @ProtoNumber(25) val tradeLastRefreshYear: Int,
    @ProtoNumber(26) val lastGiftYear: Int,
    @ProtoNumber(27) val allianceId: String = "",
    @ProtoNumber(28) val allianceStartYear: Int,
    @ProtoNumber(29) val isRighteous: Boolean,
    @ProtoNumber(31) val isPlayerOccupied: Boolean,
    @ProtoNumber(32) val occupierBattleTeamId: String = "",
    @ProtoNumber(33) val isUnderAttack: Boolean,
    @ProtoNumber(34) val attackerSectId: String = "",
    @ProtoNumber(35) val occupierSectId: String = "",
    @ProtoNumber(36) val warehouse: SerializableSectWarehouse = SerializableSectWarehouse(),
    @ProtoNumber(37) val giftPreference: String = "NONE"
)

@Serializable
data class SerializableMineSlot(
    @ProtoNumber(1) val index: Int,
    @ProtoNumber(2) val discipleId: String = "",
    @ProtoNumber(3) val discipleName: String,
    @ProtoNumber(4) val output: Int,
    @ProtoNumber(5) val efficiency: Double = 1.0,
    @ProtoNumber(6) val isActive: Boolean
)

@Serializable
data class SerializableSectDetail(
    @ProtoNumber(1) val sectId: String = "",
    @ProtoNumber(2) val mineSlots: List<SerializableMineSlot> = emptyList(),
    @ProtoNumber(3) val occupationTime: Long = 0,
    @ProtoNumber(4) val isOwned: Boolean = false,
    @ProtoNumber(5) val expiryYear: Int = 0,
    @ProtoNumber(6) val expiryMonth: Int = 0,
    @ProtoNumber(7) val scoutInfo: SerializableSectScoutInfo = SerializableSectScoutInfo(sectId="", sectName="", scoutYear=0, scoutMonth=0, discipleCount=0, maxRealm=0, isKnown=false, expiryYear=0, expiryMonth=0),
    @ProtoNumber(8) val tradeItems: List<SerializableMerchantItem> = emptyList(),
    @ProtoNumber(9) val tradeLastRefreshYear: Int = 0,
    @ProtoNumber(10) val lastGiftYear: Int = 0,
    @ProtoNumber(11) val warehouse: SerializableSectWarehouse = SerializableSectWarehouse(),
    @ProtoNumber(12) val giftPreference: String = "NONE"
)

@Serializable
data class SerializableSectWarehouse(
    @ProtoNumber(1) val items: List<SerializableWarehouseItem> = emptyList(),
    @ProtoNumber(2) val spiritStones: Long = 0
)

@Serializable
data class SerializableWarehouseItem(
    @ProtoNumber(1) val itemId: String,
    @ProtoNumber(2) val itemName: String,
    @ProtoNumber(3) val itemType: String,
    @ProtoNumber(4) val rarity: Int,
    @ProtoNumber(5) val quantity: Int
)

@Serializable
data class SerializableSectScoutInfo(
    @ProtoNumber(1) val sectId: String,
    @ProtoNumber(2) val sectName: String,
    @ProtoNumber(3) val scoutYear: Int,
    @ProtoNumber(4) val scoutMonth: Int,
    @ProtoNumber(5) val discipleCount: Int,
    @ProtoNumber(6) val maxRealm: Int,
    @ProtoNumber(7) val resources: Map<String, Int> = emptyMap(),
    @ProtoNumber(8) val isKnown: Boolean,
    @ProtoNumber(9) val disciples: Map<Int, Int> = emptyMap(),
    @ProtoNumber(10) val expiryYear: Int,
    @ProtoNumber(11) val expiryMonth: Int
)

@Serializable
data class SerializableExploredSectInfo(
    @ProtoNumber(1) val sectId: String,
    @ProtoNumber(2) val sectName: String,
    @ProtoNumber(3) val year: Int,
    @ProtoNumber(4) val month: Int,
    @ProtoNumber(5) val duration: Int,
    @ProtoNumber(6) val memberIds: List<String> = emptyList(),
    @ProtoNumber(7) val memberNames: List<String> = emptyList(),
    @ProtoNumber(8) val events: List<String> = emptyList(),
    @ProtoNumber(9) val rewards: List<String> = emptyList(),
    @ProtoNumber(10) val battleCount: Int,
    @ProtoNumber(11) val casualties: Int,
    @ProtoNumber(12) val discipleCount: Int,
    @ProtoNumber(13) val maxRealm: Int
)

@Serializable
data class SerializableMerchantItem(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val type: String,
    @ProtoNumber(4) val itemId: String,
    @ProtoNumber(5) val rarity: Int,
    @ProtoNumber(6) val price: Int,
    @ProtoNumber(7) val quantity: Int,
    @ProtoNumber(8) val description: String,
    @ProtoNumber(9) val obtainedYear: Int,
    @ProtoNumber(10) val obtainedMonth: Int,
    @ProtoNumber(11) val grade: String = ""
)

@Serializable
data class SerializablePlantSlotData(
    @ProtoNumber(1) val index: Int,
    @ProtoNumber(2) val status: String,
    @ProtoNumber(3) val seedId: String = "",
    @ProtoNumber(4) val seedName: String,
    @ProtoNumber(5) val startYear: Int,
    @ProtoNumber(6) val startMonth: Int,
    @ProtoNumber(7) val growTime: Int,
    @ProtoNumber(8) val expectedYield: Int,
    @Deprecated("Use expectedYield instead. Kept for proto serialization compatibility.")
    @ProtoNumber(9) val harvestAmount: Int,
    @Deprecated("Use seedId to derive herbId. Kept for proto serialization compatibility.")
    @ProtoNumber(10) val harvestHerbId: String = ""
)

@Serializable
data class SerializableManualProficiencyData(
    @ProtoNumber(1) val manualId: String,
    @ProtoNumber(2) val manualName: String,
    @ProtoNumber(3) val proficiency: Double,
    @ProtoNumber(4) val maxProficiency: Int,
    @ProtoNumber(5) val level: Int,
    @ProtoNumber(6) val masteryLevel: Int
)

@Serializable
data class SerializableCultivatorCave(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val level: Int,
    @ProtoNumber(4) val x: Float,
    @ProtoNumber(5) val y: Float,
    @ProtoNumber(6) val ownerSectId: String = "",
    @ProtoNumber(7) val ownerSectName: String,
    @ProtoNumber(8) val disciples: List<SerializableDisciple> = emptyList(),
    @ProtoNumber(9) val resources: Map<String, Int> = emptyMap(),
    @ProtoNumber(10) val discovered: Boolean
)

@Serializable
data class SerializableCaveExplorationTeam(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val memberIds: List<String> = emptyList(),
    @ProtoNumber(4) val targetCaveId: String,
    @ProtoNumber(5) val status: String,
    @ProtoNumber(6) val startYear: Int,
    @ProtoNumber(7) val startMonth: Int,
    @ProtoNumber(8) val duration: Int
)

@Serializable
data class SerializableAICaveTeam(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val sectId: String,
    @ProtoNumber(3) val sectName: String,
    @ProtoNumber(4) val targetCaveId: String,
    @ProtoNumber(5) val disciples: List<SerializableDisciple> = emptyList(),
    @ProtoNumber(6) val status: String,
    @ProtoNumber(7) val startYear: Int,
    @ProtoNumber(8) val startMonth: Int
)

@Serializable
data class SerializableElderSlots(
    @ProtoNumber(1) val viceSectMaster: String = "",
    @ProtoNumber(2) val herbGardenElder: String = "",
    @ProtoNumber(3) val alchemyElder: String = "",
    @ProtoNumber(4) val forgeElder: String = "",
    @ProtoNumber(6) val outerElder: String = "",
    @ProtoNumber(7) val preachingElder: String = "",
    @ProtoNumber(8) val preachingMasters: List<SerializableDirectDiscipleSlot> = emptyList(),
    @ProtoNumber(9) val lawEnforcementElder: String = "",
    @ProtoNumber(10) val lawEnforcementDisciples: List<SerializableDirectDiscipleSlot> = emptyList(),
    @ProtoNumber(11) val lawEnforcementReserveDisciples: List<SerializableDirectDiscipleSlot> = emptyList(),
    @ProtoNumber(12) val innerElder: String = "",
    @ProtoNumber(13) val qingyunPreachingElder: String = "",
    @ProtoNumber(14) val qingyunPreachingMasters: List<SerializableDirectDiscipleSlot> = emptyList(),
    @ProtoNumber(15) val herbGardenDisciples: List<SerializableDirectDiscipleSlot> = emptyList(),
    @ProtoNumber(16) val alchemyDisciples: List<SerializableDirectDiscipleSlot> = emptyList(),
    @ProtoNumber(17) val forgeDisciples: List<SerializableDirectDiscipleSlot> = emptyList(),
    @ProtoNumber(19) val herbGardenReserveDisciples: List<SerializableDirectDiscipleSlot> = emptyList(),
    @ProtoNumber(20) val alchemyReserveDisciples: List<SerializableDirectDiscipleSlot> = emptyList(),
    @ProtoNumber(21) val forgeReserveDisciples: List<SerializableDirectDiscipleSlot> = emptyList(),
    @ProtoNumber(22) val spiritMineDeaconDisciples: List<SerializableDirectDiscipleSlot> = emptyList()
)

@Serializable
data class SerializableDirectDiscipleSlot(
    @ProtoNumber(1) val index: Int,
    @ProtoNumber(2) val discipleId: String = "",
    @ProtoNumber(3) val discipleName: String,
    @ProtoNumber(4) val discipleRealm: String,
    @ProtoNumber(5) val discipleSpiritRootColor: String
)

@Serializable
data class SerializableSpiritMineSlot(
    @ProtoNumber(1) val index: Int,
    @ProtoNumber(2) val discipleId: String = "",
    @ProtoNumber(3) val discipleName: String,
    @ProtoNumber(4) val output: Int
)

@Serializable
data class SerializableLibrarySlot(
    @ProtoNumber(1) val index: Int,
    @ProtoNumber(2) val discipleId: String = "",
    @ProtoNumber(3) val discipleName: String
)

@Serializable
data class SerializableBuildingSlot(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val type: String,
    @ProtoNumber(3) val discipleId: String = "",
    @ProtoNumber(4) val discipleName: String,
    @ProtoNumber(5) val recipeId: String = "",
    @ProtoNumber(6) val recipeName: String,
    @ProtoNumber(7) val progress: Double,
    @ProtoNumber(8) val status: String,
    @ProtoNumber(9) val startYear: Int,
    @ProtoNumber(10) val startMonth: Int,
    @ProtoNumber(11) val resultItemId: String = "",
    @ProtoNumber(12) val resultQuantity: Int
)

@Serializable
data class SerializableAlchemySlot(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val discipleId: String = "",
    @ProtoNumber(3) val discipleName: String,
    @ProtoNumber(4) val recipeId: String = "",
    @ProtoNumber(5) val recipeName: String,
    @ProtoNumber(6) val progress: Double,
    @ProtoNumber(7) val status: String,
    @ProtoNumber(8) val startYear: Int,
    @ProtoNumber(9) val startMonth: Int,
    @ProtoNumber(10) val resultItemId: String = "",
    @ProtoNumber(11) val resultQuantity: Int
)

@Serializable
data class SerializableProductionSlot(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val slotIndex: Int,
    @ProtoNumber(3) val buildingType: String,
    @ProtoNumber(4) val buildingId: String,
    @ProtoNumber(5) val status: String,
    @ProtoNumber(6) val recipeId: String = "",
    @ProtoNumber(7) val recipeName: String,
    @ProtoNumber(8) val startYear: Int,
    @ProtoNumber(9) val startMonth: Int,
    @ProtoNumber(10) val duration: Int,
    @ProtoNumber(11) val assignedDiscipleId: String = "",
    @ProtoNumber(12) val assignedDiscipleName: String,
    @ProtoNumber(13) val successRate: Double,
    @ProtoNumber(14) val outputItemId: String = "",
    @ProtoNumber(15) val outputItemName: String,
    @ProtoNumber(16) val outputItemRarity: Int
)

@Serializable
data class SerializableSectRelation(
    @ProtoNumber(1) val sectId1: String,
    @ProtoNumber(2) val sectId2: String,
    @ProtoNumber(3) val favor: Int,
    @ProtoNumber(4) val lastInteractionYear: Int,
    @ProtoNumber(5) val noGiftYears: Int
)

@Serializable
data class SerializableSectPolicies(
    @ProtoNumber(1) val spiritMineBoost: Boolean = false,
    @ProtoNumber(2) val enhancedSecurity: Boolean = false,
    @ProtoNumber(3) val alchemyIncentive: Boolean = false,
    @ProtoNumber(4) val forgeIncentive: Boolean = false,
    @ProtoNumber(5) val herbCultivation: Boolean = false,
    @ProtoNumber(6) val cultivationSubsidy: Boolean = false,
    @ProtoNumber(7) val manualResearch: Boolean = false,
    @ProtoNumber(8) val autoPlant: Boolean = false,
    @ProtoNumber(9) val autoAlchemy: Boolean = false,
    @ProtoNumber(10) val autoForge: Boolean = false
)

@Serializable
data class SerializableBattleTeam(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val slots: List<SerializableBattleTeamSlot> = emptyList(),
    @ProtoNumber(4) val isAtSect: Boolean,
    @ProtoNumber(5) val currentX: Float,
    @ProtoNumber(6) val currentY: Float,
    @ProtoNumber(7) val targetX: Float,
    @ProtoNumber(8) val targetY: Float,
    @ProtoNumber(9) val status: String,
    @ProtoNumber(10) val targetSectId: String = "",
    @ProtoNumber(11) val originSectId: String = "",
    @ProtoNumber(12) val route: List<String> = emptyList(),
    @ProtoNumber(13) val currentRouteIndex: Int,
    @ProtoNumber(14) val moveProgress: Float,
    @ProtoNumber(15) val isOccupying: Boolean,
    @ProtoNumber(16) val occupiedSectId: String = "",
    @ProtoNumber(17) val isReturning: Boolean
)

@Serializable
data class SerializableBattleTeamSlot(
    @ProtoNumber(1) val index: Int,
    @ProtoNumber(2) val discipleId: String = "",
    @ProtoNumber(3) val discipleName: String,
    @ProtoNumber(4) val discipleRealm: String,
    @ProtoNumber(5) val slotType: String,
    @ProtoNumber(6) val isAlive: Boolean
)

@Serializable
data class SerializableAIBattleTeam(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val attackerSectId: String,
    @ProtoNumber(3) val attackerSectName: String,
    @ProtoNumber(4) val defenderSectId: String,
    @ProtoNumber(5) val defenderSectName: String,
    @ProtoNumber(6) val disciples: List<SerializableDisciple> = emptyList(),
    @ProtoNumber(7) val currentX: Float,
    @ProtoNumber(8) val currentY: Float,
    @ProtoNumber(9) val targetX: Float,
    @ProtoNumber(10) val targetY: Float,
    @ProtoNumber(11) val attackerStartX: Float,
    @ProtoNumber(12) val attackerStartY: Float,
    @ProtoNumber(13) val moveProgress: Float,
    @ProtoNumber(14) val status: String,
    @ProtoNumber(15) val route: List<String> = emptyList(),
    @ProtoNumber(16) val currentRouteIndex: Int,
    @ProtoNumber(17) val startYear: Int,
    @ProtoNumber(18) val startMonth: Int,
    @ProtoNumber(19) val isPlayerDefender: Boolean
)

@Serializable
data class SerializableBattleLogRound(
    @ProtoNumber(1) val roundNumber: Int,
    @ProtoNumber(2) val actions: List<SerializableBattleLogAction> = emptyList()
)

@Serializable
data class SerializableBattleLogAction(
    @ProtoNumber(1) val actorId: String,
    @ProtoNumber(2) val actorName: String,
    @ProtoNumber(3) val targetType: String,
    @ProtoNumber(4) val targetId: String,
    @ProtoNumber(5) val targetName: String,
    @ProtoNumber(6) val skillName: String,
    @ProtoNumber(7) val damage: Int,
    @ProtoNumber(8) val isCritical: Boolean,
    @ProtoNumber(9) val effect: String
)

@Serializable
data class SerializableBattleLogMember(
    @ProtoNumber(1) val discipleId: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val realm: Int,
    @ProtoNumber(4) val isAlive: Boolean,
    @ProtoNumber(5) val remainingHp: Int,
    @ProtoNumber(6) val maxHp: Int,
    @ProtoNumber(7) val remainingMp: Int = 0,
    @ProtoNumber(8) val maxMp: Int = 0
)

@Serializable
data class SerializableActiveMission(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val missionId: String,
    @ProtoNumber(3) val name: String,
    @ProtoNumber(4) val description: String,
    @ProtoNumber(5) val startYear: Int,
    @ProtoNumber(6) val startMonth: Int,
    @ProtoNumber(7) val assignedDisciples: List<String> = emptyList(),
    @ProtoNumber(8) val progress: Int,
    @ProtoNumber(9) val targetProgress: Int,
    @ProtoNumber(10) val status: String,
    @ProtoNumber(11) val missionType: String = "ESCORT",
    @ProtoNumber(12) val difficulty: Int = 0,
    @ProtoNumber(13) val discipleNames: List<String> = emptyList(),
    @ProtoNumber(35) val discipleRealms: List<String> = emptyList(),
    @ProtoNumber(14) val investigateOutcome: String = "",
    @ProtoNumber(16) val spiritStones: Int = 0,
    @ProtoNumber(34) val spiritStonesMax: Int = 0,
    @ProtoNumber(17) val extraItemChance: Double = 0.0,
    @ProtoNumber(18) val extraItemCountMin: Int = 0,
    @ProtoNumber(19) val extraItemCountMax: Int = 0,
    @ProtoNumber(20) val extraItemMinRarity: Int = 1,
    @ProtoNumber(21) val extraItemMaxRarity: Int = 2,
    @ProtoNumber(22) val materialCountMin: Int = 0,
    @ProtoNumber(23) val materialCountMax: Int = 0,
    @ProtoNumber(24) val materialMinRarity: Int = 1,
    @ProtoNumber(25) val materialMaxRarity: Int = 2,
    @ProtoNumber(26) val herbCountMin: Int = 0,
    @ProtoNumber(27) val herbCountMax: Int = 0,
    @ProtoNumber(28) val herbMinRarity: Int = 1,
    @ProtoNumber(29) val herbMaxRarity: Int = 1,
    @ProtoNumber(30) val seedCountMin: Int = 0,
    @ProtoNumber(31) val seedCountMax: Int = 0,
    @ProtoNumber(32) val seedMinRarity: Int = 1,
    @ProtoNumber(33) val seedMaxRarity: Int = 1
)

@Serializable
data class SerializableMission(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val description: String,
    @ProtoNumber(4) val difficulty: Int,
    @ProtoNumber(5) val minDisciples: Int,
    @ProtoNumber(6) val maxDisciples: Int,
    @ProtoNumber(7) val duration: Int,
    @ProtoNumber(8) val rewards: Map<String, Int> = emptyMap(),
    @ProtoNumber(9) val requirements: Map<String, Int> = emptyMap(),
    @ProtoNumber(10) val type: String = "ESCORT",
    @ProtoNumber(11) val minRealm: Int = 9,
    @ProtoNumber(12) val createdYear: Int = 1,
    @ProtoNumber(13) val createdMonth: Int = 1,
    @ProtoNumber(14) val spiritStones: Int = 0,
    @ProtoNumber(33) val spiritStonesMax: Int = 0,
    @ProtoNumber(16) val extraItemChance: Double = 0.0,
    @ProtoNumber(17) val extraItemCountMin: Int = 0,
    @ProtoNumber(18) val extraItemCountMax: Int = 0,
    @ProtoNumber(19) val extraItemMinRarity: Int = 1,
    @ProtoNumber(20) val extraItemMaxRarity: Int = 2,
    @ProtoNumber(21) val materialCountMin: Int = 0,
    @ProtoNumber(22) val materialCountMax: Int = 0,
    @ProtoNumber(23) val materialMinRarity: Int = 1,
    @ProtoNumber(24) val materialMaxRarity: Int = 2,
    @ProtoNumber(25) val herbCountMin: Int = 0,
    @ProtoNumber(26) val herbCountMax: Int = 0,
    @ProtoNumber(27) val herbMinRarity: Int = 1,
    @ProtoNumber(28) val herbMaxRarity: Int = 1,
    @ProtoNumber(29) val seedCountMin: Int = 0,
    @ProtoNumber(30) val seedCountMax: Int = 0,
    @ProtoNumber(31) val seedMinRarity: Int = 1,
    @ProtoNumber(32) val seedMaxRarity: Int = 1
)

@Serializable
data class MetadataFile(
    @ProtoNumber(1) val version: String,
    @ProtoNumber(2) val slots: Map<Int, SerializableSaveSlot>
)

/**
 * 带完整性哈希的存档数据包装
 * 序列化时自动计算并附加SHA-256哈希，反序列化时自动验证
 */
@Serializable
data class HashedSaveData(
    @ProtoNumber(1) val data: SerializableSaveData,
    @ProtoNumber(2) val integrityHash: String = "",
    @ProtoNumber(3) val hashAlgorithm: String = "SHA-256",
    @ProtoNumber(4) val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        const val DEFAULT_ALGORITHM = "SHA-256"

        /**
         * 创建带哈希的存档数据
         * 自动计算数据的SHA-256哈希值
         */
        fun create(data: SerializableSaveData): HashedSaveData {
            val hash = computeHash(data)
            return HashedSaveData(
                data = data,
                integrityHash = hash,
                hashAlgorithm = DEFAULT_ALGORITHM,
                timestamp = System.currentTimeMillis()
            )
        }

        /**
         * 计算SerializableSaveData的SHA-256哈希
         */
        private fun computeHash(data: SerializableSaveData): String {
            val digest = java.security.MessageDigest.getInstance(DEFAULT_ALGORITHM)

            digest.update(data.version.toByteArray(Charsets.UTF_8))
            digest.update(data.timestamp.toString().toByteArray(Charsets.UTF_8))

            data.gameData?.let { gd ->
                digest.update("gameData:".toByteArray(Charsets.UTF_8))
                digest.update(gd.sectName.toByteArray(Charsets.UTF_8))
                digest.update(gd.currentSlot.toString().toByteArray(Charsets.UTF_8))
                digest.update(gd.spiritStones.toString().toByteArray(Charsets.UTF_8))
            }

            digest.update("|disciples:".toByteArray(Charsets.UTF_8))
            digest.update(data.disciples.size.toString().toByteArray(Charsets.UTF_8))
            data.disciples.forEach { d ->
                digest.update(d.id.toByteArray(Charsets.UTF_8))
                digest.update(d.name.toByteArray(Charsets.UTF_8))
            }

            digest.update("|equipment:".toByteArray(Charsets.UTF_8))
            digest.update(data.equipment.size.toString().toByteArray(Charsets.UTF_8))

            digest.update("|manuals:".toByteArray(Charsets.UTF_8))
            digest.update(data.manuals.size.toString().toByteArray(Charsets.UTF_8))

            val hashBytes = digest.digest()
            return hashBytes.joinToString("") { "%02x".format(it) }
        }

        /**
         * 验证数据完整性
         * @return true 如果哈希匹配，false 如果数据被篡改
         */
        fun verifyIntegrity(hashedData: HashedSaveData): Boolean {
            if (hashedData.integrityHash.isEmpty()) {
                return false
            }

            val computedHash = computeHash(hashedData.data)
            return computedHash == hashedData.integrityHash
        }
    }

    /**
     * 验证当前实例的数据完整性
     */
    fun isValid(): Boolean {
        return verifyIntegrity(this)
    }
}
