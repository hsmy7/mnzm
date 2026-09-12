package com.xianxia.sect.data.local

import androidx.room.*
import com.xianxia.sect.core.model.GameHeavyData



@Dao
interface GameHeavyDataDao {
    @Query("SELECT * FROM game_heavy_data WHERE slot_id = :slotId AND data_key = :key")
    fun getByKey(slotId: Int, key: String): GameHeavyData?

    @Query("SELECT * FROM game_heavy_data WHERE slot_id = :slotId")
    fun getAllForSlot(slotId: Int): List<GameHeavyData>

    @Query("SELECT data_key FROM game_heavy_data WHERE slot_id = :slotId")
    fun getLoadedKeys(slotId: Int): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(data: GameHeavyData)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(data: List<GameHeavyData>)

    @Query("DELETE FROM game_heavy_data WHERE slot_id = :slotId")
    fun deleteAllForSlot(slotId: Int)

    @Query("DELETE FROM game_heavy_data WHERE slot_id = :slotId AND data_key = :key")
    fun deleteByKey(slotId: Int, key: String)

    @Query("DELETE FROM game_heavy_data WHERE slot_id = :slotId AND data_key LIKE :pattern")
    fun deleteByKeyPattern(slotId: Int, pattern: String)

    @Query("DELETE FROM game_heavy_data WHERE slot_id = :slot AND data_key LIKE :prefix || '%'")
    fun deleteByKeyPrefix(slot: Int, prefix: String)

    @Query("SELECT * FROM game_heavy_data WHERE slot_id = :slot AND data_key LIKE :prefix || '%' ORDER BY data_key")
    fun getByPrefix(slot: Int, prefix: String): List<GameHeavyData>
}
