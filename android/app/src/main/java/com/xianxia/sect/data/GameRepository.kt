package com.xianxia.sect.data

import android.util.Log
import androidx.room.withTransaction
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.data.local.*
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GameRepository @Inject constructor(
    private val database: GameDatabase,
    private val gameDataDao: GameDataDao,
    private val discipleDao: DiscipleDao,
    private val discipleCoreDao: DiscipleCoreDao,
    private val discipleCombatStatsDao: DiscipleCombatStatsDao,
    private val discipleEquipmentDao: DiscipleEquipmentDao,
    private val discipleExtendedDao: DiscipleExtendedDao,
    private val discipleAttributesDao: DiscipleAttributesDao,
    private val equipmentStackDao: EquipmentStackDao,
    private val equipmentInstanceDao: EquipmentInstanceDao,
    private val manualStackDao: ManualStackDao,
    private val manualInstanceDao: ManualInstanceDao,
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
    private val forgeSlotDao: ForgeSlotDao,
    private val alchemySlotDao: AlchemySlotDao,
    private val productionSlotDao: ProductionSlotDao
) {
    companion object {
        private const val TAG = "GameRepository"
        const val DEFAULT_SLOT_ID = 0
    }

    init {
        Log.w(TAG, "GameRepository initialized directly. " +
                "Consider using StorageGateway for unified storage access. " +
                "Direct usage may cause storage path confusion.")
    }

    // ==================== GameData ====================

    fun getGameData(slotId: Int = DEFAULT_SLOT_ID): Flow<GameData?> = gameDataDao.getGameData(slotId)
    
    suspend fun getGameDataSync(slotId: Int = DEFAULT_SLOT_ID): GameData? = gameDataDao.getGameDataSync(slotId)
    
    suspend fun saveGameData(gameData: GameData) = gameDataDao.insert(gameData)
    
    suspend fun updateGameData(gameData: GameData) = gameDataDao.update(gameData)

    // ==================== Disciple ====================

    fun getDisciples(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Disciple>> = discipleDao.getAll(slotId)
    
    fun getAliveDisciples(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Disciple>> = discipleDao.getAllAlive(slotId)
    
    suspend fun getDiscipleById(id: String, slotId: Int = DEFAULT_SLOT_ID): Disciple? = discipleDao.getById(slotId, id)
    
    suspend fun getDisciplesByStatus(status: DiscipleStatus, slotId: Int = DEFAULT_SLOT_ID): List<Disciple> = 
        discipleDao.getByStatus(slotId, status)
    
    suspend fun getAllDisciplesSync(slotId: Int = DEFAULT_SLOT_ID): List<Disciple> = 
        discipleDao.getAllAliveSync(slotId)
    
    suspend fun addDisciple(disciple: Disciple) = discipleDao.insert(disciple)
    
    suspend fun addDisciples(disciples: List<Disciple>) = discipleDao.insertAll(disciples)
    
    suspend fun updateDisciple(disciple: Disciple) = discipleDao.update(disciple)
    
    suspend fun updateDisciples(disciples: List<Disciple>) = discipleDao.updateAll(disciples)
    
    suspend fun deleteDisciple(disciple: Disciple) = discipleDao.delete(disciple)

    // ==================== EquipmentStack ====================

    fun getEquipmentStacks(slotId: Int = DEFAULT_SLOT_ID): Flow<List<EquipmentStack>> = equipmentStackDao.getAll(slotId)
    
    suspend fun getEquipmentStackById(id: String, slotId: Int = DEFAULT_SLOT_ID): EquipmentStack? = equipmentStackDao.getById(slotId, id)
    
    suspend fun addEquipmentStack(stack: EquipmentStack) = equipmentStackDao.insert(stack)
    
    suspend fun addEquipmentStacks(stacks: List<EquipmentStack>) = equipmentStackDao.insertAll(stacks)
    
    suspend fun updateEquipmentStack(stack: EquipmentStack) = equipmentStackDao.update(stack)
    
    suspend fun deleteEquipmentStack(stack: EquipmentStack) = equipmentStackDao.delete(stack)

    // ==================== EquipmentInstance ====================

    fun getEquipmentInstances(slotId: Int = DEFAULT_SLOT_ID): Flow<List<EquipmentInstance>> = equipmentInstanceDao.getAll(slotId)
    
    suspend fun getEquipmentInstanceById(id: String, slotId: Int = DEFAULT_SLOT_ID): EquipmentInstance? = equipmentInstanceDao.getById(slotId, id)
    
    suspend fun getEquipmentInstancesByOwner(discipleId: String, slotId: Int = DEFAULT_SLOT_ID): List<EquipmentInstance> = 
        equipmentInstanceDao.getByOwner(slotId, discipleId)
    
    suspend fun addEquipmentInstance(instance: EquipmentInstance) = equipmentInstanceDao.insert(instance)
    
    suspend fun addEquipmentInstances(instances: List<EquipmentInstance>) = equipmentInstanceDao.insertAll(instances)
    
    suspend fun updateEquipmentInstance(instance: EquipmentInstance) = equipmentInstanceDao.update(instance)
    
    suspend fun deleteEquipmentInstance(instance: EquipmentInstance) = equipmentInstanceDao.delete(instance)

    // ==================== ManualStack ====================

    fun getManualStacks(slotId: Int = DEFAULT_SLOT_ID): Flow<List<ManualStack>> = manualStackDao.getAll(slotId)
    
    suspend fun getManualStackById(id: String, slotId: Int = DEFAULT_SLOT_ID): ManualStack? = manualStackDao.getById(slotId, id)
    
    suspend fun addManualStack(stack: ManualStack) = manualStackDao.insert(stack)
    
    suspend fun addManualStacks(stacks: List<ManualStack>) = manualStackDao.insertAll(stacks)
    
    suspend fun updateManualStack(stack: ManualStack) = manualStackDao.update(stack)
    
    suspend fun deleteManualStack(stack: ManualStack) = manualStackDao.delete(stack)

    // ==================== ManualInstance ====================

    fun getManualInstances(slotId: Int = DEFAULT_SLOT_ID): Flow<List<ManualInstance>> = manualInstanceDao.getAll(slotId)
    
    suspend fun getManualInstanceById(id: String, slotId: Int = DEFAULT_SLOT_ID): ManualInstance? = manualInstanceDao.getById(slotId, id)
    
    suspend fun getManualInstancesByOwner(discipleId: String, slotId: Int = DEFAULT_SLOT_ID): List<ManualInstance> = 
        manualInstanceDao.getByOwner(slotId, discipleId)
    
    suspend fun addManualInstance(instance: ManualInstance) = manualInstanceDao.insert(instance)
    
    suspend fun addManualInstances(instances: List<ManualInstance>) = manualInstanceDao.insertAll(instances)
    
    suspend fun updateManualInstance(instance: ManualInstance) = manualInstanceDao.update(instance)
    
    suspend fun deleteManualInstance(instance: ManualInstance) = manualInstanceDao.delete(instance)

    // ==================== Pill ====================

    fun getPills(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Pill>> = pillDao.getAll(slotId)
    
    suspend fun getPillById(id: String, slotId: Int = DEFAULT_SLOT_ID): Pill? = pillDao.getById(slotId, id)
    
    suspend fun addPill(pill: Pill) = pillDao.insert(pill)
    
    suspend fun addPills(pills: List<Pill>) = pillDao.insertAll(pills)
    
    suspend fun updatePill(pill: Pill) = pillDao.update(pill)
    
    suspend fun deletePill(pill: Pill) = pillDao.delete(pill)

    // ==================== Material ====================

    fun getMaterials(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Material>> = materialDao.getAll(slotId)
    
    suspend fun getMaterialById(id: String, slotId: Int = DEFAULT_SLOT_ID): Material? = materialDao.getById(slotId, id)
    
    fun getMaterialsByCategory(category: MaterialCategory, slotId: Int = DEFAULT_SLOT_ID): Flow<List<Material>> = 
        materialDao.getByCategory(slotId, category)
    
    suspend fun addMaterial(material: Material) = materialDao.insert(material)
    
    suspend fun addMaterials(materials: List<Material>) = materialDao.insertAll(materials)
    
    suspend fun updateMaterial(material: Material) = materialDao.update(material)
    
    suspend fun deleteMaterial(material: Material) = materialDao.delete(material)

    // ==================== Seed ====================

    fun getSeeds(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Seed>> = seedDao.getAll(slotId)
    
    suspend fun getSeedById(id: String, slotId: Int = DEFAULT_SLOT_ID): Seed? = seedDao.getById(slotId, id)
    
    suspend fun addSeed(seed: Seed) = seedDao.insert(seed)
    
    suspend fun addSeeds(seeds: List<Seed>) = seedDao.insertAll(seeds)
    
    suspend fun updateSeed(seed: Seed) = seedDao.update(seed)
    
    suspend fun deleteSeed(seed: Seed) = seedDao.delete(seed)

    // ==================== Herb ====================

    fun getHerbs(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Herb>> = herbDao.getAll(slotId)
    
    suspend fun getHerbById(id: String, slotId: Int = DEFAULT_SLOT_ID): Herb? = herbDao.getById(slotId, id)
    
    suspend fun addHerb(herb: Herb) = herbDao.insert(herb)
    
    suspend fun addHerbs(herbs: List<Herb>) = herbDao.insertAll(herbs)
    
    suspend fun updateHerb(herb: Herb) = herbDao.update(herb)
    
    suspend fun deleteHerb(herb: Herb) = herbDao.delete(herb)

    // ==================== ExplorationTeam ====================

    fun getTeams(slotId: Int = DEFAULT_SLOT_ID): Flow<List<ExplorationTeam>> = explorationTeamDao.getAll(slotId)
    
    fun getActiveTeams(slotId: Int = DEFAULT_SLOT_ID): Flow<List<ExplorationTeam>> = explorationTeamDao.getActive(slotId)
    
    suspend fun getTeamById(id: String, slotId: Int = DEFAULT_SLOT_ID): ExplorationTeam? = explorationTeamDao.getById(slotId, id)
    
    suspend fun getAllTeamsSync(slotId: Int = DEFAULT_SLOT_ID): List<ExplorationTeam> = explorationTeamDao.getAllSync(slotId)
    
    suspend fun addTeam(team: ExplorationTeam) = explorationTeamDao.insert(team)
    
    suspend fun updateTeam(team: ExplorationTeam) = explorationTeamDao.update(team)
    
    suspend fun updateTeams(teams: List<ExplorationTeam>) = explorationTeamDao.updateAll(teams)
    
    suspend fun deleteTeam(team: ExplorationTeam) = explorationTeamDao.delete(team)

    // ==================== BuildingSlot ====================

    fun getBuildingSlots(buildingId: String, slotId: Int = DEFAULT_SLOT_ID): Flow<List<BuildingSlot>> = 
        buildingSlotDao.getByBuilding(slotId, buildingId)
    
    fun getAllBuildingSlots(slotId: Int = DEFAULT_SLOT_ID): Flow<List<BuildingSlot>> = buildingSlotDao.getAll(slotId)
    
    suspend fun getBuildingSlotsSync(buildingId: String, slotId: Int = DEFAULT_SLOT_ID): List<BuildingSlot> = 
        buildingSlotDao.getByBuildingSync(slotId, buildingId)
    
    suspend fun addBuildingSlot(slot: BuildingSlot) = buildingSlotDao.insert(slot)
    
    suspend fun addBuildingSlots(slots: List<BuildingSlot>) = buildingSlotDao.insertAll(slots)
    
    suspend fun updateBuildingSlot(slot: BuildingSlot) = buildingSlotDao.update(slot)
    
    suspend fun deleteBuildingSlot(slot: BuildingSlot) = buildingSlotDao.delete(slot)

    // ==================== GameEvent ====================

    fun getRecentEvents(limit: Int = 50, slotId: Int = DEFAULT_SLOT_ID): Flow<List<GameEvent>> = gameEventDao.getRecent(slotId, limit)
    
    fun getAllEvents(slotId: Int = DEFAULT_SLOT_ID): Flow<List<GameEvent>> = gameEventDao.getAll(slotId)
    
    suspend fun addEvent(event: GameEvent) = gameEventDao.insert(event)
    
    suspend fun deleteOldEvents(before: Long, slotId: Int = DEFAULT_SLOT_ID) = gameEventDao.deleteOld(slotId, before)

    // ==================== Dungeon ====================

    fun getUnlockedDungeons(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Dungeon>> = dungeonDao.getUnlocked(slotId)
    
    fun getAllDungeons(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Dungeon>> = dungeonDao.getAll(slotId)
    
    suspend fun getDungeonById(id: String, slotId: Int = DEFAULT_SLOT_ID): Dungeon? = dungeonDao.getById(slotId, id)
    
    suspend fun addDungeon(dungeon: Dungeon) = dungeonDao.insert(dungeon)
    
    suspend fun addDungeons(dungeons: List<Dungeon>) = dungeonDao.insertAll(dungeons)
    
    suspend fun updateDungeon(dungeon: Dungeon) = dungeonDao.update(dungeon)

    // ==================== Recipe ====================

    fun getUnlockedRecipes(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Recipe>> = recipeDao.getUnlocked(slotId)
    
    fun getAllRecipes(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Recipe>> = recipeDao.getAll(slotId)
    
    fun getRecipesByType(type: RecipeType, slotId: Int = DEFAULT_SLOT_ID): Flow<List<Recipe>> = recipeDao.getByType(slotId, type)
    
    suspend fun getRecipeById(id: String, slotId: Int = DEFAULT_SLOT_ID): Recipe? = recipeDao.getById(slotId, id)
    
    suspend fun addRecipe(recipe: Recipe) = recipeDao.insert(recipe)
    
    suspend fun addRecipes(recipes: List<Recipe>) = recipeDao.insertAll(recipes)
    
    suspend fun updateRecipe(recipe: Recipe) = recipeDao.update(recipe)

    // ==================== BattleLog ====================

    fun getRecentBattleLogs(limit: Int = 50, slotId: Int = DEFAULT_SLOT_ID): Flow<List<BattleLog>> = battleLogDao.getRecent(slotId, limit)
    
    fun getAllBattleLogs(slotId: Int = DEFAULT_SLOT_ID): Flow<List<BattleLog>> = battleLogDao.getAll(slotId)
    
    suspend fun getBattleLogById(id: String, slotId: Int = DEFAULT_SLOT_ID): BattleLog? = battleLogDao.getById(slotId, id)
    
    suspend fun addBattleLog(log: BattleLog) = battleLogDao.insert(log)

    suspend fun deleteOldBattleLogs(before: Long, slotId: Int = DEFAULT_SLOT_ID) = battleLogDao.deleteOld(slotId, before)

    // ==================== ForgeSlot ====================

    suspend fun getForgeSlotBySlotIndex(slotIndex: Int, slotId: Int = DEFAULT_SLOT_ID): ForgeSlot? = 
        forgeSlotDao.getBySlotIndex(slotId, slotIndex)
    
    suspend fun saveForgeSlot(slot: ForgeSlot) = forgeSlotDao.insert(slot)
    
    suspend fun saveForgeSlots(slots: List<ForgeSlot>) = forgeSlotDao.insertAll(slots)
    
    suspend fun deleteForgeSlot(slot: ForgeSlot) = forgeSlotDao.delete(slot)

    // ==================== Lifecycle ====================

    suspend fun initializeNewGame(): GameData {
        val gameData = GameData(id = "game_data_0")
        gameDataDao.insert(gameData)
        return gameData
    }
    
    suspend fun clearAllData(slotId: Int = DEFAULT_SLOT_ID) {
        database.withTransaction {
            gameDataDao.deleteAll(slotId)
            discipleDao.deleteAll(slotId)
            database.discipleCoreDao().deleteAll(slotId)
            database.discipleCombatStatsDao().deleteAll(slotId)
            database.discipleEquipmentDao().deleteAll(slotId)
            database.discipleExtendedDao().deleteAll(slotId)
            database.discipleAttributesDao().deleteAll(slotId)
            equipmentStackDao.deleteAll(slotId)
            equipmentInstanceDao.deleteAll(slotId)
            manualStackDao.deleteAll(slotId)
            manualInstanceDao.deleteAll(slotId)
            pillDao.deleteAll(slotId)
            materialDao.deleteAll(slotId)
            seedDao.deleteAll(slotId)
            herbDao.deleteAll(slotId)
            explorationTeamDao.deleteAll(slotId)
            buildingSlotDao.deleteAll(slotId)
            gameEventDao.deleteAll(slotId)
            dungeonDao.deleteAll(slotId)
            recipeDao.deleteAll(slotId)
            battleLogDao.deleteAll(slotId)
            forgeSlotDao.deleteAll(slotId)
            database.alchemySlotDao().deleteAll(slotId)
            database.productionSlotDao().deleteBySlot(slotId)
        }
    }
    
    suspend fun saveAll(
        gameData: GameData,
        disciples: List<Disciple>,
        discipleCores: List<DiscipleCore> = emptyList(),
        discipleCombatStats: List<DiscipleCombatStats> = emptyList(),
        discipleEquipment: List<DiscipleEquipment> = emptyList(),
        discipleExtended: List<DiscipleExtended> = emptyList(),
        discipleAttributes: List<DiscipleAttributes> = emptyList(),
        equipmentStacks: List<EquipmentStack> = emptyList(),
        equipmentInstances: List<EquipmentInstance> = emptyList(),
        manualStacks: List<ManualStack> = emptyList(),
        manualInstances: List<ManualInstance> = emptyList(),
        pills: List<Pill>,
        materials: List<Material>,
        seeds: List<Seed>,
        herbs: List<Herb>,
        teams: List<ExplorationTeam>,
        slots: List<BuildingSlot>,
        events: List<GameEvent>,
        dungeons: List<Dungeon> = emptyList(),
        recipes: List<Recipe> = emptyList(),
        battleLogs: List<BattleLog> = emptyList(),
        forgeSlots: List<ForgeSlot> = emptyList(),
        alchemySlots: List<AlchemySlot> = emptyList(),
        productionSlots: List<ProductionSlot> = emptyList(),
        slotId: Int = DEFAULT_SLOT_ID
    ) {
        database.withTransaction {
            gameDataDao.insert(gameData)
            discipleDao.insertAll(disciples)
            discipleCoreDao.insertAll(discipleCores)
            discipleCombatStatsDao.insertAll(discipleCombatStats)
            discipleEquipmentDao.insertAll(discipleEquipment)
            discipleExtendedDao.insertAll(discipleExtended)
            discipleAttributesDao.insertAll(discipleAttributes)
            equipmentStackDao.insertAll(equipmentStacks)
            equipmentInstanceDao.insertAll(equipmentInstances)
            manualStackDao.insertAll(manualStacks)
            manualInstanceDao.insertAll(manualInstances)
            pillDao.insertAll(pills)
            materialDao.insertAll(materials)
            seedDao.insertAll(seeds)
            herbDao.insertAll(herbs)
            explorationTeamDao.insertAll(teams)
            buildingSlotDao.insertAll(slots)

            gameEventDao.deleteAll(slotId)
            gameEventDao.insertAll(events)

            dungeonDao.deleteAll(slotId)
            dungeonDao.insertAll(dungeons)

            recipeDao.deleteAll(slotId)
            recipeDao.insertAll(recipes)

            battleLogDao.deleteAll(slotId)
            battleLogDao.insertAll(battleLogs)

            forgeSlotDao.deleteAll(slotId)
            forgeSlotDao.insertAll(forgeSlots)

            alchemySlotDao.deleteAll(slotId)
            alchemySlotDao.insertAll(alchemySlots)

            productionSlotDao.deleteBySlot(slotId)
            productionSlotDao.insertAll(productionSlots)
        }
    }
}
