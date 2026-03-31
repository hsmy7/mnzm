package com.xianxia.sect.data.local

import androidx.room.*
import com.xianxia.sect.core.model.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DiscipleCoreDao {
    @Transaction
    @Query("SELECT * FROM disciples_core WHERE isAlive = 1 ORDER BY realm ASC, cultivation DESC")
    fun getAllAliveWithRelations(): Flow<List<DiscipleAggregateWithRelations>>
    
    @Query("SELECT * FROM disciples_core WHERE isAlive = 1 ORDER BY realm ASC, cultivation DESC")
    fun getAllAlive(): Flow<List<DiscipleCore>>
    
    @Transaction
    @Query("SELECT * FROM disciples_core ORDER BY realm ASC, cultivation DESC")
    fun getAllWithRelations(): Flow<List<DiscipleAggregateWithRelations>>
    
    @Query("SELECT * FROM disciples_core ORDER BY realm ASC, cultivation DESC")
    fun getAll(): Flow<List<DiscipleCore>>
    
    @Transaction
    @Query("SELECT * FROM disciples_core WHERE id = :id")
    suspend fun getByIdWithRelations(id: String): DiscipleAggregateWithRelations?
    
    @Query("SELECT * FROM disciples_core WHERE id = :id")
    suspend fun getById(id: String): DiscipleCore?
    
    @Query("SELECT * FROM disciples_core WHERE status = :status AND isAlive = 1")
    suspend fun getByStatus(status: String): List<DiscipleCore>
    
    @Transaction
    @Query("SELECT * FROM disciples_core WHERE status = :status AND isAlive = 1")
    suspend fun getByStatusWithRelations(status: String): List<DiscipleAggregateWithRelations>
    
    @Query("SELECT * FROM disciples_core WHERE isAlive = 1")
    suspend fun getAllAliveSync(): List<DiscipleCore>
    
    @Transaction
    @Query("SELECT * FROM disciples_core WHERE isAlive = 1")
    suspend fun getAllAliveSyncWithRelations(): List<DiscipleAggregateWithRelations>
    
    @Query("SELECT * FROM disciples_core")
    suspend fun getAllSync(): List<DiscipleCore>
    
    @Transaction
    @Query("SELECT * FROM disciples_core")
    suspend fun getAllSyncWithRelations(): List<DiscipleAggregateWithRelations>
    
    @Transaction
    @Query("SELECT * FROM disciples_core WHERE isAlive = 1 AND realm = :realm ORDER BY cultivation DESC")
    fun getAliveByRealmWithRelations(realm: Int): Flow<List<DiscipleAggregateWithRelations>>
    
    @Query("SELECT * FROM disciples_core WHERE isAlive = 1 AND realm = :realm ORDER BY cultivation DESC")
    fun getAliveByRealm(realm: Int): Flow<List<DiscipleCore>>
    
    @Transaction
    @Query("SELECT * FROM disciples_core WHERE isAlive = 1 AND realm BETWEEN :minRealm AND :maxRealm ORDER BY realm ASC")
    fun getAliveByRealmRangeWithRelations(minRealm: Int, maxRealm: Int): Flow<List<DiscipleAggregateWithRelations>>
    
    @Query("SELECT * FROM disciples_core WHERE isAlive = 1 AND realm BETWEEN :minRealm AND :maxRealm ORDER BY realm ASC")
    fun getAliveByRealmRange(minRealm: Int, maxRealm: Int): Flow<List<DiscipleCore>>
    
    @Query("SELECT * FROM disciples_core WHERE name LIKE '%' || :keyword || '%' AND isAlive = 1")
    suspend fun searchByName(keyword: String): List<DiscipleCore>
    
    @Transaction
    @Query("SELECT * FROM disciples_core WHERE name LIKE '%' || :keyword || '%' AND isAlive = 1")
    suspend fun searchByNameWithRelations(keyword: String): List<DiscipleAggregateWithRelations>
    
    @Transaction
    @Query("SELECT * FROM disciples_core WHERE isAlive = 1 AND discipleType = :type ORDER BY realm ASC")
    fun getByDiscipleTypeWithRelations(type: String): Flow<List<DiscipleAggregateWithRelations>>
    
    @Query("SELECT * FROM disciples_core WHERE isAlive = 1 AND discipleType = :type ORDER BY realm ASC")
    fun getByDiscipleType(type: String): Flow<List<DiscipleCore>>
    
    @Query("SELECT COUNT(*) FROM disciples_core WHERE isAlive = 1")
    fun getAliveCount(): Flow<Int>
    
    @Query("SELECT COUNT(*) FROM disciples_core WHERE isAlive = 1 AND realm = :realm")
    suspend fun getCountByRealm(realm: Int): Int
    
    @Transaction
    @Query("SELECT * FROM disciples_core WHERE isAlive = 1 AND age >= :minAge ORDER BY age DESC")
    fun getByMinAgeWithRelations(minAge: Int): Flow<List<DiscipleAggregateWithRelations>>
    
    @Transaction
    @Query("SELECT * FROM disciples_core WHERE isAlive = 1 AND status = :status ORDER BY realm DESC LIMIT :limit")
    suspend fun getByStatusWithLimitWithRelations(status: String, limit: Int): List<DiscipleAggregateWithRelations>
    
    @Transaction
    @Query("SELECT * FROM disciples_core WHERE isAlive = 1 AND realm <= :maxRealm ORDER BY realm DESC, cultivation DESC")
    fun getDisciplesForBattleWithRelations(maxRealm: Int): Flow<List<DiscipleAggregateWithRelations>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(core: DiscipleCore)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(cores: List<DiscipleCore>)
    
    @Update
    suspend fun update(core: DiscipleCore)
    
    @Update
    suspend fun updateAll(cores: List<DiscipleCore>)
    
    @Delete
    suspend fun delete(core: DiscipleCore)
    
    @Query("DELETE FROM disciples_core WHERE id = :id")
    suspend fun deleteById(id: String)
    
    @Query("DELETE FROM disciples_core WHERE isAlive = 0")
    suspend fun deleteDeadDisciples(): Int
    
    @Query("DELETE FROM disciples_core")
    suspend fun deleteAll()
    
    @Transaction
    suspend fun updateBatch(cores: List<DiscipleCore>) {
        cores.forEach { update(it) }
    }
}

