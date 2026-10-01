package com.xianxia.sect.data.local

import androidx.room.*
import com.xianxia.sect.core.model.BattleLog
import kotlinx.coroutines.flow.Flow

@Dao
@Suppress("TooManyFunctions") // Room DAO @Query 契约面：函数数=数据访问协议面（查询维度×读写双向），
// Room 要求 DAO 方法驻留接口承载实现代理生成；项目已做过一轮 DAO 域拆分（DiscipleSubDaos），
// 继续拆分只会碎片化数据访问协议并倍增注入面
interface BattleLogDao {
    @Query("SELECT * FROM battle_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 50): Flow<List<BattleLog>>

    @Query("SELECT * FROM battle_logs ORDER BY timestamp DESC LIMIT 200")
    fun getAll(): Flow<List<BattleLog>>

    @Query("SELECT * FROM battle_logs ORDER BY timestamp DESC LIMIT 200")
    suspend fun getAllSync(): List<BattleLog>

    @Query("SELECT * FROM battle_logs WHERE id = :id")
    suspend fun getById(id: String): BattleLog?

    @Query("SELECT COUNT(*) FROM battle_logs")
    suspend fun countAll(): Int

    @Query("SELECT * FROM battle_logs ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getOldest(limit: Int): List<BattleLog>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: BattleLog)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<BattleLog>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(logs: List<BattleLog>)

    @Update
    suspend fun updateAll(logs: List<BattleLog>)

    @Query("DELETE FROM battle_logs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM battle_logs WHERE timestamp < :before")
    /** @return 删除行数（审计 P3-11：修剪统计口径按行计） */
    suspend fun deleteOld(before: Long): Int

    @Query("DELETE FROM battle_logs")
    suspend fun deleteAll()

    /** 增量落盘 id 对账面（SS5）：已落盘 id 全量枚举（仅 id 列，不物化实体）。 */
    @Query("SELECT id FROM battle_logs")
    suspend fun getAllIds(): List<String>

    /** 增量落盘 id 对账面（SS5）：删除集按 id 批删（调用方分批防 SQLite 变量上限）。 */
    @Query("DELETE FROM battle_logs WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

}
