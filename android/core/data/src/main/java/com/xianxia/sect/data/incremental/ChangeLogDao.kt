package com.xianxia.sect.data.incremental

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ChangeLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(changeLog: ChangeLogEntity): Long

    @Transaction
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(changeLogs: List<ChangeLogEntity>)

    @Query("SELECT * FROM change_log WHERE synced = 0 ORDER BY timestamp ASC")
    suspend fun getUnsynced(): List<ChangeLogEntity>

    @Query("SELECT * FROM change_log WHERE synced = 0 ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getUnsynced(limit: Int): List<ChangeLogEntity>

    @Query("SELECT * FROM change_log WHERE table_name = :tableName AND record_id = :recordId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getByRecordId(tableName: String, recordId: String): ChangeLogEntity?

    @Query("UPDATE change_log SET synced = 1, sync_version = :syncVersion WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>, syncVersion: Long = System.currentTimeMillis())

    @Query("DELETE FROM change_log WHERE synced = 1 AND timestamp < :threshold")
    suspend fun deleteSyncedOlderThan(threshold: Long): Int

    @Query("DELETE FROM change_log WHERE timestamp < :threshold")
    suspend fun deleteOlderThan(threshold: Long): Int

    @Query("SELECT COUNT(*) FROM change_log WHERE synced = 0")
    fun getUnsyncedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM change_log")
    suspend fun getCount(): Int

    @Query("DELETE FROM change_log")
    suspend fun deleteAll()

    @Query("SELECT * FROM change_log WHERE table_name = :tableName ORDER BY timestamp DESC")
    suspend fun getByTableName(tableName: String): List<ChangeLogEntity>

    @Query("SELECT * FROM change_log WHERE timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp ASC")
    suspend fun getByTimeRange(startTime: Long, endTime: Long): List<ChangeLogEntity>

    @Query("SELECT MAX(sync_version) FROM change_log")
    suspend fun getLastSyncVersion(): Long?
}
