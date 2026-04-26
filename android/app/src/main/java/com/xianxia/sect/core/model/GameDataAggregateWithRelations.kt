package com.xianxia.sect.core.model

import androidx.room.Embedded
import androidx.room.Relation

data class GameDataAggregateWithRelations(
    @Embedded val core: GameDataCore,

    @Relation(parentColumn = "slot_id", entityColumn = "slot_id")
    val worldMap: GameDataWorldMap? = null,

    @Relation(parentColumn = "slot_id", entityColumn = "slot_id")
    val buildings: GameDataBuildings? = null,

    @Relation(parentColumn = "slot_id", entityColumn = "slot_id")
    val economy: GameDataEconomy? = null,

    @Relation(parentColumn = "slot_id", entityColumn = "slot_id")
    val organization: GameDataOrganization? = null,

    @Relation(parentColumn = "slot_id", entityColumn = "slot_id")
    val exploration: GameDataExploration? = null
) {
    fun toGameData(): GameData {
        val wm = worldMap
        val bd = buildings
        val ec = economy
        val org = organization
        val exp = exploration
        return GameData(
            id = core.id,
            slotId = core.slotId,
            sectName = core.sectName,
            currentSlot = core.currentSlot,
            gameYear = core.gameYear,
            gameMonth = core.gameMonth,
            gameDay = core.gameDay,
            isGameStarted = core.isGameStarted,
            gameSpeed = core.gameSpeed,
            spiritStones = core.spiritStones,
            spiritHerbs = core.spiritHerbs,
            sectCultivation = core.sectCultivation,
            autoSaveIntervalMonths = core.autoSaveIntervalMonths,
            monthlySalary = core.monthlySalary,
            monthlySalaryEnabled = core.monthlySalaryEnabled,
            playerProtectionEnabled = core.playerProtectionEnabled,
            playerProtectionStartYear = core.playerProtectionStartYear,
            playerHasAttackedAI = core.playerHasAttackedAI,
            playerAllianceSlots = core.playerAllianceSlots,
            smartBattleEnabled = core.smartBattleEnabled,
            lastSaveTime = core.lastSaveTime,
            isGameOver = core.isGameOver,
            worldMapSects = wm?.worldMapSects ?: emptyList(),
            sectDetails = wm?.sectDetails ?: emptyMap(),
            exploredSects = wm?.exploredSects ?: emptyMap(),
            scoutInfo = wm?.scoutInfo ?: emptyMap(),
            sectRelations = wm?.sectRelations ?: emptyList(),
            productionSlots = bd?.productionSlots ?: emptyList(),
            spiritMineSlots = bd?.spiritMineSlots ?: emptyList(),
            librarySlots = bd?.librarySlots ?: emptyList(),
            travelingMerchantItems = ec?.travelingMerchantItems ?: emptyList(),
            merchantLastRefreshYear = ec?.merchantLastRefreshYear ?: 0,
            merchantRefreshCount = ec?.merchantRefreshCount ?: 0,
            playerListedItems = ec?.playerListedItems ?: emptyList(),
            elderSlots = org?.elderSlots ?: ElderSlots(),
            alliances = org?.alliances ?: emptyList(),
            battleTeam = org?.battleTeam,
            aiBattleTeams = org?.aiBattleTeams ?: emptyList(),
            sectPolicies = org?.sectPolicies ?: SectPolicies(),
            activeMissions = org?.activeMissions ?: emptyList(),
            availableMissions = org?.availableMissions ?: emptyList(),
            usedRedeemCodes = org?.usedRedeemCodes ?: emptyList(),
            recruitList = exp?.recruitList ?: emptyList(),
            lastRecruitYear = exp?.lastRecruitYear ?: 0,
            cultivatorCaves = exp?.cultivatorCaves ?: emptyList(),
            caveExplorationTeams = exp?.caveExplorationTeams ?: emptyList(),
            aiCaveTeams = exp?.aiCaveTeams ?: emptyList(),
            unlockedDungeons = exp?.unlockedDungeons ?: emptyList(),
            unlockedRecipes = exp?.unlockedRecipes ?: emptyList(),
            unlockedManuals = exp?.unlockedManuals ?: emptyList(),
            manualProficiencies = exp?.manualProficiencies ?: emptyMap(),
            pendingCompetitionResults = exp?.pendingCompetitionResults ?: emptyList(),
            lastCompetitionYear = exp?.lastCompetitionYear ?: 0
        )
    }

    companion object {
        fun fromGameData(gameData: GameData): GameDataAggregateWithRelations {
            return GameDataAggregateWithRelations(
                core = GameDataCore.fromGameData(gameData),
                worldMap = GameDataWorldMap.fromGameData(gameData),
                buildings = GameDataBuildings.fromGameData(gameData),
                economy = GameDataEconomy.fromGameData(gameData),
                organization = GameDataOrganization.fromGameData(gameData),
                exploration = GameDataExploration.fromGameData(gameData)
            )
        }
    }
}
