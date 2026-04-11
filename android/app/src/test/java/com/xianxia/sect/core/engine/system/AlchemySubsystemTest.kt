package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.model.AlchemySlot
import com.xianxia.sect.core.model.AlchemySlotStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AlchemySubsystemTest {

    private lateinit var system: AlchemySubsystem

    @Before
    fun setUp() {
        system = AlchemySubsystem()
        system.initialize()
    }

    private fun createSlot(slotIndex: Int): AlchemySlot {
        return AlchemySlot(slotIndex = slotIndex)
    }

    @Test
    fun `loadAlchemyData - 加载炼丹数据`() {
        val slots = listOf(createSlot(0), createSlot(1))
        system.loadAlchemyData(slots)
        assertEquals(2, system.getAlchemySlots().size)
    }

    @Test
    fun `getAlchemySlot - 按索引获取槽位`() {
        val slots = listOf(createSlot(0), createSlot(1), createSlot(2))
        system.loadAlchemyData(slots)
        val slot = system.getAlchemySlot(1)
        assertNotNull(slot)
        assertEquals(1, slot!!.slotIndex)
    }

    @Test
    fun `getAlchemySlot - 越界索引返回null`() {
        system.loadAlchemyData(listOf(createSlot(0)))
        assertNull(system.getAlchemySlot(5))
    }

    @Test
    fun `getAlchemySlot - 负数索引返回null`() {
        system.loadAlchemyData(listOf(createSlot(0)))
        assertNull(system.getAlchemySlot(-1))
    }

    @Test
    fun `loadAlchemyData - 空列表`() {
        system.loadAlchemyData(emptyList())
        assertEquals(0, system.getAlchemySlots().size)
    }

    @Test
    fun `loadAlchemyData - 覆盖旧数据`() {
        system.loadAlchemyData(listOf(createSlot(0)))
        system.loadAlchemyData(listOf(createSlot(0), createSlot(1)))
        assertEquals(2, system.getAlchemySlots().size)
    }

    @Test
    fun `alchemySlots StateFlow 反映最新数据`() {
        val slots = listOf(createSlot(0))
        system.loadAlchemyData(slots)
        assertEquals(1, system.alchemySlots.value.size)
    }

    @Test
    fun `clear - 清空炼丹数据`() = runBlocking {
        system.loadAlchemyData(listOf(createSlot(0)))
        system.clear()
        assertEquals(0, system.getAlchemySlots().size)
    }

    @Test
    fun `systemName 正确`() {
        assertEquals("AlchemySubsystem", system.systemName)
    }

    @Test
    fun `AlchemySlot - isIdle 默认为true`() {
        val slot = createSlot(0)
        assertTrue(slot.isIdle)
    }

    @Test
    fun `AlchemySlot - isWorking 默认为false`() {
        val slot = createSlot(0)
        assertFalse(slot.isWorking)
    }

    @Test
    fun `AlchemySlot - getRemainingMonths 非WORKING状态返回0`() {
        val slot = createSlot(0)
        assertEquals(0, slot.getRemainingMonths(1, 1))
    }

    @Test
    fun `AlchemySlot - getProgressPercent 非WORKING状态返回0`() {
        val slot = createSlot(0)
        assertEquals(0, slot.getProgressPercent(1, 1))
    }
}
