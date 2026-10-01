package com.xianxia.sect.data.local

import androidx.room.*
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import kotlinx.coroutines.flow.Flow



@Dao
@Suppress("TooManyFunctions") // Room DAO @Query 契约面：函数数=数据访问协议面（查询维度×读写双向），
// Room 要求 DAO 方法驻留接口承载实现代理生成；项目已做过一轮 DAO 域拆分（DiscipleSubDaos），
// 继续拆分只会碎片化数据访问协议并倍增注入面
// （EquipmentStackDao 已随 B3 移除堆叠删除）
interface EquipmentInstanceDao {
    @Query("SELECT * FROM equipment_instances ")
    fun getAll(): Flow<List<EquipmentInstance>>

    @Query("SELECT * FROM equipment_instances ")
    suspend fun getAllSync(): List<EquipmentInstance>

    @Query("SELECT * FROM equipment_instances WHERE id = :id")
    suspend fun getById(id: String): EquipmentInstance?

    @Query("SELECT * FROM equipment_instances WHERE ownerId = :discipleId")
    suspend fun getByOwner(discipleId: String): List<EquipmentInstance>

    @Query("SELECT * FROM equipment_instances WHERE part = :part")
    fun getByPart(part: EquipmentSlot): Flow<List<EquipmentInstance>>

    @Query("SELECT * FROM equipment_instances WHERE setId = :setId")
    fun getBySet(setId: String): Flow<List<EquipmentInstance>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(equipmentInstance: EquipmentInstance)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(equipmentInstances: List<EquipmentInstance>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(equipmentInstances: List<EquipmentInstance>)

    @Update
    suspend fun update(equipmentInstance: EquipmentInstance)

    @Update
    suspend fun updateAll(equipmentInstances: List<EquipmentInstance>)

    @Delete
    suspend fun delete(equipmentInstance: EquipmentInstance)

    @Query("DELETE FROM equipment_instances WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM equipment_instances ")
    suspend fun deleteAll()

    @Transaction
    suspend fun updateBatch(equipmentInstances: List<EquipmentInstance>) {
        updateAll(equipmentInstances)
    }
}
