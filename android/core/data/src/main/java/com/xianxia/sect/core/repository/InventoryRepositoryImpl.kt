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
class InventoryRepositoryImpl @Inject constructor(
    private val manualStackDao: ManualStackDao,
    private val manualInstanceDao: ManualInstanceDao,
    private val pillDao: PillDao,
    private val materialDao: MaterialDao,
    private val seedDao: SeedDao,
    private val herbDao: HerbDao
) : InventoryRepository {
    companion object {
        const val DEFAULT_SLOT_ID = 0
    }

    // ==================== ManualStack ====================

    override fun getManualStacks(slotId: Int): Flow<List<ManualStack>> =
        manualStackDao.getAll(slotId)

    override suspend fun getManualStackById(id: String, slotId: Int): ManualStack? =
        manualStackDao.getById(slotId, id)

    // ==================== ManualInstance ====================

    override fun getManualInstances(slotId: Int): Flow<List<ManualInstance>> =
        manualInstanceDao.getAll(slotId)

    override suspend fun getManualInstanceById(id: String, slotId: Int): ManualInstance? =
        manualInstanceDao.getById(slotId, id)

    override suspend fun getManualInstancesByOwner(discipleId: String, slotId: Int): List<ManualInstance> =
        manualInstanceDao.getByOwner(slotId, discipleId)

    // ==================== Pill ====================

    override fun getPills(slotId: Int): Flow<List<Pill>> =
        pillDao.getAll(slotId)

    override suspend fun getPillById(id: String, slotId: Int): Pill? =
        pillDao.getById(slotId, id)

    // ==================== Material ====================

    override fun getMaterials(slotId: Int): Flow<List<Material>> =
        materialDao.getAll(slotId)

    override suspend fun getMaterialById(id: String, slotId: Int): Material? =
        materialDao.getById(slotId, id)

    override fun getMaterialsByCategory(category: MaterialCategory, slotId: Int): Flow<List<Material>> =
        materialDao.getByCategory(slotId, category)

    // ==================== Seed ====================

    override fun getSeeds(slotId: Int): Flow<List<Seed>> =
        seedDao.getAll(slotId)

    override suspend fun getSeedById(id: String, slotId: Int): Seed? =
        seedDao.getById(slotId, id)

    // ==================== Herb ====================

    override fun getHerbs(slotId: Int): Flow<List<Herb>> =
        herbDao.getAll(slotId)

    override suspend fun getHerbById(id: String, slotId: Int): Herb? =
        herbDao.getById(slotId, id)
}
