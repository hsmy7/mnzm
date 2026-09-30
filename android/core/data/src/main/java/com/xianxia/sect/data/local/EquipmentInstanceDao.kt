package com.xianxia.sect.data.local

import androidx.room.*
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import kotlinx.coroutines.flow.Flow



@Dao
@Suppress("TooManyFunctions") // Room DAO @Query 契约面：函数数=数据访问协议面（查询维度×读写双向），
// Room 要求 DAO 方法驻留接口承载实现代理生成；项目已做过一轮 DAO 域拆分（DiscipleSubDaos），
// 继续拆分只会碎片化数据访问协议并倍增注入面
// （EquipmentStackDao 已随 B3 移除堆叠删除，equipment_stacks 表在 MIGRATION_63_64 DROP）
interface EquipmentInstanceDao {
    @Query("SELECT * FROM equipment_instances WHERE slot_id = :slotId")
    fun getAll(slotId: Int): Flow<List<EquipmentInstance>>

    @Query("SELECT * FROM equipment_instances WHERE slot_id = :slotId")
    suspend fun getAllSync(slotId: Int): List<EquipmentInstance>

    @Query("SELECT * FROM equipment_instances WHERE slot_id = :slotId AND id = :id")
    suspend fun getById(slotId: Int, id: String): EquipmentInstance?

    @Query("SELECT * FROM equipment_instances WHERE slot_id = :slotId AND ownerId = :discipleId")
    suspend fun getByOwner(slotId: Int, discipleId: String): List<EquipmentInstance>

    @Query("SELECT * FROM equipment_instances WHERE slot_id = :slotId AND part = :part")
    fun getByPart(slotId: Int, part: EquipmentSlot): Flow<List<EquipmentInstance>>

    @Query("SELECT * FROM equipment_instances WHERE slot_id = :slotId AND setId = :setId")
    fun getBySet(slotId: Int, setId: String): Flow<List<EquipmentInstance>>

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

    @Query("SELECT id FROM equipment_instances WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>

    @Delete
    suspend fun delete(equipmentInstance: EquipmentInstance)

    @Query("DELETE FROM equipment_instances WHERE slot_id = :slotId AND id = :id")
    suspend fun deleteById(slotId: Int, id: String)

    @Query("DELETE FROM equipment_instances WHERE slot_id = :slotId")
    suspend fun deleteAll(slotId: Int)

    @Query("DELETE FROM equipment_instances")
    suspend fun deleteAllGlobal()

    @Transaction
    suspend fun updateBatch(equipmentInstances: List<EquipmentInstance>) {
        updateAll(equipmentInstances)
    }
}
