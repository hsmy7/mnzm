package com.xianxia.sect.data.local

import androidx.room.*
import com.xianxia.sect.core.model.WorldMapStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorldMapStateDao {
    @Query("SELECT * FROM world_map_state WHERE slot_id = :slotId")
    suspend fun getBySlot(slotId: Int): WorldMapStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(state: WorldMapStateEntity)

    @Update
    suspend fun update(state: WorldMapStateEntity)

    @Query("SELECT * FROM world_map_state WHERE slot_id = :slotId")
    fun observeBySlot(slotId: Int): Flow<WorldMapStateEntity?>

    @Upsert
    suspend fun upsert(state: WorldMapStateEntity)

    @Query("DELETE FROM world_map_state WHERE slot_id = :slotId")
    suspend fun deleteBySlot(slotId: Int)
}
