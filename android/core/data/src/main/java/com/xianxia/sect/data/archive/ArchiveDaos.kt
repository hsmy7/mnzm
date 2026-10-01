package com.xianxia.sect.data.archive

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ArchivedBattleLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<ArchivedBattleLog>)

    @Query("SELECT * FROM archived_battle_logs ORDER BY archived_at DESC LIMIT :limit")
    suspend fun listRecent(limit: Int): List<ArchivedBattleLog>

    @Query("SELECT * FROM archived_battle_logs WHERE id = :id")
    suspend fun getById(id: Long): ArchivedBattleLog?

    @Query("SELECT COUNT(*) FROM archived_battle_logs")
    suspend fun countAll(): Int

    @Query("DELETE FROM archived_battle_logs WHERE archived_at < :threshold")
    suspend fun deleteArchivedBefore(threshold: Long): Int

    @Query("DELETE FROM archived_battle_logs ")
    suspend fun deleteAll()
}

@Dao
interface ArchivedDiscipleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(disciples: List<ArchivedDisciple>)

    @Query("SELECT * FROM archived_disciples ORDER BY archived_at DESC LIMIT :limit")
    suspend fun listRecent(limit: Int): List<ArchivedDisciple>

    @Query("SELECT * FROM archived_disciples WHERE id = :id")
    suspend fun getById(id: Long): ArchivedDisciple?

    @Query("SELECT COUNT(*) FROM archived_disciples")
    suspend fun countAll(): Int

    @Query("DELETE FROM archived_disciples WHERE archived_at < :threshold")
    suspend fun deleteArchivedBefore(threshold: Long): Int

    @Query("DELETE FROM archived_disciples ")
    suspend fun deleteAll()
}
