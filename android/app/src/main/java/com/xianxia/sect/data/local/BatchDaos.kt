@file:Suppress("DEPRECATION")

package com.xianxia.sect.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.xianxia.sect.core.model.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BatchDiscipleDao {
    @Transaction
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllBatch(disciples: List<Disciple>)
    
    @Transaction
    @Update
    suspend fun updateAllBatch(disciples: List<Disciple>)
    
    @Transaction
    @Query("DELETE FROM disciples WHERE id IN (:ids)")
    suspend fun deleteBatch(ids: List<String>)
    
    @Transaction
    @Query("SELECT * FROM disciples WHERE id IN (:ids) ORDER BY realm ASC, cultivation DESC")
    suspend fun getByIds(ids: List<String>): List<Disciple>
    
    @Query("SELECT COUNT(*) FROM disciples WHERE isAlive = 1")
    suspend fun getAliveCount(): Flow<Int>
    
    @Query("SELECT * FROM disciples WHERE isAlive = 1 AND realm BETWEEN :minRealm AND :maxRealm ORDER BY realm ASC")
    fun getAliveByRealmRange(minRealm: Int, maxRealm: Int): Flow<List<Disciple>>
}

@Dao
interface BatchEquipmentDao {
    @Transaction
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllBatch(equipments: List<Equipment>)
    
    @Transaction
    @Update
    suspend fun updateAllBatch(equipments: List<Equipment>)
    
    @Transaction
    @Query("DELETE FROM equipment WHERE id IN (:ids)")
    suspend fun deleteBatch(ids: List<String>)
    
    @Transaction
    @Query("SELECT * FROM equipment WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<Equipment>
}

@Dao
interface BatchManualDao {
    @Transaction
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllBatch(manuals: List<Manual>)
    
    @Transaction
    @Update
    suspend fun updateAllBatch(manuals: List<Manual>)
    
    @Transaction
    @Query("DELETE FROM manuals WHERE id IN (:ids)")
    suspend fun deleteBatch(ids: List<String>)
    
    @Transaction
    @Query("SELECT * FROM manuals WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<Manual>
}

@Dao
interface BatchPillDao {
    @Transaction
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllBatch(pills: List<Pill>)
    
    @Transaction
    @Update
    suspend fun updateAllBatch(pills: List<Pill>)
    
    @Transaction
    @Query("DELETE FROM pills WHERE id IN (:ids)")
    suspend fun deleteBatch(ids: List<String>)
    
    @Transaction
    @Query("SELECT * FROM pills WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<Pill>
}

@Dao
interface BatchMaterialDao {
    @Transaction
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllBatch(materials: List<Material>)
    
    @Transaction
    @Update
    suspend fun updateAllBatch(materials: List<Material>)
    
    @Transaction
    @Query("DELETE FROM materials WHERE id IN (:ids)")
    suspend fun deleteBatch(ids: List<String>)
    
    @Transaction
    @Query("SELECT * FROM materials WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<Material>
}

@Dao
interface BatchHerbDao {
    @Transaction
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllBatch(herbs: List<Herb>)
    
    @Transaction
    @Update
    suspend fun updateAllBatch(herbs: List<Herb>)
    
    @Transaction
    @Query("DELETE FROM herbs WHERE id IN (:ids)")
    suspend fun deleteBatch(ids: List<String>)
    
    @Transaction
    @Query("SELECT * FROM herbs WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<Herb>
}

@Dao
interface BatchSeedDao {
    @Transaction
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllBatch(seeds: List<Seed>)
    
    @Transaction
    @Update
    suspend fun updateAllBatch(seeds: List<Seed>)
    
    @Transaction
    @Query("DELETE FROM seeds WHERE id IN (:ids)")
    suspend fun deleteBatch(ids: List<String>)
    
    @Transaction
    @Query("SELECT * FROM seeds WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<Seed>
}

@Dao
interface BatchBattleLogDao {
    @Transaction
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllBatch(logs: List<BattleLog>)
    
    @Transaction
    @Query("DELETE FROM battle_logs WHERE id IN (:ids)")
    suspend fun deleteBatch(ids: List<String>)
    
    @Transaction
    @Query("SELECT * FROM battle_logs WHERE id IN (:ids) ORDER BY timestamp DESC")
    suspend fun getByIds(ids: List<String>): List<BattleLog>
}

@Dao
interface BatchGameEventDao {
    @Transaction
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllBatch(events: List<GameEvent>)
    
    @Transaction
    @Query("DELETE FROM game_events WHERE id IN (:ids)")
    suspend fun deleteBatch(ids: List<String>)
    
    @Transaction
    @Query("SELECT * FROM game_events WHERE id IN (:ids) ORDER BY timestamp DESC")
    suspend fun getByIds(ids: List<String>): List<GameEvent>
}
