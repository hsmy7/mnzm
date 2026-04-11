package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ProductionSubsystemTest {

    private lateinit var system: ProductionSubsystem

    @Before
    fun setUp() {
        system = ProductionSubsystem()
        system.initialize()
    }

    private fun createSlot(slotIndex: Int, buildingType: BuildingType = BuildingType.ALCHEMY): ProductionSlot {
        return ProductionSlot(slotIndex = slotIndex, buildingType = buildingType)
    }

    @Test
    fun `loadProductionData - 加载生产数据`() {
        val slots = listOf(
            createSlot(0, BuildingType.ALCHEMY),
            createSlot(0, BuildingType.FORGE)
        )
        system.loadProductionData(slots)
        assertEquals(2, system.getProductionSlots().size)
    }

    @Test
    fun `getProductionSlot - 按索引获取槽位`() {
        val slots = listOf(
            createSlot(0, BuildingType.ALCHEMY),
            createSlot(1, BuildingType.ALCHEMY)
        )
        system.loadProductionData(slots)
        val slot = system.getProductionSlot(1)
        assertNotNull(slot)
        assertEquals(1, slot!!.slotIndex)
    }

    @Test
    fun `getProductionSlot - 越界索引返回null`() {
        system.loadProductionData(listOf(createSlot(0)))
        assertNull(system.getProductionSlot(5))
    }

    @Test
    fun `loadProductionData - 空列表`() {
        system.loadProductionData(emptyList())
        assertEquals(0, system.getProductionSlots().size)
    }

    @Test
    fun `productionSlots StateFlow 反映最新数据`() {
        val slots = listOf(createSlot(0))
        system.loadProductionData(slots)
        assertEquals(1, system.productionSlots.value.size)
    }

    @Test
    fun `clear - 清空生产数据`() = runBlocking {
        system.loadProductionData(listOf(createSlot(0)))
        system.clear()
        assertEquals(0, system.getProductionSlots().size)
    }

    @Test
    fun `systemName 正确`() {
        assertEquals("ProductionSubsystem", system.systemName)
    }
}
