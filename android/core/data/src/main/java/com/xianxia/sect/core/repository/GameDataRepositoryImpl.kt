package com.xianxia.sect.core.repository

import androidx.room.withTransaction
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.data.local.GameDataDao
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GameDataRepositoryImpl @Inject constructor(
    private val database: GameDatabase,
    private val gameDataDao: GameDataDao
) : GameDataRepository {
    companion object {
        const val DEFAULT_SLOT_ID = 0
    }

    override fun getGameData(): Flow<GameData?> =
        gameDataDao.getGameData()

    override suspend fun getGameDataSync(): GameData? =
        gameDataDao.getGameDataSync()

    override suspend fun initializeNewGame(): GameData {
        val gameData = GameData(id = "game_data_0")
        gameDataDao.insert(gameData)
        return gameData
    }

    override suspend fun clearAllData() {
        database.withTransaction {
            gameDataDao.deleteAll()
            database.discipleDao().deleteAll()
            database.equipmentInstanceDao().deleteAll()
            database.manualStackDao().deleteAll()
            database.manualInstanceDao().deleteAll()
            database.pillDao().deleteAll()
            database.materialDao().deleteAll()
            database.seedDao().deleteAll()
            database.herbDao().deleteAll()
            database.buildingSlotDao().deleteAll()
            database.recipeDao().deleteAll()
            database.battleLogDao().deleteAll()
            database.productionSlotDao().deleteAll()
        }
    }
}
