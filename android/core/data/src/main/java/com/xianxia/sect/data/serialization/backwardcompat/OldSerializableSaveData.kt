package com.xianxia.sect.data.serialization.backwardcompat

import com.xianxia.sect.core.model.GridBuildingData
import kotlinx.serialization.*
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoPacked



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
    @ProtoNumber(6) val gamePhase: Int = 0,  // 0=上旬,1=中旬,2=下旬; 旧存档 gameDay 需映射: (v-1)/10
    @ProtoNumber(7) val spiritStones: Long = 1000,
    @ProtoNumber(8) val spiritHerbs: Int = 0,
    @ProtoNumber(9) val autoSaveIntervalMonths: Int = 3,
    @ProtoNumber(10) val yearlySalary: Map<Int, Int> = emptyMap(),
    @ProtoNumber(11) val yearlySalaryEnabled: Map<Int, Boolean> = emptyMap(),
    @ProtoNumber(12) val worldMapSects: List<SerializableWorldSect> = emptyList(),
    @ProtoNumber(13) val exploredSects: Map<String, SerializableExploredSectInfo> = emptyMap(),
    @ProtoNumber(14) val scoutInfo: Map<String, SerializableSectScoutInfo> = emptyMap(),
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
    // @ProtoNumber(29) val unlockedDungeons — removed
    @ProtoNumber(30) val unlockedRecipes: List<String> = emptyList(),
    @ProtoNumber(31) val unlockedManuals: List<String> = emptyList(),
    @ProtoNumber(32) val lastSaveTime: Long = 0L,
    @ProtoNumber(33) val elderSlots: SerializableElderSlots = SerializableElderSlots(),
    @ProtoNumber(34) val spiritMineSlots: List<SerializableSpiritMineSlot> = emptyList(),
    @ProtoNumber(35) val librarySlots: List<SerializableLibrarySlot> = emptyList(),
    @ProtoNumber(36) val residenceSlots: List<SerializableResidenceSlot> = emptyList(),
    @ProtoNumber(52) val productionSlots: List<SerializableProductionSlot> = emptyList(),
    @ProtoNumber(38) val alliances: List<SerializableAlliance> = emptyList(),
    @ProtoNumber(39) val sectRelations: List<SerializableSectRelation> = emptyList(),
    @ProtoNumber(40) val playerAllianceSlots: Int = 3,
    @ProtoNumber(42) val sectPolicies: SerializableSectPolicies = SerializableSectPolicies(),
    // @ProtoNumber(44) val aiBattleTeams — removed in v3.0.19
    @ProtoNumber(45) val usedRedeemCodes: List<String> = emptyList(),
    @ProtoNumber(46) val playerProtectionEnabled: Boolean = true,
    @ProtoNumber(47) val playerProtectionStartYear: Int = 1,
    @ProtoNumber(48) val playerHasAttackedAI: Boolean = false,
    @ProtoNumber(49) val activeMissions: List<SerializableActiveMission> = emptyList(),
    @ProtoNumber(50) val availableMissions: List<SerializableMission> = emptyList(),
    @ProtoNumber(53) val aiSectDisciples: List<SerializableAiSectDiscipleEntry> = emptyList(),
    @ProtoNumber(54) val sectDetails: Map<String, SerializableSectDetail> = emptyMap(),
    // @ProtoNumber(55) val smartBattleEnabled — removed
    @ProtoNumber(87) val spiritMineExpansions: Int = 0,
    @ProtoNumber(88) val merchantAcquisitionItems: List<SerializableMerchantItem> = emptyList(),
    @ProtoNumber(89) val merchantAcquisitionLastRefreshYear: Int = 0,
    @ProtoNumber(90) val merchantRefreshChances: Int = 1,
    @ProtoNumber(92) val merchantLastRefreshChanceGrantYear: Int = 0,
    @ProtoNumber(91) val gameEventRecords: List<SerializableGameEventRecord> = emptyList(),
    @ProtoNumber(93) val openRecruitmentLastPaidMonth: Int = 0,
    // ==================== 新增字段（ProtoNumber 94+）====================
    @ProtoNumber(94) val midGradeSpiritStones: Long = 0L,
    @ProtoNumber(95) val highGradeSpiritStones: Long = 0L,
    @ProtoNumber(96) val sectCultivation: Double = 0.0,
    @ProtoNumber(97) val worldLevelLastRefreshMonth: Int = 0,
    @ProtoNumber(98) val rngStates: Map<Int, Long> = emptyMap(),
    @ProtoNumber(99) val activeSectId: String = "",
    @ProtoNumber(100) val saveVersion: Int = 0,
    @ProtoPacked @ProtoNumber(101) val autoRecruitSpiritRootFilter: List<Int> = emptyList(),
    @ProtoPacked @ProtoNumber(102) val daoCompanionBannedRootCounts: List<Int> = emptyList(),
    @ProtoNumber(103) val daoCompanionConsentRequired: Boolean = false,
    @ProtoNumber(104) val patrolBattleResultPopup: Boolean = false,
    @ProtoNumber(105) val autoSellMidGradeForPurchase: Boolean = false,
    @ProtoNumber(106) val autoSellHighGradeForPurchase: Boolean = false,
    @ProtoNumber(107) val showAllAvailableDisciples: Boolean = false,
    @ProtoNumber(108) val breakthroughAutoPillFocused: Boolean = false,
    @ProtoPacked @ProtoNumber(109) val breakthroughAutoPillRootCounts: List<Int> = emptyList(),
    @ProtoNumber(110) val autoEquipFromWarehouseFocused: Boolean = false,
    @ProtoPacked @ProtoNumber(111) val autoEquipFromWarehouseRootCounts: List<Int> = emptyList(),
    @ProtoNumber(112) val autoLearnFromWarehouseFocused: Boolean = false,
    @ProtoPacked @ProtoNumber(113) val autoLearnFromWarehouseRootCounts: List<Int> = emptyList(),
    @ProtoNumber(114) val isGameOver: Boolean = false,
    @ProtoNumber(115) val bloodRefinements: Map<String, List<String>> = emptyMap(),
    @ProtoNumber(116) val suzerainSectId: String = "",
    @ProtoNumber(117) val lastYearSpiritStoneIncome: Long = 0L,
    @ProtoNumber(118) val shownWarningStageIds: List<String> = emptyList(),
    @ProtoNumber(119) val sectAttackCooldowns: Map<String, Int> = emptyMap(),
    @ProtoPacked @ProtoNumber(120) val guideClaimedRewardIds: List<Int> = emptyList(),
    @ProtoNumber(121) val guideCounters: Map<String, Long> = emptyMap(),
    @ProtoNumber(122) val mapSeed: Int = 0,
    @ProtoNumber(123) val annualIncomeBySource: Map<String, Long> = emptyMap(),
    @ProtoNumber(124) val annualExpenditureByReason: Map<String, Long> = emptyMap(),
    @ProtoNumber(125) val annualTotalIncome: Long = 0L,
    @ProtoNumber(126) val annualTotalExpenditure: Long = 0L,
    @ProtoNumber(127) val annualAlchemyCount: Int = 0,
    @ProtoNumber(128) val annualForgeCount: Int = 0,
    @ProtoNumber(129) val annualHerbCount: Int = 0,
    @ProtoNumber(130) val annualNewDisciples: Int = 0,
    @ProtoNumber(131) val annualDeceasedDisciples: Int = 0,
    @ProtoNumber(132) val annualDesertedDisciples: Int = 0,
    @ProtoNumber(133) val annualTheftCount: Int = 0,
    @ProtoNumber(134) val theftJudgementsThisMonth: Int = 0,
    @ProtoNumber(135) val annualEquipmentBySource: Map<String, Int> = emptyMap(),
    @ProtoNumber(136) val annualPillBySource: Map<String, Int> = emptyMap(),
    @ProtoNumber(137) val annualHerbBySource: Map<String, Int> = emptyMap(),
    @ProtoNumber(138) val spiritMineLastSettledMonth: Int = 0,
    @ProtoNumber(139) val placedBuildings: List<GridBuildingData> = emptyList(),
    @ProtoNumber(140) val worldLevels: List<SerializableWorldLevel> = emptyList(),
    @ProtoNumber(141) val spiritFieldPlants: List<SerializableSpiritFieldPlant> = emptyList(),
    @ProtoNumber(142) val patrolSlots: List<SerializablePatrolSlot> = emptyList(),
    @ProtoNumber(143) val patrolConfig: SerializablePatrolConfig? = null,
    @ProtoNumber(144) val patrolConfigs: List<SerializablePatrolConfig> = emptyList(),
    @ProtoNumber(145) val pendingPatrolBattleResults: List<SerializableBattleResultUIData> = emptyList(),
    @ProtoNumber(146) val warehouseGarrisons: List<SerializableWarehouseGarrisonSlot> = emptyList(),
    @ProtoNumber(147) val vassalContracts: List<SerializableVassalContract> = emptyList(),
    @ProtoNumber(148) val mailRecords: List<SerializableMailClaimRecord> = emptyList(),
    @ProtoNumber(149) val sectLevelClaimRecords: List<SerializableSectLevelClaimRecord> = emptyList(),
    @ProtoNumber(150) val activeBloodRefinements: Map<String, SerializableBloodRefinementProgress> = emptyMap(),
    @ProtoNumber(151) val bloodRefinementBonusTotals: Map<String, SerializableBloodRefinementBonusTotal> = emptyMap(),
    @ProtoNumber(152) val bloodRefinementPctTotals: Map<String, SerializableBloodRefinementPctTotal> = emptyMap(),
    @ProtoNumber(153) val heavenlyTrialState: SerializableHeavenlyTrialSaveData? = null,
    @ProtoNumber(154) val signInState: SerializableSignInState? = null,
    @ProtoNumber(155) val aiSectPersonalities: Map<String, String> = emptyMap(),
    @ProtoNumber(156) val activeAttackWarnings: List<SerializableAttackWarning> = emptyList(),
    @ProtoNumber(157) val sectBattleRecords: List<SerializableSectBattleRecord> = emptyList(),
    @ProtoNumber(158) val yearlyReports: List<SerializableYearlyReport> = emptyList(),
    @ProtoNumber(159) val autoBuyList: List<SerializableAutoBuyEntry> = emptyList(),
    @ProtoNumber(211) val watchedItemIds: List<String> = emptyList()
)

