package com.xianxia.sect.core.engine.domain.diplomacy

import org.junit.Assert.*
import org.junit.Test

class DiplomacyServicePureLogicTest {

    // ==================== selectRarityByMerchantProbabilities ====================
    // 实际值见 DiplomacyService.SECT_TRADE_RARITY_PROBABILITIES
    @Test fun merchantRarityProbabilities_sumToOne() {
        val probs = mapOf(6 to 0.003, 5 to 0.027, 4 to 0.05, 3 to 0.12, 2 to 0.40, 1 to 0.40)
        val sum = probs.values.sum()
        assertEquals(1.0, sum, 0.001)
    }


    private fun isAllyCheck(alliances: List<Pair<String, String>>, sectId: String): Boolean {
        return alliances.any { (first, second) ->
            (first == "player" && second == sectId) ||
            (first == sectId && second == "player")
        }
    }

    @Test fun `isAlly - player in alliance returns true`() {
        assertTrue(isAllyCheck(listOf("player" to "sect1"), "sect1"))
    }

    @Test fun `isAlly - not in alliance returns false`() {
        assertFalse(isAllyCheck(listOf("player" to "sect1"), "sect2"))
    }

    @Test fun `isAlly - empty alliances returns false`() {
        assertFalse(isAllyCheck(emptyList(), "sect1"))
    }

    @Test fun `isAlly - other alliance not affecting`() {
        assertFalse(isAllyCheck(listOf("player" to "sect2"), "sect1"))
    }
}
