package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.SlotRef
import com.xianxia.sect.core.model.SlotCategory
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.SpiritMineSlot
import com.xianxia.sect.core.model.LibrarySlot
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DiscipleAssignmentGateTest {

    private lateinit var registry: DiscipleAssignmentRegistry
    private lateinit var gate: DiscipleAssignmentGate

    private val elderSlot = SlotRef(SlotCategory.ELDER_POSITION, "viceSectMaster", "elder_viceSectMaster")
    private val prodSlot = SlotRef(SlotCategory.PRODUCTION_SLOT, "alchemy:0", "production_alchemy_0")
    private val mineSlot = SlotRef(SlotCategory.SPIRIT_MINE, "miner:0", "spiritMine_miner_0")

    @Before
    fun setUp() {
        registry = DiscipleAssignmentRegistry()
        gate = DiscipleAssignmentGate(registry)
    }

    // ==================== confirmAssign / release ====================

    @Test
    fun `confirmAssign - registers disciple`() {
        gate.confirmAssign("d1", elderSlot)
        assertTrue(gate.isAssigned("d1"))
        assertEquals(elderSlot, gate.getAssignment("d1")?.slotRef)
    }

    @Test
    fun `confirmAssign - overwrites old assignment`() {
        gate.confirmAssign("d1", elderSlot)
        gate.confirmAssign("d1", prodSlot)
        assertEquals("Should overwrite with new slot", prodSlot, gate.getAssignment("d1")?.slotRef)
    }

    @Test
    fun `release - removes disciple from registry`() {
        gate.confirmAssign("d1", elderSlot)
        gate.release("d1")
        assertFalse(gate.isAssigned("d1"))
        assertNull(gate.getAssignment("d1"))
    }

    @Test
    fun `release - non-existent disciple does not throw`() {
        gate.release("ghost") // should not throw
    }

    // ==================== rebuildFromGameData ====================

    @Test
    fun `rebuildFromGameData - rebuilds from elder slots`() {
        val gameData = GameData(
            elderSlots = ElderSlots(viceSectMaster = "d1", alchemyElder = "d2")
        )
        gate.rebuildFromGameData(gameData)
        assertTrue("d1 should be registered", gate.isAssigned("d1"))
        assertTrue("d2 should be registered", gate.isAssigned("d2"))
        assertEquals(2, gate.size())
    }

    @Test
    fun `rebuildFromGameData - rebuilds from spirit mine slots`() {
        val gameData = GameData(
            spiritMineSlots = listOf(
                SpiritMineSlot(index = 0, discipleId = "d1"),
                SpiritMineSlot(index = 1, discipleId = "d2")
            )
        )
        gate.rebuildFromGameData(gameData)
        assertTrue(gate.isAssigned("d1"))
        assertTrue(gate.isAssigned("d2"))
    }

    @Test
    fun `rebuildFromGameData - rebuilds from library slots`() {
        val gameData = GameData(
            librarySlots = listOf(
                LibrarySlot(index = 0, discipleId = "d1"),
                LibrarySlot(index = 1, discipleId = "d2")
            )
        )
        gate.rebuildFromGameData(gameData)
        assertTrue(gate.isAssigned("d1"))
        assertTrue(gate.isAssigned("d2"))
    }

    @Test
    fun `rebuildFromGameData - clear before rebuild`() {
        gate.confirmAssign("d1", elderSlot)
        val gameData = GameData() // empty
        gate.rebuildFromGameData(gameData)
        assertEquals(0, gate.size())
    }

    // ==================== clear / manualRegister ====================

    @Test
    fun `clear - empties registry`() {
        gate.confirmAssign("d1", elderSlot)
        gate.confirmAssign("d2", prodSlot)
        gate.clear()
        assertEquals(0, gate.size())
    }

    @Test
    fun `manualRegister - registers directly`() {
        gate.manualRegister("d1", elderSlot)
        assertTrue(gate.isAssigned("d1"))
    }
}
