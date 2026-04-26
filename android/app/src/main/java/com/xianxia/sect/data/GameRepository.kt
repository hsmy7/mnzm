package com.xianxia.sect.data

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.repository.*
import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.data.local.*
import com.xianxia.sect.data.model.SaveData
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Legacy monolithic repository — prefer the domain-specific repositories:
 * - [GameDataRepository]
 * - [DiscipleRepository]
 * - [EquipmentRepository]
 * - [InventoryRepository]
 * - [WorldRepository]
 * - [ForgeRepository]
 *
 * This class is retained for backward compatibility and delegates all
 * non-deprecated read methods to the new repositories.
 */
@Deprecated(
    message = "Use domain-specific repositories instead (GameDataRepository, DiscipleRepository, etc.)",
    replaceWith = ReplaceWith("GameDataRepository, DiscipleRepository, EquipmentRepository, InventoryRepository, WorldRepository, ForgeRepository")
)
@Singleton
class GameRepository @Inject constructor(
    private val storageFacade: StorageFacade,
    private val gameDataRepository: GameDataRepository,
    private val discipleRepository: DiscipleRepository,
    private val equipmentRepository: EquipmentRepository,
    private val inventoryRepository: InventoryRepository,
    private val worldRepository: WorldRepository,
    private val forgeRepository: ForgeRepository,
    // DAOs still needed for deprecated write methods
    private val gameDataDao: GameDataDao,
    private val discipleDao: DiscipleDao,
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
    private val forgeSlotDao: ForgeSlotDao
) {
    companion object {
        private const val TAG = "GameRepository"
        const val DEFAULT_SLOT_ID = 0
    }

    // ==================== GameData (delegates to GameDataRepository) ====================

    fun getGameData(slotId: Int = DEFAULT_SLOT_ID): Flow<GameData?> =
        gameDataRepository.getGameData(slotId)

    suspend fun getGameDataSync(slotId: Int = DEFAULT_SLOT_ID): GameData? =
        gameDataRepository.getGameDataSync(slotId)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun saveGameData(gameData: GameData) = gameDataDao.insert(gameData)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun updateGameData(gameData: GameData) = gameDataDao.update(gameData)

    // ==================== Disciple (delegates to DiscipleRepository) ====================

    fun getDisciples(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Disciple>> =
        discipleRepository.getDisciples(slotId)

    fun getAliveDisciples(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Disciple>> =
        discipleRepository.getAliveDisciples(slotId)

    suspend fun getDiscipleById(id: String, slotId: Int = DEFAULT_SLOT_ID): Disciple? =
        discipleRepository.getDiscipleById(id, slotId)

    suspend fun getDisciplesByStatus(status: DiscipleStatus, slotId: Int = DEFAULT_SLOT_ID): List<Disciple> =
        discipleRepository.getDisciplesByStatus(status, slotId)

    suspend fun getAllDisciplesSync(slotId: Int = DEFAULT_SLOT_ID): List<Disciple> =
        discipleRepository.getAllDisciplesSync(slotId)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun addDisciple(disciple: Disciple) = discipleDao.insert(disciple)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun addDisciples(disciples: List<Disciple>) = discipleDao.insertAll(disciples)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun updateDisciple(disciple: Disciple) = discipleDao.update(disciple)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun updateDisciples(disciples: List<Disciple>) = discipleDao.updateAll(disciples)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun deleteDisciple(disciple: Disciple) = discipleDao.delete(disciple)

    // ==================== EquipmentStack (delegates to EquipmentRepository) ====================

    fun getEquipmentStacks(slotId: Int = DEFAULT_SLOT_ID): Flow<List<EquipmentStack>> =
        equipmentRepository.getEquipmentStacks(slotId)

    suspend fun getEquipmentStackById(id: String, slotId: Int = DEFAULT_SLOT_ID): EquipmentStack? =
        equipmentRepository.getEquipmentStackById(id, slotId)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun addEquipmentStack(stack: EquipmentStack) = equipmentStackDao.insert(stack)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun addEquipmentStacks(stacks: List<EquipmentStack>) = equipmentStackDao.insertAll(stacks)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun updateEquipmentStack(stack: EquipmentStack) = equipmentStackDao.update(stack)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun deleteEquipmentStack(stack: EquipmentStack) = equipmentStackDao.delete(stack)

    // ==================== EquipmentInstance (delegates to EquipmentRepository) ====================

    fun getEquipmentInstances(slotId: Int = DEFAULT_SLOT_ID): Flow<List<EquipmentInstance>> =
        equipmentRepository.getEquipmentInstances(slotId)

    suspend fun getEquipmentInstanceById(id: String, slotId: Int = DEFAULT_SLOT_ID): EquipmentInstance? =
        equipmentRepository.getEquipmentInstanceById(id, slotId)

    suspend fun getEquipmentInstancesByOwner(discipleId: String, slotId: Int = DEFAULT_SLOT_ID): List<EquipmentInstance> =
        equipmentRepository.getEquipmentInstancesByOwner(discipleId, slotId)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun addEquipmentInstance(instance: EquipmentInstance) = equipmentInstanceDao.insert(instance)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun addEquipmentInstances(instances: List<EquipmentInstance>) = equipmentInstanceDao.insertAll(instances)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun updateEquipmentInstance(instance: EquipmentInstance) = equipmentInstanceDao.update(instance)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun deleteEquipmentInstance(instance: EquipmentInstance) = equipmentInstanceDao.delete(instance)

    // ==================== ManualStack (delegates to InventoryRepository) ====================

    fun getManualStacks(slotId: Int = DEFAULT_SLOT_ID): Flow<List<ManualStack>> =
        inventoryRepository.getManualStacks(slotId)

    suspend fun getManualStackById(id: String, slotId: Int = DEFAULT_SLOT_ID): ManualStack? =
        inventoryRepository.getManualStackById(id, slotId)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun addManualStack(stack: ManualStack) = manualStackDao.insert(stack)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun addManualStacks(stacks: List<ManualStack>) = manualStackDao.insertAll(stacks)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun updateManualStack(stack: ManualStack) = manualStackDao.update(stack)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun deleteManualStack(stack: ManualStack) = manualStackDao.delete(stack)

    // ==================== ManualInstance (delegates to InventoryRepository) ====================

    fun getManualInstances(slotId: Int = DEFAULT_SLOT_ID): Flow<List<ManualInstance>> =
        inventoryRepository.getManualInstances(slotId)

    suspend fun getManualInstanceById(id: String, slotId: Int = DEFAULT_SLOT_ID): ManualInstance? =
        inventoryRepository.getManualInstanceById(id, slotId)

    suspend fun getManualInstancesByOwner(discipleId: String, slotId: Int = DEFAULT_SLOT_ID): List<ManualInstance> =
        inventoryRepository.getManualInstancesByOwner(discipleId, slotId)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun addManualInstance(instance: ManualInstance) = manualInstanceDao.insert(instance)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun addManualInstances(instances: List<ManualInstance>) = manualInstanceDao.insertAll(instances)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun updateManualInstance(instance: ManualInstance) = manualInstanceDao.update(instance)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun deleteManualInstance(instance: ManualInstance) = manualInstanceDao.delete(instance)

    // ==================== Pill (delegates to InventoryRepository) ====================

    fun getPills(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Pill>> =
        inventoryRepository.getPills(slotId)

    suspend fun getPillById(id: String, slotId: Int = DEFAULT_SLOT_ID): Pill? =
        inventoryRepository.getPillById(id, slotId)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun addPill(pill: Pill) = pillDao.insert(pill)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun addPills(pills: List<Pill>) = pillDao.insertAll(pills)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun updatePill(pill: Pill) = pillDao.update(pill)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun deletePill(pill: Pill) = pillDao.delete(pill)

    // ==================== Material (delegates to InventoryRepository) ====================

    fun getMaterials(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Material>> =
        inventoryRepository.getMaterials(slotId)

    suspend fun getMaterialById(id: String, slotId: Int = DEFAULT_SLOT_ID): Material? =
        inventoryRepository.getMaterialById(id, slotId)

    fun getMaterialsByCategory(category: MaterialCategory, slotId: Int = DEFAULT_SLOT_ID): Flow<List<Material>> =
        inventoryRepository.getMaterialsByCategory(category, slotId)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun addMaterial(material: Material) = materialDao.insert(material)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun addMaterials(materials: List<Material>) = materialDao.insertAll(materials)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun updateMaterial(material: Material) = materialDao.update(material)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun deleteMaterial(material: Material) = materialDao.delete(material)

    // ==================== Seed (delegates to InventoryRepository) ====================

    fun getSeeds(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Seed>> =
        inventoryRepository.getSeeds(slotId)

    suspend fun getSeedById(id: String, slotId: Int = DEFAULT_SLOT_ID): Seed? =
        inventoryRepository.getSeedById(id, slotId)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun addSeed(seed: Seed) = seedDao.insert(seed)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun addSeeds(seeds: List<Seed>) = seedDao.insertAll(seeds)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun updateSeed(seed: Seed) = seedDao.update(seed)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun deleteSeed(seed: Seed) = seedDao.delete(seed)

    // ==================== Herb (delegates to InventoryRepository) ====================

    fun getHerbs(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Herb>> =
        inventoryRepository.getHerbs(slotId)

    suspend fun getHerbById(id: String, slotId: Int = DEFAULT_SLOT_ID): Herb? =
        inventoryRepository.getHerbById(id, slotId)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun addHerb(herb: Herb) = herbDao.insert(herb)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun addHerbs(herbs: List<Herb>) = herbDao.insertAll(herbs)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun updateHerb(herb: Herb) = herbDao.update(herb)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun deleteHerb(herb: Herb) = herbDao.delete(herb)

    // ==================== ExplorationTeam (delegates to WorldRepository) ====================

    fun getTeams(slotId: Int = DEFAULT_SLOT_ID): Flow<List<ExplorationTeam>> =
        worldRepository.getTeams(slotId)

    fun getActiveTeams(slotId: Int = DEFAULT_SLOT_ID): Flow<List<ExplorationTeam>> =
        worldRepository.getActiveTeams(slotId)

    suspend fun getTeamById(id: String, slotId: Int = DEFAULT_SLOT_ID): ExplorationTeam? =
        worldRepository.getTeamById(id, slotId)

    suspend fun getAllTeamsSync(slotId: Int = DEFAULT_SLOT_ID): List<ExplorationTeam> =
        worldRepository.getAllTeamsSync(slotId)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun addTeam(team: ExplorationTeam) = explorationTeamDao.insert(team)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun updateTeam(team: ExplorationTeam) = explorationTeamDao.update(team)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun updateTeams(teams: List<ExplorationTeam>) = explorationTeamDao.updateAll(teams)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun deleteTeam(team: ExplorationTeam) = explorationTeamDao.delete(team)

    // ==================== BuildingSlot (delegates to WorldRepository) ====================

    fun getBuildingSlots(buildingId: String, slotId: Int = DEFAULT_SLOT_ID): Flow<List<BuildingSlot>> =
        worldRepository.getBuildingSlots(buildingId, slotId)

    fun getAllBuildingSlots(slotId: Int = DEFAULT_SLOT_ID): Flow<List<BuildingSlot>> =
        worldRepository.getAllBuildingSlots(slotId)

    suspend fun getBuildingSlotsSync(buildingId: String, slotId: Int = DEFAULT_SLOT_ID): List<BuildingSlot> =
        worldRepository.getBuildingSlotsSync(buildingId, slotId)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun addBuildingSlot(slot: BuildingSlot) = buildingSlotDao.insert(slot)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun addBuildingSlots(slots: List<BuildingSlot>) = buildingSlotDao.insertAll(slots)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun updateBuildingSlot(slot: BuildingSlot) = buildingSlotDao.update(slot)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun deleteBuildingSlot(slot: BuildingSlot) = buildingSlotDao.delete(slot)

    // ==================== GameEvent (delegates to WorldRepository) ====================

    fun getRecentEvents(limit: Int = 50, slotId: Int = DEFAULT_SLOT_ID): Flow<List<GameEvent>> =
        worldRepository.getRecentEvents(limit, slotId)

    fun getAllEvents(slotId: Int = DEFAULT_SLOT_ID): Flow<List<GameEvent>> =
        worldRepository.getAllEvents(slotId)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun addEvent(event: GameEvent) = gameEventDao.insert(event)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun deleteOldEvents(before: Long, slotId: Int = DEFAULT_SLOT_ID) = gameEventDao.deleteOld(slotId, before)

    // ==================== Dungeon (delegates to WorldRepository) ====================

    fun getUnlockedDungeons(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Dungeon>> =
        worldRepository.getUnlockedDungeons(slotId)

    fun getAllDungeons(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Dungeon>> =
        worldRepository.getAllDungeons(slotId)

    suspend fun getDungeonById(id: String, slotId: Int = DEFAULT_SLOT_ID): Dungeon? =
        worldRepository.getDungeonById(id, slotId)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun addDungeon(dungeon: Dungeon) = dungeonDao.insert(dungeon)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun addDungeons(dungeons: List<Dungeon>) = dungeonDao.insertAll(dungeons)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun updateDungeon(dungeon: Dungeon) = dungeonDao.update(dungeon)

    // ==================== Recipe (delegates to WorldRepository) ====================

    fun getUnlockedRecipes(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Recipe>> =
        worldRepository.getUnlockedRecipes(slotId)

    fun getAllRecipes(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Recipe>> =
        worldRepository.getAllRecipes(slotId)

    fun getRecipesByType(type: RecipeType, slotId: Int = DEFAULT_SLOT_ID): Flow<List<Recipe>> =
        worldRepository.getRecipesByType(type, slotId)

    suspend fun getRecipeById(id: String, slotId: Int = DEFAULT_SLOT_ID): Recipe? =
        worldRepository.getRecipeById(id, slotId)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun addRecipe(recipe: Recipe) = recipeDao.insert(recipe)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun addRecipes(recipes: List<Recipe>) = recipeDao.insertAll(recipes)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun updateRecipe(recipe: Recipe) = recipeDao.update(recipe)

    // ==================== BattleLog (delegates to WorldRepository) ====================

    fun getRecentBattleLogs(limit: Int = 50, slotId: Int = DEFAULT_SLOT_ID): Flow<List<BattleLog>> =
        worldRepository.getRecentBattleLogs(limit, slotId)

    fun getAllBattleLogs(slotId: Int = DEFAULT_SLOT_ID): Flow<List<BattleLog>> =
        worldRepository.getAllBattleLogs(slotId)

    suspend fun getBattleLogById(id: String, slotId: Int = DEFAULT_SLOT_ID): BattleLog? =
        worldRepository.getBattleLogById(id, slotId)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun addBattleLog(log: BattleLog) = battleLogDao.insert(log)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun deleteOldBattleLogs(before: Long, slotId: Int = DEFAULT_SLOT_ID) = battleLogDao.deleteOld(slotId, before)

    // ==================== ForgeSlot (delegates to ForgeRepository) ====================

    suspend fun getForgeSlotBySlotIndex(slotIndex: Int, slotId: Int = DEFAULT_SLOT_ID): ForgeSlot? =
        forgeRepository.getForgeSlotBySlotIndex(slotIndex, slotId)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun saveForgeSlot(slot: ForgeSlot) = forgeSlotDao.insert(slot)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun saveForgeSlots(slots: List<ForgeSlot>) = forgeSlotDao.insertAll(slots)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, saveData)"))
    suspend fun deleteForgeSlot(slot: ForgeSlot) = forgeSlotDao.delete(slot)

    // ==================== Lifecycle (delegates to GameDataRepository) ====================

    @Deprecated("Use GameDataRepository.initializeNewGame() instead", ReplaceWith("gameDataRepository.initializeNewGame()"))
    suspend fun initializeNewGame(): GameData = gameDataRepository.initializeNewGame()

    @Deprecated("Use GameDataRepository.clearAllData() instead", ReplaceWith("gameDataRepository.clearAllData(slotId)"))
    suspend fun clearAllData(slotId: Int = DEFAULT_SLOT_ID) = gameDataRepository.clearAllData(slotId)

    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slotId, SaveData(...))"))
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
        val saveData = SaveData(
            gameData = gameData,
            disciples = disciples,
            equipmentStacks = equipmentStacks,
            equipmentInstances = equipmentInstances,
            manualStacks = manualStacks,
            manualInstances = manualInstances,
            pills = pills,
            materials = materials,
            herbs = herbs,
            seeds = seeds,
            teams = teams,
            events = events,
            battleLogs = battleLogs,
            productionSlots = productionSlots
        )
        storageFacade.save(slotId, saveData)
    }
}
