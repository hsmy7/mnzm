package com.xianxia.sect.core.repository

import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.data.local.EquipmentInstanceDao
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EquipmentRepositoryImpl @Inject constructor(
    private val equipmentInstanceDao: EquipmentInstanceDao
) : EquipmentRepository {
    companion object {
        const val DEFAULT_SLOT_ID = 0
    }

    // ==================== EquipmentInstance ====================

    override fun getEquipmentInstances(): Flow<List<EquipmentInstance>> =
        equipmentInstanceDao.getAll()

    override suspend fun getEquipmentInstanceById(id: String): EquipmentInstance? =
        equipmentInstanceDao.getById(id)

    override suspend fun getEquipmentInstancesByOwner(discipleId: String): List<EquipmentInstance> =
        equipmentInstanceDao.getByOwner(discipleId)
}
