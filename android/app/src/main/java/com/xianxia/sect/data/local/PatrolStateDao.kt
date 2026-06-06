package com.xianxia.sect.data.local

import androidx.room.*
import com.xianxia.sect.core.model.PatrolStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PatrolStateDao {
    @Query("SELECT * FROM patrol_state WHERE slot_id = :slotId")
    suspend fun getBySlot(slotId: Int): PatrolStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(state: PatrolStateEntity)

    @Update
    suspend fun update(state: PatrolStateEntity)

    @Query("SELECT * FROM patrol_state WHERE slot_id = :slotId")
    fun observeBySlot(slotId: Int): Flow<PatrolStateEntity?>

    @Upsert
    suspend fun upsert(state: PatrolStateEntity)

    @Query("DELETE FROM patrol_state WHERE slot_id = :slotId")
    suspend fun deleteBySlot(slotId: Int)
}
