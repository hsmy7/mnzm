package com.xianxia.sect.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO — disciples 表（主弟子表）。
 */
@Dao
@Suppress("TooManyFunctions") // Room DAO @Query 契约面：函数数=数据访问协议面（查询维度×读写双向），
// Room 要求 DAO 方法驻留接口承载实现代理生成；项目已做过一轮 DAO 域拆分（DiscipleSubDaos），
// 继续拆分只会碎片化数据访问协议并倍增注入面
interface DiscipleDao {
    @Query("SELECT * FROM disciples WHERE isAlive = 1 ORDER BY realm ASC, cultivation DESC")
    fun getAllAlive(): Flow<List<Disciple>>

    @Query("SELECT * FROM disciples ORDER BY realm ASC, cultivation DESC")
    fun getAll(): Flow<List<Disciple>>

    @Query("SELECT * FROM disciples WHERE id = :id")
    suspend fun getById(id: String): Disciple?

    @Query("SELECT * FROM disciples WHERE status = :status AND isAlive = 1")
    suspend fun getByStatus(status: DiscipleStatus): List<Disciple>

    @Query("SELECT * FROM disciples WHERE isAlive = 1")
    suspend fun getAllAliveSync(): List<Disciple>

    @Query("SELECT * FROM disciples ")
    suspend fun getAllSync(): List<Disciple>

    @Query("SELECT * FROM disciples WHERE isAlive = 1 AND realm = :realm ORDER BY cultivation " +
        "DESC")
    fun getAliveByRealm(realm: Int): Flow<List<Disciple>>

    @Query("SELECT * FROM disciples WHERE isAlive = 1 AND realm BETWEEN :minRealm AND " +
        ":maxRealm ORDER BY realm ASC")
    fun getAliveByRealmRange(minRealm: Int, maxRealm: Int): Flow<List<Disciple>>

    @Query("SELECT * FROM disciples WHERE name LIKE '%' || :keyword || '%' AND isAlive = 1")
    suspend fun searchByName(keyword: String): List<Disciple>

    @Query("SELECT * FROM disciples WHERE isAlive = 1 AND discipleType = :type ORDER BY realm " +
        "ASC")
    fun getByDiscipleType(type: String): Flow<List<Disciple>>

    @Query("SELECT COUNT(*) FROM disciples WHERE isAlive = 1")
    fun getAliveCount(): Flow<Int>

    /**
     * 同步存活弟子计数：存档列表/元数据查询专用——
     * 避免全表物化 getAllAliveSync().size（数千弟子时上万行全量对象）。
     */
    @Query("SELECT COUNT(*) FROM disciples WHERE isAlive = 1")
    fun getAliveCountSync(): Int

    @Query("SELECT COUNT(*) FROM disciples WHERE isAlive = 1 AND realm = :realm")
    suspend fun getCountByRealm(realm: Int): Int

    @Query("SELECT * FROM disciples WHERE isAlive = 1 AND status = :status ORDER BY realm DESC " +
        "LIMIT :limit")
    suspend fun getByStatusWithLimit(status: DiscipleStatus, limit: Int): List<Disciple>

    @Query("SELECT * FROM disciples WHERE isAlive = 1 AND realm <= :maxRealm ORDER BY realm " +
        "DESC, cultivation DESC")
    fun getDisciplesForBattle(maxRealm: Int): Flow<List<Disciple>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(disciple: Disciple)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(disciples: List<Disciple>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(disciples: List<Disciple>)

    @Update
    suspend fun update(disciple: Disciple)

    @Update
    suspend fun updateAll(disciples: List<Disciple>)

    @Delete
    suspend fun delete(disciple: Disciple)

    @Query("DELETE FROM disciples WHERE id = :id")
    suspend fun deleteById(id: String)


    @Query("SELECT * FROM disciples WHERE isAlive = 0")
    suspend fun getDeadSync(): List<Disciple>

    @Query("DELETE FROM disciples WHERE isAlive = 0")
    suspend fun deleteDead()

    @Query("DELETE FROM disciples ")
    suspend fun deleteAll()

    /** 增量落盘 id 对账面（SS5）：已落盘 id 全量枚举（仅 id 列，不物化实体）。 */
    @Query("SELECT id FROM disciples")
    suspend fun getAllIds(): List<String>

    /** 增量落盘 id 对账面（SS5）：删除集按 id 批删（调用方分批防 SQLite 变量上限）。 */
    @Query("DELETE FROM disciples WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Transaction
    suspend fun updateBatch(disciples: List<Disciple>) {
        updateAll(disciples)
    }
}
