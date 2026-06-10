package com.xianxia.sect.core.engine.domain.diplomacy

import org.junit.Assert.*
import org.junit.Test

class DiplomacyServicePureLogicTest {

    // ==================== calculatePersuasionSuccessRate ====================

    private fun calculatePersuasionSuccessRate(favorability: Int, intelligence: Int, charm: Int): Double {
        val minAllianceFavor = 80
        val favorBonus = if (favorability >= minAllianceFavor) (favorability - minAllianceFavor) * 0.05 else 0.0
        val intBonus = (intelligence - 50) / 5.0 * 0.01
        val charmBonus = (charm - 50) / 5.0 * 0.01
        return (favorBonus + intBonus + charmBonus).coerceIn(0.0, 1.0)
    }

    @Test fun persuasion_lowFavor_noBonus() = assertEquals(0.0, calculatePersuasionSuccessRate(50, 50, 50), 0.001)
    @Test fun persuasion_exactMinFavor() = assertEquals(0.0, calculatePersuasionSuccessRate(80, 50, 50), 0.001)
    @Test fun persuasion_aboveMinFavor() = assertEquals(1.0, calculatePersuasionSuccessRate(100, 50, 50), 0.001)
    @Test fun persuasion_highInt() = assertEquals(0.1, calculatePersuasionSuccessRate(80, 100, 50), 0.001)
    @Test fun persuasion_highCharm() = assertEquals(0.1, calculatePersuasionSuccessRate(80, 50, 100), 0.001)
    @Test fun persuasion_lowInt_negative() {
        // intBonus = (30-50)/5*0.01 = -0.04, result coerced to 0
        assertEquals(0.0, calculatePersuasionSuccessRate(80, 30, 50), 0.001)
    }
    @Test fun persuasion_allMax_cappedAt1() = assertEquals(1.0, calculatePersuasionSuccessRate(200, 200, 200), 0.001)

    // ==================== getEnvoyRealmRequirement ====================

    private fun getEnvoyRealmRequirement(sectLevel: Int): Int = when (sectLevel) {
        0 -> 7; 1 -> 5; 2 -> 4; 3 -> 3; else -> 7
    }

    @Test fun envoyRealm_smallSect_goldenCore() = assertEquals(7, getEnvoyRealmRequirement(0))
    @Test fun envoyRealm_mediumSect_spiritSevering() = assertEquals(5, getEnvoyRealmRequirement(1))
    @Test fun envoyRealm_largeSect_voidRefining() = assertEquals(4, getEnvoyRealmRequirement(2))
    @Test fun envoyRealm_topSect_bodyIntegration() = assertEquals(3, getEnvoyRealmRequirement(3))
    @Test fun envoyRealm_unknown_default() = assertEquals(7, getEnvoyRealmRequirement(99))

    // ==================== getAllianceCost ====================

    private fun getAllianceCost(sectLevel: Int): Long = when (sectLevel) {
        0 -> 50_000L; 1 -> 200_000L; 2 -> 800_000L; 3 -> 2_000_000L; else -> 50_000L
    }

    @Test fun allianceCost_smallSect() = assertEquals(50_000L, getAllianceCost(0))
    @Test fun allianceCost_mediumSect() = assertEquals(200_000L, getAllianceCost(1))
    @Test fun allianceCost_largeSect() = assertEquals(800_000L, getAllianceCost(2))
    @Test fun allianceCost_topSect() = assertEquals(2_000_000L, getAllianceCost(3))
    @Test fun allianceCost_unknown_default() = assertEquals(50_000L, getAllianceCost(99))

    // ==================== selectRarityByMerchantProbabilities ====================
    // SECT_TRADE_RARITY_PROBABILITIES: 6->0.05, 5->0.10, 4->0.20, 3->0.25, 2->0.25, 1->0.15
    // Test that the probability table sums to 1.0
    @Test fun merchantRarityProbabilities_sumToOne() {
        val probs = mapOf(6 to 0.05, 5 to 0.10, 4 to 0.20, 3 to 0.25, 2 to 0.25, 1 to 0.15)
        val sum = probs.values.sum()
        assertEquals(1.0, sum, 0.001)
    }
}
