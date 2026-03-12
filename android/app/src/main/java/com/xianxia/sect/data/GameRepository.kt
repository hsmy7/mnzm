package com.xianxia.sect.data

import androidx.room.withTransaction
import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.local.*
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GameRepository @Inject constructor(
    private val database: GameDatabase,
    private val gameDataDao: GameDataDao,
    private val discipleDao: DiscipleDao,
    private val equipmentDao: EquipmentDao,
    private val manualDao: ManualDao,
    private val pillDao: PillDao,
    private val materialDao: MaterialDao,
    private val seedDao: SeedDao,
    private val herbDao: HerbDao,
    private val explorationTeamDao: ExplorationTeamDao,
    private val buildingSlotDao: BuildingSlotDao,
    private val gameEventDao: GameEventDao,
    private val dungeonDao: DungeonDao,
    private val recipeDao: RecipeDao,
    private val battleLogDao: BattleLogDao,
    private val warTeamDao: WarTeamDao,
    private val forgeSlotDao: ForgeSlotDao
) {
    fun getGameData(): Flow<GameData?> = gameDataDao.getGameData()
    
    suspend fun getGameDataSync(): GameData? = gameDataDao.getGameDataSync()
    
    suspend fun saveGameData(gameData: GameData) = gameDataDao.insert(gameData)
    
    suspend fun updateGameData(gameData: GameData) = gameDataDao.update(gameData)
    
    fun getDisciples(): Flow<List<Disciple>> = discipleDao.getAll()
    
    fun getAliveDisciples(): Flow<List<Disciple>> = discipleDao.getAllAlive()
    
    suspend fun getDiscipleById(id: String): Disciple? = discipleDao.getById(id)
    
    suspend fun getDisciplesByStatus(status: DiscipleStatus): List<Disciple> = 
        discipleDao.getByStatus(status)
    
    suspend fun getAllDisciplesSync(): List<Disciple> = 
        discipleDao.getAllAliveSync()
    
    suspend fun addDisciple(disciple: Disciple) = discipleDao.insert(disciple)
    
    suspend fun addDisciples(disciples: List<Disciple>) = discipleDao.insertAll(disciples)
    
    suspend fun updateDisciple(disciple: Disciple) = discipleDao.update(disciple)
    
    suspend fun updateDisciples(disciples: List<Disciple>) = discipleDao.updateAll(disciples)
    
    suspend fun deleteDisciple(disciple: Disciple) = discipleDao.delete(disciple)
    
    fun getEquipment(): Flow<List<Equipment>> = equipmentDao.getAll()
    
    fun getUnequippedEquipment(): Flow<List<Equipment>> = equipmentDao.getUnequipped()
    
    suspend fun getEquipmentById(id: String): Equipment? = equipmentDao.getById(id)
    
    suspend fun getEquipmentByOwner(discipleId: String): List<Equipment> = 
        equipmentDao.getByOwner(discipleId)
    
    suspend fun addEquipment(equipment: Equipment) = equipmentDao.insert(equipment)
    
    suspend fun addEquipments(equipments: List<Equipment>) = equipmentDao.insertAll(equipments)
    
    suspend fun updateEquipment(equipment: Equipment) = equipmentDao.update(equipment)
    
    suspend fun deleteEquipment(equipment: Equipment) = equipmentDao.delete(equipment)
    
    fun getManuals(): Flow<List<Manual>> = manualDao.getAll()
    
    fun getUnlearnedManuals(): Flow<List<Manual>> = manualDao.getUnlearned()
    
    suspend fun getManualById(id: String): Manual? = manualDao.getById(id)
    
    suspend fun getManualsByOwner(discipleId: String): List<Manual> = 
        manualDao.getByOwner(discipleId)
    
    suspend fun addManual(manual: Manual) = manualDao.insert(manual)
    
    suspend fun addManuals(manuals: List<Manual>) = manualDao.insertAll(manuals)
    
    suspend fun updateManual(manual: Manual) = manualDao.update(manual)
    
    suspend fun deleteManual(manual: Manual) = manualDao.delete(manual)
    
    fun getPills(): Flow<List<Pill>> = pillDao.getAll()
    
    suspend fun getPillById(id: String): Pill? = pillDao.getById(id)
    
    suspend fun addPill(pill: Pill) = pillDao.insert(pill)
    
    suspend fun addPills(pills: List<Pill>) = pillDao.insertAll(pills)
    
    suspend fun updatePill(pill: Pill) = pillDao.update(pill)
    
    suspend fun deletePill(pill: Pill) = pillDao.delete(pill)
    
    fun getMaterials(): Flow<List<Material>> = materialDao.getAll()
    
    suspend fun getMaterialById(id: String): Material? = materialDao.getById(id)
    
    suspend fun getMaterialsByCategory(category: MaterialCategory): List<Material> = 
        materialDao.getByCategory(category)
    
    suspend fun addMaterial(material: Material) = materialDao.insert(material)
    
    suspend fun addMaterials(materials: List<Material>) = materialDao.insertAll(materials)
    
    suspend fun updateMaterial(material: Material) = materialDao.update(material)
    
    suspend fun deleteMaterial(material: Material) = materialDao.delete(material)
    
    fun getSeeds(): Flow<List<Seed>> = seedDao.getAll()
    
    suspend fun getSeedById(id: String): Seed? = seedDao.getById(id)
    
    suspend fun addSeed(seed: Seed) = seedDao.insert(seed)
    
    suspend fun addSeeds(seeds: List<Seed>) = seedDao.insertAll(seeds)
    
    suspend fun updateSeed(seed: Seed) = seedDao.update(seed)
    
    suspend fun deleteSeed(seed: Seed) = seedDao.delete(seed)
    
    fun getHerbs(): Flow<List<Herb>> = herbDao.getAll()
    
    suspend fun getHerbById(id: String): Herb? = herbDao.getById(id)
    
    suspend fun addHerb(herb: Herb) = herbDao.insert(herb)
    
    suspend fun addHerbs(herbs: List<Herb>) = herbDao.insertAll(herbs)
    
    suspend fun updateHerb(herb: Herb) = herbDao.update(herb)
    
    suspend fun deleteHerb(herb: Herb) = herbDao.delete(herb)
    
    fun getTeams(): Flow<List<ExplorationTeam>> = explorationTeamDao.getAll()
    
    fun getActiveTeams(): Flow<List<ExplorationTeam>> = explorationTeamDao.getActive()
    
    suspend fun getTeamById(id: String): ExplorationTeam? = explorationTeamDao.getById(id)
    
    suspend fun getAllTeamsSync(): List<ExplorationTeam> = explorationTeamDao.getAllSync()
    
    suspend fun addTeam(team: ExplorationTeam) = explorationTeamDao.insert(team)
    
    suspend fun updateTeam(team: ExplorationTeam) = explorationTeamDao.update(team)
    
    suspend fun updateTeams(teams: List<ExplorationTeam>) = explorationTeamDao.updateAll(teams)
    
    suspend fun deleteTeam(team: ExplorationTeam) = explorationTeamDao.delete(team)
    
    fun getBuildingSlots(buildingId: String): Flow<List<BuildingSlot>> = 
        buildingSlotDao.getByBuilding(buildingId)
    
    fun getAllBuildingSlots(): Flow<List<BuildingSlot>> = buildingSlotDao.getAll()
    
    suspend fun getBuildingSlotsSync(buildingId: String): List<BuildingSlot> = 
        buildingSlotDao.getByBuildingSync(buildingId)
    
    suspend fun addBuildingSlot(slot: BuildingSlot) = buildingSlotDao.insert(slot)
    
    suspend fun addBuildingSlots(slots: List<BuildingSlot>) = buildingSlotDao.insertAll(slots)
    
    suspend fun updateBuildingSlot(slot: BuildingSlot) = buildingSlotDao.update(slot)
    
    suspend fun deleteBuildingSlot(slot: BuildingSlot) = buildingSlotDao.delete(slot)
    
    fun getRecentEvents(limit: Int = 50): Flow<List<GameEvent>> = gameEventDao.getRecent(limit)
    
    fun getAllEvents(): Flow<List<GameEvent>> = gameEventDao.getAll()
    
    suspend fun addEvent(event: GameEvent) = gameEventDao.insert(event)
    
    suspend fun deleteOldEvents(before: Long) = gameEventDao.deleteOld(before)
    
    fun getUnlockedDungeons(): Flow<List<Dungeon>> = dungeonDao.getUnlocked()
    
    fun getAllDungeons(): Flow<List<Dungeon>> = dungeonDao.getAll()
    
    suspend fun getDungeonById(id: String): Dungeon? = dungeonDao.getById(id)
    
    suspend fun addDungeon(dungeon: Dungeon) = dungeonDao.insert(dungeon)
    
    suspend fun addDungeons(dungeons: List<Dungeon>) = dungeonDao.insertAll(dungeons)
    
    suspend fun updateDungeon(dungeon: Dungeon) = dungeonDao.update(dungeon)
    
    fun getUnlockedRecipes(): Flow<List<Recipe>> = recipeDao.getUnlocked()
    
    fun getAllRecipes(): Flow<List<Recipe>> = recipeDao.getAll()
    
    fun getRecipesByType(type: RecipeType): Flow<List<Recipe>> = recipeDao.getByType(type)
    
    suspend fun getRecipeById(id: String): Recipe? = recipeDao.getById(id)
    
    suspend fun addRecipe(recipe: Recipe) = recipeDao.insert(recipe)
    
    suspend fun addRecipes(recipes: List<Recipe>) = recipeDao.insertAll(recipes)
    
    suspend fun updateRecipe(recipe: Recipe) = recipeDao.update(recipe)
    
    fun getRecentBattleLogs(limit: Int = 50): Flow<List<BattleLog>> = battleLogDao.getRecent(limit)
    
    fun getAllBattleLogs(): Flow<List<BattleLog>> = battleLogDao.getAll()
    
    suspend fun getBattleLogById(id: String): BattleLog? = battleLogDao.getById(id)
    
    suspend fun addBattleLog(log: BattleLog) = battleLogDao.insert(log)

    suspend fun deleteOldBattleLogs(before: Long) = battleLogDao.deleteOld(before)

    fun getWarTeams(): Flow<List<WarTeam>> = warTeamDao.getAll()

    suspend fun getWarTeamById(id: String): WarTeam? = warTeamDao.getById(id)

    suspend fun getWarTeamsByStatus(status: WarTeamStatus): List<WarTeam> =
        warTeamDao.getByStatus(status)

    suspend fun getWarTeamsByStationedSect(sectId: String): List<WarTeam> =
        warTeamDao.getByStationedSect(sectId)

    suspend fun getWarTeamsByTargetSect(sectId: String): List<WarTeam> =
        warTeamDao.getByTargetSect(sectId)

    suspend fun addWarTeam(warTeam: WarTeam) = warTeamDao.insert(warTeam)

    suspend fun addWarTeams(warTeams: List<WarTeam>) = warTeamDao.insertAll(warTeams)

    suspend fun updateWarTeam(warTeam: WarTeam) = warTeamDao.update(warTeam)

    suspend fun updateWarTeams(warTeams: List<WarTeam>) = warTeamDao.updateAll(warTeams)

    suspend fun deleteWarTeam(warTeam: WarTeam) = warTeamDao.delete(warTeam)

    suspend fun getForgeSlotBySlotIndex(slotIndex: Int): ForgeSlot? = 
        forgeSlotDao.getBySlotIndex(slotIndex)
    
    suspend fun saveForgeSlot(slot: ForgeSlot) = forgeSlotDao.insert(slot)
    
    suspend fun saveForgeSlots(slots: List<ForgeSlot>) = forgeSlotDao.insertAll(slots)
    
    suspend fun deleteForgeSlot(slot: ForgeSlot) = forgeSlotDao.delete(slot)
    
    suspend fun initializeNewGame(): GameData {
        val gameData = GameData()
        gameDataDao.insert(gameData)
        return gameData
    }
    
    suspend fun clearAllData() {
        database.withTransaction {
            gameDataDao.deleteAll()
            discipleDao.deleteAll()
            equipmentDao.deleteAll()
            manualDao.deleteAll()
            pillDao.deleteAll()
            materialDao.deleteAll()
            seedDao.deleteAll()
            herbDao.deleteAll()
            explorationTeamDao.deleteAll()
            buildingSlotDao.deleteAll()
            gameEventDao.deleteAll()
            dungeonDao.deleteAll()
            recipeDao.deleteAll()
            battleLogDao.deleteAll()
            warTeamDao.deleteAll()
            forgeSlotDao.deleteAll()
        }
    }
    
    suspend fun saveAll(
        gameData: GameData,
        disciples: List<Disciple>,
        equipment: List<Equipment>,
        manuals: List<Manual>,
        pills: List<Pill>,
        materials: List<Material>,
        seeds: List<Seed>,
        herbs: List<Herb>,
        teams: List<ExplorationTeam>,
        slots: List<BuildingSlot>,
        events: List<GameEvent>,
        battleLogs: List<BattleLog> = emptyList(),
        warTeams: List<WarTeam> = emptyList()
    ) {
        database.withTransaction {
            gameDataDao.insert(gameData)
            discipleDao.insertAll(disciples)
            equipmentDao.insertAll(equipment)
            manualDao.insertAll(manuals)
            pillDao.insertAll(pills)
            materialDao.insertAll(materials)
            seedDao.insertAll(seeds)
            herbDao.insertAll(herbs)
            explorationTeamDao.insertAll(teams)
            buildingSlotDao.insertAll(slots)

            gameEventDao.deleteAll()
            gameEventDao.insertAll(events)

            battleLogDao.deleteAll()
            battleLogDao.insertAll(battleLogs)

            warTeamDao.deleteAll()
            warTeamDao.insertAll(warTeams)
        }
    }
}
