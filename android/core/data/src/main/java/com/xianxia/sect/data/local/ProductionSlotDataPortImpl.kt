package com.xianxia.sect.data.local

import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.repository.ProductionSlotDataPort
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProductionSlotDataPortImpl @Inject constructor(
    private val dao: ProductionSlotDao
) : ProductionSlotDataPort {

    override fun getAllSync(): List<ProductionSlot> = dao.getAllSync()

    override fun insertAll(slots: List<ProductionSlot>) = dao.insertAll(slots)

    override fun update(slot: ProductionSlot) = dao.update(slot)

    override fun updateAll(slots: List<ProductionSlot>) = dao.updateAll(slots)

    override fun insert(slot: ProductionSlot) = dao.insert(slot)

    override fun deleteById(id: String) = dao.deleteById(id)

    override fun deleteBySlot(slotId: Int) = dao.deleteBySlot(slotId)

    override fun deleteBySlotAndBuildingType(slotId: Int, buildingType: BuildingType) =
        dao.deleteBySlotAndBuildingType(slotId, buildingType)
}
