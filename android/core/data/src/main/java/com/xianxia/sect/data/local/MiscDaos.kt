package com.xianxia.sect.data.local

import androidx.room.*
import com.xianxia.sect.core.model.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ExplorationTeamDao {
    @Query("SELECT * FROM exploration_teams WHERE slot_id = :slotId")
    fun getAll(slotId: Int): Flow<List<ExplorationTeam>>

    @Query("SELECT * FROM exploration_teams WHERE slot_id = :slotId AND status != 'COMPLETED'")
    fun getActive(slotId: Int): Flow<List<ExplorationTeam>>

    @Query("SELECT * FROM exploration_teams WHERE slot_id = :slotId AND id = :id")
    fun getById(slotId: Int, id: String): ExplorationTeam?

    @Query("SELECT * FROM exploration_teams WHERE slot_id = :slotId")
    fun getAllSync(slotId: Int): List<ExplorationTeam>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(team: ExplorationTeam)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(teams: List<ExplorationTeam>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(teams: List<ExplorationTeam>)

    @Update
    fun update(team: ExplorationTeam)

    @Update
    fun updateAll(teams: List<ExplorationTeam>)

    @Query("SELECT id FROM exploration_teams WHERE slot_id = :slotId")
    fun getIdsBySlot(slotId: Int): List<String>

    @Delete
    fun delete(team: ExplorationTeam)

    @Query("DELETE FROM exploration_teams WHERE slot_id = :slotId AND id = :id")
    fun deleteById(slotId: Int, id: String)

    @Query("DELETE FROM exploration_teams WHERE slot_id = :slotId")
    fun deleteAll(slotId: Int)

    @Query("DELETE FROM exploration_teams")
    fun deleteAllGlobal()
}

@Dao
interface GameHeavyDataDao {
    @Query("SELECT * FROM game_heavy_data WHERE slot_id = :slotId AND data_key = :key")
    fun getByKey(slotId: Int, key: String): GameHeavyData?

    @Query("SELECT * FROM game_heavy_data WHERE slot_id = :slotId")
    fun getAllForSlot(slotId: Int): List<GameHeavyData>

    @Query("SELECT data_key FROM game_heavy_data WHERE slot_id = :slotId")
    fun getLoadedKeys(slotId: Int): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(data: GameHeavyData)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(data: List<GameHeavyData>)

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
