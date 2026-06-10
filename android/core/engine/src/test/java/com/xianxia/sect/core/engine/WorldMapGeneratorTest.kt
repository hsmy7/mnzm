package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.SectRelation
import com.xianxia.sect.core.model.WorldSect
import org.junit.Assert.*
import org.junit.Test

class WorldMapGeneratorTest {

    // ---- initializeSectRelations ----

    @Test
    fun initializeSectRelations_noPlayerSect_createsRelationsBetweenAiSects() {
        val sects = listOf(
            WorldSect(id = "s1", isPlayerSect = false),
            WorldSect(id = "s2", isPlayerSect = false),
            WorldSect(id = "s3", isPlayerSect = false)
        )
        val relations = WorldMapGenerator.initializeSectRelations(sects)
        // 3 AI sects: C(3,2) = 3 pairs
        assertEquals(3, relations.size)
    }

    @Test
    fun initializeSectRelations_withPlayerSect_includesPlayerRelations() {
        val sects = listOf(
            WorldSect(id = "player", isPlayerSect = true),
            WorldSect(id = "s1", isPlayerSect = false),
            WorldSect(id = "s2", isPlayerSect = false)
        )
        val relations = WorldMapGenerator.initializeSectRelations(sects)
        // 1 AI pair + 2 player-AI pairs = 3
        assertEquals(3, relations.size)
    }

    @Test
    fun initializeSectRelations_singleAiSect_noRelations() {
        val sects = listOf(
            WorldSect(id = "player", isPlayerSect = true),
            WorldSect(id = "s1", isPlayerSect = false)
        )
        val relations = WorldMapGenerator.initializeSectRelations(sects)
        // Only player-s1 pair
        assertEquals(1, relations.size)
    }

    @Test
    fun initializeSectRelations_favorInRange() {
        val sects = listOf(
            WorldSect(id = "s1", isPlayerSect = false),
            WorldSect(id = "s2", isPlayerSect = false),
            WorldSect(id = "s3", isPlayerSect = true)
        )
        val relations = WorldMapGenerator.initializeSectRelations(sects)
        for (rel in relations) {
            assertTrue("Favor should be in [40, 60]", rel.favor in 40..60)
        }
    }

    @Test
    fun initializeSectRelations_lastInteractionYearIsZero() {
        val sects = listOf(
            WorldSect(id = "s1", isPlayerSect = false),
            WorldSect(id = "s2", isPlayerSect = false)
        )
        val relations = WorldMapGenerator.initializeSectRelations(sects)
        for (rel in relations) {
            assertEquals(0, rel.lastInteractionYear)
        }
    }

    // ---- calculateInitialFavorForSects ----

    @Test
    fun calculateInitialFavorForSects_returnsValueInRange() {
        val favor = WorldMapGenerator.calculateInitialFavorForSects()
        assertTrue("Favor should be in [40, 60]", favor in 40..60)
    }

    @Test
    fun calculateInitialFavorForSects_multipleCallsVary() {
        val favors = (1..50).map { WorldMapGenerator.calculateInitialFavorForSects() }.toSet()
        // With 21 possible values (40-60), 50 calls should produce at least 2 different values
        assertTrue("Multiple calls should produce varied results", favors.size >= 2)
    }

    // ---- SectLevelInfo ----

    @Test
    fun sectLevelInfo_construction() {
        val info = WorldMapGenerator.SectLevelInfo(
            level = 2,
            levelName = "大型宗门",
            disciples = mapOf(5 to 100, 6 to 50)
        )
        assertEquals(2, info.level)
        assertEquals("大型宗门", info.levelName)
        assertEquals(2, info.disciples.size)
    }
}
