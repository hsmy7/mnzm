package com.xianxia.sect.core.repository

import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.BuildingSlot
import com.xianxia.sect.core.model.ExplorationTeam
import com.xianxia.sect.core.model.Recipe
import com.xianxia.sect.core.model.RecipeType
import com.xianxia.sect.data.local.BattleLogDao
import com.xianxia.sect.data.local.BuildingSlotDao
import com.xianxia.sect.data.local.ExplorationTeamDao
import com.xianxia.sect.data.local.RecipeDao
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorldRepositoryImpl @Inject constructor(
    private val explorationTeamDao: ExplorationTeamDao,
    private val buildingSlotDao: BuildingSlotDao,
    private val recipeDao: RecipeDao,
    private val battleLogDao: BattleLogDao
) : WorldRepository {
    companion object {
        const val DEFAULT_SLOT_ID = 0
    }

    // ==================== ExplorationTeam ====================

    override fun getTeams(slotId: Int): Flow<List<ExplorationTeam>> =
        explorationTeamDao.getAll(slotId)

    override fun getActiveTeams(slotId: Int): Flow<List<ExplorationTeam>> =
        explorationTeamDao.getActive(slotId)

    override suspend fun getTeamById(id: String, slotId: Int): ExplorationTeam? =
        explorationTeamDao.getById(slotId, id)

    override suspend fun getAllTeamsSync(slotId: Int): List<ExplorationTeam> =
        explorationTeamDao.getAllSync(slotId)

    // ==================== BuildingSlot ====================

    override fun getBuildingSlots(buildingId: String, slotId: Int): Flow<List<BuildingSlot>> =
        buildingSlotDao.getByBuilding(slotId, buildingId)

    override fun getAllBuildingSlots(slotId: Int): Flow<List<BuildingSlot>> =
        buildingSlotDao.getAll(slotId)

    override suspend fun getBuildingSlotsSync(buildingId: String, slotId: Int): List<BuildingSlot> =
        buildingSlotDao.getByBuildingSync(slotId, buildingId)

    // Dungeon methods removed

    // ==================== Recipe ====================

    override fun getUnlockedRecipes(slotId: Int): Flow<List<Recipe>> =
        recipeDao.getUnlocked(slotId)

    override fun getAllRecipes(slotId: Int): Flow<List<Recipe>> =
        recipeDao.getAll(slotId)

    override fun getRecipesByType(type: RecipeType, slotId: Int): Flow<List<Recipe>> =
        recipeDao.getByType(slotId, type)

    override suspend fun getRecipeById(id: String, slotId: Int): Recipe? =
        recipeDao.getById(slotId, id)

    // ==================== BattleLog ====================

    override fun getRecentBattleLogs(limit: Int, slotId: Int): Flow<List<BattleLog>> =
        battleLogDao.getRecent(slotId, limit)

    override fun getAllBattleLogs(slotId: Int): Flow<List<BattleLog>> =
        battleLogDao.getAll(slotId)

    override suspend fun getBattleLogById(id: String, slotId: Int): BattleLog? =
        battleLogDao.getById(slotId, id)
}
