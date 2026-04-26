package com.xianxia.sect.core.repository

import androidx.room.withTransaction
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.data.local.GameDataDao
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GameDataRepository @Inject constructor(
    private val database: GameDatabase,
    private val gameDataDao: GameDataDao
) {
    companion object {
        const val DEFAULT_SLOT_ID = 0
    }

    fun getGameData(slotId: Int = DEFAULT_SLOT_ID): Flow<GameData?> =
        gameDataDao.getGameData(slotId)

    suspend fun getGameDataSync(slotId: Int = DEFAULT_SLOT_ID): GameData? =
        gameDataDao.getGameDataSync(slotId)

    suspend fun initializeNewGame(): GameData {
        val gameData = GameData(id = "game_data_0")
        gameDataDao.insert(gameData)
        return gameData
    }

    suspend fun clearAllData(slotId: Int = DEFAULT_SLOT_ID) {
        database.withTransaction {
            gameDataDao.deleteAll(slotId)
            database.discipleDao().deleteAll(slotId)
            database.discipleCoreDao().deleteAll(slotId)
            database.discipleCombatStatsDao().deleteAll(slotId)
            database.discipleEquipmentDao().deleteAll(slotId)
            database.discipleExtendedDao().deleteAll(slotId)
            database.discipleAttributesDao().deleteAll(slotId)
            database.equipmentStackDao().deleteAll(slotId)
            database.equipmentInstanceDao().deleteAll(slotId)
            database.manualStackDao().deleteAll(slotId)
            database.manualInstanceDao().deleteAll(slotId)
            database.pillDao().deleteAll(slotId)
            database.materialDao().deleteAll(slotId)
            database.seedDao().deleteAll(slotId)
            database.herbDao().deleteAll(slotId)
            database.explorationTeamDao().deleteAll(slotId)
            database.buildingSlotDao().deleteAll(slotId)
            database.gameEventDao().deleteAll(slotId)
            database.dungeonDao().deleteAll(slotId)
            database.recipeDao().deleteAll(slotId)
            database.battleLogDao().deleteAll(slotId)
            database.forgeSlotDao().deleteAll(slotId)
            database.alchemySlotDao().deleteAll(slotId)
            database.productionSlotDao().deleteBySlot(slotId)
        }
    }
}
