package com.xianxia.sect.data.local

import androidx.room.*
import com.xianxia.sect.core.model.ProductionState
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductionStateDao {
    @Query("SELECT * FROM production_state WHERE slot_id = :slotId")
    suspend fun getBySlot(slotId: Int): ProductionState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(state: ProductionState)

    @Update
    suspend fun update(state: ProductionState)

    @Query("SELECT * FROM production_state WHERE slot_id = :slotId")
    fun observeBySlot(slotId: Int): Flow<ProductionState?>

    @Upsert
    suspend fun upsert(state: ProductionState)

    @Query("DELETE FROM production_state WHERE slot_id = :slotId")
    suspend fun deleteBySlot(slotId: Int)
}
