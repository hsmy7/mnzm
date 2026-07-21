package com.xianxia.sect.ui.game

import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.util.GridSystem
import com.xianxia.sect.ui.game.main.computeGoldFingerCellValidities
import org.junit.Assert.*
import org.junit.Test

class GoldFingerBuildTest {

    @Test
    fun `computeGoldFingerCellValidities - empty grid returns valid for all cells`() {
        val result = computeGoldFingerCellValidities(
            startGridX = 0, startGridY = 0,
            endGridX = 5, endGridY = 5,
            buildingW = 2, buildingH = 2,
            existingBuildings = emptyList(),
            worldWidthCells = 28, worldHeightCells = 28
        )
        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it.value })
        // 6x6 area with 2x2 steps = 3x3 = 9 cells
        assertEquals(9, result.size)
    }

    @Test
    fun `computeGoldFingerCellValidities - occupied cell returns invalid`() {
        val existing = listOf(
            GridBuildingData(
                buildingId = "mine", displayName = "灵矿场",
                gridX = 2, gridY = 2, width = 2, height = 2
            )
        )
        val result = computeGoldFingerCellValidities(
            startGridX = 0, startGridY = 0,
            endGridX = 5, endGridY = 5,
            buildingW = 2, buildingH = 2,
            existingBuildings = existing,
            worldWidthCells = 28, worldHeightCells = 28
        )
        val overlapKey = GridSystem.packCell(2, 2)
        assertFalse(result[overlapKey] ?: true)
        // Other valid cells should still be valid
        val validKey = GridSystem.packCell(0, 0)
        assertTrue(result[validKey] ?: false)
    }

    @Test
    fun `computeGoldFingerCellValidities - partial cells at edge are skipped`() {
        val result = computeGoldFingerCellValidities(
            startGridX = 0, startGridY = 0,
            endGridX = 5, endGridY = 5,
            buildingW = 3, buildingH = 2,
            existingBuildings = emptyList(),
            worldWidthCells = 7, worldHeightCells = 7
        )
        // 6x6 area with 3x2 steps = 2x3 = 6 cells
        assertEquals(6, result.size)
        // Grid is 7x7, building is 3x2 -> at step (6,4) the building would exceed bounds
        val edgeKey = GridSystem.packCell(6, 4)
        assertNull(result[edgeKey])
    }

    @Test
    fun `computeGoldFingerCellValidities - revertible selection uses minOf and maxOf`() {
        // Dragging in reverse direction: end < start
        val result = computeGoldFingerCellValidities(
            startGridX = 6, startGridY = 6,
            endGridX = 2, endGridY = 2,
            buildingW = 2, buildingH = 2,
            existingBuildings = emptyList(),
            worldWidthCells = 28, worldHeightCells = 28
        )
        assertEquals(4, result.size) // 2..6 width=4, 2x2 steps = 2x2 = 4 cells
        val key22 = GridSystem.packCell(2, 2)
        val key44 = GridSystem.packCell(4, 4)
        assertTrue(result[key22] ?: false)
        assertTrue(result[key44] ?: false)
    }

    @Test
    fun `computeGoldFingerCellValidities - single cell cant fit 2x2 building`() {
        val result = computeGoldFingerCellValidities(
            startGridX = 3, startGridY = 3,
            endGridX = 3, endGridY = 3,
            buildingW = 2, buildingH = 2,
            existingBuildings = emptyList(),
            worldWidthCells = 28, worldHeightCells = 28
        )
        assertEquals(0, result.size)
    }

    @Test
    fun `computeGoldFingerCellValidities - out of bounds is clamped`() {
        val result = computeGoldFingerCellValidities(
            startGridX = 0, startGridY = 0,
            endGridX = 30, endGridY = 30,
            buildingW = 2, buildingH = 2,
            existingBuildings = emptyList(),
            worldWidthCells = 10, worldHeightCells = 10
        )
        // should not crash, should clamp to 10x10 = 5x5 = 25 cells
        assertEquals(25, result.size)
    }

    @Test
    fun `computeGoldFingerCellValidities - buildableBorder clamps selection inward`() {
        // 10x10 网格，buildableBorder=3，有效区为 (3..6)
        val result = computeGoldFingerCellValidities(
            startGridX = 0, startGridY = 0,
            endGridX = 9, endGridY = 9,
            buildingW = 2, buildingH = 2,
            existingBuildings = emptyList(),
            worldWidthCells = 10, worldHeightCells = 10,
            buildableBorder = 3
        )
        // 被 clamp 到 (3..6)，步长 2 → 3,5 两行/列 = 2x2 = 4 个
        assertEquals(4, result.size)
        // 3,3 和 5,5 在范围内，应有效
        assertTrue(result[GridSystem.packCell(3, 3)] ?: false)
        assertTrue(result[GridSystem.packCell(5, 5)] ?: false)
        // 0,0 不在结果中（已被 clamp）
        assertNull(result[GridSystem.packCell(0, 0)])
        // 7,7 不在结果中（6+2=8 > 10-3=7，越界步进被跳过）
        assertNull(result[GridSystem.packCell(7, 7)])
    }

    @Test
    fun `computeGoldFingerCellValidities - buildableBorder 0 same as original`() {
        val result = computeGoldFingerCellValidities(
            startGridX = 0, startGridY = 0,
            endGridX = 5, endGridY = 5,
            buildingW = 2, buildingH = 2,
            existingBuildings = emptyList(),
            worldWidthCells = 28, worldHeightCells = 28,
            buildableBorder = 0
        )
        assertEquals(9, result.size)
        assertTrue(result[GridSystem.packCell(0, 0)] ?: false)
    }

    @Test
    fun `computeGoldFingerCellValidities - multiple existing buildings`() {
        val existing = listOf(
            GridBuildingData(buildingId = "a", displayName = "A", gridX = 0, gridY = 0, width = 2, height = 2),
            GridBuildingData(buildingId = "b", displayName = "B", gridX = 4, gridY = 4, width = 2, height = 2)
        )
        val result = computeGoldFingerCellValidities(
            startGridX = 0, startGridY = 0,
            endGridX = 7, endGridY = 7,
            buildingW = 2, buildingH = 2,
            existingBuildings = existing,
            worldWidthCells = 10, worldHeightCells = 10
        )
        // 8x8 area, 2x2 steps = 4x4 = 16 cells, 2 overlap
        assertEquals(16, result.size)
        assertFalse(result[GridSystem.packCell(0, 0)] ?: true)
        assertFalse(result[GridSystem.packCell(4, 4)] ?: true)
        assertTrue(result[GridSystem.packCell(2, 0)] ?: false)
        assertTrue(result[GridSystem.packCell(6, 6)] ?: false)
    }
}
