package com.xianxia.sect.core.model

import com.xianxia.sect.core.model.production.ProductionSlot
import org.junit.Assert.*
import org.junit.Test

class BuildingStateTest {

    // ==================== 默认构造 ====================

    @Test
    fun defaultConstruction_allFieldsEmpty() {
        val state = BuildingState()
        assertTrue(state.productionSlots.isEmpty())
        assertTrue(state.spiritMineSlots.isEmpty())
        assertTrue(state.librarySlots.isEmpty())
    }

    // ==================== 带参构造 ====================

    @Test
    fun parameterizedConstruction() {
        val productionSlots = listOf(
            ProductionSlot(slotIndex = 0, recipeId = "r1", recipeName = "丹方1")
        )
        val spiritMineSlots = listOf(
            SpiritMineSlot(index = 0, discipleId = "d1", discipleName = "弟子1")
        )
        val librarySlots = listOf(
            LibrarySlot(index = 0, discipleId = "d2", discipleName = "弟子2")
        )
        val state = BuildingState(
            productionSlots = productionSlots,
            spiritMineSlots = spiritMineSlots,
            librarySlots = librarySlots
        )
        assertEquals(1, state.productionSlots.size)
        assertEquals(1, state.spiritMineSlots.size)
        assertEquals(1, state.librarySlots.size)
        assertEquals("r1", state.productionSlots[0].recipeId)
        assertEquals("d1", state.spiritMineSlots[0].discipleId)
        assertEquals("d2", state.librarySlots[0].discipleId)
    }

    // ==================== copy ====================

    @Test
    fun copy_modifiesOnlySpecifiedFields() {
        val original = BuildingState(
            productionSlots = listOf(ProductionSlot(slotIndex = 0)),
            spiritMineSlots = listOf(SpiritMineSlot(index = 0)),
            librarySlots = listOf(LibrarySlot(index = 0))
        )
        val copied = original.copy(productionSlots = emptyList())
        assertTrue(copied.productionSlots.isEmpty())
        assertEquals(1, copied.spiritMineSlots.size)
        assertEquals(1, copied.librarySlots.size)
    }

    @Test
    fun copy_preservesAllFields() {
        val original = BuildingState(
            productionSlots = listOf(ProductionSlot(slotIndex = 1)),
            spiritMineSlots = listOf(SpiritMineSlot(index = 2)),
            librarySlots = listOf(LibrarySlot(index = 3))
        )
        val copied = original.copy()
        assertEquals(original, copied)
    }

    // ==================== SpiritMineSlot ====================

    @Test
    fun spiritMineSlot_isActive_whenDiscipleIdNotEmpty() {
        val slot = SpiritMineSlot(discipleId = "d1")
        assertTrue(slot.isActive)
    }

    @Test
    fun spiritMineSlot_isNotActive_whenDiscipleIdEmpty() {
        val slot = SpiritMineSlot(discipleId = "")
        assertFalse(slot.isActive)
    }

    @Test
    fun spiritMineSlot_defaultOutput() {
        val slot = SpiritMineSlot()
        assertEquals(100, slot.output)
    }

    // ==================== LibrarySlot ====================

    @Test
    fun librarySlot_isActive_whenDiscipleIdNotEmpty() {
        val slot = LibrarySlot(discipleId = "d1")
        assertTrue(slot.isActive)
    }

    @Test
    fun librarySlot_isNotActive_whenDiscipleIdEmpty() {
        val slot = LibrarySlot(discipleId = "")
        assertFalse(slot.isActive)
    }

    // ==================== data class 相等性 ====================

    @Test
    fun equality_sameData_returnsTrue() {
        val state1 = BuildingState()
        val state2 = BuildingState()
        assertEquals(state1, state2)
    }

    @Test
    fun equality_differentData_returnsFalse() {
        val state1 = BuildingState(productionSlots = listOf(ProductionSlot(slotIndex = 0)))
        val state2 = BuildingState()
        assertNotEquals(state1, state2)
    }
}
