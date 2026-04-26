package com.xianxia.sect.core.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.xianxia.sect.core.model.production.ProductionSlot
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "game_data_core",
    primaryKeys = ["id", "slot_id"],
    foreignKeys = [
        ForeignKey(
            entity = GameData::class,
            parentColumns = ["id", "slot_id"],
            childColumns = ["id", "slot_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["slot_id"], unique = true),
        Index(value = ["lastSaveTime"]),
        Index(value = ["gameYear", "gameMonth"]),
        Index(value = ["sectName"]),
        Index(value = ["spiritStones"]),
        Index(value = ["isGameStarted"])
    ]
)
data class GameDataCore(
    @ColumnInfo(name = "id")
    var id: String = "",

    @ColumnInfo(name = "slot_id")
    var slotId: Int = 0,

    var sectName: String = "青云宗",
    var currentSlot: Int = 1,

    var gameYear: Int = 1,
    var gameMonth: Int = 1,
    var gameDay: Int = 1,

    var isGameStarted: Boolean = false,
    var gameSpeed: Int = 1,

    var spiritStones: Long = 1000,
    var spiritHerbs: Int = 0,
    var sectCultivation: Double = 0.0,

    var autoSaveIntervalMonths: Int = 3,

    var monthlySalary: Map<Int, Int> = mapOf(
        9 to 20, 8 to 60, 7 to 100, 6 to 160, 5 to 220,
        4 to 360, 3 to 440, 2 to 560, 1 to 720, 0 to 1000
    ),

    var monthlySalaryEnabled: Map<Int, Boolean> = mapOf(
        9 to true, 8 to true, 7 to true, 6 to true, 5 to true,
        4 to true, 3 to true, 2 to true, 1 to true, 0 to true
    ),

    var playerProtectionEnabled: Boolean = true,
    var playerProtectionStartYear: Int = 1,
    var playerHasAttackedAI: Boolean = false,

    var playerAllianceSlots: Int = 3,

    var smartBattleEnabled: Boolean = false,

    var lastSaveTime: Long = 0L,

    var isGameOver: Boolean = false
) {
    companion object {
        fun fromGameData(gameData: GameData): GameDataCore = GameDataCore(
            id = gameData.id,
            slotId = gameData.slotId,
            sectName = gameData.sectName,
            currentSlot = gameData.currentSlot,
            gameYear = gameData.gameYear,
            gameMonth = gameData.gameMonth,
            gameDay = gameData.gameDay,
            isGameStarted = gameData.isGameStarted,
            gameSpeed = gameData.gameSpeed,
            spiritStones = gameData.spiritStones,
            spiritHerbs = gameData.spiritHerbs,
            sectCultivation = gameData.sectCultivation,
            autoSaveIntervalMonths = gameData.autoSaveIntervalMonths,
            monthlySalary = gameData.monthlySalary,
            monthlySalaryEnabled = gameData.monthlySalaryEnabled,
            playerProtectionEnabled = gameData.playerProtectionEnabled,
            playerProtectionStartYear = gameData.playerProtectionStartYear,
            playerHasAttackedAI = gameData.playerHasAttackedAI,
            playerAllianceSlots = gameData.playerAllianceSlots,
            smartBattleEnabled = gameData.smartBattleEnabled,
            lastSaveTime = gameData.lastSaveTime,
            isGameOver = gameData.isGameOver
        )
    }
}

@Serializable
@Entity(
    tableName = "game_data_world_map",
    primaryKeys = ["id", "slot_id"],
    foreignKeys = [
        ForeignKey(
            entity = GameData::class,
            parentColumns = ["id", "slot_id"],
            childColumns = ["id", "slot_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["slot_id"], unique = true)]
)
data class GameDataWorldMap(
    @ColumnInfo(name = "id")
    var id: String = "",

    @ColumnInfo(name = "slot_id")
    var slotId: Int = 0,

    var worldMapSects: List<WorldSect> = emptyList(),
    var sectDetails: Map<String, SectDetail> = emptyMap(),
    var exploredSects: Map<String, ExploredSectInfo> = emptyMap(),
    var scoutInfo: Map<String, SectScoutInfo> = emptyMap(),
    var sectRelations: List<SectRelation> = emptyList()
) {
    companion object {
        fun fromGameData(gameData: GameData): GameDataWorldMap = GameDataWorldMap(
            id = gameData.id,
            slotId = gameData.slotId,
            worldMapSects = gameData.worldMapSects,
            sectDetails = gameData.sectDetails,
            exploredSects = gameData.exploredSects,
            scoutInfo = gameData.scoutInfo,
            sectRelations = gameData.sectRelations
        )
    }
}

@Serializable
@Entity(
    tableName = "game_data_buildings",
    primaryKeys = ["id", "slot_id"],
    foreignKeys = [
        ForeignKey(
            entity = GameData::class,
            parentColumns = ["id", "slot_id"],
            childColumns = ["id", "slot_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["slot_id"], unique = true)]
)
data class GameDataBuildings(
    @ColumnInfo(name = "id")
    var id: String = "",

    @ColumnInfo(name = "slot_id")
    var slotId: Int = 0,

    var productionSlots: List<ProductionSlot> = emptyList(),
    var spiritMineSlots: List<SpiritMineSlot> = emptyList(),
    var librarySlots: List<LibrarySlot> = emptyList()
) {
    companion object {
        fun fromGameData(gameData: GameData): GameDataBuildings = GameDataBuildings(
            id = gameData.id,
            slotId = gameData.slotId,
            productionSlots = gameData.productionSlots,
            spiritMineSlots = gameData.spiritMineSlots,
            librarySlots = gameData.librarySlots
        )
    }
}

@Serializable
@Entity(
    tableName = "game_data_economy",
    primaryKeys = ["id", "slot_id"],
    foreignKeys = [
        ForeignKey(
            entity = GameData::class,
            parentColumns = ["id", "slot_id"],
            childColumns = ["id", "slot_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["slot_id"], unique = true)]
)
data class GameDataEconomy(
    @ColumnInfo(name = "id")
    var id: String = "",

    @ColumnInfo(name = "slot_id")
    var slotId: Int = 0,

    var travelingMerchantItems: List<MerchantItem> = emptyList(),
    var merchantLastRefreshYear: Int = 0,
    var merchantRefreshCount: Int = 0,
    var playerListedItems: List<MerchantItem> = emptyList()
) {
    companion object {
        fun fromGameData(gameData: GameData): GameDataEconomy = GameDataEconomy(
            id = gameData.id,
            slotId = gameData.slotId,
            travelingMerchantItems = gameData.travelingMerchantItems,
            merchantLastRefreshYear = gameData.merchantLastRefreshYear,
            merchantRefreshCount = gameData.merchantRefreshCount,
            playerListedItems = gameData.playerListedItems
        )
    }
}

@Serializable
@Entity(
    tableName = "game_data_organization",
    primaryKeys = ["id", "slot_id"],
    foreignKeys = [
        ForeignKey(
            entity = GameData::class,
            parentColumns = ["id", "slot_id"],
            childColumns = ["id", "slot_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["slot_id"], unique = true)]
)
data class GameDataOrganization(
    @ColumnInfo(name = "id")
    var id: String = "",

    @ColumnInfo(name = "slot_id")
    var slotId: Int = 0,

    var elderSlots: ElderSlots = ElderSlots(),
    var alliances: List<Alliance> = emptyList(),
    var battleTeam: BattleTeam? = null,
    var aiBattleTeams: List<AIBattleTeam> = emptyList(),
    var sectPolicies: SectPolicies = SectPolicies(),
    var activeMissions: List<ActiveMission> = emptyList(),
    var availableMissions: List<Mission> = emptyList(),
    var usedRedeemCodes: List<String> = emptyList()
) {
    companion object {
        fun fromGameData(gameData: GameData): GameDataOrganization = GameDataOrganization(
            id = gameData.id,
            slotId = gameData.slotId,
            elderSlots = gameData.elderSlots,
            alliances = gameData.alliances,
            battleTeam = gameData.battleTeam,
            aiBattleTeams = gameData.aiBattleTeams,
            sectPolicies = gameData.sectPolicies,
            activeMissions = gameData.activeMissions,
            availableMissions = gameData.availableMissions,
            usedRedeemCodes = gameData.usedRedeemCodes
        )
    }
}

@Serializable
@Entity(
    tableName = "game_data_exploration",
    primaryKeys = ["id", "slot_id"],
    foreignKeys = [
        ForeignKey(
            entity = GameData::class,
            parentColumns = ["id", "slot_id"],
            childColumns = ["id", "slot_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["slot_id"], unique = true)]
)
data class GameDataExploration(
    @ColumnInfo(name = "id")
    var id: String = "",

    @ColumnInfo(name = "slot_id")
    var slotId: Int = 0,

    var recruitList: List<Disciple> = emptyList(),
    var lastRecruitYear: Int = 0,
    var cultivatorCaves: List<CultivatorCave> = emptyList(),
    var caveExplorationTeams: List<CaveExplorationTeam> = emptyList(),
    var aiCaveTeams: List<AICaveTeam> = emptyList(),
    var unlockedDungeons: List<String> = emptyList(),
    var unlockedRecipes: List<String> = emptyList(),
    var unlockedManuals: List<String> = emptyList(),
    var manualProficiencies: Map<String, List<ManualProficiencyData>> = emptyMap(),
    var pendingCompetitionResults: List<CompetitionRankResult> = emptyList(),
    var lastCompetitionYear: Int = 0
) {
    companion object {
        fun fromGameData(gameData: GameData): GameDataExploration = GameDataExploration(
            id = gameData.id,
            slotId = gameData.slotId,
            recruitList = gameData.recruitList,
            lastRecruitYear = gameData.lastRecruitYear,
            cultivatorCaves = gameData.cultivatorCaves,
            caveExplorationTeams = gameData.caveExplorationTeams,
            aiCaveTeams = gameData.aiCaveTeams,
            unlockedDungeons = gameData.unlockedDungeons,
            unlockedRecipes = gameData.unlockedRecipes,
            unlockedManuals = gameData.unlockedManuals,
            manualProficiencies = gameData.manualProficiencies,
            pendingCompetitionResults = gameData.pendingCompetitionResults,
            lastCompetitionYear = gameData.lastCompetitionYear
        )
    }
}
