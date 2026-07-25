package com.xianxia.sect.data.serialization.unified

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.state.BattleResultUIData
import com.xianxia.sect.data.serialization.NullSafeProtoBuf
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SaveDataConverter @Inject constructor() {
    companion object {
        private const val TAG = "SaveDataConverter"
    }

    private val discipleConverter = DiscipleConverter()
    private val equipmentConverter = EquipmentConverter()
    private val manualConverter = ManualConverter()
    private val itemConverter = ItemConverter()

    fun toSerializable(saveData: com.xianxia.sect.data.model.SaveData): SerializableSaveData {
        val gameDataWithSlots = saveData.gameData.copy(
            productionSlots = saveData.productionSlots
        )
        return SerializableSaveData(
            version = saveData.version ?: SchemaVersion.CURRENT.toString(),
            timestamp = saveData.timestamp ?: System.currentTimeMillis(),
            gameData = convertGameData(gameDataWithSlots),
            disciples = saveData.disciples?.map { discipleConverter.convertDisciple(it) } ?: emptyList(),
            equipment = saveData.equipmentInstances?.map { equipmentConverter.convertEquipment(it) } ?: emptyList(),
            manuals = saveData.manualInstances?.map { manualConverter.convertManual(it) } ?: emptyList(),
            pills = saveData.pills?.map { itemConverter.convertPill(it) } ?: emptyList(),
            materials = saveData.materials?.map { itemConverter.convertMaterial(it) } ?: emptyList(),
            herbs = saveData.herbs?.map { itemConverter.convertHerb(it) } ?: emptyList(),
            seeds = saveData.seeds?.map { itemConverter.convertSeed(it) } ?: emptyList(),
            teams = saveData.teams?.map { manualConverter.convertTeam(it) } ?: emptyList(),
            battleLogs = saveData.battleLogs?.map { manualConverter.convertBattleLog(it) } ?: emptyList(),
            alliances = saveData.alliances?.map { manualConverter.convertAlliance(it) } ?: emptyList()
        )
    }

    fun fromSerializable(data: SerializableSaveData): com.xianxia.sect.data.model.SaveData {
        return com.xianxia.sect.data.model.SaveData(
            version = data.version,
            timestamp = data.timestamp,
            gameData = convertBackGameData(data.gameData),
            disciples = data.disciples.map { discipleConverter.convertBackDisciple(it) },
            equipmentStacks = emptyList(),
            equipmentInstances = data.equipment.map { equipmentConverter.convertBackEquipment(it) },
            manualStacks = emptyList(),
            manualInstances = data.manuals.map { manualConverter.convertBackManual(it) },
            pills = data.pills.map { itemConverter.convertBackPill(it) },
            materials = data.materials.map { itemConverter.convertBackMaterial(it) },
            herbs = data.herbs.map { itemConverter.convertBackHerb(it) },
            seeds = data.seeds.map { itemConverter.convertBackSeed(it) },
            teams = data.teams.map { manualConverter.convertBackTeam(it) },
            battleLogs = data.battleLogs.map { manualConverter.convertBackBattleLog(it) },
            alliances = data.alliances.map { manualConverter.convertBackAlliance(it) },
            productionSlots = data.gameData?.productionSlots?.map { manualConverter.convertBackProductionSlot(it) } ?: emptyList()
        )
    }

    private fun convertGameData(gameData: com.xianxia.sect.core.model.GameData?): SerializableGameData {
        if (gameData == null) return SerializableGameData()

        return SerializableGameData(
            sectName = gameData.sectName ?: "青云宗",
            currentSlot = gameData.currentSlot ?: 1,
            gameYear = gameData.gameYear ?: 1,
            gameMonth = gameData.gameMonth ?: 1,
            gamePhase = gameData.gamePhase,
            spiritStones = gameData.spiritStones ?: 1000,
            spiritHerbs = gameData.spiritHerbs ?: 0,
            autoSaveIntervalMonths = gameData.autoSaveIntervalMonths ?: 3,
            yearlySalary = gameData.yearlySalary ?: emptyMap(),
            yearlySalaryEnabled = gameData.yearlySalaryEnabled ?: emptyMap(),
            worldMapSects = gameData.worldMapSects?.map { manualConverter.convertWorldSect(it, gameData.sectDetails?.get(it.id)) } ?: emptyList(),
            sectDetails = gameData.sectDetails?.mapValues { manualConverter.convertSectDetail(it.value) } ?: emptyMap(),
            exploredSects = gameData.exploredSects?.mapValues { manualConverter.convertExploredSectInfo(it.value) } ?: emptyMap(),
            scoutInfo = gameData.scoutInfo?.mapValues { manualConverter.convertSectScoutInfo(it.value) } ?: emptyMap(),
            manualProficiencies = gameData.manualProficiencies?.mapValues {
                it.value.map { prof -> manualConverter.convertManualProficiency(prof) }
            } ?: emptyMap(),
            travelingMerchantItems = gameData.travelingMerchantItems?.map { manualConverter.convertMerchantItem(it) } ?: emptyList(),
            merchantLastRefreshYear = gameData.merchantLastRefreshYear ?: 0,
            merchantRefreshCount = gameData.merchantRefreshCount ?: 0,
            merchantRefreshChances = gameData.merchantRefreshChances ?: 1,
            merchantLastRefreshChanceGrantYear = gameData.merchantLastRefreshChanceGrantYear ?: 0,
            playerListedItems = gameData.playerListedItems?.map { manualConverter.convertMerchantItem(it) } ?: emptyList(),
            recruitList = gameData.recruitList?.map { discipleConverter.convertDisciple(it) } ?: emptyList(),
            lastRecruitYear = gameData.lastRecruitYear ?: 0,
            cultivatorCaves = gameData.cultivatorCaves?.map { manualConverter.convertCultivatorCave(it) } ?: emptyList(),
            caveExplorationTeams = gameData.caveExplorationTeams?.map { manualConverter.convertCaveExplorationTeam(it) } ?: emptyList(),
            aiCaveTeams = gameData.aiCaveTeams?.map { equipmentConverter.convertAICaveTeam(it) } ?: emptyList(),
            unlockedRecipes = gameData.unlockedRecipes ?: emptyList(),
            unlockedManuals = gameData.unlockedManuals ?: emptyList(),
            lastSaveTime = gameData.lastSaveTime ?: 0L,
            elderSlots = manualConverter.convertElderSlots(gameData.elderSlots),
            spiritMineSlots = gameData.spiritMineSlots?.map { manualConverter.convertSpiritMineSlot(it) } ?: emptyList(),
            librarySlots = gameData.librarySlots?.map { manualConverter.convertLibrarySlot(it) } ?: emptyList(),
            residenceSlots = gameData.residenceSlots.map { manualConverter.convertResidenceSlot(it) },
            productionSlots = gameData.productionSlots?.map { manualConverter.convertProductionSlot(it) } ?: emptyList(),
            alliances = gameData.alliances?.map { manualConverter.convertAlliance(it) } ?: emptyList(),
            sectRelations = gameData.sectRelations?.map { manualConverter.convertSectRelation(it) } ?: emptyList(),
            playerAllianceSlots = gameData.playerAllianceSlots ?: 3,
            sectPolicies = manualConverter.convertSectPolicies(gameData.sectPolicies),
            usedRedeemCodes = gameData.usedRedeemCodes ?: emptyList(),
            playerProtectionEnabled = gameData.playerProtectionEnabled ?: true,
            playerProtectionStartYear = gameData.playerProtectionStartYear ?: 1,
            playerHasAttackedAI = gameData.playerHasAttackedAI ?: false,
            openRecruitmentLastPaidMonth = gameData.openRecruitmentLastPaidMonth ?: 0,
            activeMissions = gameData.activeMissions?.map { manualConverter.convertActiveMission(it) } ?: emptyList(),
            availableMissions = gameData.availableMissions?.map { manualConverter.convertMission(it) } ?: emptyList(),
            aiSectDisciples = gameData.aiSectDisciples?.map { (sectId, disciples) ->
                SerializableAiSectDiscipleEntry(
                    sectId = sectId,
                    disciples = disciples.map { discipleConverter.convertDisciple(it) }
                )
            } ?: emptyList(),
            spiritMineExpansions = gameData.spiritMineExpansions,
            merchantAcquisitionItems = gameData.merchantAcquisitionItems?.map { manualConverter.convertMerchantItem(it) } ?: emptyList(),
            merchantAcquisitionLastRefreshYear = gameData.merchantAcquisitionLastRefreshYear ?: 0,
            gameEventRecords = gameData.gameEventRecords?.map { it.toSerializable() } ?: emptyList(),
            // ==================== 新增字段映射 ====================
            midGradeSpiritStones = gameData.midGradeSpiritStones ?: 0L,
            highGradeSpiritStones = gameData.highGradeSpiritStones ?: 0L,
            sectCultivation = gameData.sectCultivation ?: 0.0,
            worldLevelLastRefreshMonth = gameData.worldLevelLastRefreshMonth ?: 0,
            rngStates = gameData.rngStates ?: emptyMap(),
            activeSectId = gameData.activeSectId ?: "",
            saveVersion = gameData.saveVersion ?: 0,
            autoRecruitSpiritRootFilter = gameData.autoRecruitSpiritRootFilter?.toList() ?: emptyList(),
            daoCompanionBannedRootCounts = gameData.daoCompanionBannedRootCounts?.toList() ?: emptyList(),
            daoCompanionConsentRequired = gameData.daoCompanionConsentRequired ?: false,
            patrolBattleResultPopup = gameData.patrolBattleResultPopup ?: false,
            autoSellMidGradeForPurchase = gameData.autoSellMidGradeForPurchase ?: false,
            autoSellHighGradeForPurchase = gameData.autoSellHighGradeForPurchase ?: false,
            showAllAvailableDisciples = gameData.showAllAvailableDisciples ?: false,
            breakthroughAutoPillFocused = gameData.breakthroughAutoPillFocused ?: false,
            breakthroughAutoPillRootCounts = gameData.breakthroughAutoPillRootCounts?.toList() ?: emptyList(),
            autoEquipFromWarehouseFocused = gameData.autoEquipFromWarehouseFocused ?: false,
            autoEquipFromWarehouseRootCounts = gameData.autoEquipFromWarehouseRootCounts?.toList() ?: emptyList(),
            autoLearnFromWarehouseFocused = gameData.autoLearnFromWarehouseFocused ?: false,
            autoLearnFromWarehouseRootCounts = gameData.autoLearnFromWarehouseRootCounts?.toList() ?: emptyList(),
            isGameOver = gameData.isGameOver ?: false,
            bloodRefinements = gameData.bloodRefinements ?: emptyMap(),
            suzerainSectId = gameData.suzerainSectId ?: "",
            lastYearSpiritStoneIncome = gameData.lastYearSpiritStoneIncome ?: 0L,
            shownWarningStageIds = gameData.shownWarningStageIds ?: emptyList(),
            sectAttackCooldowns = gameData.sectAttackCooldowns ?: emptyMap(),
            guideClaimedRewardIds = gameData.guideClaimedRewardIds?.toList() ?: emptyList(),
            guideCounters = gameData.guideCounters ?: emptyMap(),
            mapSeed = gameData.mapSeed ?: 0,
            annualIncomeBySource = gameData.annualIncomeBySource ?: emptyMap(),
            annualExpenditureByReason = gameData.annualExpenditureByReason ?: emptyMap(),
            annualTotalIncome = gameData.annualTotalIncome ?: 0L,
            annualTotalExpenditure = gameData.annualTotalExpenditure ?: 0L,
            annualAlchemyCount = gameData.annualAlchemyCount ?: 0,
            annualForgeCount = gameData.annualForgeCount ?: 0,
            annualHerbCount = gameData.annualHerbCount ?: 0,
            annualNewDisciples = gameData.annualNewDisciples ?: 0,
            annualDeceasedDisciples = gameData.annualDeceasedDisciples ?: 0,
            annualDesertedDisciples = gameData.annualDesertedDisciples ?: 0,
            annualTheftCount = gameData.annualTheftCount ?: 0,
            theftJudgementsThisMonth = gameData.theftJudgementsThisMonth ?: 0,
            annualEquipmentBySource = gameData.annualEquipmentBySource ?: emptyMap(),
            annualPillBySource = gameData.annualPillBySource ?: emptyMap(),
            annualHerbBySource = gameData.annualHerbBySource ?: emptyMap(),
            spiritMineLastSettledMonth = gameData.spiritMineLastSettledMonth ?: 0,
            placedBuildings = gameData.placedBuildings ?: emptyList(),
            worldLevels = gameData.worldLevels?.map { convertWorldLevel(it) } ?: emptyList(),
            spiritFieldPlants = gameData.spiritFieldPlants?.map { convertSpiritFieldPlant(it) } ?: emptyList(),
            patrolSlots = gameData.patrolSlots?.map { convertPatrolSlot(it) } ?: emptyList(),
            patrolConfig = gameData.patrolConfig?.let { convertPatrolConfig(it) },
            patrolConfigs = gameData.patrolConfigs?.map { convertPatrolConfig(it) } ?: emptyList(),
            pendingPatrolBattleResults = gameData.pendingPatrolBattleResults?.map { convertBattleResultUIData(it) } ?: emptyList(),
            warehouseGarrisons = gameData.warehouseGarrisons?.map { convertWarehouseGarrisonSlot(it) } ?: emptyList(),
            vassalContracts = gameData.vassalContracts?.map { convertVassalContract(it) } ?: emptyList(),
            mailRecords = gameData.mailRecords?.map { convertMailClaimRecord(it) } ?: emptyList(),
            sectLevelClaimRecords = gameData.sectLevelClaimRecords?.map { convertSectLevelClaimRecord(it) } ?: emptyList(),
            activeBloodRefinements = gameData.activeBloodRefinements?.mapValues { convertBloodRefinementProgress(it.value) } ?: emptyMap(),
            bloodRefinementBonusTotals = gameData.bloodRefinementBonusTotals?.mapValues { convertBloodRefinementBonusTotal(it.value) } ?: emptyMap(),
            bloodRefinementPctTotals = gameData.bloodRefinementPctTotals?.mapValues { convertBloodRefinementPctTotal(it.value) } ?: emptyMap(),
            heavenlyTrialState = gameData.heavenlyTrialState?.let { convertHeavenlyTrialSaveData(it) },
            signInState = gameData.signInState?.let { convertSignInState(it) },
            aiSectPersonalities = gameData.aiSectPersonalities?.mapValues { it.value.name } ?: emptyMap(),
            activeAttackWarnings = gameData.activeAttackWarnings?.map { convertAttackWarning(it) } ?: emptyList(),
            sectBattleRecords = gameData.sectBattleRecords?.map { convertSectBattleRecord(it) } ?: emptyList(),
            yearlyReports = gameData.yearlyReports?.map { convertYearlyReport(it) } ?: emptyList(),
            autoBuyList = gameData.autoBuyList?.map { convertAutoBuyEntry(it) } ?: emptyList()
        )
    }

    private fun convertBackGameData(data: SerializableGameData): com.xianxia.sect.core.model.GameData {
        return com.xianxia.sect.core.model.GameData(
            sectName = data.sectName,
            currentSlot = data.currentSlot,
            gameYear = data.gameYear,
            gameMonth = data.gameMonth,
            gamePhase = if (data.gamePhase > 2) (data.gamePhase - 1) / 10 else data.gamePhase,
            spiritStones = data.spiritStones,
            spiritHerbs = data.spiritHerbs,
            autoSaveIntervalMonths = data.autoSaveIntervalMonths,
            yearlySalary = data.yearlySalary,
            yearlySalaryEnabled = data.yearlySalaryEnabled,
            worldMapSects = data.worldMapSects.map { manualConverter.convertBackWorldSect(it) },
            sectDetails = if (data.sectDetails.isNotEmpty()) {
                data.sectDetails.mapValues { manualConverter.convertBackSectDetail(it.value) }
            } else {
                data.worldMapSects.associate { it.id to manualConverter.extractSectDetailFromWorldSect(it) }
            },
            exploredSects = data.exploredSects.mapValues { manualConverter.convertBackExploredSectInfo(it.value) },
            scoutInfo = data.scoutInfo.mapValues { manualConverter.convertBackSectScoutInfo(it.value) },
            manualProficiencies = data.manualProficiencies.mapValues {
                it.value.map { prof -> manualConverter.convertBackManualProficiency(prof) }
            },
            travelingMerchantItems = data.travelingMerchantItems.map { manualConverter.convertBackMerchantItem(it) },
            merchantLastRefreshYear = data.merchantLastRefreshYear,
            merchantRefreshCount = data.merchantRefreshCount,
            merchantRefreshChances = data.merchantRefreshChances,
            merchantLastRefreshChanceGrantYear = data.merchantLastRefreshChanceGrantYear,
            playerListedItems = data.playerListedItems.map { manualConverter.convertBackMerchantItem(it) },
            recruitList = data.recruitList.map { discipleConverter.convertBackDisciple(it) },
            lastRecruitYear = data.lastRecruitYear,
            cultivatorCaves = data.cultivatorCaves.map { manualConverter.convertBackCultivatorCave(it) },
            caveExplorationTeams = data.caveExplorationTeams.map { manualConverter.convertBackCaveExplorationTeam(it) },
            aiCaveTeams = data.aiCaveTeams.map { equipmentConverter.convertBackAICaveTeam(it) },
            unlockedRecipes = data.unlockedRecipes,
            unlockedManuals = data.unlockedManuals,
            lastSaveTime = data.lastSaveTime,
            elderSlots = manualConverter.convertBackElderSlots(data.elderSlots),
            spiritMineSlots = data.spiritMineSlots.map { manualConverter.convertBackSpiritMineSlot(it) },
            librarySlots = data.librarySlots.map { manualConverter.convertBackLibrarySlot(it) },
            residenceSlots = data.residenceSlots.map { manualConverter.convertBackResidenceSlot(it) },
            productionSlots = data.productionSlots.map { manualConverter.convertBackProductionSlot(it) },
            alliances = data.alliances.map { manualConverter.convertBackAlliance(it) },
            sectRelations = data.sectRelations.map { manualConverter.convertBackSectRelation(it) },
            playerAllianceSlots = data.playerAllianceSlots,
            sectPolicies = manualConverter.convertBackSectPolicies(data.sectPolicies),
            usedRedeemCodes = data.usedRedeemCodes,
            playerProtectionEnabled = data.playerProtectionEnabled,
            playerProtectionStartYear = data.playerProtectionStartYear,
            playerHasAttackedAI = data.playerHasAttackedAI,
            openRecruitmentLastPaidMonth = data.openRecruitmentLastPaidMonth,
            activeMissions = data.activeMissions.map { manualConverter.convertBackActiveMission(it) },
            availableMissions = data.availableMissions.map { manualConverter.convertBackMission(it) },
            aiSectDisciples = data.aiSectDisciples.associate { entry ->
                entry.sectId to entry.disciples.map { discipleConverter.convertBackDisciple(it) }
            },
            spiritMineExpansions = data.spiritMineExpansions,
            merchantAcquisitionItems = data.merchantAcquisitionItems.map { manualConverter.convertBackMerchantItem(it) },
            merchantAcquisitionLastRefreshYear = data.merchantAcquisitionLastRefreshYear,
            gameEventRecords = data.gameEventRecords.mapNotNull { it.toGameEventRecord() }
                .takeLast(GameConfig.Logs.MAX_EVENT_LOGS),
            // ==================== 新增字段反向映射 ====================
            midGradeSpiritStones = data.midGradeSpiritStones,
            highGradeSpiritStones = data.highGradeSpiritStones,
            sectCultivation = data.sectCultivation,
            worldLevelLastRefreshMonth = data.worldLevelLastRefreshMonth,
            rngStates = data.rngStates,
            activeSectId = data.activeSectId,
            saveVersion = data.saveVersion,
            autoRecruitSpiritRootFilter = data.autoRecruitSpiritRootFilter.toSet(),
            daoCompanionBannedRootCounts = data.daoCompanionBannedRootCounts.toSet(),
            daoCompanionConsentRequired = data.daoCompanionConsentRequired,
            patrolBattleResultPopup = data.patrolBattleResultPopup,
            autoSellMidGradeForPurchase = data.autoSellMidGradeForPurchase,
            autoSellHighGradeForPurchase = data.autoSellHighGradeForPurchase,
            showAllAvailableDisciples = data.showAllAvailableDisciples,
            breakthroughAutoPillFocused = data.breakthroughAutoPillFocused,
            breakthroughAutoPillRootCounts = data.breakthroughAutoPillRootCounts.toSet(),
            autoEquipFromWarehouseFocused = data.autoEquipFromWarehouseFocused,
            autoEquipFromWarehouseRootCounts = data.autoEquipFromWarehouseRootCounts.toSet(),
            autoLearnFromWarehouseFocused = data.autoLearnFromWarehouseFocused,
            autoLearnFromWarehouseRootCounts = data.autoLearnFromWarehouseRootCounts.toSet(),
            isGameOver = data.isGameOver,
            bloodRefinements = data.bloodRefinements,
            suzerainSectId = data.suzerainSectId,
            lastYearSpiritStoneIncome = data.lastYearSpiritStoneIncome,
            shownWarningStageIds = data.shownWarningStageIds,
            sectAttackCooldowns = data.sectAttackCooldowns,
            guideClaimedRewardIds = data.guideClaimedRewardIds.toSet(),
            guideCounters = data.guideCounters,
            mapSeed = data.mapSeed,
            annualIncomeBySource = data.annualIncomeBySource,
            annualExpenditureByReason = data.annualExpenditureByReason,
            annualTotalIncome = data.annualTotalIncome,
            annualTotalExpenditure = data.annualTotalExpenditure,
            annualAlchemyCount = data.annualAlchemyCount,
            annualForgeCount = data.annualForgeCount,
            annualHerbCount = data.annualHerbCount,
            annualNewDisciples = data.annualNewDisciples,
            annualDeceasedDisciples = data.annualDeceasedDisciples,
            annualDesertedDisciples = data.annualDesertedDisciples,
            annualTheftCount = data.annualTheftCount,
            theftJudgementsThisMonth = data.theftJudgementsThisMonth,
            annualEquipmentBySource = data.annualEquipmentBySource,
            annualPillBySource = data.annualPillBySource,
            annualHerbBySource = data.annualHerbBySource,
            spiritMineLastSettledMonth = data.spiritMineLastSettledMonth,
            placedBuildings = data.placedBuildings,
            worldLevels = data.worldLevels.map { convertBackWorldLevel(it) },
            spiritFieldPlants = data.spiritFieldPlants.map { convertBackSpiritFieldPlant(it) },
            patrolSlots = data.patrolSlots.map { convertBackPatrolSlot(it) },
            patrolConfig = data.patrolConfig?.let { convertBackPatrolConfig(it) } ?: PatrolConfig(),
            patrolConfigs = data.patrolConfigs.map { convertBackPatrolConfig(it) },
            pendingPatrolBattleResults = data.pendingPatrolBattleResults.map { convertBackBattleResultUIData(it) },
            warehouseGarrisons = data.warehouseGarrisons.map { convertBackWarehouseGarrisonSlot(it) },
            vassalContracts = data.vassalContracts.map { convertBackVassalContract(it) },
            mailRecords = data.mailRecords.map { convertBackMailClaimRecord(it) },
            sectLevelClaimRecords = data.sectLevelClaimRecords.map { convertBackSectLevelClaimRecord(it) },
            activeBloodRefinements = data.activeBloodRefinements.mapValues { convertBackBloodRefinementProgress(it.value) },
            bloodRefinementBonusTotals = data.bloodRefinementBonusTotals.mapValues { convertBackBloodRefinementBonusTotal(it.value) },
            bloodRefinementPctTotals = data.bloodRefinementPctTotals.mapValues { convertBackBloodRefinementPctTotal(it.value) },
            heavenlyTrialState = data.heavenlyTrialState?.let { convertBackHeavenlyTrialSaveData(it) } ?: HeavenlyTrialSaveData(),
            signInState = data.signInState?.let { convertBackSignInState(it) } ?: SignInState(),
            aiSectPersonalities = data.aiSectPersonalities.mapValues { safeEnumValueOf(it.value, com.xianxia.sect.core.model.AISectPersonality.BALANCED, "aiSectPersonality") },
            activeAttackWarnings = data.activeAttackWarnings.map { convertBackAttackWarning(it) },
            sectBattleRecords = data.sectBattleRecords.map { convertBackSectBattleRecord(it) },
            yearlyReports = data.yearlyReports.map { convertBackYearlyReport(it) },
            autoBuyList = data.autoBuyList.map { convertBackAutoBuyEntry(it) }
        )
    }

    // ==================== 游戏事件记录转换 ====================

    private fun GameEventRecord.toSerializable(): SerializableGameEventRecord =
        SerializableGameEventRecord(
            timestamp = timestamp, year = year, month = month, phase = phase,
            category = category, eventType = eventType, summary = summary,
            relatedEntityId = relatedEntityId,
            relatedEntityName = relatedEntityName
        )

    private fun SerializableGameEventRecord.toGameEventRecord(): GameEventRecord? {
        // 反序列化校验——与 recordGameEvent 入口保持一致的校验逻辑
        if (summary.isBlank() || eventType.isBlank()) return null
        if (summary.length > 200) return null
        if (eventType.length > 50) return null
        if (relatedEntityId.length > 50) return null
        if (relatedEntityName.length > 50) return null
        return GameEventRecord(
            timestamp = timestamp, year = year, month = month, phase = phase,
            category = category, eventType = eventType, summary = summary,
            relatedEntityId = relatedEntityId,
            relatedEntityName = relatedEntityName
        )
    }

    // ==================== 新增字段转换器 ====================

    private fun convertWorldLevel(data: WorldLevel): SerializableWorldLevel = SerializableWorldLevel(
        id = data.id,
        type = data.type.name,
        beastType = data.beastType ?: -1,
        realm = data.realm,
        realmLayer = data.realmLayer,
        beastName = data.beastName,
        guardianName = data.guardianName,
        caveName = data.caveName,
        x = data.x,
        y = data.y,
        spawnYear = data.spawnYear,
        spawnMonth = data.spawnMonth,
        expiryYear = data.expiryYear,
        expiryMonth = data.expiryMonth,
        count = data.count,
        caveImageIndex = data.caveImageIndex,
        defeated = data.defeated,
        beastMaxHp = data.beastMaxHp,
        beastMaxMp = data.beastMaxMp,
        beastPhysicalAttack = data.beastPhysicalAttack,
        beastMagicAttack = data.beastMagicAttack,
        beastPhysicalDefense = data.beastPhysicalDefense,
        beastMagicDefense = data.beastMagicDefense,
        beastSpeed = data.beastSpeed
    )

    private fun convertBackWorldLevel(data: SerializableWorldLevel): WorldLevel = WorldLevel(
        id = data.id,
        type = safeEnumValueOf(data.type, LevelType.BEAST, "type", "WorldLevel"),
        beastType = if (data.beastType >= 0) data.beastType else null,
        realm = data.realm,
        realmLayer = data.realmLayer,
        beastName = data.beastName,
        guardianName = data.guardianName,
        caveName = data.caveName,
        x = data.x,
        y = data.y,
        spawnYear = data.spawnYear,
        spawnMonth = data.spawnMonth,
        expiryYear = data.expiryYear,
        expiryMonth = data.expiryMonth,
        count = data.count,
        caveImageIndex = data.caveImageIndex,
        defeated = data.defeated,
        beastMaxHp = data.beastMaxHp,
        beastMaxMp = data.beastMaxMp,
        beastPhysicalAttack = data.beastPhysicalAttack,
        beastMagicAttack = data.beastMagicAttack,
        beastPhysicalDefense = data.beastPhysicalDefense,
        beastMagicDefense = data.beastMagicDefense,
        beastSpeed = data.beastSpeed
    )

    private fun convertSpiritFieldPlant(data: SpiritFieldPlant): SerializableSpiritFieldPlant = SerializableSpiritFieldPlant(
        buildingInstanceId = data.buildingInstanceId,
        seedId = data.seedId,
        seedName = data.seedName,
        growTime = data.growTime,
        expectedYield = data.expectedYield,
        plantYear = data.plantYear,
        plantMonth = data.plantMonth,
        sectId = data.sectId,
        completionMonth = data.completionMonth,
        completionPhase = data.completionPhase
    )

    private fun convertBackSpiritFieldPlant(data: SerializableSpiritFieldPlant): SpiritFieldPlant = SpiritFieldPlant(
        buildingInstanceId = data.buildingInstanceId,
        seedId = data.seedId,
        seedName = data.seedName,
        growTime = data.growTime,
        expectedYield = data.expectedYield,
        plantYear = data.plantYear,
        plantMonth = data.plantMonth,
        sectId = data.sectId,
        completionMonth = data.completionMonth,
        completionPhase = data.completionPhase
    )

    private fun convertPatrolSlot(data: PatrolSlot): SerializablePatrolSlot = SerializablePatrolSlot(
        index = data.index,
        discipleId = data.discipleId,
        discipleName = data.discipleName,
        discipleRealm = data.discipleRealm,
        portraitRes = data.portraitRes,
        buildingInstanceId = data.buildingInstanceId
    )

    private fun convertBackPatrolSlot(data: SerializablePatrolSlot): PatrolSlot = PatrolSlot(
        index = data.index,
        discipleId = data.discipleId,
        discipleName = data.discipleName,
        discipleRealm = data.discipleRealm,
        portraitRes = data.portraitRes,
        buildingInstanceId = data.buildingInstanceId
    )

    private fun convertPatrolConfig(data: PatrolConfig): SerializablePatrolConfig = SerializablePatrolConfig(
        targetRealms = data.targetRealms.toList(),
        maxBeastCount = data.maxBeastCount,
        requireFullStatus = data.requireFullStatus
    )

    private fun convertBackPatrolConfig(data: SerializablePatrolConfig): PatrolConfig = PatrolConfig(
        targetRealms = data.targetRealms.toSet(),
        maxBeastCount = data.maxBeastCount,
        requireFullStatus = data.requireFullStatus
    )

    private fun convertBattleResultUIData(data: BattleResultUIData): SerializableBattleResultUIData = SerializableBattleResultUIData(
        battleLogId = data.battleLogId,
        victory = data.victory,
        teamMembers = data.teamMembers.map { convertBattleLogMember(it) },
        rewards = data.rewards.map { convertBattleRewardItem(it) },
        lootedItems = data.lootedItems.map { convertBattleRewardItem(it) },
        isBeastDefense = data.isBeastDefense
    )

    private fun convertBackBattleResultUIData(data: SerializableBattleResultUIData): BattleResultUIData = BattleResultUIData(
        battleLogId = data.battleLogId,
        victory = data.victory,
        teamMembers = data.teamMembers.map { convertBackBattleLogMember(it) },
        rewards = data.rewards.map { convertBackBattleRewardItem(it) },
        lootedItems = data.lootedItems.map { convertBackBattleRewardItem(it) },
        isBeastDefense = data.isBeastDefense
    )

    private fun convertBattleLogMember(data: com.xianxia.sect.core.model.BattleLogMember): SerializableBattleLogMember = SerializableBattleLogMember(
        discipleId = data.id,
        name = data.name,
        realm = data.realm,
        isAlive = data.isAlive,
        remainingHp = data.hp,
        maxHp = data.maxHp,
        remainingMp = data.mp,
        maxMp = data.maxMp,
        portraitRes = data.portraitRes
    )

    private fun convertBackBattleLogMember(data: SerializableBattleLogMember): com.xianxia.sect.core.model.BattleLogMember = com.xianxia.sect.core.model.BattleLogMember(
        id = data.discipleId,
        name = data.name,
        realm = data.realm,
        isAlive = data.isAlive,
        hp = data.remainingHp,
        maxHp = data.maxHp,
        mp = data.remainingMp,
        maxMp = data.maxMp,
        portraitRes = data.portraitRes
    )

    private fun convertBattleRewardItem(data: com.xianxia.sect.core.model.BattleRewardItem): SerializableBattleRewardItem = SerializableBattleRewardItem(
        itemId = data.itemId,
        name = data.name,
        quantity = data.quantity,
        rarity = data.rarity,
        type = data.type
    )

    private fun convertBackBattleRewardItem(data: SerializableBattleRewardItem): com.xianxia.sect.core.model.BattleRewardItem = com.xianxia.sect.core.model.BattleRewardItem(
        itemId = data.itemId,
        name = data.name,
        quantity = data.quantity,
        rarity = data.rarity,
        type = data.type
    )

    private fun convertWarehouseGarrisonSlot(data: WarehouseGarrisonSlot): SerializableWarehouseGarrisonSlot = SerializableWarehouseGarrisonSlot(
        buildingInstanceId = data.buildingInstanceId,
        discipleId = data.discipleId,
        discipleName = data.discipleName,
        sectId = data.sectId,
        slotIndex = data.slotIndex
    )

    private fun convertBackWarehouseGarrisonSlot(data: SerializableWarehouseGarrisonSlot): WarehouseGarrisonSlot = WarehouseGarrisonSlot(
        buildingInstanceId = data.buildingInstanceId,
        discipleId = data.discipleId,
        discipleName = data.discipleName,
        sectId = data.sectId,
        slotIndex = data.slotIndex
    )

    private fun convertVassalContract(data: VassalContract): SerializableVassalContract = SerializableVassalContract(
        vassalSectId = data.vassalSectId,
        establishedYear = data.establishedYear,
        lastTributeYear = data.lastTributeYear
    )

    private fun convertBackVassalContract(data: SerializableVassalContract): VassalContract = VassalContract(
        vassalSectId = data.vassalSectId,
        establishedYear = data.establishedYear,
        lastTributeYear = data.lastTributeYear
    )

    private fun convertMailClaimRecord(data: MailClaimRecord): SerializableMailClaimRecord = SerializableMailClaimRecord(
        mailId = data.mailId,
        claimedAt = data.claimedAt,
        source = data.source
    )

    private fun convertBackMailClaimRecord(data: SerializableMailClaimRecord): MailClaimRecord = MailClaimRecord(
        mailId = data.mailId,
        claimedAt = data.claimedAt,
        source = data.source
    )

    private fun convertSectLevelClaimRecord(data: SectLevelClaimRecord): SerializableSectLevelClaimRecord = SerializableSectLevelClaimRecord(
        level = data.level,
        claimedAtEpochMs = data.claimedAtEpochMs
    )

    private fun convertBackSectLevelClaimRecord(data: SerializableSectLevelClaimRecord): SectLevelClaimRecord = SectLevelClaimRecord(
        level = data.level,
        claimedAtEpochMs = data.claimedAtEpochMs
    )

    private fun convertBloodRefinementProgress(data: BloodRefinementProgress): SerializableBloodRefinementProgress = SerializableBloodRefinementProgress(
        discipleId = data.discipleId,
        discipleName = data.discipleName,
        materialId = data.materialId,
        materialName = data.materialName,
        startYear = data.startYear,
        startMonth = data.startMonth,
        durationMonths = data.durationMonths,
        selectedStat = data.selectedStat,
        bonusPercent = data.bonusPercent
    )

    private fun convertBackBloodRefinementProgress(data: SerializableBloodRefinementProgress): BloodRefinementProgress = BloodRefinementProgress(
        discipleId = data.discipleId,
        discipleName = data.discipleName,
        materialId = data.materialId,
        materialName = data.materialName,
        startYear = data.startYear,
        startMonth = data.startMonth,
        durationMonths = data.durationMonths,
        selectedStat = data.selectedStat,
        bonusPercent = data.bonusPercent
    )

    private fun convertBloodRefinementBonusTotal(data: BloodRefinementBonusTotal): SerializableBloodRefinementBonusTotal = SerializableBloodRefinementBonusTotal(
        discipleId = data.discipleId,
        hpBonus = data.hpBonus,
        physicalAttackBonus = data.physicalAttackBonus,
        magicAttackBonus = data.magicAttackBonus,
        physicalDefenseBonus = data.physicalDefenseBonus,
        magicDefenseBonus = data.magicDefenseBonus,
        speedBonus = data.speedBonus
    )

    private fun convertBackBloodRefinementBonusTotal(data: SerializableBloodRefinementBonusTotal): BloodRefinementBonusTotal = BloodRefinementBonusTotal(
        discipleId = data.discipleId,
        hpBonus = data.hpBonus,
        physicalAttackBonus = data.physicalAttackBonus,
        magicAttackBonus = data.magicAttackBonus,
        physicalDefenseBonus = data.physicalDefenseBonus,
        magicDefenseBonus = data.magicDefenseBonus,
        speedBonus = data.speedBonus
    )

    private fun convertBloodRefinementPctTotal(data: BloodRefinementPctTotal): SerializableBloodRefinementPctTotal = SerializableBloodRefinementPctTotal(
        discipleId = data.discipleId,
        hpBonusPct = data.hpBonusPct,
        physicalAttackBonusPct = data.physicalAttackBonusPct,
        magicAttackBonusPct = data.magicAttackBonusPct,
        physicalDefenseBonusPct = data.physicalDefenseBonusPct,
        magicDefenseBonusPct = data.magicDefenseBonusPct,
        speedBonusPct = data.speedBonusPct
    )

    private fun convertBackBloodRefinementPctTotal(data: SerializableBloodRefinementPctTotal): BloodRefinementPctTotal = BloodRefinementPctTotal(
        discipleId = data.discipleId,
        hpBonusPct = data.hpBonusPct,
        physicalAttackBonusPct = data.physicalAttackBonusPct,
        magicAttackBonusPct = data.magicAttackBonusPct,
        physicalDefenseBonusPct = data.physicalDefenseBonusPct,
        magicDefenseBonusPct = data.magicDefenseBonusPct,
        speedBonusPct = data.speedBonusPct
    )

    private fun convertHeavenlyTrialSaveData(data: HeavenlyTrialSaveData): SerializableHeavenlyTrialSaveData = SerializableHeavenlyTrialSaveData(
        highestClearedLevel = data.highestClearedLevel,
        levelClearCounts = data.levelClearCounts,
        phase1ClearedLevels = data.phase1ClearedLevels,
        phase2ClearedLevels = data.phase2ClearedLevels,
        claimedRewardLevels = data.claimedRewardLevels
    )

    private fun convertBackHeavenlyTrialSaveData(data: SerializableHeavenlyTrialSaveData): HeavenlyTrialSaveData = HeavenlyTrialSaveData(
        highestClearedLevel = data.highestClearedLevel,
        levelClearCounts = data.levelClearCounts,
        phase1ClearedLevels = data.phase1ClearedLevels,
        phase2ClearedLevels = data.phase2ClearedLevels,
        claimedRewardLevels = data.claimedRewardLevels
    )

    private fun convertSignInState(data: SignInState): SerializableSignInState = SerializableSignInState(
        claimedDays = data.claimedDays,
        currentMonth = data.currentMonth,
        currentYear = data.currentYear,
        claimedMilestones = data.claimedMilestones
    )

    private fun convertBackSignInState(data: SerializableSignInState): SignInState = SignInState(
        claimedDays = data.claimedDays,
        currentMonth = data.currentMonth,
        currentYear = data.currentYear,
        claimedMilestones = data.claimedMilestones
    )

    private fun convertAttackWarning(data: AttackWarning): SerializableAttackWarning = SerializableAttackWarning(
        warningId = data.warningId,
        attackerSectId = data.attackerSectId,
        attackerSectName = data.attackerSectName,
        stage = data.stage.name,
        attackMonth = data.attackMonth,
        createdAtMonth = data.createdAtMonth
    )

    private fun convertBackAttackWarning(data: SerializableAttackWarning): AttackWarning = AttackWarning(
        warningId = data.warningId,
        attackerSectId = data.attackerSectId,
        attackerSectName = data.attackerSectName,
        stage = safeEnumValueOf(data.stage, WarningStage.DENUNCIATION, "stage", "AttackWarning"),
        attackMonth = data.attackMonth,
        createdAtMonth = data.createdAtMonth
    )

    private fun convertSectBattleRecord(data: SectBattleRecord): SerializableSectBattleRecord = SerializableSectBattleRecord(
        year = data.year,
        type = data.type.name
    )

    private fun convertBackSectBattleRecord(data: SerializableSectBattleRecord): SectBattleRecord = SectBattleRecord(
        year = data.year,
        type = safeEnumValueOf(data.type, SectBattleType.CONQUEST, "type", "SectBattleRecord")
    )

    private fun convertYearlyReport(data: YearlyReport): SerializableYearlyReport = SerializableYearlyReport(
        year = data.year,
        totalIncome = data.totalIncome,
        totalExpenditure = data.totalExpenditure,
        incomeBySource = data.incomeBySource,
        expenditureByReason = data.expenditureByReason,
        forgeCompleted = data.forgeCompleted,
        alchemyCompleted = data.alchemyCompleted,
        herbsHarvested = data.herbsHarvested,
        equipmentBySource = data.equipmentBySource,
        pillBySource = data.pillBySource,
        herbBySource = data.herbBySource,
        newDisciples = data.newDisciples,
        deceasedDisciples = data.deceasedDisciples,
        desertedDisciples = data.desertedDisciples
    )

    private fun convertBackYearlyReport(data: SerializableYearlyReport): YearlyReport = YearlyReport(
        year = data.year,
        totalIncome = data.totalIncome,
        totalExpenditure = data.totalExpenditure,
        incomeBySource = data.incomeBySource,
        expenditureByReason = data.expenditureByReason,
        forgeCompleted = data.forgeCompleted,
        alchemyCompleted = data.alchemyCompleted,
        herbsHarvested = data.herbsHarvested,
        equipmentBySource = data.equipmentBySource,
        pillBySource = data.pillBySource,
        herbBySource = data.herbBySource,
        newDisciples = data.newDisciples,
        deceasedDisciples = data.deceasedDisciples,
        desertedDisciples = data.desertedDisciples
    )

    private fun convertAutoBuyEntry(data: AutoBuyEntry): SerializableAutoBuyEntry = SerializableAutoBuyEntry(
        itemName = data.itemName,
        itemType = data.itemType,
        rarity = data.rarity
    )

    private fun convertBackAutoBuyEntry(data: SerializableAutoBuyEntry): AutoBuyEntry = AutoBuyEntry(
        itemName = data.itemName,
        itemType = data.itemType,
        rarity = data.rarity
    )
}
