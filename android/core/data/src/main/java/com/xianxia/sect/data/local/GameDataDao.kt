package com.xianxia.sect.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.xianxia.sect.core.model.GameData
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO — game_data 表。
 *
 * 单档核心数据表，全表恒单行。
 */
@Dao
interface GameDataDao {
    @Query("SELECT * FROM game_data ORDER BY lastSaveTime DESC LIMIT 1")
    fun getGameData(): Flow<GameData?>

    @Query("SELECT * FROM game_data ORDER BY lastSaveTime DESC LIMIT 1")
    suspend fun getGameDataSync(): GameData?

    @Query("SELECT sectName, gameYear, gameMonth, gamePhase, spiritStones, spiritHerbs, sectCultivation, " +
        "lastSaveTime FROM game_data LIMIT 1")
    suspend fun getMetadata(): GameDataMetadataProjection?

    @Query("SELECT sectName FROM game_data LIMIT 1")
    suspend fun existsAny(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(gameData: GameData)

    @Update
    suspend fun update(gameData: GameData)

    @Query("DELETE FROM game_data")
    suspend fun deleteAll()
}

/** 存档元数据投影（不含完整 GameData 的大字段） */
data class GameDataMetadataProjection(
    val sectName: String,
    val gameYear: Int,
    val gameMonth: Int,
    val gamePhase: Int,
    val spiritStones: Long,
    val spiritHerbs: Int,
    val sectCultivation: Double,
    val lastSaveTime: Long
)
