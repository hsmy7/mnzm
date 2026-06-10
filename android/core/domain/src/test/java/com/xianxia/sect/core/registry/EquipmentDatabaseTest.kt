package com.xianxia.sect.core.registry

import com.xianxia.sect.core.model.EquipmentSlot
import org.junit.Assert.*
import org.junit.Test

class EquipmentDatabaseTest {

    // 1. getAllEquipment() returns non-empty list
    @Test
    fun allTemplates_isNotEmpty() {
        assertTrue(EquipmentDatabase.allTemplates.isNotEmpty())
    }

    // 2. All equipment have valid rarity (1-6)
    @Test
    fun allTemplates_haveValidRarity() {
        for (template in EquipmentDatabase.allTemplates.values) {
            assertTrue(
                "Equipment ${template.id} has invalid rarity: ${template.rarity}",
                template.rarity in 1..6
            )
        }
    }

    // 3. All equipment have non-blank id and name
    @Test
    fun allTemplates_haveNonBlankIdAndName() {
        for (template in EquipmentDatabase.allTemplates.values) {
            assertTrue(
                "Equipment has blank id",
                template.id.isNotBlank()
            )
            assertTrue(
                "Equipment ${template.id} has blank name",
                template.name.isNotBlank()
            )
        }
    }

    // 4. Equipment IDs are unique
    @Test
    fun allTemplates_haveUniqueIds() {
        val ids = EquipmentDatabase.allTemplates.keys
        val distinctIds = ids.toSet()
        assertEquals(
            "Duplicate equipment IDs found",
            ids.size,
            distinctIds.size
        )
    }

    // 5. getBySlot(slot) returns equipment for specific slot
    @Test
    fun getBySlot_returnsEquipmentForSpecificSlot() {
        for (slot in EquipmentSlot.values()) {
            val result = EquipmentDatabase.getBySlot(slot)
            assertTrue(
                "getBySlot($slot) returned empty list",
                result.isNotEmpty()
            )
            for (template in result) {
                assertEquals(
                    "Equipment ${template.id} has wrong slot",
                    slot,
                    template.slot
                )
            }
        }
    }

    // 6. getByRarity(rarity) returns equipment of specific rarity
    @Test
    fun getByRarity_returnsEquipmentOfSpecificRarity() {
        for (rarity in 1..6) {
            val result = EquipmentDatabase.getByRarity(rarity)
            assertTrue(
                "getByRarity($rarity) returned empty list",
                result.isNotEmpty()
            )
            for (template in result) {
                assertEquals(
                    "Equipment ${template.id} has wrong rarity",
                    rarity,
                    template.rarity
                )
            }
        }
    }

    // 7. getById returns equipment for known id, null for unknown
    @Test
    fun getById_returnsEquipmentForKnownId() {
        val knownId = EquipmentDatabase.allTemplates.keys.first()
        val result = EquipmentDatabase.getById(knownId)
        assertNotNull("getById returned null for known id: $knownId", result)
        assertEquals(knownId, result!!.id)
    }

    @Test
    fun getById_returnsNullForUnknownId() {
        val result = EquipmentDatabase.getById("nonExistentId12345")
        assertNull("getById should return null for unknown id", result)
    }

    // 8. Equipment covers all rarity levels (1-6)
    @Test
    fun allTemplates_coversAllRarityLevels() {
        val rarities = EquipmentDatabase.allTemplates.values.map { it.rarity }.toSet()
        for (rarity in 1..6) {
            assertTrue(
                "No equipment found for rarity $rarity",
                rarities.contains(rarity)
            )
        }
    }
}
