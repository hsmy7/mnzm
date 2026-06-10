package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.model.WorldSect
import org.junit.Assert.*
import org.junit.Test

class LevelGeneratorTest {

    // ---- getCaveReward ----

    @Test
    fun getCaveReward_realm5_returnsCorrectConfig() {
        val reward = LevelGenerator.getCaveReward(5)
        assertEquals(20000.0, reward.baseSpiritStones, 0.01)
        assertEquals(1 to 2, reward.rarityRange)
    }

    @Test
    fun getCaveReward_realm4_returnsCorrectConfig() {
        val reward = LevelGenerator.getCaveReward(4)
        assertEquals(100000.0, reward.baseSpiritStones, 0.01)
        assertEquals(2 to 3, reward.rarityRange)
    }

    @Test
    fun getCaveReward_realm3_returnsCorrectConfig() {
        val reward = LevelGenerator.getCaveReward(3)
        assertEquals(300000.0, reward.baseSpiritStones, 0.01)
        assertEquals(2 to 5, reward.rarityRange)
    }

    @Test
    fun getCaveReward_realm2_returnsCorrectConfig() {
        val reward = LevelGenerator.getCaveReward(2)
        assertEquals(700000.0, reward.baseSpiritStones, 0.01)
        assertEquals(3 to 6, reward.rarityRange)
    }

    @Test
    fun getCaveReward_realm1_returnsCorrectConfig() {
        val reward = LevelGenerator.getCaveReward(1)
        assertEquals(1500000.0, reward.baseSpiritStones, 0.01)
        assertEquals(5 to 6, reward.rarityRange)
    }

    @Test
    fun getCaveReward_unknownRealm_returnsDefault() {
        val reward = LevelGenerator.getCaveReward(99)
        assertEquals(20000.0, reward.baseSpiritStones, 0.01)
        assertEquals(1 to 2, reward.rarityRange)
    }

    // ---- CaveRewardConfig ----

    @Test
    fun caveRewardConfig_construction() {
        val config = LevelGenerator.CaveRewardConfig(
            baseSpiritStones = 50000.0,
            rarityRange = 2 to 4
        )
        assertEquals(50000.0, config.baseSpiritStones, 0.01)
        assertEquals(2 to 4, config.rarityRange)
    }

    // ---- buildConnectionEdges ----

    @Test
    fun buildConnectionEdges_emptySects_returnsEmptyList() {
        val edges = LevelGenerator.buildConnectionEdges(emptyList())
        assertEquals(0, edges.size)
    }

    @Test
    fun buildConnectionEdges_singleSect_returnsEmptyList() {
        val sect = WorldSect(id = "s1", x = 0f, y = 0f)
        val edges = LevelGenerator.buildConnectionEdges(listOf(sect))
        assertEquals(0, edges.size)
    }

    @Test
    fun buildConnectionEdges_twoSects_returnsOneEdge() {
        val sect1 = WorldSect(id = "s1", x = 0f, y = 0f)
        val sect2 = WorldSect(id = "s2", x = 30f, y = 40f)
        val edges = LevelGenerator.buildConnectionEdges(listOf(sect1, sect2))
        assertEquals(1, edges.size)
        assertEquals(50.0, edges[0].weight, 0.01)
    }

    @Test
    fun buildConnectionEdges_threeSects_returnsThreeEdges() {
        val sect1 = WorldSect(id = "s1", x = 0f, y = 0f)
        val sect2 = WorldSect(id = "s2", x = 100f, y = 0f)
        val sect3 = WorldSect(id = "s3", x = 50f, y = 86f)
        val edges = LevelGenerator.buildConnectionEdges(listOf(sect1, sect2, sect3))
        // C(3,2) = 3 edges for all pairs
        assertEquals(3, edges.size)
    }

    // ---- generateWorldLevels ----

    @Test
    fun generateWorldLevels_returnsListWithinMaxNewLevels() {
        val levels = LevelGenerator.generateWorldLevels(
            existingSects = emptyList(),
            connectionEdges = emptyList(),
            currentYear = 1,
            currentMonth = 1,
            existingLevels = emptyList(),
            maxNewLevels = 3
        )
        assertTrue("Levels count should be <= 3", levels.size <= 3)
    }

    @Test
    fun generateWorldLevels_zeroMaxNewLevels_returnsEmptyList() {
        val levels = LevelGenerator.generateWorldLevels(
            existingSects = emptyList(),
            connectionEdges = emptyList(),
            currentYear = 1,
            currentMonth = 1,
            existingLevels = emptyList(),
            maxNewLevels = 0
        )
        assertEquals(0, levels.size)
    }

    @Test
    fun generateWorldLevels_levelHasCorrectSpawnTime() {
        val levels = LevelGenerator.generateWorldLevels(
            existingSects = emptyList(),
            connectionEdges = emptyList(),
            currentYear = 5,
            currentMonth = 3,
            existingLevels = emptyList(),
            maxNewLevels = 3
        )
        for (level in levels) {
            assertEquals(5, level.spawnYear)
            assertEquals(3, level.spawnMonth)
        }
    }

    @Test
    fun generateWorldLevels_levelTypeIsBeastOrCave() {
        val validTypes = setOf(com.xianxia.sect.core.model.LevelType.BEAST, com.xianxia.sect.core.model.LevelType.CAVE)
        val levels = LevelGenerator.generateWorldLevels(
            existingSects = emptyList(),
            connectionEdges = emptyList(),
            currentYear = 1,
            currentMonth = 1,
            existingLevels = emptyList(),
            maxNewLevels = 5
        )
        for (level in levels) {
            assertTrue("Level type should be BEAST or CAVE", level.type in validTypes)
        }
    }
}