@Serializable
data class SerializableGameEventRecord(
    @ProtoNumber(1) val timestamp: Long = 0L,
    @ProtoNumber(2) val year: Int = 1,
    @ProtoNumber(3) val month: Int = 1,
    @ProtoNumber(4) val phase: Int = 0,
    @ProtoNumber(5) val category: String = "SECT",
    @ProtoNumber(6) val eventType: String = "",
    @ProtoNumber(7) val summary: String = "",
    @ProtoNumber(8) val relatedEntityId: String = "",
    @ProtoNumber(9) val relatedEntityName: String = ""
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
    @ProtoNumber(100) val surname: String = "",
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
    @ProtoNumber(81) val totalCultivation: Long,
    @ProtoNumber(82) val breakthroughCount: Int,
    @ProtoNumber(83) val breakthroughFailCount: Int,
    @ProtoNumber(84) val intelligence: Int,
    @ProtoNumber(85) val charm: Int,
    @ProtoNumber(50) val loyalty: Int,
    @ProtoNumber(51) val comprehension: Int,
    @ProtoNumber(52) val artifactRefining: Int,
    @ProtoNumber(53) val pillRefining: Int,
    @ProtoNumber(54) val spiritPlanting: Int,
    @ProtoNumber(86) val mining: Int = 50,
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
    // 丹药服用追踪 — 防重复服用（从 DiscipleComponents @Ignore Set 转换）
    @ProtoNumber(87) val usedPermanentPillKeys: List<String> = emptyList(),
    @ProtoNumber(88) val usedExtendLifePillTypes: List<String> = emptyList(),
    @ProtoNumber(89) val activePillTypes: List<String> = emptyList(),
    @ProtoNumber(77) val hasReviveEffect: Boolean,
    @ProtoNumber(78) val hasClearAllEffect: Boolean,
    @ProtoNumber(79) val currentHp: Int = -1,
    @ProtoNumber(80) val currentMp: Int = -1,
    @ProtoNumber(90) val portraitRes: String = "",
    @ProtoNumber(101) val cultivationCheckpoint: Long = 0L,
    @ProtoNumber(91) val cultivationCheckpointGameMonth: Int = 0,
    @ProtoNumber(92) val autoLearnFromWarehouse: Boolean = false,
    @ProtoNumber(93) val masterId: String = "",
    @ProtoNumber(94) val cultivationCompletionMonth: Int = 0,
    @ProtoNumber(95) val cultivationCompletionPhase: Int = 0,
    @ProtoNumber(96) val manualCompletionMonth: Int = 0,
    @ProtoNumber(97) val manualCompletionPhase: Int = 0,
    @ProtoNumber(98) val equipmentNurturingCompletionMonth: Int = 0,
    @ProtoNumber(99) val equipmentNurturingCompletionPhase: Int = 0,
    @ProtoNumber(102) val childBirthMonth: Int = 0,
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
    @ProtoNumber(9) val obtainedMonth: Int = 1,
    @ProtoNumber(10) val skillName: String = "",
    @ProtoNumber(11) val skillDescription: String = "",
    @ProtoNumber(12) val skillType: String = "attack",
    @ProtoNumber(13) val skillDamageType: String = "physical",
    @ProtoNumber(14) val skillHits: Int = 1,
    @ProtoNumber(15) val skillDamageMultiplier: Double = 1.0,
    @ProtoNumber(16) val skillCooldown: Int = 3,
    @ProtoNumber(17) val skillMpCost: Int = 10,
    @ProtoNumber(18) val skillHealPercent: Double = 0.0,
    @ProtoNumber(19) val skillHealFixed: Int = 0,
    @ProtoNumber(20) val skillHealType: String = "hp",
    @ProtoNumber(21) val skillBuffType: String = "",
    @ProtoNumber(22) val skillBuffValue: Double = 0.0,
    @ProtoNumber(23) val skillBuffDuration: Int = 0,
    @ProtoNumber(24) val skillBuffsJson: String = "",
    @ProtoNumber(25) val skillIsAoe: Boolean = false,
    @ProtoNumber(26) val skillTargetScope: String = "self",
    @ProtoNumber(27) val skillShieldPercent: Double = 0.0,
    @ProtoNumber(28) val skillTurnAdvancePercent: Double = 0.0,
    @ProtoNumber(29) val skillDamageSharePercent: Double = 0.0,
    @ProtoNumber(30) val skillDamageLinkPercent: Double = 0.0,
    @ProtoNumber(31) val minRealm: Int = 9,
    @ProtoNumber(32) val ownerId: String = "",
    @ProtoNumber(33) val isLearned: Boolean = false
)

