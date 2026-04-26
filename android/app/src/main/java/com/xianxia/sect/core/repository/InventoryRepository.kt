package com.xianxia.sect.core.repository

import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MaterialCategory
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.data.local.HerbDao
import com.xianxia.sect.data.local.ManualInstanceDao
import com.xianxia.sect.data.local.ManualStackDao
import com.xianxia.sect.data.local.MaterialDao
import com.xianxia.sect.data.local.PillDao
import com.xianxia.sect.data.local.SeedDao
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InventoryRepository @Inject constructor(
    private val manualStackDao: ManualStackDao,
    private val manualInstanceDao: ManualInstanceDao,
    private val pillDao: PillDao,
    private val materialDao: MaterialDao,
    private val seedDao: SeedDao,
    private val herbDao: HerbDao
) {
    companion object {
        const val DEFAULT_SLOT_ID = 0
    }

    // ==================== ManualStack ====================

    fun getManualStacks(slotId: Int = DEFAULT_SLOT_ID): Flow<List<ManualStack>> =
        manualStackDao.getAll(slotId)

    suspend fun getManualStackById(id: String, slotId: Int = DEFAULT_SLOT_ID): ManualStack? =
        manualStackDao.getById(slotId, id)

    // ==================== ManualInstance ====================

    fun getManualInstances(slotId: Int = DEFAULT_SLOT_ID): Flow<List<ManualInstance>> =
        manualInstanceDao.getAll(slotId)

    suspend fun getManualInstanceById(id: String, slotId: Int = DEFAULT_SLOT_ID): ManualInstance? =
        manualInstanceDao.getById(slotId, id)

    suspend fun getManualInstancesByOwner(discipleId: String, slotId: Int = DEFAULT_SLOT_ID): List<ManualInstance> =
        manualInstanceDao.getByOwner(slotId, discipleId)

    // ==================== Pill ====================

    fun getPills(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Pill>> =
        pillDao.getAll(slotId)

    suspend fun getPillById(id: String, slotId: Int = DEFAULT_SLOT_ID): Pill? =
        pillDao.getById(slotId, id)

    // ==================== Material ====================

    fun getMaterials(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Material>> =
        materialDao.getAll(slotId)

    suspend fun getMaterialById(id: String, slotId: Int = DEFAULT_SLOT_ID): Material? =
        materialDao.getById(slotId, id)

    fun getMaterialsByCategory(category: MaterialCategory, slotId: Int = DEFAULT_SLOT_ID): Flow<List<Material>> =
        materialDao.getByCategory(slotId, category)

    // ==================== Seed ====================

    fun getSeeds(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Seed>> =
        seedDao.getAll(slotId)

    suspend fun getSeedById(id: String, slotId: Int = DEFAULT_SLOT_ID): Seed? =
        seedDao.getById(slotId, id)

    // ==================== Herb ====================

    fun getHerbs(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Herb>> =
        herbDao.getAll(slotId)

    suspend fun getHerbById(id: String, slotId: Int = DEFAULT_SLOT_ID): Herb? =
        herbDao.getById(slotId, id)
}
