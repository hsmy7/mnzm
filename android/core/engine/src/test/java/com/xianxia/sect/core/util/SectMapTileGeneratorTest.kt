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
        val result1 = SectMapTileGenerator.generateTileData(28, 28, 0.30f, 12345)
        val result2 = SectMapTileGenerator.generateTileData(28, 28, 0.30f, 12345)
        for (row in result1.indices) {
            assertArrayEquals(result1[row], result2[row])
        }
    }

    @Test
    fun `generateTileData - different seeds produce different output`() {
        val result1 = SectMapTileGenerator.generateTileData(28, 28, 0.30f, 100)
        val result2 = SectMapTileGenerator.generateTileData(28, 28, 0.30f, 9999)
        var same = true
        for (row in result1.indices) {
            for (col in result1[row].indices) {
                if (result1[row][col] != result2[row][col]) same = false
            }
        }
        assertFalse("Expected different output for different seeds", same)
    }

    @Test
    fun `generateTileData - all values are valid tile types`() {
        val result = SectMapTileGenerator.generateTileData(28, 28, 1.0f)
        val validValues = setOf(
            SectMapTileGenerator.TILE_GROUND,
            SectMapTileGenerator.TILE_GROUND_V2,
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
    fun `generateTileData - has both ground variants`() {
        val result = SectMapTileGenerator.generateTileData(28, 28, 0.0f)
        var hasV1 = false
        var hasV2 = false
        for (row in result) {
            for (value in row) {
                if (value == SectMapTileGenerator.TILE_GROUND) hasV1 = true
                if (value == SectMapTileGenerator.TILE_GROUND_V2) hasV2 = true
            }
        }
        assertTrue("Expected TILE_GROUND", hasV1)
        assertTrue("Expected TILE_GROUND_V2", hasV2)
    }

    @Test
    fun `generateTileData - zero density produces only ground variants`() {
        val result = SectMapTileGenerator.generateTileData(28, 28, 0.0f)
        for (row in result) {
            for (value in row) {
                assertTrue("Expected ground variant, got $value",
                    value == SectMapTileGenerator.TILE_GROUND ||
                    value == SectMapTileGenerator.TILE_GROUND_V2)
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

    @Test
    fun `smoothNoise - values are in 0 to 1 range`() {
        for (x in 0..50) {
            for (y in 0..50) {
                val v = SectMapTileGenerator.smoothNoise(x, y, 6, 999)
                assertTrue("smoothNoise out of range: $v at ($x,$y)", v in 0.0f..1.0f)
            }
        }
    }

    @Test
    fun `smoothNoise - adjacent tiles have similar values`() {
        // 平滑噪声中相邻格的值差应小（双线性插值保证连续性）
        val v1 = SectMapTileGenerator.smoothNoise(10, 10, 6, 999)
        val v2 = SectMapTileGenerator.smoothNoise(11, 10, 6, 999)
        val diff = kotlin.math.abs(v1 - v2)
        assertTrue("Adjacent tiles too different: $v1 vs $v2 (diff=$diff)", diff < 0.2f)
    }

    @Test
    fun `smoothNoise - deterministic output`() {
        val v1 = SectMapTileGenerator.smoothNoise(7, 13, 6, 999)
        val v2 = SectMapTileGenerator.smoothNoise(7, 13, 6, 999)
        assertEquals(v1, v2, 0.0f)
    }

    // ============================================================
    // Autotile bitmask
    // ============================================================

    @Test
    fun `computeAutotileBitmask - uniform field produces 0xFF mask`() {
        val tileData = Array(5) { IntArray(5) { SectMapTileGenerator.TILE_GROUND } }
        val mask = SectMapTileGenerator.computeAutotileBitmask(tileData)
        // Center tiles (y=1..3, x=1..3) have all 8 neighbors matching → 0xFF
        for (y in 1..3) {
            for (x in 1..3) {
                assertEquals("mask at ($x,$y)", 0xFF, mask[y][x].toInt() and 0xFF)
            }
        }
        // Edge tiles (y=0 or x=0 or y=4 or x=4) are skipped → 0
        for (x in 0..4) {
            assertEquals(0, mask[0][x].toInt())
            assertEquals(0, mask[4][x].toInt())
        }
        for (y in 0..4) {
            assertEquals(0, mask[y][0].toInt())
            assertEquals(0, mask[y][4].toInt())
        }
    }

    @Test
    fun `computeAutotileBitmask - center tile with 4 cardinal neighbors gets 0x0F`() {
        val tileData = Array(5) { IntArray(5) { SectMapTileGenerator.TILE_GROUND } }
        // Set center and all 4 cardinal neighbors to grass_small
        for (dx in 0..4) {
            for (dy in 0..4) {
                val tx = 2 + dx
                val ty = 2 + dy
                if (tx in 0..4 && ty in 0..4) {
                    // Only fill neighbors, not center (center stays ground)
                }
            }
        }
        // Clean approach: create 3x3 pattern with grass in center + all 4 cardinal neighbors
        val pattern = Array(5) { row ->
            IntArray(5) { col ->
                if (row == 2 && col == 2) SectMapTileGenerator.TILE_GRASS_SMALL
                else if (row == 2 || col == 2) SectMapTileGenerator.TILE_GRASS_SMALL
                else SectMapTileGenerator.TILE_GROUND
            }
        }
        val mask = SectMapTileGenerator.computeAutotileBitmask(pattern)
        // Center tile (2,2) has N,S,W,E = all grass_small → bits 0,2,4,6 set = 0x55
        assertEquals(0x55, mask[2][2].toInt() and 0xFF)
    }

    @Test
    fun `computeAutotileBitmask - isolated grass has no neighbors`() {
        val pattern = Array(5) { IntArray(5) { SectMapTileGenerator.TILE_GROUND } }
        pattern[2][2] = SectMapTileGenerator.TILE_GRASS_SMALL
        val mask = SectMapTileGenerator.computeAutotileBitmask(pattern)
        // Only the center tile is different from its neighbors
        // The center itself has ground neighbors, so mask = 0
        assertEquals(0, mask[2][2].toInt())
    }

    @Test
    fun `computeAutotileBitmask - tree tiles are skipped`() {
        val pattern = Array(5) { IntArray(5) { SectMapTileGenerator.TILE_TREE1 } }
        val mask = SectMapTileGenerator.computeAutotileBitmask(pattern)
        // All tiles are tree, which is skipped → mask stays 0
        // Edge tiles might not be computed due to boundary check
        assertEquals(0, mask[2][2].toInt())
    }
}
