package com.xianxia.sect.data.local

import androidx.room.*
import com.xianxia.sect.core.model.BattleLog
import kotlinx.coroutines.flow.Flow

@Dao
@Suppress("TooManyFunctions") // Room DAO @Query 契约面：函数数=数据访问协议面（查询维度×读写双向），
// Room 要求 DAO 方法驻留接口承载实现代理生成；项目已做过一轮 DAO 域拆分（DiscipleSubDaos），
// 继续拆分只会碎片化数据访问协议并倍增注入面
interface BattleLogDao {
    @Query("SELECT * FROM battle_logs WHERE slot_id = :slotId ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(slotId: Int, limit: Int = 50): Flow<List<BattleLog>>

    @Query("SELECT * FROM battle_logs WHERE slot_id = :slotId ORDER BY timestamp DESC LIMIT 200")
    fun getAll(slotId: Int): Flow<List<BattleLog>>

    @Query("SELECT * FROM battle_logs WHERE slot_id = :slotId ORDER BY timestamp DESC LIMIT 200")
    suspend fun getAllSync(slotId: Int): List<BattleLog>

    @Query("SELECT * FROM battle_logs WHERE slot_id = :slotId AND id = :id")
    suspend fun getById(slotId: Int, id: String): BattleLog?

    @Query("SELECT COUNT(*) FROM battle_logs WHERE slot_id = :slotId")
    suspend fun countBySlot(slotId: Int): Int

    @Query("SELECT * FROM battle_logs WHERE slot_id = :slotId ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getOldestBySlot(slotId: Int, limit: Int): List<BattleLog>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: BattleLog)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<BattleLog>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(logs: List<BattleLog>)

    @Update
    suspend fun updateAll(logs: List<BattleLog>)

    @Query("SELECT id FROM battle_logs WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>

    @Query("DELETE FROM battle_logs WHERE slot_id = :slotId AND id = :id")
    suspend fun deleteById(slotId: Int, id: String)

    @Query("DELETE FROM battle_logs WHERE slot_id = :slotId AND timestamp < :before")
    /** @return 删除行数（审计 P3-11：修剪统计口径按行计） */
    suspend fun deleteOld(slotId: Int, before: Long): Int

    @Query("DELETE FROM battle_logs WHERE slot_id = :slotId")
    suspend fun deleteAll(slotId: Int)

    @Query("DELETE FROM battle_logs")
    suspend fun deleteAllGlobal()
}
