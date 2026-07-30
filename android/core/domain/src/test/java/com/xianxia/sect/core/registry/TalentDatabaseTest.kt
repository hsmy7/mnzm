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
    fun `all talents have valid rarity 0 to 3`() {
        TalentDatabase.talents.values.forEach { talent ->
            assertTrue("talent ${talent.id} rarity ${talent.rarity} not in 0-3", talent.rarity in 0..3)
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
    fun `getByRarity returns talents for each grade 1 to 3`() {
        for (rarity in 1..3) {
            val talents = TalentDatabase.getByRarity(rarity)
            assertTrue("grade $rarity should have talents", talents.isNotEmpty())
            talents.forEach { talent ->
                assertEquals(rarity, talent.rarity)
            }
        }
    }

    @Test
    fun `getByRarity returns negative talents for rarity 0`() {
        val negativeTalents = TalentDatabase.getByRarity(0)
        assertTrue("rarity 0 should have negative talents", negativeTalents.isNotEmpty())
        negativeTalents.forEach { talent ->
            assertTrue("rarity 0 talent should be negative", talent.isNegative)
        }
    }

    @Test
    fun `getByRarity returns empty for invalid rarity`() {
        assertTrue(TalentDatabase.getByRarity(99).isEmpty())
    }

    // 6. calculateTalentEffects returns correct effect map for known talent IDs
    @Test
    fun `calculateTalentEffects returns correct effects for known talent ids`() {
        val talentId = "r1_cult_speed"
        val effects = TalentDatabase.calculateTalentEffects(listOf(talentId))
        assertTrue("effects should contain cultivationSpeed", effects.containsKey("cultivationSpeed"))
        assertEquals(0.06, effects["cultivationSpeed"]!!, 0.001)
    }

    @Test
    fun `calculateTalentEffects accumulates effects from multiple talents`() {
        val effects = TalentDatabase.calculateTalentEffects(listOf("r1_cult_speed", "r2_cult_speed"))
        assertTrue("effects should contain cultivationSpeed", effects.containsKey("cultivationSpeed"))
        assertEquals(0.06 + 0.10, effects["cultivationSpeed"]!!, 0.001)
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

    // 9. All talents have non-empty effect keys (position bonus talents excluded — they use positionBonus instead)
    @Test
    fun `all talents have non-empty effect keys`() {
        TalentDatabase.talents.values.filter { it.effects.isEmpty() }.forEach { talent ->
            // Position-only talents have empty effects but must have positionBonus
            if (talent.positionBonus == null) {
                assertTrue("talent ${talent.id} should have non-empty effects or positionBonus",
                    talent.effects.isNotEmpty())
            }
        }
    }

    // 10. 正天赋品级名验证
    @Test
    fun `positive talents have correct grade name`() {
        TalentDatabase.talents.values.filter { !it.isNegative }.forEach { talent ->
            when (talent.rarity) {
                1 -> assertEquals("下品", talent.rarityName)
                2 -> assertEquals("中品", talent.rarityName)
                3 -> assertEquals("上品", talent.rarityName)
            }
        }
    }

    // 11. 正天赋颜色验证
    @Test
    fun `positive talents have correct grade color`() {
        TalentDatabase.talents.values.filter { !it.isNegative }.forEach { talent ->
            when (talent.rarity) {
                1 -> assertEquals("#4CAF50", talent.color)
                2 -> assertEquals("#2196F3", talent.color)
                3 -> assertEquals("#E74C3C", talent.color)
            }
        }
    }

    // 12. 负天赋颜色为灰色
    @Test
    fun `negative talents have gray color`() {
        TalentDatabase.talents.values.filter { it.isNegative }.forEach { talent ->
            assertEquals("#9E9E9E", talent.color)
        }
    }

    // 13. 负天赋品级名为"负面"（负面无品阶）
    @Test
    fun `negative talents have negative grade name`() {
        TalentDatabase.talents.values.filter { it.isNegative }.forEach { talent ->
            assertEquals("负面", talent.rarityName)
        }
    }
}
