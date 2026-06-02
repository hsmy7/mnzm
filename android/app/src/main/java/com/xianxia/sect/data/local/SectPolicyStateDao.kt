package com.xianxia.sect.data.local

import androidx.room.*
import com.xianxia.sect.core.model.SectPolicyState

@Dao
interface SectPolicyStateDao {
    @Query("SELECT * FROM sect_policy_state WHERE slot_id = :slotId")
    suspend fun getBySlot(slotId: Int): SectPolicyState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(state: SectPolicyState)

    @Update
    suspend fun update(state: SectPolicyState)

    @Query("DELETE FROM sect_policy_state WHERE slot_id = :slotId")
    suspend fun deleteBySlot(slotId: Int)
}
