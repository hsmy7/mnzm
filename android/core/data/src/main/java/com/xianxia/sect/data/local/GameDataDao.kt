package com.xianxia.sect.data.local

import androidx.room.ColumnInfo
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
 * 单体存档的核心数据表，每次读写操作均为单行（slot_id 维度）。
 */
@Dao
interface GameDataDao {
    @Query("SELECT * FROM game_data WHERE slot_id = :slotId ORDER BY lastSaveTime DESC LIMIT 1")
    fun getGameData(slotId: Int): Flow<GameData?>

    @Query("SELECT * FROM game_data WHERE slot_id = :slotId ORDER BY lastSaveTime DESC LIMIT 1")
    suspend fun getGameDataSync(slotId: Int): GameData?

    @Query("SELECT slot_id, sectName, gameYear, gameMonth, gamePhase, spiritStones, spiritHerbs, sectCultivation, isGameStarted, lastSaveTime FROM game_data WHERE slot_id = :slotId LIMIT 1")
    suspend fun getMetadataBySlot(slotId: Int): GameDataMetadataProjection?

    @Query("SELECT slot_id, sectName, gameYear, gameMonth, gamePhase, spiritStones, spiritHerbs, sectCultivation, isGameStarted, lastSaveTime FROM game_data ORDER BY slot_id ASC")
    suspend fun getAllMetadata(): List<GameDataMetadataProjection>

    @Query("SELECT slot_id FROM game_data WHERE slot_id = :slotId LIMIT 1")
    suspend fun existsBySlot(slotId: Int): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(gameData: GameData)

    @Update
    suspend fun update(gameData: GameData)

    @Query("SELECT id FROM game_data WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>

    @Query("DELETE FROM game_data WHERE slot_id = :slotId")
    suspend fun deleteAll(slotId: Int)

    @Query("DELETE FROM game_data")
    suspend fun deleteAllGlobal()
}

/** 存档元数据投影（不含完整 GameData 的大字段） */
data class GameDataMetadataProjection(
    @ColumnInfo(name = "slot_id")
    val slotId: Int,
    val sectName: String,
    val gameYear: Int,
    val gameMonth: Int,
    val gamePhase: Int,
    val spiritStones: Long,
    val spiritHerbs: Int,
    val sectCultivation: Double,
    val isGameStarted: Boolean,
    val lastSaveTime: Long
)
