package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.SlotCategory
import com.xianxia.sect.core.model.SlotRef
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DiscipleAssignmentRegistryTest {

    private lateinit var registry: DiscipleAssignmentRegistry

    private val elderSlotRef = SlotRef(
        category = SlotCategory.ELDER_POSITION,
        slotType = "viceSectMaster",
        slotId = "elder_viceSectMaster"
    )
    private val productionSlotRef = SlotRef(
        category = SlotCategory.PRODUCTION_SLOT,
        slotType = "alchemy:0",
        slotId = "production_alchemy_0"
    )
    private val spiritMineSlotRef = SlotRef(
        category = SlotCategory.SPIRIT_MINE,
        slotType = "miner:0",
        slotId = "spiritMine_miner_0"
    )

    @Before
    fun setUp() {
        registry = DiscipleAssignmentRegistry()
    }

    // ==================== tryRegister ====================

    @Test
    fun `tryRegister - empty registry returns null for new disciple`() {
        val result = registry.tryRegister("d1", elderSlotRef)
        assertNull("New disciple should be allowed to register", result)
        assertTrue("Disciple should be marked as assigned", registry.isAssigned("d1"))
    }

    @Test
    fun `tryRegister - same disciple to same slot returns null (re-allocation)`() {
        registry.tryRegister("d1", elderSlotRef)
        val result = registry.tryRegister("d1", elderSlotRef)
        assertNull("Re-registering same disciple to same slot should be allowed", result)
    }

    @Test
    fun `tryRegister - same disciple to different slot returns existing assignment`() {
        registry.tryRegister("d1", elderSlotRef)
        val result = registry.tryRegister("d1", productionSlotRef)
        assertNotNull("Disciple already in elder slot should be blocked from production", result)
        assertEquals("Blocked result should show elder slot", SlotCategory.ELDER_POSITION, result!!.slotRef.category)
    }

    @Test
    fun `tryRegister - different disciples can register independently`() {
        assertNull(registry.tryRegister("d1", elderSlotRef))
        assertNull(registry.tryRegister("d2", productionSlotRef))
        assertEquals("Two disciples should both be assigned", 2, registry.size())
    }

    // ==================== unregister ====================

    @Test
    fun `unregister - removes disciple from registry`() {
        registry.tryRegister("d1", elderSlotRef)
        assertTrue(registry.isAssigned("d1"))

        registry.unregister("d1")
        assertFalse("Disciple should no longer be assigned after unregister", registry.isAssigned("d1"))
    }

    @Test
    fun `unregister - non-existent disciple does not throw`() {
        registry.unregister("ghost_disciple") // should not throw
    }

    // ==================== getAssignment ====================

    @Test
    fun `getAssignment - returns correct slot for assigned disciple`() {
        registry.tryRegister("d1", elderSlotRef)
        val assignment = registry.getAssignment("d1")
        assertNotNull(assignment)
        assertEquals(elderSlotRef, assignment!!.slotRef)
    }

    @Test
    fun `getAssignment - returns null for unassigned disciple`() {
        val assignment = registry.getAssignment("d1")
        assertNull(assignment)
    }

    // ==================== isAssigned ====================

    @Test
    fun `isAssigned - returns true for registered disciple`() {
        registry.tryRegister("d1", elderSlotRef)
        assertTrue(registry.isAssigned("d1"))
    }

    @Test
    fun `isAssigned - returns false for unregistered disciple`() {
        assertFalse(registry.isAssigned("d1"))
    }

    // ==================== getAssignmentsByCategory ====================

    @Test
    fun `getAssignmentsByCategory - returns only matching category`() {
        registry.tryRegister("d1", elderSlotRef)
        registry.tryRegister("d2", productionSlotRef)

        val elderAssignments = registry.getAssignmentsByCategory(SlotCategory.ELDER_POSITION)
        assertEquals(1, elderAssignments.size)
        assertEquals("d1", elderAssignments[0].discipleId)

        val productionAssignments = registry.getAssignmentsByCategory(SlotCategory.PRODUCTION_SLOT)
        assertEquals(1, productionAssignments.size)
        assertEquals("d2", productionAssignments[0].discipleId)
    }

    // ==================== clear ====================

    @Test
    fun `clear - removes all registrations`() {
        registry.tryRegister("d1", elderSlotRef)
        registry.tryRegister("d2", productionSlotRef)
        assertEquals(2, registry.size())

        registry.clear()
        assertEquals(0, registry.size())
        assertFalse(registry.isAssigned("d1"))
        assertFalse(registry.isAssigned("d2"))
    }

    // ==================== updateSlot ====================

    @Test
    fun `updateSlot - updates slot ref for registered disciple`() {
        registry.tryRegister("d1", elderSlotRef)
        registry.updateSlot("d1", productionSlotRef)

        val assignment = registry.getAssignment("d1")
        assertEquals(productionSlotRef, assignment!!.slotRef)
    }

    @Test
    fun `updateSlot - does nothing for unregistered disciple`() {
        registry.updateSlot("d1", productionSlotRef) // should not throw
        assertNull(registry.getAssignment("d1"))
    }
}
