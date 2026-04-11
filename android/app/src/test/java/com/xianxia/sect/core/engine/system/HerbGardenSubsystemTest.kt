package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.PlantSlotData
import com.xianxia.sect.core.model.Seed
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class HerbGardenSubsystemTest {

    private lateinit var system: HerbGardenSubsystem

    @Before
    fun setUp() {
        system = HerbGardenSubsystem()
        system.initialize()
    }

    @Test
    fun `loadHerbGardenData - 加载药园数据`() {
        val slots = listOf(PlantSlotData(index = 0), PlantSlotData(index = 1))
        val herbs = listOf(Herb(id = "h1", name = "灵草"))
        val seeds = listOf(Seed(id = "s1", name = "灵草种子"))
        system.loadHerbGardenData(slots, herbs, seeds)
        assertEquals(2, system.getPlantSlots().size)
        assertEquals(1, system.herbs.value.size)
        assertEquals(1, system.seeds.value.size)
    }

    @Test
    fun `getPlantSlot - 按索引获取种植槽`() {
        val slots = listOf(PlantSlotData(index = 0), PlantSlotData(index = 1), PlantSlotData(index = 2))
        system.loadHerbGardenData(slots, emptyList(), emptyList())
        val slot = system.getPlantSlot(1)
        assertNotNull(slot)
        assertEquals(1, slot!!.index)
    }

    @Test
    fun `getPlantSlot - 不存在的索引返回null`() {
        val slots = listOf(PlantSlotData(index = 0))
        system.loadHerbGardenData(slots, emptyList(), emptyList())
        assertNull(system.getPlantSlot(5))
    }

    @Test
    fun `initializePlantSlots - 初始化指定数量的槽位`() {
        system.initializePlantSlots(5)
        val slots = system.getPlantSlots()
        assertEquals(5, slots.size)
        for (i in 0..4) {
            assertEquals(i, slots[i].index)
        }
    }

    @Test
    fun `initializePlantSlots - 不重复初始化`() {
        system.initializePlantSlots(3)
        system.initializePlantSlots(3)
        assertEquals(3, system.getPlantSlots().size)
    }

    @Test
    fun `updatePlantSlot - 更新指定槽位`() {
        system.loadHerbGardenData(
            listOf(PlantSlotData(index = 0), PlantSlotData(index = 1)),
            emptyList(),
            emptyList()
        )
        val result = system.updatePlantSlot(0) { it.copy(seedId = "s1") }
        assertTrue(result)
        assertEquals("s1", system.getPlantSlot(0)!!.seedId)
    }

    @Test
    fun `updatePlantSlot - 更新不存在的槽位返回false`() {
        val result = system.updatePlantSlot(99) { it.copy(seedId = "s1") }
        assertFalse(result)
    }

    @Test
    fun `updatePlantSlots - 批量更新槽位`() {
        system.loadHerbGardenData(
            listOf(PlantSlotData(index = 0), PlantSlotData(index = 1)),
            emptyList(),
            emptyList()
        )
        system.updatePlantSlots { slots ->
            slots.map { it.copy(seedId = "s1") }
        }
        system.getPlantSlots().forEach { slot ->
            assertEquals("s1", slot.seedId)
        }
    }

    @Test
    fun `clear - 清空所有数据`() = runBlocking {
        system.loadHerbGardenData(
            listOf(PlantSlotData(index = 0)),
            listOf(Herb(id = "h1", name = "灵草")),
            listOf(Seed(id = "s1", name = "种子"))
        )
        system.clear()
        assertEquals(0, system.getPlantSlots().size)
        assertEquals(0, system.herbs.value.size)
        assertEquals(0, system.seeds.value.size)
    }

    @Test
    fun `systemName 正确`() {
        assertEquals("HerbGardenSubsystem", system.systemName)
    }
}