@Dao
interface DiscipleCombatStatsDao {
    @Query("SELECT * FROM disciples_combat WHERE discipleId = :discipleId")
    suspend fun getByDiscipleId(discipleId: String): DiscipleCombatStats?
    
    @Query("SELECT * FROM disciples_combat WHERE discipleId IN (:discipleIds)")
    suspend fun getByDiscipleIds(discipleIds: List<String>): List<DiscipleCombatStats>
    
    @Query("SELECT * FROM disciples_combat")
    fun getAll(): Flow<List<DiscipleCombatStats>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(stats: DiscipleCombatStats)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(stats: List<DiscipleCombatStats>)
    
    @Update
    suspend fun update(stats: DiscipleCombatStats)
    
    @Update
    suspend fun updateAll(stats: List<DiscipleCombatStats>)
    
    @Delete
    suspend fun delete(stats: DiscipleCombatStats)
    
    @Query("DELETE FROM disciples_combat WHERE discipleId = :discipleId")
    suspend fun deleteByDiscipleId(discipleId: String)
    
    @Query("DELETE FROM disciples_combat WHERE discipleId IN (:discipleIds)")
    suspend fun deleteByDiscipleIds(discipleIds: List<String>)
    
    @Query("DELETE FROM disciples_combat")
    suspend fun deleteAll()
}

@Dao
interface DiscipleEquipmentDao {
    @Query("SELECT * FROM disciples_equipment WHERE discipleId = :discipleId")
    suspend fun getByDiscipleId(discipleId: String): DiscipleEquipment?
    
    @Query("SELECT * FROM disciples_equipment WHERE discipleId IN (:discipleIds)")
    suspend fun getByDiscipleIds(discipleIds: List<String>): List<DiscipleEquipment>
    
