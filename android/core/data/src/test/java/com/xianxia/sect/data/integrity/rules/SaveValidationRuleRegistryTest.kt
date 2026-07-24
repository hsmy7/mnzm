package com.xianxia.sect.data.integrity.rules

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SaveValidationRuleRegistryTest {

    @Before fun setup() { SaveValidationRuleRegistry.clear() }
    @After fun teardown() { SaveValidationRuleRegistry.clear() }

    @Test fun `starts empty`() {
        assertEquals(0, SaveValidationRuleRegistry.size)
    }

    @Test fun `register adds rule`() {
        SaveValidationRuleRegistry.register(SectNameRule)
        assertEquals(1, SaveValidationRuleRegistry.size)
    }

    @Test fun `register same id replaces`() {
        SaveValidationRuleRegistry.register(SectNameRule)
        SaveValidationRuleRegistry.register(SectNameRule)
        assertEquals(1, SaveValidationRuleRegistry.size)
    }

    @Test fun `rules sorted by order`() {
        SaveValidationRuleRegistry.register(GhostRefCleanupRule) // order=11
        SaveValidationRuleRegistry.register(SectNameRule)        // order=1
        val all = SaveValidationRuleRegistry.all
        assertEquals(2, all.size)
        assertEquals("sect_name", all[0].id)
        assertEquals("ghost_ref_cleanup", all[1].id)
    }

    @Test fun `clear removes all`() {
        SaveValidationRuleRegistry.register(SectNameRule)
        SaveValidationRuleRegistry.register(GameDateRule)
        SaveValidationRuleRegistry.clear()
        assertEquals(0, SaveValidationRuleRegistry.size)
    }

    @Test fun `findById returns correct rule`() {
        SaveValidationRuleRegistry.register(SectNameRule)
        assertNotNull(SaveValidationRuleRegistry.findById("sect_name"))
        assertNull(SaveValidationRuleRegistry.findById("nonexistent"))
    }

    @Test fun `unregister removes specific rule`() {
        SaveValidationRuleRegistry.register(SectNameRule)
        SaveValidationRuleRegistry.register(GameDateRule)
        SaveValidationRuleRegistry.unregister("sect_name")
        assertEquals(1, SaveValidationRuleRegistry.size)
        assertEquals("game_date", SaveValidationRuleRegistry.all[0].id)
    }

    @Test fun `registerDefaults registers all rules`() {
        SaveValidationRuleRegistry.registerDefaults()
        assertTrue(SaveValidationRuleRegistry.size >= 14)
    }
}
