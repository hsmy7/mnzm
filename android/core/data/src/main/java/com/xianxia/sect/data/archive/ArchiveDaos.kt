package com.xianxia.sect.data.archive

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ArchivedBattleLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<ArchivedBattleLog>)

    @Query("DELETE FROM archived_battle_logs WHERE archived_at < :threshold")
    suspend fun deleteArchivedBefore(threshold: Long): Int

    @Query("DELETE FROM archived_battle_logs WHERE slot_id = :slotId")
    suspend fun deleteBySlot(slotId: Int)
}

@Dao
interface ArchivedDiscipleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(disciples: List<ArchivedDisciple>)

    @Query("DELETE FROM archived_disciples WHERE archived_at < :threshold")
    suspend fun deleteArchivedBefore(threshold: Long): Int

    @Query("DELETE FROM archived_disciples WHERE slot_id = :slotId")
    suspend fun deleteBySlot(slotId: Int)
}
