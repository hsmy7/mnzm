package com.xianxia.sect.data.local

import androidx.room.*
import com.xianxia.sect.core.model.DiplomacyState

@Dao
interface DiplomacyStateDao {
    @Query("SELECT * FROM diplomacy_state WHERE slot_id = :slotId")
    suspend fun getBySlot(slotId: Int): DiplomacyState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(state: DiplomacyState)

    @Update
    suspend fun update(state: DiplomacyState)

    @Query("DELETE FROM diplomacy_state WHERE slot_id = :slotId")
    suspend fun deleteBySlot(slotId: Int)
}
