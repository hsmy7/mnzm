package com.xianxia.sect.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SaveSlotMetadataDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(metadata: SaveSlotMetadata)

    @Query("SELECT * FROM save_slot_metadata WHERE slot_id = :slotId")
    suspend fun getBySlotId(slotId: Int): SaveSlotMetadata?

    @Query("SELECT * FROM save_slot_metadata ORDER BY slot_id ASC")
    suspend fun getAll(): List<SaveSlotMetadata>

    @Query("SELECT * FROM save_slot_metadata ORDER BY slot_id ASC")
    fun getAllFlow(): Flow<List<SaveSlotMetadata>>

    @Query("SELECT COUNT(*) FROM save_slot_metadata WHERE is_game_started = 1")
    suspend fun getStartedSlotCount(): Int

    @Query("SELECT * FROM save_slot_metadata WHERE last_save_time > 0 ORDER BY last_save_time DESC LIMIT :limit")
    suspend fun getRecentlySaved(limit: Int): List<SaveSlotMetadata>

    @Query("DELETE FROM save_slot_metadata WHERE slot_id = :slotId")
    suspend fun deleteBySlotId(slotId: Int)

    @Query("DELETE FROM save_slot_metadata")
    suspend fun deleteAll()
}
