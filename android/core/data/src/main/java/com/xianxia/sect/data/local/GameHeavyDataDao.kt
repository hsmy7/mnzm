package com.xianxia.sect.data.local

import androidx.room.*
import com.xianxia.sect.core.model.GameHeavyData



@Dao
interface GameHeavyDataDao {
    @Query("SELECT * FROM game_heavy_data WHERE data_key = :key")
    fun getByKey(key: String): GameHeavyData?

    @Query("SELECT * FROM game_heavy_data")
    fun getAll(): List<GameHeavyData>

    @Query("SELECT data_key FROM game_heavy_data")
    fun getLoadedKeys(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(data: GameHeavyData)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(data: List<GameHeavyData>)

    @Query("DELETE FROM game_heavy_data")
    fun deleteAll()

    @Query("DELETE FROM game_heavy_data WHERE data_key = :key")
    fun deleteByKey(key: String)

    @Query("DELETE FROM game_heavy_data WHERE data_key LIKE :pattern")
    fun deleteByKeyPattern(pattern: String)

    @Query("DELETE FROM game_heavy_data WHERE data_key LIKE :prefix || '%'")
    fun deleteByKeyPrefix(prefix: String)

    @Query("SELECT * FROM game_heavy_data WHERE data_key LIKE :prefix || '%' ORDER BY data_key")
    fun getByPrefix(prefix: String): List<GameHeavyData>
}
