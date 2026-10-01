package com.xianxia.sect.core.repository

import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.BuildingSlot
import com.xianxia.sect.core.model.Recipe
import com.xianxia.sect.core.model.RecipeType
import com.xianxia.sect.data.local.BattleLogDao
import com.xianxia.sect.data.local.BuildingSlotDao
import com.xianxia.sect.data.local.RecipeDao
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorldRepositoryImpl @Inject constructor(
    private val buildingSlotDao: BuildingSlotDao,
    private val recipeDao: RecipeDao,
    private val battleLogDao: BattleLogDao
) : WorldRepository {
    companion object {
        const val DEFAULT_SLOT_ID = 0
    }

    // ==================== BuildingSlot ====================

    override fun getBuildingSlots(buildingId: String): Flow<List<BuildingSlot>> =
        buildingSlotDao.getByBuilding(buildingId)

    override fun getAllBuildingSlots(): Flow<List<BuildingSlot>> =
        buildingSlotDao.getAll()

    override suspend fun getBuildingSlotsSync(buildingId: String): List<BuildingSlot> =
        buildingSlotDao.getByBuildingSync(buildingId)

    // Dungeon methods removed

    // ==================== Recipe ====================

    override fun getUnlockedRecipes(): Flow<List<Recipe>> =
        recipeDao.getUnlocked()

    override fun getAllRecipes(): Flow<List<Recipe>> =
        recipeDao.getAll()

    override fun getRecipesByType(type: RecipeType): Flow<List<Recipe>> =
        recipeDao.getByType(type)

    override suspend fun getRecipeById(id: String): Recipe? =
        recipeDao.getById(id)

    // ==================== BattleLog ====================

    override fun getRecentBattleLogs(limit: Int): Flow<List<BattleLog>> =
        battleLogDao.getRecent(limit)

    override fun getAllBattleLogs(): Flow<List<BattleLog>> =
        battleLogDao.getAll()

    override suspend fun getBattleLogById(id: String): BattleLog? =
        battleLogDao.getById(id)
}
