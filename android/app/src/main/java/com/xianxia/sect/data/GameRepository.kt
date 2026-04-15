@file:Suppress("DEPRECATION")

package com.xianxia.sect.data

import android.util.Log
import androidx.room.withTransaction
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.data.local.*
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 单表 CRUD 操作层（支持参数化 slotId）
 *
 * 设计说明：
 * - 本类负责当前游戏实例的细粒度数据查询和写入操作
 * - 所有查询方法均支持 [slotId] 参数（默认值为 0，保持向后兼容）
 * - 写入方法（add/update/delete）不涉及槽位过滤，维持实体级操作语义
 *
 * ⚠️ slotId 语义说明：
 * - slotId=0：当前活跃游戏实例（运行时数据，默认值）
 * - slotId=1~6：对应存档槽位（存档中的历史数据）
 *
 * 推荐做法：
 * - 新代码应优先通过 [StorageFacade] 访问存储系统
 * - 直接使用本类可能导致存储路径混乱（三重路径问题）
 * - 仅在确实需要直接操作底层 DAO 时才使用本类
 *
 * 与其他 Repository 的关系：
 * - [StorageFacade]：统一门面类，推荐入口
 * - 本类：负责指定槽位的细粒度 CRUD 操作
 */
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
    private val forgeSlotDao: ForgeSlotDao,
    private val alchemySlotDao: AlchemySlotDao,
    private val productionSlotDao: ProductionSlotDao
) {
    companion object {
        private const val TAG = "GameRepository"
        /** 默认槽位 ID，表示当前活跃的游戏实例 */
        const val DEFAULT_SLOT_ID = 0
    }

    init {
        // 初始化时记录警告，提醒开发者应优先使用 StorageGateway
        Log.w(TAG, "GameRepository initialized directly. " +
                "Consider using StorageGateway for unified storage access. " +
                "Direct usage may cause storage path confusion.")
    }

    // ==================== GameData ====================

    /** 获取游戏数据的 Flow（响应式），默认查询 slot 0 */
    fun getGameData(slotId: Int = DEFAULT_SLOT_ID): Flow<GameData?> = gameDataDao.getGameData(slotId)
    
    /** 同步获取游戏数据，默认查询 slot 0 */
    suspend fun getGameDataSync(slotId: Int = DEFAULT_SLOT_ID): GameData? = gameDataDao.getGameDataSync(slotId)
    
    suspend fun saveGameData(gameData: GameData) = gameDataDao.insert(gameData)
    
    suspend fun updateGameData(gameData: GameData) = gameDataDao.update(gameData)

    // ==================== Disciple（弟子）====================

    /** 获取所有弟子的 Flow，默认查询 slot 0 */
    fun getDisciples(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Disciple>> = discipleDao.getAll(slotId)
    
    /** 获取存活弟子的 Flow，默认查询 slot 0 */
    fun getAliveDisciples(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Disciple>> = discipleDao.getAllAlive(slotId)
    
    /** 根据 ID 获取弟子，默认查询 slot 0 */
    suspend fun getDiscipleById(id: String, slotId: Int = DEFAULT_SLOT_ID): Disciple? = discipleDao.getById(slotId, id)
    
    /** 按状态筛选弟子，默认查询 slot 0 */
    suspend fun getDisciplesByStatus(status: DiscipleStatus, slotId: Int = DEFAULT_SLOT_ID): List<Disciple> = 
        discipleDao.getByStatus(slotId, status)
    
    /** 同步获取所有存活弟子，默认查询 slot 0 */
    suspend fun getAllDisciplesSync(slotId: Int = DEFAULT_SLOT_ID): List<Disciple> = 
        discipleDao.getAllAliveSync(slotId)
    
    suspend fun addDisciple(disciple: Disciple) = discipleDao.insert(disciple)
    
    suspend fun addDisciples(disciples: List<Disciple>) = discipleDao.insertAll(disciples)
    
    suspend fun updateDisciple(disciple: Disciple) = discipleDao.update(disciple)
    
    suspend fun updateDisciples(disciples: List<Disciple>) = discipleDao.updateAll(disciples)
    
    suspend fun deleteDisciple(disciple: Disciple) = discipleDao.delete(disciple)

    // ==================== Equipment（装备）====================

    /** 获取所有装备的 Flow，默认查询 slot 0 */
    fun getEquipment(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Equipment>> = equipmentDao.getAll(slotId)
    
    /** 获取未装备的装备 Flow，默认查询 slot 0 */
    fun getUnequippedEquipment(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Equipment>> = equipmentDao.getUnequipped(slotId)
    
    /** 根据 ID 获取装备，默认查询 slot 0 */
    suspend fun getEquipmentById(id: String, slotId: Int = DEFAULT_SLOT_ID): Equipment? = equipmentDao.getById(slotId, id)
    
    /** 按拥有者获取装备列表，默认查询 slot 0 */
    suspend fun getEquipmentByOwner(discipleId: String, slotId: Int = DEFAULT_SLOT_ID): List<Equipment> = 
        equipmentDao.getByOwner(slotId, discipleId)
    
    suspend fun addEquipment(equipment: Equipment) = equipmentDao.insert(equipment)
    
    suspend fun addEquipments(equipments: List<Equipment>) = equipmentDao.insertAll(equipments)
    
    suspend fun updateEquipment(equipment: Equipment) = equipmentDao.update(equipment)
    
    suspend fun deleteEquipment(equipment: Equipment) = equipmentDao.delete(equipment)

    // ==================== Manual（功法）====================

    /** 获取所有功法的 Flow，默认查询 slot 0 */
    fun getManuals(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Manual>> = manualDao.getAll(slotId)
    
    /** 获取未学习功法的 Flow，默认查询 slot 0 */
    fun getUnlearnedManuals(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Manual>> = manualDao.getUnlearned(slotId)
    
    /** 根据 ID 获取功法，默认查询 slot 0 */
    suspend fun getManualById(id: String, slotId: Int = DEFAULT_SLOT_ID): Manual? = manualDao.getById(slotId, id)
    
    /** 按拥有者获取功法列表，默认查询 slot 0 */
    suspend fun getManualsByOwner(discipleId: String, slotId: Int = DEFAULT_SLOT_ID): List<Manual> = 
        manualDao.getByOwner(slotId, discipleId)
    
    suspend fun addManual(manual: Manual) = manualDao.insert(manual)
    
    suspend fun addManuals(manuals: List<Manual>) = manualDao.insertAll(manuals)
    
    suspend fun updateManual(manual: Manual) = manualDao.update(manual)
    
    suspend fun deleteManual(manual: Manual) = manualDao.delete(manual)

    // ==================== Pill（丹药）====================

    /** 获取所有丹药的 Flow，默认查询 slot 0 */
    fun getPills(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Pill>> = pillDao.getAll(slotId)
    
    /** 根据 ID 获取丹药，默认查询 slot 0 */
    suspend fun getPillById(id: String, slotId: Int = DEFAULT_SLOT_ID): Pill? = pillDao.getById(slotId, id)
    
    suspend fun addPill(pill: Pill) = pillDao.insert(pill)
    
    suspend fun addPills(pills: List<Pill>) = pillDao.insertAll(pills)
    
    suspend fun updatePill(pill: Pill) = pillDao.update(pill)
    
    suspend fun deletePill(pill: Pill) = pillDao.delete(pill)

    // ==================== Material（材料）====================

    /** 获取所有材料的 Flow，默认查询 slot 0 */
    fun getMaterials(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Material>> = materialDao.getAll(slotId)
    
    /** 根据 ID 获取材料，默认查询 slot 0 */
    suspend fun getMaterialById(id: String, slotId: Int = DEFAULT_SLOT_ID): Material? = materialDao.getById(slotId, id)
    
    /** 按分类获取材料 Flow，默认查询 slot 0 */
    fun getMaterialsByCategory(category: MaterialCategory, slotId: Int = DEFAULT_SLOT_ID): Flow<List<Material>> = 
        materialDao.getByCategory(slotId, category)
    
    suspend fun addMaterial(material: Material) = materialDao.insert(material)
    
    suspend fun addMaterials(materials: List<Material>) = materialDao.insertAll(materials)
    
    suspend fun updateMaterial(material: Material) = materialDao.update(material)
    
    suspend fun deleteMaterial(material: Material) = materialDao.delete(material)

    // ==================== Seed（种子）====================

    /** 获取所有种子的 Flow，默认查询 slot 0 */
    fun getSeeds(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Seed>> = seedDao.getAll(slotId)
    
    /** 根据 ID 获取种子，默认查询 slot 0 */
    suspend fun getSeedById(id: String, slotId: Int = DEFAULT_SLOT_ID): Seed? = seedDao.getById(slotId, id)
    
    suspend fun addSeed(seed: Seed) = seedDao.insert(seed)
    
    suspend fun addSeeds(seeds: List<Seed>) = seedDao.insertAll(seeds)
    
    suspend fun updateSeed(seed: Seed) = seedDao.update(seed)
    
    suspend fun deleteSeed(seed: Seed) = seedDao.delete(seed)

    // ==================== Herb（草药）====================

    /** 获取所有草药的 Flow，默认查询 slot 0 */
    fun getHerbs(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Herb>> = herbDao.getAll(slotId)
    
    /** 根据 ID 获取草药，默认查询 slot 0 */
    suspend fun getHerbById(id: String, slotId: Int = DEFAULT_SLOT_ID): Herb? = herbDao.getById(slotId, id)
    
    suspend fun addHerb(herb: Herb) = herbDao.insert(herb)
    
    suspend fun addHerbs(herbs: List<Herb>) = herbDao.insertAll(herbs)
    
    suspend fun updateHerb(herb: Herb) = herbDao.update(herb)
    
    suspend fun deleteHerb(herb: Herb) = herbDao.delete(herb)

    // ==================== ExplorationTeam（探索队）====================

    /** 获取所有探索队的 Flow，默认查询 slot 0 */
    fun getTeams(slotId: Int = DEFAULT_SLOT_ID): Flow<List<ExplorationTeam>> = explorationTeamDao.getAll(slotId)
    
    /** 获取活跃探索队的 Flow，默认查询 slot 0 */
    fun getActiveTeams(slotId: Int = DEFAULT_SLOT_ID): Flow<List<ExplorationTeam>> = explorationTeamDao.getActive(slotId)
    
    /** 根据 ID 获取探索队，默认查询 slot 0 */
    suspend fun getTeamById(id: String, slotId: Int = DEFAULT_SLOT_ID): ExplorationTeam? = explorationTeamDao.getById(slotId, id)
    
    /** 同步获取所有探索队，默认查询 slot 0 */
    suspend fun getAllTeamsSync(slotId: Int = DEFAULT_SLOT_ID): List<ExplorationTeam> = explorationTeamDao.getAllSync(slotId)
    
    suspend fun addTeam(team: ExplorationTeam) = explorationTeamDao.insert(team)
    
    suspend fun updateTeam(team: ExplorationTeam) = explorationTeamDao.update(team)
    
    suspend fun updateTeams(teams: List<ExplorationTeam>) = explorationTeamDao.updateAll(teams)
    
    suspend fun deleteTeam(team: ExplorationTeam) = explorationTeamDao.delete(team)

    // ==================== BuildingSlot（建筑槽位）====================

    /** 按建筑 ID 获取建筑槽位，默认查询 slot 0 */
    fun getBuildingSlots(buildingId: String, slotId: Int = DEFAULT_SLOT_ID): Flow<List<BuildingSlot>> = 
        buildingSlotDao.getByBuilding(slotId, buildingId)
    
    /** 获取所有建筑槽位的 Flow，默认查询 slot 0 */
    fun getAllBuildingSlots(slotId: Int = DEFAULT_SLOT_ID): Flow<List<BuildingSlot>> = buildingSlotDao.getAll(slotId)
    
    /** 同步按建筑 ID 获取建筑槽位，默认查询 slot 0 */
    suspend fun getBuildingSlotsSync(buildingId: String, slotId: Int = DEFAULT_SLOT_ID): List<BuildingSlot> = 
        buildingSlotDao.getByBuildingSync(slotId, buildingId)
    
    suspend fun addBuildingSlot(slot: BuildingSlot) = buildingSlotDao.insert(slot)
    
    suspend fun addBuildingSlots(slots: List<BuildingSlot>) = buildingSlotDao.insertAll(slots)
    
    suspend fun updateBuildingSlot(slot: BuildingSlot) = buildingSlotDao.update(slot)
    
    suspend fun deleteBuildingSlot(slot: BuildingSlot) = buildingSlotDao.delete(slot)

    // ==================== GameEvent（游戏事件）====================

    /** 获取最近事件的 Flow，默认查询 slot 0 */
    fun getRecentEvents(limit: Int = 50, slotId: Int = DEFAULT_SLOT_ID): Flow<List<GameEvent>> = gameEventDao.getRecent(slotId, limit)
    
    /** 获取所有事件的 Flow，默认查询 slot 0 */
    fun getAllEvents(slotId: Int = DEFAULT_SLOT_ID): Flow<List<GameEvent>> = gameEventDao.getAll(slotId)
    
    suspend fun addEvent(event: GameEvent) = gameEventDao.insert(event)
    
    /** 删除指定时间之前的事件，默认清理 slot 0 */
    suspend fun deleteOldEvents(before: Long, slotId: Int = DEFAULT_SLOT_ID) = gameEventDao.deleteOld(slotId, before)

    // ==================== Dungeon（地牢）====================

    /** 获取已解锁地牢的 Flow，默认查询 slot 0 */
    fun getUnlockedDungeons(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Dungeon>> = dungeonDao.getUnlocked(slotId)
    
    /** 获取所有地牢的 Flow，默认查询 slot 0 */
    fun getAllDungeons(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Dungeon>> = dungeonDao.getAll(slotId)
    
    /**根据地牢 ID 获取地牢，默认查询 slot 0 */
    suspend fun getDungeonById(id: String, slotId: Int = DEFAULT_SLOT_ID): Dungeon? = dungeonDao.getById(slotId, id)
    
    suspend fun addDungeon(dungeon: Dungeon) = dungeonDao.insert(dungeon)
    
    suspend fun addDungeons(dungeons: List<Dungeon>) = dungeonDao.insertAll(dungeons)
    
    suspend fun updateDungeon(dungeon: Dungeon) = dungeonDao.update(dungeon)

    // ==================== Recipe（配方）====================

    /** 获取已解锁配方的 Flow，默认查询 slot 0 */
    fun getUnlockedRecipes(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Recipe>> = recipeDao.getUnlocked(slotId)
    
    /** 获取所有配方的 Flow，默认查询 slot 0 */
    fun getAllRecipes(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Recipe>> = recipeDao.getAll(slotId)
    
    /** 按类型获取配方 Flow，默认查询 slot 0 */
    fun getRecipesByType(type: RecipeType, slotId: Int = DEFAULT_SLOT_ID): Flow<List<Recipe>> = recipeDao.getByType(slotId, type)
    
    /** 根据配方 ID 获取配方，默认查询 slot 0 */
    suspend fun getRecipeById(id: String, slotId: Int = DEFAULT_SLOT_ID): Recipe? = recipeDao.getById(slotId, id)
    
    suspend fun addRecipe(recipe: Recipe) = recipeDao.insert(recipe)
    
    suspend fun addRecipes(recipes: List<Recipe>) = recipeDao.insertAll(recipes)
    
    suspend fun updateRecipe(recipe: Recipe) = recipeDao.update(recipe)

    // ==================== BattleLog（战斗日志）====================

    /** 获取最近战斗日志的 Flow，默认查询 slot 0 */
    fun getRecentBattleLogs(limit: Int = 50, slotId: Int = DEFAULT_SLOT_ID): Flow<List<BattleLog>> = battleLogDao.getRecent(slotId, limit)
    
    /** 获取所有战斗日志的 Flow，默认查询 slot 0 */
    fun getAllBattleLogs(slotId: Int = DEFAULT_SLOT_ID): Flow<List<BattleLog>> = battleLogDao.getAll(slotId)
    
    /** 根据战斗日志 ID 获取记录，默认查询 slot 0 */
    suspend fun getBattleLogById(id: String, slotId: Int = DEFAULT_SLOT_ID): BattleLog? = battleLogDao.getById(slotId, id)
    
    suspend fun addBattleLog(log: BattleLog) = battleLogDao.insert(log)

    /** 删除指定时间之前的战斗日志，默认清理 slot 0 */
    suspend fun deleteOldBattleLogs(before: Long, slotId: Int = DEFAULT_SLOT_ID) = battleLogDao.deleteOld(slotId, before)

    // ==================== ForgeSlot（锻造槽位）====================

    /** 根据槽位索引获取锻造槽位，默认查询 slot 0 */
    suspend fun getForgeSlotBySlotIndex(slotIndex: Int, slotId: Int = DEFAULT_SLOT_ID): ForgeSlot? = 
        forgeSlotDao.getBySlotIndex(slotId, slotIndex)
    
    suspend fun saveForgeSlot(slot: ForgeSlot) = forgeSlotDao.insert(slot)
    
    suspend fun saveForgeSlots(slots: List<ForgeSlot>) = forgeSlotDao.insertAll(slots)
    
    suspend fun deleteForgeSlot(slot: ForgeSlot) = forgeSlotDao.delete(slot)

    // ==================== 游戏生命周期操作 ====================

    suspend fun initializeNewGame(): GameData {
        val gameData = GameData(id = "game_data_0")
        gameDataDao.insert(gameData)
        return gameData
    }
    
    /**
     * 清除指定槽位的所有数据。
     *
     * @param slotId 目标槽位（默认 0，即当前活跃实例）
     */
    suspend fun clearAllData(slotId: Int = DEFAULT_SLOT_ID) {
        database.withTransaction {
            gameDataDao.deleteAll(slotId)
            discipleDao.deleteAll(slotId)
            database.discipleCoreDao().deleteAll(slotId)
            database.discipleCombatStatsDao().deleteAll(slotId)
            database.discipleEquipmentDao().deleteAll(slotId)
            database.discipleExtendedDao().deleteAll(slotId)
            database.discipleAttributesDao().deleteAll(slotId)
            equipmentDao.deleteAll(slotId)
            manualDao.deleteAll(slotId)
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
    
    /**
     * 批量保存所有游戏数据到指定槽位。
     *
     * 注意：此方法会先清除目标槽位的事件和战斗日志再重新写入。
     *
     * @param slotId 目标槽位（默认 0）
     */
    suspend fun saveAll(
        gameData: GameData,
        disciples: List<Disciple>,
        discipleCores: List<DiscipleCore> = emptyList(),
        discipleCombatStats: List<DiscipleCombatStats> = emptyList(),
        discipleEquipment: List<DiscipleEquipment> = emptyList(),
        discipleExtended: List<DiscipleExtended> = emptyList(),
        discipleAttributes: List<DiscipleAttributes> = emptyList(),
        equipment: List<Equipment>,
        manuals: List<Manual>,
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
            equipmentDao.insertAll(equipment)
            manualDao.insertAll(manuals)
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
