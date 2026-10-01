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

    override fun getManualStacks(): Flow<List<ManualStack>> =
        manualStackDao.getAll()

    override suspend fun getManualStackById(id: String): ManualStack? =
        manualStackDao.getById(id)

    // ==================== ManualInstance ====================

    override fun getManualInstances(): Flow<List<ManualInstance>> =
        manualInstanceDao.getAll()

    override suspend fun getManualInstanceById(id: String): ManualInstance? =
        manualInstanceDao.getById(id)

    override suspend fun getManualInstancesByOwner(discipleId: String): List<ManualInstance> =
        manualInstanceDao.getByOwner(discipleId)

    // ==================== Pill ====================

    override fun getPills(): Flow<List<Pill>> =
        pillDao.getAll()

    override suspend fun getPillById(id: String): Pill? =
        pillDao.getById(id)

    // ==================== Material ====================

    override fun getMaterials(): Flow<List<Material>> =
        materialDao.getAll()

    override suspend fun getMaterialById(id: String): Material? =
        materialDao.getById(id)

    override fun getMaterialsByCategory(category: MaterialCategory): Flow<List<Material>> =
        materialDao.getByCategory(category)

    // ==================== Seed ====================

    override fun getSeeds(): Flow<List<Seed>> =
        seedDao.getAll()

    override suspend fun getSeedById(id: String): Seed? =
        seedDao.getById(id)

    // ==================== Herb ====================

    override fun getHerbs(): Flow<List<Herb>> =
        herbDao.getAll()

    override suspend fun getHerbById(id: String): Herb? =
        herbDao.getById(id)
}
