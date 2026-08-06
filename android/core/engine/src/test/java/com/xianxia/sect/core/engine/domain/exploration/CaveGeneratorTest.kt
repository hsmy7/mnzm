package com.xianxia.sect.core.engine.domain.exploration

import org.junit.Assert.*
import org.junit.Test

class CaveGeneratorTest {

    // ---- getRarityRangeForCave ----

    @Test
    fun getRarityRangeForCave_realm5_returnsCorrectRange() {
        val range = CaveGenerator.getRarityRangeForCave(5)
        assertEquals(listOf(3, 4, 5), range)
    }

    @Test
    fun getRarityRangeForCave_realm4_returnsCorrectRange() {
        val range = CaveGenerator.getRarityRangeForCave(4)
        assertEquals(listOf(3, 4, 5), range)
    }

    @Test
    fun getRarityRangeForCave_realm3_returnsCorrectRange() {
        val range = CaveGenerator.getRarityRangeForCave(3)
        assertEquals(listOf(4, 5), range)
    }

    @Test
    fun getRarityRangeForCave_realm2_returnsCorrectRange() {
        val range = CaveGenerator.getRarityRangeForCave(2)
        assertEquals(listOf(4, 5, 6), range)
    }

    @Test
    fun getRarityRangeForCave_realm1_returnsCorrectRange() {
        val range = CaveGenerator.getRarityRangeForCave(1)
        assertEquals(listOf(5, 6), range)
    }

    @Test
    fun getRarityRangeForCave_unknownRealm_returnsDefault() {
        val range = CaveGenerator.getRarityRangeForCave(99)
        assertEquals(listOf(1, 2, 3), range)
    }

    // ---- CaveRealmConfig ----

    @Test
    fun caveRealmConfig_construction() {
        val config = CaveGenerator.CaveRealmConfig(
            realm = 5,
            realmName = "化神",
            rarityRange = listOf(3, 4, 5)
        )
        assertEquals(5, config.realm)
        assertEquals("化神", config.realmName)
        assertEquals(listOf(3, 4, 5), config.rarityRange)
    }

}