@Serializable
data class SerializablePillEffect(
    @ProtoNumber(1) val breakthroughChance: Double = 0.0,
    @ProtoNumber(2) val targetRealm: Int = 0,
    @ProtoNumber(3) val isAscension: Boolean = false,
    @ProtoNumber(4) val cultivationSpeedPercent: Double = 0.0,
    @ProtoNumber(5) val skillExpSpeedPercent: Double = 0.0,
    @ProtoNumber(6) val nurtureSpeedPercent: Double = 0.0,
    @ProtoNumber(7) val cultivationAdd: Int = 0,
    @ProtoNumber(8) val skillExpAdd: Int = 0,
    @ProtoNumber(9) val nurtureAdd: Int = 0,
    @ProtoNumber(10) val duration: Int = 3,
    @ProtoNumber(11) val cannotStack: Boolean = true,
    @ProtoNumber(12) val physicalAttackAdd: Int = 0,
    @ProtoNumber(13) val magicAttackAdd: Int = 0,
    @ProtoNumber(14) val physicalDefenseAdd: Int = 0,
    @ProtoNumber(15) val magicDefenseAdd: Int = 0,
    @ProtoNumber(16) val hpAdd: Int = 0,
    @ProtoNumber(17) val mpAdd: Int = 0,
    @ProtoNumber(18) val speedAdd: Int = 0,
    @ProtoNumber(19) val critRateAdd: Double = 0.0,
    @ProtoNumber(20) val critEffectAdd: Double = 0.0,
    @ProtoNumber(21) val extendLife: Int = 0,
    @ProtoNumber(22) val intelligenceAdd: Int = 0,
    @ProtoNumber(23) val charmAdd: Int = 0,
    @ProtoNumber(24) val loyaltyAdd: Int = 0,
    @ProtoNumber(25) val comprehensionAdd: Int = 0,
    @ProtoNumber(26) val artifactRefiningAdd: Int = 0,
    @ProtoNumber(27) val pillRefiningAdd: Int = 0,
    @ProtoNumber(28) val spiritPlantingAdd: Int = 0,
    @ProtoNumber(29) val teachingAdd: Int = 0,
    @ProtoNumber(30) val moralityAdd: Int = 0,
    @ProtoNumber(31) val miningAdd: Int = 0,
    @ProtoNumber(32) val healMaxHpPercent: Double = 0.0,
    @ProtoNumber(33) val mpRecoverMaxMpPercent: Double = 0.0,
    @ProtoNumber(34) val revive: Boolean = false,
    @ProtoNumber(35) val clearAll: Boolean = false
)

