package com.xianxia.sect.core.util

import org.junit.Assert.*
import org.junit.Test

class SectMapTileGeneratorTest {

    @Test
    fun `generateTileData - default density produces correct dimensions`() {
        val result = SectMapTileGenerator.generateTileData(28, 28)
        assertEquals(28, result.size)
        assertEquals(28, result[0].size)
    }

    @Test
    fun `generateTileData - deterministic output for same input`() {
        val result1 = SectMapTileGenerator.generateTileData(28, 28, 0.30f)
        val result2 = SectMapTileGenerator.generateTileData(28, 28, 0.30f)
        for (row in result1.indices) {
            assertArrayEquals(result1[row], result2[row])
        }
    }

    @Test
    fun `generateTileData - all values are valid tile types`() {
        val result = SectMapTileGenerator.generateTileData(28, 28, 1.0f)
        val validValues = setOf(
            SectMapTileGenerator.TILE_GROUND,
            SectMapTileGenerator.TILE_GRASS_SMALL,
            SectMapTileGenerator.TILE_GRASS_MEDIUM,
            SectMapTileGenerator.TILE_GRASS_LARGE,
            SectMapTileGenerator.TILE_TREE1,
            SectMapTileGenerator.TILE_TREE2
        )
        for (row in result) {
            for (value in row) {
                assertTrue("Unexpected tile value: $value", value in validValues)
            }
        }
    }

    @Test
    fun `generateTileData - zero density produces only ground`() {
        val result = SectMapTileGenerator.generateTileData(28, 28, 0.0f)
        for (row in result) {
            for (value in row) {
                assertEquals(SectMapTileGenerator.TILE_GROUND, value)
            }
        }
    }

    @Test
    fun `generateTileData - max density has many decorations`() {
        val result = SectMapTileGenerator.generateTileData(28, 28, 1.0f)
        var decorated = 0
        var total = 0
        for (row in result) {
            for (value in row) {
                total++
                if (value != SectMapTileGenerator.TILE_GROUND) decorated++
            }
        }
        assertTrue("Expected many decorations at max density", decorated > total / 2)
    }

    @Test
    fun `generateTileData - 1x1 map does not crash`() {
        val result = SectMapTileGenerator.generateTileData(1, 1, 0.30f)
        assertEquals(1, result.size)
        assertEquals(1, result[0].size)
    }

    @Test
    fun `generateTileData - has both tree types`() {
        val result = SectMapTileGenerator.generateTileData(28, 28, 1.0f)
        var hasTree1 = false
        var hasTree2 = false
        for (row in result) {
            for (value in row) {
                if (value == SectMapTileGenerator.TILE_TREE1) hasTree1 = true
                if (value == SectMapTileGenerator.TILE_TREE2) hasTree2 = true
            }
        }
        assertTrue("Expected at least one TREE1", hasTree1)
        assertTrue("Expected at least one TREE2", hasTree2)
    }

    @Test
    fun `cellHash - same inputs produce same value`() {
        val v1 = SectMapTileGenerator.cellHash(5, 10, 42)
        val v2 = SectMapTileGenerator.cellHash(5, 10, 42)
        assertEquals(v1, v2, 0.0f)
    }

    @Test
    fun `cellHash - different inputs produce different values`() {
        val v1 = SectMapTileGenerator.cellHash(5, 10, 42)
        val v2 = SectMapTileGenerator.cellHash(6, 10, 42)
        val v3 = SectMapTileGenerator.cellHash(5, 11, 42)
        assertNotEquals(v1, v2, 0.0f)
        assertNotEquals(v1, v3, 0.0f)
    }

    @Test
    fun `cellHash - value is in 0 to 1 range`() {
        for (x in 0..50) {
            for (y in 0..50) {
                val v = SectMapTileGenerator.cellHash(x, y, 42)
                assertTrue("cellHash out of range: $v at ($x,$y)", v in 0.0f..1.0f)
            }
        }
    }
}
