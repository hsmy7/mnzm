package com.xianxia.sect.data.local

import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.repository.ProductionSlotDataPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProductionSlotDataPortImpl @Inject constructor(
    private val dao: ProductionSlotDao
) : ProductionSlotDataPort {

    override fun getAllSync(): List<ProductionSlot> = dao.getAllSync()

    override suspend fun insertAll(slots: List<ProductionSlot>) {
        withContext(Dispatchers.IO) { dao.insertAll(slots) }
    }

    override suspend fun update(slot: ProductionSlot) {
        withContext(Dispatchers.IO) { dao.update(slot) }
    }

    override suspend fun updateAll(slots: List<ProductionSlot>) {
        withContext(Dispatchers.IO) { dao.updateAll(slots) }
    }

    override suspend fun insert(slot: ProductionSlot) {
        withContext(Dispatchers.IO) { dao.insert(slot) }
    }

    override suspend fun deleteById(id: String) {
        withContext(Dispatchers.IO) { dao.deleteById(id) }
    }

    override suspend fun deleteBySlot(slotId: Int) {
        withContext(Dispatchers.IO) { dao.deleteBySlot(slotId) }
    }

    override suspend fun deleteBySlotAndBuildingType(slotId: Int, buildingType: BuildingType) {
        withContext(Dispatchers.IO) { dao.deleteBySlotAndBuildingType(slotId, buildingType) }
    }
}