@Serializable
data class SerializablePill(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val type: String,
    @ProtoNumber(4) val rarity: Int,
    @ProtoNumber(6) val description: String = "",
    @ProtoNumber(7) val quantity: Int = 1,
    @ProtoNumber(8) val obtainedYear: Int = 1,
    @ProtoNumber(9) val obtainedMonth: Int = 1,
    @ProtoNumber(10) val category: String = "CULTIVATION",
    @ProtoNumber(11) val grade: String = "MEDIUM",
    @ProtoNumber(12) val minRealm: Int = 9,
    @ProtoNumber(13) val isLocked: Boolean = false,
    @ProtoNumber(14) val effects: SerializablePillEffect = SerializablePillEffect(),
    @ProtoNumber(15) val pillType: String = ""
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
    @ProtoNumber(17) val dungeonName: String = "",
    @ProtoNumber(18) val teamId: String = "",
    @ProtoNumber(19) val turns: Int = 0,
    @ProtoNumber(20) val teamCasualties: Int = 0,
    @ProtoNumber(21) val beastsDefeated: Int = 0,
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
data class SerializableStorageBag(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(3) val rarity: Int = 1,
    @ProtoNumber(4) val description: String = "可随机获得5-20件同品阶物品",
    @ProtoNumber(5) val quantity: Int = 1,
    @ProtoNumber(6) val isLocked: Boolean = false
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
    @ProtoNumber(13) val battleLogs: List<SerializableBattleLog> = emptyList(),
    @ProtoNumber(14) val alliances: List<SerializableAlliance> = emptyList(),
    @ProtoNumber(15) val storageBags: List<SerializableStorageBag> = emptyList()
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
    @ProtoNumber(9) val grade: String = "",
    @ProtoNumber(10) val forgetYear: Int = 0,
    @ProtoNumber(11) val forgetMonth: Int = 0,
    @ProtoNumber(12) val forgetPhase: Int = 0
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
    @ProtoNumber(88) val miningAdd: Int = 0,
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
    // @ProtoNumber(14) removed — connectedSectIds no longer used (2026-06-09)
    @ProtoNumber(15) val isOccupied: Boolean,
    @ProtoNumber(16) val occupierTeamId: String = "",
    @ProtoNumber(17) val occupierTeamName: String = "",
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
    @ProtoNumber(33) val isUnderAttack: Boolean,
    @ProtoNumber(34) val attackerSectId: String = "",
    @ProtoNumber(35) val occupierSectId: String = "",
    @ProtoNumber(36) val warehouse: SerializableSectWarehouse = SerializableSectWarehouse(),
    @ProtoNumber(37) val giftPreference: String = "NONE",
    @ProtoNumber(38) val garrisonSlots: List<SerializableGarrisonSlot> = emptyList(),
    @ProtoNumber(39) val occupierBattleTeamId: String = "",
)

@Serializable
data class SerializableGarrisonSlot(
    @ProtoNumber(1) val buildingInstanceId: String = "",
    @ProtoNumber(2) val discipleId: String = "",
    @ProtoNumber(3) val discipleName: String = "",
    @ProtoNumber(4) val sectId: String = "",
    @ProtoNumber(5) val slotIndex: Int = 0
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
    @ProtoNumber(12) val giftPreference: String = "NONE",
    @ProtoNumber(13) val portraitRes: String = "",
)

@Serializable
data class SerializableSectWarehouse(
    @ProtoNumber(1) val items: List<SerializableWarehouseItem> = emptyList(),
    @ProtoNumber(2) val spiritStones: Long = 0,
    @ProtoNumber(3) val midGradeSpiritStones: Long = 0L,
    @ProtoNumber(4) val highGradeSpiritStones: Long = 0L,
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
    @ProtoNumber(6) val price: Long,
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
    @ProtoNumber(8) val expectedYield: Int
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
    @ProtoNumber(10) val discovered: Boolean,
    @ProtoNumber(11) val spawnYear: Int = 1,
    @ProtoNumber(12) val spawnMonth: Int = 1,
    @ProtoNumber(13) val expiryYear: Int = 1,
    @ProtoNumber(14) val expiryMonth: Int = 1,
    @ProtoNumber(15) val exploredByTeamId: String = "",
    @ProtoNumber(16) val status: String = "AVAILABLE",
    @ProtoNumber(17) val canOperate: Boolean = true,
    @ProtoNumber(18) val isOwned: Boolean = false,
    @ProtoNumber(19) val connectedSects: List<String> = emptyList(),
    @ProtoNumber(20) val mineSlots: List<SerializableMineSlot> = emptyList(),
    @ProtoNumber(21) val occupationTime: Long = 0
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
data class SerializableAIRandomEquipment(
    @ProtoNumber(1) val slot: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val rarity: Int,
    @ProtoNumber(4) val nurtureLevel: Int,
    @ProtoNumber(5) val physicalAttack: Int = 0,
    @ProtoNumber(6) val magicAttack: Int = 0,
    @ProtoNumber(7) val physicalDefense: Int = 0,
    @ProtoNumber(8) val magicDefense: Int = 0,
    @ProtoNumber(9) val speed: Int = 0,
    @ProtoNumber(10) val hp: Int = 0,
    @ProtoNumber(11) val mp: Int = 0
)

@Serializable
data class SerializableAIRandomManual(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val rarity: Int,
    @ProtoNumber(3) val mastery: Int,
    @ProtoNumber(4) val stats: Map<String, Int> = emptyMap()
)

@Serializable
data class SerializableAICaveDisciple(
    @ProtoNumber(1) val id: String = "",
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(3) val realm: Int = 5,
    @ProtoNumber(4) val realmName: String = "",
    @ProtoNumber(5) val hp: Int = 1000,
    @ProtoNumber(6) val maxHp: Int = 1000,
    @ProtoNumber(7) val mp: Int = 500,
    @ProtoNumber(8) val maxMp: Int = 500,
    @ProtoNumber(9) val physicalAttack: Int = 100,
    @ProtoNumber(10) val magicAttack: Int = 50,
    @ProtoNumber(11) val physicalDefense: Int = 50,
    @ProtoNumber(12) val magicDefense: Int = 40,
    @ProtoNumber(13) val speed: Int = 100,
    @ProtoNumber(14) val critRate: Double = 0.05,
    @ProtoNumber(15) val equipments: List<SerializableAIRandomEquipment> = emptyList(),
    @ProtoNumber(16) val manuals: List<SerializableAIRandomManual> = emptyList()
)

@Serializable
data class SerializableAICaveTeam(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val sectId: String,
    @ProtoNumber(3) val sectName: String,
    @ProtoNumber(4) val targetCaveId: String,
    @ProtoNumber(5) val disciples: List<SerializableAICaveDisciple> = emptyList(),
    @ProtoNumber(6) val status: String,
    @ProtoNumber(7) val startYear: Int,
    @ProtoNumber(8) val startMonth: Int,
    @ProtoNumber(9) val memberCount: Int = 5,
    @ProtoNumber(10) val avgRealm: Int = 5,
    @ProtoNumber(11) val avgRealmName: String = "",
    @ProtoNumber(12) val caveName: String = "",
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
    @ProtoNumber(12) val innerElder: String = "",
    @ProtoNumber(23) val recruitingElder: String = "",
    @ProtoNumber(13) val qingyunPreachingElder: String = "",
    @ProtoNumber(14) val qingyunPreachingMasters: List<SerializableDirectDiscipleSlot> = emptyList(),
    @ProtoNumber(15) val herbGardenDisciples: List<SerializableDirectDiscipleSlot> = emptyList(),
    @ProtoNumber(16) val alchemyDisciples: List<SerializableDirectDiscipleSlot> = emptyList(),
    @ProtoNumber(17) val forgeDisciples: List<SerializableDirectDiscipleSlot> = emptyList(),
    @ProtoNumber(22) val spiritMineDeaconDisciples: List<SerializableDirectDiscipleSlot> = emptyList()
)

@Serializable
data class SerializableDirectDiscipleSlot(
    @ProtoNumber(1) val index: Int,
    @ProtoNumber(2) val discipleId: String = "",
    @ProtoNumber(3) val discipleName: String,
    @ProtoNumber(4) val discipleRealm: String,
    @ProtoNumber(5) val discipleSpiritRootColor: String,
    @ProtoNumber(6) val sectId: String = ""
)

@Serializable
data class SerializableSpiritMineSlot(
    @ProtoNumber(1) val index: Int,
    @ProtoNumber(2) val discipleId: String = "",
    @ProtoNumber(3) val discipleName: String,
    @ProtoNumber(4) val output: Int,
    @ProtoNumber(5) val sectId: String = "",
    @ProtoNumber(7) val buildingInstanceId: String = "",
    @ProtoNumber(8) val consecutiveMiningMonths: Int = 0,
)

@Serializable
data class SerializableLibrarySlot(
    @ProtoNumber(1) val index: Int,
    @ProtoNumber(2) val discipleId: String = "",
    @ProtoNumber(3) val discipleName: String,
    @ProtoNumber(7) val buildingInstanceId: String = "",
)

@Serializable
data class SerializableResidenceSlot(
    @ProtoNumber(1) val buildingInstanceId: String = "",
    @ProtoNumber(2) val slotIndex: Int = 0,
    @ProtoNumber(3) val discipleId: String = "",
    @ProtoNumber(4) val discipleName: String = ""
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
    @ProtoNumber(16) val outputItemRarity: Int,
    @ProtoNumber(21) val buildingInstanceId: String = "",
    @ProtoNumber(22) val baseDuration: Int = 0,
    @ProtoNumber(23) val requiredMaterials: List<SerializableMaterial> = emptyList(),
    @ProtoNumber(24) val outputItemSlot: String = "",
    @ProtoNumber(17) val expectedYield: Int = 0,
    @ProtoNumber(18) val autoRestartEnabled: Boolean = false,
    @ProtoNumber(19) val completionMonth: Int = 0,
    @ProtoNumber(20) val completionPhase: Int = 0,
)

@Serializable
data class SerializableSectRelation(
    @ProtoNumber(1) val sectId1: String,
    @ProtoNumber(2) val sectId2: String,
    @ProtoNumber(3) val favor: Int,
    @ProtoNumber(4) val lastInteractionYear: Int,
    @ProtoNumber(5) val noGiftYears: Int,
    @ProtoNumber(6) val acquainted: Boolean = false
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
    @ProtoNumber(10) val autoForge: Boolean = false,
    @ProtoNumber(11) val autoMineFocused: Boolean = false,
    @ProtoPacked
    @ProtoNumber(12) val autoMineRootCounts: List<Int> = emptyList(),
    @ProtoNumber(13) val autoMineThreshold: Int = 1,
    @ProtoNumber(14) val autoPlantFocused: Boolean = false,
    @ProtoPacked
    @ProtoNumber(15) val autoPlantRootCounts: List<Int> = emptyList(),
    @ProtoNumber(16) val autoPlantThreshold: Int = 1,
    @ProtoNumber(17) val autoAlchemyFocused: Boolean = false,
    @ProtoPacked
    @ProtoNumber(18) val autoAlchemyRootCounts: List<Int> = emptyList(),
    @ProtoNumber(19) val autoAlchemyThreshold: Int = 1,
    @ProtoNumber(20) val autoForgeFocused: Boolean = false,
    @ProtoPacked
    @ProtoNumber(21) val autoForgeRootCounts: List<Int> = emptyList(),
    @ProtoNumber(22) val autoForgeThreshold: Int = 1,
    @ProtoNumber(23) val autoSingleResidenceFocused: Boolean = false,
    @ProtoPacked
    @ProtoNumber(24) val autoSingleResidenceRootCounts: List<Int> = emptyList(),
    @ProtoNumber(25) val autoSingleResidenceThreshold: Int = 1,
    @ProtoNumber(26) val autoMultiResidenceFocused: Boolean = false,
    @ProtoPacked
    @ProtoNumber(27) val autoMultiResidenceRootCounts: List<Int> = emptyList(),
    @ProtoNumber(28) val autoMultiResidenceThreshold: Int = 1,
    // 新增10项政策（v4.0.66+）
    @ProtoNumber(29) val openRecruitment: Boolean = false,
    @ProtoNumber(30) val asceticTraining: Boolean = false,
    @ProtoNumber(31) val curfew: Boolean = false,
    @ProtoNumber(32) val rewardPunish: Boolean = false,
    @ProtoNumber(33) val strictTraining: Boolean = false,
    @ProtoNumber(34) val relaxedMgmt: Boolean = false,
    @ProtoNumber(35) val spiritSpring: Boolean = false,
    @ProtoNumber(36) val frugality: Boolean = false,
    @ProtoNumber(37) val moralEducation: Boolean = false,
    @ProtoNumber(38) val benevolentGovernance: Boolean = false
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
    @ProtoNumber(3) val attackerType: String,
    @ProtoNumber(4) val targetId: String,
    @ProtoNumber(5) val targetName: String,
    @ProtoNumber(6) val skillName: String,
    @ProtoNumber(7) val damage: Int,
    @ProtoNumber(8) val isCritical: Boolean,
    @ProtoNumber(9) val effect: String,
    @ProtoNumber(10) val type: String = "",
    @ProtoNumber(11) val damageType: String = "",
    @ProtoNumber(12) val isKill: Boolean = false
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
    @ProtoNumber(8) val maxMp: Int = 0,
    @ProtoNumber(9) val portraitRes: String = ""
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
    @ProtoNumber(33) val seedMaxRarity: Int = 1,
    @ProtoNumber(36) val pillCountMin: Int = 0,
    @ProtoNumber(37) val pillCountMax: Int = 0,
    @ProtoNumber(38) val pillMinRarity: Int = 1,
    @ProtoNumber(39) val pillMaxRarity: Int = 1,
    @ProtoNumber(40) val equipmentChance: Double = 0.0,
    @ProtoNumber(41) val equipmentMinRarity: Int = 1,
    @ProtoNumber(42) val equipmentMaxRarity: Int = 1,
    @ProtoNumber(43) val manualChance: Double = 0.0,
    @ProtoNumber(44) val manualMinRarity: Int = 1,
    @ProtoNumber(45) val manualMaxRarity: Int = 1,
    @ProtoNumber(46) val baseSpiritStones: Int = 0,
    @ProtoNumber(47) val baseMaterialCountMin: Int = 0,
    @ProtoNumber(48) val baseMaterialCountMax: Int = 0,
    @ProtoNumber(49) val baseMaterialMinRarity: Int = 1,
    @ProtoNumber(50) val baseMaterialMaxRarity: Int = 1
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
    @ProtoNumber(32) val seedMaxRarity: Int = 1,
    @ProtoNumber(36) val pillCountMin: Int = 0,
    @ProtoNumber(37) val pillCountMax: Int = 0,
    @ProtoNumber(38) val pillMinRarity: Int = 1,
    @ProtoNumber(39) val pillMaxRarity: Int = 1,
    @ProtoNumber(40) val equipmentChance: Double = 0.0,
    @ProtoNumber(41) val equipmentMinRarity: Int = 1,
    @ProtoNumber(42) val equipmentMaxRarity: Int = 1,
    @ProtoNumber(43) val manualChance: Double = 0.0,
    @ProtoNumber(44) val manualMinRarity: Int = 1,
    @ProtoNumber(45) val manualMaxRarity: Int = 1,
    @ProtoNumber(46) val baseSpiritStones: Int = 0,
    @ProtoNumber(47) val baseMaterialCountMin: Int = 0,
    @ProtoNumber(48) val baseMaterialCountMax: Int = 0,
    @ProtoNumber(49) val baseMaterialMinRarity: Int = 1,
    @ProtoNumber(50) val baseMaterialMaxRarity: Int = 1
)

// ==================== 新增 Serializable 包装类 ====================

@Serializable
data class SerializableWorldLevel(
    @ProtoNumber(1) val id: String = "",
    @ProtoNumber(2) val type: String = "BEAST",
    @ProtoNumber(3) val beastType: Int = -1,
    @ProtoNumber(4) val realm: Int = 9,
    @ProtoNumber(5) val realmLayer: Int = 1,
    @ProtoNumber(6) val beastName: String = "",
    @ProtoNumber(7) val guardianName: String = "",
    @ProtoNumber(8) val caveName: String = "",
    @ProtoNumber(9) val x: Float = 0f,
    @ProtoNumber(10) val y: Float = 0f,
    @ProtoNumber(11) val spawnYear: Int = 1,
    @ProtoNumber(12) val spawnMonth: Int = 1,
    @ProtoNumber(13) val expiryYear: Int = 1,
    @ProtoNumber(14) val expiryMonth: Int = 1,
    @ProtoNumber(15) val count: Int = 5,
    @ProtoNumber(16) val caveImageIndex: Int = 0,
    @ProtoNumber(17) val defeated: Boolean = false,
    @ProtoNumber(18) val beastMaxHp: Int = 0,
    @ProtoNumber(19) val beastMaxMp: Int = 0,
    @ProtoNumber(20) val beastPhysicalAttack: Int = 0,
    @ProtoNumber(21) val beastMagicAttack: Int = 0,
    @ProtoNumber(22) val beastPhysicalDefense: Int = 0,
    @ProtoNumber(23) val beastMagicDefense: Int = 0,
    @ProtoNumber(24) val beastSpeed: Int = 0
)

@Serializable
data class SerializableSpiritFieldPlant(
    @ProtoNumber(1) val buildingInstanceId: String = "",
    @ProtoNumber(2) val seedId: String = "",
    @ProtoNumber(3) val seedName: String = "",
    @ProtoNumber(4) val growTime: Int = 0,
    @ProtoNumber(5) val expectedYield: Int = 0,
    @ProtoNumber(6) val plantYear: Int = 0,
    @ProtoNumber(7) val plantMonth: Int = 0,
    @ProtoNumber(8) val sectId: String = "",
    @ProtoNumber(9) val completionMonth: Int = 0,
    @ProtoNumber(10) val completionPhase: Int = 1
)

@Serializable
data class SerializablePatrolSlot(
    @ProtoNumber(1) val index: Int = 0,
    @ProtoNumber(2) val discipleId: String = "",
    @ProtoNumber(3) val discipleName: String = "",
    @ProtoNumber(4) val discipleRealm: String = "",
    @ProtoNumber(5) val portraitRes: String = "",
    @ProtoNumber(6) val buildingInstanceId: String = ""
)

@Serializable
data class SerializablePatrolConfig(
    @ProtoPacked @ProtoNumber(1) val targetRealms: List<Int> = emptyList(),
    @ProtoNumber(2) val maxBeastCount: Int = 1,
    @ProtoNumber(3) val requireFullStatus: Boolean = true
)

@Serializable
data class SerializableWarehouseGarrisonSlot(
    @ProtoNumber(1) val buildingInstanceId: String = "",
    @ProtoNumber(2) val discipleId: String = "",
    @ProtoNumber(3) val discipleName: String = "",
    @ProtoNumber(4) val sectId: String = "",
    @ProtoNumber(5) val slotIndex: Int = 0
)

@Serializable
data class SerializableVassalContract(
    @ProtoNumber(1) val vassalSectId: String = "",
    @ProtoNumber(2) val establishedYear: Int = 0,
    @ProtoNumber(3) val lastTributeYear: Int = 0
)

@Serializable
data class SerializableMailClaimRecord(
    @ProtoNumber(1) val mailId: String = "",
    @ProtoNumber(2) val claimedAt: Long = 0L,
    @ProtoNumber(3) val source: String = ""
)

@Serializable
data class SerializableSectLevelClaimRecord(
    @ProtoNumber(1) val level: Int = 0,
    @ProtoNumber(2) val claimedAtEpochMs: Long = 0L
)

@Serializable
data class SerializableBloodRefinementProgress(
    @ProtoNumber(1) val discipleId: String = "",
    @ProtoNumber(2) val discipleName: String = "",
    @ProtoNumber(3) val materialId: String = "",
    @ProtoNumber(4) val materialName: String = "",
    @ProtoNumber(5) val startYear: Int = 0,
    @ProtoNumber(6) val startMonth: Int = 0,
    @ProtoNumber(7) val durationMonths: Int = 0,
    @ProtoNumber(8) val selectedStat: String = "",
    @ProtoNumber(9) val bonusPercent: Double = 0.0
)

@Serializable
data class SerializableBloodRefinementBonusTotal(
    @ProtoNumber(1) val discipleId: String = "",
    @ProtoNumber(2) val hpBonus: Int = 0,
    @ProtoNumber(3) val physicalAttackBonus: Int = 0,
    @ProtoNumber(4) val magicAttackBonus: Int = 0,
    @ProtoNumber(5) val physicalDefenseBonus: Int = 0,
    @ProtoNumber(6) val magicDefenseBonus: Int = 0,
    @ProtoNumber(7) val speedBonus: Int = 0
)

@Serializable
data class SerializableBloodRefinementPctTotal(
    @ProtoNumber(1) val discipleId: String = "",
    @ProtoNumber(2) val hpBonusPct: Double = 0.0,
    @ProtoNumber(3) val physicalAttackBonusPct: Double = 0.0,
    @ProtoNumber(4) val magicAttackBonusPct: Double = 0.0,
    @ProtoNumber(5) val physicalDefenseBonusPct: Double = 0.0,
    @ProtoNumber(6) val magicDefenseBonusPct: Double = 0.0,
    @ProtoNumber(7) val speedBonusPct: Double = 0.0
)

@Serializable
data class SerializableHeavenlyTrialSaveData(
    @ProtoNumber(1) val highestClearedLevel: Int = -1,
    @ProtoPacked @ProtoNumber(2) val levelClearCounts: List<Int> = emptyList(),
    @ProtoPacked @ProtoNumber(3) val phase1ClearedLevels: List<Int> = emptyList(),
    @ProtoPacked @ProtoNumber(4) val phase2ClearedLevels: List<Int> = emptyList(),
    @ProtoPacked @ProtoNumber(5) val claimedRewardLevels: List<Int> = emptyList()
)

@Serializable
data class SerializableSignInState(
    @ProtoPacked @ProtoNumber(1) val claimedDays: List<Int> = emptyList(),
    @ProtoNumber(2) val currentMonth: Int = 0,
    @ProtoNumber(3) val currentYear: Int = 0,
    @ProtoPacked @ProtoNumber(4) val claimedMilestones: List<Int> = emptyList()
)

@Serializable
data class SerializableAttackWarning(
    @ProtoNumber(1) val warningId: String = "",
    @ProtoNumber(2) val attackerSectId: String = "",
    @ProtoNumber(3) val attackerSectName: String = "",
    @ProtoNumber(4) val stage: String = "DENUNCIATION",
    @ProtoNumber(5) val attackMonth: Int = 0,
    @ProtoNumber(6) val createdAtMonth: Int = 0
)

@Serializable
data class SerializableSectBattleRecord(
    @ProtoNumber(1) val year: Int = 0,
    @ProtoNumber(2) val type: String = "CONQUEST"
)

@Serializable
data class SerializableYearlyReport(
    @ProtoNumber(1) val year: Int = 0,
    @ProtoNumber(2) val totalIncome: Long = 0L,
    @ProtoNumber(3) val totalExpenditure: Long = 0L,
    @ProtoNumber(4) val incomeBySource: Map<String, Long> = emptyMap(),
    @ProtoNumber(5) val expenditureByReason: Map<String, Long> = emptyMap(),
    @ProtoNumber(6) val forgeCompleted: Int = 0,
    @ProtoNumber(7) val alchemyCompleted: Int = 0,
    @ProtoNumber(8) val herbsHarvested: Int = 0,
    @ProtoNumber(9) val equipmentBySource: Map<String, Int> = emptyMap(),
    @ProtoNumber(10) val pillBySource: Map<String, Int> = emptyMap(),
    @ProtoNumber(11) val herbBySource: Map<String, Int> = emptyMap(),
    @ProtoNumber(12) val newDisciples: Int = 0,
    @ProtoNumber(13) val deceasedDisciples: Int = 0,
    @ProtoNumber(14) val desertedDisciples: Int = 0
)

@Serializable
data class SerializableBattleResultUIData(
    @ProtoNumber(1) val battleLogId: String = "",
    @ProtoNumber(2) val victory: Boolean = false,
    @ProtoNumber(3) val teamMembers: List<SerializableBattleLogMember> = emptyList(),
    @ProtoNumber(4) val rewards: List<SerializableBattleRewardItem> = emptyList(),
    @ProtoNumber(5) val lootedItems: List<SerializableBattleRewardItem> = emptyList(),
    @ProtoNumber(6) val isBeastDefense: Boolean = false
)

@Serializable
data class SerializableBattleRewardItem(
    @ProtoNumber(1) val itemId: String = "",
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(3) val quantity: Int = 0,
    @ProtoNumber(4) val rarity: Int = 1,
    @ProtoNumber(5) val type: String = ""
)

@Serializable
data class SerializableAutoBuyEntry(
    @ProtoNumber(1) val itemName: String = "",
    @ProtoNumber(2) val itemType: String = "",
    @ProtoNumber(3) val rarity: Int = 1
)

@Serializable
data class MetadataFile(
    @ProtoNumber(1) val version: String,
    @ProtoNumber(2) val slots: Map<Int, SerializableSaveSlot>
)

