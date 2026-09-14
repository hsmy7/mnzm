package com.xianxia.sect.core.repository

import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.data.local.EquipmentInstanceDao
import com.xianxia.sect.data.local.EquipmentStackDao
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EquipmentRepositoryImpl @Inject constructor(
    private val equipmentStackDao: EquipmentStackDao,
    private val equipmentInstanceDao: EquipmentInstanceDao
) : EquipmentRepository {
    companion object {
        const val DEFAULT_SLOT_ID = 0
    }

    // ==================== EquipmentStack ====================

    override fun getEquipmentStacks(slotId: Int): Flow<List<EquipmentStack>> =
        equipmentStackDao.getAll(slotId)

    override suspend fun getEquipmentStackById(id: String, slotId: Int): EquipmentStack? =
        equipmentStackDao.getById(slotId, id)

    // ==================== EquipmentInstance ====================

    override fun getEquipmentInstances(slotId: Int): Flow<List<EquipmentInstance>> =
        equipmentInstanceDao.getAll(slotId)

    override suspend fun getEquipmentInstanceById(id: String, slotId: Int): EquipmentInstance? =
        equipmentInstanceDao.getById(slotId, id)

    override suspend fun getEquipmentInstancesByOwner(discipleId: String, slotId: Int): List<EquipmentInstance> =
        equipmentInstanceDao.getByOwner(slotId, discipleId)
}
