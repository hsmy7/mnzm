package com.xianxia.sect.core.util

import com.xianxia.sect.core.model.GridBuildingData
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GridSystemTest {

    private lateinit var gridSystem: GridSystem
    private val tileSize = 64
    private val gridWidth = 10
    private val gridHeight = 10

    @Before
    fun setUp() {
        gridSystem = GridSystem(tileSize, gridWidth, gridHeight)
    }

    // --- GridSystem tests ---

    @Test
    fun newGridSystem_hasEmptyBuildingsAndOccupiedCells() {
        assertTrue(gridSystem.buildings.isEmpty())
        assertTrue(gridSystem.occupiedCells.isEmpty())
    }

    @Test
    fun validatePlacement_emptyGrid_returnsValid() {
        val result = gridSystem.validatePlacement(0, 0, 2, 2)
        assertEquals(GridSnapHelper.PlacementValidity.Valid, result)
    }

    @Test
    fun validatePlacement_negativeCoords_returnsOutOfBounds() {
        val result = gridSystem.validatePlacement(-1, 0, 2, 2)
        assertEquals(GridSnapHelper.PlacementValidity.OutOfBounds, result)
    }

    @Test
    fun validatePlacement_exceedingGrid_returnsOutOfBounds() {
        val result = gridSystem.validatePlacement(9, 9, 2, 2)
        assertEquals(GridSnapHelper.PlacementValidity.OutOfBounds, result)
    }

    @Test
    fun validatePlacement_overlappingExisting_returnsOverlap() {
        val building = GridBuildingData(
            buildingId = "b1", displayName = "Tower",
            gridX = 2, gridY = 2, width = 2, height = 2
        )
        gridSystem.placeBuilding(building)

        val result = gridSystem.validatePlacement(3, 3, 2, 2)
        assertTrue(result is GridSnapHelper.PlacementValidity.Overlap)
        val overlap = result as GridSnapHelper.PlacementValidity.Overlap
        assertTrue(overlap.names.contains("Tower"))
    }

    @Test
    fun placeBuilding_validPlacement_succeeds() {
        val building = GridBuildingData(
            buildingId = "b1", displayName = "Hall",
            gridX = 0, gridY = 0, width = 2, height = 2
        )
        val result = gridSystem.placeBuilding(building)
        assertTrue(result)
        assertEquals(1, gridSystem.buildings.size)
        assertFalse(gridSystem.occupiedCells.isEmpty())
    }

    @Test
    fun placeBuilding_invalidPlacement_fails() {
        val building = GridBuildingData(
            buildingId = "b1", displayName = "Hall",
            gridX = 9, gridY = 9, width = 2, height = 2
        )
        val result = gridSystem.placeBuilding(building)
        assertFalse(result)
        assertTrue(gridSystem.buildings.isEmpty())
        assertTrue(gridSystem.occupiedCells.isEmpty())
    }

    @Test
    fun rebuildFrom_restoresBuildingsAndOccupiedCells() {
        val buildings = listOf(
            GridBuildingData(buildingId = "b1", displayName = "A", gridX = 0, gridY = 0, width = 1, height = 1),
            GridBuildingData(buildingId = "b2", displayName = "B", gridX = 5, gridY = 5, width = 2, height = 2)
        )
        gridSystem.rebuildFrom(buildings)
        assertEquals(2, gridSystem.buildings.size)
        assertEquals(5, gridSystem.occupiedCells.size) // 1 + 4
    }

    @Test
    fun snapWorldToGrid_convertsCorrectly() {
        val (gx, gy) = gridSystem.snapWorldToGrid(128f, 192f)
        assertEquals(2, gx)
        assertEquals(3, gy)
    }

    @Test
    fun gridToWorld_convertsCorrectly() {
        val (wx, wy) = gridSystem.gridToWorld(2, 3)
        assertEquals(128, wx)
        assertEquals(192, wy)
    }

    @Test
    fun packCell_producesConsistentResults() {
        val packed = GridSystem.packCell(5, 10)
        val unpackedX = (packed shr 32).toInt()
        val unpackedY = packed.toInt()
        assertEquals(5, unpackedX)
        assertEquals(10, unpackedY)
    }

    // --- BuildableBorder tests ---

    @Test
    fun `validatePlacement with buildableBorder blocks border zone`() {
        val bordered = GridSystem(64, 10, 10, buildableBorder = 3)
        // 有效区为 (3..6) 因为 10-3=7，条件是 gridX+width ≤ 7

        // 边界内应有效
        assertEquals(GridSnapHelper.PlacementValidity.Valid,
            bordered.validatePlacement(3, 3, 2, 2))
        assertEquals(GridSnapHelper.PlacementValidity.Valid,
            bordered.validatePlacement(5, 5, 2, 2))

        // 边界区应被阻止
        assertEquals(GridSnapHelper.PlacementValidity.OutOfBounds,
            bordered.validatePlacement(0, 0, 2, 2))
        assertEquals(GridSnapHelper.PlacementValidity.OutOfBounds,
            bordered.validatePlacement(2, 2, 2, 2))
        assertEquals(GridSnapHelper.PlacementValidity.OutOfBounds,
            bordered.validatePlacement(6, 6, 2, 2))
        assertEquals(GridSnapHelper.PlacementValidity.OutOfBounds,
            bordered.validatePlacement(8, 8, 2, 2))
    }

    @Test
    fun `validatePlacement with buildableBorder 0 behaves like original`() {
        // 有 border=0 的 GridSystem 行为应与无 border 时一致
        val bordered = GridSystem(64, 10, 10, buildableBorder = 0)
        assertEquals(GridSnapHelper.PlacementValidity.Valid,
            bordered.validatePlacement(0, 0, 2, 2))
        assertEquals(GridSnapHelper.PlacementValidity.OutOfBounds,
            bordered.validatePlacement(9, 9, 2, 2))
    }

    @Test
    fun `placeBuilding with buildableBorder respects border`() {
        val bordered = GridSystem(64, 10, 10, buildableBorder = 3)
        val borderBuilding = GridBuildingData(
            buildingId = "b1", displayName = "Edge",
            gridX = 0, gridY = 0, width = 2, height = 2
        )
        assertFalse(bordered.placeBuilding(borderBuilding))
        assertTrue(bordered.buildings.isEmpty())
    }

    // --- GridSnapHelper validatePlacement with borderPadding ---

    @Test
    fun `gridSnapHelper validatePlacement with borderPadding blocks border`() {
        val result = GridSnapHelper.validatePlacement(0, 0, 2, 2, 10, 10,
            borderPadding = 3)
        assertEquals(GridSnapHelper.PlacementValidity.OutOfBounds, result)

        val inner = GridSnapHelper.validatePlacement(3, 3, 2, 2, 10, 10,
            borderPadding = 3)
        assertEquals(GridSnapHelper.PlacementValidity.Valid, inner)
    }

    // --- GridSnapHelper tests ---

    @Test
    fun gridSnapHelper_worldToGrid_roundsCorrectly() {
        assertEquals(2, GridSnapHelper.worldToGrid(128f, 64))
        assertEquals(1, GridSnapHelper.worldToGrid(80f, 64))
        assertEquals(0, GridSnapHelper.worldToGrid(10f, 64))
    }

    @Test
    fun gridSnapHelper_gridToWorld_multipliesCorrectly() {
        assertEquals(128, GridSnapHelper.gridToWorld(2, 64))
        assertEquals(0, GridSnapHelper.gridToWorld(0, 64))
    }

    @Test
    fun gridSnapHelper_screenToWorld_convertsCorrectly() {
        val result = GridSnapHelper.screenToWorld(200f, 100f, 2f)
        assertEquals(200f, result, 0.001f)
    }

    @Test
    fun gridSnapHelper_worldToScreen_convertsCorrectly() {
        val result = GridSnapHelper.worldToScreen(128, 100f, 2f)
        assertEquals(56f, result, 0.001f)
    }

    @Test
    fun gridSnapHelper_gridToScreen_convertsCorrectly() {
        val result = GridSnapHelper.gridToScreen(2, 64, 100f, 2f)
        assertEquals(56f, result, 0.001f)
    }

    @Test
    fun gridSnapHelper_validatePlacement_emptyGrid_returnsValid() {
        val result = GridSnapHelper.validatePlacement(0, 0, 2, 2, 10, 10)
        assertEquals(GridSnapHelper.PlacementValidity.Valid, result)
    }

    @Test
    fun gridSnapHelper_validatePlacement_outOfBounds() {
        val result = GridSnapHelper.validatePlacement(-1, 0, 2, 2, 10, 10)
        assertEquals(GridSnapHelper.PlacementValidity.OutOfBounds, result)
    }

    @Test
    fun gridSnapHelper_validatePlacement_overlapWithNames() {
        val rects = listOf(
            GridSnapHelper.GridRect(2, 2, 2, 2, "Tower")
        )
        val result = GridSnapHelper.validatePlacement(3, 3, 2, 2, 10, 10, occupiedRects = rects)
        assertTrue(result is GridSnapHelper.PlacementValidity.Overlap)
        val overlap = result as GridSnapHelper.PlacementValidity.Overlap
        assertTrue(overlap.names.contains("Tower"))
    }

    @Test
    fun placementValidity_sealedClassTypes() {
        val valid: GridSnapHelper.PlacementValidity = GridSnapHelper.PlacementValidity.Valid
        val outOfBounds: GridSnapHelper.PlacementValidity = GridSnapHelper.PlacementValidity.OutOfBounds
        val overlap: GridSnapHelper.PlacementValidity = GridSnapHelper.PlacementValidity.Overlap(listOf("X"))

        assertTrue(valid is GridSnapHelper.PlacementValidity.Valid)
        assertTrue(outOfBounds is GridSnapHelper.PlacementValidity.OutOfBounds)
        assertTrue(overlap is GridSnapHelper.PlacementValidity.Overlap)
    }
}
