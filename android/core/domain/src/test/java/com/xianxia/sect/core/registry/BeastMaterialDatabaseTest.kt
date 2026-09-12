package com.xianxia.sect.core.registry

import org.junit.Assert.*
import org.junit.Test

class BeastMaterialDatabaseTest {

    // 1. All materials have valid data (non-blank id/name, valid rarity 1-6)
    @Test
    fun `all materials have non-blank id`() {
        BeastMaterialDatabase.getAllMaterials().forEach { material ->
            assertTrue("material id should not be blank", material.id.isNotBlank())
        }
    }

    @Test
    fun `all materials have non-blank name`() {
        BeastMaterialDatabase.getAllMaterials().forEach { material ->
            assertTrue("material name should not be blank", material.name.isNotBlank())
        }
    }

    @Test
    fun `all materials have valid rarity 1 to 6`() {
        BeastMaterialDatabase.getAllMaterials().forEach { material ->
            assertTrue("material ${material.id} rarity ${material.rarity} not in 1-6", material.rarity in 1..6)
        }
    }

    // 2. Material IDs are unique
    @Test
    fun `material ids are unique`() {
        val materials = BeastMaterialDatabase.getAllMaterials()
        val ids = materials.map { it.id }
        val uniqueIds = ids.toSet()
        assertEquals("material ids should be unique", uniqueIds.size, ids.size)
    }

    // 3. getMaterialById returns material for known id
    @Test
    fun `getMaterialById returns material for known id`() {
        val first = BeastMaterialDatabase.getAllMaterials().first()
        val result = BeastMaterialDatabase.getMaterialById(first.id)
        assertNotNull(result)
        assertEquals(first.id, result!!.id)
    }

    // 4. getMaterialById returns null for unknown id
    @Test
    fun `getMaterialById returns null for unknown id`() {
        assertNull(BeastMaterialDatabase.getMaterialById("nonexistent_material_id"))
    }

    // 5. getMaterialByName returns material for known name
    @Test
    fun `getMaterialByName returns material for known name`() {
        val first = BeastMaterialDatabase.getAllMaterials().first()
        val result = BeastMaterialDatabase.getMaterialByName(first.name)
        assertNotNull(result)
        assertEquals(first.name, result!!.name)
    }

    // 6. getMaterialByName returns null for unknown name
    @Test
    fun `getMaterialByName returns null for unknown name`() {
        assertNull(BeastMaterialDatabase.getMaterialByName("nonexistent_material"))
    }

    // 7. getMaterialsByRarity returns materials of specific rarity
    @Test
    fun `getMaterialsByRarity returns materials for each rarity 1 to 6`() {
        for (rarity in 1..6) {
            val materials = BeastMaterialDatabase.getMaterialsByRarity(rarity)
            assertTrue("rarity $rarity should have materials", materials.isNotEmpty())
            materials.forEach { material ->
                assertEquals(rarity, material.rarity)
            }
        }
    }

    // 8. getMaterialsByRarity returns empty for invalid rarity
    @Test
    fun `getMaterialsByRarity returns empty for invalid rarity`() {
        assertTrue(BeastMaterialDatabase.getMaterialsByRarity(0).isEmpty())
        assertTrue(BeastMaterialDatabase.getMaterialsByRarity(99).isEmpty())
    }

    // 9. getRandomMaterialByRealm returns material with valid rarity in range
    @Test
    fun `getRandomMaterialByRealm returns material with valid rarity`() {
        // Test multiple times to account for randomness
        repeat(50) {
            val material = BeastMaterialDatabase.getRandomMaterialByRealm(5)
            assertNotNull("should return a material for valid realm", material)
            assertTrue("material rarity should be in 1-6", material!!.rarity in 1..6)
        }
    }
}
