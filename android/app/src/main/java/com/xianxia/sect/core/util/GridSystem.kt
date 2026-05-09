package com.xianxia.sect.core.util

import com.xianxia.sect.core.model.GridBuildingData

class GridSystem(
    val tileSize: Int,
    val gridWidthCells: Int,
    val gridHeightCells: Int
) {
    private var _buildings: List<GridBuildingData> = emptyList()
    val buildings: List<GridBuildingData> get() = _buildings

    private var _occupiedCells: Set<Long> = emptySet()
    val occupiedCells: Set<Long> get() = _occupiedCells

    fun rebuildFrom(buildings: List<GridBuildingData>) {
        _buildings = buildings
        _occupiedCells = computeOccupiedCells(buildings)
    }

    fun validatePlacement(
        gridX: Int,
        gridY: Int,
        width: Int,
        height: Int
    ): GridSnapHelper.PlacementValidity {
        if (gridX < 0 || gridY < 0 ||
            gridX + width > gridWidthCells ||
            gridY + height > gridHeightCells
        ) {
            return GridSnapHelper.PlacementValidity.OutOfBounds
        }
        for (cx in gridX until gridX + width) {
            for (cy in gridY until gridY + height) {
                if (packCell(cx, cy) in _occupiedCells) {
                    val overlapped = _buildings.filter { b ->
                        gridX < b.gridX + b.width &&
                        gridX + width > b.gridX &&
                        gridY < b.gridY + b.height &&
                        gridY + height > b.gridY
                    }.map { it.displayName }
                    return GridSnapHelper.PlacementValidity.Overlap(overlapped)
                }
            }
        }
        return GridSnapHelper.PlacementValidity.Valid
    }

    fun placeBuilding(building: GridBuildingData): Boolean {
        if (validatePlacement(building.gridX, building.gridY, building.width, building.height)
            != GridSnapHelper.PlacementValidity.Valid
        ) return false
        _buildings = _buildings + building
        for (cx in building.gridX until building.gridX + building.width) {
            for (cy in building.gridY until building.gridY + building.height) {
                _occupiedCells = _occupiedCells + packCell(cx, cy)
            }
        }
        return true
    }

    fun snapWorldToGrid(worldX: Float, worldY: Float): Pair<Int, Int> =
        GridSnapHelper.worldToGrid(worldX, tileSize) to
        GridSnapHelper.worldToGrid(worldY, tileSize)

    fun gridToWorld(gridX: Int, gridY: Int): Pair<Int, Int> =
        GridSnapHelper.gridToWorld(gridX, tileSize) to
        GridSnapHelper.gridToWorld(gridY, tileSize)

    companion object {
        const val DEFAULT_WORLD_WIDTH_CELLS = 28
        const val DEFAULT_WORLD_HEIGHT_CELLS = 28

        fun packCell(x: Int, y: Int): Long =
            (x.toLong() shl 32) or (y.toLong() and 0xFFFF_FFFF)
    }

    private fun computeOccupiedCells(buildings: List<GridBuildingData>): Set<Long> {
        val cells = mutableSetOf<Long>()
        buildings.forEach { b ->
            for (cx in b.gridX until b.gridX + b.width) {
                for (cy in b.gridY until b.gridY + b.height) {
                    cells.add(packCell(cx, cy))
                }
            }
        }
        return cells
    }
}
