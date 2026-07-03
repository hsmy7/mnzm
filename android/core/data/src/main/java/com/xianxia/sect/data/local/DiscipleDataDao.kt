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
interface DiscipleDao {
    @Query("SELECT * FROM disciples WHERE slot_id = :slotId AND isAlive = 1 ORDER BY realm ASC, cultivation DESC")
    fun getAllAlive(slotId: Int): Flow<List<Disciple>>

    @Query("SELECT * FROM disciples WHERE slot_id = :slotId ORDER BY realm ASC, cultivation DESC")
    fun getAll(slotId: Int): Flow<List<Disciple>>

    @Query("SELECT * FROM disciples WHERE slot_id = :slotId AND id = :id")
    suspend fun getById(slotId: Int, id: String): Disciple?

    @Query("SELECT * FROM disciples WHERE slot_id = :slotId AND status = :status AND isAlive = 1")
    suspend fun getByStatus(slotId: Int, status: DiscipleStatus): List<Disciple>

    @Query("SELECT * FROM disciples WHERE slot_id = :slotId AND isAlive = 1")
    suspend fun getAllAliveSync(slotId: Int): List<Disciple>

    @Query("SELECT * FROM disciples WHERE slot_id = :slotId")
    suspend fun getAllSync(slotId: Int): List<Disciple>

    @Query("SELECT * FROM disciples WHERE slot_id = :slotId AND isAlive = 1 AND realm = :realm ORDER BY cultivation DESC")
    fun getAliveByRealm(slotId: Int, realm: Int): Flow<List<Disciple>>

    @Query("SELECT * FROM disciples WHERE slot_id = :slotId AND isAlive = 1 AND realm BETWEEN :minRealm AND :maxRealm ORDER BY realm ASC")
    fun getAliveByRealmRange(slotId: Int, minRealm: Int, maxRealm: Int): Flow<List<Disciple>>

    @Query("SELECT * FROM disciples WHERE slot_id = :slotId AND name LIKE '%' || :keyword || '%' AND isAlive = 1")
    suspend fun searchByName(slotId: Int, keyword: String): List<Disciple>

    @Query("SELECT * FROM disciples WHERE slot_id = :slotId AND isAlive = 1 AND discipleType = :type ORDER BY realm ASC")
    fun getByDiscipleType(slotId: Int, type: String): Flow<List<Disciple>>

    @Query("SELECT * FROM disciples WHERE slot_id = :slotId AND isAlive = 1 AND loyalty < :threshold ORDER BY loyalty ASC")
    fun getLowLoyalty(slotId: Int, threshold: Int = 30): Flow<List<Disciple>>

    @Query("SELECT * FROM disciples WHERE slot_id = :slotId AND isAlive = 1 AND age >= :minAge ORDER BY age DESC")
    fun getByMinAge(slotId: Int, minAge: Int): Flow<List<Disciple>>

    @Query("SELECT COUNT(*) FROM disciples WHERE slot_id = :slotId AND isAlive = 1")
    fun getAliveCount(slotId: Int): Flow<Int>

    @Query("SELECT COUNT(*) FROM disciples WHERE slot_id = :slotId AND isAlive = 1 AND realm = :realm")
    suspend fun getCountByRealm(slotId: Int, realm: Int): Int

    @Query("SELECT * FROM disciples WHERE slot_id = :slotId AND isAlive = 1 AND status = :status ORDER BY realm DESC LIMIT :limit")
    suspend fun getByStatusWithLimit(slotId: Int, status: DiscipleStatus, limit: Int): List<Disciple>

    @Query("SELECT * FROM disciples WHERE slot_id = :slotId AND isAlive = 1 AND realm <= :maxRealm ORDER BY realm DESC, cultivation DESC")
    fun getDisciplesForBattle(slotId: Int, maxRealm: Int): Flow<List<Disciple>>

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

    @Query("SELECT id FROM disciples WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>

    @Delete
    suspend fun delete(disciple: Disciple)

    @Query("DELETE FROM disciples WHERE slot_id = :slotId AND id = :id")
    suspend fun deleteById(slotId: Int, id: String)

    @Query("DELETE FROM disciples WHERE slot_id = :slotId AND isAlive = 0")
    suspend fun deleteDeadDisciples(slotId: Int): Int

    @Query("SELECT * FROM disciples WHERE slot_id = :slotId AND isAlive = 0")
    suspend fun getDeadBySlotSync(slotId: Int): List<Disciple>

    @Query("DELETE FROM disciples WHERE slot_id = :slotId AND isAlive = 0")
    suspend fun deleteDeadBySlot(slotId: Int)

    @Query("DELETE FROM disciples WHERE slot_id = :slotId")
    suspend fun deleteAll(slotId: Int)

    @Query("DELETE FROM disciples")
    suspend fun deleteAllGlobal()

    @Transaction
    suspend fun updateBatch(disciples: List<Disciple>) {
        updateAll(disciples)
    }
}
