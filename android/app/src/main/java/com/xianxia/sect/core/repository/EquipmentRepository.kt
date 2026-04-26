package com.xianxia.sect.core.repository

import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.data.local.EquipmentInstanceDao
import com.xianxia.sect.data.local.EquipmentStackDao
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EquipmentRepository @Inject constructor(
    private val equipmentStackDao: EquipmentStackDao,
    private val equipmentInstanceDao: EquipmentInstanceDao
) {
    companion object {
        const val DEFAULT_SLOT_ID = 0
    }

    // ==================== EquipmentStack ====================

    fun getEquipmentStacks(slotId: Int = DEFAULT_SLOT_ID): Flow<List<EquipmentStack>> =
        equipmentStackDao.getAll(slotId)

    suspend fun getEquipmentStackById(id: String, slotId: Int = DEFAULT_SLOT_ID): EquipmentStack? =
        equipmentStackDao.getById(slotId, id)

    // ==================== EquipmentInstance ====================

    fun getEquipmentInstances(slotId: Int = DEFAULT_SLOT_ID): Flow<List<EquipmentInstance>> =
        equipmentInstanceDao.getAll(slotId)

    suspend fun getEquipmentInstanceById(id: String, slotId: Int = DEFAULT_SLOT_ID): EquipmentInstance? =
        equipmentInstanceDao.getById(slotId, id)

    suspend fun getEquipmentInstancesByOwner(discipleId: String, slotId: Int = DEFAULT_SLOT_ID): List<EquipmentInstance> =
        equipmentInstanceDao.getByOwner(slotId, discipleId)
}
