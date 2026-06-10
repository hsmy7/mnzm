package com.xianxia.sect.core.registry

import org.junit.Assert.*
import org.junit.Test

class TalentDatabaseTest {

    // 1. All talents have valid data (non-blank id/name, valid rarity 1-6)
    @Test
    fun `all talents have non-blank id`() {
        TalentDatabase.talents.values.forEach { talent ->
            assertTrue("talent id should not be blank", talent.id.isNotBlank())
        }
    }

    @Test
    fun `all talents have non-blank name`() {
        TalentDatabase.talents.values.forEach { talent ->
            assertTrue("talent name should not be blank", talent.name.isNotBlank())
        }
    }

    @Test
    fun `all talents have valid rarity 1 to 6`() {
        TalentDatabase.talents.values.forEach { talent ->
            assertTrue("talent ${talent.id} rarity ${talent.rarity} not in 1-6", talent.rarity in 1..6)
        }
    }

    // 2. Talent IDs are unique
    @Test
    fun `talent ids are unique`() {
        val talents = TalentDatabase.talents.values
        val ids = talents.map { it.id }
        val uniqueIds = ids.toSet()
        assertEquals("talent ids should be unique", uniqueIds.size, ids.size)
    }

    // 3. getById returns talent for known id
    @Test
    fun `getById returns talent for known id`() {
        val first = TalentDatabase.talents.values.first()
        val result = TalentDatabase.getById(first.id)
        assertNotNull(result)
        assertEquals(first.id, result!!.id)
    }

    // 4. getById returns null for unknown id
    @Test
    fun `getById returns null for unknown id`() {
        assertNull(TalentDatabase.getById("nonexistent_talent_id"))
    }

    // 5. getByRarity returns talents of specific rarity
    @Test
    fun `getByRarity returns talents for each rarity 1 to 6`() {
        for (rarity in 1..6) {
            val talents = TalentDatabase.getByRarity(rarity)
            assertTrue("rarity $rarity should have talents", talents.isNotEmpty())
            talents.forEach { talent ->
                assertEquals(rarity, talent.rarity)
            }
        }
    }

    @Test
    fun `getByRarity returns empty for invalid rarity`() {
        assertTrue(TalentDatabase.getByRarity(0).isEmpty())
        assertTrue(TalentDatabase.getByRarity(99).isEmpty())
    }

    // 6. calculateTalentEffects returns correct effect map for known talent IDs
    @Test
    fun `calculateTalentEffects returns correct effects for known talent ids`() {
        val talentId = "r1_cult_speed"
        val effects = TalentDatabase.calculateTalentEffects(listOf(talentId))
        assertTrue("effects should contain cultivationSpeed", effects.containsKey("cultivationSpeed"))
        assertEquals(0.08, effects["cultivationSpeed"]!!, 0.001)
    }

    @Test
    fun `calculateTalentEffects accumulates effects from multiple talents`() {
        val effects = TalentDatabase.calculateTalentEffects(listOf("r1_cult_speed", "r2_cult_speed"))
        assertTrue("effects should contain cultivationSpeed", effects.containsKey("cultivationSpeed"))
        assertEquals(0.08 + 0.14, effects["cultivationSpeed"]!!, 0.001)
    }

    // 7. calculateTalentEffects returns empty map for empty list
    @Test
    fun `calculateTalentEffects returns empty map for empty list`() {
        val effects = TalentDatabase.calculateTalentEffects(emptyList())
        assertTrue("effects should be empty for empty input", effects.isEmpty())
    }

    // 8. calculateTalentEffects returns empty map for unknown talent IDs
    @Test
    fun `calculateTalentEffects returns empty map for unknown talent ids`() {
        val effects = TalentDatabase.calculateTalentEffects(listOf("nonexistent_id_1", "nonexistent_id_2"))
        assertTrue("effects should be empty for unknown ids", effects.isEmpty())
    }

    // 9. All talents have non-empty effect keys
    @Test
    fun `all talents have non-empty effect keys`() {
        TalentDatabase.talents.values.forEach { talent ->
            assertTrue("talent ${talent.id} should have non-empty effects", talent.effects.isNotEmpty())
            talent.effects.keys.forEach { key ->
                assertTrue("talent ${talent.id} effect key should not be blank", key.isNotBlank())
            }
        }
    }
}