    @Query("SELECT * FROM disciples_equipment")
    fun getAll(): Flow<List<DiscipleEquipment>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(equipment: DiscipleEquipment)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(equipments: List<DiscipleEquipment>)
    
    @Update
    suspend fun update(equipment: DiscipleEquipment)
    
    @Update
    suspend fun updateAll(equipments: List<DiscipleEquipment>)
    
    @Delete
    suspend fun delete(equipment: DiscipleEquipment)
    
    @Query("DELETE FROM disciples_equipment WHERE discipleId = :discipleId")
    suspend fun deleteByDiscipleId(discipleId: String)
    
    @Query("DELETE FROM disciples_equipment WHERE discipleId IN (:discipleIds)")
    suspend fun deleteByDiscipleIds(discipleIds: List<String>)
    
    @Query("DELETE FROM disciples_equipment")
    suspend fun deleteAll()
}

@Dao
interface DiscipleExtendedDao {
    @Query("SELECT * FROM disciples_extended WHERE discipleId = :discipleId")
    suspend fun getByDiscipleId(discipleId: String): DiscipleExtended?
    
    @Query("SELECT * FROM disciples_extended WHERE discipleId IN (:discipleIds)")
    suspend fun getByDiscipleIds(discipleIds: List<String>): List<DiscipleExtended>
    
    @Query("SELECT * FROM disciples_extended")
    fun getAll(): Flow<List<DiscipleExtended>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(extended: DiscipleExtended)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(extendeds: List<DiscipleExtended>)
    
    @Update
    suspend fun update(extended: DiscipleExtended)
    
    @Update
    suspend fun updateAll(extendeds: List<DiscipleExtended>)
    
    @Delete
    suspend fun delete(extended: DiscipleExtended)
    
    @Query("DELETE FROM disciples_extended WHERE discipleId = :discipleId")
    suspend fun deleteByDiscipleId(discipleId: String)
    
    @Query("DELETE FROM disciples_extended WHERE discipleId IN (:discipleIds)")
    suspend fun deleteByDiscipleIds(discipleIds: List<String>)
    
    @Query("DELETE FROM disciples_extended")
    suspend fun deleteAll()
}

@Dao
interface DiscipleAttributesDao {
    @Query("SELECT * FROM disciples_attributes WHERE discipleId = :discipleId")
    suspend fun getByDiscipleId(discipleId: String): DiscipleAttributes?
    
    @Query("SELECT * FROM disciples_attributes WHERE discipleId IN (:discipleIds)")
    suspend fun getByDiscipleIds(discipleIds: List<String>): List<DiscipleAttributes>
    
    @Query("SELECT * FROM disciples_attributes WHERE loyalty < :threshold")
    fun getLowLoyalty(threshold: Int = 30): Flow<List<DiscipleAttributes>>
    
    @Transaction
    @Query("SELECT c.*, a.* FROM disciples_core c INNER JOIN disciples_attributes a ON c.id = a.discipleId WHERE c.isAlive = 1 AND a.loyalty < :threshold ORDER BY a.loyalty ASC")
    fun getLowLoyaltyWithRelations(threshold: Int = 30): Flow<List<DiscipleAggregateWithRelations>>
    
    @Query("SELECT * FROM disciples_attributes")
    fun getAll(): Flow<List<DiscipleAttributes>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attributes: DiscipleAttributes)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(attributes: List<DiscipleAttributes>)
    
    @Update
    suspend fun update(attributes: DiscipleAttributes)
    
    @Update
    suspend fun updateAll(attributes: List<DiscipleAttributes>)
    
    @Delete
    suspend fun delete(attributes: DiscipleAttributes)
    
    @Query("DELETE FROM disciples_attributes WHERE discipleId = :discipleId")
    suspend fun deleteByDiscipleId(discipleId: String)
    
    @Query("DELETE FROM disciples_attributes WHERE discipleId IN (:discipleIds)")
    suspend fun deleteByDiscipleIds(discipleIds: List<String>)
    
    @Query("DELETE FROM disciples_attributes")
    suspend fun deleteAll()
}
