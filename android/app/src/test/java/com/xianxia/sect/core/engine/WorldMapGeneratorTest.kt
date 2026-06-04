package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.SectRelation
import com.xianxia.sect.core.model.WorldSect
import org.junit.Assert.*
import org.junit.Test

class WorldMapGeneratorTest {

    // ---- generateConnections ----

    @Test
    fun generateConnections_emptyList_returnsEmptyList() {
        val result = WorldMapGenerator.generateConnections(emptyList())
        assertEquals(0, result.size)
    }

    @Test
    fun generateConnections_singleSect_returnsSameSect() {
        val sect = WorldSect(id = "s1", name = "Test")
        val result = WorldMapGenerator.generateConnections(listOf(sect))
        assertEquals(1, result.size)
        assertEquals("s1", result[0].id)
    }

    @Test
    fun generateConnections_twoSects_areConnected() {
        val sect1 = WorldSect(id = "s1", x = 0f, y = 0f)
        val sect2 = WorldSect(id = "s2", x = 100f, y = 100f)
        val result = WorldMapGenerator.generateConnections(listOf(sect1, sect2))
        assertEquals(2, result.size)
        // Both sects should be connected to each other
        assertTrue("s1 should connect to s2", result[0].connectedSectIds.contains("s2") || result[1].connectedSectIds.contains("s2"))
        assertTrue("s2 should connect to s1", result[0].connectedSectIds.contains("s1") || result[1].connectedSectIds.contains("s1"))
    }

    @Test
    fun generateConnections_threeSects_allConnected() {
        val sect1 = WorldSect(id = "s1", x = 0f, y = 0f)
        val sect2 = WorldSect(id = "s2", x = 100f, y = 0f)
        val sect3 = WorldSect(id = "s3", x = 50f, y = 86f)
        val result = WorldMapGenerator.generateConnections(listOf(sect1, sect2, sect3))
        assertEquals(3, result.size)
        // Every sect should have at least one connection
        for (sect in result) {
            assertTrue("Sect ${sect.id} should have connections", sect.connectedSectIds.isNotEmpty())
        }
    }

    @Test
    fun generateConnections_preservesSectIds() {
        val sect1 = WorldSect(id = "alpha", x = 0f, y = 0f)
        val sect2 = WorldSect(id = "beta", x = 100f, y = 0f)
        val result = WorldMapGenerator.generateConnections(listOf(sect1, sect2))
        val ids = result.map { it.id }.toSet()
        assertTrue("alpha" in ids)
        assertTrue("beta" in ids)
    }

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

    // ---- generatePathWaypoints ----

    @Test
    fun generatePathWaypoints_shortDistance_returnsEmptyList() {
        val waypoints = WorldMapGenerator.generatePathWaypoints(
            fromX = 0f, fromY = 0f,
            toX = 100f, toY = 0f,
            fromId = "a", toId = "b"
        )
        assertEquals(0, waypoints.size)
    }

    @Test
    fun generatePathWaypoints_longDistance_returnsWaypoints() {
        val waypoints = WorldMapGenerator.generatePathWaypoints(
            fromX = 0f, fromY = 0f,
            toX = 500f, toY = 500f,
            fromId = "a", toId = "b"
        )
        assertTrue("Long distance should produce waypoints", waypoints.size > 0)
    }

    @Test
    fun generatePathWaypoints_deterministicForSameInput() {
        val wp1 = WorldMapGenerator.generatePathWaypoints(
            fromX = 0f, fromY = 0f,
            toX = 500f, toY = 500f,
            fromId = "a", toId = "b"
        )
        val wp2 = WorldMapGenerator.generatePathWaypoints(
            fromX = 0f, fromY = 0f,
            toX = 500f, toY = 500f,
            fromId = "a", toId = "b"
        )
        assertEquals(wp1.size, wp2.size)
        for (i in wp1.indices) {
            assertEquals(wp1[i].first, wp2[i].first, 0.01f)
            assertEquals(wp1[i].second, wp2[i].second, 0.01f)
        }
    }

    @Test
    fun generatePathWaypoints_differentIdsProduceDifferentResults() {
        val wp1 = WorldMapGenerator.generatePathWaypoints(
            fromX = 0f, fromY = 0f,
            toX = 500f, toY = 500f,
            fromId = "a", toId = "b"
        )
        val wp2 = WorldMapGenerator.generatePathWaypoints(
            fromX = 0f, fromY = 0f,
            toX = 500f, toY = 500f,
            fromId = "c", toId = "d"
        )
        // Different IDs produce different seeds, so waypoints should differ
        val allSame = wp1.zip(wp2).all { (a, b) ->
            a.first == b.first && a.second == b.second
        }
        assertFalse("Different IDs should produce different waypoints", allSame)
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
