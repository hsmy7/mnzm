package com.xianxia.sect.core.repository

import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.BuildingSlot
import com.xianxia.sect.core.model.Dungeon
import com.xianxia.sect.core.model.ExplorationTeam
import com.xianxia.sect.core.model.GameEvent
import com.xianxia.sect.core.model.Recipe
import com.xianxia.sect.core.model.RecipeType
import com.xianxia.sect.data.local.BattleLogDao
import com.xianxia.sect.data.local.BuildingSlotDao
import com.xianxia.sect.data.local.DungeonDao
import com.xianxia.sect.data.local.ExplorationTeamDao
import com.xianxia.sect.data.local.GameEventDao
import com.xianxia.sect.data.local.RecipeDao
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorldRepository @Inject constructor(
    private val explorationTeamDao: ExplorationTeamDao,
    private val buildingSlotDao: BuildingSlotDao,
    private val dungeonDao: DungeonDao,
    private val recipeDao: RecipeDao,
    private val gameEventDao: GameEventDao,
    private val battleLogDao: BattleLogDao
) {
    companion object {
        const val DEFAULT_SLOT_ID = 0
    }

    // ==================== ExplorationTeam ====================

    fun getTeams(slotId: Int = DEFAULT_SLOT_ID): Flow<List<ExplorationTeam>> =
        explorationTeamDao.getAll(slotId)

    fun getActiveTeams(slotId: Int = DEFAULT_SLOT_ID): Flow<List<ExplorationTeam>> =
        explorationTeamDao.getActive(slotId)

    suspend fun getTeamById(id: String, slotId: Int = DEFAULT_SLOT_ID): ExplorationTeam? =
        explorationTeamDao.getById(slotId, id)

    suspend fun getAllTeamsSync(slotId: Int = DEFAULT_SLOT_ID): List<ExplorationTeam> =
        explorationTeamDao.getAllSync(slotId)

    // ==================== BuildingSlot ====================

    fun getBuildingSlots(buildingId: String, slotId: Int = DEFAULT_SLOT_ID): Flow<List<BuildingSlot>> =
        buildingSlotDao.getByBuilding(slotId, buildingId)

    fun getAllBuildingSlots(slotId: Int = DEFAULT_SLOT_ID): Flow<List<BuildingSlot>> =
        buildingSlotDao.getAll(slotId)

    suspend fun getBuildingSlotsSync(buildingId: String, slotId: Int = DEFAULT_SLOT_ID): List<BuildingSlot> =
        buildingSlotDao.getByBuildingSync(slotId, buildingId)

    // ==================== Dungeon ====================

    fun getUnlockedDungeons(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Dungeon>> =
        dungeonDao.getUnlocked(slotId)

    fun getAllDungeons(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Dungeon>> =
        dungeonDao.getAll(slotId)

    suspend fun getDungeonById(id: String, slotId: Int = DEFAULT_SLOT_ID): Dungeon? =
        dungeonDao.getById(slotId, id)

    // ==================== Recipe ====================

    fun getUnlockedRecipes(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Recipe>> =
        recipeDao.getUnlocked(slotId)

    fun getAllRecipes(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Recipe>> =
        recipeDao.getAll(slotId)

    fun getRecipesByType(type: RecipeType, slotId: Int = DEFAULT_SLOT_ID): Flow<List<Recipe>> =
        recipeDao.getByType(slotId, type)

    suspend fun getRecipeById(id: String, slotId: Int = DEFAULT_SLOT_ID): Recipe? =
        recipeDao.getById(slotId, id)

    // ==================== GameEvent ====================

    fun getRecentEvents(limit: Int = 50, slotId: Int = DEFAULT_SLOT_ID): Flow<List<GameEvent>> =
        gameEventDao.getRecent(slotId, limit)

    fun getAllEvents(slotId: Int = DEFAULT_SLOT_ID): Flow<List<GameEvent>> =
        gameEventDao.getAll(slotId)

    // ==================== BattleLog ====================

    fun getRecentBattleLogs(limit: Int = 50, slotId: Int = DEFAULT_SLOT_ID): Flow<List<BattleLog>> =
        battleLogDao.getRecent(slotId, limit)

    fun getAllBattleLogs(slotId: Int = DEFAULT_SLOT_ID): Flow<List<BattleLog>> =
        battleLogDao.getAll(slotId)

    suspend fun getBattleLogById(id: String, slotId: Int = DEFAULT_SLOT_ID): BattleLog? =
        battleLogDao.getById(slotId, id)
}
