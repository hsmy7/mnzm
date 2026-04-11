package com.xianxia.sect.data.archive

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ArchivedBattleLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<ArchivedBattleLog>)

    @Query("SELECT * FROM archived_battle_logs WHERE slot_id = :slotId ORDER BY timestamp DESC")
    suspend fun getBySlot(slotId: Int): List<ArchivedBattleLog>

    @Query("SELECT * FROM archived_battle_logs WHERE slot_id = :slotId AND timestamp BETWEEN :startMs AND :endMs ORDER BY timestamp DESC")
    suspend fun getByTimeRange(slotId: Int, startMs: Long, endMs: Long): List<ArchivedBattleLog>

    @Query("SELECT COUNT(*) FROM archived_battle_logs WHERE slot_id = :slotId")
    suspend fun getCountBySlot(slotId: Int): Int

    @Query("DELETE FROM archived_battle_logs WHERE archived_at < :threshold")
    suspend fun deleteArchivedBefore(threshold: Long): Int

    @Query("DELETE FROM archived_battle_logs WHERE slot_id = :slotId")
    suspend fun deleteBySlot(slotId: Int)
}

@Dao
interface ArchivedGameEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<ArchivedGameEvent>)

    @Query("SELECT * FROM archived_game_events WHERE slot_id = :slotId ORDER BY timestamp DESC")
    suspend fun getBySlot(slotId: Int): List<ArchivedGameEvent>

    @Query("SELECT * FROM archived_game_events WHERE slot_id = :slotId AND timestamp BETWEEN :startMs AND :endMs ORDER BY timestamp DESC")
    suspend fun getByTimeRange(slotId: Int, startMs: Long, endMs: Long): List<ArchivedGameEvent>

    @Query("SELECT COUNT(*) FROM archived_game_events WHERE slot_id = :slotId")
    suspend fun getCountBySlot(slotId: Int): Int

    @Query("DELETE FROM archived_game_events WHERE archived_at < :threshold")
    suspend fun deleteArchivedBefore(threshold: Long): Int

    @Query("DELETE FROM archived_game_events WHERE slot_id = :slotId")
    suspend fun deleteBySlot(slotId: Int)
}

@Dao
interface ArchivedDiscipleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(disciples: List<ArchivedDisciple>)

    @Query("SELECT * FROM archived_disciples WHERE slot_id = :slotId ORDER BY archived_at DESC")
    suspend fun getBySlot(slotId: Int): List<ArchivedDisciple>

    @Query("SELECT * FROM archived_disciples WHERE slot_id = :slotId AND name LIKE '%' || :keyword || '%'")
    suspend fun searchByName(slotId: Int, keyword: String): List<ArchivedDisciple>

    @Query("SELECT COUNT(*) FROM archived_disciples WHERE slot_id = :slotId")
    suspend fun getCountBySlot(slotId: Int): Int

    @Query("DELETE FROM archived_disciples WHERE archived_at < :threshold")
    suspend fun deleteArchivedBefore(threshold: Long): Int

    @Query("DELETE FROM archived_disciples WHERE slot_id = :slotId")
    suspend fun deleteBySlot(slotId: Int)
}
