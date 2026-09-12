package com.xianxia.sect.data.incremental

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
@Suppress("TooManyFunctions") // Room DAO @Query 契约面：函数数=数据访问协议面（查询维度×读写双向），
// Room 要求 DAO 方法驻留接口承载实现代理生成；项目已做过一轮 DAO 域拆分（DiscipleSubDaos），
// 继续拆分只会碎片化数据访问协议并倍增注入面
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

    @Query("SELECT * FROM change_log WHERE table_name = :tableName AND record_id = :recordId ORDER BY timestamp DESC " +
        "LIMIT 1")
    suspend fun getByRecordId(tableName: String, recordId: String): ChangeLogEntity?


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
