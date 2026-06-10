package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.model.CaveStatus
import com.xianxia.sect.core.model.CultivatorCave
import com.xianxia.sect.core.model.WorldSect
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

    // ---- generateCaves ----

    @Test
    fun generateCaves_returnsListWithinMaxNewCaves() {
        val caves = CaveGenerator.generateCaves(
            existingSects = emptyList(),
            connectionEdges = emptyList(),
            currentYear = 1,
            currentMonth = 1,
            existingCaves = emptyList(),
            maxNewCaves = 2
        )
        assertTrue("Caves count should be <= 2", caves.size <= 2)
    }

    @Test
    fun generateCaves_caveHasCorrectSpawnTime() {
        val caves = CaveGenerator.generateCaves(
            existingSects = emptyList(),
            connectionEdges = emptyList(),
            currentYear = 10,
            currentMonth = 5,
            existingCaves = emptyList(),
            maxNewCaves = 2
        )
        for (cave in caves) {
            assertEquals(10, cave.spawnYear)
            assertEquals(5, cave.spawnMonth)
        }
    }

    @Test
    fun generateCaves_caveExpiryIsOneYearLater() {
        val caves = CaveGenerator.generateCaves(
            existingSects = emptyList(),
            connectionEdges = emptyList(),
            currentYear = 10,
            currentMonth = 5,
            existingCaves = emptyList(),
            maxNewCaves = 2
        )
        for (cave in caves) {
            assertEquals(11, cave.expiryYear)
            assertEquals(5, cave.expiryMonth)
        }
    }

    @Test
    fun generateCaves_caveStatusIsAvailable() {
        val caves = CaveGenerator.generateCaves(
            existingSects = emptyList(),
            connectionEdges = emptyList(),
            currentYear = 1,
            currentMonth = 1,
            existingCaves = emptyList(),
            maxNewCaves = 2
        )
        for (cave in caves) {
            assertEquals(CaveStatus.AVAILABLE, cave.status)
        }
    }

    @Test
    fun generateCaves_caveRealmIsValid() {
        val validRealms = setOf(1, 2, 3, 4, 5)
        val caves = CaveGenerator.generateCaves(
            existingSects = emptyList(),
            connectionEdges = emptyList(),
            currentYear = 1,
            currentMonth = 1,
            existingCaves = emptyList(),
            maxNewCaves = 5
        )
        for (cave in caves) {
            assertTrue(
                "Cave realm ${cave.ownerRealm} should be in $validRealms",
                cave.ownerRealm in validRealms
            )
        }
    }

    @Test
    fun generateCaves_caveIdStartsWithPrefix() {
        val caves = CaveGenerator.generateCaves(
            existingSects = emptyList(),
            connectionEdges = emptyList(),
            currentYear = 1,
            currentMonth = 1,
            existingCaves = emptyList(),
            maxNewCaves = 2
        )
        for (cave in caves) {
            assertTrue("Cave ID should start with 'cave_'", cave.id.startsWith("cave_"))
        }
    }

    @Test
    fun generateCaves_caveNameIsNotEmpty() {
        val caves = CaveGenerator.generateCaves(
            existingSects = emptyList(),
            connectionEdges = emptyList(),
            currentYear = 1,
            currentMonth = 1,
            existingCaves = emptyList(),
            maxNewCaves = 2
        )
        for (cave in caves) {
            assertTrue("Cave name should not be empty", cave.name.isNotEmpty())
        }
    }

    @Test
    fun generateCaves_zeroMaxNewCaves_returnsEmptyOrSmallList() {
        val caves = CaveGenerator.generateCaves(
            existingSects = emptyList(),
            connectionEdges = emptyList(),
            currentYear = 1,
            currentMonth = 1,
            existingCaves = emptyList(),
            maxNewCaves = 0
        )
        assertEquals(0, caves.size)
    }
}
