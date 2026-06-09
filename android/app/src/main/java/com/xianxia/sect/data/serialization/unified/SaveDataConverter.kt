package com.xianxia.sect.data.serialization.unified

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.ProductionSlot
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
            monthlySalary = gameData.monthlySalary ?: emptyMap(),
            monthlySalaryEnabled = gameData.monthlySalaryEnabled ?: emptyMap(),
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
            merchantAcquisitionLastRefreshYear = gameData.merchantAcquisitionLastRefreshYear ?: 0
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
            monthlySalary = data.monthlySalary,
            monthlySalaryEnabled = data.monthlySalaryEnabled,
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
            activeMissions = data.activeMissions.map { manualConverter.convertBackActiveMission(it) },
            availableMissions = data.availableMissions.map { manualConverter.convertBackMission(it) },
            aiSectDisciples = data.aiSectDisciples.associate { entry ->
                entry.sectId to entry.disciples.map { discipleConverter.convertBackDisciple(it) }
            },
            spiritMineExpansions = data.spiritMineExpansions,
            merchantAcquisitionItems = data.merchantAcquisitionItems.map { manualConverter.convertBackMerchantItem(it) },
            merchantAcquisitionLastRefreshYear = data.merchantAcquisitionLastRefreshYear
        )
    }
}
